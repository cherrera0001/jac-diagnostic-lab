package app.jac.diagnosticlab.tracker

import app.jac.diagnosticlab.evidence.Json

enum class TrackerKind(val label: String) {
    APPLE_FIND_MY("Accesorio de la red Apple Find My"),
    GOOGLE_FIND_HUB("Accesorio de la red Google Find Hub"),
    XIAOMI_DEVICE("Dispositivo Xiaomi (fabricante)"),
    SAMSUNG_SMARTTAG("Samsung SmartTag"),
    TILE("Tile"),
    CHIPOLO("Chipolo"),
    UNKNOWN("BLE sin clasificar"),
}

enum class Confidence(val label: String) { HIGH("alta"), MEDIUM("media"), LOW("baja") }

/** Anuncio BLE reducido a lo que el clasificador necesita (sin dependencias de Android: probable por JVM). */
data class Advert(
    val manufacturer: Map<Int, ByteArray> = emptyMap(),
    /** UUID de 16 bits en minúscula («fd5a») o completo. */
    val serviceUuids: Set<String> = emptySet(),
    val serviceData: Map<String, ByteArray> = emptyMap(),
    val name: String? = null,
)

data class Classification(val kind: TrackerKind, val confidence: Confidence, val basis: String, val note: String)

/**
 * Firmas públicas conocidas. NO están validadas con dispositivos físicos en este proyecto.
 * Importante: un Xiaomi Tag configurado en Apple Find My o en Google Find Hub se anuncia como cualquier otro
 * accesorio de esa red; por BLE no se puede distinguir el fabricante. Un anuncio con el ID de empresa de Xiaomi
 * (0x038F) es un dispositivo Xiaomi cualquiera (banda, auriculares…), no necesariamente un Tag.
 */
object TrackerClassifier {
    const val COMPANY_APPLE = 0x004C
    const val COMPANY_XIAOMI = 0x038F
    private const val NOT_VALIDATED = "Firma pública sin validar con el dispositivo físico."

    fun short16(uuid: String): String {
        val u = uuid.lowercase()
        return if (u.length == 36 && u.startsWith("0000") && u.endsWith("-0000-1000-8000-00805f9b34fb")) u.substring(4, 8) else u
    }

    fun classify(a: Advert): Classification {
        val apple = a.manufacturer[COMPANY_APPLE]
        if (apple != null && apple.isNotEmpty() && (apple[0].toInt() and 0xFF) == 0x12) {
            return Classification(
                TrackerKind.APPLE_FIND_MY, Confidence.HIGH, "Datos de fabricante Apple, tipo 0x12 (Offline Finding).",
                "Puede ser un AirTag o cualquier accesorio con Find My, incluido un Xiaomi Tag configurado en Apple. Los identificadores rotan.",
            )
        }
        val ids = a.serviceUuids.map(::short16).toSet() + a.serviceData.keys.map(::short16)
        val eddy = a.serviceData.entries.firstOrNull { short16(it.key) == "feaa" }?.value
        if (eddy != null && eddy.isNotEmpty() && ((eddy[0].toInt() and 0xFF) == 0x40 || (eddy[0].toInt() and 0xFF) == 0x41)) {
            return Classification(
                TrackerKind.GOOGLE_FIND_HUB, Confidence.MEDIUM, "Datos de servicio 0xFEAA con trama 0x40/0x41. $NOT_VALIDATED",
                "Puede ser cualquier accesorio de la red Find Hub, incluido un Xiaomi Tag configurado en Google.",
            )
        }
        if ("fd5a" in ids) return Classification(TrackerKind.SAMSUNG_SMARTTAG, Confidence.MEDIUM, "Servicio 0xFD5A. $NOT_VALIDATED", "")
        if ("feed" in ids || "feec" in ids) return Classification(TrackerKind.TILE, Confidence.MEDIUM, "Servicio 0xFEED/0xFEEC. $NOT_VALIDATED", "")
        if ("fe33" in ids) return Classification(TrackerKind.CHIPOLO, Confidence.MEDIUM, "Servicio 0xFE33. $NOT_VALIDATED", "")
        if (COMPANY_XIAOMI in a.manufacturer || a.name?.contains("xiaomi", ignoreCase = true) == true) {
            return Classification(
                TrackerKind.XIAOMI_DEVICE, Confidence.LOW, "ID de empresa Bluetooth SIG 0x038F o nombre con «Xiaomi».",
                "Fabricante Xiaomi, no necesariamente un Tag (banda, auriculares, sensor…).",
            )
        }
        return Classification(TrackerKind.UNKNOWN, Confidence.LOW, "Sin firma conocida.", "")
    }
}

enum class ProximityBand(val label: String, val rank: Int) {
    VERY_CLOSE("Muy cerca", 3), CLOSE("Cerca", 2), MEDIUM("A unos metros", 1), FAR("Lejos", 0);
    companion object {
        /** Bandas, no metros: el RSSI varía con cuerpo, paredes y orientación. */
        fun of(rssi: Double) = when { rssi >= -55 -> VERY_CLOSE; rssi >= -70 -> CLOSE; rssi >= -85 -> MEDIUM; else -> FAR }
    }
}

data class GeoPoint(val lat: Double, val lon: Double, val accuracyM: Float? = null)

data class Sighting(
    val address: String,
    val advert: Advert,
    val classification: Classification,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val count: Int,
    val rssi: Int,
    val smoothedRssi: Double,
    val trend: Int, // +1 acercándose, -1 alejándose, 0 estable
    val bestRssi: Int,
    val bestPoint: GeoPoint?,
    val bestAtMs: Long,
    val lastPoint: GeoPoint?,
    val geoSampleCount: Int = 0,
)

/**
 * Agrega avistamientos por dirección de forma segura para hilos.
 * Las direcciones aleatorias rotan: un mismo tag puede aparecer como varias filas.
 * Además alimenta un [PositionEstimator] en segundo plano por dirección para que al abrir «Buscar»
 * el camino ya recorrido cuente de inmediato.
 */
class SightingBook(private val alpha: Double = 0.3) {
    private val map = linkedMapOf<String, Sighting>()
    private val bestPointRssi = hashMapOf<String, Int>()
    private val estimators = hashMapOf<String, PositionEstimator>()

    private fun mergeName(prev: String?, incoming: String?): String? {
        val a = prev?.trim()?.takeIf { it.isNotEmpty() }
        val b = incoming?.trim()?.takeIf { it.isNotEmpty() }
        if (b == null) return a
        if (a == null) return b
        // Evitar que un Shortened Local Name (0x08) pise un Complete Local Name (0x09).
        if (a.startsWith(b, ignoreCase = true) && a.length > b.length) return a
        return b
    }

    @Synchronized
    fun observe(address: String, advert: Advert, rssi: Int, atMs: Long, point: GeoPoint?): Sighting {
        val prev = map[address]
        val sm = if (prev == null) rssi.toDouble() else prev.smoothedRssi + alpha * (rssi - prev.smoothedRssi)
        val trend = when {
            prev == null -> 0
            sm - prev.smoothedRssi >= 1.0 -> 1
            sm - prev.smoothedRssi <= -1.0 -> -1
            else -> prev.trend
        }
        val betterOverall = prev == null || rssi > prev.bestRssi
        val prevGeoRssi = bestPointRssi[address]
        val betterPoint = point != null && (prevGeoRssi == null || rssi > prevGeoRssi)
        if (betterPoint) bestPointRssi[address] = rssi

        val est = estimators.getOrPut(address) { PositionEstimator() }
        if (point != null) est.add(point, rssi)

        // El nombre y los datos suelen llegar en anuncios distintos (respuesta de escaneo): se fusionan.
        val merged = if (prev == null) advert else Advert(
            manufacturer = prev.advert.manufacturer + advert.manufacturer,
            serviceUuids = prev.advert.serviceUuids + advert.serviceUuids,
            serviceData = prev.advert.serviceData + advert.serviceData,
            name = mergeName(prev.advert.name, advert.name),
        )
        val s = Sighting(
            address = address,
            advert = merged,
            classification = TrackerClassifier.classify(merged),
            firstSeenMs = prev?.firstSeenMs ?: atMs,
            lastSeenMs = atMs,
            count = (prev?.count ?: 0) + 1,
            rssi = rssi,
            smoothedRssi = sm,
            trend = trend,
            bestRssi = if (betterOverall) rssi else prev!!.bestRssi,
            bestPoint = if (betterPoint) point else prev?.bestPoint,
            bestAtMs = if (betterOverall) atMs else prev!!.bestAtMs,
            lastPoint = point ?: prev?.lastPoint,
            geoSampleCount = est.count,
        )
        map[address] = s
        return s
    }

    @Synchronized
    fun positionEstimator(address: String): PositionEstimator = estimators.getOrPut(address) { PositionEstimator() }

    @Synchronized
    fun resetPosition(address: String) {
        estimators[address]?.reset()
        map[address]?.let { map[address] = it.copy(geoSampleCount = 0) }
    }

    @Synchronized
    fun all(): List<Sighting> = map.values.sortedByDescending { it.smoothedRssi }

    @Synchronized
    fun clear() {
        map.clear()
        bestPointRssi.clear()
        estimators.clear()
    }
}

object TrackerExport {
    private fun resolvedPoint(s: Sighting, est: PositionEstimate?): Pair<GeoPoint, String>? = when {
        est != null && est.usable -> est.point to "estimated"
        s.bestPoint != null -> s.bestPoint to "strongest_rssi"
        else -> null
    }

    private fun props(s: Sighting, nickname: String?, est: PositionEstimate?, source: String): Map<String, Any?> = linkedMapOf(
        "address" to s.address,
        "name" to s.advert.name,
        "displayName" to DeviceNaming.resolvedTitle(s.advert, s.classification, nickname),
        "kind" to s.classification.kind.name,
        "kindLabel" to s.classification.kind.label,
        "confidence" to s.classification.confidence.name,
        "basis" to s.classification.basis,
        "pointSource" to source,
        "bestRssi" to s.bestRssi,
        "bestAtMs" to s.bestAtMs,
        "count" to s.count,
        "firstSeenMs" to s.firstSeenMs,
        "lastSeenMs" to s.lastSeenMs,
        "accuracyM" to s.bestPoint?.accuracyM?.toDouble(),
        "estimatedRadiusM" to est?.takeIf { it.usable }?.radiusM,
    )

    /** GeoJSON RFC 7946: coordenadas [lon, lat]. Usa la posición estimada si es usable; si no, donde la señal fue más fuerte. */
    fun geoJson(
        sightings: List<Sighting>,
        nicknames: Map<String, String> = emptyMap(),
        estimates: Map<String, PositionEstimate> = emptyMap(),
    ): String = Json.encode(
        linkedMapOf(
            "type" to "FeatureCollection",
            "features" to sightings.mapNotNull { s ->
                val est = estimates[s.address]
                val (pt, source) = resolvedPoint(s, est) ?: return@mapNotNull null
                linkedMapOf(
                    "type" to "Feature",
                    "geometry" to linkedMapOf("type" to "Point", "coordinates" to listOf(pt.lon, pt.lat)),
                    "properties" to props(s, nicknames[s.address], est, source),
                )
            },
        ),
    )

    private fun esc(x: String) = x.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun gpx(
        sightings: List<Sighting>,
        nicknames: Map<String, String> = emptyMap(),
        estimates: Map<String, PositionEstimate> = emptyMap(),
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"JAC Diagnostic Lab\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sightings.forEach { s ->
            val est = estimates[s.address]
            val (p, source) = resolvedPoint(s, est) ?: return@forEach
            val label = DeviceNaming.resolvedTitle(s.advert, s.classification, nicknames[s.address])
            val suffix = if (source == "estimated") " (±${est!!.radiusM.toInt()} m)" else ""
            append("  <wpt lat=\"${p.lat}\" lon=\"${p.lon}\"><name>${esc(label)} ${esc(s.address.takeLast(5))}</name>")
            append("<desc>RSSI ${s.bestRssi} dBm, ${s.count} avistamientos$suffix</desc></wpt>\n")
        }
        append("</gpx>\n")
    }

    /** Enlace `geo:` que abre cualquier app de mapas del teléfono (sin API ni clave). */
    fun geoUri(p: GeoPoint, label: String) = "geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(${java.net.URLEncoder.encode(label, "UTF-8")})"
}

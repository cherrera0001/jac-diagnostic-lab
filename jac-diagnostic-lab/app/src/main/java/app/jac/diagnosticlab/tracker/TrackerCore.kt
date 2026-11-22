package app.jac.diagnosticlab.tracker

import app.jac.diagnosticlab.evidence.Json
import java.util.Locale

enum class TrackerKind(val label: String) {
    APPLE_FIND_MY("Accesorio de la red Apple Find My"),
    GOOGLE_FIND_HUB("Accesorio de la red Google Find Hub"),
    XIAOMI_DEVICE("Dispositivo Xiaomi (fabricante)"),
    SAMSUNG_SMARTTAG("Samsung SmartTag"),
    TILE("Tile"),
    CHIPOLO("Chipolo"),
    UNKNOWN("BLE sin clasificar"),
}

enum class DeviceCategory(val label: String) {
    TRACKER("Rastreador"),
    AUDIO("Audio"),
    WEARABLE("Reloj / Pulsera"),
    PHONE_PC("Teléfono / PC"),
    TV_HOME("TV / Hogar"),
    PERIPHERAL("Periférico"),
    VEHICLE("Auto / OBD"),
    BEACON("Baliza"),
    OTHER("BLE"),
}

enum class Confidence(val label: String) { HIGH("alta"), MEDIUM("media"), LOW("baja") }

/** Resultado de consulta activa opcional (GATT GAP/DIS/Battery + IETF DULT). */
data class GattInfo(
    val deviceName: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val firmware: String? = null,
    val batteryPercent: Int? = null,
    val dultCategory: String? = null,
    val appearance: Int? = null,
    val statusNote: String = "",
    val inspectedAtMs: Long = 0L,
) {
    fun hasIdentity(): Boolean =
        !deviceName.isNullOrBlank() || !manufacturer.isNullOrBlank() || !model.isNullOrBlank() || !dultCategory.isNullOrBlank()
}

/** Anuncio BLE reducido a lo que el clasificador necesita (sin dependencias de Android: probable por JVM). */
data class Advert(
    val manufacturer: Map<Int, ByteArray> = emptyMap(),
    /** UUID de 16 bits en minúscula («fd5a») o completo. */
    val serviceUuids: Set<String> = emptySet(),
    val serviceData: Map<String, ByteArray> = emptyMap(),
    val name: String? = null,
    val appearance: Int? = null,
    val majorDeviceClass: Int? = null,
    val deviceClass: Int? = null,
    val txPower: Int? = null,
)

data class Classification(
    val kind: TrackerKind,
    val confidence: Confidence,
    val basis: String,
    val note: String,
    val category: DeviceCategory = DeviceCategory.OTHER,
)

/**
 * Firmas públicas conocidas + categorización de dispositivos BLE cercanos.
 * Importante: los tags de Apple Find My y Google Find Hub (incluido Xiaomi Tag) NO anuncian por Bluetooth
 * el nombre personalizado asignado en la app del dueño («Llaves», «Mochila»…): ese nombre vive cifrado en la nube.
 * Solo anuncian identificadores efímeros rotativos (EID), y en ciertos estados exponen fabricante/modelo vía IETF DULT.
 */
object TrackerClassifier {
    const val COMPANY_APPLE = 0x004C
    const val COMPANY_MICROSOFT = 0x0006
    const val COMPANY_HARMAN = 0x0057
    const val COMPANY_SAMSUNG = 0x0075
    const val COMPANY_GARMIN = 0x0087
    const val COMPANY_BOSE = 0x009E
    const val COMPANY_GOOGLE = 0x00E0
    const val COMPANY_SONY = 0x012D
    const val COMPANY_HUAMI = 0x0157
    const val COMPANY_LOGITECH = 0x01DA
    const val COMPANY_XIAOMI = 0x038F
    const val DULT_SERVICE_UUID = "15190001-12f4-c226-88ed-2ac5579f2a85"
    private const val NOT_VALIDATED = "Firma pública sin validar con el dispositivo físico."

    // Constantes BluetoothClass (valores estándar Android/BT SIG sin importar android.bluetooth).
    private const val MAJOR_COMPUTER = 0x0100
    private const val MAJOR_PHONE = 0x0200
    private const val MAJOR_AUDIO_VIDEO = 0x0400
    private const val MAJOR_PERIPHERAL = 0x0500
    private const val MAJOR_WEARABLE = 0x0700
    private const val CLASS_CAR_AUDIO = 0x0420

    fun short16(uuid: String): String {
        val u = uuid.lowercase(Locale.US)
        return if (u.length == 36 && u.startsWith("0000") && u.endsWith("-0000-1000-8000-00805f9b34fb")) u.substring(4, 8) else u
    }

    private fun categoryFromAppearance(code: Int?): DeviceCategory? {
        if (code == null) return null
        return when (code ushr 6) {
            0x01, 0x02 -> DeviceCategory.PHONE_PC
            0x03, 0x07, 0x0D -> DeviceCategory.WEARABLE
            0x05, 0x06 -> DeviceCategory.TV_HOME
            0x08, 0x09 -> DeviceCategory.TRACKER
            0x0F -> DeviceCategory.PERIPHERAL
            0x11 -> DeviceCategory.VEHICLE
            0x21, 0x25 -> DeviceCategory.AUDIO
            else -> null
        }
    }

    private fun categoryFromBluetoothClass(major: Int?, fullClass: Int?): DeviceCategory? {
        if (fullClass == CLASS_CAR_AUDIO) return DeviceCategory.VEHICLE
        return when (major) {
            MAJOR_AUDIO_VIDEO -> DeviceCategory.AUDIO
            MAJOR_WEARABLE -> DeviceCategory.WEARABLE
            MAJOR_PHONE, MAJOR_COMPUTER -> DeviceCategory.PHONE_PC
            MAJOR_PERIPHERAL -> DeviceCategory.PERIPHERAL
            else -> null
        }
    }

    private fun categoryFromName(text: String?): DeviceCategory? {
        val n = text?.lowercase(Locale.US)?.trim().orEmpty()
        if (n.isEmpty()) return null
        return when {
            listOf("obd", "elm327", "vlinker", "carplay", "handsfree", "jac ", "mycar").any { n.contains(it) } -> DeviceCategory.VEHICLE
            listOf("airtag", "smarttag", "chipolo", "tile ", "pebblebee", "xiaomi tag", " tag").any { n.contains(it) } || n == "tag" -> DeviceCategory.TRACKER
            listOf("airpods", "buds", "earbud", "headphone", "headset", "jbl", "soundbar", "speaker", "bose", "beats", "wh-1000", "wf-1000", "freebuds").any { n.contains(it) } -> DeviceCategory.AUDIO
            listOf("watch", "band", "amazfit", "garmin", "fitbit", "whoop", "oura", "mi smart").any { n.contains(it) } -> DeviceCategory.WEARABLE
            listOf("tv", "bravia", "webos", "chromecast", "apple tv", "echo", "alexa", "nest", "homepod", "bulb", "lamp").any { n.contains(it) } -> DeviceCategory.TV_HOME
            listOf("keyboard", "teclado", "mouse", "ratón", "gamepad", "controller", "mx master", "mx keys", "pencil").any { n.contains(it) } -> DeviceCategory.PERIPHERAL
            listOf("iphone", "ipad", "macbook", "imac", "galaxy", "pixel", "redmi", "poco", "laptop", "desktop").any { n.contains(it) } -> DeviceCategory.PHONE_PC
            else -> null
        }
    }

    private fun inferCategory(a: Advert, gatt: GattInfo?, ids: Set<String>, appleTypes: List<Int>): DeviceCategory {
        if (gatt?.dultCategory != null) return DeviceCategory.TRACKER
        categoryFromName(gatt?.model)?.let { return it }
        categoryFromName(a.name ?: gatt?.deviceName)?.let { return it }
        categoryFromAppearance(gatt?.appearance ?: a.appearance)?.let { return it }
        if (0x07 in appleTypes) return DeviceCategory.AUDIO
        if (0x02 in appleTypes) return DeviceCategory.BEACON
        if (0x09 in appleTypes || 0x06 in appleTypes) return DeviceCategory.TV_HOME
        if (appleTypes.any { it == 0x10 || it == 0x0C || it == 0x05 || it == 0x0F }) return DeviceCategory.PHONE_PC
        if ("fe2c" in ids) return DeviceCategory.AUDIO
        if ("1812" in ids) return DeviceCategory.PERIPHERAL
        if ("180d" in ids) return DeviceCategory.WEARABLE
        val eddy = a.serviceData.entries.firstOrNull { short16(it.key) == "feaa" }?.value
        if (eddy != null && eddy.isNotEmpty() && (eddy[0].toInt() and 0xFF) in setOf(0x00, 0x10, 0x20)) return DeviceCategory.BEACON
        if ("fe95" in ids) return DeviceCategory.WEARABLE
        if (COMPANY_BOSE in a.manufacturer || COMPANY_HARMAN in a.manufacturer || COMPANY_SONY in a.manufacturer) return DeviceCategory.AUDIO
        if (COMPANY_GARMIN in a.manufacturer || COMPANY_HUAMI in a.manufacturer) return DeviceCategory.WEARABLE
        if (COMPANY_LOGITECH in a.manufacturer) return DeviceCategory.PERIPHERAL
        if (COMPANY_MICROSOFT in a.manufacturer) return DeviceCategory.PHONE_PC
        categoryFromBluetoothClass(a.majorDeviceClass, a.deviceClass)?.let { return it }
        return DeviceCategory.OTHER
    }

    fun classify(a: Advert, gatt: GattInfo? = null): Classification {
        val ids = a.serviceUuids.map(::short16).toSet() + a.serviceData.keys.map(::short16)
        val apple = a.manufacturer[COMPANY_APPLE]
        val appleTypes = if (apple != null) BleAdParser.appleSubtypes(apple) else emptyList()

        if (0x12 in appleTypes) {
            return Classification(
                kind = TrackerKind.APPLE_FIND_MY,
                confidence = Confidence.HIGH,
                basis = "Datos de fabricante Apple, trama TLV 0x12 (Offline Finding).",
                note = "Puede ser un AirTag o cualquier accesorio en Apple Find My, incluido un Xiaomi Tag configurado en Apple. Los identificadores rotan.",
                category = DeviceCategory.TRACKER,
            )
        }
        val eddy = a.serviceData.entries.firstOrNull { short16(it.key) == "feaa" }?.value
        if (eddy != null && eddy.isNotEmpty()) {
            val frame = eddy[0].toInt() and 0xFF
            if (frame == 0x40 || frame == 0x41) {
                val mode = if (frame == 0x41) "protección anti-rastreo activa (0x41)" else "modo normal (0x40)"
                val bits = when {
                    eddy.size >= 33 -> ", EID 256-bit"
                    eddy.size >= 21 -> ", EID 160-bit"
                    else -> ""
                }
                return Classification(
                    kind = TrackerKind.GOOGLE_FIND_HUB,
                    confidence = Confidence.MEDIUM,
                    basis = "Servicio 0xFEAA FMDN · $mode$bits. $NOT_VALIDATED",
                    note = "Accesorio de Google Find Hub (puede ser Xiaomi Tag, Pebblebee, Chipolo, Moto Tag…). El nombre personalizado solo existe en la app del dueño.",
                    category = DeviceCategory.TRACKER,
                )
            }
        }
        if ("fd5a" in ids || "fd59" in ids) {
            return Classification(
                TrackerKind.SAMSUNG_SMARTTAG, Confidence.MEDIUM, "Servicio Samsung SmartTag (0xFD5A/0xFD59). $NOT_VALIDATED",
                "", DeviceCategory.TRACKER,
            )
        }
        if ("feed" in ids || "feec" in ids) {
            return Classification(TrackerKind.TILE, Confidence.MEDIUM, "Servicio Tile (0xFEED/0xFEEC). $NOT_VALIDATED", "", DeviceCategory.TRACKER)
        }
        if ("fe33" in ids) {
            return Classification(TrackerKind.CHIPOLO, Confidence.MEDIUM, "Servicio Chipolo (0xFE33). $NOT_VALIDATED", "", DeviceCategory.TRACKER)
        }
        if (DULT_SERVICE_UUID in ids || gatt?.dultCategory != null) {
            return Classification(
                TrackerKind.GOOGLE_FIND_HUB, Confidence.MEDIUM,
                "Servicio IETF DULT (rastreador compatible con Find Hub / Find My).",
                "Usa «Identificar» para consultar fabricante y modelo por DULT.",
                DeviceCategory.TRACKER,
            )
        }

        val cat = inferCategory(a, gatt, ids, appleTypes)
        val xiaomiName = (a.name ?: gatt?.deviceName ?: gatt?.manufacturer).orEmpty().lowercase(Locale.US)
        val isXiaomi = COMPANY_XIAOMI in a.manufacturer || "fe95" in ids ||
            listOf("xiaomi", "redmi", "mi band", "mi smart").any { xiaomiName.contains(it) }
        if (isXiaomi) {
            val basis = if ("fe95" in ids) "Servicio Xiaomi MiBeacon (0xFE95)." else "ID de empresa Bluetooth SIG 0x038F o nombre Xiaomi."
            return Classification(
                kind = TrackerKind.XIAOMI_DEVICE,
                confidence = Confidence.LOW,
                basis = basis,
                note = "Dispositivo del ecosistema Xiaomi (pulsera, reloj, audífonos, sensor o Tag).",
                category = cat,
            )
        }

        val genericBasis = when {
            cat != DeviceCategory.OTHER -> "Categoría inferida (${cat.label}) desde anuncio BLE / clase / nombre."
            else -> "Sin firma conocida."
        }
        return Classification(TrackerKind.UNKNOWN, Confidence.LOW, genericBasis, "", cat)
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
    val gattInfo: GattInfo? = null,
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
            appearance = advert.appearance ?: prev.advert.appearance,
            majorDeviceClass = advert.majorDeviceClass ?: prev.advert.majorDeviceClass,
            deviceClass = advert.deviceClass ?: prev.advert.deviceClass,
            txPower = advert.txPower ?: prev.advert.txPower,
        )
        val gatt = prev?.gattInfo
        val s = Sighting(
            address = address,
            advert = merged,
            classification = TrackerClassifier.classify(merged, gatt),
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
            gattInfo = gatt,
        )
        map[address] = s
        return s
    }

    @Synchronized
    fun attachGatt(address: String, info: GattInfo): Sighting? {
        val prev = map[address] ?: return null
        val updated = prev.copy(
            classification = TrackerClassifier.classify(prev.advert, info),
            gattInfo = info,
        )
        map[address] = updated
        return updated
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
        "name" to (s.advert.name ?: s.gattInfo?.deviceName),
        "displayName" to DeviceNaming.resolvedTitle(s.advert, s.classification, nickname, s.gattInfo),
        "category" to s.classification.category.name,
        "categoryLabel" to s.classification.category.label,
        "kind" to s.classification.kind.name,
        "kindLabel" to s.classification.kind.label,
        "gattManufacturer" to s.gattInfo?.manufacturer,
        "gattModel" to s.gattInfo?.model,
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
            val label = DeviceNaming.resolvedTitle(s.advert, s.classification, nicknames[s.address], s.gattInfo)
            val suffix = if (source == "estimated") " (±${est!!.radiusM.toInt()} m)" else ""
            append("  <wpt lat=\"${p.lat}\" lon=\"${p.lon}\"><name>${esc(label)} ${esc(s.address.takeLast(5))}</name>")
            append("<desc>${esc(s.classification.category.label)} · RSSI ${s.bestRssi} dBm, ${s.count} avistamientos$suffix</desc></wpt>\n")
        }
        append("</gpx>\n")
    }

    /** Enlace `geo:` que abre cualquier app de mapas del teléfono (sin API ni clave). */
    fun geoUri(p: GeoPoint, label: String) = "geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(${java.net.URLEncoder.encode(label, "UTF-8")})"
}

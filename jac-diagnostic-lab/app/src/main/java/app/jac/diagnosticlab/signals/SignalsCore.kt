package app.jac.diagnosticlab.signals

import app.jac.diagnosticlab.evidence.Json
import app.jac.diagnosticlab.tracker.DeviceCategory
import app.jac.diagnosticlab.tracker.Sighting
import app.jac.diagnosticlab.tracker.TrackerKind
import java.util.Locale

enum class WifiSecurity(val label: String, val weak: Boolean) {
    OPEN("Abierta", true),
    WEP("WEP (insegura)", true),
    WPA("WPA antigua", true),
    WPA2("WPA2", false),
    WPA3("WPA3", false),
    ENTERPRISE("WPA-Enterprise", false),
    UNKNOWN("Desconocida", false),
}

data class WifiNet(
    val ssid: String,
    val bssid: String,
    val freqMhz: Int,
    val channel: Int?,
    val band: String,
    val widthMhz: Int,
    val rssiDbm: Int,
    val security: WifiSecurity,
    val standard: String = "",
)

data class CellTower(
    val tech: String, // "5G NR", "4G LTE", "3G", "2G GSM"
    val registered: Boolean,
    val operatorMccMnc: String,
    val arfcn: Int?,
    val freqMhz: Double?,
    val bandHint: String,
    val pci: Int?,
    val rsrpDbm: Int?,
    val rsrqDb: Int?,
    val sinrDb: Int?,
)

data class GnssSummary(
    val visible: Int = 0,
    val used: Int = 0,
    val avgCn0DbHz: Double = 0.0,
    val maxCn0DbHz: Double = 0.0,
    val constellations: Map<String, Int> = emptyMap(),
    val carrierFreqsMhz: List<Double> = emptyList(),
)

data class MagneticReading(
    val magnitudeUt: Double = 0.0,
    val peakHz: Double = 0.0,
    val peakAmpUt: Double = 0.0,
    val sampleRateHz: Double = 0.0,
    val spectrum: List<Double> = emptyList(),
    val available: Boolean = true,
)

enum class FindingSeverity(val label: String, val rank: Int) {
    ALERT("ALERTA", 3),
    WARN("ATENCIÓN", 2),
    INFO("INFO", 1),
    OK("OK", 0),
}

data class SecurityFinding(
    val id: String,
    val severity: FindingSeverity,
    val title: String,
    val detail: String,
    val action: String,
)

object RadioParsers {
    fun parseWifiSecurity(capabilities: String): WifiSecurity {
        val c = capabilities.uppercase(Locale.US)
        return when {
            c.contains("WEP") -> WifiSecurity.WEP
            c.contains("EAP") -> WifiSecurity.ENTERPRISE
            c.contains("SAE") || c.contains("WPA3") -> WifiSecurity.WPA3
            c.contains("RSN") || c.contains("WPA2") -> WifiSecurity.WPA2
            c.contains("WPA") -> WifiSecurity.WPA
            c.contains("ESS") || c.isBlank() -> WifiSecurity.OPEN
            else -> WifiSecurity.UNKNOWN
        }
    }

    /** Detecta posibles Evil Twin: mismo SSID visible con al menos una red segura y otra abierta/débil. */
    fun findEvilTwinSsids(nets: List<WifiNet>): Set<String> =
        nets.filter { it.ssid.isNotBlank() }
            .groupBy { it.ssid }
            .filterValues { group -> group.any { it.security.weak } && group.any { !it.security.weak } }
            .keys
}

/**
 * Auditoría defensiva pasiva de puertas de entrada alrededor del vehículo (sin transmitir ni intervenir nada).
 * Usa solo las radios y sensores nativos del teléfono: BLE, Wi-Fi, Celular, GNSS y Magnetómetro.
 */
object VehicleSecurityAudit {
    private val obdHints = listOf("obd", "elm327", "vlinker", "obdlink", "carista", "veepeak")

    fun evaluate(
        sightings: List<Sighting>,
        wifi: List<WifiNet>,
        cells: List<CellTower>,
        gnss: GnssSummary,
        mag: MagneticReading,
    ): List<SecurityFinding> {
        val findings = ArrayList<SecurityFinding>()

        // 1. Adaptador OBD-II Bluetooth visible (puerta física directa al bus CAN)
        val obdVisible = sightings.filter { s ->
            val n = (s.advert.name ?: s.gattInfo?.deviceName).orEmpty().lowercase(Locale.US)
            s.classification.category == DeviceCategory.VEHICLE || obdHints.any { n.contains(it) }
        }
        if (obdVisible.isNotEmpty()) {
            val names = obdVisible.mapNotNull { it.advert.name?.takeIf { s -> s.isNotBlank() } }.distinct().ifEmpty { listOf("dispositivo OBD/vehicular") }
            findings += SecurityFinding(
                id = "OBD-BLE-EXPOSED",
                severity = FindingSeverity.WARN,
                title = "Adaptador OBD/vehicular visible por Bluetooth (${obdVisible.size})",
                detail = "Detectado: ${names.joinToString(", ")}. Los clones ELM327 suelen usar PIN fijo 1234/0000 sin cifrado fuerte.",
                action = "Desenchufe el adaptador del puerto OBD-II al dejar la camioneta estacionada.",
            )
        } else {
            findings += SecurityFinding(
                id = "OBD-BLE-OK",
                severity = FindingSeverity.OK,
                title = "Sin adaptadores OBD visibles por BLE",
                detail = "No se detectan dongles OBD anunciándose en este momento.",
                action = "Mantenga el puerto OBD-II libre cuando no esté diagnosticando.",
            )
        }

        // 2. Rastreadores BLE cercanos y tramas en modo alerta anti-rastreo (0x41)
        val trackers = sightings.filter { it.classification.category == DeviceCategory.TRACKER }
        val alertTrackers = trackers.filter { s ->
            s.classification.kind == TrackerKind.GOOGLE_FIND_HUB && s.classification.basis.contains("0x41")
        }
        when {
            alertTrackers.isNotEmpty() -> findings += SecurityFinding(
                id = "BLE-TRACKER-ALERT",
                severity = FindingSeverity.ALERT,
                title = "Rastreador en modo alerta anti-rastreo (${alertTrackers.size})",
                detail = "Se detectó trama Google Find Hub 0x41 (modo separado del dueño / protección anti-rastreo activa).",
                action = "Revise en la pestaña BLE / Tags, use «Identificar» (DULT) y busque el objeto físicamente.",
            )
            trackers.isNotEmpty() -> findings += SecurityFinding(
                id = "BLE-TRACKERS-PRESENT",
                severity = FindingSeverity.INFO,
                title = "Rastreadores BLE cercanos (${trackers.size})",
                detail = "Detectados accesorios de redes Find My / Find Hub / SmartTag / Tile. Pueden ser propios o de terceros cercanos.",
                action = "Asigne apodos a los suyos; si un desconocido lo sigue a otro lugar, inspecciónelo con la flecha.",
            )
            else -> findings += SecurityFinding(
                id = "BLE-TRACKERS-NONE",
                severity = FindingSeverity.OK,
                title = "Sin rastreadores BLE conocidos detectados",
                detail = "No hay accesorios Find My / Find Hub / SmartTag visibles en el escaneo actual.",
                action = "Ejecute el escaneo BLE en otro lugar apartado para descartar tags que roten o duerman.",
            )
        }

        // 3. Celular: downgrade a 2G GSM (vector clásico de interferencia / IMSI-catcher)
        val serving = cells.firstOrNull { it.registered }
        when {
            serving == null && cells.isEmpty() -> findings += SecurityFinding(
                id = "CELL-NO-DATA",
                severity = FindingSeverity.INFO,
                title = "Sin lectura de red celular",
                detail = "Inicie el escaneo en «Redes y RF» con ubicación activa para inspeccionar bandas 4G/5G.",
                action = "Pulse Actualizar en Redes y RF.",
            )
            serving?.tech == "2G GSM" -> findings += SecurityFinding(
                id = "CELL-2G-DOWNGRADE",
                severity = FindingSeverity.ALERT,
                title = "Conexión degradada a 2G GSM",
                detail = "El teléfono está registrado en 2G (cifrado débil/ausente), inusual en zonas con cobertura 4G/5G.",
                action = "Desactive 2G en Ajustes de Android si no lo necesita; en zonas urbanas puede indicar interferencia.",
            )
            serving != null -> {
                val freqTxt = serving.freqMhz?.let { String.format(Locale.US, " · %.1f MHz", it) }.orEmpty()
                val rsrpTxt = serving.rsrpDbm?.let { " · $it dBm" }.orEmpty()
                findings += SecurityFinding(
                    id = "CELL-OK",
                    severity = FindingSeverity.OK,
                    title = "Red celular ${serving.tech} (${serving.bandHint})",
                    detail = "Torre activa${freqTxt}${rsrpTxt} · ${cells.size} celdas visibles.",
                    action = "Normal: cobertura moderna cifrada.",
                )
            }
        }

        // 4. Wi-Fi: Evil Twin y redes abiertas/inseguras cercanas
        val evilTwins = RadioParsers.findEvilTwinSsids(wifi)
        val weakWifi = wifi.filter { it.security.weak }
        when {
            evilTwins.isNotEmpty() -> findings += SecurityFinding(
                id = "WIFI-EVIL-TWIN",
                severity = FindingSeverity.ALERT,
                title = "Posible Evil Twin Wi-Fi (${evilTwins.joinToString(", ")})",
                detail = "Un mismo nombre de red (SSID) aparece simultáneamente con cifrado seguro y en versión abierta/débil.",
                action = "No se conecte a la copia abierta ni ingrese contraseñas; verifique su router o dashcam.",
            )
            weakWifi.isNotEmpty() -> findings += SecurityFinding(
                id = "WIFI-WEAK-NEARBY",
                severity = FindingSeverity.INFO,
                title = "Redes Wi-Fi abiertas o WEP cercanas (${weakWifi.size} de ${wifi.size})",
                detail = "Ejemplos: ${weakWifi.take(3).joinToString(", ") { it.ssid.ifBlank { "(oculta)" } }}.",
                action = "Evite conexión automática a redes abiertas; revise si alguna dashcam o router propio está abierto.",
            )
            wifi.isNotEmpty() -> findings += SecurityFinding(
                id = "WIFI-OK",
                severity = FindingSeverity.OK,
                title = "Sin redes Wi-Fi abiertas ni Evil Twin (${wifi.size} redes)",
                detail = "Todas las redes visibles usan WPA2/WPA3/Enterprise.",
                action = "Correcto.",
            )
        }

        // 5. GNSS: presencia y relación portadora/ruido (C/N0)
        if (gnss.visible > 0) {
            if (gnss.used == 0 && gnss.avgCn0DbHz < 18.0) {
                findings += SecurityFinding(
                    id = "GNSS-DEGRADED",
                    severity = FindingSeverity.WARN,
                    title = "Señal satelital GNSS muy degradada (${gnss.visible} vistos, 0 usados)",
                    detail = String.format(Locale.US, "C/N0 promedio %.1f dB-Hz. En interiores es normal; a cielo abierto puede indicar interferencia.", gnss.avgCn0DbHz),
                    action = "Pruebe a cielo abierto lejos de techos metálicos para distinguir sombra física de interferencia.",
                )
            } else {
                findings += SecurityFinding(
                    id = "GNSS-OK",
                    severity = FindingSeverity.OK,
                    title = "Satélites GNSS estables (${gnss.used}/${gnss.visible} usados)",
                    detail = String.format(Locale.US, "C/N0 promedio %.1f dB-Hz · %s.", gnss.avgCn0DbHz, gnss.constellations.entries.joinToString(", ") { "${it.key}:${it.value}" }),
                    action = "Fijación satelital saludable.",
                )
            }
        }

        // 6. Magnetómetro: campo magnético local fuerte (útil para barrer chasis por cajas GPS imantadas)
        if (mag.available && mag.magnitudeUt > 120.0) {
            findings += SecurityFinding(
                id = "MAG-ANOMALY",
                severity = FindingSeverity.WARN,
                title = String.format(Locale.US, "Anomalía magnética fuerte (%.0f µT)", mag.magnitudeUt),
                detail = "El campo terrestre normal está entre 25 y 65 µT. Un pico > 120 µT indica un imán cercano, motor eléctrico o cableado de alta corriente.",
                action = "Al pasar el teléfono bajo pasos de rueda o parachoques, inspeccione visualmente si hay una caja adherida con imán.",
            )
        }

        return findings.sortedByDescending { it.severity.rank }
    }
}

object SignalsExport {
    fun toJson(
        atMs: Long,
        wifi: List<WifiNet>,
        cells: List<CellTower>,
        gnss: GnssSummary,
        mag: MagneticReading,
        findings: List<SecurityFinding>,
    ): String = Json.encode(
        linkedMapOf(
            "timestampMs" to atMs,
            "scope" to "Auditoria pasiva RF/Redes (solo sensores y radios nativas del telefono; 315/433 MHz requiere SDR externo)",
            "findings" to findings.map {
                linkedMapOf("id" to it.id, "severity" to it.severity.name, "title" to it.title, "detail" to it.detail, "action" to it.action)
            },
            "wifi" to wifi.map {
                linkedMapOf(
                    "ssid" to it.ssid, "bssid" to it.bssid, "freqMhz" to it.freqMhz, "channel" to it.channel,
                    "band" to it.band, "widthMhz" to it.widthMhz, "rssiDbm" to it.rssiDbm, "security" to it.security.name, "standard" to it.standard,
                )
            },
            "cells" to cells.map {
                linkedMapOf(
                    "tech" to it.tech, "registered" to it.registered, "operator" to it.operatorMccMnc,
                    "arfcn" to it.arfcn, "freqMhz" to it.freqMhz, "band" to it.bandHint,
                    "pci" to it.pci, "rsrpDbm" to it.rsrpDbm, "rsrqDb" to it.rsrqDb, "sinrDb" to it.sinrDb,
                )
            },
            "gnss" to linkedMapOf(
                "visible" to gnss.visible, "used" to gnss.used, "avgCn0DbHz" to gnss.avgCn0DbHz,
                "maxCn0DbHz" to gnss.maxCn0DbHz, "constellations" to gnss.constellations, "carrierFreqsMhz" to gnss.carrierFreqsMhz,
            ),
            "magnetic" to linkedMapOf(
                "magnitudeUt" to mag.magnitudeUt, "peakHz" to mag.peakHz, "peakAmpUt" to mag.peakAmpUt, "sampleRateHz" to mag.sampleRateHz,
            ),
        ),
    )
}

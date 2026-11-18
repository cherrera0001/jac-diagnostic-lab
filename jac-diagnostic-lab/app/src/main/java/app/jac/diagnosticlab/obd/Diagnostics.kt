package app.jac.diagnosticlab.obd

import app.jac.diagnosticlab.elm.Exchange
import app.jac.diagnosticlab.elm.ResponseKind

/* ───────────────────────── Monitores de preparación (01 01 / 01 41) ───────────────────────── */

enum class MonitorState(val label: String) { COMPLETE("completo"), INCOMPLETE("INCOMPLETO"), NOT_SUPPORTED("no soportado") }

data class MonitorStatus(val name: String, val state: MonitorState)

data class Readiness(
    val milOn: Boolean?,
    val dtcCount: Int?,
    val compressionIgnition: Boolean,
    val monitors: List<MonitorStatus>,
    val thisCycle: Boolean,
) {
    val incomplete: List<MonitorStatus> get() = monitors.filter { it.state == MonitorState.INCOMPLETE }
    val supported: List<MonitorStatus> get() = monitors.filter { it.state != MonitorState.NOT_SUPPORTED }
}

object ReadinessDecoder {
    private val spark = listOf("Catalizador", "Catalizador calefaccionado", "Sistema evaporativo", "Aire secundario", "Refrigerante A/C", "Sonda O2", "Calefactor sonda O2", "EGR / VVT")
    private val diesel = listOf("Catalizador NMHC", "NOx / SCR", "Reservado", "Presión de turbo", "Reservado", "Sensor de gases de escape", "Filtro de partículas (DPF)", "EGR / VVT")

    /** [data] = 4 bytes A B C D tras `41 01` o `41 41`. */
    fun decode(data: List<Int>, thisCycle: Boolean = false): Readiness? {
        if (data.size < 4) return null
        val (a, b, c, d) = data
        val ci = (b shr 3) and 1 == 1
        fun st(avail: Boolean, incomplete: Boolean) = when {
            !avail -> MonitorState.NOT_SUPPORTED
            incomplete -> MonitorState.INCOMPLETE
            else -> MonitorState.COMPLETE
        }
        val monitors = buildList {
            add(MonitorStatus("Fallo de combustión (misfire)", st(b and 1 != 0, b and 0x10 != 0)))
            add(MonitorStatus("Sistema de combustible", st(b and 2 != 0, b and 0x20 != 0)))
            add(MonitorStatus("Componentes integrales", st(b and 4 != 0, b and 0x40 != 0)))
            val names = if (ci) diesel else spark
            for (i in 0..7) {
                if (names[i] == "Reservado") continue
                add(MonitorStatus(names[i], st((c shr i) and 1 == 1, (d shr i) and 1 == 1)))
            }
        }
        return Readiness(
            milOn = if (thisCycle) null else a and 0x80 != 0,
            dtcCount = if (thisCycle) null else a and 0x7F,
            compressionIgnition = ci,
            monitors = monitors,
            thisCycle = thisCycle,
        )
    }

    fun fromExchange(exchange: Exchange?, thisCycle: Boolean = false): Readiness? {
        if (exchange == null || exchange.kind != ResponseKind.OBD_DATA) return null
        val pid = if (thisCycle) 0x41 else 0x01
        val p = exchange.frames.firstOrNull { it.payload.size >= 6 && it.payload[0] == 0x41 && it.payload[1] == pid }?.payload ?: return null
        return decode(p.drop(2), thisCycle)
    }
}

/* ───────────────────────── Mode 06: resultados de pruebas a bordo ───────────────────────── */

data class Mode06Result(
    val mid: Int,
    val tid: Int,
    val uasid: Int,
    val monitorName: String,
    val value: Double,
    val min: Double,
    val max: Double,
    val unit: String,
    val passed: Boolean,
    val rawValue: Int,
    val rawMin: Int,
    val rawMax: Int,
)

object Mode06Decoder {
    private data class Scale(val factor: Double, val unit: String, val offset: Double = 0.0)

    private val scales: Map<Int, Scale> = mapOf(
        0x01 to Scale(1.0, ""), 0x02 to Scale(0.1, ""), 0x03 to Scale(0.01, ""), 0x04 to Scale(0.001, ""),
        0x05 to Scale(0.0000305, ""), 0x06 to Scale(0.000305, ""), 0x07 to Scale(0.25, "rpm"), 0x08 to Scale(0.01, "km/h"),
        0x09 to Scale(1.0, "km/h"), 0x0A to Scale(0.122, "mV"), 0x0B to Scale(0.001, "V"), 0x0C to Scale(0.01, "V"),
        0x0D to Scale(0.00390625, "mA"), 0x0E to Scale(0.001, "A"), 0x0F to Scale(0.01, "A"), 0x10 to Scale(1.0, "ms"),
        0x11 to Scale(100.0, "ms"), 0x12 to Scale(1.0, "s"), 0x13 to Scale(1.0, "mΩ"), 0x14 to Scale(1.0, "Ω"),
        0x15 to Scale(1.0, "kΩ"), 0x16 to Scale(0.1, "°C", -40.0), 0x17 to Scale(0.01, "kPa"), 0x18 to Scale(0.0117, "kPa"),
        0x19 to Scale(0.079, "kPa"), 0x1A to Scale(1.0, "kPa"), 0x1B to Scale(10.0, "kPa"), 0x1C to Scale(0.01, "°"),
        0x1D to Scale(0.5, "°"), 0x1E to Scale(0.0000305, "λ"), 0x1F to Scale(0.05, "A/F"), 0x20 to Scale(0.00390625, ""),
        0x21 to Scale(1.0, "mHz"), 0x22 to Scale(1.0, "Hz"), 0x23 to Scale(1.0, "kHz"), 0x24 to Scale(1.0, "cuentas"),
        0x25 to Scale(1.0, "km"), 0x26 to Scale(0.1, "mV/ms"), 0x27 to Scale(0.01, "g/s"), 0x28 to Scale(1.0, "g/s"),
        0x29 to Scale(0.25, "Pa/s"), 0x2A to Scale(0.001, "kg/h"), 0x2B to Scale(1.0, "conmutaciones"), 0x2C to Scale(0.01, "g/cil"),
        0x2D to Scale(0.01, "mg/carrera"), 0x2E to Scale(1.0, "bool"), 0x2F to Scale(0.01, "%"), 0x30 to Scale(0.001526, "%"),
        0x31 to Scale(0.001, "L"), 0x33 to Scale(0.00024414, ""), 0x34 to Scale(1.0, "min"), 0x35 to Scale(10.0, "ms"),
        0x36 to Scale(0.01, "g"), 0x37 to Scale(0.1, "g"), 0x38 to Scale(1.0, "g"), 0x39 to Scale(0.01, "%", -327.68),
        0x3A to Scale(0.001, "g"), 0x3B to Scale(0.0001, "g"), 0x3C to Scale(0.1, "µs"), 0x3D to Scale(0.01, "mA"),
        0x3F to Scale(0.01, "L"),
        // Con signo (UASID ≥ 0x80)
        0x81 to Scale(1.0, ""), 0x82 to Scale(0.1, ""), 0x83 to Scale(0.01, ""), 0x84 to Scale(0.001, ""),
        0x85 to Scale(0.0000305, ""), 0x86 to Scale(0.000305, ""), 0x8A to Scale(0.122, "mV"), 0x8B to Scale(0.001, "V"),
        0x8C to Scale(0.01, "V"), 0x8D to Scale(0.00390625, "mA"), 0x8E to Scale(0.001, "A"), 0x90 to Scale(1.0, "ms"),
        0x96 to Scale(0.1, "°C"), 0x99 to Scale(0.1, "kPa"), 0x9C to Scale(0.01, "°"), 0x9D to Scale(0.5, "°"),
        0xA8 to Scale(1.0, "g/s"), 0xA9 to Scale(0.25, "Pa/s"), 0xAD to Scale(0.01, "mg/carrera"), 0xAE to Scale(0.1, "mg/carrera"),
        0xAF to Scale(0.01, "%"), 0xB0 to Scale(0.003052, "%"), 0xB1 to Scale(2.0, "mV/s"), 0xFC to Scale(0.01, "kPa"),
        0xFD to Scale(0.001, "kPa"), 0xFE to Scale(0.25, "Pa"),
    )

    fun midName(mid: Int): String = when (mid) {
        in 0x01..0x10 -> "Sonda O2 #$mid"
        0x21 -> "Catalizador banco 1"; 0x22 -> "Catalizador banco 2"
        0x31 -> "EGR banco 1"; 0x32 -> "EGR banco 2"; 0x33 -> "EGR banco 3"; 0x34 -> "EGR banco 4"
        0x35 -> "VVT banco 1"; 0x36 -> "VVT banco 2"
        0x39 -> "EVAP 0,150\""; 0x3A -> "EVAP 0,090\""; 0x3B -> "EVAP 0,040\""; 0x3C -> "EVAP 0,020\""; 0x3D -> "Flujo de purga"
        in 0x41..0x50 -> "Calefactor sonda O2 #${mid - 0x40}"
        0x61 -> "Catalizador calefaccionado B1"; 0x62 -> "Catalizador calefaccionado B2"
        0x71 -> "Aire secundario 1"; 0x72 -> "Aire secundario 2"
        0x81 -> "Sistema de combustible B1"; 0x82 -> "Sistema de combustible B2"; 0x83 -> "Sistema de combustible B3"; 0x84 -> "Sistema de combustible B4"
        0x85 -> "Presión de turbo B1"; 0x86 -> "Presión de turbo B2"
        0x90 -> "Adsorbedor NOx B1"; 0x91 -> "Adsorbedor NOx B2"
        0x98 -> "Catalizador NOx/SCR B1"; 0x99 -> "Catalizador NOx/SCR B2"
        0xA1 -> "Misfire general"
        in 0xA2..0xAD -> "Misfire cilindro ${mid - 0xA1}"
        0xB0 -> "Filtro de partículas B1"; 0xB1 -> "Filtro de partículas B2"
        in 0xE1..0xFF -> "Prueba del fabricante 0x%02X".format(mid)
        else -> "Monitor 0x%02X".format(mid)
    }

    fun isSupportMid(mid: Int) = mid % 0x20 == 0

    /** Mapa de soporte `46 00/20/40… b1 b2 b3 b4`. */
    fun supported(base: Int, exchange: Exchange): Set<Int> {
        if (exchange.kind != ResponseKind.OBD_DATA) return emptySet()
        val p = exchange.frames.firstOrNull { it.payload.size >= 6 && it.payload[0] == 0x46 && it.payload[1] == base }?.payload ?: return emptySet()
        return PidDecoder.supported(base, p.drop(2).take(4))
    }

    /** ISO 15765-4: `46 [MID TID UASID VH VL MINH MINL MAXH MAXL]×n`. */
    fun decode(exchange: Exchange): List<Mode06Result> {
        if (exchange.kind != ResponseKind.OBD_DATA) return emptyList()
        val out = mutableListOf<Mode06Result>()
        for (frame in exchange.frames) {
            val p = frame.payload
            if (p.isEmpty() || p[0] != 0x46) continue
            var i = 1
            while (i + 9 <= p.size) {
                val mid = p[i]
                if (isSupportMid(mid)) break
                val tid = p[i + 1]
                val uas = p[i + 2]
                val signed = uas >= 0x80
                fun word(k: Int): Int { val v = p[k] * 256 + p[k + 1]; return if (signed && v >= 0x8000) v - 0x10000 else v }
                val v = word(i + 3); val mn = word(i + 5); val mx = word(i + 7)
                val scale = scales[uas] ?: Scale(1.0, "UASID 0x%02X".format(uas))
                fun s(x: Int) = x * scale.factor + scale.offset
                out += Mode06Result(mid, tid, uas, midName(mid), s(v), s(mn), s(mx), scale.unit, v in mn..mx, v, mn, mx)
                i += 9
            }
        }
        return out
    }
}

/* ───────────────────────── Mode 09: identificación del vehículo ───────────────────────── */

data class VehicleInfo(
    val calibrationIds: List<String> = emptyList(),
    val cvns: List<String> = emptyList(),
    val ecuName: String? = null,
    val inUsePerformance: List<Pair<String, Int>> = emptyList(),
    val supported: Set<Int> = emptySet(),
)

object Mode09Decoder {
    private val iptSpark = listOf("Condiciones OBD", "Ciclos de encendido", "Catalizador B1 completos", "Catalizador B1 condiciones", "Catalizador B2 completos", "Catalizador B2 condiciones", "O2 B1 completos", "O2 B1 condiciones", "O2 B2 completos", "O2 B2 condiciones", "EGR completos", "EGR condiciones", "Aire sec. completos", "Aire sec. condiciones", "EVAP completos", "EVAP condiciones", "O2 sec. B1 completos", "O2 sec. B1 condiciones", "O2 sec. B2 completos", "O2 sec. B2 condiciones")
    private val iptDiesel = listOf("Condiciones OBD", "Ciclos de encendido", "Cat. NMHC completos", "Cat. NMHC condiciones", "Cat. NOx completos", "Cat. NOx condiciones", "Adsorbedor NOx completos", "Adsorbedor NOx condiciones", "DPF completos", "DPF condiciones", "Sensor gases completos", "Sensor gases condiciones", "EGR/VVT completos", "EGR/VVT condiciones", "Turbo completos", "Turbo condiciones", "Combustible completos", "Combustible condiciones")

    private fun body(exchange: Exchange, pid: Int): List<Int>? {
        if (exchange.kind != ResponseKind.OBD_DATA) return null
        val all = exchange.frames.flatMap { it.payload }
        val start = all.windowed(2).indexOfFirst { it[0] == 0x49 && it[1] == pid }
        if (start < 0) return null
        return all.drop(start + 2)
    }

    fun supported(exchange: Exchange): Set<Int> {
        val b = body(exchange, 0x00) ?: return emptySet()
        return if (b.size >= 4) PidDecoder.supported(0x00, b.take(4)) else emptySet()
    }

    private fun ascii(bytes: List<Int>) = bytes.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").trim()

    /** 49 04 NODI + 16 bytes ASCII por calibración. */
    fun calibrationIds(exchange: Exchange): List<String> {
        val b = body(exchange, 0x04) ?: return emptyList()
        if (b.isEmpty()) return emptyList()
        val data = b.drop(1)
        return data.chunked(16).map { ascii(it) }.filter { it.isNotBlank() }
    }

    fun cvns(exchange: Exchange): List<String> {
        val b = body(exchange, 0x06) ?: return emptyList()
        if (b.isEmpty()) return emptyList()
        return b.drop(1).chunked(4).filter { it.size == 4 }.map { c -> c.joinToString("") { "%02X".format(it) } }
    }

    fun ecuName(exchange: Exchange): String? {
        val b = body(exchange, 0x0A) ?: return null
        return ascii(b.drop(1).take(20)).replace("\u0000", "").ifBlank { null }
    }

    fun inUsePerformance(exchange: Exchange, diesel: Boolean): List<Pair<String, Int>> {
        val pid = if (diesel) 0x0B else 0x08
        val b = body(exchange, pid) ?: return emptyList()
        if (b.isEmpty()) return emptyList()
        val labels = if (diesel) iptDiesel else iptSpark
        return b.drop(1).chunked(2).filter { it.size == 2 }.mapIndexedNotNull { i, w ->
            labels.getOrNull(i)?.let { it to (w[0] * 256 + w[1]) }
        }
    }
}

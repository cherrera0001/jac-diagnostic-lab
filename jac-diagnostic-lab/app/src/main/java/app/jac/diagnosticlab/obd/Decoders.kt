package app.jac.diagnosticlab.obd

import app.jac.diagnosticlab.elm.Exchange
import app.jac.diagnosticlab.elm.ObdFrame
import app.jac.diagnosticlab.elm.ResponseKind

enum class DtcCategory { STORED, PENDING, PERMANENT }

enum class DtcScope { GENERIC, MANUFACTURER }

data class DtcHit(
    val code: String,
    val hi: Int,
    val lo: Int,
    val scope: DtcScope,
    val scopeNote: String,
    val ecu: String?,
    val category: DtcCategory,
)

data class DtcReadResult(
    val category: DtcCategory,
    val kind: ResponseKind,
    val codes: List<DtcHit>,
    val confirmedEmpty: Boolean,
    val raw: String,
    val note: String,
)

object DtcDecoder {
    fun decode(category: DtcCategory, exchange: Exchange, forceCan: Boolean?): DtcReadResult {
        if (exchange.kind != ResponseKind.OBD_DATA) {
            return DtcReadResult(
                category = category,
                kind = exchange.kind,
                codes = emptyList(),
                confirmedEmpty = false,
                raw = exchange.raw,
                note = when (exchange.kind) {
                    ResponseKind.NO_DATA -> "NO DATA: la ECU no respondió. No es prueba de que no existan códigos."
                    ResponseKind.PARTIAL, ResponseKind.TIMEOUT -> "Respuesta incompleta. No se inventaron códigos."
                    ResponseKind.MALFORMED -> "Respuesta malformada. No se inventaron códigos."
                    ResponseKind.DISCONNECTED -> "La conexión se cortó durante la lectura. No se inventaron códigos."
                    else -> exchange.detail
                },
            )
        }
        val service = when (category) {
            DtcCategory.STORED -> 0x43
            DtcCategory.PENDING -> 0x47
            DtcCategory.PERMANENT -> 0x4A
        }
        val hits = mutableListOf<DtcHit>()
        var confirmed = false
        var sawService = false
        var malformed = false
        var negative = false
        for (frame in exchange.frames) {
            val payload = frame.payload
            if (payload.isEmpty()) continue
            if (payload[0] == 0x7F) {
                negative = true
                continue
            }
            if (payload[0] != service) continue
            sawService = true
            when (val parsed = extract(payload, canLikely(frame, forceCan))) {
                null -> malformed = true
                else -> {
                    if (parsed.second && parsed.first.isEmpty()) confirmed = true
                    parsed.first.forEach { (hi, lo) ->
                        if (hi == 0 && lo == 0) return@forEach
                        hits += decodePair(hi, lo, frame.header, category)
                    }
                }
            }
        }
        val note = when {
            negative && hits.isEmpty() -> "La ECU respondió negativo (7F). No se inventaron códigos."
            malformed && hits.isEmpty() -> "No se pudo separar los códigos sin ambigüedad. No se inventó ninguno."
            hits.isEmpty() && confirmed -> "La ECU informó cero códigos de esta categoría."
            hits.isEmpty() && !sawService -> "No hubo un bloque del servicio esperado."
            hits.isEmpty() -> "No quedaron códigos después de descartar relleno 0000."
            else -> "Códigos decodificados de los bytes originales. Esta versión no incluye el texto del SAE J2012-DA."
        }
        return DtcReadResult(category, exchange.kind, hits, confirmed && hits.isEmpty() && !malformed, exchange.raw, note)
    }

    fun decodePair(hi: Int, lo: Int, ecu: String?, category: DtcCategory): DtcHit {
        val value = (hi shl 8) or lo
        val system = when ((value shr 14) and 0x3) {
            0 -> 'P'
            1 -> 'C'
            2 -> 'B'
            else -> 'U'
        }
        val second = (value shr 12) and 0x3
        val code = "%c%d%03X".format(system, second, value and 0xFFF)
        val generic = second == 0 || second == 2
        val scope = if (generic) DtcScope.GENERIC else DtcScope.MANUFACTURER
        val note = if (generic) {
            "Genérico según SAE J2012: el segundo carácter es 0 o 2. Sin texto descriptivo: no se incluye J2012-DA."
        } else if (second == 3) {
            "El segundo carácter 3 suele ser específico del fabricante. Algunos rangos de J2012-DA se reservan como genéricos; esta aplicación no resuelve esa excepción y no inventa una descripción."
        } else {
            "Específico del fabricante según SAE J2012: el segundo carácter es 1. Sin texto descriptivo confiable en esta aplicación."
        }
        return DtcHit(code, hi, lo, scope, note, ecu, category)
    }

    private fun canLikely(frame: ObdFrame, forceCan: Boolean?): Boolean {
        if (forceCan == true) return true
        if (forceCan == false) return false
        val header = frame.header?.uppercase()
        if (header != null && (header.matches(Regex("7E[0-9A-F]")) || header.startsWith("18DA"))) return true
        return frame.pciStripped
    }

    private fun extract(payload: List<Int>, can: Boolean): Pair<List<Pair<Int, Int>>, Boolean>? {
        if (payload.size < 2) return null
        val body = payload.drop(1)
        val byCount = countMode(body)
        val byPairs = pairMode(body)
        return when {
            can && byCount != null -> byCount
            !can && byPairs != null -> byPairs
            byCount != null && byPairs == null -> byCount
            byPairs != null && byCount == null -> byPairs
            byCount != null && byPairs != null -> if (can) byCount else byPairs
            else -> null
        }
    }

    private fun countMode(body: List<Int>): Pair<List<Pair<Int, Int>>, Boolean>? {
        if (body.isEmpty()) return null
        val n = body[0]
        if (n == 0) {
            return if (body.drop(1).all { it == 0 }) emptyList<Pair<Int, Int>>() to true else null
        }
        if (n !in 1..16) return null
        val tail = body.drop(1)
        if (tail.size < n * 2) return null
        if (tail.drop(n * 2).any { it != 0 }) return null
        val pairs = (0 until n).map { tail[it * 2] to tail[it * 2 + 1] }.filter { it != (0 to 0) }
        return pairs to pairs.isEmpty()
    }

    private fun pairMode(body: List<Int>): Pair<List<Pair<Int, Int>>, Boolean>? {
        if (body.size % 2 != 0) return null
        val pairs = body.chunked(2).map { it[0] to it[1] }.filter { it != (0 to 0) }
        val empty = pairs.isEmpty() && body.all { it == 0 }
        return pairs to empty
    }
}

fun canFromProtocol(protocolNumber: String): Boolean? {
    val n = protocolNumber.trim().uppercase().removePrefix("A")
    return when (n) {
        "6", "7", "8", "9" -> true
        "1", "2", "3", "4", "5" -> false
        else -> null
    }
}

data class PidReading(
    val pid: Int,
    val name: String,
    val raw: List<Int>,
    val value: Double?,
    val unit: String?,
    val valid: Boolean,
    val suspect: Boolean,
    val note: String,
    val standardName: String = name,
    val interpretationStatus: InterpretationStatus = InterpretationStatus.SUPPORTED,
    val physicalSignal: String = name,
)

object PidDecoder {
    private data class Spec(
        val pid: Int,
        val name: String,
        val standardName: String,
        val physicalSignal: String,
        val size: Int,
        val unit: String,
        val scale: (List<Int>) -> Double,
        val suspect: (Double) -> Boolean = { false },
        val interpretationStatus: InterpretationStatus = InterpretationStatus.SUPPORTED,
    )

    private val specs = listOf(
        Spec(0x04, "Carga calculada", "Calculated Load Value", "Carga calculada del motor", 1, "%", { it[0] * 100.0 / 255.0 }, { it < 0 || it > 100 }),
        Spec(0x05, "Temperatura del refrigerante", "Engine Coolant Temperature", "Temperatura del refrigerante", 1, "°C", { it[0] - 40.0 }, { it < -40 || it > 150 }),
        Spec(0x06, "Ajuste de combustible a corto plazo", "Short Term Fuel Trim", "STFT", 1, "%", { (it[0] - 128) * 100.0 / 128.0 }),
        Spec(0x07, "Ajuste de combustible a largo plazo", "Long Term Fuel Trim", "LTFT", 1, "%", { (it[0] - 128) * 100.0 / 128.0 }),
        Spec(0x0B, "Presión del colector", "Intake Manifold Absolute Pressure", "MAP", 1, "kPa", { it[0].toDouble() }, { it > 255 }),
        Spec(0x0C, "Régimen del motor", "Engine RPM", "RPM", 2, "rpm", { (it[0] * 256 + it[1]) / 4.0 }, { it > 8000 }),
        Spec(0x0D, "Velocidad", "Vehicle Speed", "Velocidad del vehículo", 1, "km/h", { it[0].toDouble() }),
        Spec(0x0E, "Avance de encendido", "Timing Advance", "Avance de encendido", 1, "°", { it[0] / 2.0 - 64.0 }),
        Spec(0x0F, "Temperatura de admisión", "Intake Air Temperature", "IAT", 1, "°C", { it[0] - 40.0 }),
        Spec(0x10, "Flujo de aire", "MAF Air Flow Rate", "MAF", 2, "g/s", { (it[0] * 256 + it[1]) / 100.0 }),
        // DIAG-001: SAE J1979 PID 0x11 = Absolute Throttle Position (mariposa), NO Accelerator Pedal Position.
        Spec(
            0x11,
            "Posición absoluta de mariposa",
            "Absolute Throttle Position",
            "Posición de mariposa (Throttle Position Sensor), no pedal de acelerador",
            1,
            "%",
            { it[0] * 100.0 / 255.0 },
            { it < 0 || it > 100 },
            InterpretationStatus.SUPPORTED,
        ),
        Spec(0x1F, "Tiempo de marcha", "Run Time Since Engine Start", "Tiempo desde arranque", 2, "s", { (it[0] * 256 + it[1]).toDouble() }),
        Spec(0x21, "Distancia con MIL", "Distance Traveled with MIL On", "Distancia con MIL", 2, "km", { (it[0] * 256 + it[1]).toDouble() }),
        Spec(0x2F, "Nivel de combustible", "Fuel Tank Level Input", "Nivel de combustible", 1, "%", { it[0] * 100.0 / 255.0 }),
        Spec(0x33, "Presión barométrica", "Absolute Barometric Pressure", "Baro", 1, "kPa", { it[0].toDouble() }),
        Spec(0x42, "Tensión del módulo", "Control Module Voltage", "Tensión módulo", 2, "V", { (it[0] * 256 + it[1]) / 1000.0 }, { it < 6 || it > 18 }),
        Spec(0x45, "Posición relativa de mariposa", "Relative Throttle Position", "Mariposa relativa", 1, "%", { it[0] * 100.0 / 255.0 }, { it < 0 || it > 100 }),
        Spec(0x46, "Temperatura ambiente", "Ambient Air Temperature", "Ambiente", 1, "°C", { it[0] - 40.0 }),
        Spec(0x47, "Posición absoluta de mariposa B", "Absolute Throttle Position B", "Mariposa B", 1, "%", { it[0] * 100.0 / 255.0 }, { it < 0 || it > 100 }),
        // Pedal de acelerador (APP) — distinto de 0x11 Absolute Throttle.
        Spec(0x49, "Posición del pedal acelerador D", "Accelerator Pedal Position D", "Pedal acelerador (pista D)", 1, "%", { it[0] * 100.0 / 255.0 }, { it < 0 || it > 100 }),
        Spec(0x4A, "Posición del pedal acelerador E", "Accelerator Pedal Position E", "Pedal acelerador (pista E)", 1, "%", { it[0] * 100.0 / 255.0 }, { it < 0 || it > 100 }),
        Spec(0x4B, "Posición del pedal acelerador F", "Accelerator Pedal Position F", "Pedal acelerador (pista F)", 1, "%", { it[0] * 100.0 / 255.0 }, { it < 0 || it > 100 }),
        Spec(0x5C, "Temperatura de aceite", "Engine Oil Temperature", "Aceite", 1, "°C", { it[0] - 40.0 }),
    ).associateBy { it.pid }

    /** PIDs útiles para el relato freno/potencia. No incluyen un PID genérico del interruptor de freno. */
    val faultHuntPids: List<Int> = listOf(0x0C, 0x0D, 0x04, 0x11, 0x45, 0x47, 0x49, 0x4A, 0x4B, 0x42, 0x05, 0x0B)

    fun supported(base: Int, data: List<Int>): Set<Int> {
        if (data.size < 4) return emptySet()
        val out = mutableSetOf<Int>()
        for (offset in 1..0x20) {
            val bitIndex = offset - 1
            val byte = data[bitIndex / 8]
            val bit = 7 - (bitIndex % 8)
            if (((byte shr bit) and 1) == 1) out += base + offset
        }
        return out
    }

    fun decode(pid: Int, exchange: Exchange): PidReading {
        val spec = specs[pid]
        val name = spec?.name ?: "PID %02X".format(pid)
        val standard = spec?.standardName ?: name
        val physical = spec?.physicalSignal ?: name
        val status = spec?.interpretationStatus ?: InterpretationStatus.UNKNOWN
        if (exchange.kind == ResponseKind.NO_DATA) {
            return PidReading(pid, name, emptyList(), null, spec?.unit, false, false, "NO DATA. No se usa cero como valor.", standard, InterpretationStatus.INCONCLUSIVE, physical)
        }
        if (exchange.kind != ResponseKind.OBD_DATA) {
            return PidReading(pid, name, emptyList(), null, spec?.unit, false, false, exchange.detail, standard, InterpretationStatus.INCONCLUSIVE, physical)
        }
        val payload = exchange.frames.firstOrNull { it.payload.size >= 2 && it.payload[0] == 0x41 && it.payload[1] == pid }?.payload
            ?: return PidReading(pid, name, emptyList(), null, spec?.unit, false, false, "La respuesta no contiene 41 %02X.".format(pid), standard, InterpretationStatus.INCONCLUSIVE, physical)
        val data = payload.drop(2)
        if (pid == 0x00 || pid == 0x20 || pid == 0x40 || pid == 0x60 || pid == 0x80 || pid == 0xA0 || pid == 0xC0) {
            if (data.size < 4) return PidReading(pid, "PIDs soportados", data, null, null, false, false, "Faltan bytes del mapa.")
            return PidReading(pid, "PIDs soportados", data.take(4), null, null, true, false, "Mapa de soporte, no es una medición física.")
        }
        if (spec == null) {
            return PidReading(pid, name, data, null, null, false, false, "Bytes originales conservados. Fórmula SAE J1979 no incluida para este PID.", name, InterpretationStatus.UNKNOWN, name)
        }
        if (data.size < spec.size) {
            return PidReading(pid, name, data, null, spec.unit, false, false, "Faltan bytes. No se calculó un valor.", standard, InterpretationStatus.INCONCLUSIVE, physical)
        }
        val slice = data.take(spec.size)
        val value = spec.scale(slice)
        val suspect = spec.suspect(value)
        val baseNote = if (suspect) {
            "Valor fuera del rango habitual. Se muestra igual, no se reemplaza por cero."
        } else {
            "Fórmula SAE J1979. Señal: $physical."
        }
        return PidReading(pid, name, slice, value, spec.unit, true, suspect, baseNote, standard, status, physical)
    }

    /** DIAG-002: nota contextual; no condena falla. */
    fun plausibilityNote(pid: Int, value: Double?, rpm: Double?, speedKmh: Double?): String? {
        if (pid != 0x11 || value == null) return null
        if (rpm != null && rpm in 500.0..1200.0 && (speedKmh == null || speedKmh == 0.0) && value >= 70.0) {
            return "Nota de plausibilidad contextual (DIAG-002): PID 0x11 (posición absoluta de mariposa) ≈ %.1f %% con ~%.0f rpm y 0 km/h. " +
                "No se clasifica automáticamente como falla. Revisar mapeo de señal, ECU origen y convención del vehículo.".format(value, rpm)
        }
        return null
    }
}

fun parseAdapterVoltage(raw: String): Double? =
    Regex("([0-9]+(?:\\.[0-9]+)?)").find(raw)?.groupValues?.get(1)?.toDoubleOrNull()

fun decodeVin(exchange: Exchange): String? {
    if (exchange.kind != ResponseKind.OBD_DATA) return null
    val bytes = exchange.frames.flatMap { it.payload }
    val start = bytes.windowed(2).indexOfFirst { it[0] == 0x49 && it[1] == 0x02 }
    if (start < 0) return null
    val rest = bytes.drop(start + 2)
    val ascii = rest.filter { it in 0x30..0x39 || it in 0x41..0x5A }
    if (ascii.size < 17) return null
    val text = ascii.take(17).map { it.toChar() }.joinToString("")
    return text.takeIf { it.length == 17 }
}

fun milFromPayload(payload: List<Int>): Pair<Boolean, Int>? {
    if (payload.size < 3 || payload[0] != 0x41 || payload[1] != 0x01) return null
    val a = payload[2]
    return ((a and 0x80) != 0) to (a and 0x7F)
}

data class FreezeNote(val raw: String, val dtc: String?, val note: String)

fun decodeFreeze(exchange: Exchange): FreezeNote {
    if (exchange.kind == ResponseKind.NO_DATA) {
        return FreezeNote(exchange.raw, null, "NO DATA: no hay freeze frame en esta respuesta. No equivale a decir que nunca existió.")
    }
    if (exchange.kind != ResponseKind.OBD_DATA) {
        return FreezeNote(exchange.raw, null, exchange.detail)
    }
    val payload = exchange.frames.firstOrNull { it.payload.size >= 2 && it.payload[0] == 0x42 }?.payload
        ?: return FreezeNote(exchange.raw, null, "No se identificó el servicio 42. Bytes originales conservados.")
    if (payload.size < 4 || payload[1] != 0x02) {
        return FreezeNote(exchange.raw, null, "Bytes originales del modo 02. No se forzó una decodificación.")
    }
    // DIAG-004: muchas ECU ponen número de cuadro tras el PID 02 (p. ej. 42 02 00 05 79 → P0579).
    // No tomar el 00 como byte alto del DTC (eso inventaba P0005).
    val (hi, lo, frameNote) = when {
        payload.size >= 5 -> Triple(payload[3], payload[4], "cuadro ${payload[2]}")
        else -> Triple(payload[2], payload[3], "sin número de cuadro")
    }
    if (hi == 0 && lo == 0) {
        return FreezeNote(exchange.raw, null, "El PID 02 del cuadro congelado vino en cero ($frameNote).")
    }
    val hit = DtcDecoder.decodePair(hi, lo, exchange.frames.firstOrNull()?.header, DtcCategory.STORED)
    return FreezeNote(
        exchange.raw,
        hit.code,
        "DTC del cuadro congelado, PID 02, $frameNote, bytes %02X %02X. ${hit.scopeNote}. No usar un resumen distinto de estos bytes.".format(hi, lo),
    )
}

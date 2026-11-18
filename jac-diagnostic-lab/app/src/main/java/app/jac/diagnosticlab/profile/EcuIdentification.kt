package app.jac.diagnosticlab.profile

import app.jac.diagnosticlab.elm.Exchange
import app.jac.diagnosticlab.obd.Mode09Decoder

/**
 * SPEC-031 R11. Identificación de ECU por Mode 09, solo lectura.
 * Cada respuesta se guarda con su raw, hora, latencia y valores separados por cabecera de ECU.
 * El VIN no entra en este registro: queda solo en el intercambio 0902 (que la copia anonimizada omite).
 */
data class EcuMode09Values(val ecu: String?, val values: List<String>)

data class Mode09Record(
    val command: String,
    val pid: Int,
    val atEpochMs: Long,
    val elapsedMs: Long,
    val kind: String,
    val raw: String,
    val perEcu: List<EcuMode09Values>,
    val note: String,
)

object EcuIdentification {
    const val VIN_REDACTED = "[VIN no se copia al registro de identificación; ver intercambio 0902]"

    /** 0900 siempre; luego 02 (VIN), 04, 06 y 0A si la ECU los declara. El vehículo es del usuario. */
    fun plan(supported09: Set<Int>?): List<String> = buildList {
        add("0900")
        if (supported09 == null) return@buildList
        if (0x02 in supported09) add("0902")
        listOf(0x04, 0x06, 0x0A).filter { it in supported09 }.forEach { add("09%02X".format(it)) }
    }

    fun record(exchange: Exchange): Mode09Record {
        val pid = exchange.command.trim().uppercase().removePrefix("09").take(2).toIntOrNull(16) ?: -1
        val byEcu = exchange.frames.groupBy { it.header }
        val perEcu = byEcu.map { (header, frames) ->
            val slice = exchange.copy(frames = frames)
            val values = when (pid) {
                0x00 -> Mode09Decoder.supported(slice).sorted().map { "09%02X".format(it) }
                0x02 -> if (frames.any { f -> f.payload.windowed(2).any { it[0] == 0x49 && it[1] == 0x02 } }) listOf("VIN presente (no copiado)") else emptyList()
                0x04 -> Mode09Decoder.calibrationIds(slice)
                0x06 -> Mode09Decoder.cvns(slice)
                0x0A -> listOfNotNull(Mode09Decoder.ecuName(slice))
                else -> emptyList()
            }
            EcuMode09Values(header, values)
        }.sortedBy { it.ecu ?: "" }
        val vin = pid == 0x02
        return Mode09Record(
            command = exchange.command,
            pid = pid,
            atEpochMs = exchange.atEpochMs,
            elapsedMs = exchange.elapsedMs,
            kind = exchange.kind.name,
            raw = if (vin) VIN_REDACTED else exchange.raw,
            perEcu = perEcu,
            note = when {
                exchange.frames.any { it.truncated } -> "Respuesta ISO-TP truncada por el adaptador; valores posiblemente incompletos."
                perEcu.size > 1 -> "Respondieron ${perEcu.size} ECUs; los valores no se mezclan."
                perEcu.isEmpty() -> "Sin tramas de datos (${exchange.kind.name})."
                else -> ""
            },
        )
    }

    /** CALID por ECU listo para [VehicleFingerprint.from]. */
    fun calibrationIds(records: List<Mode09Record>): List<Pair<String?, String>> =
        records.filter { it.pid == 0x04 }.flatMap { r -> r.perEcu.flatMap { e -> e.values.map { e.ecu to it } } }

    fun ecuNames(records: List<Mode09Record>): List<Pair<String?, String>> =
        records.filter { it.pid == 0x0A }.flatMap { r -> r.perEcu.flatMap { e -> e.values.map { e.ecu to it } } }

    fun toMap(r: Mode09Record): Map<String, Any?> = linkedMapOf(
        "command" to r.command,
        "atEpochMs" to r.atEpochMs,
        "elapsedMs" to r.elapsedMs,
        "kind" to r.kind,
        "raw" to r.raw,
        "perEcu" to r.perEcu.map { linkedMapOf("ecu" to it.ecu, "values" to it.values) },
        "note" to r.note,
    )
}

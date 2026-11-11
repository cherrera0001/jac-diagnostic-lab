package app.jac.diagnosticlab.evidence

import app.jac.diagnosticlab.analysis.AnalysisReport
import app.jac.diagnosticlab.analysis.Symptom
import app.jac.diagnosticlab.elm.Exchange
import app.jac.diagnosticlab.obd.DtcReadResult
import app.jac.diagnosticlab.obd.FreezeNote
import app.jac.diagnosticlab.obd.PidReading

data class Sample(
    val atEpochMs: Long,
    val pid: Int,
    val name: String,
    val value: Double?,
    val unit: String?,
    val valid: Boolean,
    val suspect: Boolean,
    val raw: String,
)

data class SessionRecord(
    val id: String,
    val startedAtEpochMs: Long,
    val androidDevice: String,
    val androidRelease: String,
    val connectionType: String,
    val adapterName: String,
    val adapterAddress: String,
    val adapterIdentity: String,
    val protocolText: String,
    val protocolNumber: String,
    val adapterVoltage: String,
    val exchanges: List<Exchange>,
    val dtcs: List<DtcReadResult>,
    val freeze: List<FreezeNote>,
    val samples: List<Sample>,
    val errors: List<String>,
    val connectionState: String,
    val symptoms: Set<Symptom>,
    val vin: String?,
    val simulated: Boolean,
    val vehicleNote: String,
    val analysis: AnalysisReport?,
)

fun anonymize(record: SessionRecord): SessionRecord {
    val vin = record.vin
    val address = record.adapterAddress
    val exchanges = record.exchanges.map { ex ->
        var raw = ex.raw
        if (!vin.isNullOrBlank()) raw = raw.replace(vin, "VIN-ANONIMIZADO")
        if (address.isNotBlank()) raw = raw.replace(address, "BT-ANONIMIZADO", ignoreCase = true)
        val dropVin = ex.command.replace(" ", "").uppercase() == "0902"
        ex.copy(raw = if (dropVin) "[consulta VIN omitida al anonimizar]" else raw)
    }
    return record.copy(
        adapterAddress = if (address.isBlank()) address else "BT-ANONIMIZADO",
        vin = if (vin.isNullOrBlank()) vin else "VIN-ANONIMIZADO",
        exchanges = exchanges,
    )
}

fun toJson(record: SessionRecord): String = Json.encode(sessionMap(record))

fun toCsv(record: SessionRecord): String {
    val sb = StringBuilder()
    sb.append("timestamp_ms,pid,nombre,valor,unidad,valido,sospechoso,raw\n")
    if (record.simulated) sb.append("# SIMULADO,no son lecturas del vehiculo\n")
    record.samples.forEach { s ->
        sb.append(s.atEpochMs).append(',')
        sb.append(s.pid).append(',')
        sb.append(csv(s.name)).append(',')
        sb.append(s.value?.toString() ?: "").append(',')
        sb.append(csv(s.unit ?: "")).append(',')
        sb.append(s.valid).append(',')
        sb.append(s.suspect).append(',')
        sb.append(csv(s.raw)).append('\n')
    }
    return sb.toString()
}

fun toTxt(record: SessionRecord): String {
    val sb = StringBuilder()
    if (record.simulated) sb.append("SIMULADO — NO SON LECTURAS DEL VEHÍCULO\n")
    sb.append("JAC Diagnostic Lab\n")
    sb.append("Sesión ${record.id}\n")
    sb.append("Inicio ${record.startedAtEpochMs}\n")
    sb.append("Teléfono ${record.androidDevice} / ${record.androidRelease}\n")
    sb.append("Vehículo ${record.vehicleNote}\n")
    sb.append("Conexión ${record.connectionType} estado ${record.connectionState}\n")
    sb.append("Adaptador ${record.adapterName} ${record.adapterIdentity}\n")
    sb.append("Protocolo ${record.protocolNumber} ${record.protocolText}\n")
    sb.append("ATRV ${record.adapterVoltage}\n")
    sb.append("VIN ${record.vin ?: "(no consultado)"}\n")
    sb.append("\nUna conexión exitosa no significa que el vehículo sea seguro para circular.\n")
    sb.append("ATZ solo reinicia el adaptador. Esta sesión no envió el servicio 04.\n\n")
    sb.append("== Transcripción ==\n")
    record.exchanges.forEach { ex ->
        sb.append("\n> ${ex.command}  [${ex.kind}] ${ex.elapsedMs} ms\n")
        sb.append(ex.raw.ifBlank { "(sin bytes)" }).append('\n')
        sb.append(ex.detail).append('\n')
    }
    sb.append("\n== DTC ==\n")
    if (record.dtcs.isEmpty()) sb.append("No se ejecutó la lectura de códigos.\n")
    record.dtcs.forEach { read ->
        sb.append("${read.category}: ${read.note}\n")
        if (read.codes.isEmpty()) sb.append("  (sin códigos decodificados)\n")
        read.codes.forEach { sb.append("  ${it.code} ECU=${it.ecu ?: "sin encabezado"} ${it.scopeNote}\n") }
    }
    sb.append("\n== Freeze frame ==\n")
    if (record.freeze.isEmpty()) sb.append("No consultado.\n")
    record.freeze.forEach { sb.append("${it.note} dtc=${it.dtc ?: "-"}\n${it.raw}\n") }
    sb.append("\n== Análisis ==\n")
    val analysis = record.analysis
    if (analysis == null) {
        sb.append("Sin análisis.\n")
    } else {
        sb.append(analysis.methodology).append("\n")
        analysis.confirmed.forEach { sb.append("Confirmado: $it\n") }
        analysis.hypotheses.forEach { h ->
            sb.append("\n${h.id} ${h.title} [${h.status}]\n")
            h.supporting.forEach { sb.append("  a favor: $it\n") }
            h.contrary.forEach { sb.append("  en contra: $it\n") }
            h.missing.forEach { sb.append("  falta: $it\n") }
            h.nextChecks.forEach { sb.append("  siguiente: $it\n") }
            if (h.needsJacScanner) sb.append("  requiere escáner JAC o inspección de taller\n")
        }
    }
    sb.append("\n== Errores ==\n")
    if (record.errors.isEmpty()) sb.append("Ninguno registrado.\n")
    record.errors.forEach { sb.append(it).append('\n') }
    return sb.toString()
}

private fun sessionMap(record: SessionRecord): Map<String, Any?> = linkedMapOf(
    "schema" to 1,
    "simulated" to record.simulated,
    "id" to record.id,
    "startedAtEpochMs" to record.startedAtEpochMs,
    "androidDevice" to record.androidDevice,
    "androidRelease" to record.androidRelease,
    "vehicleNote" to record.vehicleNote,
    "connectionType" to record.connectionType,
    "connectionState" to record.connectionState,
    "adapterName" to record.adapterName,
    "adapterAddress" to record.adapterAddress,
    "adapterIdentity" to record.adapterIdentity,
    "protocolText" to record.protocolText,
    "protocolNumber" to record.protocolNumber,
    "adapterVoltage" to record.adapterVoltage,
    "vin" to record.vin,
    "symptoms" to record.symptoms.map { it.name },
    "safety" to "Solo lectura. No se envió el servicio 04. Una conexión no autoriza a circular.",
    "exchanges" to record.exchanges.map { ex ->
        linkedMapOf(
            "atEpochMs" to ex.atEpochMs,
            "command" to ex.command,
            "kind" to ex.kind.name,
            "elapsedMs" to ex.elapsedMs,
            "promptSeen" to ex.promptSeen,
            "detail" to ex.detail,
            "raw" to ex.raw,
            "frames" to ex.frames.map { frame ->
                linkedMapOf(
                    "header" to frame.header,
                    "pciStripped" to frame.pciStripped,
                    "payload" to frame.payload.map { "%02X".format(it) },
                )
            },
        )
    },
    "dtcs" to record.dtcs.map { read ->
        linkedMapOf(
            "category" to read.category.name,
            "kind" to read.kind.name,
            "confirmedEmpty" to read.confirmedEmpty,
            "note" to read.note,
            "raw" to read.raw,
            "codes" to read.codes.map { code ->
                linkedMapOf(
                    "code" to code.code,
                    "hi" to code.hi,
                    "lo" to code.lo,
                    "scope" to code.scope.name,
                    "scopeNote" to code.scopeNote,
                    "ecu" to code.ecu,
                )
            },
        )
    },
    "freeze" to record.freeze.map { linkedMapOf("raw" to it.raw, "dtc" to it.dtc, "note" to it.note) },
    "samples" to record.samples.map { s ->
        linkedMapOf(
            "atEpochMs" to s.atEpochMs,
            "pid" to s.pid,
            "name" to s.name,
            "value" to s.value,
            "unit" to s.unit,
            "valid" to s.valid,
            "suspect" to s.suspect,
            "raw" to s.raw,
        )
    },
    "errors" to record.errors,
    "analysis" to record.analysis?.let { analysis ->
        linkedMapOf(
            "methodology" to analysis.methodology,
            "confirmed" to analysis.confirmed,
            "hypotheses" to analysis.hypotheses.map { h ->
                linkedMapOf(
                    "id" to h.id,
                    "title" to h.title,
                    "status" to h.status.name,
                    "supporting" to h.supporting,
                    "contrary" to h.contrary,
                    "missing" to h.missing,
                    "nextChecks" to h.nextChecks,
                    "needsJacScanner" to h.needsJacScanner,
                )
            },
        )
    },
)

private fun csv(value: String): String {
    val escaped = value.replace("\"", "\"\"")
    return if (escaped.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"$escaped\"" else escaped
}

object Json {
    fun encode(value: Any?): String = buildString { write(value, 0) }

    private fun StringBuilder.write(value: Any?, indent: Int) {
        when (value) {
            null -> append("null")
            is String -> append('"').append(escape(value)).append('"')
            is Boolean, is Int, is Long -> append(value.toString())
            is Double -> append(if (value % 1.0 == 0.0) value.toLong().toString() else value.toString())
            is Map<*, *> -> {
                append("{\n")
                value.entries.forEachIndexed { index, entry ->
                    append("  ".repeat(indent + 1))
                    append('"').append(escape(entry.key.toString())).append("\": ")
                    write(entry.value, indent + 1)
                    if (index != value.size - 1) append(',')
                    append('\n')
                }
                append("  ".repeat(indent)).append('}')
            }
            is Iterable<*> -> {
                val list = value.toList()
                if (list.isEmpty()) {
                    append("[]")
                } else {
                    append("[\n")
                    list.forEachIndexed { index, item ->
                        append("  ".repeat(indent + 1))
                        write(item, indent + 1)
                        if (index != list.lastIndex) append(',')
                        append('\n')
                    }
                    append("  ".repeat(indent)).append(']')
                }
            }
            else -> append('"').append(escape(value.toString())).append('"')
        }
    }

    private fun escape(text: String): String = buildString {
        text.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }
}

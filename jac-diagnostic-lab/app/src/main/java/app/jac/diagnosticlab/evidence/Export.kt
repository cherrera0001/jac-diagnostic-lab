package app.jac.diagnosticlab.evidence

import app.jac.diagnosticlab.analysis.AnalysisReport
import app.jac.diagnosticlab.analysis.Symptom
import app.jac.diagnosticlab.elm.Exchange
import app.jac.diagnosticlab.obd.DtcReadResult
import app.jac.diagnosticlab.security.SecurityGuide
import app.jac.diagnosticlab.obd.FaultIdentification
import app.jac.diagnosticlab.obd.FreezeNote
import app.jac.diagnosticlab.obd.PidReading
import app.jac.diagnosticlab.obd.SessionTimeMetrics

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

data class ReadingFacts(
    val sessionId: String,
    val adapterId: String,
    val protocolNumber: String,
    val protocolText: String,
    val voltageText: String,
    val storedCodes: List<String>,
    val ecuTalking: Boolean,
    val bleValidated: Boolean = false,
    val declaredModel: String = "",
    val declaredYear: String = "",
    val powerLossOnBrake: Boolean = false,
    val freezeDtc: String? = null,
    val freezeRaw: String? = null,
    val supportedPids: Set<Int> = emptySet(),
    val samplePairs: List<Pair<Int, Double?>> = emptyList(),
    val timeMetrics: SessionTimeMetrics? = null,
)

data class ReadingReportDocument(
    val title: String,
    val lines: List<String>,
    val bodySha256: String,
)

data class StreetStep(
    val number: Int,
    val role: String,
    val title: String,
    val detail: String,
)

object StreetGuide {
    val steps: List<StreetStep> = listOf(
        StreetStep(1, "Tú", "Deja la camioneta quieta", "Freno de mano puesto. Contacto en ON. No la muevas y no pises el acelerador para probar."),
        StreetStep(2, "Tú", "Enchufa el aparato", "El aparato pequeño va en el enchufe de diagnóstico, debajo del volante, hasta el fondo."),
        StreetStep(3, "Tú", "Cierra las otras aplicaciones", "Si hay otra aplicación de diagnóstico abierta, ciérrala. El aparato atiende a una sola."),
        StreetStep(4, "El teléfono", "Permite el Bluetooth", "Sin ese permiso el teléfono no puede hablar con el aparato."),
        StreetStep(5, "La lectora", "Lee y no cierres", "Deja el teléfono junto al aparato, con esta pantalla abierta. Tarda cerca de un minuto."),
        StreetStep(6, "La lectora", "Lee el resultado", "Dice en palabras qué contestó la computadora. No dice qué pieza cambiar."),
        StreetStep(7, "El informe", "Guarda el PDF", "Queda en Descargas, carpeta JAC-lectura, con un archivo de huella al lado."),
        StreetStep(8, "El taller", "Saca el aparato y lleva el PDF", "En un servicio JAC piden leer los mismos números sin borrarlos, y medir el pedal de freno y el del acelerador con la camioneta quieta."),
    )

    fun current(doneThrough: Int, busy: Boolean, hasReading: Boolean, pdfSaved: Boolean): Int {
        if (busy) return 5
        if (doneThrough >= 8) return 8
        if (pdfSaved) return 8
        if (hasReading && doneThrough < 6) return 6
        return (doneThrough + 1).coerceIn(1, 8)
    }
}

fun sha256Hex(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

fun payloadHasContent(
    format: String,
    exchanges: Int,
    codes: Int,
    confirmedAnswer: Boolean,
    errors: Int,
    samples: Int,
    adapterId: String,
): String? {
    val talked = exchanges > 0 || codes > 0 || confirmedAnswer || errors > 0 || adapterId.isNotBlank()
    return when (format.trim().lowercase()) {
        "csv" -> if (samples > 0) null else "No hay sensores en esta sesión. No se crea el CSV."
        "json", "txt" -> if (talked) null else "No hay respuestas ni errores. No se crea el archivo."
        "pdf" -> if (talked) null else "Todavía no hay una lectura que interpretar. No se crea el PDF."
        else -> "Formato desconocido. No se crea el archivo."
    }
}

fun interpretationLines(
    codes: List<String>,
    model: String,
    year: String,
    powerLossOnBrake: Boolean = false,
): List<String> {
    val unique = codes.map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
    val vehicle = when {
        model.isNotBlank() && year.isNotBlank() -> "${model.trim()}, año ${year.trim()}"
        model.isNotBlank() -> model.trim()
        year.isNotBlank() -> "año ${year.trim()}"
        else -> ""
    }
    return buildList {
        add("Los números salen de la camioneta. Las palabras no.")
        add("Esta aplicación no se conecta a internet para buscarlos. Usa una ficha local: la definición pública de esos números, y lo que se pudo abrir de JAC el 11-11-2026. Si el número no está en la ficha, no se inventa la frase.")
        if (vehicle.isNotBlank()) {
            add("Declaraste $vehicle. Ese dato tampoco salió de la computadora. Las fuentes abiertas revisadas no traen el texto de estos números para esa unidad.")
        }
        if (powerLossOnBrake) {
            add("Relato del conductor: al manejar se corta la potencia o la energía al frenar, y al detener aparecen fallos. Eso es un riesgo de seguridad física. No se prueba en la calle. No se borra. Se lleva al servicio JAC con este papel.")
            add("Esta aplicación no se hace responsable de la circulación. Mientras ese corte ocurra, no uses la camioneta en la vía.")
        }
        if (unique.isEmpty()) {
            add("En esta copia no hay avisos guardados que interpretar. Eso no significa que la camioneta esté bien.")
            return@buildList
        }
        val pair = "P0579" in unique && "P2299" in unique
        if (pair) {
            add("La computadora guardó dos avisos a la vez: P0579 y P2299.")
            add("P2299, en la definición pública, quiere decir que el freno y el acelerador quedaron en desacuerdo. Vio el freno pisado mientras el acelerador también estaba abierto, o una de esas señales no volvió a su sitio.")
            add("P0579, en esa misma definición pública, apunta a la entrada del crucero. Muchas veces esa entrada es el interruptor que el freno corta al pisarlo.")
            add("Leídos juntos, la frase que encaja es esta: al frenar, la señal del freno y la del acelerador no coinciden, y el corte del crucero entra en la misma discusión. Encaja con una luz EPC y con una baja de potencia al frenar. No demuestra que esa sea la causa. Tampoco demuestra un ataque informático.")
            add("No dice qué pieza cambiar. No pide cambiar el pedal, el interruptor ni el acelerador.")
            add("JAC, en lo que se pudo abrir, no publica ese texto para la T8. Un PDF del dominio JAC habla de otros números de pedal, P2123, P2127, P2128, P3004 y P3005. No son los que contestó esta camioneta.")
            add("El manual diésel de la N56 sí define P2299 y cita 750 mV en el acelerador. Es otro modelo. Ese valor no se usa aquí.")
            add("Si el escáner de la marca muestra otro número, o no muestra estos, manda ese escáner. Hay que pedirlo sin borrar.")
        }
        unique.filter { it != "P0579" && it != "P2299" || !pair }.forEach { code ->
            if (pair && (code == "P0579" || code == "P2299")) return@forEach
            val sentence = when (code) {
                "P0579" -> "P0579, en la definición pública, apunta a la entrada del crucero. Muchas veces es el interruptor que el freno corta. No dice qué pieza cambiar. JAC no publicó ese texto para esta T8."
                "P2299" -> "P2299, en la definición pública, quiere decir que el freno y el acelerador quedaron en desacuerdo. No dice qué pieza cambiar. El valor de la N56 no se usa."
                else -> "$code. Número anotado por la computadora. No está en la ficha local. No traduzco la pieza y no invento el significado. El texto del escáner JAC manda."
            }
            add(sentence)
        }
        add("En el taller, con la camioneta quieta y sin borrar: que lean estos mismos números y digan el texto de su escáner; que midan el interruptor del freno suelto y pisado; que midan las dos señales del acelerador sin pisar y pisando. No probar el corte de potencia en la calle.")
    }
}

fun readingReport(facts: ReadingFacts): ReadingReportDocument {
    val codes = facts.storedCodes.map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
    val body = buildList {
        add("Documento local. No es una certificación y no autoriza a circular.")
        add("No indica qué pieza cambiar. No borra los avisos.")
        add("")
        add("1. Ficha de falla")
        val dossier = FaultIdentification.dossier(
            storedCodes = codes,
            freezeDtc = facts.freezeDtc,
            freezeRaw = facts.freezeRaw,
            supportedPids = facts.supportedPids,
            samples = facts.samplePairs,
            powerLossOnBrake = facts.powerLossOnBrake,
            model = facts.declaredModel,
            year = facts.declaredYear,
            time = facts.timeMetrics,
        )
        add(dossier.title)
        dossier.lines.forEach { add(it) }
        add("")
        add("2. Qué significa")
        interpretationLines(codes, facts.declaredModel, facts.declaredYear, facts.powerLossOnBrake).forEach { add(it) }
        add("")
        add("3. Qué contestó la computadora")
        add("Sesión ${facts.sessionId.ifBlank { "sin número" }}.")
        val declared = listOf(facts.declaredModel.trim(), facts.declaredYear.trim()).filter { it.isNotBlank() }
        if (declared.isNotEmpty()) {
            val spoken = if (facts.declaredModel.isNotBlank() && facts.declaredYear.isNotBlank()) {
                "${facts.declaredModel.trim()}, año ${facts.declaredYear.trim()}"
            } else {
                declared.joinToString(", ")
            }
            add("Vehículo declarado por el dueño: $spoken. No salió de la computadora.")
        }
        if (facts.ecuTalking) add("La computadora del motor contestó.") else add("En esta copia no quedó constancia de que la computadora contestara.")
        if (facts.adapterId.isNotBlank()) add("El aparato se identificó como «${facts.adapterId}». Ese texto se puede copiar. No prueba que el aparato sea original.")
        val protocol = listOf(facts.protocolNumber, facts.protocolText).filter { it.isNotBlank() }.joinToString(" ")
        if (protocol.isNotBlank()) add("Protocolo informado: $protocol.")
        if (facts.voltageText.isNotBlank()) add("El aparato estimó ${facts.voltageText}. Eso no es una medición de batería. Hace falta un multímetro.")
        add("Avisos guardados, sin borrar: ${if (codes.isEmpty()) "ninguno en esta copia" else codes.joinToString(", ")}.")
        add("La luz EPC del tablero no es la luz genérica de falla. Que la luz genérica esté apagada no explica el EPC.")
        add("")
        add("4. Qué hacer con el papel")
        add("1. Sacar el aparato del enchufe cuando no se esté leyendo.")
        add("2. Llevar este PDF al servicio JAC.")
        add("3. Pedir que lean los mismos números y el cuadro congelado, sin borrarlos.")
        add("4. Pedir la medición del pedal de freno y de las dos señales del acelerador, con la camioneta quieta.")
        add("5. No probar el corte de potencia en la calle.")
        add("6. No cambiar piezas por esta hoja.")
        add("")
        add("5. Quién hace qué (dueño)")
        StreetGuide.steps.forEach { step ->
            add("${step.number}. ${step.role}: ${step.title}. ${step.detail}")
        }
        add("El revisor no es la lectora. Su trabajo es impedir que estos números se cierren como causa o como ataque.")
        add("")
        add("6. Ciclo de taller (mecánico)")
        add("Si se hace un ajuste: captura ANTES en Monitor, trabaja, captura DESPUÉS, compara DTC y medidores, nuevo PDF.")
        WorkshopGuide.steps.forEach { step ->
            add("${step.number}. ${step.who}: ${step.title}. ${step.detail} Control: ${step.control}")
        }
        add("Orden del monitor: " + WorkshopGuide.monitorOrder.joinToString(" → ") { it.shortLabel })
        add("")
        add("7. Validación de seguridad")
        add("No hay un porcentaje. Comprobado significa que esta aplicación lo revisó. No significa que la camioneta esté segura.")
        SecurityGuide.checks(facts.adapterId, facts.ecuTalking, codes, facts.bleValidated, facts.declaredModel, facts.declaredYear).forEach { check ->
            add("${check.number}. ${check.title}. ${check.result}. ${check.detail}")
        }
    }
    val hash = sha256Hex(body.joinToString("\n").toByteArray(Charsets.UTF_8))
    val lines = body + listOf("", "Huella de este texto, SHA-256: $hash", "La huella del archivo PDF va en el archivo .sha256 del mismo nombre. No va dentro del PDF, porque al escribirla el archivo cambiaría.")
    val title = if (codes.isEmpty()) "Lectura sin avisos guardados" else "Hay avisos guardados. No cambies piezas por esta hoja."
    return ReadingReportDocument(title, lines, hash)
}

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

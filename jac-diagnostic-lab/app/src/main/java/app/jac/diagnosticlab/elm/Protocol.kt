package app.jac.diagnosticlab.elm

enum class ResponseKind {
    RECOGNIZED,
    QUESTION_MARK,
    NO_DATA,
    UNABLE_TO_CONNECT,
    BUS_INIT_ERROR,
    CAN_ERROR,
    STOPPED,
    TIMEOUT,
    PARTIAL,
    MALFORMED,
    OBD_DATA,
    UNIDENTIFIED_ECU,
    DISCONNECTED,
    BLOCKED,
}

data class TransportResult(
    val raw: String,
    val promptSeen: Boolean,
    val timedOut: Boolean,
    val disconnected: Boolean,
    val error: String? = null,
)

data class ObdFrame(
    val header: String?,
    val payload: List<Int>,
    val pciStripped: Boolean,
    val rawLine: String,
)

data class ParsedResponse(
    val command: String,
    val raw: String,
    val kind: ResponseKind,
    val lines: List<String>,
    val frames: List<ObdFrame>,
    val detail: String,
)

data class Exchange(
    val atEpochMs: Long,
    val command: String,
    val raw: String,
    val kind: ResponseKind,
    val detail: String,
    val promptSeen: Boolean,
    val elapsedMs: Long,
    val frames: List<ObdFrame> = emptyList(),
)

class ReadOnlyViolation(message: String) : IllegalArgumentException(message)

object ReadOnlyGate {
    private val at = Regex("^AT(Z|I|DP|DPN|RV|E0|L0|S0|H1|SP[0-9])$")
    private val obd = Regex("^(01[0-9A-F]{2}|02[0-9A-F]{4}|03|07|0A|0900|0902)$")

    fun check(command: String) {
        val raw = command.uppercase()
        if (raw.contains(';') || raw.contains('\r') || raw.contains('\n')) {
            throw ReadOnlyViolation("Comando compuesto bloqueado. No se envió al adaptador.")
        }
        val c = raw.replace(" ", "")
        if (c == "04" || c.startsWith("04")) {
            throw ReadOnlyViolation("Servicio 04 bloqueado. Esta aplicación no borra códigos.")
        }
        if (c.startsWith("ATSH") || c.startsWith("ATMA") || c.startsWith("ATCRA") ||
            c.startsWith("ATBI") || c.startsWith("ATFI") || c.startsWith("ATAL")
        ) {
            throw ReadOnlyViolation("Comando de cabecera, monitor o control bloqueado. No se envió.")
        }
        if (!at.matches(c) && !obd.matches(c)) {
            throw ReadOnlyViolation("Comando fuera de la lista de solo lectura: $c")
        }
    }
}

class ResponseAssembler {
    private val buf = StringBuilder()

    fun append(chunk: String): String? {
        buf.append(chunk)
        val text = buf.toString()
        val idx = text.lastIndexOf('>')
        if (idx < 0) return null
        val message = text.substring(0, idx)
        buf.clear()
        if (idx + 1 < text.length) buf.append(text.substring(idx + 1))
        return message
    }

    fun partial(): String = buf.toString()

    fun clear() {
        buf.clear()
    }
}

object ElmParser {
    private val hexToken = Regex("[0-9A-F]+")

    fun parse(
        command: String,
        raw: String,
        promptSeen: Boolean,
        timedOut: Boolean,
        disconnected: Boolean,
    ): ParsedResponse {
        val lines = splitLines(raw)
        val body = stripEcho(command, lines)
        val error = detectError(body)
        if (error != null) {
            return ParsedResponse(command, raw, error, body, emptyList(), detailFor(error))
        }
        if (disconnected) {
            return ParsedResponse(
                command, raw, ResponseKind.DISCONNECTED, body, emptyList(),
                "El canal se cerró antes de completar la respuesta. Los bytes recibidos se conservan y no se interpretan como códigos.",
            )
        }
        if (!promptSeen || timedOut) {
            val kind = if (body.isEmpty() && raw.isBlank()) ResponseKind.TIMEOUT else ResponseKind.PARTIAL
            val detail = if (kind == ResponseKind.TIMEOUT) {
                "No llegó el prompt '>' del ELM327 dentro del tiempo."
            } else {
                "Llegaron bytes, pero la respuesta no terminó en '>'. No se decodifica."
            }
            return ParsedResponse(command, raw, kind, body, emptyList(), detail)
        }
        if (isAt(command)) {
            if (body.isEmpty()) {
                return ParsedResponse(command, raw, ResponseKind.MALFORMED, body, emptyList(), "El adaptador cerró '>' sin texto.")
            }
            return ParsedResponse(command, raw, ResponseKind.RECOGNIZED, body, emptyList(), "Comando AT aceptado por el adaptador.")
        }
        val frames = assemble(normalizeIndexedDump(body))
        if (frames.isEmpty()) {
            return ParsedResponse(
                command, raw, ResponseKind.MALFORMED, body, emptyList(),
                "La respuesta no es hexadecimal interpretable. No se inventaron códigos.",
            )
        }
        val expected = expectedService(command)
        val mode = expected?.minus(0x40)
        val matches = frames.any { frame ->
            val p = frame.payload
            p.isNotEmpty() && (p[0] == expected || (p[0] == 0x7F && mode != null && p.getOrNull(1) == mode))
        }
        if (expected != null && !matches) {
            return ParsedResponse(
                command, raw, ResponseKind.UNIDENTIFIED_ECU, body, frames,
                "Hay bytes hex, pero no corresponden al servicio pedido. No se inventaron códigos.",
            )
        }
        return ParsedResponse(command, raw, ResponseKind.OBD_DATA, body, frames, "Respuesta OBD con servicio esperado o negativo 7F.")
    }

    private fun detailFor(kind: ResponseKind): String = when (kind) {
        ResponseKind.QUESTION_MARK -> "El ELM327 no reconoció el comando."
        ResponseKind.NO_DATA -> "NO DATA: no hubo respuesta de la ECU. No demuestra ausencia de códigos ni de sensores."
        ResponseKind.UNABLE_TO_CONNECT -> "El adaptador no pudo iniciar el bus (UNABLE TO CONNECT)."
        ResponseKind.BUS_INIT_ERROR -> "Falló la inicialización del bus (BUS INIT ERROR)."
        ResponseKind.CAN_ERROR -> "El adaptador reportó CAN ERROR."
        ResponseKind.STOPPED -> "El adaptador detuvo la consulta (STOPPED)."
        else -> kind.name
    }

    private fun splitLines(raw: String): List<String> =
        raw.replace(">", "")
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * Algunos clones ELM parten un volcado largo en "014" + "0: …" / "1: …" / "2: …".
     * Eso no es PCI ISO-TP: hay que concatenar el hex antes de clasificar tramas.
     */
    private fun normalizeIndexedDump(lines: List<String>): List<String> {
        val indexed = Regex("^([0-9A-F]+):\\s*(.*)$", RegexOption.IGNORE_CASE)
        val bareLength = Regex("^[0-9A-F]{1,3}$", RegexOption.IGNORE_CASE)
        val out = mutableListOf<String>()
        val chunks = mutableListOf<String>()
        fun flush() {
            if (chunks.isNotEmpty()) {
                out += chunks.joinToString(" ")
                chunks.clear()
            }
        }
        for (line in lines) {
            val match = indexed.matchEntire(line)
            when {
                match != null -> {
                    val rest = match.groupValues[2].trim()
                    if (rest.isNotEmpty()) chunks += rest
                }
                bareLength.matches(line) && chunks.isEmpty() -> Unit
                else -> {
                    flush()
                    out += line
                }
            }
        }
        flush()
        return out
    }

    private fun stripEcho(command: String, lines: List<String>): List<String> {
        if (lines.isEmpty()) return lines
        val cmd = command.uppercase().replace(" ", "")
        val first = lines.first().uppercase().replace(" ", "")
        return if (first == cmd) lines.drop(1) else lines
    }

    private fun detectError(lines: List<String>): ResponseKind? {
        val text = lines.joinToString("\n").uppercase()
        if (text.contains("CAN ERROR")) return ResponseKind.CAN_ERROR
        if (text.contains("BUS INIT") && text.contains("ERROR")) return ResponseKind.BUS_INIT_ERROR
        if (lines.any { it.equals("UNABLE TO CONNECT", true) }) return ResponseKind.UNABLE_TO_CONNECT
        if (lines.any { it.equals("NO DATA", true) }) return ResponseKind.NO_DATA
        if (lines.any { it.equals("STOPPED", true) }) return ResponseKind.STOPPED
        if (lines.any { it.trim() == "?" }) return ResponseKind.QUESTION_MARK
        return null
    }

    private fun isAt(command: String) = command.trim().uppercase().startsWith("AT")

    private fun expectedService(command: String): Int? {
        val c = command.uppercase().replace(" ", "")
        if (c.startsWith("AT") || c.length < 2) return null
        return (c.take(2).toIntOrNull(16) ?: return null) + 0x40
    }

    private enum class LineKind { SINGLE, FIRST, CONSEC, RAW }

    private data class LineParts(
        val header: String?,
        val bytes: List<Int>,
        val payload: List<Int>,
        val kind: LineKind,
        val expectedLen: Int,
        val pciStripped: Boolean,
        val raw: String,
    )

    private fun assemble(lines: List<String>): List<ObdFrame> {
        data class Open(val header: String?, val expected: Int, val data: MutableList<Int>, val raw: String, val pci: Boolean)
        val done = mutableListOf<ObdFrame>()
        var open: Open? = null
        fun close(frame: Open) {
            done += ObdFrame(frame.header, frame.data.toList(), frame.pci, frame.raw)
        }
        for (line in lines) {
            if (line.uppercase().contains("SEARCHING")) continue
            val part = parseLine(line) ?: continue
            when (part.kind) {
                LineKind.FIRST -> {
                    open?.let { close(it) }
                    val bucket = Open(part.header, part.expectedLen, part.payload.toMutableList(), part.raw, true)
                    if (bucket.data.size >= bucket.expected && bucket.expected > 0) {
                        close(bucket)
                        open = null
                    } else {
                        open = bucket
                    }
                }
                LineKind.CONSEC -> {
                    val current = open
                    if (current != null && (part.header == null || part.header == current.header)) {
                        val need = (current.expected - current.data.size).coerceAtLeast(0)
                        current.data += part.payload.take(need)
                        if (current.data.size >= current.expected) {
                            close(current)
                            open = null
                        }
                    }
                }
                LineKind.SINGLE -> {
                    open?.let { close(it) }
                    open = null
                    done += ObdFrame(part.header, part.payload, part.pciStripped, part.raw)
                }
                LineKind.RAW -> {
                    val current = open
                    if (current != null && part.header == null && !isService(part.bytes.firstOrNull() ?: -1)) {
                        current.data += part.bytes
                    } else if (isService(part.bytes.firstOrNull() ?: -1) || part.bytes.firstOrNull() == 0x7F) {
                        open?.let { close(it) }
                        open = null
                        done += ObdFrame(part.header, part.bytes, false, part.raw)
                    }
                }
            }
        }
        open?.let { close(it) }
        return done
    }

    private fun parseLine(line: String): LineParts? {
        val trimmed = line.trim().uppercase()
        // ELM a veces numera ISO-TP como "0: 49 02 …" / "1: …". El índice no es un byte OBD.
        val withoutIndex = trimmed.replace(Regex("^[0-9A-F]+:\\s*"), "")
        if (withoutIndex != trimmed && withoutIndex.isBlank()) return null
        val tokens = withoutIndex.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        // Línea sola con longitud decimal/hex corta ("014"): no es trama; se ignora.
        if (tokens.size == 1 && tokens[0].length in 1..3 && isHex(tokens[0])) return null
        if (tokens.size >= 2 && tokens[0].length == 3 && isHex(tokens[0]) && tokens.drop(1).all { it.length == 2 && isHex(it) }) {
            return classify(tokens[0], tokens.drop(1).map { it.toInt(16) }, trimmed)
        }
        if (tokens.all { it.length == 2 && isHex(it) }) {
            return classify(null, tokens.map { it.toInt(16) }, trimmed)
        }
        val compact = tokens.joinToString("")
        if (!compact.matches(Regex("[0-9A-F]+"))) return null
        if (compact.length % 2 == 1 && compact.length >= 5) {
            val rest = hexBytes(compact.substring(3)) ?: return null
            return classify(compact.substring(0, 3), rest, trimmed)
        }
        if (compact.length % 2 == 0 && compact.length >= 2) {
            val bytes = hexBytes(compact) ?: return null
            if (compact.length > 10) {
                val header = compact.substring(0, 8)
                val rest = hexBytes(compact.substring(8)) ?: emptyList()
                val as29 = classify(header, rest, trimmed)
                val plain = classify(null, bytes, trimmed)
                val headerLooksCan = header.startsWith("18DA") || header.startsWith("18DB")
                if (headerLooksCan && (as29.kind == LineKind.SINGLE || as29.kind == LineKind.FIRST)) return as29
                if (plain.kind == LineKind.RAW && as29.kind != LineKind.RAW) return as29
            }
            return classify(null, bytes, trimmed)
        }
        return null
    }

    private fun classify(header: String?, bytes: List<Int>, raw: String): LineParts {
        if (bytes.isEmpty()) return LineParts(header, bytes, emptyList(), LineKind.RAW, 0, false, raw)
        val b0 = bytes[0]
        if (b0 in 0x01..0x0F && bytes.size >= 2) {
            val data = bytes.drop(1)
            if (data.size >= b0 && (isService(data[0]) || data[0] == 0x7F)) {
                return LineParts(header, bytes, data.take(b0), LineKind.SINGLE, b0, true, raw)
            }
        }
        if ((b0 and 0xF0) == 0x10 && bytes.size >= 3) {
            val len = ((b0 and 0x0F) shl 8) or bytes[1]
            return LineParts(header, bytes, bytes.drop(2), LineKind.FIRST, len, true, raw)
        }
        if ((b0 and 0xF0) == 0x20 && bytes.size >= 2) {
            return LineParts(header, bytes, bytes.drop(1), LineKind.CONSEC, 0, true, raw)
        }
        if (isService(b0) || b0 == 0x7F) {
            return LineParts(header, bytes, bytes, LineKind.SINGLE, bytes.size, false, raw)
        }
        return LineParts(header, bytes, bytes, LineKind.RAW, 0, false, raw)
    }

    private fun isService(b: Int) = b in 0x41..0x4A

    private fun isHex(token: String) = hexToken.matches(token)

    private fun hexBytes(compact: String): List<Int>? {
        if (compact.length % 2 != 0) return null
        return compact.chunked(2).map { it.toInt(16) }
    }
}

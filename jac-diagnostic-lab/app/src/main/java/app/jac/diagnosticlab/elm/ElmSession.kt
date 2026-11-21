package app.jac.diagnosticlab.elm

import app.jac.diagnosticlab.obd.DtcCategory
import app.jac.diagnosticlab.obd.DtcDecoder
import app.jac.diagnosticlab.obd.DtcReadResult
import app.jac.diagnosticlab.obd.PidDecoder
import app.jac.diagnosticlab.obd.PidReading
import app.jac.diagnosticlab.obd.canFromProtocol
import app.jac.diagnosticlab.obd.parseAdapterVoltage
import kotlinx.coroutines.delay

interface ObdTransport {
    val name: String
    val kind: TransportKind
    val connected: Boolean
    suspend fun connect()
    suspend fun disconnect()
    suspend fun transact(command: String, timeoutMs: Long): TransportResult
}

data class InitReport(
    val exchanges: List<Exchange>,
    val adapterIdentity: String,
    val protocolText: String,
    val protocolNumber: String,
    val voltageText: String,
    val voltageValue: Double?,
    val ecuTalking: Boolean,
    val supportedPids: Set<Int>,
    val stoppedBecause: String?,
)

class ElmSession(
    private val transport: ObdTransport,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = {},
    private val onExchange: (Exchange) -> Unit = {},
) {
    val exchanges = mutableListOf<Exchange>()

    suspend fun exchange(command: String, timeoutMs: Long): Exchange {
        val normalized = command.trim().uppercase()
        try {
            ReadOnlyGate.check(normalized)
        } catch (error: ReadOnlyViolation) {
            val blocked = Exchange(clock(), normalized, "", ResponseKind.BLOCKED, error.message ?: "bloqueado", false, 0)
            exchanges += blocked
            onExchange(blocked)
            log("BLOQUEADO $normalized")
            return blocked
        }
        val started = clock()
        log("TX $normalized")
        val result = try {
            transport.transact(normalized, timeoutMs)
        } catch (error: ReadOnlyViolation) {
            val blocked = Exchange(started, normalized, "", ResponseKind.BLOCKED, error.message ?: "bloqueado", false, clock() - started)
            exchanges += blocked
            onExchange(blocked)
            return blocked
        } catch (error: Exception) {
            TransportResult("", promptSeen = false, timedOut = false, disconnected = true, error = error.message)
        }
        log("RX ${result.raw}")
        val parsed = ElmParser.parse(normalized, result.raw, result.promptSeen, result.timedOut, result.disconnected)
        val exchange = Exchange(
            atEpochMs = started,
            command = normalized,
            raw = result.raw,
            kind = parsed.kind,
            detail = result.error?.let { "${parsed.detail} ($it)" } ?: parsed.detail,
            promptSeen = result.promptSeen,
            elapsedMs = clock() - started,
            frames = parsed.frames,
        )
        exchanges += exchange
        onExchange(exchange)
        return exchange
    }

    suspend fun initialize(protocol: String = "0"): InitReport {
        val digit = protocol.trim().takeIf { it.length == 1 && it[0] in '0'..'9' } ?: "0"
        val plan = listOf(
            "ATZ" to 7000L,
            "ATE0" to 2500L,
            "ATL0" to 2500L,
            "ATS0" to 2500L,
            "ATH1" to 2500L,
            "ATI" to 2500L,
            "ATSP$digit" to 2500L,
            "ATDP" to 2500L,
            "ATDPN" to 2500L,
            "ATRV" to 2500L,
            "0100" to 12000L,
        )
        var stopped: String? = null
        for ((command, timeout) in plan) {
            val result = exchange(command, timeout)
            if (result.kind == ResponseKind.DISCONNECTED || result.kind == ResponseKind.BLOCKED) {
                stopped = result.detail
                break
            }
            if (command == "ATZ" && result.kind != ResponseKind.RECOGNIZED) {
                stopped = "ATZ no devolvió una identificación utilizable. ${result.detail}"
                break
            }
            if (command == "ATZ") delay(300)
        }
        // La ECU puede tardar en despertar (arranque, contacto recién en ON): reintentar 0100.
        if (stopped == null) {
            var tries = 0
            while (tries < 3 && exchanges.lastOrNull { it.command == "0100" }?.kind != ResponseKind.OBD_DATA) {
                tries++
                log("0100 sin datos de la ECU; reintento $tries/3 en 2 s")
                delay(2000)
                val again = exchange("0100", 12000)
                if (again.kind == ResponseKind.DISCONNECTED) break
            }
            exchange("ATDP", 2500)
            exchange("ATDPN", 2500)
        }
        return report(stopped)
    }

    fun report(stopped: String? = null): InitReport {
        val identity = exchanges.lastOrNull { it.command == "ATI" && it.kind == ResponseKind.RECOGNIZED }?.raw?.trim()
            ?: exchanges.lastOrNull { it.command == "ATZ" && it.kind == ResponseKind.RECOGNIZED }?.raw?.trim()
            ?: ""
        val protocolText = exchanges.lastOrNull { it.command == "ATDP" && it.kind == ResponseKind.RECOGNIZED }?.raw?.trim().orEmpty()
        val protocolNumber = exchanges.lastOrNull { it.command == "ATDPN" && it.kind == ResponseKind.RECOGNIZED }?.raw?.trim().orEmpty()
        val voltageRaw = exchanges.lastOrNull { it.command == "ATRV" && it.kind == ResponseKind.RECOGNIZED }?.raw.orEmpty()
        val pid = exchanges.lastOrNull { it.command == "0100" }
        val supported = if (pid != null && pid.kind == ResponseKind.OBD_DATA) {
            val data = pid.frames.firstOrNull { it.payload.size >= 6 && it.payload[0] == 0x41 && it.payload[1] == 0x00 }?.payload?.drop(2)
            if (data != null) PidDecoder.supported(0x00, data) else emptySet()
        } else {
            emptySet()
        }
        return InitReport(
            exchanges = exchanges.toList(),
            adapterIdentity = identity.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
            protocolText = protocolText.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
            protocolNumber = protocolNumber.lineSequence().firstOrNull { it.isNotBlank() && !it.equals("OK", true) }.orEmpty(),
            voltageText = voltageRaw.lineSequence().firstOrNull { it.contains('V', true) || it.any(Char::isDigit) }.orEmpty(),
            voltageValue = parseAdapterVoltage(voltageRaw),
            ecuTalking = pid?.kind == ResponseKind.OBD_DATA,
            supportedPids = supported,
            stoppedBecause = stopped,
        )
    }

    suspend fun readDtc(category: DtcCategory): DtcReadResult {
        val command = when (category) {
            DtcCategory.STORED -> "03"
            DtcCategory.PENDING -> "07"
            DtcCategory.PERMANENT -> "0A"
        }
        val result = exchange(command, 10000)
        val protocol = report().protocolNumber
        return DtcDecoder.decode(category, result, canFromProtocol(protocol))
    }

    /** Mode 04. Solo con precondiciones verificadas por ClearDtcGate; la autorización se retira siempre. */
    suspend fun clearDtcs(): Exchange {
        app.jac.diagnosticlab.security.ClearDtcGate.arm()
        return try { exchange("04", 8000) } finally { app.jac.diagnosticlab.security.ClearDtcGate.disarm() }
    }

    suspend fun readPid(pid: Int): PidReading {
        val command = "01%02X".format(pid)
        return PidDecoder.decode(pid, exchange(command, 4000))
    }

    /**
     * Algunos clones ELM327 v2.1 cortan respuestas ISO-TP tras 4 tramas consecutivas.
     * Fija un FC estándar (BS=0, STmin=0) dirigido a 7E0. Devuelve true si el adaptador lo aceptó.
     */
    suspend fun applyStandardFlowControl(): Boolean {
        val a = exchange("ATFCSH7E0", 2500)
        val b = exchange("ATFCSD300000", 2500)
        val c = exchange("ATFCSM1", 2500)
        return listOf(a, b, c).all { it.kind == ResponseKind.RECOGNIZED && !it.raw.contains("?") }
    }

    /** Si la ECU dejó de aceptar multi-PID, no se vuelve a intentar en esta sesión. */
    var multiPidWorks: Boolean? = null
        private set

    /**
     * Lee varios PIDs Mode 01. En CAN agrupa hasta 6 por petición (J1979) y separa la
     * respuesta por largo conocido; lo que no vuelva se pide de a uno. Devuelve los bytes
     * de datos por PID (sin `41 PID`).
     */
    suspend fun readPidsRaw(pids: List<Int>, can: Boolean): Map<Int, List<Int>> {
        val out = linkedMapOf<Int, List<Int>>()
        val groupable = pids.filter { app.jac.diagnosticlab.obd.PidCatalog.size(it) != null }
        if (can && multiPidWorks != false && groupable.size > 1) {
            for (chunk in groupable.chunked(6)) {
                val cmd = "01" + chunk.joinToString("") { "%02X".format(it) }
                val ex = exchange(cmd, 4000)
                if (ex.kind != ResponseKind.OBD_DATA) continue
                val parts = ex.frames.filter { it.payload.firstOrNull() == 0x41 }
                    .flatMap { app.jac.diagnosticlab.obd.PidCatalog.splitMulti(it.payload, chunk).entries }
                parts.forEach { (k, v) -> out[k] = v }
                if (multiPidWorks == null) multiPidWorks = parts.size >= 2
            }
        }
        for (pid in pids) {
            if (pid in out) continue
            val ex = exchange("01%02X".format(pid), 4000)
            val payload = ex.frames.firstOrNull { it.payload.size >= 2 && it.payload[0] == 0x41 && it.payload[1] == pid }?.payload
            if (ex.kind == ResponseKind.OBD_DATA && payload != null) out[pid] = payload.drop(2)
        }
        return out
    }
}

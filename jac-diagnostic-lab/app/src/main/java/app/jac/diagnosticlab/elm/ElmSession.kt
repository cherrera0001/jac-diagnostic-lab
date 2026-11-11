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
        if (stopped == null) {
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

    suspend fun readPid(pid: Int): PidReading {
        val command = "01%02X".format(pid)
        return PidDecoder.decode(pid, exchange(command, 4000))
    }
}

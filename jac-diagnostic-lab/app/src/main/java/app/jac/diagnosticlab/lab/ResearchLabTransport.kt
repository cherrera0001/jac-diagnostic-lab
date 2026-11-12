package app.jac.diagnosticlab.lab

import app.jac.diagnosticlab.elm.ObdTransport
import app.jac.diagnosticlab.elm.TransportKind
import app.jac.diagnosticlab.elm.TransportResult

/**
 * Transporte de laboratorio (Nivel B/C). Respuestas scriptadas.
 * **Nunca** debe usarse contra el T8 real: no abre RFCOMM/GATT.
 *
 * [LabController] rechaza cualquier [ObdTransport] con
 * [TransportKind.ResearchLab] en la ruta de producción.
 */
class ResearchLabTransport(
    private val script: Map<String, String> = defaultScript,
) : ObdTransport {
    override val name: String = "ResearchLab (simulado, aislado del T8)"
    override val kind: TransportKind = TransportKind.ResearchLab
    override var connected: Boolean = false
        private set

    override suspend fun connect() {
        connected = true
    }

    override suspend fun disconnect() {
        connected = false
    }

    override suspend fun transact(command: String, timeoutMs: Long): TransportResult {
        if (!connected) {
            return TransportResult("", false, false, true, "lab desconectado")
        }
        val key = command.trim().uppercase().replace(" ", "")
        val body = script[key] ?: "NO DATA"
        return TransportResult(body, promptSeen = true, timedOut = false, disconnected = false)
    }

    companion object {
        /** Fixture mínimo alineado a la sesión de prueba (sin VIN). */
        val defaultScript: Map<String, String> = mapOf(
            "ATI" to "ELM327 v2.1",
            "ATZ" to "ELM327 v2.1",
            "ATE0" to "OK",
            "ATL0" to "OK",
            "ATS0" to "OK",
            "ATH1" to "OK",
            "ATSP0" to "OK",
            "ATDP" to "AUTO",
            "ATDPN" to "A6",
            "ATRV" to "12.4V",
            "0100" to "7E8 06 41 00 BE 1F A8 13",
            "010C" to "7E8 04 41 0C 0C 4B",
            "03" to "7E8 06 43 02 05 79 22 99",
        )
    }
}

object TransportIsolation {
    fun assertProductionOnly(transport: ObdTransport) {
        require(transport.kind == TransportKind.ProductionVehicle) {
            "LAB-CAN-001: rechazado ${transport.kind} (${transport.name}). Solo ProductionVehicle habla al T8."
        }
    }
}

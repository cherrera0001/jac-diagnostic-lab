package app.jac.diagnosticlab.security

import app.jac.diagnosticlab.elm.Exchange
import app.jac.diagnosticlab.elm.ResponseKind

/**
 * Borrado de DTC (J1979 Mode 04) — la ÚNICA escritura que esta app permite, y solo por esta compuerta.
 *
 * Qué hace Mode 04: borra DTC almacenados y pendientes, el cuadro congelado, y reinicia los monitores
 * de preparación. NO borra permanentes (Mode 0A) ni arregla la causa: si la falla persiste, vuelve.
 * Solo se exige lo que J1979 exige: ECU respondiendo y vehículo detenido.
 */
object ClearDtcGate {
    /** Condiciones técnicas de J1979 Mode 04. Nada más bloquea el borrado. */
    data class Preconditions(val ecuTalking: Boolean, val speedKmh: Double?, val rpm: Double?)

    data class Verdict(val allowed: Boolean, val blockers: List<String>, val warnings: List<String>)

    fun evaluate(p: Preconditions): Verdict {
        val blockers = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (!p.ecuTalking) blockers += "La ECU no está respondiendo."
        if (p.speedKmh != null && p.speedKmh > 0.5) blockers += "El vehículo se mueve (${p.speedKmh.toInt()} km/h): la ECU no acepta el borrado en movimiento."
        if (p.speedKmh == null) warnings += "Sin velocidad vigente: se envía igual y la ECU decide."
        if (p.rpm != null && p.rpm > 400) warnings += "Motor en marcha: J1979 pide contacto ON con motor apagado; la ECU puede rechazar (7F 04 22)."
        return Verdict(blockers.isEmpty(), blockers, warnings)
    }

    /** Autorización de un solo uso y corta vigencia para el comando «04». Sin ella, la política lo bloquea. */
    private const val TTL_MS = 20_000L
    @Volatile private var armedUntil = 0L

    @Synchronized fun arm(nowMs: Long = System.currentTimeMillis()) { armedUntil = nowMs + TTL_MS }
    @Synchronized fun disarm() { armedUntil = 0L }
    @Synchronized fun isArmed(nowMs: Long = System.currentTimeMillis()) = nowMs < armedUntil
}

enum class ClearOutcome(val label: String) {
    VERIFIED_EMPTY("Borrado confirmado: la ECU aceptó y la relectura no devuelve códigos."),
    CODES_REAPPEARED("La ECU aceptó el borrado pero la relectura ya muestra códigos: la falla sigue activa."),
    ACCEPTED_UNVERIFIED("La ECU aceptó el borrado, pero la relectura no fue válida: no se puede confirmar."),
    REJECTED("La ECU rechazó el borrado. No se borró nada."),
    NO_RESPONSE("Sin respuesta de la ECU. No se puede saber si se borró."),
}

object ClearDtcDecoder {
    sealed interface Response {
        data object Accepted : Response
        data class Rejected(val nrc: Int?) : Response
        data object NoResponse : Response
    }

    fun nrcText(nrc: Int?) = when (nrc) {
        0x22 -> "condiciones incorrectas (motor en marcha o contacto)"
        0x12 -> "subfunción no admitida"
        0x11 -> "servicio no admitido"
        0x78 -> "respuesta pendiente"
        null -> "sin código"
        else -> "NRC 0x%02X".format(nrc)
    }

    fun decode(ex: Exchange): Response {
        if (ex.kind != ResponseKind.OBD_DATA) return Response.NoResponse
        var rejected: Int? = null
        var sawNegative = false
        for (f in ex.frames) {
            val p = f.payload
            if (p.isEmpty()) continue
            if (p[0] == 0x44) return Response.Accepted
            if (p[0] == 0x7F && p.size >= 2 && p[1] == 0x04) { sawNegative = true; rejected = p.getOrNull(2) }
        }
        return if (sawNegative) Response.Rejected(rejected) else Response.NoResponse
    }

    fun outcome(response: Response, rereadStoredValid: Boolean, rereadStoredCodes: Int): ClearOutcome = when (response) {
        Response.Accepted -> when {
            !rereadStoredValid -> ClearOutcome.ACCEPTED_UNVERIFIED
            rereadStoredCodes > 0 -> ClearOutcome.CODES_REAPPEARED
            else -> ClearOutcome.VERIFIED_EMPTY
        }
        is Response.Rejected -> ClearOutcome.REJECTED
        Response.NoResponse -> ClearOutcome.NO_RESPONSE
    }
}

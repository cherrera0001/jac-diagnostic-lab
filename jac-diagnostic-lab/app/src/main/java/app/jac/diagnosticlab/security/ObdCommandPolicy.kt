package app.jac.diagnosticlab.security

import app.jac.diagnosticlab.elm.ReadOnlyViolation

/**
 * Allowlist cerrada Nivel A. Filtra solo lo que envía esta aplicación.
 * No impide que otra app o teléfono hable directo con el adaptador.
 */
object ObdCommandPolicy {
    enum class Decision { ALLOW, DENY }

    data class Verdict(
        val decision: Decision,
        val reason: String,
        val category: String,
    )

    private val atLocal = Regex("^AT(Z|I|DP|DPN|RV|E0|L0|S0|H1|SP[0-9])$")
    // 01: hasta 6 PIDs por petición (J1979 CAN). 06: resultados de pruebas a bordo. 09: identificación.
    // Todos son servicios J1979 de lectura; ninguno escribe ni activa actuadores.
    private val obdRead = Regex("^(01([0-9A-F]{2}){1,6}|02[0-9A-F]{4}|03|07|0A|06[0-9A-F]{2}|09[0-9A-F]{2})$")
    private val blockedPrefixes = listOf(
        "04", "ATSH", "ATMA", "ATCRA", "ATBI", "ATFI", "ATAL",
        "ATFC", "ATCF", "ATCM", "ST", "SR",
    )

    private val flowControlExact = setOf("ATFCSH7E0", "ATFCSD300000", "ATFCSM1", "ATFCSM0")

    fun evaluate(command: String): Verdict {
        val raw = command.trim().uppercase()
        if (raw.isEmpty()) return Verdict(Decision.DENY, "Comando vacío.", "empty")
        if (raw.contains(';') || raw.contains('\r') || raw.contains('\n')) {
            return Verdict(Decision.DENY, "Comando compuesto bloqueado.", "compound")
        }
        val c = raw.replace(" ", "")
        // Control de flujo ISO-TP estándar (FC 30 00 00 hacia 7E0). Solo transporte: no es un servicio
        // de diagnóstico ni cambia estado en la ECU. Valores exactos; cualquier otro ATFC sigue bloqueado.
        if (c in flowControlExact) {
            return Verdict(Decision.ALLOW, "Control de flujo ISO-TP estándar del adaptador.", "at_flow_control")
        }
        for (prefix in blockedPrefixes) {
            if (c == prefix || c.startsWith(prefix)) {
                return Verdict(
                    Decision.DENY,
                    when {
                        c == "04" || c.startsWith("04") ->
                            "Servicio 04 bloqueado. Esta aplicación no borra códigos."
                        else -> "Comando de cabecera, monitor, control o no estándar bloqueado: $c"
                    },
                    "blocked_prefix",
                )
            }
        }
        // UDS / servicios de escritura típicos fuera de J1979 de solo lectura
        if (c.matches(Regex("^(2E|2F|31|27|3E|34|35|36|37).*"))) {
            return Verdict(Decision.DENY, "Servicio UDS/escritura bloqueado: $c", "uds_write")
        }
        if (atLocal.matches(c)) {
            return Verdict(Decision.ALLOW, "AT local de configuración del adaptador.", "at_local")
        }
        if (obdRead.matches(c)) {
            return Verdict(Decision.ALLOW, "Consulta OBD-II estandarizada de solo lectura.", "obd_read")
        }
        return Verdict(Decision.DENY, "Comando fuera de la lista de solo lectura: $c", "not_allowlisted")
    }

    fun assertAllowed(command: String) {
        val v = evaluate(command)
        if (v.decision == Decision.DENY) throw ReadOnlyViolation(v.reason)
    }
}

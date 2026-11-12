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
    private val obdRead = Regex("^(01[0-9A-F]{2}|02[0-9A-F]{4}|03|07|0A|0900|0902)$")
    private val blockedPrefixes = listOf(
        "04", "ATSH", "ATMA", "ATCRA", "ATBI", "ATFI", "ATAL",
        "ATFC", "ATCF", "ATCM", "ST", "SR",
    )

    fun evaluate(command: String): Verdict {
        val raw = command.trim().uppercase()
        if (raw.isEmpty()) return Verdict(Decision.DENY, "Comando vacío.", "empty")
        if (raw.contains(';') || raw.contains('\r') || raw.contains('\n')) {
            return Verdict(Decision.DENY, "Comando compuesto bloqueado.", "compound")
        }
        val c = raw.replace(" ", "")
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

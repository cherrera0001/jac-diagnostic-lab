package app.jac.diagnosticlab.report

import app.jac.diagnosticlab.obd.Signal

/**
 * SPEC-035 R6/R7. Distingue tres cosas que el informe mezclaba:
 * una medición real, un valor no válido (byte 0 interpretado por la fórmula) y una medida que el
 * perfil de captura de 8 PIDs nunca lee (no es una falla).
 */
object ValueValidity {
    /** PID 61/62 son porcentajes de par con offset −125: un byte 0 da −125 %, que no es una medición. */
    fun invalidReason(s: Signal): String? {
        val v = s.value ?: return null
        return if ((s.pid == 0x61 || s.pid == 0x62) && v == -125.0) "no válido (byte 0 = −125 %)" else null
    }

    fun display(s: Signal, normal: String): String = invalidReason(s) ?: normal

    private val outsideEightPidProfile = setOf("Turbo real máximo", "EGT máxima", "Refrigerante máximo", "ΔP DPF máxima", "Consumo medio")

    fun outOfProfile(testId: String, metricName: String): Boolean =
        testId == "FAST_FAULT" && metricName in outsideEightPidProfile

    fun profileNote(): String = "Medida fuera del perfil de 8 PIDs: no se capturó, no es una falla."
}

package app.jac.diagnosticlab.security

enum class AdapterAuthenticity {
    ORIGINAL_UNVERIFIED,
    COMPATIBLE_IDENTIFIABLE,
    PROBABLE_CLONE,
    INDETERMINATE,
}

data class ElmSecurityReport(
    val ati: String,
    val protocol: String,
    val protocolNumber: String,
    val atrv: String,
    val authenticity: AdapterAuthenticity,
    val authenticityNote: String,
    val commandsObserved: List<String>,
    val policyNote: String,
)

object Elm327SecurityAnalyzer {
    fun analyze(
        ati: String,
        protocol: String,
        protocolNumber: String,
        atrv: String,
        commandLog: List<String>,
    ): ElmSecurityReport {
        val id = ati.trim()
        val authenticity = when {
            id.isBlank() -> AdapterAuthenticity.INDETERMINATE
            id.contains("ELM327", true) -> AdapterAuthenticity.PROBABLE_CLONE
            else -> AdapterAuthenticity.INDETERMINATE
        }
        val note = when (authenticity) {
            AdapterAuthenticity.PROBABLE_CLONE ->
                "ATI reportó '$id'. Esa cadena es trivialmente imitable; no prueba un ELM Electronics original ni firmware auditado."
            AdapterAuthenticity.INDETERMINATE ->
                "Sin identificación ATI usable; autenticidad indeterminada."
            else -> "Sin prueba de autenticidad de fabricante."
        }
        return ElmSecurityReport(
            ati = id,
            protocol = protocol,
            protocolNumber = protocolNumber,
            atrv = atrv,
            authenticity = authenticity,
            authenticityNote = note,
            commandsObserved = commandLog,
            policyNote = "ObdCommandPolicy solo protege esta APK. Otra aplicación puede hablar directo al adaptador.",
        )
    }
}

package app.jac.diagnosticlab.report

/** SPEC-035 R8. Relato opcional del conductor: es testimonio, no medición. */
data class DriverNarrative(
    val gear: String = "",
    val clutch: String = "",
    val temperature: String = "",
    val symptom: String = "",
    val notes: String = "",
) {
    val hasContent: Boolean get() = listOf(gear, clutch, temperature, symptom, notes).any { it.isNotBlank() }

    fun rows(): List<Pair<String, String>> = listOf(
        "Marcha" to gear, "Embrague" to clutch, "Temperatura del motor" to temperature,
        "Síntoma" to symptom, "Notas" to notes,
    ).filter { it.second.isNotBlank() }
}

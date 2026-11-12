package app.jac.diagnosticlab.security

import app.jac.diagnosticlab.analysis.HypothesisStatus
import app.jac.diagnosticlab.analysis.JacAnalysis
import app.jac.diagnosticlab.analysis.AnalysisInput
import app.jac.diagnosticlab.analysis.Symptom
import app.jac.diagnosticlab.elm.ResponseKind
import app.jac.diagnosticlab.obd.DtcReadResult

/**
 * Separa explícitamente:
 * A) diagnóstico de falla técnica
 * B) investigación de ciberseguridad
 * No atribuye causa cibernética a síntomas EPC/potencia sin evidencia.
 */
data class FaultHypothesisRow(
    val id: String,
    val title: String,
    val supporting: List<String>,
    val contrary: List<String>,
    val missing: List<String>,
    val verification: String,
    val status: HypothesisStatus,
)

data class FaultCorrelationReport(
    val technicalLine: List<FaultHypothesisRow>,
    val cyberLine: List<String>,
    val preservedDtcs: List<String>,
    val mil: String,
    val disclaimer: String,
)

object VehicleFaultCorrelation {
    fun correlate(
        symptoms: Set<Symptom>,
        dtcReads: List<DtcReadResult>,
        milOn: Boolean?,
        adapterVoltage: Double?,
        throttleSupported: Boolean,
        ecuAnswered0100: Boolean,
        commKinds: Set<ResponseKind>,
    ): FaultCorrelationReport {
        val analysis = JacAnalysis.evaluate(
            AnalysisInput(
                symptoms = symptoms,
                dtcReads = dtcReads,
                milOn = milOn,
                adapterVoltage = adapterVoltage,
                rpm = null,
                throttleSupported = throttleSupported,
                commKinds = commKinds,
                ecuAnswered0100 = ecuAnswered0100,
            ),
        )
        val codes = dtcReads.flatMap { read -> read.codes.map { it.code } }
        val technical = analysis.hypotheses.map { h ->
            FaultHypothesisRow(
                id = h.id,
                title = h.title,
                supporting = h.supporting,
                contrary = h.contrary,
                missing = h.missing,
                verification = h.nextChecks.joinToString(" · "),
                status = h.status,
            )
        }
        val cyber = buildList {
            add("Línea B (ciber): no se demostró que un adversario provocara EPC/pérdida de potencia.")
            add("La presencia de P0579/P2299 es evidencia de falla técnica reportada por la ECU, no de explotación.")
            add("Para atribuir causa cibernética haría falta evidencia de comandos no autorizados, tampering de firmware o acceso demonstrable a buses propietarios.")
            if (ecuAnswered0100) {
                add("Hecho: el camino OBD genérico respondió; eso no implica compromiso.")
            }
        }
        return FaultCorrelationReport(
            technicalLine = technical,
            cyberLine = cyber,
            preservedDtcs = codes,
            mil = when (milOn) {
                true -> "MIL genérico ON (0101)"
                false -> "MIL genérico OFF (0101)"
                null -> "MIL no leído"
            },
            disclaimer = "Los síntomas del conductor no son mediciones. No se recomienda cambiar piezas solo por esta correlación.",
        )
    }
}

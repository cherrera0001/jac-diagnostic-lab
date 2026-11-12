package app.jac.diagnosticlab.security

enum class FindingStatus {
    NOT_TESTED,
    TESTING,
    VERIFIED,
    NOT_REPRODUCIBLE,
    INCONCLUSIVE,
    MITIGATED,
    ACCEPTED_RISK,
}

enum class EvidenceKind {
    DIRECT,
    DOCUMENTARY,
    LAB,
    INFERENCE,
    HYPOTHESIS,
    UNAVAILABLE,
}

data class SecurityFinding(
    val findingId: String,
    val component: String,
    val title: String,
    val status: FindingStatus,
    val severity: String?,
    val confidence: String?,
    val evidenceIds: List<String>,
    val evidenceKind: EvidenceKind,
    val impact: String,
    val recommendation: String,
    val validationMethod: String,
    val residualRisk: String,
    val threatIds: List<String> = emptyList(),
)

data class InventoryAttribute(
    val id: String,
    val label: String,
    val value: String?,
    val status: String,
    val source: String?,
    val method: String?,
    val confidence: String?,
)

data class SecurityDashboard(
    val inventory: List<InventoryAttribute>,
    val findings: List<SecurityFinding>,
    val threats: List<ThreatScenario>,
    val hardening: List<HardeningItem>,
    val controlsEvaluated: Int,
    val controlsKnown: Int,
    val notes: List<String>,
)

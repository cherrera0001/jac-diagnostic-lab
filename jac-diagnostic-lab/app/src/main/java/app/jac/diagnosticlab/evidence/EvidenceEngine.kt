package app.jac.diagnosticlab.evidence

import app.jac.diagnosticlab.analysis.HealthStatus
import app.jac.diagnosticlab.elm.Exchange
import app.jac.diagnosticlab.elm.ReadOnlyGate
import app.jac.diagnosticlab.elm.ResponseKind
import app.jac.diagnosticlab.obd.DtcCategory
import app.jac.diagnosticlab.profile.JacT8Catalog
import app.jac.diagnosticlab.profile.ProfileCatalog
import app.jac.diagnosticlab.profile.ProfileResolver
import app.jac.diagnosticlab.profile.ResolutionStatus
import app.jac.diagnosticlab.profile.SignalEncoding
import app.jac.diagnosticlab.profile.VehicleFingerprint
import app.jac.diagnosticlab.report.Para
import app.jac.diagnosticlab.report.Section
import app.jac.diagnosticlab.report.Tone
import app.jac.diagnosticlab.report.WorkshopDoc
import app.jac.diagnosticlab.report.WorkshopInput
import app.jac.diagnosticlab.report.WorkshopReport
import app.jac.diagnosticlab.security.ObdCommandPolicy
import java.time.ZoneId
import java.util.Locale

/**
 * SPEC-034 — Motor de Evidencia Técnica (Blue Ocean MVP).
 *
 * Separa estrictamente hechos observados (crudos), datos ausentes/rechazados e inferencias con
 * confianza; degrada con elegancia ante ECU desconocida o ausencia de OBD sin inventar porcentajes
 * de salud; exporta trazabilidad offline con SHA-256 + versión de catálogo; compara sesiones
 * antes/después de reparación; y refuerza la política de solo lectura en campo.
 */

enum class FactKind {
    PID_READING,
    DTC_CODE,
    FREEZE_FRAME,
    MODE06_TEST,
    MODE09_ID,
    READINESS_MONITOR,
    MANUAL_OBSERVATION,
}

data class ObservedFact(
    val id: String,
    val kind: FactKind,
    val key: String,
    val label: String,
    val value: Double?,
    val textValue: String?,
    val unit: String?,
    val rawBytes: String,
    val timestampMs: Long,
    val latencyMs: Long?,
    val ecuProfile: String,
    val ecuHeader: String? = null,
)

enum class MissingReason {
    NOT_READ,
    NO_RESPONSE,
    SERVICE_REFUSED,
    UNVERIFIED_ECU_PROFILE,
    NO_OBD_COMMUNICATION,
    UNSUPPORTED_BY_ECU,
    UNRELIABLE_ZERO_FILL,
}

data class MissingDatum(
    val key: String,
    val description: String,
    val reason: MissingReason,
    val rawEvidence: String? = null,
)

enum class InferenceConfidence {
    LOW,
    MODERATE,
    HIGH_CORRELATION_NOT_CONFIRMED,
}

data class DiagnosticInference(
    val id: String,
    val title: String,
    val hypothesis: String,
    val confidence: InferenceConfidence,
    val isConfirmedRootCause: Boolean = false,
    val supportingFactIds: List<String> = emptyList(),
    val recommendedChecks: List<String> = emptyList(),
    val caveat: String = "Hipótesis diagnóstica genérica; nunca es causa raíz confirmada ni sustituye verificación física.",
) {
    init {
        require(!isConfirmedRootCause) {
            "SPEC-034 R1: las inferencias nunca deben presentarse como causa raíz confirmada."
        }
    }
}

enum class OperatingMode {
    VERIFIED_PROFILE,
    UNVERIFIED_PROFILE,
    MANUAL_INSPECTION,
}

data class ManualInspection(
    val recordedAtMs: Long,
    val odometerKm: Double? = null,
    val horometerHours: Double? = null,
    val visualCondition: String = "",
    val observations: List<String> = emptyList(),
    val inspector: String = "",
)

data class EvidenceEnvelope(
    val schemaVersion: String = SCHEMA_VERSION,
    val sessionId: String,
    val capturedAtMs: Long,
    val appVersion: String,
    val catalogVersion: String,
    val assetId: String,
    val vin: String?,
    val declaredAsset: String,
    val operatingMode: OperatingMode,
    val ecuProfileId: String?,
    val observedFacts: List<ObservedFact>,
    val missingData: List<MissingDatum>,
    val inferences: List<DiagnosticInference>,
    val manualInspection: ManualInspection? = null,
    val readOnlyEnforced: Boolean = true,
    val offlineOnly: Boolean = true,
) {
    /**
     * SPEC-034 R2: prohibido inventar o mostrar porcentajes globales de salud.
     * Esta propiedad es de solo lectura y siempre es null.
     */
    val globalHealthScorePercent: Double? get() = null

    init {
        val inferenceIds = inferences.map { it.id }.toSet()
        require(observedFacts.none { it.id in inferenceIds }) {
            "SPEC-034 R1: observedFacts no puede contener inferencias."
        }
        require(inferences.none { it.isConfirmedRootCause }) {
            "SPEC-034 R1: ninguna inferencia puede marcarse como causa raíz confirmada."
        }
    }

    companion object {
        const val SCHEMA_VERSION = "evidence-envelope-v1"
        const val HEALTH_SCORE_POLICY = "PROHIBITED_NO_GLOBAL_HEALTH_PERCENTAGE"
    }
}

data class EvidenceBundle(
    val envelope: EvidenceEnvelope,
    val appVersion: String,
    val catalogVersion: String,
    val rawEvidenceJson: String,
    val rawEvidenceSha256: String,
    val json: String,
    val pdfDocument: WorkshopDoc,
    val pdfLines: List<String>,
    val offlineOnly: Boolean = true,
)

data class DtcDiff(
    val resolved: List<String>,
    val persistent: List<String>,
    val newCodes: List<String>,
)

data class MetricDelta(
    val key: String,
    val label: String,
    val unit: String?,
    val beforeValue: Double?,
    val afterValue: Double?,
    val delta: Double?,
)

data class MonitorDiff(
    val monitorName: String,
    val beforeState: String?,
    val afterState: String?,
    val changed: Boolean,
)

data class SessionComparison(
    val assetId: String,
    val sameAsset: Boolean,
    val beforeSessionId: String,
    val afterSessionId: String,
    val dtcs: DtcDiff,
    val metricDeltas: List<MetricDelta>,
    val monitorChanges: List<MonitorDiff>,
    val summary: String,
)

object EvidenceEngine {
    const val STANDARD_OBD_PROFILE = "J1979_STANDARD"
    const val MANUAL_PROFILE = "MANUAL_INSPECTION"

    private val noResponseKinds = setOf(
        ResponseKind.NO_DATA,
        ResponseKind.TIMEOUT,
        ResponseKind.UNABLE_TO_CONNECT,
        ResponseKind.BUS_INIT_ERROR,
        ResponseKind.CAN_ERROR,
        ResponseKind.QUESTION_MARK,
        ResponseKind.DISCONNECTED,
        ResponseKind.STOPPED,
        ResponseKind.PARTIAL,
        ResponseKind.MALFORMED,
    )

    /** R5: Delega la validación estricta de solo lectura a [ObdCommandPolicy]. */
    fun evaluateCommand(command: String): ObdCommandPolicy.Verdict = ObdCommandPolicy.evaluate(command)

    /** R5: Lanza [app.jac.diagnosticlab.elm.ReadOnlyViolation] ante cualquier comando de escritura. */
    fun assertReadOnly(command: String) = ReadOnlyGate.check(command)

    /**
     * R1 + R2: Construye el [EvidenceEnvelope] canónico separando estrictamente hechos crudos,
     * datos ausentes/rechazados e inferencias con confianza.
     */
    fun buildEnvelope(
        input: WorkshopInput,
        catalog: ProfileCatalog = JacT8Catalog.v1,
        manualInspection: ManualInspection? = input.manualInspection,
    ): EvidenceEnvelope {
        val hasObdData = hasObdEvidence(input)
        val resolvedProfile = input.profile ?: autoResolveProfile(input, catalog)

        val operatingMode = when {
            !hasObdData -> OperatingMode.MANUAL_INSPECTION
            resolvedProfile?.status == ResolutionStatus.IDENTIFIED -> OperatingMode.VERIFIED_PROFILE
            else -> OperatingMode.UNVERIFIED_PROFILE
        }

        val ecuProfileId = if (operatingMode == OperatingMode.VERIFIED_PROFILE) {
            resolvedProfile?.matchedCandidateIds?.sorted()?.joinToString("+")
        } else {
            null
        }
        val factProfileLabel = ecuProfileId ?: if (hasObdData) STANDARD_OBD_PROFILE else MANUAL_PROFILE

        val exchangeByCmd = input.exchanges.groupBy { it.command.trim().uppercase().replace(" ", "") }
        fun exchangeFor(cmd: String): Exchange? = exchangeByCmd[cmd.uppercase()]?.lastOrNull()

        val observedFacts = mutableListOf<ObservedFact>()
        val missingData = mutableListOf<MissingDatum>()
        val inferences = mutableListOf<DiagnosticInference>()

        val captureTs = input.scanAtMs ?: input.generatedAtMs

        // 1. Observed facts — Mode 01 live signals (raw readings only)
        input.signals.forEach { s ->
            val cmd = "01%02X".format(s.pid)
            val ex = exchangeFor(cmd)
            val raw = input.rawByPid[s.pid]?.takeIf { it.isNotBlank() } ?: ex?.raw.orEmpty()
            observedFacts += ObservedFact(
                id = "PID:${s.key}",
                kind = FactKind.PID_READING,
                key = s.key,
                label = s.name,
                value = s.value,
                textValue = s.text,
                unit = s.unit.ifBlank { null },
                rawBytes = raw,
                timestampMs = ex?.atEpochMs ?: captureTs,
                latencyMs = ex?.elapsedMs,
                ecuProfile = factProfileLabel,
                ecuHeader = ex?.frames?.firstOrNull()?.header,
            )
        }

        // 2. Observed facts & missing data — DTCs (Modes 03 / 07 / 0A)
        val dtcFactIdsByCode = linkedMapOf<String, MutableList<String>>()
        DtcCategory.entries.forEach { cat ->
            val sid = when (cat) {
                DtcCategory.STORED -> "03"
                DtcCategory.PENDING -> "07"
                DtcCategory.PERMANENT -> "0A"
            }
            val read = input.dtcs.firstOrNull { it.category == cat }
            val ex = exchangeFor(sid)
            when {
                read == null -> {
                    if (hasObdData) {
                        missingData += MissingDatum(
                            key = "DTC:${cat.name}",
                            description = "Servicio OBD $sid (${cat.name.lowercase()}) no leído en esta sesión.",
                            reason = MissingReason.NOT_READ,
                        )
                    }
                }
                WorkshopReport.refused(read) -> {
                    missingData += MissingDatum(
                        key = "DTC:${cat.name}",
                        description = "La ECU rechazó el servicio $sid (respuesta negativa 7F $sid). No equivale a ausencia de códigos.",
                        reason = MissingReason.SERVICE_REFUSED,
                        rawEvidence = read.raw.ifBlank { ex?.raw },
                    )
                }
                read.codes.isEmpty() && read.kind != ResponseKind.OBD_DATA && !read.confirmedEmpty -> {
                    missingData += MissingDatum(
                        key = "DTC:${cat.name}",
                        description = "Servicio $sid sin respuesta OBD válida (${read.kind.name}).",
                        reason = MissingReason.NO_RESPONSE,
                        rawEvidence = read.raw.ifBlank { ex?.raw },
                    )
                }
                else -> {
                    read.codes.forEach { hit ->
                        val code = hit.code.uppercase(Locale.ROOT)
                        val factId = "DTC:${cat.name}:$code"
                        observedFacts += ObservedFact(
                            id = factId,
                            kind = FactKind.DTC_CODE,
                            key = code,
                            label = "Código ${cat.name.lowercase()} $code",
                            value = null,
                            textValue = code,
                            unit = null,
                            rawBytes = read.raw.ifBlank { "%02X %02X".format(hit.hi, hit.lo) },
                            timestampMs = ex?.atEpochMs ?: captureTs,
                            latencyMs = ex?.elapsedMs,
                            ecuProfile = factProfileLabel,
                            ecuHeader = hit.ecu,
                        )
                        dtcFactIdsByCode.getOrPut(code) { mutableListOf() } += factId
                    }
                }
            }
        }

        // 3. Observed facts & missing data — Freeze frame (Mode 02)
        val zerosUnreliable = WorkshopReport.freezeZerosUnreliable(input)
        input.freezeNotes.forEachIndexed { idx, note ->
            if (!note.dtc.isNullOrBlank()) {
                val ex = exchangeFor("020200")
                observedFacts += ObservedFact(
                    id = "FREEZE:DTC:$idx:${note.dtc}",
                    kind = FactKind.FREEZE_FRAME,
                    key = "FREEZE_DTC",
                    label = "Código que guardó cuadro congelado",
                    value = null,
                    textValue = note.dtc,
                    unit = null,
                    rawBytes = note.raw,
                    timestampMs = ex?.atEpochMs ?: captureTs,
                    latencyMs = ex?.elapsedMs,
                    ecuProfile = factProfileLabel,
                    ecuHeader = ex?.frames?.firstOrNull()?.header,
                )
            }
        }
        input.freezeValues.forEach { fv ->
            val cmd = "02%02X00".format(fv.pid)
            val ex = exchangeFor(cmd)
            fv.signals.forEach { s ->
                if (zerosUnreliable && s.value == 0.0) {
                    missingData += MissingDatum(
                        key = "FREEZE:${s.key}",
                        description = "Cuadro congelado PID %02X rellenado con cero no confiable por la ECU.".format(fv.pid),
                        reason = MissingReason.UNRELIABLE_ZERO_FILL,
                        rawEvidence = fv.raw,
                    )
                } else {
                    observedFacts += ObservedFact(
                        id = "FREEZE:${s.key}",
                        kind = FactKind.FREEZE_FRAME,
                        key = "FREEZE_${s.key}",
                        label = "Cuadro congelado ${s.name}",
                        value = s.value,
                        textValue = s.text,
                        unit = s.unit.ifBlank { null },
                        rawBytes = fv.raw,
                        timestampMs = ex?.atEpochMs ?: captureTs,
                        latencyMs = ex?.elapsedMs,
                        ecuProfile = factProfileLabel,
                        ecuHeader = ex?.frames?.firstOrNull()?.header,
                    )
                }
            }
        }

        // 4. Observed facts — Readiness monitors (0101)
        input.readiness?.let { r ->
            val ex = exchangeFor("0101")
            r.supported.forEach { m ->
                observedFacts += ObservedFact(
                    id = "MONITOR:${m.name}",
                    kind = FactKind.READINESS_MONITOR,
                    key = m.name,
                    label = "Monitor ${m.name}",
                    value = null,
                    textValue = m.state.name,
                    unit = null,
                    rawBytes = ex?.raw.orEmpty(),
                    timestampMs = ex?.atEpochMs ?: captureTs,
                    latencyMs = ex?.elapsedMs,
                    ecuProfile = factProfileLabel,
                    ecuHeader = ex?.frames?.firstOrNull()?.header,
                )
            }
        }

        // 5. Observed facts — Mode 06
        input.mode06.forEach { m ->
            val cmd = "06%02X".format(m.mid)
            val ex = exchangeFor(cmd)
            observedFacts += ObservedFact(
                id = "MODE06:%02X:%02X".format(m.mid, m.tid),
                kind = FactKind.MODE06_TEST,
                key = "%02X/%02X".format(m.mid, m.tid),
                label = m.monitorName,
                value = m.value,
                textValue = if (m.passed) "PASSED" else "OUT_OF_LIMIT",
                unit = m.unit.ifBlank { null },
                rawBytes = ex?.raw ?: "raw=${m.rawValue} min=${m.rawMin} max=${m.rawMax}",
                timestampMs = ex?.atEpochMs ?: captureTs,
                latencyMs = ex?.elapsedMs,
                ecuProfile = factProfileLabel,
                ecuHeader = ex?.frames?.firstOrNull()?.header,
            )
        }

        // 6. Observed facts & missing data — Mode 09 ECU identification
        input.ecuIdentification.forEach { rec ->
            if (rec.perEcu.isEmpty()) {
                missingData += MissingDatum(
                    key = "MODE09:${rec.command}",
                    description = "Sin tramas válidas para ${rec.command} (${rec.kind}).",
                    reason = MissingReason.NO_RESPONSE,
                    rawEvidence = rec.raw,
                )
            } else {
                rec.perEcu.forEach { per ->
                    per.values.forEachIndexed { idx, value ->
                        observedFacts += ObservedFact(
                            id = "MODE09:${rec.command}:${per.ecu ?: "ECU"}:$idx",
                            kind = FactKind.MODE09_ID,
                            key = rec.command,
                            label = "Identificación Mode 09 (${rec.command})",
                            value = null,
                            textValue = value,
                            unit = null,
                            rawBytes = rec.raw,
                            timestampMs = rec.atEpochMs,
                            latencyMs = rec.elapsedMs,
                            ecuProfile = factProfileLabel,
                            ecuHeader = per.ecu,
                        )
                    }
                }
            }
        }

        // 7. Missing data from non-responding exchanges
        val recordedMissingKeys = missingData.map { it.key }.toMutableSet()
        input.exchanges
            .filter { it.kind in noResponseKinds }
            .forEach { ex ->
                val cmd = ex.command.trim().uppercase().replace(" ", "")
                val key = "EXCHANGE:$cmd"
                if (recordedMissingKeys.add(key)) {
                    missingData += MissingDatum(
                        key = key,
                        description = "Comando $cmd sin respuesta válida (${ex.kind.name}).",
                        reason = MissingReason.NO_RESPONSE,
                        rawEvidence = ex.raw.ifBlank { ex.detail },
                    )
                }
            }

        // 8. R2: Graceful degradation explicit missing data + manual physical inspection
        when (operatingMode) {
            OperatingMode.MANUAL_INSPECTION -> {
                missingData += MissingDatum(
                    key = "OBD_LINK",
                    description = "Sin comunicación OBD-II / electrónica disponible. Operando en modo de inspección física guiada.",
                    reason = MissingReason.NO_OBD_COMMUNICATION,
                )
            }
            OperatingMode.UNVERIFIED_PROFILE -> {
                val calids = input.vehicleInfo?.calibrationIds.orEmpty() +
                    input.ecuIdentification.filter { it.pid == 0x04 }.flatMap { r -> r.perEcu.flatMap { it.values } }
                val calidNote = calids.distinct().takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "sin CALID catalogado"
                missingData += MissingDatum(
                    key = "ECU_PROFILE",
                    description = "Perfil de ECU no verificado en el catálogo ${catalog.version} ($calidNote). Operando en modo OBD-II estándar J1979.",
                    reason = MissingReason.UNVERIFIED_ECU_PROFILE,
                    rawEvidence = calids.distinct().joinToString(", ").ifBlank { null },
                )
                catalog.signals
                    .filter { it.encoding == SignalEncoding.UNKNOWN_ENCODING || it.ecuScope != null }
                    .forEach { sig ->
                        missingData += MissingDatum(
                            key = "SIGNAL:${sig.key}",
                            description = "Señal propietaria ${sig.name} no disponible sin perfil de ECU verificado.",
                            reason = MissingReason.UNVERIFIED_ECU_PROFILE,
                        )
                    }
            }
            OperatingMode.VERIFIED_PROFILE -> Unit
        }

        if (manualInspection != null) {
            manualInspection.odometerKm?.let { km ->
                observedFacts += ObservedFact(
                    id = "MANUAL:ODOMETER",
                    kind = FactKind.MANUAL_OBSERVATION,
                    key = "odometer_km",
                    label = "Odómetro (inspección física manual)",
                    value = km,
                    textValue = null,
                    unit = "km",
                    rawBytes = "MANUAL",
                    timestampMs = manualInspection.recordedAtMs,
                    latencyMs = null,
                    ecuProfile = MANUAL_PROFILE,
                )
            }
            manualInspection.horometerHours?.let { hrs ->
                observedFacts += ObservedFact(
                    id = "MANUAL:HOROMETER",
                    kind = FactKind.MANUAL_OBSERVATION,
                    key = "horometer_h",
                    label = "Horómetro (inspección física manual)",
                    value = hrs,
                    textValue = null,
                    unit = "h",
                    rawBytes = "MANUAL",
                    timestampMs = manualInspection.recordedAtMs,
                    latencyMs = null,
                    ecuProfile = MANUAL_PROFILE,
                )
            }
            if (manualInspection.visualCondition.isNotBlank()) {
                observedFacts += ObservedFact(
                    id = "MANUAL:VISUAL",
                    kind = FactKind.MANUAL_OBSERVATION,
                    key = "visual_condition",
                    label = "Estado visual (inspección física manual)",
                    value = null,
                    textValue = manualInspection.visualCondition.trim(),
                    unit = null,
                    rawBytes = "MANUAL",
                    timestampMs = manualInspection.recordedAtMs,
                    latencyMs = null,
                    ecuProfile = MANUAL_PROFILE,
                )
            }
            manualInspection.observations.filter { it.isNotBlank() }.forEachIndexed { idx, obs ->
                observedFacts += ObservedFact(
                    id = "MANUAL:OBS:$idx",
                    kind = FactKind.MANUAL_OBSERVATION,
                    key = "observation_$idx",
                    label = "Observación física #${idx + 1}",
                    value = null,
                    textValue = obs.trim(),
                    unit = null,
                    rawBytes = "MANUAL",
                    timestampMs = manualInspection.recordedAtMs,
                    latencyMs = null,
                    ecuProfile = MANUAL_PROFILE,
                )
            }
        }

        // 9. Inferences — strictly separated from observedFacts, always tagged with confidence & never confirmed root cause
        val groupedCodes = WorkshopReport.codes(input)
        groupedCodes.groupBy { WorkshopReport.hints[it.first] }.forEach { (hint, group) ->
            val codesList = group.map { it.first }
            val supportIds = codesList.flatMap { dtcFactIdsByCode[it].orEmpty() }.distinct()
            if (hint != null) {
                val confidence = if (codesList.size >= 2) {
                    InferenceConfidence.MODERATE
                } else {
                    InferenceConfidence.LOW
                }
                inferences += DiagnosticInference(
                    id = "INF:DTC:${codesList.joinToString("+")}",
                    title = "Correlación diagnóstica para ${codesList.joinToString(" + ")}",
                    hypothesis = hint.meaning,
                    confidence = confidence,
                    isConfirmedRootCause = false,
                    supportingFactIds = supportIds,
                    recommendedChecks = hint.checks,
                    caveat = WorkshopReport.SCOPE_NOTE,
                )
            } else {
                codesList.forEach { code ->
                    inferences += DiagnosticInference(
                        id = "INF:DTC:$code",
                        title = "Código genérico reportado: $code",
                        hypothesis = "Código $code presente sin procedimiento de marca local; requiere contraste con manual o escáner oficial.",
                        confidence = InferenceConfidence.LOW,
                        isConfirmedRootCause = false,
                        supportingFactIds = dtcFactIdsByCode[code].orEmpty(),
                        recommendedChecks = listOf("Leer descripción exacta y datos congelados con escáner de marca sin borrar códigos."),
                        caveat = WorkshopReport.SCOPE_NOTE,
                    )
                }
            }
        }

        input.health
            .filter { it.status == HealthStatus.CHECK || it.status == HealthStatus.ATTENTION }
            .forEachIndexed { idx, item ->
                inferences += DiagnosticInference(
                    id = "INF:HEALTH:${item.system}:$idx",
                    title = "${item.system}: ${item.title}",
                    hypothesis = item.detail.ifBlank { item.title },
                    confidence = if (item.status == HealthStatus.CHECK) InferenceConfidence.MODERATE else InferenceConfidence.LOW,
                    isConfirmedRootCause = false,
                    supportingFactIds = emptyList(),
                    recommendedChecks = listOf("Verificar medición en taller (${item.evidence})."),
                    caveat = "Evaluación con rangos genéricos de referencia; nunca es causa raíz confirmada ni porcentaje de salud.",
                )
            }

        val declaredAsset = listOf(input.declaredModel.trim(), input.declaredYear.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Activo sin declarar" }
        val assetId = input.vin?.trim()?.takeIf { it.isNotBlank() }
            ?: declaredAsset.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { input.sessionId }

        return EvidenceEnvelope(
            schemaVersion = EvidenceEnvelope.SCHEMA_VERSION,
            sessionId = input.sessionId,
            capturedAtMs = captureTs,
            appVersion = input.appVersion,
            catalogVersion = catalog.version,
            assetId = assetId,
            vin = input.vin?.trim()?.ifBlank { null },
            declaredAsset = declaredAsset,
            operatingMode = operatingMode,
            ecuProfileId = ecuProfileId,
            observedFacts = observedFacts,
            missingData = missingData,
            inferences = inferences,
            manualInspection = manualInspection,
            readOnlyEnforced = true,
            offlineOnly = true,
        )
    }

    /**
     * R3: Exporta paquete offline (JSON + documento PDF estructurado/líneas) con hash SHA-256
     * de la evidencia cruda, AppVersion y versión del catálogo.
     */
    fun exportBundle(
        input: WorkshopInput,
        catalog: ProfileCatalog = JacT8Catalog.v1,
        manualInspection: ManualInspection? = input.manualInspection,
        zone: ZoneId = ZoneId.of("UTC"),
    ): EvidenceBundle {
        val enrichedInput = input.copy(
            catalogVersion = catalog.version,
            manualInspection = manualInspection,
        )
        val envelope = buildEnvelope(enrichedInput, catalog, manualInspection)
        val rawPayloadMap = linkedMapOf<String, Any?>(
            "schemaVersion" to envelope.schemaVersion,
            "sessionId" to envelope.sessionId,
            "capturedAtMs" to envelope.capturedAtMs,
            "appVersion" to envelope.appVersion,
            "catalogVersion" to envelope.catalogVersion,
            "assetId" to envelope.assetId,
            "operatingMode" to envelope.operatingMode.name,
            "observedFacts" to envelope.observedFacts.map { factToMap(it) },
            "missingData" to envelope.missingData.map { missingToMap(it) },
            "rawExchanges" to input.exchanges.map { ex ->
                linkedMapOf(
                    "atEpochMs" to ex.atEpochMs,
                    "command" to ex.command,
                    "kind" to ex.kind.name,
                    "elapsedMs" to ex.elapsedMs,
                    "raw" to ex.raw,
                )
            },
        )
        val rawEvidenceJson = Json.encode(rawPayloadMap)
        val rawSha256 = sha256Hex(rawEvidenceJson.toByteArray(Charsets.UTF_8))

        val bundleJsonMap = linkedMapOf<String, Any?>(
            "schemaVersion" to envelope.schemaVersion,
            "appVersion" to envelope.appVersion,
            "catalogVersion" to envelope.catalogVersion,
            "rawEvidenceSha256" to rawSha256,
            "offlineOnly" to true,
            "readOnlyEnforced" to true,
            "healthScorePolicy" to EvidenceEnvelope.HEALTH_SCORE_POLICY,
            "envelope" to envelopeToMap(envelope),
        )
        val bundleJson = Json.encode(bundleJsonMap)

        val baseDoc = WorkshopReport.buildDoc(enrichedInput, zone)
        val traceSection = Section(
            number = baseDoc.sections.size + 1,
            title = "Trazabilidad de evidencia (SPEC-034)",
            blocks = buildList {
                add(Para("Modo operativo: ${envelope.operatingMode.name} · Política de salud: sin porcentaje global inventado.", Tone.INFO, bold = true))
                add(Para("App: ${envelope.appVersion} · Catálogo: ${envelope.catalogVersion} · Operación 100% offline · Solo lectura."))
                add(Para("Separación estricta — Hechos observados: ${envelope.observedFacts.size} · Datos ausentes/rechazados: ${envelope.missingData.size} · Inferencias (no confirmadas): ${envelope.inferences.size}."))
                add(Para("SHA-256 evidencia cruda: $rawSha256", Tone.MUTED))
            },
        )
        val pdfDoc = baseDoc.copy(
            meta = if (baseDoc.meta.any { it.first == "Catálogo" }) baseDoc.meta else baseDoc.meta + ("Catálogo" to envelope.catalogVersion),
            sections = baseDoc.sections + traceSection,
        )
        val pdfLines = pdfDoc.toLines() + listOf(
            "",
            "Versión app: ${envelope.appVersion}",
            "Versión catálogo: ${envelope.catalogVersion}",
            "Modo operativo: ${envelope.operatingMode.name}",
            "SHA-256 evidencia cruda: $rawSha256",
        )

        return EvidenceBundle(
            envelope = envelope,
            appVersion = envelope.appVersion,
            catalogVersion = envelope.catalogVersion,
            rawEvidenceJson = rawEvidenceJson,
            rawEvidenceSha256 = rawSha256,
            json = bundleJson,
            pdfDocument = pdfDoc,
            pdfLines = pdfLines,
            offlineOnly = true,
        )
    }

    /**
     * R4: Compara dos sesiones (antes y después de reparación) del mismo activo.
     * Detecta DTCs resueltos, persistentes y nuevos, cambios en lecturas clave y monitores.
     */
    fun compareSessions(before: EvidenceEnvelope, after: EvidenceEnvelope): SessionComparison {
        val sameAsset = when {
            !before.vin.isNullOrBlank() && !after.vin.isNullOrBlank() ->
                before.vin.equals(after.vin, ignoreCase = true)
            else ->
                before.assetId.equals(after.assetId, ignoreCase = true)
        }
        val assetId = after.vin ?: before.vin ?: after.assetId

        val beforeDtcs = before.observedFacts
            .filter { it.kind == FactKind.DTC_CODE }
            .map { (it.textValue ?: it.key).uppercase(Locale.ROOT) }
            .toSet()
        val afterDtcs = after.observedFacts
            .filter { it.kind == FactKind.DTC_CODE }
            .map { (it.textValue ?: it.key).uppercase(Locale.ROOT) }
            .toSet()

        val resolved = (beforeDtcs - afterDtcs).sorted()
        val persistent = (beforeDtcs intersect afterDtcs).sorted()
        val newCodes = (afterDtcs - beforeDtcs).sorted()

        val beforeMetrics = before.observedFacts
            .filter { it.value != null && (it.kind == FactKind.PID_READING || it.kind == FactKind.MANUAL_OBSERVATION) }
            .associateBy { it.key }
        val afterMetrics = after.observedFacts
            .filter { it.value != null && (it.kind == FactKind.PID_READING || it.kind == FactKind.MANUAL_OBSERVATION) }
            .associateBy { it.key }
        val allMetricKeys = (beforeMetrics.keys + afterMetrics.keys).sorted()
        val metricDeltas = allMetricKeys.map { key ->
            val b = beforeMetrics[key]
            val a = afterMetrics[key]
            val bv = b?.value
            val av = a?.value
            val delta = if (bv != null && av != null) av - bv else null
            MetricDelta(
                key = key,
                label = a?.label ?: b?.label ?: key,
                unit = a?.unit ?: b?.unit,
                beforeValue = bv,
                afterValue = av,
                delta = delta,
            )
        }

        val beforeMonitors = before.observedFacts
            .filter { it.kind == FactKind.READINESS_MONITOR }
            .associate { it.key to it.textValue }
        val afterMonitors = after.observedFacts
            .filter { it.kind == FactKind.READINESS_MONITOR }
            .associate { it.key to it.textValue }
        val monitorChanges = (beforeMonitors.keys + afterMonitors.keys).sorted().map { name ->
            val bs = beforeMonitors[name]
            val asState = afterMonitors[name]
            MonitorDiff(name, bs, asState, bs != asState)
        }

        val summary = buildString {
            if (!sameAsset) append("ADVERTENCIA: sesiones de activos distintos (${before.assetId} vs ${after.assetId}). ")
            append("DTCs resueltos: ${resolved.ifEmpty { listOf("ninguno") }.joinToString()}; ")
            append("persistentes: ${persistent.ifEmpty { listOf("ninguno") }.joinToString()}; ")
            append("nuevos: ${newCodes.ifEmpty { listOf("ninguno") }.joinToString()}.")
        }

        return SessionComparison(
            assetId = assetId,
            sameAsset = sameAsset,
            beforeSessionId = before.sessionId,
            afterSessionId = after.sessionId,
            dtcs = DtcDiff(resolved = resolved, persistent = persistent, newCodes = newCodes),
            metricDeltas = metricDeltas,
            monitorChanges = monitorChanges,
            summary = summary,
        )
    }

    fun envelopeToMap(e: EvidenceEnvelope): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to e.schemaVersion,
        "sessionId" to e.sessionId,
        "capturedAtMs" to e.capturedAtMs,
        "appVersion" to e.appVersion,
        "catalogVersion" to e.catalogVersion,
        "assetId" to e.assetId,
        "vin" to e.vin,
        "declaredAsset" to e.declaredAsset,
        "operatingMode" to e.operatingMode.name,
        "ecuProfileId" to e.ecuProfileId,
        "readOnlyEnforced" to e.readOnlyEnforced,
        "offlineOnly" to e.offlineOnly,
        "observedFacts" to e.observedFacts.map { factToMap(it) },
        "missingData" to e.missingData.map { missingToMap(it) },
        "inferences" to e.inferences.map { inferenceToMap(it) },
        "manualInspection" to e.manualInspection?.let { m ->
            linkedMapOf(
                "recordedAtMs" to m.recordedAtMs,
                "odometerKm" to m.odometerKm,
                "horometerHours" to m.horometerHours,
                "visualCondition" to m.visualCondition,
                "observations" to m.observations,
                "inspector" to m.inspector,
            )
        },
    )

    fun toJson(e: EvidenceEnvelope): String = Json.encode(envelopeToMap(e))

    private fun factToMap(f: ObservedFact): Map<String, Any?> = linkedMapOf(
        "id" to f.id,
        "kind" to f.kind.name,
        "key" to f.key,
        "label" to f.label,
        "value" to f.value,
        "textValue" to f.textValue,
        "unit" to f.unit,
        "rawBytes" to f.rawBytes,
        "timestampMs" to f.timestampMs,
        "latencyMs" to f.latencyMs,
        "ecuProfile" to f.ecuProfile,
        "ecuHeader" to f.ecuHeader,
    )

    private fun missingToMap(m: MissingDatum): Map<String, Any?> = linkedMapOf(
        "key" to m.key,
        "description" to m.description,
        "reason" to m.reason.name,
        "rawEvidence" to m.rawEvidence,
    )

    private fun inferenceToMap(inf: DiagnosticInference): Map<String, Any?> = linkedMapOf(
        "id" to inf.id,
        "title" to inf.title,
        "hypothesis" to inf.hypothesis,
        "confidence" to inf.confidence.name,
        "isConfirmedRootCause" to inf.isConfirmedRootCause,
        "supportingFactIds" to inf.supportingFactIds,
        "recommendedChecks" to inf.recommendedChecks,
        "caveat" to inf.caveat,
    )

    private fun hasObdEvidence(input: WorkshopInput): Boolean =
        input.signals.isNotEmpty() ||
            input.supportedPids.isNotEmpty() ||
            input.readiness != null ||
            input.mode06.isNotEmpty() ||
            input.freezeValues.isNotEmpty() ||
            input.dtcs.any { it.kind == ResponseKind.OBD_DATA || it.codes.isNotEmpty() || it.confirmedEmpty } ||
            input.ecuIdentification.any { it.perEcu.isNotEmpty() } ||
            input.exchanges.any { it.kind == ResponseKind.OBD_DATA }

    private fun autoResolveProfile(input: WorkshopInput, catalog: ProfileCatalog) =
        if (!hasObdEvidence(input)) {
            null
        } else {
            val calidsFromRecords = input.ecuIdentification
                .filter { it.pid == 0x04 }
                .flatMap { rec -> rec.perEcu.flatMap { e -> e.values.map { e.ecu to it } } }
            val calidsFromInfo = input.vehicleInfo?.calibrationIds.orEmpty().map { null as String? to it }
            val calids = (calidsFromRecords + calidsFromInfo).distinctBy { it.first to it.second }
            val ecuName = input.vehicleInfo?.ecuName
                ?: input.ecuIdentification.filter { it.pid == 0x0A }.flatMap { it.perEcu }.flatMap { it.values }.firstOrNull()
            val headers = input.exchanges.flatMap { it.frames }.mapNotNull { it.header }
            val fp = VehicleFingerprint.from(
                declaredModel = input.declaredModel,
                declaredYear = input.declaredYear,
                vin = input.vin,
                calibrationIds = calids,
                ecuName = ecuName,
                ecuLabel = null,
                protocolNumber = input.protocol,
                responseHeaders = headers,
            )
            ProfileResolver.resolve(fp, catalog)
        }
}

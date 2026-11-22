package app.jac.diagnosticlab.analysis

import app.jac.diagnosticlab.dynamic.TraceFrame
import app.jac.diagnosticlab.evidence.DiagnosticInference
import app.jac.diagnosticlab.evidence.InferenceConfidence
import java.util.Locale

/**
 * SPEC-034 R7 — Estimación de combustible sin mirar el tablero.
 *
 * Jerarquía estricta de fuentes (solo lectura):
 * 1. PID 0x2F (Fuel Tank Level Input, %) si la ECU lo soporta y entrega dato válido.
 * 2. Si 0x2F no está disponible: integración de consumo acumulado mediante PID 0x5E (Fuel Rate, L/h)
 *    desde un evento manual de «Estanque lleno».
 * 3. Si 0x5E tampoco está disponible: integración mediante PID 0x10 (MAF, g/s) usando relación
 *    aire/combustible y densidad del combustible desde «Estanque lleno».
 *
 * REGLA ESTRICTA: el resultado en litros se etiqueta SIEMPRE como ESTIMACIÓN/INFERENCIA y nunca
 * como medición directa de volumen.
 */

enum class FuelProfile(
    val label: String,
    val stoichiometricAfr: Double,
    val effectiveAfr: Double,
    val densityGramsPerLiter: Double,
) {
    /** Diésel common-rail: opera con exceso de aire; AFR efectiva media ≈ 18:1, densidad ≈ 835 g/L. */
    DIESEL("Diésel", 14.5, 18.0, 835.0),

    /** Gasolina: AFR estequiométrica 14,7:1, densidad ≈ 745 g/L. */
    GASOLINE("Gasolina", 14.7, 14.7, 745.0),
}

enum class FuelEstimateSource(val label: String) {
    PID_2F_TANK_LEVEL("PID 0x2F (Nivel de estanque %)"),
    PID_5E_FUEL_RATE_INTEGRATION("PID 0x5E (Flujo L/h desde Estanque lleno)"),
    PID_10_MAF_INTEGRATION("PID 0x10 (MAF g/s desde Estanque lleno)"),
    UNAVAILABLE("Sin fuente disponible"),
}

data class FullTankAnchor(
    val atEpochMs: Long,
    val tankCapacityLiters: Double = FuelEstimator.DEFAULT_TANK_CAPACITY_LITERS,
    val label: String = "Estanque lleno",
) {
    init {
        require(tankCapacityLiters > 0.0) {
            "La capacidad nominal del estanque debe ser mayor que 0 L."
        }
    }
}

data class FuelEstimate(
    val source: FuelEstimateSource,
    val tankCapacityLiters: Double,
    val estimatedRemainingLiters: Double?,
    val estimatedLevelPercent: Double?,
    val estimatedConsumedLiters: Double?,
    val instantaneousRateLph: Double?,
    val fullTankAnchor: FullTankAnchor?,
    val classificationLabel: String = LABEL_ESTIMATION_INFERENCE,
    val isInference: Boolean = true,
    val isDirectMeasurement: Boolean = false,
    val note: String,
) {
    init {
        require(tankCapacityLiters > 0.0) {
            "La capacidad nominal del estanque debe ser mayor que 0 L."
        }
        require(isInference && !isDirectMeasurement) {
            "SPEC-034 R7: la estimación de combustible en litros debe etiquetarse siempre como ESTIMACIÓN/INFERENCIA y nunca como medición directa."
        }
        require(
            classificationLabel.contains("ESTIMACIÓN", ignoreCase = true) ||
                classificationLabel.contains("INFERENCIA", ignoreCase = true),
        ) {
            "SPEC-034 R7: classificationLabel debe declarar explícitamente ESTIMACIÓN/INFERENCIA."
        }
    }

    /** Texto compacto para UI/Android Auto conservando siempre el carácter de estimación. */
    fun summaryText(): String = if (estimatedRemainingLiters != null) {
        val pctPart = estimatedLevelPercent?.let { " (%.0f%%)".format(Locale.US, it) }.orEmpty()
        "~%.1f L%s · %s".format(Locale.US, estimatedRemainingLiters, pctPart, classificationLabel)
    } else {
        "Sin estimación · $classificationLabel"
    }

    /** Convierte la estimación a una [DiagnosticInference] compatible con SPEC-034 R1. */
    fun toDiagnosticInference(): DiagnosticInference {
        val support = buildList {
            when (source) {
                FuelEstimateSource.PID_2F_TANK_LEVEL -> add("PID:2F")
                FuelEstimateSource.PID_5E_FUEL_RATE_INTEGRATION -> {
                    add("PID:5E")
                    add("MANUAL:FULL_TANK")
                }
                FuelEstimateSource.PID_10_MAF_INTEGRATION -> {
                    add("PID:10")
                    add("MANUAL:FULL_TANK")
                }
                FuelEstimateSource.UNAVAILABLE -> Unit
            }
        }
        val confidence = when (source) {
            FuelEstimateSource.PID_2F_TANK_LEVEL -> InferenceConfidence.MODERATE
            FuelEstimateSource.PID_5E_FUEL_RATE_INTEGRATION -> InferenceConfidence.MODERATE
            FuelEstimateSource.PID_10_MAF_INTEGRATION -> InferenceConfidence.LOW
            FuelEstimateSource.UNAVAILABLE -> InferenceConfidence.LOW
        }
        return DiagnosticInference(
            id = "INF:FUEL:${source.name}",
            title = "Combustible restante [$classificationLabel]: ${summaryText()}",
            hypothesis = note,
            confidence = confidence,
            isConfirmedRootCause = false,
            supportingFactIds = support,
            recommendedChecks = emptyList(),
            caveat = "SPEC-034 R7 ($classificationLabel): los litros reportados son una estimación matemática (capacidad nominal / integración de flujo o MAF), nunca una medición directa de volumen.",
        )
    }

    companion object {
        const val LABEL_ESTIMATION_INFERENCE = "ESTIMACIÓN/INFERENCIA"
    }
}

object FuelEstimator {
    /** Capacidad nominal de referencia del estanque de la JAC T8 (76 L). */
    const val DEFAULT_TANK_CAPACITY_LITERS = 76.0
    const val PID_FUEL_LEVEL = 0x2F
    const val PID_FUEL_RATE = 0x5E
    const val PID_MAF = 0x10
    const val KEY_FUEL_LEVEL = "2F"
    const val KEY_FUEL_RATE = "5E"
    const val KEY_MAF = "10"
    const val KEY_LAMBDA = "44"
    const val DEFAULT_MAX_STEP_MS = 3_600_000L

    /**
     * Convierte flujo másico de aire (MAF, PID 0x10, g/s) en tasa estimada de consumo (L/h).
     * Si se conoce lambda comandada (PID 0x44), ajusta la relación aire/combustible efectiva.
     */
    fun fuelRateFromMafLph(
        mafGramsPerSec: Double,
        profile: FuelProfile = FuelProfile.DIESEL,
        lambda: Double? = null,
    ): Double {
        if (mafGramsPerSec <= 0.0 || !mafGramsPerSec.isFinite()) return 0.0
        val afr = if (lambda != null && lambda.isFinite() && lambda > 0.2) {
            profile.stoichiometricAfr * lambda
        } else {
            profile.effectiveAfr
        }
        return (mafGramsPerSec / afr) * 3600.0 / profile.densityGramsPerLiter
    }

    /**
     * Calcula el incremento de consumo (L) entre dos tramas consecutivas usando 0x5E o respaldo 0x10 (MAF).
     * Útil para acumular consumo en streaming durante captura continua en segundo plano.
     */
    fun incrementalConsumedLiters(
        previous: TraceFrame?,
        current: TraceFrame,
        fuelProfile: FuelProfile = FuelProfile.DIESEL,
        maxStepMs: Long = DEFAULT_MAX_STEP_MS,
    ): Pair<Double, FuelEstimateSource?> {
        if (previous == null) return 0.0 to null
        val dtMs = current.atMs - previous.atMs
        if (dtMs <= 0L || dtMs > maxStepMs) return 0.0 to null
        val dtHours = dtMs / 3_600_000.0

        val curRate = current.values[KEY_FUEL_RATE]?.takeIf { it.isFinite() && it >= 0.0 }
        val prevRate = previous.values[KEY_FUEL_RATE]?.takeIf { it.isFinite() && it >= 0.0 }
        if (curRate != null) {
            val avg = if (prevRate != null) (prevRate + curRate) / 2.0 else curRate
            return (avg * dtHours) to FuelEstimateSource.PID_5E_FUEL_RATE_INTEGRATION
        }

        val curMaf = current.values[KEY_MAF]?.takeIf { it.isFinite() && it >= 0.0 }
        val prevMaf = previous.values[KEY_MAF]?.takeIf { it.isFinite() && it >= 0.0 }
        if (curMaf != null) {
            val curLph = fuelRateFromMafLph(curMaf, fuelProfile, current.values[KEY_LAMBDA])
            val prevLph = prevMaf?.let { fuelRateFromMafLph(it, fuelProfile, previous.values[KEY_LAMBDA]) }
            val avg = if (prevLph != null) (prevLph + curLph) / 2.0 else curLph
            return (avg * dtHours) to FuelEstimateSource.PID_10_MAF_INTEGRATION
        }

        return 0.0 to null
    }

    /**
     * Estima el combustible restante en litros respetando la jerarquía SPEC-034 R7:
     * PID 0x2F (si la ECU lo soporta) → PID 0x5E desde «Estanque lleno» → PID 0x10 (MAF) desde «Estanque lleno».
     */
    fun estimate(
        supportedPids: Set<Int> = emptySet(),
        pid2fLevelPercent: Double? = null,
        frames: List<TraceFrame> = emptyList(),
        fullTankAnchor: FullTankAnchor? = null,
        priorConsumedLiters: Double = 0.0,
        tankCapacityLiters: Double = fullTankAnchor?.tankCapacityLiters ?: DEFAULT_TANK_CAPACITY_LITERS,
        fuelProfile: FuelProfile = FuelProfile.DIESEL,
        maxStepMs: Long = DEFAULT_MAX_STEP_MS,
    ): FuelEstimate {
        val cap = tankCapacityLiters.also {
            require(it > 0.0) { "La capacidad nominal del estanque debe ser mayor que 0 L." }
        }
        val pid2fAllowed = supportedPids.isEmpty() || PID_FUEL_LEVEL in supportedPids
        val latest2f = pid2fLevelPercent ?: frames.lastOrNull { it.values[KEY_FUEL_LEVEL] != null }?.values?.get(KEY_FUEL_LEVEL)
        val latestRate = latestInstantRateLph(frames, fuelProfile)

        // 1. Fuente primaria: PID 0x2F (Fuel Tank Level Input, %)
        if (pid2fAllowed && latest2f != null && latest2f.isFinite() && latest2f in 0.0..100.0) {
            val remaining = (latest2f / 100.0) * cap
            val consumed = (cap - remaining).coerceAtLeast(0.0)
            return FuelEstimate(
                source = FuelEstimateSource.PID_2F_TANK_LEVEL,
                tankCapacityLiters = cap,
                estimatedRemainingLiters = remaining,
                estimatedLevelPercent = latest2f,
                estimatedConsumedLiters = consumed,
                instantaneousRateLph = latestRate,
                fullTankAnchor = fullTankAnchor,
                classificationLabel = FuelEstimate.LABEL_ESTIMATION_INFERENCE,
                isInference = true,
                isDirectMeasurement = false,
                note = "ESTIMACIÓN/INFERENCIA desde PID 0x2F (%.1f%%) sobre estanque nominal de %.0f L. No es medición directa de volumen."
                    .format(Locale.US, latest2f, cap),
            )
        }

        // 2. Sin PID 0x2F: se requiere evento manual de «Estanque lleno» para integrar consumo.
        if (fullTankAnchor == null) {
            return FuelEstimate(
                source = FuelEstimateSource.UNAVAILABLE,
                tankCapacityLiters = cap,
                estimatedRemainingLiters = null,
                estimatedLevelPercent = null,
                estimatedConsumedLiters = null,
                instantaneousRateLph = latestRate,
                fullTankAnchor = null,
                classificationLabel = FuelEstimate.LABEL_ESTIMATION_INFERENCE,
                isInference = true,
                isDirectMeasurement = false,
                note = "PID 0x2F no soportado/disponible. Registre el evento manual «Estanque lleno» para estimar consumo por PID 0x5E o MAF (0x10).",
            )
        }

        val relevantFrames = frames.filter { it.atMs >= fullTankAnchor.atEpochMs }.sortedBy { it.atMs }
        val baseConsumed = priorConsumedLiters.coerceAtLeast(0.0)

        // 2a. Respaldo preferido: PID 0x5E (Engine Fuel Rate, L/h)
        val pid5eAllowed = supportedPids.isEmpty() || PID_FUEL_RATE in supportedPids
        val has5e = pid5eAllowed && relevantFrames.any { f -> f.values[KEY_FUEL_RATE]?.let { it.isFinite() && it >= 0.0 } == true }
        if (has5e) {
            val integrated = integrateRate(relevantFrames, maxStepMs) { f ->
                f.values[KEY_FUEL_RATE]?.takeIf { it.isFinite() && it >= 0.0 }
            }
            val totalConsumed = (baseConsumed + integrated).coerceAtLeast(0.0)
            val remaining = (cap - totalConsumed).coerceAtLeast(0.0)
            val pct = (remaining / cap * 100.0).coerceIn(0.0, 100.0)
            return FuelEstimate(
                source = FuelEstimateSource.PID_5E_FUEL_RATE_INTEGRATION,
                tankCapacityLiters = cap,
                estimatedRemainingLiters = remaining,
                estimatedLevelPercent = pct,
                estimatedConsumedLiters = totalConsumed,
                instantaneousRateLph = latestRate,
                fullTankAnchor = fullTankAnchor,
                classificationLabel = FuelEstimate.LABEL_ESTIMATION_INFERENCE,
                isInference = true,
                isDirectMeasurement = false,
                note = "ESTIMACIÓN/INFERENCIA integrando PID 0x5E (L/h) desde «${fullTankAnchor.label}» (%.0f L): consumo est. %.2f L. No es medición directa."
                    .format(Locale.US, cap, totalConsumed),
            )
        }

        // 2b. Segundo respaldo: PID 0x10 (MAF, g/s)
        val pid10Allowed = supportedPids.isEmpty() || PID_MAF in supportedPids
        val has10 = pid10Allowed && relevantFrames.any { f -> f.values[KEY_MAF]?.let { it.isFinite() && it >= 0.0 } == true }
        if (has10) {
            val integrated = integrateRate(relevantFrames, maxStepMs) { f ->
                f.values[KEY_MAF]?.takeIf { it.isFinite() && it >= 0.0 }?.let { maf ->
                    fuelRateFromMafLph(maf, fuelProfile, f.values[KEY_LAMBDA])
                }
            }
            val totalConsumed = (baseConsumed + integrated).coerceAtLeast(0.0)
            val remaining = (cap - totalConsumed).coerceAtLeast(0.0)
            val pct = (remaining / cap * 100.0).coerceIn(0.0, 100.0)
            return FuelEstimate(
                source = FuelEstimateSource.PID_10_MAF_INTEGRATION,
                tankCapacityLiters = cap,
                estimatedRemainingLiters = remaining,
                estimatedLevelPercent = pct,
                estimatedConsumedLiters = totalConsumed,
                instantaneousRateLph = latestRate,
                fullTankAnchor = fullTankAnchor,
                classificationLabel = FuelEstimate.LABEL_ESTIMATION_INFERENCE,
                isInference = true,
                isDirectMeasurement = false,
                note = "ESTIMACIÓN/INFERENCIA desde PID 0x10 (MAF, perfil ${fuelProfile.label}) tras «${fullTankAnchor.label}» (%.0f L): consumo est. %.2f L. No es medición directa."
                    .format(Locale.US, cap, totalConsumed),
            )
        }

        return FuelEstimate(
            source = FuelEstimateSource.UNAVAILABLE,
            tankCapacityLiters = cap,
            estimatedRemainingLiters = null,
            estimatedLevelPercent = null,
            estimatedConsumedLiters = if (baseConsumed > 0.0) baseConsumed else null,
            instantaneousRateLph = latestRate,
            fullTankAnchor = fullTankAnchor,
            classificationLabel = FuelEstimate.LABEL_ESTIMATION_INFERENCE,
            isInference = true,
            isDirectMeasurement = false,
            note = "PID 0x2F no soportado y sin lecturas válidas de PID 0x5E (Fuel Rate) ni PID 0x10 (MAF) tras «${fullTankAnchor.label}».",
        )
    }

    private fun integrateRate(
        sortedFrames: List<TraceFrame>,
        maxStepMs: Long,
        rateLphOf: (TraceFrame) -> Double?,
    ): Double {
        if (sortedFrames.size < 2) return 0.0
        var consumed = 0.0
        var prevTime: Long? = null
        var prevRate: Double? = null
        for (frame in sortedFrames) {
            val rate = rateLphOf(frame) ?: continue
            val pt = prevTime
            val pr = prevRate
            if (pt != null && pr != null) {
                val dtMs = frame.atMs - pt
                if (dtMs in 1L..maxStepMs) {
                    consumed += ((pr + rate) / 2.0) * (dtMs / 3_600_000.0)
                }
            }
            prevTime = frame.atMs
            prevRate = rate
        }
        return consumed
    }

    private fun latestInstantRateLph(frames: List<TraceFrame>, profile: FuelProfile): Double? {
        val last5e = frames.lastOrNull { it.values[KEY_FUEL_RATE] != null }?.values?.get(KEY_FUEL_RATE)
        if (last5e != null && last5e.isFinite() && last5e >= 0.0) return last5e
        val lastMafFrame = frames.lastOrNull { it.values[KEY_MAF] != null }
        val maf = lastMafFrame?.values?.get(KEY_MAF)
        return if (maf != null && maf.isFinite() && maf >= 0.0) {
            fuelRateFromMafLph(maf, profile, lastMafFrame.values[KEY_LAMBDA])
        } else {
            null
        }
    }
}

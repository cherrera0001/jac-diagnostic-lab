package app.jac.diagnosticlab.obd

/**
 * DIAG-001…007 — tipado semántico separado de la fórmula matemática.
 */
enum class InterpretationStatus {
    VERIFIED,
    SUPPORTED,
    HYPOTHESIS,
    UNKNOWN,
    NOT_TESTED,
    INCONCLUSIVE,
}

enum class OperationalState(val reportLabel: String) {
    ENGINE_OFF("Motor apagado (declarado o sin RPM)"),
    KEY_ON_ENGINE_OFF("Contacto ON / motor detenido"),
    ENGINE_IDLING_OR_RUNNING("Motor en ralentí o en marcha (RPM > 0)"),
    UNKNOWN("Estado operacional desconocido"),
    ;

    companion object {
        fun fromHints(rpm: Double?, speedKmh: Double?, declared: OperationalState?): OperationalState {
            if (declared != null) return declared
            val r = rpm ?: return UNKNOWN
            return when {
                r > 50 -> ENGINE_IDLING_OR_RUNNING
                r == 0.0 && (speedKmh == null || speedKmh == 0.0) -> KEY_ON_ENGINE_OFF
                else -> UNKNOWN
            }
        }
    }
}

data class VoltageAssessment(
    val valueVolts: Double?,
    val status: Status,
    val isConfirmedBatteryMeasurement: Boolean,
    val note: String,
) {
    enum class Status {
        REQUIRES_INDEPENDENT_MEASUREMENT,
        NOT_AVAILABLE,
        PARSE_FAILED,
    }
}

fun assessAdapterVoltage(raw: String?): VoltageAssessment {
    if (raw.isNullOrBlank()) {
        return VoltageAssessment(null, VoltageAssessment.Status.NOT_AVAILABLE, false, "Sin ATRV.")
    }
    val value = parseAdapterVoltage(raw)
        ?: return VoltageAssessment(null, VoltageAssessment.Status.PARSE_FAILED, false, "No se pudo parsear ATRV: $raw")
    return VoltageAssessment(
        valueVolts = value,
        status = VoltageAssessment.Status.REQUIRES_INDEPENDENT_MEASUREMENT,
        isConfirmedBatteryMeasurement = false,
        note = "Estimación del adaptador (ATRV). REQUIRES_INDEPENDENT_MEASUREMENT: no es medición de batería/tierras con multímetro.",
    )
}

data class SessionTimeMetrics(
    val sampleCount: Int,
    val windowMs: Long,
    val effectiveHz: Double,
    val minGapMs: Long?,
    val maxGapMs: Long?,
) {
    companion object {
        fun fromTimestamps(stamps: List<Long>): SessionTimeMetrics {
            if (stamps.isEmpty()) return SessionTimeMetrics(0, 0, 0.0, null, null)
            val sorted = stamps.sorted()
            val window = if (sorted.size == 1) 0L else sorted.last() - sorted.first()
            val gaps = sorted.zipWithNext { a, b -> b - a }
            val hz = if (window <= 0L) 0.0 else (sorted.size - 1).toDouble() * 1000.0 / window.toDouble()
            return SessionTimeMetrics(
                sampleCount = sorted.size,
                windowMs = window,
                effectiveHz = hz,
                minGapMs = gaps.minOrNull(),
                maxGapMs = gaps.maxOrNull(),
            )
        }
    }
}

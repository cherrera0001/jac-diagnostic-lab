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

data class FaultDossier(
    val title: String,
    val lines: List<String>,
)

object FaultIdentification {
    fun dossier(
        storedCodes: List<String>,
        freezeDtc: String?,
        freezeRaw: String?,
        supportedPids: Set<Int>,
        samples: List<Pair<Int, Double?>>,
        powerLossOnBrake: Boolean,
        model: String,
        year: String,
        time: SessionTimeMetrics?,
    ): FaultDossier {
        val codes = storedCodes.map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
        val app = listOf(0x49, 0x4A, 0x4B).filter { it in supportedPids }
        val throttle = listOf(0x11, 0x45, 0x47).filter { it in supportedPids }
        val lines = buildList {
            add("Ficha para identificar la falla (solo lectura). No certifica circulación.")
            if (model.isNotBlank() || year.isNotBlank()) {
                add("Unidad declarada: ${listOf(model, year).filter { it.isNotBlank() }.joinToString(" ")}. No salió de la ECU.")
            }
            if (powerLossOnBrake) {
                add("Relato: corte de potencia/energía al frenar en marcha. NO PROBAR EN LA CALLE.")
            }
            add("Avisos guardados: ${if (codes.isEmpty()) "ninguno en esta copia" else codes.joinToString(", ")}.")
            if (freezeDtc != null) {
                add("Cuadro congelado apunta a $freezeDtc. Raw: ${freezeRaw.orEmpty()}")
                if (codes.isNotEmpty() && freezeDtc !in codes) {
                    add("Ojo: el cuadro congelado ($freezeDtc) no coincide con la lista guardada. Llevar ambos al escáner JAC.")
                }
            } else {
                add("Cuadro congelado: sin DTC decodificado en esta lectura. Bytes: ${freezeRaw ?: "no consultado"}.")
            }
            if (app.isEmpty()) {
                add("Pedal de acelerador (PID 0x49/0x4A/0x4B): la ECU no los declaró soportados en el mapa leído. El interruptor de freno no tiene PID genérico: se mide en taller.")
            } else {
                add("Pedal de acelerador declarado soportado: ${app.joinToString { "0x%02X".format(it) }}.")
                app.forEach { pid ->
                    val v = samples.lastOrNull { it.first == pid }?.second
                    add("Última lectura 0x%02X: %s".format(pid, v?.let { "%.1f %%".format(it) } ?: "sin valor"))
                }
            }
            if (throttle.isNotEmpty()) {
                add("Mariposa (no es el pedal): ${throttle.joinToString { "0x%02X".format(it) }}.")
            }
            val rpm = samples.lastOrNull { it.first == 0x0C }?.second
            val spd = samples.lastOrNull { it.first == 0x0D }?.second
            if (rpm != null) add("RPM en esta captura: %.0f. Velocidad: %s.".format(rpm, spd?.let { "%.0f km/h".format(it) } ?: "sin dato"))
            if (time != null && time.sampleCount > 0) {
                add("Ventana de muestreo: ${time.windowMs} ms, ${time.sampleCount} muestras, %.2f Hz efectivo.".format(time.effectiveHz))
                if (time.windowMs < 5_000) add("Ventana corta para un síntoma intermitente. Conviene un registro de al menos 15 s con la camioneta quieta.")
            }
            add("Checklist taller (quieta, sin borrar):")
            add("1. Confirmar P0579/P2299 y el texto del escáner JAC.")
            add("2. Congelado: mismo DTC y PIDs del momento.")
            add("3. Interruptor de freno: contacto ON, pedal suelto y pisado.")
            add("4. Dos pistas del acelerador: voltajes sin pisar y pisando (no usar 750 mV de la N56).")
            add("5. Masas y batería con multímetro (ATRV no sirve).")
            add("Hipótesis técnica abierta: desacuerdo freno/acelerador o interruptor de crucero/freno. No demostrada. No es un ataque por estos bytes.")
        }
        val title = when {
            powerLossOnBrake && codes.isNotEmpty() -> "Falla abierta: corte al frenar + avisos ${codes.joinToString()}"
            powerLossOnBrake -> "Falla abierta: corte al frenar (sin avisos en esta copia)"
            codes.isNotEmpty() -> "Avisos guardados: ${codes.joinToString()}"
            else -> "Sin avisos en esta copia; el relato sigue pendiente de taller"
        }
        return FaultDossier(title, lines)
    }
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

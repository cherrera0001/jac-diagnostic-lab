package app.jac.diagnosticlab.analysis

import app.jac.diagnosticlab.dynamic.TraceFrame
import app.jac.diagnosticlab.evidence.DiagnosticInference
import app.jac.diagnosticlab.evidence.InferenceConfidence
import java.util.Locale
import kotlin.math.roundToInt

/**
 * SPEC-034 R6 — Asistencia de conducción segura, marcha estimada (inferencia) y resumen post-viaje.
 *
 * Principios de seguridad vial y rigor técnico:
 * - La marcha actual se infiere a partir de la relación RPM / Velocidad (cuando velocidad > 5 km/h)
 *   y SIEMPRE se etiqueta explícitamente como inferencia, nunca como sensor directo de caja.
 * - Detecta sobreesfuerzo (lugging: bajas RPM con alta carga), RPM altas con baja carga y motor frío (< 70 °C).
 * - Genera métricas post-viaje accionables (% sobreesfuerzo, % motor frío, % zona óptima) sin inventar
 *   porcentajes globales de salud del vehículo.
 */

enum class DashboardLinkStatus(val label: String) {
    CONNECTED("CONECTADO"),
    RECONNECTING("RECONECTANDO"),
    PAUSED("PAUSADO"),
}

enum class ShiftCue(val arrow: String, val label: String, val detail: String) {
    SHIFT_UP("↑", "SUBIR MARCHA", "RPM altas con carga baja/media."),
    SHIFT_DOWN("↓", "BAJAR MARCHA", "Sobreesfuerzo (bajas RPM con alta carga)."),
    COLD_ENGINE("❄", "MOTOR FRÍO", "Refrigerante bajo 70 °C: conducir suave."),
    OPTIMAL("●", "ZONA ÓPTIMA", "Régimen y carga equilibrados."),
    IDLE_OR_STOPPED("—", "DETENIDO / RALENTÍ", "Sin marcha inferida por velocidad ≤ 5 km/h."),
    INSUFFICIENT_DATA("?", "SIN DATOS", "Faltan RPM o velocidad para evaluar."),
}

enum class TripZone {
    LUGGING,
    COLD_ENGINE,
    SHIFT_UP,
    OPTIMAL,
    IDLE_OR_STOPPED,
}

data class GearInference(
    val gear: Int?,
    val label: String,
    val ratioRpmPerKmh: Double?,
    val isInference: Boolean = true,
    val originNote: String = "Inferencia por relación RPM/Velocidad (no es sensor directo de caja).",
) {
    init {
        require(isInference) {
            "SPEC-034 R6: la marcha estimada debe etiquetarse siempre como inferencia."
        }
    }
}

data class DrivingGuidanceSnapshot(
    val observedRpm: Double?,
    val observedSpeedKmh: Double?,
    val observedLoadPct: Double?,
    val observedCoolantC: Double?,
    val gearInference: GearInference,
    val cue: ShiftCue,
    val zone: TripZone,
    val isLugging: Boolean,
    val isColdEngine: Boolean,
    val isGuidanceInference: Boolean = true,
    val note: String,
    /** Marcha destino sugerida cuando cue es SHIFT_UP o SHIFT_DOWN (p. ej. 3 para «2ª → 3ª»). */
    val recommendedGear: Int? = null,
    /** Etiqueta corta y clara para tablero/Android Auto (p. ej. «SUBIR A 3ª» o «BAJAR A 4ª»). */
    val actionLabel: String = cue.label,
) {
    init {
        require(isGuidanceInference) {
            "SPEC-034 R6: la guía de marcha y carga debe declararse como inferencia."
        }
    }

    /** Convierte la guía actual en una [DiagnosticInference] compatible con SPEC-034 R1. */
    fun toDiagnosticInference(): DiagnosticInference = DiagnosticInference(
        id = "INF:DRIVING:${cue.name}",
        title = "${cue.arrow} ${cue.label} (Marcha est.: ${gearInference.label})",
        hypothesis = "$note ${gearInference.originNote}",
        confidence = if (observedLoadPct != null && observedRpm != null && observedSpeedKmh != null) {
            InferenceConfidence.MODERATE
        } else {
            InferenceConfidence.LOW
        },
        isConfirmedRootCause = false,
        supportingFactIds = listOfNotNull(
            observedRpm?.let { "PID:0C" },
            observedSpeedKmh?.let { "PID:0D" },
            observedLoadPct?.let { "PID:04" },
            observedCoolantC?.let { "PID:05" },
        ),
        recommendedChecks = emptyList(),
        caveat = "Inferencia en tiempo real por RPM/Velocidad/Carga; no sustituye la atención del conductor ni mide sensores internos de transmisión.",
    )
}

data class TripGuidanceSummary(
    val totalSamples: Int,
    val movingSamples: Int,
    val luggingSamples: Int,
    val coldEngineSamples: Int,
    val shiftUpSamples: Int,
    val optimalSamples: Int,
    val luggingPercent: Double,
    val coldEnginePercent: Double,
    val shiftUpPercent: Double,
    val optimalPercent: Double,
    val summaryLines: List<String>,
)

object DrivingGuidanceEngine {
    const val MIN_MOVING_SPEED_KMH = 5.0
    const val COLD_ENGINE_THRESHOLD_C = 70.0
    const val LUGGING_MAX_RPM = 1500.0
    const val LUGGING_MIN_LOAD_PCT = 75.0
    const val LOW_RPM_DOWNSHIFT = 1220.0
    const val SHIFT_UP_MIN_RPM = 2300.0
    const val SHIFT_UP_HIGH_RPM = 2700.0
    const val SHIFT_UP_COLD_MIN_RPM = 2200.0
    const val SHIFT_UP_MAX_LOAD_PCT = 82.0

    /**
     * Infiere la marcha aproximada de una caja mecánica de 6 velocidades diésel (calibrada para pickup 2.0T como JAC T8)
     * a partir de la relación RPM / Velocidad (solo cuando velocidad > 5 km/h y RPM > 400).
     * Filtra desacople de embrague / punto muerto en movimiento (velocidad > 18 km/h con RPM < 1050).
     */
    fun inferGear(rpm: Double?, speedKmh: Double?): GearInference {
        if (rpm == null || speedKmh == null || speedKmh <= MIN_MOVING_SPEED_KMH || rpm <= 400.0) {
            return GearInference(
                gear = null,
                label = "—",
                ratioRpmPerKmh = null,
                isInference = true,
            )
        }
        // Embrague pisado o punto muerto rodando: evita saltos falsos a 5ª/6ª durante el cambio.
        if (speedKmh > 18.0 && rpm < 1050.0) {
            return GearInference(
                gear = null,
                label = "N",
                ratioRpmPerKmh = rpm / speedKmh,
                isInference = true,
                originNote = "Posible embrague pisado o punto muerto rodando (inferencia RPM/Velocidad).",
            )
        }
        val ratio = rpm / speedKmh
        val gear = when {
            ratio >= 98.0 -> 1
            ratio >= 58.0 -> 2
            ratio >= 38.0 -> 3
            ratio >= 27.5 -> 4
            ratio >= 22.0 -> 5
            else -> 6
        }
        return GearInference(
            gear = gear,
            label = "${gear}ª",
            ratioRpmPerKmh = ratio,
            isInference = true,
        )
    }

    /**
     * Evalúa una muestra observada (RPM, Velocidad, Carga, Refrigerante) y produce
     * la guía hands-free para conducción segura con marcha destino concreta («2ª → 3ª», «4ª → 3ª»).
     */
    fun evaluate(
        rpm: Double?,
        speedKmh: Double?,
        loadPct: Double?,
        coolantC: Double?,
    ): DrivingGuidanceSnapshot {
        val gearInf = inferGear(rpm, speedKmh)
        val g = gearInf.gear
        val cold = coolantC != null && coolantC < COLD_ENGINE_THRESHOLD_C

        if (rpm == null || speedKmh == null) {
            return DrivingGuidanceSnapshot(
                observedRpm = rpm,
                observedSpeedKmh = speedKmh,
                observedLoadPct = loadPct,
                observedCoolantC = coolantC,
                gearInference = gearInf,
                cue = ShiftCue.INSUFFICIENT_DATA,
                zone = TripZone.IDLE_OR_STOPPED,
                isLugging = false,
                isColdEngine = cold,
                note = "Esperando lecturas observadas de RPM (0x0C) y velocidad (0x0D).",
            )
        }

        if (speedKmh <= MIN_MOVING_SPEED_KMH) {
            val note = if (cold) {
                "Vehículo detenido/ralentí con motor frío (${coolantC!!.roundToInt()} °C): iniciar marcha con suavidad."
            } else {
                "Vehículo detenido o a muy baja velocidad (≤ 5 km/h)."
            }
            return DrivingGuidanceSnapshot(
                observedRpm = rpm,
                observedSpeedKmh = speedKmh,
                observedLoadPct = loadPct,
                observedCoolantC = coolantC,
                gearInference = gearInf,
                cue = if (cold) ShiftCue.COLD_ENGINE else ShiftCue.IDLE_OR_STOPPED,
                zone = TripZone.IDLE_OR_STOPPED,
                isLugging = false,
                isColdEngine = cold,
                note = note,
            )
        }

        // Embrague pisado o punto muerto rodando: no disparar recomendaciones falsas en medio del cambio.
        if (g == null) {
            return DrivingGuidanceSnapshot(
                observedRpm = rpm,
                observedSpeedKmh = speedKmh,
                observedLoadPct = loadPct,
                observedCoolantC = coolantC,
                gearInference = gearInf,
                cue = if (cold) ShiftCue.COLD_ENGINE else ShiftCue.OPTIMAL,
                zone = if (cold) TripZone.COLD_ENGINE else TripZone.OPTIMAL,
                isLugging = false,
                isColdEngine = cold,
                note = "Cambio en curso o rodando en punto muerto.",
                actionLabel = if (cold) ShiftCue.COLD_ENGINE.label else "CAMBIO / NEUTRO",
            )
        }

        // 1. Prioridad máxima de salud mecánica: evitar lugging / sobreesfuerzo en diésel/turbo.
        val lugging = loadPct != null && rpm < LUGGING_MAX_RPM && loadPct >= LUGGING_MIN_LOAD_PCT
        if (lugging && g > 1) {
            val target = g - 1
            return DrivingGuidanceSnapshot(
                observedRpm = rpm,
                observedSpeedKmh = speedKmh,
                observedLoadPct = loadPct,
                observedCoolantC = coolantC,
                gearInference = gearInf,
                cue = ShiftCue.SHIFT_DOWN,
                zone = TripZone.LUGGING,
                isLugging = true,
                isColdEngine = cold,
                note = "Sobreesfuerzo (%.0f rpm, %.0f %% carga): bajar de ${g}ª a ${target}ª para proteger motor y turbo."
                    .format(Locale.ROOT, rpm, loadPct),
                recommendedGear = target,
                actionLabel = "BAJAR A ${target}ª (${g}ª→${target}ª)",
            )
        }

        // 1b. Régimen demasiado bajo en marchas altas (3ª–6ª) al desacelerar: sugerir bajar cambio.
        if (g >= 3 && rpm < LOW_RPM_DOWNSHIFT && speedKmh >= 18.0) {
            val target = g - 1
            return DrivingGuidanceSnapshot(
                observedRpm = rpm,
                observedSpeedKmh = speedKmh,
                observedLoadPct = loadPct,
                observedCoolantC = coolantC,
                gearInference = gearInf,
                cue = ShiftCue.SHIFT_DOWN,
                zone = TripZone.LUGGING,
                isLugging = false,
                isColdEngine = cold,
                note = "RPM bajas (%.0f rpm) en ${g}ª: bajar a ${target}ª para mantener respuesta."
                    .format(Locale.ROOT, rpm),
                recommendedGear = target,
                actionLabel = "BAJAR A ${target}ª (${g}ª→${target}ª)",
            )
        }

        // 2. Motor frío (< 70 °C): si ya pasó 2200 rpm en 1ª–5ª, sí sugerir subir cambio; si va suave, aviso de motor frío.
        if (cold) {
            if (rpm >= SHIFT_UP_COLD_MIN_RPM && g < 6) {
                val target = g + 1
                return DrivingGuidanceSnapshot(
                    observedRpm = rpm,
                    observedSpeedKmh = speedKmh,
                    observedLoadPct = loadPct,
                    observedCoolantC = coolantC,
                    gearInference = gearInf,
                    cue = ShiftCue.SHIFT_UP,
                    zone = TripZone.SHIFT_UP,
                    isLugging = false,
                    isColdEngine = true,
                    note = "Motor frío (${coolantC!!.roundToInt()} °C) a %.0f rpm: subir de ${g}ª a ${target}ª."
                        .format(Locale.ROOT, rpm),
                    recommendedGear = target,
                    actionLabel = "SUBIR A ${target}ª (${g}ª→${target}ª)",
                )
            }
            return DrivingGuidanceSnapshot(
                observedRpm = rpm,
                observedSpeedKmh = speedKmh,
                observedLoadPct = loadPct,
                observedCoolantC = coolantC,
                gearInference = gearInf,
                cue = ShiftCue.COLD_ENGINE,
                zone = TripZone.COLD_ENGINE,
                isLugging = false,
                isColdEngine = true,
                note = "Motor frío (${coolantC!!.roundToInt()} °C): conducir suave hasta ~70 °C.",
                actionLabel = "MOTOR FRÍO (${g}ª)",
            )
        }

        // 3. Subir marcha en diésel 2.0T: desde 2300 rpm con carga <= 82%, o >= 2700 rpm con cualquier carga (si < 6ª).
        val shouldShiftUp = g < 6 && (
            rpm >= SHIFT_UP_HIGH_RPM ||
                (rpm >= SHIFT_UP_MIN_RPM && (loadPct == null || loadPct <= SHIFT_UP_MAX_LOAD_PCT))
            )
        if (shouldShiftUp) {
            val target = g + 1
            return DrivingGuidanceSnapshot(
                observedRpm = rpm,
                observedSpeedKmh = speedKmh,
                observedLoadPct = loadPct,
                observedCoolantC = coolantC,
                gearInference = gearInf,
                cue = ShiftCue.SHIFT_UP,
                zone = TripZone.SHIFT_UP,
                isLugging = false,
                isColdEngine = false,
                note = "Subir de ${g}ª a ${target}ª (%.0f rpm) para reducir consumo y cuidar el turbo."
                    .format(Locale.ROOT, rpm),
                recommendedGear = target,
                actionLabel = "SUBIR A ${target}ª (${g}ª→${target}ª)",
            )
        }

        // 4. Zona óptima
        return DrivingGuidanceSnapshot(
            observedRpm = rpm,
            observedSpeedKmh = speedKmh,
            observedLoadPct = loadPct,
            observedCoolantC = coolantC,
            gearInference = gearInf,
            cue = ShiftCue.OPTIMAL,
            zone = TripZone.OPTIMAL,
            isLugging = false,
            isColdEngine = false,
            note = "En ${g}ª · régimen y carga dentro de zona óptima diésel.",
            actionLabel = "ÓPTIMO (${g}ª)",
        )
    }

    /** Evalúa un [TraceFrame] en vivo usando las claves J1979 0C (RPM), 0D (Velocidad), 04 (Carga), 05 (Refrigerante). */
    fun evaluateFrame(values: Map<String, Double>): DrivingGuidanceSnapshot = evaluate(
        rpm = values["0C"],
        speedKmh = values["0D"],
        loadPct = values["04"],
        coolantC = values["05"],
    )

    /**
     * Resumen post-viaje: calcula % de muestras en movimiento en sobreesfuerzo, en frío,
     * en RPM altas y en zona óptima.
     */
    fun summarizeTrip(frames: List<TraceFrame>): TripGuidanceSummary {
        var lastRpm: Double? = null
        var lastSpeed: Double? = null
        var lastLoad: Double? = null
        var lastCoolant: Double? = null
        val snapshots = frames.map { f ->
            f.values["0C"]?.let { lastRpm = it }
            f.values["0D"]?.let { lastSpeed = it }
            f.values["04"]?.let { lastLoad = it }
            f.values["05"]?.let { lastCoolant = it }
            evaluate(lastRpm, lastSpeed, lastLoad, lastCoolant)
        }
        return summarizeSnapshots(snapshots)
    }

    fun summarizeSnapshots(snapshots: List<DrivingGuidanceSnapshot>): TripGuidanceSummary {
        val moving = snapshots.filter { it.zone != TripZone.IDLE_OR_STOPPED }
        val denom = moving.size
        val lug = moving.count { it.zone == TripZone.LUGGING }
        val cold = moving.count { it.zone == TripZone.COLD_ENGINE }
        val high = moving.count { it.zone == TripZone.SHIFT_UP }
        val opt = moving.count { it.zone == TripZone.OPTIMAL }
        fun pct(n: Int) = if (denom == 0) 0.0 else (n * 100.0) / denom
        val lugPct = pct(lug)
        val coldPct = pct(cold)
        val highPct = pct(high)
        val optPct = pct(opt)

        val lines = if (denom == 0) {
            listOf("Sin tramo en movimiento (> 5 km/h) registrado en esta sesión.")
        } else {
            buildList {
                add("${lugPct.roundToInt()}% del viaje en sobreesfuerzo")
                add("${coldPct.roundToInt()}% con motor frío")
                if (high > 0) add("${highPct.roundToInt()}% con RPM altas (sugerencia subir marcha)")
                add("${optPct.roundToInt()}% en zona óptima")
            }
        }
        return TripGuidanceSummary(
            totalSamples = snapshots.size,
            movingSamples = denom,
            luggingSamples = lug,
            coldEngineSamples = cold,
            shiftUpSamples = high,
            optimalSamples = opt,
            luggingPercent = lugPct,
            coldEnginePercent = coldPct,
            shiftUpPercent = highPct,
            optimalPercent = optPct,
            summaryLines = lines,
        )
    }
}

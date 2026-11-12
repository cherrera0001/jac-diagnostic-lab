package app.jac.diagnosticlab.analysis

import app.jac.diagnosticlab.bt.LinkPhase
import app.jac.diagnosticlab.obd.OperationalState
import app.jac.diagnosticlab.telemetry.ChannelStats

enum class GaugeFreshness {
    LIVE,
    STALE,
    NO_VALUE,
    UNSUPPORTED,
    READ_ERROR,
    ADAPTER_ONLY,
}

data class LiveHypothesis(
    val id: String,
    val title: String,
    val status: String,
    val favoring: List<String>,
    val against: List<String>,
    val missing: List<String>,
    val nextCheck: String,
)

data class LiveSituationReport(
    val headline: String,
    val operational: String,
    val sensorLines: List<String>,
    val hypotheses: List<LiveHypothesis>,
    val nextActions: List<String>,
)

/**
 * Resumen para el mecánico: qué está ocurriendo con los datos vivos.
 * No recomienda piezas. No afirma causa sin evidencia.
 */
object LiveSituationEngine {

    fun freshness(
        pid: Int,
        supported: Boolean?,
        hasValue: Boolean,
        sampleValid: Boolean?,
        ageMs: Long?,
        targetIntervalMs: Long?,
        failCount: Int,
        sampleCount: Int,
    ): GaugeFreshness {
        if (pid < 0) return GaugeFreshness.ADAPTER_ONLY
        if (supported == false) return GaugeFreshness.UNSUPPORTED
        if (failCount > 0 && sampleCount == 0) return GaugeFreshness.READ_ERROR
        if (!hasValue) return GaugeFreshness.NO_VALUE
        if (sampleValid == false) return GaugeFreshness.READ_ERROR
        val target = (targetIntervalMs ?: 2000L).coerceAtLeast(500L)
        val staleAfter = (target * 2.5).toLong().coerceAtLeast(2_500L)
        if (ageMs != null && ageMs > staleAfter) return GaugeFreshness.STALE
        return GaugeFreshness.LIVE
    }

    fun mechanicNote(freshness: GaugeFreshness, channelLabel: String, pid: Int): String? = when (freshness) {
        GaugeFreshness.LIVE -> null
        GaugeFreshness.STALE -> "$channelLabel: dato viejo. No decida con este valor hasta que vuelva VIVO."
        GaugeFreshness.READ_ERROR -> "$channelLabel: lectura fallida o NO DATA. No equivale a falla de pieza."
        GaugeFreshness.UNSUPPORTED -> "$channelLabel: fuera del mapa Mode 01 de esta ECU. Use escáner JAC / multímetro."
        GaugeFreshness.ADAPTER_ONLY -> "ATRV es del dongle, no de la batería."
        GaugeFreshness.NO_VALUE -> if (pid < 0) "Sin ATRV todavía." else "$channelLabel: aún sin muestra en esta sesión."
    }

    fun report(
        codes: List<String>,
        freezeDtc: String?,
        rpm: Double?,
        speed: Double?,
        throttleAbs: Double?,
        load: Double?,
        coolant: Double?,
        appD: Double?,
        appE: Double?,
        atrv: Double?,
        supported: Set<Int>,
        bitmapRead: Boolean,
        powerLossOnBrake: Boolean,
        linkPhase: LinkPhase,
        polling: Boolean,
        channelStats: Map<Int, ChannelStats>,
    ): LiveSituationReport {
        val unique = codes.map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
        val op = when {
            speed != null && speed > 0.5 -> OperationalState.ENGINE_IDLING_OR_RUNNING // en movimiento: no etiquetar como apagado
            rpm != null && rpm > 50 -> OperationalState.ENGINE_IDLING_OR_RUNNING
            linkPhase >= LinkPhase.ECU_RESPONDING -> OperationalState.KEY_ON_ENGINE_OFF
            else -> OperationalState.UNKNOWN
        }
        val opLabel = when {
            speed != null && speed > 0.5 -> "Vehículo en movimiento (%.0f km/h) — no usar para provocar falla".format(speed)
            else -> op.reportLabel
        }
        val sensorLines = buildList {
            if (!bitmapRead) add("Mapa PID aún no leído: no se puede afirmar qué sensores existen.")
            add("Fase enlace: ${linkPhase.label}. Monitor ${if (polling) "activo" else "detenido"}.")
            if (rpm != null) add("Motor: ≈%.0f rpm · velocidad %s.".format(rpm, speed?.let { "%.0f km/h".format(it) } ?: "sin dato"))
            if (throttleAbs != null) {
                add("Mariposa absoluta (0x11): %.1f %% — no es el pedal.".format(throttleAbs))
                if (rpm != null && rpm in 500.0..1200.0 && (speed == null || speed == 0.0) && throttleAbs >= 70.0) {
                    add("Plausibilidad: mariposa alta en ralentí quieto. Revisar mapeo/ECU; no auto-falla.")
                }
            }
            if (load != null) add("Carga calculada: %.1f %%.".format(load))
            if (coolant != null) add("Refrigerante: %.0f °C.".format(coolant))
            val apps = listOfNotNull(
                appD?.let { "D=%.1f%%".format(it) },
                appE?.let { "E=%.1f%%".format(it) },
            )
            when {
                listOf(0x49, 0x4A, 0x4B).none { it in supported } && bitmapRead ->
                    add("Pedal APP (0x49/4A/4B): fuera del mapa Mode 01. Medir con multímetro / escáner JAC.")
                apps.isNotEmpty() -> add("Pedal APP leído: ${apps.joinToString(", ")}.")
                else -> add("Pedal APP: declarado o no evaluado; sin valor fresco todavía.")
            }
            add("Interruptor de freno: sin PID OBD genérico. Obligatorio en banco (ON, suelto/pisado).")
            if (atrv != null) add("ATRV adaptador: %.1f V — REQUIRES_INDEPENDENT_MEASUREMENT.".format(atrv))
            if (unique.isNotEmpty()) add("DTC en esta sesión/copia: ${unique.joinToString()}.")
            if (freezeDtc != null) add("Freeze apunta a $freezeDtc.")
            channelStats[0x0C]?.let {
                if (it.effectiveHz > 0) add("RPM Hz medido: %.2f (no prometido).".format(it.effectiveHz))
            }
        }

        val hypotheses = buildList {
            add(
                LiveHypothesis(
                    id = "H2",
                    title = "Incoherencia freno / acelerador",
                    status = when {
                        "P2299" in unique -> "ABIERTA — código genérico presente"
                        powerLossOnBrake -> "ABIERTA — relato sin PID de freno"
                        else -> "INSUFICIENTE"
                    },
                    favoring = buildList {
                        if ("P2299" in unique) add("P2299 (definición pública: freno/acelerador incompatibles)")
                        if (powerLossOnBrake) add("Relato: corte al frenar")
                    },
                    against = buildList {
                        if (!powerLossOnBrake && "P2299" !in unique) add("Sin relato ni P2299 en esta copia")
                    },
                    missing = listOf(
                        "Señal de interruptor de freno (hardware)",
                        "Voltajes APP en reposo y pisado",
                        "Texto exacto del escáner JAC para P2299",
                    ),
                    nextCheck = "Quietos: medir freno suelto/pisado y dos pistas APP. No circular para provocar el corte.",
                ),
            )
            add(
                LiveHypothesis(
                    id = "H1",
                    title = "Entrada crucero / interruptor asociado al freno (P0579)",
                    status = if ("P0579" in unique) "ABIERTA — código genérico presente" else "INSUFICIENTE",
                    favoring = if ("P0579" in unique) listOf("P0579 en almacenados o freeze") else emptyList(),
                    against = if ("P0579" !in unique) listOf("P0579 no aparece en esta copia") else emptyList(),
                    missing = listOf("Confirmación escáner JAC", "Continuidad interruptor / cableado"),
                    nextCheck = "Comparar freeze y Mode 03 en escáner de marca; no borrar.",
                ),
            )
            add(
                LiveHypothesis(
                    id = "H4",
                    title = "Alimentación / masas",
                    status = if (atrv != null && (atrv < 11.6 || atrv > 15.2)) "ATENCIÓN — ATRV fuera de banda habitual" else "PENDIENTE_MULTÍMETRO",
                    favoring = if (atrv != null && (atrv < 11.6 || atrv > 15.2)) listOf("ATRV=%.1f V (solo dongle)".format(atrv)) else emptyList(),
                    against = listOf("ATRV no es batería"),
                    missing = listOf("Medición de batería y masas con multímetro"),
                    nextCheck = "Multímetro en bornes y masas motor/carrocería. Ignorar ATRV como veredicto.",
                ),
            )
            add(
                LiveHypothesis(
                    id = "H3",
                    title = "Limitación de par / estrategia ECU",
                    status = "INSUFICIENTE — OBD genérico no lee modo de limitación",
                    favoring = if (powerLossOnBrake) listOf("Síntoma de pérdida de potencia (relato)") else emptyList(),
                    against = emptyList(),
                    missing = listOf("Parámetros OEM de torque / escáner JAC"),
                    nextCheck = "No inventar PID de torque. Pedir lectura de marca.",
                ),
            )
        }

        val headline = when {
            powerLossOnBrake && unique.isNotEmpty() ->
                "Situación abierta: relato de corte al frenar + ${unique.joinToString()}"
            powerLossOnBrake ->
                "Situación abierta: corte al frenar (sin DTC en esta vista)"
            unique.isNotEmpty() ->
                "Avisos presentes: ${unique.joinToString()}. Falta correlacionar con sensores y banco."
            polling ->
                "Monitor activo — interprete valores vivos; no hay pieza condenada."
            else ->
                "Sin flujo vivo — inicie monitor o lea DTC para armar la situación."
        }

        val nextActions = buildList {
            if (linkPhase < LinkPhase.ECU_RESPONDING) add("Conectar ELM, contacto ON, lograr ECU responde.")
            if (!bitmapRead) add("Leer mapa PID (Capacidad).")
            if (!polling) add("Iniciar monitor ordenado (quieta).")
            add("Captura ANTES → trabajo → DESPUÉS.")
            add("Medir freno y APP con multímetro (no hay PID de freno).")
            if (powerLossOnBrake) add("SAFE-001: no probar el corte en la calle.")
            add("Llevar PDF + esta lectura al escáner JAC sin borrar.")
        }

        return LiveSituationReport(
            headline = headline,
            operational = opLabel,
            sensorLines = sensorLines,
            hypotheses = hypotheses,
            nextActions = nextActions,
        )
    }
}

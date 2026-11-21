package app.jac.diagnosticlab.dynamic

import java.util.Locale

enum class ForensicKind(val label: String) { STALL("MOTOR DETENIDO"), RPM_COLLAPSE("CAÍDA BRUSCA DE RPM") }

/** Un evento con el contexto de ±segundos. Todo son observables de PIDs; ninguno es el estado del interruptor de freno. */
data class ForensicEvent(
    val atMs: Long,
    val kind: ForensicKind,
    val rpmBefore: Double,
    val rpmAfter: Double,
    val speedBefore: Double?,
    val speedAt: Double?,
    val pedalD: Double?,
    val pedalE: Double?,
    val voltBefore: Double?,
    val voltMin: Double?,
    val railDevPct: Double?,
    val boostDeficit: Double?,
    val decelerating: Boolean,
    val stopped: Boolean,
)

enum class Support(val label: String) { COMPATIBLE("compatible con los datos"), NOT_SUPPORTED("no respaldada por los datos"), NO_DATA("sin datos para juzgar") }

data class Hypothesis(val name: String, val support: Support, val evidence: String, val nextMeasurement: String)

data class ForensicAssessment(val events: List<ForensicEvent>, val hypotheses: List<Hypothesis>, val summary: String)

/**
 * Detección y correlación de «el motor se corta / cae al frenar o detenerse».
 * Una caída de RPM con el pedal suelto (levantar el pie, embrague) es normal y NO se cuenta:
 * solo paros (RPM < 300 viniendo de ≥ 600) y caídas bruscas con el pedal aún pisado.
 */
object EventForensics {
    private const val LOOKBACK_MS = 2_000L
    private const val DEDUP_MS = 10_000L
    private const val CTX_BEFORE_MS = 5_000L
    private const val CTX_AFTER_MS = 3_000L

    private fun window(fr: List<TraceFrame>, from: Long, to: Long) = fr.filter { it.atMs in from..to }
    private fun List<TraceFrame>.vals(key: String) = mapNotNull { it.values[key] }

    fun detect(frames: List<TraceFrame>): List<ForensicEvent> {
        val fr = frames.sortedBy { it.atMs }
        val out = mutableListOf<ForensicEvent>()
        var last = Long.MIN_VALUE
        for (f in fr) {
            val rpm = f.values[GuidedTests.RPM] ?: continue
            if (f.atMs - last < DEDUP_MS) continue
            val prior = window(fr, f.atMs - LOOKBACK_MS, f.atMs - 1).vals(GuidedTests.RPM)
            val prevMax = prior.maxOrNull() ?: continue
            val pedalNow = window(fr, f.atMs - 1_000, f.atMs).vals(GuidedTests.PEDAL).lastOrNull()
            val pedalPrior = window(fr, f.atMs - 1_500, f.atMs).vals(GuidedTests.PEDAL)
            val kind = when {
                rpm < 300 && prevMax >= 600 -> ForensicKind.STALL
                prevMax >= 1000 && rpm >= 300 && rpm <= prevMax * 0.6 &&
                    pedalNow != null && pedalNow >= 10 && (pedalPrior.maxOrNull() ?: 0.0) >= 15 -> ForensicKind.RPM_COLLAPSE
                else -> null
            } ?: continue
            last = f.atMs
            val ctx = window(fr, f.atMs - CTX_BEFORE_MS, f.atMs + CTX_AFTER_MS)
            val before = window(fr, f.atMs - CTX_BEFORE_MS, f.atMs)
            val speedBefore = before.vals(GuidedTests.SPEED).maxOrNull()
            val speedAt = window(fr, f.atMs - 1_000, f.atMs + 1_000).vals(GuidedTests.SPEED).lastOrNull()
            val volts = ctx.vals(GuidedTests.VOLT)
            val cmd = ctx.vals(GuidedTests.RAIL_CMD); val act = ctx.vals(GuidedTests.RAIL_ACT)
            val railDev = if (cmd.isNotEmpty() && act.isNotEmpty() && cmd.average() > 0) (act.average() - cmd.average()) / cmd.average() * 100 else null
            val bc = ctx.vals(GuidedTests.BOOST_CMD); val ba = ctx.vals(GuidedTests.BOOST_ACT)
            out += ForensicEvent(
                atMs = f.atMs, kind = kind, rpmBefore = prevMax, rpmAfter = rpm,
                speedBefore = speedBefore, speedAt = speedAt,
                pedalD = pedalNow, pedalE = window(fr, f.atMs - 1_000, f.atMs).vals("4A").lastOrNull(),
                voltBefore = before.vals(GuidedTests.VOLT).let { if (it.isEmpty()) null else it.sorted()[it.size / 2] },
                voltMin = volts.minOrNull(),
                railDevPct = railDev,
                boostDeficit = if (bc.isNotEmpty() && ba.isNotEmpty()) bc.max() - ba.min() else null,
                decelerating = speedBefore != null && speedAt != null && speedBefore - speedAt >= 5,
                stopped = speedAt != null && speedAt < 3,
            )
        }
        return out
    }

    private fun pct(n: Int, d: Int) = if (d == 0) 0 else n * 100 / d

    fun assess(frames: List<TraceFrame>): ForensicAssessment {
        val ev = detect(frames)
        if (ev.isEmpty()) return ForensicAssessment(ev, emptyList(),
            "Sin paros ni caídas bruscas de RPM con el pedal pisado en esta captura. Si el síntoma ocurrió igual, la captura no lo registró: repetir en la misma ruta con la app grabando.")
        val n = ev.size
        val stopCtx = ev.count { it.stopped || it.decelerating }
        val withVolt = ev.filter { it.voltMin != null }
        val voltDip = withVolt.count { (it.voltMin ?: 99.0) < 11.5 || ((it.voltBefore ?: 0.0) - (it.voltMin ?: 0.0)) >= 1.5 }
        val withRail = ev.filter { it.railDevPct != null }
        val railBad = withRail.count { (it.railDevPct ?: 0.0) < -20 }
        val withBoost = ev.filter { it.boostDeficit != null }
        val boostBad = withBoost.count { (it.boostDeficit ?: 0.0) > 30 }
        val withPedals = ev.filter { it.pedalD != null && it.pedalE != null && it.pedalE!! > 1 }
        val pedalOdd = withPedals.count { val r = it.pedalD!! / it.pedalE!!; r < 1.4 || r > 2.6 }
        val stalls = ev.count { it.kind == ForensicKind.STALL }

        fun judge(hits: Int, total: Int, thr: Int = 40) = when {
            total == 0 -> Support.NO_DATA
            pct(hits, total) >= thr -> Support.COMPATIBLE
            else -> Support.NOT_SUPPORTED
        }
        val h = listOf(
            Hypothesis(
                "Señal o interruptor de freno (contactos, ajuste, cableado)",
                if (stopCtx * 100 >= n * 60) Support.COMPATIBLE else Support.NOT_SUPPORTED,
                "$stopCtx de $n eventos ocurrieron frenando o con el vehículo detenido (inferido de la velocidad: la app NO lee el interruptor). Coincide con P0579/P2299 si están presentes.",
                "Multímetro en los DOS contactos del interruptor: pedal suelto y pisado; luces de freno; ajuste contra el pedal; mover el mazo con el motor en ralentí y ver si cae.",
            ),
            Hypothesis(
                "Alimentación eléctrica de la ECU (batería, masas, conector, relé)",
                judge(voltDip, withVolt.size),
                if (withVolt.isEmpty()) "No hubo lectura de tensión (PID 42) alrededor de los eventos." else "$voltDip de ${withVolt.size} eventos con caída de tensión (< 11,5 V o ≥ 1,5 V).",
                "Caída de tensión en positivo y masa de la ECU con el motor en marcha; apriete y limpieza de bornes y masas; osciloscopio en el pin de alimentación durante el corte.",
            ),
            Hypothesis(
                "Sensor de pedal (pistas D/E)",
                judge(pedalOdd, withPedals.size),
                if (withPedals.isEmpty()) "Sin pista E (PID 4A) en los eventos." else "$pedalOdd de ${withPedals.size} eventos con relación D/E fuera de lo esperable (1,4–2,6; referencia genérica).",
                "Dos pistas con el pedal quieto en reposo y a fondo; comparar con el valor de manual JAC (no público).",
            ),
            Hypothesis(
                "Presión de riel / combustible",
                judge(railBad, withRail.size),
                if (withRail.isEmpty()) "Sin riel comandado y real en los eventos." else "$railBad de ${withRail.size} eventos con riel real > 20 % bajo lo comandado.",
                "Filtro, aire en baja presión, bomba y regulador; riel real contra comandado en ruta con el mismo registro.",
            ),
            Hypothesis(
                "Aire / turbo",
                judge(boostBad, withBoost.size),
                if (withBoost.isEmpty()) "Sin turbo comandado y real en los eventos." else "$boostBad de ${withBoost.size} eventos con turbo > 30 kPa bajo lo comandado.",
                "Mangueras y actuador del turbo; MAP contra baro con motor apagado.",
            ),
        )
        val ranked = h.sortedBy { when (it.support) { Support.COMPATIBLE -> 0; Support.NO_DATA -> 1; Support.NOT_SUPPORTED -> 2 } }
        val summary = "%d evento(s): %d paro(s) de motor y %d caída(s) brusca(s) con el pedal pisado; %d asociados a frenado/detención. ".format(Locale.ROOT, n, stalls, n - stalls, stopCtx) +
            "Compatibles: ${ranked.filter { it.support == Support.COMPATIBLE }.joinToString { it.name }.ifBlank { "ninguna con los datos disponibles" }}. " +
            "Esto ordena qué medir; no identifica la pieza. Falta siempre el estado eléctrico del interruptor de freno."
        return ForensicAssessment(ev, ranked, summary)
    }
}

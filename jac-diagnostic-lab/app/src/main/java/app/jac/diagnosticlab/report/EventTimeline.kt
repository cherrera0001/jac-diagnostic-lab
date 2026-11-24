package app.jac.diagnosticlab.report

import app.jac.diagnosticlab.dynamic.GuidedTests
import app.jac.diagnosticlab.dynamic.TraceEvent
import app.jac.diagnosticlab.dynamic.TraceFrame
import kotlin.math.roundToInt

/** Una fila de la cronología: el evento y el último valor conocido (≤ 2 s antes) de cada señal clave. */
data class EventRow(
    val atMs: Long,
    val kind: String,
    val detail: String,
    val speedKmh: Double?,
    val rpm: Double?,
    val pedalD: Double?,
    val pedalE: Double?,
    val railKpa: Double?,
)

/** Series (segundos relativos al evento, valor) para dibujar la línea de tiempo alrededor de un evento. */
data class EventWindow(val event: TraceEvent, val series: Map<String, List<Pair<Float, Float>>>)

/** SPEC-035 R4/R5. Lógica pura: sin Android, para poder probarla. */
object EventTimeline {
    const val LOOKBACK_MS = 2_000L
    const val BEFORE_MS = 6_000L
    const val AFTER_MS = 4_000L
    val CHART_KEYS = listOf(GuidedTests.RPM, GuidedTests.PEDAL, GuidedTests.PEDAL_E, GuidedTests.RAIL_GAUGE, GuidedTests.SPEED)

    private fun lastKnown(frames: List<TraceFrame>, key: String, atMs: Long): Double? {
        for (idx in frames.indices.reversed()) {
            val f = frames[idx]
            if (f.atMs > atMs) continue
            if (atMs - f.atMs > LOOKBACK_MS) return null
            f.values[key]?.let { return it }
        }
        return null
    }

    fun rows(frames: List<TraceFrame>, events: List<TraceEvent>, maxRows: Int = 40): List<EventRow> {
        val sorted = events.sortedBy { it.atMs }
        val picked = if (sorted.size <= maxRows || maxRows < 2) sorted.take(maxRows.coerceAtLeast(0)) else {
            val last = sorted.size - 1
            (0 until maxRows).map { (it * last.toDouble() / (maxRows - 1)).roundToInt() }.distinct().map { sorted[it] }
        }
        val ordered = frames.sortedBy { it.atMs }
        return picked.map { e ->
            EventRow(
                e.atMs, e.kind, e.detail,
                speedKmh = lastKnown(ordered, GuidedTests.SPEED, e.atMs),
                rpm = lastKnown(ordered, GuidedTests.RPM, e.atMs),
                pedalD = lastKnown(ordered, GuidedTests.PEDAL, e.atMs),
                pedalE = lastKnown(ordered, GuidedTests.PEDAL_E, e.atMs),
                railKpa = lastKnown(ordered, GuidedTests.RAIL_GAUGE, e.atMs),
            )
        }
    }

    fun window(frames: List<TraceFrame>, event: TraceEvent, beforeMs: Long = BEFORE_MS, afterMs: Long = AFTER_MS): EventWindow {
        val inWindow = frames.filter { it.atMs in (event.atMs - beforeMs)..(event.atMs + afterMs) }.sortedBy { it.atMs }
        val series = CHART_KEYS.associateWith { key ->
            inWindow.mapNotNull { f -> f.values[key]?.let { v -> ((f.atMs - event.atMs) / 1000f) to v.toFloat() } }
        }
        return EventWindow(event, series)
    }
}

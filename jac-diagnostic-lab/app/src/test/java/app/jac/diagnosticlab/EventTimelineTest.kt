package app.jac.diagnosticlab

import app.jac.diagnosticlab.dynamic.TraceEvent
import app.jac.diagnosticlab.dynamic.TraceFrame
import app.jac.diagnosticlab.report.EventTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC-035 R4/R5. Datos sintéticos. */
class EventTimelineTest {
    private fun frames() = (0..40).map { n ->
        val t = n * 500L
        TraceFrame(
            1_000_000L + t,
            buildMap {
                put("0C", 800.0 + n)
                put("0D", n.toDouble())
                // Pedal y riel solo se leen en rondas pares: el valor «último conocido» debe arrastrarse.
                if (n % 2 == 0) { put("49", 10.0 + n); put("4A", 5.0 + n); put("23", 30_000.0 - n * 100) }
            },
        )
    }

    @Test fun rowsAreChronologicalAndCarryLastKnownValues() {
        val ev = listOf(
            TraceEvent(1_000_000L + 15_000, "MOTOR DETENIDO", "RPM a 0"),
            TraceEvent(1_000_000L + 5_000, "CORTE DE POTENCIA", "par cae"),
        )
        val rows = EventTimeline.rows(frames(), ev)
        assertEquals(listOf("CORTE DE POTENCIA", "MOTOR DETENIDO"), rows.map { it.kind })
        val r = rows[1]
        assertEquals(30.0, r.speedKmh!!, 0.001)         // n = 30 (t = 15 000)
        assertEquals(830.0, r.rpm!!, 0.001)
        assertEquals(40.0, r.pedalD!!, 0.001)
        assertEquals(35.0, r.pedalE!!, 0.001)
        assertEquals(27_000.0, r.railKpa!!, 0.001)
    }

    @Test fun valueOlderThanLookbackIsNotInvented() {
        val old = listOf(TraceFrame(1_000L, mapOf("49" to 50.0)), TraceFrame(20_000L, mapOf("0C" to 700.0)))
        val rows = EventTimeline.rows(old, listOf(TraceEvent(20_500L, "X", "y")))
        assertNull(rows.single().pedalD)
        assertEquals(700.0, rows.single().rpm!!, 0.001)
    }

    @Test fun rowsAreCappedButKeepTheFirstAndLastEvents() {
        val many = (0 until 100).map { TraceEvent(1_000_000L + it * 100L, "E$it", "d") }
        val rows = EventTimeline.rows(frames(), many, maxRows = 30)
        assertEquals(30, rows.size)
        assertEquals("E0", rows.first().kind)
        assertEquals("E99", rows.last().kind)
    }

    @Test fun windowAroundEventIsRelativeSecondsAndBounded() {
        val ev = TraceEvent(1_000_000L + 10_000, "MOTOR DETENIDO", "RPM a 0")
        val w = EventTimeline.window(frames(), ev)
        val rpm = w.series.getValue("0C")
        assertTrue(rpm.first().first >= -6.0f - 0.001f)
        assertTrue(rpm.last().first <= 4.0f + 0.001f)
        assertTrue(rpm.any { it.first == 0.0f })
        // Pedal solo en rondas pares: la serie tiene menos puntos pero nunca valores inventados.
        assertTrue(w.series.getValue("49").size < rpm.size)
    }

    @Test fun eventsWithoutFramesProduceEmptyRowsSafely() {
        assertTrue(EventTimeline.rows(emptyList(), emptyList()).isEmpty())
        val rows = EventTimeline.rows(emptyList(), listOf(TraceEvent(5L, "X", "y")))
        assertNull(rows.single().rpm)
    }
}

package app.jac.diagnosticlab

import app.jac.diagnosticlab.dynamic.EventForensics
import app.jac.diagnosticlab.dynamic.ForensicKind
import app.jac.diagnosticlab.dynamic.Support
import app.jac.diagnosticlab.dynamic.TraceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventForensicsTest {
    private fun f(t: Long, rpm: Double, speed: Double, pedal: Double, volt: Double = 14.2) =
        TraceFrame(t, mapOf("0C" to rpm, "0D" to speed, "49" to pedal, "42" to volt))

    /** Crucero 2000 rpm a 40 km/h, luego el motor cae a 0 al frenar hasta detenerse. */
    private fun stallWhileBraking(t0: Long, volt: (Long) -> Double = { 14.2 }): List<TraceFrame> {
        val l = mutableListOf<TraceFrame>()
        for (i in 0 until 8) l += f(t0 + i * 500L, 2000.0, 40.0, 20.0, volt(i.toLong()))
        l += f(t0 + 4_000, 1900.0, 30.0, 18.0, volt(8))
        l += f(t0 + 4_500, 150.0, 12.0, 16.0, volt(9))
        l += f(t0 + 5_000, 0.0, 3.0, 0.0, volt(10))
        l += f(t0 + 6_000, 0.0, 0.0, 0.0, volt(11))
        return l
    }

    @Test fun detectsStallWhileBrakingAndMarksStopContext() {
        val ev = EventForensics.detect(stallWhileBraking(0))
        assertEquals(1, ev.size)
        assertEquals(ForensicKind.STALL, ev[0].kind)
        assertTrue(ev[0].decelerating || ev[0].stopped)
    }

    @Test fun liftingFootOrClutchIsNotAnEvent() {
        val l = mutableListOf<TraceFrame>()
        for (i in 0 until 6) l += f(i * 500L, 2000.0, 40.0, 20.0)
        l += f(3_000, 900.0, 38.0, 0.0)   // pie fuera, embrague: normal
        l += f(3_500, 850.0, 20.0, 0.0)
        assertTrue(EventForensics.detect(l).isEmpty())
    }

    @Test fun collapseWithPedalHeldIsDetected() {
        val l = mutableListOf<TraceFrame>()
        for (i in 0 until 6) l += f(i * 400L, 2400.0, 60.0, 35.0)
        l += f(2_600, 800.0, 58.0, 35.0)  // corte con pedal pisado
        val ev = EventForensics.detect(l)
        assertEquals(1, ev.size); assertEquals(ForensicKind.RPM_COLLAPSE, ev[0].kind)
    }

    @Test fun tenEventsAreSeparatedAndNeverDeclareDefinitiveCause() {
        val l = (0 until 10).flatMap { stallWhileBraking(it * 120_000L) }
        val a = EventForensics.assess(l)
        assertEquals(10, a.events.size)
        val brake = a.hypotheses.first { it.name.startsWith("Señal o interruptor de freno") }
        assertEquals(Support.COMPATIBLE, brake.support)
        assertTrue(a.summary.contains("no identifica la pieza"))
        assertTrue(a.summary.contains("Falta siempre"))
    }

    @Test fun voltageDipPromotesElectricalHypothesis() {
        val l = (0 until 4).flatMap { k -> stallWhileBraking(k * 120_000L) { i -> if (i in 8..10) 10.4 else 14.2 } }
        val e = EventForensics.assess(l).hypotheses.first { it.name.startsWith("Alimentación eléctrica") }
        assertEquals(Support.COMPATIBLE, e.support)
    }

    @Test fun noVoltageDataIsReportedAsNoDataNotAsRuledOut() {
        val l = stallWhileBraking(0).map { TraceFrame(it.atMs, it.values - "42") }
        val e = EventForensics.assess(l).hypotheses.first { it.name.startsWith("Alimentación eléctrica") }
        assertEquals(Support.NO_DATA, e.support)
    }

    @Test fun emptyCaptureSaysSoWithoutClaimingHealthy() {
        val a = EventForensics.assess(listOf(f(0, 800.0, 0.0, 0.0), f(500, 800.0, 0.0, 0.0)))
        assertTrue(a.events.isEmpty()); assertTrue(a.summary.contains("no lo registró"))
    }
}

package app.jac.diagnosticlab

import app.jac.diagnosticlab.tracker.DirectionEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

class DirectionEstimatorTest {
    private fun circDiff(a: Double, b: Double) = abs(((a - b) % 360 + 540) % 360 - 180)

    /** Señal más fuerte hacia [peakDeg]: -55 dBm de frente, -75 dBm de espaldas (cuerpo apantallando). */
    private fun sweep(e: DirectionEstimator, peakDeg: Double, t0: Long = 0, step: Int = 10) {
        var t = t0
        var h = 0
        while (h < 360) { e.add(h.toDouble(), (-65 + 10 * cos(Math.toRadians(h - peakDeg))).toInt(), t); t += 100; h += step }
    }

    @Test fun pointsTowardStrongestSector() {
        for (peak in listOf(0.0, 90.0, 200.0, 315.0)) {
            val e = DirectionEstimator(); sweep(e, peak)
            val est = e.estimate(5_000)!!
            assertTrue("peak $peak got ${est.bearingDeg}", circDiff(est.bearingDeg, peak) <= 20)
            assertTrue(est.clear)
        }
    }
    @Test fun wrapsAroundNorth() {
        val e = DirectionEstimator(); sweep(e, 355.0)
        assertTrue(circDiff(e.estimate(5_000)!!.bearingDeg, 355.0) <= 20)
    }
    @Test fun notEnoughTurningGivesNoEstimate() {
        val e = DirectionEstimator()
        for (h in 0 until 60 step 10) e.add(h.toDouble(), -60, h * 10L)
        assertNull(e.estimate(1_000))
    }
    @Test fun flatSignalIsNotClear() {
        val e = DirectionEstimator()
        for (h in 0 until 360 step 10) e.add(h.toDouble(), -70, h * 10L)
        val est = e.estimate(5_000)
        assertNotNull(est); assertFalse(est!!.clear)
    }
    @Test fun oldSamplesExpire() {
        val e = DirectionEstimator(maxAgeMs = 5_000); sweep(e, 90.0)
        assertNull(e.estimate(60_000))
    }
    @Test fun resetClears() {
        val e = DirectionEstimator(); sweep(e, 90.0); e.reset()
        assertNull(e.estimate(5_000)); assertEquals(0.0, e.coverage(5_000), 0.0)
    }
    @Test fun relativeAngleIsSignedShortestTurn() {
        assertEquals(0.0, DirectionEstimator.relative(90.0, 90.0), 1e-9)
        assertEquals(90.0, DirectionEstimator.relative(180.0, 90.0), 1e-9)
        assertEquals(-90.0, DirectionEstimator.relative(0.0, 90.0), 1e-9)
        assertEquals(20.0, DirectionEstimator.relative(10.0, 350.0), 1e-9)
        assertEquals(180.0, DirectionEstimator.relative(270.0, 90.0), 1e-9)
    }
}

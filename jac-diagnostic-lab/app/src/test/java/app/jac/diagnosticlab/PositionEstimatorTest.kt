package app.jac.diagnosticlab

import app.jac.diagnosticlab.tracker.Geo
import app.jac.diagnosticlab.tracker.GeoPoint
import app.jac.diagnosticlab.tracker.PositionEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

class PositionEstimatorTest {
    private val origin = GeoPoint(-33.4500, -70.6600, 4f)
    private val cosLat = cos(Math.toRadians(origin.lat))

    private fun offset(eM: Double, nM: Double, acc: Float? = 4f) = GeoPoint(
        lat = origin.lat + nM / 110_540.0,
        lon = origin.lon + eM / (111_320.0 * cosLat),
        accuracyM = acc,
    )

    private fun synthRssi(txE: Double, txN: Double, e: Double, n: Double, a: Double = -58.0, exp: Double = 2.4, noise: Double = 0.0): Int {
        val d = max(1.0, hypot(e - txE, n - txN))
        return (a - 10.0 * exp * log10(d) + noise).roundToInt()
    }

    @Test fun geoDistanceAndBearingCardinals() {
        val north = offset(0.0, 50.0)
        val east = offset(50.0, 0.0)
        val south = offset(0.0, -50.0)
        val west = offset(-50.0, 0.0)
        assertEquals(50.0, Geo.distanceM(origin, north), 0.5)
        assertEquals(50.0, Geo.distanceM(origin, east), 0.5)
        assertEquals(0.0, Geo.bearingDeg(origin, north), 1.0)
        assertEquals(90.0, Geo.bearingDeg(origin, east), 1.0)
        assertEquals(180.0, Geo.bearingDeg(origin, south), 1.0)
        assertEquals(270.0, Geo.bearingDeg(origin, west), 1.0)
    }

    @Test fun lShapedWalkLocatesStaticTag() {
        val pe = PositionEstimator()
        val rng = Random(42L)
        val txE = 15.0; val txN = 15.0
        val path = (0..10).map { it * 2.0 to 0.0 } + (1..10).map { 20.0 to it * 2.0 }
        for ((e, n) in path) {
            val rssi = synthRssi(txE, txN, e, n, noise = rng.nextGaussian() * 2.0)
            pe.add(offset(e, n), rssi)
        }
        val est = pe.estimate()
        assertNotNull(est)
        assertTrue("Expected usable estimate, got $est", est!!.usable)
        val err = Geo.distanceM(est.point, offset(txE, txN))
        assertTrue("Error ${err}m should stay within reasonable bound (radius=${est.radiusM}m)", err <= max(12.0, est.radiusM))
        assertTrue(est.r2 >= 0.35)
        assertTrue(est.rmsDb <= 8.0)
    }

    @Test fun straightLineWalkIsRejectedAsAmbiguous() {
        val pe = PositionEstimator()
        // Caminar en línea recta deja el tag y su reflejo al otro lado igual de plausibles.
        for (i in 0..15) {
            val e = i * 2.0
            pe.add(offset(e, 0.0), synthRssi(15.0, 14.0, e, 0.0))
        }
        val est = pe.estimate()
        assertTrue("Straight walk must not produce a usable fix, got $est", est == null || !est.usable)
    }

    @Test fun pureHighNoiseIsRejected() {
        val pe = PositionEstimator()
        val rng = Random(7L)
        val path = (0..10).map { it * 2.0 to 0.0 } + (1..10).map { 20.0 to it * 2.0 }
        for ((e, n) in path) {
            pe.add(offset(e, n), -95 + rng.nextInt(45))
        }
        val est = pe.estimate()
        assertTrue("Random noise must not look confident, got $est", est == null || !est.usable)
    }

    @Test fun stationaryJitterMergesIntoSingleSpatialPoint() {
        val pe = PositionEstimator()
        for (i in 0 until 25) {
            pe.add(offset((i % 2) * 0.4, 0.0), if (i % 2 == 0) -60 else -72)
        }
        assertEquals(1, pe.count)
        assertNull(pe.estimate())
    }

    @Test fun lowAccuracyGpsPointsAreIgnored() {
        val pe = PositionEstimator(maxAccuracyM = 30f)
        for (i in 0..12) pe.add(offset(i * 3.0, 0.0, acc = 55f), -60 - i)
        assertEquals(0, pe.count)
        assertNull(pe.estimate())
    }

    @Test fun resetClearsSamplesAndBumpsVersion() {
        val pe = PositionEstimator()
        pe.add(offset(0.0, 0.0), -60)
        val vBefore = pe.version
        pe.reset()
        assertEquals(0, pe.count)
        assertTrue(pe.version > vBefore)
        assertNull(pe.estimate())
    }
}

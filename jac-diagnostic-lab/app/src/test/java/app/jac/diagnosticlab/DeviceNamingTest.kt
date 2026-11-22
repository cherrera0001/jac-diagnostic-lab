package app.jac.diagnosticlab

import app.jac.diagnosticlab.tracker.Advert
import app.jac.diagnosticlab.tracker.DeviceNaming
import app.jac.diagnosticlab.tracker.GeoPoint
import app.jac.diagnosticlab.tracker.SightingBook
import app.jac.diagnosticlab.tracker.TrackerClassifier
import app.jac.diagnosticlab.tracker.TrackerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

class DeviceNamingTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun advertisedNameTakesPriority() {
        val ad = Advert(manufacturer = mapOf(0x038F to b(1)), name = "Mi Band 7")
        val d = DeviceNaming.display(ad, TrackerClassifier.classify(ad))
        assertTrue(d.named)
        assertEquals("Mi Band 7", d.title)
        assertTrue(d.subtitle.contains("Xiaomi"))
    }

    @Test fun trackerWithoutNameUsesTrackerLabel() {
        val ad = Advert(manufacturer = mapOf(0x004C to b(0x12, 0x19)))
        val d = DeviceNaming.display(ad, TrackerClassifier.classify(ad))
        assertFalse(d.named)
        assertEquals(TrackerKind.APPLE_FIND_MY.label, d.title)
        assertTrue(d.subtitle.contains("sin nombre anunciado"))
    }

    @Test fun appleSubtypesAndServicesInferred() {
        val pods = Advert(manufacturer = mapOf(0x004C to b(0x07, 0x19)))
        val dp = DeviceNaming.display(pods, TrackerClassifier.classify(pods))
        assertFalse(dp.named)
        assertEquals("Apple · AirPods / Beats", dp.title)

        val fastPair = Advert(serviceUuids = setOf("fe2c"))
        val df = DeviceNaming.display(fastPair, TrackerClassifier.classify(fastPair))
        assertEquals("Google Fast Pair", df.title)
    }

    @Test fun emptyAdvertFallsBackCleanly() {
        val ad = Advert()
        val d = DeviceNaming.display(ad, TrackerClassifier.classify(ad))
        assertFalse(d.named)
        assertEquals("Dispositivo BLE sin nombre", d.title)
    }

    @Test fun resolvedTitlePrefersNickname() {
        val ad = Advert(name = "OBDII")
        val c = TrackerClassifier.classify(ad)
        assertEquals("Mi escáner", DeviceNaming.resolvedTitle(ad, c, "  Mi escáner "))
        assertEquals("OBDII", DeviceNaming.resolvedTitle(ad, c, "   "))
    }

    @Test fun sightingBookMergesScanResponseAndPreservesCompleteName() {
        val bk = SightingBook()
        bk.observe("AA:11", Advert(manufacturer = mapOf(0x038F to b(1))), -70, 1_000, null)
        bk.observe("AA:11", Advert(name = "Mi Band 7 Pro"), -68, 2_000, null)
        val s = bk.observe("AA:11", Advert(name = "Mi Band"), -67, 3_000, null)
        assertEquals("Mi Band 7 Pro", s.advert.name)
        assertEquals(TrackerKind.XIAOMI_DEVICE, s.classification.kind)
    }

    @Test fun sightingBookCapturesBestPointAfterLateGpsLock() {
        val bk = SightingBook()
        bk.observe("BB:22", Advert(name = "Tag"), -55, 1_000, null)
        val s = bk.observe("BB:22", Advert(), -72, 2_000, GeoPoint(-33.45, -70.66, 5f))
        assertNotNull(s.bestPoint)
        assertEquals(-33.45, s.bestPoint!!.lat, 1e-9)
        assertEquals(-55, s.bestRssi)
    }

    @Test fun sightingBookFeedsBackgroundPositionEstimator() {
        val bk = SightingBook()
        val oLat = -33.45; val oLon = -70.66; val cosLat = cos(Math.toRadians(oLat))
        val path = (0..10).map { it * 2.0 to 0.0 } + (1..10).map { 20.0 to it * 2.0 }
        var t = 1_000L
        for ((e, n) in path) {
            val d = max(1.0, hypot(e - 15.0, n - 15.0))
            val rssi = (-58.0 - 24.0 * log10(d)).roundToInt()
            val pt = GeoPoint(oLat + n / 110_540.0, oLon + e / (111_320.0 * cosLat), 4f)
            bk.observe("CC:33", Advert(name = "Llaves"), rssi, t, pt)
            t += 1_000L
        }
        val est = bk.positionEstimator("CC:33").estimate()
        assertNotNull(est)
        assertTrue(est!!.usable)
    }
}

package app.jac.diagnosticlab

import app.jac.diagnosticlab.tracker.Advert
import app.jac.diagnosticlab.tracker.Confidence
import app.jac.diagnosticlab.tracker.GeoPoint
import app.jac.diagnosticlab.tracker.PositionEstimate
import app.jac.diagnosticlab.tracker.ProximityBand
import app.jac.diagnosticlab.tracker.SightingBook
import app.jac.diagnosticlab.tracker.TrackerClassifier
import app.jac.diagnosticlab.tracker.TrackerExport
import app.jac.diagnosticlab.tracker.TrackerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun appleFindMyByType12() {
        val c = TrackerClassifier.classify(Advert(manufacturer = mapOf(0x004C to b(0x12, 0x19, 0x00))))
        assertEquals(TrackerKind.APPLE_FIND_MY, c.kind); assertEquals(Confidence.HIGH, c.confidence)
        assertTrue(c.note.contains("Xiaomi Tag"))
    }
    @Test fun appleNonFindMyIsNotATracker() =
        assertEquals(TrackerKind.UNKNOWN, TrackerClassifier.classify(Advert(manufacturer = mapOf(0x004C to b(0x10, 0x05)))).kind)
    @Test fun googleFindHubByFeaaFrame() {
        val c = TrackerClassifier.classify(Advert(serviceData = mapOf("feaa" to b(0x41, 1, 2))))
        assertEquals(TrackerKind.GOOGLE_FIND_HUB, c.kind); assertEquals(Confidence.MEDIUM, c.confidence)
        assertTrue(c.basis.contains("sin validar"))
    }
    @Test fun eddystoneUidFrameIsNotFindHub() =
        assertEquals(TrackerKind.UNKNOWN, TrackerClassifier.classify(Advert(serviceData = mapOf("feaa" to b(0x00, 1, 2)))).kind)
    @Test fun xiaomiManufacturerIsLowConfidenceAndNotClaimedAsTag() {
        val c = TrackerClassifier.classify(Advert(manufacturer = mapOf(0x038F to b(1))))
        assertEquals(TrackerKind.XIAOMI_DEVICE, c.kind); assertEquals(Confidence.LOW, c.confidence)
    }
    @Test fun otherBrandsByService() {
        assertEquals(TrackerKind.SAMSUNG_SMARTTAG, TrackerClassifier.classify(Advert(serviceUuids = setOf("fd5a"))).kind)
        assertEquals(TrackerKind.TILE, TrackerClassifier.classify(Advert(serviceUuids = setOf("feed"))).kind)
    }
    @Test fun short16Uuid() {
        assertEquals("fd5a", TrackerClassifier.short16("0000FD5A-0000-1000-8000-00805F9B34FB"))
        assertEquals("12345678-0000-0000-0000-000000000000", TrackerClassifier.short16("12345678-0000-0000-0000-000000000000"))
    }
    @Test fun proximityBands() {
        assertEquals(ProximityBand.VERY_CLOSE, ProximityBand.of(-50.0)); assertEquals(ProximityBand.CLOSE, ProximityBand.of(-65.0))
        assertEquals(ProximityBand.MEDIUM, ProximityBand.of(-80.0)); assertEquals(ProximityBand.FAR, ProximityBand.of(-95.0))
    }

    @Test fun bookKeepsBestPointAndTrend() {
        val bk = SightingBook()
        val ad = Advert(manufacturer = mapOf(0x004C to b(0x12)))
        bk.observe("AA:01", ad, -90, 1_000, GeoPoint(-33.0, -70.0))
        bk.observe("AA:01", ad, -60, 2_000, GeoPoint(-33.001, -70.001))
        val s = bk.observe("AA:01", ad, -75, 3_000, GeoPoint(-33.002, -70.002))
        assertEquals(-60, s.bestRssi); assertEquals(-33.001, s.bestPoint!!.lat, 1e-9); assertEquals(3, s.count)
        assertEquals(-33.002, s.lastPoint!!.lat, 1e-9)
    }
    @Test fun trendFollowsSmoothedSignal() {
        val bk = SightingBook(alpha = 0.5)
        val ad = Advert()
        bk.observe("x", ad, -90, 1, null)
        assertEquals(1, bk.observe("x", ad, -60, 2, null).trend)
        assertEquals(-1, bk.observe("x", ad, -95, 3, null).trend)
    }
    @Test fun noPointMeansNotInGeoJson() {
        val bk = SightingBook(); bk.observe("x", Advert(), -70, 1, null)
        assertFalse(TrackerExport.geoJson(bk.all()).contains("\"Point\""))
    }
    @Test fun geoJsonUsesLonLatOrderAndGpxEscapes() {
        val bk = SightingBook(); bk.observe("AA:BB", Advert(manufacturer = mapOf(0x004C to b(0x12))), -50, 1, GeoPoint(-33.45, -70.66))
        val g = TrackerExport.geoJson(bk.all())
        assertTrue(g.indexOf("-70.66") < g.indexOf("-33.45"))
        val x = TrackerExport.gpx(bk.all())
        assertTrue(x.contains("lat=\"-33.45\" lon=\"-70.66\""))
        assertTrue(TrackerExport.geoUri(GeoPoint(-33.45, -70.66), "Tag & co").startsWith("geo:-33.45,-70.66?q="))
    }
    @Test fun exportPrefersUsableEstimateAndIncludesNickname() {
        val bk = SightingBook()
        bk.observe("AA:BB", Advert(name = "Mi Tag"), -70, 1, GeoPoint(-33.45, -70.66))
        val est = PositionEstimate(GeoPoint(-33.46, -70.67), 9.0, 2.2, -58.0, 2.1, 0.82, 16, usable = true)
        val g = TrackerExport.geoJson(bk.all(), mapOf("AA:BB" to "Llaves camioneta"), mapOf("AA:BB" to est))
        assertTrue(g.contains("\"displayName\": \"Llaves camioneta\""))
        assertTrue(g.contains("\"pointSource\": \"estimated\""))
        assertTrue(g.contains("-70.67"))
        val x = TrackerExport.gpx(bk.all(), mapOf("AA:BB" to "Llaves & Tag"), mapOf("AA:BB" to est))
        assertTrue(x.contains("Llaves &amp; Tag"))
        assertTrue(x.contains("lat=\"-33.46\" lon=\"-70.67\""))
    }
}

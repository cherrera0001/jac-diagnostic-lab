package app.jac.diagnosticlab

import app.jac.diagnosticlab.tracker.Advert
import app.jac.diagnosticlab.tracker.BleAdParser
import app.jac.diagnosticlab.tracker.DeviceCategory
import app.jac.diagnosticlab.tracker.DeviceNaming
import app.jac.diagnosticlab.tracker.GattInfo
import app.jac.diagnosticlab.tracker.SightingBook
import app.jac.diagnosticlab.tracker.TrackerClassifier
import app.jac.diagnosticlab.tracker.TrackerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleAdParserTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun parsesGapAppearanceFromRawScanRecord() {
        // AD 1: Flags [02, 01, 06]; AD 2: Appearance [03, 19, 41, 09] -> 0x0941 (Earbud)
        val raw = b(0x02, 0x01, 0x06, 0x03, 0x19, 0x41, 0x09, 0x00)
        assertEquals(0x0941, BleAdParser.parseAppearance(raw))
        assertEquals("Audífonos intraurales", BleAdParser.appearanceLabel(0x0941))

        val tagRaw = b(0x03, 0x19, 0x00, 0x02)
        assertEquals(0x0200, BleAdParser.parseAppearance(tagRaw))
        assertEquals("Etiqueta / Tag", BleAdParser.appearanceLabel(0x0200))
        assertNull(BleAdParser.parseAppearance(b(0x02, 0x01, 0x06)))
    }

    @Test fun parsesMultiTlvAppleContinuityAndDetectsFindMySecondBlock() {
        // TLV 1: Nearby Info 0x10 len 2 [01, 02]; TLV 2: Find My 0x12 len 2 [19, 00]
        val payload = b(0x10, 0x02, 0x01, 0x02, 0x12, 0x02, 0x19, 0x00)
        assertEquals(listOf(0x10, 0x12), BleAdParser.appleSubtypes(payload))
        val c = TrackerClassifier.classify(Advert(manufacturer = mapOf(0x004C to payload)))
        assertEquals(TrackerKind.APPLE_FIND_MY, c.kind)
        assertEquals(DeviceCategory.TRACKER, c.category)
    }

    @Test fun parsesDultResponses() {
        val mfr = byteArrayOf(0x04, 0x08) + "Xiaomi".toByteArray(Charsets.UTF_8)
        val parsed = BleAdParser.parseDultResponse(mfr)
        assertNotNull(parsed)
        assertEquals(0x0804, parsed!!.first)
        assertEquals("Xiaomi", parsed.second.toString(Charsets.UTF_8))
        assertEquals("Rastreador / Tag", BleAdParser.dultCategoryLabel(1))
        assertEquals("Llaves", BleAdParser.dultCategoryLabel(6))
    }

    @Test fun differentiatesDeviceCategoriesCleanly() {
        // Google Find Hub normal (0x40) vs alerta (0x41)
        val fmdn = ByteArray(22).also { it[0] = 0x41 }
        val cFmdn = TrackerClassifier.classify(Advert(serviceData = mapOf("feaa" to fmdn)))
        assertEquals(TrackerKind.GOOGLE_FIND_HUB, cFmdn.kind)
        assertEquals(DeviceCategory.TRACKER, cFmdn.category)
        assertTrue(cFmdn.basis.contains("0x41"))

        // Xiaomi MiBeacon (0xFE95) -> Wearable
        val cMi = TrackerClassifier.classify(Advert(serviceData = mapOf("fe95" to b(0x30, 0x58, 0x5B, 0x05))))
        assertEquals(TrackerKind.XIAOMI_DEVICE, cMi.kind)
        assertEquals(DeviceCategory.WEARABLE, cMi.category)

        // Audio: AirPods, Fast Pair, Harman/JBL
        assertEquals(DeviceCategory.AUDIO, TrackerClassifier.classify(Advert(manufacturer = mapOf(0x004C to b(0x07, 0x02, 0x01, 0x02)))).category)
        assertEquals(DeviceCategory.AUDIO, TrackerClassifier.classify(Advert(serviceUuids = setOf("fe2c"))).category)
        assertEquals(DeviceCategory.AUDIO, TrackerClassifier.classify(Advert(manufacturer = mapOf(0x0057 to b(1)))).category)

        // Phone/PC: Apple Nearby 0x10 & Microsoft Swift Pair 0x0006
        assertEquals(DeviceCategory.PHONE_PC, TrackerClassifier.classify(Advert(manufacturer = mapOf(0x004C to b(0x10, 0x02, 0x01, 0x02)))).category)
        assertEquals(DeviceCategory.PHONE_PC, TrackerClassifier.classify(Advert(manufacturer = mapOf(0x0006 to b(0x03, 0x00)))).category)

        // TV/Home, Peripheral, Vehicle, Beacon
        assertEquals(DeviceCategory.TV_HOME, TrackerClassifier.classify(Advert(manufacturer = mapOf(0x004C to b(0x09, 0x02, 0x01, 0x02)))).category)
        assertEquals(DeviceCategory.PERIPHERAL, TrackerClassifier.classify(Advert(serviceUuids = setOf("1812"))).category)
        assertEquals(DeviceCategory.VEHICLE, TrackerClassifier.classify(Advert(name = "OBDBLE")).category)
        assertEquals(DeviceCategory.BEACON, TrackerClassifier.classify(Advert(manufacturer = mapOf(0x004C to b(0x02, 0x02, 0x01, 0x02)))).category)
    }

    @Test fun attachGattUpgradesAnonymousTrackerWithDultIdentity() {
        val bk = SightingBook()
        val fmdn = ByteArray(22).also { it[0] = 0x40 }
        bk.observe("AA:CC", Advert(serviceData = mapOf("feaa" to fmdn)), -64, 1_000, null)
        val gatt = GattInfo(
            manufacturer = "Xiaomi",
            model = "Xiaomi Tag",
            dultCategory = "Rastreador / Tag",
            batteryPercent = 92,
            statusNote = "Datos leídos vía GATT/DULT.",
        )
        val updated = bk.attachGatt("AA:CC", gatt)
        assertNotNull(updated)
        val disp = DeviceNaming.display(updated!!.advert, updated.classification, updated.gattInfo)
        assertTrue(disp.named)
        assertEquals("Xiaomi Tag", disp.title)
        assertTrue(disp.subtitle.contains("Batería 92%"))
        assertEquals(DeviceCategory.TRACKER, updated.classification.category)
    }
}

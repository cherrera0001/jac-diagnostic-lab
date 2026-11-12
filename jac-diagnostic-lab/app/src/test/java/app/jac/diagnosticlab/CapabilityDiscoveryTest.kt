package app.jac.diagnosticlab

import app.jac.diagnosticlab.obd.AbsenceReason
import app.jac.diagnosticlab.obd.ObdCapabilityDiscoveryEngine
import app.jac.diagnosticlab.obd.PidDecoder
import app.jac.diagnosticlab.obd.SupportStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityDiscoveryTest {

    @Test
    fun testCap001_bitmapDeclaresRpm() {
        // BE 1F A8 13 — sesión de referencia / ProtocolTest
        val supported = PidDecoder.supported(0x00, listOf(0xBE, 0x1F, 0xA8, 0x13))
        assertTrue(0x0C in supported)
        val snap = ObdCapabilityDiscoveryEngine.build(
            supportedBitmap = supported,
            bitmapRead = true,
            ecuTalking = true,
            adapterConnected = true,
            sourceEcu = "7E8",
        )
        val rpm = snap.entries.first { it.pid == 0x0C }
        assertEquals(SupportStatus.DECLARED_SUPPORTED, rpm.supportStatus)
        assertEquals(AbsenceReason.NONE, rpm.absenceReason)
    }

    @Test
    fun testCap002_pidOutsideBitmap() {
        val supported = PidDecoder.supported(0x00, listOf(0xBE, 0x1F, 0xA8, 0x13))
        val snap = ObdCapabilityDiscoveryEngine.build(supported, true, true, true)
        val appD = snap.entries.first { it.pid == 0x49 }
        // 0x49 may or may not be in this bitmap — assert rule: if not in set → UNSUPPORTED
        if (0x49 !in supported) {
            assertEquals(SupportStatus.DECLARED_UNSUPPORTED, appD.supportStatus)
            assertEquals(AbsenceReason.PID_NOT_IN_BITMAP, appD.absenceReason)
        }
        val ambient = snap.entries.first { it.pid == 0x46 }
        if (0x46 !in supported) {
            assertEquals(AbsenceReason.PID_NOT_IN_BITMAP, ambient.absenceReason)
        }
    }

    @Test
    fun testCap003_brakeRequiresDifferentHardware() {
        val snap = ObdCapabilityDiscoveryEngine.build(emptySet(), true, true, true)
        val brake = snap.entries.first { it.key == "BRAKE-SW" }
        assertEquals(SupportStatus.REQUIRES_DIFFERENT_HARDWARE, brake.supportStatus)
        assertEquals(AbsenceReason.REQUIRES_DIFFERENT_HARDWARE, brake.absenceReason)
        assertTrue(brake.note.contains("PID", ignoreCase = true))
    }

    @Test
    fun testCap004_noBitmapNoEcu() {
        val noEcu = ObdCapabilityDiscoveryEngine.build(emptySet(), false, false, true)
        val rpm = noEcu.entries.first { it.pid == 0x0C }
        assertEquals(AbsenceReason.ECU_NOT_RESPONDING, rpm.absenceReason)

        val noAdapter = ObdCapabilityDiscoveryEngine.build(emptySet(), false, false, false)
        assertEquals(AbsenceReason.ADAPTER_NOT_CONNECTED, noAdapter.entries.first { it.pid == 0x0C }.absenceReason)

        val noBitmap = ObdCapabilityDiscoveryEngine.build(emptySet(), false, true, true)
        assertEquals(AbsenceReason.BITMAP_NOT_READ, noBitmap.entries.first { it.pid == 0x0C }.absenceReason)
    }
}

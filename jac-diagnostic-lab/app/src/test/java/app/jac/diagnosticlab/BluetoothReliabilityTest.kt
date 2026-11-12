package app.jac.diagnosticlab

import app.jac.diagnosticlab.bt.GattCharView
import app.jac.diagnosticlab.bt.GattObdProfile
import app.jac.diagnosticlab.bt.GattRetryPolicy
import app.jac.diagnosticlab.bt.LinkPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothReliabilityTest {
    @Test
    fun testBt001_linkPhaseLabelsAreDistinct() {
        val labels = LinkPhase.entries.map { it.label }.toSet()
        assertEquals(LinkPhase.entries.size, labels.size)
        assertEquals("Desconectado", LinkPhase.DISCONNECTED.label)
        assertEquals("ECU responde (Mode 01)", LinkPhase.ECU_RESPONDING.label)
    }

    @Test
    fun testBt002_gatt133RetryPolicy() {
        assertEquals(3, GattRetryPolicy.MAX_ATTEMPTS)
        assertEquals(400L, GattRetryPolicy.backoffMs(0))
        assertEquals(800L, GattRetryPolicy.backoffMs(1))
        assertTrue(GattRetryPolicy.shouldRetry(133, 0))
        assertTrue(GattRetryPolicy.shouldRetry(133, 1))
        assertFalse(GattRetryPolicy.shouldRetry(133, 2))
        assertFalse(GattRetryPolicy.shouldRetry(0, 0)) // success → no retry
    }

    @Test
    fun testBt003_rejectsServiceChangedAndDeviceName() {
        assertTrue(GattObdProfile.isRejectedUuid("00002a05-0000-1000-8000-00805f9b34fb"))
        assertTrue(GattObdProfile.isRejectedUuid("00002a00-0000-1000-8000-00805f9b34fb"))
        assertTrue(GattObdProfile.scorePair(
            "00002a00-0000-1000-8000-00805f9b34fb",
            "00002a05-0000-1000-8000-00805f9b34fb",
        ) < 0)
    }

    @Test
    fun testBt004_prefersFff1Fff2OverGenericGatt() {
        val rows = listOf(
            GattCharView("00001800-0000-1000-8000-00805f9b34fb", "00002a00-0000-1000-8000-00805f9b34fb", listOf("WRITE"), true, false),
            GattCharView("00001801-0000-1000-8000-00805f9b34fb", "00002a05-0000-1000-8000-00805f9b34fb", listOf("INDICATE"), false, true),
            GattCharView("0000fff0-0000-1000-8000-00805f9b34fb", "0000fff1-0000-1000-8000-00805f9b34fb", listOf("NOTIFY"), false, true),
            GattCharView("0000fff0-0000-1000-8000-00805f9b34fb", "0000fff2-0000-1000-8000-00805f9b34fb", listOf("WRITE"), true, false),
        )
        assertEquals("0000fff2-0000-1000-8000-00805f9b34fb", GattObdProfile.defaultWrite(rows))
        assertEquals("0000fff1-0000-1000-8000-00805f9b34fb", GattObdProfile.defaultNotify(rows))
        val ranked = LabController.bleCandidates(rows)
        assertEquals("0000fff2-0000-1000-8000-00805f9b34fb", ranked.first().first)
        assertEquals("0000fff1-0000-1000-8000-00805f9b34fb", ranked.first().second)
    }
}

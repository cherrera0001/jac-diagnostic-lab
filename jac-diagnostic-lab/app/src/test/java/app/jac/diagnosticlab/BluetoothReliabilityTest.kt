package app.jac.diagnosticlab

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
}

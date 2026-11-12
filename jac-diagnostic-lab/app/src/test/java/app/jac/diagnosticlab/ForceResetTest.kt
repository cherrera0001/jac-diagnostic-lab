package app.jac.diagnosticlab

import app.jac.diagnosticlab.bt.LinkPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documenta el contrato de reset: LabState fresco deja busy/polling en falso
 * y fase desconectada (el controller fuerza ese estado en forceResetAll).
 */
class ForceResetTest {
    @Test
    fun testReset001_defaultLabStateIsUnlocked() {
        val s = LabState()
        assertFalse(s.busy)
        assertFalse(s.polling)
        assertEquals(LinkPhase.DISCONNECTED, s.linkPhase)
        assertNull(s.error)
        assertTrue(s.samples.isEmpty())
    }
}

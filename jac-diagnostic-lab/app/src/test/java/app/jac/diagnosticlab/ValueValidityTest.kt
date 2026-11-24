package app.jac.diagnosticlab

import app.jac.diagnosticlab.obd.Signal
import app.jac.diagnosticlab.report.ValueValidity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC-035 R6/R7. */
class ValueValidityTest {
    private fun sig(pid: Int, v: Double?) = Signal("%02X".format(pid), pid, "x", v, "%")

    @Test fun torqueByteZeroIsFlaggedNotAMeasurement() {
        val reason = ValueValidity.invalidReason(sig(0x61, -125.0))
        assertNotNull(reason)
        assertTrue(reason!!.contains("no válido"))
        assertNotNull(ValueValidity.invalidReason(sig(0x62, -125.0)))
    }

    @Test fun plausibleValuesAreNotFlagged() {
        assertNull(ValueValidity.invalidReason(sig(0x61, 12.0)))
        assertNull(ValueValidity.invalidReason(sig(0x62, 24.0)))
        assertNull(ValueValidity.invalidReason(sig(0x0C, null)))
    }

    @Test fun displayTextReplacesTheNumberWhenInvalid() {
        assertEquals("no válido (byte 0 = −125 %)", ValueValidity.display(sig(0x61, -125.0), "-125 %"))
        assertEquals("24 %", ValueValidity.display(sig(0x62, 24.0), "24 %"))
    }

    @Test fun blanksOutsideTheEightPidProfileAreNotFaults() {
        assertTrue(ValueValidity.outOfProfile("FAST_FAULT", "Turbo real máximo"))
        assertTrue(ValueValidity.outOfProfile("FAST_FAULT", "EGT máxima"))
        assertTrue(ValueValidity.outOfProfile("FAST_FAULT", "Consumo medio"))
        assertFalse(ValueValidity.outOfProfile("FAST_FAULT", "Presión riel mínima (0x23)"))
        assertFalse(ValueValidity.outOfProfile("FAST_FAULT", "Tensión mínima"))
        assertFalse(ValueValidity.outOfProfile("ROAD", "Turbo real máximo"))
    }

    @Test fun noteExplainsTheProfileLimit() {
        assertTrue(ValueValidity.profileNote().contains("8 PIDs"))
    }
}

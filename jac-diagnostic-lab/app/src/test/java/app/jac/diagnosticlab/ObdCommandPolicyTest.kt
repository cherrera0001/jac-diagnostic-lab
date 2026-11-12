package app.jac.diagnosticlab

import app.jac.diagnosticlab.elm.ReadOnlyGate
import app.jac.diagnosticlab.elm.ReadOnlyViolation
import app.jac.diagnosticlab.security.ObdCommandPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ObdCommandPolicyTest {
    @Test
    fun allowsReadOnlyPlan() {
        listOf("ATZ", "ATE0", "ATI", "ATSP0", "0100", "03", "07", "0A", "0101", "0902").forEach {
            assertEquals(ObdCommandPolicy.Decision.ALLOW, ObdCommandPolicy.evaluate(it).decision)
        }
    }

    @Test
    fun blocksClearCodes() {
        val v = ObdCommandPolicy.evaluate("04")
        assertEquals(ObdCommandPolicy.Decision.DENY, v.decision)
        assertThrows(ReadOnlyViolation::class.java) { ReadOnlyGate.check("04") }
    }

    @Test
    fun blocksUdsWriteAndHeaders() {
        listOf("2E1234", "27 01", "ATSH7E0", "ATMA", "31FF00").forEach { cmd ->
            assertEquals(cmd, ObdCommandPolicy.Decision.DENY, ObdCommandPolicy.evaluate(cmd).decision)
        }
    }

    @Test
    fun blocksCompound() {
        assertEquals(ObdCommandPolicy.Decision.DENY, ObdCommandPolicy.evaluate("ATI;04").decision)
    }
}

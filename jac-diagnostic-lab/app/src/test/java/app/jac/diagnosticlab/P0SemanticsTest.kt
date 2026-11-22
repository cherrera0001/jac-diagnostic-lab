package app.jac.diagnosticlab

import app.jac.diagnosticlab.analysis.HealthAssessment
import app.jac.diagnosticlab.analysis.HealthInput
import app.jac.diagnosticlab.analysis.HealthStatus
import app.jac.diagnosticlab.analysis.Plausibility
import app.jac.diagnosticlab.obd.DtcReadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P0SemanticsTest {
    private fun codes(i: HealthInput) = HealthAssessment.evaluate(i).filter { it.system == "Códigos" }

    @Test fun dtcNotReadIsNotOk() {
        val c = codes(HealthInput(values = emptyMap()))
        assertTrue(c.none { it.status == HealthStatus.OK })
        assertEquals(HealthStatus.NO_DATA, c.first().status)
    }

    @Test fun dtcReadErrorAndUnsupportedAreNotOk() {
        for (s in listOf(DtcReadState.READ_ERROR, DtcReadState.UNSUPPORTED)) {
            val c = codes(HealthInput(values = emptyMap(), storedState = s))
            assertEquals(HealthStatus.NO_DATA, c.first().status)
        }
    }

    @Test fun validEmptyIsOk() {
        val c = codes(HealthInput(values = emptyMap(), storedState = DtcReadState.VALID_EMPTY))
        assertEquals(HealthStatus.OK, c.first().status)
    }

    @Test fun pendingRejectedIsNotZero() {
        val c = codes(HealthInput(values = emptyMap(), storedState = DtcReadState.VALID_EMPTY, pendingState = DtcReadState.UNSUPPORTED))
        assertTrue(c.any { it.evidence == "Mode 07" && it.status == HealthStatus.NO_DATA })
    }

    @Test fun negativeDpfIsUnverifiedNotOk() {
        val items = HealthAssessment.evaluate(HealthInput(values = mapOf("0C" to 800.0, "0D" to 0.0, "7A.DP" to -3.2)))
        val dpf = items.first { it.system == "DPF" }
        assertEquals(HealthStatus.UNVERIFIED, dpf.status)
        assertFalse(Plausibility.dpfDifferentialOk(-0.01))
        assertTrue(Plausibility.dpfDifferentialOk(1.2))
    }

    @Test fun nanIsUnverified() {
        val items = HealthAssessment.evaluate(HealthInput(values = mapOf("42" to Double.NaN)))
        assertTrue(items.any { it.status == HealthStatus.UNVERIFIED })
        assertTrue(items.none { it.system == "Eléctrico" && it.status == HealthStatus.OK })
    }

    @Test fun noSpeedDoesNotPresumeIdle() {
        val items = HealthAssessment.evaluate(HealthInput(values = mapOf("0C" to 800.0, "7A.DP" to 1.0)))
        val dpf = items.first { it.system == "DPF" }
        assertEquals(HealthStatus.INFO, dpf.status) // «evaluar solo en ralentí»: no se asume
    }

    @Test fun staleRpmDoesNotCountAsRunning() {
        val items = HealthAssessment.evaluate(
            HealthInput(
                values = mapOf("0C" to 800.0, "0D" to 0.0, "7A.DP" to 1.0),
                signalAgesMs = mapOf("0C" to 60_000L, "0D" to 100L, "7A.DP" to 100L),
            ),
        )
        assertEquals(HealthStatus.INFO, items.first { it.system == "DPF" }.status)
    }
}

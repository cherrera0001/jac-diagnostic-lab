package app.jac.diagnosticlab

import app.jac.diagnosticlab.bt.LinkRecoveryPolicy
import app.jac.diagnosticlab.bt.RecoveryAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkRecoveryPolicyTest {
    private val policy = LinkRecoveryPolicy()

    @Test fun fewMissesKeepTryingTheSameLink() {
        assertEquals(RecoveryAction.KEEP_TRYING, policy.decide(consecutiveMisses = 1, failedReconnects = 0, outageMs = 1_500))
        assertEquals(RecoveryAction.KEEP_TRYING, policy.decide(consecutiveMisses = 2, failedReconnects = 0, outageMs = 3_000))
    }

    @Test fun threeMissesTriggerReconnectInsteadOfEndingTheTest() {
        assertEquals(RecoveryAction.RECONNECT, policy.decide(consecutiveMisses = 3, failedReconnects = 0, outageMs = 4_500))
        // Regresión v1.19.4: con 5 rondas perdidas la prueba se rendía; ahora sigue intentando reconectar.
        assertEquals(RecoveryAction.RECONNECT, policy.decide(consecutiveMisses = 5, failedReconnects = 1, outageMs = 20_000))
    }

    @Test fun givesUpOnlyAfterManyFailedReconnectsOrALongOutage() {
        assertEquals(RecoveryAction.GIVE_UP, policy.decide(consecutiveMisses = 3, failedReconnects = policy.maxReconnects, outageMs = 60_000))
        assertEquals(RecoveryAction.GIVE_UP, policy.decide(consecutiveMisses = 3, failedReconnects = 1, outageMs = policy.maxOutageMs + 1))
        assertEquals(RecoveryAction.RECONNECT, policy.decide(consecutiveMisses = 3, failedReconnects = policy.maxReconnects - 1, outageMs = 60_000))
    }

    @Test fun backoffGrowsAndIsCapped() {
        val waits = (0..8).map { policy.backoffMs(it) }
        assertEquals(waits.sorted(), waits)
        assertTrue(waits.all { it in 1_000L..10_000L })
        assertEquals(10_000L, policy.backoffMs(50))
    }

    @Test fun outageLogSummarisesDurationAndRecovery() {
        val log = app.jac.diagnosticlab.bt.LinkOutageLog()
        log.begin(atMs = 1_000)
        log.reconnectAttempt(ok = false)
        log.reconnectAttempt(ok = true)
        log.end(atMs = 9_000)
        log.begin(atMs = 20_000)
        log.end(atMs = 21_500)
        assertEquals(2, log.outages.size)
        assertEquals(8_000L, log.outages[0].durationMs)
        assertEquals(2, log.outages[0].reconnectAttempts)
        assertTrue(log.outages[0].recovered)
        assertEquals(9_500L, log.totalOutageMs)
        assertTrue(log.summary().contains("2 corte"))
    }
}

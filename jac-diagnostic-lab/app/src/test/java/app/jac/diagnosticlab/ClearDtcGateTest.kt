package app.jac.diagnosticlab

import app.jac.diagnosticlab.elm.ElmParser
import app.jac.diagnosticlab.elm.Exchange
import app.jac.diagnosticlab.security.ClearDtcDecoder
import app.jac.diagnosticlab.security.ClearDtcGate
import app.jac.diagnosticlab.security.ClearOutcome
import app.jac.diagnosticlab.security.ObdCommandPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClearDtcGateTest {
    @After fun reset() = ClearDtcGate.disarm()

    private fun ex(raw: String): Exchange {
        val p = ElmParser.parse("04", raw, promptSeen = true, timedOut = false, disconnected = false)
        return Exchange(1L, "04", raw, p.kind, p.detail, true, 30L, p.frames)
    }
    private fun pre(speed: Double? = 0.0, rpm: Double? = 0.0, ev: Long? = 9_000L, phrase: String = "BORRAR", ecu: Boolean = true) =
        ClearDtcGate.Preconditions(ecu, speed, rpm, ev, 10_000L, phrase)

    @Test fun allowedWhenStoppedWithEvidenceAndPhrase() = assertTrue(ClearDtcGate.evaluate(pre()).allowed)
    @Test fun movingBlocked() = assertFalse(ClearDtcGate.evaluate(pre(speed = 12.0)).allowed)
    @Test fun unknownSpeedBlocked() = assertFalse(ClearDtcGate.evaluate(pre(speed = null)).allowed)
    @Test fun noEvidenceBlocked() = assertFalse(ClearDtcGate.evaluate(pre(ev = null)).allowed)
    @Test fun staleEvidenceBlocked() = assertFalse(ClearDtcGate.evaluate(pre(ev = 10_000L - ClearDtcGate.EVIDENCE_MAX_AGE_MS - 1)).allowed)
    @Test fun wrongPhraseBlocked() = assertFalse(ClearDtcGate.evaluate(pre(phrase = "borrar ya")).allowed)
    @Test fun lowercasePhraseAccepted() = assertTrue(ClearDtcGate.evaluate(pre(phrase = " borrar ")).allowed)
    @Test fun noEcuBlocked() = assertFalse(ClearDtcGate.evaluate(pre(ecu = false)).allowed)
    @Test fun runningEngineWarnsButDoesNotBlock() {
        val v = ClearDtcGate.evaluate(pre(rpm = 800.0))
        assertTrue(v.allowed); assertTrue(v.warnings.any { it.contains("7F 04 22") })
    }

    @Test fun policyBlocks04UnlessArmedAndOnlyExact04() {
        assertEquals(ObdCommandPolicy.Decision.DENY, ObdCommandPolicy.evaluate("04").decision)
        ClearDtcGate.arm()
        assertEquals(ObdCommandPolicy.Decision.ALLOW, ObdCommandPolicy.evaluate("04").decision)
        for (c in listOf("0400", "04;03", "ATSH7E0", "2E1234", "14FFFFFF", "27 01", "ATZ;04")) {
            assertEquals(c, ObdCommandPolicy.Decision.DENY, ObdCommandPolicy.evaluate(c).decision)
        }
        ClearDtcGate.disarm()
        assertEquals(ObdCommandPolicy.Decision.DENY, ObdCommandPolicy.evaluate("04").decision)
    }

    @Test fun armExpires() {
        ClearDtcGate.arm(nowMs = 0L)
        assertFalse(ClearDtcGate.isArmed(nowMs = 25_000L))
    }

    @Test fun decodesPositive() = assertEquals(ClearDtcDecoder.Response.Accepted, ClearDtcDecoder.decode(ex("7E8 01 44")))
    @Test fun decodesNegativeConditions() =
        assertEquals(ClearDtcDecoder.Response.Rejected(0x22), ClearDtcDecoder.decode(ex("7E8 03 7F 04 22")))
    @Test fun noDataIsNoResponse() = assertEquals(ClearDtcDecoder.Response.NoResponse, ClearDtcDecoder.decode(ex("NO DATA")))

    @Test fun outcomeNeverClaimsVerifiedWithoutValidReread() {
        val a = ClearDtcDecoder.Response.Accepted
        assertEquals(ClearOutcome.VERIFIED_EMPTY, ClearDtcDecoder.outcome(a, true, 0))
        assertEquals(ClearOutcome.CODES_REAPPEARED, ClearDtcDecoder.outcome(a, true, 2))
        assertEquals(ClearOutcome.ACCEPTED_UNVERIFIED, ClearDtcDecoder.outcome(a, false, 0))
        assertEquals(ClearOutcome.REJECTED, ClearDtcDecoder.outcome(ClearDtcDecoder.Response.Rejected(0x22), true, 0))
    }
}

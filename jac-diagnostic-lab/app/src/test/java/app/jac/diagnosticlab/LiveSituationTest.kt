package app.jac.diagnosticlab

import app.jac.diagnosticlab.analysis.GaugeFreshness
import app.jac.diagnosticlab.analysis.LiveSituationEngine
import app.jac.diagnosticlab.bt.LinkPhase
import app.jac.diagnosticlab.telemetry.ChannelStats
import app.jac.diagnosticlab.telemetry.PollBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSituationTest {

    @Test
    fun testSit001_unsupportedWhenBitmapSaysNo() {
        val f = LiveSituationEngine.freshness(
            pid = 0x49,
            supported = false,
            hasValue = false,
            sampleValid = null,
            ageMs = null,
            targetIntervalMs = 1000L,
            failCount = 0,
            sampleCount = 0,
        )
        assertEquals(GaugeFreshness.UNSUPPORTED, f)
    }

    @Test
    fun testSit002_staleWhenAgeExceedsBudget() {
        val f = LiveSituationEngine.freshness(
            pid = 0x0C,
            supported = true,
            hasValue = true,
            sampleValid = true,
            ageMs = 10_000L,
            targetIntervalMs = 500L,
            failCount = 0,
            sampleCount = 3,
        )
        assertEquals(GaugeFreshness.STALE, f)
    }

    @Test
    fun testSit003_liveWhenFresh() {
        val f = LiveSituationEngine.freshness(
            pid = 0x0C,
            supported = true,
            hasValue = true,
            sampleValid = true,
            ageMs = 200L,
            targetIntervalMs = 500L,
            failCount = 0,
            sampleCount = 5,
        )
        assertEquals(GaugeFreshness.LIVE, f)
    }

    @Test
    fun testSit004_adapterOnlyForAtrv() {
        val f = LiveSituationEngine.freshness(
            pid = -1,
            supported = true,
            hasValue = true,
            sampleValid = true,
            ageMs = 100L,
            targetIntervalMs = 2000L,
            failCount = 0,
            sampleCount = 1,
        )
        assertEquals(GaugeFreshness.ADAPTER_ONLY, f)
    }

    @Test
    fun testSit005_readErrorWhenOnlyFails() {
        val f = LiveSituationEngine.freshness(
            pid = 0x0B,
            supported = true,
            hasValue = false,
            sampleValid = null,
            ageMs = null,
            targetIntervalMs = 1000L,
            failCount = 4,
            sampleCount = 0,
        )
        assertEquals(GaugeFreshness.READ_ERROR, f)
    }

    @Test
    fun testSit006_reportMentionsBrakeGapAndSafe001() {
        val report = LiveSituationEngine.report(
            codes = listOf("P0579"),
            freezeDtc = "P0579",
            rpm = 780.0,
            speed = 0.0,
            throttleAbs = 12.0,
            load = 22.0,
            coolant = 84.0,
            appD = null,
            appE = null,
            atrv = 12.3,
            supported = setOf(0x0C, 0x0D, 0x11, 0x04, 0x05),
            bitmapRead = true,
            powerLossOnBrake = true,
            linkPhase = LinkPhase.DIAGNOSTIC_READY,
            polling = true,
            channelStats = mapOf(
                0x0C to ChannelStats(
                    pid = 0x0C,
                    band = PollBand.FAST,
                    targetIntervalMs = 500L,
                    sampleCount = 10,
                    failCount = 0,
                    lastAtMs = 1_000L,
                    lastLatencyMs = 40L,
                    effectiveHz = 1.8,
                    minGapMs = 400L,
                    maxGapMs = 600L,
                    ageMs = 120L,
                ),
            ),
        )
        assertTrue(report.headline.contains("corte", ignoreCase = true) || report.headline.contains("P0579"))
        assertTrue(report.sensorLines.any { it.contains("freno", ignoreCase = true) })
        assertTrue(report.hypotheses.any { it.id == "H1" && it.status.contains("ABIERTA") })
        assertTrue(report.hypotheses.any { it.id == "H2" && it.status.contains("ABIERTA") })
        assertTrue(report.nextActions.any { it.contains("SAFE-001") })
        assertTrue(report.nextActions.any { it.contains("freno", ignoreCase = true) })
    }

    @Test
    fun testSit007_mechanicNoteForStale() {
        val note = LiveSituationEngine.mechanicNote(GaugeFreshness.STALE, "RPM", 0x0C)
        assertTrue(note!!.contains("viejo", ignoreCase = true))
    }
}

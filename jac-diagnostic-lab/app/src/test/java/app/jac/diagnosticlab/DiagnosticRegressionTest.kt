package app.jac.diagnosticlab

import app.jac.diagnosticlab.elm.ElmParser
import app.jac.diagnosticlab.elm.Exchange
import app.jac.diagnosticlab.elm.ResponseKind
import app.jac.diagnosticlab.obd.DtcCategory
import app.jac.diagnosticlab.obd.DtcDecoder
import app.jac.diagnosticlab.obd.InterpretationStatus
import app.jac.diagnosticlab.obd.OperationalState
import app.jac.diagnosticlab.obd.PidDecoder
import app.jac.diagnosticlab.obd.SessionTimeMetrics
import app.jac.diagnosticlab.obd.VoltageAssessment
import app.jac.diagnosticlab.obd.assessAdapterVoltage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Regresión P0 sobre la sesión original 2026-11-11T2233-288 (CSV + PDF verificados).
 * DIAG-001 … DIAG-007.
 */
class DiagnosticRegressionTest {

    private fun pidExchange(pid: Int, dataBytes: List<Int>): Exchange {
        val payload = listOf(0x41, pid) + dataBytes
        val hex = payload.joinToString(" ") { "%02X".format(it) }
        val raw = "7E8%02X%s".format(payload.size, payload.joinToString("") { "%02X".format(it) })
        val parsed = ElmParser.parse("%02X%02X".format(0x01, pid), "7E8 $hex\r\n", true, false, false)
        // Prefer structured frames from parser; fall back to synthetic OBD_DATA.
        return if (parsed.kind == ResponseKind.OBD_DATA && parsed.frames.isNotEmpty()) {
            Exchange(0, "%02X%02X".format(0x01, pid), parsed.raw, parsed.kind, parsed.detail, true, 1, parsed.frames)
        } else {
            Exchange(
                0,
                "%02X%02X".format(0x01, pid),
                raw,
                ResponseKind.OBD_DATA,
                "synthetic",
                true,
                1,
                listOf(app.jac.diagnosticlab.elm.ObdFrame("7E8", payload, false, hex)),
            )
        }
    }

    @Test
    fun testPid011_mathAndSemanticAbsoluteThrottleNotPedal() {
        // TEST-PID-011 — CSV: E5 → ~89.8039 %; nombre estándar ≠ pedal acelerador.
        val reading = PidDecoder.decode(0x11, pidExchange(0x11, listOf(0xE5)))
        assertTrue(reading.valid)
        assertEquals(89.80392156862744, reading.value!!, 1e-9)
        assertFalse(
            "DIAG-001: no rotular 0x11 como posición del pedal/acelerador",
            reading.name.contains("acelerador", ignoreCase = true) &&
                !reading.name.contains("mariposa", ignoreCase = true),
        )
        assertTrue(
            "DIAG-001: nombre debe indicar posición absoluta de mariposa / Absolute Throttle Position",
            reading.name.contains("mariposa", ignoreCase = true) ||
                reading.standardName.contains("Throttle Position", ignoreCase = true),
        )
        assertEquals(InterpretationStatus.SUPPORTED, reading.interpretationStatus)
        assertFalse(reading.standardName.contains("Accelerator Pedal", ignoreCase = true))
    }

    @Test
    fun testRpm001_fromSessionCsv() {
        // TEST-RPM-001 — bytes 0C 4B → 786.75 rpm
        val reading = PidDecoder.decode(0x0C, pidExchange(0x0C, listOf(0x0C, 0x4B)))
        assertEquals(786.75, reading.value!!, 0.001)
        assertEquals("rpm", reading.unit)
    }

    @Test
    fun testTime001_sixSampleWindow755ms() {
        // TEST-TIME-001 — timestamps del CSV original
        val stamps = listOf(
            1794447222457L,
            1794447222606L,
            1794447222790L,
            1794447222911L,
            1794447223092L,
            1794447223212L,
        )
        val metrics = SessionTimeMetrics.fromTimestamps(stamps)
        assertEquals(755L, metrics.windowMs)
        assertEquals(6, metrics.sampleCount)
        assertTrue(metrics.effectiveHz > 0)
    }

    @Test
    fun testVolt001_adapterEstimateRequiresIndependentMeasurement() {
        // TEST-VOLT-001 — ATRV 16.9 V
        val assessment = assessAdapterVoltage("16.9V")
        assertEquals(16.9, assessment.valueVolts!!, 0.01)
        assertEquals(VoltageAssessment.Status.REQUIRES_INDEPENDENT_MEASUREMENT, assessment.status)
        assertFalse(assessment.isConfirmedBatteryMeasurement)
        assertTrue(assessment.note.contains("adaptador", ignoreCase = true))
    }

    @Test
    fun testDtc001_preservesP0579AndP2299Exactly() {
        // TEST-DTC-001 — trama stored de sesión real
        val parsed = ElmParser.parse("03", "7E806430205792299", true, false, false)
        val exchange = Exchange(0, "03", parsed.raw, parsed.kind, parsed.detail, true, 1, parsed.frames)
        val result = DtcDecoder.decode(DtcCategory.STORED, exchange, true)
        val codes = result.codes.map { it.code }
        assertTrue(codes.contains("P0579"))
        assertTrue(codes.contains("P2299"))
        assertEquals(listOf("P0579", "P2299"), codes)
        assertTrue(result.raw.contains("0579") || result.raw.contains("05 79") || result.raw.contains("0579299") || exchange.raw.contains("0579"))
    }

    @Test
    fun testDiag007_rpmNonZeroIsNotContactOnly() {
        val state = OperationalState.fromHints(rpm = 786.75, speedKmh = 0.0, declared = null)
        assertEquals(OperationalState.ENGINE_IDLING_OR_RUNNING, state)
        assertFalse(state == OperationalState.KEY_ON_ENGINE_OFF)
        assertTrue(state.reportLabel.contains("ralentí") || state.reportLabel.contains("marcha") || state.reportLabel.contains("RPM"))
    }

    @Test
    fun testDiag002_highThrottleWithIdleIsPlausibilityNoteNotAutoFault() {
        val reading = PidDecoder.decode(0x11, pidExchange(0x11, listOf(0xE5)))
        val note = PidDecoder.plausibilityNote(
            pid = 0x11,
            value = reading.value,
            rpm = 786.75,
            speedKmh = 0.0,
        )
        requireNotNull(note)
        assertTrue(note.contains("plausibilidad", ignoreCase = true) || note.contains("contexto", ignoreCase = true) || note.contains("mariposa", ignoreCase = true))
        assertFalse(note.contains("falla confirmada", ignoreCase = true))
    }
}

package app.jac.diagnosticlab

import app.jac.diagnosticlab.analysis.HealthStatus
import app.jac.diagnosticlab.bt.LinkOutage
import app.jac.diagnosticlab.dynamic.TestMetric
import app.jac.diagnosticlab.dynamic.TestResult
import app.jac.diagnosticlab.dynamic.TraceEvent
import app.jac.diagnosticlab.dynamic.TraceFrame
import app.jac.diagnosticlab.obd.DtcCategory
import app.jac.diagnosticlab.obd.DtcHit
import app.jac.diagnosticlab.obd.DtcReadResult
import app.jac.diagnosticlab.obd.DtcScope
import app.jac.diagnosticlab.obd.Signal
import app.jac.diagnosticlab.report.DriverNarrative
import app.jac.diagnosticlab.report.WorkshopInput
import app.jac.diagnosticlab.report.WorkshopReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** SPEC-035 R2, R3, R4, R6, R7, R8. Fixtures sintéticos que reproducen el informe del 24-11-2026. */
class WorkshopReportPhase1Test {
    private fun base() = WorkshopInput(
        appVersion = "v1.20.0 (41)", sessionId = "S1", generatedAtMs = 1_000_000L, declaredModel = "JAC T8", declaredYear = "2023",
        vin = null, protocol = "6", adapterId = "ELM327 v2.1", adapterVoltage = "16.4V", scanAtMs = 1_000L,
        dtcs = listOf(DtcCategory.STORED, DtcCategory.PENDING, DtcCategory.PERMANENT).map { c ->
            DtcReadResult(
                c, app.jac.diagnosticlab.elm.ResponseKind.OBD_DATA,
                if (c == DtcCategory.STORED) listOf("P0579", "P2299").map { DtcHit(it, 5, 0x79, DtcScope.GENERIC, "g", "7E8", c) } else emptyList(),
                false, "", "",
            )
        },
        freezeNotes = emptyList(), freezeValues = emptyList(), signals = emptyList(), rawByPid = emptyMap(),
        supportedPids = emptySet(), readiness = null, readinessCycle = null, mode06 = emptyList(), vehicleInfo = null,
        ecuIdentification = emptyList(), profile = null, health = emptyList(), exchanges = emptyList(), errors = emptyList(),
    )

    private fun fastFaultTest(events: List<TraceEvent>) = TestResult(
        testId = "FAST_FAULT", title = "Captura rápida de falla", startedAtMs = 1_000_000L, durationMs = 400_000, frames = 1342, effectiveHz = 3.3,
        metrics = listOf(
            TestMetric("Presión riel mínima (0x23)", 3290.0, "kPa", HealthStatus.CHECK),
            TestMetric("Turbo real máximo", null, "kPa", HealthStatus.NO_DATA),
            TestMetric("Tensión mínima", 13.24, "V", HealthStatus.OK),
        ),
        events = events, verdict = HealthStatus.CHECK, summary = "x",
    )

    private val stall = TraceEvent(1_010_000L, "MOTOR DETENIDO", "RPM a 0 con pedal pisado")

    @Test fun r3_minimumRailPressureIsNotContradictedByTheDifferential() {
        val dd = WorkshopReport.buildDifferentialDiagnosis(base().copy(tests = listOf(fastFaultTest(listOf(stall)))))
        val text = dd.observedFacts.joinToString(" ") + dd.engineStallHypothesis
        assertTrue("Debe citar la presión mínima 3290 kPa", text.contains("3290"))
        assertFalse("No puede decir que no hubo caída de riel", dd.engineStallHypothesis.contains("sin caída confirmada de riel"))
        assertTrue(dd.engineStallHypothesis.contains("riel"))
    }

    @Test fun r3_noRailDropClaimWhenMinimumIsNormal() {
        val ok = fastFaultTest(emptyList()).copy(metrics = listOf(TestMetric("Presión riel mínima (0x23)", 24_000.0, "kPa", HealthStatus.OK)))
        val dd = WorkshopReport.buildDifferentialDiagnosis(base().copy(tests = listOf(ok)))
        assertFalse(dd.engineStallHypothesis.contains("caída de presión de riel de combustible"))
    }

    @Test fun r2_outagesAppearInTheCommunicationSection() {
        val outage = LinkOutage(startMs = 1_034_000L, endMs = 1_041_500L, reconnectAttempts = 2, recovered = true)
        val doc = WorkshopReport.buildDoc(base().copy(linkOutages = listOf(outage)), ZoneId.of("UTC"))
        val text = doc.toLines().joinToString("\n")
        assertTrue(text.contains("Cortes de enlace"))
        assertTrue(text.contains("recuperado"))
        assertTrue(text.contains("7 s") || text.contains("7.5") || text.contains("7,5"))
    }

    @Test fun r4_eventTableListsTimeSpeedRpmPedalsAndRail() {
        val frames = (0..30).map { n ->
            TraceFrame(1_000_000L + n * 500L, mapOf("0C" to 800.0, "0D" to 20.0, "49" to 30.0, "4A" to 15.0, "23" to 25_000.0))
        }
        val doc = WorkshopReport.buildDoc(
            base().copy(tests = listOf(fastFaultTest(listOf(stall))), traceFrames = frames), ZoneId.of("UTC"),
        )
        val text = doc.toLines().joinToString("\n")
        assertTrue(text.contains("Cronología de eventos"))
        assertTrue(text.contains("MOTOR DETENIDO"))
        assertTrue(text.contains("25000") || text.contains("25 000") || text.contains("25.0"))
    }

    @Test fun r6_invalidTorqueByteIsLabelledNotShownAsMeasurement() {
        val sig = Signal("61", 0x61, "Torque demandado por conductor", -125.0, "%")
        val doc = WorkshopReport.buildDoc(base().copy(signals = listOf(sig), supportedPids = setOf(0x61)), ZoneId.of("UTC"))
        val text = doc.toLines().joinToString("\n")
        assertTrue(text.contains("no válido"))
        assertFalse(text.contains("-125"))
    }

    @Test fun r7_blanksFromTheEightPidProfileAreNotPresentedAsFaults() {
        val doc = WorkshopReport.buildDoc(base().copy(tests = listOf(fastFaultTest(emptyList()))), ZoneId.of("UTC"))
        val text = doc.toLines().joinToString("\n")
        assertTrue(text.contains("fuera del perfil"))
    }

    @Test fun r8_driverNarrativeIsOptionalAndAppearsWhenFilled() {
        val without = WorkshopReport.buildDoc(base(), ZoneId.of("UTC")).toLines().joinToString("\n")
        assertFalse(without.contains("Relato del conductor"))
        val n = DriverNarrative(gear = "3ª", clutch = "pisado", temperature = "motor caliente", symptom = "se apaga al frenar", notes = "")
        val with = WorkshopReport.buildDoc(base().copy(narrative = n), ZoneId.of("UTC")).toLines().joinToString("\n")
        assertTrue(with.contains("Relato del conductor"))
        assertTrue(with.contains("se apaga al frenar"))
        assertTrue(with.contains("3ª"))
        assertEquals(false, DriverNarrative().hasContent)
        assertEquals(true, n.hasContent)
    }
}

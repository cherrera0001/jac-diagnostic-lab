package app.jac.diagnosticlab

import app.jac.diagnosticlab.analysis.HealthItem
import app.jac.diagnosticlab.analysis.HealthStatus
import app.jac.diagnosticlab.elm.ElmParser
import app.jac.diagnosticlab.elm.Exchange
import app.jac.diagnosticlab.obd.DtcCategory
import app.jac.diagnosticlab.obd.DtcHit
import app.jac.diagnosticlab.obd.DtcReadResult
import app.jac.diagnosticlab.obd.DtcScope
import app.jac.diagnosticlab.obd.FreezeFrame
import app.jac.diagnosticlab.obd.FreezeNote
import app.jac.diagnosticlab.obd.Mode06Result
import app.jac.diagnosticlab.obd.PidCatalog
import app.jac.diagnosticlab.profile.EcuMode09Values
import app.jac.diagnosticlab.profile.Mode09Record
import app.jac.diagnosticlab.report.WorkshopInput
import app.jac.diagnosticlab.report.WorkshopReport
import app.jac.diagnosticlab.security.ObdCommandPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** SPEC-033 R13/R14. Fixtures SINTÉTICOS, no lecturas de la unidad. */
class WorkshopReportTest {

    private fun ex(command: String, raw: String): Exchange {
        val p = ElmParser.parse(command, raw, promptSeen = true, timedOut = false, disconnected = false)
        return Exchange(1_000, command, raw, p.kind, p.detail, true, 40, p.frames)
    }

    private fun empty() = WorkshopInput(
        appVersion = "v1.10.0 (23)", sessionId = "S1", generatedAtMs = 0, declaredModel = "JAC T8", declaredYear = "2023",
        vin = null, protocol = "6", adapterId = "ELM327 v1.5", adapterVoltage = "12.4V", scanAtMs = null,
        dtcs = emptyList(), freezeNotes = emptyList(), freezeValues = emptyList(), signals = emptyList(), rawByPid = emptyMap(),
        supportedPids = emptySet(), readiness = null, readinessCycle = null, mode06 = emptyList(), vehicleInfo = null,
        ecuIdentification = emptyList(), profile = null, health = emptyList(), exchanges = emptyList(), errors = emptyList(),
    )

    @Test
    fun r13_reportWaitsForTheFullReadAndSaysWhatIsMissing() {
        val gaps = WorkshopReport.missing(empty())
        assertEquals(5, gaps.size)
        assertTrue(gaps.first().startsWith("Escaneo completo"))
        val doc = WorkshopReport.build(empty(), ZoneId.of("UTC"))
        assertTrue(doc.lines.any { it.startsWith("INCOMPLETO. Falta:") })
    }

    @Test
    fun r14_reportGivesTheWorkshopCodeContextFreezeLiveDataAndChecks() {
        val supported = setOf(0x05, 0x0C, 0x23)
        val freezeEx = ex("022300", "7E8 05 42 23 00 1B 58")
        val freeze = FreezeFrame.decode(0x23, freezeEx)
        assertNotNull(freeze)
        assertNull(FreezeFrame.decode(0x23, ex("022300", "NO DATA")))
        FreezeFrame.plan(supported).forEach { ObdCommandPolicy.assertAllowed(FreezeFrame.command(it)) }

        val hit = DtcHit("P0087", 0x00, 0x87, DtcScope.GENERIC, "genérico SAE", "7E8", DtcCategory.STORED)
        val dtcs = DtcCategory.entries.map { c ->
            DtcReadResult(c, app.jac.diagnosticlab.elm.ResponseKind.OBD_DATA, if (c == DtcCategory.STORED) listOf(hit) else emptyList(), c != DtcCategory.STORED, "", "")
        }
        val signals = PidCatalog.decode(0x0C, listOf(0x0C, 0x80)) + PidCatalog.decode(0x05, listOf(0x82))
        val input = empty().copy(
            vin = "SYNTHETICP0000001", scanAtMs = 2_000, dtcs = dtcs,
            freezeNotes = listOf(FreezeNote("42 02 00 00 87", "P0087", "cuadro")), freezeValues = listOf(freeze!!),
            signals = signals, rawByPid = mapOf(0x0C to "0C 80"), supportedPids = supported,
            mode06 = listOf(Mode06Result(0x85, 0x01, 0x00, "Presión de riel", 9.0, 10.0, 20.0, "MPa", false, 9, 10, 20)),
            ecuIdentification = listOf(Mode09Record("0904", 4, 1_500, 30, "OBD_DATA", "SINTETICO", listOf(EcuMode09Values("7E8", listOf("SYNTCAL01"))), "")),
            health = listOf(HealthItem("Combustible", HealthStatus.CHECK, "Presión baja", "detalle", "PID 23")),
            exchanges = listOf(freezeEx, ex("0902", "NO DATA")),
        )
        assertTrue(WorkshopReport.missing(input).isEmpty())
        val text = WorkshopReport.build(input, ZoneId.of("UTC")).lines.joinToString("\n")

        assertFalse(text.contains("INCOMPLETO"))
        assertTrue(text.contains("JAC Diagnostic Lab v1.10.0 (23)"))
        assertTrue(text.contains("VIN (leído de la ECU, 0902): SYNTHETICP0000001. Año modelo según ISO 3779: 2023"))
        assertTrue(text.contains("• P0087 (almacenado): Presión de riel muy baja — causó el cuadro congelado"))
        assertTrue(text.contains("PID 23: ahora sin dato · congelado"))
        assertTrue(text.contains("PID 0C: ahora"))
        // Unidades con «%» no rompen el texto (antes se pasaba por String.format).
        assertFalse(text.contains("Exception"))
        assertTrue(text.contains("presión de riel real contra deseada"))
        assertTrue(text.contains("Mode 06 FUERA de límite: Presión de riel = 9 MPa (mín 10, máx 20)"))
        assertTrue(text.contains("REVISAR Combustible: Presión baja"))
        assertTrue(text.contains("ECU 7E8: SYNTCAL01"))
        assertTrue(text.contains("0902 → NO_DATA"))
        assertTrue(text.contains(WorkshopReport.SCOPE_NOTE))
    }
}

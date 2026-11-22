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
        assertTrue(text.contains("SYNTHETICP0000001 · año modelo 2023 (ISO 3779)"))
        assertTrue(text.contains("Requiere revisión: 1 código(s) de falla — P0087"))
        assertTrue(text.contains("P0087 | almacenado | Presión de riel muy baja — guardó el cuadro congelado"))
        assertTrue(text.contains("presión de riel real contra deseada"))
        assertTrue(text.contains("85/01 | Presión de riel | 9 MPa | 10 | 20 | FUERA"))
        assertTrue(text.contains("REVISAR Combustible: Presión baja"))
        assertTrue(text.contains("0904 | 7E8 | SYNTCAL01"))
        assertTrue(text.contains("0902 | NO_DATA"))
        assertTrue(text.contains(WorkshopReport.SCOPE_NOTE))
    }

    @Test
    fun r16_realSessionIssuesAreHandled() {
        // Sesión real 2026-11-18 (bytes reales, VIN omitido): P0579 almacenado y pendiente, 0A rechazado, ceros del cuadro congelado.
        val p0579s = DtcHit("P0579", 0x05, 0x79, DtcScope.GENERIC, "", "7E8", DtcCategory.STORED)
        val dtcs = listOf(
            DtcReadResult(DtcCategory.STORED, app.jac.diagnosticlab.elm.ResponseKind.OBD_DATA, listOf(p0579s, p0579s.copy(code = "P2299", hi = 0x22, lo = 0x99)), false, "7E806430205792299", ""),
            DtcReadResult(DtcCategory.PENDING, app.jac.diagnosticlab.elm.ResponseKind.OBD_DATA, listOf(p0579s.copy(category = DtcCategory.PENDING)), false, "7E80447010579", ""),
            DtcReadResult(DtcCategory.PERMANENT, app.jac.diagnosticlab.elm.ResponseKind.OBD_DATA, emptyList(), false, "7E8037F0A13", ""),
        )
        val freeze = listOf(
            FreezeFrame.decode(0x0C, ex("020C00", "7E8 05 42 0C 00 15 99"))!!,
            FreezeFrame.decode(0x33, ex("023300", "7E8 04 42 33 00 00"))!!,
            FreezeFrame.decode(0x49, ex("024900", "7E8 04 42 49 00 00"))!!,
        )
        val input = empty().copy(dtcs = dtcs, freezeValues = freeze, freezeNotes = listOf(FreezeNote("7E8054202000579", "P0579", "")))
        assertEquals(listOf("P0579", "P2299"), WorkshopReport.codes(input).map { it.first })
        assertTrue(WorkshopReport.refused(dtcs[2]))
        assertTrue(WorkshopReport.freezeZerosUnreliable(input))
        val text = WorkshopReport.build(input, ZoneId.of("UTC")).lines.joinToString("\n")
        assertTrue(text.contains("Requiere revisión: 2 código(s) de falla — P0579, P2299"))
        assertTrue(text.contains("P0579 (almacenado, pendiente) + P2299 (almacenado)"))
        assertTrue(text.contains("la ECU no admite este servicio"))
        assertTrue(text.contains("0 % · no capturado"))
        assertTrue(text.contains("Tope de goma del pedal de freno"))
    }

    @Test
    fun r26_criticalFaultCaptureProfileRestrictsToEightEssentialPids() {
        val expectedPids = listOf(0x49, 0x4A, 0x04, 0x62, 0x0C, 0x0D, 0x23, 0x42)
        assertEquals(8, app.jac.diagnosticlab.dynamic.GuidedTests.CRITICAL_FAULT_PIDS.size)
        assertEquals(expectedPids, app.jac.diagnosticlab.dynamic.GuidedTests.CRITICAL_FAULT_PIDS)

        val profile = app.jac.diagnosticlab.dynamic.GuidedTests.byId(app.jac.diagnosticlab.dynamic.GuidedTests.FAST_FAULT_ID)
        assertEquals(expectedPids, profile.pids)
        assertTrue(profile.title.contains("Captura rápida", ignoreCase = true))

        // Todos los comandos Mode 01 del set crítico deben estar permitidos por ObdCommandPolicy (solo lectura).
        profile.pids.forEach { pid ->
            ObdCommandPolicy.assertAllowed("01%02X".format(pid))
        }

        // Análisis de caída de presión de riel (0x23) y tensión (0x42) sobre el perfil ligero.
        val frames = listOf(
            app.jac.diagnosticlab.dynamic.TraceFrame(1_000L, mapOf("0C" to 1900.0, "0D" to 60.0, "49" to 35.0, "4A" to 17.5, "04" to 60.0, "62" to 55.0, "23" to 42_000.0, "42" to 14.1)),
            app.jac.diagnosticlab.dynamic.TraceFrame(1_500L, mapOf("0C" to 1850.0, "0D" to 58.0, "49" to 35.0, "4A" to 17.5, "04" to 15.0, "62" to 10.0, "23" to 11_000.0, "42" to 11.2)),
            app.jac.diagnosticlab.dynamic.TraceFrame(2_000L, mapOf("0C" to 1500.0, "0D" to 52.0, "49" to 35.0, "4A" to 17.5, "04" to 12.0, "62" to 8.0, "23" to 10_500.0, "42" to 11.1)),
            app.jac.diagnosticlab.dynamic.TraceFrame(2_500L, mapOf("0C" to 1200.0, "0D" to 46.0, "49" to 35.0, "4A" to 17.5, "04" to 10.0, "62" to 5.0, "23" to 10_000.0, "42" to 11.0)),
            app.jac.diagnosticlab.dynamic.TraceFrame(3_000L, mapOf("0C" to 900.0, "0D" to 40.0, "49" to 35.0, "4A" to 17.5, "04" to 10.0, "62" to 5.0, "23" to 9_500.0, "42" to 11.0)),
        )
        val result = app.jac.diagnosticlab.dynamic.GuidedTests.analyze(profile, frames)
        assertTrue(result.events.any { it.kind == "CORTE DE POTENCIA" })
        assertTrue(result.events.any { it.kind == "CAÍDA PRESIÓN RIEL" })
        assertTrue(result.events.any { it.kind == "CAÍDA DE TENSIÓN" })
        assertEquals(HealthStatus.CHECK, result.verdict)
    }

    @Test
    fun r27_workshopReportIncludesDifferentialDiagnosisAndCriticalSetRecommendation() {
        // Caso A: P0579 + P2299 (brake override) + datos no capturados en Mode 02 + pérdida de enlace ->
        // debe separar HECHO OBSERVABLE de HIPÓTESIS y recomendar automáticamente el Set Crítico de 8 PIDs.
        val p0579 = DtcHit("P0579", 0x05, 0x79, DtcScope.GENERIC, "SAE", "7E8", DtcCategory.STORED)
        val p2299 = DtcHit("P2299", 0x22, 0x99, DtcScope.GENERIC, "SAE", "7E8", DtcCategory.STORED)
        val dtcs = listOf(
            DtcReadResult(DtcCategory.STORED, app.jac.diagnosticlab.elm.ResponseKind.OBD_DATA, listOf(p0579, p2299), false, "7E806430205792299", ""),
        )
        val unreliableFreeze = listOf(
            FreezeFrame.decode(0x0C, ex("020C00", "7E8 05 42 0C 00 15 99"))!!,
            FreezeFrame.decode(0x42, ex("024200", "7E8 05 42 42 00 00 00"))!!,
        )
        val timeoutEx = Exchange(
            atEpochMs = 2_000L,
            command = "0123",
            raw = "",
            kind = app.jac.diagnosticlab.elm.ResponseKind.TIMEOUT,
            detail = "timeout",
            promptSeen = false,
            elapsedMs = 3_000L,
            frames = emptyList(),
        )
        val brakeInput = empty().copy(
            scanAtMs = 2_500L,
            dtcs = dtcs,
            freezeNotes = listOf(FreezeNote("7E8054202000579", "P0579", "")),
            freezeValues = unreliableFreeze,
            signals = PidCatalog.decode(0x0C, listOf(0x0C, 0x80)) +
                PidCatalog.decode(0x23, listOf(0x0D, 0xAC)) + // 35000 kPa normal
                PidCatalog.decode(0x42, listOf(0x37, 0x14)),  // 14.1 V normal
            exchanges = listOf(timeoutEx),
        )
        val diffBrake = WorkshopReport.buildDifferentialDiagnosis(brakeInput)
        assertTrue(diffBrake.observedFacts.any { it.contains("P0579") && it.contains("P2299") })
        assertTrue(diffBrake.torqueCutHypothesis.contains("HIPÓTESIS"))
        assertTrue(diffBrake.torqueCutHypothesis.contains("brake override", ignoreCase = true))
        assertTrue(diffBrake.engineStallHypothesis.contains("HIPÓTESIS"))
        assertTrue(diffBrake.recommendCriticalSet)
        assertTrue(diffBrake.criticalSetReasons.any { it.contains("pérdida de enlace", ignoreCase = true) })
        assertTrue(diffBrake.criticalSetReasons.any { it.contains("Mode 02", ignoreCase = true) })

        val reportText = WorkshopReport.build(brakeInput, ZoneId.of("UTC")).lines.joinToString("\n")
        assertTrue(reportText.contains("Diagnóstico diferencial (Hechos observables vs. Hipótesis)"))
        assertTrue(reportText.contains("HECHO OBSERVABLE"))
        assertTrue(reportText.contains("HIPÓTESIS · Corte de torque / potencia"))
        assertTrue(reportText.contains("HIPÓTESIS · Apagado total de motor"))
        assertTrue(reportText.contains("Set Crítico de 8 PIDs"))
        assertTrue(reportText.contains("0x49") && reportText.contains("0x4A") && reportText.contains("0x23") && reportText.contains("0x42"))

        // Caso B: Caída real de presión de riel (0x23) y tensión baja (0x42) -> hipótesis de apagado total activada;
        // sin pérdida de enlace ni Mode 02 ausente -> NO dispara recomendación innecesaria.
        val stallInput = empty().copy(
            scanAtMs = 3_000L,
            dtcs = listOf(
                DtcReadResult(
                    DtcCategory.STORED,
                    app.jac.diagnosticlab.elm.ResponseKind.OBD_DATA,
                    listOf(DtcHit("P0087", 0x00, 0x87, DtcScope.GENERIC, "SAE", "7E8", DtcCategory.STORED)),
                    false,
                    "43 00 87",
                    "",
                ),
            ),
            freezeValues = listOf(
                FreezeFrame.decode(0x0C, ex("020C00", "7E8 05 42 0C 00 0C 80"))!!,
                FreezeFrame.decode(0x23, ex("022300", "7E8 05 42 23 00 03 E8"))!!, // 10000 kPa
                FreezeFrame.decode(0x42, ex("024200", "7E8 05 42 42 00 2A F8"))!!, // 11.0 V
            ),
            signals = PidCatalog.decode(0x0C, listOf(0x0C, 0x80)) +
                PidCatalog.decode(0x23, listOf(0x03, 0xE8)) + // 10000 kPa
                PidCatalog.decode(0x42, listOf(0x2A, 0xF8)),  // 11.0 V
            exchanges = listOf(ex("022300", "7E8 05 42 23 00 03 E8")),
        )
        val diffStall = WorkshopReport.buildDifferentialDiagnosis(stallInput)
        assertTrue(diffStall.engineStallHypothesis.contains("apagado total", ignoreCase = true))
        assertTrue(diffStall.engineStallHypothesis.contains("0x23") && diffStall.engineStallHypothesis.contains("0x42"))
        assertFalse("Sin pérdida de enlace ni Mode 02 faltante no debe forzar alerta de recomendación", diffStall.recommendCriticalSet)
    }
}

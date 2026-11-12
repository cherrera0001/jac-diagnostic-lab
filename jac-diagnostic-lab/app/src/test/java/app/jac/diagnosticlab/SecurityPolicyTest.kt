package app.jac.diagnosticlab

import app.jac.diagnosticlab.security.AdapterAuthenticity
import app.jac.diagnosticlab.security.Elm327SecurityAnalyzer
import app.jac.diagnosticlab.security.FindingStatus
import app.jac.diagnosticlab.security.ObdCommandPolicy
import app.jac.diagnosticlab.security.SecurityAssessment
import app.jac.diagnosticlab.security.ThreatModelEngine
import app.jac.diagnosticlab.security.SecurityGuide
import app.jac.diagnosticlab.security.VehicleFaultCorrelation
import app.jac.diagnosticlab.analysis.Symptom
import app.jac.diagnosticlab.elm.ResponseKind
import app.jac.diagnosticlab.obd.DtcCategory
import app.jac.diagnosticlab.obd.DtcDecoder
import app.jac.diagnosticlab.PlainReadingWriter
import app.jac.diagnosticlab.evidence.ReadingFacts
import app.jac.diagnosticlab.evidence.StreetGuide
import app.jac.diagnosticlab.evidence.payloadHasContent
import app.jac.diagnosticlab.evidence.readingReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityPolicyTest {
    @Test
    fun allowlistPermitsDocumentedReadPath() {
        listOf("ATZ", "ATI", "ATE0", "ATL0", "ATS0", "ATH1", "ATSP0", "ATDP", "ATDPN", "ATRV", "0100", "0101", "03", "07", "0A", "0900", "0902", "020200").forEach {
            assertEquals(ObdCommandPolicy.Decision.ALLOW, ObdCommandPolicy.evaluate(it).decision)
        }
    }

    @Test
    fun policyBlocksClearUdsWriteAndHeaders() {
        listOf("04", "ATSH7E0", "ATMA", "2E1234", "27 01", "31 01 00", "ATZ;04", "03\r04").forEach {
            assertEquals("debe denegar $it", ObdCommandPolicy.Decision.DENY, ObdCommandPolicy.evaluate(it).decision)
        }
    }

    @Test
    fun atiAloneDoesNotProveOriginalElm() {
        val report = Elm327SecurityAnalyzer.analyze(
            ati = "ELM327 v2.1",
            protocol = "AUTO,ISO 15765-4 (CAN 11/500)",
            protocolNumber = "A6",
            atrv = "16.9V",
            commandLog = listOf("ATI", "0100", "03"),
        )
        assertEquals(AdapterAuthenticity.PROBABLE_CLONE, report.authenticity)
        assertTrue(report.authenticityNote.contains("imitable"))
    }

    @Test
    fun findingsRequireEvidenceFlags() {
        val empty = SecurityAssessment.findingsFromEvidence(false, false, "", emptyList())
        assertTrue(empty.none { it.findingId == "C4A-AUTO-2026-001" })
        val withBle = SecurityAssessment.findingsFromEvidence(true, true, "ELM327 v2.1", listOf("P0579", "P2299"))
        assertTrue(withBle.any { it.findingId == "C4A-AUTO-2026-001" && it.status == FindingStatus.VERIFIED })
        assertTrue(withBle.any { it.findingId == "C4A-AUTO-2026-005" })
        assertTrue(withBle.any { it.findingId == "C4A-AUTO-2026-006" && it.status == FindingStatus.INCONCLUSIVE })
    }

    @Test
    fun threatModelCoversT001ToT010() {
        val threats = ThreatModelEngine.baselineFromSession(true, true, "ELM327 v2.1", listOf("P0579"))
        val ids = threats.map { it.id }.toSet()
        (1..10).forEach { n ->
            assertTrue("falta T-%03d".format(n), ids.contains("T-%03d".format(n)))
        }
    }

    @Test
    fun faultCorrelationDoesNotBlameCyber() {
        val parsed = app.jac.diagnosticlab.elm.ElmParser.parse(
            "03", "7E806430205792299", promptSeen = true, timedOut = false, disconnected = false,
        )
        val exchange = app.jac.diagnosticlab.elm.Exchange(
            0, "03", parsed.raw, parsed.kind, parsed.detail, true, 1, parsed.frames,
        )
        val dtc = DtcDecoder.decode(DtcCategory.STORED, exchange, true)
        val report = VehicleFaultCorrelation.correlate(
            symptoms = setOf(Symptom.EPC_LAMP, Symptom.BRAKE_POWER_LOSS),
            dtcReads = listOf(dtc),
            milOn = false,
            adapterVoltage = 16.9,
            throttleSupported = true,
            ecuAnswered0100 = true,
            commKinds = setOf(ResponseKind.OBD_DATA),
        )
        assertTrue(report.preservedDtcs.contains("P0579"))
        assertTrue(report.preservedDtcs.contains("P2299"))
        assertTrue(report.cyberLine.any { it.contains("no se demostró", true) })
        assertFalse(report.cyberLine.any { it.contains("comprometido el vehículo", true) })
    }

    @Test
    fun plainReadingExplainsKnownCodesAndDoesNotInventOthers() {
        val idle = PlainReadingWriter.explain(false, false, emptyList(), "", null)
        assertTrue(idle.lines.first().startsWith("1."))
        val known = PlainReadingWriter.explain(false, true, listOf("P0579", "P2299"), "ELM327 v2.1", null)
        assertTrue(known.lines.any { it.contains("desacuerdo") })
        assertTrue(known.title.contains("No cambies piezas"))
        val unknown = PlainReadingWriter.explain(false, true, listOf("P0999"), "", null)
        assertTrue(unknown.lines.any { it.contains("P0999") && it.contains("No traduzco") })
    }

    @Test
    fun streetGuideIsNumberedAndTheReadingJumpsToTheResult() {
        assertEquals((1..8).toList(), StreetGuide.steps.map { it.number })
        assertTrue(StreetGuide.steps.all { it.role.isNotBlank() && it.detail.isNotBlank() })
        assertEquals(1, StreetGuide.current(0, busy = false, hasReading = false, pdfSaved = false))
        assertEquals(5, StreetGuide.current(4, busy = true, hasReading = false, pdfSaved = false))
        assertEquals(6, StreetGuide.current(0, busy = false, hasReading = true, pdfSaved = false))
        assertEquals(8, StreetGuide.current(6, busy = false, hasReading = true, pdfSaved = true))
    }

    @Test
    fun readingReportHashesTheTextAndDoesNotCondemnAPart() {
        val document = readingReport(
            ReadingFacts("2026-11-11T2136", "ELM327 v2.1", "A6", "CAN 11/500", "16.9V", listOf("P0579", "P2299"), true),
        )
        val text = document.lines.joinToString("\n")
        assertEquals(64, document.bodySha256.length)
        assertTrue(text.contains(document.bodySha256))
        assertTrue(text.contains("desacuerdo"))
        assertTrue(text.contains("1. Tú:"))
        assertTrue(text.contains("8. El taller:"))
        assertTrue(text.contains("como ataque"))
        assertFalse(text.contains("reemplaza", true))
        val unknown = readingReport(ReadingFacts("s", "", "", "", "", listOf("P0999"), true))
        assertTrue(unknown.lines.any { it.contains("P0999") && it.contains("No traduzco") })
        assertTrue(text.contains("5. Validación de seguridad"))
        assertTrue(text.contains("no se conecta a internet"))
        assertTrue(text.contains("ficha local"))
        assertTrue(text.contains("N56"))
        assertTrue(text.contains("no se usa"))
        assertFalse(text.contains("%"))
    }

    @Test
    fun securityGuideNumbersEveryCheckAndDoesNotCallTheTruckSafe() {
        val idle = SecurityGuide.checks("", false, emptyList(), false)
        assertEquals((1..9).toList(), idle.map { it.number })
        assertTrue(idle.all { it.result in SecurityGuide.results })
        assertEquals("Comprobado", idle.first { it.number == 1 }.result)
        assertTrue(idle.first { it.number == 1 }.detail.contains("otra aplicación", ignoreCase = true))
        assertEquals("Te toca a ti", idle.first { it.number == 6 }.result)
        assertEquals("Sin comprobar", idle.first { it.number == 8 }.result)
        val read = SecurityGuide.checks("ELM327 v2.1", true, listOf("P0579"), true)
        assertTrue(read.first { it.number == 3 }.detail.contains("se puede copiar"))
        assertTrue(read.first { it.number == 7 }.detail.contains("no demuestra"))
        assertFalse(read.joinToString { it.detail + it.title }.contains("%"))
        assertFalse(read.joinToString { it.detail }.contains("reemplaza", ignoreCase = true))
    }

    @Test
    fun r4_typedYearStaysDeclaredAndDoesNotVerifyTheGateway() {
        val declared = SecurityGuide.checks("", false, emptyList(), false, "JAC T8", "2023").first { it.number == 8 }
        assertEquals("Declarado por ti", declared.result)
        assertTrue(declared.detail.contains("2023"))
        assertTrue(declared.detail.contains("No salió de la computadora"))
        assertTrue(declared.detail.contains("compuerta"))
        assertFalse(declared.detail.contains("verificad", ignoreCase = true))
        val missing = SecurityGuide.checks("", false, emptyList(), false, "", "")
        assertEquals("Sin comprobar", missing.first { it.number == 8 }.result)
        assertTrue(missing.first { it.number == 8 }.detail.contains("Ingresa"))
    }

    @Test
    fun r1_pdfJoinsTheOtherFormatsOnlyWhenThereIsContent() {
        assertEquals(null, payloadHasContent("pdf", exchanges = 1, codes = 0, confirmedAnswer = false, errors = 0, samples = 0, adapterId = ""))
        assertEquals(null, payloadHasContent("json", exchanges = 1, codes = 0, confirmedAnswer = false, errors = 0, samples = 0, adapterId = ""))
        assertEquals(null, payloadHasContent("txt", exchanges = 0, codes = 1, confirmedAnswer = false, errors = 0, samples = 0, adapterId = ""))
        assertEquals(null, payloadHasContent("csv", exchanges = 0, codes = 0, confirmedAnswer = false, errors = 0, samples = 2, adapterId = ""))
    }

    @Test
    fun r2_refusesAnEmptyExportAndSaysTheFileIsNotCreated() {
        listOf("pdf", "json", "txt", "csv").forEach { format ->
            val reason = payloadHasContent(format, 0, 0, false, 0, 0, "")
            assertTrue(format, reason != null && reason!!.contains("No se crea"))
        }
        val csv = payloadHasContent("csv", exchanges = 4, codes = 2, confirmedAnswer = true, errors = 0, samples = 0, adapterId = "ELM327 v2.1")
        assertTrue(csv!!.contains("No se crea el CSV"))
    }

    @Test
    fun r3_savedReadingCanFeedThePdfWhenTheScreenSessionIsEmpty() {
        val screen = payloadHasContent("json", 0, 0, false, 0, 0, "")
        val saved = payloadHasContent("pdf", 0, 2, false, 0, 0, "ELM327 v2.1")
        assertTrue(screen!!.contains("No se crea"))
        assertEquals(null, saved)
    }
}

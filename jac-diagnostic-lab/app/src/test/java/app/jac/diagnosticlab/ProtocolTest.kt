package app.jac.diagnosticlab

import app.jac.diagnosticlab.analysis.AnalysisInput
import app.jac.diagnosticlab.analysis.HypothesisStatus
import app.jac.diagnosticlab.analysis.JacAnalysis
import app.jac.diagnosticlab.analysis.Symptom
import app.jac.diagnosticlab.elm.ElmParser
import app.jac.diagnosticlab.elm.ElmSession
import app.jac.diagnosticlab.elm.Exchange
import app.jac.diagnosticlab.elm.ObdTransport
import app.jac.diagnosticlab.elm.ReadOnlyGate
import app.jac.diagnosticlab.elm.ReadOnlyViolation
import app.jac.diagnosticlab.elm.ResponseAssembler
import app.jac.diagnosticlab.elm.ResponseKind
import app.jac.diagnosticlab.elm.TransportResult
import app.jac.diagnosticlab.evidence.SessionRecord
import app.jac.diagnosticlab.evidence.anonymize
import app.jac.diagnosticlab.evidence.toJson
import app.jac.diagnosticlab.obd.DtcCategory
import app.jac.diagnosticlab.obd.DtcDecoder
import app.jac.diagnosticlab.obd.DtcScope
import app.jac.diagnosticlab.obd.PidDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeObdTransport(
    private val script: Map<String, String> = emptyMap(),
) : ObdTransport {
    val sent = mutableListOf<String>()
    var disconnectOn: String? = null
    var timeoutOn: String? = null
    override val name = "simulado"
    override var connected = true
    override suspend fun connect() { connected = true }
    override suspend fun disconnect() { connected = false }
    override suspend fun transact(command: String, timeoutMs: Long): TransportResult {
        val key = command.uppercase().replace(" ", "")
        sent += key
        if (disconnectOn == key) {
            connected = false
            return TransportResult("41 00 BE", promptSeen = false, timedOut = false, disconnected = true, error = "socket cerrado")
        }
        if (timeoutOn == key) return TransportResult("", promptSeen = false, timedOut = true, disconnected = false, error = "timeout")
        return TransportResult(script[key] ?: "?", promptSeen = true, timedOut = false, disconnected = false)
    }
}

class ProtocolTest {
    @Test
    fun assemblerJoinsFragmentsUntilPrompt() {
        val assembler = ResponseAssembler()
        assertNull(assembler.append("41 00 "))
        assertEquals("41 00 BE 1F A8 13\r", assembler.append("BE 1F A8 13\r>"))
    }

    @Test
    fun recognizesElmIdentity() {
        val parsed = ElmParser.parse("ATI", "ELM327 v1.5", promptSeen = true, timedOut = false, disconnected = false)
        assertEquals(ResponseKind.RECOGNIZED, parsed.kind)
    }

    @Test
    fun stripsEchoAndCanHeader() {
        val raw = "0100\r7E8 06 41 00 BE 1F A8 13\r"
        val parsed = ElmParser.parse("0100", raw, true, false, false)
        assertEquals(ResponseKind.OBD_DATA, parsed.kind)
        assertEquals(listOf(0x41, 0x00, 0xBE, 0x1F, 0xA8, 0x13), parsed.frames.single().payload)
        assertEquals("7E8", parsed.frames.single().header)
    }

    @Test
    fun parsesCompactHeaderWithoutSpaces() {
        val parsed = ElmParser.parse("0100", "7E8064100BE1FA813", true, false, false)
        assertEquals(listOf(0x41, 0x00, 0xBE, 0x1F, 0xA8, 0x13), parsed.frames.single().payload)
    }

    @Test
    fun reassemblesMultiFrameDtcsFromTwoEcus() {
        val multi = "7E8 10 0A 43 04 01 33 01 34\r7E8 21 02 00 03 00 00 00 00"
        val parsed = ElmParser.parse("03", multi, true, false, false)
        val result = DtcDecoder.decode(DtcCategory.STORED, exchange("03", parsed), true)
        assertEquals(listOf("P0133", "P0134", "P0200", "P0300"), result.codes.map { it.code })
        val two = ElmParser.parse("03", "7E8 04 43 01 01 33\r7E9 04 43 01 C1 00", true, false, false)
        val both = DtcDecoder.decode(DtcCategory.STORED, exchange("03", two), true)
        assertEquals(listOf("P0133", "U0100"), both.codes.map { it.code })
        assertEquals(listOf("7E8", "7E9"), both.codes.map { it.ecu })
    }

    @Test
    fun emptyDtcIsNotNoData() {
        val empty = DtcDecoder.decode(DtcCategory.STORED, parsedExchange("03", "43 00"), null)
        assertTrue(empty.confirmedEmpty)
        assertTrue(empty.codes.isEmpty())
        val missing = DtcDecoder.decode(DtcCategory.STORED, parsedExchange("03", "NO DATA"), null)
        assertEquals(ResponseKind.NO_DATA, missing.kind)
        assertFalse(missing.confirmedEmpty)
        assertTrue(missing.note.contains("No es prueba"))
    }

    @Test
    fun classifiesGenericAndManufacturerWithoutInventingText() {
        val generic = DtcDecoder.decodePair(0x01, 0x33, null, DtcCategory.STORED)
        val specific = DtcDecoder.decodePair(0x12, 0x34, null, DtcCategory.STORED)
        assertEquals("P0133", generic.code)
        assertEquals(DtcScope.GENERIC, generic.scope)
        assertEquals("P1234", specific.code)
        assertEquals(DtcScope.MANUFACTURER, specific.scope)
        assertNull(null)
        assertTrue(generic.scopeNote.contains("J2012-DA"))
    }

    @Test
    fun legacyDatasheetPairsDoNotBecomeCountCodes() {
        val parsed = ElmParser.parse("03", "43 01 33 00 00 00 00", true, false, false)
        val result = DtcDecoder.decode(DtcCategory.STORED, exchange("03", parsed), false)
        assertEquals(listOf("P0133"), result.codes.map { it.code })
    }

    @Test
    fun timeoutPartialDisconnectAndBusErrorsDoNotInventCodes() {
        assertEquals(ResponseKind.TIMEOUT, ElmParser.parse("03", "", false, true, false).kind)
        val partial = ElmParser.parse("03", "43 01", false, true, false)
        assertEquals(ResponseKind.PARTIAL, partial.kind)
        assertTrue(DtcDecoder.decode(DtcCategory.STORED, exchange("03", partial), true).codes.isEmpty())
        assertEquals(ResponseKind.DISCONNECTED, ElmParser.parse("0100", "41 00", false, false, true).kind)
        assertEquals(ResponseKind.CAN_ERROR, ElmParser.parse("0100", "CAN ERROR", true, false, false).kind)
        assertEquals(ResponseKind.BUS_INIT_ERROR, ElmParser.parse("0100", "BUS INIT: ERROR", true, false, false).kind)
        assertEquals(ResponseKind.BUS_INIT_ERROR, ElmParser.parse("0100", "BUS INIT... ERROR", true, false, false).kind)
        assertEquals(ResponseKind.UNABLE_TO_CONNECT, ElmParser.parse("0100", "SEARCHING...\rUNABLE TO CONNECT", true, false, false).kind)
        assertEquals(ResponseKind.STOPPED, ElmParser.parse("0100", "STOPPED", true, false, false).kind)
        assertEquals(ResponseKind.QUESTION_MARK, ElmParser.parse("ATZ", "?", true, false, false).kind)
        val bad = ElmParser.parse("0100", "41 00 GG", true, false, false)
        assertEquals(ResponseKind.MALFORMED, bad.kind)
        assertTrue(bad.frames.isEmpty())
    }

    @Test
    fun unsupportedPidStaysWithoutAZero() {
        val reading = PidDecoder.decode(0x0C, parsedExchange("010C", "NO DATA"))
        assertNull(reading.value)
        assertFalse(reading.valid)
        val rpm = PidDecoder.decode(0x0C, parsedExchange("010C", "41 0C 1A F8"))
        assertEquals(1726.0, rpm.value!!, 0.01)
        val coolant = PidDecoder.decode(0x05, parsedExchange("0105", "41 05 5A"))
        assertEquals(50.0, coolant.value!!, 0.01)
        val support = PidDecoder.supported(0x00, listOf(0xBE, 0x1F, 0xA8, 0x13))
        assertTrue(0x01 in support)
        assertFalse(0x02 in support)
        assertTrue(0x0C in support)
    }

    @Test
    fun gateBlocksClearAndSessionNeverSendsIt() = runBlocking {
        assertFails { ReadOnlyGate.check("04") }
        assertFails { ReadOnlyGate.check("ATZ;04") }
        assertFails { ReadOnlyGate.check("ATSH7E0") }
        assertFails { ReadOnlyGate.check("0801") }
        ReadOnlyGate.check("ATZ")
        ReadOnlyGate.check("0100")
        ReadOnlyGate.check("03")
        ReadOnlyGate.check("0A")
        ReadOnlyGate.check("ATSP6")
        val fake = FakeObdTransport(happy())
        val session = ElmSession(fake)
        session.exchange("04", 1000)
        val report = session.initialize("0")
        assertFalse(fake.sent.contains("04"))
        assertEquals(
            listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATI", "ATSP0", "ATDP", "ATDPN", "ATRV", "0100", "ATDP", "ATDPN"),
            fake.sent,
        )
        assertTrue(report.ecuTalking)
        assertEquals("ELM327 v1.5", report.adapterIdentity)
    }

    @Test
    fun disconnectDuringReadStopsTheSequence() = runBlocking {
        val fake = FakeObdTransport(happy())
        fake.disconnectOn = "ATE0"
        val report = ElmSession(fake).initialize("0")
        assertEquals(listOf("ATZ", "ATE0"), fake.sent)
        assertEquals(ResponseKind.DISCONNECTED, report.exchanges.last().kind)
        assertFalse(report.ecuTalking)
    }

    @Test
    fun symptomsAloneDoNotConfirmAFault() {
        val report = JacAnalysis.evaluate(
            AnalysisInput(
                symptoms = Symptom.entries.toSet(),
                dtcReads = emptyList(),
                milOn = null,
                adapterVoltage = null,
                rpm = null,
                throttleSupported = false,
                commKinds = emptySet(),
                ecuAnswered0100 = false,
            ),
        )
        assertEquals(HypothesisStatus.INSUFFICIENT_DATA, report.hypotheses.first { it.id == "H1" }.status)
        assertTrue(report.confirmed.isEmpty())
        assertFalse(report.methodology.contains("%"))
        val text = report.hypotheses.joinToString { it.title + it.supporting + it.nextChecks }
        assertFalse(text.contains("reemplace", ignoreCase = true))
    }

    @Test
    fun exportCanHideVinAndAddress() {
        val record = SessionRecord(
            id = "t",
            startedAtEpochMs = 1,
            androidDevice = "Xiaomi",
            androidRelease = "17",
            connectionType = "clásico",
            adapterName = "OBDII",
            adapterAddress = "11:22:33:44:55:66",
            adapterIdentity = "ELM327 v1.5",
            protocolText = "ISO 15765-4",
            protocolNumber = "A6",
            adapterVoltage = "12.4V",
            exchanges = listOf(parsedExchange("0902", "49 02 VIN-REAL")),
            dtcs = emptyList(),
            freeze = emptyList(),
            samples = emptyList(),
            errors = emptyList(),
            connectionState = "conectado",
            symptoms = emptySet(),
            vin = "VIN-REAL",
            simulated = false,
            vehicleNote = "JAC T8",
            analysis = null,
        )
        val hidden = anonymize(record)
        val json = toJson(hidden)
        assertFalse(json.contains("VIN-REAL"))
        assertFalse(json.contains("11:22:33:44:55:66"))
        assertTrue(json.contains("VIN-ANONIMIZADO"))
    }

    private fun happy(): Map<String, String> = mapOf(
        "ATZ" to "ELM327 v1.5",
        "ATE0" to "OK",
        "ATL0" to "OK",
        "ATS0" to "OK",
        "ATH1" to "OK",
        "ATI" to "ELM327 v1.5",
        "ATSP0" to "OK",
        "ATDP" to "ISO 15765-4 (CAN 11/500)",
        "ATDPN" to "A6",
        "ATRV" to "12.6V",
        "0100" to "7E8 06 41 00 BE 1F A8 13",
    )

    private fun parsedExchange(command: String, raw: String): Exchange {
        val parsed = ElmParser.parse(command, raw, raw.isNotBlank() && !raw.contains("BUS") || true, false, false)
        val kind = ElmParser.parse(command, raw, promptSeen = true, timedOut = false, disconnected = false)
        return Exchange(0, command, raw, kind.kind, kind.detail, true, 1, kind.frames)
    }

    private fun exchange(command: String, parsed: app.jac.diagnosticlab.elm.ParsedResponse) =
        Exchange(0, command, parsed.raw, parsed.kind, parsed.detail, true, 1, parsed.frames)

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("debía bloquearse")
        } catch (_: ReadOnlyViolation) {
        }
    }
}

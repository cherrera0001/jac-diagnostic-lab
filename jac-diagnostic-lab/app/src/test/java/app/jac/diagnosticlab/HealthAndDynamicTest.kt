package app.jac.diagnosticlab

import app.jac.diagnosticlab.analysis.HealthAssessment
import app.jac.diagnosticlab.analysis.HealthInput
import app.jac.diagnosticlab.analysis.HealthStatus
import app.jac.diagnosticlab.dynamic.GuidedTests
import app.jac.diagnosticlab.dynamic.TraceFrame
import app.jac.diagnosticlab.elm.ElmParser
import app.jac.diagnosticlab.elm.Exchange
import app.jac.diagnosticlab.obd.Mode06Decoder
import app.jac.diagnosticlab.obd.MonitorState
import app.jac.diagnosticlab.obd.PidCatalog
import app.jac.diagnosticlab.obd.PidDecoder
import app.jac.diagnosticlab.obd.ReadinessDecoder
import app.jac.diagnosticlab.security.ObdCommandPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bytes reales: JAC T8 2023, ELM327 BLE, 18-11-2026, motor en ralentí. */
class HealthAndDynamicTest {
    private fun ex(cmd: String, raw: String): Exchange {
        val p = ElmParser.parse(cmd, raw, promptSeen = true, timedOut = false, disconnected = false)
        return Exchange(0, cmd, raw, p.kind, p.detail, true, 0, p.frames)
    }

    @Test fun readinessRealT8IsDieselAndComplete() {
        val r = ReadinessDecoder.fromExchange(ex("0101", "7E8064101020EC900"))!!
        assertTrue(r.compressionIgnition)
        assertEquals(false, r.milOn)
        assertEquals(2, r.dtcCount)
        assertTrue(r.incomplete.isEmpty())
        val names = r.supported.map { it.name }
        assertTrue("DPF" in names.joinToString())
        assertTrue("Presión de turbo" in names)
        assertEquals(MonitorState.NOT_SUPPORTED, r.monitors.first { it.name.startsWith("Fallo de combustión") }.state)
    }

    @Test fun readinessIncompleteFromNov12() {
        val r = ReadinessDecoder.fromExchange(ex("0101", "7E8064101020EC901"))!!
        assertEquals(listOf("Catalizador NMHC"), r.incomplete.map { it.name })
    }

    @Test fun realBitmapsDeclareDieselPids() {
        val sup = PidDecoder.supported(0x60, listOf(0xEF, 0xEB, 0x6B, 0x53)) + PidDecoder.supported(0x80, listOf(0x26, 0x37, 0x60, 0x00))
        listOf(0x6D, 0x70, 0x7A, 0x7C, 0x78, 0x83, 0x8B).forEach { assertTrue("%02X".format(it), it in sup) }
    }

    @Test fun catalogDecodesRealIdleValues() {
        assertEquals(778.25, PidCatalog.decode(0x0C, listOf(0x0C, 0x29)).single().value!!, 0.01)
        assertEquals(14.39, PidCatalog.decode(0x42, listOf(0x38, 0x36)).single().value!!, 0.01)
        assertEquals(60.0, PidCatalog.decode(0x05, listOf(0x64)).single().value!!, 0.01)
        // 0x46 = Diésel
        assertEquals("Diésel", PidCatalog.decode(0x51, listOf(0x04)).single().text)
    }

    @Test fun catalogRespectsSupportBits() {
        // 0x7A: solo ΔP soportado (bit0) → 1 señal, 1,25 kPa
        val dpf = PidCatalog.decode(0x7A, listOf(0x01, 0x00, 0x7D, 0, 0, 0, 0))
        assertEquals(1, dpf.size)
        assertEquals(1.25, dpf[0].value!!, 0.001)
        // 0x70: comandado y real A; 0x0CE0*0.03125 = 103 kPa
        val boost = PidCatalog.decode(0x70, listOf(0x03, 0x0C, 0xE0, 0x0C, 0x80, 0, 0, 0, 0, 0))
        assertEquals(listOf("70.CA", "70.RA"), boost.map { it.key })
        assertEquals(103.0, boost[0].value!!, 0.01)
        // 0x8B: regeneración en curso + carga 50 %
        val at = PidCatalog.decode(0x8B, listOf(0x13, 0x01, 0x80, 0, 0, 0, 0))
        assertEquals("EN CURSO", at.first { it.key == "8B.REGEN" }.text)
        assertEquals(50.2, at.first { it.key == "8B.TRIG" }.value!!, 0.1)
    }

    @Test fun multiPidSplitByKnownLength() {
        val payload = listOf(0x41, 0x0C, 0x0C, 0x29, 0x0D, 0x00, 0x05, 0x64, 0x42, 0x38, 0x36)
        val parts = PidCatalog.splitMulti(payload, listOf(0x0C, 0x0D, 0x05, 0x42))
        assertEquals(setOf(0x0C, 0x0D, 0x05, 0x42), parts.keys)
        assertEquals(listOf(0x38, 0x36), parts[0x42])
    }

    @Test fun multiPidThroughElmParserIsoTp() {
        val raw = "7E8100B410C0C290D00\r7E8210564423836AAAA\r"
        val e = ex("010C0D0542", raw)
        val payload = e.frames.first { it.payload.first() == 0x41 }.payload
        val parts = PidCatalog.splitMulti(payload, listOf(0x0C, 0x0D, 0x05, 0x42))
        assertEquals(4, parts.size)
        assertEquals(listOf(0x38, 0x36), parts[0x42])
    }

    @Test fun policyAllowsReadOnlyExtensionsOnly() {
        listOf("010C0D0542", "0600", "0621", "0900", "0904", "090A", "090B", "0141").forEach {
            assertEquals(it, ObdCommandPolicy.Decision.ALLOW, ObdCommandPolicy.evaluate(it).decision)
        }
        listOf("04", "010C0D05420B1011", "2E1234", "2F0101", "3101", "1003", "ATSH7E0", "0A01").forEach {
            assertEquals(it, ObdCommandPolicy.Decision.DENY, ObdCommandPolicy.evaluate(it).decision)
        }
    }

    @Test fun mode06PassAndFail() {
        // 46 | 85 01 1A 0064 0050 0078 (en límite) | 85 02 1A 0010 0050 0078 (fuera)
        val raw = "7E8101346850 11A0064\r".replace(" ", "")
        val bytes = listOf(0x46, 0x85, 0x01, 0x1A, 0x00, 0x64, 0x00, 0x50, 0x00, 0x78, 0x85, 0x02, 0x1A, 0x00, 0x10, 0x00, 0x50, 0x00, 0x78)
        val e = Exchange(0, "0685", raw, app.jac.diagnosticlab.elm.ResponseKind.OBD_DATA, "", true, 0,
            listOf(app.jac.diagnosticlab.elm.ObdFrame("7E8", bytes, true, raw)))
        val r = Mode06Decoder.decode(e)
        assertEquals(2, r.size)
        assertTrue(r[0].passed)
        assertFalse(r[1].passed)
        assertEquals("Presión de turbo B1", r[0].monitorName)
        assertEquals("kPa", r[0].unit)
    }

    @Test fun healthFlagsRealT8Codes() {
        val readiness = ReadinessDecoder.decode(listOf(0x02, 0x0E, 0xC9, 0x00))
        val items = HealthAssessment.evaluate(
            HealthInput(
                values = mapOf("0C" to 778.0, "0D" to 0.0, "42" to 14.39, "05" to 61.0, "1F" to 300.0),
                readiness = readiness,
                storedDtcs = listOf("P0579", "P2299"),
                pendingDtcs = listOf("P0579"),
                adapterVoltage = 16.7,
            ),
        )
        assertEquals(HealthStatus.CHECK, items.first().status)
        assertTrue(items.any { it.system == "Eléctrico" && it.status == HealthStatus.OK })
        assertTrue(items.any { it.title.contains("adaptador mide") })
        assertTrue(items.any { it.system == "Monitores" && it.status == HealthStatus.OK })
    }

    @Test fun dieselIdleThrottleIsNotFlagged() {
        assertEquals(null, PidDecoder.plausibilityNote(0x11, 90.0, 800.0, 0.0, compressionIgnition = true))
        assertNotNull(PidDecoder.plausibilityNote(0x11, 90.0, 800.0, 0.0, compressionIgnition = false))
    }

    @Test fun idleTestStableEngineIsOk() {
        val frames = (0 until 60).map { i -> TraceFrame(i * 1000L, mapOf("0C" to 780.0 + (i % 3), "42" to 14.4, "0D" to 0.0)) }
        val r = GuidedTests.analyze(GuidedTests.byId("IDLE"), frames)
        assertEquals(HealthStatus.OK, r.metrics.first { it.name == "RPM desviación estándar" }.status)
        assertEquals(HealthStatus.OK, r.verdict)
    }

    @Test fun roadDetectsPowerCutWithPedalHeld() {
        val frames = buildList {
            for (i in 0 until 10) add(TraceFrame(i * 250L, mapOf("0D" to 50.0, "49" to 40.0, "62" to 60.0, "0C" to 2000.0)))
            add(TraceFrame(2500, mapOf("0D" to 49.0, "49" to 40.0, "62" to 10.0, "0C" to 1500.0)))
            for (i in 11 until 20) add(TraceFrame(i * 250L, mapOf("0D" to 45.0, "49" to 40.0, "62" to 12.0, "0C" to 1400.0)))
        }
        val r = GuidedTests.analyze(GuidedTests.byId("ROAD"), frames)
        assertEquals(1, r.events.count { it.kind == "CORTE DE POTENCIA" })
        assertEquals(HealthStatus.CHECK, r.verdict)
    }

    @Test fun roadNoFalseCutWhenDriverLiftsPedal() {
        val frames = buildList {
            for (i in 0 until 10) add(TraceFrame(i * 250L, mapOf("0D" to 50.0, "49" to 40.0, "62" to 60.0)))
            for (i in 10 until 20) add(TraceFrame(i * 250L, mapOf("0D" to 48.0, "49" to 0.0, "62" to 5.0)))
        }
        assertEquals(0, GuidedTests.analyze(GuidedTests.byId("ROAD"), frames).events.size)
    }

    @Test fun csvUsesDotDecimalsRegardlessOfLocale() {
        val prev = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("es-CL"))
        try {
            val csv = GuidedTests.toCsv(listOf(TraceFrame(0, mapOf("42" to 14.39))), emptyList())
            assertTrue(csv, csv.contains("14.3900"))
        } finally {
            java.util.Locale.setDefault(prev)
        }
    }

    @Test fun flowControlOnlyExactStandardValues() {
        listOf("ATFCSH7E0", "ATFCSD300000", "ATFCSM1").forEach {
            assertEquals(it, ObdCommandPolicy.Decision.ALLOW, ObdCommandPolicy.evaluate(it).decision)
        }
        listOf("ATFCSH7DF", "ATFCSD300A00", "ATFCSD", "ATFCSH7E1").forEach {
            assertEquals(it, ObdCommandPolicy.Decision.DENY, ObdCommandPolicy.evaluate(it).decision)
        }
    }

    @Test fun parserMarksTruncatedIsoTp() {
        // Respuesta real 0685: FF anuncia 0x25 bytes, solo llegan 4 CF (34 bytes).
        val e = ex("0685", "7E810254685B099004F\r7E821FCE0138885B199\r7E822004FEC78032085\r7E823B2990000000000\r7E8240085B399000000\r")
        assertTrue(e.frames.any { it.truncated })
        assertEquals(3, Mode06Decoder.decode(e).size)
    }

    @Test fun sentinelsBecomeNoData() {
        assertEquals(null, PidCatalog.decode(0x34, listOf(0, 0, 0, 0)).single().value)
        assertEquals(null, PidCatalog.decode(0x8E, listOf(0xFF)).single().value)
    }

    @Test fun realDpfInletOutletMatchDelta() {
        val s = PidCatalog.decode(0x7A, listOf(0x07, 0x00, 0x60, 0x27, 0x1E, 0x26, 0xBB)).associate { it.key to it.value!! }
        assertEquals(0.96, s.getValue("7A.DP"), 0.001)
        assertEquals(s.getValue("7A.IN") - s.getValue("7A.OUT"), s.getValue("7A.DP"), 0.05)
    }
}

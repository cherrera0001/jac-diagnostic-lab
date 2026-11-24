package app.jac.diagnosticlab

import app.jac.diagnosticlab.analysis.DashboardLinkStatus
import app.jac.diagnosticlab.analysis.DrivingGuidanceEngine
import app.jac.diagnosticlab.analysis.FuelEstimate
import app.jac.diagnosticlab.analysis.FuelEstimateSource
import app.jac.diagnosticlab.analysis.FuelEstimator
import app.jac.diagnosticlab.analysis.FuelProfile
import app.jac.diagnosticlab.analysis.FullTankAnchor
import app.jac.diagnosticlab.analysis.GearInference
import app.jac.diagnosticlab.analysis.HealthItem
import app.jac.diagnosticlab.analysis.HealthStatus
import app.jac.diagnosticlab.analysis.ShiftCue
import app.jac.diagnosticlab.analysis.TripZone
import app.jac.diagnosticlab.bt.BackgroundCaptureNotification
import app.jac.diagnosticlab.bt.CaptureNotificationSpec
import app.jac.diagnosticlab.bt.LinkPhase
import app.jac.diagnosticlab.car.CarDashboardModel
import app.jac.diagnosticlab.car.CarTone
import app.jac.diagnosticlab.dynamic.TraceFrame
import app.jac.diagnosticlab.elm.ElmParser
import app.jac.diagnosticlab.elm.ElmSession
import app.jac.diagnosticlab.elm.Exchange
import app.jac.diagnosticlab.elm.ObdTransport
import app.jac.diagnosticlab.elm.ReadOnlyGate
import app.jac.diagnosticlab.elm.ReadOnlyViolation
import app.jac.diagnosticlab.elm.ResponseKind
import app.jac.diagnosticlab.elm.TransportKind
import app.jac.diagnosticlab.elm.TransportResult
import app.jac.diagnosticlab.evidence.DiagnosticInference
import app.jac.diagnosticlab.evidence.EvidenceEngine
import app.jac.diagnosticlab.evidence.FactKind
import app.jac.diagnosticlab.evidence.InferenceConfidence
import app.jac.diagnosticlab.evidence.ManualInspection
import app.jac.diagnosticlab.evidence.MissingReason
import app.jac.diagnosticlab.evidence.OperatingMode
import app.jac.diagnosticlab.evidence.sha256Hex
import app.jac.diagnosticlab.obd.DtcCategory
import app.jac.diagnosticlab.obd.DtcHit
import app.jac.diagnosticlab.obd.DtcReadResult
import app.jac.diagnosticlab.obd.DtcScope
import app.jac.diagnosticlab.obd.FreezeFrame
import app.jac.diagnosticlab.obd.FreezeNote
import app.jac.diagnosticlab.obd.MonitorState
import app.jac.diagnosticlab.obd.MonitorStatus
import app.jac.diagnosticlab.obd.PidCatalog
import app.jac.diagnosticlab.obd.Readiness
import app.jac.diagnosticlab.obd.VehicleInfo
import app.jac.diagnosticlab.profile.EcuMode09Values
import app.jac.diagnosticlab.profile.JacT8Catalog
import app.jac.diagnosticlab.profile.Mode09Record
import app.jac.diagnosticlab.profile.ProfileResolver
import app.jac.diagnosticlab.profile.VehicleFingerprint
import app.jac.diagnosticlab.report.WorkshopInput
import app.jac.diagnosticlab.security.ObdCommandPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * SPEC-034 — Motor de Evidencia Técnica (Blue Ocean MVP).
 * Pruebas TDD r17..r21 sobre fixtures SINTÉTICOS (sin datos personales reales).
 */
class EvidenceEngineTest {

    private fun ex(command: String, raw: String, atMs: Long = 1_000L, elapsedMs: Long = 45L): Exchange {
        val parsed = ElmParser.parse(command, raw, promptSeen = true, timedOut = false, disconnected = false)
        return Exchange(atMs, command, raw, parsed.kind, parsed.detail, true, elapsedMs, parsed.frames)
    }

    private fun baseInput(
        sessionId: String = "SPEC034-S1",
        vin: String? = "SYNTHETICP0000001",
    ) = WorkshopInput(
        appVersion = "v1.11.0 (25)",
        sessionId = sessionId,
        generatedAtMs = 1_700_000_000_000L,
        declaredModel = "JAC T8",
        declaredYear = "2023",
        vin = vin,
        protocol = "6 ISO 15765-4 (CAN 11/500)",
        adapterId = "ELM327 v1.5",
        adapterVoltage = "12.6V",
        scanAtMs = null,
        dtcs = emptyList(),
        freezeNotes = emptyList(),
        freezeValues = emptyList(),
        signals = emptyList(),
        rawByPid = emptyMap(),
        supportedPids = emptySet(),
        readiness = null,
        readinessCycle = null,
        mode06 = emptyList(),
        vehicleInfo = null,
        ecuIdentification = emptyList(),
        profile = null,
        health = emptyList(),
        exchanges = emptyList(),
        errors = emptyList(),
    )

    @Test
    fun r17_evidenceEnvelopeSeparatesFactsMissingDataAndInferences() {
        val p0579 = DtcHit("P0579", 0x05, 0x79, DtcScope.GENERIC, "genérico SAE", "7E8", DtcCategory.STORED)
        val p2299 = DtcHit("P2299", 0x22, 0x99, DtcScope.GENERIC, "genérico SAE", "7E8", DtcCategory.STORED)
        val dtcs = listOf(
            DtcReadResult(DtcCategory.STORED, ResponseKind.OBD_DATA, listOf(p0579, p2299), false, "7E806430205792299", ""),
            DtcReadResult(DtcCategory.PENDING, ResponseKind.OBD_DATA, listOf(p0579.copy(category = DtcCategory.PENDING)), false, "7E80447010579", ""),
            DtcReadResult(DtcCategory.PERMANENT, ResponseKind.OBD_DATA, emptyList(), false, "7E8037F0A13", ""),
        )
        val rpmEx = ex("010C", "7E8 04 41 0C 0C 80", atMs = 2_000L, elapsedMs = 32L)
        val noDataEx = ex("0902", "NO DATA", atMs = 2_100L, elapsedMs = 80L)
        val freeze = listOf(
            FreezeFrame.decode(0x0C, ex("020C00", "7E8 05 42 0C 00 15 99"))!!,
            FreezeFrame.decode(0x33, ex("023300", "7E8 04 42 33 00 00"))!!,
        )
        val profile = ProfileResolver.resolve(
            VehicleFingerprint.from("JAC T8", "2023", "SYNTHETICP0000001", listOf("7E8" to "DCM6.2"), null, null, "6", listOf("7E8")),
        )
        val input = baseInput().copy(
            scanAtMs = 2_500L,
            dtcs = dtcs,
            signals = PidCatalog.decode(0x0C, listOf(0x0C, 0x80)),
            rawByPid = mapOf(0x0C to "0C 80"),
            supportedPids = setOf(0x0C, 0x05),
            freezeNotes = listOf(FreezeNote("7E8054202000579", "P0579", "")),
            freezeValues = freeze,
            ecuIdentification = listOf(
                Mode09Record("0904", 0x04, 2_200L, 41L, "OBD_DATA", "7E8 DCM6.2", listOf(EcuMode09Values("7E8", listOf("DCM6.2"))), ""),
            ),
            profile = profile,
            health = listOf(
                HealthItem("Códigos", HealthStatus.CHECK, "Códigos almacenados: P0579, P2299", "Leer con escáner de marca.", "Mode 03"),
            ),
            exchanges = listOf(rpmEx, noDataEx),
        )

        val envelope = EvidenceEngine.buildEnvelope(input, JacT8Catalog.v1)

        assertEquals(OperatingMode.VERIFIED_PROFILE, envelope.operatingMode)
        assertEquals("DELPHI_DCM62_PN", envelope.ecuProfileId)

        // 1. observedFacts contiene únicamente lecturas crudas verificadas con trazabilidad completa.
        val rpmFact = envelope.observedFacts.first { it.kind == FactKind.PID_READING && it.key == "0C" }
        assertEquals(800.0, rpmFact.value!!, 0.01)
        assertEquals("rpm", rpmFact.unit)
        assertEquals("0C 80", rpmFact.rawBytes)
        assertEquals(2_000L, rpmFact.timestampMs)
        assertEquals(32L, rpmFact.latencyMs)
        assertEquals("DELPHI_DCM62_PN", rpmFact.ecuProfile)
        assertTrue(envelope.observedFacts.any { it.kind == FactKind.DTC_CODE && it.key == "P0579" })
        assertTrue(envelope.observedFacts.any { it.kind == FactKind.DTC_CODE && it.key == "P2299" })

        // 2. missingData separa explícitamente servicios rechazados (Mode 0A), NO_DATA y ceros no confiables.
        assertTrue(envelope.missingData.any { it.key == "DTC:PERMANENT" && it.reason == MissingReason.SERVICE_REFUSED })
        assertTrue(envelope.missingData.any { it.key == "EXCHANGE:0902" && it.reason == MissingReason.NO_RESPONSE })
        assertTrue(envelope.missingData.any { it.key == "FREEZE:33" && it.reason == MissingReason.UNRELIABLE_ZERO_FILL })

        // 3. inferences siempre tienen confianza y nunca se presentan como causa raíz confirmada ni se mezclan en observedFacts.
        assertTrue(envelope.inferences.isNotEmpty())
        assertTrue(envelope.inferences.all { !it.isConfirmedRootCause })
        assertTrue(envelope.inferences.any { it.confidence == InferenceConfidence.MODERATE && it.supportingFactIds.isNotEmpty() })
        val inferenceIds = envelope.inferences.map { it.id }.toSet()
        assertTrue(envelope.observedFacts.none { it.id in inferenceIds })
        assertTrue(envelope.observedFacts.none { it.label.contains("hipótesis", ignoreCase = true) || it.label.contains("correlación", ignoreCase = true) })

        // Invariante del contrato: intentar marcar una inferencia como causa raíz confirmada falla inmediatamente.
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticInference(
                id = "INF:INVALID",
                title = "Causa inventada",
                hypothesis = "X",
                confidence = InferenceConfidence.MODERATE,
                isConfirmedRootCause = true,
            )
        }
    }

    @Test
    fun r18_unknownEcuDegradesGracefullyWithoutInventingHealthScore() {
        // Caso A: CALID desconocido -> modo OBD-II estándar (UNVERIFIED_PROFILE), sin inventar puntaje porcentual.
        val unknownCalidInput = baseInput(sessionId = "UNKNOWN-ECU", vin = null).copy(
            declaredModel = "Maquinaria Genérica",
            declaredYear = "2019",
            scanAtMs = 3_000L,
            signals = PidCatalog.decode(0x0C, listOf(0x0B, 0xB8)),
            rawByPid = mapOf(0x0C to "0B B8"),
            supportedPids = setOf(0x0C),
            vehicleInfo = VehicleInfo(calibrationIds = listOf("UNCATALOGUED_ECU_9999")),
            ecuIdentification = listOf(
                Mode09Record("0904", 0x04, 2_900L, 50L, "OBD_DATA", "7E8 UNCATALOGUED_ECU_9999", listOf(EcuMode09Values("7E8", listOf("UNCATALOGUED_ECU_9999"))), ""),
            ),
        )
        val unknownBundle = EvidenceEngine.exportBundle(unknownCalidInput, JacT8Catalog.v1, zone = ZoneId.of("UTC"))
        val unknownEnv = unknownBundle.envelope

        assertEquals(OperatingMode.UNVERIFIED_PROFILE, unknownEnv.operatingMode)
        assertNull(unknownEnv.ecuProfileId)
        assertNull(unknownEnv.globalHealthScorePercent)
        assertTrue(unknownEnv.observedFacts.any { it.kind == FactKind.PID_READING && it.ecuProfile == EvidenceEngine.STANDARD_OBD_PROFILE })
        assertTrue(unknownEnv.missingData.any { it.key == "ECU_PROFILE" && it.reason == MissingReason.UNVERIFIED_ECU_PROFILE })
        assertFalse(unknownBundle.json.contains("healthScore\"", ignoreCase = true))
        assertFalse(unknownBundle.json.contains("healthPercentage", ignoreCase = true))
        assertFalse(unknownBundle.pdfLines.joinToString("\n").contains(Regex("salud\\s*[:=]?\\s*\\d+\\s*%", RegexOption.IGNORE_CASE)))

        // Caso B: Sin respuesta OBD -> inspección física guiada (MANUAL_INSPECTION), sin puntaje porcentual.
        val inspection = ManualInspection(
            recordedAtMs = 4_000L,
            odometerKm = 184_500.0,
            horometerHours = 4_250.5,
            visualCondition = "Fuga leve en manguera de admisión; correa de accesorios desgastada",
            observations = listOf("Sin conector OBD-II activo", "Nivel de refrigerante en marca MIN"),
            inspector = "Técnico de campo",
        )
        val noObdInput = baseInput(sessionId = "MANUAL-01", vin = null).copy(
            declaredModel = "Tractor Mecánico",
            declaredYear = "2008",
            exchanges = listOf(ex("0100", "UNABLE TO CONNECT", atMs = 3_900L, elapsedMs = 2_000L)),
            manualInspection = inspection,
        )
        val manualBundle = EvidenceEngine.exportBundle(noObdInput, JacT8Catalog.v1, inspection, ZoneId.of("UTC"))
        val manualEnv = manualBundle.envelope

        assertEquals(OperatingMode.MANUAL_INSPECTION, manualEnv.operatingMode)
        assertNull(manualEnv.ecuProfileId)
        assertNull(manualEnv.globalHealthScorePercent)
        assertTrue(manualEnv.missingData.any { it.key == "OBD_LINK" && it.reason == MissingReason.NO_OBD_COMMUNICATION })
        assertTrue(manualEnv.observedFacts.any { it.kind == FactKind.MANUAL_OBSERVATION && it.key == "horometer_h" && it.value == 4_250.5 })
        assertTrue(manualEnv.observedFacts.any { it.kind == FactKind.MANUAL_OBSERVATION && it.key == "odometer_km" && it.value == 184_500.0 })
        assertTrue(manualEnv.observedFacts.any { it.kind == FactKind.MANUAL_OBSERVATION && it.key == "visual_condition" })
        assertTrue(manualBundle.pdfLines.any { it.contains("Inspección física guiada") })
        assertFalse(manualBundle.pdfLines.joinToString("\n").contains(Regex("salud\\s*[:=]?\\s*\\d+\\s*%", RegexOption.IGNORE_CASE)))
    }

    @Test
    fun r19_exportedEvidenceBundleIncludesSha256AndCatalogVersion() {
        val input = baseInput(sessionId = "TRACE-01").copy(
            scanAtMs = 5_000L,
            signals = PidCatalog.decode(0x0C, listOf(0x0C, 0x4B)) + PidCatalog.decode(0x42, listOf(0x36, 0xB0)),
            rawByPid = mapOf(0x0C to "0C 4B", 0x42 to "36 B0"),
            supportedPids = setOf(0x0C, 0x42),
            ecuIdentification = listOf(
                Mode09Record("0904", 0x04, 4_900L, 35L, "OBD_DATA", "7E8 DCM6.2", listOf(EcuMode09Values("7E8", listOf("DCM6.2"))), ""),
            ),
            exchanges = listOf(ex("010C", "7E8 04 41 0C 0C 4B", atMs = 4_800L, elapsedMs = 28L)),
        )

        val bundle = EvidenceEngine.exportBundle(input, JacT8Catalog.v1, zone = ZoneId.of("UTC"))
        val expectedHash = sha256Hex(bundle.rawEvidenceJson.toByteArray(Charsets.UTF_8))

        assertTrue(bundle.offlineOnly)
        assertEquals("v1.11.0 (25)", bundle.appVersion)
        assertEquals(JacT8Catalog.v1.version, bundle.catalogVersion)
        assertEquals("jac-t8-catalog-v1", bundle.catalogVersion)
        assertTrue("SHA-256 debe tener 64 hex minúsculas", bundle.rawEvidenceSha256.matches(Regex("^[0-9a-f]{64}$")))
        assertEquals(expectedHash, bundle.rawEvidenceSha256)

        // JSON exportado incluye versión app, catálogo y hash SHA-256 válido.
        assertTrue(bundle.json.contains("\"appVersion\": \"v1.11.0 (25)\""))
        assertTrue(bundle.json.contains("\"catalogVersion\": \"jac-t8-catalog-v1\""))
        assertTrue(bundle.json.contains("\"rawEvidenceSha256\": \"${bundle.rawEvidenceSha256}\""))
        assertTrue(bundle.json.contains("\"offlineOnly\": true"))

        // PDF (estructura y líneas) incluye versión app, catálogo y hash SHA-256 válido.
        val pdfText = bundle.pdfLines.joinToString("\n")
        assertTrue(bundle.pdfDocument.meta.any { it.first == "Catálogo" && it.second == "jac-t8-catalog-v1" })
        assertTrue(pdfText.contains("v1.11.0 (25)"))
        assertTrue(pdfText.contains("jac-t8-catalog-v1"))
        assertTrue(pdfText.contains(bundle.rawEvidenceSha256))

        // Integridad criptográfica: cualquier alteración en los hechos crudos cambia el hash.
        val altered = EvidenceEngine.exportBundle(
            input.copy(rawByPid = mapOf(0x0C to "0C 4C", 0x42 to "36 B0")),
            JacT8Catalog.v1,
            zone = ZoneId.of("UTC"),
        )
        assertFalse(bundle.rawEvidenceSha256 == altered.rawEvidenceSha256)
    }

    @Test
    fun r20_sessionComparisonDetectsResolvedAndNewDtcs() {
        val p0579 = DtcHit("P0579", 0x05, 0x79, DtcScope.GENERIC, "SAE", "7E8", DtcCategory.STORED)
        val p2299 = DtcHit("P2299", 0x22, 0x99, DtcScope.GENERIC, "SAE", "7E8", DtcCategory.STORED)
        val p0401 = DtcHit("P0401", 0x04, 0x01, DtcScope.GENERIC, "SAE", "7E8", DtcCategory.STORED)

        val beforeInput = baseInput(sessionId = "PRE-REPAIR", vin = "SYNTHETICP0000001").copy(
            scanAtMs = 10_000L,
            dtcs = listOf(
                DtcReadResult(DtcCategory.STORED, ResponseKind.OBD_DATA, listOf(p0579, p2299), false, "7E806430205792299", ""),
            ),
            // PID 31 = Distancia desde borrado de códigos: 00 78 = 120 km
            signals = PidCatalog.decode(0x31, listOf(0x00, 0x78)),
            rawByPid = mapOf(0x31 to "00 78"),
            readiness = Readiness(
                milOn = true,
                dtcCount = 2,
                compressionIgnition = true,
                monitors = listOf(
                    MonitorStatus("EGR / VVT", MonitorState.INCOMPLETE),
                    MonitorStatus("Combustible", MonitorState.COMPLETE),
                ),
                thisCycle = false,
            ),
        )
        val afterInput = baseInput(sessionId = "POST-REPAIR", vin = "SYNTHETICP0000001").copy(
            scanAtMs = 20_000L,
            dtcs = listOf(
                DtcReadResult(DtcCategory.STORED, ResponseKind.OBD_DATA, listOf(p2299, p0401), false, "7E806430222990401", ""),
            ),
            // PID 31 = Distancia desde borrado de códigos: 01 5E = 350 km
            signals = PidCatalog.decode(0x31, listOf(0x01, 0x5E)),
            rawByPid = mapOf(0x31 to "01 5E"),
            readiness = Readiness(
                milOn = false,
                dtcCount = 2,
                compressionIgnition = true,
                monitors = listOf(
                    MonitorStatus("EGR / VVT", MonitorState.COMPLETE),
                    MonitorStatus("Combustible", MonitorState.COMPLETE),
                ),
                thisCycle = false,
            ),
        )

        val beforeEnv = EvidenceEngine.buildEnvelope(beforeInput)
        val afterEnv = EvidenceEngine.buildEnvelope(afterInput)
        val diff = EvidenceEngine.compareSessions(beforeEnv, afterEnv)

        assertTrue(diff.sameAsset)
        assertEquals("SYNTHETICP0000001", diff.assetId)
        assertEquals(listOf("P0579"), diff.dtcs.resolved)
        assertEquals(listOf("P2299"), diff.dtcs.persistent)
        assertEquals(listOf("P0401"), diff.dtcs.newCodes)

        val distDelta = diff.metricDeltas.first { it.key == "31" }
        assertEquals(120.0, distDelta.beforeValue!!, 0.01)
        assertEquals(350.0, distDelta.afterValue!!, 0.01)
        assertEquals(230.0, distDelta.delta!!, 0.01)

        val egrMonitor = diff.monitorChanges.first { it.monitorName == "EGR / VVT" }
        assertEquals("INCOMPLETE", egrMonitor.beforeState)
        assertEquals("COMPLETE", egrMonitor.afterState)
        assertTrue(egrMonitor.changed)

        // Activos distintos por VIN: se señala explícitamente sameAsset = false.
        val otherAssetEnv = EvidenceEngine.buildEnvelope(afterInput.copy(vin = "SYNTHETICP9999999"))
        val mismatch = EvidenceEngine.compareSessions(beforeEnv, otherAssetEnv)
        assertFalse(mismatch.sameAsset)
        assertTrue(mismatch.summary.contains("ADVERTENCIA"))
    }

    @Test
    fun r21_readOnlyPolicyBlocksWriteCommands() {
        val forbiddenWriteCommands = listOf(
            "04",             // J1979 Clear/Reset DTCs
            "0801",           // J1979 Control of on-board system / actuators
            "1003",           // UDS DiagnosticSessionControl
            "1101",           // UDS ECUReset
            "14FFFFFF",       // UDS ClearDiagnosticInformation
            "2701",           // UDS SecurityAccess
            "2803",           // UDS CommunicationControl
            "2EF19001",       // UDS WriteDataByIdentifier
            "2F123403",       // UDS InputOutputControlByIdentifier
            "3101FF00",       // UDS RoutineControl
            "3400440000",     // UDS RequestDownload (flashing)
            "3601AA",         // UDS TransferData
            "37",             // UDS RequestTransferExit
            "3D14000001",     // UDS WriteMemoryByAddress
            "8502",           // UDS ControlDTCSetting
            "ATSH7E0",        // Custom CAN header override
            "ATMA",           // Bus monitor / raw sniff
            "0100;04",        // Compound injection
            "03\r04",         // Line-break injection
        )

        var transportTouches = 0
        val spyTransport = object : ObdTransport {
            override val name = "spy"
            override val kind = TransportKind.ResearchLab
            override val connected = true
            override suspend fun connect() = Unit
            override suspend fun disconnect() = Unit
            override suspend fun transact(command: String, timeoutMs: Long): TransportResult {
                transportTouches++
                return TransportResult("OK>", promptSeen = true, timedOut = false, disconnected = false)
            }
        }
        val elmSession = ElmSession(spyTransport)

        forbiddenWriteCommands.forEach { cmd ->
            assertEquals("ObdCommandPolicy debe bloquear $cmd", ObdCommandPolicy.Decision.DENY, EvidenceEngine.evaluateCommand(cmd).decision)
            assertThrows("ReadOnlyGate debe lanzar ReadOnlyViolation para $cmd", ReadOnlyViolation::class.java) {
                EvidenceEngine.assertReadOnly(cmd)
            }
            assertThrows("ReadOnlyGate directo debe lanzar para $cmd", ReadOnlyViolation::class.java) {
                ReadOnlyGate.check(cmd)
            }
            val exResult = runBlocking { elmSession.exchange(cmd, 1_000L) }
            assertEquals("ElmSession debe devolver BLOCKED para $cmd", ResponseKind.BLOCKED, exResult.kind)
        }
        assertEquals("Ningún comando de escritura debe tocar el transporte", 0, transportTouches)

        // Confirmar que las lecturas J1979 válidas siguen permitidas.
        listOf("0100", "010C", "020200", "03", "07", "0A", "0600", "0904").forEach { readCmd ->
            assertEquals("Lectura válida debe permitirse: $readCmd", ObdCommandPolicy.Decision.ALLOW, EvidenceEngine.evaluateCommand(readCmd).decision)
        }
    }

    @Test
    fun r22_shiftAndLoadGuidanceUsesObservedRpmSpeedAndLoadSafely() {
        // 1. Inferencia de marcha por relación RPM / Velocidad (solo si velocidad > 5 km/h), etiquetada como inferencia.
        val stopped = DrivingGuidanceEngine.evaluate(rpm = 800.0, speedKmh = 0.0, loadPct = 22.0, coolantC = 85.0)
        assertNull(stopped.gearInference.gear)
        assertTrue(stopped.gearInference.isInference)
        assertEquals(ShiftCue.IDLE_OR_STOPPED, stopped.cue)

        val fourthGear = DrivingGuidanceEngine.inferGear(rpm = 1800.0, speedKmh = 60.0) // ratio 30.0 -> 4ª
        assertEquals(4, fourthGear.gear)
        assertEquals("4ª", fourthGear.label)
        assertTrue(fourthGear.isInference)
        assertTrue(fourthGear.originNote.contains("Inferencia", ignoreCase = true))

        // Invariante R6: prohibido declarar la marcha estimada como sensor directo (isInference = false).
        assertThrows(IllegalArgumentException::class.java) {
            GearInference(gear = 4, label = "4ª", ratioRpmPerKmh = 30.0, isInference = false)
        }

        // 2. Sobreesfuerzo / lugging: bajas RPM + alta carga -> sugerir bajar marcha (↓).
        val lugging = DrivingGuidanceEngine.evaluate(rpm = 1200.0, speedKmh = 55.0, loadPct = 88.0, coolantC = 86.0)
        assertEquals(ShiftCue.SHIFT_DOWN, lugging.cue)
        assertEquals("↓", lugging.cue.arrow)
        assertTrue(lugging.isLugging)
        assertEquals(TripZone.LUGGING, lugging.zone)
        assertTrue(lugging.isGuidanceInference)
        val asR1Inference = lugging.toDiagnosticInference()
        assertFalse(asR1Inference.isConfirmedRootCause)
        assertTrue(asR1Inference.supportingFactIds.containsAll(listOf("PID:0C", "PID:0D", "PID:04", "PID:05")))

        // 3. Subir marcha (↑): RPM altas con carga baja/media.
        val shiftUp = DrivingGuidanceEngine.evaluate(rpm = 2900.0, speedKmh = 65.0, loadPct = 38.0, coolantC = 88.0)
        assertEquals(ShiftCue.SHIFT_UP, shiftUp.cue)
        assertEquals("↑", shiftUp.cue.arrow)
        assertEquals(TripZone.SHIFT_UP, shiftUp.zone)

        // 4. Motor frío (< 70 °C): aviso suave para evitar exigir el motor.
        val cold = DrivingGuidanceEngine.evaluate(rpm = 1850.0, speedKmh = 50.0, loadPct = 42.0, coolantC = 54.0)
        assertEquals(ShiftCue.COLD_ENGINE, cold.cue)
        assertTrue(cold.isColdEngine)
        assertEquals(TripZone.COLD_ENGINE, cold.zone)

        // 5. Zona óptima: RPM y carga moderadas con motor a temperatura de trabajo.
        val optimal = DrivingGuidanceEngine.evaluate(rpm = 1900.0, speedKmh = 62.0, loadPct = 45.0, coolantC = 88.0)
        assertEquals(ShiftCue.OPTIMAL, optimal.cue)
        assertEquals(TripZone.OPTIMAL, optimal.zone)
        assertFalse(optimal.isLugging)
        assertFalse(optimal.isColdEngine)

        // 6. Resumen post-viaje: 18% sobreesfuerzo, 12% frío, 70% zona óptima (sobre 100 muestras en movimiento + 10 detenido).
        val frames = buildList {
            var t = 1_000L
            repeat(10) { add(TraceFrame(t, mapOf("0C" to 800.0, "0D" to 0.0, "04" to 20.0, "05" to 85.0))); t += 500 }
            repeat(18) { add(TraceFrame(t, mapOf("0C" to 1200.0, "0D" to 55.0, "04" to 88.0, "05" to 85.0))); t += 500 }
            repeat(12) { add(TraceFrame(t, mapOf("0C" to 1800.0, "0D" to 50.0, "04" to 40.0, "05" to 55.0))); t += 500 }
            repeat(70) { add(TraceFrame(t, mapOf("0C" to 1900.0, "0D" to 62.0, "04" to 45.0, "05" to 88.0))); t += 500 }
        }
        val summary = DrivingGuidanceEngine.summarizeTrip(frames)
        assertEquals(110, summary.totalSamples)
        assertEquals(100, summary.movingSamples)
        assertEquals(18.0, summary.luggingPercent, 0.01)
        assertEquals(12.0, summary.coldEnginePercent, 0.01)
        assertEquals(70.0, summary.optimalPercent, 0.01)
        assertTrue(summary.summaryLines.contains("18% del viaje en sobreesfuerzo"))
        assertTrue(summary.summaryLines.contains("12% con motor frío"))
        assertTrue(summary.summaryLines.contains("70% en zona óptima"))

        // 7. Estados explícitos de enlace resiliente para el Modo Tablero.
        assertEquals(listOf("CONECTADO", "RECONECTANDO", "PAUSADO"), DashboardLinkStatus.entries.map { it.label })
    }

    @Test
    fun r23_fuelEstimationUsesPid2FOrFallsBackToFuelRateOrMafAndAlwaysLabelsAsInference() {
        // 1. Fuente primaria: PID 0x2F (Fuel Tank Level Input = 65%) sobre estanque nominal de 76 L -> ~49.4 L.
        val from2f = FuelEstimator.estimate(
            supportedPids = setOf(0x0C, 0x0D, 0x2F, 0x5E, 0x10),
            pid2fLevelPercent = 65.0,
            tankCapacityLiters = 76.0,
        )
        assertEquals(FuelEstimateSource.PID_2F_TANK_LEVEL, from2f.source)
        assertEquals(49.4, from2f.estimatedRemainingLiters!!, 0.05)
        assertEquals(65.0, from2f.estimatedLevelPercent!!, 0.01)
        assertTrue(from2f.isInference)
        assertFalse(from2f.isDirectMeasurement)
        assertEquals(FuelEstimate.LABEL_ESTIMATION_INFERENCE, from2f.classificationLabel)
        assertTrue(from2f.summaryText().contains("ESTIMACIÓN/INFERENCIA"))
        val inf2f = from2f.toDiagnosticInference()
        assertFalse(inf2f.isConfirmedRootCause)
        assertTrue(inf2f.supportingFactIds.contains("PID:2F"))

        // 2. Respaldo 1: ECU no soporta PID 0x2F -> integrar PID 0x5E (6.0 L/h durante 30 min = 3.0 L consumidos) desde «Estanque lleno».
        val anchor = FullTankAnchor(atEpochMs = 1_000L, tankCapacityLiters = 76.0)
        val rateFrames = listOf(
            TraceFrame(atMs = 1_000L, values = mapOf("5E" to 6.0)),
            TraceFrame(atMs = 901_000L, values = mapOf("5E" to 6.0)),
            TraceFrame(atMs = 1_801_000L, values = mapOf("5E" to 6.0)),
        )
        val from5e = FuelEstimator.estimate(
            supportedPids = setOf(0x0C, 0x0D, 0x5E, 0x10), // sin 0x2F
            frames = rateFrames,
            fullTankAnchor = anchor,
            fuelProfile = FuelProfile.DIESEL,
        )
        assertEquals(FuelEstimateSource.PID_5E_FUEL_RATE_INTEGRATION, from5e.source)
        assertEquals(3.0, from5e.estimatedConsumedLiters!!, 0.01)
        assertEquals(73.0, from5e.estimatedRemainingLiters!!, 0.01)
        assertTrue(from5e.isInference)
        assertFalse(from5e.isDirectMeasurement)
        assertEquals("ESTIMACIÓN/INFERENCIA", from5e.classificationLabel)
        assertTrue(from5e.toDiagnosticInference().supportingFactIds.containsAll(listOf("PID:5E", "MANUAL:FULL_TANK")))

        // 3. Respaldo 2: ECU sin 0x2F ni 0x5E -> integrar PID 0x10 (MAF 25 g/s durante 1 h en diésel) desde «Estanque lleno».
        val mafFrames = listOf(
            TraceFrame(atMs = 1_000L, values = mapOf("10" to 25.0)),
            TraceFrame(atMs = 1_801_000L, values = mapOf("10" to 25.0)),
            TraceFrame(atMs = 3_601_000L, values = mapOf("10" to 25.0)),
        )
        val expectedMafRateLph = (25.0 / FuelProfile.DIESEL.effectiveAfr) * 3600.0 / FuelProfile.DIESEL.densityGramsPerLiter
        val fromMaf = FuelEstimator.estimate(
            supportedPids = setOf(0x0C, 0x0D, 0x10), // solo MAF
            frames = mafFrames,
            fullTankAnchor = anchor,
            fuelProfile = FuelProfile.DIESEL,
        )
        assertEquals(FuelEstimateSource.PID_10_MAF_INTEGRATION, fromMaf.source)
        assertEquals(expectedMafRateLph, fromMaf.estimatedConsumedLiters!!, 0.02)
        assertEquals(76.0 - expectedMafRateLph, fromMaf.estimatedRemainingLiters!!, 0.02)
        assertTrue(fromMaf.isInference)
        assertFalse(fromMaf.isDirectMeasurement)
        assertEquals("ESTIMACIÓN/INFERENCIA", fromMaf.classificationLabel)
        assertTrue(fromMaf.toDiagnosticInference().supportingFactIds.containsAll(listOf("PID:10", "MANUAL:FULL_TANK")))

        // 4. Sin PID 0x2F y sin evento manual «Estanque lleno»: no inventa litros.
        val unavailable = FuelEstimator.estimate(
            supportedPids = setOf(0x0C, 0x5E, 0x10),
            frames = rateFrames,
            fullTankAnchor = null,
        )
        assertEquals(FuelEstimateSource.UNAVAILABLE, unavailable.source)
        assertNull(unavailable.estimatedRemainingLiters)
        assertTrue(unavailable.isInference)
        assertFalse(unavailable.isDirectMeasurement)

        // 5. Invariante estricto R7: prohibido etiquetar el resultado como medición directa o no-inferencia.
        assertThrows(IllegalArgumentException::class.java) {
            FuelEstimate(
                source = FuelEstimateSource.PID_2F_TANK_LEVEL,
                tankCapacityLiters = 76.0,
                estimatedRemainingLiters = 50.0,
                estimatedLevelPercent = 65.8,
                estimatedConsumedLiters = 26.0,
                instantaneousRateLph = null,
                fullTankAnchor = null,
                isInference = false,
                isDirectMeasurement = true,
                note = "Medición directa inválida",
            )
        }
    }

    @Test
    fun r24_foregroundServiceContractSupportsBackgroundObdCaptureAndNotification() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue("Debe declarar permiso FOREGROUND_SERVICE", manifest.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(
            "Debe declarar permiso FOREGROUND_SERVICE_CONNECTED_DEVICE",
            manifest.contains("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"),
        )
        assertTrue("Debe declarar DiagnosticConnectionService", manifest.contains(".bt.DiagnosticConnectionService"))
        assertTrue(
            "Debe declarar foregroundServiceType connectedDevice",
            manifest.contains("android:foregroundServiceType=\"connectedDevice\""),
        )

        val liveSpec = BackgroundCaptureNotification.build(
            linkPhase = LinkPhase.DIAGNOSTIC_READY,
            activeTest = "LIVE",
            polling = false,
            sampleCount = 42,
            liveStatus = DashboardLinkStatus.CONNECTED,
            fuelHint = "Comb. est. ~52.0 L (inf.)",
        )
        assertTrue(liveSpec.ongoing)
        assertTrue(liveSpec.title.contains("2º plano", ignoreCase = true))
        assertTrue(liveSpec.text.contains("42 rondas"))
        assertTrue(liveSpec.text.contains("52.0 L"))

        val reconnectingSpec = BackgroundCaptureNotification.build(
            linkPhase = LinkPhase.ECU_RESPONDING,
            activeTest = "ROAD",
            polling = false,
            sampleCount = 120,
            liveStatus = DashboardLinkStatus.RECONNECTING,
        )
        assertTrue(reconnectingSpec.ongoing)
        assertTrue(reconnectingSpec.title.contains("Reconectando", ignoreCase = true))
        assertTrue(reconnectingSpec.text.contains("conservadas", ignoreCase = true))

        assertThrows(IllegalArgumentException::class.java) {
            CaptureNotificationSpec(title = "Test", text = "No persistente", ongoing = false)
        }
    }

    @Test
    fun r25_androidAutoCarAppServiceAndManifestDeclareGlanceableReadOnlyDashboard() {
        val gradle = java.io.File("build.gradle.kts").readText()
        assertTrue("Debe incluir androidx.car.app:app", gradle.contains("androidx.car.app:app:"))
        assertTrue("Debe incluir androidx.car.app:app-projected para Android Auto", gradle.contains("androidx.car.app:app-projected:"))

        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("com.google.android.gms.car.application"))
        assertTrue(manifest.contains("@xml/automotive_app_desc"))
        assertTrue("Debe declarar la clave exacta androidx.car.app.minCarApiLevel", manifest.contains("androidx.car.app.minCarApiLevel"))
        assertFalse("No debe usar la clave errónea minCarAppApiLevel", manifest.contains("androidx.car.app.minCarAppApiLevel"))
        assertTrue(manifest.contains(".car.JacCarAppService"))
        assertTrue(manifest.contains("androidx.car.app.CarAppService"))
        assertTrue(manifest.contains("androidx.car.app.category.POI"))
        assertTrue("Debe usar icono mipmap compatible con Android Auto", manifest.contains("@mipmap/ic_launcher"))

        val carDesc = java.io.File("src/main/res/xml/automotive_app_desc.xml").readText()
        assertTrue(carDesc.contains("<automotiveApp>"))
        assertTrue(carDesc.contains("name=\"template\""))

        // Verificar que la pantalla Android Auto expone estado de enlace, DTCs activos, lecturas clave y combustible R7 en solo lectura.
        val dtcHit = DtcHit("P0579", 0x05, 0x79, DtcScope.GENERIC, "SAE", "7E8", DtcCategory.STORED)
        val fuelEst = FuelEstimator.estimate(
            supportedPids = setOf(0x0C, 0x0D, 0x2F),
            pid2fLevelPercent = 50.0,
            tankCapacityLiters = 76.0,
        )
        val snap = CarDashboardModel.from(
            LabState(
                ecuTalking = true,
                linkPhase = LinkPhase.DIAGNOSTIC_READY,
                activeTest = "LIVE",
                testLive = TraceFrame(1_000L, mapOf("0C" to 1950.0, "0D" to 64.0, "05" to 88.0, "42" to 14.2, "2F" to 50.0)),
                dtcs = listOf(DtcReadResult(DtcCategory.STORED, ResponseKind.OBD_DATA, listOf(dtcHit), false, "43 05 79", "")),
                fuelEstimate = fuelEst,
            ),
        )
        assertTrue(snap.connected)
        assertTrue(snap.headerTitle.contains("En vivo"))
        assertEquals(6, snap.tiles.size)
        assertEquals("1", snap.tiles[5].valueText)
        assertEquals(CarTone.ALERT, snap.tiles[5].tone)
        assertTrue(snap.statusRows.any { it.title.contains("P0579") })
        assertTrue(
            "Android Auto debe etiquetar el combustible como ESTIMACIÓN/INFERENCIA",
            snap.detailRows.any { it.line2?.contains("38.0 L") == true && it.line2?.contains("ESTIMACIÓN/INFERENCIA") == true },
        )
        val carServiceSrc = java.io.File("src/main/java/app/jac/diagnosticlab/car/JacCarAppService.kt").readText()
        assertFalse("CarAppService no debe exponer borrado de DTC", carServiceSrc.contains("cleanAndClear"))
    }
}

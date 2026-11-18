package app.jac.diagnosticlab

import app.jac.diagnosticlab.elm.Exchange
import app.jac.diagnosticlab.elm.ObdFrame
import app.jac.diagnosticlab.elm.ResponseKind
import app.jac.diagnosticlab.evidence.Json
import app.jac.diagnosticlab.profile.EcuIdentification
import app.jac.diagnosticlab.security.ObdCommandPolicy
import app.jac.diagnosticlab.obd.ObdCapabilityDiscoveryEngine
import app.jac.diagnosticlab.profile.CoverageStatus
import app.jac.diagnosticlab.profile.FieldOrigin
import app.jac.diagnosticlab.profile.JacT8Catalog
import app.jac.diagnosticlab.profile.ProfileResolver
import app.jac.diagnosticlab.profile.ProfileSignal
import app.jac.diagnosticlab.profile.ResolutionStatus
import app.jac.diagnosticlab.profile.SignalEncoding
import app.jac.diagnosticlab.profile.VehicleFingerprint
import app.jac.diagnosticlab.profile.YearCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SPEC-031. Todos los VIN, CALID y etiquetas de este archivo son SINTÉTICOS (fixtures de prueba),
 * no capturas de la unidad. Los identificadores de ECU son los que citan las fuentes S11/S18/S21.
 */
class VehicleProfileTest {

    private fun fp(
        year: String? = "2023",
        vin: String? = null,
        calid: List<String> = emptyList(),
        ecuName: String? = null,
        label: String? = null,
    ) = VehicleFingerprint.from("JAC T8", year, vin, calid.map { "7E8" to it }, ecuName, label, "6", listOf("7E8"))

    @Test
    fun r5_vinYearMatchesOrContradictsTheDeclaredYear() {
        // Sintético: 9 caracteres de relleno + año en posición 10 + 7 de serie.
        val vin2023 = "SYNTHETIC" + "P" + "0000001"
        val vin2022 = "SYNTHETIC" + "N" + "0000001"
        assertEquals(17, vin2023.length)

        val match = ProfileResolver.resolve(fp(vin = vin2023))
        assertEquals(YearCheck.MATCH, match.yearCheck)
        assertEquals(2023, fp(vin = vin2023).vinYear?.value)
        assertEquals(FieldOrigin.VIN_ISO3779, fp(vin = vin2023).vinYear?.origin)

        val mismatch = fp(vin = vin2022)
        assertEquals(YearCheck.MISMATCH, ProfileResolver.resolve(mismatch).yearCheck)
        assertEquals("el año declarado no se corrige", 2023, mismatch.declaredYear?.value)

        assertEquals(YearCheck.DECLARED_ONLY, ProfileResolver.resolve(fp()).yearCheck)
        assertEquals(null, fp(vin = "CORTO").vinYear)
    }

    @Test
    fun r6_ecuProfileResolvesOnlyFromADocumentedIdentifier() {
        val none = ProfileResolver.resolve(fp())
        assertEquals(ResolutionStatus.PARTIAL, none.status)
        assertFalse(none.summary.contains("Bosch") || none.summary.contains("Delphi"))

        val delphi = ProfileResolver.resolve(fp(label = "DELPHI DCM6.2AP 28xxxx"))
        assertEquals(ResolutionStatus.IDENTIFIED, delphi.status)
        assertEquals(setOf("Delphi"), delphi.suppliers)
        assertEquals(FieldOrigin.ECU_LABEL_TYPED, delphi.matches.first().origin)

        val fromCalid = ProfileResolver.resolve(fp(calid = listOf("1MJACLSA_A010D11")))
        assertEquals(ResolutionStatus.IDENTIFIED, fromCalid.status)
        assertEquals(FieldOrigin.ECU_MODE09, fromCalid.matches.first().origin)

        assertEquals("7E8", fromCalid.matches.first().ecu)

        val both = ProfileResolver.resolve(fp(calid = listOf("EDC17C81 SYN"), label = "DCM6.2AP"))
        assertEquals(ResolutionStatus.AMBIGUOUS, both.status)

        // No mezclar modelos: el identificador más largo gana dentro del mismo texto.
        val sixD = ProfileResolver.resolve(fp(label = "DELPHI DCM6.2AP-6D"))
        assertEquals(ResolutionStatus.IDENTIFIED, sixD.status)
        assertEquals(setOf("DELPHI_DCM62AP_6D"), sixD.matchedCandidateIds)

        val md1 = ProfileResolver.resolve(fp(label = "BOSCH MD1CS089"))
        assertEquals(setOf("BOSCH_MD1CS089"), md1.matchedCandidateIds)
        assertEquals(setOf("BOSCH_EDC17C81"), ProfileResolver.resolve(fp(label = "EDC17C81")).matchedCandidateIds)

        val oem = ProfileResolver.resolve(fp(label = "1026400FD020B-007"))
        assertEquals("MDC Chile asocia este código a DCM6.2AP", setOf("DELPHI_DCM62AP"), oem.matchedCandidateIds)

        val supplierOnly = ProfileResolver.resolve(fp(calid = listOf("1026400FD020"), label = "DCM6.2AP"))
        assertEquals(ResolutionStatus.SUPPLIER_ONLY, supplierOnly.status)
        assertEquals(setOf("Delphi"), supplierOnly.suppliers)

        assertFalse(ProfileResolver.containsIdentifier("1026400FD020B054", "1026400FD020"))

        assertTrue(ProfileResolver.containsIdentifier("bosch edc17 c81", "EDC17C81"))
        assertTrue(ProfileResolver.containsIdentifier("dcm 6.2ap", "DCM6.2AP"))
        assertFalse(ProfileResolver.containsIdentifier("EDC17C810", "EDC17C81"))
        assertFalse(ProfileResolver.containsIdentifier("XEDC17C81", "EDC17C81"))
        assertEquals(ResolutionStatus.PARTIAL, ProfileResolver.resolve(fp(calid = listOf("EDC17C810"))).status)
    }

    @Test
    fun r7_oemSignalNeverActivatesWithoutSourceEncodingAndMatchingEcu() {
        val identified = ProfileResolver.resolve(fp(label = "DCM6.2AP"))
        val active = ProfileResolver.activeSignals(identified)
        assertTrue(active.isNotEmpty())
        assertTrue("v1 solo activa J1979", active.all { it.encoding == SignalEncoding.J1979_MODE01 && it.ecuScope == null })
        assertTrue(JacT8Catalog.v1.signals.any { it.encoding == SignalEncoding.UNKNOWN_ENCODING })

        // Señal ficticia de prueba: codificación y fuente válidas, pero ECU Bosch.
        val boschOnly = ProfileSignal(
            "TEST-BOSCH", "test", "u", 0x01, 0x0C, "BOSCH_EDC17C81", SignalEncoding.J1979_MODE01,
            "A", true, listOf("S18"), "fixture",
        )
        assertTrue(ProfileResolver.activeSignals(identified, signals = listOf(boschOnly)).isEmpty())
        val bosch = ProfileResolver.resolve(fp(label = "EDC17C81"))
        assertEquals(1, ProfileResolver.activeSignals(bosch, signals = listOf(boschOnly)).size)
        // Fuente inexistente → no se activa aunque la ECU coincida.
        val unsourced = boschOnly.copy(sourceIds = listOf("S-NO-EXISTE"))
        assertTrue(ProfileResolver.activeSignals(bosch, signals = listOf(unsourced)).isEmpty())
        // AMBIGUOUS nunca activa señales con alcance de ECU.
        val ambiguous = ProfileResolver.resolve(fp(calid = listOf("EDC17C81"), label = "DCM6.2AP"))
        assertTrue(ProfileResolver.activeSignals(ambiguous, signals = listOf(boschOnly)).isEmpty())
    }

    @Test
    fun r8_coverageComparesProfileWithWhatTheEcuReported() {
        val cov = ProfileResolver.coverage(bitmapRead = true, supported = setOf(0x0C, 0x05), validPids = setOf(0x0C))
        fun status(key: String) = cov.first { it.signal.key == key }.status
        assertEquals(CoverageStatus.SUPPORTED_VERIFIED, status("01-0C"))
        assertEquals(CoverageStatus.SUPPORTED_UNVALIDATED, status("01-05"))
        assertEquals(CoverageStatus.UNSUPPORTED, status("01-10"))
        assertEquals(CoverageStatus.UNKNOWN, status("OEM-DPF-SOOT"))

        val unread = ProfileResolver.coverage(bitmapRead = false, supported = emptySet(), validPids = emptySet())
        assertTrue(unread.all { it.status == CoverageStatus.UNKNOWN })
    }

    @Test
    fun r9_versionedCatalogFileMatchesTheAppCatalog() {
        val expected = Json.encode(JacT8Catalog.toMap()) + "\n"
        val file = File("../jac-t8-profiles/catalog-v1.json")
        if (System.getenv("JAC_WRITE_CATALOG") == "1") {
            file.parentFile.mkdirs()
            file.writeText(expected)
        }
        assertTrue("Falta ${file.absolutePath}; regenerar con JAC_WRITE_CATALOG=1", file.exists())
        assertEquals("catalog-v1.json desalineado con VehicleProfiles.kt", expected, file.readText().replace("\r\n", "\n"))
    }

    @Test
    fun r11_mode09IdentificationIsReadOnlyPerEcuAndKeepsVinOut() {
        assertEquals(listOf("0900", "0902", "0904", "090A"), EcuIdentification.plan(setOf(0x02, 0x04, 0x0A)))
        assertEquals(listOf("0900", "0904"), EcuIdentification.plan(setOf(0x04)))
        assertEquals(listOf("0900"), EcuIdentification.plan(null))
        val all = EcuIdentification.plan(setOf(0x02, 0x04, 0x06, 0x0A))
        all.forEach { ObdCommandPolicy.assertAllowed(it) }
        assertTrue(all.none { it.startsWith("04") || it.startsWith("14") || it.startsWith("2E") || it.startsWith("31") || it.startsWith("34") })

        fun ascii(t: String) = t.padEnd(16, ' ').map { it.code }
        // Sintético: dos ECUs responden 0904 con un CALID cada una.
        val calidEx = Exchange(
            atEpochMs = 1_700_000_000_000, command = "0904", raw = "7E8 … / 7E9 …", kind = ResponseKind.OBD_DATA,
            detail = "", promptSeen = true, elapsedMs = 120,
            frames = listOf(
                ObdFrame("7E8", listOf(0x49, 0x04, 0x01) + ascii("1MJACLSA_A010D11"), true, "7E8 …"),
                ObdFrame("7E9", listOf(0x49, 0x04, 0x01) + ascii("SYNTCM00000001"), true, "7E9 …"),
            ),
        )
        val rec = EcuIdentification.record(calidEx)
        assertEquals(2, rec.perEcu.size)
        assertEquals(listOf("1MJACLSA_A010D11"), rec.perEcu.first { it.ecu == "7E8" }.values)
        assertEquals(listOf("SYNTCM00000001"), rec.perEcu.first { it.ecu == "7E9" }.values)
        assertEquals(120L, rec.elapsedMs)
        assertEquals(1_700_000_000_000, rec.atEpochMs)

        val pairs = EcuIdentification.calibrationIds(listOf(rec))
        val resolved = ProfileResolver.resolve(VehicleFingerprint.from("JAC T8", "2023", null, pairs, null, null, "6", listOf("7E8", "7E9")))
        assertEquals(setOf("DELPHI_DCM62_PN"), resolved.matchedCandidateIds)
        assertEquals("7E8", resolved.matches.single().ecu)

        val vinText = "SYNTHETIC" + "P" + "0000001"
        val vinEx = Exchange(
            atEpochMs = 1, command = "0902", raw = "49 02 01 $vinText", kind = ResponseKind.OBD_DATA,
            detail = "", promptSeen = true, elapsedMs = 90,
            frames = listOf(ObdFrame("7E8", listOf(0x49, 0x02, 0x01) + vinText.map { it.code }, true, "7E8 …")),
        )
        val vinRec = EcuIdentification.record(vinEx)
        assertFalse(vinRec.raw.contains(vinText))
        assertFalse(vinRec.perEcu.flatMap { it.values }.any { it.contains(vinText) })
        assertFalse(Json.encode(EcuIdentification.toMap(vinRec)).contains(vinText))
    }

    @Test
    fun r10_supportPagesFollowTheNextPageBitOnly() {
        // 0100 → BE 1F A8 13: bit 0x20 = 1 (último bit del 4.º byte) → se pide 0120.
        // 0120 → 00 00 00 00: bit 0x40 = 0 → se detiene.
        val asked = mutableListOf<Int>()
        val walk = ObdCapabilityDiscoveryEngine.walkSupportPages { page ->
            asked += page
            when (page) {
                0x00 -> listOf(0xBE, 0x1F, 0xA8, 0x13)
                0x20 -> listOf(0x00, 0x00, 0x00, 0x00)
                else -> error("no debía pedir ${"%02X".format(page)}")
            }
        }
        assertEquals(listOf(0x00, 0x20), asked)
        assertTrue(walk.bitmapRead)
        assertTrue(0x0C in walk.supported)
        assertFalse(0x40 in walk.supported)

        val silent = ObdCapabilityDiscoveryEngine.walkSupportPages { null }
        assertFalse(silent.bitmapRead)
        assertTrue(silent.supported.isEmpty())

        // 0x00 responde con bit 0x20 = 1, pero 0120 no responde: queda lo leído, sin pedir 0140.
        val partialAsked = mutableListOf<Int>()
        val partial = ObdCapabilityDiscoveryEngine.walkSupportPages { page ->
            partialAsked += page
            if (page == 0x00) listOf(0xBE, 0x1F, 0xA8, 0x13) else null
        }
        assertEquals(listOf(0x00, 0x20), partialAsked)
        assertTrue(partial.bitmapRead)
        assertEquals(listOf(0x00), partial.pagesRead)
    }
}

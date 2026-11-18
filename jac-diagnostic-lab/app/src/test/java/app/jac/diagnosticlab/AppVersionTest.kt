package app.jac.diagnosticlab

import app.jac.diagnosticlab.evidence.SessionRecord
import app.jac.diagnosticlab.evidence.toJson
import app.jac.diagnosticlab.evidence.toTxt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** SPEC-032 R12: la versión instalada se informa en pantalla y en cada archivo exportado. */
class AppVersionTest {

    private fun record(version: String) = SessionRecord(
        id = "t", startedAtEpochMs = 1, androidDevice = "x", androidRelease = "17", connectionType = "clásico",
        adapterName = "OBDII", adapterAddress = "", adapterIdentity = "ELM327", protocolText = "ISO 15765-4",
        protocolNumber = "6", adapterVoltage = "", exchanges = emptyList(), dtcs = emptyList(), freeze = emptyList(),
        samples = emptyList(), errors = emptyList(), connectionState = "conectado", symptoms = emptySet(), vin = null,
        simulated = false, vehicleNote = "JAC T8", analysis = null, appVersion = version,
    )

    @Test
    fun r12_versionIsReportedOnScreenPdfJsonAndTxt() {
        assertEquals("v1.9.0 (22)", AppVersion.label("1.9.0", 22))
        assertEquals("v?", AppVersion.label(null, null))
        assertEquals("v? (5)", AppVersion.label(" ", 5))

        val r = record("v1.9.0 (22)")
        assertTrue(toJson(r).contains("\"appVersion\": \"v1.9.0 (22)\""))
        assertTrue(toTxt(r).lineSequence().first().contains("JAC Diagnostic Lab v1.9.0 (22)"))

        // El pie del PDF ya no lleva una versión escrita a mano (antes decía 1.0.0 en todas las versiones).
        val ui = File("src/main/java/app/jac/diagnosticlab/ui/Ui.kt").readText()
        assertFalse(ui.contains("JAC Diagnostic Lab 1.0.0"))
        assertTrue(ui.contains("JAC Diagnostic Lab \$appVersion"))
    }
}

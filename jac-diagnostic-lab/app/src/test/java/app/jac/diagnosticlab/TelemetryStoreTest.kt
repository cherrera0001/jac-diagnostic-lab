package app.jac.diagnosticlab

import app.jac.diagnosticlab.evidence.Sample
import app.jac.diagnosticlab.telemetry.TelemetryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class TelemetryStoreTest {
    @Test
    fun testStore001_appendAndCount() {
        val dir = createTempDirectory("jac-tel").toFile()
        val store = TelemetryStore(dir)
        val sample = Sample(1_000L, 0x0C, "RPM", 786.75, "rpm", true, false, "0C 4B")
        store.appendMeasurement("sess-a", sample, 42, "FAST")
        store.appendMeasurement("sess-a", sample.copy(atEpochMs = 1_100L, value = 800.0), 40, "FAST")
        assertEquals(2, store.measurementCount("sess-a"))
        val text = File(dir, "telemetry/sess-a.jsonl").readText()
        assertTrue(text.contains("\"pid\":12"))
        assertTrue(text.contains("786.75"))
        assertTrue(!text.contains("\"synthetic\":true"))
    }

    @Test
    fun testStore002_listSessionsByRecency() {
        val dir = createTempDirectory("jac-tel2").toFile()
        val store = TelemetryStore(dir)
        store.appendMeasurement("old", Sample(100L, 0x0D, "V", 0.0, "km/h", true, false, "00"), 10, "FAST")
        Thread.sleep(5)
        store.appendMeasurement("new", Sample(200L, 0x0C, "R", 700.0, "rpm", true, false, "00"), 10, "FAST")
        val list = store.listSessions()
        assertEquals("new", list.first().id)
        assertTrue(list.any { it.id == "old" && it.measurementCount == 1 })
    }
}

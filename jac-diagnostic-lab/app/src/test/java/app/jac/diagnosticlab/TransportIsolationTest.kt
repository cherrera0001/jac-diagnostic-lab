package app.jac.diagnosticlab

import app.jac.diagnosticlab.lab.ResearchLabTransport
import app.jac.diagnosticlab.lab.TransportIsolation
import app.jac.diagnosticlab.elm.TransportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class TransportIsolationTest {
    @Test
    fun researchLabIsNeverProduction() {
        val lab = ResearchLabTransport()
        assertEquals(TransportKind.ResearchLab, lab.kind)
        assertTrue(lab.name.contains("aislado", ignoreCase = true))
        assertThrows(IllegalArgumentException::class.java) {
            TransportIsolation.assertProductionOnly(lab)
        }
    }

    @Test
    fun fakeTransportUsedInUnitTestsIsResearchLab() {
        val fake = FakeObdTransport()
        assertEquals(TransportKind.ResearchLab, fake.kind)
        assertThrows(IllegalArgumentException::class.java) {
            TransportIsolation.assertProductionOnly(fake)
        }
    }

    @Test
    fun researchLabAnswersAtiWithoutBluetooth() = runBlocking {
        val lab = ResearchLabTransport()
        lab.connect()
        val ati = lab.transact("ATI", 1000)
        assertTrue(ati.raw.contains("ELM327"))
        lab.disconnect()
    }
}

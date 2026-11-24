package app.jac.diagnosticlab

import app.jac.diagnosticlab.analysis.HealthItem
import app.jac.diagnosticlab.analysis.HealthStatus
import app.jac.diagnosticlab.bt.LinkPhase
import app.jac.diagnosticlab.car.CarDashboardModel
import app.jac.diagnosticlab.car.CarTone
import app.jac.diagnosticlab.dynamic.GuidedTests
import app.jac.diagnosticlab.dynamic.TraceFrame
import app.jac.diagnosticlab.elm.ResponseKind
import app.jac.diagnosticlab.obd.DtcCategory
import app.jac.diagnosticlab.obd.DtcHit
import app.jac.diagnosticlab.obd.DtcReadResult
import app.jac.diagnosticlab.obd.DtcScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarDashboardModelTest {
    @Test fun disconnectedStateIsHonestAndRespectsTemplateLimits() {
        val snap = CarDashboardModel.from(LabState())
        assertFalse(snap.connected)
        assertEquals("Conectar", snap.primaryActionLabel)
        assertEquals(6, snap.tiles.size)
        assertTrue(snap.detailRows.size <= 4)
        assertTrue(snap.statusRows.size <= 6)
        assertTrue(snap.tiles.all { it.valueText == "—" })
    }

    @Test fun normalLiveDieselStateRendersCalmOkTiles() {
        val emptyDtc = DtcReadResult(DtcCategory.STORED, ResponseKind.OBD_DATA, emptyList(), confirmedEmpty = true, raw = "43 00", note = "")
        val frame = TraceFrame(
            atMs = 1_000L,
            values = mapOf(
                GuidedTests.RPM to 850.0,
                GuidedTests.SPEED to 0.0,
                GuidedTests.LOAD to 24.0,
                GuidedTests.ECT to 88.0,
                GuidedTests.VOLT to 14.1,
                GuidedTests.BOOST_ACT to 108.0,
                GuidedTests.RAIL_ACT to 34_000.0,
                GuidedTests.DPF_DP to 2.4,
                GuidedTests.EGT to 290.0,
                GuidedTests.REGEN to 0.0,
            ),
        )
        val snap = CarDashboardModel.from(
            LabState(
                ecuTalking = true,
                linkPhase = LinkPhase.ECU_RESPONDING,
                activeTest = "LIVE",
                testLive = frame,
                dtcs = listOf(emptyDtc),
            ),
        )
        assertTrue(snap.connected)
        assertTrue(snap.liveRunning)
        assertEquals("Pausar", snap.primaryActionLabel)
        assertEquals("850", snap.tiles[0].valueText)
        assertEquals(CarTone.OK, snap.tiles[1].tone) // 88 °C normal
        assertEquals("14.1", snap.tiles[2].valueText)
        assertEquals(CarTone.OK, snap.tiles[2].tone)
        assertEquals("108", snap.tiles[3].valueText)
        assertTrue(snap.tiles[3].subtitle.contains("34 MPa"))
        assertEquals(CarTone.OK, snap.tiles[4].tone)
        assertEquals("OK", snap.tiles[5].valueText)
        assertEquals(CarTone.OK, snap.tiles[5].tone)
    }

    @Test fun alertsFireForOverheatLowBatteryActiveDpfRegenAndDtcs() {
        val hit = DtcHit("P0579", 0x05, 0x79, DtcScope.GENERIC, "", "7E8", DtcCategory.STORED)
        val dtc = DtcReadResult(DtcCategory.STORED, ResponseKind.OBD_DATA, listOf(hit), confirmedEmpty = false, raw = "43 05 79", note = "")
        val frame = TraceFrame(
            atMs = 2_000L,
            values = mapOf(
                GuidedTests.RPM to 2100.0,
                GuidedTests.ECT to 108.0,
                GuidedTests.VOLT to 11.4,
                GuidedTests.REGEN to 1.0,
                GuidedTests.EGT to 590.0,
            ),
        )
        val snap = CarDashboardModel.from(
            LabState(
                ecuTalking = true,
                linkPhase = LinkPhase.ECU_RESPONDING,
                testLive = frame,
                dtcs = listOf(dtc),
                health = listOf(HealthItem("Freno/Pedal", HealthStatus.CHECK, "P0579", "Revisar circuito", "Mode 03")),
            ),
        )
        assertEquals(CarTone.ALERT, snap.tiles[1].tone) // 108 °C
        assertEquals(CarTone.ALERT, snap.tiles[2].tone) // 11.4 V
        assertEquals("REGEN", snap.tiles[4].valueText)
        assertEquals(CarTone.WARN, snap.tiles[4].tone)
        assertTrue(snap.tiles[4].subtitle.contains("No apagar"))
        assertEquals("1", snap.tiles[5].valueText)
        assertEquals(CarTone.ALERT, snap.tiles[5].tone)
        assertTrue(snap.tiles[5].subtitle.contains("P0579"))
        assertTrue(snap.statusRows.any { it.title.contains("P0579") })
    }

    @Test fun dieselSixSpeedGearAndShiftRecommendationsWorkAccurately() {
        // 6ª marcha real en carretera (100 km/h @ 2050 rpm -> ratio 20.5 -> 6ª, óptimo)
        val sixth = app.jac.diagnosticlab.analysis.DrivingGuidanceEngine.evaluate(2050.0, 100.0, 50.0, 88.0)
        assertEquals(6, sixth.gearInference.gear)
        assertEquals(app.jac.diagnosticlab.analysis.ShiftCue.OPTIMAL, sixth.cue)

        // Embrague pisado / punto muerto rodando (900 rpm @ 40 km/h -> no inventar 6ª)
        val clutch = app.jac.diagnosticlab.analysis.DrivingGuidanceEngine.evaluate(900.0, 40.0, 15.0, 88.0)
        assertEquals(null, clutch.gearInference.gear)
        assertEquals("N", clutch.gearInference.label)

        // Acelerando en 2ª a 2400 rpm (34 km/h, ratio 70.6, carga 72%) -> subir de 2ª a 3ª
        val up = app.jac.diagnosticlab.analysis.DrivingGuidanceEngine.evaluate(2400.0, 34.0, 72.0, 88.0)
        assertEquals(2, up.gearInference.gear)
        assertEquals(app.jac.diagnosticlab.analysis.ShiftCue.SHIFT_UP, up.cue)
        assertEquals(3, up.recommendedGear)
        assertTrue(up.actionLabel.contains("SUBIR A 3ª"))

        // Reflejado en el mosaico de Android Auto
        val frame = TraceFrame(3_000L, mapOf(GuidedTests.RPM to 2400.0, GuidedTests.SPEED to 34.0, GuidedTests.LOAD to 72.0, GuidedTests.ECT to 88.0))
        val carSnap = CarDashboardModel.from(LabState(ecuTalking = true, linkPhase = LinkPhase.ECU_RESPONDING, activeTest = "LIVE", testLive = frame))
        assertEquals("↑ 3ª", carSnap.tiles[0].unitText)
        assertTrue(carSnap.tiles[0].subtitle.contains("Subir a 3ª"))
    }

    @Test fun carGridActionStripKeepsOnlyOneTitledAction() {
        // ACTIONS_CONSTRAINTS_SIMPLE (Car App Library) admite 2 acciones y solo 1 con título;
        // las plantillas no se pueden construir en JVM sin Robolectric, así que se valida el código fuente.
        val src = java.io.File("src/main/java/app/jac/diagnosticlab/car/JacCarAppService.kt").readText()
        val strip = src.substringAfter("val strip = ActionStrip.Builder()").substringBefore(".build()\n\n        return GridTemplate")
        assertEquals("Una sola acción con título en el ActionStrip de la grilla", 1, Regex("setTitle\\(").findAll(strip).count())
        assertTrue("La segunda acción debe ser solo ícono", strip.contains(".setIcon(statusIcon)"))
        assertTrue(src.contains("override fun onCreateSession(sessionInfo: SessionInfo)"))
    }

    @Test fun glanceTilesKeepsSingleRowOfThreeWithShortLabels() {
        val glance = CarDashboardModel.glanceTiles(CarDashboardModel.from(LabState()).tiles)
        assertEquals(listOf("RPM", "ECT", "DTC"), glance.map { it.id })
        glance.forEach { assertTrue("Título corto: ${it.title}", it.title.substringBefore(" /").length <= 14) }
    }

    @Test fun glanceTilesReplacesCoolantWithDpfRegenWarning() {
        val base = CarDashboardModel.from(LabState()).tiles
        val regen = base.map { if (it.id == "DPF") it.copy(valueText = "REGEN") else it }
        val glance = CarDashboardModel.glanceTiles(regen)
        assertEquals(listOf("RPM", "DPF", "DTC"), glance.map { it.id })
        assertEquals("No apagar", glance[1].subtitle)
    }
}

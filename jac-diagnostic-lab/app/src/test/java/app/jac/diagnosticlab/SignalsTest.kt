package app.jac.diagnosticlab

import app.jac.diagnosticlab.signals.CellTower
import app.jac.diagnosticlab.signals.FindingSeverity
import app.jac.diagnosticlab.signals.GnssSummary
import app.jac.diagnosticlab.signals.MagneticReading
import app.jac.diagnosticlab.signals.RadioMath
import app.jac.diagnosticlab.signals.RadioParsers
import app.jac.diagnosticlab.signals.SignalsExport
import app.jac.diagnosticlab.signals.VehicleSecurityAudit
import app.jac.diagnosticlab.signals.WifiNet
import app.jac.diagnosticlab.signals.WifiSecurity
import app.jac.diagnosticlab.tracker.Advert
import app.jac.diagnosticlab.tracker.SightingBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SignalsTest {
    @Test fun wifiChannelAndBandConversions() {
        assertEquals(6, RadioMath.wifiChannel(2437))
        assertEquals("2.4 GHz", RadioMath.wifiBandLabel(2437))
        assertEquals(36, RadioMath.wifiChannel(5180))
        assertEquals("5 GHz", RadioMath.wifiBandLabel(5180))
        assertEquals(1, RadioMath.wifiChannel(5955))
        assertEquals("6 GHz", RadioMath.wifiBandLabel(5955))
    }

    @Test fun lteEarfcnAndNrArfcnToMhz() {
        // Banda 28 (700 APT, usada en Chile): EARFCN 9260 -> 758 + 5 = 763.0 MHz
        val b28 = RadioMath.lteEarfcnInfo(9260)
        assertNotNull(b28)
        assertEquals(763.0, b28!!.first, 0.05)
        assertTrue(b28.second.contains("B28"))

        // Banda 7 (2600): EARFCN 3100 -> 2620 + 35 = 2655.0 MHz
        val b7 = RadioMath.lteEarfcnInfo(3100)
        assertEquals(2655.0, b7!!.first, 0.05)

        // 5G n78 (3500 MHz): NR-ARFCN 633334 -> 3000 + 0.015 * 33334 = 3500.01 MHz
        val n78 = RadioMath.nrArfcnToMhz(633_334)
        assertNotNull(n78)
        assertEquals(3500.0, n78!!, 0.2)
        assertTrue(RadioMath.nrBandHint(n78).contains("n78"))
    }

    @Test fun shannonCapacityMatchesFormula() {
        // 20 MHz con SNR 10 dB (lineal = 10) -> 20 * log2(11) ≈ 69.19 Mbps
        assertEquals(69.19, RadioMath.shannonCapacityMbps(20.0, 10.0), 0.1)
    }

    @Test fun fftFindsDominantOscillationFrequency() {
        // Onda senoidal pura de 10 Hz muestreada a 64 Hz (64 muestras) + offset DC de 45 µT
        val fs = 64.0
        val samples = DoubleArray(64) { i -> 45.0 + 12.0 * sin(2.0 * PI * 10.0 * i / fs) }
        val peak = RadioMath.dominantFrequencyHz(samples, fs)
        assertNotNull(peak)
        assertEquals(10.0, peak!!.first, 0.5)
        assertTrue("Expected amplitude > 8 µT, got ${peak.second}", peak.second > 8.0)
    }

    @Test fun parsesWifiSecurityAndDetectsEvilTwin() {
        assertEquals(WifiSecurity.WPA3, RadioParsers.parseWifiSecurity("[WPA2-PSK-CCMP][SAE-CCMP][ESS]"))
        assertEquals(WifiSecurity.WPA2, RadioParsers.parseWifiSecurity("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"))
        assertEquals(WifiSecurity.OPEN, RadioParsers.parseWifiSecurity("[ESS]"))
        assertEquals(WifiSecurity.WEP, RadioParsers.parseWifiSecurity("[WEP][ESS]"))

        val nets = listOf(
            WifiNet("Casa_WiFi", "00:11", 2437, 6, "2.4 GHz", 20, -55, WifiSecurity.WPA2),
            WifiNet("Casa_WiFi", "00:22", 2437, 6, "2.4 GHz", 20, -48, WifiSecurity.OPEN),
        )
        assertEquals(setOf("Casa_WiFi"), RadioParsers.findEvilTwinSsids(nets))
    }

    @Test fun securityAuditFlagsObd2gEvilTwinTrackerAlertAndMagnet() {
        val bk = SightingBook()
        bk.observe("11:22", Advert(name = "OBDBLE"), -55, 1_000, null)
        val fmdnAlert = ByteArray(22).also { it[0] = 0x41 }
        bk.observe("33:44", Advert(serviceData = mapOf("feaa" to fmdnAlert)), -62, 1_000, null)

        val wifi = listOf(
            WifiNet("MiCasa", "AA:01", 2412, 1, "2.4 GHz", 20, -60, WifiSecurity.WPA2),
            WifiNet("MiCasa", "AA:02", 2412, 1, "2.4 GHz", 20, -50, WifiSecurity.OPEN),
        )
        val cells = listOf(CellTower("2G GSM", true, "730-01", 10, null, "2G GSM", null, -85, null, null))
        val gnss = GnssSummary(visible = 10, used = 8, avgCn0DbHz = 31.0, maxCn0DbHz = 39.0, constellations = mapOf("GPS" to 6, "Galileo" to 4))
        val mag = MagneticReading(magnitudeUt = 185.0, peakHz = 5.0, peakAmpUt = 3.0, sampleRateHz = 50.0, available = true)

        val findings = VehicleSecurityAudit.evaluate(bk.all(), wifi, cells, gnss, mag)
        val ids = findings.map { it.id }.toSet()
        assertTrue("OBD-BLE-EXPOSED" in ids)
        assertTrue("BLE-TRACKER-ALERT" in ids)
        assertTrue("CELL-2G-DOWNGRADE" in ids)
        assertTrue("WIFI-EVIL-TWIN" in ids)
        assertTrue("MAG-ANOMALY" in ids)
        assertEquals(FindingSeverity.ALERT, findings.first().severity)

        val json = SignalsExport.toJson(1_000L, wifi, cells, gnss, mag, findings)
        assertTrue(json.contains("\"WIFI-EVIL-TWIN\""))
        assertTrue(json.contains("\"CELL-2G-DOWNGRADE\""))
    }
}

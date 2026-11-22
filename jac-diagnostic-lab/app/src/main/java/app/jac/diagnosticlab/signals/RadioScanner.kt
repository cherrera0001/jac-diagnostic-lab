package app.jac.diagnosticlab.signals

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellIdentityNr
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Escáner pasivo de radios y sensores nativos del teléfono:
 * - Wi-Fi (2.4 / 5 / 6 GHz: frecuencia, canal, ancho, RSSI, cifrado)
 * - Celular (5G NR / 4G LTE / 3G / 2G: ARFCN -> MHz, RSRP, RSRQ, SINR, PCI)
 * - GNSS (GPS / Galileo / GLONASS / BeiDou: satélites, C/N0, frecuencias portadoras)
 * - Magnetómetro (campo total en µT + FFT en tiempo real de oscilaciones)
 */
class RadioScanner(private val context: Context) : SensorEventListener {
    private val _wifi = MutableStateFlow<List<WifiNet>>(emptyList())
    val wifi: StateFlow<List<WifiNet>> = _wifi

    private val _cells = MutableStateFlow<List<CellTower>>(emptyList())
    val cells: StateFlow<List<CellTower>> = _cells

    private val _gnss = MutableStateFlow(GnssSummary())
    val gnss: StateFlow<GnssSummary> = _gnss

    private val _mag = MutableStateFlow(MagneticReading())
    val magnetic: StateFlow<MagneticReading> = _mag

    val running = MutableStateFlow(false)
    val note = MutableStateFlow<String?>(null)

    private var gnssCallback: GnssStatus.Callback? = null
    private val magRing = DoubleArray(64)
    private var magCount = 0
    private var lastMagTsNs = 0L
    private var avgDtSec = 0.02
    private var lastFftMs = 0L

    @SuppressLint("MissingPermission")
    fun start() {
        running.value = true
        note.value = null
        refreshRadios()
        startGnss()
        startMagnetometer()
    }

    @SuppressLint("MissingPermission")
    fun refreshRadios() {
        readWifi(triggerScan = true)
        readCells()
    }

    @SuppressLint("MissingPermission")
    private fun readWifi(triggerScan: Boolean) {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return
        if (triggerScan) {
            runCatching {
                @Suppress("DEPRECATION")
                wm.startScan()
            }
        }
        val raw = runCatching { wm.scanResults }.getOrNull().orEmpty()
        val parsed = raw.map { sr ->
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sr.wifiSsid?.toString()?.removeSurrounding("\"")?.trim().orEmpty()
            } else {
                @Suppress("DEPRECATION")
                sr.SSID.orEmpty().trim()
            }
            val width = when (sr.channelWidth) {
                ScanResult.CHANNEL_WIDTH_40MHZ -> 40
                ScanResult.CHANNEL_WIDTH_80MHZ -> 80
                ScanResult.CHANNEL_WIDTH_160MHZ, ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
                else -> 20
            }
            val std = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                when (sr.wifiStandard) {
                    ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6"
                    ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5"
                    ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4"
                    ScanResult.WIFI_STANDARD_LEGACY -> "Legacy"
                    else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && sr.wifiStandard == ScanResult.WIFI_STANDARD_11BE) "Wi-Fi 7" else ""
                }
            } else {
                ""
            }
            WifiNet(
                ssid = ssid,
                bssid = sr.BSSID.orEmpty(),
                freqMhz = sr.frequency,
                channel = RadioMath.wifiChannel(sr.frequency),
                band = RadioMath.wifiBandLabel(sr.frequency),
                widthMhz = width,
                rssiDbm = sr.level,
                security = RadioParsers.parseWifiSecurity(sr.capabilities.orEmpty()),
                standard = std,
            )
        }.sortedByDescending { it.rssiDbm }
        _wifi.value = parsed
    }

    @SuppressLint("MissingPermission")
    private fun readCells() {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return
        val raw = runCatching { tm.allCellInfo }.getOrNull().orEmpty()
        val out = raw.mapNotNull { info -> parseCell(info) }
            .sortedWith(compareByDescending<CellTower> { it.registered }.thenByDescending { it.rsrpDbm ?: -150 })
        _cells.value = out
    }

    private fun parseCell(info: CellInfo): CellTower? {
        val unavail = Int.MAX_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr) {
            val id = info.cellIdentity as? CellIdentityNr ?: return null
            val sig = info.cellSignalStrength as? CellSignalStrengthNr ?: return null
            val arfcn = id.nrarfcn.takeIf { it != unavail && it >= 0 }
            val freq = arfcn?.let { RadioMath.nrArfcnToMhz(it) }
            val mccmnc = listOfNotNull(id.mccString, id.mncString).joinToString("-")
            return CellTower(
                tech = "5G NR",
                registered = info.isRegistered,
                operatorMccMnc = mccmnc,
                arfcn = arfcn,
                freqMhz = freq,
                bandHint = freq?.let { RadioMath.nrBandHint(it) } ?: "5G NR",
                pci = id.pci.takeIf { it != unavail },
                rsrpDbm = sig.ssRsrp.takeIf { it != unavail },
                rsrqDb = sig.ssRsrq.takeIf { it != unavail },
                sinrDb = sig.ssSinr.takeIf { it != unavail },
            )
        }
        if (info is CellInfoLte) {
            val id = info.cellIdentity
            val sig = info.cellSignalStrength
            val arfcn = id.earfcn.takeIf { it != unavail && it >= 0 }
            val freqBand = arfcn?.let { RadioMath.lteEarfcnInfo(it) }
            val mccmnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                listOfNotNull(id.mccString, id.mncString).joinToString("-")
            } else {
                ""
            }
            return CellTower(
                tech = "4G LTE",
                registered = info.isRegistered,
                operatorMccMnc = mccmnc,
                arfcn = arfcn,
                freqMhz = freqBand?.first,
                bandHint = freqBand?.second ?: "LTE",
                pci = id.pci.takeIf { it != unavail },
                rsrpDbm = sig.rsrp.takeIf { it != unavail },
                rsrqDb = sig.rsrq.takeIf { it != unavail },
                sinrDb = sig.rssnr.takeIf { it != unavail && it in -20..40 },
            )
        }
        if (info is CellInfoWcdma) {
            val id = info.cellIdentity
            val sig = info.cellSignalStrength
            val mccmnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                listOfNotNull(id.mccString, id.mncString).joinToString("-")
            } else {
                ""
            }
            return CellTower(
                tech = "3G",
                registered = info.isRegistered,
                operatorMccMnc = mccmnc,
                arfcn = id.uarfcn.takeIf { it != unavail },
                freqMhz = null,
                bandHint = "3G WCDMA",
                pci = id.psc.takeIf { it != unavail },
                rsrpDbm = sig.dbm.takeIf { it != unavail },
                rsrqDb = null,
                sinrDb = null,
            )
        }
        if (info is CellInfoGsm) {
            val id = info.cellIdentity
            val sig = info.cellSignalStrength
            val mccmnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                listOfNotNull(id.mccString, id.mncString).joinToString("-")
            } else {
                ""
            }
            return CellTower(
                tech = "2G GSM",
                registered = info.isRegistered,
                operatorMccMnc = mccmnc,
                arfcn = id.arfcn.takeIf { it != unavail },
                freqMhz = null,
                bandHint = "2G GSM",
                pci = null,
                rsrpDbm = sig.dbm.takeIf { it != unavail },
                rsrqDb = null,
                sinrDb = null,
            )
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun startGnss() {
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val cb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val count = status.satelliteCount
                var used = 0
                var sumCn0 = 0.0
                var maxCn0 = 0.0
                var cn0Cnt = 0
                val constMap = linkedMapOf<String, Int>()
                val freqs = linkedSetOf<Double>()
                for (i in 0 until count) {
                    if (status.usedInFix(i)) used++
                    val cn0 = status.getCn0DbHz(i).toDouble()
                    if (cn0 > 0.0) {
                        sumCn0 += cn0
                        if (cn0 > maxCn0) maxCn0 = cn0
                        cn0Cnt++
                    }
                    val cName = when (status.getConstellationType(i)) {
                        GnssStatus.CONSTELLATION_GPS -> "GPS"
                        GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
                        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
                        GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
                        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
                        else -> "Otro"
                    }
                    constMap[cName] = (constMap[cName] ?: 0) + 1
                    if (status.hasCarrierFrequencyHz(i)) {
                        val mhz = round(status.getCarrierFrequencyHz(i).toDouble() / 100_000.0) / 10.0
                        if (mhz > 1000.0) freqs += mhz
                    }
                }
                _gnss.value = GnssSummary(
                    visible = count,
                    used = used,
                    avgCn0DbHz = if (cn0Cnt > 0) sumCn0 / cn0Cnt else 0.0,
                    maxCn0DbHz = maxCn0,
                    constellations = constMap,
                    carrierFreqsMhz = freqs.sorted(),
                )
            }
        }
        gnssCallback = cb
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.registerGnssStatusCallback(context.mainExecutor, cb)
            } else {
                @Suppress("DEPRECATION")
                lm.registerGnssStatusCallback(cb, Handler(Looper.getMainLooper()))
            }
        }
    }

    private fun startMagnetometer() {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (sensor == null) {
            _mag.value = MagneticReading(available = false)
            return
        }
        magCount = 0
        lastMagTsNs = 0L
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return
        val x = e.values[0].toDouble()
        val y = e.values[1].toDouble()
        val z = e.values[2].toDouble()
        val mag = sqrt(x * x + y * y + z * z)
        if (lastMagTsNs > 0L) {
            val dt = (e.timestamp - lastMagTsNs) / 1e9
            if (dt in 0.002..0.25) avgDtSec += 0.1 * (dt - avgDtSec)
        }
        lastMagTsNs = e.timestamp
        if (magCount < magRing.size) {
            magRing[magCount++] = mag
        } else {
            System.arraycopy(magRing, 1, magRing, 0, magRing.size - 1)
            magRing[magRing.size - 1] = mag
        }
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastFftMs >= 450L) {
            lastFftMs = nowMs
            val rate = if (avgDtSec > 0.0) 1.0 / avgDtSec else 50.0
            val slice = if (magCount == magRing.size) magRing.copyOf() else magRing.copyOf(magCount)
            val spectrum = RadioMath.fftMagnitudes(slice)
            val dom = RadioMath.dominantFrequencyHz(slice, rate)
            _mag.value = MagneticReading(
                magnitudeUt = mag,
                peakHz = dom?.first ?: 0.0,
                peakAmpUt = dom?.second ?: 0.0,
                sampleRateHz = rate,
                spectrum = spectrum.toList(),
                available = true,
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun stop() {
        running.value = false
        runCatching {
            gnssCallback?.let { context.getSystemService(LocationManager::class.java)?.unregisterGnssStatusCallback(it) }
        }
        gnssCallback = null
        runCatching { context.getSystemService(SensorManager::class.java)?.unregisterListener(this) }
    }
}

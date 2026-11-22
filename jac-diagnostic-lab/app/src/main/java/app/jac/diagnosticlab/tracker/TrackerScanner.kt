package app.jac.diagnosticlab.tracker

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Escáner BLE pasivo (no se conecta a nada por sí solo) que sella cada avistamiento con la última posición
 * del teléfono, extrae metadatos GAP/BluetoothClass y alimenta en segundo plano el estimador espacial.
 */
class TrackerScanner(private val context: Context) {
    private val book = SightingBook()
    private val _list = MutableStateFlow<List<Sighting>>(emptyList())
    val sightings: StateFlow<List<Sighting>> = _list
    private val _lastPoint = MutableStateFlow<GeoPoint?>(null)
    val lastPointFlow: StateFlow<GeoPoint?> = _lastPoint
    val lastPoint: GeoPoint? get() = _lastPoint.value
    val error = MutableStateFlow<String?>(null)
    val running = MutableStateFlow(false)
    private var callback: ScanCallback? = null
    private var locListener: LocationListener? = null
    private var lastPublish = 0L

    @SuppressLint("MissingPermission")
    private fun advertOf(r: ScanResult): Advert {
        val rec = r.scanRecord
        val man = buildMap {
            rec?.manufacturerSpecificData?.let { sa ->
                for (i in 0 until sa.size()) put(sa.keyAt(i), sa.valueAt(i))
            }
        }
        val uuids = rec?.serviceUuids?.map { TrackerClassifier.short16(it.uuid.toString()) }?.toSet().orEmpty()
        val sd = rec?.serviceData?.entries?.associate { TrackerClassifier.short16(it.key.uuid.toString()) to it.value }.orEmpty()
        val recName = rec?.deviceName?.trim()?.takeIf { it.isNotEmpty() }
        val aliasName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { r.device.alias }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val sysName = runCatching { r.device.name }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val appearance = BleAdParser.parseAppearance(rec?.bytes)
        val btClass = runCatching { r.device.bluetoothClass }.getOrNull()
        val tx = rec?.txPowerLevel?.takeIf { it != Int.MIN_VALUE && it in -127..127 }
        return Advert(
            manufacturer = man,
            serviceUuids = uuids,
            serviceData = sd,
            name = recName ?: aliasName ?: sysName,
            appearance = appearance,
            majorDeviceClass = btClass?.majorDeviceClass,
            deviceClass = btClass?.deviceClass,
            txPower = tx,
        )
    }

    @SuppressLint("MissingPermission")
    fun start() {
        stop()
        val bt = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (bt == null || !bt.isEnabled) {
            error.value = "Bluetooth apagado o no disponible."
            return
        }
        val scanner = bt.bluetoothLeScanner ?: run {
            error.value = "Este teléfono no expone escáner BLE."
            return
        }
        error.value = null
        startLocation()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                book.observe(result.device.address, advertOf(result), result.rssi, System.currentTimeMillis(), _lastPoint.value)
                val now = System.currentTimeMillis()
                if (now - lastPublish > 350) {
                    lastPublish = now
                    _list.value = book.all()
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(0, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                error.value = "El escaneo BLE falló (código $errorCode)."
                running.value = false
            }
        }
        callback = cb
        runCatching {
            scanner.startScan(emptyList(), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            running.value = true
        }.onFailure {
            error.value = "No se pudo iniciar el escaneo: ${it.message}. ¿Permiso de dispositivos cercanos?"
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val l = LocationListener { loc: Location ->
            _lastPoint.value = GeoPoint(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else null)
        }
        locListener = l
        runCatching {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { lm.isProviderEnabled(it) }
                .forEach { p ->
                    lm.getLastKnownLocation(p)?.let { l.onLocationChanged(it) }
                    lm.requestLocationUpdates(p, 1500L, 0f, l, Looper.getMainLooper())
                }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        runCatching {
            callback?.let { context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(it) }
        }
        callback = null
        runCatching { locListener?.let { context.getSystemService(LocationManager::class.java)?.removeUpdates(it) } }
        locListener = null
        running.value = false
        _list.value = book.all()
    }

    fun attachGatt(address: String, info: GattInfo) {
        book.attachGatt(address, info)
        _list.value = book.all()
    }

    fun positionEstimator(address: String): PositionEstimator = book.positionEstimator(address)

    fun resetPosition(address: String) {
        book.resetPosition(address)
        _list.value = book.all()
    }

    fun clear() {
        book.clear()
        _list.value = emptyList()
    }
}

package app.jac.diagnosticlab.bt

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import app.jac.diagnosticlab.elm.ObdTransport
import app.jac.diagnosticlab.elm.ReadOnlyGate
import app.jac.diagnosticlab.elm.ResponseAssembler
import app.jac.diagnosticlab.elm.TransportResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class BtDevice(
    val name: String,
    val address: String,
    val typeLabel: String,
    val candidate: Boolean,
    val primary: Boolean,
)

data class GattCharView(
    val serviceUuid: String,
    val uuid: String,
    val properties: List<String>,
    val canWrite: Boolean,
    val canNotify: Boolean,
)

private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

@SuppressLint("MissingPermission")
class BluetoothRepository(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter
    private val cache = linkedMapOf<String, BluetoothDevice>()
    private var classicReceiver: BroadcastReceiver? = null
    private var scanCallback: ScanCallback? = null

    fun isEnabled(): Boolean = adapter?.isEnabled == true

    fun bonded(): List<BtDevice> {
        val devices = adapter?.bondedDevices ?: return emptyList()
        devices.forEach { cache[it.address] = it }
        return devices.map { it.toView() }.sortedBy { it.name }
    }

    fun device(address: String): BluetoothDevice? = cache[address] ?: adapter?.getRemoteDevice(address)

    fun startScan(onDevice: (BtDevice) -> Unit, onError: (String) -> Unit) {
        stopScan()
        val bt = adapter ?: return onError("Este teléfono no expone un adaptador Bluetooth.")
        if (!bt.isEnabled) return onError("Bluetooth está apagado.")
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND).apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == BluetoothDevice.ACTION_FOUND) {
                    val device = intent.parcelDevice() ?: return
                    cache[device.address] = device
                    onDevice(device.toView())
                }
            }
        }
        classicReceiver = receiver
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        if (!bt.startDiscovery()) onError("Android no inició la búsqueda clásica.")
        val scanner = bt.bluetoothLeScanner ?: return
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                cache[result.device.address] = result.device
                onDevice(result.device.toView())
            }

            override fun onScanFailed(errorCode: Int) {
                onError("El escaneo BLE falló con código $errorCode.")
            }
        }
        scanCallback = callback
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(emptyList(), settings, callback)
    }

    fun stopScan() {
        try {
            adapter?.cancelDiscovery()
        } catch (_: Exception) {
        }
        try {
            scanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
        } catch (_: Exception) {
        }
        scanCallback = null
        classicReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        classicReceiver = null
    }

    fun adapter(): BluetoothAdapter? = adapter
}

@SuppressLint("MissingPermission")
class ClassicElmTransport(
    private val device: BluetoothDevice,
    private val adapter: BluetoothAdapter,
    private val log: (String) -> Unit,
) : ObdTransport {
    override val name: String = "Bluetooth clásico RFCOMM"
    private var socket: android.bluetooth.BluetoothSocket? = null
    private val mutex = Mutex()
    override val connected: Boolean get() = socket?.isConnected == true

    override suspend fun connect() {
        try {
            adapter.cancelDiscovery()
        } catch (_: Exception) {
        }
        var last: Exception? = null
        val attempts = listOf(false, false, true)
        for (fallback in attempts) {
            val opened = try {
                if (fallback) channelOne() else device.createRfcommSocketToServiceRecord(SPP)
            } catch (error: Exception) {
                last = error
                continue
            }
            socket = opened
            try {
                withTimeout(10_000) {
                    runInterruptible(Dispatchers.IO) { opened.connect() }
                }
                log(if (fallback) "RFCOMM conectado por el canal 1 de respaldo." else "RFCOMM conectado con el UUID SPP.")
                return
            } catch (error: Exception) {
                last = error
                try {
                    opened.close()
                } catch (_: Exception) {
                }
                socket = null
            }
        }
        throw last ?: IOException("No se pudo abrir RFCOMM.")
    }

    override suspend fun disconnect() {
        mutex.withLock {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            socket = null
        }
    }

    override suspend fun transact(command: String, timeoutMs: Long): TransportResult = mutex.withLock {
        ReadOnlyGate.check(command)
        val opened = socket ?: return TransportResult("", false, false, true, "socket cerrado")
        val assembler = ResponseAssembler()
        withContext(Dispatchers.IO) {
            try {
                val input = opened.inputStream
                val output = opened.outputStream
                val drain = ByteArray(256)
                while (input.available() > 0) input.read(drain)
                val payload = (command.trim() + "\r").toByteArray(Charsets.US_ASCII)
                output.write(payload)
                output.flush()
                val buffer = ByteArray(512)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val ready = input.available()
                    if (ready <= 0) {
                        delay(40)
                        continue
                    }
                    val count = input.read(buffer)
                    if (count < 0) {
                        return@withContext TransportResult(assembler.partial(), false, false, true, "fin de stream")
                    }
                    val done = assembler.append(String(buffer, 0, count, Charsets.ISO_8859_1))
                    if (done != null) return@withContext TransportResult(done, true, false, false, null)
                }
                TransportResult(assembler.partial(), false, true, false, "timeout")
            } catch (error: Exception) {
                TransportResult(assembler.partial(), false, false, true, error.message)
            }
        }
    }

    private fun channelOne(): android.bluetooth.BluetoothSocket {
        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        return method.invoke(device, 1) as android.bluetooth.BluetoothSocket
    }
}

@SuppressLint("MissingPermission")
class BleInspector(
    private val context: Context,
    private val device: BluetoothDevice,
    private val log: (String) -> Unit,
) : ObdTransport {
    override val name: String = "Bluetooth LE GATT"
    private var gatt: BluetoothGatt? = null
    private val incoming = Channel<ByteArray>(capacity = 64)
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyReady = CompletableDeferred<Boolean>()
    private val validated = AtomicBoolean(false)
    private val mutex = Mutex()
    override val connected: Boolean get() = gatt != null
    var profile: List<GattCharView> = emptyList()
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!connectedDeferred.isCompleted) connectedDeferred.complete(status)
                if (status == BluetoothGatt.GATT_SUCCESS) gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!connectedDeferred.isCompleted) connectedDeferred.complete(status)
                if (!servicesDeferred.isCompleted) servicesDeferred.complete(status)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!servicesDeferred.isCompleted) servicesDeferred.complete(status)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!notifyReady.isCompleted) notifyReady.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            incoming.trySend(value)
        }

        @Deprecated("API anterior a 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                characteristic.value?.let { incoming.trySend(it) }
            }
        }
    }

    private var connectedDeferred = CompletableDeferred<Int>()
    private var servicesDeferred = CompletableDeferred<Int>()

    suspend fun connectAndDiscover(): List<GattCharView> {
        connectedDeferred = CompletableDeferred()
        servicesDeferred = CompletableDeferred()
        withContext(Dispatchers.Main) {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        }
        val connectStatus = withTimeout(12_000) { connectedDeferred.await() }
        if (connectStatus != BluetoothGatt.GATT_SUCCESS) {
            throw IOException("GATT no conectó. Código $connectStatus.")
        }
        val discoverStatus = withTimeout(12_000) { servicesDeferred.await() }
        if (discoverStatus != BluetoothGatt.GATT_SUCCESS) throw IOException("No se descubrieron servicios. Código $discoverStatus.")
        withContext(Dispatchers.Main) { gatt?.requestMtu(185) }
        delay(300)
        profile = gatt?.services.orEmpty().flatMap { service ->
            service.characteristics.map { characteristic ->
                val props = characteristic.properties
                val names = mutableListOf<String>()
                val canWrite = props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                    props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                val canNotify = props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                    props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) names += "READ"
                if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) names += "WRITE"
                if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) names += "WRITE_NR"
                if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) names += "NOTIFY"
                if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) names += "INDICATE"
                GattCharView(service.uuid.toString(), characteristic.uuid.toString(), names, canWrite, canNotify)
            }
        }
        log("GATT descubrió ${profile.size} características. Aún no se escribió ninguna.")
        return profile
    }

    suspend fun selectAndValidate(writeUuid: String, notifyUuid: String): String {
        val g = gatt ?: throw IOException("GATT cerrado.")
        val write = find(writeUuid, notify = false)
        val notify = find(notifyUuid, notify = true)
        if (!write.canWrite()) throw IOException("La característica elegida no admite escritura.")
        if (!notify.canNotify()) throw IOException("La característica elegida no admite notificación.")
        writeChar = write
        notifyReady = CompletableDeferred()
        withContext(Dispatchers.Main) {
            g.setCharacteristicNotification(notify, true)
            val descriptor = notify.getDescriptor(CCCD) ?: throw IOException("La característica no tiene el descriptor estándar CCCD.")
            val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(descriptor, value)
            } else {
                descriptor.value = value
                g.writeDescriptor(descriptor)
            }
        }
        val subscribed = withTimeout(5_000) { notifyReady.await() }
        if (!subscribed) throw IOException("No se pudo suscribir la notificación.")
        log("Notificación suscrita en $notifyUuid. Se envía solo ATI para validar.")
        val result = transact("ATI", 5000, allowUnvalidated = true)
        val text = result.raw.uppercase()
        val ok = result.promptSeen && (text.contains("ELM") || Regex("V\\d+\\.\\d+").containsMatchIn(text))
        validated.set(ok)
        if (!ok) {
            writeChar = null
            throw IOException("ATI no validó un ELM327. Respuesta: ${result.raw.ifBlank { result.error ?: "vacía" }}")
        }
        log("BLE validado con ATI.")
        return result.raw
    }

    override suspend fun connect() {
        if (profile.isEmpty()) connectAndDiscover()
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.Main) {
            try {
                gatt?.disconnect()
                gatt?.close()
            } catch (_: Exception) {
            }
            gatt = null
        }
        validated.set(false)
    }

    override suspend fun transact(command: String, timeoutMs: Long): TransportResult =
        transact(command, timeoutMs, allowUnvalidated = false)

    private suspend fun transact(command: String, timeoutMs: Long, allowUnvalidated: Boolean): TransportResult = mutex.withLock {
        ReadOnlyGate.check(command)
        if (!validated.get() && !allowUnvalidated) {
            return TransportResult("", false, false, true, "BLE todavía no fue validado con ATI")
        }
        val g = gatt ?: return TransportResult("", false, false, true, "GATT cerrado")
        val characteristic = writeChar ?: return TransportResult("", false, false, true, "sin característica de escritura")
        val assembler = ResponseAssembler()
        while (incoming.tryReceive().getOrNull() != null) {
        }
        val type = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val bytes = (command.trim() + "\r").toByteArray(Charsets.US_ASCII)
        val queued = withContext(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(characteristic, bytes, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                characteristic.writeType = type
                characteristic.value = bytes
                g.writeCharacteristic(characteristic)
            }
        }
        if (!queued) return TransportResult("", false, false, false, "Android rechazó la escritura GATT")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val wait = deadline - System.currentTimeMillis()
            val chunk = try {
                kotlinx.coroutines.withTimeoutOrNull(wait.coerceAtLeast(1)) { incoming.receive() }
            } catch (_: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                return TransportResult(assembler.partial(), false, false, true, "GATT cerrado")
            } ?: break
            val done = assembler.append(String(chunk, Charsets.ISO_8859_1))
            if (done != null) return TransportResult(done, true, false, false, null)
        }
        TransportResult(assembler.partial(), false, true, false, "timeout BLE")
    }

    private fun find(uuid: String, notify: Boolean): BluetoothGattCharacteristic {
        val target = UUID.fromString(uuid)
        val all = gatt?.services.orEmpty().flatMap { it.characteristics }
        return all.firstOrNull { it.uuid == target && if (notify) it.canNotify() else it.canWrite() }
            ?: throw IOException("No está la característica $uuid con la propiedad requerida.")
    }
}

private fun BluetoothGattCharacteristic.canWrite(): Boolean =
    properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
        properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0

private fun BluetoothGattCharacteristic.canNotify(): Boolean =
    properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
        properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0

@SuppressLint("MissingPermission")
private fun BluetoothDevice.toView(): BtDevice {
    val label = name?.trim().orEmpty().ifBlank { "(sin nombre)" }
    val upper = label.uppercase()
    val primary = upper == "OBDII" || upper == "OBDBLE"
    val type = when (type) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Clásico"
        BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "Clásico y BLE"
        else -> "Tipo no informado"
    }
    return BtDevice(label, address, type, primary || upper.contains("OBD") || upper.contains("ELM"), primary)
}

private fun Intent.parcelDevice(): BluetoothDevice? =
    if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }

class DiagnosticConnectionService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Diagnóstico", NotificationManager.IMPORTANCE_LOW),
                )
            }
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("JAC Diagnostic Lab")
                .setContentText("Sesión de solo lectura. No borra códigos ni modifica la ECU.")
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(42, notification)
            }
            START_NOT_STICKY
        } catch (_: Exception) {
            stopSelf()
            START_NOT_STICKY
        }
    }

    companion object {
        const val CHANNEL = "jac.diagnostic.session"
    }
}

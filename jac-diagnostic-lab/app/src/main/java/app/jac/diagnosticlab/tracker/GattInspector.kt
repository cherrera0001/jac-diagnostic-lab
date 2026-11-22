package app.jac.diagnosticlab.tracker

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Consulta activa opcional (solo bajo demanda al pulsar «Identificar»).
 * Lee características estándar de solo lectura:
 * - GAP (0x1800): Device Name (0x2A00), Appearance (0x2A01)
 * - Device Information (0x180A): Manufacturer (0x2A29), Model (0x2A24), Firmware (0x2A26)
 * - Battery (0x180F): Battery Level (0x2A19)
 * - IETF DULT Non-Owner Service (15190001-12F4-C226-88ED-2AC5579F2A85):
 *   Get_Manufacturer_Name (0x0004), Get_Model_Name (0x0005), Get_Accessory_Category (0x0006)
 *
 * Muchos tags junto a su dueño rechazan conexiones GATT o esconden DULT por privacidad:
 * en ese caso se informa honestamente sin inventar datos.
 */
object GattInspector {
    private val UUID_GAP = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
    private val UUID_DEVICE_NAME = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")
    private val UUID_APPEARANCE = UUID.fromString("00002a01-0000-1000-8000-00805f9b34fb")
    private val UUID_DIS = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    private val UUID_MANUFACTURER = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
    private val UUID_MODEL = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    private val UUID_FIRMWARE = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    private val UUID_BATTERY_SVC = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val UUID_BATTERY_LVL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    private val UUID_DULT_SVC = UUID.fromString("15190001-12f4-c226-88ed-2ac5579f2a85")
    private val UUID_DULT_CHAR = UUID.fromString("8e0c0001-1d68-fb92-bf61-48377421680e")
    private val UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    @SuppressLint("MissingPermission")
    suspend fun inspect(context: Context, address: String, timeoutMs: Long = 8_500L): GattInfo = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val bt = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return@withContext GattInfo(statusNote = "Bluetooth no disponible.", inspectedAtMs = now)
        val device = runCatching { bt.getRemoteDevice(address) }.getOrNull()
            ?: return@withContext GattInfo(statusNote = "Dirección BLE no válida.", inspectedAtMs = now)

        val done = CompletableDeferred<GattInfo>()
        var gattRef: BluetoothGatt? = null

        var deviceName: String? = null
        var manufacturer: String? = null
        var model: String? = null
        var firmware: String? = null
        var battery: Int? = null
        var appearance: Int? = null
        var dultCategory: String? = null
        var dultChar: BluetoothGattCharacteristic? = null
        val readQueue = ArrayDeque<BluetoothGattCharacteristic>()
        val dultOps = ArrayDeque<ByteArray>()

        fun finish(note: String) {
            if (!done.isCompleted) {
                done.complete(
                    GattInfo(
                        deviceName = deviceName,
                        manufacturer = manufacturer,
                        model = model,
                        firmware = firmware,
                        batteryPercent = battery,
                        dultCategory = dultCategory,
                        appearance = appearance,
                        statusNote = note,
                        inspectedAtMs = System.currentTimeMillis(),
                    ),
                )
            }
        }

        fun cleanUtf8(bytes: ByteArray): String? =
            bytes.toString(Charsets.UTF_8).replace("\u0000", "").trim().takeIf { it.isNotEmpty() }

        fun sendNextDult(gatt: BluetoothGatt) {
            val ch = dultChar
            if (ch == null || dultOps.isEmpty()) {
                val got = listOfNotNull(deviceName, manufacturer, model, dultCategory).isNotEmpty()
                finish(if (got) "Datos leídos vía GATT/DULT." else "Conectó, pero no expuso nombre ni modelo.")
                return
            }
            val cmd = dultOps.removeFirst()
            val ok = writeChar(gatt, ch, cmd)
            if (!ok) {
                finish(if (deviceName != null || manufacturer != null || model != null) "Datos GATT leídos (DULT no respondió)." else "DULT rechazó la consulta.")
            }
        }

        fun startDultOrFinish(gatt: BluetoothGatt) {
            val ch = dultChar
            if (ch == null) {
                val got = listOfNotNull(deviceName, manufacturer, model, firmware, battery).isNotEmpty()
                finish(
                    if (got) "Leído por GATT estándar."
                    else "Conectó por GATT pero no expone nombre, modelo ni servicio DULT (normal en tags junto a su dueño).",
                )
                return
            }
            gatt.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(UUID_CCCD)
            dultOps.addLast(byteArrayOf(0x04, 0x00)) // Get_Manufacturer_Name
            dultOps.addLast(byteArrayOf(0x05, 0x00)) // Get_Model_Name
            dultOps.addLast(byteArrayOf(0x06, 0x00)) // Get_Accessory_Category
            if (cccd != null && writeDesc(gatt, cccd, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
                return
            }
            sendNextDult(gatt)
        }

        fun readNext(gatt: BluetoothGatt) {
            while (readQueue.isNotEmpty()) {
                val ch = readQueue.removeFirst()
                if (gatt.readCharacteristic(ch)) return
            }
            startDultOrFinish(gatt)
        }

        fun handleRead(gatt: BluetoothGatt, uuid: UUID, value: ByteArray?) {
            if (value != null && value.isNotEmpty()) {
                when (uuid) {
                    UUID_DEVICE_NAME -> deviceName = cleanUtf8(value)
                    UUID_MANUFACTURER -> manufacturer = cleanUtf8(value)
                    UUID_MODEL -> model = cleanUtf8(value)
                    UUID_FIRMWARE -> firmware = cleanUtf8(value)
                    UUID_BATTERY_LVL -> battery = (value[0].toInt() and 0xFF).coerceIn(0, 100)
                    UUID_APPEARANCE -> if (value.size >= 2) {
                        appearance = (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)
                    }
                }
            }
            readNext(gatt)
        }

        fun handleDultIndication(gatt: BluetoothGatt, value: ByteArray) {
            val parsed = BleAdParser.parseDultResponse(value)
            if (parsed != null) {
                val (op, payload) = parsed
                when (op) {
                    0x0804 -> manufacturer = cleanUtf8(payload) ?: manufacturer
                    0x0805 -> model = cleanUtf8(payload) ?: model
                    0x0806 -> if (payload.isNotEmpty()) dultCategory = BleAdParser.dultCategoryLabel(payload[0].toInt() and 0xFF)
                }
            }
            sendNextDult(gatt)
        }

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val got = listOfNotNull(deviceName, manufacturer, model, dultCategory).isNotEmpty()
                    finish(
                        if (got) "Datos parciales leídos antes de desconectar."
                        else "No aceptó conexión GATT (código $status). Es normal en tags emparejados junto a su dueño.",
                    )
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!gatt.discoverServices()) finish("No se pudieron descubrir servicios GATT.")
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish("Error al descubrir servicios GATT ($status).")
                    return
                }
                gatt.getService(UUID_GAP)?.let { s ->
                    s.getCharacteristic(UUID_DEVICE_NAME)?.let(readQueue::addLast)
                    s.getCharacteristic(UUID_APPEARANCE)?.let(readQueue::addLast)
                }
                gatt.getService(UUID_DIS)?.let { s ->
                    s.getCharacteristic(UUID_MANUFACTURER)?.let(readQueue::addLast)
                    s.getCharacteristic(UUID_MODEL)?.let(readQueue::addLast)
                    s.getCharacteristic(UUID_FIRMWARE)?.let(readQueue::addLast)
                }
                gatt.getService(UUID_BATTERY_SVC)?.getCharacteristic(UUID_BATTERY_LVL)?.let(readQueue::addLast)
                dultChar = gatt.getService(UUID_DULT_SVC)?.getCharacteristic(UUID_DULT_CHAR)
                readNext(gatt)
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                handleRead(gatt, characteristic.uuid, if (status == BluetoothGatt.GATT_SUCCESS) value else null)
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    @Suppress("DEPRECATION")
                    handleRead(gatt, characteristic.uuid, if (status == BluetoothGatt.GATT_SUCCESS) characteristic.value else null)
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                sendNextDult(gatt)
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (characteristic.uuid == UUID_DULT_CHAR && status != BluetoothGatt.GATT_SUCCESS) {
                    sendNextDult(gatt)
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                if (characteristic.uuid == UUID_DULT_CHAR) handleDultIndication(gatt, value)
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && characteristic.uuid == UUID_DULT_CHAR) {
                    @Suppress("DEPRECATION")
                    characteristic.value?.let { handleDultIndication(gatt, it) }
                }
            }
        }

        try {
            gattRef = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
            withTimeoutOrNull(timeoutMs) { done.await() }
                ?: GattInfo(
                    deviceName = deviceName,
                    manufacturer = manufacturer,
                    model = model,
                    firmware = firmware,
                    batteryPercent = battery,
                    dultCategory = dultCategory,
                    appearance = appearance,
                    statusNote = "Tiempo agotado esperando respuesta GATT/DULT (normal en tags pasivos o lejos).",
                    inspectedAtMs = System.currentTimeMillis(),
                )
        } catch (t: Throwable) {
            GattInfo(statusNote = "Error al consultar GATT: ${t.message.orEmpty()}", inspectedAtMs = System.currentTimeMillis())
        } finally {
            runCatching { gattRef?.disconnect() }
            runCatching { gattRef?.close() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeDesc(gatt: BluetoothGatt, desc: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(desc, value) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            desc.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(desc)
        }

    @SuppressLint("MissingPermission")
    private fun writeChar(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.value = value
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(ch)
        }
}

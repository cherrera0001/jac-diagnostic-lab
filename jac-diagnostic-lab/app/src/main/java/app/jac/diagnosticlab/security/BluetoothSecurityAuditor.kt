package app.jac.diagnosticlab.security

import app.jac.diagnosticlab.bt.BtDevice
import app.jac.diagnosticlab.bt.GattCharView

data class BluetoothAuditReport(
    val devicesSeen: Int,
    val candidates: List<String>,
    val classicNameSeen: Boolean,
    val bleNameSeen: Boolean,
    val gattServices: List<String>,
    val writeChars: List<String>,
    val notifyChars: List<String>,
    val states: List<String>,
    val risksVerified: List<String>,
    val unknowns: List<String>,
)

object BluetoothSecurityAuditor {
    fun audit(
        devices: List<BtDevice>,
        gatt: List<GattCharView>,
        bleValidated: Boolean,
        link: String,
        transportKind: String,
    ): BluetoothAuditReport {
        val classic = devices.any { it.name.equals("OBDII", true) }
        val ble = devices.any { it.name.equals("OBDBLE", true) }
        val candidates = devices.filter { it.candidate }.map { "${it.name} (${it.typeLabel})" }
        val services = gatt.map { it.serviceUuid }.distinct()
        val writes = gatt.filter { it.canWrite }.map { it.uuid }
        val notifies = gatt.filter { it.canNotify }.map { it.uuid }
        val states = buildList {
            add("visible_or_bonded=${devices.isNotEmpty()}")
            add("link=$link")
            add("transport=$transportKind")
            add("ble_validated=$bleValidated")
            add("gatt_chars=${gatt.size}")
        }
        val risks = buildList {
            if (bleValidated || writes.isNotEmpty()) {
                add("Tras vincular/conectar, el perfil GATT expuso características WRITE/NOTIFY utilizables para ATI.")
            }
            if (ble && writes.any { it.contains("fff2", true) }) {
                add("Servicio propietario FFF0/FFF2 aceptó escritura de comandos AT en la sesión real.")
            }
        }
        val unknowns = buildList {
            add("No se midió en esta app si un segundo teléfono no emparejado obtiene GATT sin interacción del dueño.")
            add("PIN/código de emparejamiento no se captura ni se fuerza; queda al sistema Android.")
            if (!classic) add("Nombre OBDII no apareció en el escaneo de la sesión analizada.")
        }
        return BluetoothAuditReport(
            devicesSeen = devices.size,
            candidates = candidates,
            classicNameSeen = classic,
            bleNameSeen = ble,
            gattServices = services,
            writeChars = writes,
            notifyChars = notifies,
            states = states,
            risksVerified = risks,
            unknowns = unknowns,
        )
    }
}

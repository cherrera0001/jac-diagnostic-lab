package app.jac.diagnosticlab.bt

/**
 * Selección segura de características OBD sobre BLE.
 * Evita el default peligroso: 2A00 (Device Name WRITE) + 2A05 (Service Changed INDICATE),
 * que en muchos dongles aparecen antes que FFF1/FFF2 y producen «validaciones» inútiles.
 */
object GattObdProfile {
    private val REJECT_UUID_FRAGMENTS = listOf(
        "00001800-", // Generic Access
        "00001801-", // Generic Attribute
        "0000180a-", // Device Information
        "0000180f-", // Battery
        "00001804-", // Tx Power
        "00002a00-", // Device Name
        "00002a01-", // Appearance
        "00002a04-", // Preferred Connection Parameters
        "00002a05-", // Service Changed
        "00002a07-", // Tx Power Level
        "00002a19-", // Battery Level
        "00002a29-", // Manufacturer Name
        "00002a24-", // Model Number
        "00002a25-", // Serial Number
        "00002a26-", // Firmware Revision
        "00002a27-", // Hardware Revision
        "00002a28-", // Software Revision
    )

    private val PREFERRED_WRITE = listOf("0000fff2-", "fff2")
    private val PREFERRED_NOTIFY = listOf("0000fff1-", "fff1")

    fun isRejectedUuid(uuid: String): Boolean {
        val u = uuid.lowercase()
        return REJECT_UUID_FRAGMENTS.any { u.startsWith(it) || u.contains(it.trimEnd('-')) }
    }

    fun isPreferredWrite(uuid: String): Boolean {
        val u = uuid.lowercase()
        return PREFERRED_WRITE.any { u.startsWith(it) || u.endsWith(it) || u.contains(it.trimEnd('-')) }
    }

    fun isPreferredNotify(uuid: String): Boolean {
        val u = uuid.lowercase()
        return PREFERRED_NOTIFY.any { u.startsWith(it) || u.endsWith(it) || u.contains(it.trimEnd('-')) }
    }

    fun scorePair(writeUuid: String, notifyUuid: String): Int {
        var score = 0
        if (isRejectedUuid(writeUuid) || isRejectedUuid(notifyUuid)) return -1000
        if (isPreferredWrite(writeUuid)) score += 100
        if (isPreferredNotify(notifyUuid)) score += 100
        if (writeUuid.equals(notifyUuid, true)) score -= 50
        return score
    }

    fun defaultWrite(rows: List<GattCharView>): String =
        rows.filter { it.canWrite && !isRejectedUuid(it.uuid) }
            .maxByOrNull { if (isPreferredWrite(it.uuid)) 10 else 1 }
            ?.uuid
            .orEmpty()

    fun defaultNotify(rows: List<GattCharView>): String =
        rows.filter { it.canNotify && !isRejectedUuid(it.uuid) }
            .maxByOrNull { if (isPreferredNotify(it.uuid)) 10 else 1 }
            ?.uuid
            .orEmpty()

    fun rankCandidates(pairs: List<Pair<String, String>>): List<Pair<String, String>> =
        pairs
            .filter { scorePair(it.first, it.second) >= 0 }
            .sortedByDescending { scorePair(it.first, it.second) }
            .ifEmpty {
                // Último recurso: pares no preferidos pero no claramente basura estándar
                pairs.filter { scorePair(it.first, it.second) > -1000 }
                    .sortedByDescending { scorePair(it.first, it.second) }
            }

    /** Paquetes conocidos que pelean el RFCOMM/GATT del mismo dongle. */
    val CONFLICTING_PACKAGES = listOf(
        "com.obdautodoctor",
        "com.digitalcarkeyllc.car.scanner.obd.elm.torque.fixd.carly",
        "org.prowl.torque",
        "com.evotools.obdscanner",
        "com.obd.hybrid",
    )
}

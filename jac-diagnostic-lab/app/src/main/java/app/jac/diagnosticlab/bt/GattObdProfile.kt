package app.jac.diagnosticlab.bt

/**
 * Selección segura de características OBD sobre BLE.
 * Evita el default peligroso: 2A00 (Device Name WRITE) + 2A05 (Service Changed INDICATE),
 * que en muchos dongles aparecen antes que FFF1/FFF2 y producen «validaciones» inútiles.
 *
 * Perfiles aceptados:
 *  - FFF2 escritura + FFF1 aviso (OBDBLE / Vgate y clones).
 *  - FFE1 escritura+aviso en una sola característica (HM-10 y clones ELM327 BLE).
 */
object GattObdProfile {
    private const val SIG_BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    /** UUID SIG estándar (16 bits) que nunca transportan ELM327. */
    private val REJECTED_SHORT = setOf(
        "1800", // Generic Access
        "1801", // Generic Attribute
        "180a", // Device Information
        "180f", // Battery
        "1804", // Tx Power
        "2a00", // Device Name
        "2a01", // Appearance
        "2a04", // Preferred Connection Parameters
        "2a05", // Service Changed
        "2a07", // Tx Power Level
        "2a19", // Battery Level
        "2a24", // Model Number
        "2a25", // Serial Number
        "2a26", // Firmware Revision
        "2a27", // Hardware Revision
        "2a28", // Software Revision
        "2a29", // Manufacturer Name
    )

    private val PREFERRED_WRITE = setOf("fff2", "ffe1")
    private val PREFERRED_NOTIFY = setOf("fff1", "ffe1")

    /**
     * Forma corta de 16 bits si el UUID está en la base SIG (`0000xxxx-0000-1000-8000-00805f9b34fb`)
     * o ya viene corto (`fff1`); `null` para UUID de 128 bits propietarios.
     */
    internal fun shortId(uuid: String): String? {
        val u = uuid.trim().lowercase()
        return when {
            u.length == 4 -> u
            u.length == 36 && u.startsWith("0000") && u.endsWith(SIG_BASE_SUFFIX) -> u.substring(4, 8)
            else -> null
        }
    }

    fun isRejectedUuid(uuid: String): Boolean = shortId(uuid) in REJECTED_SHORT

    fun isPreferredWrite(uuid: String): Boolean = shortId(uuid) in PREFERRED_WRITE

    fun isPreferredNotify(uuid: String): Boolean = shortId(uuid) in PREFERRED_NOTIFY

    /**
     * < 0 solo para pares con UUID SIG estándar (basura). Una misma característica con
     * WRITE+NOTIFY (FFE1) es válida y no se penaliza por debajo de cero.
     */
    fun scorePair(writeUuid: String, notifyUuid: String): Int {
        if (isRejectedUuid(writeUuid) || isRejectedUuid(notifyUuid)) return -1000
        var score = 0
        if (isPreferredWrite(writeUuid)) score += 100
        if (isPreferredNotify(notifyUuid)) score += 100
        // Pareja canónica FFF2/FFF1 por encima de FFE1/FFE1.
        if (writeUuid.equals(notifyUuid, ignoreCase = true)) score -= 10
        return score.coerceAtLeast(0)
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

    /** Paquetes conocidos que pelean el RFCOMM/GATT del mismo dongle (declarados en <queries>). */
    val CONFLICTING_PACKAGES = listOf(
        "com.obdautodoctor",
        "com.digitalcarkeyllc.car.scanner.obd.elm.torque.fixd.carly",
        "org.prowl.torque",
        "com.evotools.obdscanner",
        "com.obd.hybrid",
    )
}

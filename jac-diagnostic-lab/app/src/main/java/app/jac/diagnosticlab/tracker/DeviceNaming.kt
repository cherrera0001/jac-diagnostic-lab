package app.jac.diagnosticlab.tracker

import java.util.Locale

/**
 * Nombre legible de un dispositivo BLE.
 * Prioridad: apodo local > modelo/nombre leído por GATT/DULT > nombre anunciado BLE > red de rastreador >
 * fabricante + subtipo / apariencia GAP / servicios. Cuando no hay nombre real se aclara que es inferido.
 */
object DeviceNaming {
    /** Números de empresa del Bluetooth SIG (Assigned Numbers verificados). */
    private val companies = mapOf(
        0x0000 to "Ericsson", 0x0001 to "Nokia", 0x0002 to "Intel", 0x0006 to "Microsoft", 0x0008 to "Motorola",
        0x000A to "Qualcomm (CSR)", 0x000D to "Texas Instruments", 0x000F to "Broadcom", 0x004C to "Apple",
        0x0057 to "Harman / JBL", 0x0059 to "Nordic Semiconductor", 0x005D to "Realtek", 0x0075 to "Samsung",
        0x0087 to "Garmin", 0x009E to "Bose", 0x00C4 to "LG", 0x00E0 to "Google", 0x012D to "Sony",
        0x0157 to "Huami / Amazfit", 0x0171 to "Amazon", 0x01DA to "Logitech", 0x027D to "Huawei",
        0x02E5 to "Espressif", 0x038F to "Xiaomi",
    )
    private val services = mapOf(
        "180a" to "Información del dispositivo", "180d" to "Pulsómetro", "180f" to "Batería", "1812" to "Teclado/ratón (HID)",
        "fe2c" to "Google Fast Pair", "fe33" to "Chipolo", "fe95" to "Xiaomi MiBeacon", "fd59" to "Samsung SmartTag",
        "fd5a" to "Samsung SmartTag", "fd6f" to "Notificación de exposición", "feaa" to "Eddystone / Find Hub",
        "feec" to "Tile", "feed" to "Tile", TrackerClassifier.DULT_SERVICE_UUID to "Rastreador DULT",
    )

    fun companyName(id: Int): String? = companies[id]
    fun serviceName(short16: String): String? = services[short16.lowercase(Locale.US)]

    /** Subtipo de los datos de fabricante de Apple (TLV). Inferido de documentación pública. */
    fun appleKind(type: Int): String? = when (type) {
        0x12 -> "Find My (accesorio)"
        0x10 -> "iPhone/iPad/Mac cercano"
        0x0C -> "Handoff"
        0x07 -> "AirPods / Beats"
        0x0F -> "Acción cercana"
        0x09 -> "AirPlay"
        0x06 -> "HomeKit"
        0x05 -> "AirDrop"
        0x02 -> "iBeacon"
        else -> null
    }

    data class Display(val title: String, val subtitle: String, val named: Boolean)

    private fun gattPrimaryLabel(gatt: GattInfo?): String? {
        if (gatt == null) return null
        val mfr = gatt.manufacturer?.trim()?.takeIf { it.isNotEmpty() }
        val model = gatt.model?.trim()?.takeIf { it.isNotEmpty() }
        val gName = gatt.deviceName?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            mfr != null && model != null -> if (model.contains(mfr, ignoreCase = true)) model else "$mfr · $model"
            model != null -> model
            gName != null -> gName
            mfr != null && gatt.dultCategory != null -> "$mfr · ${gatt.dultCategory}"
            else -> null
        }
    }

    fun display(a: Advert, c: Classification, gatt: GattInfo? = null): Display {
        val cleanName = a.name?.trim()?.takeIf { it.isNotEmpty() }
        val gattTitle = gattPrimaryLabel(gatt)
        val named = cleanName != null || gattTitle != null
        val appearanceStr = BleAdParser.appearanceLabel(gatt?.appearance ?: a.appearance ?: -1)

        val details = buildList {
            a.manufacturer.keys.sorted().forEach { id ->
                val comp = companyName(id) ?: String.format(Locale.US, "fabricante 0x%04X", id)
                val sub = when (id) {
                    TrackerClassifier.COMPANY_APPLE -> {
                        val types = BleAdParser.appleSubtypes(a.manufacturer[id] ?: ByteArray(0))
                        types.firstNotNullOfOrNull(::appleKind)
                    }
                    TrackerClassifier.COMPANY_MICROSOFT -> "PC Windows (Swift Pair)"
                    else -> null
                }
                add(if (sub != null) "$comp · $sub" else comp)
            }
            (a.serviceUuids + a.serviceData.keys)
                .map { TrackerClassifier.short16(it) }
                .distinct()
                .mapNotNull(::serviceName)
                .forEach { svc -> if (svc !in this) add(svc) }
            if (appearanceStr != null && appearanceStr !in this) add(appearanceStr)
        }

        val title = when {
            gattTitle != null -> gattTitle
            cleanName != null -> cleanName
            c.kind != TrackerKind.UNKNOWN -> c.kind.label
            details.isNotEmpty() -> details.first()
            c.category != DeviceCategory.OTHER -> "${c.category.label} sin nombre"
            else -> "Dispositivo BLE sin nombre"
        }

        val sub = buildList {
            if (gattTitle != null && cleanName != null && !gattTitle.equals(cleanName, ignoreCase = true)) {
                add("BLE: $cleanName")
            }
            if (named && c.kind != TrackerKind.UNKNOWN && c.kind.label != title) {
                add(c.kind.label)
            }
            gatt?.dultCategory?.let { if (it !in title) add("DULT: $it") }
            gatt?.batteryPercent?.let { add("Batería $it%") }
            val rest = if (!named && c.kind == TrackerKind.UNKNOWN) details.drop(1) else details
            rest.forEach { item -> if (item != title && item !in this) add(item) }
            if (!named) add("sin nombre anunciado")
        }.joinToString(" · ")

        return Display(title, sub, named)
    }

    fun resolvedTitle(a: Advert, c: Classification, nickname: String? = null, gatt: GattInfo? = null): String =
        nickname?.trim()?.takeIf { it.isNotEmpty() } ?: display(a, c, gatt).title
}

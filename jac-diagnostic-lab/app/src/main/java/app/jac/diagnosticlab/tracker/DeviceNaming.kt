package app.jac.diagnosticlab.tracker

import java.util.Locale

/**
 * Nombre legible de un dispositivo BLE. Muchos (y casi todos los rastreadores) NO anuncian nombre por privacidad:
 * en ese caso se usa lo que sí se puede identificar del anuncio (fabricante, subtipo, servicios) y se declara que es inferido.
 */
object DeviceNaming {
    /** Números de empresa del Bluetooth SIG (Assigned Numbers). */
    private val companies = mapOf(
        0x0000 to "Ericsson", 0x0001 to "Nokia", 0x0002 to "Intel", 0x0006 to "Microsoft", 0x0008 to "Motorola",
        0x000A to "Qualcomm (CSR)", 0x000D to "Texas Instruments", 0x000F to "Broadcom", 0x004C to "Apple", 0x0059 to "Nordic Semiconductor",
        0x005D to "Realtek", 0x0075 to "Samsung", 0x0087 to "Garmin", 0x009E to "Bose", 0x00C4 to "LG", 0x00E0 to "Google",
        0x012D to "Sony", 0x0171 to "Amazon", 0x027D to "Huawei", 0x02E5 to "Espressif", 0x038F to "Xiaomi",
    )
    private val services = mapOf(
        "180a" to "Información del dispositivo", "180d" to "Pulsómetro", "180f" to "Batería", "1812" to "Teclado/ratón (HID)",
        "fe2c" to "Google Fast Pair", "fe33" to "Chipolo", "fd6f" to "Notificación de exposición",
        "feaa" to "Eddystone", "feec" to "Tile", "feed" to "Tile", "fd5a" to "Samsung SmartTag",
    )

    fun companyName(id: Int): String? = companies[id]
    fun serviceName(short16: String): String? = services[short16.lowercase(Locale.US)]

    /** Subtipo de los datos de fabricante de Apple (primer byte). Inferido de documentación pública, sin validar. */
    fun appleKind(type: Int): String? = when (type) {
        0x12 -> "Find My (accesorio)"; 0x10 -> "iPhone/iPad/Mac cercano"; 0x0C -> "Handoff"; 0x07 -> "AirPods / Beats"
        0x0F -> "Acción cercana"; 0x09 -> "AirPlay"; 0x05 -> "AirDrop"; else -> null
    }

    data class Display(val title: String, val subtitle: String, val named: Boolean)

    fun display(a: Advert, c: Classification): Display {
        val cleanName = a.name?.trim()?.takeIf { it.isNotEmpty() }
        val named = cleanName != null
        val details = buildList {
            a.manufacturer.keys.sorted().forEach { id ->
                val comp = companyName(id) ?: String.format(Locale.US, "fabricante 0x%04X", id)
                val sub = if (id == TrackerClassifier.COMPANY_APPLE) a.manufacturer[id]?.firstOrNull()?.let { appleKind(it.toInt() and 0xFF) } else null
                add(if (sub != null) "$comp · $sub" else comp)
            }
            (a.serviceUuids + a.serviceData.keys)
                .map { TrackerClassifier.short16(it) }
                .distinct()
                .mapNotNull(::serviceName)
                .forEach { svc -> if (svc !in this) add(svc) }
        }
        val title = when {
            named -> cleanName!!
            c.kind != TrackerKind.UNKNOWN -> c.kind.label
            details.isNotEmpty() -> details.first()
            else -> "Dispositivo BLE sin nombre"
        }
        val sub = buildList {
            if (named && c.kind != TrackerKind.UNKNOWN) add(c.kind.label)
            val rest = if (!named && c.kind == TrackerKind.UNKNOWN) details.drop(1) else details
            rest.forEach { if (it != title && it !in this) add(it) }
            if (!named) add("sin nombre anunciado")
        }.joinToString(" · ")
        return Display(title, sub, named)
    }

    fun resolvedTitle(a: Advert, c: Classification, nickname: String? = null): String =
        nickname?.trim()?.takeIf { it.isNotEmpty() } ?: display(a, c).title
}

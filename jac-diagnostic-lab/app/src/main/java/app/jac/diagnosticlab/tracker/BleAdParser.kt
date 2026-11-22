package app.jac.diagnosticlab.tracker

import java.util.Locale

/**
 * Utilidades puras (JVM) para extraer metadatos estándar de paquetes BLE crudos (AD structures),
 * subtipos Apple Continuity TLV y respuestas del protocolo IETF DULT.
 */
object BleAdParser {
    private const val AD_TYPE_APPEARANCE = 0x19

    /** Extrae el código GAP Appearance (uint16 little-endian, AD Type 0x19) desde ScanRecord.bytes. */
    fun parseAppearance(rawBytes: ByteArray?): Int? {
        if (rawBytes == null || rawBytes.isEmpty()) return null
        var i = 0
        while (i < rawBytes.size) {
            val len = rawBytes[i].toInt() and 0xFF
            if (len == 0) break
            val next = i + 1 + len
            if (next > rawBytes.size) break
            val type = rawBytes[i + 1].toInt() and 0xFF
            if (type == AD_TYPE_APPEARANCE && len >= 3) {
                val lo = rawBytes[i + 2].toInt() and 0xFF
                val hi = rawBytes[i + 3].toInt() and 0xFF
                return lo or (hi shl 8)
            }
            i = next
        }
        return null
    }

    /** Traduce códigos estándar Bluetooth SIG GAP Appearance a etiquetas legibles. */
    fun appearanceLabel(code: Int): String? = when (code) {
        0x03C1 -> "Teclado"
        0x03C2 -> "Ratón"
        0x03C3 -> "Joystick"
        0x03C4 -> "Control de juegos"
        0x0941 -> "Audífonos intraurales"
        0x0942 -> "Diadema / Headset"
        0x0943 -> "Audífonos"
        else -> when (code ushr 6) {
            0x01 -> "Teléfono"
            0x02 -> "Computador"
            0x03 -> "Reloj"
            0x04 -> "Reloj de mesa"
            0x05 -> "Pantalla / TV"
            0x06 -> "Control remoto"
            0x07 -> "Gafas inteligentes"
            0x08 -> "Etiqueta / Tag"
            0x09 -> "Llavero"
            0x0A -> "Reproductor multimedia"
            0x0D -> "Sensor de pulso"
            0x0F -> "Periférico HID"
            0x11 -> "Dispositivo vehicular"
            0x21 -> "Audio / Parlante"
            0x25 -> "Audio portátil"
            else -> null
        }
    }

    /**
     * Extrae todos los subtipos TLV contenidos en datos de fabricante Apple (0x004C).
     * Los anuncios Continuity suelen concatenar varios bloques `[tipo, largo, payload...]`.
     */
    fun appleSubtypes(data: ByteArray): List<Int> {
        if (data.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        var i = 0
        while (i + 1 < data.size) {
            val type = data[i].toInt() and 0xFF
            val len = data[i + 1].toInt() and 0xFF
            val next = i + 2 + len
            if (len == 0 || next > data.size) break
            out += type
            i = next
        }
        if (out.isEmpty()) out += (data[0].toInt() and 0xFF)
        return out
    }

    /**
     * Parsea una respuesta IETF DULT (opcode uint16 little-endian + payload).
     * Opcodes conocidos: 0x0804 (Manufacturer), 0x0805 (Model), 0x0806 (Category).
     */
    fun parseDultResponse(payload: ByteArray): Pair<Int, ByteArray>? {
        if (payload.size < 2) return null
        val opcode = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
        return opcode to payload.copyOfRange(2, payload.size)
    }

    fun dultCategoryLabel(code: Int): String = when (code) {
        1 -> "Rastreador / Tag"
        2 -> "Equipaje"
        3 -> "Bolso / Mochila"
        4 -> "Bicicleta"
        5 -> "Cámara"
        6 -> "Llaves"
        7 -> "Cartera"
        8 -> "Audífonos"
        9 -> "Electrónica"
        10 -> "Mascota"
        11 -> "Vehículo"
        else -> "Accesorio DULT (#$code)"
    }

    fun hexPrefix(bytes: ByteArray, maxBytes: Int = 8): String {
        val shown = bytes.take(maxBytes).joinToString(" ") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }
        return if (bytes.size > maxBytes) "$shown … (${bytes.size} B)" else shown
    }
}

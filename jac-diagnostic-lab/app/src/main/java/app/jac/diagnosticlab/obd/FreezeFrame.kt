package app.jac.diagnosticlab.obd

import app.jac.diagnosticlab.elm.Exchange
import app.jac.diagnosticlab.elm.ResponseKind

/** Valor del cuadro congelado (Mode 02, cuadro 00) con sus bytes originales. */
data class FreezeValue(val pid: Int, val signals: List<Signal>, val raw: String)

/**
 * Cuadro congelado completo: el estado del motor en el momento en que se guardó el DTC.
 * Solo lectura (`02PP00`, permitido por ObdCommandPolicy). La decodificación es la misma de Mode 01.
 */
object FreezeFrame {
    /** PID que el taller necesita para ubicar la falla, en orden de lectura. */
    val pids = listOf(0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x1F, 0x23, 0x2C, 0x2F, 0x33, 0x42, 0x46, 0x49, 0x4A, 0x5C)

    fun command(pid: Int): String = "02%02X00".format(pid)

    fun plan(supported: Set<Int>): List<Int> = pids.filter { it in supported }

    /** `42 PP 00 A B…` → señales del PID. null si la ECU no tiene ese dato congelado. */
    fun decode(pid: Int, exchange: Exchange): FreezeValue? {
        if (exchange.kind != ResponseKind.OBD_DATA) return null
        val payload = exchange.frames.map { it.payload }.firstOrNull { it.size >= 4 && it[0] == 0x42 && it[1] == pid } ?: return null
        val data = payload.drop(3)
        val signals = PidCatalog.decode(pid, data)
        if (signals.isEmpty()) return null
        return FreezeValue(pid, signals, payload.joinToString(" ") { "%02X".format(it) })
    }
}

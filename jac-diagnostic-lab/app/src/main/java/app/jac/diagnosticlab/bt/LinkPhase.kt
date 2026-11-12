package app.jac.diagnosticlab.bt

/**
 * No confundir Bluetooth conectado con ECU respondiendo.
 */
enum class LinkPhase(val label: String) {
    DISCONNECTED("Desconectado"),
    TRANSPORT_CONNECTED("Transporte abierto"),
    ELM_READY("ELM listo (ATI)"),
    ECU_RESPONDING("ECU responde (Mode 01)"),
    DIAGNOSTIC_READY("Diagnóstico listo (mapa/DTC)"),
}

object GattRetryPolicy {
    const val MAX_ATTEMPTS = 3

    /** Backoff tras fallo 133 (u otro status de connect). */
    fun backoffMs(attemptZeroBased: Int): Long = 400L * (attemptZeroBased + 1)

    fun shouldRetry(status: Int, attemptZeroBased: Int): Boolean {
        if (attemptZeroBased + 1 >= MAX_ATTEMPTS) return false
        // 133 = GATT_ERROR típico en stacks Android; también reintentar otros fallos de connect.
        return status != 0 // 0 = GATT_SUCCESS; cualquier otro status de connect puede reintentarse
    }
}

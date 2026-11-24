package app.jac.diagnosticlab.bt

enum class RecoveryAction { KEEP_TRYING, RECONNECT, GIVE_UP }

/**
 * Política pura de recuperación del enlace OBD durante una prueba/captura.
 * Antes la prueba terminaba a las 5 rondas sin respuesta; ahora, tras [missesBeforeReconnect] rondas
 * perdidas se reabre el transporte y la captura continúa, hasta [maxReconnects] intentos fallidos
 * o [maxOutageMs] de corte continuo.
 */
class LinkRecoveryPolicy(
    val missesBeforeReconnect: Int = 3,
    val maxReconnects: Int = 10,
    val maxOutageMs: Long = 5 * 60_000L,
) {
    fun decide(consecutiveMisses: Int, failedReconnects: Int, outageMs: Long): RecoveryAction = when {
        failedReconnects >= maxReconnects || outageMs > maxOutageMs -> RecoveryAction.GIVE_UP
        consecutiveMisses >= missesBeforeReconnect -> RecoveryAction.RECONNECT
        else -> RecoveryAction.KEEP_TRYING
    }

    /** Espera antes del siguiente intento: 2 s, 3 s, 4,5 s… con tope de 10 s. */
    fun backoffMs(failedReconnects: Int): Long {
        var wait = 2_000.0
        repeat(failedReconnects.coerceAtLeast(0)) { wait = (wait * 1.5).coerceAtMost(10_000.0) }
        return wait.toLong().coerceIn(1_000L, 10_000L)
    }
}

data class LinkOutage(
    val startMs: Long,
    val endMs: Long?,
    val reconnectAttempts: Int,
    val recovered: Boolean,
) {
    val durationMs: Long get() = ((endMs ?: startMs) - startMs).coerceAtLeast(0)
}

/** Registro de cortes del enlace para el informe: cuándo cayó, cuánto duró y si se recuperó. */
class LinkOutageLog {
    private val _outages = mutableListOf<LinkOutage>()
    val outages: List<LinkOutage> get() = _outages.toList()
    private var openStart: Long? = null
    private var attempts = 0
    private var recoveredFlag = false

    val totalOutageMs: Long get() = _outages.sumOf { it.durationMs }
    val isOpen: Boolean get() = openStart != null

    fun begin(atMs: Long) {
        if (openStart != null) return
        openStart = atMs; attempts = 0; recoveredFlag = false
    }

    fun reconnectAttempt(ok: Boolean) {
        if (openStart == null) return
        attempts++
        if (ok) recoveredFlag = true
    }

    fun end(atMs: Long) {
        val start = openStart ?: return
        _outages += LinkOutage(start, atMs, attempts, recovered = true)
        openStart = null
    }

    /** Cierra un corte que no se recuperó (la prueba terminó sin enlace). */
    fun endUnrecovered(atMs: Long) {
        val start = openStart ?: return
        _outages += LinkOutage(start, atMs, attempts, recovered = false)
        openStart = null
    }

    fun summary(): String = if (_outages.isEmpty()) "Sin cortes de enlace." else {
        val ok = _outages.count { it.recovered }
        "${_outages.size} corte(s) de enlace, $ok recuperado(s); ${totalOutageMs / 1000} s sin datos en total."
    }
}

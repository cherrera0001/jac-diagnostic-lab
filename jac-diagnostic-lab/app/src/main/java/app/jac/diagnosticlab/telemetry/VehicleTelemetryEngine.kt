package app.jac.diagnosticlab.telemetry

/**
 * Motor de telemetría independiente de Compose.
 * Un solo ejecutor: el llamador pregunta [nextDue], ejecuta, y registra el resultado.
 * No promete Hz; solo mide lo obtenido.
 */
enum class PollBand { FAST, MEDIUM, SLOW }

data class ChannelPlan(
    val pid: Int,
    val band: PollBand,
    val targetIntervalMs: Long,
)

data class ChannelStats(
    val pid: Int,
    val band: PollBand,
    val targetIntervalMs: Long,
    val sampleCount: Int,
    val failCount: Int,
    val lastAtMs: Long?,
    val lastLatencyMs: Long?,
    val effectiveHz: Double,
    val minGapMs: Long?,
    val maxGapMs: Long?,
    val ageMs: Long?,
)

class VehicleTelemetryEngine(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var plans: List<ChannelPlan> = emptyList()
    private val lastPolled = mutableMapOf<Int, Long>()
    private val timestamps = mutableMapOf<Int, MutableList<Long>>()
    private val latencies = mutableMapOf<Int, Long>()
    private val fails = mutableMapOf<Int, Int>()
    private var paused: Boolean = false
    private var rrCursor: Int = 0

    fun reset() {
        plans = emptyList()
        lastPolled.clear()
        timestamps.clear()
        latencies.clear()
        fails.clear()
        paused = false
        rrCursor = 0
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    fun isPaused(): Boolean = paused

    fun plan(pids: Set<Int>, baseIntervalMs: Long): List<ChannelPlan> {
        val base = baseIntervalMs.coerceIn(100L, 10_000L)
        plans = pids.filter { it != 0x00 }.distinct().sorted().map { pid ->
            val band = bandFor(pid)
            val mult = when (band) {
                PollBand.FAST -> 1L
                PollBand.MEDIUM -> 2L
                PollBand.SLOW -> 4L
            }
            ChannelPlan(pid, band, base * mult)
        }
        return plans
    }

    fun currentPlans(): List<ChannelPlan> = plans

    /**
     * Elige como máximo un PID vencido. Prioridad: FAST > MEDIUM > SLOW,
     * y dentro de la misma banda el más atrasado (anti-starvation por antigüedad).
     */
    fun nextDue(now: Long = clock()): Int? {
        if (paused || plans.isEmpty()) return null
        val due = plans.filter { plan ->
            val last = lastPolled[plan.pid]
            last == null || now - last >= plan.targetIntervalMs
        }
        if (due.isEmpty()) return null
        val bestBand = due.minOf { it.band.ordinal }
        val inBand = due.filter { it.band.ordinal == bestBand }
        return inBand.minBy { plan ->
            val last = lastPolled[plan.pid] ?: Long.MIN_VALUE / 4
            last
        }.pid
    }

    /** Ms hasta el próximo vencimiento (para sleep); null si hay trabajo ya. */
    fun sleepHintMs(now: Long = clock()): Long? {
        if (paused || plans.isEmpty()) return 100L
        if (nextDue(now) != null) return null
        val waits = plans.map { plan ->
            val last = lastPolled[plan.pid] ?: return@map 0L
            (plan.targetIntervalMs - (now - last)).coerceAtLeast(0L)
        }
        return waits.minOrNull()?.coerceAtMost(500L) ?: 50L
    }

    fun recordSuccess(pid: Int, latencyMs: Long, atMs: Long = clock()) {
        lastPolled[pid] = atMs
        latencies[pid] = latencyMs.coerceAtLeast(0L)
        val list = timestamps.getOrPut(pid) { mutableListOf() }
        list += atMs
        if (list.size > 64) list.removeAt(0)
    }

    fun recordFailure(pid: Int, latencyMs: Long, atMs: Long = clock()) {
        lastPolled[pid] = atMs
        latencies[pid] = latencyMs.coerceAtLeast(0L)
        fails[pid] = (fails[pid] ?: 0) + 1
    }

    fun stats(now: Long = clock()): Map<Int, ChannelStats> {
        return plans.associate { plan ->
            val stamps = timestamps[plan.pid].orEmpty().sorted()
            val gaps = stamps.zipWithNext { a, b -> b - a }
            val window = if (stamps.size >= 2) stamps.last() - stamps.first() else 0L
            val hz = if (window > 0L && stamps.size >= 2) {
                (stamps.size - 1).toDouble() * 1000.0 / window.toDouble()
            } else {
                0.0
            }
            val last = lastPolled[plan.pid]
            plan.pid to ChannelStats(
                pid = plan.pid,
                band = plan.band,
                targetIntervalMs = plan.targetIntervalMs,
                sampleCount = stamps.size,
                failCount = fails[plan.pid] ?: 0,
                lastAtMs = last,
                lastLatencyMs = latencies[plan.pid],
                effectiveHz = hz,
                minGapMs = gaps.minOrNull(),
                maxGapMs = gaps.maxOrNull(),
                ageMs = last?.let { (now - it).coerceAtLeast(0L) },
            )
        }
    }

    companion object {
        fun bandFor(pid: Int): PollBand = when (pid) {
            0x0C, 0x0D, 0x11, 0x49, 0x4A, 0x4B -> PollBand.FAST
            0x04, 0x0B, 0x45, 0x47 -> PollBand.MEDIUM
            else -> PollBand.SLOW // refrigerante, voltajes, ATRV (-1), etc.
        }
    }
}

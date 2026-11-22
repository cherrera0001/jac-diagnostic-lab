package app.jac.diagnosticlab.tracker

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

data class DirectionEstimate(
    /** Rumbo absoluto (0 = norte, horario) hacia donde la señal fue más fuerte. */
    val bearingDeg: Double,
    /** Diferencia en dB entre el mejor sector y el opuesto. Poco contraste = flecha poco fiable. */
    val contrastDb: Double,
    val coverage: Double,
    val clear: Boolean,
)

/**
 * Dirección ORIENTATIVA a partir de BLE: el RSSI no trae dirección, pero con el teléfono delante del cuerpo
 * el cuerpo apantalla la señal, así que girando se ve un sector más fuerte. Se acumula RSSI por sector de rumbo
 * (brújula del teléfono). Sin UWB ni antena direccional esto es una heurística: devuelve [clear]=false cuando
 * no hay contraste suficiente, en vez de inventar una flecha.
 */
class DirectionEstimator(private val bins: Int = 16, private val maxAgeMs: Long = 25_000L, private val minCoverage: Double = 0.5, private val minContrastDb: Double = 5.0) {
    private val avg = DoubleArray(bins)
    private val n = IntArray(bins)
    private val last = LongArray(bins)

    private fun idx(headingDeg: Double): Int = (((headingDeg % 360 + 360) % 360) / (360.0 / bins)).toInt().coerceIn(0, bins - 1)

    fun add(headingDeg: Double, rssi: Int, atMs: Long) {
        val i = idx(headingDeg)
        if (n[i] == 0 || atMs - last[i] > maxAgeMs) { avg[i] = rssi.toDouble(); n[i] = 1 } else { avg[i] += 0.4 * (rssi - avg[i]); n[i]++ }
        last[i] = atMs
    }

    fun reset() { n.fill(0) }

    private fun valid(i: Int, now: Long) = n[i] > 0 && now - last[i] <= maxAgeMs

    fun coverage(now: Long): Double = (0 until bins).count { valid(it, now) }.toDouble() / bins

    /** null = aún no se giró lo suficiente. */
    fun estimate(now: Long): DirectionEstimate? {
        val cov = coverage(now)
        if (cov < minCoverage) return null
        val ids = (0 until bins).filter { valid(it, now) }
        val best = ids.maxOf { avg[it] }
        var x = 0.0; var y = 0.0
        for (i in ids) {
            val w = exp((avg[i] - best) / 5.0)
            val a = Math.toRadians((i + 0.5) * 360.0 / bins)
            x += w * sin(a); y += w * cos(a)
        }
        val bearing = (Math.toDegrees(atan2(x, y)) + 360) % 360
        val opp = ids.filter { val d = abs(((i2deg(it) - bearing - 180 + 540) % 360) - 180); d <= 45 }
        val oppMean = if (opp.isEmpty()) best else opp.map { avg[it] }.average()
        val contrast = best - oppMean
        return DirectionEstimate(bearing, contrast, cov, contrast >= minContrastDb)
    }

    private fun i2deg(i: Int) = (i + 0.5) * 360.0 / bins

    companion object {
        /** Ángulo con el que dibujar la flecha respecto a la parte superior del teléfono, en (-180, 180]. */
        fun relative(bearingDeg: Double, headingDeg: Double): Double {
            val d = ((bearingDeg - headingDeg) % 360 + 540) % 360 - 180
            return if (d == -180.0) 180.0 else d
        }
    }
}

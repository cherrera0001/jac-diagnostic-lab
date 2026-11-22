package app.jac.diagnosticlab.tracker

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

object Geo {
    private const val M_LAT = 110_540.0
    private const val M_LON = 111_320.0

    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val n = (b.lat - a.lat) * M_LAT
        val e = (b.lon - a.lon) * M_LON * cos(Math.toRadians((a.lat + b.lat) / 2))
        return hypot(e, n)
    }

    /** Rumbo inicial (0 = norte, horario) de [a] hacia [b]; válido para decenas de metros. */
    fun bearingDeg(a: GeoPoint, b: GeoPoint): Double {
        val n = (b.lat - a.lat) * M_LAT
        val e = (b.lon - a.lon) * M_LON * cos(Math.toRadians((a.lat + b.lat) / 2))
        return (Math.toDegrees(atan2(e, n)) + 360) % 360
    }
}

data class PositionEstimate(
    val point: GeoPoint,
    /** Radio de las posiciones igual de plausibles: incertidumbre real del ajuste, no una promesa. */
    val radiusM: Double,
    val pathLossExponent: Double,
    val rssiAt1m: Double,
    val rmsDb: Double,
    val r2: Double,
    val samples: Int,
    val usable: Boolean,
)

/**
 * Posición estimada de un tag estático a partir de (posición GPS, RSSI) mientras el usuario camina.
 * Opera en segundo plano sin obligar al usuario a abrir un mapa:
 * - Fusiona lecturas muy cercanas (< 1,5 m) suavizando el RSSI en ese punto (evita sesgar al quedarse quieto).
 * - Ajusta rssi = A − 10·n·log10(d) sobre una malla local por mínimos cuadrados.
 * - Calibra la región de incertidumbre con la varianza residual real (σ²), el ajuste explicado (R²) y el RMS:
 *   un recorrido en línea recta (dos lados simétricos igual de plausibles) o ruido puro / tag en movimiento
 *   devuelven usable = false en vez de inventar una flecha.
 * - Seguro para hilos: add() puede llamarse desde el escáner mientras estimate() corre en Dispatchers.Default.
 */
class PositionEstimator(
    private val maxSamples: Int = 200,
    private val maxAccuracyM: Float = 30f,
    private val usableRadiusM: Double = 25.0,
    private val minSpanM: Double = 8.0,
    private val minRssiSpanDb: Double = 6.0,
    private val minR2: Double = 0.35,
    private val maxRmsDb: Double = 8.0,
) {
    private class Sample(var p: GeoPoint, var rssi: Double, var hits: Int)

    private val samples = ArrayList<Sample>()
    private var _version: Long = 0L

    @Synchronized
    fun reset() {
        samples.clear()
        _version++
    }

    @get:Synchronized
    val count: Int get() = samples.size

    @get:Synchronized
    val version: Long get() = _version

    @Synchronized
    fun add(p: GeoPoint, rssi: Int) {
        if (p.accuracyM != null && p.accuracyM > maxAccuracyM) return
        val last = samples.lastOrNull()
        if (last != null && Geo.distanceM(last.p, p) < 1.5) {
            // Mismo sitio: suavizar RSSI mejora la relación señal/ruido sin inflar el peso de esa parada.
            last.hits++
            val alpha = max(0.2, 1.0 / last.hits)
            last.rssi += alpha * (rssi - last.rssi)
            if ((p.accuracyM ?: Float.MAX_VALUE) < (last.p.accuracyM ?: Float.MAX_VALUE)) {
                last.p = p
            }
            _version++
            return
        }
        samples += Sample(p, rssi.toDouble(), 1)
        if (samples.size > maxSamples) samples.removeAt(0)
        _version++
    }

    fun estimate(): PositionEstimate? {
        val snap = synchronized(this) {
            if (samples.size < 8) return null
            samples.map { it.p to it.rssi }
        }
        val n = snap.size
        val o = snap[0].first
        val cosLat = cos(Math.toRadians(o.lat))
        val e = DoubleArray(n) { (snap[it].first.lon - o.lon) * 111_320.0 * cosLat }
        val nn = DoubleArray(n) { (snap[it].first.lat - o.lat) * 110_540.0 }
        val r = DoubleArray(n) { snap[it].second }
        val minE = e.min(); val maxE = e.max()
        val minN = nn.min(); val maxN = nn.max()
        val spanE = maxE - minE; val spanN = maxN - minN
        if (hypot(spanE, spanN) < minSpanM || r.max() - r.min() < minRssiSpanDb) return null

        val meanR = r.average()
        var ssTot = 0.0
        for (i in 0 until n) {
            val dr = r[i] - meanR
            ssTot += dr * dr
        }
        if (ssTot < 1e-6) return null

        val g = 41
        val extent = max(spanE, spanN) + 80.0
        val e0 = (minE + maxE) / 2.0 - extent / 2.0
        val n0 = (minN + maxN) / 2.0 - extent / 2.0
        val step = extent / (g - 1)
        val exps = doubleArrayOf(1.8, 2.2, 2.8, 3.4)
        val res = DoubleArray(g * g) { Double.POSITIVE_INFINITY }
        val resExp = IntArray(g * g)
        val resA = DoubleArray(g * g)
        var best = Double.POSITIVE_INFINITY
        var bi = -1
        val x = DoubleArray(n)

        for (iy in 0 until g) {
            val cn = n0 + iy * step
            for (ix in 0 until g) {
                val ce = e0 + ix * step
                var sumX = 0.0
                for (i in 0 until n) {
                    val xi = -10.0 * log10(max(1.0, hypot(e[i] - ce, nn[i] - cn)))
                    x[i] = xi
                    sumX += xi
                }
                val meanX = sumX / n
                val c = iy * g + ix
                for ((k, ex) in exps.withIndex()) {
                    val a = meanR - ex * meanX
                    if (a < -85.0 || a > -35.0) continue
                    var rs = 0.0
                    for (i in 0 until n) {
                        val d = r[i] - a - ex * x[i]
                        rs += d * d
                    }
                    if (rs < res[c]) {
                        res[c] = rs; resExp[c] = k; resA[c] = a
                    }
                    if (rs < best) {
                        best = rs; bi = c
                    }
                }
            }
        }
        if (bi < 0) return null

        val r2 = 1.0 - best / ssTot
        val rms = sqrt(best / n)
        val sigma2 = max(9.0, best / max(1, n - 3))
        val thr = best + max(6.0 * sigma2, n * 1.5)
        val bE = e0 + (bi % g) * step
        val bN = n0 + (bi / g) * step

        var radius = 0.0
        var se = 0.0; var sn = 0.0; var sw = 0.0
        for (c in res.indices) {
            val v = res[c]
            if (v <= thr) {
                val ce = e0 + (c % g) * step
                val cn = n0 + (c / g) * step
                radius = max(radius, hypot(ce - bE, cn - bN))
                val w = exp(-(v - best) / (2.0 * sigma2))
                se += w * ce; sn += w * cn; sw += w
            }
        }
        val mE = if (sw > 0.0) se / sw else bE
        val mN = if (sw > 0.0) sn / sw else bN
        val lat = o.lat + mN / 110_540.0
        val lon = o.lon + mE / (111_320.0 * cosLat)
        radius = max(radius, step)
        val usable = radius <= usableRadiusM && r2 >= minR2 && rms <= maxRmsDb
        return PositionEstimate(
            point = GeoPoint(lat, lon),
            radiusM = radius,
            pathLossExponent = exps[resExp[bi]],
            rssiAt1m = resA[bi],
            rmsDb = rms,
            r2 = r2,
            samples = n,
            usable = usable,
        )
    }
}

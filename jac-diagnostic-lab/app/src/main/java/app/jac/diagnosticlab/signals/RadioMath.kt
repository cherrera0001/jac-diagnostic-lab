package app.jac.diagnosticlab.signals

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * Cálculos físicos y de radiofrecuencia puros (JVM):
 * - Conversión de canales Wi-Fi, LTE EARFCN y 5G NR-ARFCN (3GPP TS 38.104) a frecuencia en MHz.
 * - Capacidad teórica de Shannon–Hartley: C = B * log2(1 + SNR).
 * - FFT radix-2 (Cooley–Tukey) con ventana de Hann para espectro de oscilaciones en tiempo real.
 */
object RadioMath {
    fun wifiChannel(freqMhz: Int): Int? = when {
        freqMhz == 2484 -> 14
        freqMhz in 2412..2472 && (freqMhz - 2407) % 5 == 0 -> (freqMhz - 2407) / 5
        freqMhz in 5180..5885 && (freqMhz - 5000) % 5 == 0 -> (freqMhz - 5000) / 5
        freqMhz in 5955..7115 && (freqMhz - 5950) % 5 == 0 -> (freqMhz - 5950) / 5
        else -> null
    }

    fun wifiBandLabel(freqMhz: Int): String = when {
        freqMhz in 2400..2500 -> "2.4 GHz"
        freqMhz in 5000..5900 -> "5 GHz"
        freqMhz in 5925..7125 -> "6 GHz"
        else -> "${freqMhz} MHz"
    }

    private data class LteBand(val name: String, val fLowMhz: Double, val nOffs: Int, val nMin: Int, val nMax: Int)

    /** Bandas 4G LTE más comunes (incluye todas las bandas usadas en Chile y Latinoamérica: B2, B4/66, B5, B7, B28). */
    private val lteBands = listOf(
        LteBand("B1 (2100)", 2110.0, 0, 0, 599),
        LteBand("B2 (1900)", 1930.0, 600, 600, 1199),
        LteBand("B3 (1800)", 1805.0, 1200, 1200, 1949),
        LteBand("B4 (AWS)", 2110.0, 1950, 1950, 2399),
        LteBand("B5 (850)", 869.0, 2400, 2400, 2649),
        LteBand("B7 (2600)", 2620.0, 2750, 2750, 3449),
        LteBand("B8 (900)", 925.0, 3450, 3450, 3799),
        LteBand("B20 (800)", 791.0, 6150, 6150, 6449),
        LteBand("B28 (700 APT)", 758.0, 9210, 9210, 9659),
        LteBand("B40 (2300 TDD)", 2300.0, 38650, 38650, 39649),
        LteBand("B41 (2500 TDD)", 2496.0, 39650, 39650, 41589),
        LteBand("B42 (3500 TDD)", 3400.0, 41590, 41590, 43589),
        LteBand("B66 (AWS-3)", 2110.0, 66436, 66436, 67335),
    )

    /** Devuelve (frecuencia MHz, etiqueta de banda) para un EARFCN downlink LTE. */
    fun lteEarfcnInfo(earfcn: Int): Pair<Double, String>? {
        val b = lteBands.firstOrNull { earfcn in it.nMin..it.nMax } ?: return null
        return (b.fLowMhz + 0.1 * (earfcn - b.nOffs)) to b.name
    }

    /** Conversión exacta 3GPP TS 38.104 de NR-ARFCN (5G NR) a frecuencia en MHz. */
    fun nrArfcnToMhz(nrarfcn: Int): Double? = when (nrarfcn) {
        in 0..599_999 -> 0.005 * nrarfcn
        in 600_000..2_016_666 -> 3000.0 + 0.015 * (nrarfcn - 600_000)
        in 2_016_667..3_279_165 -> 24250.08 + 0.06 * (nrarfcn - 2_016_667)
        else -> null
    }

    fun nrBandHint(freqMhz: Double): String = when {
        freqMhz in 700.0..810.0 -> "n28 (700 MHz)"
        freqMhz in 1700.0..2200.0 -> "n66/n1 (AWS/2100)"
        freqMhz in 2500.0..2700.0 -> "n7/n41 (2600)"
        freqMhz in 3300.0..3800.0 -> "n78 (3.5 GHz)"
        freqMhz >= 24000.0 -> "mmWave (FR2)"
        else -> "5G NR"
    }

    /** Capacidad teórica de Shannon–Hartley en Mbps (una capa SISO): C = B * log2(1 + 10^(SNR/10)). */
    fun shannonCapacityMbps(bandwidthMhz: Double, snrDb: Double): Double {
        if (bandwidthMhz <= 0.0) return 0.0
        val linear = 10.0.pow(snrDb.coerceIn(-20.0, 45.0) / 10.0)
        return bandwidthMhz * (ln(1.0 + linear) / ln(2.0))
    }

    /**
     * FFT radix-2 (Cooley–Tukey) sobre la mayor potencia de 2 <= samples.size.
     * Quita el componente DC, aplica ventana de Hann y devuelve magnitudes unilaterales (bins 1..N/2).
     */
    fun fftMagnitudes(samples: DoubleArray): DoubleArray {
        var n = 1
        while (n * 2 <= samples.size) n *= 2
        if (n < 8) return DoubleArray(0)
        val offset = samples.size - n
        var mean = 0.0
        for (i in 0 until n) mean += samples[offset + i]
        mean /= n

        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (i in 0 until n) {
            val w = 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
            re[i] = (samples[offset + i] - mean) * w
        }

        // Permutación bit-reversal
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wLenRe = cos(ang)
            val wLenIm = sin(ang)
            var i = 0
            while (i < n) {
                var wRe = 1.0
                var wIm = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + half] * wRe - im[i + k + half] * wIm
                    val vIm = re[i + k + half] * wIm + im[i + k + half] * wRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + half] = uRe - vRe
                    im[i + k + half] = uIm - vIm
                    val nextRe = wRe * wLenRe - wIm * wLenIm
                    val nextIm = wRe * wLenIm + wIm * wLenRe
                    wRe = nextRe
                    wIm = nextIm
                }
                i += len
            }
            len = len shl 1
        }

        val half = n / 2
        // Factor 4/N compensa la ventana de Hann y el espectro unilateral
        val scale = 4.0 / n
        return DoubleArray(half) { k ->
            val bin = k + 1
            if (bin < half) hypot(re[bin], im[bin]) * scale else hypot(re[half], im[half]) * (2.0 / n)
        }
    }

    /** Devuelve (frecuencia dominante Hz, amplitud) de una serie temporal muestreada a [sampleRateHz]. */
    fun dominantFrequencyHz(samples: DoubleArray, sampleRateHz: Double): Pair<Double, Double>? {
        if (sampleRateHz <= 0.0) return null
        val mags = fftMagnitudes(samples)
        if (mags.isEmpty()) return null
        val n = mags.size * 2
        var bestIdx = 0
        for (i in 1 until mags.size) {
            if (mags[i] > mags[bestIdx]) bestIdx = i
        }
        val freq = (bestIdx + 1) * sampleRateHz / n
        return freq to mags[bestIdx]
    }
}

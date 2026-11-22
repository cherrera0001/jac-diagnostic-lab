package app.jac.diagnosticlab.ui

import app.jac.diagnosticlab.analysis.HealthItem
import app.jac.diagnosticlab.analysis.HealthStatus
import app.jac.diagnosticlab.obd.Signal
import kotlin.math.abs

/** Presentación: convierte datos crudos en piezas cortas y legibles. Sin lógica de diagnóstico. */
object Present {

    /** Sistemas en el orden en que se muestran. */
    val systems = listOf("Códigos", "Motor", "Combustible", "Turbo y aire", "EGR", "DPF y escape", "Refrigeración", "Eléctrico", "Monitores")

    /** Sistema de HealthAssessment → mosaico. */
    fun tileOf(item: HealthItem): String = when (item.system) {
        "Códigos" -> "Códigos"
        "Monitores", "Autopruebas" -> "Monitores"
        "Combustible" -> "Combustible"
        "Turbo", "Admisión" -> "Turbo y aire"
        "EGR" -> "EGR"
        "DPF", "Escape", "Emisiones" -> "DPF y escape"
        "Refrigeración", "Lubricación" -> "Refrigeración"
        "Eléctrico" -> "Eléctrico"
        else -> "Motor"
    }

    fun worst(items: List<HealthItem>): HealthStatus =
        items.filter { it.status.rank >= 0 }.maxByOrNull { it.status.rank }?.status ?: HealthStatus.NO_DATA

    fun word(s: HealthStatus) = when (s) {
        HealthStatus.OK -> "Bien"
        HealthStatus.INFO -> "Bien"
        HealthStatus.ATTENTION -> "Atención"
        HealthStatus.CHECK -> "Revisar"
        HealthStatus.NO_DATA -> "Sin dato"
        HealthStatus.UNVERIFIED -> "No verificable"
    }

    /** Grupo de una señal para la pestaña Datos. */
    fun groupOf(pid: Int): String = when (pid) {
        0x03, 0x0A, 0x22, 0x23, 0x2F, 0x51, 0x59, 0x5E, 0x6D, 0x6E, 0x5D, 0xA2 -> "Combustible"
        0x0B, 0x0F, 0x10, 0x11, 0x33, 0x45, 0x47, 0x4C, 0x66, 0x68, 0x6A, 0x6C, 0x6F, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x87 -> "Turbo y aire"
        0x2C, 0x2D, 0x69, 0x6B -> "EGR"
        in 0x34..0x3F, 0x73, 0x78, 0x79, 0x7A, 0x7B, 0x7C, 0x83, 0x86, 0x8B, 0x9E -> "DPF y escape"
        0x05, 0x46, 0x5C, 0x67, 0x84 -> "Temperaturas"
        0x42 -> "Eléctrico"
        0x49, 0x4A, 0x4B, 0x5A, 0x8D -> "Pedal"
        0x01, 0x41, 0x1C, 0x21, 0x30, 0x31, 0x4D, 0x4E, 0x65 -> "Diagnóstico"
        else -> "Motor"
    }

    val groupOrder = listOf("Motor", "Combustible", "Turbo y aire", "EGR", "DPF y escape", "Temperaturas", "Eléctrico", "Pedal", "Diagnóstico")

    /** Valor con la unidad más legible: riel en MPa, tiempos largos en h/min. */
    fun value(v: Double?, unit: String): Pair<String, String> {
        if (v == null) return "—" to ""
        return when {
            unit == "kPa" && v >= 5000 -> "%.1f".format(v / 1000) to "MPa"
            unit == "s" && v >= 7200 -> "%.0f".format(v / 3600) to "h"
            unit == "s" && v >= 120 -> "%.0f".format(v / 60) to "min"
            unit == "min" && v >= 120 -> "%.0f".format(v / 60) to "h"
            unit == "V" -> "%.2f".format(v) to unit
            unit == "λ" || unit == "" && abs(v) < 10 && v % 1.0 != 0.0 -> "%.2f".format(v) to unit
            abs(v) >= 100 || unit in setOf("rpm", "°C", "km", "km/h", "ppm", "Nm", "min", "") -> "%.0f".format(v) to unit
            else -> "%.1f".format(v) to unit
        }
    }

    fun signalValue(s: Signal): String = s.text ?: value(s.value, s.unit).let { (a, b) -> if (b.isEmpty()) a else "$a $b" }

    /** Color por rango para los medidores en vivo. null = neutro. */
    fun gaugeStatus(key: String, v: Double?, running: Boolean): HealthStatus? {
        if (v == null) return HealthStatus.NO_DATA
        return when (key) {
            "05" -> when { v > 108 -> HealthStatus.CHECK; v > 102 -> HealthStatus.ATTENTION; v < 70 -> HealthStatus.ATTENTION; else -> HealthStatus.OK }
            "42" -> if (!running) null else when { v < 12.8 || v > 15.2 -> HealthStatus.CHECK; v < 13.3 -> HealthStatus.ATTENTION; else -> HealthStatus.OK }
            "7A.DP" -> if (!app.jac.diagnosticlab.analysis.Plausibility.dpfDifferentialOk(v)) HealthStatus.UNVERIFIED else when { v > 5 -> HealthStatus.CHECK; v > 2.5 -> HealthStatus.ATTENTION; else -> HealthStatus.OK }
            "8B.TRIG" -> when { v >= 95 -> HealthStatus.CHECK; v >= 80 -> HealthStatus.ATTENTION; else -> HealthStatus.OK }
            "78.S1" -> when { v > 850 -> HealthStatus.CHECK; v > 750 -> HealthStatus.ATTENTION; else -> null }
            else -> null
        }
    }

    /** Títulos cortos de códigos frecuentes (texto genérico SAE J2012; no es texto JAC). */
    private val dtcTitles = mapOf(
        "P0579" to "Mando de crucero / interruptor de freno",
        "P2299" to "Freno y acelerador a la vez",
        "P0087" to "Presión de riel muy baja",
        "P0088" to "Presión de riel muy alta",
        "P0093" to "Fuga de combustible",
        "P0101" to "Sensor de flujo de aire (MAF)",
        "P0102" to "MAF señal baja",
        "P0103" to "MAF señal alta",
        "P0106" to "Sensor de presión de admisión",
        "P0115" to "Sensor de temperatura del motor",
        "P0128" to "Motor no alcanza temperatura (termostato)",
        "P0190" to "Sensor de presión de riel",
        "P0201" to "Inyector 1", "P0202" to "Inyector 2", "P0203" to "Inyector 3", "P0204" to "Inyector 4",
        "P0234" to "Sobrepresión de turbo",
        "P0299" to "Falta presión de turbo",
        "P0335" to "Sensor de cigüeñal",
        "P0340" to "Sensor de árbol de levas",
        "P0380" to "Bujías incandescentes",
        "P0400" to "Flujo de EGR",
        "P0401" to "EGR insuficiente",
        "P0403" to "Circuito de válvula EGR",
        "P0471" to "Sensor de presión de escape",
        "P0500" to "Sensor de velocidad",
        "P0562" to "Tensión del sistema baja",
        "P0563" to "Tensión del sistema alta",
        "P0571" to "Interruptor de freno",
        "P0606" to "Procesador de la ECU",
        "P0670" to "Módulo de bujías incandescentes",
        "P2002" to "Eficiencia del DPF baja",
        "P2263" to "Rendimiento del turbo",
        "P242F" to "DPF con ceniza / restringido",
        "P2452" to "Sensor de presión del DPF",
        "P2453" to "Sensor de presión del DPF (rango)",
        "P2463" to "DPF saturado de hollín",
        "P2135" to "Pistas del acelerador no coinciden",
        "P2138" to "Pistas del pedal no coinciden",
    )

    fun dtcTitle(code: String): String = dtcTitles[code.uppercase()] ?: when (code.getOrNull(1)) {
        '1', '3' -> "Código propio del fabricante"
        else -> "Código genérico"
    }
}

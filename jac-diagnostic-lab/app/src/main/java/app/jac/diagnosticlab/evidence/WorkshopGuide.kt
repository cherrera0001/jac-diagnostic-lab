package app.jac.diagnosticlab.evidence

data class WorkshopStep(
    val number: Int,
    val who: String,
    val title: String,
    val detail: String,
    val control: String,
)

/**
 * Ciclo de taller: qué hacer, cómo controlar el cambio y qué medir después.
 * No prescribe piezas. No borra DTC. No prueba el corte en movimiento.
 */
object WorkshopGuide {
    val steps: List<WorkshopStep> = listOf(
        WorkshopStep(
            1, "Mecánico", "Leer sin borrar",
            "Escáner JAC + esta app. Anotar almacenados, pendientes y permanentes. No Mode 04.",
            "Comparar P0579/P2299 con el texto del escáner de marca.",
        ),
        WorkshopStep(
            2, "Mecánico", "Cuadro congelado",
            "Confirmar el DTC del freeze y los PID del momento. En esta unidad el PID 02 del freeze apuntó a P0579.",
            "Si el freeze y la lista no coinciden, llevar ambos papeles.",
        ),
        WorkshopStep(
            3, "Mecánico", "Baseline antes del ajuste",
            "Con la camioneta quieta, contactos ON: captura «Antes» en Monitor. Guarda PDF.",
            "Orden fijo: RPM → velocidad → refrigerante → carga → mariposa 0x11 → MAP → APP si existen → ATRV (solo dongle).",
        ),
        WorkshopStep(
            4, "Mecánico", "Medir freno y acelerador",
            "Interruptor de freno suelto/pisado. Dos pistas APP con multímetro. No usar 750 mV de la N56.",
            "Anotar voltajes en el PDF del taller. ATRV no sustituye la batería.",
        ),
        WorkshopStep(
            5, "Mecánico", "Ajuste o cambio",
            "Solo lo que el diagnóstico de marca indique. Esta app no dice qué pieza cambiar.",
            "Tras el trabajo: misma postura quieta, mismos PID.",
        ),
        WorkshopStep(
            6, "Mecánico", "Después: Monitor + PDF",
            "Captura «Después» y genera PDF. Compara DTC (¿siguen?), mariposa, APP, RPM y velocidad 0.",
            "Si el corte al frenar sigue: no entregar para circular.",
        ),
        WorkshopStep(
            7, "Dueño", "Decisión de circulación",
            "Esta app no certifica aptitud. Si el síntoma SAFE-001 sigue abierto, no circular.",
            "Llevar ambos PDF (antes/después) al servicio.",
        ),
    )

    fun current(doneThrough: Int): Int = (doneThrough + 1).coerceIn(1, steps.size)

    /** PID en orden de lectura del monitor vivo. */
    val monitorOrder: List<MonitorChannel> = listOf(
        MonitorChannel(0x0C, "RPM", "Régimen", "rpm", 0.0, 8000.0, GaugeKind.ARC),
        MonitorChannel(0x0D, "Velocidad", "Vehículo quieto = 0", "km/h", 0.0, 200.0, GaugeKind.ARC),
        MonitorChannel(0x05, "Refrigerante", "Temperatura motor", "°C", -40.0, 120.0, GaugeKind.THERMO),
        MonitorChannel(0x04, "Carga", "Carga calculada", "%", 0.0, 100.0, GaugeKind.BAR),
        MonitorChannel(0x11, "Mariposa", "Posición absoluta (NO es el pedal)", "%", 0.0, 100.0, GaugeKind.BAR),
        MonitorChannel(0x0B, "MAP", "Presión del colector", "kPa", 0.0, 255.0, GaugeKind.BAR),
        MonitorChannel(0x49, "APP D", "Pedal acelerador pista D", "%", 0.0, 100.0, GaugeKind.BAR),
        MonitorChannel(0x4A, "APP E", "Pedal acelerador pista E", "%", 0.0, 100.0, GaugeKind.BAR),
        MonitorChannel(0x4B, "APP F", "Pedal acelerador pista F", "%", 0.0, 100.0, GaugeKind.BAR),
        MonitorChannel(-1, "ATRV", "Solo estimación del adaptador", "V", 0.0, 20.0, GaugeKind.WARN),
    )
}

enum class GaugeKind { ARC, BAR, THERMO, WARN }

data class MonitorChannel(
    val pid: Int,
    val shortLabel: String,
    val detail: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val kind: GaugeKind,
)

data class SnapshotReading(
    val label: String,
    val atMs: Long,
    val codes: List<String>,
    val values: Map<Int, Double?>,
)

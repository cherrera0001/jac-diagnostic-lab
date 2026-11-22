package app.jac.diagnosticlab.dynamic

import app.jac.diagnosticlab.analysis.HealthStatus
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/** Una ronda de muestreo: todas las señales leídas en esa vuelta, con su instante. */
data class TraceFrame(val atMs: Long, val values: Map<String, Double>)

data class TestMetric(val name: String, val value: Double?, val unit: String, val status: HealthStatus, val note: String = "")

data class TraceEvent(val atMs: Long, val kind: String, val detail: String, val manual: Boolean = false)

data class TestResult(
    val testId: String,
    val title: String,
    val startedAtMs: Long,
    val durationMs: Long,
    val frames: Int,
    val effectiveHz: Double,
    val metrics: List<TestMetric>,
    val events: List<TraceEvent>,
    val verdict: HealthStatus,
    val summary: String,
)

enum class TestContext { STATIONARY, ROAD }

data class GuidedTest(
    val id: String,
    val title: String,
    val context: TestContext,
    val durationMs: Long?,
    /** PIDs Mode 01 a muestrear en cada ronda (se agrupan en multi-PID). */
    val pids: List<Int>,
    val safety: String,
    val steps: List<String>,
    /** Condición previa sobre la última ronda: null = puede empezar; texto = motivo para no empezar. */
    val precondition: (Map<String, Double>) -> String?,
)

/**
 * Pruebas guiadas de taller y ruta. Las estacionarias se hacen con freno de mano, neutro
 * y sin nadie delante del vehículo. La de ruta solo graba: nadie opera el teléfono al volante.
 */
object GuidedTests {
    // Claves de catálogo
    const val RPM = "0C"; const val SPEED = "0D"; const val LOAD = "04"; const val ECT = "05"; const val VOLT = "42"
    const val MAP = "0B"; const val MAF = "10"; const val PEDAL = "49"; const val PEDAL_E = "4A"; const val TORQUE = "62"; const val DEMAND = "61"
    const val RAIL_GAUGE = "23"; const val RAIL = "59"; const val RAIL_CMD = "6D.CA"; const val RAIL_ACT = "6D.RA"; const val BOOST_CMD = "70.CA"; const val BOOST_ACT = "70.RA"
    const val DPF_DP = "7A.DP"; const val REGEN = "8B.REGEN"; const val SOOT = "8B.TRIG"; const val EGT = "78.S1"; const val DPF_IN = "7C.B1I"
    const val FUEL = "5E"; const val FUEL_LEVEL = "2F"; const val OIL = "5C"; const val EGR_ERR = "69.EA"

    /** SPEC-034 R10: Perfil de Captura Rápida de Falla — Set Crítico de exactamente 8 PIDs para evitar saturar ELM327. */
    const val FAST_FAULT_ID = "FAST_FAULT"
    val CRITICAL_FAULT_PIDS: List<Int> = listOf(0x49, 0x4A, 0x04, 0x62, 0x0C, 0x0D, 0x23, 0x42)
    const val CRITICAL_FAULT_PIDS_LABEL =
        "0x49 (Pedal D), 0x4A (Pedal E), 0x04 (Carga), 0x62 (Par real), 0x0C (RPM), 0x0D (Velocidad), 0x23 (Presión riel), 0x42 (Tensión ECU)"

    private fun stationary(m: Map<String, Double>): String? {
        val s = m[SPEED]
        return if (s != null && s > 0.5) "El vehículo se mueve (${s.toInt()} km/h). Esta prueba es con el vehículo detenido." else null
    }

    private fun running(m: Map<String, Double>): String? {
        val r = m[RPM] ?: return "Sin RPM: la ECU no respondió."
        return if (r < 400) "Motor detenido. Arranque el motor." else null
    }

    val catalog: List<GuidedTest> = listOf(
        GuidedTest(
            "IDLE", "Estabilidad de ralentí (60 s)", TestContext.STATIONARY, 60_000,
            listOf(0x0C, 0x0D, 0x04, 0x05, 0x42, 0x59, 0x6D, 0x10, 0x0B, 0x5E, 0x69),
            "Freno de mano, neutro/P, sin tocar el pedal. Accesorios apagados.",
            listOf("Motor caliente si es posible (refrigerante > 70 °C).", "No tocar el acelerador durante 60 s.", "La app mide RPM, riel, aire y tensión."),
        ) { m -> stationary(m) ?: running(m) },
        GuidedTest(
            "WARMUP", "Calentamiento y termostato (hasta 10 min)", TestContext.STATIONARY, 600_000,
            listOf(0x0C, 0x0D, 0x05, 0x5C, 0x42, 0x0F, 0x46, 0x67),
            "Arrancar en frío. Freno de mano, motor en ralentí. Nunca en un lugar cerrado.",
            listOf("Arrancar con el motor frío (refrigerante < 50 °C ideal).", "Dejar en ralentí; la app grafica la subida.", "Se detiene sola a los 10 min o al llegar a 85 °C."),
        ) { m -> stationary(m) ?: running(m) },
        GuidedTest(
            "SNAP", "Aceleración libre (respuesta riel/turbo)", TestContext.STATIONARY, 40_000,
            listOf(0x0C, 0x0D, 0x49, 0x61, 0x62, 0x59, 0x6D, 0x70, 0x0B, 0x10),
            "Freno de mano, neutro, motor caliente. Nadie delante ni detrás. No mantener a fondo más de 2 s.",
            listOf("Con el motor caliente, en neutro y con freno de mano.", "Cuando la app lo pida, pisar a fondo de golpe y soltar al llegar al corte (≤ 2 s).", "Repetir 3 veces, con 8 s de pausa entre cada una."),
        ) { m -> stationary(m) ?: running(m) ?: m[ECT]?.let { if (it < 60) "Motor frío (${it.toInt()} °C). Esperar a > 60 °C para no forzarlo." else null } },
        GuidedTest(
            "ELECTRIC", "Carga eléctrica y alternador (60 s)", TestContext.STATIONARY, 60_000,
            listOf(0x0C, 0x0D, 0x42, 0x04),
            "Freno de mano, motor en ralentí.",
            listOf("Primeros 15 s: todo apagado.", "Luego encender luces altas, ventilador al máximo, desempañador y A/C.", "Mantener hasta el final; la app mide la caída y la recuperación."),
        ) { m -> stationary(m) ?: running(m) },
        GuidedTest(
            "BRAKE", "Freno contra acelerador (P2299 / P0579)", TestContext.STATIONARY, 60_000,
            listOf(0x0C, 0x49, 0x4A, 0x61, 0x62, 0x04, 0x0D, 0x11),
            "Detenido, freno de mano puesto, NEUTRO. Nadie delante. Solo acelerador suave (≤ 1800 rpm).",
            listOf(
                "Pisar el acelerador suave hasta ~1500 rpm y mantenerlo.",
                "Sin soltar el acelerador, pisar el freno firme 3 s.",
                "Soltar el freno y mantener el acelerador 3 s más. Soltar todo 5 s.",
                "Repetir 3 veces. La app mide si la ECU corta al detectar el freno.",
            ),
        ) { m -> stationary(m) ?: running(m) },
        GuidedTest(
            "ROAD", "Registro en ruta / banco de rodillos", TestContext.ROAD, null,
            listOf(0x0C, 0x0D, 0x04, 0x49, 0x61, 0x62, 0x42, 0x05, 0x70, 0x6D, 0x59, 0x7A, 0x8B, 0x78, 0x5E, 0x2F, 0x0B, 0x10),
            "Solo con un acompañante que opere el teléfono, o en banco de rodillos. El conductor no toca el teléfono. " +
                "Si el vehículo tiene el corte al frenar (SAFE-001) abierto, NO salir a la calle: usar banco o pista cerrada.",
            listOf("Iniciar la grabación con el vehículo detenido.", "Conducir normalmente; la app detecta eventos sola.", "El acompañante pulsa «Marcar evento» si nota algo (tirón, corte, ruido).", "Detener la grabación con el vehículo detenido."),
        ) { _ -> null },
        GuidedTest(
            FAST_FAULT_ID, "Captura rápida de falla (8 PIDs críticos)", TestContext.ROAD, null,
            CRITICAL_FAULT_PIDS,
            "Perfil ligero (8 PIDs) diseñado para no saturar adaptadores ELM327 y prevenir pérdida de enlace. " +
                "Con SAFE-001 abierto, usar solo con acompañante o en banco/pista cerrada.",
            listOf(
                "Consulta únicamente los 8 PIDs críticos: $CRITICAL_FAULT_PIDS_LABEL.",
                "Permite distinguir corte de par (pedal/carga/torque) frente a apagado total de motor (presión de riel 0x23 o tensión 0x42).",
                "Mantiene captura continua en segundo plano y permite «Marcar evento» al instante.",
            ),
        ) { _ -> null },
    )

    /** Perfil de acceso rápido al set crítico de 8 PIDs (SPEC-034 R10). */
    val fastFault: GuidedTest get() = catalog.first { it.id == FAST_FAULT_ID }

    /** Vista en vivo: muestreo continuo sin veredicto (no aparece en la lista de pruebas). */
    val live = GuidedTest(
        "LIVE", "En vivo", TestContext.STATIONARY, null,
        listOf(0x0C, 0x0D, 0x05, 0x42, 0x04, 0x0B, 0x70, 0x6D, 0x59, 0x7A, 0x8B, 0x78, 0x10, 0x5E, 0x2F, 0x49, 0x62),
        "", emptyList(),
    ) { _ -> null }

    fun byId(id: String) = when (id) {
        "LIVE" -> live
        FAST_FAULT_ID -> fastFault
        else -> catalog.first { it.id == id }
    }

    /* ───────────── utilidades estadísticas ───────────── */

    private fun series(frames: List<TraceFrame>, key: String) = frames.mapNotNull { f -> f.values[key]?.let { f.atMs to it } }
    private fun vals(frames: List<TraceFrame>, key: String) = frames.mapNotNull { it.values[key] }
    private fun mean(x: List<Double>) = if (x.isEmpty()) null else x.average()
    private fun std(x: List<Double>): Double? {
        if (x.size < 2) return null
        val m = x.average()
        return sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1))
    }
    private fun hz(frames: List<TraceFrame>): Double {
        if (frames.size < 2) return 0.0
        val w = frames.last().atMs - frames.first().atMs
        return if (w <= 0) 0.0 else (frames.size - 1) * 1000.0 / w
    }
    private fun worst(ms: List<TestMetric>, ev: List<TraceEvent> = emptyList()): HealthStatus {
        val m = ms.filter { it.status != HealthStatus.NO_DATA }.maxByOrNull { it.status.rank }?.status ?: HealthStatus.NO_DATA
        val e = if (ev.any { !it.manual && (it.kind.startsWith("CORTE") || it.kind == "MOTOR DETENIDO" || it.kind.startsWith("CAÍDA BRUSCA")) }) HealthStatus.CHECK else HealthStatus.OK
        return if (e.rank > m.rank) e else m
    }
    private fun st(bad: Boolean, warn: Boolean) = when { bad -> HealthStatus.CHECK; warn -> HealthStatus.ATTENTION; else -> HealthStatus.OK }

    /* ───────────── análisis por prueba ───────────── */

    fun analyze(test: GuidedTest, frames: List<TraceFrame>, manualEvents: List<TraceEvent> = emptyList()): TestResult {
        val start = frames.firstOrNull()?.atMs ?: 0L
        val dur = if (frames.size < 2) 0L else frames.last().atMs - start
        val (metrics, events, summary) = when (test.id) {
            "IDLE" -> idle(frames)
            "WARMUP" -> warmup(frames)
            "SNAP" -> snap(frames)
            "ELECTRIC" -> electric(frames)
            "BRAKE" -> brake(frames)
            else -> road(frames)
        }
        val allEvents = (events + manualEvents).sortedBy { it.atMs }
        val verdict = if (frames.size < 5) HealthStatus.NO_DATA else worst(metrics, allEvents)
        return TestResult(test.id, test.title, start, dur, frames.size, hz(frames), metrics, allEvents, verdict,
            if (frames.size < 5) "Muy pocas muestras (${frames.size}). Repetir con el enlace estable." else summary)
    }

    /**
     * P2299 = freno y acelerador incompatibles; P0579 en muchos diésel es la plausibilidad del
     * interruptor de freno de doble contacto. El ELM327 no puede leer el interruptor (dato
     * propietario), pero sí ver si la ECU corta el par al pisar el freno con el acelerador pisado.
     */
    private fun brake(fr: List<TraceFrame>): Triple<List<TestMetric>, List<TraceEvent>, String> {
        val events = mutableListOf<TraceEvent>()
        val d = vals(fr, PEDAL)
        val rest = d.minOrNull()
        var held = 0
        var cuts = 0
        var inHold = false
        var peak = 0.0
        for (f in fr) {
            val p = f.values[PEDAL] ?: continue
            val r = f.values[RPM] ?: continue
            val pressed = rest != null && p > rest + 6
            if (pressed && !inHold) { inHold = true; held++; peak = r }
            if (pressed && inHold) {
                if (r > peak) peak = r
                if (peak > 1250 && r < peak - 350) {
                    cuts++
                    events += TraceEvent(f.atMs, "CORTE POR FRENO", "RPM %.0f → %.0f con el acelerador aún pisado (%.0f %%)".format(Locale.ROOT, peak, r, p))
                    peak = r
                }
            }
            if (!pressed) inHold = false
        }
        val e = vals(fr, "4A")
        val ratios = fr.mapNotNull { f -> val a = f.values[PEDAL]; val b = f.values["4A"]; if (a != null && b != null && b > 1) a / b else null }
        val rm = mean(ratios); val rs = std(ratios)
        val ratioBad = rm != null && rs != null && rm > 0 && rs / rm > 0.12
        val metrics = listOf(
            TestMetric("Aceleraciones mantenidas", held.toDouble(), "", if (held == 0) HealthStatus.NO_DATA else HealthStatus.INFO, "Se esperaban 3."),
            TestMetric("Cortes de par al frenar", cuts.toDouble(), "", when { held == 0 -> HealthStatus.NO_DATA; cuts == 0 -> HealthStatus.ATTENTION; else -> HealthStatus.OK },
                "Observable: RPM cae bajo acelerador mantenido. Es una inferencia, no mide el interruptor de freno."),
            TestMetric("Pedal pista D en reposo", rest, "%", if (rest == null) HealthStatus.NO_DATA else HealthStatus.INFO),
            TestMetric("Relación pistas D/E", rm, "", if (rm == null) HealthStatus.NO_DATA else st(ratioBad, false),
                if (rs != null && rm != null) "Variación %.0f %%. Las dos pistas deben moverse en proporción fija.".format(Locale.ROOT, rs / rm * 100) else ""),
            TestMetric("Pista E mínima", e.minOrNull(), "%", if (e.isEmpty()) HealthStatus.NO_DATA else HealthStatus.INFO),
        )
        val summary = when {
            held == 0 -> "No se detectó acelerador mantenido. Repetir siguiendo los pasos."
            cuts > 0 -> "Observable: la RPM cayó $cuts vez/veces al frenar con el acelerador pisado. Inferencia (no confirmada): la ECU pudo reaccionar a una señal de freno; también explican la caída el corte por plausibilidad APP, el soltar el pedal o una estrategia de la calibración. Falta medir: estado eléctrico de AMBOS contactos del interruptor de freno (equipo externo) y leer DTC/freeze frame. Con P0579/P2299 priorizar el protocolo estacionario."
            else -> "Observable: no hubo caída de RPM al frenar en $held intento(s). No demuestra que la ECU no reciba el freno: la calibración puede no cortar en esta condición. Falta medir: ambos contactos del interruptor de freno con multímetro/osciloscopio y que enciendan las luces de freno."
        }
        return Triple(metrics, events, summary + if (ratioBad) " Las pistas del pedal no se mueven en proporción: revisar el pedal." else "")
    }

    private fun idle(fr: List<TraceFrame>): Triple<List<TestMetric>, List<TraceEvent>, String> {
        val rpm = vals(fr, RPM)
        val sd = std(rpm); val m = mean(rpm)
        val span = if (rpm.isEmpty()) null else rpm.max() - rpm.min()
        val rail = vals(fr, RAIL_ACT).ifEmpty { vals(fr, RAIL) }
        val railSd = std(rail)?.div(1000)
        val railCmd = vals(fr, RAIL_CMD)
        val railDev = if (rail.isNotEmpty() && railCmd.isNotEmpty()) (rail.average() - railCmd.average()) / railCmd.average() * 100 else null
        val volt = vals(fr, VOLT)
        val maf = vals(fr, MAF)
        val egr = vals(fr, EGR_ERR)
        val metrics = listOf(
            TestMetric("RPM media", m, "rpm", if (m == null) HealthStatus.NO_DATA else st(m < 600 || m > 1050, m < 680 || m > 950), "Ref. genérica diésel caliente 700–900 rpm."),
            TestMetric("RPM desviación estándar", sd, "rpm", if (sd == null) HealthStatus.NO_DATA else st(sd > 50, sd > 25), "Ralentí estable < 25 rpm. Alto = inyector, aire o compresión."),
            TestMetric("RPM rango (máx−mín)", span, "rpm", if (span == null) HealthStatus.NO_DATA else st(span > 200, span > 100)),
            TestMetric("Riel media", mean(rail)?.div(1000), "MPa", if (rail.isEmpty()) HealthStatus.NO_DATA else HealthStatus.INFO),
            TestMetric("Riel desviación estándar", railSd, "MPa", if (railSd == null) HealthStatus.NO_DATA else st(railSd > 3, railSd > 1.5), "Oscilación alta = regulador, bomba o aire en el combustible."),
            TestMetric("Riel real vs comandada", railDev, "%", if (railDev == null) HealthStatus.NO_DATA else st(abs(railDev) > 20, abs(railDev) > 10)),
            TestMetric("Tensión media", mean(volt), "V", if (volt.isEmpty()) HealthStatus.NO_DATA else st(volt.average() < 13.0 || volt.average() > 15.0, volt.average() < 13.4)),
            TestMetric("MAF media", mean(maf), "g/s", if (maf.isEmpty()) HealthStatus.NO_DATA else HealthStatus.INFO, "Comparar con la misma unidad sana: depende de EGR."),
            TestMetric("Error EGR medio", mean(egr), "%", if (egr.isEmpty()) HealthStatus.NO_DATA else st(abs(egr.average()) > 25, abs(egr.average()) > 12)),
        )
        val s = "Ralentí ${m?.let { "%.0f".format(it) } ?: "—"} rpm ± ${sd?.let { "%.0f".format(it) } ?: "—"}; riel ± ${railSd?.let { "%.2f".format(it) } ?: "—"} MPa."
        return Triple(metrics, emptyList(), s)
    }

    private fun warmup(fr: List<TraceFrame>): Triple<List<TestMetric>, List<TraceEvent>, String> {
        val ect = series(fr, ECT)
        if (ect.size < 2) return Triple(listOf(TestMetric("Refrigerante", null, "°C", HealthStatus.NO_DATA)), emptyList(), "Sin refrigerante.")
        val first = ect.first(); val last = ect.last()
        val minutes = (last.first - first.first) / 60_000.0
        val rate = if (minutes > 0) (last.second - first.second) / minutes else null
        val reached70 = ect.firstOrNull { it.second >= 70 }?.let { (it.first - first.first) / 60_000.0 }
        // Termostato: si abre, la curva se aplana cerca de 80–90 °C. Si sube lento desde el inicio, puede estar abierto.
        val stalled = minutes >= 5 && last.second < 65
        val metrics = listOf(
            TestMetric("Temperatura inicial", first.second, "°C", HealthStatus.INFO),
            TestMetric("Temperatura final", last.second, "°C", if (last.second > 108) HealthStatus.CHECK else HealthStatus.INFO),
            TestMetric("Ritmo de subida", rate, "°C/min", if (rate == null) HealthStatus.NO_DATA else st(stalled, rate < 2.0 && last.second < 70), "En ralentí diésel la subida es lenta; < 2 °C/min con motor frío sugiere termostato abierto."),
            TestMetric("Minutos hasta 70 °C", reached70, "min", if (reached70 == null) (if (stalled) HealthStatus.ATTENTION else HealthStatus.NO_DATA) else st(false, reached70 > 12)),
            TestMetric("Tensión media", mean(vals(fr, VOLT)), "V", HealthStatus.INFO),
        )
        return Triple(metrics, emptyList(), "De %.0f a %.0f °C en %.1f min.".format(first.second, last.second, minutes))
    }

    private fun snap(fr: List<TraceFrame>): Triple<List<TestMetric>, List<TraceEvent>, String> {
        val rpm = series(fr, RPM)
        val events = mutableListOf<TraceEvent>()
        // Detectar golpes de acelerador: pedal (o RPM) sube rápido.
        val pedal = series(fr, PEDAL)
        var pulls = 0
        val riseTimes = mutableListOf<Double>()
        var i = 0
        while (i < rpm.size - 1) {
            if (rpm[i].second < 1200) {
                val j = (i + 1 until rpm.size).firstOrNull { rpm[it].second >= 2500 && rpm[it].first - rpm[i].first <= 3000 }
                if (j != null) {
                    pulls++
                    riseTimes += (rpm[j].first - rpm[i].first) / 1000.0
                    events += TraceEvent(rpm[i].first, "ACELERACIÓN", "Subida a 2500 rpm en %.2f s".format(riseTimes.last()))
                    i = j
                }
            }
            i++
        }
        val peakRpm = rpm.maxOfOrNull { it.second }
        val boostAct = vals(fr, BOOST_ACT); val boostCmd = vals(fr, BOOST_CMD)
        val mapPeak = vals(fr, MAP).maxOrNull()
        val railPairs = fr.mapNotNull { f -> val a = f.values[RAIL_ACT]; val c = f.values[RAIL_CMD]; if (a != null && c != null && c > 0) (a - c) / c * 100 else null }
        val railWorst = railPairs.maxByOrNull { abs(it) }
        val boostWorst = fr.mapNotNull { f -> val a = f.values[BOOST_ACT]; val c = f.values[BOOST_CMD]; if (a != null && c != null) a - c else null }.minOrNull()
        val metrics = listOf(
            TestMetric("Aceleraciones detectadas", pulls.toDouble(), "", if (pulls == 0) HealthStatus.NO_DATA else HealthStatus.INFO, "Se esperaban 3."),
            TestMetric("Tiempo medio a 2500 rpm", mean(riseTimes), "s", if (riseTimes.isEmpty()) HealthStatus.NO_DATA else st(riseTimes.average() > 2.0, riseTimes.average() > 1.2), "Lento = falta de combustible/aire. Comparar antes/después."),
            TestMetric("RPM pico", peakRpm, "rpm", if (peakRpm == null) HealthStatus.NO_DATA else HealthStatus.INFO),
            TestMetric("Presión admisión pico", mapPeak ?: boostAct.maxOrNull(), "kPa", HealthStatus.INFO, "Sin carga el turbo casi no sopla: sirve para comparar."),
            TestMetric("Peor desvío del riel", railWorst, "%", if (railWorst == null) HealthStatus.NO_DATA else st(abs(railWorst) > 25, abs(railWorst) > 15), "Transitorio: la bomba debe seguir lo comandado."),
            TestMetric("Peor falta de turbo", boostWorst, "kPa", if (boostWorst == null || boostCmd.isEmpty()) HealthStatus.NO_DATA else st(boostWorst < -40, boostWorst < -20)),
            TestMetric("Pedal máximo", pedal.maxOfOrNull { it.second }, "%", HealthStatus.INFO),
        )
        return Triple(metrics, events, "$pulls aceleración(es); a 2500 rpm en ${mean(riseTimes)?.let { "%.2f".format(it) } ?: "—"} s.")
    }

    private fun electric(fr: List<TraceFrame>): Triple<List<TestMetric>, List<TraceEvent>, String> {
        val v = series(fr, VOLT)
        if (v.size < 3) return Triple(listOf(TestMetric("Tensión", null, "V", HealthStatus.NO_DATA)), emptyList(), "Sin PID 42.")
        val t0 = v.first().first
        val base = v.filter { it.first - t0 < 15_000 }.map { it.second }
        val loaded = v.filter { it.first - t0 >= 25_000 }.map { it.second }
        val minV = v.minOf { it.second }
        val b = mean(base); val l = mean(loaded)
        val drop = if (b != null && l != null) b - l else null
        val metrics = listOf(
            TestMetric("Tensión sin carga", b, "V", if (b == null) HealthStatus.NO_DATA else st(b < 13.0 || b > 15.0, b < 13.4)),
            TestMetric("Tensión con carga", l, "V", if (l == null) HealthStatus.NO_DATA else st(l < 12.8, l < 13.2), "Con consumidores el alternador debe sostener > 13,2 V."),
            TestMetric("Caída por carga", drop, "V", if (drop == null) HealthStatus.NO_DATA else st(drop > 1.0, drop > 0.5)),
            TestMetric("Mínimo registrado", minV, "V", st(minV < 12.5, minV < 13.0)),
        )
        return Triple(metrics, emptyList(), "Sin carga ${b?.let { "%.2f".format(it) } ?: "—"} V → con carga ${l?.let { "%.2f".format(it) } ?: "—"} V.")
    }

    /**
     * Ruta: estadísticas y detección automática de eventos.
     * CORTE DE POTENCIA: pedal ≥ 20 % sostenido y el torque real cae ≥ 30 puntos en ≤ 1,5 s con velocidad > 5 km/h.
     */
    fun road(fr: List<TraceFrame>): Triple<List<TestMetric>, List<TraceEvent>, String> {
        val events = mutableListOf<TraceEvent>()
        var distanceKm = 0.0
        for (k in 1 until fr.size) {
            val a = fr[k - 1]; val b = fr[k]
            val sa = a.values[SPEED]; val sb = b.values[SPEED]
            if (sa != null && sb != null) distanceKm += (sa + sb) / 2.0 * (b.atMs - a.atMs) / 3_600_000.0
        }
        var lastCut: Long? = null
        var regen: Boolean? = null
        var lowVoltAt: Long? = null
        var underboostSince: Long? = null
        for (k in fr.indices) {
            val f = fr[k]
            val pedal = f.values[PEDAL]; val tq = f.values[TORQUE]; val spd = f.values[SPEED]
            if (pedal != null && tq != null && spd != null && spd > 5 && pedal >= 20 && (lastCut == null || f.atMs - lastCut!! > 5_000)) {
                val prior = fr.subList(0, k).asReversed().takeWhile { f.atMs - it.atMs <= 1_500 }
                val maxTq = prior.mapNotNull { it.values[TORQUE] }.maxOrNull()
                val pedalHeld = prior.mapNotNull { it.values[PEDAL] }.all { it >= 15 }
                if (maxTq != null && maxTq - tq >= 30 && pedalHeld) {
                    lastCut = f.atMs
                    events += TraceEvent(f.atMs, "CORTE DE POTENCIA", "Torque %.0f→%.0f %% con pedal %.0f %% a %.0f km/h (posible SAFE-001)".format(maxTq, tq, pedal, spd))
                }
            }
            val v = f.values[VOLT]
            if (v != null && v < 11.8 && (lowVoltAt == null || f.atMs - lowVoltAt > 10_000)) { lowVoltAt = f.atMs; events += TraceEvent(f.atMs, "CAÍDA DE TENSIÓN", "%.2f V".format(Locale.ROOT, v)) }
            val rail23 = f.values[RAIL_GAUGE]
            val rpmNow = f.values[RPM]
            if (rail23 != null && rpmNow != null && rpmNow >= 500.0 && rail23 < 15_000.0 &&
                events.none { it.kind == "CAÍDA PRESIÓN RIEL" && f.atMs - it.atMs < 10_000 }
            ) {
                events += TraceEvent(f.atMs, "CAÍDA PRESIÓN RIEL", "PID 0x23 %.0f kPa (%.1f MPa) a %.0f rpm".format(Locale.ROOT, rail23, rail23 / 1000.0, rpmNow))
            }
            val r = f.values[REGEN]?.let { it >= 1 }
            if (r != null && regen != null && r != regen) events += TraceEvent(f.atMs, if (r) "INICIO REGENERACIÓN DPF" else "FIN REGENERACIÓN DPF", "Hollín %s %%".format(f.values[SOOT]?.let { "%.0f".format(it) } ?: "—"))
            if (r != null) regen = r
            val bc = f.values[BOOST_CMD]; val ba = f.values[BOOST_ACT]
            if (bc != null && ba != null && bc - ba > 30) {
                if (underboostSince == null) underboostSince = f.atMs
                else if (f.atMs - underboostSince > 2_000 && events.none { it.kind == "FALTA DE TURBO" && f.atMs - it.atMs < 15_000 }) {
                    events += TraceEvent(f.atMs, "FALTA DE TURBO", "Comandado %.0f kPa, real %.0f kPa por > 2 s".format(bc, ba))
                }
            } else underboostSince = null
            val ect = f.values[ECT]
            if (ect != null && ect > 108 && events.none { it.kind == "SOBRETEMPERATURA" && f.atMs - it.atMs < 60_000 }) events += TraceEvent(f.atMs, "SOBRETEMPERATURA", "Refrigerante %.0f °C".format(ect))
        }
        val forensic = EventForensics.assess(fr)
        forensic.events.forEach { e ->
            events += TraceEvent(
                e.atMs, e.kind.label,
                "RPM %.0f→%.0f, vel %s km/h, pedal D %s %%, tensión mín %s V%s".format(
                    Locale.ROOT, e.rpmBefore, e.rpmAfter, e.speedAt?.let { "%.0f".format(Locale.ROOT, it) } ?: "—",
                    e.pedalD?.let { "%.0f".format(Locale.ROOT, it) } ?: "—", e.voltMin?.let { "%.1f".format(Locale.ROOT, it) } ?: "—",
                    if (e.decelerating || e.stopped) " (frenando/detenido, inferido)" else ""),
            )
        }
        events.sortBy { it.atMs }
        val spd = vals(fr, SPEED)
        val cuts = events.count { it.kind == "CORTE DE POTENCIA" }
        val metrics = listOf(
            TestMetric("Distancia estimada", distanceKm, "km", HealthStatus.INFO, "Integrada de la velocidad OBD."),
            TestMetric("Velocidad máxima", spd.maxOrNull(), "km/h", HealthStatus.INFO),
            TestMetric("Turbo real máximo", vals(fr, BOOST_ACT).maxOrNull() ?: vals(fr, MAP).maxOrNull(), "kPa", HealthStatus.INFO),
            TestMetric("Torque real máximo", vals(fr, TORQUE).maxOrNull(), "%", HealthStatus.INFO),
            TestMetric("EGT máxima", vals(fr, EGT).maxOrNull(), "°C", vals(fr, EGT).maxOrNull()?.let { st(it > 850, it > 750) } ?: HealthStatus.NO_DATA),
            TestMetric("Refrigerante máximo", vals(fr, ECT).maxOrNull(), "°C", vals(fr, ECT).maxOrNull()?.let { st(it > 108, it > 102) } ?: HealthStatus.NO_DATA),
            TestMetric("Tensión mínima", vals(fr, VOLT).minOrNull(), "V", vals(fr, VOLT).minOrNull()?.let { st(it < 11.8, it < 12.8) } ?: HealthStatus.NO_DATA),
            TestMetric("Presión riel mínima (0x23)", vals(fr, RAIL_GAUGE).minOrNull(), "kPa", vals(fr, RAIL_GAUGE).minOrNull()?.let { st(it < 15_000.0, it < 20_000.0) } ?: HealthStatus.NO_DATA),
            TestMetric("Cortes de potencia detectados", cuts.toDouble(), "", if (cuts > 0) HealthStatus.CHECK else HealthStatus.OK),
            TestMetric("ΔP DPF máxima", vals(fr, DPF_DP).maxOrNull(), "kPa", HealthStatus.INFO, "Bajo carga sube; comparar entre recorridos."),
            TestMetric("Consumo medio", mean(vals(fr, FUEL)), "L/h", HealthStatus.INFO),
        )
        val hyp = forensic.hypotheses.joinToString(" ") { "[${it.support.label}] ${it.name}: ${it.evidence} Medir: ${it.nextMeasurement}" }
        return Triple(
            metrics + TestMetric("Paros / caídas de RPM", forensic.events.size.toDouble(), "", if (forensic.events.isEmpty()) HealthStatus.OK else HealthStatus.CHECK, forensic.summary),
            events,
            "%.1f km, %d evento(s) automáticos. %s %s".format(Locale.ROOT, distanceKm, events.size, forensic.summary, hyp),
        )
    }

    /** CSV ancho: una fila por ronda, una columna por señal. Para Excel / MATLAB / el taller. */
    fun toCsv(frames: List<TraceFrame>, events: List<TraceEvent>): String {
        val keys = frames.flatMap { it.values.keys }.distinct().sorted()
        val sb = StringBuilder("t_ms,t_rel_s,").append(keys.joinToString(",")).append(",evento\n")
        val t0 = frames.firstOrNull()?.atMs ?: 0L
        val evByFrame = events.groupBy { e -> frames.minByOrNull { abs(it.atMs - e.atMs) }?.atMs }
        for (f in frames) {
            sb.append(f.atMs).append(',').append(String.format(Locale.US, "%.3f", (f.atMs - t0) / 1000.0)).append(',')
            sb.append(keys.joinToString(",") { k -> f.values[k]?.let { String.format(Locale.US, "%.4f", it) } ?: "" })
            sb.append(',').append(evByFrame[f.atMs]?.joinToString(" | ") { "${it.kind}: ${it.detail}".replace(",", ";") }.orEmpty()).append('\n')
        }
        return sb.toString()
    }
}

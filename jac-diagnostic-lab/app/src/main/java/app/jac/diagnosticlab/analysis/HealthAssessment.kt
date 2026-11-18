package app.jac.diagnosticlab.analysis

import app.jac.diagnosticlab.obd.Mode06Result
import app.jac.diagnosticlab.obd.MonitorState
import app.jac.diagnosticlab.obd.Readiness
import app.jac.diagnosticlab.obd.VehicleInfo
import kotlin.math.abs

enum class HealthStatus(val label: String, val rank: Int) {
    OK("OK", 0), INFO("INFO", 1), ATTENTION("ATENCIÓN", 2), CHECK("REVISAR", 3), NO_DATA("SIN DATO", -1)
}

data class HealthItem(
    val system: String,
    val status: HealthStatus,
    val title: String,
    val detail: String,
    val evidence: String,
)

data class HealthInput(
    /** Señales del escaneo completo por clave de catálogo ("0C", "70.RA", "8B.TRIG"…). */
    val values: Map<String, Double?>,
    val texts: Map<String, String> = emptyMap(),
    val readiness: Readiness? = null,
    val readinessThisCycle: Readiness? = null,
    val storedDtcs: List<String> = emptyList(),
    val pendingDtcs: List<String> = emptyList(),
    val mode06: List<Mode06Result> = emptyList(),
    val vehicleInfo: VehicleInfo? = null,
    val adapterVoltage: Double? = null,
)

/**
 * Evaluación de salud con reglas genéricas (J1979 + práctica de taller diésel common-rail).
 * No son tolerancias del fabricante. Cada ítem cita el dato que lo motivó.
 */
object HealthAssessment {
    const val BASIS = "Rangos genéricos de referencia, no especificación JAC. Confirmar con escáner de marca."

    fun evaluate(input: HealthInput): List<HealthItem> {
        val v = input.values
        fun g(k: String) = v[k]
        val rpm = g("0C")
        val speed = g("0D")
        val running = rpm != null && rpm > 400
        val idling = running && (speed == null || speed < 1) && rpm!! < 1100
        val diesel = input.readiness?.compressionIgnition == true || input.texts["51"]?.contains("Diésel") == true
        val items = mutableListOf<HealthItem>()
        fun add(system: String, status: HealthStatus, title: String, detail: String, evidence: String) {
            items += HealthItem(system, status, title, detail, evidence)
        }
        fun f(x: Double?, d: Int = 1) = x?.let { "%.${d}f".format(it) } ?: "—"

        // ── Códigos y MIL
        val r = input.readiness
        when {
            input.storedDtcs.isNotEmpty() -> add("Códigos", HealthStatus.CHECK, "Códigos almacenados: ${input.storedDtcs.joinToString()}",
                "La ECU guardó fallas confirmadas. Leer sin borrar con escáner de marca.", "Mode 03")
            r?.dtcCount != null && r.dtcCount > 0 -> add("Códigos", HealthStatus.CHECK, "La ECU informa ${r.dtcCount} código(s)", "Mode 03 no los entregó; repetir lectura.", "0101 A=${r.dtcCount}")
            else -> add("Códigos", HealthStatus.OK, "Sin códigos almacenados", "Mode 03 respondió sin códigos.", "Mode 03")
        }
        if (input.pendingDtcs.isNotEmpty()) {
            add("Códigos", HealthStatus.ATTENTION, "Pendientes: ${input.pendingDtcs.joinToString()}",
                "Falla detectada en este ciclo o el anterior, aún no confirmada. Puede volver a aparecer.", "Mode 07")
        }
        if (r?.milOn == true) add("Códigos", HealthStatus.CHECK, "Luz de falla (MIL) encendida", "La ECU ordena la MIL.", "0101 bit 7")
        g("21")?.let { if (it > 0) add("Códigos", HealthStatus.ATTENTION, "Recorrió ${f(it, 0)} km con la MIL encendida", "Distancia acumulada con falla activa.", "PID 21") }

        // ── Preparación y borrado reciente
        if (r != null) {
            val inc = r.incomplete
            if (inc.isEmpty()) {
                add("Monitores", HealthStatus.OK, "Monitores completos (${r.supported.size} soportados)", "Todas las autopruebas soportadas terminaron desde el último borrado.", "0101 C/D")
            } else {
                add("Monitores", HealthStatus.ATTENTION, "Monitores incompletos: ${inc.joinToString { it.name }}",
                    "Faltan autopruebas. Ocurre tras borrar códigos o desconectar batería; requiere ciclos de manejo.", "0101 C/D")
            }
        }
        val km = g("31"); val warm = g("30"); val mins = g("4E")
        if (km != null || warm != null) {
            val recent = (km != null && km < 100) || (warm != null && warm < 10)
            add("Monitores", if (recent) HealthStatus.ATTENTION else HealthStatus.INFO,
                if (recent) "Códigos borrados hace poco" else "Último borrado de códigos",
                "Desde el último borrado: ${f(km, 0)} km, ${f(warm, 0)} calentamientos, ${f(mins, 0)} min de motor. " +
                    if (recent) "Un borrado reciente puede ocultar fallas: preguntar quién y por qué." else "",
                "PID 31/30/4E")
        }

        // ── Eléctrico
        val volt = g("42")
        if (volt == null) {
            add("Eléctrico", HealthStatus.NO_DATA, "Tensión del módulo", "PID 42 no leído.", "—")
        } else if (running) {
            val st = when { volt < 13.0 -> HealthStatus.CHECK; volt < 13.4 -> HealthStatus.ATTENTION; volt > 15.0 -> HealthStatus.CHECK; else -> HealthStatus.OK }
            add("Eléctrico", st, "Carga con motor en marcha: ${f(volt, 2)} V",
                when (st) { HealthStatus.OK -> "Alternador cargando (13,4–15,0 V)."; else -> if (volt > 15.0) "Sobretensión: revisar regulador." else "Carga baja: revisar alternador, correa y masas." }, "PID 42")
        } else {
            val st = if (volt < 12.2) HealthStatus.ATTENTION else HealthStatus.OK
            add("Eléctrico", st, "Batería con motor detenido: ${f(volt, 2)} V", if (st == HealthStatus.OK) "Tensión de reposo normal." else "Batería baja o descargándose.", "PID 42")
        }
        val atrv = input.adapterVoltage
        if (atrv != null && volt != null && abs(atrv - volt) > 0.8) {
            add("Eléctrico", HealthStatus.INFO, "El adaptador mide ${f(atrv)} V; la ECU ${f(volt, 2)} V",
                "ATRV del dongle descalibrado en ${f(abs(atrv - volt))} V. Usar PID 42 o multímetro.", "ATRV vs PID 42")
        }

        // ── Refrigeración / lubricación
        val ect = g("05")
        val runtime = g("1F")
        if (ect != null) {
            when {
                ect > 108 -> add("Refrigeración", HealthStatus.CHECK, "Refrigerante alto: ${f(ect, 0)} °C", "Sobre 108 °C: riesgo de sobrecalentamiento.", "PID 05")
                running && runtime != null && runtime > 900 && ect < 65 ->
                    add("Refrigeración", HealthStatus.ATTENTION, "Motor no calienta: ${f(ect, 0)} °C tras ${f(runtime / 60, 0)} min",
                        "Posible termostato abierto o sensor desviado. Confirmar con prueba de calentamiento.", "PID 05 + 1F")
                else -> add("Refrigeración", HealthStatus.OK, "Refrigerante ${f(ect, 0)} °C", if (ect < 70) "Motor aún frío." else "Temperatura de trabajo.", "PID 05")
            }
        }
        val e1 = g("67.1"); val e2 = g("67.2")
        if (e1 != null && e2 != null && abs(e1 - e2) > 10) {
            add("Refrigeración", HealthStatus.ATTENTION, "Sensores de refrigerante discrepan", "S1 ${f(e1, 0)} °C vs S2 ${f(e2, 0)} °C.", "PID 67")
        }
        g("5C")?.let { if (it > 125) add("Lubricación", HealthStatus.CHECK, "Aceite muy caliente: ${f(it, 0)} °C", "Sobre 125 °C.", "PID 5C") else add("Lubricación", HealthStatus.OK, "Aceite ${f(it, 0)} °C", "", "PID 5C") }

        // ── Admisión / sensores en reposo
        val map = g("0B") ?: g("87.A")
        val baro = g("33")
        if (!running && map != null && baro != null) {
            val d = abs(map - baro)
            add("Admisión", if (d > 6) HealthStatus.ATTENTION else HealthStatus.OK, "MAP vs barométrica con motor detenido",
                "Diferencia ${f(d)} kPa (MAP ${f(map, 0)}, baro ${f(baro, 0)}). " + if (d > 6) "Sensor MAP desviado o con fuga." else "Coinciden.", "PID 0B/33")
        }
        if (idling && map != null && baro != null && diesel) {
            val d = map - baro
            add("Admisión", if (abs(d) > 15) HealthStatus.ATTENTION else HealthStatus.OK, "Presión de admisión en ralentí",
                "MAP ${f(map, 0)} kPa vs baro ${f(baro, 0)} kPa (Δ ${f(d)}). En ralentí diésel suele ser cercana a la atmosférica.", "PID 0B/33")
        }

        // ── Combustible (common-rail)
        val railCmd = g("6D.CA"); val railAct = g("6D.RA") ?: g("59") ?: g("23")
        if (railCmd != null && railAct != null && railCmd > 0) {
            val dev = (railAct - railCmd) / railCmd * 100
            val st = when { abs(dev) > 20 -> HealthStatus.CHECK; abs(dev) > 10 -> HealthStatus.ATTENTION; else -> HealthStatus.OK }
            add("Combustible", st, "Riel: real ${f(railAct / 1000)} MPa vs comandada ${f(railCmd / 1000)} MPa",
                "Desvío ${f(dev)} %. " + if (st == HealthStatus.OK) "La bomba sigue lo pedido." else "Revisar filtro, bomba de alta, regulador o fugas de retorno.", "PID 6D")
        } else if (railAct != null && idling && diesel) {
            val mpa = railAct / 1000
            add("Combustible", if (mpa < 18) HealthStatus.ATTENTION else HealthStatus.OK, "Presión de riel en ralentí ${f(mpa)} MPa",
                if (mpa < 18) "Baja para common-rail en ralentí (ref. genérica 22–45 MPa)." else "Dentro de la referencia genérica.", "PID 59/23")
        }
        g("5E")?.let { rate ->
            if (idling) add("Combustible", if (rate > 2.0) HealthStatus.ATTENTION else HealthStatus.OK, "Consumo en ralentí ${f(rate, 2)} L/h",
                if (rate > 2.0) "Alto para un 2.0 diésel en ralentí (ref. 0,5–1,5 L/h). Puede haber regeneración o carga." else "Normal.", "PID 5E")
        }

        // ── Turbo
        val bCmd = g("70.CA"); val bAct = g("70.RA")
        if (bCmd != null && bAct != null) {
            val d = bAct - bCmd
            val st = when { abs(d) > 30 -> HealthStatus.CHECK; abs(d) > 15 -> HealthStatus.ATTENTION; else -> HealthStatus.OK }
            add("Turbo", st, "Turbo: real ${f(bAct, 0)} kPa vs comandado ${f(bCmd, 0)} kPa",
                "Desvío ${f(d)} kPa. " + if (st == HealthStatus.OK) "Sigue lo pedido en esta condición." else "Fuga de admisión, VGT trabado o sensor. Confirmar en aceleración libre / ruta.", "PID 70")
        }
        input.texts["70.SA"]?.let { if (it.contains("falla")) add("Turbo", HealthStatus.CHECK, "Control de turbo informa falla", it, "PID 70") }

        // ── EGR
        val egrErr = g("69.EA") ?: g("2D")
        if (egrErr != null && running) {
            val st = when { abs(egrErr) > 25 -> HealthStatus.CHECK; abs(egrErr) > 12 -> HealthStatus.ATTENTION; else -> HealthStatus.OK }
            add("EGR", st, "Error de EGR ${f(egrErr)} %", "Comandada ${f(g("69.CA") ?: g("2C"))} %, real ${f(g("69.RA"))} %. " +
                if (st == HealthStatus.OK) "Válvula sigue lo pedido." else "Válvula EGR con carbón o actuador lento.", "PID 69/2C/2D")
        }

        // ── DPF / postratamiento
        val dp = g("7A.DP")
        if (dp != null && idling) {
            val st = when { dp > 5 -> HealthStatus.CHECK; dp > 2.5 -> HealthStatus.ATTENTION; else -> HealthStatus.OK }
            add("DPF", st, "Presión diferencial DPF en ralentí ${f(dp, 2)} kPa",
                if (st == HealthStatus.OK) "Filtro sin restricción notable en ralentí." else "Filtro cargado o sensor/mangueras. Ver carga hacia regeneración.", "PID 7A")
        } else if (dp != null) add("DPF", HealthStatus.INFO, "Presión diferencial DPF ${f(dp, 2)} kPa", "Evaluar solo en ralentí estable.", "PID 7A")
        val trig = g("8B.TRIG")
        if (trig != null) {
            val st = when { trig >= 95 -> HealthStatus.CHECK; trig >= 80 -> HealthStatus.ATTENTION; else -> HealthStatus.OK }
            add("DPF", st, "Carga hacia la próxima regeneración: ${f(trig, 0)} %",
                when (st) { HealthStatus.OK -> "Hollín bajo control."; HealthStatus.ATTENTION -> "Regeneración próxima: conviene un tramo de ruta sostenido."; else -> "Límite: si no regenera, puede quedar en modo protegido." }, "PID 8B")
        }
        if (g("8B.REGEN") == 1.0) add("DPF", HealthStatus.INFO, "REGENERACIÓN EN CURSO", "No apagar el motor hasta que termine. Temperaturas de escape altas son normales ahora.", "PID 8B")
        g("8B.AVGD")?.let { if (it in 1.0..150.0) add("DPF", HealthStatus.ATTENTION, "Regenera cada ${f(it, 0)} km en promedio", "Regeneraciones frecuentes: uso urbano corto, inyector o EGR.", "PID 8B") else if (it > 0) add("DPF", HealthStatus.OK, "Regenera cada ${f(it, 0)} km en promedio", "", "PID 8B") }
        val dpfIn = g("7C.B1I"); val egt = g("78.S1")
        if (idling && g("8B.REGEN") != 1.0) {
            listOfNotNull(dpfIn?.let { "DPF entrada" to it }, egt?.let { "EGT S1" to it }).forEach { (n, t) ->
                if (t > 600) add("Escape", HealthStatus.ATTENTION, "$n ${f(t, 0)} °C en ralentí sin regeneración", "Inusual en ralentí.", "PID 7C/78")
            }
        }
        g("83.B1S1")?.let {
            if (it == 2000.0 || it >= 3000) {
                add("Emisiones", HealthStatus.INFO, "Sensor NOx aún no activo (${f(it, 0)} ppm)",
                    "Valor por defecto: el sensor espera escape caliente (punto de rocío). Con escape a ${f(egt ?: dpfIn, 0)} °C es esperable.", "PID 83")
            } else add("Emisiones", HealthStatus.INFO, "NOx B1S1 ${f(it, 0)} ppm", "Referencia para seguimiento.", "PID 83")
        }

        // ── Mode 06
        if (input.mode06.isNotEmpty()) {
            val failed = input.mode06.filter { !it.passed }
            if (failed.isEmpty()) {
                add("Autopruebas", HealthStatus.OK, "${input.mode06.size} pruebas a bordo dentro de límite", "Mode 06: todas entre mínimo y máximo.", "Mode 06")
            } else {
                failed.forEach { m ->
                    add("Autopruebas", HealthStatus.CHECK, "Fuera de límite: ${m.monitorName} (TID 0x%02X)".format(m.tid),
                        "Valor ${f(m.value, 3)} ${m.unit}, límites ${f(m.min, 3)}…${f(m.max, 3)}.", "Mode 06 MID 0x%02X".format(m.mid))
                }
            }
        }

        // ── Rendimiento en uso (Mode 09)
        input.vehicleInfo?.inUsePerformance?.let { ipt ->
            val pairs = ipt.windowed(2, 2).filter { it.size == 2 && it[0].first.endsWith("completos") }
            pairs.forEach { (comp, cond) ->
                if (cond.second >= 20) {
                    val ratio = comp.second.toDouble() / cond.second
                    if (ratio < 0.1) add("Autopruebas", HealthStatus.ATTENTION, "${comp.first.removeSuffix(" completos")}: corre poco (${f(ratio, 2)})",
                        "${comp.second} completados / ${cond.second} oportunidades. El monitor casi no termina.", "Mode 09 IPT")
                }
            }
        }
        if (items.none { it.system == "Monitores" } && r == null) add("Monitores", HealthStatus.NO_DATA, "Estado de monitores", "0101 no leído.", "—")
        return items.sortedWith(compareByDescending<HealthItem> { it.status.rank }.thenBy { it.system })
    }
}

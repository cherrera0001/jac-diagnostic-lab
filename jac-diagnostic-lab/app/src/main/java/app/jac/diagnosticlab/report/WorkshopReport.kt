package app.jac.diagnosticlab.report

import app.jac.diagnosticlab.analysis.HealthItem
import app.jac.diagnosticlab.analysis.HealthStatus
import app.jac.diagnosticlab.elm.Exchange
import app.jac.diagnosticlab.elm.ResponseKind
import app.jac.diagnosticlab.evidence.ReadingReportDocument
import app.jac.diagnosticlab.evidence.sha256Hex
import app.jac.diagnosticlab.obd.DtcCategory
import app.jac.diagnosticlab.obd.DtcReadResult
import app.jac.diagnosticlab.obd.FreezeNote
import app.jac.diagnosticlab.obd.FreezeValue
import app.jac.diagnosticlab.obd.Mode06Result
import app.jac.diagnosticlab.obd.MonitorState
import app.jac.diagnosticlab.obd.Readiness
import app.jac.diagnosticlab.obd.Signal
import app.jac.diagnosticlab.obd.VehicleInfo
import app.jac.diagnosticlab.profile.Mode09Record
import app.jac.diagnosticlab.profile.ProfileResolution
import app.jac.diagnosticlab.profile.ResolutionStatus
import app.jac.diagnosticlab.profile.VinYear
import app.jac.diagnosticlab.ui.Present
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Todo lo leído en la sesión. Se arma desde LabState; aquí no hay Android para poder probarlo. */
data class WorkshopInput(
    val appVersion: String,
    val sessionId: String,
    val generatedAtMs: Long,
    val declaredModel: String,
    val declaredYear: String,
    val vin: String?,
    val protocol: String,
    val adapterId: String,
    val adapterVoltage: String,
    val scanAtMs: Long?,
    val dtcs: List<DtcReadResult>,
    val freezeNotes: List<FreezeNote>,
    val freezeValues: List<FreezeValue>,
    val signals: List<Signal>,
    val rawByPid: Map<Int, String>,
    val supportedPids: Set<Int>,
    val readiness: Readiness?,
    val readinessCycle: Readiness?,
    val mode06: List<Mode06Result>,
    val vehicleInfo: VehicleInfo?,
    val ecuIdentification: List<Mode09Record>,
    val profile: ProfileResolution?,
    val health: List<HealthItem>,
    val exchanges: List<Exchange>,
    val errors: List<String>,
)

/** Qué verificar por código. Texto genérico (definición SAE J2012 + práctica de taller), no procedimiento JAC. */
data class DtcHint(val related: List<Int>, val checks: List<String>)

object WorkshopReport {
    const val SCOPE_NOTE = "Verificación genérica según la definición pública SAE J2012/J1979 y práctica de taller. " +
        "El procedimiento, los valores nominales y el torque son del manual de taller JAC, que no es público (research/gaps.md G-01)."

    private val rail = DtcHint(listOf(0x23, 0x0C, 0x04, 0x05), listOf(
        "Con escáner de marca comparar presión de riel real contra deseada en ralentí y a 2500 rpm.",
        "Revisar filtro de combustible, aire en la línea de baja presión y bomba de cebado.",
        "Revisar sensor de presión de riel y su conector (pines, humedad, corrosión).",
        "Prueba de retorno de inyectores según procedimiento de marca.",
    ))
    private val maf = DtcHint(listOf(0x10, 0x0B, 0x0F, 0x0C, 0x04), listOf(
        "Filtro de aire y ducto entre MAF y turbo: fugas, abrazaderas, roturas.",
        "Conector del MAF; comparar flujo en ralentí caliente con el valor del manual.",
        "Si IAT/MAF salen fuera de rango con el motor frío, descartar primero un perfil de escáner equivocado (tesis UPS 2024, p. 47).",
    ))
    private val boost = DtcHint(listOf(0x0B, 0x33, 0x0C, 0x04, 0x10), listOf(
        "Mangueras de presión del turbo e intercooler: fugas audibles o aceite en uniones.",
        "Actuador del turbo (geometría variable o wastegate) y su vacío/mando.",
        "Sensor MAP y su manguera; con motor apagado MAP ≈ presión barométrica (PID 33).",
    ))
    private val coolant = DtcHint(listOf(0x05, 0x0C, 0x46), listOf(
        "Comparar PID 05 con un termómetro en la salida del termostato.",
        "Termostato: tiempo hasta llegar a temperatura de trabajo.",
        "Sensor ECT y su conector.",
    ))
    private val position = DtcHint(listOf(0x0C, 0x0D), listOf(
        "Sensor y rueda fónica: distancia, limalla, daño.",
        "Conector y cableado hasta la ECU; revisar con osciloscopio si es posible.",
    ))
    private val glow = DtcHint(listOf(0x05, 0x42), listOf(
        "Medir resistencia de cada bujía incandescente.",
        "Relé o módulo de precalentamiento, fusible y alimentación.",
    ))
    private val egr = DtcHint(listOf(0x2C, 0x2D, 0x10, 0x0B), listOf(
        "Válvula EGR: carbonilla, movimiento libre, conector.",
        "Comparar mando EGR (PID 2C) con el flujo de aire esperado.",
    ))
    private val dpf = DtcHint(listOf(0x7A, 0x7C, 0x8B, 0x0B, 0x05), listOf(
        "Sensor de presión diferencial del DPF y sus dos mangueras: rotas, invertidas o tapadas.",
        "Nivel de hollín y kilómetros desde la última regeneración (escáner de marca).",
        "Regeneración forzada solo con el procedimiento de marca. Esta app no la ejecuta.",
    ))
    private val speed = DtcHint(listOf(0x0D, 0x0C), listOf("Sensor de velocidad, cableado y señal del ABS si comparte el dato."))
    private val voltage = DtcHint(listOf(0x42), listOf(
        "Medir batería con multímetro (motor apagado y en marcha). El valor del adaptador no sirve como medición.",
        "Alternador, masas del motor y chasis, bornes.",
    ))
    private val brake = DtcHint(listOf(0x49, 0x4A, 0x11, 0x0D), listOf(
        "Interruptor de freno de doble contacto: medir ambas señales suelto y pisado.",
        "Ajuste mecánico del interruptor contra el pedal.",
        "Dos pistas del pedal acelerador (PID 49/4A) con multímetro: deben moverse en paralelo.",
        "No probar el corte de potencia circulando.",
    ))
    private val pedal = DtcHint(listOf(0x49, 0x4A, 0x11), listOf(
        "Dos pistas del pedal: medir tensión de cada una en reposo y a fondo.",
        "Conector del pedal y masa de referencia.",
    ))
    private val injector = DtcHint(listOf(0x0C, 0x23), listOf(
        "Circuito del inyector: resistencia, cableado, conector.",
        "Balance de cilindros con escáner de marca.",
    ))
    private val ecu = DtcHint(listOf(0x42), listOf("Alimentación y masas de la ECU. Diagnóstico de marca antes de cambiar o reprogramar."))

    val hints: Map<String, DtcHint> = buildMap {
        listOf("P0087", "P0088", "P0093", "P0190").forEach { put(it, rail) }
        listOf("P0101", "P0102", "P0103").forEach { put(it, maf) }
        listOf("P0106", "P0234", "P0299", "P2263").forEach { put(it, boost) }
        listOf("P0115", "P0128").forEach { put(it, coolant) }
        listOf("P0335", "P0340").forEach { put(it, position) }
        listOf("P0380", "P0670").forEach { put(it, glow) }
        listOf("P0400", "P0401", "P0403").forEach { put(it, egr) }
        listOf("P0471", "P2002", "P242F", "P2452", "P2453", "P2463").forEach { put(it, dpf) }
        put("P0500", speed)
        listOf("P0562", "P0563").forEach { put(it, voltage) }
        listOf("P0571", "P0579", "P2299").forEach { put(it, brake) }
        listOf("P2135", "P2138").forEach { put(it, pedal) }
        listOf("P0201", "P0202", "P0203", "P0204").forEach { put(it, injector) }
        put("P0606", ecu)
    }

    /** Lo que falta leer para que el informe esté completo. Vacío = se puede generar. */
    fun missing(i: WorkshopInput): List<String> = buildList {
        if (i.scanAtMs == null) add("Escaneo completo (todos los PID, monitores, Mode 06 y Mode 09)")
        DtcCategory.entries.forEach { c -> if (i.dtcs.none { it.category == c }) add("Códigos ${label(c)}s") }
        if (i.ecuIdentification.isEmpty()) add("Identificación de la ECU (Mode 09)")
    }

    fun build(i: WorkshopInput, zone: ZoneId = ZoneId.systemDefault()): ReadingReportDocument {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)
        fun t(ms: Long) = fmt.format(Instant.ofEpochMilli(ms))
        val codes = i.dtcs.flatMap { r -> r.codes.map { it.code.uppercase() to r.category } }.distinct()
        val freezeDtc = i.freezeNotes.firstNotNullOfOrNull { it.dtc }
        val bySignalPid = i.signals.groupBy { it.pid }
        val body = buildList {
            add("Informe técnico para taller. Solo lectura: no se borró ningún código ni se modificó la ECU.")
            add("Generado ${t(i.generatedAtMs)} con JAC Diagnostic Lab ${i.appVersion}. Escaneo ${i.scanAtMs?.let { t(it) } ?: "no realizado"}.")
            val gaps = missing(i)
            if (gaps.isNotEmpty()) add("INCOMPLETO. Falta: ${gaps.joinToString("; ")}.")
            add("")

            add("1. Vehículo y ECU")
            add("Declarado por el dueño: ${listOf(i.declaredModel, i.declaredYear).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "sin declarar" }}.")
            if (i.vin.isNullOrBlank()) add("VIN: la ECU no lo entregó.") else {
                val y = VinYear.decode(i.vin)
                add("VIN (leído de la ECU, 0902): ${i.vin}${y?.let { ". Año modelo según ISO 3779: $it" } ?: ""}.")
            }
            i.ecuIdentification.forEach { r ->
                val per = r.perEcu.joinToString(" | ") { e -> "ECU ${e.ecu ?: "?"}: ${e.values.joinToString(", ")}" }
                add("Mode 09 ${r.command} (${r.kind}, ${r.elapsedMs} ms): ${per.ifBlank { r.note }}")
            }
            i.vehicleInfo?.let { v ->
                if (v.calibrationIds.isNotEmpty()) add("CALID: ${v.calibrationIds.joinToString(", ")}")
                if (v.cvns.isNotEmpty()) add("CVN: ${v.cvns.joinToString(", ")}")
                v.ecuName?.let { add("Nombre de ECU: $it") }
            }
            i.profile?.let { p ->
                add("Perfil de ECU (${p.catalogVersion}): ${p.status}. ${p.summary}")
                p.matches.forEach { m -> add("  ${m.candidate.supplier} ${m.candidate.family}: «${m.identifier}» en ${m.origin}${m.ecu?.let { " ($it)" } ?: ""}; fuentes ${m.candidate.sourceIds.joinToString(", ")}") }
                if (p.status == ResolutionStatus.PARTIAL) add("  Sin identificador documentado. Confirmar con la etiqueta física de la ECU.")
            }
            add("Protocolo ${i.protocol.ifBlank { "?" }} · adaptador ${i.adapterId.ifBlank { "?" }} · tensión estimada por el adaptador ${i.adapterVoltage.ifBlank { "?" }} (no es medición de batería).")
            add("")

            add("2. Resumen para el taller")
            val mil = i.readiness?.milOn
            add("Luz de falla (MIL): ${when (mil) { true -> "ENCENDIDA"; false -> "apagada"; null -> "sin dato" }}. DTC informados por la ECU (0101): ${i.readiness?.dtcCount ?: "sin dato"}.")
            if (codes.isEmpty()) add("Sin códigos en almacenados, pendientes ni permanentes.")
            else codes.forEach { (c, cat) -> add("• $c (${label(cat)}): ${Present.dtcTitle(c)}${if (c == freezeDtc) " — causó el cuadro congelado" else ""}") }
            val bad = i.health.filter { it.status == HealthStatus.CHECK || it.status == HealthStatus.ATTENTION }.sortedByDescending { it.status.rank }
            bad.forEach { add("• ${it.status.label} ${it.system}: ${it.title}. ${it.detail} [${it.evidence}]") }
            i.readiness?.incomplete?.takeIf { it.isNotEmpty() }?.let { add("• Monitores incompletos: ${it.joinToString { m -> m.name }}") }
            i.mode06.filter { !it.passed }.forEach { add("• Mode 06 FUERA de límite: ${it.monitorName} = ${fmtNum(it.value)} ${it.unit} (mín ${fmtNum(it.min)}, máx ${fmtNum(it.max)})") }
            if (codes.isEmpty() && bad.isEmpty() && i.mode06.none { !it.passed }) add("No hay hallazgos en lo que la ECU genérica informa. Si hay un síntoma, la falla puede estar en otro módulo (ABS, carrocería) o requerir escáner de marca.")
            add("")

            add("3. Detalle por código")
            if (codes.isEmpty()) add("Sin códigos.")
            codes.forEach { (c, cat) ->
                val hit = i.dtcs.flatMap { it.codes }.firstOrNull { it.code.equals(c, true) }
                add("$c — ${Present.dtcTitle(c)} (${label(cat)}${hit?.ecu?.let { ", ECU $it" } ?: ""}${hit?.let { ", ${it.scopeNote}" } ?: ""})")
                val hint = hints[c]
                if (hint == null) {
                    add("  Sin guía genérica en la app. Buscar $c en el manual de taller JAC con el cuadro congelado y los datos de abajo.")
                } else {
                    add("  Datos relacionados ahora:")
                    hint.related.forEach { pid ->
                        val now = bySignalPid[pid]?.joinToString("; ") { s -> "${s.name} ${s.text ?: fmtNum(s.value)} ${s.unit}".trim() }
                        val frz = i.freezeValues.firstOrNull { it.pid == pid }?.signals?.joinToString("; ") { s -> "${s.text ?: fmtNum(s.value)} ${s.unit}".trim() }
                        when {
                            now != null || frz != null -> add("   PID ${hex(pid)}: ahora ${now ?: "sin dato"}${frz?.let { " · congelado $it" } ?: ""}")
                            pid !in i.supportedPids -> add("   PID ${hex(pid)}: la ECU no lo declara")
                            else -> add("   PID ${hex(pid)}: declarado, sin respuesta en el escaneo")
                        }
                    }
                    add("  Verificar:")
                    hint.checks.forEachIndexed { n, s -> add("   ${n + 1}. $s") }
                }
            }
            add("  $SCOPE_NOTE")
            add("")

            add("4. Cuadro congelado (Mode 02)")
            i.freezeNotes.forEach { add("DTC del cuadro: ${it.dtc ?: "ninguno"}. ${it.note} Raw: ${it.raw.trim().replace("\n", " ")}") }
            if (i.freezeValues.isEmpty()) add("Sin valores congelados (no hay DTC guardado o la ECU no los entrega).")
            i.freezeValues.forEach { f -> f.signals.forEach { s -> add("PID ${hex(f.pid)} ${s.name}: ${s.text ?: fmtNum(s.value)} ${s.unit} [${f.raw}]") } }
            add("")

            add("5. Datos en vivo del escaneo (${i.signals.size} señales de ${i.supportedPids.size} PID declarados)")
            i.signals.sortedBy { it.pid }.forEach { s ->
                val st = Present.gaugeStatus(s.key, s.value, running = (bySignalPid[0x0C]?.firstOrNull()?.value ?: 0.0) > 300)
                val flag = if (st == HealthStatus.CHECK || st == HealthStatus.ATTENTION) " ← ${st.label}" else ""
                add("PID ${hex(s.pid)} ${s.name}: ${s.text ?: fmtNum(s.value)} ${s.unit}$flag [${i.rawByPid[s.pid].orEmpty()}]".replace(" []", ""))
            }
            add("")

            add("6. Monitores de preparación")
            listOfNotNull(i.readiness?.let { "Desde el último borrado" to it }, i.readinessCycle?.let { "Este ciclo" to it }).forEach { (name, r) ->
                add("$name (${if (r.compressionIgnition) "diésel" else "gasolina"}): " + r.supported.joinToString(", ") { "${it.name} ${it.state.label}" })
            }
            if (i.readiness == null) add("Sin dato.")
            add("")

            add("7. Pruebas a bordo (Mode 06)")
            if (i.mode06.isEmpty()) add("La ECU no entregó resultados Mode 06.")
            i.mode06.forEach { add("MID ${hex(it.mid)} TID ${hex(it.tid)} ${it.monitorName}: ${fmtNum(it.value)} ${it.unit} [${fmtNum(it.min)} … ${fmtNum(it.max)}] ${if (it.passed) "OK" else "FUERA"}") }
            i.vehicleInfo?.inUsePerformance?.takeIf { it.isNotEmpty() }?.let { add("Rendimiento en uso (0908/090B): " + it.joinToString(", ") { (k, v) -> "$k $v" }) }
            add("")

            add("8. Salud por sistema")
            if (i.health.isEmpty()) add("Sin evaluación.")
            i.health.sortedByDescending { it.status.rank }.forEach { add("${it.status.label} · ${it.system}: ${it.title}. ${it.detail}") }
            add("")

            add("9. Registro de comunicación")
            val byKind = i.exchanges.groupingBy { it.kind }.eachCount()
            add("${i.exchanges.size} comandos: " + byKind.entries.joinToString(", ") { "${it.key} ${it.value}" })
            if (i.exchanges.isNotEmpty()) add("Latencia: mediana ${median(i.exchanges.map { it.elapsedMs })} ms, máxima ${i.exchanges.maxOf { it.elapsedMs }} ms.")
            i.errors.takeLast(20).forEach { add("Error: $it") }
            i.exchanges.filter { it.kind != ResponseKind.OBD_DATA && it.kind != ResponseKind.RECOGNIZED }.takeLast(30).forEach {
                add("${t(it.atEpochMs)} ${it.command} → ${it.kind} (${it.elapsedMs} ms) ${it.raw.trim().replace("\n", " ").take(80)}")
            }
            add("El registro completo de cada comando con sus bytes va en el JSON de la sesión.")
            add("")

            add("10. De dónde sale cada dato")
            add("Valores, códigos, monitores y Mode 06: respuesta de la ECU por OBD-II (SAE J1979), en esta sesión.")
            add("Títulos de códigos: definición genérica SAE J2012, no texto JAC. Códigos P1xxx/P3xxx: del fabricante, sin texto público.")
            add("Rangos marcados ATENCIÓN/REVISAR: referencia genérica de la app, no tolerancia JAC.")
            add("Perfil de ECU: catálogo con fuentes y niveles de evidencia (research/source-manifest.json). JAC no publica API ni datos de taller para la T8.")
        }
        val hash = sha256Hex(body.joinToString("\n").toByteArray(Charsets.UTF_8))
        val title = when {
            codes.isNotEmpty() -> "Informe de taller: ${codes.size} código(s) — ${codes.joinToString(", ") { it.first }}"
            else -> "Informe de taller: sin códigos guardados"
        }
        return ReadingReportDocument(title, body + listOf("", "Huella SHA-256 del texto: $hash"), hash)
    }

    private fun label(c: DtcCategory) = when (c) { DtcCategory.STORED -> "almacenado"; DtcCategory.PENDING -> "pendiente"; DtcCategory.PERMANENT -> "permanente" }
    private fun fmtNum(v: Double?): String = when {
        v == null -> "sin dato"
        v % 1.0 == 0.0 -> v.toLong().toString()
        else -> "%.2f".format(java.util.Locale.ROOT, v)
    }
    private fun hex(v: Int) = "%02X".format(v)
    private fun median(xs: List<Long>): Long = xs.sorted().let { it[it.size / 2] }
}

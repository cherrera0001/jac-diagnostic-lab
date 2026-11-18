package app.jac.diagnosticlab.report

import app.jac.diagnosticlab.analysis.HealthItem
import app.jac.diagnosticlab.analysis.HealthStatus
import app.jac.diagnosticlab.dynamic.TestResult
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
import java.util.Locale

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
    val tests: List<TestResult> = emptyList(),
)

/* ───────── Documento estructurado: lo dibuja WorkshopPdf y se aplana a texto para la huella ───────── */

enum class Tone { CHECK, ATTENTION, OK, INFO, MUTED }

sealed interface Block
data class Para(val text: String, val tone: Tone? = null, val bold: Boolean = false) : Block
data class KeyValues(val rows: List<Pair<String, String>>) : Block
data class Table(val headers: List<String>, val weights: List<Float>, val rows: List<List<String>>, val tones: List<Tone?> = emptyList()) : Block
data class Finding(val tone: Tone, val label: String, val title: String, val detail: String)
data class Findings(val items: List<Finding>) : Block
data class Steps(val title: String, val items: List<String>) : Block
data class Section(val number: Int, val title: String, val blocks: List<Block>)
data class SummaryBox(val verdict: String, val tone: Tone, val counts: List<Pair<String, Tone>>, val codes: List<Pair<String, Tone>>, val lines: List<String>)
data class WorkshopDoc(
    val title: String,
    val subtitle: String,
    val meta: List<Pair<String, String>>,
    val summary: SummaryBox,
    val sections: List<Section>,
    val footer: String,
) {
    fun toLines(): List<String> = buildList {
        add(title); add(subtitle)
        meta.forEach { add("${it.first}: ${it.second}") }
        add(summary.verdict); summary.lines.forEach { add(it) }
        sections.forEach { s ->
            add(""); add("${s.number}. ${s.title}")
            s.blocks.forEach { b ->
                when (b) {
                    is Para -> add(b.text)
                    is KeyValues -> b.rows.forEach { add("${it.first}: ${it.second}") }
                    is Table -> { add(b.headers.joinToString(" | ")); b.rows.forEach { add(it.joinToString(" | ")) } }
                    is Findings -> b.items.forEach { add("• ${it.label} ${it.title}. ${it.detail}") }
                    is Steps -> { add(b.title); b.items.forEachIndexed { i, t -> add("  ${i + 1}. $t") } }
                }
            }
        }
    }
}

/** Qué verificar por código. Texto genérico (definición SAE J2012 + práctica de taller), no procedimiento JAC. */
data class DtcHint(val meaning: String, val related: List<Int>, val checks: List<String>)

object WorkshopReport {
    const val SCOPE_NOTE = "Verificación genérica según la definición pública SAE J2012/J1979 y práctica de taller. " +
        "El procedimiento, los valores nominales y el torque son del manual de taller JAC, que no es público (research/gaps.md G-01)."

    private val rail = DtcHint("Presión de riel fuera de lo comandado.", listOf(0x23, 0x59, 0x6D, 0x0C, 0x04), listOf(
        "Con escáner de marca comparar presión de riel real contra deseada en ralentí y a 2500 rpm.",
        "Revisar filtro de combustible, aire en la línea de baja presión y bomba de cebado.",
        "Revisar sensor de presión de riel y su conector (pines, humedad, corrosión).",
        "Prueba de retorno de inyectores según procedimiento de marca.",
    ))
    private val maf = DtcHint("Señal de flujo de aire incoherente.", listOf(0x10, 0x66, 0x0B, 0x0F, 0x0C), listOf(
        "Filtro de aire y ducto entre MAF y turbo: fugas, abrazaderas, roturas.",
        "Conector del MAF; comparar flujo en ralentí caliente con el valor del manual.",
    ))
    private val boost = DtcHint("Presión de sobrealimentación fuera de lo comandado.", listOf(0x0B, 0x70, 0x33, 0x0C, 0x04), listOf(
        "Mangueras de presión del turbo e intercooler: fugas audibles o aceite en uniones.",
        "Actuador del turbo (geometría variable o wastegate) y su mando.",
        "Sensor MAP y su manguera; con motor apagado MAP ≈ presión barométrica (PID 33).",
    ))
    private val coolant = DtcHint("Temperatura de motor fuera de lo esperado.", listOf(0x05, 0x67, 0x0C, 0x46), listOf(
        "Comparar PID 05 con un termómetro en la salida del termostato.",
        "Termostato: tiempo hasta llegar a temperatura de trabajo (prueba «Calentamiento»).",
        "Sensor ECT y su conector.",
    ))
    private val position = DtcHint("Señal de giro del motor perdida o incoherente.", listOf(0x0C, 0x0D), listOf(
        "Sensor y rueda fónica: distancia, limalla, daño.",
        "Conector y cableado hasta la ECU; osciloscopio si es posible.",
    ))
    private val glow = DtcHint("Circuito de precalentamiento.", listOf(0x05, 0x42), listOf(
        "Medir resistencia de cada bujía incandescente.",
        "Relé o módulo de precalentamiento, fusible y alimentación.",
    ))
    private val egr = DtcHint("EGR no sigue lo comandado.", listOf(0x2C, 0x2D, 0x69, 0x10), listOf(
        "Válvula EGR: carbonilla, movimiento libre, conector.",
        "Comparar EGR comandada contra real (PID 69).",
    ))
    private val dpf = DtcHint("Filtro de partículas o su sensor.", listOf(0x7A, 0x7C, 0x8B, 0x78), listOf(
        "Sensor de presión diferencial del DPF y sus dos mangueras: rotas, invertidas o tapadas.",
        "Nivel de hollín y kilómetros desde la última regeneración (escáner de marca).",
        "Regeneración forzada solo con el procedimiento de marca. Esta app no la ejecuta.",
    ))
    private val speed = DtcHint("Señal de velocidad.", listOf(0x0D, 0x0C), listOf("Sensor de velocidad, cableado y señal del ABS si comparte el dato."))
    private val voltage = DtcHint("Tensión del sistema fuera de rango.", listOf(0x42), listOf(
        "Medir batería con multímetro (motor apagado y en marcha). El valor del adaptador no sirve como medición.",
        "Alternador, masas del motor y chasis, bornes.",
    ))
    private val brake = DtcHint(
        "La ECU vio el freno en conflicto con el acelerador o con el mando de crucero. En diésel con interruptor de freno de doble contacto, " +
            "P0579 suele ser la plausibilidad entre los dos contactos y P2299 la presencia simultánea de freno y acelerador. Que aparezcan juntos " +
            "apunta primero al interruptor de freno (desajuste, tope de goma del pedal, conector) antes que al pedal del acelerador.",
        listOf(0x49, 0x4A, 0x0C, 0x0D, 0x04, 0x61),
        listOf(
            "Luces de freno: comprobar que encienden al primer milímetro de pedal y apagan al soltar.",
            "Interruptor de freno de doble contacto: con multímetro, un contacto cerrado y el otro abierto en reposo; se invierten al pisar. Ambos deben cambiar a la vez.",
            "Tope de goma del pedal de freno: si falta o está roto, el émbolo del interruptor queda a medio camino. Es una causa muy común.",
            "Ajuste del interruptor contra el pedal y estado del conector (pines abiertos, humedad).",
            "Pedal del acelerador: las dos pistas (PID 49 y 4A) deben moverse en proporción fija. Prueba «Freno contra acelerador» de la app.",
            "Mando de crucero en el volante (si existe): botones pegados o humedad.",
            "No probar el corte de potencia circulando.",
        ),
    )
    private val pedal = DtcHint("Pistas del pedal del acelerador no coinciden.", listOf(0x49, 0x4A, 0x11), listOf(
        "Dos pistas del pedal: medir tensión de cada una en reposo y a fondo.",
        "Conector del pedal y masa de referencia.",
    ))
    private val injector = DtcHint("Circuito del inyector.", listOf(0x0C, 0x23), listOf(
        "Circuito del inyector: resistencia, cableado, conector.",
        "Balance de cilindros con escáner de marca.",
    ))
    private val ecu = DtcHint("Falla interna o de alimentación de la ECU.", listOf(0x42), listOf("Alimentación y masas de la ECU. Diagnóstico de marca antes de cambiar o reprogramar."))

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
        DtcCategory.entries.forEach { c -> if (i.dtcs.none { it.category == c }) add("Códigos ${plural(c)}") }
        if (i.ecuIdentification.isEmpty()) add("Identificación de la ECU (Mode 09)")
    }

    /** Códigos únicos con todas sus categorías (un P0579 almacenado y pendiente es un solo código). */
    fun codes(i: WorkshopInput): List<Pair<String, List<DtcCategory>>> =
        i.dtcs.flatMap { r -> r.codes.map { it.code.uppercase() to r.category } }
            .groupBy({ it.first }, { it.second }).map { (c, cats) -> c to cats.distinct().sortedBy { it.ordinal } }

    /** «7F 0A 13»: la ECU rechazó el servicio. No equivale a «sin códigos». */
    fun refused(r: DtcReadResult): Boolean {
        val compact = r.raw.uppercase().replace(Regex("[^0-9A-F]"), "")
        return r.codes.isEmpty() && compact.contains("7F${sid(r.category)}")
    }

    /**
     * Algunas ECU rellenan con 0 los PID que no guardaron en el cuadro congelado. Si barométrica o
     * tensión valen 0 con el motor girando, los ceros del cuadro no son mediciones.
     */
    fun freezeZerosUnreliable(i: WorkshopInput): Boolean {
        fun v(pid: Int) = i.freezeValues.firstOrNull { it.pid == pid }?.signals?.firstOrNull()?.value
        val rpm = v(0x0C) ?: 0.0
        return rpm > 300 && (v(0x33) == 0.0 || v(0x42) == 0.0)
    }

    fun buildDoc(i: WorkshopInput, zone: ZoneId = ZoneId.systemDefault()): WorkshopDoc {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)
        fun t(ms: Long) = fmt.format(Instant.ofEpochMilli(ms))
        val codes = codes(i)
        val freezeDtc = i.freezeNotes.firstNotNullOfOrNull { it.dtc }
        val zerosBad = freezeZerosUnreliable(i)
        val byPid = i.signals.groupBy { it.pid }
        fun live(pid: Int) = byPid[pid]?.let { list -> list.joinToString("; ") { show(it) + if (list.size > 1) " (${it.name})" else "" } }
        fun frozen(pid: Int) = i.freezeValues.firstOrNull { it.pid == pid }?.signals?.joinToString("; ") { s ->
            show(s) + if (zerosBad && s.value == 0.0) " (no capturado)" else ""
        }
        val runtime = byPid[0x1F]?.firstOrNull()?.value
        val cleared = byPid[0x31]?.firstOrNull()?.value
        val problems = i.health.filter { it.status == HealthStatus.CHECK }
        val warnings = i.health.filter { it.status == HealthStatus.ATTENTION }
        val incomplete = missing(i)

        val verdictTone = when { codes.isNotEmpty() || problems.isNotEmpty() -> Tone.CHECK; warnings.isNotEmpty() -> Tone.ATTENTION; else -> Tone.OK }
        val verdict = when {
            codes.isNotEmpty() -> "Requiere revisión: ${codes.size} código(s) de falla — ${codes.joinToString(", ") { it.first }}"
            problems.isNotEmpty() -> "Requiere revisión: ${problems.size} problema(s)"
            warnings.isNotEmpty() -> "Sin códigos. ${warnings.size} aviso(s) a revisar"
            else -> "Sin códigos ni hallazgos en lo que informa la ECU de motor"
        }
        val summary = SummaryBox(
            verdict = verdict,
            tone = verdictTone,
            counts = listOf("${problems.size} problema(s)" to Tone.CHECK, "${warnings.size} aviso(s)" to Tone.ATTENTION,
                "${i.health.count { it.status == HealthStatus.OK }} OK" to Tone.OK, "${i.signals.size} datos" to Tone.INFO),
            codes = codes.map { (c, cats) -> "$c ${cats.joinToString("+") { short(it) }}" to if (DtcCategory.STORED in cats) Tone.CHECK else Tone.ATTENTION },
            lines = buildList {
                add("Luz de falla (MIL): ${when (i.readiness?.milOn) { true -> "ENCENDIDA"; false -> "apagada"; null -> "sin dato" }}" +
                    (cleared?.let { " · códigos borrados hace ${it.toLong()} km" } ?: ""))
                i.vehicleInfo?.calibrationIds?.takeIf { it.isNotEmpty() }?.let { add("ECU: ${it.joinToString()} · CVN ${i.vehicleInfo.cvns.joinToString().ifBlank { "—" }}") }
                if (incomplete.isNotEmpty()) add("INCOMPLETO. Falta: ${incomplete.joinToString("; ")}")
            },
        )

        val sections = mutableListOf<Section>()
        var n = 0

        // Diagnóstico principal: lo que el mecánico necesita leer primero.
        sections += Section(++n, "Diagnóstico principal", buildList {
            if (codes.isEmpty()) {
                add(Para("La ECU de motor no tiene códigos almacenados ni pendientes.", Tone.OK, bold = true))
                add(Para("Si hay un síntoma, puede estar en otro módulo (ABS, carrocería, transmisión) que el OBD genérico no ve, o requerir escáner de marca."))
            }
            codes.groupBy { hints[it.first] }.forEach { (hint, group) ->
                add(Para(group.joinToString(" + ") { "${it.first} (${it.second.joinToString(", ") { c -> label(c) }})" }, Tone.CHECK, bold = true))
                add(Para(group.joinToString(" · ") { "${it.first}: ${Present.dtcTitle(it.first)}" }))
                if (hint != null) add(Para(hint.meaning))
                if (freezeDtc != null && group.any { it.first == freezeDtc }) {
                    val ctx = listOfNotNull(frozen(0x0D)?.let { "velocidad $it" }, frozen(0x0C), frozen(0x04)?.let { "carga $it" },
                        frozen(0x05)?.let { "refrigerante $it" }).joinToString(", ")
                    add(Para("Cuando se guardó $freezeDtc: $ctx.", Tone.INFO))
                }
                if (cleared != null && cleared < 2000) add(Para("Los códigos se borraron hace ${cleared.toLong()} km y volvieron: la falla es persistente, no un evento aislado.", Tone.ATTENTION))
                if (hint != null) add(Steps("Qué revisar, en este orden", hint.checks))
                if (hint === brake) add(Para("Límite del OBD genérico: el estado del interruptor de freno no es un dato estándar J1979. Este adaptador no puede leerlo; " +
                    "la prueba «Freno contra acelerador» de la app muestra si la ECU recibe el freno.", Tone.MUTED))
            }
            i.tests.filter { it.testId == "BRAKE" }.maxByOrNull { it.startedAtMs }?.let { add(Para("Prueba freno contra acelerador (${t(it.startedAtMs)}): ${it.summary}", tone(it.verdict), bold = true)) }
            add(Para(SCOPE_NOTE, Tone.MUTED))
        })

        val ranked = i.health.filter { it.status == HealthStatus.CHECK || it.status == HealthStatus.ATTENTION || it.status == HealthStatus.INFO }
            .sortedByDescending { it.status.rank }
        sections += Section(++n, "Hallazgos", listOf(
            if (ranked.isEmpty()) Para("Sin hallazgos.") else Findings(ranked.map { Finding(tone(it.status), it.status.label, "${it.system}: ${it.title}", "${it.detail} Evidencia: ${it.evidence}.") }),
        ))

        sections += Section(++n, "Vehículo e identificación de la ECU", buildList {
            add(KeyValues(buildList {
                add("Vehículo declarado" to listOf(i.declaredModel, i.declaredYear).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "sin declarar" })
                add("VIN (0902)" to (i.vin?.let { v -> v + (VinYear.decode(v)?.let { " · año modelo $it (ISO 3779)" } ?: "") } ?: "no entregado"))
                i.vehicleInfo?.let { v ->
                    add("Calibración CALID (0904)" to v.calibrationIds.joinToString().ifBlank { "—" })
                    add("CVN (0906)" to v.cvns.joinToString().ifBlank { "—" })
                    add("Nombre de ECU (090A)" to (v.ecuName ?: "—"))
                }
                i.profile?.let { p ->
                    val who = p.matches.joinToString { "${it.candidate.supplier} ${it.candidate.family}" }.ifBlank { "sin identificador documentado" }
                    add("Perfil de ECU" to "${statusText(p.status)} — $who")
                    p.matches.firstOrNull()?.let { add("Fuente del perfil" to "«${it.identifier}» en ${originText(it.origin.name)}; fuentes ${it.candidate.sourceIds.joinToString()}") }
                }
                add("Norma OBD" to (byPid[0x1C]?.firstOrNull()?.let { show(it) } ?: "—"))
                add("Protocolo" to i.protocol.ifBlank { "—" })
                add("Adaptador" to "${i.adapterId.ifBlank { "—" }} · tensión estimada ${i.adapterVoltage.ifBlank { "—" }} (no es medición de batería)")
                add("Motor en marcha total (7F)" to (byPid[0x7F]?.firstOrNull()?.value?.let { "%.0f h".format(Locale.ROOT, it / 3600) } ?: "—"))
            }))
            if (i.profile?.status == ResolutionStatus.PARTIAL) add(Para("El perfil no se pudo resolver: fotografiar la etiqueta física de la ECU.", Tone.ATTENTION))
            if (i.ecuIdentification.isNotEmpty()) add(Table(listOf("Comando", "ECU", "Respuesta", "ms"), listOf(1f, 0.8f, 4f, 0.7f),
                i.ecuIdentification.flatMap { r ->
                    if (r.perEcu.isEmpty()) listOf(listOf(r.command, "—", r.note.ifBlank { r.kind }, "${r.elapsedMs}"))
                    else r.perEcu.map { e -> listOf(r.command, e.ecu ?: "?", e.values.joinToString(", "), "${r.elapsedMs}") }
                }))
        })

        sections += Section(++n, "Códigos de falla", buildList {
            add(Table(listOf("Lectura", "Servicio", "Resultado"), listOf(1.3f, 0.8f, 4f), DtcCategory.entries.map { c ->
                val r = i.dtcs.firstOrNull { it.category == c }
                listOf(plural(c).replaceFirstChar { it.uppercase() }, sid(c), when {
                    r == null -> "no leído"
                    refused(r) -> "la ECU no admite este servicio (respuesta negativa). No significa «sin códigos»."
                    r.codes.isEmpty() -> if (r.kind == ResponseKind.OBD_DATA || r.confirmedEmpty) "ninguno" else "sin respuesta válida (${r.kind})"
                    else -> r.codes.joinToString { it.code }
                })
            }, DtcCategory.entries.map { c -> val r = i.dtcs.firstOrNull { it.category == c }; when { r == null -> Tone.MUTED; r.codes.isNotEmpty() -> Tone.CHECK; refused(r) -> Tone.MUTED; else -> null } }))
            if (codes.isNotEmpty()) add(Table(listOf("Código", "Tipo", "Descripción genérica (SAE J2012)", "ECU"), listOf(0.9f, 1.6f, 3.6f, 0.6f),
                codes.map { (c, cats) -> listOf(c, cats.joinToString(" + ") { label(it) }, Present.dtcTitle(c) + if (c == freezeDtc) " — guardó el cuadro congelado" else "",
                    i.dtcs.flatMap { it.codes }.firstOrNull { it.code.equals(c, true) }?.ecu ?: "—") },
                codes.map { if (DtcCategory.STORED in it.second) Tone.CHECK else Tone.ATTENTION }))
            codes.mapNotNull { hints[it.first] }.distinct().forEach { h ->
                add(Para("Datos relacionados: ahora contra el momento de la falla", bold = true))
                add(Table(listOf("PID", "Parámetro", "Ahora", "Al guardarse la falla"), listOf(0.6f, 2.2f, 1.6f, 1.8f), h.related.map { pid ->
                    val name = byPid[pid]?.firstOrNull()?.name ?: i.freezeValues.firstOrNull { it.pid == pid }?.signals?.firstOrNull()?.name ?: "—"
                    listOf(hex(pid), name, live(pid) ?: if (pid in i.supportedPids) "sin respuesta" else "no soportado", frozen(pid) ?: "—")
                }))
            }
        })

        sections += Section(++n, "Cuadro congelado (Mode 02)", buildList {
            i.freezeNotes.forEach { add(Para("Código que lo guardó: ${it.dtc ?: "ninguno"}. Respuesta: ${it.raw.trim().replace("\n", " ")}")) }
            if (zerosBad) add(Para("Esta ECU rellena con 0 los datos que no capturó (barométrica o tensión en 0 con el motor girando es imposible). Los ceros marcados «no capturado» no son mediciones.", Tone.ATTENTION))
            if (i.freezeValues.isEmpty()) add(Para("Sin valores congelados (no hay código guardado o la ECU no los entrega)."))
            else add(Table(listOf("PID", "Parámetro", "Valor", "Bytes"), listOf(0.6f, 2.6f, 1.6f, 1.6f),
                i.freezeValues.flatMap { f -> f.signals.map { s -> listOf(hex(f.pid), s.name, show(s) + if (zerosBad && s.value == 0.0) " · no capturado" else "", f.raw) } },
                i.freezeValues.flatMap { f -> f.signals.map { s -> if (zerosBad && s.value == 0.0) Tone.MUTED else null } }))
        })

        val running = (byPid[0x0C]?.firstOrNull()?.value ?: 0.0) > 300
        val sorted = i.signals.sortedBy { it.pid }
        sections += Section(++n, "Datos en vivo (${i.signals.size} señales de ${i.supportedPids.size} PID declarados)", listOf(
            Table(listOf("PID", "Parámetro", "Valor", "Estado", "Bytes"), listOf(0.5f, 2.6f, 1.6f, 1.0f, 1.6f),
                sorted.map { s -> listOf(hex(s.pid), s.name, show(s), liveStatus(s, running, runtime)?.label ?: "", i.rawByPid[s.pid].orEmpty()) },
                sorted.map { s -> liveStatus(s, running, runtime)?.let { tone(it) } }),
            Para("Estado: rangos genéricos de taller, no tolerancias JAC. Vacío = sin rango de referencia en la app.", Tone.MUTED),
        ))

        sections += Section(++n, "Monitores de preparación", buildList {
            val a = i.readiness; val b = i.readinessCycle
            if (a == null) add(Para("Sin dato.")) else add(Table(listOf("Monitor", "Desde el último borrado", "Este ciclo"), listOf(2.4f, 1.6f, 1.6f),
                a.supported.map { m -> listOf(m.name, m.state.label, b?.monitors?.firstOrNull { it.name == m.name }?.state?.label ?: "—") },
                a.supported.map { m -> if (m.state == MonitorState.INCOMPLETE) Tone.ATTENTION else null }))
            i.vehicleInfo?.inUsePerformance?.takeIf { it.isNotEmpty() }?.let { ipt -> add(Para("Rendimiento en uso (090B): " + ipt.joinToString(", ") { (k, v) -> "$k $v" }, Tone.MUTED)) }
        })

        sections += Section(++n, "Pruebas a bordo (Mode 06)", listOf(
            if (i.mode06.isEmpty()) Para("La ECU no entregó resultados Mode 06.") else Table(listOf("MID/TID", "Monitor", "Valor", "Mín", "Máx", "Resultado"), listOf(0.9f, 2.2f, 1.0f, 0.8f, 0.8f, 0.9f),
                i.mode06.map { listOf("${hex(it.mid)}/${hex(it.tid)}", it.monitorName, "${num(it.value)} ${it.unit}".trim(), num(it.min), num(it.max), if (it.passed) "OK" else "FUERA") },
                i.mode06.map { if (it.passed) null else Tone.CHECK }),
        ))

        if (i.tests.isNotEmpty()) sections += Section(++n, "Pruebas dinámicas", i.tests.flatMap { r ->
            listOf(
                Para("${r.title} — ${t(r.startedAtMs)} · ${r.frames} muestras · ${"%.1f".format(Locale.ROOT, r.effectiveHz)} Hz", tone(r.verdict), bold = true),
                Para(r.summary),
                Table(listOf("Medida", "Valor", "Estado", "Nota"), listOf(2f, 1.2f, 1f, 3f),
                    r.metrics.map { listOf(it.name, it.value?.let { v -> "${num(v)} ${it.unit}".trim() } ?: "—", it.status.label, it.note) }, r.metrics.map { tone(it.status) }),
            )
        })

        sections += Section(++n, "Registro de comunicación", buildList {
            val byKind = i.exchanges.groupingBy { it.kind }.eachCount()
            add(KeyValues(listOf(
                "Comandos" to "${i.exchanges.size} (" + byKind.entries.joinToString(", ") { "${it.key} ${it.value}" } + ")",
                "Latencia" to if (i.exchanges.isEmpty()) "—" else "mediana ${median(i.exchanges.map { it.elapsedMs })} ms · máxima ${i.exchanges.maxOf { it.elapsedMs }} ms",
                "Escaneo" to (i.scanAtMs?.let { t(it) } ?: "no realizado"),
            )))
            val fails = i.exchanges.filter { it.kind != ResponseKind.OBD_DATA && it.kind != ResponseKind.RECOGNIZED }.takeLast(30)
            if (fails.isNotEmpty()) add(Table(listOf("Hora", "Comando", "Resultado", "ms"), listOf(1.2f, 1.2f, 3f, 0.6f),
                fails.map { listOf(t(it.atEpochMs).takeLast(8), it.command, "${it.kind} ${it.raw.trim().replace("\n", " ").take(40)}", "${it.elapsedMs}") }))
            i.errors.takeLast(10).forEach { add(Para("Error: $it", Tone.ATTENTION)) }
            add(Para("Cada comando con sus bytes exactos está en el JSON de la sesión, junto a este PDF.", Tone.MUTED))
        })

        sections += Section(++n, "Origen de los datos y alcance", listOf(
            Para("Valores, códigos, monitores, Mode 06 y cuadro congelado: respuesta de la ECU de motor por OBD-II (SAE J1979) en esta sesión. Solo lectura: no se borró ningún código ni se escribió en la ECU."),
            Para("Descripciones de códigos: definición genérica SAE J2012, no texto JAC. Rangos ATENCIÓN/REVISAR: referencia genérica de la app."),
            Para("JAC no publica manual de taller, diagramas ni valores nominales para la T8, ni una API de diagnóstico. El perfil de ECU cita fuentes con nivel de evidencia (research/source-manifest.json)."),
            Para("Revisado por: ______________________   Taller: ______________________   Fecha: ____________"),
        ))

        return WorkshopDoc(
            title = "Informe técnico de diagnóstico",
            subtitle = "${i.declaredModel.ifBlank { "Vehículo" }} ${i.declaredYear}".trim() + " · OBD-II solo lectura",
            meta = listOf(
                "VIN" to (i.vin ?: "—"), "Fecha" to t(i.generatedAtMs), "Sesión" to i.sessionId.ifBlank { "—" },
                "ECU" to (i.vehicleInfo?.calibrationIds?.joinToString()?.ifBlank { null } ?: "—"), "App" to "JAC Diagnostic Lab ${i.appVersion}",
            ),
            summary = summary,
            sections = sections,
            footer = "JAC Diagnostic Lab ${i.appVersion} · solo lectura · no autoriza a circular",
        )
    }

    /** Texto plano del documento (huella y pruebas). */
    fun build(i: WorkshopInput, zone: ZoneId = ZoneId.systemDefault()): ReadingReportDocument {
        val body = buildDoc(i, zone).toLines()
        val hash = sha256Hex(body.joinToString("\n").toByteArray(Charsets.UTF_8))
        return ReadingReportDocument(body.getOrElse(4) { "" }, body + listOf("", "Huella SHA-256 del texto: $hash"), hash)
    }

    private fun liveStatus(s: Signal, running: Boolean, runtime: Double?): HealthStatus? {
        // Motor recién arrancado: frío no es falla.
        if (s.key == "05" && (runtime ?: 9999.0) < 1200 && (s.value ?: 99.0) < 70) return null
        val st = Present.gaugeStatus(s.key, s.value, running)
        return if (st == HealthStatus.NO_DATA) null else st
    }

    fun tone(s: HealthStatus) = when (s) {
        HealthStatus.CHECK -> Tone.CHECK; HealthStatus.ATTENTION -> Tone.ATTENTION; HealthStatus.OK -> Tone.OK
        HealthStatus.INFO -> Tone.INFO; HealthStatus.NO_DATA -> Tone.MUTED
    }
    private fun statusText(s: ResolutionStatus) = when (s) {
        ResolutionStatus.IDENTIFIED -> "identificado"; ResolutionStatus.SUPPLIER_ONLY -> "solo proveedor"
        ResolutionStatus.AMBIGUOUS -> "ambiguo"; ResolutionStatus.PARTIAL -> "no resuelto"
    }
    private fun originText(o: String) = when (o) {
        "ECU_MODE09" -> "respuesta Mode 09 de la ECU"; "ECU_LABEL_TYPED" -> "etiqueta escrita por el usuario"; else -> o
    }
    private fun show(s: Signal) = (s.text ?: (num(s.value) + if (s.unit.isNotBlank()) " ${s.unit}" else "")).trim()
    private fun label(c: DtcCategory) = when (c) { DtcCategory.STORED -> "almacenado"; DtcCategory.PENDING -> "pendiente"; DtcCategory.PERMANENT -> "permanente" }
    private fun short(c: DtcCategory) = when (c) { DtcCategory.STORED -> "ALM"; DtcCategory.PENDING -> "PEND"; DtcCategory.PERMANENT -> "PERM" }
    private fun plural(c: DtcCategory) = label(c) + "s"
    private fun sid(c: DtcCategory) = when (c) { DtcCategory.STORED -> "03"; DtcCategory.PENDING -> "07"; DtcCategory.PERMANENT -> "0A" }
    private fun num(v: Double?): String = when {
        v == null -> "sin dato"
        v % 1.0 == 0.0 -> v.toLong().toString()
        else -> "%.2f".format(Locale.ROOT, v)
    }
    private fun hex(v: Int) = "%02X".format(v)
    private fun median(xs: List<Long>): Long = xs.sorted().let { it[it.size / 2] }
}

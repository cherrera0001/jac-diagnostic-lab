package app.jac.diagnosticlab.ui

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.jac.diagnosticlab.LabState
import app.jac.diagnosticlab.analysis.DashboardLinkStatus
import app.jac.diagnosticlab.analysis.DrivingGuidanceEngine
import app.jac.diagnosticlab.analysis.HealthStatus
import app.jac.diagnosticlab.analysis.ShiftCue
import app.jac.diagnosticlab.analysis.TripGuidanceSummary
import app.jac.diagnosticlab.bt.LinkPhase
import app.jac.diagnosticlab.dynamic.GuidedTests
import app.jac.diagnosticlab.dynamic.TestContext
import app.jac.diagnosticlab.dynamic.TestResult
import app.jac.diagnosticlab.obd.DtcCategory
import app.jac.diagnosticlab.obd.MonitorState

/* ─────────────────────────── Paleta ─────────────────────────── */

private val Bg = Color(0xFF0F1419)
private val Panel = Color(0xFF1A222C)
private val Panel2 = Color(0xFF232D39)
private val Text1 = Color(0xFFECEFF2)
private val Text2 = Color(0xFF8A96A3)
private val Accent = Color(0xFFF5C16C)

private fun tone(s: HealthStatus?): Color = when (s) {
    HealthStatus.OK, HealthStatus.INFO -> Color(0xFF66BB6A)
    HealthStatus.ATTENTION -> Color(0xFFFFCA28)
    HealthStatus.CHECK -> Color(0xFFEF5350)
    HealthStatus.NO_DATA -> Color(0xFF5C6773)
    HealthStatus.UNVERIFIED -> Color(0xFF9575CD)
    null -> Text1
}

/** Acciones que la interfaz necesita; el controlador las implementa. */
class ShellActions(
    val connect: () -> Unit,
    val reconnect: () -> Unit,
    val fullScan: () -> Unit,
    val reset: () -> Unit,
    val startTest: (String) -> Unit,
    val stopTest: () -> Unit,
    val mark: () -> Unit,
    val markFullTank: () -> Unit = {},
)

@Composable
fun AppShell(
    state: LabState,
    actions: ShellActions,
    exportSection: @Composable ColumnScope.() -> Unit,
    advancedSection: @Composable ColumnScope.() -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var handsFreeBoard by rememberSaveable { mutableStateOf(false) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val liveOn = state.activeTest == "LIVE"
    val showBoard = handsFreeBoard || (tab == 1 && liveOn && isLandscape)
    if (showBoard) {
        HandsFreeDashboard(
            state = state,
            isLandscape = isLandscape,
            onExit = { handsFreeBoard = false },
            onStop = {
                handsFreeBoard = false
                actions.stopTest()
            },
        )
        return
    }
    val tabs = listOf("Estado", "En vivo", "Pruebas", "Datos", "Señales", "Informe")
    Scaffold(
        containerColor = Bg,
        topBar = { ConnectionBar(state, actions) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(Panel)) {
                NavigationBar(containerColor = Panel, windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0), modifier = Modifier.height(60.dp)) {
                    tabs.forEachIndexed { i, label ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Box(Modifier.size(6.dp).clip(CircleShape).background(if (tab == i) Accent else Color.Transparent)) },
                            label = { Text(label, fontSize = 12.sp) },
                        )
                    }
                }
                VersionFooter(state.appVersion)
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.error?.let { ErrorStrip(it, state.recovery) }
            when (tab) {
                0 -> StatusTab(state, actions) { tab = 3 }
                1 -> LiveTab(state, actions) { handsFreeBoard = true }
                2 -> TestsTab(state, actions)
                3 -> DataTab(state)
                4 -> SignalsHubTab()
                else -> ReportTab(exportSection, advancedSection, actions)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Pie central con la versión instalada; siempre visible bajo las pestañas. */
@Composable
private fun VersionFooter(version: String) {
    Text(
        "JAC Diagnostic Lab ${version.ifBlank { "v?" }} · solo lectura",
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 6.dp, top = 2.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        fontSize = 11.sp,
        color = Color(0xFF9AA3AD),
    )
}

/* ─────────────────────────── Barra de conexión ─────────────────────────── */

@Composable
private fun ConnectionBar(state: LabState, actions: ShellActions) {
    val (color, label) = when {
        state.busy -> Accent to "Trabajando…"
        state.activeTest != null -> Color(0xFF42A5F5) to "Midiendo"
        state.ecuTalking && state.linkPhase != LinkPhase.DISCONNECTED -> Color(0xFF66BB6A) to "Conectado a la ECU"
        state.linkPhase == LinkPhase.DISCONNECTED -> Color(0xFF5C6773) to "Sin conexión"
        else -> Accent to state.linkPhase.label
    }
    Row(
        Modifier.fillMaxWidth().background(Panel).statusBarsPadding().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("JAC T8 · diagnóstico", color = Text1, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(if (state.busy) state.statusLine else label, color = Text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (state.busy) TextButton(onClick = actions.reset) { Text("Cancelar", color = Accent) }
    }
}

@Composable
private fun ErrorStrip(error: String, recovery: String?) {
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x33EF5350)).clickable { open = !open }.padding(12.dp),
    ) {
        Column {
            Text(error.take(90), color = Color(0xFFFFCDD2), fontSize = 13.sp, maxLines = if (open) 6 else 1, overflow = TextOverflow.Ellipsis)
            if (open && recovery != null) Text(recovery, color = Text2, fontSize = 12.sp)
        }
    }
}

/* ─────────────────────────── Estado ─────────────────────────── */

@Composable
private fun StatusTab(state: LabState, actions: ShellActions, openData: () -> Unit) {
    val connected = state.ecuTalking && state.linkPhase != LinkPhase.DISCONNECTED
    if (state.health.isEmpty()) {
        Hero(
            title = if (connected) "Conectado" else "Sin diagnóstico",
            subtitle = if (connected) "Lee todo lo que la ECU informa." else "Motor encendido, vehículo detenido, adaptador enchufado.",
            color = Accent,
        )
        PrimaryButton(if (connected) "Hacer diagnóstico completo" else "Conectar y diagnosticar", !state.busy) {
            if (connected) actions.fullScan() else actions.connect()
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
        return
    }

    val stored = state.dtcs.filter { it.category == DtcCategory.STORED }.flatMap { it.codes.map { c -> c.code } }.distinct()
    val pending = state.dtcs.filter { it.category == DtcCategory.PENDING }.flatMap { it.codes.map { c -> c.code } }.distinct() - stored.toSet()
    val problems = state.health.count { it.status == HealthStatus.CHECK }
    val warnings = state.health.count { it.status == HealthStatus.ATTENTION }
    val overall = Present.worst(state.health)
    Hero(
        title = when (overall) {
            HealthStatus.CHECK -> if (problems == 1) "1 problema" else "$problems problemas"
            HealthStatus.ATTENTION -> if (warnings == 1) "1 aviso" else "$warnings avisos"
            else -> "Todo en orden"
        },
        subtitle = buildString {
            if (overall == HealthStatus.CHECK && warnings > 0) append("y $warnings aviso(s) · ")
            append("${state.scanSignals.size} datos leídos")
            state.readiness?.let { append(if (it.compressionIgnition) " · diésel" else " · gasolina") }
        },
        color = tone(overall),
    )

    OutlinedButton(onClick = openData, modifier = Modifier.fillMaxWidth()) { Text("Ver los ${state.scanSignals.size} datos leídos", color = Text1) }

    // Cada problema y aviso se puede abrir: qué es, con qué dato se detectó y qué hacer.
    val findings = state.health.filter { it.status == HealthStatus.CHECK || it.status == HealthStatus.ATTENTION }.sortedByDescending { it.status.rank }
    if (findings.isNotEmpty()) {
        SectionTitle("Problemas y avisos (toque para ver el detalle)")
        var openFinding by rememberSaveable { mutableStateOf<Int?>(null) }
        findings.forEachIndexed { idx, item ->
            val isOpen = openFinding == idx
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (isOpen) Panel2 else Panel)
                    .clickable { openFinding = if (isOpen) null else idx }.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.status.label, color = tone(item.status), fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.width(84.dp))
                    Text("${item.system}: ${item.title}", color = Text1, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(if (isOpen) "▲" else "▼", color = Text2, fontSize = 12.sp)
                }
                if (isOpen) {
                    Text(item.detail, color = Text1, fontSize = 13.sp)
                    Text("Dato: ${item.evidence}", color = Text2, fontSize = 12.sp)
                }
            }
        }
    }

    if (stored.isNotEmpty() || pending.isNotEmpty()) {
        SectionTitle("Códigos de falla (toque para ver qué revisar)")
        var openCode by rememberSaveable { mutableStateOf<String?>(null) }
        (stored.map { it to "Guardado" } + pending.map { it to "Pendiente" }).forEach { (code, kind) ->
            val key = "$code-$kind"
            val alsoPending = kind == "Guardado" && state.dtcs.any { r -> r.category == DtcCategory.PENDING && r.codes.any { it.code == code } }
            DtcRow(code, if (alsoPending) "Guardado y pendiente (volvió a detectarse)" else kind, if (kind == "Guardado") HealthStatus.CHECK else HealthStatus.ATTENTION) {
                openCode = if (openCode == key) null else key
            }
            if (openCode == key) DtcDetail(code, state)
        }
    }

    SectionTitle("Sistemas")
    val bySystem = state.health.groupBy { Present.tileOf(it) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    Present.systems.filter { it in bySystem && it != "Códigos" }.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { sys ->
                val items = bySystem.getValue(sys)
                val s = Present.worst(items)
                val lead = items.maxByOrNull { it.status.rank }
                SystemTile(sys, s, lead?.title.orEmpty(), open == sys, Modifier.weight(1f)) { open = if (open == sys) null else sys }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
        val expanded = row.firstOrNull { it == open }
        if (expanded != null) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel2).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                bySystem.getValue(expanded).forEach { item ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(tone(item.status)))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(item.title, color = Text1, fontSize = 14.sp)
                            if (item.detail.isNotBlank() && item.status.rank >= HealthStatus.ATTENTION.rank) {
                                Text(item.detail, color = Text2, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    state.readiness?.let { r ->
        val sup = r.monitors.filter { it.state != MonitorState.NOT_SUPPORTED }
        val done = sup.count { it.state == MonitorState.COMPLETE }
        SectionTitle("Autopruebas de la ECU")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$done de ${sup.size}", color = Text1, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Text("completas", color = Text2, fontSize = 14.sp)
        }
        LinearProgressIndicator(
            progress = { if (sup.isEmpty()) 0f else done.toFloat() / sup.size },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = if (done == sup.size) tone(HealthStatus.OK) else tone(HealthStatus.ATTENTION),
            trackColor = Panel2,
        )
    }

    Spacer(Modifier.height(4.dp))
    PrimaryButton("Actualizar diagnóstico", !state.busy && state.activeTest == null) { actions.fullScan() }
    if (!connected) OutlinedButton(onClick = actions.connect, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) { Text("Reconectar") }
    Text("Rangos genéricos de taller, no especificación JAC. Solo lectura: no borra códigos.", color = Text2, fontSize = 11.sp)
}

@Composable
private fun Hero(title: String, subtitle: String, color: Color) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.height(6.dp))
        Text(title, color = Text1, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Text2, fontSize = 14.sp)
    }
}

@Composable
private fun DtcDetail(code: String, state: LabState) {
    val hint = app.jac.diagnosticlab.report.WorkshopReport.hints[code]
    val byPid = state.scanSignals.groupBy { it.pid }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel2).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (hint == null) {
            Text("La app no tiene guía para $code. Buscarlo en el manual de taller JAC con el cuadro congelado (Informe → PDF de taller).", color = Text1, fontSize = 13.sp)
            return@Column
        }
        Text(hint.meaning, color = Text1, fontSize = 13.sp)
        Text("Ahora / al guardarse la falla", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        hint.related.forEach { pid ->
            val now = byPid[pid]?.firstOrNull()
            val frz = state.freezeValues.firstOrNull { it.pid == pid }?.signals?.firstOrNull()
            if (now != null || frz != null) {
                val name = now?.name ?: frz?.name.orEmpty()
                fun v(s: app.jac.diagnosticlab.obd.Signal?) = s?.let { it.text ?: "${it.value?.let { x -> if (x % 1.0 == 0.0) x.toLong().toString() else "%.1f".format(x) } ?: "—"} ${it.unit}" } ?: "—"
                Text("• $name: ${v(now)}  ·  falla: ${v(frz)}", color = Text2, fontSize = 12.sp)
            }
        }
        Text("Qué revisar, en orden", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        hint.checks.forEachIndexed { i, c -> Text("${i + 1}. $c", color = Text1, fontSize = 13.sp) }
        Text("Verificación genérica (SAE J2012 + práctica de taller), no procedimiento JAC.", color = Text2, fontSize = 11.sp)
    }
}

@Composable
private fun DtcRow(code: String, kind: String, status: HealthStatus, onClick: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(code, color = tone(status), fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.width(76.dp))
        Column(Modifier.weight(1f)) {
            Text(Present.dtcTitle(code), color = Text1, fontSize = 14.sp)
            Text(kind, color = Text2, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SystemTile(name: String, status: HealthStatus, lead: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(if (selected) Panel2 else Panel).clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(tone(status)))
            Spacer(Modifier.width(8.dp))
            Text(Present.word(status), color = tone(status), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(name, color = Text1, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(lead, color = Text2, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/* ─────────────────────────── En vivo ─────────────────────────── */

private data class GaugeSpec(val keys: List<String>, val label: String, val unit: String)

private val liveGauges = listOf(
    GaugeSpec(listOf("0C"), "RPM", "rpm"),
    GaugeSpec(listOf("0D"), "Velocidad", "km/h"),
    GaugeSpec(listOf("05"), "Refrigerante", "°C"),
    GaugeSpec(listOf("42"), "Tensión", "V"),
    GaugeSpec(listOf("04"), "Carga", "%"),
    GaugeSpec(listOf("49"), "Pedal D", "%"),
    GaugeSpec(listOf("4A"), "Pedal E", "%"),
    GaugeSpec(listOf("70.RA", "0B"), "Turbo / admisión", "kPa"),
    GaugeSpec(listOf("23", "6D.RA", "59"), "Riel", "kPa"),
    GaugeSpec(listOf("62"), "Torque", "%"),
    GaugeSpec(listOf("10"), "Aire (MAF)", "g/s"),
    GaugeSpec(listOf("7A.DP"), "DPF presión", "kPa"),
    GaugeSpec(listOf("8B.TRIG"), "DPF hollín", "%"),
    GaugeSpec(listOf("78.S1"), "Escape", "°C"),
    GaugeSpec(listOf("5E"), "Consumo", "L/h"),
)

@Composable
private fun LiveTab(state: LabState, actions: ShellActions, openBoard: () -> Unit) {
    val liveOn = state.activeTest == "LIVE" || state.activeTest == GuidedTests.FAST_FAULT_ID
    val fastFaultOn = state.activeTest == GuidedTests.FAST_FAULT_ID
    val other = state.activeTest != null && !liveOn
    if (!liveOn) {
        Hero("En vivo", if (other) "Hay una prueba en curso." else "Modo Tablero / Tablet hands-free, Captura rápida (8 PIDs) y sensores en tiempo real.", Accent)
        PrimaryButton("Iniciar en vivo", !state.busy && !other && state.ecuTalking) { actions.startTest("LIVE") }
        OutlinedButton(
            onClick = { actions.startTest(GuidedTests.FAST_FAULT_ID) },
            enabled = !state.busy && !other && state.ecuTalking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Captura rápida de falla (8 PIDs críticos)", color = Accent)
        }
        OutlinedButton(onClick = openBoard, modifier = Modifier.fillMaxWidth()) {
            Text("Abrir Modo Tablero (Hands-Free)", color = Text1)
        }
        FuelEstimateCard(state, actions.markFullTank)
        if (!state.ecuTalking) Text("Primero conecta desde Estado.", color = Text2, fontSize = 13.sp)
        state.lastTripSummary?.let { TripSummaryCard(it) }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        LinkStatusChip(state.liveLinkStatus)
        Spacer(Modifier.width(8.dp))
        val modeTag = if (fastFaultOn) "8 PIDs · " else ""
        Text("$modeTag%.1f Hz · %d s".format(state.testHz, state.testElapsedMs / 1000), color = Text2, fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = openBoard) { Text("Tablero", color = Accent) }
        TextButton(onClick = actions.stopTest) { Text("Detener", color = Accent) }
    }
    if (fastFaultOn) {
        OutlinedButton(onClick = actions.mark, modifier = Modifier.fillMaxWidth()) {
            Text("MARCAR EVENTO (Captura rápida 8 PIDs)", color = Color(0xFFFF8A80), fontWeight = FontWeight.Bold)
        }
    }
    state.drivingGuidance?.let { GuidanceStrip(it) }
    FuelEstimateCard(state, actions.markFullTank)
    GaugeGrid(state)
}

@Composable
private fun FuelEstimateCard(state: LabState, onMarkFullTank: () -> Unit) {
    val est = state.fuelEstimate
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("COMBUSTIBLE · ESTIMACIÓN/INFERENCIA (R7)", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    est?.summaryText() ?: "Sin estimación · ESTIMACIÓN/INFERENCIA",
                    color = Text1,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            OutlinedButton(onClick = onMarkFullTank) {
                Text("Estanque lleno", fontSize = 12.sp, color = Accent)
            }
        }
        Text(
            est?.note ?: "Usa PID 0x2F si la ECU lo soporta, o integra PID 0x5E / MAF (0x10) desde «Estanque lleno». Nunca es medición directa.",
            color = Text2,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun LinkStatusChip(status: DashboardLinkStatus) {
    val color = when (status) {
        DashboardLinkStatus.CONNECTED -> Color(0xFF2ECC71)
        DashboardLinkStatus.RECONNECTING -> Color(0xFFFFC107)
        DashboardLinkStatus.PAUSED -> Color(0xFF90A4AE)
    }
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(status.label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

private fun cueColor(cue: ShiftCue): Color = when (cue) {
    ShiftCue.SHIFT_DOWN -> Color(0xFFFF5252)
    ShiftCue.SHIFT_UP -> Color(0xFFFFC107)
    ShiftCue.COLD_ENGINE -> Color(0xFF4FC3F7)
    ShiftCue.OPTIMAL -> Color(0xFF2ECC71)
    ShiftCue.IDLE_OR_STOPPED, ShiftCue.INSUFFICIENT_DATA -> Color(0xFFB0BEC5)
}

@Composable
private fun GuidanceStrip(g: app.jac.diagnosticlab.analysis.DrivingGuidanceSnapshot) {
    val c = cueColor(g.cue)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.copy(alpha = 0.14f)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(g.cue.arrow, color = c, fontSize = 36.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(44.dp))
        Column(Modifier.weight(1f)) {
            Text(g.cue.label, color = c, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(g.note, color = Text1, fontSize = 12.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(g.gearInference.label, color = Text1, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Marcha (inf.)", color = Text2, fontSize = 10.sp)
        }
    }
}

@Composable
private fun TripSummaryCard(summary: TripGuidanceSummary) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("RESUMEN POST-VIAJE (R6)", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        summary.summaryLines.forEach { line ->
            Text("• $line", color = Text1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "${summary.movingSamples} muestras en movimiento de ${summary.totalSamples} totales · sin puntaje global inventado.",
            color = Text2,
            fontSize = 11.sp,
        )
    }
}

/**
 * SPEC-034 R6: Modo Tablero / Tablet Hands-Free.
 * Alto contraste, tipografía gigante para lectura de un vistazo en soporte fijo, cero interacción
 * requerida al conducir y pantalla siempre activa.
 */
@Composable
private fun HandsFreeDashboard(
    state: LabState,
    isLandscape: Boolean,
    onExit: () -> Unit,
    onStop: () -> Unit,
) {
    val view = LocalView.current
    DisposableEffect(view) {
        val prev = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = prev }
    }
    val live = state.testLive?.values.orEmpty()
    val g = state.drivingGuidance ?: DrivingGuidanceEngine.evaluateFrame(live)
    val c = cueColor(g.cue)
    val rpmTxt = live["0C"]?.let { "%.0f".format(it) } ?: "—"
    val spdTxt = live["0D"]?.let { "%.0f".format(it) } ?: "—"
    val loadTxt = live["04"]?.let { "%.0f".format(it) } ?: "—"
    val ectTxt = live["05"]?.let { "%.0f".format(it) } ?: "—"

    Column(
        Modifier.fillMaxSize().background(Color(0xFF05080C)).statusBarsPadding().navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LinkStatusChip(state.liveLinkStatus)
            Spacer(Modifier.width(12.dp))
            Text("MODO TABLERO · SOLO LECTURA", color = Color(0xFFB0BEC5), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (state.activeTest == "LIVE") {
                TextButton(onClick = onStop) { Text("Detener viaje", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold) }
            }
            TextButton(onClick = onExit) { Text("Salir", color = Accent, fontWeight = FontWeight.Bold) }
        }

        if (isLandscape) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(
                    Modifier.weight(1.1f).fillMaxSize().clip(RoundedCornerShape(20.dp)).background(c.copy(alpha = 0.16f)).padding(20.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(g.actionLabel, color = c, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text(g.cue.arrow, color = c, fontSize = 86.sp, fontWeight = FontWeight.Black)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val gearDisplay = if (g.recommendedGear != null && g.gearInference.gear != null) {
                            "${g.gearInference.label} → ${g.recommendedGear}ª"
                        } else {
                            "MARCHA EST. ${g.gearInference.label}"
                        }
                        Text(gearDisplay, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text("Inferencia RPM/Vel · no es sensor de caja", color = Color(0xFFB0BEC5), fontSize = 12.sp)
                    }
                }
                Column(Modifier.weight(1.4f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BoardMetricTile("RPM", rpmTxt, "rpm", Color.White, Modifier.weight(1f))
                        BoardMetricTile("VELOCIDAD", spdTxt, "km/h", Color.White, Modifier.weight(1f))
                    }
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BoardMetricTile("CARGA MOTOR", loadTxt, "%", if (g.isLugging) Color(0xFFFF5252) else Color.White, Modifier.weight(1f))
                        BoardMetricTile("REFRIGERANTE", ectTxt, "°C", if (g.isColdEngine) Color(0xFF4FC3F7) else Color(0xFF2ECC71), Modifier.weight(1f))
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.copy(alpha = 0.16f)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(g.actionLabel, color = c, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text(g.cue.arrow, color = c, fontSize = 76.sp, fontWeight = FontWeight.Black)
                val gearDisplay = if (g.recommendedGear != null && g.gearInference.gear != null) {
                    "${g.gearInference.label} → ${g.recommendedGear}ª"
                } else {
                    "MARCHA EST. ${g.gearInference.label}"
                }
                Text(gearDisplay, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Inferencia RPM/Velocidad (no sensor directo)", color = Color(0xFFB0BEC5), fontSize = 12.sp)
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BoardMetricTile("RPM", rpmTxt, "rpm", Color.White, Modifier.weight(1f))
                BoardMetricTile("VELOCIDAD", spdTxt, "km/h", Color.White, Modifier.weight(1f))
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BoardMetricTile("CARGA MOTOR", loadTxt, "%", if (g.isLugging) Color(0xFFFF5252) else Color.White, Modifier.weight(1f))
                BoardMetricTile("REFRIGERANTE", ectTxt, "°C", if (g.isColdEngine) Color(0xFF4FC3F7) else Color(0xFF2ECC71), Modifier.weight(1f))
            }
        }
        Text(
            "Uso seguro: fijar en soporte; no manipular al conducir · ${state.appVersion}",
            color = Color(0xFF90A4AE),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun BoardMetricTile(label: String, value: String, unit: String, valueColor: Color, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).background(Color(0xFF111820)).padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color(0xFF90A4AE), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = valueColor, fontSize = 52.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(6.dp))
            Text(unit, color = Color(0xFF90A4AE), fontSize = 18.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

@Composable
private fun GaugeGrid(state: LabState) {
    val live = state.testLive?.values.orEmpty()
    val running = (live["0C"] ?: 0.0) > 400
    val shown = liveGauges.mapNotNull { g -> g.keys.firstOrNull { it in live }?.let { k -> g to k } }
    shown.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { (g, k) ->
                val v = live[k]
                val history = state.testHistory.mapNotNull { it.values[k] }
                val (txt, unit) = Present.value(v, g.unit)
                Gauge(g.label, txt, unit, Present.gaugeStatus(k, v, running), history, Modifier.weight(1f))
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun Gauge(label: String, value: String, unit: String, status: HealthStatus?, history: List<Double>, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Panel).padding(12.dp)) {
        Text(label, color = Text2, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = tone(status), fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            Text(unit, color = Text2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 5.dp))
        }
        Sparkline(history, tone(status).copy(alpha = 0.8f))
    }
}

@Composable
private fun Sparkline(values: List<Double>, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(26.dp)) {
        if (values.size < 2) return@Canvas
        val min = values.min(); val max = values.max()
        val span = (max - min).takeIf { it > 1e-9 } ?: 1.0
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = size.height - ((v - min) / span * size.height).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
        drawCircle(color, 2.5.dp.toPx(), Offset(size.width, size.height - ((values.last() - min) / span * size.height).toFloat()))
    }
}

/* ─────────────────────────── Pruebas ─────────────────────────── */

private val testBlurb = mapOf(
    "IDLE" to "¿El motor gira parejo?",
    "WARMUP" to "¿Calienta como debe? (termostato)",
    "SNAP" to "¿Responden riel y turbo al acelerar?",
    "ELECTRIC" to "¿El alternador aguanta la carga?",
    "ROAD" to "Graba un recorrido y detecta cortes",
    "FAST_FAULT" to "Perfil ligero de 8 PIDs críticos (evita saturar ELM327)",
)

@Composable
private fun TestsTab(state: LabState, actions: ShellActions) {
    val active = state.activeTest?.takeIf { it != "LIVE" }?.let { GuidedTests.byId(it) }
    if (active != null) {
        RunningTest(state, active.title, active.durationMs, active.context == TestContext.ROAD, actions)
        return
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val blocked = state.busy || state.activeTest != null || !state.ecuTalking
    if (!state.ecuTalking) Text("Conecta desde Estado para habilitar las pruebas.", color = Text2, fontSize = 13.sp)
    GuidedTests.catalog.forEach { t ->
        val last = state.testResults.lastOrNull { it.testId == t.id }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected == t.id) Panel2 else Panel)
                .clickable { selected = if (selected == t.id) null else t.id }.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title.substringBefore(" ("), color = Text1, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(testBlurb[t.id].orEmpty(), color = Text2, fontSize = 13.sp)
                }
                if (last != null) Pill(Present.word(last.verdict), tone(last.verdict))
                else Text(t.durationMs?.let { "${it / 1000} s" } ?: "libre", color = Text2, fontSize = 12.sp)
            }
            if (selected == t.id) {
                Text(if (t.context == TestContext.ROAD) "Con acompañante o en banco de rodillos" else "Vehículo detenido, freno de mano", color = Accent, fontSize = 12.sp)
                t.steps.forEachIndexed { i, s -> Text("${i + 1}. $s", color = Text1, fontSize = 13.sp) }
                PrimaryButton(if (t.context == TestContext.ROAD) "Iniciar grabación" else "Iniciar", !blocked) { actions.startTest(t.id) }
                last?.let { ResultCard(it) }
            }
        }
    }
}

@Composable
private fun RunningTest(state: LabState, title: String, durationMs: Long?, road: Boolean, actions: ShellActions) {
    Text(title.substringBefore(" ("), color = Text1, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    if (durationMs != null) {
        LinearProgressIndicator(
            progress = { (state.testElapsedMs.toFloat() / durationMs).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = Accent, trackColor = Panel2,
        )
        Text("${state.testElapsedMs / 1000} / ${durationMs / 1000} s", color = Text2, fontSize = 13.sp)
    } else {
        Text("Grabando · ${state.testElapsedMs / 1000} s · %.1f actualizaciones/s".format(state.testHz), color = Text2, fontSize = 13.sp)
    }
    state.testCue?.let { cue ->
        val urgent = cue.startsWith("¡")
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (urgent) Color(0xFFC62828) else Panel).padding(20.dp),
            contentAlignment = Alignment.Center,
        ) { Text(cue, color = Color.White, fontSize = if (urgent) 26.sp else 18.sp, fontWeight = FontWeight.Bold) }
    }
    if (road) {
        Button(
            onClick = actions.mark,
            modifier = Modifier.fillMaxWidth().height(80.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350)),
            shape = RoundedCornerShape(16.dp),
        ) { Text("MARCAR EVENTO", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White) }
        state.testEvents.takeLast(4).asReversed().forEach { e ->
            Text("● ${e.kind}", color = if (e.manual) Accent else tone(HealthStatus.CHECK), fontSize = 13.sp)
        }
    }
    GaugeGrid(state)
    OutlinedButton(onClick = actions.stopTest, modifier = Modifier.fillMaxWidth()) { Text("Terminar y ver resultado") }
}

@Composable
private fun ResultCard(r: TestResult) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Bg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(r.summary, color = Text1, fontSize = 13.sp)
        r.metrics.filter { it.value != null }.forEach { m ->
            val (v, u) = Present.value(m.value, m.unit)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(tone(m.status)))
                Spacer(Modifier.width(8.dp))
                Text(m.name, color = Text2, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("$v $u".trim(), color = Text1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        r.events.forEach { e -> Text("● ${e.kind}: ${e.detail}", color = if (e.manual) Accent else tone(HealthStatus.CHECK), fontSize = 12.sp) }
    }
}

/* ─────────────────────────── Datos ─────────────────────────── */

@Composable
private fun DataTab(state: LabState) {
    if (state.scanSignals.isEmpty()) {
        Hero("Datos", "Aparecen después del diagnóstico completo.", Accent)
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Buscar (ej. turbo, riel, DPF)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    val q = query.trim().lowercase()
    val filtered = state.scanSignals.filter { q.isEmpty() || it.name.lowercase().contains(q) || Present.groupOf(it.pid).lowercase().contains(q) }
    var open by rememberSaveable { mutableStateOf(setOf("Motor")) }
    Present.groupOrder.forEach { group ->
        val rows = filtered.filter { Present.groupOf(it.pid) == group }
        if (rows.isEmpty()) return@forEach
        val isOpen = q.isNotEmpty() || group in open
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).clickable { open = if (group in open) open - group else open + group }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(group, color = Text1, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${rows.size}", color = Text2, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Text(if (isOpen) "▲" else "▼", color = Text2, fontSize = 12.sp)
        }
        if (isOpen) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                rows.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, color = Text2, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(Present.signalValue(s), color = Text1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
    if (state.mode06.isNotEmpty()) {
        val failed = state.mode06.count { !it.passed }
        SectionTitle("Autopruebas internas (Mode 06)")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${state.mode06.size - failed}/${state.mode06.size}", color = tone(if (failed == 0) HealthStatus.OK else HealthStatus.CHECK), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Text("dentro de los límites de la ECU", color = Text2, fontSize = 14.sp)
        }
        state.mode06.filter { !it.passed }.forEach { m -> Text("● ${m.monitorName}: ${Present.value(m.value, m.unit).first} ${m.unit}", color = tone(HealthStatus.CHECK), fontSize = 13.sp) }
    }
    state.vehicleInfo?.let { v ->
        SectionTitle("Computador del motor")
        KeyValue("Nombre", v.ecuName)
        KeyValue("Calibración", v.calibrationIds.joinToString().ifBlank { null })
        KeyValue("CVN", v.cvns.joinToString().ifBlank { null })
        v.inUsePerformance.firstOrNull { it.first == "Ciclos de encendido" }?.let { KeyValue("Arranques registrados", "%,d".format(it.second)) }
    }
    state.scanSignals.firstOrNull { it.key == "7F.TOT" }?.value?.let { KeyValue("Horas de motor", "%,.0f h".format(it / 3600)) }
    state.scanSignals.firstOrNull { it.key == "31" }?.value?.let { KeyValue("Km desde el último borrado", "%,.0f km".format(it)) }
}

@Composable
private fun KeyValue(k: String, v: String?) {
    if (v == null) return
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(k, color = Text2, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(v, color = Text1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/* ─────────────────────────── Informe ─────────────────────────── */

@Composable
private fun ReportTab(
    exportSection: @Composable ColumnScope.() -> Unit,
    advancedSection: @Composable ColumnScope.() -> Unit,
    actions: ShellActions,
) {
    SectionTitle("Exportar")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        exportSection()
    }
    var adv by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { adv = !adv }, modifier = Modifier.fillMaxWidth()) { Text(if (adv) "Ocultar herramientas avanzadas" else "Herramientas avanzadas (taller / técnico)") }
    if (adv) {
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { advancedSection() }
        }
    }
    OutlinedButton(onClick = actions.reset, modifier = Modifier.fillMaxWidth()) { Text("Reiniciar conexión") }
}

/* ─────────────────────────── Piezas comunes ─────────────────────────── */

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), color = Text2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF1A1300)),
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

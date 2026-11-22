package app.jac.diagnosticlab.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.jac.diagnosticlab.saveDownload
import app.jac.diagnosticlab.vaultSave
import app.jac.diagnosticlab.tracker.DirectionEstimator
import app.jac.diagnosticlab.tracker.HeadingSensor
import app.jac.diagnosticlab.tracker.ProximityBand
import app.jac.diagnosticlab.tracker.Sighting
import app.jac.diagnosticlab.tracker.TrackerExport
import app.jac.diagnosticlab.tracker.TrackerKind
import app.jac.diagnosticlab.tracker.TrackerScanner

private val Ink = Color(0xFFE6EAF0)
private val Quiet = Color(0xFF8A96A3)
private val Ring = Color(0xFF2A3440)

private fun bandColor(b: ProximityBand) = when (b) {
    ProximityBand.VERY_CLOSE -> Color(0xFF66BB6A); ProximityBand.CLOSE -> Color(0xFFFFCA28)
    ProximityBand.MEDIUM -> Color(0xFFFF8A65); ProximityBand.FAR -> Color(0xFF5C6773)
}

private fun trackerPermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_SCAN)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
}
private fun hasPerms(c: Context) = trackerPermissions().all { ContextCompat.checkSelfPermission(c, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

/** Pestaña «Tags»: lista mínima y vista Buscar a pantalla completa con flecha orientativa. */
@Composable
internal fun TrackerTab() {
    val context = LocalContext.current
    val scanner = remember { TrackerScanner(context.applicationContext) }
    val compass = remember { HeadingSensor(context.applicationContext) }
    val list by scanner.sightings.collectAsState()
    val running by scanner.running.collectAsState()
    val error by scanner.error.collectAsState()
    var target by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf("") }
    var onlyTrackers by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { scanner.stop(); compass.stop() } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) scanner.start() else msg = "Hacen falta «dispositivos cercanos» y ubicación para escanear."
    }
    fun toggle() { if (running) scanner.stop() else if (hasPerms(context)) scanner.start() else launcher.launch(trackerPermissions().toTypedArray()) }
    val sel = list.firstOrNull { it.address == target }

    if (sel != null) {
        FindView(sel, compass, context, onBack = { target = null; compass.stop() })
        return
    }

    val shown = list.filter { !onlyTrackers || it.classification.kind != TrackerKind.UNKNOWN }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Tags", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Text("Escaneo pasivo de rastreadores Bluetooth. Toque uno para buscarlo.", fontSize = 13.sp, color = Quiet)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { toggle() }, modifier = Modifier.weight(1f)) { Text(if (running) "Detener" else "Escanear") }
            TextButton(onClick = { scanner.clear() }) { Text("Limpiar") }
        }
        Row(Modifier.clickable { onlyTrackers = !onlyTrackers }, verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(checked = onlyTrackers, onCheckedChange = { onlyTrackers = it })
            Text("Solo rastreadores", fontSize = 13.sp, color = Quiet)
        }
        error?.let { Text(it, fontSize = 12.sp, color = Color(0xFFEF5350)) }
        if (msg.isNotBlank()) Text(msg, fontSize = 12.sp, color = Quiet)
        if (shown.isEmpty()) Text(if (running) "Buscando…" else "Nada aún. Pulse Escanear.", fontSize = 13.sp, color = Quiet)
        shown.take(40).forEach { s -> Row(s) { target = s.address; compass.start() } }
        if (list.any { it.bestPoint != null }) {
            OutlinedButton(onClick = {
                runCatching {
                    val g = TrackerExport.geoJson(shown).toByteArray(Charsets.UTF_8)
                    val x = TrackerExport.gpx(shown).toByteArray(Charsets.UTF_8)
                    val t = System.currentTimeMillis()
                    val where = saveDownload(context, "JAC-tags-$t.geojson", g, "application/geo+json")
                    saveDownload(context, "JAC-tags-$t.gpx", x, "application/gpx+xml")
                    vaultSave(context, "tags-$t", mapOf("JAC-tags-$t.geojson" to g, "JAC-tags-$t.gpx" to x))
                    msg = "Mapa exportado: $where (+ .gpx)."
                }.onFailure { msg = "No se pudo exportar: ${it.message.orEmpty()}" }
            }, modifier = Modifier.fillMaxWidth()) { Text("Exportar mapa (GeoJSON + GPX)") }
        }
        Text("Un Xiaomi Tag aparece como accesorio de Apple Find My o Google Find Hub; por Bluetooth no se distingue la marca. La ubicación en la nube solo la ve su dueño.", fontSize = 11.sp, color = Quiet)
    }
}

@Composable
private fun Row(s: Sighting, onClick: () -> Unit) {
    val b = ProximityBand.of(s.smoothedRssi)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(bandColor(b)))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(s.classification.kind.label, fontSize = 15.sp, color = Ink)
            Text("${b.label} · ${s.rssi} dBm · …${s.address.takeLast(5)}", fontSize = 12.sp, color = Quiet)
        }
        Text("›", fontSize = 20.sp, color = Quiet)
    }
}

/** Vista Buscar: flecha orientativa (gire para calibrar), banda de cercanía y tendencia. Sin adornos. */
@Composable
private fun FindView(s: Sighting, compass: HeadingSensor, context: Context, onBack: () -> Unit) {
    val estimator = remember(s.address) { DirectionEstimator() }
    val heading by compass.heading.collectAsState()
    val hasCompass by compass.available.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(400) } }
    LaunchedEffect(s.lastSeenMs) { heading?.let { estimator.add(it.toDouble(), s.rssi, s.lastSeenMs) } }
    val est = estimator.estimate(now)
    val band = ProximityBand.of(s.smoothedRssi)
    val color = bandColor(band)
    val showArrow = est != null && est.clear && heading != null && band != ProximityBand.VERY_CLOSE
    val rel = if (showArrow) DirectionEstimator.relative(est!!.bearingDeg, heading!!.toDouble()).toFloat() else 0f
    var prev by remember { mutableStateOf(0f) }
    val unwrapped = prev + (((rel - prev) % 360f + 540f) % 360f - 180f)
    prev = unwrapped
    val angle by animateFloatAsState(unwrapped, tween(250), label = "arrow")
    val cov = estimator.coverage(now).toFloat()

    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(s.classification.kind.label, fontSize = 13.sp, color = Quiet, textAlign = TextAlign.Center)
        Canvas(
            Modifier.size(260.dp).semantics { contentDescription = if (showArrow) "Flecha hacia la dirección más probable" else "Sin dirección todavía" },
        ) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension / 2 - 8f
            drawCircle(Ring, r, c, style = Stroke(3f))
            if (est == null) {
                // Progreso del giro: arco proporcional a los sectores cubiertos.
                drawArc(color, -90f, 360f * cov.coerceIn(0f, 1f), false, topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(2 * r, 2 * r), style = Stroke(8f, cap = StrokeCap.Round))
            } else if (showArrow) {
                rotate(angle, c) {
                    val p = Path().apply {
                        moveTo(c.x, c.y - r * 0.78f)
                        lineTo(c.x + r * 0.30f, c.y - r * 0.12f)
                        lineTo(c.x + r * 0.09f, c.y - r * 0.12f)
                        lineTo(c.x + r * 0.09f, c.y + r * 0.52f)
                        lineTo(c.x - r * 0.09f, c.y + r * 0.52f)
                        lineTo(c.x - r * 0.09f, c.y - r * 0.12f)
                        lineTo(c.x - r * 0.30f, c.y - r * 0.12f)
                        close()
                    }
                    drawPath(p, color)
                }
            } else {
                drawCircle(color.copy(alpha = if (band == ProximityBand.VERY_CLOSE) 0.9f else 0.25f), r * 0.55f, c)
            }
        }
        Text(band.label, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = color)
        Text(
            when {
                !hasCompass -> "Este teléfono no tiene brújula: solo hay caliente/frío."
                heading == null -> "Activando brújula…"
                band == ProximityBand.VERY_CLOSE -> "Está aquí. Mire alrededor."
                est == null -> "Gire despacio sobre sí mismo, con el teléfono plano delante."
                !est.clear -> "Sin dirección clara. Avance unos pasos y gire otra vez."
                else -> when (s.trend) { 1 -> "Se acerca"; -1 -> "Se aleja"; else -> "Avance en la dirección de la flecha" }
            },
            fontSize = 14.sp, color = Ink, textAlign = TextAlign.Center,
        )
        Text("${"%.0f".format(s.smoothedRssi)} dBm" + (est?.let { " · contraste ${"%.0f".format(it.contrastDb)} dB" } ?: ""), fontSize = 11.sp, color = Quiet)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { estimator.reset() }) { Text("Girar de nuevo") }
            s.bestPoint?.let { p ->
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TrackerExport.geoUri(p, s.classification.kind.label))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Mapa") }
            }
            TextButton(onClick = onBack) { Text("Volver") }
        }
        Text("La flecha es orientativa: el cuerpo y las paredes cambian la señal. Si no hay contraste, no se muestra.", fontSize = 11.sp, color = Quiet, textAlign = TextAlign.Center)
    }
}

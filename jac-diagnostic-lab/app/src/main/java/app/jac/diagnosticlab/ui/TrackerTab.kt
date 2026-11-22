package app.jac.diagnosticlab.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.jac.diagnosticlab.saveDownload
import app.jac.diagnosticlab.vaultSave
import app.jac.diagnosticlab.tracker.Confidence
import app.jac.diagnosticlab.tracker.ProximityBand
import app.jac.diagnosticlab.tracker.Sighting
import app.jac.diagnosticlab.tracker.TrackerExport
import app.jac.diagnosticlab.tracker.TrackerKind
import app.jac.diagnosticlab.tracker.TrackerScanner

private fun hasPerms(c: Context) = trackerPermissions().all { ContextCompat.checkSelfPermission(c, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

private fun trackerPermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_SCAN)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
}

/** Pestaña «Tags»: escaneo BLE pasivo, clasificación por firma, modo Buscar (caliente/frío) y mapa de avistamientos exportable. */
@Composable
internal fun TrackerTab() {
    val context = LocalContext.current
    val scanner = remember { TrackerScanner(context.applicationContext) }
    val list by scanner.sightings.collectAsState()
    val running by scanner.running.collectAsState()
    val error by scanner.error.collectAsState()
    var target by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf("") }
    var onlyTrackers by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { scanner.stop() } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) scanner.start() else msg = "Sin permiso de dispositivos cercanos y ubicación no se puede escanear ni ubicar."
    }
    val shown = list.filter { !onlyTrackers || it.classification.kind != TrackerKind.UNKNOWN }
    val sel = list.firstOrNull { it.address == target }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Rastreadores BLE cercanos", style = MaterialTheme.typography.titleMedium)
            Text("Escaneo pasivo: no se conecta a nada. Cada avistamiento queda con la posición del teléfono; el punto del mapa es donde la señal fue más fuerte. La app NO obtiene la ubicación de un tag desde la nube: esa solo la ve su dueño en Find My / Find Hub.", fontSize = 13.sp)
            Text("Un Xiaomi Tag aparece como accesorio de la red Apple o Google que se eligió al configurarlo; por Bluetooth no se distingue el fabricante.", fontSize = 12.sp, color = Color(0xFFF5C16C))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (running) scanner.stop() else if (hasPerms(context)) scanner.start() else launcher.launch(trackerPermissions().toTypedArray())
                }, modifier = Modifier.weight(1f)) { Text(if (running) "Detener" else "Escanear") }
                OutlinedButton(onClick = { scanner.clear(); target = null }) { Text("Limpiar") }
            }
            Row(Modifier.clickable { onlyTrackers = !onlyTrackers }) {
                androidx.compose.material3.Checkbox(checked = onlyTrackers, onCheckedChange = { onlyTrackers = it })
                Text("Solo los que parecen rastreadores", fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            }
            error?.let { Text(it, fontSize = 12.sp, color = Color(0xFFEF5350)) }
            if (msg.isNotBlank()) Text(msg, fontSize = 12.sp)
        }
    }

    if (sel != null) FindCard(sel, onClose = { target = null }, context = context)

    Text("${shown.size} dispositivo(s)${if (running) " · escaneando" else ""}", fontSize = 12.sp, color = Color(0xFF9AA3AD))
    shown.take(60).forEach { s -> SightingRow(s, selected = s.address == target) { target = s.address } }
    if (list.isEmpty() && !running) Text("Pulse Escanear. Para localizar uno propio, camine mientras escanea y abra «Buscar» sobre su fila.", fontSize = 13.sp)

    if (list.any { it.bestPoint != null }) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Mapa de avistamientos", style = MaterialTheme.typography.titleMedium)
                Text("Exporta a GeoJSON o GPX para verlos en cualquier mapa. Se guarda en Descargas/JAC-lectura y en la copia privada de la app.", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        runCatching {
                            val g = TrackerExport.geoJson(shown).toByteArray(Charsets.UTF_8)
                            val x = TrackerExport.gpx(shown).toByteArray(Charsets.UTF_8)
                            val stamp = System.currentTimeMillis()
                            val where = saveDownload(context, "JAC-tags-$stamp.geojson", g, "application/geo+json")
                            saveDownload(context, "JAC-tags-$stamp.gpx", x, "application/gpx+xml")
                            vaultSave(context, "tags-$stamp", mapOf("JAC-tags-$stamp.geojson" to g, "JAC-tags-$stamp.gpx" to x))
                            msg = "Exportado: $where (+ .gpx) y copia privada."
                        }.onFailure { msg = "No se pudo exportar: ${it.message.orEmpty()}" }
                    }) { Text("Exportar GeoJSON + GPX") }
                }
            }
        }
    }
}

@Composable
private fun SightingRow(s: Sighting, selected: Boolean, onFind: () -> Unit) {
    val c = s.classification
    Card(Modifier.fillMaxWidth().clickable(onClick = onFind)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(c.kind.label, style = MaterialTheme.typography.titleSmall)
            Text("${ProximityBand.of(s.smoothedRssi).label} · ${s.rssi} dBm · ${s.count} avist. · …${s.address.takeLast(5)}", fontSize = 12.sp)
            Text("Confianza ${c.confidence.label}. ${c.basis}", fontSize = 11.sp, color = Color(0xFF9AA3AD))
            if (c.note.isNotBlank()) Text(c.note, fontSize = 11.sp, color = Color(0xFF9AA3AD))
            Text(if (selected) "Buscando…" else "Toque para Buscar", fontSize = 11.sp, color = Color(0xFFF5C16C))
        }
    }
}

/** Modo Buscar: banda de proximidad, tendencia y mejor punto. Barra proporcional al RSSI suavizado. */
@Composable
private fun FindCard(s: Sighting, onClose: () -> Unit, context: Context) {
    val band = ProximityBand.of(s.smoothedRssi)
    val frac = ((s.smoothedRssi + 100.0) / 60.0).coerceIn(0.03, 1.0).toFloat()
    val color = when (band) { ProximityBand.VERY_CLOSE -> Color(0xFF66BB6A); ProximityBand.CLOSE -> Color(0xFFFFCA28); ProximityBand.MEDIUM -> Color(0xFFFF8A65); ProximityBand.FAR -> Color(0xFF5C6773) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Buscar: ${s.classification.kind.label}", style = MaterialTheme.typography.titleMedium)
            Text(band.label, fontSize = 26.sp, color = color)
            Box(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A3440))) {
                Box(Modifier.fillMaxWidth(frac).height(16.dp).clip(RoundedCornerShape(8.dp)).background(color))
            }
            Text(
                when (s.trend) { 1 -> "Más caliente: se acerca"; -1 -> "Más frío: se aleja"; else -> "Estable: muévase despacio" } + " · ${"%.0f".format(s.smoothedRssi)} dBm",
                fontSize = 14.sp,
            )
            Text("Camine despacio y gire: el cuerpo y las paredes cambian la señal. Es una banda, no una distancia exacta.", fontSize = 11.sp, color = Color(0xFF9AA3AD))
            s.bestPoint?.let { p ->
                Text("Punto de señal más fuerte: %.5f, %.5f (máx. %d dBm)".format(java.util.Locale.ROOT, p.lat, p.lon, s.bestRssi), fontSize = 12.sp)
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TrackerExport.geoUri(p, s.classification.kind.label))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Abrir en el mapa") }
            } ?: Text("Aún sin posición GPS para este avistamiento (¿ubicación activada?).", fontSize = 12.sp)
            OutlinedButton(onClick = onClose) { Text("Cerrar Buscar") }
        }
    }
}

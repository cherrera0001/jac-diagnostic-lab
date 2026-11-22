package app.jac.diagnosticlab.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.jac.diagnosticlab.saveDownload
import app.jac.diagnosticlab.tracker.BleAdParser
import app.jac.diagnosticlab.tracker.DeviceCategory
import app.jac.diagnosticlab.tracker.DeviceNaming
import app.jac.diagnosticlab.tracker.DirectionEstimator
import app.jac.diagnosticlab.tracker.GattInspector
import app.jac.diagnosticlab.tracker.Geo
import app.jac.diagnosticlab.tracker.HeadingSensor
import app.jac.diagnosticlab.tracker.PositionEstimate
import app.jac.diagnosticlab.tracker.ProximityBand
import app.jac.diagnosticlab.tracker.Sighting
import app.jac.diagnosticlab.tracker.TrackerExport
import app.jac.diagnosticlab.tracker.TrackerKind
import app.jac.diagnosticlab.tracker.TrackerScanner
import app.jac.diagnosticlab.vaultSave
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.roundToInt

private val Ink = Color(0xFFE8ECF2)
private val Quiet = Color(0xFF8A96A3)
private val Ring = Color(0xFF232C37)
private val RingSubtle = Color(0xFF19212A)
private val ChipBg = Color(0xFF161D26)
private val ChipActive = Color(0xFF223042)
private val ChipBorder = Color(0xFF2D3B4D)
private val Warn = Color(0xFFEF5350)

private enum class DeviceFilter(val label: String) {
    ALL("Todos"),
    TRACKERS("Rastreadores"),
    AUDIO("Audio"),
    WEARABLE("Relojes"),
    PHONE_PC("Teléf./PC"),
    NAMED("Con nombre"),
}

private fun bandColor(b: ProximityBand) = when (b) {
    ProximityBand.VERY_CLOSE -> Color(0xFF56CC6D)
    ProximityBand.CLOSE -> Color(0xFFFFCA3A)
    ProximityBand.MEDIUM -> Color(0xFFFF8C5A)
    ProximityBand.FAR -> Color(0xFF647282)
}

private fun categoryColor(c: DeviceCategory) = when (c) {
    DeviceCategory.TRACKER -> Color(0xFFFFCA3A)
    DeviceCategory.AUDIO -> Color(0xFF64B5F6)
    DeviceCategory.WEARABLE -> Color(0xFF4DB6AC)
    DeviceCategory.PHONE_PC -> Color(0xFF90A4AE)
    DeviceCategory.TV_HOME -> Color(0xFFBA68C8)
    DeviceCategory.PERIPHERAL -> Color(0xFF81C784)
    DeviceCategory.VEHICLE -> Color(0xFFFF8A65)
    DeviceCategory.BEACON -> Color(0xFFFFD54F)
    DeviceCategory.OTHER -> Color(0xFF647282)
}

private fun trackerPermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
}

private fun hasPerms(c: Context) =
    trackerPermissions().all { ContextCompat.checkSelfPermission(c, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

private fun loadNicknames(prefs: android.content.SharedPreferences): Map<String, String> =
    prefs.all.mapNotNull { (k, v) ->
        if (k.startsWith("nick_") && v is String && v.isNotBlank()) k.removePrefix("nick_") to v.trim() else null
    }.toMap()

/** Pestaña «Tags»: escáner BLE con diferenciación por categoría, inspección GATT/DULT bajo demanda y vista Buscar minimalista. */
@Composable
internal fun TrackerTab(externalScanner: TrackerScanner? = null) {
    val context = LocalContext.current
    val scanner = remember(externalScanner) { externalScanner ?: TrackerScanner(context.applicationContext) }
    val compass = remember { HeadingSensor(context.applicationContext) }
    val list by scanner.sightings.collectAsState()
    val running by scanner.running.collectAsState()
    val error by scanner.error.collectAsState()
    var target by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(DeviceFilter.ALL) }
    val prefs = remember { context.getSharedPreferences("jaclab_tags", Context.MODE_PRIVATE) }
    var nicknames by remember { mutableStateOf(loadNicknames(prefs)) }
    DisposableEffect(externalScanner) {
        onDispose {
            if (externalScanner == null) scanner.stop()
            compass.stop()
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) scanner.start() else msg = "Hacen falta «dispositivos cercanos» y ubicación para escanear."
    }

    fun startIfAllowed() {
        if (hasPerms(context)) scanner.start() else launcher.launch(trackerPermissions().toTypedArray())
    }

    fun toggle() {
        if (running) scanner.stop() else startIfAllowed()
    }

    fun saveNickname(address: String, value: String) {
        val clean = value.trim()
        prefs.edit().apply {
            if (clean.isEmpty()) remove("nick_$address") else putString("nick_$address", clean)
        }.apply()
        nicknames = if (clean.isEmpty()) nicknames - address else nicknames + (address to clean)
    }

    val sel = list.firstOrNull { it.address == target }
    if (sel != null) {
        FindView(
            s = sel,
            nickname = nicknames[sel.address].orEmpty(),
            onSaveNickname = { saveNickname(sel.address, it) },
            compass = compass,
            scanner = scanner,
            running = running,
            onResumeScan = { startIfAllowed() },
            context = context,
            onBack = { target = null; compass.stop() },
        )
        return
    }

    val trackersCount = list.count { it.classification.category == DeviceCategory.TRACKER || it.classification.kind != TrackerKind.UNKNOWN }
    val namedCount = list.count {
        !it.advert.name.isNullOrBlank() || it.gattInfo?.hasIdentity() == true || !nicknames[it.address].isNullOrBlank()
    }
    val shown = list.filter { s ->
        when (filter) {
            DeviceFilter.ALL -> true
            DeviceFilter.TRACKERS -> s.classification.category == DeviceCategory.TRACKER || s.classification.kind != TrackerKind.UNKNOWN
            DeviceFilter.AUDIO -> s.classification.category == DeviceCategory.AUDIO
            DeviceFilter.WEARABLE -> s.classification.category == DeviceCategory.WEARABLE
            DeviceFilter.PHONE_PC -> s.classification.category == DeviceCategory.PHONE_PC
            DeviceFilter.NAMED -> !s.advert.name.isNullOrBlank() || s.gattInfo?.hasIdentity() == true || !nicknames[s.address].isNullOrBlank()
        }
    }.sortedWith(
        compareBy<Sighting> { it.classification.category != DeviceCategory.TRACKER }
            .thenBy { it.classification.kind == TrackerKind.UNKNOWN }
            .thenBy { it.advert.name.isNullOrBlank() && nicknames[it.address].isNullOrBlank() }
            .thenByDescending { it.smoothedRssi },
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Tags", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(
                when {
                    list.isEmpty() && running -> "Buscando dispositivos BLE cercanos…"
                    list.isEmpty() -> "Escaneo pasivo BLE por tipo de dispositivo, identificación GATT/DULT y guía direccional."
                    else -> "${list.size} detectados · $trackersCount rastreadores/Xiaomi · $namedCount identificados"
                },
                fontSize = 12.sp,
                color = Quiet,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { toggle() }, modifier = Modifier.weight(1f)) {
                Text(if (running) "Detener" else "Escanear")
            }
            if (list.isNotEmpty()) {
                TextButton(onClick = { scanner.clear(); msg = "" }) { Text("Limpiar") }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DeviceFilter.entries.forEach { f ->
                val active = filter == f
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (active) ChipActive else ChipBg)
                        .border(1.dp, if (active) Color(0xFF4C6A8C) else ChipBorder, RoundedCornerShape(20.dp))
                        .clickable { filter = f }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        f.label,
                        fontSize = 12.sp,
                        color = if (active) Ink else Quiet,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
        error?.let { Text(it, fontSize = 12.sp, color = Warn) }
        if (msg.isNotBlank()) Text(msg, fontSize = 12.sp, color = Quiet)
        if (shown.isEmpty()) {
            Text(
                if (running) "Escuchando anuncios Bluetooth LE…" else "Nada aún. Pulse Escanear.",
                fontSize = 13.sp,
                color = Quiet,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        val nowMs = System.currentTimeMillis()
        shown.take(60).forEach { s ->
            DeviceRow(s, nicknames[s.address], nowMs) {
                target = s.address
                compass.start()
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(RingSubtle))
        }
        if (list.any { it.bestPoint != null }) {
            OutlinedButton(
                onClick = {
                    runCatching {
                        val estimates = shown.mapNotNull { s ->
                            scanner.positionEstimator(s.address).estimate()?.takeIf { it.usable }?.let { s.address to it }
                        }.toMap()
                        val g = TrackerExport.geoJson(shown, nicknames, estimates).toByteArray(Charsets.UTF_8)
                        val x = TrackerExport.gpx(shown, nicknames, estimates).toByteArray(Charsets.UTF_8)
                        val t = System.currentTimeMillis()
                        val where = saveDownload(context, "JAC-tags-$t.geojson", g, "application/geo+json")
                        saveDownload(context, "JAC-tags-$t.gpx", x, "application/gpx+xml")
                        vaultSave(context, "tags-$t", mapOf("JAC-tags-$t.geojson" to g, "JAC-tags-$t.gpx" to x))
                        msg = "Mapa exportado: $where (+ .gpx)."
                    }.onFailure { msg = "No se pudo exportar: ${it.message.orEmpty()}" }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Exportar mapa (GeoJSON + GPX)")
            }
        }
        Text(
            "Nota: el nombre personalizado de un tag («Llaves», «Mochila») pertenece solo a la app Google Find Hub o Apple Find My y nunca se transmite por Bluetooth. Toque un dispositivo y use «Identificar» (GATT/DULT) o asígnele un apodo local.",
            fontSize = 11.sp,
            color = Quiet,
        )
    }
}

@Composable
private fun CategoryBadge(category: DeviceCategory) {
    val accent = categoryColor(category)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.40f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(category.label, fontSize = 10.sp, color = accent, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun DeviceRow(s: Sighting, nick: String?, nowMs: Long, onClick: () -> Unit) {
    val b = ProximityBand.of(s.smoothedRssi)
    val d = DeviceNaming.display(s.advert, s.classification, s.gattInfo)
    val stale = nowMs - s.lastSeenMs > 12_000L
    val dotColor = if (stale) Quiet.copy(alpha = 0.45f) else bandColor(b)
    val trendGlyph = when {
        stale -> ""
        s.trend > 0 -> " · ↑"
        s.trend < 0 -> " · ↓"
        else -> ""
    }
    val geoTag = if (s.geoSampleCount >= 8) " · ${s.geoSampleCount} pts" else ""
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor))
        Column(Modifier.padding(start = 12.dp, end = 8.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    nick?.takeIf { it.isNotBlank() } ?: d.title,
                    fontSize = 15.sp,
                    color = if (stale) Quiet else Ink,
                    fontWeight = if (!nick.isNullOrBlank() || d.named) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                CategoryBadge(s.classification.category)
            }
            val secondary = buildList {
                if (!nick.isNullOrBlank()) add(d.title)
                if (d.subtitle.isNotBlank()) add(d.subtitle)
            }.joinToString(" · ")
            if (secondary.isNotBlank()) {
                Text(secondary, fontSize = 11.sp, color = Quiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "${b.label} · ${s.rssi} dBm$trendGlyph$geoTag · …${s.address.takeLast(5)}",
                fontSize = 12.sp,
                color = Quiet,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("›", fontSize = 20.sp, color = Quiet)
    }
}

private class AngleMemory(var unwrapped: Float = 0f)

/**
 * Vista Buscar minimalista. Prioridad de la flecha:
 *  1) posición estimada silenciosamente con GPS + RSSI mientras se camina (funciona sin girar ni abrir mapa);
 *  2) giro lento con brújula (sector más fuerte cuando el cuerpo apantalla por detrás);
 *  3) solo proximidad (caliente/frío) con arco de calibración. Nunca se inventa una flecha sin respaldo.
 */
@Composable
private fun FindView(
    s: Sighting,
    nickname: String,
    onSaveNickname: (String) -> Unit,
    compass: HeadingSensor,
    scanner: TrackerScanner,
    running: Boolean,
    onResumeScan: () -> Unit,
    context: Context,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }

    val scope = rememberCoroutineScope()
    val estimator = remember(s.address) { DirectionEstimator() }
    val pos = remember(s.address) { scanner.positionEstimator(s.address) }
    val heading by compass.heading.collectAsState()
    val hasCompass by compass.available.collectAsState()
    val here by scanner.lastPointFlow.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var posEst by remember(s.address) { mutableStateOf<PositionEstimate?>(null) }
    var showNickDialog by remember { mutableStateOf(false) }
    var showRawDialog by remember { mutableStateOf(false) }
    var probing by remember(s.address) { mutableStateOf(false) }

    LaunchedEffect(s.address) {
        var lastVersion = -1L
        while (true) {
            now = System.currentTimeMillis()
            val v = pos.version
            if (v != lastVersion) {
                lastVersion = v
                posEst = withContext(Dispatchers.Default) { pos.estimate() }
            }
            delay(750)
        }
    }
    LaunchedEffect(s.lastSeenMs) {
        heading?.let { estimator.add(it.toDouble(), s.rssi, s.lastSeenMs) }
    }

    val ageSec = ((now - s.lastSeenMs).coerceAtLeast(0L) / 1000L)
    val signalLost = ageSec >= 10L
    val est = estimator.estimate(now)
    val band = ProximityBand.of(s.smoothedRssi)
    val color = if (signalLost) Quiet else bandColor(band)
    val near = band == ProximityBand.VERY_CLOSE && !signalLost
    val pe = posEst
    val currentHere = here
    val distM = if (pe != null && currentHere != null) Geo.distanceM(currentHere, pe.point) else null
    val byPosition = pe != null && pe.usable && currentHere != null && heading != null && !near && (distM ?: 0.0) > 3.0
    val byTurning = !byPosition && est != null && est.clear && heading != null && !near && !signalLost
    val showArrow = byPosition || byTurning
    val bearing = when {
        byPosition -> Geo.bearingDeg(currentHere!!, pe!!.point)
        byTurning -> est!!.bearingDeg
        else -> 0.0
    }

    val rel = if (showArrow) DirectionEstimator.relative(bearing, heading!!.toDouble()).toFloat() else 0f
    val angleMem = remember(s.address) { AngleMemory() }
    val unwrapped = remember(rel, showArrow) {
        if (!showArrow) {
            angleMem.unwrapped
        } else {
            val delta = ((rel - angleMem.unwrapped) % 360f + 540f) % 360f - 180f
            angleMem.unwrapped += delta
            angleMem.unwrapped
        }
    }
    val angle by animateFloatAsState(unwrapped, tween(220), label = "arrow")
    val compassAngle by animateFloatAsState(heading?.let { -it } ?: 0f, tween(220), label = "north")

    val cov = estimator.coverage(now).toFloat().coerceIn(0f, 1f)
    val walkProgress = (pos.count / 10f).coerceIn(0f, 1f)
    val calProgress = maxOf(cov, walkProgress)
    val sectorSweepDeg = if (byPosition && distM != null && distM > 1.0) {
        (Math.toDegrees(2.0 * atan2(pe!!.radiusM, distM)).toFloat()).coerceIn(18f, 110f)
    } else {
        0f
    }

    val d = DeviceNaming.display(s.advert, s.classification, s.gattInfo)
    val title = nickname.ifBlank { d.title }
    val distText = if (byPosition && distM != null) {
        val rounded = if (distM >= 20.0) (distM / 5.0).roundToInt() * 5 else distM.roundToInt()
        "≈ $rounded m"
    } else {
        null
    }
    val mapPoint = pe?.takeIf { it.usable }?.point ?: s.bestPoint

    if (showNickDialog) {
        var draft by remember(nickname) { mutableStateOf(nickname) }
        AlertDialog(
            onDismissRequest = { showNickDialog = false },
            title = { Text("Apodo del dispositivo") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    placeholder = { Text(d.title) },
                    label = { Text("Se guarda solo en este teléfono") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { onSaveNickname(draft); showNickDialog = false }) { Text("Guardar") }
            },
            dismissButton = {
                TextButton(onClick = { showNickDialog = false }) { Text("Cancelar") }
            },
        )
    }

    if (showRawDialog) {
        val mfrLines = s.advert.manufacturer.entries.sortedBy { it.key }.joinToString("\n") { (id, bytes) ->
            val comp = DeviceNaming.companyName(id) ?: "Desconocido"
            String.format(Locale.US, "0x%04X (%s): %s", id, comp, BleAdParser.hexPrefix(bytes))
        }.ifBlank { "Ninguno" }
        val svcLines = (s.advert.serviceUuids + s.advert.serviceData.keys).distinct().sorted().joinToString("\n") { u ->
            val data = s.advert.serviceData[u]
            val lbl = DeviceNaming.serviceName(u)?.let { " ($it)" }.orEmpty()
            if (data != null) "$u$lbl: ${BleAdParser.hexPrefix(data)}" else "$u$lbl"
        }.ifBlank { "Ninguno" }
        val appText = s.advert.appearance?.let { code ->
            String.format(Locale.US, "0x%04X (%s)", code, BleAdParser.appearanceLabel(code) ?: "otro")
        } ?: "No anunciado"
        val gattLines = s.gattInfo?.let { g ->
            buildList {
                g.deviceName?.let { add("Nombre GATT: $it") }
                g.manufacturer?.let { add("Fabricante: $it") }
                g.model?.let { add("Modelo: $it") }
                g.dultCategory?.let { add("Categoría DULT: $it") }
                g.firmware?.let { add("Firmware: $it") }
                g.batteryPercent?.let { add("Batería: $it%") }
                if (g.statusNote.isNotBlank()) add("Estado: ${g.statusNote}")
            }.joinToString("\n")
        } ?: "No consultado todavía (pulse «Identificar»)."

        AlertDialog(
            onDismissRequest = { showRawDialog = false },
            title = { Text("Datos BLE · …${s.address.takeLast(5)}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Categoría: ${s.classification.category.label}", fontSize = 12.sp, color = Ink)
                    Text("Base: ${s.classification.basis}", fontSize = 12.sp, color = Quiet)
                    Text("Nombre BLE: ${s.advert.name ?: "ninguno"}", fontSize = 12.sp, color = Ink)
                    Text("Apariencia GAP: $appText", fontSize = 12.sp, color = Quiet)
                    Text("Fabricante:\n$mfrLines", fontSize = 11.sp, color = Quiet)
                    Text("Servicios:\n$svcLines", fontSize = 11.sp, color = Quiet)
                    Text("GATT / DULT:\n$gattLines", fontSize = 11.sp, color = Ink)
                }
            },
            confirmButton = {
                TextButton(onClick = { showRawDialog = false }) { Text("Cerrar") }
            },
        )
    }

    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Volver") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showRawDialog = true }) { Text("Datos BLE") }
            TextButton(onClick = { showNickDialog = true }) { Text("Apodo") }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CategoryBadge(s.classification.category)
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink, textAlign = TextAlign.Center)
            val sub = buildList {
                if (nickname.isNotBlank()) add(d.title)
                if (d.subtitle.isNotBlank()) add(d.subtitle)
            }.joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(sub, fontSize = 12.sp, color = Quiet, textAlign = TextAlign.Center)
            }
        }

        s.gattInfo?.statusNote?.takeIf { it.isNotBlank() }?.let { note ->
            Text(
                note,
                fontSize = 11.sp,
                color = if (s.gattInfo.hasIdentity()) Color(0xFF56CC6D) else Quiet,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }

        if (!running) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(ChipBg)
                    .border(1.dp, ChipBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Escaneo pausado", fontSize = 12.sp, color = Quiet)
                Text(
                    "Reanudar",
                    fontSize = 12.sp,
                    color = Ink,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onResumeScan() },
                )
            }
        }

        Spacer(Modifier.height(2.dp))

        Canvas(
            Modifier
                .size(264.dp)
                .semantics {
                    contentDescription = when {
                        near -> "Dispositivo muy cerca"
                        showArrow -> "Flecha hacia la dirección más probable"
                        else -> "Calibrando dirección"
                    }
                },
        ) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f - 10f

            drawCircle(RingSubtle, r * 0.42f, c, style = Stroke(1.5f))
            drawCircle(RingSubtle, r * 0.72f, c, style = Stroke(1.5f))
            drawCircle(Ring, r, c, style = Stroke(2.5f))

            if (heading != null) {
                rotate(compassAngle, c) {
                    drawCircle(Quiet.copy(alpha = 0.55f), 3.5f, Offset(c.x, c.y - r))
                }
            }

            if (near) {
                drawCircle(color.copy(alpha = 0.16f), r * 0.80f, c)
                drawCircle(color.copy(alpha = 0.34f), r * 0.56f, c)
                drawCircle(color, r * 0.34f, c)
            } else if (showArrow) {
                if (byPosition && sectorSweepDeg > 0f) {
                    drawArc(
                        color = color.copy(alpha = 0.22f),
                        startAngle = -90f + angle - sectorSweepDeg / 2f,
                        sweepAngle = sectorSweepDeg,
                        useCenter = false,
                        topLeft = Offset(c.x - r, c.y - r),
                        size = Size(2 * r, 2 * r),
                        style = Stroke(10f, cap = StrokeCap.Round),
                    )
                }
                rotate(angle, c) {
                    val p = Path().apply {
                        moveTo(c.x, c.y - r * 0.74f)
                        lineTo(c.x + r * 0.27f, c.y - r * 0.10f)
                        lineTo(c.x + r * 0.08f, c.y - r * 0.10f)
                        lineTo(c.x + r * 0.08f, c.y + r * 0.48f)
                        lineTo(c.x - r * 0.08f, c.y + r * 0.48f)
                        lineTo(c.x - r * 0.08f, c.y - r * 0.10f)
                        lineTo(c.x - r * 0.27f, c.y - r * 0.10f)
                        close()
                    }
                    drawPath(p, color)
                }
            } else {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * calProgress,
                    useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = Size(2 * r, 2 * r),
                    style = Stroke(6f, cap = StrokeCap.Round),
                )
                val coreScale = when (band) {
                    ProximityBand.VERY_CLOSE -> 0.42f
                    ProximityBand.CLOSE -> 0.34f
                    ProximityBand.MEDIUM -> 0.24f
                    ProximityBand.FAR -> 0.16f
                }
                drawCircle(color.copy(alpha = if (signalLost) 0.18f else 0.32f), r * coreScale, c)
                drawCircle(color, r * 0.09f, c)
            }
        }

        Text(
            when {
                signalLost -> "Sin señal (${ageSec} s)"
                distText != null -> "${band.label} · $distText"
                else -> band.label
            },
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
            textAlign = TextAlign.Center,
        )

        val trendText = when {
            signalLost || near -> null
            s.trend > 0 -> "Acercándose"
            s.trend < 0 -> "Alejándose"
            else -> null
        }
        if (trendText != null) {
            Text(
                trendText,
                fontSize = 12.sp,
                color = if (s.trend > 0) Color(0xFF56CC6D) else Color(0xFFFF8C5A),
                fontWeight = FontWeight.Medium,
            )
        }

        Text(
            when {
                signalLost -> "Última lectura hace $ageSec s. Acérquese al último punto conocido."
                !hasCompass -> "Este teléfono no tiene brújula: solo se muestra proximidad."
                heading == null -> "Activando brújula…"
                near -> "Está aquí cerca. Mire alrededor."
                byPosition -> "Siga la flecha · precisión estimada ±${pe!!.radiusM.roundToInt()} m."
                byTurning -> "Dirección por giro. Camine en L y se afinará por debajo con GPS."
                pos.count < 8 -> "Camine unos pasos cambiando de dirección (o gire despacio sobre sí mismo)."
                else -> "Ajuste aún ambiguo. Cambie de ángulo unos metros para triangular."
            },
            fontSize = 14.sp,
            color = Ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        val gpsAcc = currentHere?.accuracyM?.let { " · GPS ±${it.roundToInt()} m" }.orEmpty()
        val extra = when {
            byPosition -> " · R²=${(pe!!.r2 * 100).roundToInt()}%"
            est != null -> " · contraste ${est.contrastDb.roundToInt()} dB"
            else -> ""
        }
        Text(
            "${s.smoothedRssi.roundToInt()} dBm · ${pos.count} pts$gpsAcc$extra · …${s.address.takeLast(5)}",
            fontSize = 11.sp,
            color = Quiet,
            textAlign = TextAlign.Center,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                enabled = !probing,
                onClick = {
                    probing = true
                    scope.launch {
                        val info = GattInspector.inspect(context.applicationContext, s.address)
                        scanner.attachGatt(s.address, info)
                        probing = false
                    }
                },
            ) {
                Text(if (probing) "Consultando…" else "Identificar")
            }
            OutlinedButton(onClick = { estimator.reset(); scanner.resetPosition(s.address); posEst = null }) {
                Text("Reiniciar")
            }
            if (mapPoint != null) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(TrackerExport.geoUri(mapPoint, title)))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                ) {
                    Text(if (pe?.usable == true) "Mapa" else "Punto")
                }
            }
        }

        Text(
            "Orientativo: el cuerpo, las paredes y el GPS en interiores alteran la señal. «Identificar» consulta nombre/modelo vía GATT e IETF DULT cuando el dispositivo lo permite.",
            fontSize = 11.sp,
            color = Quiet,
            textAlign = TextAlign.Center,
        )
    }
}

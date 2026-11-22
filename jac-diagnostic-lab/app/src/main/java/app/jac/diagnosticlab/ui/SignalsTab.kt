package app.jac.diagnosticlab.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.jac.diagnosticlab.saveDownload
import app.jac.diagnosticlab.signals.CellTower
import app.jac.diagnosticlab.signals.FindingSeverity
import app.jac.diagnosticlab.signals.GnssSummary
import app.jac.diagnosticlab.signals.MagneticReading
import app.jac.diagnosticlab.signals.RadioMath
import app.jac.diagnosticlab.signals.RadioParsers
import app.jac.diagnosticlab.signals.RadioScanner
import app.jac.diagnosticlab.signals.SecurityFinding
import app.jac.diagnosticlab.signals.SignalsExport
import app.jac.diagnosticlab.signals.VehicleSecurityAudit
import app.jac.diagnosticlab.signals.WifiNet
import app.jac.diagnosticlab.tracker.Sighting
import app.jac.diagnosticlab.tracker.TrackerScanner
import app.jac.diagnosticlab.vaultSave
import kotlinx.coroutines.delay
import java.util.Locale

private val SigInk = Color(0xFFE8ECF2)
private val SigQuiet = Color(0xFF8A96A3)
private val SigCard = Color(0xFF141B23)
private val SigBorder = Color(0xFF263241)
private val SigActive = Color(0xFF223449)
private val SigAccent = Color(0xFF56A8F5)
private val SigOk = Color(0xFF56CC6D)
private val SigWarn = Color(0xFFFFCA3A)
private val SigAlert = Color(0xFFEF5350)

private enum class SignalsMode(val label: String) {
    BLE("BLE / Tags"),
    RF("Redes y RF"),
    SHIELD("Seguridad"),
}

private fun radioPerms(): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
}

private fun hasRadioPerms(c: Context) =
    radioPerms().all { ContextCompat.checkSelfPermission(c, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

private fun severityColor(s: FindingSeverity) = when (s) {
    FindingSeverity.ALERT -> SigAlert
    FindingSeverity.WARN -> SigWarn
    FindingSeverity.INFO -> SigAccent
    FindingSeverity.OK -> SigOk
}

/**
 * Hub «Señales»: unifica
 * 1) BLE / Tags (rastreadores, categorías, GATT/DULT, flecha direccional)
 * 2) Redes y RF (espectro Wi-Fi 2.4/5/6 GHz, torres 4G/5G con MHz y SINR, satélites GNSS, FFT magnético en vivo)
 * 3) Seguridad defensiva (auditoría de puertas de entrada del vehículo + guía SDR)
 */
@Composable
internal fun SignalsHubTab() {
    val context = LocalContext.current
    val bleScanner = remember { TrackerScanner(context.applicationContext) }
    val radioScanner = remember { RadioScanner(context.applicationContext) }
    var mode by remember { mutableStateOf(SignalsMode.BLE) }
    var permMsg by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose {
            bleScanner.stop()
            radioScanner.stop()
        }
    }

    val wifi by radioScanner.wifi.collectAsState()
    val cells by radioScanner.cells.collectAsState()
    val gnss by radioScanner.gnss.collectAsState()
    val mag by radioScanner.magnetic.collectAsState()
    val rfRunning by radioScanner.running.collectAsState()
    val bleList by bleScanner.sightings.collectAsState()

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) {
            permMsg = ""
            radioScanner.start()
        } else {
            permMsg = "Se requiere ubicación (y dispositivos cercanos) para leer canales Wi-Fi, torres 4G/5G y GNSS."
        }
    }

    LaunchedEffect(mode, rfRunning) {
        if ((mode == SignalsMode.RF || mode == SignalsMode.SHIELD) && rfRunning) {
            while (true) {
                delay(4_000)
                radioScanner.refreshRadios()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Barra segmentada minimalista
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SigCard)
                .border(1.dp, SigBorder, RoundedCornerShape(14.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SignalsMode.entries.forEach { m ->
                val active = mode == m
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) SigActive else Color.Transparent)
                        .clickable {
                            mode = m
                            if ((m == SignalsMode.RF || m == SignalsMode.SHIELD) && !rfRunning && hasRadioPerms(context)) {
                                radioScanner.start()
                            }
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        m.label,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) SigInk else SigQuiet,
                        maxLines = 1,
                    )
                }
            }
        }

        if (permMsg.isNotBlank() && mode != SignalsMode.BLE) {
            Text(permMsg, fontSize = 12.sp, color = SigAlert)
        }

        when (mode) {
            SignalsMode.BLE -> TrackerTab(externalScanner = bleScanner)
            SignalsMode.RF -> RadioSpectrumView(
                wifi = wifi,
                cells = cells,
                gnss = gnss,
                mag = mag,
                running = rfRunning,
                onToggle = {
                    if (rfRunning) radioScanner.stop()
                    else if (hasRadioPerms(context)) radioScanner.start()
                    else permLauncher.launch(radioPerms().toTypedArray())
                },
                onRefresh = {
                    if (hasRadioPerms(context)) radioScanner.refreshRadios()
                    else permLauncher.launch(radioPerms().toTypedArray())
                },
            )
            SignalsMode.SHIELD -> VehicleSecurityView(
                sightings = bleList,
                wifi = wifi,
                cells = cells,
                gnss = gnss,
                mag = mag,
                rfRunning = rfRunning,
                context = context,
                onStartScan = {
                    if (hasRadioPerms(context)) {
                        radioScanner.start()
                        if (!bleScanner.running.value) bleScanner.start()
                    } else {
                        permLauncher.launch(radioPerms().toTypedArray())
                    }
                },
            )
        }
    }
}

@Composable
private fun RadioSpectrumView(
    wifi: List<WifiNet>,
    cells: List<CellTower>,
    gnss: GnssSummary,
    mag: MagneticReading,
    running: Boolean,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
) {
    val evilTwins = remember(wifi) { RadioParsers.findEvilTwinSsids(wifi) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Redes y espectro RF", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = SigInk)
            Text(
                "Frecuencias y canales reales de las radios nativas del teléfono (Wi-Fi, 4G/5G, GNSS y FFT magnético).",
                fontSize = 12.sp,
                color = SigQuiet,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onToggle, modifier = Modifier.weight(1f)) {
                Text(if (running) "Detener monitoreo" else "Iniciar monitoreo RF")
            }
            OutlinedButton(onClick = onRefresh) { Text("Actualizar") }
        }

        // 1. Espectro Wi-Fi 2.4 GHz
        SectionCard("Wi-Fi · ${wifi.size} redes visibles") {
            val net24 = wifi.filter { it.freqMhz in 2400..2500 }
            val net5 = wifi.filter { it.freqMhz in 5000..5900 }
            val net6 = wifi.filter { it.freqMhz >= 5925 }
            Text(
                "2.4 GHz: ${net24.size} · 5 GHz: ${net5.size} · 6 GHz: ${net6.size}",
                fontSize = 11.sp,
                color = SigQuiet,
            )
            if (net24.isNotEmpty()) {
                WifiChannelChart(net24)
            }
            if (wifi.isEmpty()) {
                Text("Sin redes Wi-Fi aún. Verifique Wi-Fi y ubicación activos.", fontSize = 12.sp, color = SigQuiet)
            } else {
                wifi.take(15).forEach { w ->
                    val isEvil = w.ssid in evilTwins
                    val secColor = when {
                        isEvil -> SigAlert
                        w.security.weak -> SigWarn
                        else -> SigOk
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                w.ssid.ifBlank { "(SSID oculto)" },
                                fontSize = 13.sp,
                                color = if (isEvil) SigAlert else SigInk,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val ch = w.channel?.let { "Ch $it · " }.orEmpty()
                            val std = if (w.standard.isNotBlank()) " · ${w.standard}" else ""
                            Text(
                                "${w.freqMhz} MHz · ${ch}${w.widthMhz} MHz$std",
                                fontSize = 11.sp,
                                color = SigQuiet,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${w.rssiDbm} dBm", fontSize = 12.sp, color = SigInk, fontWeight = FontWeight.Medium)
                            Text(
                                if (isEvil) "EVIL TWIN?" else w.security.label,
                                fontSize = 10.sp,
                                color = secColor,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }

        // 2. Celular 4G / 5G
        SectionCard("Celular · ${cells.size} celdas visibles") {
            if (cells.isEmpty()) {
                Text("Sin lectura celular aún.", fontSize = 12.sp, color = SigQuiet)
            } else {
                cells.take(8).forEach { c ->
                    val freq = c.freqMhz?.let { String.format(Locale.US, "%.1f MHz", it) } ?: (c.arfcn?.let { "ARFCN $it" } ?: "—")
                    val shannon = if (c.sinrDb != null) {
                        val bw = if (c.tech == "5G NR") 40.0 else 20.0
                        String.format(Locale.US, " · C≈%.0f Mbps", RadioMath.shannonCapacityMbps(bw, c.sinrDb.toDouble()))
                    } else {
                        ""
                    }
                    val techColor = if (c.tech == "2G GSM") SigAlert else if (c.registered) SigOk else SigAccent
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(techColor))
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(
                                "${c.tech} · ${c.bandHint}" + if (c.registered) " (activa)" else "",
                                fontSize = 13.sp,
                                color = SigInk,
                                fontWeight = if (c.registered) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            val pciTxt = c.pci?.let { " · PCI $it" }.orEmpty()
                            val sinrTxt = c.sinrDb?.let { " · SINR $it dB" }.orEmpty()
                            Text("$freq$pciTxt$sinrTxt$shannon", fontSize = 11.sp, color = SigQuiet)
                        }
                        Text(
                            c.rsrpDbm?.let { "$it dBm" } ?: "—",
                            fontSize = 12.sp,
                            color = SigInk,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        // 3. Satélites GNSS
        SectionCard("Satélites GNSS (L1/L5)") {
            if (gnss.visible == 0) {
                Text("Esperando constelaciones GNSS (funciona mejor cerca de ventana o exterior).", fontSize = 12.sp, color = SigQuiet)
            } else {
                Text(
                    "${gnss.used} usados de ${gnss.visible} visibles · C/N0 prom ${String.format(Locale.US, "%.1f", gnss.avgCn0DbHz)} dB-Hz (máx ${String.format(Locale.US, "%.1f", gnss.maxCn0DbHz)})",
                    fontSize = 12.sp,
                    color = SigInk,
                )
                if (gnss.constellations.isNotEmpty()) {
                    Text(
                        gnss.constellations.entries.joinToString(" · ") { "${it.key}: ${it.value}" },
                        fontSize = 11.sp,
                        color = SigQuiet,
                    )
                }
                if (gnss.carrierFreqsMhz.isNotEmpty()) {
                    Text(
                        "Portadoras: ${gnss.carrierFreqsMhz.joinToString(", ") { String.format(Locale.US, "%.1f MHz", it) }}",
                        fontSize = 11.sp,
                        color = SigAccent,
                    )
                }
            }
        }

        // 4. FFT Magnético en vivo
        SectionCard("Oscilación y campo magnético (FFT en vivo)") {
            if (!mag.available) {
                Text("Este teléfono no expone magnetómetro.", fontSize = 12.sp, color = SigQuiet)
            } else {
                val anom = mag.magnitudeUt > 120.0
                Text(
                    String.format(Locale.US, "Campo actual: %.1f µT (normal terrestre 25–65 µT)", mag.magnitudeUt),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (anom) SigWarn else SigInk,
                )
                Text(
                    String.format(
                        Locale.US,
                        "Pico oscilación FFT: %.1f Hz (%.2f µT) · muestreo %.0f Hz",
                        mag.peakHz,
                        mag.peakAmpUt,
                        mag.sampleRateHz,
                    ),
                    fontSize = 11.sp,
                    color = SigQuiet,
                )
                if (mag.spectrum.isNotEmpty()) {
                    FftSpectrumChart(mag.spectrum)
                }
                Text(
                    "Útil para detectar oscilaciones electromagnéticas cercanas o imanes de cajas GPS ocultas bajo el chasis.",
                    fontSize = 11.sp,
                    color = SigQuiet,
                )
            }
        }

        Text(
            "Límite físico: el teléfono sin hardware externo no sintoniza 315/433 MHz (llaves, portones, TPMS), radio FM ni TV digital. Para esas bandas se requiere un receptor USB RTL-SDR.",
            fontSize = 11.sp,
            color = SigQuiet,
        )
    }
}

@Composable
private fun WifiChannelChart(nets24: List<WifiNet>) {
    Canvas(Modifier.fillMaxWidth().height(72.dp).padding(top = 4.dp)) {
        val w = size.width
        val h = size.height
        val barW = w / 14f
        for (ch in 1..13) {
            val best = nets24.filter { it.channel == ch }.maxOfOrNull { it.rssiDbm }
            val x = (ch - 0.5f) * barW
            drawLine(SigBorder, Offset(x, h - 4f), Offset(x, 6f), strokeWidth = 1f)
            if (best != null) {
                val norm = ((best + 100f) / 65f).coerceIn(0.08f, 1f)
                val barH = norm * (h - 10f)
                val count = nets24.count { it.channel == ch }
                val col = when {
                    count >= 4 -> SigAlert
                    count >= 2 -> SigWarn
                    else -> SigAccent
                }
                drawRect(
                    color = col.copy(alpha = 0.75f),
                    topLeft = Offset(x - barW * 0.35f, h - 4f - barH),
                    size = Size(barW * 0.7f, barH),
                )
            }
        }
    }
}

@Composable
private fun FftSpectrumChart(bins: List<Double>) {
    Canvas(Modifier.fillMaxWidth().height(64.dp).padding(top = 4.dp)) {
        val maxVal = (bins.maxOrNull() ?: 1.0).coerceAtLeast(0.5)
        val n = bins.size
        val step = size.width / n.coerceAtLeast(1)
        bins.forEachIndexed { i, v ->
            val norm = (v / maxVal).toFloat().coerceIn(0.03f, 1f)
            val bh = norm * (size.height - 6f)
            drawRect(
                color = SigAccent.copy(alpha = 0.8f),
                topLeft = Offset(i * step + 1f, size.height - bh),
                size = Size((step - 2f).coerceAtLeast(2f), bh),
            )
        }
    }
}

@Composable
private fun VehicleSecurityView(
    sightings: List<Sighting>,
    wifi: List<WifiNet>,
    cells: List<CellTower>,
    gnss: GnssSummary,
    mag: MagneticReading,
    rfRunning: Boolean,
    context: Context,
    onStartScan: () -> Unit,
) {
    var exportMsg by remember { mutableStateOf("") }
    val findings = remember(sightings, wifi, cells, gnss, mag) {
        VehicleSecurityAudit.evaluate(sightings, wifi, cells, gnss, mag)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Escudo del vehículo", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = SigInk)
            Text(
                "Auditoría defensiva pasiva de puertas de entrada detectables con el teléfono + lista de control físico/RF.",
                fontSize = 12.sp,
                color = SigQuiet,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStartScan, modifier = Modifier.weight(1f)) {
                Text(if (rfRunning) "Auditoría activa (actualizando)" else "Ejecutar auditoría en vivo")
            }
            OutlinedButton(
                onClick = {
                    runCatching {
                        val t = System.currentTimeMillis()
                        val bytes = SignalsExport.toJson(t, wifi, cells, gnss, mag, findings).toByteArray(Charsets.UTF_8)
                        val where = saveDownload(context, "JAC-seguridad-rf-$t.json", bytes, "application/json")
                        vaultSave(context, "rf-$t", mapOf("JAC-seguridad-rf-$t.json" to bytes))
                        exportMsg = "Reporte guardado: $where (+ bóveda privada)."
                    }.onFailure { exportMsg = "No se pudo exportar: ${it.message.orEmpty()}" }
                },
            ) {
                Text("Exportar")
            }
        }
        if (exportMsg.isNotBlank()) Text(exportMsg, fontSize = 12.sp, color = SigQuiet)

        findings.forEach { f ->
            FindingCard(f)
        }

        SectionCard("Puertas de entrada fuera del teléfono (llave, portón y alarmas)") {
            BulletItem(
                "Llave RF (315/433 MHz) — Jamming / RollJam",
                "Un inhibidor bloquea el botón «cerrar» o captura un código rodante. Defensa: pruebe físicamente la manilla / cierre de espejos antes de alejarse.",
            )
            BulletItem(
                "Keyless / Proximidad (125 kHz LF) — Ataque de relé",
                "Retransmite la señal desde su llave dentro de casa hasta la camioneta afuera. Defensa: guarde las llaves en funda Faraday o caja metálica lejos de la puerta.",
            )
            BulletItem(
                "Portón de casa (433 MHz)",
                "Muchos portones usan código fijo fácil de clonar. Al sumar un RTL-SDR podrá verificar si el pulso se repite idéntico (fijo) o cambia cada vez (rolling code).",
            )
            BulletItem(
                "Radio / Infotainment Bluetooth",
                "Borre teléfonos desconocidos emparejados en la pantalla de la camioneta y retire siempre el adaptador ELM327 del puerto OBD-II al estacionar.",
            )
        }

        SectionCard("Laboratorio defensivo: SDR recomendado") {
            BulletItem(
                "Inicio recomendado (~US$35–45): RTL-SDR Blog V4 + adaptador USB-C OTG",
                "Solo recepción (500 kHz – 1,76 GHz). Ideal y seguro para ver en cascada 315/433 MHz, detectar inhibidores (jamming), probar si su portón usa código fijo y leer TPMS.",
            )
            BulletItem(
                "Nivel intermedio (~US$120–150): SDRplay RSP1B",
                "Solo recepción de 14 bits (1 kHz – 2 GHz, 10 MHz de ancho). Mucho mejor rango dinámico y menos ruido.",
            )
            BulletItem(
                "OBD profesional para laboratorio CAN",
                "OBDLink MX+ o vLinker MC+ para evitar los cortes ISO-TP de los clones ELM327 v2.1.",
            )
        }
    }
}

@Composable
private fun FindingCard(f: SecurityFinding) {
    val accent = severityColor(f.severity)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SigCard)
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(accent.copy(alpha = 0.16f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(f.severity.label, fontSize = 10.sp, color = accent, fontWeight = FontWeight.SemiBold)
            }
            Text(f.title, fontSize = 13.sp, color = SigInk, fontWeight = FontWeight.SemiBold)
        }
        Text(f.detail, fontSize = 12.sp, color = SigQuiet)
        Text("Acción: ${f.action}", fontSize = 11.sp, color = SigInk)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SigCard)
            .border(1.dp, SigBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = SigInk)
        content()
    }
}

@Composable
private fun BulletItem(heading: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("• $heading", fontSize = 12.sp, color = SigInk, fontWeight = FontWeight.Medium)
        Text(body, fontSize = 11.sp, color = SigQuiet)
        Spacer(Modifier.height(2.dp))
    }
}

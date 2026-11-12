package app.jac.diagnosticlab.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.jac.diagnosticlab.analysis.GaugeFreshness
import app.jac.diagnosticlab.evidence.GaugeKind
import app.jac.diagnosticlab.evidence.MonitorChannel
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val Ink = Color(0xFFE8E2D6)
private val Muted = Color(0xFF9AA3AD)
private val Accent = Color(0xFFD4A24C)
private val Good = Color(0xFF6FBF8A)
private val Warn = Color(0xFFE0A060)
private val Bad = Color(0xFFE57373)
private val Track = Color(0xFF2A3340)
private val Panel = Color(0xFF161D26)

@Composable
fun LiveGaugeCard(
    channel: MonitorChannel,
    value: Double?,
    rawHint: String?,
    baseline: Double?,
    supported: Boolean?,
    statsHint: String? = null,
    freshness: GaugeFreshness = GaugeFreshness.NO_VALUE,
    mechanicNote: String? = null,
) {
    val statusColor = when (freshness) {
        GaugeFreshness.LIVE -> when {
            channel.kind == GaugeKind.WARN -> Warn
            channel.pid == 0x0D && value != null && value > 0.5 -> Warn
            channel.pid == 0x11 && value != null && value > 70.0 -> Warn
            else -> Good
        }
        GaugeFreshness.STALE -> Warn
        GaugeFreshness.READ_ERROR -> Bad
        GaugeFreshness.UNSUPPORTED -> Muted
        GaugeFreshness.ADAPTER_ONLY -> Warn
        GaugeFreshness.NO_VALUE -> Muted
    }
    val badge = when (freshness) {
        GaugeFreshness.LIVE -> "VIVO"
        GaugeFreshness.STALE -> "STALE"
        GaugeFreshness.UNSUPPORTED -> "NO SOPORTADO"
        GaugeFreshness.READ_ERROR -> "ERROR LECTURA"
        GaugeFreshness.ADAPTER_ONLY -> "SOLO DONGLE"
        GaugeFreshness.NO_VALUE -> "SIN DATO"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(channel.shortLabel, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(channel.detail, color = Muted, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    when {
                        freshness == GaugeFreshness.UNSUPPORTED -> "—"
                        freshness == GaugeFreshness.NO_VALUE -> "—"
                        value == null -> "—"
                        else -> formatValue(value, channel)
                    },
                    color = statusColor,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                )
                Text(
                    badge,
                    color = statusColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
            }
        }
        when (channel.kind) {
            GaugeKind.ARC -> ArcGauge(
                if (freshness == GaugeFreshness.LIVE || freshness == GaugeFreshness.STALE) value else null,
                channel.min, channel.max, statusColor,
            )
            GaugeKind.THERMO -> ThermoGauge(
                if (freshness == GaugeFreshness.LIVE || freshness == GaugeFreshness.STALE) value else null,
                channel.min, channel.max, statusColor,
            )
            GaugeKind.BAR -> BarGauge(
                if (freshness == GaugeFreshness.LIVE || freshness == GaugeFreshness.STALE) value else null,
                channel.min, channel.max, statusColor,
            )
            GaugeKind.WARN -> WarnStrip(value, channel)
        }
        if (!mechanicNote.isNullOrBlank()) {
            Text(mechanicNote, color = Ink, fontSize = 12.sp)
        }
        if (!statsHint.isNullOrBlank()) {
            Text(statsHint, color = Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (baseline != null) "Antes: ${formatValue(baseline, channel)}" else "Sin baseline",
                color = Muted,
                fontSize = 11.sp,
            )
            if (baseline != null && value != null && freshness != GaugeFreshness.UNSUPPORTED) {
                val delta = value - baseline
                Text(
                    "Δ ${if (delta >= 0) "+" else ""}${formatValue(delta, channel)}",
                    color = if (kotlin.math.abs(delta) < channel.max * 0.02) Good else Accent,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (!rawHint.isNullOrBlank()) {
                Text(rawHint, color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun SnapshotDeltaStrip(
    title: String,
    before: Map<Int, Double?>?,
    after: Map<Int, Double?>?,
    channels: List<MonitorChannel>,
) {
    if (before == null && after == null) return
    Column(
        Modifier
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = Accent, fontWeight = FontWeight.SemiBold)
        channels.filter { it.pid > 0 }.forEach { ch ->
            val b = before?.get(ch.pid)
            val a = after?.get(ch.pid)
            if (b == null && a == null) return@forEach
            Text(
                "%s  antes %s  →  después %s".format(
                    ch.shortLabel,
                    b?.let { formatValue(it, ch) } ?: "—",
                    a?.let { formatValue(it, ch) } ?: "—",
                ),
                color = Ink,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
fun MissingSensorBanner(lines: List<String>) {
    if (lines.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF2A1E14), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("SENSORES QUE ESTA APP NO LEE", color = Warn, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        lines.forEach { Text("· $it", color = Ink, fontSize = 12.sp) }
    }
}

@Composable
fun MechanicSituationCard(headline: String, operational: String, sensors: List<String>, hypotheses: List<Pair<String, String>>, actions: List<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A222C), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("QUÉ OCURRE (lectura)", color = Accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(headline, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(operational, color = Muted, fontSize = 12.sp)
        sensors.take(8).forEach { Text("· $it", color = Ink, fontSize = 12.sp) }
        if (hypotheses.isNotEmpty()) {
            Text("Hipótesis abiertas", color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            hypotheses.forEach { (title, status) ->
                Text("$title — $status", color = Ink, fontSize = 12.sp)
            }
        }
        Text("Siguiente medición", color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        actions.take(5).forEach { Text("→ $it", color = Good, fontSize = 12.sp) }
        Text("No prescribe piezas. No prueba el corte en la calle.", color = Muted, fontSize = 11.sp, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatValue(value: Double, channel: MonitorChannel): String {
    return when (channel.unit) {
        "rpm" -> "%.0f %s".format(value, channel.unit)
        "km/h" -> "%.0f %s".format(value, channel.unit)
        "V" -> "%.1f %s".format(value, channel.unit)
        else -> "%.1f %s".format(value, channel.unit)
    }
}

@Composable
private fun ArcGauge(value: Double?, min: Double, max: Double, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        val stroke = 14.dp.toPx()
        val diameter = min(size.width, size.height * 1.6f) - stroke
        val left = (size.width - diameter) / 2f
        val top = size.height - diameter / 1.15f
        val sweepMax = 240f
        drawArc(
            color = Track,
            startAngle = 150f,
            sweepAngle = sweepMax,
            useCenter = false,
            topLeft = Offset(left, top),
            size = Size(diameter, diameter),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        val ratio = ((value ?: min) - min) / (max - min)
        val clamped = ratio.toFloat().coerceIn(0f, 1f)
        if (value != null) {
            drawArc(
                brush = Brush.sweepGradient(listOf(color.copy(alpha = 0.4f), color)),
                startAngle = 150f,
                sweepAngle = sweepMax * clamped,
                useCenter = false,
                topLeft = Offset(left, top),
                size = Size(diameter, diameter),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            val angle = Math.toRadians((150.0 + sweepMax * clamped).toDouble())
            val cx = left + diameter / 2f
            val cy = top + diameter / 2f
            val r = diameter / 2f - stroke / 2f
            drawCircle(
                color = color,
                radius = 6.dp.toPx(),
                center = Offset(cx + (r * cos(angle)).toFloat(), cy + (r * sin(angle)).toFloat()),
            )
        }
    }
}

@Composable
private fun BarGauge(value: Double?, min: Double, max: Double, color: Color) {
    val ratio = if (value == null) 0f else (((value - min) / (max - min)).toFloat().coerceIn(0f, 1f))
    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        drawRoundRect(color = Track, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()))
        if (value != null) {
            drawRoundRect(
                color = color,
                size = Size(size.width * ratio, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()),
            )
        }
    }
}

@Composable
private fun ThermoGauge(value: Double?, min: Double, max: Double, color: Color) {
    val ratio = if (value == null) 0f else (((value - min) / (max - min)).toFloat().coerceIn(0f, 1f))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(Modifier.width(28.dp).height(88.dp)) {
            val tubeW = 10.dp.toPx()
            val cx = size.width / 2f
            val bulb = 12.dp.toPx()
            drawCircle(Track, bulb, Offset(cx, size.height - bulb))
            drawRoundRect(
                color = Track,
                topLeft = Offset(cx - tubeW / 2f, 4.dp.toPx()),
                size = Size(tubeW, size.height - bulb - 2.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(tubeW / 2f),
            )
            if (value != null) {
                val fillH = (size.height - bulb * 2) * ratio
                drawCircle(color, bulb * 0.85f, Offset(cx, size.height - bulb))
                drawRoundRect(
                    color = color,
                    topLeft = Offset(cx - tubeW / 2.6f, size.height - bulb - fillH),
                    size = Size(tubeW * 0.75f, fillH + bulb / 2f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(tubeW / 3f),
                )
            }
        }
        Column {
            Text("frío", color = Muted, fontSize = 10.sp)
            Spacer(Modifier.height(48.dp))
            Text("caliente", color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun WarnStrip(value: Double?, channel: MonitorChannel) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF3A2A1A), RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Column {
            Text(
                value?.let { "%.1f V (dongle)".format(it) } ?: "Sin ATRV en esta captura",
                color = Warn,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "REQUIRES_INDEPENDENT_MEASUREMENT — mide la batería con multímetro. ${channel.detail}.",
                color = Ink,
                fontSize = 12.sp,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

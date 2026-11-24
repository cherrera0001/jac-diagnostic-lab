package app.jac.diagnosticlab.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import kotlin.math.roundToInt

/**
 * Renderiza mosaicos minimalistas (256×256 px, con valor, unidad y estado dentro) de alto contraste y lectura pausada para GridTemplate en Android Auto.
 * Cachea por contenido visual para evitar recrear bitmaps en cada ciclo.
 */
object CarTileRenderer {
    private const val SIZE = 256
    private val cache = object : LinkedHashMap<String, CarIcon>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CarIcon>?): Boolean = size > 40
    }

    private fun accentColor(tone: CarTone): Int = when (tone) {
        CarTone.OK -> Color.parseColor("#56CC6D")
        CarTone.WARN -> Color.parseColor("#FFCA3A")
        CarTone.ALERT -> Color.parseColor("#EF5350")
        CarTone.ACCENT -> Color.parseColor("#56A8F5")
        CarTone.MUTED -> Color.parseColor("#5C6A78")
    }

    @Synchronized
    fun iconFor(tile: CarTile): CarIcon {
        val bucket = (tile.progress.coerceIn(0f, 1f) * 20f).roundToInt()
        val key = "${tile.valueText}|${tile.unitText}|${tile.subtitle}|${tile.tone.name}|$bucket"
        cache[key]?.let { return it }

        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val accent = accentColor(tile.tone)

        // Fondo oscuro calmado con borde sutil
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#121922")
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#263241")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        val cardRect = RectF(4f, 4f, SIZE - 4f, SIZE - 4f)
        canvas.drawRoundRect(cardRect, 40f, 40f, bgPaint)
        canvas.drawRoundRect(cardRect, 40f, 40f, borderPaint)

        // Arco circular minimalista (240°, de 150° a 390°)
        val arcRect = RectF(30f, 24f, SIZE - 30f, SIZE - 36f)
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#212C38")
            style = Paint.Style.STROKE
            strokeWidth = 16f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(arcRect, 150f, 240f, false, trackPaint)

        if (tile.progress > 0.01f) {
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                style = Paint.Style.STROKE
                strokeWidth = 16f
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawArc(arcRect, 150f, 240f * tile.progress.coerceIn(0f, 1f), false, fillPaint)
        }

        // Valor central grande y legible en 1 vistazo
        val valSize = when {
            tile.valueText.length >= 5 -> 52f
            tile.valueText.length == 4 -> 62f
            else -> 76f
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (tile.tone == CarTone.ALERT || tile.tone == CarTone.WARN) accent else Color.parseColor("#EEF2F6")
            textSize = valSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val yVal = SIZE * 0.45f - (valuePaint.descent() + valuePaint.ascent()) / 2f
        canvas.drawText(tile.valueText, SIZE / 2f, yVal, valuePaint)

        // Unidad pequeña abajo
        if (tile.unitText.isNotBlank()) {
            val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#8A96A3")
                textSize = 28f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(tile.unitText, SIZE / 2f, SIZE * 0.62f, unitPaint)
        }

        // Estado breve dentro de la tarjeta (se recorta con «…» para no desbordar)
        val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (tile.tone == CarTone.MUTED) Color.parseColor("#8A96A3") else accent
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val maxW = SIZE - 36f
        var status = tile.subtitle.trim()
        while (status.length > 1 && statusPaint.measureText(status) > maxW) status = status.dropLast(2).trimEnd()
        if (status != tile.subtitle.trim()) status += "…"
        if (status.isNotBlank()) canvas.drawText(status, SIZE / 2f, SIZE - 22f, statusPaint)

        val icon = CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
        cache[key] = icon
        return icon
    }
}

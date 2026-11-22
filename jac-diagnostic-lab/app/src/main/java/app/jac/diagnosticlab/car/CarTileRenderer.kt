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
 * Renderiza mosaicos minimalistas (160×160 px) de alto contraste y lectura pausada para GridTemplate en Android Auto.
 * Cachea por contenido visual para evitar recrear bitmaps en cada ciclo.
 */
object CarTileRenderer {
    private const val SIZE = 160
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
        val key = "${tile.valueText}|${tile.unitText}|${tile.tone.name}|$bucket"
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
            strokeWidth = 3f
        }
        val cardRect = RectF(4f, 4f, SIZE - 4f, SIZE - 4f)
        canvas.drawRoundRect(cardRect, 28f, 28f, bgPaint)
        canvas.drawRoundRect(cardRect, 28f, 28f, borderPaint)

        // Arco circular minimalista (240°, de 150° a 390°)
        val arcRect = RectF(20f, 20f, SIZE - 20f, SIZE - 20f)
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#212C38")
            style = Paint.Style.STROKE
            strokeWidth = 9f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(arcRect, 150f, 240f, false, trackPaint)

        if (tile.progress > 0.01f) {
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                style = Paint.Style.STROKE
                strokeWidth = 9f
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawArc(arcRect, 150f, 240f * tile.progress.coerceIn(0f, 1f), false, fillPaint)
        }

        // Valor central grande y legible en 1 vistazo
        val valSize = when {
            tile.valueText.length >= 5 -> 30f
            tile.valueText.length == 4 -> 36f
            else -> 44f
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (tile.tone == CarTone.ALERT || tile.tone == CarTone.WARN) accent else Color.parseColor("#EEF2F6")
            textSize = valSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val yVal = SIZE * 0.54f - (valuePaint.descent() + valuePaint.ascent()) / 2f
        canvas.drawText(tile.valueText, SIZE / 2f, yVal, valuePaint)

        // Unidad pequeña abajo
        if (tile.unitText.isNotBlank()) {
            val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#8A96A3")
                textSize = 18f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(tile.unitText, SIZE / 2f, SIZE * 0.80f, unitPaint)
        }

        val icon = CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
        cache[key] = icon
        return icon
    }
}

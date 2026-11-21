package app.jac.diagnosticlab.report

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import app.jac.diagnosticlab.evidence.sha256Hex
import java.io.ByteArrayOutputStream

/**
 * Dibuja el informe de taller en A4. Dos pasadas: la primera solo mide para saber el total de
 * páginas («página X de Y»); la segunda dibuja. Sin dependencias: android.graphics.pdf.
 */
object WorkshopPdf {
    private const val W = 595f
    private const val H = 842f
    private const val M = 36f
    private const val CONTENT_TOP = 70f
    private const val CONTENT_BOTTOM = H - 44f

    private val INK = 0xFF1B2430.toInt()
    private val MUTED = 0xFF6B7684.toInt()
    private val LINE = 0xFFD5DBE2.toInt()
    private val ZEBRA = 0xFFF4F6F8.toInt()
    private val BRAND = 0xFF12324A.toInt()
    private val ACCENT = 0xFFE0A93B.toInt()

    private fun color(t: Tone?) = when (t) {
        Tone.CHECK -> 0xFFC62828.toInt(); Tone.ATTENTION -> 0xFFE08600.toInt(); Tone.OK -> 0xFF2E7D32.toInt()
        Tone.INFO -> 0xFF1565C0.toInt(); Tone.MUTED -> MUTED; null -> INK
    }
    private fun tint(t: Tone?) = when (t) {
        Tone.CHECK -> 0xFFFDECEA.toInt(); Tone.ATTENTION -> 0xFFFFF4E0.toInt(); Tone.OK -> 0xFFE8F5E9.toInt()
        Tone.INFO -> 0xFFE3F0FB.toInt(); Tone.MUTED -> 0xFFF1F3F5.toInt(); null -> 0
    }

    const val BRAND_NAME = "C4A Automotive Security"
    const val BRAND_SITE = "c4a.cl"

    /** Sello vectorial C4A (sin archivos de imagen): anillo doble, «C4A» y el sitio. [r] = radio. */
    internal fun drawSeal(c: Canvas, cx: Float, cy: Float, r: Float, onDark: Boolean) {
        val fg = if (onDark) 0xFFFFFFFF.toInt() else BRAND
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ACCENT; strokeWidth = r * 0.09f }
        c.drawCircle(cx, cy, r, ring)
        ring.color = fg; ring.strokeWidth = r * 0.04f
        c.drawCircle(cx, cy, r * 0.86f, ring)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textSize = r * 0.62f }
        c.drawText("C4A", cx - t.measureText("C4A") / 2, cy + r * 0.12f, t)
        t.textSize = r * 0.26f; t.color = ACCENT
        c.drawText(BRAND_SITE, cx - t.measureText(BRAND_SITE) / 2, cy + r * 0.50f, t)
        t.textSize = r * 0.17f; t.color = fg; t.typeface = Typeface.SANS_SERIF
        val top = "SEGURIDAD AUTOMOTRIZ"
        c.drawText(top, cx - t.measureText(top) / 2, cy - r * 0.40f, t)
    }

    private class Pen(val doc: WorkshopDoc, val pdf: PdfDocument?, val totalPages: Int, val contentHash: String = "") {
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var number = 0
        var y = 0f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        fun font(size: Float, bold: Boolean = false, c: Int = INK): Paint {
            p.reset(); p.isAntiAlias = true; p.textSize = size; p.color = c
            p.typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
            return p
        }

        fun wrap(text: String, width: Float, paint: Paint): List<String> {
            if (text.isEmpty()) return listOf("")
            val out = mutableListOf<String>()
            text.split('\n').forEach { para ->
                var rest = para
                if (rest.isEmpty()) out += ""
                while (rest.isNotEmpty()) {
                    var n = paint.breakText(rest, true, width, null).coerceAtLeast(1)
                    if (n < rest.length) { val sp = rest.lastIndexOf(' ', n - 1); if (sp > 0) n = sp }
                    out += rest.substring(0, n).trimEnd()
                    rest = rest.substring(n).trimStart()
                }
            }
            return out
        }

        fun newPage() {
            finishPage()
            number++
            if (pdf != null) {
                page = pdf.startPage(PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), number).create())
                canvas = page!!.canvas
            }
            // Cabecera de página.
            canvas?.let { c ->
                c.drawRect(0f, 0f, W, 6f, font(1f, c = BRAND).also { it.style = Paint.Style.FILL })
                drawSeal(c, M + 9f, 22f, 9f, onDark = false)
                c.drawText("$BRAND_SITE · ${doc.title.uppercase()}", M + 24f, 26f, font(8f, true, BRAND))
                val right = doc.meta.firstOrNull { it.first == "VIN" }?.let { "VIN ${it.second}" } ?: ""
                c.drawText(right, W - M - font(8f).measureText(right), 30f, font(8f, c = MUTED))
                c.drawLine(M, 40f, W - M, 40f, font(1f, c = LINE).also { it.strokeWidth = 0.6f })
            }
            y = if (number == 1) 48f else CONTENT_TOP - 20f
        }

        fun finishPage() {
            val pg = page ?: return
            canvas?.let { c ->
                c.drawLine(M, H - 34f, W - M, H - 34f, font(1f, c = LINE).also { it.strokeWidth = 0.6f })
                c.drawText(doc.footer, M, H - 20f, font(7.5f, c = MUTED))
                c.drawText("$BRAND_NAME · $BRAND_SITE", M, H - 10f, font(6.8f, true, BRAND))
                val pn = "Página $number de $totalPages"
                c.drawText(pn, W - M - font(7.5f).measureText(pn), H - 20f, font(7.5f, c = MUTED))
            }
            pdf?.finishPage(pg)
            page = null
        }

        fun ensure(h: Float) { if (y + h > CONTENT_BOTTOM) newPage() }

        fun text(s: String, size: Float, bold: Boolean = false, c: Int = INK, x: Float = M, width: Float = W - 2 * M, lead: Float = 1.35f) {
            val paint = font(size, bold, c)
            wrap(s, width, paint).forEach { line ->
                ensure(size * lead)
                y += size * lead
                canvas?.drawText(line, x, y - size * 0.3f, font(size, bold, c))
            }
        }

        fun integrityBlock() {
            val h = 92f
            ensure(h + 12f)
            y += 12f
            val top = y
            canvas?.let { c ->
                c.drawRoundRect(RectF(M, top, W - M, top + h), 6f, 6f, font(1f, c = ZEBRA))
                c.drawRect(M, top, M + 5f, top + h, font(1f, c = ACCENT))
                drawSeal(c, M + 52f, top + h / 2, 32f, onDark = false)
                c.drawText("SELLO DE INTEGRIDAD · $BRAND_SITE", M + 100f, top + 20f, font(9.5f, true, BRAND))
                c.drawText("Emitido con $BRAND_NAME. Huella SHA-256 del contenido del informe:", M + 100f, top + 36f, font(8f, c = INK))
                val hash = if (contentHash.isBlank()) "—" else contentHash
                c.drawText(hash.take(32), M + 100f, top + 50f, font(8.2f, true, INK).also { it.typeface = Typeface.MONOSPACE })
                c.drawText(hash.drop(32), M + 100f, top + 62f, font(8.2f, true, INK).also { it.typeface = Typeface.MONOSPACE })
                c.drawText("La huella del archivo PDF va en el .sha256 contiguo. Es una huella de integridad, no una firma digital ni una certificación.", M + 100f, top + 78f, font(6.6f, c = MUTED))
            }
            y = top + h + 6f
        }

        fun cover() {
            newPage()
            val c = canvas
            c?.drawRect(M, y, W - M, y + 74f, font(1f, c = BRAND))
            c?.drawRect(M, y, M + 5f, y + 74f, font(1f, c = ACCENT))
            c?.drawText(doc.title, M + 18f, y + 30f, font(19f, true, 0xFFFFFFFF.toInt()))
            c?.drawText(doc.subtitle, M + 18f, y + 52f, font(10f, c = 0xFFC9D6E2.toInt()))
            c?.let { drawSeal(it, W - M - 40f, y + 37f, 31f, onDark = true) }
            y += 86f
            // Ficha en dos columnas.
            val colW = (W - 2 * M) / 2
            doc.meta.chunked(2).forEach { pair ->
                ensure(15f)
                pair.forEachIndexed { k, (key, v) ->
                    val x = M + k * colW
                    c?.drawText(key.uppercase(), x, y + 9f, font(6.5f, true, MUTED))
                    c?.drawText(v, x + 52f, y + 9f, font(8.5f, false, INK))
                }
                y += 15f
            }
            y += 6f
            summary(doc.summary)
        }

        fun summary(s: SummaryBox) {
            val pad = 12f
            val inner = W - 2 * M - 2 * pad
            val vLines = wrap(s.verdict, inner - 10f, font(12.5f, true))
            val bodyLines = s.lines.flatMap { wrap(it, inner, font(8.5f)) }
            val h = pad + vLines.size * 17f + 30f + (if (s.codes.isEmpty()) 0f else 24f) + bodyLines.size * 12f + pad
            ensure(h)
            val top = y
            canvas?.let { c ->
                c.drawRoundRect(RectF(M, top, W - M, top + h), 6f, 6f, font(1f, c = tint(s.tone)))
                c.drawRect(M, top, M + 5f, top + h, font(1f, c = color(s.tone)))
            }
            y = top + pad
            vLines.forEach { y += 17f; canvas?.drawText(it, M + pad + 4f, y - 4f, font(12.5f, true, color(s.tone))) }
            // Contadores tipo semáforo.
            y += 6f
            var x = M + pad + 4f
            s.counts.forEach { (label, tone) ->
                val w = font(8.5f, true).measureText(label) + 18f
                canvas?.let { c ->
                    c.drawRoundRect(RectF(x, y, x + w, y + 18f), 9f, 9f, font(1f, c = 0xFFFFFFFF.toInt()))
                    c.drawCircle(x + 8f, y + 9f, 3f, font(1f, c = color(tone)))
                    c.drawText(label, x + 14f, y + 12.5f, font(8.5f, true, INK))
                }
                x += w + 6f
            }
            y += 24f
            if (s.codes.isNotEmpty()) {
                x = M + pad + 4f
                s.codes.forEach { (label, tone) ->
                    val w = font(9f, true).measureText(label) + 14f
                    canvas?.let { c ->
                        c.drawRoundRect(RectF(x, y, x + w, y + 18f), 4f, 4f, font(1f, c = color(tone)))
                        c.drawText(label, x + 7f, y + 12.5f, font(9f, true, 0xFFFFFFFF.toInt()))
                    }
                    x += w + 6f
                }
                y += 24f
            }
            bodyLines.forEach { y += 12f; canvas?.drawText(it, M + pad + 4f, y - 3f, font(8.5f, false, INK)) }
            y = top + h + 12f
        }

        fun section(sec: Section) {
            ensure(46f)
            y += 8f
            canvas?.let { c ->
                c.drawRect(M, y, W - M, y + 20f, font(1f, c = BRAND))
                c.drawText("${sec.number}", M + 8f, y + 14f, font(10f, true, ACCENT))
                c.drawText(sec.title.uppercase(), M + 26f, y + 14f, font(9.5f, true, 0xFFFFFFFF.toInt()))
            }
            y += 26f
            sec.blocks.forEach { block(it); y += 4f }
        }

        fun block(b: Block) {
            when (b) {
                is Para -> {
                    if (b.tone != null && b.tone != Tone.MUTED && b.bold) {
                        val lines = wrap(b.text, W - 2 * M - 16f, font(9f, true))
                        val h = lines.size * 12.5f + 8f
                        ensure(h)
                        canvas?.let { c ->
                            c.drawRect(M, y, W - M, y + h, font(1f, c = tint(b.tone)))
                            c.drawRect(M, y, M + 3f, y + h, font(1f, c = color(b.tone)))
                        }
                        var yy = y + 4f
                        lines.forEach { yy += 12.5f; canvas?.drawText(it, M + 10f, yy - 3.5f, font(9f, true, color(b.tone))) }
                        y += h
                    } else {
                        text(b.text, if (b.tone == Tone.MUTED) 7.5f else 8.8f, b.bold, if (b.tone == Tone.MUTED) MUTED else color(b.tone))
                    }
                }
                is KeyValues -> {
                    val keyW = 150f
                    b.rows.forEachIndexed { idx, (k, v) ->
                        val lines = wrap(v, W - 2 * M - keyW - 8f, font(8.5f))
                        val h = lines.size * 11.5f + 6f
                        ensure(h)
                        if (idx % 2 == 0) canvas?.drawRect(M, y, W - M, y + h, font(1f, c = ZEBRA))
                        canvas?.drawText(k, M + 6f, y + 11.5f, font(8f, true, MUTED))
                        lines.forEachIndexed { li, line -> canvas?.drawText(line, M + keyW, y + 11.5f + li * 11.5f, font(8.5f, false, INK)) }
                        y += h
                    }
                }
                is Table -> table(b)
                is Findings -> b.items.forEach { f ->
                    val inner = W - 2 * M - 74f
                    val tl = wrap(f.title, inner, font(9f, true))
                    val dl = wrap(f.detail, inner, font(8f))
                    val h = tl.size * 12f + dl.size * 10.5f + 10f
                    ensure(h)
                    canvas?.let { c ->
                        c.drawRect(M, y, W - M, y + h, font(1f, c = tint(f.tone)))
                        c.drawRoundRect(RectF(M + 6f, y + 6f, M + 64f, y + 20f), 3f, 3f, font(1f, c = color(f.tone)))
                        val lw = font(7f, true).measureText(f.label)
                        c.drawText(f.label, M + 35f - lw / 2, y + 16f, font(7f, true, 0xFFFFFFFF.toInt()))
                    }
                    var yy = y + 5f
                    tl.forEach { yy += 12f; canvas?.drawText(it, M + 72f, yy - 3f, font(9f, true, INK)) }
                    dl.forEach { yy += 10.5f; canvas?.drawText(it, M + 72f, yy - 2.5f, font(8f, false, MUTED)) }
                    y += h + 3f
                }
                is Steps -> {
                    text(b.title, 9f, true, BRAND)
                    b.items.forEachIndexed { i, s ->
                        val lines = wrap(s, W - 2 * M - 24f, font(8.6f))
                        ensure(lines.size * 11.8f + 2f)
                        canvas?.let { c ->
                            c.drawCircle(M + 8f, y + 7.5f, 6.5f, font(1f, c = BRAND))
                            val label = "${i + 1}"
                            c.drawText(label, M + 8f - font(7.5f, true).measureText(label) / 2, y + 10.2f, font(7.5f, true, 0xFFFFFFFF.toInt()))
                        }
                        lines.forEachIndexed { li, line -> canvas?.drawText(line, M + 22f, y + 10.5f + li * 11.8f, font(8.6f, false, INK)) }
                        y += lines.size * 11.8f + 3f
                    }
                }
            }
        }

        fun table(t: Table) {
            val total = t.weights.sum()
            val widths = t.weights.map { it / total * (W - 2 * M) }
            val size = 7.6f
            val lead = 10f
            fun header() {
                ensure(18f)
                canvas?.drawRect(M, y, W - M, y + 16f, font(1f, c = 0xFF2B3A4A.toInt()))
                var x = M
                t.headers.forEachIndexed { i, h -> canvas?.drawText(h, x + 4f, y + 11f, font(7.4f, true, 0xFFFFFFFF.toInt())); x += widths[i] }
                y += 16f
            }
            header()
            t.rows.forEachIndexed { r, row ->
                val cells = row.mapIndexed { i, cell -> wrap(cell, widths.getOrElse(i) { 40f } - 8f, font(size)) }
                val h = (cells.maxOfOrNull { it.size } ?: 1) * lead + 5f
                if (y + h > CONTENT_BOTTOM) { newPage(); header() }
                val tone = t.tones.getOrNull(r)
                val bg = if (tone != null && tone != Tone.MUTED) tint(tone) else if (r % 2 == 1) ZEBRA else 0
                if (bg != 0) canvas?.drawRect(M, y, W - M, y + h, font(1f, c = bg))
                if (tone != null && tone != Tone.MUTED) canvas?.drawRect(M, y, M + 2.5f, y + h, font(1f, c = color(tone)))
                var x = M
                cells.forEachIndexed { i, lines ->
                    val statusCol = t.headers.getOrNull(i) in setOf("Estado", "Resultado")
                    val bold = i == 0 || (statusCol && tone != null && tone != Tone.MUTED)
                    val c = when { tone == Tone.MUTED -> MUTED; statusCol && tone != null -> color(tone); else -> INK }
                    lines.forEachIndexed { li, line -> canvas?.drawText(line, x + 4f, y + lead + li * lead - 1f, font(size, bold, c)) }
                    x += widths.getOrElse(i) { 40f }
                }
                canvas?.drawLine(M, y + h, W - M, y + h, font(1f, c = LINE).also { it.strokeWidth = 0.4f })
                y += h
            }
            y += 4f
        }

        fun run() {
            cover()
            doc.sections.forEach { section(it) }
            integrityBlock()
            finishPage()
        }
    }

    /** Texto de una sesión guardada (sin lectura en vivo) dentro del mismo marco profesional con sello. */
    fun renderPlain(title: String, lines: List<String>, appVersion: String, generatedAt: String): ByteArray = render(
        WorkshopDoc(
            title = "Informe de lectura OBD-II",
            subtitle = title,
            meta = listOf("Fecha" to generatedAt, "App" to "JAC Diagnostic Lab $appVersion"),
            summary = SummaryBox("Sesión guardada: resumen en texto", Tone.INFO, emptyList(), emptyList(), listOf("Generado desde el registro guardado; no hay lectura en vivo.")),
            sections = listOf(Section(1, "Contenido de la lectura", lines.map { Para(it) })),
            footer = "JAC Diagnostic Lab $appVersion · solo lectura · no autoriza a circular",
        ),
    )

    fun render(doc: WorkshopDoc): ByteArray {
        val contentHash = sha256Hex(doc.toLines().joinToString("\n").toByteArray(Charsets.UTF_8))
        val measure = Pen(doc, null, 0, contentHash).also { it.run() }
        val pdf = PdfDocument()
        Pen(doc, pdf, measure.number, contentHash).run()
        val out = ByteArrayOutputStream()
        pdf.writeTo(out)
        pdf.close()
        return out.toByteArray()
    }
}

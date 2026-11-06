package app.simple.felicity.widget

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.min

/**
 * Draws real frosted glass for the widgets.
 *
 * A launcher will not let a widget blur whatever is behind it, so the glass is rendered
 * into the widget's own background bitmap: the artwork is strongly blurred, its colours are
 * pushed a little, a white wash and a fine film grain are laid on top, then a soft specular
 * highlight, a diagonal sheen and a light rim give the pane its edge. Everything happens on
 * a small copy of the artwork, so it stays cheap.
 *
 * @author Hamza417
 */
object GlassRenderer {

    /** Look of the glass. The defaults are what the glass widgets use. */
    data class Style(
            /** Blur radius on the ~96px working copy; bigger = milkier. */
            val blurRadius: Int = 7,
            /** 1 = original colours, higher = more vivid glass. */
            val saturation: Float = 1.35f,
            /** Strength (0..1) of the white wash that makes it look frosted. */
            val frost: Float = 0.16f,
            /** Strength (0..1) of the film grain. */
            val grain: Float = 0.07f,
            /** Darkening (0..1) towards the bottom so white text stays readable. */
            val shade: Float = 0.30f,
            /** Strength (0..1) of the top highlight and diagonal sheen. */
            val sheen: Float = 0.28f
    )

    private const val WORK_SIZE = 96f
    private const val MAX_PIXELS = 900_000

    private val noiseTile: Bitmap by lazy {
        val size = 96
        val px = IntArray(size * size)
        val rnd = java.util.Random(7)
        for (i in px.indices) {
            val v = rnd.nextInt(256)
            px[i] = Color.argb(255, v, v, v)
        }
        Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    /**
     * A full frosted pane of [widthPx] × [heightPx] with rounded corners, built from [art]
     * (or a neutral gradient when [art] is null).
     */
    fun frostedPane(art: Bitmap?, widthPx: Int, heightPx: Int, cornerPx: Float, style: Style = Style()): Bitmap {
        val (w, h) = fit(widthPx, heightPx)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val shape = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val radius = cornerPx * (w.toFloat() / widthPx)
        canvas.clipPath(Path().apply { addRoundRect(shape, radius, radius, Path.Direction.CW) })
        drawGlass(canvas, art, shape, style, radius, drawRim = true)
        return out
    }

    /**
     * The artwork filling a rounded tile, with a floating frosted strip along the bottom whose
     * glass is the blurred artwork actually underneath it.
     */
    fun artWithGlassStrip(
            art: Bitmap?, widthPx: Int, heightPx: Int, cornerPx: Float,
            stripInsetPx: Float, stripHeightPx: Float, style: Style = Style()
    ): Bitmap {
        val (w, h) = fit(widthPx, heightPx)
        val k = w.toFloat() / widthPx
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val tile = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val tileRadius = cornerPx * k
        canvas.clipPath(Path().apply { addRoundRect(tile, tileRadius, tileRadius, Path.Direction.CW) })

        if (art != null) {
            canvas.drawBitmap(art, centerCrop(art, w, h), tile, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        } else {
            drawFallback(canvas, tile)
        }

        val inset = stripInsetPx * k
        val strip = RectF(inset, h - inset - stripHeightPx * k, w - inset, h - inset)
        val stripRadius = (cornerPx - stripInsetPx).coerceAtLeast(12f) * k
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(strip, stripRadius, stripRadius, Path.Direction.CW) })
        // The pane blurs the part of the picture behind it, then gets its own frost on top.
        drawGlass(canvas, art, tile, style.copy(shade = style.shade * 0.5f), stripRadius, drawRim = false)
        canvas.restore()
        drawRim(canvas, strip, stripRadius, k)
        return out
    }

    /**
     * A record: dark vinyl with fine grooves and a light sweep, the artwork as its centre label and a
     * spindle hole. While [playing] the rim glows in [accent].
     */
    fun vinylDisc(art: Bitmap?, sizePx: Int, playing: Boolean, accent: Int): Bitmap {
        val size = min(max(64, sizePx), 900)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val cx = size / 2f
        val r = size / 2f - size * 0.03f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        if (playing) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * 0.03f
            paint.color = accent
            paint.alpha = 190
            paint.maskFilter = android.graphics.BlurMaskFilter(size * 0.025f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(cx, cx, r, paint)
            paint.maskFilter = null
            paint.alpha = 255
        }

        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx, cx, r, intArrayOf(Color.rgb(34, 34, 38), Color.rgb(12, 12, 14)),
                                      floatArrayOf(0.3f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cx, r, paint)
        paint.shader = null

        // Grooves.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, size * 0.004f)
        var ring = r * 0.43f
        var i = 0
        while (ring < r * 0.97f) {
            paint.color = Color.argb(if (i % 5 == 0) 38 else 18, 255, 255, 255)
            c.drawCircle(cx, cx, ring, paint)
            ring += size * 0.0125f
            i++
        }

        // Two opposite light sweeps, like light raking across vinyl.
        paint.style = Paint.Style.FILL
        val clear = Color.argb(0, 255, 255, 255)
        val glint = Color.argb(70, 255, 255, 255)
        paint.shader = android.graphics.SweepGradient(cx, cx,
                                                      intArrayOf(clear, glint, clear, clear, glint, clear, clear),
                                                      floatArrayOf(0f, 0.08f, 0.16f, 0.5f, 0.58f, 0.66f, 1f))
        c.drawCircle(cx, cx, r, paint)
        paint.shader = null

        // Label with the artwork.
        val label = r * 0.38f
        if (art != null) {
            c.save()
            c.clipPath(Path().apply { addCircle(cx, cx, label, Path.Direction.CW) })
            c.drawBitmap(art, centerCrop(art, 100, 100), RectF(cx - label, cx - label, cx + label, cx + label),
                         Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            c.restore()
        } else {
            paint.color = accent
            c.drawCircle(cx, cx, label, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, size * 0.008f)
        paint.color = Color.argb(120, 255, 255, 255)
        c.drawCircle(cx, cx, label, paint)

        // Spindle hole.
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(10, 10, 12)
        c.drawCircle(cx, cx, r * 0.05f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, size * 0.005f)
        paint.color = Color.argb(150, 255, 255, 255)
        c.drawCircle(cx, cx, r * 0.05f, paint)

        // Outer rim.
        paint.strokeWidth = max(1f, size * 0.006f)
        paint.color = Color.argb(90, 255, 255, 255)
        c.drawCircle(cx, cx, r, paint)
        return out
    }

    /** A darker shade of [accent] for icons that sit on a white button. */
    fun iconShade(accent: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(accent, hsv)
        hsv[1] = min(1f, hsv[1] + 0.1f)
        hsv[2] = 0.38f
        return Color.HSVToColor(hsv)
    }

    /** Dominant (average) colour of the artwork, lifted a bit so it works as an accent. */
    fun accentOf(art: Bitmap?): Int {
        if (art == null) return Color.rgb(140, 160, 255)
        val tiny = Bitmap.createScaledBitmap(art, 8, 8, true)
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            val c = tiny.getPixel(x, y)
            // Ignore near-black and near-white pixels, they make dull accents.
            val l = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
            if (l in 28..235) { r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++ }
        }
        if (tiny !== art) tiny.recycle()
        if (n == 0) return Color.rgb(140, 160, 255)
        val hsv = FloatArray(3)
        Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
        hsv[1] = min(1f, max(hsv[1], 0.35f) * 1.15f)
        hsv[2] = hsv[2].coerceIn(0.72f, 1f)
        return Color.HSVToColor(hsv)
    }

    // ---------------------------------------------------------------------------------------

    private fun drawGlass(canvas: Canvas, art: Bitmap?, area: RectF, style: Style, radius: Float, drawRim: Boolean) {
        val w = area.width()
        val h = area.height()

        val blurred = blurredCopy(art, w, h, style.blurRadius)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(style.saturation) })
        canvas.drawBitmap(blurred, null, area, paint)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        // Frost: the white wash that turns "blurred picture" into "glass".
        fill.color = Color.argb((255 * style.frost).toInt(), 255, 255, 255)
        canvas.drawRect(area, fill)

        // Shade towards the bottom for text legibility.
        fill.shader = LinearGradient(0f, area.top, 0f, area.bottom,
                                     Color.argb(0, 0, 0, 0), Color.argb((255 * style.shade).toInt(), 0, 0, 0),
                                     Shader.TileMode.CLAMP)
        canvas.drawRect(area, fill)

        // Specular highlight along the top, plus a faint diagonal sheen.
        fill.shader = LinearGradient(0f, area.top, 0f, area.top + h * 0.55f,
                                     Color.argb((255 * style.sheen).toInt(), 255, 255, 255), Color.argb(0, 255, 255, 255),
                                     Shader.TileMode.CLAMP)
        canvas.drawRect(area, fill)
        fill.shader = LinearGradient(area.left, area.top, area.right, area.bottom,
                                     intArrayOf(Color.argb((255 * style.sheen * 0.55f).toInt(), 255, 255, 255),
                                                Color.argb(0, 255, 255, 255),
                                                Color.argb((255 * style.sheen * 0.3f).toInt(), 255, 255, 255)),
                                     floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(area, fill)

        // A soft inner glow in one corner, like light catching the pane.
        fill.shader = RadialGradient(area.left + w * 0.12f, area.top + h * 0.1f, max(w, h) * 0.55f,
                                     Color.argb((255 * style.sheen * 0.45f).toInt(), 255, 255, 255), Color.argb(0, 255, 255, 255),
                                     Shader.TileMode.CLAMP)
        canvas.drawRect(area, fill)

        // Film grain.
        if (style.grain > 0f) {
            val grain = Paint()
            grain.shader = BitmapShader(noiseTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            grain.alpha = (255 * style.grain).toInt()
            grain.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.OVERLAY)
            canvas.drawRect(area, grain)
        }

        if (drawRim) drawRim(canvas, area, radius, 1f)
        blurred.recycle()
    }

    /** Thin bright edge that fades from the top-left to the bottom-right, like a cut glass edge. */
    private fun drawRim(canvas: Canvas, area: RectF, radius: Float, scale: Float) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 1.2f * scale.coerceAtLeast(1f)
        stroke.shader = LinearGradient(area.left, area.top, area.right, area.bottom,
                                       Color.argb(150, 255, 255, 255), Color.argb(34, 255, 255, 255), Shader.TileMode.CLAMP)
        val r = RectF(area)
        r.inset(stroke.strokeWidth / 2f, stroke.strokeWidth / 2f)
        canvas.drawRoundRect(r, radius, radius, stroke)
    }

    private fun drawFallback(canvas: Canvas, area: RectF) {
        val p = Paint()
        p.shader = LinearGradient(area.left, area.top, area.right, area.bottom,
                                  Color.rgb(70, 76, 120), Color.rgb(24, 24, 40), Shader.TileMode.CLAMP)
        canvas.drawRect(area, p)
    }

    /** Blurs a small centre-cropped copy of [art]; the result is stretched back up by the caller. */
    private fun blurredCopy(art: Bitmap?, w: Float, h: Float, radius: Int): Bitmap {
        val scale = WORK_SIZE / max(w, h)
        val sw = max(8, (w * scale).toInt())
        val sh = max(8, (h * scale).toInt())
        val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val c = Canvas(small)
        val target = RectF(0f, 0f, sw.toFloat(), sh.toFloat())
        if (art != null) {
            // Zoom in a touch so the blur is not dominated by the edges of the cover.
            c.drawBitmap(art, centerCrop(art, sw, sh, zoom = 1.25f), target, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            drawFallback(c, target)
        }
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        small.recycle()
        val tmp = IntArray(px.size)
        repeat(3) {
            boxPass(px, tmp, sw, sh, radius, horizontal = true)
            boxPass(tmp, px, sw, sh, radius, horizontal = false)
        }
        return Bitmap.createBitmap(px, sw, sh, Bitmap.Config.ARGB_8888)
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (l in 0 until lines) {
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = src[index(i.coerceIn(0, len - 1), l, w, horizontal)]
                sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255
            }
            for (i in 0 until len) {
                dst[index(i, l, w, horizontal)] = (255 shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = src[index(min(i + r + 1, len - 1), l, w, horizontal)]
                val sub = src[index(max(i - r, 0), l, w, horizontal)]
                sr += ((add shr 16) and 255) - ((sub shr 16) and 255)
                sg += ((add shr 8) and 255) - ((sub shr 8) and 255)
                sb += (add and 255) - (sub and 255)
            }
        }
    }

    private fun index(i: Int, line: Int, w: Int, horizontal: Boolean) = if (horizontal) line * w + i else i * w + line

    private fun centerCrop(art: Bitmap, w: Int, h: Int, zoom: Float = 1f): Rect {
        val targetRatio = w.toFloat() / h
        val srcRatio = art.width.toFloat() / art.height
        var cw = art.width.toFloat()
        var ch = art.height.toFloat()
        if (srcRatio > targetRatio) cw = ch * targetRatio else ch = cw / targetRatio
        cw /= zoom; ch /= zoom
        val left = (art.width - cw) / 2f
        val top = (art.height - ch) / 2f
        return Rect(left.toInt(), top.toInt(), (left + cw).toInt(), (top + ch).toInt())
    }

    /** Keeps bitmaps under what RemoteViews can carry; the ImageView scales them back up. */
    private fun fit(w: Int, h: Int): Pair<Int, Int> {
        val cw = max(8, w)
        val ch = max(8, h)
        if (cw * ch <= MAX_PIXELS) return cw to ch
        val k = Math.sqrt(MAX_PIXELS.toDouble() / (cw * ch)).toFloat()
        return max(8, (cw * k).toInt()) to max(8, (ch * k).toInt())
    }
}

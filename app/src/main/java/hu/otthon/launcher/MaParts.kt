package hu.otthon.launcher

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

// The Music Assistant screen's drawn pieces: covers for items without a picture, the bouncing bars,
// the blurred background and the icons the app has no picture for (MaActivity.kt uses them).

/**
 * A cover without a picture: a soft two-colour gradient chosen from the name, with the name
 * on it, in the manner of the streaming apps' generated covers.
 */
internal class NameCover(private val name: String, private val round: Boolean, density: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillShader = CachedShader()
    private val baseText = 13f * density
    // the name, cleaned of the file-name look (dashes, underscores), on two or three lines
    private val clean = name.replace('_', ' ').replace(Regex("^0+-"), "").replace(Regex("\\s+"), " ").trim()
    private var laid: android.text.StaticLayout? = null
    private var laidWidth = -1
    private var laidSize = -1f
    private val t = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        textSize = 13f * density
    }
    private val pairs = arrayOf(
        intArrayOf(0xFF5B5BD6.toInt(), 0xFFB45BD6.toInt()), intArrayOf(0xFF2F8F83.toInt(), 0xFF4FB6D6.toInt()),
        intArrayOf(0xFFD6735B.toInt(), 0xFFD6B25B.toInt()), intArrayOf(0xFF3B6FD6.toInt(), 0xFF5BD6C4.toInt()),
        intArrayOf(0xFFB0476F.toInt(), 0xFF6F47B0.toInt()), intArrayOf(0xFF4E7A3A.toInt(), 0xFF9AB54A.toInt())
    )

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.width() <= 0) return
        val pair = pairs[Math.abs(name.hashCode() % pairs.size)]
        p.shader = fillShader.get(mixKey(mixKey(mixKey(mixKey(0L, b.left), b.top), b.right), b.bottom)) {
            LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), pair[0], pair[1], Shader.TileMode.CLAMP)
        }
        c.drawRect(b, p)
        p.shader = null
        t.textSize = if (clean.length <= 2) b.height() * 0.4f else maxOf(b.width() / 10f, baseText * 0.6f)
        val pad = b.width() * 0.1f
        val w = (b.width() - 2 * pad).toInt().coerceAtLeast(1)
        // the text laid out once per size
        val layout = laid?.takeIf { laidWidth == w && laidSize == t.textSize } ?: run {
            @Suppress("DEPRECATION")
            val l = android.text.StaticLayout(clean, t, w,
                if (round) android.text.Layout.Alignment.ALIGN_CENTER else android.text.Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)
            laid = l
            laidWidth = w
            laidSize = t.textSize
            l
        }
        val lines = minOf(layout.lineCount, if (round) 2 else 3)
        val h = if (lines > 0) layout.getLineBottom(lines - 1) else 0
        c.save()
        c.translate(b.left + pad, if (round) b.exactCenterY() - h / 2f else b.bottom - pad - h)
        c.clipRect(0f, 0f, w.toFloat(), h.toFloat())
        layout.draw(c)
        c.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
}

/** Three little bars that bounce while music plays. */
internal class Bars(ctx: android.content.Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var color = C.ON
        set(v) { field = v; invalidate() }
    var moving = false
        set(v) { field = v; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width / 5f
        p.color = color
        val t = System.currentTimeMillis() / 160.0
        for (i in 0..2) {
            val h = if (moving) (0.35 + 0.65 * Math.abs(Math.sin(t + i * 1.7))).toFloat() else 0.35f + i * 0.15f
            val x = i * 2 * w
            c.drawRoundRect(x, height * (1 - h), x + w, height.toFloat(), w / 2, w / 2, p)
        }
        if (moving && isShown) postInvalidateDelayed(60)
    }
}

/** A soft blur that Android 9 can do: shrink the picture to a few pixels, then let scaling smooth it. */
internal fun MaActivity.blur(b: Bitmap): Bitmap {
    val tiny = Bitmap.createScaledBitmap(b, 12, 12, true)
    val mid = Bitmap.createScaledBitmap(tiny, 48, 48, true)
    return Bitmap.createScaledBitmap(mid, 192, 192, true)
}

/** No picture for "volume": a speaker with waves (up) or a dash (down). */
internal fun MaActivity.volumeIcon(up: Boolean): Drawable = object : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private var cf: android.graphics.ColorFilter? = null
    private val speaker = android.graphics.Path().apply {
        moveTo(4f, 9.5f); lineTo(7.5f, 9.5f); lineTo(12f, 5.5f); lineTo(12f, 18.5f); lineTo(7.5f, 14.5f); lineTo(4f, 14.5f); close()
    }
    override fun draw(c: Canvas) {
        val b = bounds
        val s = b.width() / 24f
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        c.scale(s, s)
        p.colorFilter = cf
        p.color = Color.WHITE
        p.style = Paint.Style.FILL
        c.drawPath(speaker, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.8f
        if (up) {
            c.drawArc(11f, 8f, 18f, 16f, -50f, 100f, false, p)
            c.drawArc(11f, 4.5f, 21.5f, 19.5f, -50f, 100f, false, p)
        } else {
            c.drawLine(15.5f, 12f, 20.5f, 12f, p)
        }
        c.restore()
    }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { cf = colorFilter; invalidateSelf() }
    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** A magnifying glass, drawn: there is no search icon among the app's pictures. */
internal fun MaActivity.searchIcon(): Drawable = object : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private var cf: android.graphics.ColorFilter? = null
    override fun draw(c: Canvas) {
        val b = bounds
        val s = b.width() / 24f
        p.strokeWidth = 2f * s
        p.color = Color.WHITE
        p.colorFilter = cf
        c.drawCircle(b.left + 10.5f * s, b.top + 10.5f * s, 6.5f * s, p)
        c.drawLine(b.left + 15.5f * s, b.top + 15.5f * s, b.left + 20f * s, b.top + 20f * s, p)
    }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { cf = colorFilter; invalidateSelf() }
    override fun setAlpha(a: Int) { p.alpha = a }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

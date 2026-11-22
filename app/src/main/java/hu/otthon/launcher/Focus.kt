package hu.otthon.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.BlurMaskFilter
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.FocusFinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Collections
import java.util.WeakHashMap

/**
 * The home screen's cursor on a control that is not a picture (a round button, a pill, the status
 * capsule, the music card): the same soft glow around it as around the app tiles, the control
 * itself turning a solid dark (so the glow is a halo, not seen through it) and growing a little,
 * its neighbours stepping aside. Where no gliding cursor is drawn, the light fill as before.
 */
object Glow {
    const val COLOR = 0xFFE9E7E2.toInt()
    const val SOLID = 0xFF1D2026.toInt()

    fun on(v: View, radius: Float, color: Int = COLOR) {
        FocusGlide.shape(v, radius, 0f)
        FocusGlide.glow(v, color)
    }

    /** True when [v] glows: its text and icons stay light. */
    fun glows(v: View) = FocusGlide.covers(v)

    /** The edge of a glowing control: a thin light line inside the glow. */
    const val EDGE = 0xD9F5F3EE.toInt()

    /** The background of [v] while it has focus: solid dark with a thin light edge, inside its glow. */
    fun lit(v: View, radius: Float): Drawable =
        if (glows(v)) rounded(SOLID, radius, Math.round(1.5f * v.resources.displayMetrics.density), EDGE)
        else rounded(C.FOCUS_FILL, radius)

    /** The grow and the neighbours' step aside, as on the app row. */
    fun move(v: View, focused: Boolean, by: Float = Look.GROW) {
        Look.grow(v, focused, by)
        Neighbors.push(v, focused)
    }
}

/**
 * When a tile in a row grows on focus, its neighbours step aside a little, with a small spring,
 * as if making room for it; they come back when it lets go.
 */
object Neighbors {
    private val spring = android.view.animation.OvershootInterpolator(1.6f)

    fun push(v: View, focused: Boolean) {
        val row = v.parent as? LinearLayout ?: return
        if (row.orientation != LinearLayout.HORIZONTAL || Motion.level == Motion.OFF) return
        val i = row.indexOfChild(v)
        // half of the growth, plus a little air
        val step = v.width * 0.05f + v.resources.displayMetrics.density * 5f
        for (k in 1..2) {
            val share = if (k == 1) 1f else 0.35f
            for (side in intArrayOf(-1, 1)) {
                val n = row.getChildAt(i + side * k) ?: continue
                if (n.width == 0) continue
                val to = if (focused) side * step * share else 0f
                n.animate().translationX(to).setDuration(if (focused) 320L else 220L)
                    .setInterpolator(if (focused) spring else DecelerateInterpolator()).start()
            }
        }
    }
}

/**
 * The focus cursor. One light shape per screen glides from the control that had focus to the
 * one that has it now, instead of each control lighting up on its own. It is painted as part
 * of the host's background, so it lies under the controls: a focused control only makes its
 * own background transparent and its text dark (see [fill]).
 */
class FocusGlide private constructor(private val host: View) {
    private class Shape(val radius: Float, val outset: Float) {
        /** 0: the light fill. Otherwise the cursor is a soft glow of this colour around the control. */
        var glow = 0
        /** At Christmas, the string of fairy lights around it (the pictures); false: the plain glow. */
        var lights = true
    }

    companion object {
        private val hosts: MutableSet<View> = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
        private val shapes = WeakHashMap<View, Shape>()

        /** Draws a cursor for every control inside [host] that has a [shape]. */
        fun install(host: View) {
            if (hosts.add(host)) FocusGlide(host)
        }

        /** How the cursor looks on [v]. A negative radius means fully rounded ends. */
        fun shape(v: View, radius: Float, outset: Float = 0f) {
            shapes[v] = Shape(radius, outset)
        }

        /** Christmas: the glow around app tiles becomes a string of twinkling fairy lights. */
        @Volatile
        var festive = false

        /** The bulbs' colours, in the order they hang on the string. */
        private val BULBS = intArrayOf(
            0xFFFF3B30.toInt(), 0xFFFFC52E.toInt(), 0xFF34C759.toInt(), 0xFF3D8BFF.toInt(), 0xFFFF8A1F.toInt()
        )

        /** Makes the cursor a soft glow of [color] around [v] instead of the light fill under it. */
        fun glow(v: View, color: Int, lights: Boolean = true) {
            shapes[v]?.let {
                it.glow = color
                it.lights = lights
            }
        }

        private val glows = LinkedHashMap<String, Bitmap>()

        /**
         * A blurred rounded rectangle as a mask, drawn at a quarter of the size: the blur hides
         * the scaling, and it is made once per tile size instead of being blurred every frame.
         */
        private fun glowMask(w: Float, h: Float, radius: Float, margin: Float): Bitmap {
            val key = "${w.toInt()}x${h.toInt()}r${radius.toInt()}m${margin.toInt()}"
            glows[key]?.let { return it }
            val q = 4f
            val bw = Math.ceil(((w + 2 * margin) / q).toDouble()).toInt().coerceAtLeast(2)
            val bh = Math.ceil(((h + 2 * margin) / q).toDouble()).toInt().coerceAtLeast(2)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8)
            // the blur is about half the margin, so the glow has faded out well before the mask's edge
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(margin * 0.48f / q, BlurMaskFilter.Blur.NORMAL) }
            val spread = margin * 0.08f
            val m = (margin - spread) / q
            Canvas(bmp).drawRoundRect(m, m, bw - m, bh - m, (radius + spread) / q, (radius + spread) / q, p)
            glows[key] = bmp
            while (glows.size > 12) glows.remove(glows.keys.first())
            return bmp
        }

        /** True when a cursor is drawn for [v], so [v] must not paint a highlight of its own. */
        fun covers(v: View): Boolean {
            if (!shapes.containsKey(v)) return false
            var p: View? = v.parent as? View
            while (p != null) {
                if (hosts.contains(p)) return true
                p = p.parent as? View
            }
            return false
        }

        /** Background of a focused control: nothing under a cursor, the light fill otherwise. */
        fun fill(v: View, radius: Float): Drawable =
            rounded(if (covers(v)) Color.TRANSPARENT else C.FOCUS_FILL, radius)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.FOCUS_FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val margin = host.resources.displayMetrics.density * 44f
    private val glowRect = RectF()
    private var glowMask: Bitmap? = null
    private var fromGlow = 0f
    private var nowGlow = 0f
    private var fromColor = 0
    private var nowColor = 0
    private var nowLights = true
    private val from = RectF()
    private val to = RectF()
    private val now = RectF()
    private var fromRadius = 0f
    private var nowRadius = 0f
    private val clip = Rect()
    private val nextClip = Rect()
    private var clipped = false
    private var target: View? = null
    private var shown = false
    private var t = 1f
    private var move: ValueAnimator? = null
    private val a = IntArray(2)
    private val b = IntArray(2)

    // ---------- fairy lights ----------
    private val dens = host.resources.displayMetrics.density
    private val wirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dens
        color = 0xFF1C3322.toInt()
    }
    private val socketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A4A30.toInt() }
    private val bulbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val haloRadius = 15f * dens
    private val haloPaints = BULBS.map { c ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.RadialGradient(
                0f, 0f, haloRadius,
                intArrayOf((c and 0xFFFFFF) or 0xAA000000.toInt(), (c and 0xFFFFFF) or 0x40000000, c and 0xFFFFFF),
                floatArrayOf(0f, 0.4f, 1f), android.graphics.Shader.TileMode.CLAMP
            )
        }
    }
    private val lightPath = android.graphics.Path()
    private val measure = android.graphics.PathMeasure()
    private val lightRect = RectF()
    private val bulbOval = RectF()
    private val pos = FloatArray(2)
    private val tan = FloatArray(2)

    /** A string of bulbs hung around the cursor's rectangle, each twinkling at its own pace. */
    private fun drawLights(canvas: Canvas, k: Float) {
        val o = 4f * dens
        lightRect.set(now)
        lightRect.inset(-o, -o)
        val r = nowRadius + o
        lightPath.reset()
        lightPath.addRoundRect(lightRect, r, r, android.graphics.Path.Direction.CW)
        measure.setPath(lightPath, true)
        val len = measure.length
        if (len <= 0f) return
        val n = maxOf(10, Math.round(len / (23f * dens)))
        val step = len / n
        val time = android.os.SystemClock.uptimeMillis() / 1000f
        val a = (k * 255).toInt()
        wirePaint.alpha = a
        // a plain rounded rectangle: the graphics chip draws it itself, a path it would have to
        // rasterise and upload again in every frame
        canvas.drawRoundRect(lightRect, r, r, wirePaint)
        // halos first, so no halo covers a neighbouring bulb
        val glow = FloatArray(n)
        for (i in 0 until n) {
            // each bulb has its own speed; together they look like a slow, irregular twinkle
            val speed = 1.3f + ((i * 7) % 5) * 0.32f
            glow[i] = 0.5f + 0.5f * kotlin.math.sin(time * speed + i * 2.3f)
            measure.getPosTan(i * step, pos, tan)
            val hp = haloPaints[i % BULBS.size]
            hp.alpha = (k * (0.25f + 0.75f * glow[i]) * 255).toInt()
            val save = canvas.save()
            canvas.translate(pos[0] + tan[1] * 5f * dens, pos[1] - tan[0] * 5f * dens)
            canvas.drawCircle(0f, 0f, haloRadius, hp)
            canvas.restoreToCount(save)
        }
        for (i in 0 until n) {
            measure.getPosTan(i * step, pos, tan)
            val c = BULBS[i % BULBS.size]
            val save = canvas.save()
            canvas.translate(pos[0], pos[1])
            // outward is to the left of the direction of travel on a clockwise path
            canvas.rotate(Math.toDegrees(Math.atan2(-tan[0].toDouble(), tan[1].toDouble())).toFloat())
            socketPaint.alpha = a
            canvas.drawRect(-0.4f * dens, -1.7f * dens, 2.4f * dens, 1.7f * dens, socketPaint)
            bulbPaint.color = blend(dim(c), c, 0.35f + 0.65f * glow[i])
            bulbPaint.alpha = a
            bulbOval.set(1.6f * dens, -2.5f * dens, 8.6f * dens, 2.5f * dens)
            canvas.drawOval(bulbOval, bulbPaint)
            shinePaint.alpha = (k * (0.25f + 0.55f * glow[i]) * 255).toInt()
            bulbOval.set(4.6f * dens, -1.5f * dens, 7.2f * dens, -0.3f * dens)
            canvas.drawOval(bulbOval, shinePaint)
            canvas.restoreToCount(save)
        }
    }

    private fun dim(c: Int): Int {
        val r = ((c shr 16) and 0xFF) * 2 / 5
        val g = ((c shr 8) and 0xFF) * 2 / 5
        val b = (c and 0xFF) * 2 / 5
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private val onFocus = ViewTreeObserver.OnGlobalFocusChangeListener { _, v -> focusTo(v) }
    private val onPreDraw = ViewTreeObserver.OnPreDrawListener { track(); true }

    init {
        host.background = Layer(host.background)
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = attach()
            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnGlobalFocusChangeListener(onFocus)
                v.viewTreeObserver.removeOnPreDrawListener(onPreDraw)
            }
        })
        if (host.isAttachedToWindow) attach()
    }

    private fun attach() {
        host.viewTreeObserver.addOnGlobalFocusChangeListener(onFocus)
        host.viewTreeObserver.addOnPreDrawListener(onPreDraw)
        focusTo(host.findFocus())
    }

    private fun inside(v: View): Boolean {
        var p: View? = v.parent as? View
        while (p != null) {
            if (p === host) return true
            p = p.parent as? View
        }
        return false
    }

    private fun focusTo(v: View?) {
        val next = v?.takeIf { shapes.containsKey(it) && inside(it) }
        if (next === target) return
        target = next
        move?.cancel()
        if (next == null) {
            shown = false
            host.invalidate()
            return
        }
        if (!shown || now.isEmpty || Motion.level == Motion.OFF) {
            t = 1f                              // nothing to glide from: appear in place
        } else {
            from.set(now)
            fromRadius = nowRadius
            fromGlow = nowGlow
            fromColor = nowColor
            t = 0f
            move = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 260
                interpolator = Motion.EASE
                addUpdateListener { t = it.animatedValue as Float; host.invalidate() }
                start()
            }
        }
        shown = true
        host.invalidate()
    }

    /** Runs before every frame: follows the target while it scrolls, zooms or is glided to. */
    private fun track() {
        val v = target ?: return
        val s = shapes[v]
        if (s == null || !v.isAttachedToWindow || v.width == 0 || !v.isShown) {
            if (!now.isEmpty) {
                now.setEmpty()
                host.invalidate()
            }
            return
        }
        host.getLocationInWindow(b)
        v.getLocationInWindow(a)
        val x = (a[0] - b[0]).toFloat()
        val y = (a[1] - b[1]).toFloat()
        to.set(x - s.outset, y - s.outset, x + v.width * v.scaleX + s.outset, y + v.height * v.scaleY + s.outset)
        val toRadius = if (s.radius < 0) to.height() / 2 else s.radius + s.outset
        val k = if (now.isEmpty) 1f else t
        val l = from.left + (to.left - from.left) * k
        val tp = from.top + (to.top - from.top) * k
        val r = from.right + (to.right - from.right) * k
        val bt = from.bottom + (to.bottom - from.bottom) * k
        val rad = fromRadius + (toRadius - fromRadius) * k
        var changed = false

        // glow or light fill, blended while the cursor moves between the two kinds
        if (nowLights != s.lights) {
            nowLights = s.lights
            changed = true
        }
        val toGlow = if (s.glow != 0) 1f else 0f
        val toColor = if (s.glow != 0) s.glow else nowColor
        if (s.glow != 0) {
            glowMask = glowMask(v.width + 2 * s.outset, v.height + 2 * s.outset, if (s.radius < 0) (v.height / 2f + s.outset) else s.radius + s.outset, margin)
        }
        val g: Float
        val col: Int
        if (k >= 1f || now.isEmpty) {
            g = toGlow
            col = toColor
        } else {
            g = fromGlow + (toGlow - fromGlow) * k
            col = when {
                fromGlow == 0f -> toColor
                toGlow == 0f -> fromColor
                else -> blend(fromColor, toColor, k)
            }
        }
        if (g != nowGlow || col != nowColor) {
            nowGlow = g
            nowColor = col
            changed = true
        }
        if (k >= 1f) {
            if (now != to || nowRadius != toRadius) {
                now.set(to)
                nowRadius = toRadius
                changed = true
            }
        } else if (now.left != l || now.top != tp || now.right != r || now.bottom != bt || nowRadius != rad) {
            now.set(l, tp, r, bt)
            nowRadius = rad
            changed = true
        }

        // Do not paint outside the lists the target scrolls in.
        var any = false
        var p: ViewGroup? = v.parent as? ViewGroup
        while (p != null && p !== host) {
            // only along the way a list scrolls, and only when it has more than it shows: a row
            // that fits keeps the glow and a horizontal row does not cut it above and below
            val across = p is HorizontalScrollView && (p.getChildAt(0)?.width ?: 0) > p.width - p.paddingLeft - p.paddingRight
            val down = p is ScrollView && (p.getChildAt(0)?.height ?: 0) > p.height - p.paddingTop - p.paddingBottom
            if (across || down) {
                p.getLocationInWindow(a)
                var cl = a[0] - b[0]
                var ct = a[1] - b[1]
                var cr = cl + p.width
                var cb = ct + p.height
                if (p.clipToPadding) {
                    cl += p.paddingLeft; ct += p.paddingTop; cr -= p.paddingRight; cb -= p.paddingBottom
                }
                // the other way it does not move: no edge there
                if (across) { ct = -100_000; cb = 100_000 } else { cl = -100_000; cr = 100_000 }
                if (!any) nextClip.set(cl, ct, cr, cb)
                else nextClip.set(maxOf(nextClip.left, cl), maxOf(nextClip.top, ct), minOf(nextClip.right, cr), minOf(nextClip.bottom, cb))
                any = true
            }
            p = p.parent as? ViewGroup
        }
        if (any != clipped || (any && nextClip != clip)) {
            clipped = any
            clip.set(nextClip)
            changed = true
        }
        if (changed) host.invalidate()
    }

    private fun blend(a: Int, b: Int, k: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + ((((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * k)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** The host's own background with the cursor painted over it. */
    private inner class Layer(private val base: Drawable?) : Drawable() {
        override fun draw(canvas: Canvas) {
            base?.draw(canvas)
            if (!shown || now.isEmpty) return
            val mask = glowMask
            if (nowGlow > 0f && festive && nowLights) {
                val save = canvas.save()
                if (clipped) canvas.clipRect(clip.left - margin, clip.top - margin, clip.right + margin, clip.bottom + margin)
                drawLights(canvas, nowGlow)
                canvas.restoreToCount(save)
                // keep twinkling, at a pace that is kind to the TV
                host.postInvalidateDelayed(if (LowRam.on) 120L else 40L)
            } else if (nowGlow > 0f && mask != null) {
                val save = canvas.save()
                // the glow may spill over the edge of its list, as far as it reaches
                if (clipped) canvas.clipRect(clip.left - margin, clip.top - margin, clip.right + margin, clip.bottom + margin)
                glowRect.set(now)
                glowRect.inset(-margin, -margin)
                glowPaint.color = (nowColor and 0x00FFFFFF) or ((nowGlow * 0xE6).toInt() shl 24)
                canvas.drawBitmap(mask, null, glowRect, glowPaint)
                canvas.restoreToCount(save)
            }
            if (nowGlow < 1f) {
                val save = canvas.save()
                if (clipped) canvas.clipRect(clip)
                paint.alpha = ((1f - nowGlow) * 255).toInt()
                canvas.drawRoundRect(now, nowRadius, nowRadius, paint)
                canvas.restoreToCount(save)
            }
        }

        override fun onBoundsChange(bounds: Rect) {
            base?.bounds = bounds
        }

        override fun getOutline(outline: Outline) {
            if (base != null) base.getOutline(outline) else super.getOutline(outline)
        }

        override fun getPadding(padding: Rect): Boolean = base?.getPadding(padding) ?: false
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}

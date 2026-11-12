package hu.otthon.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
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

object C {
    const val TEXT = 0xFFF5F3EE.toInt()
    const val TEXT2 = 0xFFE4E1DA.toInt()
    const val TEXT3 = 0xFFC9C6BE.toInt()
    const val DARK = 0xFF141413.toInt()
    const val DARK2 = 0xFF444441.toInt()
    const val GLASS = 0x8C0A0A0E.toInt()
    const val GLASS_SOFT = 0x730A0A0E.toInt()
    const val PANEL = 0xE60E0F13.toInt()
    const val CARD = 0xB30E0F13.toInt()
    const val ROW = 0x12FFFFFF
    const val BORDER = 0x1FFFFFFF
    const val FOCUS_FILL = 0xF0F5F3EE.toInt()
    const val ON = 0xFF9FE1CB.toInt()
}

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

fun Context.label(text: String, sp: Float, color: Int = C.TEXT, bold: Boolean = false, light: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        typeface = when {
            light -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            bold -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
        includeFontPadding = false
    }

/** Non-focusable status chip (icon + text). */
fun Context.chip(iconRes: Int, text: String): TextView = label(text, 12f, C.TEXT).apply {
    val d = getDrawable(iconRes)!!.mutate()
    d.setBounds(0, 0, dp(13f), dp(13f))
    d.setTint(C.TEXT)
    setCompoundDrawables(d, null, null, null)
    compoundDrawablePadding = dp(5f)
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(10f), dp(6f), dp(12f), dp(6f))
    background = rounded(C.GLASS_SOFT, dp(20f).toFloat(), 1, C.BORDER)
}

/** Focusable pill button: dark glass, turns light when focused. */
fun Context.pill(text: String, iconRes: Int?, onClick: () -> Unit): TextView {
    val ctx = this
    return label(text, 11f, C.TEXT).apply {
        isFocusable = true
        isClickable = true
        maxLines = 1
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12f), dp(7f), dp(14f), dp(7f))
        val icon: Drawable? = iconRes?.let { ctx.getDrawable(it)!!.mutate().apply { setBounds(0, 0, dp(12f), dp(12f)) } }
        if (icon != null) {
            setCompoundDrawables(icon, null, null, null)
            compoundDrawablePadding = dp(5f)
        }
        FocusGlide.shape(this, -1f)
        fun paint(focused: Boolean) {
            background = if (focused) FocusGlide.fill(this, dp(20f).toFloat())
            else rounded(C.GLASS, dp(20f).toFloat(), 1, C.BORDER)
            setTextColor(if (focused) C.DARK else C.TEXT)
            icon?.setTint(if (focused) C.DARK else C.TEXT)
        }
        paint(false)
        setOnFocusChangeListener { _, f -> paint(f) }
        setOnClickListener { onClick() }
    }
}

fun weatherIcon(condition: String): Int = when (condition) {
    "sunny" -> R.drawable.ic_sun
    "clear-night" -> R.drawable.ic_moon
    "partlycloudy" -> R.drawable.ic_partly
    "fog" -> R.drawable.ic_fog
    "rainy", "pouring" -> R.drawable.ic_rain
    "snowy", "snowy-rainy", "hail" -> R.drawable.ic_snow
    "lightning", "lightning-rainy" -> R.drawable.ic_storm
    "windy", "windy-variant" -> R.drawable.ic_wind
    else -> R.drawable.ic_cloud
}

/** App tile with banner (or icon + name) and focus zoom. */
class AppTile(ctx: Context, val app: AppEntry) : FrameLayout(ctx) {
    private val ring = rounded(0, ctx.dp(8f).toFloat(), ctx.dp(2f), C.TEXT)
    private val glass = rounded(C.GLASS, ctx.dp(8f).toFloat(), 1, C.BORDER)
    private val solid = rounded(0xFF17181D.toInt(), ctx.dp(8f).toFloat())
    var onFocused: (() -> Unit)? = null

    init {
        isFocusable = true
        isClickable = true
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        background = glass
        FocusGlide.shape(this, ctx.dp(8f).toFloat(), ctx.dp(3f).toFloat())
        val banner = app.banner
        if (banner != null) {
            addView(ImageView(ctx).apply {
                setImageDrawable(banner)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else {
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            col.addView(ImageView(ctx).apply { setImageDrawable(app.icon) }, LinearLayout.LayoutParams(ctx.dp(28f), ctx.dp(28f)))
            col.addView(ctx.label(app.label, 10f, C.TEXT, bold = true).apply {
                setPadding(ctx.dp(4f), ctx.dp(5f), ctx.dp(4f), 0)
                maxLines = 1
                gravity = Gravity.CENTER
            })
            addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        contentDescription = app.label
        setOnFocusChangeListener { v, f ->
            // Under the gliding cursor the tile only needs to be opaque: the cursor is its frame.
            val glide = f && FocusGlide.covers(this)
            foreground = if (f && !glide) ring else null
            background = if (glide) solid else glass
            v.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f)
                .translationZ(if (f && !glide) ctx.dp(6f).toFloat() else 0f).setDuration(140).start()
            if (f) onFocused?.invoke()
        }
    }
}

/** Camera tile: latest snapshot with the camera name along the bottom edge. */
class CamTile(ctx: Context, val item: HaItem, radius: Float, labelSp: Float, zoom: Float) : FrameLayout(ctx) {
    val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val ring = rounded(0, radius, ctx.dp(2f), C.TEXT)

    init {
        isFocusable = true
        isClickable = true
        background = rounded(0xFF22262C.toInt(), radius)
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val name = item.title
        val caption = ctx.label(name, labelSp, C.TEXT, bold = true)
        caption.maxLines = 1
        caption.setPadding(ctx.dp(labelSp * 0.6f), ctx.dp(labelSp * 0.25f), ctx.dp(labelSp * 0.6f), ctx.dp(labelSp * 0.3f))
        caption.setBackgroundColor(0x99060709.toInt())
        addView(caption, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        contentDescription = "$name camera"
        FocusGlide.shape(this, radius, ctx.dp(3f).toFloat())
        setOnFocusChangeListener { v, f ->
            val glide = f && FocusGlide.covers(this)
            foreground = if (f && !glide) ring else null
            v.animate().scaleX(if (f) zoom else 1f).scaleY(if (f) zoom else 1f)
                .translationZ(if (f && !glide) ctx.dp(6f).toFloat() else 0f).setDuration(140).start()
        }
    }
}

/**
 * Horizontal row that glides so the focused child sits inside the side padding, instead of
 * being squeezed or cut at the screen edge. The stock scroller jumps whenever two moves come
 * within a quarter of a second, so this one runs its own animation and retargets it.
 */
class RowScroller(ctx: Context) : HorizontalScrollView(ctx) {
    private var anim: ValueAnimator? = null
    private var goal = 0

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        clipChildren = false
    }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        if (childCount == 0 || width == 0) return 0
        val base = if (anim?.isRunning == true) goal else scrollX
        val left = base + paddingLeft
        val right = base + width - paddingRight
        val to = when {
            rect.left < left -> base + rect.left - left
            rect.right > right -> base + rect.right - right
            else -> base
        }
        val max = maxOf(0, getChildAt(0).width + paddingLeft + paddingRight - width)
        glideTo(to.coerceIn(0, max))
        return 0
    }

    /** Moves focus only; the scrolling follows from the method above. */
    override fun arrowScroll(direction: Int): Boolean {
        val current = findFocus() ?: return false
        val next = FocusFinder.getInstance().findNextFocus(this, current, direction) ?: return false
        return next !== this && next.requestFocus(direction)
    }

    private fun glideTo(to: Int) {
        val running = anim?.isRunning == true
        if (if (running) to == goal else to == scrollX) return
        anim?.cancel()
        goal = to
        if (Motion.level == Motion.OFF) {
            scrollTo(to, 0)
            return
        }
        anim = ValueAnimator.ofInt(scrollX, to).apply {
            duration = 260
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
            start()
        }
    }
}

/** Vertical list with the same gliding scroll as [RowScroller]. */
class ColScroller(ctx: Context) : ScrollView(ctx) {
    private var anim: ValueAnimator? = null
    private var goal = 0

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        if (childCount == 0 || height == 0) return 0
        val base = if (anim?.isRunning == true) goal else scrollY
        val top = base + paddingTop
        val bottom = base + height - paddingBottom
        var to = when {
            rect.top < top -> base + rect.top - top
            rect.bottom > bottom -> base + rect.bottom - bottom
            else -> base
        }
        val max = maxOf(0, getChildAt(0).height + paddingTop + paddingBottom - height)
        // Close to either end: go all the way, so a heading above the first row is not left cut.
        val near = context.dp(48f)
        if (to < near && rect.bottom <= height - paddingBottom) to = 0
        else if (to > max - near && rect.top >= max + paddingTop) to = max
        glideTo(to.coerceIn(0, max))
        return 0
    }

    override fun arrowScroll(direction: Int): Boolean {
        val current = findFocus()
        val next = if (current == null) null else FocusFinder.getInstance().findNextFocus(this, current, direction)
        if (next != null && next !== this) return next.requestFocus(direction)
        anim?.cancel()      // nothing more to focus: let the list scroll its last part into view
        return super.arrowScroll(direction)
    }

    private fun glideTo(to: Int) {
        val running = anim?.isRunning == true
        if (if (running) to == goal else to == scrollY) return
        anim?.cancel()
        goal = to
        if (Motion.level == Motion.OFF) {
            scrollTo(0, to)
            return
        }
        anim = ValueAnimator.ofInt(scrollY, to).apply {
            duration = 260
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { scrollTo(0, it.animatedValue as Int) }
            start()
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
    private class Shape(val radius: Float, val outset: Float)

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
            t = 0f
            move = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 200
                interpolator = DecelerateInterpolator(1.7f)
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
            if (p is ScrollView || p is HorizontalScrollView) {
                p.getLocationInWindow(a)
                var cl = a[0] - b[0]
                var ct = a[1] - b[1]
                var cr = cl + p.width
                var cb = ct + p.height
                if (p.clipToPadding) {
                    cl += p.paddingLeft; ct += p.paddingTop; cr -= p.paddingRight; cb -= p.paddingBottom
                }
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

    /** The host's own background with the cursor painted over it. */
    private inner class Layer(private val base: Drawable?) : Drawable() {
        override fun draw(canvas: Canvas) {
            base?.draw(canvas)
            if (!shown || now.isEmpty) return
            val save = canvas.save()
            if (clipped) canvas.clipRect(clip)
            canvas.drawRoundRect(now, nowRadius, nowRadius, paint)
            canvas.restoreToCount(save)
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

/**
 * Base of every sub-screen. The window itself switches at once (the background and the clock
 * are the same on both sides, so that is invisible, and cross-fading two full-screen windows
 * is more than this TV can do smoothly). Only the panel moves, as one pre-rendered layer.
 */
open class PanelActivity : Activity() {
    private var moving: View? = null
    private var fromSide = true
    private var entering = false
    private var closing = false
    private val waiting = ArrayList<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    /** Opens another sub-screen. */
    fun open(intent: Intent, requestCode: Int = -1) {
        if (requestCode >= 0) startActivityForResult(intent, requestCode) else startActivity(intent)
        overridePendingTransition(0, 0)
    }

    /** Call once the panel is in the layout: [side] slides it in from the right, otherwise it grows. */
    protected fun animateIn(panel: View, side: Boolean = true) {
        moving = panel
        fromSide = side
        if (Motion.level == Motion.OFF) return
        val full = Motion.level == Motion.FULL
        entering = true
        panel.alpha = 0f
        if (full && side) panel.translationX = dp(56f).toFloat()
        if (full && !side) {
            panel.scaleX = 0.95f
            panel.scaleY = 0.95f
        }
        // Start on the first frame, when building and laying out the screen is already done.
        panel.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                panel.viewTreeObserver.removeOnPreDrawListener(this)
                panel.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
                    .setDuration(if (full) 240L else 140L).setInterpolator(DecelerateInterpolator(2f))
                    .withLayer().withEndAction { entered() }.start()
                return true
            }
        })
        panel.postDelayed({ entered() }, 800)
    }

    private fun entered() {
        if (!entering) return
        entering = false
        val run = ArrayList(waiting)
        waiting.clear()
        run.forEach { it() }
    }

    /** Runs [work] when the opening animation is over, so heavy layout cannot make it stutter. */
    protected fun afterEnter(work: () -> Unit) {
        if (entering) waiting.add(work) else work()
    }

    private fun reallyFinish() {
        if (isFinishing) return
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun finish() {
        val p = moving
        if (p == null || Motion.level == Motion.OFF || !p.isAttachedToWindow) {
            reallyFinish()
            return
        }
        if (closing) return
        closing = true
        val full = Motion.level == Motion.FULL
        p.animate().cancel()
        val out = p.animate().alpha(0f).setStartDelay(0).setDuration(if (full) 150L else 110L)
            .setInterpolator(AccelerateInterpolator(1.5f)).withLayer()
        if (full && fromSide) out.translationX(dp(40f).toFloat())
        if (full && !fromSide) out.scaleX(0.96f).scaleY(0.96f)
        out.withEndAction { reallyFinish() }.start()
        p.postDelayed({ reallyFinish() }, 400)
    }
}

/** Small on/off indicator for toggle rows. */
class SwitchDot(ctx: Context) : View(ctx) {
    var on = false
        set(v) { field = v; invalidate() }
    var dark = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.color = if (on) (if (dark) 0xFF1D9E75.toInt() else C.ON) else (if (dark) 0x55141413 else 0x44FFFFFF)
        c.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, p)
        p.color = if (dark && !on) C.DARK2 else if (on) C.DARK else C.TEXT
        val r = h / 2 - h * 0.14f
        c.drawCircle(if (on) w - h / 2 else h / 2, h / 2, r, p)
    }
}

/** One line in a settings list or popup: title, optional value on the right, optional radio mark. */
class RowView(ctx: Context, title: String, value: String? = null, selected: Boolean? = null) : LinearLayout(ctx) {
    private val radio: TextView? = if (selected != null) ctx.label(if (selected) "●" else "○", 12f, C.TEXT2) else null
    val titleView: TextView = ctx.label(title, 12f, C.TEXT)
    val valueView: TextView = ctx.label(value ?: "", 11f, C.TEXT3)
    private var dot: SwitchDot? = null
    private var chevron: ImageView? = null
    var passive = false
        set(v) { field = v; paint(isFocused) }

    /** Shows an on/off switch at the end of the row. */
    fun switch(on: Boolean) {
        val d = dot ?: SwitchDot(context).also {
            dot = it
            addView(it, LayoutParams(context.dp(26f), context.dp(14f)).apply { leftMargin = context.dp(10f) })
        }
        d.on = on
        paint(isFocused)
    }

    /** Marks the row as one that opens in place; the arrow turns over while it is open. */
    fun chevron(open: Boolean) {
        val c = chevron ?: ImageView(context).also {
            it.setImageResource(R.drawable.ic_chevron)
            chevron = it
            addView(it, LayoutParams(context.dp(12f), context.dp(12f)).apply { leftMargin = context.dp(8f) })
        }
        c.rotation = if (open) 180f else 0f
        paint(isFocused)
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        setPadding(ctx.dp(12f), ctx.dp(9f), ctx.dp(14f), ctx.dp(9f))
        radio?.let { addView(it, LayoutParams(-2, -2).apply { rightMargin = ctx.dp(10f) }) }
        titleView.maxLines = 1
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        addView(titleView, LayoutParams(0, -2, 1f))
        valueView.maxLines = 1
        valueView.maxWidth = ctx.dp(210f)
        valueView.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        valueView.gravity = Gravity.END
        addView(valueView, LayoutParams(-2, -2).apply { leftMargin = ctx.dp(12f) })
        FocusGlide.shape(this, ctx.dp(8f).toFloat())
        paint(false)
        setOnFocusChangeListener { _, f -> paint(f) }
    }

    private fun paint(f: Boolean) {
        val r = context.dp(8f).toFloat()
        background = if (f) FocusGlide.fill(this, r) else rounded(C.ROW, r)
        titleView.setTextColor(if (f) C.DARK else if (passive) C.TEXT3 else C.TEXT)
        valueView.setTextColor(if (f) C.DARK2 else C.TEXT3)
        radio?.setTextColor(if (f) C.DARK else C.TEXT2)
        dot?.dark = f
        chevron?.setColorFilter(if (f) C.DARK2 else C.TEXT3)
    }
}

/** Floating popups in the launcher's own style. */
object Popup {
    private fun shell(act: Activity, title: String, widthDp: Float): Triple<Dialog, LinearLayout, FrameLayout> {
        val d = Dialog(act, android.R.style.Theme_Translucent_NoTitleBar)
        val root = FrameLayout(act)
        root.setBackgroundColor(0xB3000000.toInt())
        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF516171C.toInt(), act.dp(16f).toFloat(), 1, C.BORDER)
            setPadding(act.dp(16f), act.dp(16f), act.dp(16f), act.dp(14f))
        }
        panel.addView(act.label(title, 15f, C.TEXT, bold = true).apply {
            setPadding(act.dp(4f), 0, act.dp(4f), act.dp(10f))
        })
        FocusGlide.install(panel)
        root.addView(panel, FrameLayout.LayoutParams(act.dp(widthDp), -2, Gravity.CENTER))
        d.setContentView(root)
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        d.window?.setWindowAnimations(0)
        if (Motion.level != Motion.OFF) {
            val full = Motion.level == Motion.FULL
            panel.alpha = 0f
            if (full) {
                panel.scaleX = 0.95f
                panel.scaleY = 0.95f
            }
            d.setOnShowListener {
                panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(if (full) 180L else 120L)
                    .setInterpolator(DecelerateInterpolator(2f)).withLayer().start()
            }
        }
        return Triple(d, panel, root)
    }

    fun menu(act: Activity, title: String, items: List<String>, selected: Int = -1, onPick: (Int) -> Unit) {
        val (d, panel, _) = shell(act, title, 340f)
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        var first: View? = null
        items.forEachIndexed { i, s ->
            val row = RowView(act, s, null, if (selected >= 0) i == selected else null)
            row.setOnClickListener { d.dismiss(); onPick(i) }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = act.dp(4f) })
            if (i == selected || first == null) first = row
        }
        val scroll = ColScroller(act).apply { addView(list) }
        val maxH = act.dp(360f)
        val wanted = items.size * act.dp(38f)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, if (wanted > maxH) maxH else -2))
        d.show()
        first?.requestFocus()
    }

    fun text(act: Activity, title: String, initial: String, hint: String = "", onDone: (String) -> Unit) {
        val (d, panel, _) = shell(act, title, 460f)
        val edit = EditText(act).apply {
            setText(initial)
            setSelection(initial.length)
            this.hint = hint
            setHintTextColor(0x66FFFFFF)
            setTextColor(C.TEXT)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            background = rounded(0x1FFFFFFF, act.dp(8f).toFloat(), 1, C.BORDER)
            setPadding(act.dp(12f), act.dp(10f), act.dp(12f), act.dp(10f))
        }
        panel.addView(edit, LinearLayout.LayoutParams(-1, -2))
        val save = RowView(act, "Save")
        val cancel = RowView(act, "Cancel")
        save.setOnClickListener { d.dismiss(); onDone(edit.text.toString().trim()) }
        cancel.setOnClickListener { d.dismiss() }
        edit.setOnEditorActionListener { _, _, _ -> save.requestFocus(); true }
        panel.addView(save, LinearLayout.LayoutParams(-1, -2).apply { topMargin = act.dp(10f) })
        panel.addView(cancel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = act.dp(4f) })
        d.show()
        edit.requestFocus()
    }
}

/**
 * Full-screen background shared by every launcher screen: the vector landscape or a photo
 * for the current time of day, cross-faded, with a darkening layer on top.
 */
class Backdrop(ctx: Context) : FrameLayout(ctx) {
    private val landscape = LandscapeView(ctx)
    private val photoA = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
    private val photoB = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
    private val dimView = View(ctx)
    private var front: ImageView? = null
    private var key: String? = null
    private var gen = 0

    init {
        setBackgroundColor(Color.BLACK)
        addView(landscape, LayoutParams(-1, -1))
        addView(photoA, LayoutParams(-1, -1))
        addView(photoB, LayoutParams(-1, -1))
        addView(dimView, LayoutParams(-1, -1))
    }

    /** [flat] darkens evenly (for panels); otherwise only the top and bottom are darkened. */
    fun setDim(dim: Float, flat: Boolean = false) {
        val d = dim.coerceIn(0f, 1f)
        dimView.background = if (flat) {
            ColorDrawable(Color.argb((d * 255).toInt(), 6, 7, 10))
        } else {
            val bottom = (d * 255).toInt()
            val top = (d * 0.7f * 255).toInt()
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(top, 6, 7, 10), Color.argb(0, 6, 7, 10), Color.argb(bottom / 4, 6, 7, 10), Color.argb(bottom, 6, 7, 10))
            )
        }
    }

    fun show(bg: String, phase: Phase, animate: Boolean) {
        val turn = if (Backgrounds.isPhoto(bg)) Backgrounds.variant(context, bg, phase) ?: "" else ""
        val k = bg + "/" + phase.key + "/" + turn
        if (k == key) return
        val anim = animate && key != null
        key = k
        val g = ++gen
        if (Backgrounds.isPhoto(bg)) {
            val app = context.applicationContext
            Bg.run({ Backgrounds.photo(app, bg, phase) }) { bmp ->
                if (g == gen) {
                    if (bmp != null) showPhoto(bmp, anim) else showLandscape("alpine", phase, anim)
                }
            }
        } else {
            showLandscape(bg, phase, anim)
        }
    }

    private fun showLandscape(style: String, p: Phase, animate: Boolean) {
        landscape.style = if (style == "hills") "domb" else "alpesi"
        landscape.setPhase(p, animate)
        val ms = if (animate) 2500L else 0L
        photoA.animate().alpha(0f).setDuration(ms).start()
        photoB.animate().alpha(0f).setDuration(ms).start()
        front = null
    }

    private fun showPhoto(bmp: Bitmap, animate: Boolean) {
        val next = if (front === photoA) photoB else photoA
        val prev = front
        next.animate().cancel()
        next.setImageBitmap(bmp)
        next.bringToFront()
        dimView.bringToFront()
        next.alpha = 0f
        next.animate().alpha(1f).setDuration(if (animate) 2500L else 0L).withEndAction {
            if (prev != null && front === next) {
                prev.alpha = 0f
                prev.setImageDrawable(null)
            }
        }.start()
        front = next
    }
}

/**
 * The shared look of every secondary screen: the live background with the clock on the left
 * and a floating glass panel docked to the right, like the settings screen.
 */
class PanelFrame(private val act: Activity, title: String, hint: String? = null) {
    val root = FrameLayout(act)
    val backdrop = Backdrop(act)
    val left = LinearLayout(act)
    val panel = LinearLayout(act)
    val titleView: TextView = act.label(title, 17f, C.TEXT, bold = true)
    private val clock = act.label("", 62f, C.TEXT, light = true)
    private val date = act.label("", 15f, C.TEXT2)

    init {
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))

        left.orientation = LinearLayout.VERTICAL
        clock.letterSpacing = -0.02f
        date.setPadding(act.dp(2f), act.dp(6f), 0, 0)
        left.addView(clock, LinearLayout.LayoutParams(-2, -2))
        left.addView(date, LinearLayout.LayoutParams(-2, -2))
        root.addView(left, FrameLayout.LayoutParams(act.dp(272f), -2).apply {
            leftMargin = act.dp(48f)
            topMargin = act.dp(34f)
        })

        panel.orientation = LinearLayout.VERTICAL
        panel.background = rounded(C.PANEL, act.dp(20f).toFloat(), 1, C.BORDER)
        panel.clipToOutline = true
        panel.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        FocusGlide.install(panel)
        val header = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
        if (hint != null) header.addView(act.label(hint, 10f, C.TEXT3))
        panel.addView(header, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = act.dp(22f)
            rightMargin = act.dp(22f)
            topMargin = act.dp(20f)
        })
        root.addView(panel, FrameLayout.LayoutParams(act.dp(610f), -1, Gravity.END).apply {
            topMargin = act.dp(26f)
            bottomMargin = act.dp(26f)
            rightMargin = act.dp(30f)
        })
    }

    fun tick() {
        val now = java.time.ZonedDateTime.now()
        clock.text = now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.ENGLISH))
        date.text = now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d", java.util.Locale.ENGLISH))
    }

    fun update(cfg: Config) {
        tick()
        backdrop.setDim(cfg.dim)
        backdrop.show(cfg.background, Backgrounds.phase(act, cfg), false)
    }
}

data class AppEntry(val pkg: String, val label: String, val banner: Drawable?, val icon: Drawable)

object Apps {
    fun all(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val seen = LinkedHashMap<String, AppEntry>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == ctx.packageName || seen.containsKey(pkg)) continue
                val banner = try {
                    ri.activityInfo.loadBanner(pm) ?: pm.getApplicationBanner(pkg)
                } catch (e: Exception) { null }
                seen[pkg] = AppEntry(pkg, ri.loadLabel(pm).toString(), banner, ri.loadIcon(pm))
            }
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    fun one(ctx: Context, pkg: String): AppEntry? {
        val pm = ctx.packageManager
        return try {
            val ai = pm.getApplicationInfo(pkg, 0)
            if (!ai.enabled) return null
            val banner = try { pm.getApplicationBanner(pkg) } catch (e: Exception) { null }
            AppEntry(pkg, pm.getApplicationLabel(ai).toString(), banner, pm.getApplicationIcon(pkg))
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    const val STOCK = "com.google.android.tvlauncher"

    /** The package the Home button opens now; "android" means the TV would ask. */
    fun defaultHome(ctx: Context): String? {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return ctx.packageManager.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }

    /** True when the Google TV home app is on the TV at all, switched on or off. */
    fun stockInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getApplicationInfo(STOCK, android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * The shell commands that hand the Home button to this launcher or back to Google TV home.
     * Google's home app is a system app with a higher priority, so while it is switched on the
     * TV always picks it; the only way to choose another one is to switch it off (it stays
     * installed, and switching it back on restores it).
     */
    fun homeCommand(ctx: Context, ours: Boolean): String {
        val me = android.content.ComponentName(ctx, HomeActivity::class.java).flattenToShortString()
        return if (ours) "pm disable-user --user 0 $STOCK; cmd package set-home-activity $me"
        else "pm enable $STOCK; cmd package set-home-activity $STOCK/.MainActivity"
    }

    /** This TV's address on the local network, for finding it among Home Assistant's players. */
    fun localIp(): String? = try {
        java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            .flatMap { java.util.Collections.list(it.inetAddresses) }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }?.hostAddress
    } catch (e: Exception) {
        null
    }

    fun stockHomeIntent(ctx: Context): Intent? {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(STOCK)
        val ri = ctx.packageManager.resolveActivity(i, 0) ?: return null
        return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun openStockHome(ctx: Context): Boolean {
        val i = stockHomeIntent(ctx) ?: return false
        return try { ctx.startActivity(i); true } catch (e: Exception) { false }
    }

    fun openSystemSettings(ctx: Context): Boolean = try {
        ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    fun launch(ctx: Context, pkg: String): Boolean {
        val pm = ctx.packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); true } catch (e: Exception) { false }
    }
}

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
 * Horizontal row that glides so the focused child sits inside the side padding, instead of
 * being squeezed or cut at the screen edge. The stock scroller jumps whenever two moves come
 * within a quarter of a second, so this one runs its own animation and retargets it.
 */
class RowScroller(ctx: Context) : HorizontalScrollView(ctx) {
    private val glide = Follow(17f) { scrollTo(Math.round(it), 0) }
    private val goal get() = glide.target.toInt()

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        clipChildren = false
    }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        if (childCount == 0 || width == 0) return 0
        val base = if (glide.running) goal else scrollX
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
        if (if (glide.running) to == goal else to == scrollX) return
        if (!glide.running) glide.snap(scrollX.toFloat())
        glide.to(to.toFloat())
    }
}

/** Vertical list with the same gliding scroll as [RowScroller]. */
class ColScroller(ctx: Context) : ScrollView(ctx) {
    private val glide = Follow(13f) { scrollTo(0, Math.round(it)) }
    private val goal get() = glide.target.toInt()

    /**
     * Where the list should be scrolled to for the focused [View], or null for the default
     * (scroll only as far as needed). Lets a list keep its focused row in one place.
     */
    var anchor: ((View) -> Int?)? = null

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        if (childCount == 0 || height == 0) return 0
        val max = maxOf(0, getChildAt(0).height + paddingTop + paddingBottom - height)
        findFocus()?.let { f -> anchor?.invoke(f) }?.let { glideTo(it.coerceIn(0, max)); return 0 }
        val base = if (glide.running) goal else scrollY
        val top = base + paddingTop
        val bottom = base + height - paddingBottom
        var to = when {
            rect.top < top -> base + rect.top - top
            rect.bottom > bottom -> base + rect.bottom - bottom
            else -> base
        }
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
        glide.stop()        // nothing more to focus: let the list scroll its last part into view
        return super.arrowScroll(direction)
    }

    /** Jumps to the top without gliding. */
    fun reset() {
        glide.snap(0f)
    }

    private fun glideTo(to: Int) {
        if (if (glide.running) to == goal else to == scrollY) return
        if (!glide.running) glide.snap(scrollY.toFloat())
        glide.to(to.toFloat())
    }
}

/**
 * A list of rows that stops on whole rows: the focused row, with the heading above it, is brought
 * [gap] below the list's top, so a row is never left cut at the top edge; what is above it fades
 * out in a [fade] strip while it moves. The end gets room enough for the last row to come up too.
 * [content] is the list's one child (the rows and their headings).
 */
fun ColScroller.rowsAtTop(content: android.view.ViewGroup, gap: Int, fade: Int) {
    isVerticalFadingEdgeEnabled = fade > 0
    setFadingEdgeLength(fade)
    fun block(of: View): View? {
        var v: View = of
        while (v.parent !== content) v = v.parent as? View ?: return null
        return v
    }
    fun targetOf(b: View): Int {
        val i = content.indexOfChild(b)
        val prev = if (i > 0) content.getChildAt(i - 1) else null
        val head = prev is android.widget.TextView
        val top = if (head) prev!!.top else b.top
        val first = (0 until (if (head) i - 1 else i)).none { content.getChildAt(it).hasFocusable() }
        return if (first) 0 else maxOf(0, top - gap)
    }
    anchor = { f -> block(f)?.let { targetOf(it) } }
    fun pad() {
        if (height == 0 || content.childCount == 0) return
        val last = (content.childCount - 1 downTo 0).map { content.getChildAt(it) }.firstOrNull { it.hasFocusable() } ?: return
        val t = targetOf(last)
        val want = maxOf(0, t + height - paddingTop - paddingBottom - (content.height - content.paddingBottom))
        if (content.paddingBottom != want) content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, want)
    }
    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> post { pad() } }
    content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> post { pad() } }
}


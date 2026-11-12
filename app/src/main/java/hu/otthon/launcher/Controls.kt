package hu.otthon.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Settings row with a slider. Left and right change the value at once; at either end the
 * key is let through, so Left still leads back to the menu.
 */
class SliderRow(
    ctx: Context,
    title: String,
    value: Float,
    private val min: Float,
    private val max: Float,
    private val step: Float,
    private val format: (Float) -> String,
    private val onChange: (Float) -> Unit
) : LinearLayout(ctx) {
    var value = value.coerceIn(min, max)
        private set
    private val titleView = ctx.label(title, 12f, C.TEXT)
    private val valueView = ctx.label("", 11f, C.TEXT3)
    private val bar = Bar(ctx)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        setPadding(ctx.dp(12f), ctx.dp(9f), ctx.dp(14f), ctx.dp(9f))
        titleView.maxLines = 1
        addView(titleView, LayoutParams(0, -2, 1f))
        valueView.gravity = Gravity.END
        addView(valueView, LayoutParams(ctx.dp(40f), -2).apply { leftMargin = ctx.dp(8f) })
        addView(bar, LayoutParams(ctx.dp(150f), ctx.dp(14f)).apply { leftMargin = ctx.dp(10f) })
        FocusGlide.shape(this, ctx.dp(8f).toFloat())
        paint(false)
        setOnFocusChangeListener { _, f -> paint(f) }
        setOnKeyListener { _, code, e ->
            val dir = when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                else -> 0
            }
            dir != 0 && e.action == KeyEvent.ACTION_DOWN && nudge(dir)
        }
        setOnClickListener { if (!nudge(1)) set(min) }
    }

    private fun nudge(dir: Int): Boolean {
        val steps = Math.round((value - min) / step) + dir
        return set(min + steps * step)
    }

    private fun set(v: Float): Boolean {
        val next = v.coerceIn(min, max)
        if (Math.abs(next - value) < step / 100f) return false
        value = next
        paint(isFocused)
        onChange(next)
        return true
    }

    private fun paint(f: Boolean) {
        val r = context.dp(8f).toFloat()
        background = if (f) FocusGlide.fill(this, r) else rounded(C.ROW, r)
        titleView.setTextColor(if (f) C.DARK else C.TEXT)
        valueView.setTextColor(if (f) C.DARK2 else C.TEXT3)
        valueView.text = format(value)
        bar.dark = f
        bar.fraction = if (max > min) (value - min) / (max - min) else 0f
        bar.invalidate()
    }

    private class Bar(ctx: Context) : View(ctx) {
        var fraction = 0f
        var dark = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val knob = height / 2f
            val left = knob
            val right = width - knob
            val y = height / 2f
            val half = height * 0.14f
            val x = left + (right - left) * fraction
            p.color = if (dark) 0x40141413 else 0x40FFFFFF
            c.drawRoundRect(left, y - half, right, y + half, half, half, p)
            p.color = if (dark) C.DARK else C.TEXT
            c.drawRoundRect(left, y - half, x, y + half, half, half, p)
            c.drawCircle(x, y, knob * 0.82f, p)
        }
    }
}

/** Settings row with a few choices side by side. Left and right move the choice at once. */
class SegmentRow(
    ctx: Context,
    title: String,
    options: List<String>,
    selected: Int,
    private val onChange: (Int) -> Unit
) : LinearLayout(ctx) {
    var selected = selected.coerceIn(0, options.size - 1)
        private set
    private val titleView = ctx.label(title, 12f, C.TEXT)
    private val segments = ArrayList<TextView>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        setPadding(ctx.dp(12f), ctx.dp(5f), ctx.dp(8f), ctx.dp(5f))
        titleView.maxLines = 1
        addView(titleView, LayoutParams(0, -2, 1f))
        val group = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        options.forEachIndexed { i, o ->
            val t = ctx.label(o, 11f, C.TEXT2)
            t.setPadding(ctx.dp(9f), ctx.dp(5f), ctx.dp(9f), ctx.dp(5f))
            segments.add(t)
            group.addView(t, LayoutParams(-2, -2).apply { if (i > 0) leftMargin = ctx.dp(2f) })
        }
        addView(group, LayoutParams(-2, -2).apply { leftMargin = ctx.dp(10f) })
        FocusGlide.shape(this, ctx.dp(8f).toFloat())
        paint(false)
        setOnFocusChangeListener { _, f -> paint(f) }
        setOnKeyListener { _, code, e ->
            val dir = when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                else -> 0
            }
            dir != 0 && e.action == KeyEvent.ACTION_DOWN && select(this.selected + dir)
        }
        setOnClickListener { select((this.selected + 1) % segments.size) }
    }

    private fun select(i: Int): Boolean {
        if (i < 0 || i >= segments.size || i == selected) return false
        selected = i
        paint(isFocused)
        onChange(i)
        return true
    }

    private fun paint(f: Boolean) {
        val r = context.dp(8f).toFloat()
        background = if (f) FocusGlide.fill(this, r) else rounded(C.ROW, r)
        titleView.setTextColor(if (f) C.DARK else C.TEXT)
        segments.forEachIndexed { i, t ->
            val on = i == selected
            t.background = if (on) rounded(if (f) C.DARK else C.FOCUS_FILL, context.dp(11f).toFloat()) else null
            t.setTextColor(
                when {
                    on && f -> C.TEXT
                    on -> C.DARK
                    f -> C.DARK2
                    else -> C.TEXT2
                }
            )
        }
    }
}

package hu.otthon.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Line chart of one numeric sensor over a time window. */
class ChartView(ctx: Context) : View(ctx) {

    private var buckets = FloatArray(0)
    private var start = 0L
    private var end = 0L
    private var unit = ""

    var min = Float.NaN
        private set
    var max = Float.NaN
        private set
    var avg = Float.NaN
        private set

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(1.6f).toFloat()
        color = C.TEXT
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val grid = Paint().apply { color = 0x22FFFFFF; strokeWidth = 1f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.TEXT3
        textSize = 10f * ctx.resources.displayMetrics.scaledDensity
    }

    /** [points] are (time, value) changes, oldest first. A value holds until the next change. */
    fun setData(points: List<Pair<Long, Float>>, start: Long, end: Long, unit: String) {
        this.start = start
        this.end = end
        this.unit = unit
        val n = 240
        val sum = DoubleArray(n)
        val cnt = IntArray(n)
        val span = (end - start).coerceAtLeast(1L).toDouble()
        var before = Float.NaN
        for ((t, v) in points) {
            if (t < start) { before = v; continue }
            val b = (((t - start) / span) * n).toInt().coerceIn(0, n - 1)
            sum[b] += v.toDouble()
            cnt[b]++
        }
        val out = FloatArray(n)
        var last = before
        for (i in 0 until n) {
            if (cnt[i] > 0) last = (sum[i] / cnt[i]).toFloat()
            out[i] = last
        }
        buckets = out
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        var total = 0.0
        var k = 0
        for (v in out) {
            if (v.isNaN()) continue
            if (v < lo) lo = v
            if (v > hi) hi = v
            total += v
            k++
        }
        if (k == 0) {
            min = Float.NaN; max = Float.NaN; avg = Float.NaN
        } else {
            min = lo; max = hi; avg = (total / k).toFloat()
        }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (buckets.isEmpty() || min.isNaN()) return
        val padL = context.dp(52f).toFloat()
        val padR = context.dp(6f).toFloat()
        val padT = context.dp(8f).toFloat()
        val padB = context.dp(20f).toFloat()
        val w = width - padL - padR
        val h = height - padT - padB
        if (w <= 0 || h <= 0) return

        var lo = min
        var hi = max
        if (hi - lo < 1e-6f) { lo -= 1f; hi += 1f }
        val margin = (hi - lo) * 0.08f
        lo -= margin
        hi += margin

        // horizontal guides with value labels
        for (i in 0..2) {
            val frac = i / 2f
            val y = padT + h * (1 - frac)
            c.drawLine(padL, y, padL + w, y, grid)
            val value = lo + (hi - lo) * frac
            val lbl = Fmt.num(value.toDouble())
            c.drawText(lbl, padL - text.measureText(lbl) - context.dp(6f), y + text.textSize * 0.35f, text)
        }

        // the line and the soft area under it
        val path = Path()
        val area = Path()
        var started = false
        var lastX = padL
        val n = buckets.size
        for (i in 0 until n) {
            val v = buckets[i]
            if (v.isNaN()) continue
            val x = padL + w * (i / (n - 1f))
            val y = padT + h * (1 - (v - lo) / (hi - lo))
            if (!started) {
                path.moveTo(x, y)
                area.moveTo(x, padT + h)
                area.lineTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
                area.lineTo(x, y)
            }
            lastX = x
        }
        if (started) {
            area.lineTo(lastX, padT + h)
            area.close()
            fill.shader = LinearGradient(0f, padT, 0f, padT + h, 0x40F5F3EE, 0x00F5F3EE, Shader.TileMode.CLAMP)
            c.drawPath(area, fill)
            c.drawPath(path, line)
        }

        // time labels
        val long = end - start > 26 * 3600_000L
        val fmt = DateTimeFormatter.ofPattern(if (long) "EEE d" else "HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault())
        for (i in 0..3) {
            val frac = i / 3f
            val t = start + ((end - start) * frac).toLong()
            val lbl = fmt.format(Instant.ofEpochMilli(t))
            val tw = text.measureText(lbl)
            val x = (padL + w * frac - tw / 2).coerceIn(padL, padL + w - tw)
            c.drawText(lbl, x, height - context.dp(4f).toFloat(), text)
        }
    }
}

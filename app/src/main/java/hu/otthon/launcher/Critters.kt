package hu.otthon.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Little bears and bunnies that live on the home screen: they walk along the top edges of the
 * tiles, chips and buttons, sit down for a while, and hop from one to the next. A tile that gets
 * focus startles whoever is standing on it. Purely for fun; switched on in Settings › Appearance.
 *
 * [platforms] lists the views they may stand on; it is asked again every couple of seconds.
 */
class CritterLayer(ctx: Context, private val platforms: () -> List<View>) : View(ctx) {

    private enum class State { WALK, IDLE, JUMP }

    private inner class Critter(val bunny: Boolean, val coat: Int, val light: Int) {
        var on: View? = null
        var x = 0f                 // position along the platform, from its left edge
        var dir = 1f
        var state = State.IDLE
        var timer = 1f
        var phase = Random.nextFloat() * 10f
        var wave = false
        // a jump: from a point in this view to a spot on another platform
        var fromX = 0f
        var fromY = 0f
        var target: View? = null
        var targetX = 0f
        var t = 0f
        var jumpTime = 0.6f
        // where it was drawn last
        var px = 0f
        var py = 0f
        var alpha = 0f
    }

    private val d = resources.displayMetrics.density
    /** Drawing units to dp: the bears are about 30 dp tall standing, the bunnies about 34 with ears. */
    private val SIZE = 1.05f
    private val critters = ArrayList<Critter>()
    private var places: List<View> = emptyList()
    private var placesAt = 0L
    private val rects = HashMap<View, RectF>()
    private val myLoc = IntArray(2)
    private val loc = IntArray(2)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private var last = 0L
    private var running = false

    /** True while the home screen is slid away or covered: nothing moves, nothing is drawn. */
    var paused = false
        set(v) {
            field = v
            visibility = if (v || critters.isEmpty()) INVISIBLE else VISIBLE
            if (!v) kick()
        }

    init {
        isFocusable = false
        isClickable = false
        visibility = INVISIBLE
    }

    /** 0: none, 1: a few, 2: a whole family. */
    fun setCount(level: Int) {
        val want = when (level) { 1 -> 3; 2 -> 7; else -> 0 }
        if (want == critters.size) return
        critters.clear()
        for (i in 0 until want) {
            val bunny = i % 2 == 1 || (want > 3 && i % 3 == 2)
            critters.add(
                if (bunny) {
                    if (i % 4 == 1) Critter(true, 0xFFF4F1EC.toInt(), 0xFFF6C3CF.toInt())
                    else Critter(true, 0xFFC9B39A.toInt(), 0xFFF2C2B8.toInt())
                } else {
                    if (i % 4 == 0) Critter(false, 0xFF8A5A3B.toInt(), 0xFFD8B38C.toInt())
                    else Critter(false, 0xFFB8834F.toInt(), 0xFFEBCB9E.toInt())
                }
            )
        }
        visibility = if (paused || critters.isEmpty()) INVISIBLE else VISIBLE
        kick()
    }

    /** A view got focus: anyone standing on it jumps up in surprise. */
    fun onFocus(v: View?) {
        if (v == null) return
        for (c in critters) {
            if (c.on === v && c.state != State.JUMP) startJump(c, v, c.x, 0.45f)
        }
    }

    private fun kick() {
        if (running || paused || critters.isEmpty() || !isAttachedToWindow) return
        running = true
        last = SystemClock.uptimeMillis()
        post(step)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        kick()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        removeCallbacks(step)
    }

    private val step = object : Runnable {
        override fun run() {
            if (paused || critters.isEmpty() || !isAttachedToWindow) {
                running = false
                return
            }
            val now = SystemClock.uptimeMillis()
            val dt = min(0.1f, (now - last) / 1000f)
            last = now
            update(dt)
            invalidate()
            // about 30 frames a second is plenty for them and kind to the TV
            postDelayed(this, 33L)
        }
    }

    // ---------- where they can stand ----------

    private fun usable(v: View) = v.isAttachedToWindow && v.isShown && v.width > 20 * d && v.alpha > 0.5f

    /** The top edge of a view as seen on screen, in this layer's coordinates. */
    private fun rect(v: View): RectF? {
        rects[v]?.let { return it }
        if (!usable(v)) return null
        v.getLocationInWindow(loc)
        val r = RectF(
            (loc[0] - myLoc[0]).toFloat(), (loc[1] - myLoc[1]).toFloat(),
            (loc[0] - myLoc[0]) + v.width * v.scaleX, (loc[1] - myLoc[1]) + v.height * v.scaleY
        )
        if (r.right < 0 || r.left > width || r.top < 0 || r.top > height) return null
        rects[v] = r
        return r
    }

    private fun place(c: Critter) {
        val list = places.filter { rect(it) != null }
        if (list.isEmpty()) {
            c.on = null
            return
        }
        val v = list[Random.nextInt(list.size)]
        val r = rect(v)!!
        c.on = v
        c.x = 8 * d + Random.nextFloat() * max(1f, r.width() - 16 * d)
        c.dir = if (Random.nextBoolean()) 1f else -1f
        c.state = State.IDLE
        c.timer = 0.5f + Random.nextFloat() * 2f
        c.alpha = 0f
    }

    private fun startJump(c: Critter, to: View, toX: Float, time: Float) {
        c.fromX = c.px
        c.fromY = c.py
        c.target = to
        c.targetX = toX
        c.t = 0f
        c.jumpTime = time
        c.state = State.JUMP
        c.wave = false
    }

    // ---------- behaviour ----------

    private fun update(dt: Float) {
        getLocationInWindow(myLoc)
        rects.clear()
        val now = SystemClock.uptimeMillis()
        if (now - placesAt > 2000L || places.isEmpty()) {
            places = platforms()
            placesAt = now
        }
        for (c in critters) {
            c.phase += dt
            c.alpha = min(1f, c.alpha + dt * 2f)
            val on = c.on
            val r = on?.let { rect(it) }
            if (c.state != State.JUMP && (on == null || r == null)) {
                place(c)
                continue
            }
            when (c.state) {
                State.IDLE -> {
                    c.timer -= dt
                    if (c.timer <= 0f) {
                        c.state = State.WALK
                        c.wave = false
                        c.dir = if (Random.nextFloat() < 0.6f) c.dir else -c.dir
                        c.timer = 2f + Random.nextFloat() * 5f
                    }
                }
                State.WALK -> {
                    // bunnies only move while in the air
                    val speed = (if (c.bunny) 26f else 15f) * d
                    c.x += c.dir * speed * dt
                    c.timer -= dt
                    // half a body from the edge, so nobody hangs over it
                    val edge = (if (c.bunny) 9f else 17f) * d * SIZE
                    if (c.x < edge || c.x > r!!.width() - edge) {
                        c.x = c.x.coerceIn(edge, max(edge, r!!.width() - edge))
                        if (Random.nextFloat() < 0.65f && !jumpAcross(c, r)) c.dir = -c.dir
                        else if (c.state != State.JUMP) c.dir = -c.dir
                    } else if (c.timer <= 0f) {
                        c.state = State.IDLE
                        c.wave = !c.bunny && Random.nextFloat() < 0.35f
                        c.timer = 1.5f + Random.nextFloat() * 3.5f
                    }
                }
                State.JUMP -> {
                    c.t += dt / c.jumpTime
                    val to = c.target
                    val tr = to?.let { rect(it) }
                    if (to == null || tr == null) {
                        place(c)
                        continue
                    }
                    if (c.t >= 1f) {
                        c.on = to
                        c.x = c.targetX.coerceIn(4 * d, max(4 * d, tr.width() - 4 * d))
                        c.state = if (Random.nextFloat() < 0.4f) State.IDLE else State.WALK
                        c.timer = 1f + Random.nextFloat() * 3f
                    }
                }
            }
            // where it is on screen now
            if (c.state == State.JUMP) {
                val tr = rect(c.target!!) ?: continue
                val tx = tr.left + c.targetX
                val ty = tr.top
                val k = c.t.coerceIn(0f, 1f)
                val arc = 26 * d + max(0f, c.fromY - ty) * 0.6f
                c.px = c.fromX + (tx - c.fromX) * k
                c.py = c.fromY + (ty - c.fromY) * k - arc * 4f * k * (1f - k)
                if (tx != c.fromX) c.dir = if (tx > c.fromX) 1f else -1f
            } else {
                val rr = rect(c.on!!) ?: continue
                c.px = rr.left + c.x
                c.py = rr.top
            }
        }
    }

    /** At the end of a tile: hop to a neighbour if one is near enough. */
    private fun jumpAcross(c: Critter, from: RectF): Boolean {
        val fx = from.left + c.x
        val fy = from.top
        val reach = 150 * d
        val options = places.filter { it !== c.on }.mapNotNull { v ->
            val r = rect(v) ?: return@mapNotNull null
            val nx = if (c.dir > 0) r.left + 10 * d else r.right - 10 * d
            val ahead = if (c.dir > 0) nx > fx else nx < fx
            if (!ahead || abs(nx - fx) > reach || abs(r.top - fy) > 170 * d) null else Pair(v, nx - r.left)
        }
        if (options.isEmpty()) return false
        val (v, x) = options[Random.nextInt(options.size)]
        startJump(c, v, x, 0.55f + Random.nextFloat() * 0.2f)
        return true
    }

    // ---------- drawing ----------

    override fun onDraw(canvas: Canvas) {
        for (c in critters) {
            if (c.on == null && c.state != State.JUMP) continue
            val save = canvas.save()
            canvas.translate(c.px, c.py)
            canvas.scale(c.dir * d * SIZE, d * SIZE)
            val a = (c.alpha * 255).toInt()
            if (c.bunny) bunny(canvas, c, a) else bear(canvas, c, a)
            canvas.restoreToCount(save)
        }
    }

    private fun fill(color: Int, a: Int) {
        p.color = color
        p.alpha = (Color.alpha(color) * a / 255)
    }

    private object Color {
        fun alpha(c: Int) = (c ushr 24) and 0xFF
    }

    private fun ellipse(canvas: Canvas, cx: Float, cy: Float, rx: Float, ry: Float) {
        oval.set(cx - rx, cy - ry, cx + rx, cy + ry)
        canvas.drawOval(oval, p)
    }

    /**
     * A bear, drawn facing right with its feet at (0, 0): on all fours while it walks, with the
     * shoulder hump, short round ears and long muzzle of a real one; sitting up when it rests.
     */
    private fun bear(canvas: Canvas, c: Critter, a: Int) {
        val dark = darker(c.coat)
        if (c.state == State.IDLE) {
            // sitting up
            fill(dark, a)
            ellipse(canvas, -3f, -2f, 5f, 2.2f)
            ellipse(canvas, 7f, -2f, 5f, 2.2f)
            fill(c.coat, a)
            ellipse(canvas, 1f, -14f, 11f, 13f)
            fill(c.light, a)
            ellipse(canvas, 3f, -12f, 6.5f, 8f)
            // front paws, one waving now and then
            fill(dark, a)
            ellipse(canvas, 8f, -10f, 3.2f, 2.6f)
            canvas.save()
            canvas.rotate(if (c.wave) -140f + sin(c.phase * 9f) * 22f else 0f, 6f, -20f)
            ellipse(canvas, 8f, -14f, 2.8f, 5f)
            canvas.restore()
            // head
            fill(c.coat, a)
            ellipse(canvas, -1f, -36.5f, 3f, 3f)
            ellipse(canvas, 9f, -36.5f, 3f, 3f)
            fill(dark, a)
            ellipse(canvas, -1f, -36.5f, 1.5f, 1.5f)
            ellipse(canvas, 9f, -36.5f, 1.5f, 1.5f)
            fill(c.coat, a)
            ellipse(canvas, 4f, -30f, 8f, 7.4f)
            fill(c.light, a)
            ellipse(canvas, 9.5f, -27.5f, 4.6f, 3.4f)
            fill(0xFF22160F.toInt(), a)
            ellipse(canvas, 13.3f, -28.6f, 1.6f, 1.2f)
            eye(canvas, c, 8.4f, -32f, 1.1f)
            return
        }
        // walking on all fours, legs in a diagonal gait
        val g = sin(c.phase * 8f)
        val sway = sin(c.phase * 4f) * 0.8f
        fun leg(x: Float, phase: Float, far: Boolean) {
            val lift = max(0f, phase) * 2.2f
            val swing = phase * 1.6f
            fill(if (far) darker(dark) else dark, a)
            oval.set(x - 2.6f + swing, -11f, x + 2.6f + swing, -lift)
            canvas.drawRoundRect(oval, 2.4f, 2.4f, p)
        }
        leg(-9f, -g, true)
        leg(10f, g, true)
        canvas.save()
        canvas.translate(0f, sway * 0.3f)
        fill(c.coat, a)
        ellipse(canvas, 0f, -15f, 16f, 8.5f)
        ellipse(canvas, 6f, -19.5f, 8.5f, 6f)            // shoulder hump
        ellipse(canvas, -15f, -17f, 2f, 2f)              // tail
        canvas.restore()
        leg(-13f, g, false)
        leg(6f, -g, false)
        // head, a little low, the way bears carry it
        fill(c.coat, a)
        ellipse(canvas, 14f, -24f, 2.4f, 2.4f)
        ellipse(canvas, 19f, -24.4f, 2.2f, 2.2f)
        fill(dark, a)
        ellipse(canvas, 14f, -24f, 1.1f, 1.1f)
        fill(c.coat, a)
        ellipse(canvas, 17.5f, -18f, 7f, 6.2f)
        fill(c.light, a)
        ellipse(canvas, 23.5f, -16f, 4.4f, 3.2f)
        fill(0xFF22160F.toInt(), a)
        ellipse(canvas, 27.2f, -17.2f, 1.9f, 1.4f)
        eye(canvas, c, 20.2f, -20f, 1f)
    }

    /** A bunny that hops rather than walks, drawn facing right with its feet at (0, 0). */
    private fun bunny(canvas: Canvas, c: Critter, a: Int) {
        val walking = c.state == State.WALK
        val hop = if (walking) abs(sin(c.phase * 7f)) * 5f else 0f
        val twitch = if (!walking && (c.phase % 3f) < 0.3f) sin(c.phase * 40f) * 8f else 0f
        canvas.save()
        canvas.translate(0f, -hop)
        // feet
        fill(darker(c.coat), a)
        ellipse(canvas, 3f, -1.4f, 3.4f, 1.5f)
        ellipse(canvas, -4f, -1.4f, 3.4f, 1.5f)
        // body and fluffy tail
        fill(c.coat, a)
        ellipse(canvas, -1f, -8f, 8f, 6.8f)
        fill(0xFFFFFFFF.toInt(), a)
        ellipse(canvas, -8.4f, -9f, 2.8f, 2.8f)
        // ears
        for ((ex, tilt) in listOf(3.6f to -8f, 7.4f to 6f)) {
            canvas.save()
            canvas.rotate(tilt + twitch, ex, -20f)
            fill(c.coat, a)
            ellipse(canvas, ex, -26f, 2f, 6.4f)
            fill(c.light, a)
            ellipse(canvas, ex, -26f, 1f, 4.8f)
            canvas.restore()
        }
        // head
        fill(c.coat, a)
        ellipse(canvas, 6f, -16f, 5.6f, 5.2f)
        fill(0xFF2B2422.toInt(), a)
        val blink = (c.phase % 3.5f) < 0.12f
        if (blink) ellipse(canvas, 7.8f, -16.8f, 1f, 0.25f) else ellipse(canvas, 7.8f, -16.8f, 0.9f, 1f)
        fill(0xFFE88FA0.toInt(), a)
        ellipse(canvas, 11.2f, -15f, 0.9f, 0.7f)
        fill(0xFFF4A3B4.toInt(), a / 2)
        ellipse(canvas, 8.4f, -13.8f, 1.5f, 1f)
        canvas.restore()
    }

    /** A dark eye with a tiny highlight; it blinks every few seconds. */
    private fun eye(canvas: Canvas, c: Critter, x: Float, y: Float, r: Float) {
        fill(0xFF1E1714.toInt(), (c.alpha.coerceIn(0f, 1f) * 255).toInt())
        if ((c.phase % 3.7f) < 0.12f) {
            ellipse(canvas, x, y, r, r * 0.25f)
        } else {
            ellipse(canvas, x, y, r, r * 1.1f)
            fill(0xFFFFFFFF.toInt(), (c.alpha * 200).toInt())
            ellipse(canvas, x + r * 0.35f, y - r * 0.4f, r * 0.35f, r * 0.35f)
        }
    }

    private fun darker(c: Int): Int {
        val r = ((c shr 16) and 0xFF) * 4 / 5
        val g = ((c shr 8) and 0xFF) * 4 / 5
        val b = (c and 0xFF) * 4 / 5
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

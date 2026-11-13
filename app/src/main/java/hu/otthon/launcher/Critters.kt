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
 * tiles, chips and buttons and rest now and then. Bunnies hop from tile to tile and sometimes
 * stop for a carrot; bears step across small gaps and, to reach another row, set up a ladder,
 * climb it and pack it away. Purely for fun; switched on in Settings › Appearance.
 *
 * [platforms] lists the views they may stand on; it is asked again every couple of seconds.
 */
class CritterLayer(ctx: Context, private val platforms: () -> List<View>) : View(ctx) {

    private enum class State { WALK, IDLE, JUMP, LADDER }

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
        /** A "jump" that is really a few steps across a small gap: no arc, walking legs. */
        var walkOver = false
        // a ladder: from the spot it stands on to a spot on another row
        var lFrom: View? = null
        var lFromX = 0f
        var lStage = 0          // 0 setting it up, 1 climbing, 2 packing it away
        var lT = 0f
        var climbTime = 1f
        // a carrot break
        var eating = false
        var eatTotal = 1f
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
            if (c.on !== v || c.state == State.JUMP || c.state == State.LADDER) continue
            if (c.bunny) {
                c.eating = false
                startJump(c, v, c.x, 0.45f)
            } else {
                // a bear just waves at you
                c.state = State.IDLE
                c.wave = true
                c.timer = 1.6f
            }
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
        c.walkOver = false
    }

    private fun startLadder(c: Critter, to: View, toX: Float) {
        c.lFrom = c.on
        c.lFromX = c.x
        c.target = to
        c.targetX = toX
        c.lStage = 0
        c.lT = 0f
        c.state = State.LADDER
        c.wave = false
    }

    /** The two ends of a bear's ladder on screen, or null if a tile is gone. */
    private fun ladderEnds(c: Critter): FloatArray? {
        val a = c.lFrom?.let { rect(it) } ?: return null
        val b = c.target?.let { rect(it) } ?: return null
        return floatArrayOf(a.left + c.lFromX, a.top, b.left + c.targetX, b.top)
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
                        c.eating = false
                        c.dir = if (Random.nextFloat() < 0.6f) c.dir else -c.dir
                        c.timer = 2f + Random.nextFloat() * 5f
                    }
                }
                State.WALK -> {
                    // bunnies only move while in the air
                    val speed = (if (c.bunny) 26f else 17f) * d
                    c.x += c.dir * speed * dt
                    c.timer -= dt
                    // half a body from the edge, so nobody hangs over it
                    val edge = (if (c.bunny) 9f else 12f) * d * SIZE
                    if (c.x < edge || c.x > r!!.width() - edge) {
                        c.x = c.x.coerceIn(edge, max(edge, r!!.width() - edge))
                        val moved = Random.nextFloat() < 0.65f && (if (c.bunny) jumpAcross(c, r) else bearAcross(c, r))
                        if (!moved) c.dir = -c.dir
                    } else if (c.timer <= 0f) {
                        c.state = State.IDLE
                        c.wave = !c.bunny && Random.nextFloat() < 0.35f
                        c.timer = 1.5f + Random.nextFloat() * 3.5f
                        if (c.bunny && Random.nextFloat() < 0.4f) {
                            c.eating = true
                            c.timer = 4f + Random.nextFloat() * 3f
                            c.eatTotal = c.timer
                        }
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
                        c.walkOver = false
                    }
                }
                State.LADDER -> {
                    val ends = ladderEnds(c)
                    if (ends == null) {
                        place(c)
                        continue
                    }
                    when (c.lStage) {
                        0 -> {
                            c.lT += dt / 0.9f
                            if (c.lT >= 1f) {
                                c.lStage = 1
                                c.lT = 0f
                                val len = Math.hypot((ends[2] - ends[0]).toDouble(), (ends[3] - ends[1]).toDouble()).toFloat()
                                c.climbTime = max(0.8f, len / (26f * d))
                            }
                        }
                        1 -> {
                            c.lT += dt / c.climbTime
                            if (c.lT >= 1f) {
                                c.on = c.target
                                c.x = c.targetX
                                c.lStage = 2
                                c.lT = 0f
                            }
                        }
                        else -> {
                            c.lT += dt / 0.7f
                            if (c.lT >= 1f) {
                                c.state = State.IDLE
                                c.timer = 0.8f + Random.nextFloat() * 1.5f
                                c.lFrom = null
                            }
                        }
                    }
                }
            }
            // where it is on screen now
            if (c.state == State.LADDER) {
                val e = ladderEnds(c) ?: continue
                val k = if (c.lStage == 1) c.lT.coerceIn(0f, 1f) else if (c.lStage == 0) 0f else 1f
                c.px = e[0] + (e[2] - e[0]) * k
                c.py = e[1] + (e[3] - e[1]) * k
            } else if (c.state == State.JUMP) {
                val tr = rect(c.target!!) ?: continue
                val tx = tr.left + c.targetX
                val ty = tr.top
                val k = c.t.coerceIn(0f, 1f)
                val arc = if (c.walkOver) 0f else 26 * d + max(0f, c.fromY - ty) * 0.6f
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

    /**
     * A bear at the end of a tile: a neighbour on the same level close by is reached in a few
     * steps; another row is reached by ladder, if there is a spot on it more or less straight
     * above or below.
     */
    private fun bearAcross(c: Critter, from: RectF): Boolean {
        val fx = from.left + c.x
        val fy = from.top
        val margin = 14 * d * SIZE
        val steps = ArrayList<Pair<View, Float>>()
        val ladders = ArrayList<Pair<View, Float>>()
        for (v in places) {
            if (v === c.on) continue
            val r = rect(v) ?: continue
            if (r.width() < 2 * margin) continue
            if (abs(r.top - fy) < 10 * d) {
                val nx = if (c.dir > 0) r.left + margin else r.right - margin
                val ahead = if (c.dir > 0) nx > fx else nx < fx
                if (ahead && abs(nx - fx) < 60 * d) steps.add(Pair(v, nx - r.left))
            } else if (abs(r.top - fy) in (24 * d)..(230 * d)) {
                val tx = fx.coerceIn(r.left + margin, r.right - margin)
                if (abs(tx - fx) < 50 * d) ladders.add(Pair(v, tx - r.left))
            }
        }
        if (steps.isNotEmpty() && (ladders.isEmpty() || Random.nextFloat() < 0.6f)) {
            val (v, x) = steps[Random.nextInt(steps.size)]
            val r = rect(v)!!
            startJump(c, v, x, max(0.4f, abs(r.left + x - fx) / (17f * d)))
            c.walkOver = true
            return true
        }
        if (ladders.isNotEmpty()) {
            val (v, x) = ladders[Random.nextInt(ladders.size)]
            startLadder(c, v, x)
            return true
        }
        return false
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

    private val wood = 0xFFB07A45.toInt()
    private val woodDark = 0xFF8A5A2E.toInt()

    /** The part of a bear's ladder that is out: growing while set up, shrinking while packed. */
    private fun drawLadder(canvas: Canvas, c: Critter) {
        val e = ladderEnds(c) ?: return
        val k = c.lT.coerceIn(0f, 1f)
        val (s0, s1) = when (c.lStage) {
            0 -> 0f to (1f - (1f - k) * (1f - k))
            1 -> 0f to 1f
            else -> k to 1f
        }
        val x0 = e[0] + (e[2] - e[0]) * s0
        val y0 = e[1] + (e[3] - e[1]) * s0
        val x1 = e[0] + (e[2] - e[0]) * s1
        val y1 = e[1] + (e[3] - e[1]) * s1
        val len = Math.hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
        if (len < 1f) return
        val nx = -(y1 - y0) / len * 4f * d
        val ny = (x1 - x0) / len * 4f * d
        val a = (c.alpha * 255).toInt()
        p.strokeCap = Paint.Cap.ROUND
        p.style = Paint.Style.STROKE
        // rungs, then the two rails over them
        fill(woodDark, a)
        p.strokeWidth = 1.4f * d
        var t = 5f * d
        while (t < len) {
            val f = t / len
            val cx = x0 + (x1 - x0) * f
            val cy = y0 + (y1 - y0) * f
            canvas.drawLine(cx - nx, cy - ny, cx + nx, cy + ny, p)
            t += 6f * d
        }
        fill(wood, a)
        p.strokeWidth = 1.8f * d
        canvas.drawLine(x0 - nx, y0 - ny, x1 - nx, y1 - ny, p)
        canvas.drawLine(x0 + nx, y0 + ny, x1 + nx, y1 + ny, p)
        p.style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        for (c in critters) if (!c.bunny && c.state == State.LADDER) drawLadder(canvas, c)
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
     * A chubby cartoon bear, drawn facing right with its feet at (0, 0): big round head, round
     * belly, short arms; it waddles on two legs and waves now and then while it rests.
     */
    private fun bear(canvas: Canvas, c: Critter, a: Int) {
        val dark = darker(c.coat)
        val climbing = c.state == State.LADDER && c.lStage == 1
        val walking = c.state == State.WALK || (c.state == State.JUMP && c.walkOver) || climbing
        val step = if (walking) sin(c.phase * (if (climbing) 10f else 8f)) else 0f
        val bob = if (walking) abs(step) * 1.2f else sin(c.phase * 2f) * 0.3f
        canvas.save()
        canvas.rotate(step * 5f, 0f, 0f)
        // feet, stepping in turn
        fill(dark, a)
        ellipse(canvas, -4.5f, -2f - max(0f, step) * 2.2f, 4.2f, 2.2f)
        ellipse(canvas, 5f, -2f - max(0f, -step) * 2.2f, 4.2f, 2.2f)
        canvas.translate(0f, -bob)
        // far arm, body, belly, tail
        fill(dark, a)
        canvas.save()
        canvas.rotate(-step * 25f, -6f, -17f)
        ellipse(canvas, -7f, -13f, 2.8f, 4.8f)
        canvas.restore()
        fill(c.coat, a)
        ellipse(canvas, 0f, -10f, 11.5f, 8.5f)
        ellipse(canvas, 0f, -15f, 9.5f, 8f)
        ellipse(canvas, -11f, -10f, 2.4f, 2.4f)
        fill(c.light, a)
        ellipse(canvas, 2.5f, -11f, 6.8f, 6.6f)
        // near arm: swings while walking, waves while resting
        fill(c.coat, a)
        canvas.save()
        val arm = when {
            c.wave -> -150f + sin(c.phase * 9f) * 22f
            climbing -> -160f + step * 25f
            c.state == State.LADDER -> -115f          // handling the ladder
            walking -> step * 25f
            else -> 0f
        }
        canvas.rotate(arm, 6.5f, -18f)
        ellipse(canvas, 7.5f, -13.5f, 3f, 5.2f)
        fill(dark, a)
        ellipse(canvas, 7.8f, -9.5f, 2.4f, 1.8f)
        canvas.restore()
        // head
        fill(c.coat, a)
        ellipse(canvas, -4.5f, -35.5f, 3.6f, 3.6f)
        ellipse(canvas, 8.5f, -36.5f, 3.6f, 3.6f)
        fill(c.light, a)
        ellipse(canvas, -4.5f, -35.5f, 1.9f, 1.9f)
        ellipse(canvas, 8.5f, -36.5f, 1.9f, 1.9f)
        fill(c.coat, a)
        ellipse(canvas, 2f, -27.5f, 10f, 9.4f)
        fill(c.light, a)
        ellipse(canvas, 7.6f, -24.2f, 4.8f, 3.6f)
        fill(0xFF22160F.toInt(), a)
        ellipse(canvas, 10.2f, -25.8f, 2f, 1.5f)
        // a small smile under the nose
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.7f
        p.strokeCap = Paint.Cap.ROUND
        oval.set(7.4f, -24.6f, 10.4f, -21.8f)
        canvas.drawArc(oval, 20f, 120f, false, p)
        p.style = Paint.Style.FILL
        eye(canvas, c, 4.2f, -29.4f, 1.35f)
        eye(canvas, c, 9.2f, -29.8f, 1.2f)
        fill(0xFFF0909A.toInt(), a / 2)
        ellipse(canvas, 3.6f, -24.5f, 2f, 1.2f)
        canvas.restore()
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
        if (c.eating && c.state == State.IDLE) carrot(canvas, c, a)
        canvas.restore()
    }

    /** A carrot held up to the mouth, a little shorter with every bite. */
    private fun carrot(canvas: Canvas, c: Critter, a: Int) {
        val left = (c.timer / c.eatTotal).coerceIn(0f, 1f)
        val chew = sin(c.phase * 14f) * 0.4f
        // leaves at the bottom end, the eaten tip at the mouth
        val bx = 13.5f
        val by = -4.5f
        val tx = bx + (10.8f - bx) * (0.25f + 0.75f * left)
        val ty = by + (-13.2f - by) * (0.25f + 0.75f * left)
        fill(0xFF5DA03A.toInt(), a)
        ellipse(canvas, bx + 1.2f, by + 1.6f, 1f, 2.2f)
        ellipse(canvas, bx - 0.6f, by + 1.8f, 0.9f, 2f)
        val path = android.graphics.Path()
        path.moveTo(bx - 1.9f, by)
        path.lineTo(bx + 1.9f, by)
        path.lineTo(tx + 0.5f, ty + chew)
        path.lineTo(tx - 0.5f, ty + chew)
        path.close()
        fill(0xFFF08A24.toInt(), a)
        canvas.drawPath(path, p)
        // the paw holding it
        fill(darker(c.coat), a)
        ellipse(canvas, 11.6f, -7.6f, 1.8f, 1.5f)
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

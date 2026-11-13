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

    private inner class Critter(val bunny: Boolean, val coat: Int, val light: Int, val deer: Boolean = false) {
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

    /** Christmas: reindeer instead of bears and bunnies, and now and then Santa flies over. */
    private var christmas = false

    /**
     * 0: none, 1: a few, 2: a whole family. At [xmas] they are reindeer, at least a few of them,
     * and Santa's sleigh comes by every minute or two.
     */
    fun setCount(level: Int, xmas: Boolean = false) {
        val lvl = if (xmas) max(1, level) else level
        val want = when (lvl) { 1 -> 3; 2 -> if (xmas) 6 else 7; else -> 0 }
        if (want == critters.size && xmas == christmas) return
        christmas = xmas
        critters.clear()
        sleighT = -1f
        sleighWait = 8f
        sparkles.clear()
        if (xmas) {
            val coats = listOf(
                0xFFA0703F.toInt() to 0xFFEBD8BA.toInt(),
                0xFF87592F.toInt() to 0xFFE2C9A2.toInt(),
                0xFFB88A5A.toInt() to 0xFFF2E3CB.toInt()
            )
            for (i in 0 until want) {
                val (coat, light) = coats[i % coats.size]
                critters.add(Critter(false, coat, light, deer = true))
            }
            visibility = if (paused || critters.isEmpty()) INVISIBLE else VISIBLE
            kick()
            return
        }
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
            if (c.bunny || c.deer) {
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
        if (christmas) updateSleigh(dt)
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
                    val speed = (if (c.bunny) 26f else if (c.deer) 21f else 17f) * d
                    c.x += c.dir * speed * dt
                    c.timer -= dt
                    // half a body from the edge, so nobody hangs over it
                    val edge = (if (c.bunny) 9f else if (c.deer) 13f else 12f) * d * SIZE
                    if (c.x < edge || c.x > r!!.width() - edge) {
                        c.x = c.x.coerceIn(edge, max(edge, r!!.width() - edge))
                        val moved = Random.nextFloat() < 0.65f && (if (c.bunny || c.deer) jumpAcross(c, r) else bearAcross(c, r))
                        if (!moved) c.dir = -c.dir
                    } else if (c.timer <= 0f) {
                        c.state = State.IDLE
                        c.wave = !c.bunny && !c.deer && Random.nextFloat() < 0.35f
                        c.timer = 1.5f + Random.nextFloat() * 3.5f
                        // bunnies have a carrot, reindeer nibble at the frost on the tile
                        if ((c.bunny || c.deer) && Random.nextFloat() < 0.4f) {
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
            when {
                c.deer -> grazingDeer(canvas, c, a)
                c.bunny -> bunny(canvas, c, a)
                else -> bear(canvas, c, a)
            }
            canvas.restoreToCount(save)
        }
        if (christmas) drawSleigh(canvas)
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

    // ---------- Christmas: reindeer and Santa's sleigh ----------

    /** Where the sleigh is on its way across, 0..1; below 0 it is away. */
    private var sleighT = -1f
    /** Seconds until it comes again. */
    private var sleighWait = 8f
    private var sleighDir = 1f
    private var sleighY0 = 0f
    private var sleighTime = 11f
    private var sleighPhase = 0f
    private var sparkleAt = 0f
    private var sx = 0f
    private var sy = 0f
    private var sTilt = 0f
    /** Gold dust the sleigh leaves behind: x, y, age 0..1. */
    private val sparkles = ArrayList<FloatArray>()
    private val SLEIGH = 1.25f
    private val path = android.graphics.Path()

    private fun updateSleigh(dt: Float) {
        sleighPhase += dt
        for (sp in sparkles) sp[2] += dt / 1.6f
        sparkles.removeAll { it[2] >= 1f }
        if (width == 0) return
        if (sleighT < 0f) {
            sleighWait -= dt
            if (sleighWait <= 0f) {
                sleighT = 0f
                sleighDir = if (Random.nextBoolean()) 1f else -1f
                sleighY0 = height * (0.16f + Random.nextFloat() * 0.2f)
                sleighTime = 10f + Random.nextFloat() * 3f
            }
            return
        }
        sleighT += dt / sleighTime
        if (sleighT >= 1f) {
            sleighT = -1f
            sleighWait = 70f + Random.nextFloat() * 80f
            return
        }
        val s = d * SLEIGH
        val travel = width + 260f * s
        val t = sleighT
        sx = if (sleighDir > 0) -140f * s + t * travel else width + 140f * s - t * travel
        val wave = 16f * d
        sy = sleighY0 - t * 50f * d + sin(t * Math.PI.toFloat() * 3f) * wave
        // nose up a little while climbing, down while sinking
        val dy = -50f * d + kotlin.math.cos(t * Math.PI.toFloat() * 3f) * wave * Math.PI.toFloat() * 3f
        sTilt = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), travel.toDouble())).toFloat()
        sparkleAt -= dt
        if (sparkleAt <= 0f && sparkles.size < 70) {
            sparkleAt = 0.045f
            // from the back of the runners
            sparkles.add(floatArrayOf(sx - sleighDir * 22f * s + (Random.nextFloat() - 0.5f) * 6f * d, sy - 2f * s + (Random.nextFloat() - 0.5f) * 6f * d, 0f))
        }
    }

    private fun drawSleigh(canvas: Canvas) {
        for (sp in sparkles) {
            val k = sp[2]
            val a = ((1f - k) * 230).toInt()
            val r = (2.4f - 1.6f * k) * d
            fill(0xFFFFE08A.toInt(), a)
            val y = sp[1] + k * 14f * d
            canvas.drawCircle(sp[0], y, r * 0.55f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 0.7f * d
            canvas.drawLine(sp[0] - r, y, sp[0] + r, y, p)
            canvas.drawLine(sp[0], y - r, sp[0], y + r, p)
            p.style = Paint.Style.FILL
        }
        if (sleighT < 0f) return
        val a = (min(1f, min(sleighT, 1f - sleighT) * 12f) * 255).toInt()
        val save = canvas.save()
        canvas.translate(sx, sy)
        canvas.rotate(sTilt * sleighDir)
        canvas.scale(sleighDir * d * SLEIGH, d * SLEIGH)
        val ph = sleighPhase
        // two pairs of reindeer in front, the far one of each pair a little higher and darker
        val leads = floatArrayOf(66f, 36f)
        p.strokeCap = Paint.Cap.ROUND
        for ((j, x) in leads.withIndex()) {
            val gallop = sin(ph * 7f + j * 1.3f)
            // harness: from the front of the sleigh to the deer's chest
            p.style = Paint.Style.STROKE
            p.strokeWidth = 0.9f
            fill(0xFFE2B84A.toInt(), a)
            canvas.drawLine(19f, -14f, x + 8f, -18f + gallop, p)
            p.style = Paint.Style.FILL
            for (far in listOf(true, false)) {
                canvas.save()
                canvas.translate(x + (if (far) -5f else 0f), (if (far) -5f else 0f) + gallop * 1.5f)
                val coat = if (far) 0xFF7A4F2A.toInt() else 0xFFA0703F.toInt()
                val light = if (far) 0xFFCDB592.toInt() else 0xFFEBD8BA.toInt()
                reindeer(canvas, coat, light, a, ph + j + (if (far) 0.4f else 0f), gallop * 0.4f, 1f, 0f, true)
                canvas.restore()
            }
        }
        santa(canvas, a, ph)
        canvas.restoreToCount(save)
    }

    /** The sleigh with its load of presents and Santa holding the reins; runners at (0, 0). */
    private fun santa(canvas: Canvas, a: Int, ph: Float) {
        val gold = 0xFFE2B84A.toInt()
        val red = 0xFFC62828.toInt()
        val white = 0xFFF7F4EF.toInt()
        // presents piled up in the back
        fun box(l: Float, t: Float, r: Float, b: Float, color: Int, ribbon: Int) {
            fill(color, a)
            canvas.drawRect(l, t, r, b, p)
            fill(ribbon, a)
            val mx = (l + r) / 2
            canvas.drawRect(mx - 0.9f, t, mx + 0.9f, b, p)
            canvas.drawRect(l, t + (b - t) * 0.4f, r, t + (b - t) * 0.4f + 1.6f, p)
            ellipse(canvas, mx - 1.6f, t - 0.6f, 1.6f, 1f)
            ellipse(canvas, mx + 1.6f, t - 0.6f, 1.6f, 1f)
        }
        box(-23f, -33f, -14f, -18f, 0xFF2E7D32.toInt(), red)
        box(-15f, -30f, -7f, -18f, 0xFF1E5AA8.toInt(), gold)
        box(-21f, -40f, -14.5f, -33.5f, 0xFFF2C230.toInt(), red)
        box(-13.5f, -36f, -8.5f, -30f, 0xFFD84343.toInt(), white)
        // Santa, sitting behind the front of the sleigh
        val waving = sleighT in 0.38f..0.62f
        fill(0xFF9E1F1F.toInt(), a)
        canvas.save()
        val farArm = if (waving) 160f + sin(ph * 9f) * 20f else -40f
        canvas.rotate(farArm, -5f, -24f)
        ellipse(canvas, -5f, -18.5f, 2.6f, 5.5f)
        fill(white, a)
        ellipse(canvas, -5f, -13.5f, 2.4f, 1.3f)
        fill(0xFF2B2B2B.toInt(), a)
        ellipse(canvas, -5f, -11.8f, 1.8f, 1.6f)
        canvas.restore()
        fill(red, a)
        ellipse(canvas, -1f, -20f, 7.5f, 8.5f)
        fill(white, a)
        canvas.drawRect(-1.3f, -28f, 0.6f, -13f, p)
        // head: face, beard, hat
        fill(0xFFF2C6A0.toInt(), a)
        ellipse(canvas, 1f, -32f, 4.3f, 4.3f)
        fill(white, a)
        ellipse(canvas, 2.2f, -28.4f, 4.6f, 4.8f)
        ellipse(canvas, 4f, -30.6f, 2.6f, 1.3f)
        fill(0xFFE88A86.toInt(), a)
        ellipse(canvas, 5.2f, -31.8f, 1.2f, 1f)
        fill(0xFF1E1714.toInt(), a)
        ellipse(canvas, 3.2f, -33.4f, 0.65f, 0.75f)
        fill(red, a)
        path.reset()
        path.moveTo(-3.6f, -34.6f)
        path.lineTo(5f, -35f)
        path.quadTo(1f, -42f, -6f, -41.5f)
        path.close()
        canvas.drawPath(path, p)
        fill(white, a)
        ellipse(canvas, 0.6f, -35f, 5.4f, 1.6f)
        ellipse(canvas, -6.6f, -41.2f, 1.8f, 1.8f)
        // near arm forward, holding the reins
        fill(red, a)
        canvas.save()
        canvas.rotate(-62f + sin(ph * 3f) * 4f, 1f, -24f)
        ellipse(canvas, 1f, -18.5f, 2.6f, 5.5f)
        fill(white, a)
        ellipse(canvas, 1f, -13.5f, 2.4f, 1.3f)
        fill(0xFF2B2B2B.toInt(), a)
        ellipse(canvas, 1f, -11.8f, 1.8f, 1.6f)
        canvas.restore()
        // the sleigh: red body with a curled front and gold trim
        path.reset()
        path.moveTo(-24f, -5f)
        path.lineTo(-24f, -22f)
        path.quadTo(-23f, -26f, -18f, -24.5f)
        path.lineTo(-15f, -15f)
        path.lineTo(8f, -15f)
        path.quadTo(15f, -15f, 17f, -21f)
        path.quadTo(19f, -26f, 23f, -23f)
        path.quadTo(24f, -9f, 12f, -5f)
        path.close()
        fill(red, a)
        canvas.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f
        p.strokeCap = Paint.Cap.ROUND
        fill(gold, a)
        canvas.drawPath(path, p)
        // runners
        path.reset()
        path.moveTo(-26f, 0f)
        path.lineTo(16f, 0f)
        path.quadTo(24f, 0f, 23f, -5f)
        path.quadTo(22f, -8f, 19.5f, -6f)
        canvas.drawPath(path, p)
        canvas.drawLine(-16f, 0f, -16f, -5f, p)
        canvas.drawLine(6f, 0f, 6f, -5f, p)
        p.style = Paint.Style.FILL
        // a bell on the front
        fill(gold, a)
        ellipse(canvas, 18f, -13f + sin(ph * 10f) * 0.6f, 1.6f, 1.6f)
    }

    /** A reindeer on a tile: walks, leaps across gaps and nibbles at the frost now and then. */
    private fun grazingDeer(canvas: Canvas, c: Critter, a: Int) {
        val walking = c.state == State.WALK || (c.state == State.JUMP && c.walkOver)
        val leaping = c.state == State.JUMP && !c.walkOver
        val swing = if (walking) sin(c.phase * 8f) else 0f
        val graze = if (c.eating && c.state == State.IDLE) {
            // lower the head over half a second, raise it at the end
            val down = min(c.eatTotal - c.timer, c.timer).coerceIn(0f, 0.5f) * 2f
            down
        } else 0f
        canvas.save()
        canvas.scale(0.92f, 0.92f)
        if (walking) canvas.translate(0f, -abs(swing) * 0.8f)
        reindeer(canvas, c.coat, c.light, a, c.phase, swing, if (leaping) 1f else 0f, graze, true)
        canvas.restore()
    }

    /**
     * A reindeer facing right with its hooves at (0, 0). [legs] swings the legs while walking,
     * [fly] stretches them out front and back for a leap or a flight, [head] lowers the head.
     */
    private fun reindeer(canvas: Canvas, coat: Int, light: Int, a: Int, phase: Float, legs: Float, fly: Float, head: Float, bell: Boolean) {
        val dark = darker(coat)
        val hoof = 0xFF3A2A1E.toInt()
        fun leg(hx: Float, angle: Float, color: Int) {
            canvas.save()
            canvas.rotate(angle, hx, -11f)
            fill(color, a)
            ellipse(canvas, hx, -6f, 1.5f, 5.6f)
            fill(hoof, a)
            ellipse(canvas, hx, -0.8f, 1.6f, 1f)
            canvas.restore()
        }
        // far legs, darker
        leg(-7.5f, fly * 55f + legs * 22f, dark)
        leg(6f, -fly * 60f - legs * 22f, dark)
        // tail
        fill(light, a)
        ellipse(canvas, -12f, -16.5f, 2f, 1.5f)
        // body and belly
        fill(coat, a)
        ellipse(canvas, -0.5f, -14.5f, 11.5f, 5.8f)
        fill(light, a)
        ellipse(canvas, 0.5f, -11.4f, 7f, 2.6f)
        // near legs
        leg(-6f, fly * 48f - legs * 22f, coat)
        leg(7.5f, -fly * 52f + legs * 22f, coat)
        // neck and head, lowered together while grazing
        canvas.save()
        canvas.rotate(head * 60f, 8f, -16f)
        fill(coat, a)
        canvas.save()
        canvas.rotate(28f, 10f, -21f)
        ellipse(canvas, 10f, -21f, 3.2f, 6.4f)
        canvas.restore()
        fill(light, a)
        ellipse(canvas, 10.5f, -17.6f, 2.8f, 3f)
        if (bell) {
            // a red collar with a little gold bell
            fill(0xFFC62828.toInt(), a)
            canvas.save()
            canvas.rotate(28f, 10f, -19.5f)
            ellipse(canvas, 10f, -19.5f, 3.6f, 1.1f)
            canvas.restore()
            fill(0xFFE8C04A.toInt(), a)
            ellipse(canvas, 12f, -17.4f + sin(phase * 9f) * 0.3f, 1.2f, 1.2f)
        }
        // antlers: two branched stems, the far one darker
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = 1.1f
        for (far in listOf(true, false)) {
            val o = if (far) -1.8f else 0f
            fill(if (far) 0xFFA88A5E.toInt() else 0xFFD3B482.toInt(), a)
            path.reset()
            path.moveTo(12f + o, -30f)
            path.quadTo(10f + o, -35f, 7f + o, -38.5f)
            path.moveTo(10.6f + o, -33.6f)
            path.lineTo(13.6f + o, -37f)
            path.moveTo(8.6f + o, -36.6f)
            path.lineTo(9f + o, -40.6f)
            path.moveTo(7f + o, -38.5f)
            path.lineTo(4.6f + o, -39.6f)
            canvas.drawPath(path, p)
        }
        p.style = Paint.Style.FILL
        // ear, head, muzzle, nose
        fill(dark, a)
        canvas.save()
        canvas.rotate(-55f, 9.6f, -29f)
        ellipse(canvas, 9.6f, -29f, 1.2f, 2.6f)
        canvas.restore()
        fill(coat, a)
        ellipse(canvas, 13f, -27.6f, 4f, 3.2f)
        fill(light, a)
        ellipse(canvas, 16.4f, -26.2f, 2.8f, 2.1f)
        fill(0xFF2A1D16.toInt(), a)
        ellipse(canvas, 18.8f, -26.6f, 1.1f, 0.9f)
        eyeAt(canvas, 13.4f, -28.6f, 0.9f, a, phase)
        canvas.restore()
    }

    /** A dark eye with a tiny highlight that blinks every few seconds. */
    private fun eyeAt(canvas: Canvas, x: Float, y: Float, r: Float, a: Int, phase: Float) {
        fill(0xFF1E1714.toInt(), a)
        if ((phase % 3.7f) < 0.12f) {
            ellipse(canvas, x, y, r, r * 0.25f)
        } else {
            ellipse(canvas, x, y, r, r * 1.1f)
            fill(0xFFFFFFFF.toInt(), a * 200 / 255)
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

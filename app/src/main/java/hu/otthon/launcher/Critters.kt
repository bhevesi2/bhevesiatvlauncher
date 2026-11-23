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
 *
 * They are drawn on a see-through surface of their own above the home screen, so their every step
 * redraws only them: the home screen under them (the photo, the tiles) is not drawn again, which a
 * weak TV (the Sony) feels as smooth scrolling.
 */
class CritterLayer(ctx: Context, private val platforms: () -> List<View>) :
    android.view.SurfaceView(ctx), android.view.SurfaceHolder.Callback {

    /** AWAY: off screen fetching something. FLOAT: flying about after a magic cookie. */
    internal enum class State { WALK, IDLE, JUMP, LADDER, AWAY, FLOAT }

    internal inner class Critter(val bunny: Boolean, val coat: Int, val light: Int, val deer: Boolean = false) {
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
        // Christmas: a little tree (0) or a present (1) on its back, to be set down somewhere
        var carry = -1
        var carryColor = 0
        var placing = false
        /** A jump out of the screen, to (offX, offY), to fetch a tree or a present. */
        var exiting = false
        var offX = 0f
        var offY = 0f
        // a magic cookie, then a flight trailing glitter
        var cookie = false
        var floatT = 0f
        var floatTime = 12f
        var floatFrom = 0f
        var floatAlt = 0f
        var trail = 0f
        var trailAt = 0f
    }

    /** A tree or a present a reindeer set down on top of a tile. */
    private class Decor(val on: View, val x: Float, val kind: Int, val color: Int) {
        var age = 0f
        var alpha = 0f
        var dying = false
    }

    internal val d = resources.displayMetrics.density
    /** Drawing units to dp: the bears are about 30 dp tall standing, the bunnies about 34 with ears. */
    private val SIZE = 1.05f
    private val critters = ArrayList<Critter>()
    private var places: List<View> = emptyList()
    private var placesAt = 0L
    private val rects = HashMap<View, RectF>()
    private val myLoc = IntArray(2)
    private val loc = IntArray(2)
    internal val p = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val oval = RectF()
    private var last = 0L
    private var running = false

    /** True while the home screen is slid away or covered: nothing moves, nothing is drawn. */
    var paused = false
        set(v) {
            field = v
            visibility = if (v || critters.isEmpty()) INVISIBLE else VISIBLE
            if (!v) kick()
        }

    /** True while the surface exists (it does only while the layer is visible). */
    private var ready = false

    init {
        isFocusable = false
        isClickable = false
        setZOrderOnTop(true)
        holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
        visibility = INVISIBLE
    }

    /**
     * The surface's own size: never above full HD, a quarter of it on a weak TV. The little
     * visitors are drawn smaller into it and the TV scales the picture up; their buffers are
     * graphics memory the Philips did not have to spare.
     */
    private var scale = 1f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val bw = minOf(w, if (LowRam.lite) 960 else 1920)
        scale = bw.toFloat() / w
        holder.setFixedSize(bw, maxOf(1, (h * scale).toInt()))
    }

    override fun surfaceCreated(holder: android.view.SurfaceHolder) {
        ready = true
        kick()
    }

    override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!running) render()
    }

    override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
        ready = false
    }

    /** One picture of them on their own surface: cleared, then everything drawn afresh. */
    private fun render() {
        if (!ready) return
        val surface = holder.surface
        if (!surface.isValid) return
        val canvas = try {
            surface.lockHardwareCanvas()
        } catch (e: Exception) {
            try { holder.lockCanvas() } catch (e2: Exception) { null }
        } ?: return
        try {
            canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
            if (scale != 1f) canvas.scale(scale, scale)
            drawScene(canvas)
        } finally {
            try { surface.unlockCanvasAndPost(canvas) } catch (e: Exception) { }
        }
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
        decor.clear()
        deliverWait = 25f + Random.nextFloat() * 20f
        cookieWait = 90f + Random.nextFloat() * 60f
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
            if (c.on !== v || c.state == State.JUMP || c.state == State.LADDER || c.cookie) continue
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
        if (running || paused || !ready || critters.isEmpty() || !isAttachedToWindow) return
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
            if (paused || !ready || critters.isEmpty() || !isAttachedToWindow) {
                running = false
                return
            }
            val now = SystemClock.uptimeMillis()
            val dt = min(0.1f, (now - last) / 1000f)
            last = now
            update(dt)
            render()
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
    internal fun ladderEnds(c: Critter): FloatArray? {
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
        if (christmas) {
            updateSleigh(dt)
            updateChristmas(dt)
        }
        for (c in critters) {
            c.phase += dt
            c.alpha = min(1f, c.alpha + dt * 2f)
            if (c.trail > 0f) {
                c.trail -= dt
                trailSparkle(c, dt)
            }
            if (c.state == State.AWAY) {
                c.timer -= dt
                if (c.timer <= 0f) comeBack(c)
                continue
            }
            if (c.state == State.FLOAT) {
                updateFloat(c, dt)
                continue
            }
            val on = c.on
            val r = on?.let { rect(it) }
            if (c.state != State.JUMP && (on == null || r == null)) {
                place(c)
                continue
            }
            when (c.state) {
                State.IDLE -> {
                    c.timer -= dt
                    // setting a tree or a present down, half way through the bow
                    if (c.placing && c.carry >= 0 && c.timer < c.eatTotal * 0.5f) putDown(c, r!!)
                    if (c.timer <= 0f && c.cookie) {
                        startFloat(c)
                        continue
                    }
                    if (c.timer <= 0f) {
                        c.placing = false
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
                    } else if (c.timer <= 0f && c.carry >= 0) {
                        // found a good spot: bow down and leave the tree or present there
                        c.state = State.IDLE
                        c.eating = true
                        c.placing = true
                        c.timer = 1.5f
                        c.eatTotal = 1.5f
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
                    if (c.exiting) {
                        if (c.t >= 1f) {
                            // gone to fetch something; back in a few seconds
                            c.exiting = false
                            c.state = State.AWAY
                            c.on = null
                            c.alpha = 0f
                            c.timer = 2.5f + Random.nextFloat() * 3f
                            continue
                        }
                    } else {
                    val to = c.target
                    val tr = to?.let { rect(it) }
                    if (to == null || tr == null) {
                        place(c)
                        continue
                    }
                    if (c.t >= 1f) {
                        c.on = to
                        c.x = c.targetX.coerceIn(4 * d, max(4 * d, tr.width() - 4 * d))
                        c.state = if (Random.nextFloat() < 0.4f && c.carry < 0) State.IDLE else State.WALK
                        c.timer = 1f + Random.nextFloat() * 3f
                        c.walkOver = false
                    }
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
                // handled above, before the platform check
                State.AWAY, State.FLOAT -> {}
            }
            // where it is on screen now
            if (c.state == State.LADDER) {
                val e = ladderEnds(c) ?: continue
                val k = if (c.lStage == 1) c.lT.coerceIn(0f, 1f) else if (c.lStage == 0) 0f else 1f
                c.px = e[0] + (e[2] - e[0]) * k
                c.py = e[1] + (e[3] - e[1]) * k
            } else if (c.state == State.JUMP) {
                val tx: Float
                val ty: Float
                if (c.exiting) {
                    tx = c.offX
                    ty = c.offY
                } else {
                    val tr = rect(c.target!!) ?: continue
                    tx = tr.left + c.targetX
                    ty = tr.top
                }
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

    internal val wood = 0xFFB07A45.toInt()
    internal val woodDark = 0xFF8A5A2E.toInt()

    private fun drawScene(canvas: Canvas) {
        for (c in critters) if (!c.bunny && c.state == State.LADDER) drawLadder(canvas, c)
        if (decor.isNotEmpty()) drawDecor(canvas)
        for (c in critters) {
            if (c.state == State.AWAY) continue
            if (c.on == null && c.state != State.JUMP && c.state != State.FLOAT) continue
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

    internal fun fill(color: Int, a: Int) {
        p.color = color
        p.alpha = (Color.alpha(color) * a / 255)
    }

    private object Color {
        fun alpha(c: Int) = (c ushr 24) and 0xFF
    }

    internal fun ellipse(canvas: Canvas, cx: Float, cy: Float, rx: Float, ry: Float) {
        oval.set(cx - rx, cy - ry, cx + rx, cy + ry)
        canvas.drawOval(oval, p)
    }

    // ---------- Christmas: reindeer and Santa's sleigh ----------

    /** Where the sleigh is on its way across, 0..1; below 0 it is away. */
    internal var sleighT = -1f
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
    /** Gold dust the sleigh leaves behind, glitter behind a flying reindeer: x, y, age 0..1, colour. */
    private val sparkles = ArrayList<FloatArray>()
    internal val GLITTER = intArrayOf(
        0xFFFFE08A.toInt(), 0xFFFF9EC7.toInt(), 0xFF9EE7FF.toInt(), 0xFFC9A6FF.toInt(), 0xFFA6FFC9.toInt(), 0xFFFFFFFF.toInt()
    )
    private val SLEIGH = 1.25f
    internal val path = android.graphics.Path()

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
        if (sparkleAt <= 0f && sparkles.size < 120) {
            sparkleAt = 0.045f
            // from the back of the runners
            sparkles.add(floatArrayOf(sx - sleighDir * 22f * s + (Random.nextFloat() - 0.5f) * 6f * d, sy - 2f * s + (Random.nextFloat() - 0.5f) * 6f * d, 0f, 0f))
        }
    }

    private fun drawSleigh(canvas: Canvas) {
        for (sp in sparkles) {
            val k = sp[2]
            val a = ((1f - k) * 230).toInt()
            val r = (2.4f - 1.6f * k) * d
            fill(GLITTER[sp[3].toInt().coerceIn(0, GLITTER.size - 1)], a)
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

    // ---------- Christmas: presents, little trees and magic cookies ----------

    private val decor = ArrayList<Decor>()
    /** At most this many trees and presents stay; the oldest fades away when one more arrives. */
    private val MAX_DECOR = 4
    private var deliverWait = 30f
    private var cookieWait = 120f
    private val WRAP = intArrayOf(0xFFD84343.toInt(), 0xFF2E7DD1.toInt(), 0xFFF2C230.toInt(), 0xFF8E5BD8.toInt(), 0xFF2E9E5B.toInt())

    private fun updateChristmas(dt: Float) {
        for (o in decor) {
            o.age += dt
            o.alpha = if (o.dying) o.alpha - dt / 1.5f else min(1f, o.alpha + dt * 3f)
        }
        decor.removeAll { (it.dying && it.alpha <= 0f) || !it.on.isAttachedToWindow }
        if (width == 0 || critters.isEmpty()) return
        val idle = critters.filter {
            (it.state == State.WALK || it.state == State.IDLE) && it.on != null && it.carry < 0 && !it.cookie && !it.placing
        }
        // now and then one of them goes to fetch a little tree or a present
        deliverWait -= dt
        if (deliverWait <= 0f) {
            val busy = critters.any { it.state == State.AWAY || it.exiting || it.carry >= 0 }
            val c = if (busy) null else idle.randomOrNull()
            if (c == null) {
                deliverWait = 5f
            } else {
                deliverWait = 60f + Random.nextFloat() * 60f
                fetch(c)
            }
        }
        // and once in a long while one finds a magic cookie
        cookieWait -= dt
        if (cookieWait <= 0f) {
            val flying = critters.any { it.state == State.FLOAT || it.cookie }
            val c = if (flying) null else idle.filter { it.state == State.IDLE || it.state == State.WALK }.randomOrNull()
            if (c == null) {
                cookieWait = 6f
            } else {
                cookieWait = 150f + Random.nextFloat() * 150f
                c.cookie = true
                c.state = State.IDLE
                c.wave = false
                c.eating = true
                c.timer = 3.4f
                c.eatTotal = 3.4f
            }
        }
    }

    /** Leap out over the nearer side edge of the screen. */
    private fun fetch(c: Critter) {
        val left = c.px < width / 2f
        c.fromX = c.px
        c.fromY = c.py
        c.offX = if (left) -40f * d else width + 40f * d
        c.offY = c.py - 70f * d
        c.dir = if (left) -1f else 1f
        c.t = 0f
        c.jumpTime = max(0.7f, abs(c.offX - c.px) / (260f * d))
        c.state = State.JUMP
        c.exiting = true
        c.walkOver = false
        c.eating = false
        c.wave = false
    }

    /** Back with a tree or a present, leaping in from the side onto a tile. */
    private fun comeBack(c: Critter) {
        val list = places.filter { rect(it) != null }
        if (list.isEmpty()) {
            c.timer = 2f
            return
        }
        val v = list[Random.nextInt(list.size)]
        val r = rect(v)!!
        val fromLeft = r.centerX() < width / 2f
        c.carry = if (Random.nextFloat() < 0.5f) 0 else 1
        c.carryColor = WRAP[Random.nextInt(WRAP.size)]
        c.fromX = if (fromLeft) -30f * d else width + 30f * d
        c.fromY = r.top - 90f * d
        c.px = c.fromX
        c.py = c.fromY
        c.target = v
        c.targetX = 14f * d + Random.nextFloat() * max(1f, r.width() - 28f * d)
        c.t = 0f
        c.jumpTime = 1.2f + abs(r.left + c.targetX - c.fromX) / (900f * d)
        c.state = State.JUMP
        c.walkOver = false
        c.exiting = false
        c.dir = if (fromLeft) 1f else -1f
        c.alpha = 1f
    }

    private fun putDown(c: Critter, r: RectF) {
        val on = c.on ?: return
        val x = (c.x + c.dir * 17f * d * SIZE).coerceIn(9f * d, max(9f * d, r.width() - 9f * d))
        decor.add(Decor(on, x, c.carry, c.carryColor))
        c.carry = -1
        c.placing = false
        // a little puff of gold where it landed
        for (i in 0 until 8) {
            sparkles.add(floatArrayOf(r.left + x + (Random.nextFloat() - 0.5f) * 22f * d, r.top - Random.nextFloat() * 22f * d, Random.nextFloat() * 0.3f, 0f))
        }
        val alive = decor.filter { !it.dying }
        if (alive.size > MAX_DECOR) alive.first().dying = true
    }

    private fun startFloat(c: Critter) {
        c.cookie = false
        c.eating = false
        c.state = State.FLOAT
        c.on = null
        c.floatT = 0f
        c.floatTime = 11f + Random.nextFloat() * 5f
        c.floatFrom = c.py
        c.floatAlt = height * (0.16f + Random.nextFloat() * 0.2f)
        c.trail = c.floatTime + 1.8f
        for (i in 0 until 14) {
            sparkles.add(floatArrayOf(c.px + (Random.nextFloat() - 0.5f) * 34f * d, c.py - Random.nextFloat() * 34f * d, Random.nextFloat() * 0.2f, (1 + Random.nextInt(GLITTER.size - 1)).toFloat()))
        }
    }

    private fun updateFloat(c: Critter, dt: Float) {
        c.floatT += dt
        val margin = 50f * d
        c.px += c.dir * 72f * d * dt
        if (c.px < margin) c.dir = 1f
        if (c.px > width - margin) c.dir = -1f
        // up into the air over two seconds, then bobbing gently along
        val k = min(1f, c.floatT / 2.4f)
        val rise = k * k * (3f - 2f * k)
        val cruise = c.floatAlt + sin(c.floatT * 1.3f) * 24f * d
        c.py = c.floatFrom + (cruise - c.floatFrom) * rise
        if (c.floatT >= c.floatTime) {
            // the magic wears off: glide down onto a tile, the nearest one ahead if possible
            val list = places.mapNotNull { v -> rect(v)?.let { v to it } }
            if (list.isEmpty()) {
                c.floatTime += 2f
                return
            }
            val (v, r) = list.minByOrNull { (_, r) -> abs(r.centerX() - (c.px + c.dir * 120f * d)) + abs(r.top - c.py) * 0.3f }!!
            startJump(c, v, (c.px - r.left).coerceIn(14f * d, max(14f * d, r.width() - 14f * d)), 1.5f)
        }
    }

    private fun trailSparkle(c: Critter, dt: Float) {
        if (c.state != State.FLOAT && c.state != State.JUMP) return
        c.trailAt -= dt
        if (c.trailAt > 0f || sparkles.size >= 160) return
        c.trailAt = 0.04f
        val bx = c.px - c.dir * 11f * d * SIZE
        val by = c.py - 13f * d * SIZE
        sparkles.add(floatArrayOf(bx + (Random.nextFloat() - 0.5f) * 8f * d, by + (Random.nextFloat() - 0.5f) * 10f * d, 0f, (1 + Random.nextInt(GLITTER.size - 1)).toFloat()))
    }

    private fun drawDecor(canvas: Canvas) {
        for (o in decor) {
            val r = rect(o.on) ?: continue
            val a = (o.alpha.coerceIn(0f, 1f) * 255).toInt()
            if (a <= 0) continue
            val save = canvas.save()
            canvas.translate(r.left + o.x, r.top)
            // pops up a little bigger, then settles
            val pop = if (o.age < 0.5f) 0.6f + 0.5f * sin(o.age / 0.5f * Math.PI.toFloat() * 0.75f) else 1f
            canvas.scale(d * SIZE * pop, d * SIZE * pop)
            drawItem(canvas, o.kind, o.color, a, o.age)
            canvas.restoreToCount(save)
        }
    }

}

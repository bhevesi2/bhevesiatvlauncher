package hu.otthon.launcher

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * "Répakaland": a small platform game hidden in the launcher. The hero is the bunny of the home
 * screen's little visitors; it hops through three levels to its burrow, throwing carrots at the
 * foxes, the wolves and the bears (the bears carry a rifle and climb up and down their ladders).
 * The bunny and the bears are drawn by the same code as the visitors on the home screen; the fox
 * and the wolf are drawn here in the same style.
 *
 * Opened from Settings › About: press OK on the version number seven times.
 * On the remote: ◀ ▶ hop, OK or ▲ jump (held: higher), ▼ throw a carrot, Back pauses (Back again
 * leaves). Carrots picked up are thrown later; a fox or a wolf goes with one carrot or a jump on
 * its head, a bear needs two.
 */
class BunnyGameActivity : Activity() {
    private lateinit var game: BunnyGameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        game = BunnyGameView(this) { finish() }
        setContentView(game)
        game.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        CrashLog.entered(this)
        game.running = true
    }

    override fun onPause() {
        super.onPause()
        CrashLog.left(this)
        game.running = false
        game.pause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = game.key(event) || super.dispatchKeyEvent(event)

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back belongs to the game: pause, then leave
    }
}

/** The levels, built from a few commands. y counts in tiles from the bottom; things stand on row y. */
object BunnyLevels {
    const val ROWS = 12

    class Level(val name: String, val cols: Int, val grid: Array<CharArray>, val theme: Int)

    class Builder(val cols: Int) {
        val grid = Array(ROWS) { CharArray(cols) { ' ' } }
        private fun set(x: Int, y: Int, c: Char) {
            if (x in 0 until cols && y in 0 until ROWS) grid[ROWS - 1 - y][x] = c
        }
        fun ground(from: Int, to: Int, h: Int = 2) { for (x in from..to) for (y in 0 until h) set(x, y, '#') }
        /** A log: stood on from above, passed through from below. */
        fun log(x: Int, y: Int, len: Int) {
            // a ladder reaching up to the log keeps its top rung there
            for (i in 0 until len) if (x + i in 0 until cols && y in 0 until ROWS && grid[ROWS - 1 - y][x + i] != 'H') set(x + i, y, '=')
        }
        fun rock(x: Int, y: Int, w: Int = 1, h: Int = 1) { for (i in 0 until w) for (j in 0 until h) set(x + i, y + j, '#') }
        /** A bear's ladder from row [from] up to row [to]; its top rung is stood on like a log. */
        fun ladder(x: Int, from: Int, to: Int) { for (y in from..to) set(x, y, 'H') }
        fun carrots(x: Int, y: Int, n: Int = 1) { for (i in 0 until n) set(x + i, y, 'c') }
        fun heart(x: Int, y: Int) = set(x, y, 'v')
        fun spikes(from: Int, to: Int, y: Int) { for (x in from..to) set(x, y, '^') }
        fun fox(x: Int, y: Int) = set(x, y, 'f')
        fun wolf(x: Int, y: Int) = set(x, y, 'w')
        fun bear(x: Int, y: Int) = set(x, y, 'b')
        fun start(x: Int, y: Int) = set(x, y, 'S')
        fun burrow(x: Int, y: Int) = set(x, y, 'C')
    }

    private fun level(name: String, cols: Int, theme: Int, b: Builder.() -> Unit): Level {
        val builder = Builder(cols)
        builder.b()
        return Level(name, cols, builder.grid, theme)
    }

    val all: List<Level> by lazy {
        listOf(
            level("Reggeli erdő", 130, 0) {
                ground(0, 30)
                start(2, 2)
                carrots(6, 2, 3)
                fox(13, 2)
                ladder(16, 2, 4)
                log(17, 4, 8)
                carrots(18, 5, 4)
                bear(22, 5)
                wolf(27, 2)
                ground(34, 60)
                carrots(36, 5, 3)
                rock(40, 2, 2, 2)
                fox(46, 2)
                ladder(50, 2, 4)
                log(51, 4, 7)
                bear(54, 5)
                heart(57, 5)
                wolf(58, 2)
                log(62, 4, 3)
                ground(66, 96)
                fox(71, 2)
                ladder(76, 2, 4)
                log(77, 4, 6)
                carrots(78, 5, 4)
                bear(80, 5)
                fox(85, 2)
                spikes(89, 91, 2)
                ground(99, 129)
                carrots(101, 2, 3)
                wolf(106, 2)
                bear(112, 2)
                carrots(116, 4, 3)
                burrow(124, 2)
            },
            level("Alkonyi rét", 140, 1) {
                ground(0, 16)
                start(2, 2)
                carrots(5, 2, 4)
                log(18, 3, 3)
                log(23, 5, 3)
                carrots(23, 6, 3)
                ground(28, 52)
                fox(32, 2)
                ladder(35, 2, 4)
                log(36, 4, 9)
                ladder(44, 5, 7)
                log(40, 7, 6)
                carrots(40, 8, 5)
                bear(39, 5)
                bear(42, 8)
                wolf(48, 2)
                spikes(53, 55, 1)
                ground(53, 55, 1)
                ground(56, 78)
                fox(60, 2)
                fox(66, 2)
                ladder(70, 2, 4)
                log(71, 4, 6)
                heart(75, 5)
                bear(73, 5)
                log(80, 4, 3)
                log(85, 6, 3)
                carrots(85, 7, 3)
                ground(90, 139)
                wolf(95, 2)
                wolf(101, 2)
                ladder(106, 2, 4)
                log(107, 4, 8)
                bear(110, 5)
                bear(118, 2)
                carrots(120, 2, 4)
                fox(126, 2)
                burrow(134, 2)
            },
            level("Holdfényes hegyek", 150, 2) {
                ground(0, 12)
                start(2, 2)
                wolf(9, 2)
                log(14, 4, 2)
                log(18, 6, 2)
                log(22, 8, 2)
                carrots(22, 9, 2)
                ground(26, 46)
                spikes(30, 33, 2)
                log(30, 4, 4)
                fox(37, 2)
                ladder(40, 2, 4)
                log(41, 4, 6)
                ladder(46, 5, 7)
                log(42, 7, 5)
                bear(43, 5)
                bear(44, 8)
                carrots(42, 8, 3)
                wolf(45, 2)
                log(49, 4, 2)
                log(54, 6, 2)
                log(59, 4, 2)
                carrots(54, 7, 2)
                ground(63, 100)
                fox(67, 2)
                wolf(73, 2)
                ladder(78, 2, 4)
                log(79, 4, 9)
                ladder(87, 5, 7)
                log(81, 7, 6)
                bear(82, 5)
                bear(84, 8)
                heart(86, 8)
                spikes(91, 93, 2)
                wolf(96, 2)
                log(102, 4, 3)
                log(107, 6, 3)
                log(112, 4, 3)
                carrots(107, 7, 3)
                ground(117, 149)
                fox(121, 2)
                bear(127, 2)
                wolf(132, 2)
                bear(137, 2)
                carrots(124, 4, 6)
                burrow(144, 2)
            },
        )
    }
}

class BunnyGameView(ctx: Context, private val onLeave: () -> Unit) : View(ctx), Choreographer.FrameCallback {

    private enum class Phase { INTRO, PLAY, PAUSED, DEAD, WON }

    /** A fox (0), a wolf (1) or a bear (2); [fy] is where its feet are, in tiles from the top. */
    private class Foe(val kind: Int, var x: Float, var fy: Float) {
        var dir = -1f
        var vy = 0f
        var onGround = true
        var hp = if (kind == 2) 2 else 1
        var alive = true
        var gone = 0f
        var phase = Random.nextFloat() * 10f
        var hopIn = 1f + Random.nextFloat() * 2f
        var climb = 0              // a bear on its ladder: -1 up, 1 down
        var climbX = 0
        var aim = 0f               // a bear raising its rifle, then the shot
        var reload = 2f + Random.nextFloat() * 2f
        var flash = 0f
        var hit = 0f
        val w get() = if (kind == 2) 0.9f else 0.8f
        val h get() = if (kind == 2) 1.3f else 0.8f
    }

    private class Shot(var x: Float, var y: Float, var vx: Float, var vy: Float, val carrot: Boolean) {
        var spin = 0f
    }

    private class Bit(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val color: Int, val size: Float, val grav: Float)

    private var levelIndex = 0
    private lateinit var level: BunnyLevels.Level
    private lateinit var tiles: Array<CharArray>
    private val foes = ArrayList<Foe>()
    private val shots = ArrayList<Shot>()
    private val bits = ArrayList<Bit>()

    // the bunny, in tiles (px: left edge, py: top edge; y grows downwards)
    private var px = 0f
    private var py = 0f
    private var vx = 0f
    private var vy = 0f
    private var facing = 1f
    private var onGround = false
    private var coyote = 0f
    private var hurtFor = 0f
    private var throwWait = 0f
    private var startX = 0f
    private var startY = 0f
    private var checkX = 0f
    private var checkY = 0f
    private var hop = 0f

    private var hearts = 3
    private var carrots = 8
    private var score = 0
    private var best = 0
    private var phase = Phase.INTRO
    private var phaseTime = 0f
    private var camX = 0f
    private var time = 0f
    private var shake = 0f

    private var left = false
    private var right = false
    private var jumpHeld = false
    private var jumpPressed = false
    private var throwPressed = false

    var running = false
        set(v) {
            field = v
            if (v) { last = 0L; Choreographer.getInstance().postFrameCallback(this) }
        }
    private var last = 0L

    private val W = 0.7f
    private val H = 0.85f

    /** The home screen's visitors, borrowed for their drawings: the bunny and the bears. */
    private val art = CritterLayer(ctx) { emptyList() }
    private val bunny = art.Critter(true, 0xFFF4F1EC.toInt(), 0xFFF6C3CF.toInt()).apply { alpha = 1f }
    private val bearArt = art.Critter(false, 0xFF8A5A3B.toInt(), 0xFFD8B38C.toInt()).apply { alpha = 1f }
    /** The same bear, lighter for a moment when a carrot hits it. */
    private val bearHit = art.Critter(false, 0xFFC98B6A.toInt(), 0xFFF0D2B0.toInt()).apply { alpha = 1f }

    init {
        isFocusable = true
        best = Prefs.of(ctx).getInt("bunny_best", 0)
        load(0)
    }

    private fun load(i: Int) {
        levelIndex = i
        level = BunnyLevels.all[i]
        tiles = Array(BunnyLevels.ROWS) { r -> level.grid[r].copyOf() }
        foes.clear()
        shots.clear()
        bits.clear()
        for (r in 0 until BunnyLevels.ROWS) for (c in 0 until level.cols) {
            val kind = when (tiles[r][c]) { 'f' -> 0; 'w' -> 1; 'b' -> 2; else -> -1 }
            if (kind >= 0) { foes.add(Foe(kind, c.toFloat(), r + 1f)); tiles[r][c] = ' ' }
            if (tiles[r][c] == 'S') { startX = c.toFloat(); startY = r + 1 - H; tiles[r][c] = ' ' }
        }
        checkX = startX
        checkY = startY
        respawn()
        phase = Phase.INTRO
        phaseTime = 0f
    }

    private fun respawn() {
        px = checkX
        py = checkY
        vx = 0f
        vy = 0f
        hurtFor = 1.2f
        camX = max(0f, px - 6f)
    }

    fun pause() {
        if (phase == Phase.PLAY) phase = Phase.PAUSED
        left = false; right = false; jumpHeld = false
    }

    // ---------- the remote ----------

    fun key(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> left = down
            KeyEvent.KEYCODE_DPAD_RIGHT -> right = down
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                jumpHeld = down
                if (first) when (phase) {
                    Phase.PLAY -> jumpPressed = true
                    Phase.PAUSED -> phase = Phase.PLAY
                    Phase.INTRO -> if (phaseTime > 0.4f) phase = Phase.PLAY
                    Phase.DEAD, Phase.WON -> if (phaseTime > 0.8f) restart()
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BUTTON_B -> if (first && phase == Phase.PLAY) throwPressed = true
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (first) {
                if (phase == Phase.PLAY || phase == Phase.INTRO) phase = Phase.PAUSED else onLeave()
            }
            else -> return false
        }
        return true
    }

    private fun restart() {
        hearts = 3
        carrots = 8
        score = 0
        load(0)
    }

    // ---------- the world ----------

    private fun tile(c: Int, r: Int): Char =
        if (c < 0 || c >= level.cols) '#' else if (r < 0 || r >= BunnyLevels.ROWS) ' ' else tiles[r][c]

    private fun solid(c: Int, r: Int) = tile(c, r) == '#'

    /** Something to stand on: ground, a log, or the top rung of a ladder. */
    private fun floor(c: Int, r: Int): Boolean {
        val t = tile(c, r)
        return t == '#' || t == '=' || (t == 'H' && tile(c, r - 1) != 'H')
    }

    // ---------- the frame ----------

    override fun doFrame(now: Long) {
        if (!running) return
        val dt = if (last == 0L) 0f else min(0.05f, (now - last) / 1e9f)
        last = now
        try {
            step(dt)
        } catch (e: Exception) {
            CrashLog.note(e)
        }
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun step(dt: Float) {
        time += dt
        phaseTime += dt
        bunny.phase += dt
        bearArt.phase += dt
        stepBits(dt)
        shake = max(0f, shake - dt)
        if (phase != Phase.PLAY) {
            if (phase == Phase.INTRO && phaseTime > 2f) phase = Phase.PLAY
            return
        }
        val target = (if (right) 6.5f else 0f) - (if (left) 6.5f else 0f)
        val accel = if (onGround) 45f else 28f
        vx = if (vx < target) min(target, vx + accel * dt) else max(target, vx - accel * dt)
        if (target != 0f) facing = if (target > 0) 1f else -1f
        coyote = if (onGround) 0.1f else max(0f, coyote - dt)
        if (jumpPressed && coyote > 0f) {
            vy = -17f
            coyote = 0f
            onGround = false
            dust(px + W / 2, py + H, 5)
        }
        jumpPressed = false
        if (!jumpHeld && vy < -5f) vy = -5f
        val falling = vy
        vy = min(18f, vy + 40f * dt)
        moveX(vx * dt)
        moveY(vy * dt)
        if (onGround && falling > 10f) dust(px + W / 2, py + H, 6)
        hop = if (onGround && abs(vx) > 0.5f) hop + dt else 0f
        bunny.state = if (abs(vx) > 0.5f || !onGround) CritterLayer.State.WALK else CritterLayer.State.IDLE

        throwWait = max(0f, throwWait - dt)
        if (throwPressed && throwWait == 0f && carrots > 0) {
            shots.add(Shot(px + W / 2 + facing * 0.35f, py + 0.3f, facing * 12f, -4f, true))
            carrots--
            throwWait = 0.3f
        }
        throwPressed = false
        stepShots(dt)
        stepFoes(dt)
        hurtFor = max(0f, hurtFor - dt)

        val c0 = floor(px).toInt()
        val c1 = floor(px + W - 0.01f).toInt()
        val r0 = floor(py).toInt()
        val r1 = floor(py + H - 0.01f).toInt()
        for (c in c0..c1) for (r in r0..r1) {
            when (tile(c, r)) {
                'c' -> { tiles[r][c] = ' '; carrots += 2; score += 10; sparkle(c + 0.5f, r + 0.5f, 0xFFF08A24.toInt()) }
                'v' -> { tiles[r][c] = ' '; hearts = min(5, hearts + 1); score += 25; sparkle(c + 0.5f, r + 0.5f, 0xFFE5484D.toInt()) }
                '^' -> if (py + H > r + 0.45f) hurt()
                'C' -> { finishLevel(); return }
            }
        }
        if (onGround && hurtFor == 0f) { checkX = floor(px); checkY = py }
        if (py > BunnyLevels.ROWS + 1) {
            hearts--
            shake = 0.3f
            if (hearts <= 0) { die(); return }
            respawn()
        }
        val view = width / tileSize()
        val ahead = facing * 1.5f
        val wantCam = (px + ahead - view * 0.4f).coerceIn(0f, max(0f, level.cols - view))
        camX += (wantCam - camX) * min(1f, dt * 5f)
    }

    private fun moveX(d: Float) {
        px += d
        val r0 = floor(py + 0.05f).toInt()
        val r1 = floor(py + H - 0.05f).toInt()
        if (d > 0) {
            val c = floor(px + W).toInt()
            for (r in r0..r1) if (solid(c, r)) { px = c - W - 0.001f; vx = 0f; return }
        } else if (d < 0) {
            val c = floor(px).toInt()
            for (r in r0..r1) if (solid(c, r)) { px = c + 1f + 0.001f; vx = 0f; return }
        }
    }

    private fun moveY(d: Float) {
        val before = py
        py += d
        onGround = false
        val c0 = floor(px + 0.05f).toInt()
        val c1 = floor(px + W - 0.05f).toInt()
        if (d > 0) {
            val r = floor(py + H).toInt()
            for (c in c0..c1) {
                val fromAbove = before + H <= r + 0.01f
                if (solid(c, r) || (fromAbove && floor(c, r))) { py = r - H; vy = 0f; onGround = true; return }
            }
        } else if (d < 0) {
            val r = floor(py).toInt()
            for (c in c0..c1) if (solid(c, r)) { py = r + 1f; vy = 0f; return }
        }
    }

    private fun stepShots(dt: Float) {
        val it = shots.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (s.carrot) s.vy += 16f * dt
            s.x += s.vx * dt
            s.y += s.vy * dt
            s.spin += dt * 900f * (if (s.vx > 0) 1 else -1)
            if (solid(floor(s.x).toInt(), floor(s.y).toInt()) || s.y > BunnyLevels.ROWS || abs(s.x - px) > 18f) {
                puff(s.x, s.y, if (s.carrot) 0xFFF08A24.toInt() else 0xFF9A9AA2.toInt(), 5)
                it.remove()
                continue
            }
            if (s.carrot) {
                for (f in foes) {
                    if (!f.alive) continue
                    if (s.x > f.x && s.x < f.x + f.w && s.y > f.fy - f.h && s.y < f.fy) {
                        damage(f)
                        it.remove()
                        break
                    }
                }
            } else if (hurtFor == 0f && s.x > px && s.x < px + W && s.y > py && s.y < py + H) {
                it.remove()
                hurt()
            }
        }
    }

    private fun damage(f: Foe) {
        f.hp--
        f.hit = 0.25f
        if (f.hp <= 0) {
            f.alive = false
            f.gone = 0.6f
            score += if (f.kind == 2) 150 else 60
            puff(f.x + f.w / 2, f.fy - f.h / 2, 0xFFFFFFFF.toInt(), 14)
            sparkle(f.x + f.w / 2, f.fy - f.h / 2, 0xFFFFE08A.toInt())
        } else {
            puff(f.x + f.w / 2, f.fy - f.h / 2, 0xFFFFFFFF.toInt(), 6)
        }
    }

    private fun stepFoes(dt: Float) {
        for (f in foes) {
            f.hit = max(0f, f.hit - dt)
            f.flash = max(0f, f.flash - dt)
            if (!f.alive) { f.gone = max(0f, f.gone - dt); continue }
            if (abs(f.x - px) > 22f) continue
            f.phase += dt
            val sameFloor = abs(f.fy - (py + H)) < 0.6f
            val toBunny = if (px > f.x) 1f else -1f
            when {
                f.kind == 2 && f.climb != 0 -> climbBear(f, dt)
                f.kind == 2 && f.aim > 0f -> {
                    // the rifle is up: the shot comes when it is at the shoulder
                    f.aim -= dt
                    if (f.aim <= 0f) {
                        shots.add(Shot(f.x + f.w / 2 + f.dir * 0.7f, f.fy - 0.75f, f.dir * 9f, 0f, false))
                        f.flash = 0.12f
                        f.reload = 2.5f + Random.nextFloat() * 1.5f
                    }
                }
                else -> {
                    if (f.kind == 2) {
                        f.reload -= dt
                        if (sameFloor && abs(px - f.x) < 9f && f.reload <= 0f && f.onGround) {
                            f.dir = toBunny
                            f.aim = 0.7f
                            continue
                        }
                        // at a ladder: now and then up or down it
                        val cx = floor(f.x + f.w / 2).toInt()
                        val centred = abs((f.x + f.w / 2) - (cx + 0.5f)) < 0.08f
                        if (centred && f.onGround && Random.nextFloat() < dt * 0.8f) {
                            val feetRow = floor(f.fy).toInt()
                            if (tile(cx, feetRow - 1) == 'H') { f.climb = -1; f.climbX = cx }
                            else if (tile(cx, feetRow) == 'H') { f.climb = 1; f.climbX = cx }
                            if (f.climb != 0) { f.x = cx + 0.5f - f.w / 2; continue }
                        }
                    }
                    // the wolf runs at the bunny when it sees it
                    val chase = f.kind == 1 && sameFloor && abs(px - f.x) < 7f
                    if (chase) f.dir = toBunny
                    val speed = when (f.kind) { 0 -> 1.8f; 1 -> if (chase) 4.2f else 2.4f; else -> 1.2f }
                    if (f.onGround) {
                        val nx = f.x + f.dir * speed * dt
                        val ahead = if (f.dir > 0) floor(nx + f.w).toInt() else floor(nx).toInt()
                        val feet = floor(f.fy).toInt()
                        if (solid(ahead, feet - 1) || !floor(ahead, feet)) f.dir = -f.dir else f.x = nx
                    }
                    // the fox hops now and then
                    if (f.kind == 0) {
                        f.hopIn -= dt
                        if (f.hopIn <= 0f && f.onGround) { f.vy = -9f; f.onGround = false; f.hopIn = 1.5f + Random.nextFloat() * 2f }
                    }
                    if (!f.onGround) {
                        val before = f.fy
                        f.vy = min(18f, f.vy + 40f * dt)
                        f.fy += f.vy * dt
                        val r = floor(f.fy).toInt()
                        val c = floor(f.x + f.w / 2).toInt()
                        if (f.vy > 0 && before <= r + 0.01f && floor(c, r)) { f.fy = r.toFloat(); f.vy = 0f; f.onGround = true }
                    }
                }
            }
            // touching the bunny: a jump on its head, or a hurt
            if (px + W > f.x + 0.1f && px < f.x + f.w - 0.1f && py + H > f.fy - f.h + 0.1f && py < f.fy) {
                val stomp = vy > 0f && py + H < f.fy - f.h + 0.5f
                if (stomp) {
                    vy = if (jumpHeld) -14f else -10f
                    damage(f)
                } else {
                    hurt()
                }
            }
        }
    }

    private fun climbBear(f: Foe, dt: Float) {
        f.fy += f.climb * 2.2f * dt
        val r = floor(f.fy).toInt()
        if (f.climb < 0) {
            // up: off at the top rung
            if (tile(f.climbX, r - 1) != 'H' && f.fy <= r + 0.02f && floor(f.climbX, r)) { f.fy = r.toFloat(); f.climb = 0 }
            if (tile(f.climbX, r) != 'H' && tile(f.climbX, r - 1) != 'H') { f.fy = (r + 1).toFloat(); f.climb = 0 }
        } else {
            // down: off where there is ground under the ladder
            if (tile(f.climbX, r) != 'H' && floor(f.climbX, r)) { f.fy = r.toFloat(); f.climb = 0 }
        }
        if (f.climb == 0) { f.onGround = true; f.dir = if (Random.nextBoolean()) 1f else -1f }
    }

    private fun hurt() {
        if (hurtFor > 0f) return
        hearts--
        hurtFor = 1.5f
        shake = 0.35f
        vy = -8f
        vx = -facing * 4f
        puff(px + W / 2, py + H / 2, 0xFFFFD1DC.toInt(), 8)
        if (hearts <= 0) die()
    }

    private fun die() {
        phase = Phase.DEAD
        phaseTime = 0f
        keepBest()
    }

    private fun finishLevel() {
        score += 300 + hearts * 50
        for (k in 0 until 4) sparkle(px + W / 2 + Random.nextFloat() * 2 - 1, py, 0xFFFFE08A.toInt())
        if (levelIndex + 1 < BunnyLevels.all.size) load(levelIndex + 1) else {
            phase = Phase.WON
            phaseTime = 0f
            keepBest()
        }
    }

    private fun keepBest() {
        if (score > best) {
            best = score
            Prefs.of(context).edit().putInt("bunny_best", best).apply()
        }
    }

    // ---------- little bits: dust, puffs, sparkles ----------

    private fun dust(x: Float, y: Float, n: Int) {
        repeat(n) { bits.add(Bit(x, y, (Random.nextFloat() - 0.5f) * 3f, -Random.nextFloat() * 1.5f, 0.4f, 0x99E8DCC8.toInt(), 0.08f, 2f)) }
    }

    private fun puff(x: Float, y: Float, color: Int, n: Int) {
        repeat(n) {
            val a = Random.nextFloat() * 6.283f
            val s = 1.5f + Random.nextFloat() * 3f
            bits.add(Bit(x, y, cos(a) * s, sin(a) * s, 0.5f + Random.nextFloat() * 0.3f, color, 0.12f, 0f))
        }
    }

    private fun sparkle(x: Float, y: Float, color: Int) {
        repeat(8) {
            val a = it * 0.785f
            bits.add(Bit(x, y, cos(a) * 3f, sin(a) * 3f - 1f, 0.6f, color, 0.07f, 4f))
        }
    }

    private fun stepBits(dt: Float) {
        val it = bits.iterator()
        while (it.hasNext()) {
            val b = it.next()
            b.life -= dt
            if (b.life <= 0f) { it.remove(); continue }
            b.vy += b.grav * dt
            b.x += b.vx * dt
            b.y += b.vy * dt
            b.vx *= (1f - dt * 2f)
        }
        if (bits.size > 300) bits.subList(0, bits.size - 300).clear()
    }

    // ---------- drawing ----------

    private fun tileSize(): Float = height / BunnyLevels.ROWS.toFloat()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val path = Path()
    private val rect = RectF()
    private var skyFor = -1
    private var skyH = 0
    private var skyShader: Shader? = null
    private var glowShader: Shader? = null

    private val skies = arrayOf(
        intArrayOf(0xFF8FD3F4.toInt(), 0xFFE6F5FB.toInt()),
        intArrayOf(0xFFE9785A.toInt(), 0xFFF7D9A6.toInt()),
        intArrayOf(0xFF141C3D.toInt(), 0xFF3E4A82.toInt()),
    )
    private val hillColors = arrayOf(
        intArrayOf(0xFFA9D3A2.toInt(), 0xFF79B472.toInt(), 0xFF4F8F4C.toInt()),
        intArrayOf(0xFFC98E7A.toInt(), 0xFF9C6A63.toInt(), 0xFF6B4B52.toInt()),
        intArrayOf(0xFF34406E.toInt(), 0xFF283358.toInt(), 0xFF1C2542.toInt()),
    )

    override fun onDraw(c: Canvas) {
        val t = tileSize()
        if (t <= 0f) return
        val theme = level.theme
        if (skyFor != levelIndex || skyH != height) {
            skyShader = LinearGradient(0f, 0f, 0f, height.toFloat(), skies[theme][0], skies[theme][1], Shader.TileMode.CLAMP)
            glowShader = RadialGradient(width * 0.78f, height * 0.2f, t * 3f,
                if (theme == 2) 0x66E8EEFF else 0x88FFF3C4.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
            skyFor = levelIndex
            skyH = height
        }
        p.shader = skyShader
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        // the sun (the moon at night) with a glow around it
        p.shader = glowShader
        c.drawCircle(width * 0.78f, height * 0.2f, t * 3f, p)
        p.shader = null
        p.color = when (theme) { 0 -> 0xFFFFF1B8.toInt(); 1 -> 0xFFFFC27A.toInt(); else -> 0xFFF2F4FF.toInt() }
        c.drawCircle(width * 0.78f, height * 0.2f, t * 0.8f, p)
        if (theme == 2) {
            p.color = skies[2][0]
            c.drawCircle(width * 0.78f + t * 0.35f, height * 0.2f - t * 0.15f, t * 0.65f, p)
            for (i in 0 until 60) {
                val sx = (((i * 197 + 13) % 1000) / 1000f * width - camX * t * 0.03f) % width
                val sy = ((i * 389 + 71) % 1000) / 1000f * height * 0.55f
                p.color = Color.argb((140 + 100 * sin(time * 2f + i)).toInt().coerceIn(40, 255), 255, 255, 255)
                c.drawCircle((sx + width) % width, sy, t * 0.03f * (1 + i % 3), p)
            }
        } else {
            clouds(c, t)
        }
        hills(c, 0.1f, 0.5f, hillColors[theme][0], 2.4f, 0.07f)
        hills(c, 0.25f, 0.6f, hillColors[theme][1], 3.4f, 0.06f)
        trees(c, t, 0.45f, hillColors[theme][2])

        val sx = if (shake > 0f) (Random.nextFloat() - 0.5f) * t * 0.25f * (shake / 0.35f) else 0f
        val sy = if (shake > 0f) (Random.nextFloat() - 0.5f) * t * 0.25f * (shake / 0.35f) else 0f
        c.save()
        c.translate(-camX * t + sx, sy)
        val first = max(0, floor(camX).toInt() - 1)
        val lastCol = min(level.cols - 1, first + (width / t).toInt() + 3)
        for (r in 0 until BunnyLevels.ROWS) for (col in first..lastCol) {
            val ch = tiles[r][col]
            if (ch != ' ') tileAt(c, ch, col, r, t, theme)
        }
        for (f in foes) if (f.alive || f.gone > 0f) foe(c, f, t)
        for (s in shots) shot(c, s, t)
        if (hurtFor == 0f || ((time * 12).toInt() % 2 == 0)) hero(c, t)
        for (b in bits) {
            p.color = b.color
            p.alpha = (Color.alpha(b.color) * (b.life / 0.6f).coerceIn(0f, 1f)).toInt()
            c.drawCircle(b.x * t, b.y * t, b.size * t, p)
        }
        p.alpha = 255
        c.restore()
        hud(c, t)
        overlay(c, t)
    }

    private fun clouds(c: Canvas, t: Float) {
        p.color = 0xB3FFFFFF.toInt()
        for (i in 0 until 5) {
            val base = i * width / 4.5f + 60f
            val x = ((base - camX * t * 0.08f - time * 8f) % (width + t * 6) + width + t * 6) % (width + t * 6) - t * 3
            val y = height * (0.12f + 0.07f * (i % 3))
            c.drawCircle(x, y, t * 0.55f, p)
            c.drawCircle(x + t * 0.6f, y - t * 0.25f, t * 0.7f, p)
            c.drawCircle(x + t * 1.3f, y, t * 0.5f, p)
        }
    }

    private fun hills(c: Canvas, speed: Float, top: Float, color: Int, waves: Float, amp: Float) {
        val t = tileSize()
        p.color = color
        path.reset()
        path.moveTo(0f, height.toFloat())
        val off = camX * t * speed
        var x = 0f
        while (x <= width + 24) {
            val y = height * top + sin((x + off) / width * Math.PI.toFloat() * waves) * height * amp
            path.lineTo(x, y)
            x += 24f
        }
        path.lineTo(width.toFloat(), height.toFloat())
        path.close()
        c.drawPath(path, p)
    }

    /** A row of fir trees between the hills and the ground, moving at their own speed. */
    private fun trees(c: Canvas, t: Float, speed: Float, color: Int) {
        p.color = color
        val gap = t * 2.3f
        val off = (camX * t * speed) % gap
        var x = -off - gap
        var i = floor(camX * t * speed / gap).toInt()
        while (x < width + gap) {
            val hgt = t * (1.6f + ((i * 37) % 5) * 0.25f)
            val bx = x + ((i * 53) % 7) * t * 0.1f
            val by = height * 0.72f
            path.reset()
            path.moveTo(bx, by - hgt)
            path.lineTo(bx + hgt * 0.32f, by)
            path.lineTo(bx - hgt * 0.32f, by)
            path.close()
            c.drawPath(path, p)
            x += gap
            i++
        }
        c.drawRect(0f, height * 0.72f - 1, width.toFloat(), height.toFloat(), p)
    }

    private fun tileAt(c: Canvas, ch: Char, col: Int, r: Int, t: Float, theme: Int) {
        val x = col * t
        val y = r * t
        when (ch) {
            '#' -> {
                p.color = when (theme) { 2 -> 0xFF4E4660.toInt(); 1 -> 0xFF8E5D44.toInt(); else -> 0xFF8A5A3B.toInt() }
                c.drawRect(x, y, x + t + 1, y + t + 1, p)
                p.color = when (theme) { 2 -> 0xFF3F3850.toInt(); else -> 0xFF74492F.toInt() }
                c.drawCircle(x + t * 0.3f, y + t * 0.6f, t * 0.08f, p)
                c.drawCircle(x + t * 0.72f, y + t * 0.35f, t * 0.06f, p)
                if (r == 0 || tiles[r - 1][col] != '#') {
                    p.color = when (theme) { 2 -> 0xFF4F7F63.toInt(); 1 -> 0xFF8FAF4A.toInt(); else -> 0xFF5DB04A.toInt() }
                    c.drawRect(x, y, x + t + 1, y + t * 0.22f, p)
                    // a few blades of grass
                    c.drawRect(x + t * 0.2f, y - t * 0.08f, x + t * 0.26f, y, p)
                    c.drawRect(x + t * 0.62f, y - t * 0.1f, x + t * 0.68f, y, p)
                }
            }
            '=' -> {
                p.color = 0xFF9B6A3E.toInt()
                rect.set(x, y + t * 0.1f, x + t + 1, y + t * 0.45f)
                c.drawRoundRect(rect, t * 0.15f, t * 0.15f, p)
                p.color = 0xFF7A5030.toInt()
                c.drawCircle(x + t * 0.5f, y + t * 0.27f, t * 0.06f, p)
            }
            'H' -> {
                // the bears' ladder: two rails, three rungs a tile
                p.color = art.woodDark
                for (k in 0 until 3) c.drawRect(x + t * 0.2f, y + t * (0.15f + k * 0.33f), x + t * 0.8f, y + t * (0.22f + k * 0.33f), p)
                p.color = art.wood
                c.drawRect(x + t * 0.15f, y, x + t * 0.25f, y + t, p)
                c.drawRect(x + t * 0.75f, y, x + t * 0.85f, y + t, p)
            }
            '^' -> {
                p.color = 0xFFB8BCC4.toInt()
                path.reset()
                for (k in 0 until 3) {
                    val sx = x + k * t / 3f
                    path.moveTo(sx, y + t)
                    path.lineTo(sx + t / 6f, y + t * 0.45f)
                    path.lineTo(sx + t / 3f, y + t)
                }
                c.drawPath(path, p)
            }
            'c' -> {
                val bob = sin(time * 4f + col) * t * 0.06f
                carrotShape(c, x + t * 0.5f, y + t * 0.5f + bob, t * 0.9f, 20f)
            }
            'v' -> {
                val beat = 1f + sin(time * 6f) * 0.08f
                heartShape(c, x + t * 0.5f, y + t * 0.5f, t * 0.32f * beat, 0xFFE5484D.toInt())
            }
            'C' -> burrow(c, x, y, t)
        }
    }

    /** A carrot along the screen, tip at the left when [angle] is 0. */
    private fun carrotShape(c: Canvas, cx: Float, cy: Float, len: Float, angle: Float) {
        c.save()
        c.translate(cx, cy)
        c.rotate(angle)
        p.color = 0xFF5DA03A.toInt()
        c.drawOval(len * 0.3f, -len * 0.2f, len * 0.55f, -len * 0.02f, p)
        c.drawOval(len * 0.3f, len * 0.02f, len * 0.55f, len * 0.2f, p)
        p.color = 0xFFF08A24.toInt()
        path.reset()
        path.moveTo(-len * 0.45f, 0f)
        path.lineTo(len * 0.32f, -len * 0.13f)
        path.lineTo(len * 0.32f, len * 0.13f)
        path.close()
        c.drawPath(path, p)
        c.restore()
    }

    private fun heartShape(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        p.color = color
        c.drawCircle(cx - r * 0.5f, cy - r * 0.25f, r * 0.55f, p)
        c.drawCircle(cx + r * 0.5f, cy - r * 0.25f, r * 0.55f, p)
        path.reset()
        path.moveTo(cx - r * 1.02f, cy - r * 0.05f)
        path.lineTo(cx, cy + r)
        path.lineTo(cx + r * 1.02f, cy - r * 0.05f)
        path.close()
        c.drawPath(path, p)
    }

    /** The bunny's burrow: a mound with a dark hole and a little flag of grass on top. */
    private fun burrow(c: Canvas, x: Float, y: Float, t: Float) {
        p.color = 0xFF7A8F4A.toInt()
        rect.set(x - t * 1.2f, y - t * 0.6f, x + t * 2.2f, y + t * 1.6f)
        c.drawOval(rect, p)
        p.color = 0xFF2A1D14.toInt()
        rect.set(x + t * 0.05f, y + t * 0.15f, x + t * 0.95f, y + t * 1.15f)
        c.drawOval(rect, p)
        // a warm light inside
        val f = 1f + sin(time * 5f) * 0.1f
        p.color = 0x66FFD35C
        c.drawCircle(x + t * 0.5f, y + t * 0.75f, t * 0.28f * f, p)
        p.color = 0xFF5DA03A.toInt()
        c.drawRect(x + t * 0.45f, y - t * 1.1f, x + t * 0.52f, y - t * 0.55f, p)
        p.color = 0xFFF08A24.toInt()
        path.reset()
        path.moveTo(x + t * 0.52f, y - t * 1.1f)
        path.lineTo(x + t * 0.95f, y - t * 0.95f)
        path.lineTo(x + t * 0.52f, y - t * 0.8f)
        path.close()
        c.drawPath(path, p)
    }

    /** The bunny of the home screen, a little larger: its drawing is about 30 units tall. */
    private fun hero(c: Canvas, t: Float) {
        c.save()
        c.translate((px + W / 2) * t, (py + H) * t)
        val k = t * 1.15f / 30f
        c.scale(facing * k, k)
        // in the air: stretched a little; just landed: squashed
        if (!onGround) c.scale(0.94f, 1.08f)
        art.bunny(c, bunny, 255)
        c.restore()
    }

    private fun foe(c: Canvas, f: Foe, t: Float) {
        c.save()
        c.translate((f.x + f.w / 2) * t, f.fy * t)
        if (!f.alive) {
            val k = f.gone / 0.6f
            c.scale(1f + (1f - k) * 0.5f, k)
        }
        when (f.kind) {
            2 -> {
                val k = t * 1.35f / 40f
                c.scale(f.dir * k, k)
                val b = if (f.hit > 0f) bearHit else bearArt
                b.state = when {
                    f.climb != 0 -> CritterLayer.State.LADDER
                    f.aim > 0f -> CritterLayer.State.IDLE
                    else -> CritterLayer.State.WALK
                }
                b.lStage = if (f.climb != 0) 1 else 0
                b.phase = f.phase
                art.bear(c, b, 255)
                if (f.climb == 0) rifle(c, f)
            }
            1 -> { val k = t / 30f; c.scale(f.dir * k, k); wolf(c, f) }
            else -> { val k = t / 30f; c.scale(f.dir * k, k); fox(c, f) }
        }
        c.restore()
    }

    /** The bear's rifle, in the bear's own units: low while walking, at the shoulder while aiming. */
    private fun rifle(c: Canvas, f: Foe) {
        c.save()
        val up = if (f.aim > 0f) min(1f, (0.7f - f.aim) / 0.3f) else 0f
        c.rotate(-25f + 25f * up + (if (f.aim <= 0f) sin(f.phase * 8f) * 4f else 0f), 6f, -16f)
        p.color = 0xFF5A3A22.toInt()
        rect.set(-2f, -19f, 9f, -14.5f)
        c.drawRoundRect(rect, 2f, 2f, p)
        p.color = 0xFF2B2B30.toInt()
        c.drawRect(8f, -18.6f, 26f, -16.2f, p)
        c.drawRect(12f, -16.2f, 14f, -14f, p)
        if (f.flash > 0f) {
            p.color = 0xFFFFD35C.toInt()
            c.drawCircle(28f, -17.4f, 4f, p)
            p.color = 0xFFFFFFFF.toInt()
            c.drawCircle(28f, -17.4f, 2f, p)
        }
        c.restore()
    }

    /** A fox: orange, white-tipped bushy tail, pointed ears. Facing right, feet at 0, about 24 units tall. */
    private fun fox(c: Canvas, f: Foe) {
        val run = sin(f.phase * 10f)
        val orange = if (f.hit > 0f) 0xFFFFB27A.toInt() else 0xFFE8742C.toInt()
        // tail
        p.color = orange
        c.save()
        c.rotate(-15f + run * 8f, -9f, -9f)
        c.drawOval(-22f, -14f, -6f, -6f, p)
        p.color = 0xFFFFFFFF.toInt()
        c.drawOval(-23f, -12.5f, -17f, -7.5f, p)
        c.restore()
        // legs
        p.color = 0xFF3A2A22.toInt()
        c.drawRect(-6f + run * 2f, -6f, -3.5f + run * 2f, 0f, p)
        c.drawRect(4f - run * 2f, -6f, 6.5f - run * 2f, 0f, p)
        // body
        p.color = orange
        c.drawOval(-10f, -14f, 9f, -5f, p)
        p.color = 0xFFFFF1E4.toInt()
        c.drawOval(-2f, -10f, 8f, -5.5f, p)
        // head and snout
        p.color = orange
        c.drawCircle(10f, -15f, 5.5f, p)
        path.reset()
        path.moveTo(13f, -17f)
        path.lineTo(21f, -13f)
        path.lineTo(13f, -11f)
        path.close()
        c.drawPath(path, p)
        // ears
        path.reset()
        path.moveTo(6f, -18f); path.lineTo(7f, -26f); path.lineTo(10.5f, -19.5f); path.close()
        path.moveTo(10f, -19.5f); path.lineTo(13f, -27f); path.lineTo(14.5f, -18f); path.close()
        c.drawPath(path, p)
        p.color = 0xFFFFF1E4.toInt()
        path.reset()
        path.moveTo(14f, -12.5f); path.lineTo(20f, -12.8f); path.lineTo(14f, -11f); path.close()
        c.drawPath(path, p)
        p.color = 0xFF1E1714.toInt()
        c.drawCircle(21f, -13f, 1.2f, p)
        c.drawOval(11f, -17.5f, 13f, -15f, p)
    }

    /** A wolf: grey, long legs, a narrow muzzle. Facing right, feet at 0, about 26 units tall. */
    private fun wolf(c: Canvas, f: Foe) {
        val run = sin(f.phase * 12f)
        val grey = if (f.hit > 0f) 0xFFC8CCD6.toInt() else 0xFF7D8494.toInt()
        val dark = 0xFF545A68.toInt()
        // tail
        p.color = dark
        c.save()
        c.rotate(10f + run * 10f, -10f, -12f)
        c.drawOval(-22f, -15f, -8f, -9f, p)
        c.restore()
        // legs, in pairs
        p.color = dark
        c.drawRect(-8f + run * 3f, -8f, -5.5f + run * 3f, 0f, p)
        c.drawRect(-5f - run * 3f, -8f, -2.5f - run * 3f, 0f, p)
        c.drawRect(5f - run * 3f, -8f, 7.5f - run * 3f, 0f, p)
        c.drawRect(8f + run * 3f, -8f, 10.5f + run * 3f, 0f, p)
        // body with a lighter belly
        p.color = grey
        c.drawOval(-12f, -17f, 12f, -7f, p)
        p.color = 0xFFC9CDD6.toInt()
        c.drawOval(-6f, -11.5f, 9f, -7f, p)
        // head
        p.color = grey
        c.drawCircle(13f, -18f, 5.5f, p)
        path.reset()
        path.moveTo(15f, -20f)
        path.lineTo(25f, -16.5f)
        path.lineTo(15f, -14f)
        path.close()
        c.drawPath(path, p)
        path.reset()
        path.moveTo(9f, -21f); path.lineTo(10f, -29f); path.lineTo(13f, -22.5f); path.close()
        path.moveTo(13f, -22.5f); path.lineTo(16f, -29f); path.lineTo(17f, -21f); path.close()
        c.drawPath(path, p)
        p.color = 0xFF1E1714.toInt()
        c.drawCircle(25f, -16.5f, 1.2f, p)
        p.color = 0xFFF2D04E.toInt()
        c.drawOval(14f, -21f, 16.4f, -18.6f, p)
        p.color = 0xFF1E1714.toInt()
        c.drawCircle(15.4f, -19.8f, 0.6f, p)
    }

    private fun shot(c: Canvas, s: Shot, t: Float) {
        if (s.carrot) {
            carrotShape(c, s.x * t, s.y * t, t * 0.6f, s.spin)
        } else {
            p.color = 0x55FFE08A
            c.drawCircle(s.x * t - (if (s.vx > 0) t * 0.15f else -t * 0.15f), s.y * t, t * 0.1f, p)
            p.color = 0xFF2B2B30.toInt()
            c.drawCircle(s.x * t, s.y * t, t * 0.07f, p)
        }
    }

    private fun hud(c: Canvas, t: Float) {
        p.color = 0x66000000
        rect.set(t * 0.3f, t * 0.3f, t * 6.6f, t * 1.1f)
        c.drawRoundRect(rect, t * 0.3f, t * 0.3f, p)
        for (i in 0 until max(3, hearts)) {
            heartShape(c, t * (0.75f + i * 0.55f), t * 0.68f, t * 0.2f, if (i < hearts) 0xFFE5484D.toInt() else 0x55FFFFFF)
        }
        val cx = t * (0.75f + max(3, hearts) * 0.55f + 0.3f)
        carrotShape(c, cx, t * 0.7f, t * 0.55f, -30f)
        text.textSize = t * 0.4f
        text.textAlign = Paint.Align.LEFT
        text.color = Color.WHITE
        c.drawText("$carrots", cx + t * 0.35f, t * 0.84f, text)
        c.drawText("$score", cx + t * 1.3f, t * 0.84f, text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText(Lang.tr("Level ${levelIndex + 1}/${BunnyLevels.all.size}"), width - t * 0.4f, t * 0.84f, text)
        text.textAlign = Paint.Align.CENTER
    }

    private fun overlay(c: Canvas, t: Float) {
        val lines: List<String> = when (phase) {
            Phase.PLAY -> return
            Phase.INTRO -> listOf(Lang.tr("Level ${levelIndex + 1}"), level.name, Lang.tr("◀ ▶ hop · OK jump · ▼ throw a carrot"))
            Phase.PAUSED -> listOf(Lang.tr("Paused"), Lang.tr("OK: go on · Back: leave"))
            Phase.DEAD -> listOf(Lang.tr("The bunny got tired"), Lang.tr("Score: $score · Best: $best"), Lang.tr("OK: again · Back: leave"))
            Phase.WON -> listOf(Lang.tr("The bunny got home!"), Lang.tr("Score: $score · Best: $best"), Lang.tr("OK: again · Back: leave"))
        }
        p.color = 0x99000000.toInt()
        c.drawRect(0f, height * 0.32f, width.toFloat(), height * 0.68f, p)
        lines.forEachIndexed { i, s ->
            text.textSize = if (i == 0) t * 0.9f else t * 0.42f
            text.color = if (i == 0) Color.WHITE else 0xFFDDDDDD.toInt()
            c.drawText(s, width / 2f, height * 0.42f + (if (i == 0) 0f else t * 0.35f + i * t * 0.6f), text)
        }
        text.color = Color.WHITE
        // the bunny waits in the corner of the intro
        if (phase == Phase.INTRO || phase == Phase.WON) {
            c.save()
            c.translate(width / 2f - t * 4.5f, height * 0.62f)
            val k = t * 1.6f / 30f
            c.scale(k, k)
            bunny.state = if (phase == Phase.WON) CritterLayer.State.WALK else CritterLayer.State.IDLE
            art.bunny(c, bunny, 255)
            c.restore()
        }
    }
}

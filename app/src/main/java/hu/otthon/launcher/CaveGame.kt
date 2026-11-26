package hu.otthon.launcher

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * "Kavics kalandja": a small platform game hidden in the launcher (an easter egg). Kavics, a cave
 * girl with a red ponytail, runs through three stone-age levels to the cave at their end, picks up
 * amber, jumps on the lizards and throws pebbles at the hedgehogs (they cannot be jumped on).
 *
 * Opened on the home screen with ▲ ▲ ▼ ▼ ◀ ▶ ◀ ▶. On the remote: ◀ ▶ run, OK or ▲ jump (held:
 * higher), ▼ throws a pebble, Back pauses (Back again leaves).
 *
 * Everything is drawn with shapes on a canvas: no pictures, nothing to load, light enough for the
 * 2 GB TVs. The best score is kept in the launcher's preferences.
 */
class CaveGameActivity : Activity() {
    private lateinit var game: CaveGameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        game = CaveGameView(this) { finish() }
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        game.key(event) || super.dispatchKeyEvent(event)

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back is the game's: pause, then leave
    }
}

/** The levels: built from a few commands, so each is easy to read and change. */
object CaveLevels {
    const val ROWS = 12

    class Level(val name: String, val cols: Int, val grid: Array<CharArray>, val sky: IntArray)

    /** Commands in tile units; y counts from the bottom (0 = the lowest row). */
    class Builder(val cols: Int) {
        val grid = Array(ROWS) { CharArray(cols) { ' ' } }
        private fun set(x: Int, y: Int, c: Char) {
            if (x in 0 until cols && y in 0 until ROWS) grid[ROWS - 1 - y][x] = c
        }
        /** Ground from column [from] to [to] (inclusive), [h] tiles high. */
        fun ground(from: Int, to: Int, h: Int = 2) { for (x in from..to) for (y in 0 until h) set(x, y, '#') }
        /** A log to stand on (from above only). */
        fun log(x: Int, y: Int, len: Int) { for (i in 0 until len) set(x + i, y, '=') }
        /** A stone block (solid from every side). */
        fun rock(x: Int, y: Int, w: Int = 1, h: Int = 1) { for (i in 0 until w) for (j in 0 until h) set(x + i, y + j, '#') }
        fun amber(x: Int, y: Int, n: Int = 1) { for (i in 0 until n) set(x + i, y, 'o') }
        fun lizard(x: Int, y: Int) = set(x, y, 'l')
        fun hog(x: Int, y: Int) = set(x, y, 'h')
        fun spikes(from: Int, to: Int, y: Int) { for (x in from..to) set(x, y, '^') }
        fun start(x: Int, y: Int) = set(x, y, 'S')
        fun cave(x: Int, y: Int) = set(x, y, 'C')
    }

    private fun level(name: String, cols: Int, sky: IntArray, b: Builder.() -> Unit): Level {
        val builder = Builder(cols)
        builder.b()
        return Level(name, cols, builder.grid, sky)
    }

    val all: List<Level> by lazy {
        listOf(
            level("Napos völgy", 120, intArrayOf(0xFF7EC8F2.toInt(), 0xFFCFEAF7.toInt())) {
                ground(0, 24)
                start(2, 2)
                amber(6, 3, 3)
                lizard(14, 2)
                log(18, 4, 4)
                amber(18, 5, 4)
                ground(28, 46)
                rock(32, 2, 2, 1)
                lizard(38, 2)
                amber(40, 4, 2)
                log(48, 4, 3)
                ground(53, 70)
                hog(60, 2)
                log(62, 4, 4)
                amber(62, 5, 4)
                rock(66, 2, 1, 2)
                ground(74, 90)
                spikes(80, 82, 2)
                amber(80, 5, 3)
                lizard(86, 2)
                log(92, 4, 3)
                ground(97, 119)
                amber(100, 3, 3)
                lizard(106, 2)
                cave(114, 2)
            },
            level("Alkonyi szakadék", 140, intArrayOf(0xFFF29A6B.toInt(), 0xFFF6D8A8.toInt())) {
                ground(0, 14)
                start(2, 2)
                log(16, 3, 3)
                log(21, 5, 3)
                amber(21, 6, 3)
                ground(26, 40, 3)
                hog(31, 3)
                lizard(36, 3)
                amber(30, 6, 4)
                log(43, 4, 2)
                log(47, 6, 2)
                log(51, 4, 2)
                amber(47, 7, 2)
                ground(55, 72)
                spikes(60, 63, 2)
                log(59, 4, 6)
                amber(59, 5, 6)
                lizard(68, 2)
                rock(72, 2, 1, 2)
                ground(73, 88, 3)
                hog(78, 3)
                hog(84, 3)
                log(91, 5, 3)
                log(96, 7, 3)
                amber(96, 8, 3)
                ground(101, 139)
                lizard(106, 2)
                rock(110, 2, 2, 2)
                lizard(116, 2)
                amber(118, 4, 4)
                hog(124, 2)
                cave(133, 2)
            },
            level("Csillagos barlangvidék", 150, intArrayOf(0xFF2B3566.toInt(), 0xFF6B6FA8.toInt())) {
                ground(0, 12)
                start(2, 2)
                lizard(9, 2)
                log(14, 4, 2)
                log(18, 6, 2)
                log(22, 8, 2)
                amber(22, 9, 2)
                ground(26, 44, 2)
                spikes(30, 34, 2)
                log(30, 4, 5)
                hog(38, 2)
                lizard(42, 2)
                log(40, 4, 3)
                rock(44, 2, 1, 4)
                ground(45, 58, 6)
                amber(48, 7, 5)
                lizard(54, 6)
                log(61, 6, 2)
                log(66, 4, 2)
                log(71, 6, 2)
                amber(66, 5, 2)
                ground(76, 96)
                hog(80, 2)
                spikes(85, 87, 2)
                log(84, 4, 5)
                lizard(91, 2)
                hog(94, 2)
                log(99, 4, 3)
                log(104, 6, 3)
                log(109, 4, 3)
                amber(104, 7, 3)
                ground(114, 149)
                lizard(119, 2)
                rock(124, 2, 2, 2)
                hog(129, 2)
                lizard(134, 2)
                amber(127, 5, 6)
                cave(144, 2)
            },
        )
    }
}

class CaveGameView(ctx: Context, private val onLeave: () -> Unit) : View(ctx), Choreographer.FrameCallback {

    // ---------- state ----------

    private enum class Phase { INTRO, PLAY, PAUSED, DEAD, WON }

    private class Mob(var x: Float, var y: Float, var dir: Float, val hog: Boolean) {
        var alive = true
        var squash = 0f
    }

    private class Pebble(var x: Float, var y: Float, var vx: Float, var vy: Float)

    private var levelIndex = 0
    private lateinit var level: CaveLevels.Level
    private lateinit var tiles: Array<CharArray>
    private val mobs = ArrayList<Mob>()
    private val pebbles = ArrayList<Pebble>()

    // Kavics, in tiles (x: left edge, y: top edge; y grows downwards as on the screen)
    private var px = 0f
    private var py = 0f
    private var vx = 0f
    private var vy = 0f
    private var facing = 1f
    private var onGround = false
    private var coyote = 0f
    private var hurtFor = 0f
    private var throwWait = 0f
    private var runPhase = 0f
    private var startX = 0f
    private var startY = 0f
    private var checkX = 0f
    private var checkY = 0f

    private var hearts = 3
    private var amber = 0
    private var score = 0
    private var best = 0
    private var phase = Phase.INTRO
    private var phaseTime = 0f
    private var camX = 0f
    private var time = 0f

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

    private val W = 0.7f      // Kavics's width and height, in tiles
    private val H = 0.95f

    init {
        isFocusable = true
        best = Prefs.of(ctx).getInt("cave_best", 0)
        load(0)
    }

    private fun load(i: Int) {
        levelIndex = i
        level = CaveLevels.all[i]
        tiles = Array(CaveLevels.ROWS) { r -> level.grid[r].copyOf() }
        mobs.clear()
        pebbles.clear()
        for (r in 0 until CaveLevels.ROWS) for (c in 0 until level.cols) {
            when (tiles[r][c]) {
                'S' -> { startX = c.toFloat(); startY = r + 1 - H; tiles[r][c] = ' ' }
                'l' -> { mobs.add(Mob(c.toFloat(), r.toFloat(), -1f, false)); tiles[r][c] = ' ' }
                'h' -> { mobs.add(Mob(c.toFloat(), r.toFloat(), -1f, true)); tiles[r][c] = ' ' }
            }
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
                if (first) {
                    when (phase) {
                        Phase.PLAY -> jumpPressed = true
                        Phase.PAUSED -> phase = Phase.PLAY
                        Phase.INTRO -> if (phaseTime > 0.4f) phase = Phase.PLAY
                        Phase.DEAD -> restart()
                        Phase.WON -> restart()
                    }
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BUTTON_B -> if (first && phase == Phase.PLAY) throwPressed = true
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (first) {
                    if (phase == Phase.PLAY || phase == Phase.INTRO) phase = Phase.PAUSED else onLeave()
                }
            }
            else -> return false
        }
        return true
    }

    private fun restart() {
        hearts = 3
        amber = 0
        score = 0
        load(0)
    }

    // ---------- the world ----------

    private fun tile(c: Int, r: Int): Char =
        if (c < 0 || c >= level.cols) '#' else if (r < 0) ' ' else if (r >= CaveLevels.ROWS) ' ' else tiles[r][c]

    private fun solid(c: Int, r: Int) = tile(c, r) == '#'

    private fun mobSolid(c: Int, r: Int) = tile(c, r) == '#' || tile(c, r) == '='

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
        if (phase != Phase.PLAY) {
            if (phase == Phase.INTRO && phaseTime > 1.8f) phase = Phase.PLAY
            return
        }
        // running
        val target = (if (right) 6.5f else 0f) - (if (left) 6.5f else 0f)
        val accel = if (onGround) 45f else 28f
        vx = if (vx < target) min(target, vx + accel * dt) else max(target, vx - accel * dt)
        if (target != 0f) facing = if (target > 0) 1f else -1f
        // jumping: a moment after leaving an edge still counts, a short press jumps lower
        coyote = if (onGround) 0.1f else max(0f, coyote - dt)
        if (jumpPressed && coyote > 0f) {
            vy = -17f
            coyote = 0f
            onGround = false
        }
        jumpPressed = false
        if (!jumpHeld && vy < -5f) vy = -5f
        vy = min(18f, vy + 40f * dt)
        moveX(vx * dt)
        moveY(vy * dt)
        runPhase += abs(vx) * dt * 2.2f

        // throwing
        throwWait = max(0f, throwWait - dt)
        if (throwPressed && throwWait == 0f && pebbles.size < 2) {
            pebbles.add(Pebble(px + W / 2 + facing * 0.4f, py + 0.35f, facing * 13f, -3f))
            throwWait = 0.35f
        }
        throwPressed = false
        stepPebbles(dt)
        stepMobs(dt)
        hurtFor = max(0f, hurtFor - dt)

        // amber, spikes, the cave
        val c0 = floor(px).toInt()
        val c1 = floor(px + W - 0.01f).toInt()
        val r0 = floor(py).toInt()
        val r1 = floor(py + H - 0.01f).toInt()
        for (c in c0..c1) for (r in r0..r1) {
            when (tile(c, r)) {
                'o' -> { tiles[r][c] = ' '; amber++; score += 10 }
                '^' -> if (py + H > r + 0.45f) hurt()
                'C' -> { finishLevel(); return }
            }
        }
        // a solid place to come back to after a fall
        if (onGround && hurtFor == 0f) { checkX = floor(px); checkY = py }
        // fell into a gap
        if (py > CaveLevels.ROWS + 1) {
            hearts--
            if (hearts <= 0) { die(); return }
            respawn()
        }
        // the camera: Kavics a little left of the middle, looking ahead
        val wantCam = (px - width / tileSize() * 0.38f).coerceIn(0f, max(0f, level.cols - width / tileSize()))
        camX += (wantCam - camX) * min(1f, dt * 6f)
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
                // logs hold only from above
                val log = tile(c, r) == '=' && before + H <= r + 0.01f
                if (solid(c, r) || log) { py = r - H; vy = 0f; onGround = true; return }
            }
        } else if (d < 0) {
            val r = floor(py).toInt()
            for (c in c0..c1) if (solid(c, r)) { py = r + 1f; vy = 0f; return }
        }
    }

    private fun stepPebbles(dt: Float) {
        val it = pebbles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.vy += 18f * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            if (solid(floor(p.x).toInt(), floor(p.y).toInt()) || p.y > CaveLevels.ROWS || abs(p.x - px) > 16f) { it.remove(); continue }
            for (m in mobs) {
                if (!m.alive) continue
                if (p.x > m.x && p.x < m.x + 0.9f && p.y > m.y && p.y < m.y + 0.9f) {
                    m.alive = false
                    m.squash = 0.4f
                    score += 50
                    it.remove()
                    break
                }
            }
        }
    }

    private fun stepMobs(dt: Float) {
        for (m in mobs) {
            if (!m.alive) { m.squash = max(0f, m.squash - dt); continue }
            // only the ones near the screen move
            if (abs(m.x - px) > 20f) continue
            val speed = if (m.hog) 1.2f else 1.8f
            val nx = m.x + m.dir * speed * dt
            val ahead = if (m.dir > 0) floor(nx + 0.9f).toInt() else floor(nx).toInt()
            val row = floor(m.y + 0.5f).toInt()
            // turns at a wall or at the edge of its ground
            if (mobSolid(ahead, row) || !mobSolid(ahead, row + 1)) m.dir = -m.dir else m.x = nx
            // touching Kavics
            if (px + W > m.x + 0.1f && px < m.x + 0.8f && py + H > m.y + 0.15f && py < m.y + 0.9f) {
                val stomp = !m.hog && vy > 0f && py + H < m.y + 0.55f
                if (stomp) {
                    m.alive = false
                    m.squash = 0.4f
                    vy = if (jumpHeld) -13f else -9f
                    score += 50
                } else {
                    hurt()
                }
            }
        }
    }

    private fun hurt() {
        if (hurtFor > 0f) return
        hearts--
        hurtFor = 1.5f
        vy = -8f
        vx = -facing * 4f
        if (hearts <= 0) die()
    }

    private fun die() {
        phase = Phase.DEAD
        phaseTime = 0f
        keepBest()
    }

    private fun finishLevel() {
        score += 200 + hearts * 50
        if (levelIndex + 1 < CaveLevels.all.size) {
            load(levelIndex + 1)
        } else {
            phase = Phase.WON
            phaseTime = 0f
            keepBest()
        }
    }

    private fun keepBest() {
        if (score > best) {
            best = score
            Prefs.of(context).edit().putInt("cave_best", best).apply()
        }
    }

    // ---------- drawing ----------

    private fun tileSize(): Float = height / CaveLevels.ROWS.toFloat()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val path = Path()
    private val rect = RectF()
    private var skyFor = -1
    private var skyH = 0
    private var skyShader: Shader? = null

    override fun onDraw(c: Canvas) {
        val t = tileSize()
        if (t <= 0f) return
        // the sky and two rows of hills moving slower than the ground
        if (skyFor != levelIndex || skyH != height) {
            skyShader = LinearGradient(0f, 0f, 0f, height.toFloat(), level.sky[0], level.sky[1], Shader.TileMode.CLAMP)
            skyFor = levelIndex
            skyH = height
        }
        p.shader = skyShader
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.shader = null
        if (levelIndex == 2) {
            p.color = 0xCCFFFFFF.toInt()
            for (i in 0 until 40) {
                val sx = ((i * 197 + 13) % 1000) / 1000f * width - (camX * t * 0.05f) % width
                val sy = ((i * 389 + 71) % 1000) / 1000f * height * 0.5f
                c.drawCircle((sx + width) % width, sy, t * 0.04f * (1 + i % 3), p)
            }
        }
        hills(c, 0.15f, 0.55f, if (levelIndex == 2) 0xFF3D4A7A.toInt() else 0xFF9CC7A0.toInt(), 3.2f)
        hills(c, 0.35f, 0.68f, if (levelIndex == 2) 0xFF2F3A60.toInt() else 0xFF6FA36F.toInt(), 2.1f)

        c.save()
        c.translate(-camX * t, 0f)
        val first = max(0, floor(camX).toInt() - 1)
        val lastCol = min(level.cols - 1, first + (width / t).toInt() + 3)
        for (r in 0 until CaveLevels.ROWS) for (col in first..lastCol) {
            val ch = tiles[r][col]
            if (ch == ' ') continue
            val x = col * t
            val y = r * t
            when (ch) {
                '#' -> {
                    p.color = if (levelIndex == 2) 0xFF5A4A63.toInt() else 0xFF8A5A3B.toInt()
                    c.drawRect(x, y, x + t + 1, y + t + 1, p)
                    p.color = if (levelIndex == 2) 0xFF4A3D52.toInt() else 0xFF74492F.toInt()
                    c.drawCircle(x + t * 0.3f, y + t * 0.6f, t * 0.08f, p)
                    c.drawCircle(x + t * 0.72f, y + t * 0.35f, t * 0.06f, p)
                    if (r == 0 || tiles[r - 1][col] != '#') {
                        p.color = if (levelIndex == 2) 0xFF5E8F6A.toInt() else 0xFF5DB04A.toInt()
                        c.drawRect(x, y, x + t + 1, y + t * 0.22f, p)
                    }
                }
                '=' -> {
                    p.color = 0xFF9B6A3E.toInt()
                    rect.set(x, y + t * 0.1f, x + t + 1, y + t * 0.45f)
                    c.drawRoundRect(rect, t * 0.15f, t * 0.15f, p)
                    p.color = 0xFF7A5030.toInt()
                    c.drawCircle(x + t * 0.5f, y + t * 0.27f, t * 0.06f, p)
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
                'o' -> {
                    val bob = sin(time * 4f + col) * t * 0.06f
                    p.color = 0xFFF2A33A.toInt()
                    path.reset()
                    path.moveTo(x + t * 0.5f, y + t * 0.2f + bob)
                    path.lineTo(x + t * 0.75f, y + t * 0.5f + bob)
                    path.lineTo(x + t * 0.5f, y + t * 0.82f + bob)
                    path.lineTo(x + t * 0.25f, y + t * 0.5f + bob)
                    path.close()
                    c.drawPath(path, p)
                    p.color = 0xCCFFF3C4.toInt()
                    c.drawCircle(x + t * 0.44f, y + t * 0.42f + bob, t * 0.06f, p)
                }
                'C' -> cave(c, x, y, t)
            }
        }
        for (m in mobs) if (m.alive || m.squash > 0f) mob(c, m, t)
        p.color = 0xFF8E9096.toInt()
        for (pb in pebbles) c.drawCircle(pb.x * t, pb.y * t, t * 0.12f, p)
        if (hurtFor == 0f || ((time * 12).toInt() % 2 == 0)) hero(c, t)
        c.restore()

        hud(c, t)
        overlay(c, t)
    }

    private fun hills(c: Canvas, speed: Float, top: Float, color: Int, waves: Float) {
        val t = tileSize()
        p.color = color
        path.reset()
        path.moveTo(0f, height.toFloat())
        val off = camX * t * speed
        var x = 0f
        while (x <= width + 20) {
            val y = height * top + sin((x + off) / width * Math.PI.toFloat() * waves) * height * 0.06f
            path.lineTo(x, y)
            x += 20f
        }
        path.lineTo(width.toFloat(), height.toFloat())
        path.close()
        c.drawPath(path, p)
    }

    private fun cave(c: Canvas, x: Float, y: Float, t: Float) {
        p.color = 0xFF6E6A66.toInt()
        rect.set(x - t * 0.8f, y - t * 1.6f, x + t * 1.8f, y + t)
        c.drawRoundRect(rect, t, t, p)
        p.color = 0xFF1E1B1A.toInt()
        rect.set(x - t * 0.25f, y - t * 0.9f, x + t * 1.25f, y + t * 1.3f)
        c.drawOval(rect, p)
        // a little fire inside, flickering
        val f = 1f + sin(time * 14f) * 0.15f
        p.color = 0xFFF28B30.toInt()
        c.drawCircle(x + t * 0.5f, y + t * 0.7f, t * 0.18f * f, p)
        p.color = 0xFFFFD35C.toInt()
        c.drawCircle(x + t * 0.5f, y + t * 0.76f, t * 0.09f * f, p)
    }

    /** Kavics: red ponytail with a bone clip, teal tunic, bare feet. Her own design. */
    private fun hero(c: Canvas, t: Float) {
        val cx = (px + W / 2) * t
        val by = (py + H) * t
        c.save()
        c.translate(cx, by)
        c.scale(facing, 1f)
        val swing = if (onGround && abs(vx) > 0.5f) sin(runPhase) * t * 0.12f else 0f
        // legs
        p.color = 0xFFE8B48A.toInt()
        c.drawRect(-t * 0.16f + swing, -t * 0.3f, -t * 0.04f + swing, 0f, p)
        c.drawRect(t * 0.04f - swing, -t * 0.3f, t * 0.16f - swing, 0f, p)
        // tunic, cut in a zig-zag at the bottom
        p.color = 0xFF2FA39A.toInt()
        path.reset()
        path.moveTo(-t * 0.26f, -t * 0.66f)
        path.lineTo(t * 0.26f, -t * 0.66f)
        path.lineTo(t * 0.3f, -t * 0.28f)
        path.lineTo(t * 0.15f, -t * 0.34f)
        path.lineTo(0f, -t * 0.26f)
        path.lineTo(-t * 0.15f, -t * 0.34f)
        path.lineTo(-t * 0.3f, -t * 0.28f)
        path.close()
        c.drawPath(path, p)
        // arm
        p.color = 0xFFE8B48A.toInt()
        c.drawRect(t * 0.12f, -t * 0.6f, t * 0.3f, -t * 0.5f, p)
        // head
        c.drawCircle(0f, -t * 0.8f, t * 0.2f, p)
        // hair and the ponytail
        p.color = 0xFFC2412D.toInt()
        rect.set(-t * 0.22f, -t * 1.02f, t * 0.2f, -t * 0.82f)
        c.drawArc(rect, 180f, 180f, true, p)
        val sway = sin(time * 6f) * t * 0.04f
        path.reset()
        path.moveTo(-t * 0.18f, -t * 0.92f)
        path.quadTo(-t * 0.5f, -t * 0.95f + sway, -t * 0.42f, -t * 0.62f + sway)
        path.lineTo(-t * 0.3f, -t * 0.7f)
        path.close()
        c.drawPath(path, p)
        // the bone clip
        p.color = 0xFFF3EEDF.toInt()
        c.drawRect(-t * 0.26f, -t * 0.95f, -t * 0.14f, -t * 0.9f, p)
        // eye
        p.color = 0xFF1E1B1A.toInt()
        c.drawCircle(t * 0.09f, -t * 0.82f, t * 0.03f, p)
        c.restore()
    }

    private fun mob(c: Canvas, m: Mob, t: Float) {
        val x = m.x * t
        val y = m.y * t
        c.save()
        c.translate(x + t * 0.45f, y + t)
        if (!m.alive) c.scale(1.2f, 0.3f)
        c.scale(-m.dir, 1f)
        if (m.hog) {
            // a hedgehog: spines all over, so it cannot be jumped on
            p.color = 0xFF6B4A2E.toInt()
            path.reset()
            for (k in 0..6) {
                val a = Math.PI * (k / 6.0)
                path.moveTo((-Math.cos(a) * t * 0.32).toFloat(), (-Math.sin(a) * t * 0.32).toFloat() - t * 0.05f)
                path.lineTo((-Math.cos(a) * t * 0.5).toFloat(), (-Math.sin(a) * t * 0.5).toFloat() - t * 0.05f)
                path.lineTo((-Math.cos(a + 0.25) * t * 0.32).toFloat(), (-Math.sin(a + 0.25) * t * 0.32).toFloat() - t * 0.05f)
            }
            c.drawPath(path, p)
            rect.set(-t * 0.38f, -t * 0.45f, t * 0.38f, t * 0.05f)
            c.drawArc(rect, 180f, 180f, true, p)
            p.color = 0xFFD9B48C.toInt()
            c.drawCircle(-t * 0.36f, -t * 0.12f, t * 0.1f, p)
            p.color = 0xFF1E1B1A.toInt()
            c.drawCircle(-t * 0.43f, -t * 0.13f, t * 0.03f, p)
        } else {
            // a small lizard with a frill
            p.color = 0xFF5FAE3E.toInt()
            rect.set(-t * 0.3f, -t * 0.42f, t * 0.3f, -t * 0.08f)
            c.drawOval(rect, p)
            c.drawCircle(-t * 0.32f, -t * 0.42f, t * 0.14f, p)
            path.reset()
            path.moveTo(t * 0.25f, -t * 0.25f)
            path.lineTo(t * 0.55f, -t * 0.12f)
            path.lineTo(t * 0.25f, -t * 0.14f)
            path.close()
            c.drawPath(path, p)
            p.color = 0xFFF2C14E.toInt()
            path.reset()
            path.moveTo(-t * 0.22f, -t * 0.5f)
            path.lineTo(-t * 0.12f, -t * 0.62f)
            path.lineTo(-t * 0.02f, -t * 0.42f)
            path.close()
            c.drawPath(path, p)
            p.color = 0xFF3F7F2A.toInt()
            c.drawRect(-t * 0.2f, -t * 0.12f, -t * 0.1f, 0f, p)
            c.drawRect(t * 0.1f, -t * 0.12f, t * 0.2f, 0f, p)
            p.color = 0xFF1E1B1A.toInt()
            c.drawCircle(-t * 0.37f, -t * 0.46f, t * 0.03f, p)
        }
        c.restore()
    }

    private fun hud(c: Canvas, t: Float) {
        p.color = 0x66000000
        rect.set(t * 0.3f, t * 0.3f, t * 5.2f, t * 1.1f)
        c.drawRoundRect(rect, t * 0.3f, t * 0.3f, p)
        for (i in 0 until 3) {
            p.color = if (i < hearts) 0xFFE5484D.toInt() else 0x55FFFFFF
            val hx = t * (0.75f + i * 0.6f)
            val hy = t * 0.7f
            c.drawCircle(hx - t * 0.1f, hy - t * 0.05f, t * 0.12f, p)
            c.drawCircle(hx + t * 0.1f, hy - t * 0.05f, t * 0.12f, p)
            path.reset()
            path.moveTo(hx - t * 0.22f, hy)
            path.lineTo(hx, hy + t * 0.22f)
            path.lineTo(hx + t * 0.22f, hy)
            path.close()
            c.drawPath(path, p)
        }
        p.color = 0xFFF2A33A.toInt()
        c.drawCircle(t * 2.75f, t * 0.7f, t * 0.15f, p)
        text.textSize = t * 0.4f
        text.textAlign = Paint.Align.LEFT
        c.drawText("$amber   $score", t * 3.0f, t * 0.84f, text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText(Lang.tr("Level ${levelIndex + 1}/${CaveLevels.all.size}"), width - t * 0.4f, t * 0.84f, text)
        text.textAlign = Paint.Align.CENTER
    }

    private fun overlay(c: Canvas, t: Float) {
        val lines: List<String> = when (phase) {
            Phase.PLAY -> return
            Phase.INTRO -> listOf(Lang.tr("Level ${levelIndex + 1}"), level.name, Lang.tr("◀ ▶ run · OK jump · ▼ throw a pebble"))
            Phase.PAUSED -> listOf(Lang.tr("Paused"), Lang.tr("OK: go on · Back: leave"))
            Phase.DEAD -> listOf(Lang.tr("Kavics got tired"), Lang.tr("Score: $score · Best: $best"), Lang.tr("OK: again · Back: leave"))
            Phase.WON -> listOf(Lang.tr("Kavics got home!"), Lang.tr("Score: $score · Best: $best"), Lang.tr("OK: again · Back: leave"))
        }
        p.color = 0x99000000.toInt()
        c.drawRect(0f, height * 0.32f, width.toFloat(), height * 0.68f, p)
        lines.forEachIndexed { i, s ->
            text.textSize = if (i == 0) t * 0.9f else t * 0.42f
            text.color = if (i == 0) Color.WHITE else 0xFFDDDDDD.toInt()
            c.drawText(s, width / 2f, height * 0.42f + (if (i == 0) 0f else t * 0.35f + i * t * 0.6f), text)
        }
        text.color = Color.WHITE
    }
}

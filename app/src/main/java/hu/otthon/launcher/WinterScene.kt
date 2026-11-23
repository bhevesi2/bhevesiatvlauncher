package hu.otthon.launcher

import android.animation.ArgbEvaluator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The Christmas landscapes: snowy village, forest cabin, frozen lake. They are drawn from data
 * in assets/winter/ (made by tools/winter/gen.py, which can also preview them on a PC), in the
 * colours of the time of day: pink dawn, blue day, violet dusk, moonlit night with warm windows.
 */
object Winter {

    /** Colours by role, opacity factors ("lights", "stars", "aurora", "bulbs") and the sun. */
    class Pal(val colors: Map<String, Int>, val f: Map<String, Float>, val sunY: Float, val sunR: Float) {
        fun c(role: String): Int = colors[role] ?: Color.MAGENTA
        fun k(name: String?): Float = if (name == null) 1f else f[name] ?: 1f

        companion object {
            private val argb = ArgbEvaluator()
            fun lerp(a: Pal, b: Pal, t: Float): Pal {
                if (t <= 0f) return a
                if (t >= 1f) return b
                val cs = HashMap<String, Int>(a.colors.size)
                for ((k, v) in a.colors) cs[k] = argb.evaluate(t, v, b.colors[k] ?: v) as Int
                val fs = HashMap<String, Float>()
                for ((k, v) in a.f) fs[k] = v + ((b.f[k] ?: v) - v) * t
                return Pal(cs, fs, a.sunY + (b.sunY - a.sunY) * t, a.sunR + (b.sunR - a.sunR) * t)
            }
        }
    }

    sealed class Item(val role: String, val alpha: Float, val factor: String?)
    class Fill(val path: Path, role: String, a: Float, k: String?) : Item(role, a, k)
    class Stroke(val path: Path, val width: Float, role: String, a: Float, k: String?) : Item(role, a, k)
    class Dot(val x: Float, val y: Float, val r: Float, role: String, a: Float, k: String?) : Item(role, a, k)
    class Glow(val x: Float, val y: Float, val r: Float, role: String, a: Float, k: String?) : Item(role, a, k) {
        val shader = CachedShader()
    }
    class Grad(val path: Path, val y0: Float, val y1: Float, role: String, val role2: String, a: Float, k: String?) : Item(role, a, k) {
        val shader = CachedShader()
    }

    class Scene(val name: String, val sunX: Float, val items: List<Item>)

    private var palettes: Map<String, Pal>? = null
    private var names: List<String>? = null
    private val scenes = HashMap<String, Scene>()

    @Synchronized
    fun names(ctx: Context): List<String> {
        names?.let { return it }
        val list = try {
            val a = JSONArray(read(ctx, "winter/scenes.json"))
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
        names = list
        return list
    }

    /** The scene for this hour: they take turns, a new one every hour like the photo sets. */
    fun current(ctx: Context): String? {
        val n = names(ctx)
        if (n.isEmpty()) return null
        val zone = java.util.TimeZone.getDefault()
        val now = System.currentTimeMillis()
        val hours = (now + zone.getOffset(now)) / 3_600_000L
        return n[(hours % n.size).toInt()]
    }

    @Synchronized
    fun palette(ctx: Context, p: Phase): Pal {
        val all = palettes ?: loadPalettes(ctx).also { palettes = it }
        return all[p.key] ?: all.values.first()
    }

    /** Reads and parses a scene; slow the first time (a few hundred shapes), call off the main thread. */
    @Synchronized
    fun scene(ctx: Context, name: String): Scene? {
        scenes[name]?.let { return it }
        val s = try { parse(name, JSONObject(read(ctx, "winter/$name.json"))) } catch (e: Exception) { null } ?: return null
        // keep just the one on screen and the next; they are not small
        if (scenes.size >= 2) scenes.clear()
        scenes[name] = s
        return s
    }

    private fun read(ctx: Context, path: String) = ctx.assets.open(path).bufferedReader().use { it.readText() }

    private fun loadPalettes(ctx: Context): Map<String, Pal> {
        val j = JSONObject(read(ctx, "winter/palettes.json"))
        val out = HashMap<String, Pal>()
        for (key in j.keys()) {
            val o = j.getJSONObject(key)
            val cs = HashMap<String, Int>()
            val fs = HashMap<String, Float>()
            for (k in o.keys()) {
                when (k) {
                    "f" -> { val f = o.getJSONObject("f"); for (n in f.keys()) fs[n] = f.getDouble(n).toFloat() }
                    "sunY", "sunR" -> {}
                    else -> cs[k] = Color.parseColor(o.getString(k))
                }
            }
            out[key] = Pal(cs, fs, o.getDouble("sunY").toFloat(), o.getDouble("sunR").toFloat())
        }
        return out
    }

    private fun parse(name: String, j: JSONObject): Scene {
        val arr = j.getJSONArray("items")
        val items = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val role = o.getString("c")
            val a = o.optDouble("a", 1.0).toFloat()
            val k = if (o.has("k")) o.getString("k") else null
            when {
                o.has("g") -> o.getJSONArray("g").let { g -> items.add(Glow(g.f(0), g.f(1), g.f(2), role, a, k)) }
                o.has("o") -> o.getJSONArray("o").let { g -> items.add(Dot(g.f(0), g.f(1), g.f(2), role, a, k)) }
                o.has("s") -> items.add(Stroke(path(o.getString("p")), o.getDouble("s").toFloat(), role, a, k))
                o.has("v") -> o.getJSONArray("v").let { v -> items.add(Grad(path(o.getString("p")), v.f(0), v.f(1), role, o.getString("c2"), a, k)) }
                else -> items.add(Fill(path(o.getString("p")), role, a, k))
            }
        }
        return Scene(name, j.optDouble("sunX", 1380.0).toFloat(), items)
    }

    private fun JSONArray.f(i: Int) = getDouble(i).toFloat()

    /** Absolute M, L, Q, C and Z: all the generator writes. */
    fun path(s: String): Path {
        val p = Path()
        var i = 0
        val n = s.length
        val num = FloatArray(6)
        var cmd = 'M'
        fun skip() { while (i < n && (s[i] == ' ' || s[i] == ',')) i++ }
        fun number(): Float {
            skip()
            val start = i
            if (i < n && (s[i] == '-' || s[i] == '+')) i++
            while (i < n && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(start, i).toFloat()
        }
        while (true) {
            skip()
            if (i >= n) break
            val ch = s[i]
            if (ch.isLetter()) {
                cmd = ch
                i++
                if (cmd == 'Z') { p.close(); continue }
            }
            when (cmd) {
                'M' -> { num[0] = number(); num[1] = number(); p.moveTo(num[0], num[1]); cmd = 'L' }
                'L' -> { num[0] = number(); num[1] = number(); p.lineTo(num[0], num[1]) }
                'Q' -> { for (k in 0 until 4) num[k] = number(); p.quadTo(num[0], num[1], num[2], num[3]) }
                'C' -> { for (k in 0 until 6) num[k] = number(); p.cubicTo(num[0], num[1], num[2], num[3], num[4], num[5]) }
                else -> i++
            }
        }
        return p
    }

    // ---------- drawing ----------

    private val stars: FloatArray by lazy {
        val r = Random(3)
        FloatArray(140 * 4) { 0f }.also { a ->
            for (s in 0 until 140) {
                a[s * 4] = r.nextFloat() * 1920f
                a[s * 4 + 1] = r.nextFloat() * 560f
                a[s * 4 + 2] = 0.8f + r.nextFloat() * 1.4f
                a[s * 4 + 3] = 0.3f + r.nextFloat() * 0.6f
            }
        }
    }

    private fun withAlpha(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((Color.alpha(c) * a.coerceIn(0f, 1f)).toInt() shl 24)

    private val skyShader = CachedShader()
    private val sunShader = CachedShader()

    /** Draws [scene] in the 1920x1080 design space (the caller scales). */
    fun draw(canvas: Canvas, scene: Scene, pal: Pal, paint: Paint) {
        paint.style = Paint.Style.FILL
        val sky1 = pal.c("sky1")
        val sky2 = pal.c("sky2")
        paint.shader = skyShader.get(mixKey(mixKey(0L, sky1), sky2)) { LinearGradient(0f, 0f, 0f, 760f, sky1, sky2, Shader.TileMode.CLAMP) }
        canvas.drawRect(0f, 0f, 1920f, 1080f, paint)
        paint.shader = null
        val sk = pal.k("stars")
        if (sk > 0.01f) {
            paint.color = Color.WHITE
            var i = 0
            while (i < stars.size) {
                paint.alpha = (255 * sk * stars[i + 3]).toInt()
                canvas.drawCircle(stars[i], stars[i + 1], stars[i + 2], paint)
                i += 4
            }
        }
        // the sun, or the moon at night, with a soft halo
        val sun = pal.c("sun")
        paint.shader = sunShader.get(mixKey(mixKey(mixKey(mixKey(0L, scene.sunX), pal.sunY), pal.sunR), sun)) {
            RadialGradient(scene.sunX, pal.sunY, pal.sunR * 4f,
                intArrayOf(withAlpha(sun, 0.35f), withAlpha(sun, 0.12f), withAlpha(sun, 0f)), floatArrayOf(0.2f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(scene.sunX, pal.sunY, pal.sunR * 4f, paint)
        paint.shader = null
        paint.color = sun
        canvas.drawCircle(scene.sunX, pal.sunY, pal.sunR, paint)

        for (it in scene.items) {
            val a = it.alpha * pal.k(it.factor)
            if (a <= 0.004f) continue
            val color = pal.c(it.role)
            when (it) {
                is Fill -> {
                    paint.style = Paint.Style.FILL
                    paint.color = withAlpha(color, a)
                    canvas.drawPath(it.path, paint)
                }
                is Stroke -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = it.width
                    paint.strokeCap = Paint.Cap.ROUND
                    paint.strokeJoin = Paint.Join.ROUND
                    paint.color = withAlpha(color, a)
                    canvas.drawPath(it.path, paint)
                    paint.style = Paint.Style.FILL
                }
                is Dot -> {
                    paint.color = withAlpha(color, a)
                    canvas.drawCircle(it.x, it.y, it.r, paint)
                }
                is Glow -> {
                    paint.color = Color.WHITE
                    paint.shader = it.shader.get(mixKey(mixKey(0L, color), a)) { RadialGradient(it.x, it.y, it.r, withAlpha(color, a), withAlpha(color, 0f), Shader.TileMode.CLAMP) }
                    canvas.drawCircle(it.x, it.y, it.r, paint)
                    paint.shader = null
                }
                is Grad -> {
                    paint.color = Color.WHITE
                    val c2 = pal.c(it.role2)
                    paint.shader = it.shader.get(mixKey(mixKey(mixKey(0L, color), c2), a)) { LinearGradient(0f, it.y0, 0f, it.y1, withAlpha(color, a), withAlpha(c2, a), Shader.TileMode.CLAMP) }
                    canvas.drawPath(it.path, paint)
                    paint.shader = null
                }
            }
        }
    }
}

/**
 * Gently falling snow over the Christmas landscape: three layers of flakes, the near ones
 * bigger and faster, each swaying a little. About 30 frames a second, and only while shown.
 */
class SnowView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    /** x, y, radius, speed, sway phase, sway amount, alpha */
    private var flakes = FloatArray(0)
    private val F = 7
    private var last = 0L
    private var running = false

    var active = false
        set(v) {
            field = v
            visibility = if (v) VISIBLE else GONE
            if (v) kick()
        }

    init {
        isFocusable = false
        isClickable = false
        visibility = GONE
    }

    private fun seed(w: Int, h: Int) {
        val count = if (Motion.level == Motion.REDUCED) 45 else 90
        flakes = FloatArray(count * F)
        for (i in 0 until count) {
            val layer = i % 3   // 0 far, 1 middle, 2 near
            val o = i * F
            flakes[o] = Random.nextFloat() * w
            flakes[o + 1] = Random.nextFloat() * h
            flakes[o + 2] = (0.9f + layer * 0.8f + Random.nextFloat() * 0.6f) * d
            flakes[o + 3] = (14f + layer * 14f + Random.nextFloat() * 10f) * d
            flakes[o + 4] = Random.nextFloat() * 6.28f
            flakes[o + 5] = (6f + layer * 6f) * d
            flakes[o + 6] = 0.45f + layer * 0.17f + Random.nextFloat() * 0.1f
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w > 0 && h > 0) seed(w, h)
    }

    private fun kick() {
        if (running || !active || Motion.level == Motion.OFF || !isAttachedToWindow) return
        running = true
        last = SystemClock.uptimeMillis()
        postOnAnimation(step)
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

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) kick()
    }

    private val step = object : Runnable {
        override fun run() {
            if (!active || !isAttachedToWindow || windowVisibility != VISIBLE || Motion.level == Motion.OFF) {
                running = false
                return
            }
            val now = SystemClock.uptimeMillis()
            val dt = min(0.1f, (now - last) / 1000f)
            last = now
            val w = width.toFloat()
            val h = height.toFloat()
            var i = 0
            while (i < flakes.size) {
                flakes[i + 1] += flakes[i + 3] * dt
                flakes[i + 4] += dt * 0.9f
                if (flakes[i + 1] > h + 6 * d) {
                    flakes[i + 1] = -6 * d
                    flakes[i] = Random.nextFloat() * w
                }
                i += F
            }
            invalidate()
            postDelayed(this, 33L)
        }
    }

    override fun onDraw(canvas: Canvas) {
        var i = 0
        while (i < flakes.size) {
            val x = flakes[i] + sin(flakes[i + 4]) * flakes[i + 5]
            paint.alpha = (flakes[i + 6] * 255).toInt()
            canvas.drawCircle(x, flakes[i + 1], flakes[i + 2], paint)
            i += F
        }
    }
}

package hu.otthon.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import org.json.JSONObject

/** Picks a picture for an entity from what it is and what it is called. */
object DashIcons {
    private val DOORS = setOf("door", "garage_door", "opening")
    private val POWER = setOf("W", "kW", "Wh", "kWh")
    private val TIME = setOf("h", "min", "s")
    private val CARS = arrayOf("car", "xdrive", "bmw", "skoda", "ford", "tdi", "mondeo", "tesla", "audi", "vw_", "volkswagen", "toyota")

    fun pick(item: HaItem, deviceClass: String, unit: String): Int {
        val e = item.entity.lowercase()
        val d = item.domain
        fun has(vararg keys: String) = keys.any { e.contains(it) }
        return when {
            d == "vacuum" -> R.drawable.ic_vacuum
            d == "person" -> R.drawable.ic_person
            d == "lock" || deviceClass == "lock" || has("lock") -> R.drawable.ic_lock
            deviceClass in DOORS || has("door", "gate", "garage") -> R.drawable.ic_door
            deviceClass == "window" || has("window") -> R.drawable.ic_window
            d == "alarm_control_panel" || has("security", "alarm", "house_status") -> R.drawable.ic_shield
            d == "device_tracker" -> if (CARS.any { e.contains(it) }) R.drawable.ic_car else R.drawable.ic_pin
            has("heating", "boiler", "nest") -> R.drawable.ic_flame
            d == "climate" || deviceClass == "temperature" || unit.startsWith("°") -> R.drawable.ic_thermo
            deviceClass == "humidity" || deviceClass == "moisture" ||
                has("fuel", "tank", "pool", "irrigation", "sprinkler", "water", "wash") -> R.drawable.ic_drop
            has("range") -> R.drawable.ic_car
            has("movie", "cinema") -> R.drawable.ic_film
            has("hifi", "music") -> R.drawable.ic_music
            d == "light" || d == "scene" || has("light", "lamp") -> R.drawable.ic_bulb
            deviceClass == "power" || deviceClass == "energy" || unit in POWER || has("power", "energy", "electric") -> R.drawable.ic_bolt
            has("_tv") -> R.drawable.ic_tv
            deviceClass == "duration" || unit in TIME || has("hours") -> R.drawable.ic_clock
            d == "script" || d == "button" || d == "input_button" -> R.drawable.ic_play
            d == "switch" || d == "input_boolean" || d == "fan" || d == "automation" -> R.drawable.ic_power
            d == "weather" -> R.drawable.ic_cloud
            else -> R.drawable.ic_chart
        }
    }
}

/**
 * One entity on the dashboard, drawn as a tile.
 *
 *  - a number gets its value with a small curve of the last 24 hours,
 *  - a percentage gets a level bar,
 *  - anything with a state gets a round badge that lights up while it is on, open or home,
 *  - the first tile of a section can be a tall "hero": a thermostat dial, a big curve, a big badge.
 */
class DashTile(ctx: Context, val item: HaItem, val hero: Boolean) : View(ctx) {
    var state: JSONObject? = null
        private set

    private var value = "…"
    private var numeric = false
    private var dead = true
    private var fraction = Float.NaN
    private var active = false
    private var accent = C.ON
    private var iconRes = 0
    private var icon: Drawable? = null
    private var spark: FloatArray? = null
    private var current = Float.NaN
    private var target = Float.NaN
    private var action = ""

    private val d = resources.displayMetrics.density
    private val sp = d * resources.configuration.fontScale
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val radius = 10f * d

    /** True once the tile shows a plain number, which is when a history curve makes sense. */
    val wantsSpark: Boolean get() = numeric && fraction.isNaN() && item.domain != "climate"

    init {
        isFocusable = true
        isClickable = true
        contentDescription = item.title
        FocusGlide.shape(this, radius)
        paintBackground(false)
        setOnFocusChangeListener { _, f ->
            paintBackground(f)
            invalidate()
        }
        setIcon(DashIcons.pick(item, "", ""))
    }

    private fun paintBackground(f: Boolean) {
        background = if (f) FocusGlide.fill(this, radius) else rounded(0x14FFFFFF, radius)
    }

    private fun setIcon(res: Int) {
        if (res == iconRes) return
        iconRes = res
        icon = context.getDrawable(res)?.mutate()
    }

    fun setSpark(values: FloatArray?) {
        spark = values
        invalidate()
    }

    fun bind(s: JSONObject?) {
        state = s
        val st = s?.optString("state") ?: ""
        val a = s?.optJSONObject("attributes")
        val deviceClass = a?.optString("device_class", "") ?: ""
        val unit = a?.optString("unit_of_measurement", "") ?: ""
        val n = st.toDoubleOrNull()
        dead = s == null || st == "unavailable" || st == "unknown" || st.isEmpty()
        value = if (s == null) "–" else Fmt.value(item.entity, s)
        numeric = !dead && n != null && item.domain in NUMBERS
        fraction = if (numeric && unit == "%") (n!! / 100.0).toFloat().coerceIn(0f, 1f) else Float.NaN
        active = false
        accent = C.ON
        current = Float.NaN
        target = Float.NaN
        action = ""
        if (!dead) when (item.domain) {
            "binary_sensor" -> if (st == "on") {
                active = true
                if (deviceClass in ATTENTION) accent = AMBER
            }
            "lock" -> if (st != "locked") { active = true; accent = AMBER }
            "person", "device_tracker" -> active = st == "home"
            "climate" -> {
                current = a?.optDouble("current_temperature", Double.NaN)?.toFloat() ?: Float.NaN
                target = a?.optDouble("temperature", Double.NaN)?.toFloat() ?: Float.NaN
                action = a?.optString("hvac_action", "") ?: ""
                if (st == "off") action = "off"
                active = action == "heating" || action == "cooling"
                accent = if (action == "cooling") COOL else HEAT
            }
            "vacuum" -> {
                active = st == "cleaning" || st == "returning"
                val battery = a?.optDouble("battery_level", Double.NaN) ?: Double.NaN
                if (!battery.isNaN()) fraction = (battery / 100.0).toFloat().coerceIn(0f, 1f)
            }
            else -> active = Fmt.isOn(item.domain, st)
        }
        setIcon(DashIcons.pick(item, deviceClass, unit))
        invalidate()
    }

    // ---------- drawing ----------

    override fun onDraw(c: Canvas) {
        val f = isFocused
        when {
            hero && item.domain == "climate" -> drawDial(c, f)
            hero && wantsSpark -> drawBigNumber(c, f)
            hero -> drawBadge(c, f, big = true)
            wantsSpark -> drawNumber(c, f)
            else -> drawBadge(c, f, big = false)
        }
    }

    private fun ink(f: Boolean) = if (f) C.DARK else C.TEXT
    private fun ink2(f: Boolean) = if (f) C.DARK2 else C.TEXT2
    private fun faint(f: Boolean) = if (f) 0x30141413 else 0x26FFFFFF

    private fun write(c: Canvas, s: String, x: Float, baseline: Float, maxWidth: Float, size: Float, face: Typeface, color: Int): Float {
        if (maxWidth <= 0f) return 0f
        text.textSize = size * sp
        text.typeface = face
        text.color = color
        val shown = TextUtils.ellipsize(s, text, maxWidth, TextUtils.TruncateAt.END).toString()
        c.drawText(shown, x, baseline, text)
        return text.measureText(shown)
    }

    /** Round badge with the entity's icon, name and state; a level bar when there is a percentage. */
    private fun drawBadge(c: Canvas, f: Boolean, big: Boolean) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 10f * d
        val r = (if (big) 22f else 14f) * d
        val cx = pad + r
        val cy = h / 2f
        p.style = Paint.Style.FILL
        p.color = when {
            active && f -> C.DARK
            active -> accent
            f -> 0x1F141413
            else -> 0x1FFFFFFF
        }
        c.drawCircle(cx, cy, r, p)
        icon?.let { ic ->
            val half = ((if (big) 12f else 8f) * d).toInt()
            ic.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
            ic.setTint(
                when {
                    active && f -> accent
                    active -> C.DARK
                    else -> ink2(f)
                }
            )
            ic.draw(c)
        }
        val x = cx + r + (if (big) 12f else 8f) * d
        val room = w - x - pad
        val hasBar = !fraction.isNaN()
        val lift = if (hasBar) 3f * d else 0f
        val valueColor = when {
            f -> C.DARK
            dead -> C.TEXT3
            active -> accent
            else -> C.TEXT
        }
        if (big) {
            write(c, item.title, x, cy - 8f * d - lift, room, 11f, regular, ink2(f))
            write(c, value, x, cy + 15f * d - lift, room, 19f, medium, valueColor)
        } else {
            write(c, item.title, x, cy - 3.5f * d - lift, room, 10f, regular, ink2(f))
            write(c, value, x, cy + 11.5f * d - lift, room, 13f, medium, valueColor)
        }
        if (hasBar) {
            val top = h - (if (big) 16f else 9f) * d
            val bottom = top + 3f * d
            val half = 1.5f * d
            p.color = faint(f)
            c.drawRoundRect(x, top, w - pad, bottom, half, half, p)
            p.color = if (fraction < 0.15f && !f) AMBER else ink(f)
            c.drawRoundRect(x, top, x + (w - pad - x) * fraction, bottom, half, half, p)
        }
    }

    /** Name and value, with the last 24 hours as a small curve on the right. */
    private fun drawNumber(c: Canvas, f: Boolean) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 11f * d
        write(c, item.title, pad, 20f * d, w - 2 * pad, 10f, regular, ink2(f))
        val used = write(c, value, pad, h - 12f * d, w - 2 * pad, 14f, medium, ink(f))
        val left = maxOf(pad + used + 10f * d, w * 0.5f)
        drawCurve(c, f, left, 27f * d, w - pad, h - 10f * d, fill = false)
    }

    /** The same on a tall tile: a big value over a wide curve. */
    private fun drawBigNumber(c: Canvas, f: Boolean) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 12f * d
        write(c, item.title, pad, 21f * d, w - 2 * pad - 30f * d, 11f, regular, ink2(f))
        text.textAlign = Paint.Align.RIGHT
        write(c, "24 h", w - pad, 21f * d, 30f * d, 9f, regular, if (f) C.DARK2 else C.TEXT3)
        text.textAlign = Paint.Align.LEFT
        write(c, value, pad, 49f * d, w - 2 * pad, 24f, light, ink(f))
        drawCurve(c, f, pad, 58f * d, w - pad, h - 11f * d, fill = true)
    }

    private fun drawCurve(c: Canvas, f: Boolean, left: Float, top: Float, right: Float, bottom: Float, fill: Boolean) {
        val v = spark ?: return
        if (v.size < 2 || right - left < 22f * d || bottom - top < 8f * d) return
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (x in v) {
            if (x < lo) lo = x
            if (x > hi) hi = x
        }
        val span = if (hi - lo < 1e-6f) 1f else hi - lo
        val flat = hi - lo < 1e-6f
        fun px(i: Int) = left + (right - left) * i / (v.size - 1)
        fun py(i: Int) = if (flat) (top + bottom) / 2f else bottom - (bottom - top) * (v[i] - lo) / span
        path.rewind()
        path.moveTo(px(0), py(0))
        for (i in 1 until v.size) path.lineTo(px(i), py(i))
        if (fill) {
            val area = Path(path)
            area.lineTo(right, bottom)
            area.lineTo(left, bottom)
            area.close()
            p.style = Paint.Style.FILL
            p.color = if (f) 0x1F141413 else 0x1AFFFFFF
            c.drawPath(area, p)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.6f * d
        p.strokeJoin = Paint.Join.ROUND
        p.strokeCap = Paint.Cap.ROUND
        p.color = if (f) 0xCC141413.toInt() else 0xB3F5F3EE.toInt()
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        p.color = ink(f)
        c.drawCircle(px(v.size - 1), py(v.size - 1), 2.4f * d, p)
    }

    /** Thermostat: a dial with the measured temperature, a mark at the target, and what it is doing. */
    private fun drawDial(c: Canvas, f: Boolean) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 12f * d
        val r = minOf(36f * d, h / 2f - 12f * d)
        val cx = pad + r + 3f * d
        val cy = h / 2f + 4f * d
        oval.set(cx - r, cy - r, cx + r, cy + r)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 5f * d
        p.color = faint(f)
        c.drawArc(oval, 135f, 270f, false, p)
        fun share(t: Float) = ((t - DIAL_MIN) / (DIAL_MAX - DIAL_MIN)).coerceIn(0f, 1f)
        if (!current.isNaN()) {
            p.color = when {
                active && f -> C.DARK
                active -> accent
                else -> ink2(f)
            }
            c.drawArc(oval, 135f, maxOf(1f, 270f * share(current)), false, p)
        }
        if (!target.isNaN()) {
            val ang = Math.toRadians((135f + 270f * share(target)).toDouble())
            p.style = Paint.Style.FILL
            p.color = ink(f)
            c.drawCircle(cx + r * Math.cos(ang).toFloat(), cy + r * Math.sin(ang).toFloat(), 4.5f * d, p)
        }
        text.textAlign = Paint.Align.CENTER
        write(c, if (current.isNaN()) "–" else Fmt.num(current.toDouble(), "°"), cx, cy + 7f * d, 2 * r - 12f * d, 19f, light, ink(f))
        text.textAlign = Paint.Align.LEFT

        val x = cx + r + 16f * d
        val room = w - x - pad
        write(c, item.title, x, cy - 21f * d, room, 11f, regular, ink2(f))
        val line = if (action == "off") "Off" else if (target.isNaN()) "–" else "Target " + Fmt.num(target.toDouble(), "°")
        write(c, line, x, cy + 1f * d, room, 16f, medium, ink(f))
        val doing = when (action) {
            "" -> ""
            "off" -> "Switched off"
            else -> action.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
        write(c, doing, x, cy + 19f * d, room, 11f, regular, if (active && !f) accent else ink2(f))
    }

    companion object {
        const val AMBER = 0xFFFAC775.toInt()
        const val HEAT = 0xFFF0997B.toInt()
        const val COOL = 0xFF85B7EB.toInt()
        const val DIAL_MIN = 10f
        const val DIAL_MAX = 30f
        val NUMBERS = setOf("sensor", "number", "input_number", "counter")
        val ATTENTION = setOf("door", "garage_door", "window", "opening", "lock", "problem", "safety", "smoke", "gas", "moisture", "tamper")

        /** Whether an item may open its section as a tall tile. */
        fun canBeHero(item: HaItem): Boolean = when (item.domain) {
            "climate", "input_select", "select", "alarm_control_panel", "vacuum" -> true
            "sensor" -> item.entity.contains("power")
            else -> false
        }
    }
}

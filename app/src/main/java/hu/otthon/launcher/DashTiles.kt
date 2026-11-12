package hu.otthon.launcher

import android.content.Context
import android.graphics.Bitmap
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

    fun looksLikeCar(entity: String): Boolean = entity.lowercase().let { e -> CARS.any { e.contains(it) } }

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

/** Anything on the dashboard that shows live entities. */
interface DashCell {
    val items: List<HaItem>
    fun bind(item: HaItem, s: JSONObject?)
}

/**
 * One entity on the dashboard, drawn as a tile.
 *
 *  - a number gets its value with a small curve of the last 24 hours,
 *  - a percentage gets a level bar,
 *  - anything with a state gets a round badge that lights up while it is on, open or home,
 *  - the first tile of a section can be a tall "hero": a thermostat dial, a big curve, a big badge,
 *  - [control] tiles are the things you can switch: a wide row with a switch, "Run" or the choice.
 */
class DashTile(ctx: Context, val item: HaItem, val hero: Boolean, val control: Boolean = false) : View(ctx), DashCell {
    override val items = listOf(item)
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
    val wantsSpark: Boolean get() = !control && numeric && fraction.isNaN() && item.domain != "climate"

    override fun bind(item: HaItem, s: JSONObject?) = bind(s)

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
            control -> drawControl(c, f)
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

    /** Splits a name over at most two lines at a space; what still does not fit ends in "…". */
    private fun nameLines(s: String, maxWidth: Float, size: Float): List<String> {
        text.textSize = size * sp
        text.typeface = regular
        if (maxWidth <= 0f || text.measureText(s) <= maxWidth) return listOf(s)
        val words = s.split(' ')
        var line = ""
        var used = 0
        for (word in words) {
            val longer = if (line.isEmpty()) word else "$line $word"
            if (text.measureText(longer) > maxWidth) break
            line = longer
            used++
        }
        if (used == 0 || used == words.size) return listOf(s)
        return listOf(line, words.drop(used).joinToString(" "))
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
            val name = nameLines(item.title, room, 10f)
            if (name.size == 1) {
                write(c, name[0], x, cy - 4f * d - lift, room, 10f, regular, ink2(f))
                write(c, value, x, cy + 12f * d - lift, room, 13f, medium, valueColor)
            } else {
                write(c, name[0], x, cy - 13.5f * d - lift, room, 10f, regular, ink2(f))
                write(c, name[1], x, cy - 2f * d - lift, room, 10f, regular, ink2(f))
                write(c, value, x, cy + 15f * d - lift, room, 13f, medium, valueColor)
            }
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
        val name = nameLines(item.title, w - 2 * pad, 10f)
        write(c, name[0], pad, (if (name.size == 1) 19f else 17f) * d, w - 2 * pad, 10f, regular, ink2(f))
        if (name.size > 1) write(c, name[1], pad, 28.5f * d, w - 2 * pad, 10f, regular, ink2(f))
        val used = write(c, value, pad, h - (if (name.size == 1) 11f else 9.5f) * d, w - 2 * pad, 14f, medium, ink(f))
        val left = maxOf(pad + used + 10f * d, w * 0.5f)
        drawCurve(c, f, left, (if (name.size == 1) 27f else 35f) * d, w - pad, h - 9f * d, fill = false)
    }

    /** Something you can switch: badge and name on the left; a switch, "Run" or the current choice on the right. */
    private fun drawControl(c: Canvas, f: Boolean) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 10f * d
        val r = 13f * d
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
            val half = (7.5f * d).toInt()
            ic.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
            ic.setTint(if (active && f) accent else if (active) C.DARK else ink2(f))
            ic.draw(c)
        }
        val x = cx + r + 10f * d
        var right = w - pad
        when (Actions.kind(item)) {
            Actions.Kind.TOGGLE -> {
                val tw = 28f * d
                val th = 15f * d
                val left = right - tw
                p.color = when {
                    dead -> faint(f)
                    active && f -> C.DARK
                    active -> accent
                    f -> 0x40141413
                    else -> 0x40FFFFFF
                }
                c.drawRoundRect(left, cy - th / 2, right, cy + th / 2, th / 2, th / 2, p)
                p.color = when {
                    active && f -> accent
                    active -> C.DARK
                    f -> C.DARK2
                    else -> C.TEXT
                }
                val knob = th / 2 - 2.5f * d
                c.drawCircle(if (active) right - th / 2 else left + th / 2, cy, knob, p)
                right = left - 10f * d
            }
            Actions.Kind.RUN -> {
                text.textAlign = Paint.Align.RIGHT
                val used = write(c, "Run  ›", right, cy + 4f * d, 60f * d, 11f, medium, ink2(f))
                text.textAlign = Paint.Align.LEFT
                right -= used + 10f * d
            }
            else -> {
                text.textAlign = Paint.Align.RIGHT
                val used = write(c, value, right, cy + 4f * d, (w - x) * 0.5f, 11f, medium, if (f) C.DARK else C.TEXT)
                text.textAlign = Paint.Align.LEFT
                right -= used + 10f * d
            }
        }
        write(c, item.title, x, cy + 4.3f * d, right - x, 12f, regular, ink(f))
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

/**
 * A car: its picture, where it is, and the readings listed after it in the section
 * (fuel, range, locks, windows ...). A percentage among them becomes the level bar.
 */
class CarCard(ctx: Context, val tracker: HaItem, val details: List<HaItem>) : View(ctx), DashCell {
    override val items = listOf(tracker) + details
    private val states = HashMap<String, JSONObject?>()
    private var picture: Bitmap? = null
    private val icons = HashMap<Int, Drawable?>()

    private val d = resources.displayMetrics.density
    private val sp = d * resources.configuration.fontScale
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val box = RectF()
    private val dst = RectF()
    private val clip = Path()
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val radius = 10f * d

    init {
        isFocusable = true
        isClickable = true
        contentDescription = tracker.title
        FocusGlide.shape(this, radius)
        paintBackground(false)
        setOnFocusChangeListener { _, f ->
            paintBackground(f)
            invalidate()
        }
    }

    private fun paintBackground(f: Boolean) {
        background = if (f) FocusGlide.fill(this, radius) else rounded(0x14FFFFFF, radius)
    }

    fun stateOf(item: HaItem): JSONObject? = states[item.entity]

    fun setPicture(b: Bitmap?) {
        picture = b
        invalidate()
    }

    override fun bind(item: HaItem, s: JSONObject?) {
        states[item.entity] = s
        invalidate()
    }

    private fun icon(res: Int): Drawable? = icons.getOrPut(res) { context.getDrawable(res)?.mutate() }

    private fun write(c: Canvas, s: String, x: Float, baseline: Float, maxWidth: Float, size: Float, face: Typeface, color: Int) {
        if (maxWidth <= 0f) return
        text.textSize = size * sp
        text.typeface = face
        text.color = color
        c.drawText(TextUtils.ellipsize(s, text, maxWidth, TextUtils.TruncateAt.END).toString(), x, baseline, text)
    }

    override fun onDraw(c: Canvas) {
        val f = isFocused
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 10f * d
        val ink = if (f) C.DARK else C.TEXT
        val ink2 = if (f) C.DARK2 else C.TEXT2
        val faint = if (f) 0x30141413 else 0x26FFFFFF

        // picture: a cut-out render is shown whole, a photo fills the box with rounded corners
        box.set(pad, pad, pad + 112f * d, h - pad)
        val b = picture
        if (b != null && b.width > 0 && b.height > 0) {
            val cover = !b.hasAlpha()
            val sx = box.width() / b.width
            val sy = box.height() / b.height
            val scale = if (cover) maxOf(sx, sy) else minOf(sx, sy)
            val bw = b.width * scale
            val bh = b.height * scale
            dst.set(box.centerX() - bw / 2, box.centerY() - bh / 2, box.centerX() + bw / 2, box.centerY() + bh / 2)
            p.alpha = 255
            if (cover) {
                val save = c.save()
                clip.rewind()
                clip.addRoundRect(box, 7f * d, 7f * d, Path.Direction.CW)
                c.clipPath(clip)
                c.drawBitmap(b, null, dst, p)
                c.restoreToCount(save)
            } else {
                c.drawBitmap(b, null, dst, p)
            }
        } else {
            p.style = Paint.Style.FILL
            p.color = if (f) 0x14141413 else 0x12FFFFFF
            c.drawRoundRect(box, 7f * d, 7f * d, p)
            icon(R.drawable.ic_car)?.let { ic ->
                val half = (22f * d).toInt()
                ic.setBounds(box.centerX().toInt() - half, box.centerY().toInt() - half, box.centerX().toInt() + half, box.centerY().toInt() + half)
                ic.setTint(if (f) C.DARK2 else C.TEXT3)
                ic.draw(c)
            }
        }

        val x = box.right + 12f * d
        val room = w - x - pad
        write(c, tracker.title, x, 23f * d, room, 12.5f, medium, ink)

        // where it is
        val where = states[tracker.entity]?.optString("state") ?: ""
        val home = where == "home"
        val place = when (where) {
            "" -> "…"
            "home" -> "Home"
            "not_home" -> "Away"
            "unavailable", "unknown" -> "–"
            else -> where.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
        p.style = Paint.Style.FILL
        p.color = if (home && !f) C.ON else ink2
        c.drawCircle(x + 3.5f * d, 36.5f * d, 3.5f * d, p)
        write(c, place, x + 12f * d, 40f * d, room - 12f * d, 10.5f, regular, if (home && !f) C.ON else ink2)

        // readings: icon and value, two to a line
        var fraction = Float.NaN
        val half = room / 2f
        var shown = 0
        for (item in details) {
            val s = states[item.entity]
            val st = s?.optString("state") ?: ""
            val a = s?.optJSONObject("attributes")
            val unit = a?.optString("unit_of_measurement", "") ?: ""
            val deviceClass = a?.optString("device_class", "") ?: ""
            val n = st.toDoubleOrNull()
            if (fraction.isNaN() && unit == "%" && n != null) fraction = (n / 100.0).toFloat().coerceIn(0f, 1f)
            if (shown >= 4) continue
            val low = unit == "%" && n != null && n < 15.0
            val open = item.domain == "binary_sensor" && st == "on" && deviceClass in DashTile.ATTENTION
            val unlocked = st.contains("unlock")
            val warn = (low || open || unlocked) && !f
            val cx = x + (shown % 2) * half
            val baseline = 60f * d + (shown / 2) * 17.5f * d
            icon(DashIcons.pick(item, deviceClass, unit))?.let { ic ->
                val size = (12f * d).toInt()
                val top = (baseline - 10.5f * d).toInt()
                ic.setBounds(cx.toInt(), top, cx.toInt() + size, top + size)
                ic.setTint(if (warn) DashTile.AMBER else ink2)
                ic.draw(c)
            }
            write(c, if (s == null) "…" else Fmt.value(item.entity, s), cx + 16f * d, baseline, half - 20f * d, 10.5f, regular, if (warn) DashTile.AMBER else ink)
            shown++
        }
        if (!fraction.isNaN()) {
            val top = h - 12f * d
            val r = 1.5f * d
            p.color = faint
            c.drawRoundRect(x, top, w - pad, top + 3f * d, r, r, p)
            p.color = if (fraction < 0.15f && !f) DashTile.AMBER else ink
            c.drawRoundRect(x, top, x + (w - pad - x) * fraction, top + 3f * d, r, r, p)
        }
    }

    companion object {
        private val WORDS = arrayOf("car", "vehicle", "auto")

        /** A tracker is shown as a car when it is called like one, or sits in a section called like that. */
        fun isCar(item: HaItem, section: String): Boolean {
            if (item.domain != "device_tracker") return false
            val title = section.lowercase()
            return WORDS.any { title.contains(it) } || DashIcons.looksLikeCar(item.entity)
        }
    }
}

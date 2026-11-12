package hu.otthon.launcher

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Today: what needs attention at home, the weather of the next hours, the calendar of the coming
 * week, the to-do lists and who is at home. Weather and the sun sit under the clock on the left.
 */
class TodayActivity : PanelActivity() {

    private lateinit var cfg: Config
    private var ha: HaClient? = null
    private val exec = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private var active = false
    private var gen = 0

    private lateinit var frame: PanelFrame
    private lateinit var list: LinearLayout
    private lateinit var nowIcon: ImageView
    private lateinit var nowTemp: TextView
    private lateinit var nowText: TextView
    private lateinit var sunText: TextView
    private lateinit var dayText: TextView

    private val zone = ZoneId.systemDefault()
    private val hourFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /** Everything the panel shows, gathered off the main thread. */
    private class Data {
        var attention = ArrayList<Pair<String, String>>()
        var hourly: List<Forecast> = emptyList()
        var events: List<HaClient.Event> = emptyList()
        var calendarsOk = true
        val todos = ArrayList<Triple<HaItem, String, List<HaClient.TodoItem>?>>()
        var people = ArrayList<Pair<String, String>>()
        var weather: JSONObject? = null
        var sun: JSONObject? = null
        var workday: String? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config.load(this)
        if (!cfg.haEnabled) {
            Toast.makeText(this, "Set up Home Assistant first", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        ha = HaClient(cfg.haUrl!!, cfg.haToken!!)
        frame = PanelFrame(this, "Today", "OK on a to-do item: done")
        frame.update(cfg)
        buildLeft()
        val scroll = ColScroller(this).apply {
            clipToPadding = false
            setPadding(dp(22f), dp(4f), dp(22f), dp(20f))
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(label("Loading…", 12f, C.TEXT2).apply { setPadding(dp(2f), dp(14f), 0, 0) })
        scroll.addView(list, FrameLayout.LayoutParams(-1, -2))
        frame.panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(frame.root)
        animateIn(frame.panel)
    }

    private fun buildLeft() {
        val left = frame.left
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nowIcon = ImageView(this).apply { setColorFilter(C.TEXT) }
        row.addView(nowIcon, LinearLayout.LayoutParams(dp(34f), dp(34f)))
        nowTemp = label("", 30f, C.TEXT, light = true).apply { setPadding(dp(10f), 0, 0, 0) }
        row.addView(nowTemp)
        left.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(26f) })
        nowText = label("", 13f, C.TEXT2).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        left.addView(nowText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })
        sunText = label("", 12f, C.TEXT2).apply { setLineSpacing(dp(3f).toFloat(), 1f) }
        left.addView(sunText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f) })
        dayText = label("", 12f, C.TEXT2)
        left.addView(dayText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
    }

    override fun onResume() {
        super.onResume()
        active = true
        val g = ++gen
        afterEnter { tick(g) }
    }

    override fun onPause() {
        super.onPause()
        active = false
    }

    override fun onDestroy() {
        super.onDestroy()
        exec.shutdownNow()
    }

    private fun tick(g: Int) {
        if (!active || g != gen) return
        frame.tick()
        load()
        main.postDelayed({ tick(g) }, 60_000L)
    }

    // ---------- gathering ----------

    private fun load() {
        val client = ha ?: return
        val c = cfg
        try {
            exec.execute {
                val d = Data()
                val now = ZonedDateTime.now(zone)
                fun <T> safe(work: () -> T): T? = try { work() } catch (e: Exception) { null }

                d.weather = c.weather?.let { w -> safe { client.state(w) } }
                d.hourly = c.weather?.let { w -> safe { client.forecast(w, "hourly") } } ?: emptyList()
                d.sun = safe { client.state("sun.sun") }

                // calendars: the coming seven days
                val from = now.toLocalDate().atStartOfDay(zone).toInstant()
                val to = from.plusSeconds(8 * 86400L)
                val cals = safe { client.calendars() }
                if (cals == null) d.calendarsOk = false
                d.events = (cals ?: emptyList()).flatMap { (id, name) -> safe { client.events(id, name, from, to) } ?: emptyList() }
                    .sortedWith(compareBy({ it.start }, { !it.allDay }))

                for (t in c.todoLists) {
                    val name = t.label.ifBlank { safe { client.state(t.entity).optJSONObject("attributes")?.optString("friendly_name") } ?: t.title }
                    d.todos.add(Triple(t, name, safe { client.todoItems(t.entity) }))
                }

                // people, and everything on the dashboard and the status chips that needs a look
                val all = safe { client.states() } ?: emptyList()
                d.people = ArrayList(all.filter { it.domain == "person" && it.state != "unknown" && it.state != "unavailable" }
                    .map { Pair(it.name, if (it.state == "home") "Home" else if (it.state == "not_home") "Away" else it.state.replace('_', ' ').replaceFirstChar { ch -> ch.uppercase() }) })
                d.workday = all.firstOrNull { it.domain == "binary_sensor" && it.id.contains("workday") }?.state
                val watch = (c.sections.flatMap { it.items } + c.status).distinctBy { it.entity }
                for (item in watch) {
                    val s = safe { client.state(item.entity) } ?: continue
                    attention(item, s)?.let { d.attention.add(Pair(item.title, it)) }
                }
                main.post { if (active) show(d) }
            }
        } catch (e: Exception) { }
    }

    /** Why an entity needs a look, or null if it is fine. */
    private fun attention(item: HaItem, s: JSONObject): String? {
        val st = s.optString("state")
        val a = s.optJSONObject("attributes") ?: JSONObject()
        val dc = a.optString("device_class", "")
        val unit = a.optString("unit_of_measurement", "")
        val e = item.entity
        return when {
            st == "unavailable" -> null
            item.domain == "binary_sensor" && st == "on" && dc in setOf("door", "garage_door", "window", "opening") ->
                if (dc == "window") "Open window" else "Open"
            item.domain == "binary_sensor" && st == "on" && dc in setOf("problem", "safety", "smoke", "gas", "moisture", "tamper") -> "Alert"
            item.domain == "lock" && st != "locked" -> "Unlocked"
            st == "unlocked" || (e.contains("lock") && st.contains("unlock")) -> "Unlocked"
            unit == "%" && (dc == "battery" || e.contains("fuel") || e.contains("battery") || e.contains("tank")) -> {
                val n = st.toDoubleOrNull() ?: return null
                if (n < 15) Fmt.num(n, "%") + " left" else null
            }
            else -> null
        }
    }

    // ---------- showing ----------

    private fun show(d: Data) {
        // left: weather now, the sun, the day
        val w = d.weather
        if (w != null) {
            val a = w.optJSONObject("attributes") ?: JSONObject()
            val cond = w.optString("state")
            nowIcon.setImageResource(weatherIcon(cond))
            val t = a.optDouble("temperature", Double.NaN)
            nowTemp.text = if (t.isNaN()) "" else Fmt.num(t, "°")
            nowText.text = cond.replace("partlycloudy", "partly cloudy").replace('-', ' ').replaceFirstChar { it.uppercase() }
        }
        d.sun?.optJSONObject("attributes")?.let { a ->
            fun at(k: String) = try { OffsetDateTime.parse(a.optString(k)).atZoneSameInstant(zone) } catch (e: Exception) { null }
            val rise = at("next_rising")
            val set = at("next_setting")
            val parts = ArrayList<String>()
            if (rise != null) parts.add("Sunrise  " + rise.format(hourFmt))
            if (set != null) parts.add("Sunset  " + set.format(hourFmt))
            sunText.text = parts.joinToString("\n")
        }
        dayText.text = when (d.workday) {
            "on" -> "Workday"
            "off" -> "Day off"
            else -> ""
        }

        val focusedText = (currentFocus as? RowView)?.titleView?.text?.toString()
        list.removeAllViews()

        section("Needs attention")
        if (d.attention.isEmpty()) row("Nothing needs attention", "✓", C.ON)
        for ((name, why) in d.attention) row(name, why, DashTile.AMBER)

        if (d.hourly.isNotEmpty()) {
            section("Next hours")
            list.addView(hours(d.hourly), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })
        }

        section("Calendar")
        val today = LocalDate.now(zone)
        if (!d.calendarsOk) row("Couldn't read the calendars", null, null)
        else if (d.events.isEmpty()) row("Nothing in the next seven days", null, null)
        for (ev in d.events.take(12)) {
            val day = ev.start.toLocalDate()
            val whenText = when (day) {
                today -> "Today"
                today.plusDays(1) -> "Tomorrow"
                else -> ev.start.format(dayFmt)
            } + if (ev.allDay) "" else "  " + ev.start.format(hourFmt)
            row(ev.summary.ifBlank { ev.calendar }, whenText, if (day == today) C.TEXT else null)
        }

        for ((t, name, items) in d.todos) {
            section(name)
            when {
                items == null -> row("Couldn't read this list", null, null)
                items.isEmpty() -> row("Nothing on the list", "✓", C.ON)
                else -> for (it in items) {
                    row(it.summary, "", null).setOnClickListener { _ -> done(t.entity, it) }
                }
            }
        }

        if (d.people.isNotEmpty()) {
            section("At home")
            for ((name, where) in d.people) row(name, where, if (where == "Home") C.ON else null)
        }

        // keep the cursor where it was, or start at the top
        val rows = (0 until list.childCount).map { list.getChildAt(it) }.filterIsInstance<RowView>()
        (rows.firstOrNull { it.titleView.text.toString() == focusedText } ?: rows.firstOrNull())?.requestFocus()
    }

    private fun section(title: String) {
        list.addView(label(title, 11f, C.TEXT2, bold = true), LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = if (list.childCount > 0) dp(16f) else dp(8f)
            bottomMargin = dp(2f)
            leftMargin = dp(2f)
        })
    }

    /** A row the cursor can rest on, so the list can be scrolled; [tone] colours the value. */
    private fun row(title: String, value: String?, tone: Int?): RowView {
        val r = RowView(this, title, value, null)
        r.tone = tone
        list.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })
        return r
    }

    /** The next twelve hours, side by side: time, sky, temperature, rain. */
    private fun hours(all: List<Forecast>): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(C.ROW, dp(8f).toFloat())
            setPadding(dp(6f), dp(10f), dp(6f), dp(10f))
        }
        val now = System.currentTimeMillis() - 30 * 60_000L
        for (f in all.filter { it.time >= now }.take(12).filterIndexed { i, _ -> i % 2 == 0 }) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(label(Instant.ofEpochMilli(f.time).atZone(zone).format(hourFmt), 10f, C.TEXT3))
            col.addView(ImageView(this).apply {
                setImageResource(weatherIcon(f.condition))
                setColorFilter(C.TEXT2)
            }, LinearLayout.LayoutParams(dp(18f), dp(18f)).apply { topMargin = dp(4f); bottomMargin = dp(4f) })
            col.addView(label(if (f.hi.isNaN()) "–" else Fmt.num(Math.round(f.hi).toDouble(), "°"), 12f, C.TEXT))
            col.addView(label(if (!f.rain.isNaN() && f.rain > 0.05) Fmt.num(f.rain, "mm") else "", 9f, 0xFF85B7EB.toInt()))
            box.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return box
    }

    private fun done(list: String, item: HaClient.TodoItem) {
        val client = ha ?: return
        try {
            exec.execute {
                val ok = try {
                    client.call("todo", "update_item", JSONObject().put("entity_id", list).put("item", item.uid).put("status", "completed"))
                    true
                } catch (e: Exception) {
                    false
                }
                main.post {
                    Toast.makeText(this, if (ok) "${item.summary} ✓" else "Couldn't update the list", Toast.LENGTH_SHORT).show()
                    if (ok) load()
                }
            }
        } catch (e: Exception) { }
    }
}

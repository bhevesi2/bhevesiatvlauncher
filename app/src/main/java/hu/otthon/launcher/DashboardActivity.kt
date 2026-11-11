package hu.otthon.launcher

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/** One entity on the dashboard: name on the left, live value (and switch) on the right. */
class DashRow(ctx: Context, val item: HaItem) : LinearLayout(ctx) {
    private val name = ctx.label(item.title, 12f, C.TEXT2)
    private val value = ctx.label("…", 12f, C.TEXT, bold = true)
    private val dot: SwitchDot? = if (Actions.kind(item) == Actions.Kind.TOGGLE) SwitchDot(ctx) else null
    var state: JSONObject? = null
        private set
    private var tone = C.TEXT

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        setPadding(ctx.dp(10f), ctx.dp(7f), ctx.dp(10f), ctx.dp(7f))
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        addView(name, LayoutParams(0, -2, 1f))
        value.maxLines = 1
        addView(value, LayoutParams(-2, -2).apply { leftMargin = ctx.dp(8f) })
        dot?.let { addView(it, LayoutParams(ctx.dp(26f), ctx.dp(14f)).apply { leftMargin = ctx.dp(8f) }) }
        paint(false)
        setOnFocusChangeListener { _, f -> paint(f) }
    }

    fun bind(s: JSONObject?) {
        state = s
        if (s == null) {
            value.text = "–"
            tone = C.TEXT3
        } else {
            val st = s.optString("state")
            val dc = s.optJSONObject("attributes")?.optString("device_class", "") ?: ""
            value.text = Fmt.value(item.entity, s)
            dot?.on = st == "on"
            tone = when {
                st == "unavailable" || st == "unknown" -> C.TEXT3
                item.domain == "binary_sensor" && st == "on" && dc in ATTENTION -> AMBER
                Fmt.isOn(item.domain, st) -> C.ON
                else -> C.TEXT
            }
        }
        paint(isFocused)
    }

    private fun paint(f: Boolean) {
        background = if (f) rounded(C.FOCUS_FILL, context.dp(7f).toFloat()) else null
        name.setTextColor(if (f) C.DARK2 else C.TEXT2)
        value.setTextColor(if (f) C.DARK else tone)
        dot?.dark = f
    }

    companion object {
        const val AMBER = 0xFFFAC775.toInt()
        val ATTENTION = setOf("door", "garage_door", "window", "opening", "lock", "problem", "safety", "smoke", "gas", "moisture", "tamper")
    }
}

/** Home Assistant overview: weather, sections of live entities, toggles and statistics. */
class DashboardActivity : Activity() {

    private lateinit var cfg: Config
    private var ha: HaClient? = null
    private val exec = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private var active = false
    private var gen = 0

    private lateinit var backdrop: Backdrop
    private lateinit var clockView: TextView
    private lateinit var weatherBox: LinearLayout
    private lateinit var weatherIconView: ImageView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherText: TextView
    private lateinit var weatherSub: TextView
    private lateinit var forecastRow: LinearLayout
    private lateinit var message: TextView
    private val columns = ArrayList<LinearLayout>()
    private val rows = ArrayList<DashRow>()
    private var builtFor: String? = null
    private var lastForecast = 0L

    private lateinit var overlay: FrameLayout
    private lateinit var statsTitle: TextView
    private lateinit var statsValue: TextView
    private lateinit var statsSummary: TextView
    private lateinit var statsList: TextView
    private lateinit var chart: ChartView
    private val tabs = ArrayList<TextView>()
    private var statsItem: HaItem? = null
    private var statsUnit = ""
    private var statsRange = 1
    private var statsGen = 0
    private var overlayDownSeen = false

    private val ranges = intArrayOf(6, 24, 72, 168)
    private val rangeNames = listOf("6 hours", "24 hours", "3 days", "7 days")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val side = dp(40f)
        val root = FrameLayout(this)
        backdrop = Backdrop(this)
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(24f), 0, 0)
        }

        // header: title on the left, weather on the right
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        titleRow.addView(label("Home", 24f, C.TEXT, bold = true))
        clockView = label("", 14f, C.TEXT2).apply { setPadding(dp(12f), 0, 0, dp(2f)) }
        titleRow.addView(clockView)
        titleCol.addView(titleRow)
        titleCol.addView(label("OK: switch or open statistics   ·   Hold OK: statistics   ·   Back: home", 10f, C.TEXT3).apply {
            setPadding(0, dp(6f), 0, 0)
        })
        header.addView(titleCol)
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        weatherBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
            background = rounded(C.CARD, dp(12f).toFloat(), 1, C.BORDER)
            visibility = View.GONE
        }
        weatherIconView = ImageView(this).apply { setColorFilter(C.TEXT) }
        weatherBox.addView(weatherIconView, LinearLayout.LayoutParams(dp(28f), dp(28f)))
        weatherTemp = label("", 26f, C.TEXT, light = true).apply { setPadding(dp(8f), 0, dp(10f), 0) }
        weatherBox.addView(weatherTemp)
        val wCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        weatherText = label("", 12f, C.TEXT, bold = true)
        weatherSub = label("", 10f, C.TEXT3).apply { setPadding(0, dp(3f), 0, 0) }
        wCol.addView(weatherText)
        wCol.addView(weatherSub)
        weatherBox.addView(wCol)
        forecastRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8f), 0, 0, 0) }
        weatherBox.addView(forecastRow)
        header.addView(weatherBox)
        page.addView(header, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = side; rightMargin = side })

        message = label("", 13f, C.TEXT2).apply { setPadding(side, dp(30f), side, 0); visibility = View.GONE }
        page.addView(message)

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(side, dp(16f), side, dp(24f))
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until 3) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            columns.add(col)
            body.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(12f) })
        }
        scroll.addView(body)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        buildOverlay()
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun buildOverlay() {
        overlay = FrameLayout(this)
        overlay.setBackgroundColor(0xB3000000.toInt())
        overlay.visibility = View.GONE
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF516171C.toInt(), dp(16f).toFloat(), 1, C.BORDER)
            setPadding(dp(22f), dp(18f), dp(22f), dp(14f))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        statsTitle = label("", 17f, C.TEXT, bold = true)
        statsValue = label("", 17f, C.TEXT, light = true)
        head.addView(statsTitle, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(statsValue)
        panel.addView(head)

        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12f), 0, dp(8f)) }
        rangeNames.forEachIndexed { i, n ->
            val t = label(n, 11f, C.TEXT2).apply { setPadding(dp(11f), dp(5f), dp(11f), dp(5f)) }
            tabs.add(t)
            tabRow.addView(t, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = dp(6f) })
        }
        panel.addView(tabRow)

        val frame = FrameLayout(this)
        chart = ChartView(this)
        frame.addView(chart, FrameLayout.LayoutParams(-1, -1))
        statsList = label("", 12f, C.TEXT2).apply { setLineSpacing(dp(5f).toFloat(), 1f); visibility = View.GONE }
        frame.addView(statsList, FrameLayout.LayoutParams(-1, -1))
        panel.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        val foot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8f), 0, 0) }
        statsSummary = label("", 12f, C.TEXT)
        foot.addView(statsSummary, LinearLayout.LayoutParams(0, -2, 1f))
        foot.addView(label("◀ ▶ range   ·   Back: close", 10f, C.TEXT3))
        panel.addView(foot)
        overlay.addView(panel, FrameLayout.LayoutParams(dp(680f), dp(390f), Gravity.CENTER))
    }

    // ---------- lifecycle ----------

    override fun onResume() {
        super.onResume()
        active = true
        val g = ++gen
        cfg = Config.load(this)
        backdrop.setDim(0.78f, flat = true)
        backdrop.show(cfg.background, Backgrounds.phase(this, cfg), false)
        clockView.text = ZonedDateTime.now().format(timeFmt)
        val client = if (cfg.haEnabled) HaClient(cfg.haUrl!!, cfg.haToken!!) else null
        ha = client
        if (client == null) {
            showMessage("Home Assistant is not set up yet. Open Settings › Home Assistant.")
            return
        }
        if (cfg.sections.isEmpty()) {
            showMessage("The dashboard is empty. Add sections in Settings › Dashboard.")
            return
        }
        message.visibility = View.GONE
        val hides = cfg.sections.mapNotNull { it.hideWhen }.distinct()
        exec.execute {
            val map = HashMap<String, String>()
            for (h in hides) map[h] = try { client.state(h).optString("state") } catch (e: Exception) { "" }
            main.post {
                if (active && g == gen) {
                    val visible = cfg.sections.filter { it.items.isNotEmpty() && (it.hideWhen == null || map[it.hideWhen] != "on") }
                    val sig = visible.toString()
                    if (sig != builtFor) {
                        builtFor = sig
                        build(visible)
                    }
                    tick(g)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        active = false
    }

    override fun onDestroy() {
        super.onDestroy()
        exec.shutdownNow()
    }

    private fun showMessage(text: String) {
        columns.forEach { it.removeAllViews() }
        rows.clear()
        builtFor = null
        message.text = text
        message.visibility = View.VISIBLE
    }

    private fun build(visible: List<DashSection>) {
        columns.forEach { it.removeAllViews() }
        rows.clear()
        val heights = IntArray(columns.size)
        for (sec in visible) {
            var col = 0
            for (i in heights.indices) if (heights[i] < heights[col]) col = i
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(C.CARD, dp(12f).toFloat(), 1, C.BORDER)
                setPadding(dp(6f), dp(10f), dp(6f), dp(6f))
            }
            card.addView(label(sec.title, 11f, C.TEXT3, bold = true).apply { setPadding(dp(10f), 0, dp(10f), dp(5f)) })
            for (item in sec.items) {
                val r = DashRow(this, item)
                r.setOnClickListener { onRowClick(r) }
                r.setOnLongClickListener { showStats(r); true }
                card.addView(r, LinearLayout.LayoutParams(-1, -2))
                rows.add(r)
            }
            val lp = LinearLayout.LayoutParams(-1, -2)
            if (columns[col].childCount > 0) lp.topMargin = dp(12f)
            columns[col].addView(card, lp)
            heights[col] += sec.items.size + 2
        }
        rows.firstOrNull()?.requestFocus()
    }

    // ---------- live data ----------

    private fun tick(g: Int) {
        if (!active || g != gen) return
        clockView.text = ZonedDateTime.now().format(timeFmt)
        for (r in rows.toList()) refreshRow(r)
        refreshWeather()
        main.postDelayed({ tick(g) }, 10_000L)
    }

    private fun refreshRow(r: DashRow) {
        val client = ha ?: return
        if (!active) return
        try {
            exec.execute {
                val s = try { client.state(r.item.entity) } catch (e: Exception) { null }
                main.post { if (active) r.bind(s) }
            }
        } catch (e: Exception) { }
    }

    private fun refreshWeather() {
        val client = ha ?: return
        val entity = cfg.weather ?: run { weatherBox.visibility = View.GONE; return }
        val wantForecast = System.currentTimeMillis() - lastForecast > 15 * 60_000L
        exec.execute {
            val s = try { client.state(entity) } catch (e: Exception) { null }
            val fc = if (wantForecast) try { client.forecast(entity) } catch (e: Exception) { null } else null
            main.post {
                if (active && s != null) {
                    val a = s.optJSONObject("attributes") ?: JSONObject()
                    val cond = s.optString("state")
                    weatherBox.visibility = View.VISIBLE
                    weatherIconView.setImageResource(weatherIcon(cond))
                    val t = a.optDouble("temperature", Double.NaN)
                    weatherTemp.text = if (t.isNaN()) "" else Fmt.num(t, "°")
                    weatherText.text = cond.replace('-', ' ').replace("partlycloudy", "partly cloudy").replaceFirstChar { it.uppercase() }
                    val parts = ArrayList<String>()
                    val hum = a.optDouble("humidity", Double.NaN)
                    val wind = a.optDouble("wind_speed", Double.NaN)
                    if (!hum.isNaN()) parts.add("Humidity " + Fmt.num(hum, "%"))
                    if (!wind.isNaN()) parts.add("Wind " + Fmt.num(wind, a.optString("wind_speed_unit", "km/h")))
                    weatherSub.text = parts.joinToString("  ·  ")
                    if (fc != null) {
                        lastForecast = System.currentTimeMillis()
                        showForecast(fc)
                    }
                }
            }
        }
    }

    private fun showForecast(list: List<Forecast>) {
        forecastRow.removeAllViews()
        val dayFmt = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH).withZone(ZoneId.systemDefault())
        val today = ZonedDateTime.now().toLocalDate()
        val days = list.filter { Instant.ofEpochMilli(it.time).atZone(ZoneId.systemDefault()).toLocalDate() >= today }.take(5)
        for (f in days) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(10f), 0, dp(2f), 0)
            }
            col.addView(label(dayFmt.format(Instant.ofEpochMilli(f.time)), 10f, C.TEXT3), LinearLayout.LayoutParams(-2, -2))
            col.addView(ImageView(this).apply {
                setImageResource(weatherIcon(f.condition))
                setColorFilter(C.TEXT2)
            }, LinearLayout.LayoutParams(dp(16f), dp(16f)).apply { topMargin = dp(3f); bottomMargin = dp(3f) })
            val hi = if (f.hi.isNaN()) "–" else Fmt.num(Math.round(f.hi).toDouble(), "°")
            val lo = if (f.lo.isNaN()) "" else " " + Fmt.num(Math.round(f.lo).toDouble(), "°")
            col.addView(label(hi + lo, 10f, C.TEXT2).apply { maxLines = 1 }, LinearLayout.LayoutParams(-2, -2))
            forecastRow.addView(col)
        }
    }

    // ---------- actions ----------

    private fun onRowClick(r: DashRow) {
        val client = ha ?: return
        val item = r.item
        when (Actions.kind(item)) {
            Actions.Kind.TOGGLE -> act(r, null) { Actions.fire(client, item) }
            Actions.Kind.RUN -> act(r, "${item.title} ✓") { Actions.fire(client, item) }
            Actions.Kind.VACUUM -> Popup.menu(this, item.title, listOf("Start cleaning", "Return to dock", "Statistics")) { which ->
                when (which) {
                    0 -> act(r, "Cleaning started") { client.call("vacuum", "start", item.entity) }
                    1 -> act(r, "Returning to dock") { client.call("vacuum", "return_to_base", item.entity) }
                    else -> showStats(r)
                }
            }
            Actions.Kind.SELECT -> {
                val arr = r.state?.optJSONObject("attributes")?.optJSONArray("options")
                val options = ArrayList<String>()
                if (arr != null) for (i in 0 until arr.length()) options.add(arr.optString(i))
                if (options.isEmpty()) {
                    showStats(r)
                } else {
                    val current = options.indexOf(r.state?.optString("state") ?: "")
                    Popup.menu(this, item.title, options, current) { which ->
                        act(r, null) {
                            client.call(item.domain, "select_option", JSONObject().put("entity_id", item.entity).put("option", options[which]))
                        }
                    }
                }
            }
            Actions.Kind.NONE -> showStats(r)
        }
    }

    private fun act(r: DashRow, okMessage: String?, work: () -> Unit) {
        try {
            exec.execute {
                val ok = try { work(); true } catch (e: Exception) { false }
                main.post {
                    if (!ok) toast("Home Assistant did not accept that")
                    else if (okMessage != null) toast(okMessage)
                    main.postDelayed({ refreshRow(r) }, 500L)
                    main.postDelayed({ refreshRow(r) }, 2500L)
                }
            }
        } catch (e: Exception) { }
    }

    // ---------- statistics ----------

    private fun showStats(r: DashRow) {
        statsItem = r.item
        statsUnit = r.state?.optJSONObject("attributes")?.optString("unit_of_measurement", "") ?: ""
        statsTitle.text = r.item.title
        statsValue.text = r.state?.let { Fmt.value(r.item.entity, it) } ?: ""
        overlayDownSeen = false
        overlay.visibility = View.VISIBLE
        loadStats()
    }

    private fun hideStats() {
        overlay.visibility = View.GONE
        statsItem = null
        statsGen++
    }

    private fun loadStats() {
        val item = statsItem ?: return
        val client = ha ?: return
        val hours = ranges[statsRange]
        val g = ++statsGen
        tabs.forEachIndexed { i, t ->
            val on = i == statsRange
            t.background = if (on) rounded(C.FOCUS_FILL, dp(14f).toFloat()) else rounded(C.ROW, dp(14f).toFloat())
            t.setTextColor(if (on) C.DARK else C.TEXT2)
        }
        statsSummary.text = "Loading…"
        val end = System.currentTimeMillis()
        val start = end - hours * 3600_000L
        try {
            exec.execute {
                val data = try { client.history(item.entity, hours) } catch (e: Exception) { null }
                main.post { if (g == statsGen && overlay.visibility == View.VISIBLE) renderStats(data, start, end) }
            }
        } catch (e: Exception) { }
    }

    private fun renderStats(data: List<Pair<Long, String>>?, start: Long, end: Long) {
        if (data == null) {
            chart.visibility = View.INVISIBLE
            statsList.visibility = View.GONE
            statsSummary.text = "Couldn't load the history from Home Assistant"
            return
        }
        val numeric = ArrayList<Pair<Long, Float>>(data.size)
        for ((t, s) in data) {
            val v = s.toFloatOrNull()
            if (v != null && !v.isNaN()) numeric.add(Pair(t, v))
        }
        if (numeric.isNotEmpty() && numeric.size * 2 >= data.size) {
            chart.setData(numeric, start, end, statsUnit)
            chart.visibility = View.VISIBLE
            statsList.visibility = View.GONE
            statsSummary.text = if (chart.min.isNaN()) "No data in this period"
            else "Min " + Fmt.num(chart.min.toDouble(), statsUnit) + "   ·   Avg " + Fmt.num(chart.avg.toDouble(), statsUnit) +
                "   ·   Max " + Fmt.num(chart.max.toDouble(), statsUnit)
        } else {
            val fmt = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault())
            val lines = data.asReversed().take(12).map { (t, s) ->
                fmt.format(Instant.ofEpochMilli(t)) + "     " + s.replace('_', ' ').replaceFirstChar { it.uppercase() }
            }
            chart.visibility = View.INVISIBLE
            statsList.visibility = View.VISIBLE
            statsList.text = if (lines.isEmpty()) "No changes in this period" else lines.joinToString("\n")
            statsSummary.text = if (data.isEmpty()) "" else "${data.size} changes"
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (overlay.visibility == View.VISIBLE) {
            val down = event.action == KeyEvent.ACTION_DOWN
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (down && statsRange > 0) { statsRange--; loadStats() }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down && statsRange < ranges.size - 1) { statsRange++; loadStats() }
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    // Ignore the release of the long-press that opened the panel.
                    if (down && event.repeatCount == 0) overlayDownSeen = true
                    else if (!down && overlayDownSeen) hideStats()
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

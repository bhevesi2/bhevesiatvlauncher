package hu.otthon.launcher

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The Home Assistant overview: a wide panel of tiles in three columns, one block per section.
 * OK switches what can be switched, and opens the statistics of everything else.
 */
class DashboardActivity : PanelActivity() {

    private lateinit var cfg: Config
    private var ha: HaClient? = null
    private val exec = Executors.newFixedThreadPool(4)
    private val historyExec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var active = false
    private var gen = 0

    private lateinit var backdrop: Backdrop
    private lateinit var panel: LinearLayout
    private lateinit var clock: TextView
    private lateinit var weatherBox: LinearLayout
    private lateinit var weatherIconView: ImageView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherText: TextView
    private lateinit var weatherSub: TextView
    private lateinit var forecastRow: LinearLayout
    private lateinit var message: TextView
    private val columns = ArrayList<LinearLayout>()
    private val tiles = ArrayList<DashTile>()
    private val sparkPending = HashSet<String>()
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
    private val clockFmt = DateTimeFormatter.ofPattern("EEEE, MMMM d   ·   HH:mm", Locale.ENGLISH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        backdrop = Backdrop(this)
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C.PANEL, dp(20f).toFloat(), 1, C.BORDER)
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        FocusGlide.install(panel)

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        header.addView(label("Home", 17f, C.TEXT, bold = true))
        header.addView(
            label("OK: switch or statistics   ·   Hold OK: statistics", 10f, C.TEXT3).apply { setPadding(dp(14f), 0, 0, dp(1f)) },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        clock = label("", 12f, C.TEXT2)
        header.addView(clock)
        panel.addView(header, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(22f)
            rightMargin = dp(22f)
            topMargin = dp(18f)
        })

        message = label("", 12f, C.TEXT2).apply { setPadding(dp(22f), dp(20f), dp(22f), 0); visibility = View.GONE }
        panel.addView(message)

        val scroll = ColScroller(this).apply {
            clipToPadding = false
            setPadding(dp(22f), dp(12f), dp(22f), dp(20f))
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until 3) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            columns.add(col)
            body.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(16f) })
        }
        scroll.addView(body, FrameLayout.LayoutParams(-1, -2))
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(panel, FrameLayout.LayoutParams(-1, -1).apply {
            leftMargin = dp(30f)
            rightMargin = dp(30f)
            topMargin = dp(26f)
            bottomMargin = dp(26f)
        })

        buildWeather()
        buildOverlay()
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        animateIn(panel)
    }

    /** Weather card: now, and the next five days. It leads the first column when a source is set. */
    private fun buildWeather() {
        weatherBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            background = rounded(0x14FFFFFF, dp(10f).toFloat())
            visibility = View.GONE
        }
        val nowRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        weatherIconView = ImageView(this).apply { setColorFilter(C.TEXT) }
        nowRow.addView(weatherIconView, LinearLayout.LayoutParams(dp(34f), dp(34f)))
        weatherTemp = label("", 30f, C.TEXT, light = true).apply { setPadding(dp(10f), 0, dp(12f), 0) }
        nowRow.addView(weatherTemp)
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        weatherText = label("", 13f, C.TEXT, bold = true).apply { maxLines = 1 }
        weatherSub = label("", 10f, C.TEXT3).apply { maxLines = 1; setPadding(0, dp(3f), 0, 0) }
        words.addView(weatherText, LinearLayout.LayoutParams(-2, -2))
        words.addView(weatherSub, LinearLayout.LayoutParams(-2, -2))
        nowRow.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        weatherBox.addView(nowRow, LinearLayout.LayoutParams(-1, -2))
        forecastRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        weatherBox.addView(forecastRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })
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
        clock.text = ZonedDateTime.now().format(clockFmt)
        backdrop.setDim(cfg.dim)
        backdrop.show(cfg.background, Backgrounds.phase(this, cfg), false)
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
                // wait for the panel to arrive: building forty tiles in the middle of it would stutter
                afterEnter {
                    if (active && g == gen) {
                        val visible = cfg.sections.filter { it.items.isNotEmpty() && (it.hideWhen == null || map[it.hideWhen] != "on") }
                        val sig = visible.toString() + cfg.weather
                        if (sig != builtFor) {
                            builtFor = sig
                            build(visible)
                        }
                        tick(g)
                    }
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
        historyExec.shutdownNow()
    }

    private fun showMessage(text: String) {
        columns.forEach { it.removeAllViews() }
        tiles.clear()
        builtFor = null
        message.text = text
        message.visibility = View.VISIBLE
    }

    /** Lays the sections out as blocks, each into the column that is shortest so far. */
    private fun build(visible: List<DashSection>) {
        columns.forEach { it.removeAllViews() }
        tiles.clear()
        val heights = FloatArray(columns.size)
        (weatherBox.parent as? ViewGroup)?.removeView(weatherBox)
        if (cfg.weather != null) {
            columns[0].addView(weatherBox, LinearLayout.LayoutParams(-1, -2))
            heights[0] += 2.6f
        }
        for (sec in visible) {
            var col = 0
            for (i in heights.indices) if (heights[i] < heights[col]) col = i
            val column = columns[col]
            column.addView(label(sec.title, 11f, C.TEXT2, bold = true), LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = if (column.childCount > 0) dp(15f) else dp(2f)
                leftMargin = dp(2f)
            })
            var used = 0.5f
            var row: LinearLayout? = null
            sec.items.forEachIndexed { i, item ->
                val hero = i == 0 && DashTile.canBeHero(item)
                val tile = DashTile(this, item, hero)
                tile.setOnClickListener { onTileClick(tile) }
                tile.setOnLongClickListener { showStats(tile); true }
                tiles.add(tile)
                if (hero) {
                    column.addView(tile, LinearLayout.LayoutParams(-1, dp(104f)).apply { topMargin = dp(7f) })
                    used += 1.9f
                } else {
                    var r = row
                    if (r == null) {
                        r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                        column.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7f) })
                        used += 1f
                        r.addView(tile, LinearLayout.LayoutParams(0, dp(54f), 1f))
                        row = r
                    } else {
                        r.addView(tile, LinearLayout.LayoutParams(0, dp(54f), 1f).apply { leftMargin = dp(7f) })
                        row = null
                    }
                }
            }
            // an odd last tile keeps its half width
            row?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(7f) })
            heights[col] += used
        }
        tiles.firstOrNull()?.requestFocus()
    }

    // ---------- live data ----------

    private fun tick(g: Int) {
        if (!active || g != gen) return
        clock.text = ZonedDateTime.now().format(clockFmt)
        for (t in tiles.toList()) refreshTile(t)
        refreshWeather()
        main.postDelayed({ tick(g) }, 10_000L)
    }

    private fun refreshTile(t: DashTile) {
        val client = ha ?: return
        if (!active) return
        try {
            exec.execute {
                val s = try { client.state(t.item.entity) } catch (e: Exception) { null }
                main.post {
                    if (active) {
                        t.bind(s)
                        if (t.wantsSpark) loadSpark(t)
                    }
                }
            }
        } catch (e: Exception) { }
    }

    /** The last 24 hours of a number as a few dozen averages. Kept for ten minutes. */
    private fun loadSpark(t: DashTile) {
        val client = ha ?: return
        val entity = t.item.entity
        val now = System.currentTimeMillis()
        val cached = sparkCache[entity]
        if (cached != null) t.setSpark(cached.second)
        if (cached != null && now - cached.first < 10 * 60_000L) return
        if (!sparkPending.add(entity)) return
        try {
            historyExec.execute {
                val data = try { client.history(entity, 24) } catch (e: Exception) { null }
                val curve = if (data == null) null else averages(data, now - 24 * 3600_000L, now, 48)
                main.post {
                    sparkPending.remove(entity)
                    if (curve != null) {
                        sparkCache[entity] = Pair(now, curve)
                        tiles.firstOrNull { it.item.entity == entity }?.setSpark(curve)
                    } else if (data != null) {
                        // nothing numeric in the history: do not ask again for a while
                        sparkCache[entity] = Pair(now, FloatArray(0))
                    }
                }
            }
        } catch (e: Exception) {
            sparkPending.remove(entity)
        }
    }

    private fun averages(data: List<Pair<Long, String>>, start: Long, end: Long, n: Int): FloatArray? {
        val sum = FloatArray(n)
        val count = IntArray(n)
        var any = false
        for ((time, s) in data) {
            val v = s.toFloatOrNull() ?: continue
            if (v.isNaN() || v.isInfinite()) continue
            val i = ((time - start).toDouble() / (end - start) * n).toInt().coerceIn(0, n - 1)
            sum[i] += v
            count[i]++
            any = true
        }
        if (!any) return null
        val out = FloatArray(n)
        var last = Float.NaN
        for (i in 0 until n) {
            if (count[i] > 0) last = sum[i] / count[i]
            out[i] = last
        }
        // nothing known before the first reading: start the curve flat at it
        val first = out.first { !it.isNaN() }
        for (i in 0 until n) {
            if (out[i].isNaN()) out[i] = first else break
        }
        return out
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
                    weatherText.text = cond.replace("partlycloudy", "partly cloudy").replace('-', ' ').replaceFirstChar { it.uppercase() }
                    val parts = ArrayList<String>()
                    val hum = a.optDouble("humidity", Double.NaN)
                    val wind = a.optDouble("wind_speed", Double.NaN)
                    if (!hum.isNaN()) parts.add("Humidity " + Fmt.num(hum, "%"))
                    if (!wind.isNaN()) parts.add("Wind " + Fmt.num(Math.round(wind).toDouble(), a.optString("wind_speed_unit", "km/h")))
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
            }
            col.addView(label(dayFmt.format(Instant.ofEpochMilli(f.time)), 10f, C.TEXT3), LinearLayout.LayoutParams(-2, -2))
            col.addView(ImageView(this).apply {
                setImageResource(weatherIcon(f.condition))
                setColorFilter(C.TEXT2)
            }, LinearLayout.LayoutParams(dp(16f), dp(16f)).apply { topMargin = dp(3f); bottomMargin = dp(3f) })
            val hi = if (f.hi.isNaN()) "–" else Fmt.num(Math.round(f.hi).toDouble(), "°")
            val lo = if (f.lo.isNaN()) "" else " " + Fmt.num(Math.round(f.lo).toDouble(), "°")
            col.addView(label(hi + lo, 10f, C.TEXT2).apply { maxLines = 1 }, LinearLayout.LayoutParams(-2, -2))
            forecastRow.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    // ---------- actions ----------

    private fun onTileClick(r: DashTile) {
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

    private fun act(r: DashTile, okMessage: String?, work: () -> Unit) {
        try {
            exec.execute {
                val ok = try { work(); true } catch (e: Exception) { false }
                main.post {
                    if (!ok) toast("Home Assistant did not accept that")
                    else if (okMessage != null) toast(okMessage)
                    main.postDelayed({ refreshTile(r) }, 500L)
                    main.postDelayed({ refreshTile(r) }, 2500L)
                }
            }
        } catch (e: Exception) { }
    }

    // ---------- statistics ----------

    private fun showStats(r: DashTile) {
        statsItem = r.item
        statsUnit = r.state?.optJSONObject("attributes")?.optString("unit_of_measurement", "") ?: ""
        statsTitle.text = r.item.title
        statsValue.text = r.state?.let { Fmt.value(r.item.entity, it) } ?: ""
        overlayDownSeen = false
        overlay.visibility = View.VISIBLE
        val box = overlay.getChildAt(0)
        if (box != null && Motion.level != Motion.OFF) {
            box.alpha = 0f
            box.animate().alpha(1f).setDuration(160).withLayer().start()
        }
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

    companion object {
        /** Curves survive closing and reopening the dashboard. */
        private val sparkCache = HashMap<String, Pair<Long, FloatArray>>()
    }
}

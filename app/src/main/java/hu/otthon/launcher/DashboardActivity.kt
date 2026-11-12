package hu.otthon.launcher

import android.graphics.Bitmap
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
import android.text.TextUtils
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
    private val cells = ArrayList<DashCell>()
    private val cars = ArrayList<CarCard>()
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
            label("OK: switch, or statistics   ·   Hold OK: statistics", 10f, C.TEXT3).apply { setPadding(dp(14f), 0, 0, dp(1f)) },
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
        nowRow.addView(weatherIconView, LinearLayout.LayoutParams(dp(32f), dp(32f)))
        weatherTemp = label("", 28f, C.TEXT, light = true).apply { setPadding(dp(10f), 0, dp(12f), 0) }
        nowRow.addView(weatherTemp)
        weatherText = label("", 13f, C.TEXT, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        nowRow.addView(weatherText, LinearLayout.LayoutParams(0, -2, 1f))
        weatherBox.addView(nowRow, LinearLayout.LayoutParams(-1, -2))
        weatherSub = label("", 10f, C.TEXT3).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        weatherBox.addView(weatherSub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5f) })
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
        cells.clear()
        cars.clear()
        builtFor = null
        message.text = text
        message.visibility = View.VISIBLE
    }

    /**
     * Lays the dashboard out. Everything that can be switched goes into one "Switches" block
     * at the top of the last column; what is left of each section becomes a block of tiles in
     * whichever column is shortest so far. Cars get a card with their picture.
     */
    private fun build(visible: List<DashSection>) {
        columns.forEach { it.removeAllViews() }
        tiles.clear()
        cells.clear()
        cars.clear()
        val heights = FloatArray(columns.size)
        (weatherBox.parent as? ViewGroup)?.removeView(weatherBox)
        if (cfg.weather != null) {
            columns[0].addView(weatherBox, LinearLayout.LayoutParams(-1, -2))
            heights[0] += 2.5f
        }

        fun title(column: LinearLayout, text: String) {
            column.addView(label(text, 11f, C.TEXT2, bold = true), LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = if (column.childCount > 0) dp(15f) else dp(2f)
                leftMargin = dp(2f)
            })
        }

        fun isSwitch(item: HaItem) = when (Actions.kind(item)) {
            Actions.Kind.TOGGLE, Actions.Kind.RUN, Actions.Kind.SELECT -> true
            else -> false
        }

        // switches first, pinned to the top of the last column
        val last = columns.size - 1
        if (visible.any { sec -> sec.items.any { isSwitch(it) } }) {
            val column = columns[last]
            title(column, "Switches")
            var used = 0.5f
            for (sec in visible) {
                val switches = sec.items.filter { isSwitch(it) }
                if (switches.isEmpty()) continue
                column.addView(label(sec.title, 10f, C.TEXT3), LinearLayout.LayoutParams(-2, -2).apply {
                    topMargin = dp(8f)
                    leftMargin = dp(2f)
                })
                used += 0.35f
                for (item in switches) {
                    val tile = DashTile(this, item, hero = false, control = true)
                    tile.setOnClickListener { onTileClick(tile) }
                    tile.setOnLongClickListener { showStats(tile.item, tile.state); true }
                    tiles.add(tile)
                    cells.add(tile)
                    column.addView(tile, LinearLayout.LayoutParams(-1, dp(46f)).apply { topMargin = dp(6f) })
                    used += 0.85f
                }
            }
            heights[last] += used
        }

        for (sec in visible) {
            val status = sec.items.filter { !isSwitch(it) }
            if (status.isEmpty()) continue
            var col = 0
            for (i in heights.indices) if (heights[i] < heights[col]) col = i
            val column = columns[col]
            title(column, sec.title)
            var used = 0.5f
            var row: LinearLayout? = null
            var i = 0
            var first = true
            while (i < status.size) {
                val item = status[i]
                if (CarCard.isCar(item, sec.title)) {
                    // the readings up to the next car belong to this one
                    var end = i + 1
                    while (end < status.size && status[end].domain != "device_tracker") end++
                    val card = CarCard(this, item, status.subList(i + 1, end).toList())
                    card.setOnClickListener { onCarClick(card) }
                    card.setOnLongClickListener { showStats(card.tracker, card.stateOf(card.tracker)); true }
                    cells.add(card)
                    cars.add(card)
                    row?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(7f) })
                    row = null
                    column.addView(card, LinearLayout.LayoutParams(-1, dp(100f)).apply { topMargin = dp(7f) })
                    used += 1.75f
                    i = end
                    first = false
                    continue
                }
                val hero = first && DashTile.canBeHero(item)
                val tile = DashTile(this, item, hero)
                tile.setOnClickListener { onTileClick(tile) }
                tile.setOnLongClickListener { showStats(tile.item, tile.state); true }
                tiles.add(tile)
                cells.add(tile)
                if (hero) {
                    column.addView(tile, LinearLayout.LayoutParams(-1, dp(104f)).apply { topMargin = dp(7f) })
                    used += 1.8f
                } else {
                    val r = row
                    if (r == null) {
                        val fresh = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                        column.addView(fresh, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7f) })
                        used += 1.05f
                        fresh.addView(tile, LinearLayout.LayoutParams(0, dp(58f), 1f))
                        row = fresh
                    } else {
                        r.addView(tile, LinearLayout.LayoutParams(0, dp(58f), 1f).apply { leftMargin = dp(7f) })
                        row = null
                    }
                }
                first = false
                i++
            }
            // an odd last tile keeps its half width
            row?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(7f) })
            heights[col] += used
        }
        for (card in cars) loadCarPicture(card)
        (cells.firstOrNull() as? View)?.requestFocus()
    }

    // ---------- live data ----------

    private fun tick(g: Int) {
        if (!active || g != gen) return
        clock.text = ZonedDateTime.now().format(clockFmt)
        for (cell in cells.toList()) refresh(cell)
        refreshWeather()
        main.postDelayed({ tick(g) }, 10_000L)
    }

    private fun refresh(cell: DashCell) {
        val client = ha ?: return
        if (!active) return
        for (item in cell.items) {
            try {
                exec.execute {
                    val s = try { client.state(item.entity) } catch (e: Exception) { null }
                    main.post {
                        if (active) {
                            cell.bind(item, s)
                            if (cell is DashTile && cell.wantsSpark) loadSpark(cell)
                        }
                    }
                }
            } catch (e: Exception) { }
        }
    }

    /**
     * A car's picture: the one chosen in the settings, else what Home Assistant offers for the
     * tracker (its entity picture, or an image entity named after the car). Kept for six hours.
     */
    private fun loadCarPicture(card: CarCard) {
        val client = ha ?: return
        val key = card.tracker.entity + "|" + (card.tracker.picture ?: "")
        val now = System.currentTimeMillis()
        val cached = carPictures[key]
        if (cached != null) card.setPicture(cached.second)
        if (cached != null && now - cached.first < 6 * 3600_000L) return
        try {
            exec.execute {
                val a = try { client.state(card.tracker.entity).optJSONObject("attributes") } catch (e: Exception) { null }
                val candidates = ArrayList<String>()
                card.tracker.picture?.let { candidates.add(it) }
                a?.optString("entity_picture", "")?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
                val vin = a?.optJSONObject("vehicle_basic_data")?.optString("vin", "")?.ifBlank { null } ?: a?.optString("vin", "")
                if (!vin.isNullOrBlank()) candidates.add("image." + vin.lowercase())
                candidates.add("image." + card.tracker.entity.substringAfter('.'))
                var found: Bitmap? = null
                for (c in candidates) {
                    val path = when {
                        c.startsWith("http") || c.startsWith("/") -> c
                        c.startsWith("camera.") -> "/api/camera_proxy/$c"
                        c.startsWith("image.") -> "/api/image_proxy/$c"
                        else -> null
                    } ?: continue
                    found = try { client.bitmap(path, 520, 15000) } catch (e: Exception) { null }
                    if (found != null) break
                }
                val picture = found?.let { trimmed(it) }
                main.post {
                    // nothing found: look again in five minutes instead of six hours
                    carPictures[key] = Pair(if (picture != null) now else now - 6 * 3600_000L + 5 * 60_000L, picture)
                    if (active) card.setPicture(picture)
                }
            }
        } catch (e: Exception) { }
    }

    /** Cuts the empty margin off a cut-out picture, so the car fills its place. */
    private fun trimmed(b: Bitmap): Bitmap {
        if (!b.hasAlpha()) return b
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((px[row + x] ushr 24) > 24) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right - left < 8 || bottom - top < 8) return b
        if (left == 0 && top == 0 && right == w - 1 && bottom == h - 1) return b
        return try { Bitmap.createBitmap(b, left, top, right - left + 1, bottom - top + 1) } catch (e: Exception) { b }
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
                    else -> showStats(r.item, r.state)
                }
            }
            Actions.Kind.SELECT -> {
                val arr = r.state?.optJSONObject("attributes")?.optJSONArray("options")
                val options = ArrayList<String>()
                if (arr != null) for (i in 0 until arr.length()) options.add(arr.optString(i))
                if (options.isEmpty()) {
                    showStats(r.item, r.state)
                } else {
                    val current = options.indexOf(r.state?.optString("state") ?: "")
                    Popup.menu(this, item.title, options, current) { which ->
                        act(r, null) {
                            client.call(item.domain, "select_option", JSONObject().put("entity_id", item.entity).put("option", options[which]))
                        }
                    }
                }
            }
            Actions.Kind.NONE -> showStats(r.item, r.state)
        }
    }

    /** A car has several readings: OK lists them, and picking one opens its statistics. */
    private fun onCarClick(card: CarCard) {
        if (card.items.size == 1) {
            showStats(card.tracker, card.stateOf(card.tracker))
            return
        }
        val names = card.items.mapIndexed { i, item -> if (i == 0) "Location" else item.title }
        Popup.menu(this, card.tracker.title, names) { which ->
            val item = card.items[which]
            showStats(item, card.stateOf(item))
        }
    }

    private fun act(r: DashTile, okMessage: String?, work: () -> Unit) {
        try {
            exec.execute {
                val ok = try { work(); true } catch (e: Exception) { false }
                main.post {
                    if (!ok) toast("Home Assistant did not accept that")
                    else if (okMessage != null) toast(okMessage)
                    main.postDelayed({ refresh(r) }, 500L)
                    main.postDelayed({ refresh(r) }, 2500L)
                }
            }
        } catch (e: Exception) { }
    }

    // ---------- statistics ----------

    private fun showStats(item: HaItem, state: JSONObject?) {
        statsItem = item
        statsUnit = state?.optJSONObject("attributes")?.optString("unit_of_measurement", "") ?: ""
        statsTitle.text = item.title
        statsValue.text = state?.let { Fmt.value(item.entity, it) } ?: ""
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
        private val carPictures = HashMap<String, Pair<Long, Bitmap?>>()
    }
}

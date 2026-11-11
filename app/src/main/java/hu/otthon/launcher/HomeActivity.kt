package hu.otthon.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class HomeActivity : Activity() {

    private lateinit var cfg: Config
    private var ha: HaClient? = null
    private var signature = ""

    private lateinit var backdrop: Backdrop
    private lateinit var content: LinearLayout
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var chipsRow: LinearLayout
    private lateinit var favScroll: RowScroller
    private lateinit var favRow: LinearLayout
    private lateinit var bottom: LinearLayout

    private lateinit var npCard: LinearLayout
    private lateinit var npCover: ImageView
    private lateinit var npLabel: TextView
    private lateinit var npTitle: TextView
    private lateinit var npArtist: TextView
    private lateinit var npProgress: View
    private lateinit var npProgressTrack: FrameLayout
    private lateinit var npButton: ImageView
    private var npPicture: String? = null

    private val chipViews = mutableListOf<Pair<HaItem, TextView>>()
    private val camTiles = mutableListOf<CamTile>()
    private var resumed = false
    private var shiftIndex = 0

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val dateFmt = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.ENGLISH)

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = onMinute()
    }

    private val statusLoop = object : Runnable {
        override fun run() {
            refreshStatus()
            Bg.main.postDelayed(this, 20_000)
        }
    }
    private val npLoop = object : Runnable {
        override fun run() {
            refreshNowPlaying()
            Bg.main.postDelayed(this, 5_000)
        }
    }
    private val camLoop = object : Runnable {
        override fun run() {
            refreshCams()
            Bg.main.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    // ---------- UI ----------

    private fun buildUi() {
        val side = dp(48f)
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        backdrop = Backdrop(this)
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(34f), 0, dp(30f))
            clipChildren = false
            clipToPadding = false
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        // top row
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        val clockCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clock = label("--:--", 62f, C.TEXT, light = true).apply { letterSpacing = -0.02f }
        date = label("", 15f, C.TEXT2).apply { setPadding(dp(2f), dp(6f), 0, 0) }
        clockCol.addView(clock)
        clockCol.addView(date)
        top.addView(clockCol)
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(chipsRow)
        val gear = ImageView(this).apply {
            setImageResource(R.drawable.ic_gear)
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            isFocusable = true
            isClickable = true
            contentDescription = "Launcher settings"
            fun paint(f: Boolean) {
                background = if (f) rounded(C.FOCUS_FILL, dp(20f).toFloat()) else rounded(C.GLASS_SOFT, dp(20f).toFloat(), 1, C.BORDER)
                setColorFilter(if (f) C.DARK else C.TEXT)
            }
            paint(false)
            setOnFocusChangeListener { _, f -> paint(f) }
            setOnClickListener { startActivity(Intent(this@HomeActivity, SettingsActivity::class.java)) }
        }
        top.addView(gear, LinearLayout.LayoutParams(dp(30f), dp(30f)).apply { leftMargin = dp(7f) })
        content.addView(top, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = side; rightMargin = side })

        buildNowPlaying()
        content.addView(npCard, LinearLayout.LayoutParams(dp(320f), -2).apply { leftMargin = side; topMargin = dp(24f) })

        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        content.addView(label("Favorites", 11f, C.TEXT2, bold = true), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = side })
        favScroll = RowScroller(this)
        favScroll.setPadding(side, dp(9f), side, dp(9f))
        favRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        favScroll.addView(favRow, FrameLayout.LayoutParams(-2, dp(76f)))
        content.addView(favScroll, LinearLayout.LayoutParams(-1, -2))

        bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            clipChildren = false
        }
        content.addView(bottom, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = side
            rightMargin = side
            topMargin = dp(9f)
        })

        setContentView(root)
    }

    private fun buildNowPlaying() {
        npCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9f), dp(9f), dp(11f), dp(9f))
            background = rounded(C.GLASS, dp(9f).toFloat(), 1, C.BORDER)
            visibility = View.GONE
        }
        npCover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(0xFF3C3489.toInt(), dp(5f).toFloat())
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        npCard.addView(npCover, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11f), 0, dp(11f), 0) }
        npLabel = label("", 10f, C.TEXT3)
        npTitle = label("", 14f, C.TEXT, bold = true).apply { maxLines = 1; setPadding(0, dp(2f), 0, 0) }
        npArtist = label("", 11f, C.TEXT2).apply { maxLines = 1; setPadding(0, dp(1f), 0, 0) }
        col.addView(npLabel); col.addView(npTitle); col.addView(npArtist)
        npProgressTrack = FrameLayout(this).apply { background = rounded(0x2EFFFFFF, dp(1f).toFloat()) }
        npProgress = View(this).apply { background = rounded(C.TEXT, dp(1f).toFloat()) }
        npProgressTrack.addView(npProgress, FrameLayout.LayoutParams(0, dp(2f)))
        col.addView(npProgressTrack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2f)).apply { topMargin = dp(6f) })
        npCard.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        npButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_pause)
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            isFocusable = true
            isClickable = true
            contentDescription = "Play / pause"
            fun paint(f: Boolean) {
                background = if (f) rounded(C.FOCUS_FILL, dp(16f).toFloat()) else rounded(0x33FFFFFF, dp(16f).toFloat())
                setColorFilter(if (f) C.DARK else C.TEXT)
            }
            paint(false)
            setOnFocusChangeListener { _, f -> paint(f) }
            setOnClickListener { togglePlay() }
        }
        npCard.addView(npButton, LinearLayout.LayoutParams(dp(32f), dp(32f)))
    }

    private fun pillRow(parent: LinearLayout, title: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        row.addView(label(title, 11f, C.TEXT2, bold = true), LinearLayout.LayoutParams(dp(50f), -2))
        val lp = LinearLayout.LayoutParams(-2, -2)
        if (parent.childCount > 0) lp.topMargin = dp(7f)
        parent.addView(row, lp)
        return row
    }

    private fun addPill(row: LinearLayout, v: View) {
        val lp = LinearLayout.LayoutParams(-2, -2)
        if (row.childCount > 1) lp.leftMargin = dp(6f)
        row.addView(v, lp)
    }

    private fun rebuildDynamic(focusFav: Int = 0) {
        // favourites: fixed-size tiles in a scrolling row
        favRow.removeAllViews()
        val tileW = (resources.displayMetrics.widthPixels - 2 * dp(48f) - 5 * dp(12f)) / 6
        val entries = FavStore.get(this, cfg).mapNotNull { Apps.one(this, it) }
        entries.forEachIndexed { i, app ->
            val tile = AppTile(this, app)
            tile.setOnClickListener { if (!Apps.launch(this, app.pkg)) toast("Can't open ${app.label}") }
            tile.setOnLongClickListener { favMenu(app); true }
            val lp = LinearLayout.LayoutParams(tileW, ViewGroup.LayoutParams.MATCH_PARENT)
            if (i > 0) lp.leftMargin = dp(12f)
            favRow.addView(tile, lp)
        }
        if (entries.isEmpty()) {
            favRow.addView(pill("Add favorites", R.drawable.ic_plus) {
                startActivity(Intent(this, AllAppsActivity::class.java))
            }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_VERTICAL })
        }

        // bottom block: labelled rows on the left, cameras on the right
        bottom.removeAllViews()
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }

        val apps = pillRow(rows, "Apps")
        addPill(apps, pill("All apps", R.drawable.ic_grid) { startActivity(Intent(this, AllAppsActivity::class.java)) })
        if (Apps.stockHomeIntent(this) != null) {
            addPill(apps, pill("Google TV home", R.drawable.ic_home) {
                if (!Apps.openStockHome(this)) toast("Google TV home is not available")
            })
        }
        addPill(apps, pill("TV settings", R.drawable.ic_gear) {
            if (!Apps.openSystemSettings(this)) toast("Android settings are not available")
        })

        if (cfg.haEnabled) {
            val home = pillRow(rows, "Home")
            addPill(home, pill("Dashboard", R.drawable.ic_dashboard) { startActivity(Intent(this, DashboardActivity::class.java)) })
            cfg.scenes.take(5).forEach { s -> addPill(home, pill(s.title, sceneIcon(s)) { runScene(s) }) }
        }

        val inputs = Inputs.visible(this, cfg)
        val hasTv = Inputs.hasTv(this)
        if (hasTv || inputs.isNotEmpty()) {
            val row = pillRow(rows, "Inputs")
            if (hasTv) addPill(row, pill("TV", R.drawable.ic_tv) { if (!Inputs.openTv(this)) toast("TV is not available") })
            inputs.take(6).forEach { inp ->
                addPill(row, pill(inp.name, null) { if (!Inputs.open(this, inp.id)) toast("Can't switch to ${inp.name}") })
            }
        }
        bottom.addView(rows, LinearLayout.LayoutParams(0, -2, 1f))

        camTiles.clear()
        if (cfg.haEnabled && cfg.cameras.isNotEmpty()) {
            val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
            g.addView(label("Cameras", 11f, C.TEXT2, bold = true), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(7f) })
            var line: LinearLayout? = null
            cfg.cameras.take(4).forEachIndexed { i, cam ->
                if (i % 2 == 0) {
                    val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
                    line = l
                    val lp = LinearLayout.LayoutParams(-2, -2)
                    if (i > 0) lp.topMargin = dp(6f)
                    g.addView(l, lp)
                }
                val t = CamTile(this, cam, dp(6f).toFloat(), 8f, 1.08f)
                t.setOnClickListener {
                    startActivity(Intent(this, CamerasActivity::class.java).putExtra("index", i))
                }
                val lp = LinearLayout.LayoutParams(dp(88f), dp(50f))
                if (i % 2 == 1) lp.leftMargin = dp(6f)
                line!!.addView(t, lp)
                camTiles.add(t)
            }
            bottom.addView(g, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(24f) })
        }

        // status chips
        chipsRow.removeAllViews()
        chipViews.clear()
        if (cfg.haEnabled) {
            cfg.status.take(4).forEachIndexed { i, s ->
                val v = chip(statusIcon(s), if (s.label.isBlank()) "–" else "${s.label} –")
                chipsRow.addView(v, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = dp(7f) })
                chipViews.add(s to v)
            }
        }
        if (!cfg.haEnabled || cfg.nowPlaying == null) npCard.visibility = View.GONE

        val idx = focusFav.coerceIn(0, maxOf(0, favRow.childCount - 1))
        favRow.getChildAt(idx)?.takeIf { it.isFocusable }?.requestFocus()
    }

    private fun statusIcon(item: HaItem): Int {
        val e = item.entity
        return when {
            item.icon == "cloud" || item.domain == "weather" || e.contains("outdoor") || e.contains("garden") || e.contains("outside") -> R.drawable.ic_cloud
            item.icon == "lock" || item.domain == "lock" || e.contains("door") || e.contains("gate") || e.contains("security") -> R.drawable.ic_lock
            item.icon == "bolt" || e.contains("power") || e.contains("energy") || e.contains("electric") -> R.drawable.ic_bolt
            item.icon == "music" || item.domain == "media_player" -> R.drawable.ic_music
            else -> R.drawable.ic_home
        }
    }

    private fun sceneIcon(item: HaItem): Int {
        val e = item.entity + " " + item.label.lowercase()
        return when {
            item.icon == "film" || e.contains("movie") || e.contains("cinema") -> R.drawable.ic_film
            item.icon == "music" || e.contains("music") || e.contains("hifi") -> R.drawable.ic_music
            item.icon == "lock" || e.contains("lock") || e.contains("security") -> R.drawable.ic_lock
            item.icon == "bulb" || item.domain == "light" || item.domain == "scene" -> R.drawable.ic_bulb
            else -> R.drawable.ic_bolt
        }
    }

    // ---------- lifecycle ----------

    private fun reload(focusFav: Int = 0) {
        cfg = Config.load(this)
        ha = if (cfg.haEnabled) HaClient(cfg.haUrl!!, cfg.haToken!!) else null
        backdrop.setDim(cfg.dim)
        val sig = cfg.toString() + FavStore.get(this, cfg).joinToString() + Inputs.all(this).size
        if (sig != signature) {
            signature = sig
            rebuildDynamic(focusFav)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        reload()
        registerReceiver(tick, IntentFilter(Intent.ACTION_TIME_TICK))
        onMinute()
        if (ha != null) {
            Bg.main.post(statusLoop)
            if (cfg.nowPlaying != null) Bg.main.post(npLoop)
            if (camTiles.isNotEmpty()) Bg.main.post(camLoop)
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        try { unregisterReceiver(tick) } catch (e: Exception) { }
        Bg.main.removeCallbacks(statusLoop)
        Bg.main.removeCallbacks(npLoop)
        Bg.main.removeCallbacks(camLoop)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        favRow.getChildAt(0)?.takeIf { it.isFocusable }?.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Launcher: Back does nothing on the home screen.
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            (currentFocus as? AppTile)?.let { favMenu(it.app); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- clock, background, OLED care ----------

    private fun onMinute() {
        val now = ZonedDateTime.now()
        clock.text = now.format(timeFmt)
        date.text = now.format(dateFmt)
        backdrop.show(cfg.background, Backgrounds.phase(this, cfg), true)
        // OLED: shift static content by a few pixels every minute
        val shifts = arrayOf(0 to 0, 2 to 1, -1 to 2, -2 to -1, 1 to -2, 2 to -1, -1 to -1)
        shiftIndex = (shiftIndex + 1) % shifts.size
        val (sx, sy) = shifts[shiftIndex]
        content.animate().translationX(dp(sx.toFloat()).toFloat()).translationY(dp(sy.toFloat()).toFloat()).setDuration(1500).start()
    }

    // ---------- Home Assistant ----------

    private fun refreshStatus() {
        val client = ha ?: return
        for ((item, view) in chipViews.toList()) {
            Bg.run({ client.state(item.entity) }) { s ->
                if (s != null && resumed) view.text = Fmt.chip(item, s)
            }
        }
    }

    private fun refreshCams() {
        val client = ha ?: return
        for (t in camTiles.toList()) {
            Bg.run({ client.bitmap("/api/camera_proxy/" + t.item.entity, 320) }) { b ->
                if (b != null && resumed) t.image.setImageBitmap(b)
            }
        }
    }

    private fun refreshNowPlaying() {
        val client = ha ?: return
        val entity = cfg.nowPlaying ?: return
        Bg.run({ client.state(entity) }) { s ->
            if (s == null || !resumed) return@run
            val state = s.optString("state")
            val a = s.optJSONObject("attributes")
            val active = (state == "playing" || state == "paused") && a != null
            if (!active) {
                if (npCard.hasFocus()) favRow.getChildAt(0)?.requestFocus()
                npCard.visibility = View.GONE
                return@run
            }
            npCard.visibility = View.VISIBLE
            npLabel.text = cfg.nowPlayingLabel
            npTitle.text = a!!.optString("media_title", "")
            val artist = a.optString("media_artist", "")
            val album = a.optString("media_album_name", "")
            npArtist.text = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" — ")
            npButton.setImageResource(if (state == "playing") R.drawable.ic_pause else R.drawable.ic_play)
            val dur = a.optDouble("media_duration", 0.0)
            var pos = a.optDouble("media_position", 0.0)
            val upd = a.optString("media_position_updated_at", "")
            if (state == "playing" && upd.isNotBlank()) {
                try {
                    pos += (System.currentTimeMillis() - OffsetDateTime.parse(upd).toInstant().toEpochMilli()) / 1000.0
                } catch (e: Exception) { }
            }
            val frac = if (dur > 0) (pos / dur).coerceIn(0.0, 1.0) else 0.0
            npProgressTrack.post {
                npProgress.layoutParams = npProgress.layoutParams.apply { width = (npProgressTrack.width * frac).toInt() }
            }
            val pic = a.optString("entity_picture", "")
            if (pic.isNotBlank() && pic != npPicture) {
                npPicture = pic
                Bg.run({ client.bitmap(pic) }) { b -> if (b != null) npCover.setImageBitmap(b) }
            } else if (pic.isBlank()) {
                npPicture = null
                npCover.setImageResource(R.drawable.ic_disc)
                npCover.setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            }
            if (pic.isNotBlank()) npCover.setPadding(0, 0, 0, 0)
        }
    }

    private fun togglePlay() {
        val client = ha ?: return
        val entity = cfg.nowPlaying ?: return
        Bg.run({ client.call("media_player", "media_play_pause", entity) }) {
            Bg.main.postDelayed({ refreshNowPlaying() }, 600)
        }
    }

    private fun runScene(s: HaItem) {
        val client = ha ?: return
        Bg.run({ Actions.fire(client, s); true }) { ok ->
            toast(if (ok == true) "${s.title} ✓" else "Couldn't run ${s.title}")
        }
    }

    // ---------- favourites ----------

    private fun favMenu(app: AppEntry) {
        val favs = FavStore.get(this, cfg)
        val idx = favs.indexOf(app.pkg)
        if (idx < 0) return
        Popup.menu(this, app.label, listOf("Move left", "Move right", "Remove from favorites")) { which ->
            when (which) {
                0 -> if (idx > 0) { favs.removeAt(idx); favs.add(idx - 1, app.pkg) }
                1 -> if (idx < favs.size - 1) { favs.removeAt(idx); favs.add(idx + 1, app.pkg) }
                2 -> favs.removeAt(idx)
            }
            FavStore.set(this, favs)
            signature = ""
            val shown = favs.mapNotNull { Apps.one(this, it) }.map { it.pkg }
            reload(shown.indexOf(app.pkg).coerceAtLeast(0))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

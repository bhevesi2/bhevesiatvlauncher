package hu.otthon.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class HomeActivity : Activity() {

    private lateinit var cfg: Config
    private var ha: HaClient? = null
    private var signature = ""

    private lateinit var landscape: LandscapeView
    private lateinit var photoA: ImageView
    private lateinit var photoB: ImageView
    private var photoFront: ImageView? = null
    private lateinit var dimView: View
    private lateinit var content: LinearLayout
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var chipsRow: LinearLayout
    private lateinit var favRow: LinearLayout
    private lateinit var bottomRow: LinearLayout

    private lateinit var npCard: LinearLayout
    private lateinit var npCover: ImageView
    private lateinit var npLabel: TextView
    private lateinit var npTitle: TextView
    private lateinit var npArtist: TextView
    private lateinit var npProgress: View
    private lateinit var npProgressTrack: FrameLayout
    private lateinit var npButton: ImageView
    private var npPicture: String? = null

    private var phase: Phase? = null
    private var bgKey: String? = null
    private val chipViews = mutableListOf<Pair<HaItem, TextView>>()
    private var resumed = false
    private var shiftIndex = 0

    private val hu = Locale("hu", "HU")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", hu)
    private val dateFmt = DateTimeFormatter.ofPattern("EEEE, MMMM d.", hu)

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    // ---------- UI ----------

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        val match = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        landscape = LandscapeView(this)
        root.addView(landscape, match)
        photoA = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        photoB = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        root.addView(photoA, FrameLayout.LayoutParams(match))
        root.addView(photoB, FrameLayout.LayoutParams(match))
        dimView = View(this)
        root.addView(dimView, FrameLayout.LayoutParams(match))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48f), dp(34f), dp(48f), dp(34f))
            clipChildren = false
            clipToPadding = false
        }
        root.addView(content, FrameLayout.LayoutParams(match))

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
            contentDescription = "Beállítások"
            fun paint(f: Boolean) {
                background = if (f) rounded(C.FOCUS_FILL, dp(20f).toFloat()) else rounded(C.GLASS_SOFT, dp(20f).toFloat(), 1, C.BORDER)
                setColorFilter(if (f) C.DARK else C.TEXT)
            }
            paint(false)
            setOnFocusChangeListener { _, f -> paint(f) }
            setOnClickListener { openSystemSettings() }
        }
        top.addView(gear, LinearLayout.LayoutParams(dp(30f), dp(30f)).apply { leftMargin = dp(7f) })
        content.addView(top)

        buildNowPlaying()
        content.addView(npCard, LinearLayout.LayoutParams(dp(320f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(26f) })

        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        content.addView(label("Kedvencek", 11f, C.TEXT2, bold = true), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(9f) })
        favRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        content.addView(favRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76f)))

        bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        content.addView(bottomRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(22f) })

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
            contentDescription = "Lejátszás / szünet"
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

    private fun rebuildDynamic() {
        // favourites
        favRow.removeAllViews()
        val favs = FavStore.get(this, cfg)
        val entries = favs.mapNotNull { Apps.one(this, it) }
        entries.forEachIndexed { i, app ->
            val tile = AppTile(this, app)
            tile.setOnClickListener { if (!Apps.launch(this, app.pkg)) toast("Nem indítható: ${app.label}") }
            tile.setOnLongClickListener { favMenu(app); true }
            favRow.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (i > 0) leftMargin = dp(12f)
            })
        }
        if (entries.size < 6) {
            for (i in entries.size until 6) {
                favRow.addView(View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(12f) })
            }
        }

        // bottom row
        bottomRow.removeAllViews()
        val appsGroup = group("Alkalmazások")
        (appsGroup.getChildAt(1) as LinearLayout).addView(pill("Minden app", R.drawable.ic_grid) {
            startActivity(Intent(this, AllAppsActivity::class.java))
        })
        bottomRow.addView(appsGroup)
        if (cfg.haEnabled && cfg.scenes.isNotEmpty()) {
            val g = group("Otthon")
            val row = g.getChildAt(1) as LinearLayout
            cfg.scenes.forEachIndexed { i, s ->
                row.addView(pill(s.label, sceneIcon(s.icon)) { runScene(s) }, LinearLayout.LayoutParams(-2, -2).apply {
                    if (i > 0) leftMargin = dp(6f)
                })
            }
            bottomRow.addView(g, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(28f) })
        }

        // status chips
        chipsRow.removeAllViews()
        chipViews.clear()
        if (cfg.haEnabled) {
            cfg.status.forEachIndexed { i, s ->
                val v = chip(statusIcon(s.icon), if (s.label.isBlank()) "–" else "${s.label} –")
                chipsRow.addView(v, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = dp(7f) })
                chipViews.add(s to v)
            }
        }
        if (!cfg.haEnabled || cfg.nowPlaying == null) npCard.visibility = View.GONE

        favRow.getChildAt(0)?.takeIf { it.isFocusable }?.requestFocus()
    }

    private fun group(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        addView(label(title, 11f, C.TEXT2, bold = true), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(7f) })
        addView(LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false })
    }

    private fun statusIcon(name: String) = when (name) {
        "cloud", "weather" -> R.drawable.ic_cloud
        "lock" -> R.drawable.ic_lock
        "bolt", "power" -> R.drawable.ic_bolt
        "music" -> R.drawable.ic_music
        else -> R.drawable.ic_home
    }

    private fun sceneIcon(name: String) = when (name) {
        "film", "movie" -> R.drawable.ic_film
        "bulb", "light" -> R.drawable.ic_bulb
        "music" -> R.drawable.ic_music
        "lock" -> R.drawable.ic_lock
        else -> R.drawable.ic_bolt
    }

    // ---------- lifecycle ----------

    private fun reload() {
        cfg = Config.load(this)
        ha = if (cfg.haEnabled) HaClient(cfg.haUrl!!, cfg.haToken!!) else null
        applyDim()
        val sig = cfg.toString() + FavStore.get(this, cfg).joinToString()
        if (sig != signature) {
            signature = sig
            bgKey = null
            rebuildDynamic()
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
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        try { unregisterReceiver(tick) } catch (e: Exception) { }
        Bg.main.removeCallbacks(statusLoop)
        Bg.main.removeCallbacks(npLoop)
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
        date.text = now.format(dateFmt).replaceFirstChar { it.titlecase(hu) }
        val p = Sun.phase(System.currentTimeMillis(), cfg.lat, cfg.lon)
        val key = cfg.background + "/" + p.key
        if (key != bgKey) {
            val animate = bgKey != null
            bgKey = key
            phase = p
            applyBackground(p, animate)
        }
        // OLED: shift static content by a few pixels every minute
        val shifts = arrayOf(0 to 0, 2 to 1, -1 to 2, -2 to -1, 1 to -2, 2 to -1, -1 to -1)
        shiftIndex = (shiftIndex + 1) % shifts.size
        val (sx, sy) = shifts[shiftIndex]
        content.animate().translationX(dp(sx.toFloat()).toFloat()).translationY(dp(sy.toFloat()).toFloat()).setDuration(1500).start()
    }

    private fun applyDim() {
        val bottom = (cfg.dim * 255).toInt().coerceIn(0, 255)
        val top = (cfg.dim * 0.7f * 255).toInt().coerceIn(0, 255)
        dimView.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(top, 6, 7, 10), Color.argb(0, 6, 7, 10), Color.argb(bottom / 4, 6, 7, 10), Color.argb(bottom, 6, 7, 10))
        )
    }

    private fun applyBackground(p: Phase, animate: Boolean) {
        val bg = cfg.background
        if (bg.startsWith("foto:")) {
            val dir = File(getExternalFilesDir("backgrounds"), bg.removePrefix("foto:"))
            val f = listOf("jpg", "jpeg", "png", "webp").map { File(dir, "${p.key}.$it") }.firstOrNull { it.exists() }
            if (f != null) {
                Bg.run({ decodeScaled(f, 1920, 1080) }) { bmp -> if (bmp != null) showPhoto(bmp, animate) }
                return
            }
        }
        landscape.style = if (bg == "domb") "domb" else "alpesi"
        landscape.setPhase(p, animate)
        photoA.animate().alpha(0f).setDuration(if (animate) 3000 else 0).start()
        photoB.animate().alpha(0f).setDuration(if (animate) 3000 else 0).start()
        photoFront = null
    }

    private fun showPhoto(bmp: Bitmap, animate: Boolean) {
        val next = if (photoFront === photoA) photoB else photoA
        val prev = photoFront
        next.setImageBitmap(bmp)
        next.bringToFront()
        dimView.bringToFront()
        content.bringToFront()
        next.alpha = 0f
        next.animate().alpha(1f).setDuration(if (animate) 4000 else 0).withEndAction {
            prev?.alpha = 0f
            prev?.setImageDrawable(null)
        }.start()
        photoFront = next
    }

    private fun decodeScaled(f: File, w: Int, h: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    // ---------- Home Assistant ----------

    private fun refreshStatus() {
        val client = ha ?: return
        for ((item, view) in chipViews.toList()) {
            Bg.run({ client.state(item.entity) }) { s ->
                if (s != null && resumed) view.text = HaClient.formatState(item, s)
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
        val domain = s.entity.substringBefore('.')
        val service = if (domain in setOf("light", "switch", "input_boolean", "fan")) "toggle" else "turn_on"
        Bg.run({ client.call(domain, service, s.entity); true }) { ok ->
            toast(if (ok == true) "${s.label} ✓" else "Nem sikerült: ${s.label}")
        }
    }

    // ---------- favourites ----------

    private fun favMenu(app: AppEntry) {
        val favs = FavStore.get(this, cfg)
        val idx = favs.indexOf(app.pkg)
        if (idx < 0) return
        val items = arrayOf("Mozgatás balra", "Mozgatás jobbra", "Eltávolítás a kedvencek közül")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> if (idx > 0) { favs.removeAt(idx); favs.add(idx - 1, app.pkg) }
                    1 -> if (idx < favs.size - 1) { favs.removeAt(idx); favs.add(idx + 1, app.pkg) }
                    2 -> favs.removeAt(idx)
                }
                FavStore.set(this, favs)
                signature = ""
                reload()
                onMinute()
                val newIdx = favs.indexOf(app.pkg).coerceAtLeast(0)
                favRow.getChildAt(newIdx)?.takeIf { it.isFocusable }?.requestFocus()
            }
            .show()
    }

    private fun openSystemSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("A beállítások nem nyithatók meg")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

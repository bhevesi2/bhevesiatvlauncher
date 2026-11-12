package hu.otthon.launcher

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.util.concurrent.Executors

/**
 * Music: every Roon zone with what it is playing. Moving over a zone shows it large on the
 * left, with previous / play-pause / next; OK makes it the one on the home screen card.
 */
class MusicActivity : PanelActivity() {

    private class Player(val id: String) {
        var name = id.substringAfter('.')
        var state = ""
        var title = ""
        var artist = ""
        var album = ""
        var picture = ""
        var duration = 0.0
        var position = 0.0
        var positionAt = 0L

        val playing get() = state == "playing"
        val active get() = state == "playing" || state == "paused"
        val usable get() = state != "unavailable" && state != ""

        /** Where the track is now, counting on from the last report while it plays. */
        fun now(): Double {
            if (!playing || positionAt == 0L) return position
            return position + (System.currentTimeMillis() - positionAt) / 1000.0
        }
    }

    private lateinit var cfg: Config
    private var ha: HaClient? = null
    private val exec = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private var active = false
    private var gen = 0

    private lateinit var frame: PanelFrame
    private lateinit var list: LinearLayout
    private lateinit var message: TextView

    private lateinit var bigCover: ImageView
    private lateinit var bigZone: TextView
    private lateinit var bigTitle: TextView
    private lateinit var bigArtist: TextView
    private lateinit var track: FrameLayout
    private lateinit var bar: View
    private lateinit var elapsed: TextView
    private lateinit var total: TextView
    private lateinit var prevButton: ImageView
    private lateinit var playButton: ImageView
    private lateinit var nextButton: ImageView

    private val players = ArrayList<Player>()
    private val rows = HashMap<String, PlayerRow>()
    private var shown: String? = null
    private var shownPicture: String? = null
    private var ids: List<String>? = null
    private var listedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config.load(this)
        if (!cfg.haEnabled) {
            Toast.makeText(this, "Set up Home Assistant first", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        ha = HaClient(cfg.haUrl!!, cfg.haToken!!)
        shown = cfg.nowPlaying

        frame = PanelFrame(this, "Music", "OK: show on the home screen")
        frame.update(cfg)
        buildPlayer()

        message = label("Looking for Roon zones…", 12f, C.TEXT2).apply { setPadding(dp(22f), dp(18f), dp(22f), 0) }
        frame.panel.addView(message)
        val scroll = ColScroller(this).apply {
            clipToPadding = false
            setPadding(dp(22f), dp(10f), dp(22f), dp(20f))
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list, FrameLayout.LayoutParams(-1, -2))
        frame.panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(frame.root)
        animateIn(frame.panel)
    }

    // ---------- the large view on the left ----------

    private fun buildPlayer() {
        val left = frame.left
        FocusGlide.install(left)
        bigCover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(0x33FFFFFF, dp(10f).toFloat())
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            setImageResource(R.drawable.ic_disc)
            setColorFilter(C.TEXT3)
            val p = dp(44f)
            setPadding(p, p, p, p)
        }
        left.addView(bigCover, LinearLayout.LayoutParams(dp(150f), dp(150f)).apply { topMargin = dp(22f) })
        bigZone = label("", 10f, C.TEXT3).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        left.addView(bigZone, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })
        bigTitle = label("", 17f, C.TEXT, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        left.addView(bigTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3f) })
        bigArtist = label("", 12f, C.TEXT2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        left.addView(bigArtist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3f) })

        track = FrameLayout(this).apply { background = rounded(0x2EFFFFFF, dp(1.5f).toFloat()) }
        bar = View(this).apply { background = rounded(C.TEXT, dp(1.5f).toFloat()) }
        track.addView(bar, FrameLayout.LayoutParams(0, dp(3f)))
        left.addView(track, LinearLayout.LayoutParams(-1, dp(3f)).apply { topMargin = dp(12f) })
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        elapsed = label("", 10f, C.TEXT3)
        total = label("", 10f, C.TEXT3)
        times.addView(elapsed, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(total)
        left.addView(times, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        prevButton = roundButton(R.drawable.ic_prev, "Previous", 40f) { send("media_previous_track") }
        playButton = roundButton(R.drawable.ic_play, "Play / pause", 52f) { send("media_play_pause") }
        nextButton = roundButton(R.drawable.ic_next, "Next", 40f) { send("media_next_track") }
        buttons.addView(prevButton, LinearLayout.LayoutParams(dp(40f), dp(40f)))
        buttons.addView(playButton, LinearLayout.LayoutParams(dp(52f), dp(52f)).apply { leftMargin = dp(14f) })
        buttons.addView(nextButton, LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { leftMargin = dp(14f) })
        left.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10f) })
        prevButton.nextFocusLeftId = prevButton.id
    }

    private fun roundButton(icon: Int, name: String, sizeDp: Float, onClick: () -> Unit): ImageView = ImageView(this).apply {
        id = View.generateViewId()
        setImageResource(icon)
        val p = dp(sizeDp * 0.26f)
        setPadding(p, p, p, p)
        isFocusable = true
        isClickable = true
        contentDescription = name
        FocusGlide.shape(this, -1f)
        fun paint(f: Boolean) {
            background = if (f) FocusGlide.fill(this, dp(sizeDp / 2).toFloat()) else rounded(0x33FFFFFF, dp(sizeDp / 2).toFloat())
            setColorFilter(if (f) C.DARK else C.TEXT)
        }
        paint(false)
        setOnFocusChangeListener { _, f -> paint(f) }
        setOnClickListener { onClick() }
    }

    private fun showLarge(p: Player?) {
        if (p == null) {
            bigZone.text = ""
            bigTitle.text = "No zone selected"
            bigArtist.text = ""
            setCover(null)
            return
        }
        bigZone.text = p.name.uppercase()
        bigTitle.text = when {
            p.active && p.title.isNotBlank() -> p.title
            p.state == "unavailable" -> "Not available"
            else -> "Nothing playing"
        }
        bigArtist.text = if (p.active) listOf(p.artist, p.album).filter { it.isNotBlank() }.joinToString(" — ") else ""
        playButton.setImageResource(if (p.playing) R.drawable.ic_pause else R.drawable.ic_play)
        setCover(if (p.active) p.picture.ifBlank { null } else null)
        updateProgress()
    }

    private fun setCover(url: String?) {
        if (url == shownPicture) return
        shownPicture = url
        val pad = dp(44f)
        if (url == null) {
            bigCover.setImageResource(R.drawable.ic_disc)
            bigCover.setColorFilter(C.TEXT3)
            bigCover.setPadding(pad, pad, pad, pad)
            return
        }
        cover(url, 400) { b ->
            if (url != shownPicture) return@cover
            if (b != null) {
                bigCover.clearColorFilter()
                bigCover.setPadding(0, 0, 0, 0)
                bigCover.setImageBitmap(b)
            }
        }
    }

    private fun updateProgress() {
        val p = players.firstOrNull { it.id == shown }
        val dur = p?.duration ?: 0.0
        val pos = p?.now() ?: 0.0
        val visible = p != null && p.active && dur > 0
        track.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        elapsed.text = if (visible) clock(pos.coerceAtMost(dur)) else ""
        total.text = if (visible) clock(dur) else ""
        if (visible) {
            val frac = (pos / dur).coerceIn(0.0, 1.0)
            track.post { bar.layoutParams = bar.layoutParams.apply { width = (track.width * frac).toInt() } }
        }
    }

    private fun clock(seconds: Double): String {
        val s = seconds.toLong().coerceAtLeast(0)
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format("%d:%02d", s / 60, s % 60)
    }

    // ---------- the zone list ----------

    private inner class PlayerRow(val player: Player) : LinearLayout(this@MusicActivity) {
        val thumb = ImageView(context)
        val name = label("", 13f, C.TEXT, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val line = label("", 11f, C.TEXT2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val badge = label("", 10f, C.TEXT2)
        var picture: String? = "-"

        init {
            id = View.generateViewId()
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(9f), dp(12f), dp(9f))
            isFocusable = true
            isClickable = true
            thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            thumb.background = rounded(0x26FFFFFF, dp(6f).toFloat())
            thumb.clipToOutline = true
            thumb.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            addView(thumb, LayoutParams(dp(44f), dp(44f)))
            val col = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(12f), 0, dp(10f), 0) }
            col.addView(name, LayoutParams(-1, -2))
            col.addView(line, LayoutParams(-1, -2).apply { topMargin = dp(2f) })
            addView(col, LayoutParams(0, -2, 1f))
            addView(badge, LayoutParams(-2, -2))
            FocusGlide.shape(this, dp(9f).toFloat())
            setOnFocusChangeListener { _, f ->
                paint(f)
                if (f && shown != player.id) {
                    shown = player.id
                    showLarge(player)
                }
            }
            setOnClickListener { choose(player) }
            nextFocusLeftId = playButton.id
            bind()
        }

        fun paint(f: Boolean) {
            val r = dp(9f).toFloat()
            background = if (f) FocusGlide.fill(this, r) else rounded(C.ROW, r)
            val dead = !player.usable
            name.setTextColor(if (f) C.DARK else if (dead) C.TEXT3 else C.TEXT)
            line.setTextColor(if (f) C.DARK2 else if (player.playing) C.ON else C.TEXT3)
            badge.setTextColor(if (f) C.DARK2 else C.TEXT2)
        }

        fun bind() {
            val p = player
            name.text = p.name
            line.text = when {
                p.state == "unavailable" -> "Not available"
                p.active && p.title.isNotBlank() ->
                    (if (p.playing) "▶  " else "❚❚  ") + listOf(p.title, p.artist).filter { it.isNotBlank() }.joinToString(" — ")
                p.active -> if (p.playing) "Playing" else "Paused"
                p.state == "idle" -> "Idle"
                p.state == "off" -> "Off"
                else -> p.state.replaceFirstChar { it.uppercase() }
            }
            badge.text = if (p.id == cfg.nowPlaying) "On home screen  ✓" else ""
            val pic = if (p.active) p.picture.ifBlank { null } else null
            if (pic != picture) {
                picture = pic
                if (pic == null) {
                    thumb.setImageResource(R.drawable.ic_music)
                    thumb.setColorFilter(C.TEXT3)
                    val pad = dp(12f)
                    thumb.setPadding(pad, pad, pad, pad)
                } else {
                    cover(pic, 120) { b ->
                        if (b != null && picture == pic) {
                            thumb.clearColorFilter()
                            thumb.setPadding(0, 0, 0, 0)
                            thumb.setImageBitmap(b)
                        }
                    }
                }
            }
            paint(isFocused)
        }
    }

    private fun choose(p: Player) {
        if (!p.usable) {
            toast("${p.name} is not available")
            return
        }
        ConfigStore.edit(this) { it.put("now_playing", p.id).put("now_playing_label", "Roon · " + p.name) }
        cfg = Config.load(this)
        rows.values.forEach { it.bind() }
        toast("${p.name} is now on the home screen")
    }

    private fun rebuildList() {
        list.removeAllViews()
        rows.clear()
        // zones that can play first, the ones that are switched off or gone at the end
        val ordered = players.sortedWith(compareBy({ if (it.active) 0 else if (it.usable) 1 else 2 }, { it.name.lowercase() }))
        for (p in ordered) {
            val row = PlayerRow(p)
            rows[p.id] = row
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
        }
        message.visibility = if (players.isEmpty()) View.VISIBLE else View.GONE
        if (players.isEmpty()) message.text = "No Roon zones found in Home Assistant."
        val target = rows[shown] ?: rows.values.firstOrNull()
        val playId = target?.id ?: View.NO_ID
        prevButton.nextFocusRightId = playId
        playButton.nextFocusRightId = playId
        nextButton.nextFocusRightId = playId
        // the first time, start on the zone that is on the home screen, whatever Android focused meanwhile
        if (!listedOnce || currentFocus == null || currentFocus === window.decorView) target?.requestFocus()
        listedOnce = true
        showLarge(players.firstOrNull { it.id == shown })
    }

    // ---------- live data ----------

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
        refresh()
        main.postDelayed({ tick(g) }, 3000L)
        progressLoop(g)
    }

    private fun progressLoop(g: Int) {
        main.removeCallbacksAndMessages(PROGRESS)
        fun step() {
            if (!active || g != gen) return
            updateProgress()
            main.postAtTime({ step() }, PROGRESS, android.os.SystemClock.uptimeMillis() + 1000L)
        }
        step()
    }

    private fun refresh() {
        val client = ha ?: return
        val known = ids
        try {
            exec.execute {
                val list = known ?: findZones(client)
                val states = list.map { id -> id to (try { client.state(id) } catch (e: Exception) { null }) }
                main.post {
                    if (!active) return@post
                    ids = list
                    apply(states)
                }
            }
        } catch (e: Exception) { }
    }

    /** The Roon zones, from Home Assistant; without them, every player that is playing right now. */
    private fun findZones(client: HaClient): List<String> {
        val roon = try {
            val text = client.template("{{ integration_entities('roon') | select('match', 'media_player') | list | tojson }}")
            val arr = JSONArray(text.trim())
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            null
        }
        val out = LinkedHashSet<String>()
        if (!roon.isNullOrEmpty()) {
            out.addAll(roon)
        } else {
            try {
                client.states().filter { it.domain == "media_player" && (it.state == "playing" || it.state == "paused") }.forEach { out.add(it.id) }
            } catch (e: Exception) { }
        }
        cfg.nowPlaying?.let { out.add(it) }
        return out.toList()
    }

    private fun apply(states: List<Pair<String, JSONObject?>>) {
        val before = players.map { it.id }.toSet()
        val wasOrder = players.map { "${it.id}:${it.active}:${it.usable}" }
        for ((id, s) in states) {
            val p = players.firstOrNull { it.id == id } ?: Player(id).also { players.add(it) }
            if (s == null) {
                p.state = "unavailable"
                continue
            }
            val a = s.optJSONObject("attributes") ?: JSONObject()
            p.name = a.optString("friendly_name", "").ifBlank { id.substringAfter('.') }
            p.state = s.optString("state", "")
            p.title = a.optString("media_title", "")
            p.artist = a.optString("media_artist", "")
            p.album = a.optString("media_album_name", "")
            p.picture = a.optString("entity_picture", "")
            p.duration = a.optDouble("media_duration", 0.0)
            p.position = a.optDouble("media_position", 0.0)
            p.positionAt = try {
                OffsetDateTime.parse(a.optString("media_position_updated_at", "")).toInstant().toEpochMilli()
            } catch (e: Exception) {
                0L
            }
        }
        val order = players.sortedWith(compareBy({ if (it.active) 0 else if (it.usable) 1 else 2 }, { it.name.lowercase() }))
            .map { "${it.id}:${it.active}:${it.usable}" }
        if (before != players.map { it.id }.toSet() || rows.isEmpty() || wasOrder.sorted() != order.sorted()) {
            // keep the zone the cursor is on
            val focused = rows.entries.firstOrNull { it.value.isFocused }?.key
            rebuildList()
            if (focused != null) rows[focused]?.requestFocus()
        } else {
            rows.values.forEach { it.bind() }
            showLarge(players.firstOrNull { it.id == shown })
        }
    }

    // ---------- actions ----------

    private fun send(service: String) {
        val client = ha ?: return
        val id = shown ?: return
        val p = players.firstOrNull { it.id == id }
        if (p != null && !p.usable) {
            toast("${p.name} is not available")
            return
        }
        try {
            exec.execute {
                val ok = try { client.call("media_player", service, id); true } catch (e: Exception) { false }
                main.post {
                    if (!ok) toast("Roon did not accept that")
                    main.postDelayed({ refresh() }, 400L)
                    main.postDelayed({ refresh() }, 1500L)
                }
            }
        } catch (e: Exception) { }
    }

    /** The remote's own play, pause, next and previous keys work on the zone shown on the left. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val service = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> "media_play_pause"
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "media_next_track"
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> "media_previous_track"
            else -> null
        }
        if (service != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) send(service)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Album art, kept for the next time it is asked for. */
    private fun cover(url: String, width: Int, done: (Bitmap?) -> Unit) {
        val key = "$width|$url"
        covers[key]?.let {
            done(it)
            return
        }
        val client = ha ?: return
        try {
            exec.execute {
                val b = try { client.bitmap(url, width) } catch (e: Exception) { null }
                main.post {
                    if (b != null) {
                        covers[key] = b
                        while (covers.size > 16) covers.remove(covers.keys.first())
                    }
                    done(b)
                }
            }
        } catch (e: Exception) { }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private val PROGRESS = Any()
        private val covers = LinkedHashMap<String, Bitmap>()
    }
}

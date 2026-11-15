package hu.otthon.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Music Assistant on the TV, in two halves. On the left the library: rows of covers on Home
 * (recently played, playlists, radio, artists, new albums), a grid per tab, and search. On the
 * right, always in reach, Now playing: the player it plays on, a big cover, the track, the
 * quality, a progress bar, previous / play / next, volume, and what comes next.
 * OK on a cover plays it at once; hold OK for play next, add to the queue or radio mode.
 */
class MaActivity : PanelActivity() {

    /** One thing in the library. */
    private class Item(val uri: String, val type: String, val name: String, val sub: String, val image: String?)

    private class Section(val title: String, val items: List<Item>)

    private lateinit var cfg: Config
    private var ha: HaClient? = null
    private val exec = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private var gen = 0

    private lateinit var backdrop: Backdrop
    private lateinit var body: LinearLayout
    private lateinit var scroll: ColScroller
    private lateinit var message: TextView
    private val tabs = ArrayList<TextView>()
    private var tab = 0
    private var libWidth = 0

    // Now playing
    private lateinit var playOn: TextView
    private lateinit var npCover: ImageView
    private lateinit var npTitle: TextView
    private lateinit var npArtist: TextView
    private lateinit var npQuality: TextView
    private lateinit var npTrack: FrameLayout
    private lateinit var npBar: View
    private lateinit var npElapsed: TextView
    private lateinit var npTotal: TextView
    private lateinit var npPlay: ImageView
    private lateinit var npNext: TextView
    private var npPicture: String? = null
    private var npPlaying = false
    private var npElapsedAt = 0L
    private var npElapsedS = 0.0
    private var npDuration = 0.0

    /** Music Assistant's config entry, found once. */
    private var entry: String? = null
    private var target: String? = null
    private var targetName = ""
    private val covers = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 120
    }

    private val TABS = listOf("Home", "Playlists", "Albums", "Artists", "Radio")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config.load(this)
        ha = if (cfg.haEnabled) HaClient(cfg.haUrl!!, cfg.haToken!!) else null
        buildUi()
        if (ha == null) {
            showMessage("Set up or switch on Home Assistant first.")
            return
        }
        exec.execute {
            val found = findMusicAssistant()
            main.post {
                Prefs.of(this).edit().putBoolean("ma_present", found).apply()
                if (found) {
                    showTab(0)
                    refreshNow()
                } else {
                    showMessage("Music Assistant was not found in Home Assistant.")
                }
            }
        }
    }

    // ---------- the screen ----------

    private fun buildUi() {
        val root = FrameLayout(this)
        backdrop = Backdrop(this)
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xE6070810.toInt(), 0xCC070810.toInt(), 0xB3070810.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))

        val split = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        split.setPadding(dp(44f), dp(28f), dp(30f), dp(26f))
        root.addView(split, FrameLayout.LayoutParams(-1, -1))
        FocusGlide.install(split)

        // ---- left: the library
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("Library", 24f, C.TEXT, light = true), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(18f) })
        TABS.forEachIndexed { i, t ->
            val p = pill(t, null) { showTab(i) }
            val painted = p.onFocusChangeListener
            p.setOnFocusChangeListener { v, f ->
                painted?.onFocusChange(v, f)
                if (!f) paintTabs()
            }
            tabs.add(p)
            head.addView(p, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = dp(6f) })
        }
        head.addView(roundButton(null, "Search") { search() }.apply { setImageDrawable(searchIcon()) },
            LinearLayout.LayoutParams(dp(36f), dp(36f)).apply { leftMargin = dp(10f) })
        left.addView(head, LinearLayout.LayoutParams(-1, -2))

        scroll = ColScroller(this).apply {
            clipToPadding = false
            clipChildren = false
            isVerticalScrollBarEnabled = false
            setPadding(0, dp(6f), 0, dp(24f))
        }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        scroll.addView(body, FrameLayout.LayoutParams(-1, -2))
        left.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8f) })
        message = label("", 14f, C.TEXT2).apply { visibility = View.GONE }
        left.addView(message, LinearLayout.LayoutParams(-2, -2))
        split.addView(left, LinearLayout.LayoutParams(0, -1, 1f))

        // ---- right: Now playing
        split.addView(buildNowPlaying(), LinearLayout.LayoutParams(dp(330f), -1).apply { leftMargin = dp(24f) })

        setContentView(root)
        libWidth = resources.displayMetrics.widthPixels - dp(44f) - dp(30f) - dp(330f) - dp(24f)
        backdrop.setDim(maxOf(cfg.dim, 0.75f))
        backdrop.show(cfg.background, Backgrounds.phase(this, cfg), false)
        tabs.firstOrNull()?.requestFocus()
    }

    private fun buildNowPlaying(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Look.surface(this@MaActivity, dp(24f).toFloat())
            setPadding(dp(22f), dp(18f), dp(22f), dp(18f))
            clipChildren = false
            clipToPadding = false
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(label("Now playing", 13f, C.TEXT3, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        playOn = pill("Choose a player", R.drawable.ic_music) { choosePlayer() }.apply {
            maxWidth = dp(190f)
            ellipsize = TextUtils.TruncateAt.END
        }
        top.addView(playOn)
        panel.addView(top, LinearLayout.LayoutParams(-1, -2))

        npCover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = rounded(0xFF22242E.toInt(), dp(16f).toFloat())
            elevation = dp(10f).toFloat()
        }
        panel.addView(npCover, LinearLayout.LayoutParams(dp(220f), dp(220f)).apply { topMargin = dp(16f) })

        npTitle = label("Nothing playing", 18f, C.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        panel.addView(npTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f) })
        npArtist = label("", 13f, C.TEXT2).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        panel.addView(npArtist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })
        npQuality = label("", 10f, C.TEXT2, bold = true).apply {
            background = rounded(0x1FFFFFFF, dp(8f).toFloat())
            setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
            visibility = View.GONE
            letterSpacing = 0.06f
        }
        panel.addView(npQuality, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8f) })

        npTrack = FrameLayout(this).apply { background = rounded(0x33FFFFFF, dp(2f).toFloat()) }
        npBar = View(this).apply { background = rounded(C.TEXT, dp(2f).toFloat()) }
        npTrack.addView(npBar, FrameLayout.LayoutParams(0, dp(3f)))
        panel.addView(npTrack, LinearLayout.LayoutParams(-1, dp(3f)).apply { topMargin = dp(14f) })
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        npElapsed = label("", 10f, C.TEXT3)
        npTotal = label("", 10f, C.TEXT3)
        times.addView(npElapsed, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(npTotal)
        panel.addView(times, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5f) })

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; clipChildren = false }
        controls.addView(roundButton(null, "Volume down") { send("volume_down") }.apply { setImageDrawable(volumeIcon(false)) }, LinearLayout.LayoutParams(dp(34f), dp(34f)))
        controls.addView(roundButton(R.drawable.ic_prev, "Previous") { send("media_previous_track") }, LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { leftMargin = dp(12f) })
        npPlay = roundButton(R.drawable.ic_play, "Play / pause") { send("media_play_pause") }
        controls.addView(npPlay, LinearLayout.LayoutParams(dp(52f), dp(52f)).apply { leftMargin = dp(12f) })
        controls.addView(roundButton(R.drawable.ic_next, "Next") { send("media_next_track") }, LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { leftMargin = dp(12f) })
        controls.addView(roundButton(null, "Volume up") { send("volume_up") }.apply { setImageDrawable(volumeIcon(true)) }, LinearLayout.LayoutParams(dp(34f), dp(34f)).apply { leftMargin = dp(12f) })
        panel.addView(controls, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12f) })

        npNext = label("", 11f, C.TEXT3).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        panel.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        panel.addView(npNext, LinearLayout.LayoutParams(-1, -2))
        showIdle()
        return panel
    }

    /** No picture for "volume": a speaker with waves (up) or a dash (down). */
    private fun volumeIcon(up: Boolean): Drawable = object : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private var cf: android.graphics.ColorFilter? = null
        override fun draw(c: Canvas) {
            val b = bounds
            val s = b.width() / 24f
            c.save()
            c.translate(b.left.toFloat(), b.top.toFloat())
            c.scale(s, s)
            p.colorFilter = cf
            p.color = Color.WHITE
            p.style = Paint.Style.FILL
            val path = android.graphics.Path().apply {
                moveTo(4f, 9.5f); lineTo(7.5f, 9.5f); lineTo(12f, 5.5f); lineTo(12f, 18.5f); lineTo(7.5f, 14.5f); lineTo(4f, 14.5f); close()
            }
            c.drawPath(path, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.8f
            if (up) {
                c.drawArc(11f, 8f, 18f, 16f, -50f, 100f, false, p)
                c.drawArc(11f, 4.5f, 21.5f, 19.5f, -50f, 100f, false, p)
            } else {
                c.drawLine(15.5f, 12f, 20.5f, 12f, p)
            }
            c.restore()
        }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { cf = colorFilter; invalidateSelf() }
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** A magnifying glass, drawn: there is no search icon among the app's pictures. */
    private fun searchIcon(): Drawable = object : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private var cf: android.graphics.ColorFilter? = null
        override fun draw(c: Canvas) {
            val b = bounds
            val s = b.width() / 24f
            p.strokeWidth = 2f * s
            p.color = Color.WHITE
            p.colorFilter = cf
            c.drawCircle(b.left + 10.5f * s, b.top + 10.5f * s, 6.5f * s, p)
            c.drawLine(b.left + 15.5f * s, b.top + 15.5f * s, b.left + 20f * s, b.top + 20f * s, p)
        }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { cf = colorFilter; invalidateSelf() }
        override fun setAlpha(a: Int) { p.alpha = a }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun roundButton(icon: Int?, name: String, onClick: () -> Unit): ImageView = ImageView(this).apply {
        if (icon != null) setImageResource(icon)
        val pad = dp(9f)
        setPadding(pad, pad, pad, pad)
        isFocusable = true
        isClickable = true
        contentDescription = name
        val normal = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Look.SURFACE)
            setStroke(dp(1f), Look.HAIRLINE)
        }
        background = normal
        setColorFilter(C.TEXT)
        FocusGlide.shape(this, -1f)
        setOnFocusChangeListener { v, f ->
            background = if (f) FocusGlide.fill(this, 999f) else normal
            setColorFilter(if (f) C.DARK else C.TEXT)
            Look.grow(v, f)
        }
        setOnClickListener { onClick() }
    }

    private fun showMessage(text: String) {
        body.removeAllViews()
        message.text = text
        message.visibility = View.VISIBLE
    }

    private fun paintTabs() {
        tabs.forEachIndexed { i, t ->
            // the open tab keeps a light edge, so it can be told apart without the cursor on it
            if (!t.isFocused) t.background = if (i == tab) rounded(Look.SURFACE, dp(18f).toFloat(), dp(2f), 0xCCFFFFFF.toInt()) else Look.surface(this, dp(18f).toFloat())
        }
    }

    // ---------- the library ----------

    private fun showTab(i: Int) {
        tab = i
        paintTabs()
        val g = ++gen
        message.visibility = View.GONE
        body.removeAllViews()
        body.addView(label("Loading…", 13f, C.TEXT3), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20f) })
        exec.execute {
            val sections = try {
                when (i) {
                    0 -> listOfNotNull(
                        section("Recently played", "album", "last_played_desc", 20),
                        section("Playlists", "playlist", "last_played_desc", 20),
                        section("Radio", "radio", "name", 20),
                        section("Artists you play most", "artist", "play_count_desc", 20),
                        section("Recently added albums", "album", "timestamp_added_desc", 20)
                    )
                    1 -> listOfNotNull(section("Playlists", "playlist", "name", 240))
                    2 -> listOfNotNull(section("Albums", "album", "name", 240))
                    3 -> listOfNotNull(section("Artists", "artist", "name", 240))
                    else -> listOfNotNull(section("Radio", "radio", "name", 240))
                }
            } catch (e: Exception) {
                null
            }
            main.post {
                if (g != gen || isFinishing) return@post
                when {
                    sections == null -> showMessage("Music Assistant did not answer.")
                    sections.isEmpty() -> showMessage("Nothing here yet.")
                    i == 0 -> showRows(sections)
                    else -> showGrid(sections[0])
                }
            }
        }
    }

    private fun section(title: String, type: String, order: String, limit: Int): Section? {
        val client = ha ?: return null
        val data = JSONObject()
            .put("config_entry_id", entry)
            .put("media_type", type)
            .put("order_by", order)
            .put("limit", limit)
        val r = client.callResponse("music_assistant", "get_library", data)
        val items = parse(r.optJSONArray("items"))
        return if (items.isEmpty()) null else Section(title, items)
    }

    private fun parse(arr: JSONArray?): List<Item> {
        if (arr == null) return emptyList()
        val out = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val uri = o.optString("uri", "")
            if (uri.isBlank()) continue
            out.add(Item(uri, o.optString("media_type", ""), o.optString("name", ""), artistsOf(o), imageOf(o)))
        }
        return out
    }

    private fun artistsOf(o: JSONObject): String {
        val artists = o.optJSONArray("artists")
        if (artists != null && artists.length() > 0) return (0 until artists.length()).mapNotNull { artists.optJSONObject(it)?.optString("name") }.joinToString(", ")
        return o.optJSONObject("album")?.optString("name", "") ?: ""
    }

    private fun imageOf(o: JSONObject): String? =
        o.optString("image", "").takeIf { it.isNotBlank() && it != "null" }
            ?: o.optJSONObject("album")?.optString("image", "")?.takeIf { it.isNotBlank() && it != "null" }

    /** Home: one row of covers per section. */
    private fun showRows(sections: List<Section>) {
        body.removeAllViews()
        for (s in sections) {
            body.addView(label(s.title, 14f, C.TEXT, bold = true), LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = if (body.childCount > 0) dp(14f) else dp(6f)
            })
            val rowScroll = RowScroller(this)
            rowScroll.setPadding(0, dp(10f), dp(20f), dp(6f))
            rowScroll.clipToPadding = false
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
            s.items.forEachIndexed { i, item ->
                line.addView(card(item, dp(124f)), LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = dp(14f) })
            }
            rowScroll.addView(line)
            body.addView(rowScroll, LinearLayout.LayoutParams(-1, -2))
        }
        scroll.scrollTo(0, 0)
    }

    /** A tab: all of one kind, four covers to a line. */
    private fun showGrid(s: Section, perLine: Int = 4) {
        body.removeAllViews()
        val gap = dp(16f)
        val w = (libWidth - (perLine - 1) * gap) / perLine
        var line: LinearLayout? = null
        s.items.forEachIndexed { i, item ->
            if (i % perLine == 0) {
                line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
                body.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (i == 0) dp(10f) else dp(14f) })
            }
            line!!.addView(card(item, w), LinearLayout.LayoutParams(w, -2).apply { if (i % perLine > 0) leftMargin = gap })
        }
        scroll.scrollTo(0, 0)
    }

    /**
     * A cover without a picture: a soft two-colour gradient chosen from the name, with the name
     * on it, in the manner of the streaming apps' generated covers.
     */
    private class NameCover(private val name: String, private val round: Boolean, density: Float) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            textSize = 13f * density
        }
        private val pairs = arrayOf(
            intArrayOf(0xFF5B5BD6.toInt(), 0xFFB45BD6.toInt()), intArrayOf(0xFF2F8F83.toInt(), 0xFF4FB6D6.toInt()),
            intArrayOf(0xFFD6735B.toInt(), 0xFFD6B25B.toInt()), intArrayOf(0xFF3B6FD6.toInt(), 0xFF5BD6C4.toInt()),
            intArrayOf(0xFFB0476F.toInt(), 0xFF6F47B0.toInt()), intArrayOf(0xFF4E7A3A.toInt(), 0xFF9AB54A.toInt())
        )

        override fun draw(c: Canvas) {
            val b = bounds
            if (b.width() <= 0) return
            val pair = pairs[Math.abs(name.hashCode() % pairs.size)]
            p.shader = LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), pair[0], pair[1], Shader.TileMode.CLAMP)
            c.drawRect(b, p)
            p.shader = null
            // the name, cleaned of the file-name look (dashes, underscores), on two or three lines
            val clean = name.replace('_', ' ').replace(Regex("^0+-"), "").replace(Regex("\\s+"), " ").trim()
            val pad = b.width() * 0.1f
            val w = (b.width() - 2 * pad).toInt().coerceAtLeast(1)
            @Suppress("DEPRECATION")
            val layout = android.text.StaticLayout(clean, t, w,
                if (round) android.text.Layout.Alignment.ALIGN_CENTER else android.text.Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)
            val lines = minOf(layout.lineCount, if (round) 2 else 3)
            val h = if (lines > 0) layout.getLineBottom(lines - 1) else 0
            c.save()
            c.translate(b.left + pad, if (round) b.exactCenterY() - h / 2f else b.bottom - pad - h)
            c.clipRect(0f, 0f, w.toFloat(), h.toFloat())
            layout.draw(c)
            c.restore()
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
    }

    /** A square cover (round for artists) with the name and the artist under it. */
    private fun card(item: Item, size: Int): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        val round = item.type == "artist"
        val radius = if (round) size / 2f else dp(10f).toFloat()
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(0xFF262833.toInt(), radius)
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            isFocusable = true
            isClickable = true
            isLongClickable = true
            contentDescription = item.name
            setImageDrawable(NameCover(item.name, round, resources.displayMetrics.density))
        }
        FocusGlide.shape(cover, radius)
        FocusGlide.glow(cover, 0xFFE9E7E2.toInt())
        val name = label(item.name.replace('_', ' '), 12f, C.TEXT2, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2f), dp(8f), dp(2f), 0)
            if (round) gravity = Gravity.CENTER
        }
        val sub = label(item.sub, 10f, C.TEXT3).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2f), dp(2f), dp(2f), 0)
            if (round) gravity = Gravity.CENTER
            visibility = if (item.sub.isBlank()) View.GONE else View.VISIBLE
        }
        cover.setOnFocusChangeListener { v, f ->
            Look.grow(v, f, 1.06f)
            name.setTextColor(if (f) C.TEXT else C.TEXT2)
            name.animate().translationY(if (f) dp(4f).toFloat() else 0f).setDuration(200).start()
            sub.animate().translationY(if (f) dp(4f).toFloat() else 0f).setDuration(200).start()
        }
        cover.setOnClickListener { play(item, "replace", false) }
        cover.setOnLongClickListener { menu(item); true }
        box.addView(cover, LinearLayout.LayoutParams(size, size))
        box.addView(name, LinearLayout.LayoutParams(size, -2))
        box.addView(sub, LinearLayout.LayoutParams(size, -2))
        item.image?.let { url ->
            picture(url, size) { b -> if (b != null) cover.setImageBitmap(b) }
        }
        return box
    }

    private fun menu(item: Item) {
        val options = listOf("Play now", "Play next", "Add to the queue", "Radio: more like this")
        Popup.menu(this, item.name, options) { which ->
            when (which) {
                0 -> play(item, "replace", false)
                1 -> play(item, "next", false)
                2 -> play(item, "add", false)
                else -> play(item, "replace", true)
            }
        }
    }

    private fun search() {
        Popup.text(this, "Search music", "", "An artist, album, song or radio station") { q ->
            if (q.isBlank()) return@text
            val g = ++gen
            tab = -1
            paintTabs()
            message.visibility = View.GONE
            body.removeAllViews()
            body.addView(label("Searching “$q”…", 13f, C.TEXT3), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20f) })
            exec.execute {
                val sections = try {
                    val r = ha!!.callResponse("music_assistant", "search", JSONObject()
                        .put("config_entry_id", entry)
                        .put("name", q)
                        .put("limit", 16), 20000)
                    listOf("artists" to "Artists", "albums" to "Albums", "tracks" to "Songs", "playlists" to "Playlists", "radio" to "Radio")
                        .mapNotNull { (k, t) -> parse(r.optJSONArray(k)).takeIf { it.isNotEmpty() }?.let { Section(t, it) } }
                } catch (e: Exception) {
                    null
                }
                main.post {
                    if (g != gen || isFinishing) return@post
                    when {
                        sections == null -> showMessage("Music Assistant did not answer.")
                        sections.isEmpty() -> showMessage("Nothing found for “$q”.")
                        else -> {
                            showRows(sections)
                            (body.getChildAt(1) as? ViewGroup)?.let { firstFocusable(it)?.requestFocus() }
                        }
                    }
                }
            }
        }
    }

    private fun firstFocusable(v: View): View? {
        if (v.isFocusable && v is ImageView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
        return null
    }

    // ---------- playing ----------

    private fun play(item: Item, enqueue: String, radio: Boolean) {
        val t = target
        val client = ha
        if (t == null || client == null) {
            choosePlayer()
            return
        }
        val data = JSONObject()
            .put("entity_id", t)
            .put("media_id", item.uri)
            .put("enqueue", enqueue)
        if (item.type.isNotBlank()) data.put("media_type", item.type)
        if (radio) data.put("radio_mode", true)
        exec.execute {
            val ok = try { client.call("music_assistant", "play_media", data, 15000); true } catch (e: Exception) { false }
            main.post {
                toast(if (!ok) "Couldn't play ${item.name}" else when (enqueue) {
                    "next" -> "${item.name} plays next"
                    "add" -> "${item.name} added to the queue"
                    else -> "${item.name} ▶ $targetName"
                })
                main.postDelayed({ refreshNow() }, 1200)
            }
        }
    }

    private fun send(service: String) {
        val t = target ?: return
        val client = ha ?: return
        exec.execute {
            try { client.call("media_player", service, t) } catch (e: Exception) { }
            main.postDelayed({ refreshNow() }, 600)
        }
    }

    /** The players, with their room; the choice is kept on this TV (the home screen card follows it too). */
    private fun players(): List<Triple<String, String, String>> {
        val client = ha ?: return emptyList()
        val text = client.template(
            "{% set ns = namespace(o=[]) %}{% for e in integration_entities('music_assistant') | select('match', 'media_player') %}" +
                "{% if states(e) not in ['unavailable', 'unknown'] %}{% set ns.o = ns.o + [[e, state_attr(e, 'friendly_name') or e, states(e), area_name(e) or '']] %}{% endif %}" +
                "{% endfor %}{{ ns.o | tojson }}"
        )
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val a = arr.getJSONArray(i)
            val area = a.optString(3, "")
            Triple(a.getString(0), if (area.isNotBlank() && !a.getString(1).contains(area, true)) "${a.getString(1)} · $area" else a.getString(1), a.getString(2))
        }
    }

    private fun choosePlayer() {
        if (ha == null) return
        exec.execute {
            val list = try { players() } catch (e: Exception) { emptyList() }
            main.post {
                if (list.isEmpty()) {
                    toast("No Music Assistant player is on")
                    return@post
                }
                val sel = list.indexOfFirst { it.first == target }
                Popup.menu(this, "Play on", list.map { (_, n, s) -> if (s == "playing") "$n  ♪" else n }, sel) { i ->
                    setTarget(list[i].first, list[i].second)
                    npPicture = null
                    refreshNow()
                }
            }
        }
    }

    private fun setTarget(id: String, name: String) {
        target = id
        targetName = name.substringBefore(" · ").trim()
        playOn.text = targetName
        Prefs.of(this).edit().putString("ma_player", id).apply()
    }

    /** Finds Music Assistant and the player to start with: the last one chosen here, or one in this TV's room. */
    private fun findMusicAssistant(): Boolean {
        val client = ha ?: return false
        return try {
            val e = client.template(
                "{% set p = integration_entities('music_assistant') | select('match', 'media_player') | list %}" +
                    "{{ config_entry_id(p[0]) if p else '' }}"
            ).trim()
            if (e.isBlank()) return false
            entry = e
            val list = players()
            val saved = Prefs.of(this).getString("ma_player", null)
            val adbArea = cfg.adbEntity?.let { a -> try { client.template("{{ area_id('$a') or '' }}").trim() } catch (x: Exception) { "" } } ?: ""
            val inRoom = if (adbArea.isNotBlank()) try {
                JSONArray(client.template("{{ area_entities('$adbArea') | select('in', integration_entities('music_assistant')) | select('match', 'media_player') | list | tojson }}"))
            } catch (x: Exception) { JSONArray() } else JSONArray()
            val roomIds = (0 until inRoom.length()).map { inRoom.getString(it) }
            val pick = list.firstOrNull { it.first == saved }
                ?: list.firstOrNull { it.first in roomIds }
                ?: list.firstOrNull { it.third == "playing" }
                ?: list.firstOrNull()
            if (pick != null) main.post { setTarget(pick.first, pick.second) }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun showIdle() {
        npTitle.text = "Nothing playing"
        npArtist.text = if (targetName.isNotBlank()) "on $targetName" else ""
        npQuality.visibility = View.GONE
        npNext.text = ""
        npElapsed.text = ""
        npTotal.text = ""
        npBar.layoutParams = npBar.layoutParams.apply { width = 0 }
        npPicture = null
        npCover.setImageDrawable(NameCover("♪", false, resources.displayMetrics.density))
        npPlay.setImageResource(R.drawable.ic_play)
        npPlaying = false
        npDuration = 0.0
    }

    /** Now playing: the player's state, and its queue for the track, the quality and what comes next. */
    private fun refreshNow() {
        val t = target ?: return
        val client = ha ?: return
        exec.execute {
            val s = try { client.state(t) } catch (e: Exception) { null }
            val q = try {
                client.callResponse("music_assistant", "get_queue", JSONObject().put("entity_id", t)).optJSONObject(t)
            } catch (e: Exception) { null }
            main.post {
                if (isFinishing) return@post
                val state = s?.optString("state") ?: ""
                val a = s?.optJSONObject("attributes") ?: JSONObject()
                val cur = q?.optJSONObject("current_item")
                val media = cur?.optJSONObject("media_item")
                val title = media?.optString("name")?.takeIf { it.isNotBlank() } ?: a.optString("media_title", "")
                if (title.isBlank() || (state != "playing" && state != "paused")) {
                    showIdle()
                    return@post
                }
                npPlaying = state == "playing"
                npTitle.text = title
                val artist = media?.let { artistsOf(it) }?.takeIf { it.isNotBlank() } ?: a.optString("media_artist", "")
                val album = media?.optJSONObject("album")?.optString("name", "") ?: a.optString("media_album_name", "")
                npArtist.text = listOf(artist, album).filter { it.isNotBlank() }.distinct().joinToString(" — ")
                // quality: the file type and sample rate
                val sd = cur?.optJSONObject("stream_details")
                val type = sd?.optString("content_type", "")?.uppercase() ?: ""
                val rate = sd?.optInt("sample_rate", 0) ?: 0
                if (type.isNotBlank()) {
                    val khz = java.lang.String.format(java.util.Locale.ROOT, "%.1f", rate / 1000.0).removeSuffix(".0")
                    npQuality.text = if (rate > 0) "$type · $khz kHz" else type
                    npQuality.visibility = View.VISIBLE
                } else npQuality.visibility = View.GONE
                npPlay.setImageResource(if (npPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                npDuration = cur?.optDouble("duration", 0.0) ?: a.optDouble("media_duration", 0.0)
                npElapsedS = q?.optDouble("elapsed_time", 0.0) ?: a.optDouble("media_position", 0.0)
                npElapsedAt = System.currentTimeMillis()
                updateProgress()
                val next = q?.optJSONObject("next_item")?.optJSONObject("media_item")
                npNext.text = if (next != null) "Up next:  ${next.optString("name")}" + (artistsOf(next).takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") else ""
                val pic = media?.let { imageOf(it) } ?: a.optString("entity_picture", "").ifBlank { null }
                if (pic != null && pic != npPicture) {
                    npPicture = pic
                    picture(pic, dp(220f)) { b -> if (b != null && npPicture == pic) npCover.setImageBitmap(b) }
                } else if (pic == null) {
                    npPicture = null
                    npCover.setImageDrawable(NameCover(album.ifBlank { title }, false, resources.displayMetrics.density))
                }
            }
        }
    }

    private fun updateProgress() {
        if (npDuration <= 0) {
            npElapsed.text = ""
            npTotal.text = ""
            npBar.layoutParams = npBar.layoutParams.apply { width = 0 }
            return
        }
        val now = if (npPlaying) npElapsedS + (System.currentTimeMillis() - npElapsedAt) / 1000.0 else npElapsedS
        val pos = now.coerceIn(0.0, npDuration)
        npElapsed.text = clock(pos)
        npTotal.text = clock(npDuration)
        val w = (npTrack.width * (pos / npDuration)).toInt()
        npBar.layoutParams = npBar.layoutParams.apply { width = w }
    }

    private fun clock(s: Double): String {
        val t = s.toInt()
        return "${t / 60}:${(t % 60).toString().padStart(2, '0')}"
    }

    private val nowLoop = object : Runnable {
        override fun run() {
            refreshNow()
            main.postDelayed(this, 4000)
        }
    }
    private val progressLoop = object : Runnable {
        override fun run() {
            updateProgress()
            main.postDelayed(this, 1000)
        }
    }

    /**
     * Covers. Music Assistant's picture proxy only makes 80, 160, 256, 512 or 1024 pixel versions
     * (0 is the original), so the next size up is asked for; any other size is refused.
     */
    private fun picture(url: String, width: Int, done: (Bitmap?) -> Unit) {
        val sized = if (url.contains("imageproxy") && url.contains("size=")) {
            val want = listOf(160, 256, 512, 1024).firstOrNull { it >= width } ?: 1024
            url.replace(Regex("size=\\d+"), "size=$want")
        } else url
        val key = "$width|$sized"
        covers[key]?.let {
            done(it)
            return
        }
        val client = ha ?: return
        try {
            exec.execute {
                val b = try { client.bitmap(sized, width) } catch (e: Exception) { null }
                main.post {
                    if (b != null) covers[key] = b
                    if (!isFinishing) done(b)
                }
            }
        } catch (e: Exception) { }
    }

    // ---------- lifecycle and keys ----------

    override fun onResume() {
        super.onResume()
        main.postDelayed(nowLoop, 4000)
        main.post(progressLoop)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(nowLoop)
        main.removeCallbacks(progressLoop)
    }

    override fun onDestroy() {
        super.onDestroy()
        exec.shutdownNow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { send("media_play_pause"); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { send("media_next_track"); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { send("media_previous_track"); return true }
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            finish()
            overridePendingTransition(0, 0)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

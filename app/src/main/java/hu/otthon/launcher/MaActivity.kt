package hu.otthon.launcher

import android.app.Activity
import android.graphics.Bitmap
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
 * Music Assistant on the TV: the library as rows and grids of covers (playlists, albums,
 * artists, radio), search, and a choice of where it plays: this TV, a speaker, any Music
 * Assistant player. OK plays at once; hold OK for play next, add to the queue or radio mode.
 * A bar at the bottom shows what the chosen player is playing, with previous / play / next.
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
    private var active = false

    private lateinit var backdrop: Backdrop
    private lateinit var body: LinearLayout
    private lateinit var scroll: ColScroller
    private lateinit var message: TextView
    private lateinit var playOn: TextView
    private val tabs = ArrayList<TextView>()
    private var tab = 0

    private lateinit var bar: LinearLayout
    private lateinit var barCover: ImageView
    private lateinit var barTitle: TextView
    private lateinit var barArtist: TextView
    private lateinit var barPlay: ImageView
    private var barPicture: String? = null

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
                if (found) {
                    Prefs.of(this).edit().putBoolean("ma_present", true).apply()
                    showTab(0)
                    refreshBar()
                } else {
                    Prefs.of(this).edit().putBoolean("ma_present", false).apply()
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
        // a deep scrim, darkest at the bottom where the covers and the player bar are
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xB3070810.toInt(), 0xD9070810.toInt(), 0xF2070810.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        col.setPadding(0, dp(30f), 0, dp(18f))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        FocusGlide.install(col)

        // title, tabs, search and the player it plays on
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_music)
            setColorFilter(C.TEXT)
        }, LinearLayout.LayoutParams(dp(26f), dp(26f)).apply { rightMargin = dp(12f) })
        head.addView(label("Music", 28f, C.TEXT, light = true), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(28f) })
        TABS.forEachIndexed { i, t ->
            val p = pill(t, null) { showTab(i) }
            val painted = p.onFocusChangeListener
            p.setOnFocusChangeListener { v, f ->
                painted?.onFocusChange(v, f)
                if (!f) paintTabs()
            }
            tabs.add(p)
            head.addView(p, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = dp(8f) })
        }
        head.addView(roundButton(R.drawable.ic_grid, "Search") { search() }.apply { setImageDrawable(searchIcon()) },
            LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { leftMargin = dp(12f) })
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        playOn = pill("Play on …", R.drawable.ic_music) { choosePlayer() }
        head.addView(playOn)
        col.addView(head, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(48f); rightMargin = dp(48f) })

        scroll = ColScroller(this).apply {
            clipToPadding = false
            clipChildren = false
            isVerticalScrollBarEnabled = false
            setPadding(0, dp(10f), 0, dp(20f))
        }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        scroll.addView(body, FrameLayout.LayoutParams(-1, -2))
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10f) })
        message = label("", 15f, C.TEXT2).apply { visibility = View.GONE }
        col.addView(message, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(48f) })

        buildBar()
        col.addView(bar, LinearLayout.LayoutParams(-1, dp(68f)).apply { leftMargin = dp(48f); rightMargin = dp(48f); topMargin = dp(8f) })

        setContentView(root)
        backdrop.setDim(maxOf(cfg.dim, 0.8f))
        backdrop.show(cfg.background, Backgrounds.phase(this, cfg), false)
        tabs.firstOrNull()?.requestFocus()
    }

    /** A magnifying glass, drawn: there is no search icon among the app's pictures. */
    private fun searchIcon(): android.graphics.drawable.Drawable = object : android.graphics.drawable.Drawable() {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        private var tint = C.TEXT
        override fun draw(c: android.graphics.Canvas) {
            val b = bounds
            val s = b.width() / 24f
            p.strokeWidth = 2f * s
            p.color = tint
            c.drawCircle(b.left + 10.5f * s, b.top + 10.5f * s, 6.5f * s, p)
            c.drawLine(b.left + 15.5f * s, b.top + 15.5f * s, b.left + 20f * s, b.top + 20f * s, p)
        }
        override fun setTintList(t: android.content.res.ColorStateList?) { tint = t?.defaultColor ?: C.TEXT; invalidateSelf() }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { p.colorFilter = cf; invalidateSelf() }
        override fun setAlpha(a: Int) { p.alpha = a }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun roundButton(icon: Int, name: String, onClick: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(icon)
        setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        isFocusable = true
        isClickable = true
        contentDescription = name
        val normal = Look.surface(this@MaActivity, dp(22f).toFloat())
        background = normal
        setColorFilter(C.TEXT)
        FocusGlide.shape(this, -1f)
        setOnFocusChangeListener { v, f ->
            background = if (f) FocusGlide.fill(this, dp(22f).toFloat()) else normal
            setColorFilter(if (f) C.DARK else C.TEXT)
            Look.grow(v, f)
        }
        setOnClickListener { onClick() }
    }

    private fun buildBar() {
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Look.surface(this@MaActivity, dp(34f).toFloat())
            setPadding(dp(10f), 0, dp(12f), 0)
            visibility = View.INVISIBLE
        }
        barCover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(0xFF2A2C35.toInt(), dp(24f).toFloat())
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        bar.addView(barCover, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14f), 0, dp(14f), 0) }
        barTitle = label("", 15f, C.TEXT, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        barArtist = label("", 12f, C.TEXT3).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3f), 0, 0) }
        text.addView(barTitle)
        text.addView(barArtist)
        bar.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(roundButton(R.drawable.ic_prev, "Previous") { send("media_previous_track") }, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        barPlay = roundButton(R.drawable.ic_play, "Play / pause") { send("media_play_pause") }
        bar.addView(barPlay, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { leftMargin = dp(10f) })
        bar.addView(roundButton(R.drawable.ic_next, "Next") { send("media_next_track") }, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { leftMargin = dp(10f) })
    }

    private fun showMessage(text: String) {
        body.removeAllViews()
        message.text = text
        message.visibility = View.VISIBLE
    }

    private fun paintTabs() {
        tabs.forEachIndexed { i, t ->
            // the open tab keeps a light edge, so it can be told apart without the cursor on it
            if (!t.isFocused) t.background = if (i == tab) rounded(Look.SURFACE, dp(22f).toFloat(), dp(2f), 0xCCFFFFFF.toInt()) else Look.surface(this, dp(22f).toFloat())
        }
    }

    // ---------- the library ----------

    private fun showTab(i: Int) {
        tab = i
        paintTabs()
        val g = ++gen
        message.visibility = View.GONE
        body.removeAllViews()
        body.addView(label("Loading…", 14f, C.TEXT3), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(48f); topMargin = dp(20f) })
        exec.execute {
            val sections = try {
                when (i) {
                    0 -> listOfNotNull(
                        section("Recently played", "album", "last_played_desc", 24),
                        section("Playlists", "playlist", "last_played_desc", 24),
                        section("Radio", "radio", "name", 24),
                        section("Artists you play most", "artist", "play_count_desc", 24),
                        section("Recently added albums", "album", "timestamp_added_desc", 24)
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
            val artists = o.optJSONArray("artists")
            val sub = when {
                artists != null && artists.length() > 0 -> (0 until artists.length()).mapNotNull { artists.optJSONObject(it)?.optString("name") }.joinToString(", ")
                else -> o.optJSONObject("album")?.optString("name", "") ?: ""
            }
            val image = o.optString("image", "").ifBlank { null }
                ?: o.optJSONObject("album")?.optString("image", "")?.ifBlank { null }
            out.add(Item(uri, o.optString("media_type", ""), o.optString("name", ""), sub, image))
        }
        return out
    }

    /** Home: one row of covers per section. */
    private fun showRows(sections: List<Section>) {
        body.removeAllViews()
        for (s in sections) {
            body.addView(label(s.title, 15f, C.TEXT, bold = true), LinearLayout.LayoutParams(-2, -2).apply {
                leftMargin = dp(48f)
                topMargin = if (body.childCount > 0) dp(18f) else dp(6f)
            })
            val rowScroll = RowScroller(this)
            rowScroll.setPadding(dp(48f), dp(12f), dp(48f), dp(8f))
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
            s.items.forEachIndexed { i, item ->
                line.addView(card(item, dp(148f)), LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = dp(18f) })
            }
            rowScroll.addView(line)
            body.addView(rowScroll, LinearLayout.LayoutParams(-1, -2))
        }
        scroll.scrollTo(0, 0)
    }

    /** A tab: all of one kind, six covers to a line. */
    private fun showGrid(s: Section, perLine: Int = 6) {
        body.removeAllViews()
        val gap = dp(18f)
        val w = (resources.displayMetrics.widthPixels - 2 * dp(48f) - (perLine - 1) * gap) / perLine
        var line: LinearLayout? = null
        s.items.forEachIndexed { i, item ->
            if (i % perLine == 0) {
                line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
                body.addView(line, LinearLayout.LayoutParams(-1, -2).apply {
                    leftMargin = dp(48f)
                    rightMargin = dp(48f)
                    topMargin = if (i == 0) dp(8f) else dp(16f)
                })
            }
            line!!.addView(card(item, w), LinearLayout.LayoutParams(w, -2).apply { if (i % perLine > 0) leftMargin = gap })
        }
        scroll.scrollTo(0, 0)
    }

    /** A square cover with the name and the artist under it. */
    private fun card(item: Item, size: Int): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        val round = item.type == "artist"
        val radius = if (round) size / 2f else dp(12f).toFloat()
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(0xFF262833.toInt(), radius)
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            isFocusable = true
            isClickable = true
            isLongClickable = true
            contentDescription = item.name
            setImageResource(when (item.type) { "radio" -> R.drawable.ic_wind; "artist" -> R.drawable.ic_person; else -> R.drawable.ic_disc })
            setColorFilter(0x66FFFFFF)
            val pad = size / 3
            setPadding(pad, pad, pad, pad)
        }
        FocusGlide.shape(cover, radius)
        FocusGlide.glow(cover, 0xFFE9E7E2.toInt())
        val name = label(item.name, 13f, C.TEXT2, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2f), dp(10f), dp(2f), 0)
            if (round) gravity = Gravity.CENTER
        }
        val sub = label(item.sub, 11f, C.TEXT3).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2f), dp(2f), dp(2f), 0)
            if (round) gravity = Gravity.CENTER
            visibility = if (item.sub.isBlank()) View.GONE else View.VISIBLE
        }
        cover.setOnFocusChangeListener { v, f ->
            Look.grow(v, f, 1.07f)
            name.setTextColor(if (f) C.TEXT else C.TEXT2)
            name.animate().translationY(if (f) dp(5f).toFloat() else 0f).setDuration(200).start()
            sub.animate().translationY(if (f) dp(5f).toFloat() else 0f).setDuration(200).start()
        }
        cover.setOnClickListener { play(item, "replace", false) }
        cover.setOnLongClickListener { menu(item); true }
        box.addView(cover, LinearLayout.LayoutParams(size, size))
        box.addView(name, LinearLayout.LayoutParams(size, -2))
        box.addView(sub, LinearLayout.LayoutParams(size, -2))
        item.image?.let { url ->
            picture(url, size) { b ->
                if (b != null) {
                    cover.clearColorFilter()
                    cover.setPadding(0, 0, 0, 0)
                    cover.setImageBitmap(b)
                }
            }
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
            body.addView(label("Searching “$q”…", 14f, C.TEXT3), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(48f); topMargin = dp(20f) })
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
                main.postDelayed({ refreshBar() }, 1200)
            }
        }
    }

    private fun send(service: String) {
        val t = target ?: return
        val client = ha ?: return
        exec.execute {
            try { client.call("media_player", service, t) } catch (e: Exception) { }
            main.postDelayed({ refreshBar() }, 600)
        }
    }

    /** The players, with this TV's room first; the choice is kept on this TV. */
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
        val client = ha ?: return
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
                    Prefs.of(this).edit().putString("ma_player", list[i].first).apply()
                    refreshBar()
                }
            }
        }
    }

    private fun setTarget(id: String, name: String) {
        target = id
        targetName = name.substringBefore(" · ")
        playOn.text = "Play on: $targetName"
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

    /** The player bar: what the chosen player is playing now. */
    private fun refreshBar() {
        val t = target ?: return
        val client = ha ?: return
        exec.execute {
            val s = try { client.state(t) } catch (e: Exception) { null }
            main.post {
                if (s == null || isFinishing) return@post
                val state = s.optString("state")
                val a = s.optJSONObject("attributes") ?: JSONObject()
                val title = a.optString("media_title", "")
                if (title.isBlank() || (state != "playing" && state != "paused")) {
                    bar.visibility = View.INVISIBLE
                    return@post
                }
                bar.visibility = View.VISIBLE
                barTitle.text = title
                barArtist.text = listOf(a.optString("media_artist", ""), a.optString("media_album_name", "")).filter { it.isNotBlank() }.joinToString(" — ").ifBlank { targetName }
                barPlay.setImageResource(if (state == "playing") R.drawable.ic_pause else R.drawable.ic_play)
                val pic = a.optString("entity_picture", "")
                if (pic.isNotBlank() && pic != barPicture) {
                    barPicture = pic
                    picture(pic, dp(48f)) { b -> if (b != null) barCover.setImageBitmap(b) }
                }
            }
        }
    }

    private val barLoop = object : Runnable {
        override fun run() {
            refreshBar()
            main.postDelayed(this, 4000)
        }
    }

    /** Covers: Music Assistant's picture proxy can make them small, so they load quickly. */
    private fun picture(url: String, width: Int, done: (Bitmap?) -> Unit) {
        val sized = if (url.contains("imageproxy") && url.contains("size=0")) url.replace("size=0", "size=${(width * 1.2f).toInt().coerceAtMost(500)}") else url
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
        active = true
        main.postDelayed(barLoop, 4000)
    }

    override fun onPause() {
        super.onPause()
        active = false
        main.removeCallbacks(barLoop)
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

package hu.otthon.launcher

import android.content.Intent
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
 * Music Assistant on the TV, as pages, the way the streaming apps do it:
 *  - Library, the full width, in the film library's look: the chosen album's or artist's picture
 *    fills the screen behind its name and story (from TheAudioDB and Wikipedia, see [MusicInfo]),
 *    the covers below in rows that turn in space where there is memory for it (Settings ›
 *    Library › 3D effects). Tabs (Home, Playlists, Albums, Artists, Radio) and search. In the
 *    top right corner, always one press up away, a Now playing chip and the player it plays on.
 *  - An album or playlist opens on its own page over its picture: the cover, the name, its year
 *    and genre and story, Play / Shuffle / Radio, and the songs. OK on a song plays the album from
 *    that song; holding OK gives more choices.
 *  - An artist opens on a page with the photo, the biography and the artist's albums.
 *  - Now playing is a page of its own: the cover large, the cover blurred as the background,
 *    the song, the quality, progress, the controls and what comes next.
 * Back goes back a page; from the library it closes the screen.
 */
class MaActivity : PanelActivity() {

    /** One thing in the library. */
    private class Item(val uri: String, val type: String, val name: String, val sub: String, val image: String?)

    private class Section(val title: String, val items: List<Item>)

    /** A page under the one on top, and the control to give focus back to. */
    private class Under(val page: View, val focus: View?)

    /** A player: entity, name to show, state. */
    private class Player(val id: String, val name: String, val state: String)

    private lateinit var cfg: Config
    private var ha: HaClient? = null
    private val exec = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private var gen = 0

    private lateinit var backdrop: Backdrop
    private lateinit var pages: FrameLayout
    /** The chosen item's picture over the whole screen, and the next one fading in over it. */
    private lateinit var art: ImageView
    private lateinit var artNext: ImageView
    private var artKey: String? = null
    private val arts = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 6
    }
    /** Stories are fetched one at a time (TheAudioDB answers 30 questions a minute). */
    private val infoExec = Executors.newSingleThreadExecutor()
    private var deep = false

    // the library's chosen item, big
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroText: TextView
    private var heroItem: Item? = null
    private val under = ArrayList<Under>()

    // library
    private lateinit var library: View
    private lateinit var body: LinearLayout
    private lateinit var scroll: ColScroller
    private lateinit var message: TextView
    private val tabs = ArrayList<TextView>()
    private var tab = 0
    private var pageWidth = 0

    // the chips in the corner of every page, kept up to date together
    private val nowChips = ArrayList<NowChip>()
    private val playerPills = ArrayList<TextView>()
    /** Song rows on open album pages: the song's uri and its "playing" mark. */
    private val songRows = ArrayList<Pair<String, TextView>>()

    // Now playing
    private var nowPage: FrameLayout? = null
    private lateinit var npBack: ImageView
    private lateinit var npCover: ImageView
    private lateinit var npFrom: TextView
    private lateinit var npTitle: TextView
    private lateinit var npArtist: TextView
    private lateinit var npQuality: TextView
    private lateinit var npTrack: FrameLayout
    private lateinit var npBar: View
    private lateinit var npElapsed: TextView
    private lateinit var npTotal: TextView
    private lateinit var npPlay: ImageView
    private lateinit var npNext: TextView
    private lateinit var npAlbum: TextView

    // what plays, as last read
    private var npPicture: String? = null
    private var npBitmap: Bitmap? = null
    private var npPlaying = false
    private var npActive = false
    private var npTitleText = ""
    private var npArtistText = ""
    private var npUri: String? = null
    private var npAlbumItem: Item? = null
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
    private val SIDE = 44f
    private val TOP = 26f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config.load(this)
        ha = cfg.client()
        buildUi()
        if (ha == null) {
            showMessage("Set up or switch on Home Assistant first.")
            return
        }
        val wantNow = intent.getBooleanExtra("now", false)
        exec.execute {
            val found = findMusicAssistant()
            main.post {
                Prefs.of(this).edit().putBoolean("ma_present", found).apply()
                if (found) {
                    showTab(0)
                    refreshNow()
                    if (wantNow) openNow()
                } else {
                    showMessage("Music Assistant was not found in Home Assistant.")
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("now", false) && entry != null) openNow()
    }

    // ---------- pages ----------

    private fun buildUi() {
        val root = FrameLayout(this)
        deep = !LowRam.on && MediaActivity.threeD(this)
        backdrop = Backdrop(this)
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xD9070810.toInt(), 0xC7070810.toInt(), 0xE6070810.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))
        // the chosen item's picture, faded into the dark to the left and to the bottom (as in the film library)
        art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        artNext = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        root.addView(art, FrameLayout.LayoutParams(-1, -1))
        root.addView(artNext, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF20B0C10.toInt(), 0xB30B0C10.toInt(), 0x330B0C10))
        }, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xFF0B0C10.toInt(), 0xE60B0C10.toInt(), 0x000B0C10))
        }, FrameLayout.LayoutParams(-1, dp(380f), Gravity.BOTTOM))
        pages = FrameLayout(this).apply { clipChildren = false }
        root.addView(pages, FrameLayout.LayoutParams(-1, -1))
        FocusGlide.install(pages)
        setContentView(root)
        pageWidth = resources.displayMetrics.widthPixels - 2 * dp(SIDE)
        library = buildLibrary()
        pages.addView(library, FrameLayout.LayoutParams(-1, -1))
        backdrop.setDim(maxOf(cfg.dim, 0.75f))
        backdrop.show(cfg.background, Backgrounds.phase(this, cfg), false)
        tabs.firstOrNull()?.requestFocus()
    }

    /** Puts [page] on top; Back brings back the one under it, with focus where it was. */
    private fun push(page: View, focus: View?) {
        val top = pages.getChildAt(pages.childCount - 1)
        under.add(Under(top, currentFocus))
        top.visibility = View.GONE
        pages.addView(page, FrameLayout.LayoutParams(-1, -1))
        if (Motion.level != Motion.OFF) {
            page.alpha = 0f
            page.translationX = if (Motion.level == Motion.FULL) dp(28f).toFloat() else 0f
            page.animate().alpha(1f).translationX(0f).setDuration(200).start()
        }
        page.post { (focus ?: firstFocusable(page))?.requestFocus() }
    }

    private fun pop(): Boolean {
        if (under.isEmpty()) return false
        val top = pages.getChildAt(pages.childCount - 1)
        pages.removeView(top)
        val u = under.removeAt(under.size - 1)
        u.page.visibility = View.VISIBLE
        u.focus?.requestFocus() ?: firstFocusable(u.page)?.requestFocus()
        // the corner chips and song rows of a page that is gone are not updated any more
        nowChips.removeAll { !isIn(it, pages) }
        playerPills.removeAll { !isIn(it, pages) }
        songRows.removeAll { !isIn(it.second, pages) }
        return true
    }

    private fun isIn(v: View, parent: View): Boolean {
        var p = v.parent
        while (p != null) {
            if (p === parent) return true
            p = p.parent
        }
        return false
    }

    /** A page: a column with the side margins, its title on the left and the corner on the right. */
    private fun page(title: String?, extra: View? = null, chip: Boolean = true): Pair<LinearLayout, LinearLayout> {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(SIDE), dp(TOP), dp(SIDE), 0)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        if (title != null) head.addView(label(title, 13f, C.TEXT3, bold = true).apply { letterSpacing = 0.08f }, LinearLayout.LayoutParams(-2, -2))
        if (extra != null) head.addView(extra)
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(corner(chip))
        col.addView(head, LinearLayout.LayoutParams(-1, dp(40f)))
        return col to head
    }

    /** The top right corner of a page: what plays (opens Now playing) and the player. */
    private fun corner(withChip: Boolean): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        if (withChip) {
            val chip = NowChip()
            nowChips.add(chip)
            chip.update()
            box.addView(chip, LinearLayout.LayoutParams(-2, dp(36f)).apply { rightMargin = dp(8f) })
        }
        box.addView(playerPill(), LinearLayout.LayoutParams(-2, -2))
        return box
    }

    private fun playerPill(): TextView = pill(targetName.ifBlank { "Choose a player" }, R.drawable.ic_speaker) { choosePlayer(null) }.apply {
        maxWidth = dp(200f)
        ellipsize = TextUtils.TruncateAt.END
        contentDescription = "Play on"
        playerPills.add(this)
    }

    /** "Now playing" in a page's corner: a small cover and the song; OK opens the Now playing page. */
    private inner class NowChip : LinearLayout(this@MaActivity) {
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(0xFF2A2C36.toInt(), dp(6f).toFloat())
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        val bars = Bars(context)
        val text = label("Now playing", 12f, C.TEXT, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(190f)
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            contentDescription = "Now playing"
            setPadding(dp(5f), 0, dp(14f), 0)
            addView(cover, LayoutParams(dp(26f), dp(26f)))
            addView(bars, LayoutParams(dp(12f), dp(12f)).apply { leftMargin = dp(9f) })
            addView(text, LayoutParams(-2, -2).apply { leftMargin = dp(7f) })
            FocusGlide.shape(this, dp(Look.R_TILE).toFloat())
            paint(false)
            setOnFocusChangeListener { _, f -> paint(f) }
            setOnClickListener { openNow() }
        }

        private fun paint(f: Boolean) {
            background = if (f) FocusGlide.fill(this, dp(Look.R_TILE).toFloat()) else Look.surface(context, dp(Look.R_TILE).toFloat())
            text.setTextColor(if (f) C.DARK else C.TEXT)
            bars.color = if (f) C.DARK else C.ON
        }

        fun update() {
            text.text = if (npActive) npTitleText else "Now playing"
            bars.visibility = if (npActive) View.VISIBLE else View.GONE
            bars.moving = npPlaying
            val b = npBitmap
            if (npActive && b != null) cover.setImageBitmap(b)
            else cover.setImageDrawable(NameCover("♪", true, resources.displayMetrics.density))
        }
    }

    // ---------- the library ----------

    private fun buildLibrary(): View {
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        TABS.forEachIndexed { i, t ->
            val p = pill(t, null) { showTab(i) }
            val painted = p.onFocusChangeListener
            p.setOnFocusChangeListener { v, f ->
                painted?.onFocusChange(v, f)
                if (!f) paintTabs()
            }
            tabs.add(p)
            tabRow.addView(p, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = dp(6f) })
        }
        tabRow.addView(roundButton(null, "Search") { search() }.apply { setImageDrawable(searchIcon()) },
            LinearLayout.LayoutParams(dp(36f), dp(36f)).apply { leftMargin = dp(10f) })
        val (col, _) = page(null, tabRow)
        // the chosen album, artist or playlist, big: its name, what it is, its story
        val hero = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroTitle = label("", 26f, C.TEXT, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        heroMeta = label("", 12f, C.TEXT2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        heroText = label("", 12f, 0xFFD9D7D2.toInt()).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
        hero.addView(heroTitle)
        hero.addView(heroMeta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })
        hero.addView(heroText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        col.addView(hero, LinearLayout.LayoutParams(dp(600f), dp(132f)).apply { topMargin = dp(14f) })
        scroll = ColScroller(this).apply {
            clipToPadding = false
            clipChildren = false
            isVerticalScrollBarEnabled = false
            setPadding(0, dp(12f), 0, dp(30f))
        }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        scroll.addView(body, FrameLayout.LayoutParams(-1, -2))
        // scrolled up, the covers pass under the story, not over it
        val clip = FrameLayout(this).apply { clipChildren = true }
        clip.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        col.addView(clip, LinearLayout.LayoutParams(-1, 0, 1f))
        message = label("", 14f, C.TEXT2).apply { visibility = View.GONE }
        col.addView(message, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(40f) })
        return col
    }

    private fun showMessage(text: String) {
        body.removeAllViews()
        message.text = text
        message.visibility = View.VISIBLE
    }

    private fun paintTabs() {
        tabs.forEachIndexed { i, t ->
            // the open tab keeps a light edge, so it can be told apart without the cursor on it
            if (!t.isFocused) t.background = if (i == tab) rounded(Look.SURFACE_SOLID, dp(Look.R_TILE).toFloat(), dp(2f), 0xCCFFFFFF.toInt()) else Look.surface(this, dp(Look.R_TILE).toFloat())
        }
    }

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
                    2 -> listOfNotNull(section("Albums", "album", "name", 300))
                    3 -> listOfNotNull(section("Artists", "artist", "name", 300))
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
                    i == 0 -> showRows(body, sections)
                    else -> showGrid(body, sections[0].items, pageWidth, 6)
                }
                heroItem = null
                sections?.firstOrNull()?.items?.firstOrNull()?.let { hero(it) }
                scroll.scrollTo(0, 0)
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
        val r = client.callResponse("music_assistant", "get_library", data, 20000)
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

    /** Rows of covers, one per section. */
    private fun showRows(into: LinearLayout, sections: List<Section>) {
        into.removeAllViews()
        for (s in sections) {
            into.addView(label(s.title, 14f, C.TEXT, bold = true), LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = if (into.childCount > 0) dp(14f) else dp(6f)
            })
            val rowScroll = RowScroller(this)
            rowScroll.setPadding(0, dp(12f), dp(20f), dp(6f))
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
            s.items.forEachIndexed { i, item ->
                line.addView(card(item, dp(124f)), LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = dp(16f) })
            }
            rowScroll.addView(line)
            into.addView(rowScroll, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /** A grid of covers, [perLine] to a line across [width]. */
    private fun showGrid(into: LinearLayout, items: List<Item>, width: Int, perLine: Int) {
        into.removeAllViews()
        val gap = dp(18f)
        val w = (width - (perLine - 1) * gap) / perLine
        var line: LinearLayout? = null
        items.forEachIndexed { i, item ->
            if (i % perLine == 0) {
                line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
                into.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (i == 0) dp(10f) else dp(14f) })
            }
            line!!.addView(card(item, w), LinearLayout.LayoutParams(w, -2).apply { if (i % perLine > 0) leftMargin = gap })
        }
    }

    private fun coverView(item: Item, size: Int, round: Boolean, radius: Float): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = rounded(0xFF262833.toInt(), if (round) size / 2f else radius)
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        setImageDrawable(NameCover(item.name, round, resources.displayMetrics.density))
        item.image?.let { url -> picture(url, size) { b -> if (b != null) setImageBitmap(b) } }
    }

    /** A square cover (round for artists) with the name and the artist under it. */
    private fun card(item: Item, size: Int): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        val round = item.type == "artist"
        val radius = if (round) size / 2f else dp(Look.R_TILE).toFloat()
        val cover = coverView(item, size, round, radius).apply {
            isFocusable = true
            isClickable = true
            isLongClickable = true
            contentDescription = item.name
        }
        FocusGlide.shape(cover, radius)
        FocusGlide.glow(cover, 0xFFE9E7E2.toInt())
        val name = label(clean(item.name), 12f, C.TEXT2, bold = true).apply {
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
            visibility = if (item.sub.isBlank() || round) View.GONE else View.VISIBLE
        }
        cover.setOnFocusChangeListener { v, f ->
            Look.grow(v, f, if (deep) 1.1f else 1.06f)
            name.setTextColor(if (f) C.TEXT else C.TEXT2)
            name.animate().translationY(if (f) dp(6f).toFloat() else 0f).setDuration(200).start()
            sub.animate().translationY(if (f) dp(6f).toFloat() else 0f).setDuration(200).start()
            val line = box.parent as? LinearLayout
            if (f) {
                if (isIn(box, library)) hero(item)
                if (deep && line != null) { lean(line, box); light(line) }
            } else if (deep && line != null) {
                line.post { if (line.findFocus() == null) flatten(line) }
            }
        }
        cover.setOnClickListener { open(item) }
        cover.setOnLongClickListener { menu(item); true }
        box.addView(cover, LinearLayout.LayoutParams(size, size))
        box.addView(name, LinearLayout.LayoutParams(size, -2))
        box.addView(sub, LinearLayout.LayoutParams(size, -2))
        return box
    }

    private fun clean(name: String) = name.replace('_', ' ').replace(Regex("\\s+"), " ").trim()

    // ---------- 3D, as in the film library ----------

    /** The focused cover faces you, the ones beside it turn in towards it and sink back. */
    private fun lean(line: LinearLayout, focused: View) {
        val at = line.indexOfChild(focused)
        val camera = 9f * resources.displayMetrics.densityDpi
        for (i in 0 until line.childCount) {
            val c = line.getChildAt(i)
            val d = i - at
            val n = Math.abs(d)
            c.cameraDistance = camera
            c.pivotY = c.height * 0.4f
            val turn = if (n == 0) 0f else (24f + (n - 1) * 5f).coerceAtMost(38f) * (if (d < 0) 1f else -1f)
            val a = c.animate().rotationY(turn).alpha(if (n == 0) 1f else (1f - n * 0.1f).coerceAtLeast(0.55f))
            a.scaleX(if (n == 0) 1f else 0.93f).scaleY(if (n == 0) 1f else 0.93f)
            a.setDuration(320).setInterpolator(android.view.animation.DecelerateInterpolator(2.2f)).start()
        }
    }

    private fun flatten(line: LinearLayout) {
        for (i in 0 until line.childCount) line.getChildAt(i).animate().rotationY(0f).alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(300).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
    }

    /** The row with the cursor is lit, the other rows of the page step back (rows only, not the long grids). */
    private fun light(line: LinearLayout) {
        val row = line.parent as? RowScroller ?: return
        val holder = row.parent as? ViewGroup ?: return
        for (i in 0 until holder.childCount) {
            val r = holder.getChildAt(i)
            if (r !is RowScroller) continue
            val to = if (r === row) 1f else 0.6f
            if (r.alpha == to) continue
            r.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            r.animate().alpha(to).setDuration(260).withEndAction { if (r.alpha == 1f) r.setLayerType(View.LAYER_TYPE_NONE, null) }.start()
        }
    }

    // ---------- the chosen item, big ----------

    private val heroLater = Runnable { heroItem?.let { heroDetails(it) } }

    private fun kindName(type: String) = when (type) {
        "album" -> "Album"; "artist" -> "Artist"; "playlist" -> "Playlist"; "radio" -> "Radio"; "track" -> "Song"; else -> ""
    }

    private fun hero(item: Item) {
        if (heroItem?.uri == item.uri) return
        heroItem = item
        heroTitle.text = clean(item.name)
        val known = infoOf(item, cachedOnly = true)
        heroMeta.text = metaOf(item, known)
        heroText.text = known?.text ?: ""
        if (deep) for ((k, v) in listOf(heroTitle, heroMeta, heroText).withIndex()) {
            v.animate().cancel()
            v.alpha = 0f
            v.translationX = dp(16f).toFloat()
            v.animate().alpha(1f).translationX(0f).setStartDelay(k * 40L).setDuration(280)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        }
        // the story and the picture when the cursor rests a moment (fast moves stay calm)
        main.removeCallbacks(heroLater)
        main.postDelayed(heroLater, 380)
    }

    private fun metaOf(item: Item, info: MusicInfo.Info?): String =
        // the names as they are; only the words of the launcher are translated
        (listOf(Lang.tr(kindName(item.type)), if (item.type == "artist") "" else item.sub) +
            (info?.meta?.split(" · ")?.map { if (it.startsWith("since ")) Lang.tr(it) else it } ?: emptyList()))
            .filter { it.isNotBlank() }.joinToString("  ·  ")

    /** What the internet says about [item] (null for playlists and radio). Background thread unless [cachedOnly]. */
    private fun infoOf(item: Item, cachedOnly: Boolean = false): MusicInfo.Info? {
        val artist = item.sub.substringBefore(",").trim()
        return when (item.type) {
            "artist" -> if (cachedOnly) MusicInfo.cached(MusicInfo.artistKey(item.name)) else MusicInfo.artist(applicationContext, item.name)
            "album" -> if (cachedOnly) MusicInfo.cached(MusicInfo.albumKey(artist, item.name)) else MusicInfo.album(applicationContext, artist, item.name)
            else -> null
        }
    }

    private fun heroDetails(item: Item) {
        try {
            infoExec.execute {
                // the cursor has moved on while this waited its turn: not asked for any more
                if (heroItem !== item) return@execute
                val info = try { infoOf(item) } catch (e: Exception) { null }
                main.post {
                    if (isFinishing || heroItem !== item) return@post
                    heroMeta.text = metaOf(item, info)
                    if (info != null && heroText.text.isEmpty()) {
                        heroText.alpha = 0f
                        heroText.animate().alpha(1f).setDuration(300).start()
                    }
                    heroText.text = info?.text ?: ""
                    showArtOf(item, info)
                }
            }
        } catch (e: Exception) { }
    }

    /** The big picture for [item]: the artist's wide photo, else its cover, blurred. */
    private fun showArtOf(item: Item, info: MusicInfo.Info?) {
        val wide = info?.backdrop
        if (wide != null) {
            artPicture(wide) { b -> if (b != null) showArt(wide, b) else coverArt(item) }
        } else coverArt(item)
    }

    private fun coverArt(item: Item) {
        val url = item.image ?: return
        val key = "blur|$url"
        arts[key]?.let { showArt(key, it); return }
        picture(url, 256) { b ->
            if (b == null) return@picture
            val blurred = blur(b)
            arts[key] = blurred
            showArt(key, blurred)
        }
    }

    private fun artPicture(url: String, done: (Bitmap?) -> Unit) {
        arts[url]?.let { done(it); return }
        val client = ha ?: return done(null)
        val width = if (LowRam.on) 960 else 1600
        try {
            exec.execute {
                val b = try { client.bitmap(url, width, 10000) } catch (e: Throwable) { null }
                main.post {
                    if (b != null) arts[url] = b
                    if (!isFinishing) done(b)
                }
            }
        } catch (e: Exception) { }
    }

    private fun showArt(key: String, b: Bitmap) {
        if (artKey == key) return
        artKey = key
        artNext.animate().cancel()
        artNext.setImageBitmap(b)
        artNext.alpha = 0f
        artNext.animate().alpha(1f).setDuration(450).withEndAction {
            art.setImageBitmap(b)
            art.alpha = 1f
            art.scaleX = 1f; art.scaleY = 1f
            artNext.alpha = 0f
            // a slow drift into the picture
            if (deep) art.animate().scaleX(1.07f).scaleY(1.07f).setDuration(22_000).setInterpolator(null).start()
        }.start()
    }

    /** OK on a cover: albums, playlists and artists open; radio stations and songs play. */
    private fun open(item: Item) {
        when (item.type) {
            "album", "playlist" -> openCollection(item)
            "artist" -> openArtist(item)
            else -> play(item, "replace", false)
        }
    }

    private fun menu(item: Item) {
        val options = listOf("Play now", "Play next", "Add to the queue", "Radio: more like this", "Play on another player…")
        Popup.menu(this, clean(item.name), options) { which ->
            when (which) {
                0 -> play(item, "replace", false)
                1 -> play(item, "next", false)
                2 -> play(item, "add", false)
                3 -> play(item, "replace", true)
                else -> choosePlayer { play(item, "replace", false) }
            }
        }
    }

    private fun search() {
        Popup.text(this, "Search music", "", "An artist, album, song or radio station") { q ->
            if (q.isBlank()) return@text
            while (under.isNotEmpty()) pop()
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
                            showRows(body, sections)
                            scroll.scrollTo(0, 0)
                            (body.getChildAt(1) as? ViewGroup)?.let { firstFocusable(it)?.requestFocus() }
                        }
                    }
                }
            }
        }
    }

    private fun firstFocusable(v: View): View? {
        if (v.isFocusable && v.visibility == View.VISIBLE && v !is ViewGroup) return v
        if (v.isFocusable && v.visibility == View.VISIBLE && v is ViewGroup && v.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS) return v
        if (v is ViewGroup && v.visibility == View.VISIBLE) {
            // the corner is the last place to start from
            for (i in 0 until v.childCount) {
                val c = v.getChildAt(i)
                if (c is NowChip) continue
                firstFocusable(c)?.let { if (it !in playerPills) return it }
            }
        }
        return null
    }

    // ---------- an album or a playlist ----------

    /** A song in an album or playlist, from Home Assistant's media browser. */
    private class Song(val uri: String, val title: String, val artist: String)

    private fun songsOf(item: Item): Pair<List<Song>, String?> {
        val client = ha ?: return emptyList<Song>() to null
        val player = target ?: return emptyList<Song>() to null
        val r = client.callResponse("media_player", "browse_media", JSONObject()
            .put("entity_id", player)
            .put("media_content_id", item.uri)
            .put("media_content_type", item.type), 30000)
        val node = r.optJSONObject(player) ?: return emptyList<Song>() to null
        val kids = node.optJSONArray("children") ?: JSONArray()
        val out = ArrayList<Song>()
        for (i in 0 until minOf(kids.length(), 500)) {
            val k = kids.optJSONObject(i) ?: continue
            val uri = k.optString("media_content_id", "")
            if (uri.isBlank()) continue
            // the browser names a song "Artist - Title"
            val full = k.optString("title", "")
            val cut = full.indexOf(" - ")
            val (artist, title) = if (cut > 0) full.substring(0, cut) to full.substring(cut + 3) else "" to full
            out.add(Song(uri, title, artist))
        }
        val thumb = node.optString("thumbnail", "").takeIf { it.isNotBlank() && it != "null" }
            ?: kids.optJSONObject(0)?.optString("thumbnail", "")?.takeIf { it.isNotBlank() && it != "null" }
        return out to thumb
    }

    private fun openCollection(item: Item) {
        if (target == null) {
            choosePlayer { openCollection(item) }
            return
        }
        val (col, _) = page(if (item.type == "playlist") "PLAYLIST" else "ALBUM")
        val split = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        col.addView(split, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(14f) })

        // left: the cover beside the names, the story, the buttons (over the album's picture)
        val leftW = dp(330f)
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; clipChildren = false }
        val coverSize = dp(150f)
        val cover = coverView(item, coverSize, false, dp(Look.R_TILE).toFloat()).apply { elevation = dp(12f).toFloat() }
        top.addView(cover, LinearLayout.LayoutParams(coverSize, coverSize))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(label(clean(item.name), 19f, C.TEXT, bold = true).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        })
        if (item.sub.isNotBlank()) names.addView(label(item.sub, 13f, C.TEXT2).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })
        val facts = label("", 11f, C.TEXT3).apply { maxLines = 2 }
        names.addView(facts, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })
        val count = label("", 11f, C.TEXT3)
        names.addView(count, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2f) })
        top.addView(names, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(16f) })
        left.addView(top, LinearLayout.LayoutParams(-1, -2))
        val story = label("", 11f, 0xFFD9D7D2.toInt()).apply { maxLines = 9; ellipsize = TextUtils.TruncateAt.END; visibility = View.GONE }
        left.addView(story, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14f) })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        val playBtn = pill("Play", R.drawable.ic_play) { play(item, "replace", false, shuffle = false) }
        buttons.addView(playBtn)
        buttons.addView(pill("Shuffle", null) { play(item, "replace", false, shuffle = true) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8f) })
        buttons.addView(pill("Radio", null) { play(item, "replace", true) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8f) })
        left.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14f) })
        left.addView(label("Hold OK on a song for more", 10f, C.TEXT3), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10f) })
        split.addView(left, LinearLayout.LayoutParams(leftW, -1))
        fillStory(item, story, facts)

        // right: the songs
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        val songs = ColScroller(this).apply {
            clipToPadding = false
            clipChildren = false
            setPadding(dp(6f), dp(2f), dp(6f), dp(30f))
            addView(list, FrameLayout.LayoutParams(-1, -2))
        }
        list.addView(label("Loading the songs…", 13f, C.TEXT3), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10f) })
        split.addView(songs, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(40f) })
        push(col, playBtn)

        exec.execute {
            val got = try { songsOf(item) } catch (e: Exception) { null }
            main.post {
                if (isFinishing) return@post
                list.removeAllViews()
                if (got == null || got.first.isEmpty()) {
                    list.addView(label(if (got == null) "Music Assistant did not answer." else "No songs found.", 13f, C.TEXT3),
                        LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10f) })
                    return@post
                }
                val (all, thumb) = got
                if (item.image == null && thumb != null) picture(thumb, coverSize) { b -> if (b != null) cover.setImageBitmap(b) }
                count.text = if (all.size == 1) "1 song" else "${all.size} songs"
                val mainArtist = item.sub.substringBefore(",").trim()
                all.forEachIndexed { i, s ->
                    list.addView(songRow(item, s, i + 1, s.artist.isNotBlank() && !s.artist.equals(mainArtist, true)),
                        LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(2f) })
                }
            }
        }
    }

    private fun songRow(from: Item, song: Song, n: Int, showArtist: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            isLongClickable = true
            setPadding(dp(12f), dp(8f), dp(14f), dp(8f))
            minimumHeight = dp(44f)
        }
        val num = label("$n", 12f, C.TEXT3).apply { gravity = Gravity.END }
        val mark = label("♪", 13f, C.ON, bold = true).apply { gravity = Gravity.END; visibility = View.GONE }
        val numBox = FrameLayout(this)
        numBox.addView(num, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
        numBox.addView(mark, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
        row.addView(numBox, LinearLayout.LayoutParams(dp(26f), -2).apply { rightMargin = dp(16f) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = label(song.title, 14f, C.TEXT).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        texts.addView(title)
        val artist = label(song.artist, 11f, C.TEXT3).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        if (showArtist) texts.addView(artist, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3f) })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        FocusGlide.shape(row, dp(Look.R_SMALL).toFloat())
        val normal = rounded(0x0DFFFFFF, dp(Look.R_SMALL).toFloat())
        row.background = normal
        row.setOnFocusChangeListener { _, f ->
            row.background = if (f) FocusGlide.fill(row, dp(Look.R_SMALL).toFloat()) else normal
            title.setTextColor(if (f) C.DARK else C.TEXT)
            artist.setTextColor(if (f) C.DARK2 else C.TEXT3)
            num.setTextColor(if (f) C.DARK2 else C.TEXT3)
            mark.setTextColor(if (f) C.DARK else C.ON)
        }
        row.setOnClickListener { playFrom(from, song) }
        row.setOnLongClickListener {
            val one = Item(song.uri, "track", song.title, song.artist, null)
            Popup.menu(this, song.title, listOf("Play from here", "Play only this song", "Play next", "Add to the queue", "Radio: more like this", "Play on another player…")) { w ->
                when (w) {
                    0 -> playFrom(from, song)
                    1 -> play(one, "replace", false)
                    2 -> play(one, "next", false)
                    3 -> play(one, "add", false)
                    4 -> play(one, "replace", true)
                    else -> choosePlayer { playFrom(from, song) }
                }
            }
            true
        }
        songRows.add(song.uri to mark)
        mark.tag = num
        markSong(song.uri, mark)
        return row
    }

    private fun markSong(uri: String, mark: TextView) {
        val on = npActive && uri == npUri
        mark.visibility = if (on) View.VISIBLE else View.GONE
        (mark.tag as? View)?.visibility = if (on) View.INVISIBLE else View.VISIBLE
    }

    /**
     * An album's or artist's page: its story under the names, its year, genre or country beside
     * them, its picture behind the page; for an artist without a picture in the library, the photo.
     */
    private fun fillStory(item: Item, story: TextView, meta: TextView, photo: ImageView? = null) {
        fun put(info: MusicInfo.Info?) {
            if (info != null && info.text.isNotBlank()) {
                story.text = info.text
                if (story.visibility != View.VISIBLE) {
                    story.visibility = View.VISIBLE
                    story.alpha = 0f
                    story.animate().alpha(1f).setDuration(300).start()
                }
            }
            meta.text = info?.meta?.split(" · ")?.joinToString("  ·  ") { if (it.startsWith("since ")) Lang.tr(it) else it } ?: ""
            if (photo != null && item.image == null) info?.picture?.let { url -> artPicture(url) { b -> if (b != null) photo.setImageBitmap(b) } }
            showArtOf(item, info)
        }
        val known = infoOf(item, cachedOnly = true)
        if (known != null || item.type !in listOf("album", "artist")) { put(known); return }
        coverArt(item)
        try {
            infoExec.execute {
                val info = try { infoOf(item) } catch (e: Exception) { null }
                main.post { if (!isFinishing && isIn(story, pages)) put(info) }
            }
        } catch (e: Exception) { }
    }

    // ---------- an artist ----------

    private fun openArtist(item: Item) {
        if (target == null) {
            choosePlayer { openArtist(item) }
            return
        }
        val (col, _) = page("ARTIST")
        val split = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        col.addView(split, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(14f) })
        val leftW = dp(330f)
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        val size = dp(130f)
        val photo = coverView(item, size, true, 0f).apply { elevation = dp(12f).toFloat() }
        top.addView(photo, LinearLayout.LayoutParams(size, size))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(label(clean(item.name), 21f, C.TEXT, bold = true).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        })
        val meta = label("", 11f, C.TEXT3).apply { maxLines = 2 }
        names.addView(meta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })
        top.addView(names, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(16f) })
        left.addView(top, LinearLayout.LayoutParams(-1, -2))
        val story = label("", 11f, 0xFFD9D7D2.toInt()).apply { maxLines = 11; ellipsize = TextUtils.TruncateAt.END; visibility = View.GONE }
        left.addView(story, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14f) })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        val playBtn = pill("Play", R.drawable.ic_play) { play(item, "replace", false) }
        buttons.addView(playBtn)
        buttons.addView(pill("Shuffle", null) { play(item, "replace", false, shuffle = true) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8f) })
        buttons.addView(pill("Radio", null) { play(item, "replace", true) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8f) })
        left.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14f) })
        split.addView(left, LinearLayout.LayoutParams(leftW, -1))
        fillStory(item, story, meta, photo)

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        val sc = ColScroller(this).apply {
            clipToPadding = false
            clipChildren = false
            setPadding(0, dp(2f), 0, dp(30f))
            addView(grid, FrameLayout.LayoutParams(-1, -2))
        }
        grid.addView(label("Loading the albums…", 13f, C.TEXT3))
        split.addView(sc, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(40f) })
        push(col, playBtn)
        val gridWidth = pageWidth - dp(330f) - dp(40f)
        exec.execute {
            val albums = try {
                val client = ha!!
                val player = target!!
                val r = client.callResponse("media_player", "browse_media", JSONObject()
                    .put("entity_id", player)
                    .put("media_content_id", item.uri)
                    .put("media_content_type", "artist"), 30000)
                val kids = r.optJSONObject(player)?.optJSONArray("children") ?: JSONArray()
                (0 until kids.length()).mapNotNull { i ->
                    val k = kids.optJSONObject(i) ?: return@mapNotNull null
                    val uri = k.optString("media_content_id", "")
                    if (!uri.contains("album")) return@mapNotNull null
                    val t = k.optString("title", "")
                    val name = if (t.startsWith(item.name + " - ")) t.substring(item.name.length + 3) else t
                    Item(uri, "album", name, item.name, k.optString("thumbnail", "").takeIf { it.isNotBlank() && it != "null" })
                }
            } catch (e: Exception) { null }
            main.post {
                if (isFinishing) return@post
                when {
                    albums == null -> { grid.removeAllViews(); grid.addView(label("Music Assistant did not answer.", 13f, C.TEXT3)) }
                    albums.isEmpty() -> { grid.removeAllViews(); grid.addView(label("No albums in the library.", 13f, C.TEXT3)) }
                    else -> {
                        grid.addView(label("Albums", 14f, C.TEXT, bold = true), 0)
                        grid.removeViewAt(1)
                        val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
                        showGrid(g, albums, gridWidth, 3)
                        grid.addView(g)
                    }
                }
            }
        }
    }

    // ---------- Now playing ----------

    private fun openNow() {
        val existing = nowPage
        if (existing != null && existing.parent != null) {
            // already open under other pages: go back down to it
            while (pages.getChildAt(pages.childCount - 1) !== existing && under.isNotEmpty()) pop()
            return
        }
        val p = buildNowPage()
        push(p, npPlay)
        showNow()
        refreshNow()
    }

    private fun buildNowPage(): FrameLayout {
        val frame = FrameLayout(this)
        // the cover, blurred, fills the screen; a dark veil keeps the text readable
        npBack = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        frame.addView(View(this).apply { setBackgroundColor(0xFF0B0C11.toInt()) }, FrameLayout.LayoutParams(-1, -1))
        frame.addView(npBack, FrameLayout.LayoutParams(-1, -1))
        frame.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x99050608.toInt(), 0xB3050608.toInt(), 0xE0050608.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))

        val (col, _) = page("NOW PLAYING", chip = false)
        FocusGlide.install(col)
        frame.addView(col, FrameLayout.LayoutParams(-1, -1))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        col.addView(mid, LinearLayout.LayoutParams(-1, 0, 1f))

        val coverSize = dp(320f)
        npCover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = rounded(0xFF22242E.toInt(), dp(Look.R_PANEL).toFloat())
            elevation = dp(24f).toFloat()
        }
        mid.addView(npCover, LinearLayout.LayoutParams(coverSize, coverSize).apply { leftMargin = dp(12f) })

        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        npFrom = label("", 11f, C.TEXT3, bold = true).apply { letterSpacing = 0.08f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        info.addView(npFrom)
        npTitle = label("", 30f, C.TEXT, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        info.addView(npTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        npArtist = label("", 17f, C.TEXT2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        info.addView(npArtist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        npQuality = label("", 10f, C.TEXT2, bold = true).apply {
            background = rounded(0x26FFFFFF, dp(8f).toFloat())
            setPadding(dp(9f), dp(4f), dp(9f), dp(4f))
            visibility = View.GONE
            letterSpacing = 0.06f
        }
        info.addView(npQuality, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12f) })

        npTrack = FrameLayout(this).apply { background = rounded(0x40FFFFFF, dp(2f).toFloat()) }
        npBar = View(this).apply { background = rounded(C.TEXT, dp(2f).toFloat()) }
        npTrack.addView(npBar, FrameLayout.LayoutParams(0, dp(4f)))
        info.addView(npTrack, LinearLayout.LayoutParams(-1, dp(4f)).apply { topMargin = dp(26f) })
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        npElapsed = label("", 11f, C.TEXT3)
        npTotal = label("", 11f, C.TEXT3)
        times.addView(npElapsed, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(npTotal)
        info.addView(times, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        controls.addView(roundButton(null, "Volume down") { send("volume_down") }.apply { setImageDrawable(volumeIcon(false)) }, LinearLayout.LayoutParams(dp(40f), dp(40f)))
        controls.addView(roundButton(R.drawable.ic_prev, "Previous") { send("media_previous_track") }, LinearLayout.LayoutParams(dp(46f), dp(46f)).apply { leftMargin = dp(14f) })
        npPlay = roundButton(R.drawable.ic_play, "Play / pause") { send("media_play_pause") }.apply {
            val pad = dp(15f)
            setPadding(pad, pad, pad, pad)
        }
        controls.addView(npPlay, LinearLayout.LayoutParams(dp(62f), dp(62f)).apply { leftMargin = dp(14f) })
        controls.addView(roundButton(R.drawable.ic_next, "Next") { send("media_next_track") }, LinearLayout.LayoutParams(dp(46f), dp(46f)).apply { leftMargin = dp(14f) })
        controls.addView(roundButton(null, "Volume up") { send("volume_up") }.apply { setImageDrawable(volumeIcon(true)) }, LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { leftMargin = dp(14f) })
        npAlbum = pill("Go to album", null) { npAlbumItem?.let { openCollection(it) } }
        controls.addView(npAlbum, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(26f) })
        info.addView(controls, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18f) })

        npNext = label("", 12f, C.TEXT3).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        info.addView(npNext, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24f) })
        mid.addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(48f); rightMargin = dp(12f) })
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(30f)))
        frame.isClickable = true
        nowPage = frame
        return frame
    }

    /** Fills the Now playing page from what was read last. */
    private fun showNow() {
        if (nowPage == null) return
        if (!npActive) {
            npFrom.text = if (targetName.isNotBlank()) "ON ${targetName.uppercase()}" else ""
            npTitle.text = "Nothing playing"
            npArtist.text = "Choose something in the library, or another player up in the corner."
            npArtist.maxLines = 2
            npQuality.visibility = View.GONE
            npNext.text = ""
            npElapsed.text = ""
            npTotal.text = ""
            npBar.layoutParams = npBar.layoutParams.apply { width = 0 }
            npCover.setImageDrawable(NameCover("♪", false, resources.displayMetrics.density))
            npBack.animate().alpha(0f).setDuration(300).start()
            npPlay.setImageResource(R.drawable.ic_play)
            npAlbum.visibility = View.GONE
            return
        }
        npArtist.maxLines = 1
        npTitle.text = npTitleText
        npArtist.text = npArtistText
        npPlay.setImageResource(if (npPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        npAlbum.visibility = if (npAlbumItem != null) View.VISIBLE else View.GONE
        val b = npBitmap
        if (b != null) {
            npCover.setImageBitmap(b)
            npBack.setImageBitmap(blur(b))
            npBack.animate().alpha(1f).setDuration(400).start()
        } else {
            npCover.setImageDrawable(NameCover(npAlbumItem?.name ?: npTitleText, false, resources.displayMetrics.density))
            npBack.animate().alpha(0f).setDuration(300).start()
        }
        updateProgress()
    }

    // ---------- controls and icons ----------

    private fun roundButton(icon: Int?, name: String, onClick: () -> Unit): ImageView = ImageView(this).apply {
        if (icon != null) setImageResource(icon)
        val pad = dp(10f)
        setPadding(pad, pad, pad, pad)
        isFocusable = true
        isClickable = true
        contentDescription = name
        val normal = Look.surface(this@MaActivity, dp(Look.R_TILE).toFloat())
        background = normal
        setColorFilter(C.TEXT)
        FocusGlide.shape(this, dp(Look.R_TILE).toFloat())
        setOnFocusChangeListener { v, f ->
            background = if (f) FocusGlide.fill(this, dp(Look.R_TILE).toFloat()) else normal
            setColorFilter(if (f) C.DARK else C.TEXT)
            Look.grow(v, f)
        }
        setOnClickListener { onClick() }
    }

    // ---------- playing ----------

    private fun play(item: Item, enqueue: String, radio: Boolean, shuffle: Boolean? = null, startAt: String? = null) {
        val t = target
        val client = ha
        if (t == null || client == null) {
            choosePlayer { play(item, enqueue, radio, shuffle, startAt) }
            return
        }
        val data = JSONObject()
            .put("entity_id", t)
            .put("media_id", item.uri)
            .put("enqueue", enqueue)
        if (item.type.isNotBlank()) data.put("media_type", item.type)
        if (radio) data.put("radio_mode", true)
        if (startAt != null) data.put("start_item", startAt)
        val name = clean(item.name)
        exec.execute {
            val ok = try {
                if (shuffle != null && enqueue == "replace") {
                    try { client.call("media_player", "shuffle_set", JSONObject().put("entity_id", t).put("shuffle", shuffle)) } catch (e: Exception) { }
                }
                client.call("music_assistant", "play_media", data, 15000)
                true
            } catch (e: Exception) { false }
            main.post {
                toast(if (!ok) "Couldn't play $name" else when (enqueue) {
                    "next" -> "$name plays next"
                    "add" -> "$name added to the queue"
                    else -> "$name ▶ $targetName"
                })
                main.postDelayed({ refreshNow() }, 1200)
            }
        }
    }

    /** OK on a song: the whole album or playlist plays, starting from that song. */
    private fun playFrom(from: Item, song: Song) {
        val t = target
        val client = ha
        if (t == null || client == null) {
            choosePlayer { playFrom(from, song) }
            return
        }
        exec.execute {
            val ok = try {
                client.call("music_assistant", "play_media", JSONObject()
                    .put("entity_id", t)
                    .put("media_id", from.uri)
                    .put("media_type", from.type)
                    .put("enqueue", "replace")
                    .put("start_item", song.uri), 15000)
                true
            } catch (e: Exception) {
                // an older Music Assistant without "start item": just the song
                try {
                    client.call("music_assistant", "play_media", JSONObject()
                        .put("entity_id", t).put("media_id", song.uri).put("media_type", "track").put("enqueue", "replace"), 15000)
                    true
                } catch (x: Exception) { false }
            }
            main.post {
                toast(if (ok) "${song.title} ▶ $targetName" else "Couldn't play ${song.title}")
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

    /** The players, with their room. */
    private fun players(): List<Player> {
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
            Player(a.getString(0), if (area.isNotBlank() && !a.getString(1).contains(area, true)) "${a.getString(1)} · $area" else a.getString(1), a.getString(2))
        }
    }

    /**
     * Picks the player. The choice is kept on this TV (the home screen card follows it too).
     * When music plays on the old one, it can move along to the new one.
     */
    private fun choosePlayer(then: (() -> Unit)?) {
        if (ha == null) return
        exec.execute {
            val list = try { players() } catch (e: Exception) { emptyList() }
            main.post {
                if (list.isEmpty()) {
                    toast("No Music Assistant player is on")
                    return@post
                }
                val sel = list.indexOfFirst { it.id == target }
                Popup.menu(this, "Play on", list.map { if (it.state == "playing") "${it.name}  ♪" else it.name }, sel) { i ->
                    val old = target
                    val chosen = list[i]
                    val moveable = then == null && old != null && old != chosen.id && npActive && chosen.state != "playing"
                    setTarget(chosen.id, chosen.name)
                    if (moveable) {
                        Popup.menu(this, chosen.name.substringBefore(" · "), listOf("Move the music here", "Just switch to this player")) { w ->
                            if (w == 0) transfer(old!!, chosen.id) else refreshNow()
                        }
                    } else {
                        refreshNow()
                    }
                    then?.invoke()
                }
            }
        }
    }

    private fun transfer(from: String, to: String) {
        val client = ha ?: return
        exec.execute {
            val ok = try {
                client.call("music_assistant", "transfer_queue", JSONObject().put("entity_id", to).put("source_player", from).put("auto_play", true), 15000)
                true
            } catch (e: Exception) { false }
            main.post {
                toast(if (ok) "The music moved to $targetName" else "Couldn't move the music")
                main.postDelayed({ refreshNow() }, 1500)
            }
        }
    }

    private fun setTarget(id: String, name: String) {
        target = id
        targetName = name.substringBefore(" · ").trim()
        playerPills.forEach { it.text = targetName }
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
            val pick = list.firstOrNull { it.id == saved }
                ?: list.firstOrNull { it.id in roomIds }
                ?: list.firstOrNull { it.state == "playing" }
                ?: list.firstOrNull()
            if (pick != null) main.post { setTarget(pick.id, pick.name) }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** What plays: the player's state, and its queue for the song, the quality and what comes next. */
    private fun refreshNow() {
        val t = target ?: return
        val client = ha ?: return
        exec.execute {
            val s = try { client.state(t) } catch (e: Exception) { null }
            val q = try {
                client.callResponse("music_assistant", "get_queue", JSONObject().put("entity_id", t)).optJSONObject(t)
            } catch (e: Exception) { null }
            main.post {
                if (isFinishing || t != target) return@post
                val state = s?.optString("state") ?: ""
                val a = s?.optJSONObject("attributes") ?: JSONObject()
                val cur = q?.optJSONObject("current_item")
                val media = cur?.optJSONObject("media_item")
                val title = media?.optString("name")?.takeIf { it.isNotBlank() } ?: a.optString("media_title", "")
                val wasActive = npActive
                npActive = title.isNotBlank() && (state == "playing" || state == "paused")
                if (!npActive) {
                    npPlaying = false
                    npUri = null
                    npBitmap = null
                    npPicture = null
                    npAlbumItem = null
                    npDuration = 0.0
                    afterRefresh()
                    return@post
                }
                npPlaying = state == "playing"
                npTitleText = title
                npUri = media?.optString("uri")
                val artist = media?.let { artistsOf(it) }?.takeIf { it.isNotBlank() } ?: a.optString("media_artist", "")
                val albumObj = media?.optJSONObject("album")
                val album = albumObj?.optString("name", "") ?: a.optString("media_album_name", "")
                npArtistText = listOf(artist, album).filter { it.isNotBlank() }.distinct().joinToString(" — ")
                npAlbumItem = albumObj?.optString("uri")?.takeIf { it.isNotBlank() }?.let {
                    Item(it, "album", album, artist, imageOf(albumObj) ?: media?.let { m -> imageOf(m) })
                }
                if (nowPage != null) npFrom.text = "PLAYING ON ${targetName.uppercase()}"
                // quality: the file type and sample rate
                if (nowPage != null) {
                    val sd = cur?.optJSONObject("stream_details")
                    val type = sd?.optString("content_type", "")?.uppercase() ?: ""
                    val rate = sd?.optInt("sample_rate", 0) ?: 0
                    val bits = sd?.optInt("bit_depth", 0) ?: 0
                    if (type.isNotBlank()) {
                        val khz = java.lang.String.format(java.util.Locale.ROOT, "%.1f", rate / 1000.0).removeSuffix(".0")
                        npQuality.text = listOfNotNull(type, if (rate > 0) "$khz kHz" else null, if (bits > 0) "$bits bit" else null).joinToString(" · ")
                        npQuality.visibility = View.VISIBLE
                    } else npQuality.visibility = View.GONE
                    val next = q?.optJSONObject("next_item")?.optJSONObject("media_item")
                    npNext.text = if (next != null) "Up next   ${next.optString("name")}" + (artistsOf(next).takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") else ""
                }
                npDuration = cur?.optDouble("duration", 0.0) ?: a.optDouble("media_duration", 0.0)
                npElapsedS = q?.optDouble("elapsed_time", 0.0) ?: a.optDouble("media_position", 0.0)
                npElapsedAt = System.currentTimeMillis()
                val pic = media?.let { imageOf(it) } ?: a.optString("entity_picture", "").ifBlank { null }
                if (pic != npPicture || !wasActive) {
                    npPicture = pic
                    npBitmap = null
                    if (pic != null) picture(pic, dp(320f)) { b ->
                        if (npPicture == pic) {
                            npBitmap = b
                            afterRefresh()
                        }
                    }
                }
                afterRefresh()
            }
        }
    }

    private fun afterRefresh() {
        nowChips.forEach { it.update() }
        songRows.forEach { (uri, mark) -> markSong(uri, mark) }
        showNow()
    }

    private fun updateProgress() {
        if (nowPage == null) return
        if (npDuration <= 0 || !npActive) {
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
                val b = try { client.bitmap(sized, width) } catch (e: Throwable) { null }
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
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (!pop()) {
                    finish()
                    overridePendingTransition(0, 0)
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun toast(msg: String) = Toast.makeText(this, Lang.tr(msg), Toast.LENGTH_SHORT).show()
}

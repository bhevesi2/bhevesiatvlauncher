package hu.otthon.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The film and series library ("Filmtár"). The chosen title's backdrop fills the screen behind
 * its name, data and story; below, rows of posters: Continue watching, Recently added, Films,
 * Series and the larger genres. OK on a poster opens it: play, play from the start, mark as
 * watched; a series shows its seasons and episodes. ▲ from the first row reaches Search.
 *
 * On the TVs with memory to spare the posters turn in space (the focused one comes forward, its
 * neighbours lean towards it) and the backdrop drifts slowly; on the others the same, flat.
 */
class MediaActivity : Activity() {
    private lateinit var root: FrameLayout
    /** Everything under an open details panel: closed to the cursor while the panel is open. */
    private lateinit var page: LinearLayout
    private lateinit var backdrop: ImageView
    private lateinit var backdropNext: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroBadges: TextView
    private lateinit var heroText: TextView
    private lateinit var rows: LinearLayout
    private lateinit var scroll: ColScroller
    private lateinit var status: TextView
    private lateinit var search: TextView
    private var details: View? = null
    private var detailsTitle: MediaLib.Title? = null
    private var titles: List<MediaLib.Title> = emptyList()
    private var query = ""
    private var heroKey: String? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var deep = false      // 3D and drifting backdrop: where there is memory for it and it is on (Settings › Library)
    private val posterSize get() = if (LowRam.lite) "w185" else "w342"
    private val backdropSize get() = if (LowRam.on) "w780" else "w1280"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        // opened straight after the launcher was restarted, the memory tier may not be known yet
        LowRam.init(this)
        root = FrameLayout(this).apply { setBackgroundColor(0xFF0B0C10.toInt()) }
        deep = !LowRam.on && threeD(this)
        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        backdropNext = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        root.addView(backdropNext, FrameLayout.LayoutParams(-1, -1))
        // the backdrop fades into the dark to the left and to the bottom, where the text is
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF20B0C10.toInt(), 0xB30B0C10.toInt(), 0x000B0C10))
        }, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xFF0B0C10.toInt(), 0xE60B0C10.toInt(), 0x000B0C10))
        }, FrameLayout.LayoutParams(-1, dp(420f), Gravity.BOTTOM))

        // the rows are drawn only in their own area: scrolled up, they pass under the title and the story, not over them
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = true }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ImageView(this).apply { setImageResource(R.drawable.ic_clapper); setColorFilter(0xFFFAC775.toInt()) },
            LinearLayout.LayoutParams(dp(20f), dp(20f)).apply { rightMargin = dp(8f) })
        top.addView(label("Library", 14f, C.TEXT, bold = true))
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        status = label("", 10f, C.TEXT2)
        top.addView(status, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(14f) })
        // new films without going to the settings
        val refresh = pill("Library Update")
        refresh.setOnClickListener { maybeScan(force = true) }
        top.addView(refresh, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(12f) })
        search = pill("Search")
        search.setOnClickListener { askSearch() }
        top.addView(search)
        page.addView(top, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(48f); rightMargin = dp(48f); topMargin = dp(24f) })

        val hero = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroTitle = label("", 28f, C.TEXT, bold = true).apply { maxLines = 2 }
        heroMeta = label("", 12f, C.TEXT2)
        heroBadges = label("", 11f, 0xFFFAC775.toInt(), bold = true)
        heroText = label("", 12f, 0xFFD9D7D2.toInt()).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }
        hero.addView(heroTitle)
        hero.addView(heroMeta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6f) })
        hero.addView(heroBadges, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4f) })
        hero.addView(heroText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        page.addView(hero, LinearLayout.LayoutParams(dp(560f), dp(170f)).apply { leftMargin = dp(48f); topMargin = dp(14f) })

        scroll = ColScroller(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false }
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; setPadding(0, dp(6f), 0, dp(40f)) }
        scroll.addView(rows)
        // the rows stop whole at the top, the one above fades out
        scroll.rowsAtTop(rows, dp(22f), dp(18f))
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        reload()
        maybeScan()
    }

    override fun onResume() {
        super.onResume()
        CrashLog.entered(this)
        // back from the player: where it got to may have changed (an open title shows its next episode)
        if (titles.isNotEmpty()) reload(keepFocus = true)
    }

    override fun onPause() {
        super.onPause()
        CrashLog.left(this)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        MediaLib.shrink()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) MediaLib.trim()
    }

    private fun pill(text: String) = label(text, 12f, C.TEXT).apply {
        setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        background = rounded(C.GLASS, dp(18f).toFloat())
        isFocusable = true
        isClickable = true
        setOnFocusChangeListener { v, f -> v.background = rounded(if (f) 0xFFE9E7E2.toInt() else C.GLASS, dp(18f).toFloat()); (v as TextView).setTextColor(if (f) 0xFF111111.toInt() else C.TEXT) }
    }

    // ---------- reading ----------

    private fun maybeScan(force: Boolean = false) {
        if (MediaLib.sources(this).isEmpty() || MediaLib.scanning) return
        val last = Prefs.of(this).getLong("media_scanned_at", 0L)
        if (!force && System.currentTimeMillis() - last < 6 * 3600_000L) return
        status.text = Lang.tr("Looking for new films…")
        val app = applicationContext
        Bg.slow({ MediaLib.scan(app) }) { result ->
            status.text = ""
            if (result != null) android.util.Log.i("OtthonMedia", "scan: $result")
            reload(keepFocus = true)
        }
    }

    private fun reload(keepFocus: Boolean = false) {
        val app = applicationContext
        val focusKey = if (keepFocus) (currentFocus as? PosterCard)?.title?.key else null
        Bg.run({ MediaLib.titles(app) }) { list ->
            titles = list ?: emptyList()
            build(focusKey)
            val open = detailsTitle
            if (details != null && open != null) openDetails(titles.firstOrNull { it.key == open.key } ?: open)
        }
    }

    // ---------- the rows ----------

    private fun build(focusKey: String?) {
        rows.removeAllViews()
        if (MediaLib.sources(this).isEmpty()) {
            empty("No films yet", "Add where they are: Settings › Library › Add a NAS folder or USB", "Open the settings") {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", "media"))
            }
            return
        }
        if (titles.isEmpty()) {
            empty("No films found yet", if (MediaLib.scanning) "Looking through the folders…" else "Look through the folders again", "Look now") { maybeScan(force = true) }
            return
        }
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) {
            val hits = titles.filter { t -> listOf(t.title, t.original, t.cast, t.genres).any { it.lowercase().contains(q) } }
            addRow(Lang.tr("Results for “$query”") + " · ${hits.size}", hits)
        }
        val going = titles.filter { it.position > 0 && !it.watched }.sortedByDescending { it.played }
        addRow(Lang.tr("Continue watching"), going)
        addRow(Lang.tr("Recently added"), titles.take(20))
        addRow(Lang.tr("Films"), titles.filter { !it.series }.sortedBy { it.title.lowercase() })
        addRow(Lang.tr("Series"), titles.filter { it.series }.sortedBy { it.title.lowercase() })
        val genres = titles.flatMap { it.genres.split(", ").filter { g -> g.isNotBlank() } }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(6).map { it.key }
        for (g in genres) addRow(g, titles.filter { it.genres.contains(g) })
        if (MediaLib.tmdbKey(this).isBlank()) {
            rows.addView(label("Without a TMDB key the films have no pictures or stories: Settings › Library › TMDB key", 11f, C.TEXT2),
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(48f); topMargin = dp(10f) })
        }
        rows.addView(label("Film data and pictures: TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB. " +
            "Where TMDB has nothing: series from TVmaze, films from Wikipedia (CC BY-SA).", 9f, 0x88FFFFFF.toInt()),
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(48f); topMargin = dp(18f) })
        val all = allCards()
        (all.firstOrNull { it.title.key == focusKey } ?: all.firstOrNull())?.requestFocus()
    }

    private fun empty(title: String, text: String, button: String, action: () -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(label(title, 16f, C.TEXT, bold = true))
        box.addView(label(text, 12f, C.TEXT2), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6f) })
        val b = pill(button)
        b.setOnClickListener { action() }
        box.addView(b, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14f) })
        rows.addView(box, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(48f); topMargin = dp(20f) })
        b.requestFocus()
        heroTitle.text = ""
        heroMeta.text = ""
        heroText.text = ""
    }

    private fun addRow(name: String, list: List<MediaLib.Title>) {
        if (list.isEmpty()) return
        val head = label(name, 13f, C.TEXT, bold = true)
        rows.addView(head, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(48f); topMargin = dp(10f) })
        val rs = RowScroller(this)
        rs.setPadding(dp(48f), dp(12f), dp(48f), dp(16f))
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        rs.addView(line, FrameLayout.LayoutParams(-2, dp(168f)))
        for ((i, t) in list.withIndex()) {
            val card = PosterCard(this, t)
            card.setOnClickListener { openDetails(t) }
            card.setOnFocusChangeListener { v, f ->
                Look.grow(v, f, if (deep) 1.14f else 1.1f)
                card.focused(f)
                if (f) { postersNear(line, line.indexOfChild(v)); hero(t); if (deep) { lean(line, v); light(rs) } }
                // the cursor left the row: it lies flat again
                else if (deep) line.post { if (line.findFocus() == null && details == null) flatten(line) }
            }
            line.addView(card, LinearLayout.LayoutParams(dp(112f), dp(168f)).apply { if (i > 0) leftMargin = dp(14f) })
        }
        // the posters a screen holds; the rest come as the cursor moves along the row
        postersNear(line, 0)
        rows.addView(rs, LinearLayout.LayoutParams(-1, -2))
    }

    private fun allCards(): List<PosterCard> {
        val out = ArrayList<PosterCard>()
        fun walk(v: View) {
            if (v is PosterCard) out.add(v) else if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(rows)
        return out
    }

    /**
     * 3D: the focused poster comes forward and faces you, the ones beside it turn in towards it,
     * more the further they are, and sink back a little. The camera is close enough for the
     * perspective to show (at the default distance a turn of 20° looks like a narrower poster).
     */
    private fun lean(line: LinearLayout, focused: View) {
        val at = line.indexOfChild(focused)
        val camera = 9f * resources.displayMetrics.densityDpi
        for (i in 0 until line.childCount) {
            val c = line.getChildAt(i)
            val d = i - at
            c.cameraDistance = camera
            val n = Math.abs(d)
            val turn = if (n == 0) 0f else (26f + (n - 1) * 5f).coerceAtMost(40f) * (if (d < 0) 1f else -1f)
            val a = c.animate().rotationY(turn).translationZ(if (n == 0) dp(14f).toFloat() else 0f)
                .alpha(if (n == 0) 1f else (1f - n * 0.1f).coerceAtLeast(0.55f))
            if (n != 0) a.scaleX(0.92f).scaleY(0.92f)
            a.setDuration(320).setInterpolator(DecelerateInterpolator(2.2f)).start()
        }
    }

    private fun flatten(line: LinearLayout) {
        for (i in 0 until line.childCount) {
            line.getChildAt(i).animate().rotationY(0f).translationZ(0f).alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(300).setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }

    /** The row with the cursor is lit, the others step back into the dark. */
    private fun light(focusedRow: View) {
        for (i in 0 until rows.childCount) {
            val r = rows.getChildAt(i)
            if (r !is RowScroller) continue
            val to = if (r === focusedRow) 1f else 0.6f
            if (r.alpha == to) continue
            // a dimmed row is kept as one picture on the GPU, not drawn see-through card by card
            r.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            r.animate().alpha(to).setDuration(260).withEndAction { if (r.alpha == 1f) r.setLayerType(View.LAYER_TYPE_NONE, null) }.start()
        }
    }

    /**
     * The posters around [at] in a row (two before, ten after). A library of hundreds of titles
     * in eight rows would otherwise load every poster at once: minutes of network, and memory a
     * smaller TV does not have.
     */
    private fun postersNear(line: LinearLayout, at: Int) {
        for (i in maxOf(0, at - 2)..minOf(line.childCount - 1, at + 10)) (line.getChildAt(i) as? PosterCard)?.let { loadPoster(it) }
    }

    private fun loadPoster(card: PosterCard) {
        if (card.asked) return
        card.asked = true
        val p = card.title.poster ?: return
        // made as wide as the card shows it (a w342 poster is half again as wide as the card)
        val w = dp(112f * 1.15f)
        MediaLib.cached(p, posterSize, w)?.let { card.setPicture(it, false); return }
        MediaLib.load(this, p, posterSize, w) { b -> if (b != null && !isFinishing) card.setPicture(b, true) }
    }

    // ---------- the chosen title, big ----------

    private val heroLater = Runnable { }
    private var pendingHero: MediaLib.Title? = null

    private fun hero(t: MediaLib.Title) {
        if (heroKey == t.key) return
        heroKey = t.key
        heroTitle.text = t.title
        val meta = ArrayList<String>()
        t.year?.let { meta.add(it.toString()) }
        if (t.rating > 0) meta.add("★ " + String.format("%.1f", t.rating))
        if (t.genres.isNotBlank()) meta.add(t.genres.split(", ").take(3).joinToString(", "))
        if (t.series) meta.add(Lang.tr("${t.files} episodes")) else if (t.runtime > 0) meta.add("${t.runtime / 60}h ${t.runtime % 60}m")
        heroMeta.text = meta.joinToString("  ·  ")
        val badges = ArrayList<String>()
        if (t.uhd) badges.add("4K")
        if (t.dv) badges.add("Dolby Vision") else if (t.hdr) badges.add("HDR")
        if (t.position > 0 && t.duration > 0 && !t.watched) badges.add(Lang.tr("${(t.position * 100 / t.duration)}% watched"))
        if (t.watched) badges.add(Lang.tr("Watched"))
        heroBadges.text = badges.joinToString("  ·  ")
        heroText.text = t.overview
        if (deep) for ((k, v) in listOf(heroTitle, heroMeta, heroBadges, heroText).withIndex()) {
            v.animate().cancel()
            v.alpha = 0f
            v.translationX = dp(18f).toFloat()
            v.animate().alpha(1f).translationX(0f).setStartDelay(k * 40L).setDuration(300).setInterpolator(DecelerateInterpolator(2f)).start()
        }
        // the backdrop changes only when the focus rests for a moment (fast scrolling stays calm)
        pendingHero = t
        main.removeCallbacks(backdropLater)
        main.postDelayed(backdropLater, 260)
    }

    private val backdropLater = Runnable {
        val t = pendingHero ?: return@Runnable
        val path = t.backdrop ?: return@Runnable
        val app = applicationContext
        MediaLib.load(app, path, backdropSize) { b -> if (b != null && pendingHero === t && !isFinishing) showBackdrop(b) }
    }

    private fun showBackdrop(b: Bitmap) {
        backdropNext.setImageBitmap(b)
        backdropNext.alpha = 0f
        backdropNext.scaleX = 1f
        backdropNext.scaleY = 1f
        backdropNext.animate().alpha(1f).setDuration(450).withEndAction {
            backdrop.setImageBitmap(b)
            backdrop.scaleX = 1f; backdrop.scaleY = 1f
            backdropNext.alpha = 0f
            // a slow drift into the picture
            if (deep) backdrop.animate().scaleX(1.07f).scaleY(1.07f).setDuration(22_000).setInterpolator(null).start()
        }.start()
    }

    // ---------- search ----------

    private fun askSearch() {
        Popup.text(this, "Search", query, "Title, actor or genre") { v ->
            query = v
            search.text = if (v.isBlank()) Lang.tr("Search") else "“$v”"
            build(null)
            scroll.scrollTo(0, 0)
        }
    }

    // ---------- details ----------

    private fun openDetails(t: MediaLib.Title) {
        val app = applicationContext
        Bg.run({ MediaLib.items(app, t) }) { items -> if (items != null && items.isNotEmpty()) showDetails(t, items) }
    }

    private fun showDetails(t: MediaLib.Title, items: List<MediaLib.Item>) {
        closeDetails()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48f), dp(36f), dp(48f), dp(24f))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF70B0C10.toInt(), 0xE60B0C10.toInt(), 0x990B0C10.toInt()))
            isClickable = true
        }
        panel.addView(label(t.title, 26f, C.TEXT, bold = true))
        if (t.original.isNotBlank() && t.original != t.title) panel.addView(label(t.original, 12f, C.TEXT2))
        panel.addView(label(heroMeta.text.toString(), 12f, C.TEXT2), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6f) })
        panel.addView(label(t.overview, 12f, 0xFFD9D7D2.toInt()).apply { maxLines = 5 }, LinearLayout.LayoutParams(dp(640f), -2).apply { topMargin = dp(10f) })
        if (t.cast.isNotBlank()) panel.addView(label(Lang.tr("Cast: ") + t.cast, 11f, C.TEXT2).apply { maxLines = 2 }, LinearLayout.LayoutParams(dp(640f), -2).apply { topMargin = dp(8f) })

        val ids = items.map { it.id }.toLongArray()
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // the first not watched (a film: its file; a series: the next episode)
        val next = items.indexOfFirst { it.position > 0 && !it.watched }.takeIf { it >= 0 } ?: items.indexOfFirst { !it.watched }.takeIf { it >= 0 } ?: 0
        val nextItem = items[next]
        val play = pill(when {
            nextItem.position > 0 && !nextItem.watched -> Lang.tr("Continue") + " · " + clock(nextItem.position)
            t.series -> Lang.tr("Play") + " · S${nextItem.season}E${nextItem.episode}"
            else -> Lang.tr("Play")
        })
        play.setOnClickListener { PlayerActivity.play(this, ids, next) }
        buttons.addView(play)
        if (nextItem.position > 0) {
            val again = pill("From the start")
            again.setOnClickListener { PlayerActivity.play(this, ids, next, 0L) }
            buttons.addView(again, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12f) })
        }
        val all = items.all { it.watched }
        val mark = pill(if (all) "Mark as not watched" else "Mark as watched")
        mark.setOnClickListener {
            MediaLib.setWatched(this, items, !all)
            closeDetails()
            reload(keepFocus = true)
        }
        buttons.addView(mark, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12f) })
        panel.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18f) })

        if (t.series) {
            val seasons = items.mapNotNull { it.season }.distinct().sorted()
            val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val tabIds = seasons.associateWith { View.generateViewId() }
            var shown: Int? = null
            fun showSeason(s: Int) {
                if (shown == s && list.childCount > 0) return
                shown = s
                list.removeAllViews()
                items.withIndex().filter { it.value.season == s }.forEach { (i, e) ->
                    // up from the first episode: back to this season's tab, not whichever tab is nearest
                    list.addView(episodeRow(e) { PlayerActivity.play(this, ids, i) }.apply { nextFocusUpId = if (list.childCount == 0) tabIds.getValue(s) else View.NO_ID })
                }
            }
            for (s in seasons) {
                val tab = pill(Lang.tr("Season $s")).apply { id = tabIds.getValue(s) }
                tab.setOnClickListener { showSeason(s) }
                tab.setOnFocusChangeListener { v, f ->
                    v.background = rounded(if (f) 0xFFE9E7E2.toInt() else C.GLASS, dp(18f).toFloat())
                    (v as TextView).setTextColor(if (f) 0xFF111111.toInt() else C.TEXT)
                    if (f) showSeason(s)
                }
                tabs.addView(tab, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10f) })
            }
            panel.addView(tabs, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18f) })
            val sc = ColScroller(this).apply { isVerticalScrollBarEnabled = false }
            sc.addView(list)
            panel.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10f) })
            showSeason(nextItem.season ?: seasons.firstOrNull() ?: 1)
        }
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        details = panel
        detailsTitle = t
        // the cursor stays in the panel: the posters behind it cannot be reached
        page.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        panel.alpha = 0f
        panel.translationX = -dp(30f).toFloat()
        panel.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
        play.requestFocus()
    }

    private fun episodeRow(e: MediaLib.Item, onPlay: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            isFocusable = true
            isClickable = true
            background = rounded(0x00000000, dp(10f).toFloat())
            setOnFocusChangeListener { v, f -> v.background = rounded(if (f) 0x33FFFFFF else 0x00000000, dp(10f).toFloat()) }
            setOnClickListener { onPlay() }
        }
        val still = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = rounded(0x22FFFFFF, dp(6f).toFloat()); clipToOutline = true }
        row.addView(still, LinearLayout.LayoutParams(dp(128f), dp(72f)))
        e.still?.let { p ->
            val app = applicationContext
            MediaLib.load(app, p, "w300", dp(128f)) { b -> if (b != null) still.setImageBitmap(b) }
        }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(label("${e.episode}. " + (e.name ?: Lang.tr("Episode ${e.episode}")), 12f, C.TEXT, bold = true).apply { maxLines = 1 })
        val state = when {
            e.watched -> Lang.tr("Watched")
            e.position > 0 && e.duration > 0 -> Lang.tr("${e.position * 100 / e.duration}% watched")
            else -> ""
        }
        text.addView(label(listOf(state, e.overview ?: "").filter { it.isNotBlank() }.joinToString(" · "), 10f, C.TEXT2).apply { maxLines = 2 })
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14f) })
        return row
    }

    private fun closeDetails(): Boolean {
        val d = details ?: return false
        details = null
        detailsTitle = null
        root.removeView(d)
        page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        allCards().firstOrNull { it.title.key == heroKey }?.requestFocus()
        return true
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK && details != null) {
            if (e.action == KeyEvent.ACTION_UP) closeDetails()
            return true
        }
        if (e.keyCode == KeyEvent.KEYCODE_SEARCH && e.action == KeyEvent.ACTION_UP) { askSearch(); return true }
        return super.dispatchKeyEvent(e)
    }

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, MediaActivity::class.java))

        /** The 3D effects switch (Settings › Library); on unless turned off. */
        fun threeD(ctx: Context) = Prefs.of(ctx).getBoolean("media_3d", true)
    }
}

/** A poster in the library: the picture, or the title on a coloured card until the picture comes. */
class PosterCard(ctx: Context, val title: MediaLib.Title) : FrameLayout(ctx) {
    /** Its picture has been asked for (once is enough). */
    var asked = false
    private val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val name = ctx.label(title.title, 11f, C.TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(ctx.dp(8f), 0, ctx.dp(8f), 0)
    }
    private val ring = View(ctx).apply { background = rounded(0x00000000, ctx.dp(8f).toFloat(), ctx.dp(2f), 0xFFE9E7E2.toInt()); alpha = 0f }
    private val bar = View(ctx)

    init {
        isFocusable = true
        isClickable = true
        clipToOutline = true
        val hue = (title.title.hashCode() and 0xFFFFFF) or 0xFF000000.toInt()
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(mix(hue, 0xFF1A1C24.toInt()), 0xFF111218.toInt())).apply {
            cornerRadius = ctx.dp(8f).toFloat()
        }
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        addView(name, LayoutParams(-1, -1))
        addView(image, LayoutParams(-1, -1))
        // how far it is watched: a thin line at the bottom
        if (title.position > 0 && title.duration > 0 && !title.watched) {
            bar.background = rounded(0xFFFAC775.toInt(), 0f)
            addView(bar, LayoutParams(0, ctx.dp(3f), Gravity.BOTTOM).apply { width = 0 })
            post { (bar.layoutParams as LayoutParams).width = (width * title.position / title.duration).toInt(); bar.requestLayout() }
        }
        if (title.watched) addView(ctx.label("✓", 11f, 0xFF111111.toInt(), bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFFFAC775.toInt(), ctx.dp(9f).toFloat())
        }, LayoutParams(ctx.dp(18f), ctx.dp(18f), Gravity.TOP or Gravity.END).apply { topMargin = ctx.dp(6f); rightMargin = ctx.dp(6f) })
        addView(ring, LayoutParams(-1, -1))
    }

    private fun mix(a: Int, b: Int): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * 0.35f + ((b shr s) and 0xFF) * 0.65f).toInt() shl s
        return 0xFF000000.toInt() or ch(16) or ch(8) or ch(0)
    }

    fun setPicture(b: Bitmap, fade: Boolean) {
        image.setImageBitmap(b)
        if (fade) { image.alpha = 0f; image.animate().alpha(1f).setDuration(220).start() }
    }

    fun focused(f: Boolean) {
        ring.animate().alpha(if (f) 1f else 0f).setDuration(150).start()
    }
}

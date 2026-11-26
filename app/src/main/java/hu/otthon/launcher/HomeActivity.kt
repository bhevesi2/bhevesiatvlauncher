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
    /** A folder from the favourites, opened: its apps in a row just above the favourites. */
    private lateinit var folderBox: LinearLayout
    private lateinit var folderTitle: android.widget.TextView
    private lateinit var folderRow: LinearLayout
    private var folderOpen: View? = null
    /** The favourite being moved (Hold OK › Move), as on Google TV home: ◀ ▶ moves it, OK puts it down. */
    private var moving: View? = null
    private var swallowUp = -1
    private lateinit var bottom: LinearLayout
    private lateinit var rootView: FrameLayout

    // the recommendation rows below the home screen, and the page that slides up to them
    private lateinit var page: FrameLayout
    private lateinit var recoBox: LinearLayout
    private lateinit var recoScroll: ColScroller
    private lateinit var reco: RecoPanel
    private lateinit var recoDim: View
    private lateinit var moreHint: LinearLayout
    private var expanded = false
    /** Where the sliding page ends up: 0 on the home screen, negative while the rows are up. */
    private var pageTarget = 0f
    /** Where the slide to the recommendations is, 0 (home screen) to the full shift; one spring drives it all. */
    private var slideShift = 1f
    private val slide = Follow(11f) { y -> applySlide(y) }
    private var critters: CritterLayer? = null
    private var lastBottomFocus: View? = null
    private var recoLoading = false
    private lateinit var topButtons: LinearLayout
    private var gearButton: ImageView? = null
    private var weatherChipView: TextView? = null
    private lateinit var hint: TextView
    private var hintFor: View? = null

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
    private var resumed = false
    private var awayOnOwnScreen = false
    private var shiftIndex = 0

    // the kids profile: its own screen in place of the usual one
    private lateinit var kidsHome: KidsHome
    private lateinit var picker: ProfilePicker
    private var kidsOn = false
    private var kidsAsleepSince = 0L
    private var kidsSentToSleep = ""
    private val onProfile: () -> Unit = {
        signature = ""
        if (resumed) {
            reload()
            critters?.setCount(cfg.critters, cfg.christmas)
            loadReco()
            onMinute()
        }
    }

    private val timeFmt = Lang.dtf("HH:mm")
    private val dateFmt = Lang.dtf("EEEE, MMMM d")

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
        CrashLog.install(this)
        CrashLog.checkMemoryCrash(this)
        LowRam.init(this)
        LocalAdb.init(this)
        Config.load(this)
        builtHu = Lang.hu
        buildUi()
        Profile.listeners.add(onProfile)
        RecoWatch.onSettled = onRecoChanged
        try {
            registerReceiver(screen, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            })
        } catch (e: Exception) { CrashLog.note(e) }
    }

    override fun onDestroy() {
        Profile.listeners.remove(onProfile)
        if (RecoWatch.onSettled === onRecoChanged) RecoWatch.onSettled = null
        try { unregisterReceiver(screen) } catch (e: Exception) { }
        super.onDestroy()
    }

    // ---------- memory: the pictures let go of while nobody sees them ----------

    /**
     * The photos, the recommendations' pictures and the kept banners go, on a low-tier TV: at
     * once when it sleeps or the home screen goes behind another app. On the high and medium
     * tiers they stay (loading them again is what would make the home screen slow), unless
     * Android is about to stop the launcher. They come back as the home screen shows again,
     * the photo fading in.
     */
    private fun releaseImages() {
        backdrop.release()
        reco.release()
        if (kidsOn) kidsHome.reco.release()
        Reco.trim()
        Backgrounds.trim()
        Apps.trim()
        FocusGlide.trim()
    }

    private val screen = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                if (LowRam.releasesUnseen) releaseImages()
            } else if (resumed && LowRam.releasesUnseen) {
                // the screen back on with the home screen in front: the photo and the rows return
                backdrop.show(cfg.background, Backgrounds.phase(this@HomeActivity, cfg), true)
                loadReco()
            }
        }
    }

    // ---------- UI ----------

    private fun buildUi() {
        val side = dp(48f)
        val root = FrameLayout(this)
        // the recommendations sit below the screen inside the page that slides up; the screen
        // must not cut the page to its own first screenful, or the rows stay invisible
        root.clipChildren = false
        rootView = root
        root.setBackgroundColor(Color.BLACK)
        backdrop = Backdrop(this)
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))

        recoDim = View(this).apply { setBackgroundColor(0xCC07080B.toInt()); alpha = 0f }
        root.addView(recoDim, FrameLayout.LayoutParams(-1, -1))
        page = FrameLayout(this).apply { clipChildren = false }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(34f), 0, dp(30f))
            clipChildren = false
            clipToPadding = false
        }
        page.addView(content, FrameLayout.LayoutParams(-1, -1))
        FocusGlide.install(content)
        kidsHome = KidsHome(this) { leaveKids() }
        kidsHome.visibility = View.GONE
        page.addView(kidsHome, FrameLayout.LayoutParams(-1, -1))
        FocusGlide.install(kidsHome)
        buildReco()

        // top row
        // nothing cut: the corner buttons grow, and step aside for each other
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP; clipChildren = false }
        val clockCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clock = label("--:--", 62f, C.TEXT, light = true).apply { letterSpacing = -0.02f }
        date = label("", 15f, C.TEXT2).apply { setPadding(dp(2f), dp(6f), 0, 0) }
        clockCol.addView(clock)
        clockCol.addView(date)
        top.addView(clockCol)
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(chipsRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12f) })
        topButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        top.addView(topButtons, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10f); topMargin = dp(12f) })
        content.addView(top, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = side; rightMargin = side })

        buildNowPlaying()
        content.addView(npCard, LinearLayout.LayoutParams(dp(320f), -2).apply { leftMargin = side; topMargin = dp(24f) })

        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        folderBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            visibility = View.GONE
            // the panel behind the row, its point over the folder's tile
            background = folderCallout
            setPadding(0, dp(14f), 0, dp(12f))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeCallout() }
        }
        folderTitle = label("", 12f, C.TEXT, bold = true)
        folderBox.addView(folderTitle, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = side; topMargin = dp(4f) })
        val folderScroll = RowScroller(this)
        folderScroll.setPadding(side, dp(9f), side, dp(4f))
        folderRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        folderScroll.addView(folderRow, FrameLayout.LayoutParams(-2, dp(76f)))
        folderBox.addView(folderScroll, LinearLayout.LayoutParams(-1, -2))
        content.addView(folderBox, LinearLayout.LayoutParams(-1, -2))

        favScroll = RowScroller(this)
        favScroll.setPadding(side, dp(9f), side, dp(9f))
        favRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        favScroll.addView(favRow, FrameLayout.LayoutParams(-2, dp(76f)))
        content.addView(favScroll, LinearLayout.LayoutParams(-1, -2))

        bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        content.addView(bottom, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = side
            rightMargin = side
            topMargin = dp(18f)
        })

        // name of the focused icon button
        hint = label("", 10f, C.DARK, bold = true).apply {
            background = rounded(C.FOCUS_FILL, dp(6f).toFloat())
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            visibility = View.INVISIBLE
        }
        // the bears and bunnies walk on top of everything, but under the button names
        val layer = CritterLayer(this) { critterPlaces() }
        critters = layer
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        root.addView(hint, FrameLayout.LayoutParams(-2, -2))
        // "Who's watching?" over everything, when the TV wakes up
        picker = ProfilePicker(this) { kids -> pick(kids) }
        picker.visibility = View.GONE
        root.addView(picker, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
        // the background always covers the window: no need to paint the window's black under it
        window.setBackgroundDrawable(null)
    }

    /**
     * The recommendations live just below the bottom row, outside the screen. Down on the bottom
     * row slides the page up so the bottom row sits at the top and the rows fill the rest.
     */
    private fun buildReco() {
        recoBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            // hidden until the page slides up, so nothing of it peeks in at the bottom edge
            alpha = 0f
        }
        recoScroll = ColScroller(this).apply {
            clipToPadding = false
            clipChildren = false
            // room above the first and below the last row for the enlarged card and its glow
            setPadding(0, dp(12f), 0, dp(44f))
        }
        reco = RecoPanel(this)
        recoScroll.addView(reco, FrameLayout.LayoutParams(-1, -2))
        // the focused row always comes to the top of the list, so each step moves by one row
        recoScroll.anchor = { v -> reco.scrollFor(v) }
        recoBox.addView(recoScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        page.addView(recoBox, FrameLayout.LayoutParams(-1, 0))
        FocusGlide.install(recoBox)

        // a small mark at the bottom edge: there is more below
        moreHint = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(3f), dp(12f), dp(4f))
            // a dark pill, so it reads on a bright photo too
            background = rounded(0x99000000.toInt(), dp(12f).toFloat())
            alpha = 0f
            visibility = View.GONE
        }
        moreHint.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron)
            setColorFilter(C.TEXT)
        }, LinearLayout.LayoutParams(dp(15f), dp(15f)))
        moreHint.addView(label("Recommendations", 11f, C.TEXT, bold = true).apply { setPadding(dp(5f), 0, 0, 0) })
        page.addView(moreHint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(4f) })

        reco.onAllow = {
            if (android.os.Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(Reco.PERMISSION), 11)
        }
        reco.onHold = { card -> moveRowMenu(card) }
        // keep the rows right under the bottom row, as tall as the screen minus the bottom row
        page.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeReco() }
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            if (now == null) return@addOnGlobalFocusChangeListener
            // an open folder closes when the focus leaves its row (Down to the favourites, Up)
            if (folderOpen != null && !isInside(now, folderBox)) closeFolder(false)
            if (expanded && isInside(now, content)) collapse(false)
            if (!expanded && isInside(now, bottom)) lastBottomFocus = now
            if (!expanded) showMoreHint()
            critters?.onFocus(now)
        }
    }

    /** Hold OK on a recommendation: move its row. The new order is kept in the settings. */
    private fun moveRowMenu(card: RecoCard) {
        val keys = reco.rows.map { it.key }
        val i = keys.indexOf(card.row.key)
        if (i < 0) return
        val options = ArrayList<Pair<String, Int>>()
        if (i > 0) {
            options.add("Move row up" to i - 1)
            if (i > 1) options.add("Move row to the top" to 0)
        }
        if (i < keys.size - 1) {
            options.add("Move row down" to i + 1)
            if (i < keys.size - 2) options.add("Move row to the bottom" to keys.size - 1)
        }
        if (options.isEmpty()) return
        Popup.menu(this, card.row.title, options.map { it.first }) { which ->
            val to = options[which].second
            val order = keys.toMutableList()
            order.removeAt(i)
            order.add(to, card.row.key)
            ConfigStore.edit(this) { r -> r.put("reco_order", org.json.JSONArray().apply { order.forEach { put(it) } }) }
            cfg = Config.load(this)
            loadReco()
        }
    }

    /** Everything on the home screen the critters may stand on. */
    private fun critterPlaces(): List<View> {
        if (kidsOn) return kidsHome.places()
        val out = ArrayList<View>()
        for (row in listOf(favRow, chipsRow, topButtons)) for (i in 0 until row.childCount) out.add(row.getChildAt(i))
        fun walk(v: View) {
            if (v.isFocusable && v !is android.widget.HorizontalScrollView) out.add(v)
            else if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(bottom)
        if (npCard.visibility == View.VISIBLE) out.add(npCard)
        return out
    }

    private fun isInside(v: View, parent: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === parent) return true
            p = p.parent as? View
        }
        return false
    }

    private fun placeReco() {
        if (bottom.height == 0) return
        val top = bottom.bottom + content.top + dp(24f)
        val height = page.height - (bottom.height + dp(46f))
        val lp = recoBox.layoutParams as FrameLayout.LayoutParams
        if (lp.topMargin != top || lp.height != height) {
            lp.topMargin = top
            lp.height = height
            recoBox.layoutParams = lp
        }
    }

    /** Shows the mark when there is something below; brighter while the cursor is on the bottom row. */
    private fun showMoreHint() {
        val there = cfg.recommendations && reco.firstFocusable != null && !expanded && !kidsOn
        val onBottom = currentFocus?.let { isInside(it, bottom) } == true
        val target = if (!there) 0f else if (onBottom) 1f else 0.75f
        if (there) moreHint.visibility = View.VISIBLE
        moreHint.animate().alpha(target).setDuration(220).withEndAction { if (!there) moreHint.visibility = View.GONE }.start()
    }

    private fun expand() {
        if (expanded || reco.firstFocusable == null) return
        expanded = true
        moreHint.animate().alpha(0f).setDuration(120).start()
        recoBox.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val shift = -(bottom.top + content.top - dp(22f)).toFloat()
        pageTarget = shift
        slideShift = shift
        critters?.paused = true
        recoScroll.reset()
        reco.firstFocusable?.let { f ->
            f.requestFocus()
            reco.emphasize(f)
        }
        startSlide()
        slide.onRest = {
            endSlide()
            // fresh rows only once the page has settled, never in the middle of the motion
            if (expanded) loadReco()
        }
        slide.to(shift)
    }

    private fun collapse(moveFocus: Boolean) {
        if (!expanded) return
        expanded = false
        pageTarget = 0f
        critters?.paused = false
        if (moveFocus) (lastBottomFocus?.takeIf { it.isAttachedToWindow && isInside(it, bottom) } ?: bottom.getChildAt(0))?.requestFocus()
        recoBox.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        startSlide()
        slide.onRest = {
            endSlide()
            if (!expanded) {
                recoScroll.reset()
                reco.emphasize(null)
            }
        }
        slide.to(0f)
        showMoreHint()
    }

    /**
     * One frame of the slide: the page moves up, the rows rise and fade in a little behind it,
     * the screen darkens, and the photo behind drifts and zooms slightly, for depth.
     */
    private fun applySlide(y: Float) {
        page.translationY = y
        val k = if (slideShift == 0f) 0f else (y / slideShift).coerceIn(0f, 1f)
        recoDim.alpha = k
        recoBox.alpha = k * k
        reco.translationY = (1f - k) * dp(40f)
        if (Motion.level == Motion.FULL) {
            backdrop.pivotY = 0f
            backdrop.pivotX = backdrop.width / 2f
            backdrop.scaleX = 1f + 0.06f * k
            backdrop.scaleY = 1f + 0.06f * k
            backdrop.translationY = slideShift * 0.05f * k
        }
    }

    /**
     * The photo is drawn once into a ready-made layer while it drifts, so moving and zooming it
     * costs next to nothing. (The page itself changes during the slide, the cursor moves on it,
     * so a layer would have to be redrawn every frame and would only cost.)
     */
    private fun startSlide() {
        if (slide.running) return
        backdrop.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    private fun endSlide() {
        backdrop.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    private val liveTiles = ArrayList<LiveTile>()
    private var lastRows: List<Reco.Row>? = null

    /** Gives each live tile the pictures its app recommends now. */
    private fun feedLive(rows: List<Reco.Row>) {
        lastRows = rows
        for (t in liveTiles) {
            t.setPrograms(rows.flatMap { r -> r.programs.filter { it.pkg == t.app.pkg || (r.pkg == t.app.pkg && it.pkg.isBlank()) } })
        }
    }

    /**
     * An app published new recommendations: in front, the rows are read again; behind a menu or
     * the screensaver, they are read and their pictures fetched ahead (never while watching).
     */
    private val onRecoChanged: () -> Unit = {
        if (resumed) {
            // not while someone is looking through the rows (they would be built again under them):
            // the next time the rows are opened they are read again anyway
            if (!expanded) loadReco()
        } else if (cfg.recommendations && !kidsOn && Reco.allowed(this) && TvState.state(this) != "watching") {
            val app = applicationContext
            val order = FavStore.apps(this, cfg)
            val hidden = cfg.recoHidden
            val watchNext = cfg.recoWatchNext
            val custom = cfg.recoOrder
            Bg.run({ Reco.prefetch(app, Reco.load(app, order, hidden, watchNext, custom)) }) { }
        }
    }

    /** Reads the apps' rows in the background; the first rows are the favorites' apps. */
    private fun loadReco() {
        if (kidsOn) {
            loadKidsReco()
            return
        }
        if (!cfg.recommendations) {
            recoBox.visibility = View.GONE
            showMoreHint()
            // the live tiles still want their pictures, without the rows below
            if (liveTiles.isNotEmpty() && Reco.allowed(this) && !recoLoading) {
                recoLoading = true
                val app = applicationContext
                val order = FavStore.apps(this, cfg)
                Bg.run({ Reco.load(app, order, cfg.recoHidden, true, emptyList()) }) { rows ->
                    recoLoading = false
                    if (!rows.isNullOrEmpty()) feedLive(rows)
                }
            }
            return
        }
        recoBox.visibility = View.VISIBLE
        if (!Reco.allowed(this)) {
            reco.showAsk()
            showMoreHint()
            return
        }
        if (recoLoading) return
        recoLoading = true
        val app = applicationContext
        val order = FavStore.apps(this, cfg)
        val hidden = cfg.recoHidden
        val watchNext = cfg.recoWatchNext
        val custom = cfg.recoOrder
        Bg.run({ Reco.load(app, order, hidden, watchNext, custom) }) { rows ->
            recoLoading = false
            if (!rows.isNullOrEmpty()) feedLive(rows)
            if (rows.isNullOrEmpty()) reco.showEmpty()
            else reco.show(rows) { pkg -> AppColors.known(pkg) ?: Apps.one(this, pkg)?.let { AppColors.of(it) } ?: 0xFFE9E7E2.toInt() }
            showMoreHint()
            if (expanded && currentFocus?.let { isInside(it, recoBox) } != true) reco.firstFocusable?.requestFocus()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 11) loadReco()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (KeyLag.swallow(this, event)) return true
        if (picker.visibility == View.VISIBLE) {
            // a profile has to be chosen; Back does nothing here
            if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
            return super.dispatchKeyEvent(event)
        }
        if (kidsOn) {
            // the kids screen: no colour buttons, no recommendations page, Back does nothing
            return super.dispatchKeyEvent(event)
        }
        // the release of the button that put a favourite down: not a click on it
        if (swallowUp >= 0 && event.keyCode == swallowUp) {
            if (event.action == KeyEvent.ACTION_UP) swallowUp = -1
            return true
        }
        // moving a favourite: ◀ ▶ move it, OK / Back put it down, nothing else
        if (moving != null) {
            val k = event.keyCode
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_UP ||
                k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER ||
                k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_NUMPAD_ENTER
            ) {
                if (event.action == KeyEvent.ACTION_DOWN) when (k) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> shift(-1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> shift(1)
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { }
                    else -> if (event.repeatCount == 0) { endMove(); swallowUp = k }
                }
                return true
            }
        }
        // an open folder: Back closes it
        if (folderOpen != null && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) closeFolder(true)
            return true
        }
        // ▲ ▲ ▼ ▼ ◀ ▶ ◀ ▶: Kavics kalandja (the keys still move the focus as usual)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && secret(event.keyCode)) {
            startActivity(android.content.Intent(this, CaveGameActivity::class.java))
        }
        // the remote's colour buttons; when they work everywhere, KeyService has them already
        if (!(cfg.colorKeysEverywhere && KeyService.isOn(this)) && ColorKeys.handle(this, cfg, event)) return true
        // the gear button; KeyService has it when it runs
        if (!KeyService.isOn(this) && SettingsKey.handle(this, cfg, event)) return true
        val down = event.action == KeyEvent.ACTION_DOWN
        val focus = currentFocus
        if (!expanded && down && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && focus != null && isInside(focus, bottom) &&
            cfg.recommendations && reco.firstFocusable != null
        ) {
            lastBottomFocus = focus
            expand()
            return true
        }
        if (expanded && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (!down) collapse(true)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun buildNowPlaying() {
        npCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9f), dp(9f), dp(11f), dp(9f))
            background = Look.surface(this@HomeActivity, dp(Look.R_TILE).toFloat())
            visibility = View.GONE
            // OK plays or pauses; holding OK opens the Music panel with every zone
            isFocusable = true
            isClickable = true
            isLongClickable = true
            contentDescription = "Play / pause. Hold for music zones"
            Glow.on(this, dp(Look.R_TILE).toFloat())
            setOnFocusChangeListener { v, f ->
                paintNowPlaying(f)
                Glow.move(v, f, 1.04f)
            }
            setOnClickListener { togglePlay() }
            setOnLongClickListener {
                if (cfg.nowPlaying == null) {
                    // Music Assistant: straight to its Now playing page
                    startActivity(Intent(this@HomeActivity, MaActivity::class.java).putExtra("now", true))
                    overridePendingTransition(0, 0)
                    awayOnOwnScreen = true
                } else open(MusicActivity::class.java)
                true
            }
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
            // only shows the state now: the whole card is the button
            setImageResource(R.drawable.ic_pause)
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            isFocusable = false
            background = rounded(0x33FFFFFF, dp(16f).toFloat())
            setColorFilter(C.TEXT)
        }
        npCard.addView(npButton, LinearLayout.LayoutParams(dp(32f), dp(32f)))
    }

    private fun paintNowPlaying(f: Boolean) {
        npCard.background = if (f) Glow.lit(npCard, dp(Look.R_TILE).toFloat()) else Look.surface(this, dp(Look.R_TILE).toFloat())
        val dark = f && !Glow.glows(npCard)
        npLabel.setTextColor(if (dark) C.DARK2 else C.TEXT3)
        npTitle.setTextColor(if (dark) C.DARK else C.TEXT)
        npArtist.setTextColor(if (dark) C.DARK2 else C.TEXT2)
        npProgress.background = rounded(if (dark) C.DARK else C.TEXT, dp(1f).toFloat())
        npButton.background = rounded(if (dark) 0x26141413 else 0x33FFFFFF, dp(16f).toFloat())
        npButton.setColorFilter(if (dark) C.DARK else C.TEXT)
    }

    /** Round icon-only button; no name label (the icons speak for themselves). */
    private fun iconButton(icon: Int, name: String, above: Boolean, onClick: () -> Unit): ImageView {
        val v = ImageView(this)
        v.setImageResource(icon)
        v.setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
        v.isFocusable = true
        v.isClickable = true
        v.contentDescription = name
        val normal = Look.surface(this, dp(Look.R_TILE).toFloat())
        v.background = normal
        v.setColorFilter(C.TEXT)
        Glow.on(v, dp(Look.R_TILE).toFloat())
        v.setOnFocusChangeListener { _, f ->
            v.background = if (f) Glow.lit(v, dp(Look.R_TILE).toFloat()) else normal
            v.setColorFilter(if (f && !Glow.glows(v)) C.DARK else C.TEXT)
            Glow.move(v, f)
        }
        v.setOnClickListener { onClick() }
        return v
    }

    /** Opens one of the launcher's own screens with a cross-fade. */
    private fun open(screen: Class<*>) {
        startActivity(Intent(this, screen))
        overridePendingTransition(0, 0)
        awayOnOwnScreen = true
    }

    private fun addIcon(row: LinearLayout, v: View) {
        val lp = LinearLayout.LayoutParams(dp(40f), dp(40f))
        if (row.childCount > 0) lp.leftMargin = dp(8f)
        row.addView(v, lp)
    }

    private fun showHint(v: View, text: String, above: Boolean) {
        hintFor = v
        hint.animate().cancel()
        hint.text = text
        hint.alpha = 0f
        hint.visibility = View.VISIBLE
        hint.post {
            if (hintFor !== v) return@post
            hint.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val w = hint.measuredWidth
            val h = hint.measuredHeight
            val loc = IntArray(2)
            v.getLocationInWindow(loc)
            // the page may still be sliding back: place the label where the button will rest
            if (isInside(v, page)) loc[1] += (pageTarget - page.translationY).toInt()
            val maxX = maxOf(dp(8f), rootView.width - w - dp(8f))
            val x = (loc[0] + v.width / 2 - w / 2).coerceIn(dp(8f), maxX)
            val y = if (above) loc[1] - h - dp(5f) else loc[1] + v.height + dp(5f)
            hint.translationX = x.toFloat()
            hint.translationY = y.toFloat()
            hint.animate().alpha(1f).setDuration(120).start()
        }
    }

    private fun hideHint(v: View) {
        if (hintFor === v) {
            hintFor = null
            hint.animate().cancel()
            hint.visibility = View.INVISIBLE
        }
    }

    private fun rebuildDynamic(focusFav: Int = 0) {
        // favourites: fixed-size tiles in a scrolling row
        favRow.removeAllViews()
        liveTiles.clear()
        val live = cfg.design == "live" && !kidsOn
        val tileW = (resources.displayMetrics.widthPixels - 2 * dp(48f) - 5 * dp(12f)) / 6
        // Live: taller tiles (about 4:3), so a recommendation's picture has room
        favRow.layoutParams = favRow.layoutParams.apply { height = if (live) tileW * 3 / 4 else dp(76f) }
        closeFolder(false)
        moving = null
        // apps and folders, in the favourites' order; an app no longer installed is left out
        val entries: List<View> = FavStore.get(this, cfg).mapNotNull { token ->
            if (Folders.isToken(token)) {
                val f = Folders.get(this, Folders.idOf(token)) ?: return@mapNotNull null
                FolderTile(this, f, Folders.apps(this, f)).apply {
                    setOnClickListener { openFolder(this) }
                }
            } else {
                val app = Apps.one(this, token) ?: return@mapNotNull null
                val tile: View = if (live) LiveTile(this, app).also { liveTiles.add(it) } else AppTile(this, app)
                tile.setOnClickListener { if (!Apps.launch(this, app.pkg)) toast("Can't open ${app.label}") }
                tile
            }.also { it.tag = token }
        }
        entries.forEachIndexed { i, tile ->
            tile.setOnLongClickListener { favMenu(tile); true }
            val lp = LinearLayout.LayoutParams(tileW, ViewGroup.LayoutParams.MATCH_PARENT)
            if (i > 0) lp.leftMargin = dp(12f)
            favRow.addView(tile, lp)
        }
        lastRows?.let { feedLive(it) }
        if (entries.isEmpty()) {
            favRow.addView(pill("Add favorites", R.drawable.ic_plus, glow = true) {
                open(AllAppsActivity::class.java)
            }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_VERTICAL })
        }

        if (kidsOn) {
            bindKids()
            return
        }

        // top right, icon only: Today, TV settings and launcher settings. (Google TV home is in
        // Settings › About, it is not needed every day.)
        topButtons.removeAllViews()
        if (cfg.haEnabled) {
            val today = iconButton(R.drawable.ic_today, "Today", false) { open(TodayActivity::class.java) }
            topButtons.addView(today, LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { leftMargin = dp(8f) })
        }
        // the quick settings (the launcher's settings are one row inside them)
        val gear = iconButton(R.drawable.ic_gear, "Quick settings", false) {
            QuickPanel.show(this)
        }
        gear.setOnLongClickListener { CrashLog.showLog(this); true }
        gearButton = gear
        markUpdate()
        topButtons.addView(gear, LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { leftMargin = dp(8f) })
        // the TV's settings and the profiles are in the quick settings (the remote's gear button)

        // bottom row: icon buttons on the left, quick actions in the middle, inputs on the right
        bottom.removeAllViews()
        val icons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        addIcon(icons, iconButton(R.drawable.ic_grid, "All apps", true) {
            open(AllAppsActivity::class.java)
        })
        if (cfg.haEnabled) {
            addIcon(icons, iconButton(R.drawable.ic_dashboard, "Home Assistant dashboard", true) {
                open(DashboardActivity::class.java)
            })
            if (cfg.cameras.isNotEmpty()) {
                addIcon(icons, iconButton(R.drawable.ic_camera, "Cameras", true) {
                    open(CamerasActivity::class.java)
                })
            }
            // Music Assistant: hidden once the screen found that Home Assistant has none
            if (Prefs.of(this).getBoolean("ma_present", true)) {
                addIcon(icons, iconButton(R.drawable.ic_music, "Music", true) {
                    open(MaActivity::class.java)
                })
            }
        }
        bottom.addView(icons)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        if (cfg.haEnabled) {
            cfg.scenes.take(6).forEachIndexed { i, sc ->
                val lp = LinearLayout.LayoutParams(-2, -2)
                if (i > 0) lp.leftMargin = dp(8f)
                actions.addView(pill(sc.title, sceneIcon(sc), glow = true) { runScene(sc) }, lp)
            }
        }
        // nothing cut: a pill grows and glows like an app tile (the row seldom scrolls at all)
        actions.clipChildren = false
        val actionScroll = RowScroller(this)
        actionScroll.clipToPadding = false
        actionScroll.clipChildren = false
        actionScroll.isFocusable = false
        actionScroll.isFillViewport = true      // stretches the row, so its pills sit centred
        actionScroll.addView(actions)
        bottom.addView(actionScroll, LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(16f)
            rightMargin = dp(16f)
        })

        // the TV and the inputs: one "Inputs" pill with a list, so the row never runs out of room
        val inputs = Inputs.visible(this, cfg)
        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        val choices = ArrayList<Pair<String, () -> Unit>>()
        if (Inputs.hasTv(this)) choices.add("TV" to { if (!Inputs.openTv(this)) toast("TV is not available") })
        inputs.forEach { inp ->
            val text = if (inp.device != null) "${inp.name} · ${inp.device}" else inp.name
            choices.add(text to { if (!Inputs.open(this, inp.id)) toast("Can't switch to ${inp.name}") })
        }
        when (choices.size) {
            0 -> {}
            1 -> inputRow.addView(pill(choices[0].first, if (choices[0].first == "TV") R.drawable.ic_tv else R.drawable.ic_input, glow = true) { choices[0].second() })
            else -> inputRow.addView(pill("Inputs", R.drawable.ic_input, glow = true) {
                Popup.menu(this, "Inputs", choices.map { it.first }) { i -> choices[i].second() }
            })
        }
        bottom.addView(inputRow)

        // the statuses, together in one glass capsule, with thin lines between them
        chipsRow.removeAllViews()
        chipViews.clear()
        weatherChipView = null
        fun divider() {
            if (chipsRow.childCount == 0) return
            chipsRow.addView(View(this).apply { setBackgroundColor(Look.DIVIDER) }, LinearLayout.LayoutParams(dp(1f), dp(14f)).apply {
                leftMargin = dp(11f)
                rightMargin = dp(11f)
            })
        }
        if (cfg.haEnabled) {
            if (cfg.weatherChip && cfg.weather != null) {
                val w = chip(R.drawable.ic_cloud, "–")
                weatherChipView = w
                chipsRow.addView(w, LinearLayout.LayoutParams(-2, -2))
            }
            cfg.status.take(4).forEach { s ->
                divider()
                val v = chip(statusIcon(s), if (s.label.isBlank()) "–" else "${s.label} –")
                chipsRow.addView(v, LinearLayout.LayoutParams(-2, -2))
                chipViews.add(s to v)
            }
        }
        if (chipsRow.childCount > 0) {
            chipsRow.setPadding(dp(15f), 0, dp(15f), 0)
            chipsRow.minimumHeight = dp(40f)
            chipsRow.visibility = View.VISIBLE
            // OK on the status capsule opens Home Assistant's dashboard
            chipsRow.isFocusable = cfg.haEnabled
            chipsRow.isClickable = cfg.haEnabled
            chipsRow.contentDescription = "Home Assistant dashboard"
            Glow.on(chipsRow, dp(Look.R_TILE).toFloat())
            paintChips(chipsRow.isFocused)
            chipsRow.setOnFocusChangeListener { v, f ->
                paintChips(f)
                // only grows: its neighbours are the clock and the corner buttons, which stay put
                Look.grow(v, f, 1.05f)
            }
            chipsRow.setOnClickListener { open(DashboardActivity::class.java) }
        } else {
            chipsRow.visibility = View.GONE
        }
        if (!cfg.haEnabled || npEntity() == null) npCard.visibility = View.GONE

        val idx = focusFav.coerceIn(0, maxOf(0, favRow.childCount - 1))
        favRow.getChildAt(idx)?.takeIf { it.isFocusable }?.requestFocus()
    }

    /** The status capsule lit by the cursor: dark text and icons on the light fill. */
    private fun paintChips(f: Boolean) {
        val r = dp(Look.R_TILE).toFloat()
        chipsRow.background = if (f) Glow.lit(chipsRow, r) else Look.surface(this, r)
        val dark = f && !Glow.glows(chipsRow)
        for (i in 0 until chipsRow.childCount) {
            val c = chipsRow.getChildAt(i)
            if (c is TextView) {
                // glowing: the text a step brighter
                c.setTextColor(if (dark) C.DARK else if (f) C.TEXT else C.TEXT2)
                c.compoundDrawables.forEach { it?.setTint(if (dark) C.DARK else if (f) C.TEXT else C.TEXT2) }
            } else if (c !is ViewGroup) {
                c.setBackgroundColor(if (dark) 0x40141413 else Look.DIVIDER)
            }
        }
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
        Profile.checkBoot(this, cfg)
        ha = cfg.client()
        backdrop.setDim(cfg.dim)
        val kids = Profile.kids(this, cfg)
        if (kids != kidsOn) {
            kidsOn = kids
            content.visibility = if (kids) View.GONE else View.VISIBLE
            kidsHome.visibility = if (kids) View.VISIBLE else View.GONE
            if (kids) collapse(false)
            hint.visibility = View.INVISIBLE
        }
        val sig = kids.toString() + cfg.toString() + FavStore.get(this, cfg).joinToString() + Inputs.all(this).size +
            (Prefs.of(this).getString("folders", "") ?: "")
        if (sig != signature) {
            signature = sig
            rebuildDynamic(focusFav)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        LowRam.trim(level)
        // about to be stopped (or, on a low-tier TV, in the background and Android wants memory)
        if (!resumed && (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
                (LowRam.releasesUnseen && level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))) releaseImages()
    }

    /** The language the screen was built in: a change in Settings builds it again. */
    private var builtHu = Lang.hu

    override fun onResume() {
        super.onResume()
        CrashLog.entered(this)
        if (builtHu != Lang.hu) {
            builtHu = Lang.hu
            recreate()
            return
        }
        resumed = true
        reload()
        ServiceKeeper.ensure(this, cfg)
        LocalAdb.askOnce(this) { resumed && !isFinishing }
        CrashLog.showIfAny(this)
        critters?.setCount(cfg.critters, cfg.christmas)
        critters?.paused = expanded
        if (awayOnOwnScreen) {
            // back from one of our own screens: let the tiles fade back instead of popping in
            awayOnOwnScreen = false
            if (Motion.level == Motion.FULL) {
                // its own animator: content.animate() is shared with the slow OLED pixel shift
                content.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                android.animation.ObjectAnimator.ofFloat(content, View.ALPHA, 0.3f, 1f).apply {
                    duration = 200
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            content.setLayerType(View.LAYER_TYPE_NONE, null)
                        }
                    })
                    start()
                }
            }
        }
        registerReceiver(tick, IntentFilter(Intent.ACTION_TIME_TICK))
        onMinute()
        askProfile()
        PopupService.sync(this)
        markUpdate()
        checkUpdates()
        loadReco()
        if (ha != null) {
            Bg.main.post(statusLoop)
            if (npEntity() != null) Bg.main.post(npLoop)
        }
    }

    override fun onStop() {
        super.onStop()
        // another screen in front: a low-tier TV makes room at once; the others keep everything
        if (LowRam.releasesUnseen) releaseImages()
    }

    override fun onPause() {
        super.onPause()
        // another app in front: a favourite being moved is put down where it is, a folder closes
        endMove()
        closeFolder(false)
        CrashLog.left(this)
        resumed = false
        critters?.paused = true
        try { unregisterReceiver(tick) } catch (e: Exception) { }
        Bg.main.removeCallbacks(statusLoop)
        Bg.main.removeCallbacks(npLoop)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        collapse(false)
        if (intent?.getBooleanExtra("wake", false) == true) {
            signature = ""
            if (resumed) {
                reload()
                askProfile()
            }
            return
        }
        if (kidsOn) {
            // sent back here by the kids profile's guard: say why, kindly
            val blocked = intent?.getStringExtra("kids_blocked")
            val reason = intent?.getStringExtra("kids_reason").orEmpty()
            if (blocked != null && reason.isEmpty()) kidsHome.say(if (blocked.isBlank()) "That one is not for now 🙈" else "$blocked is not for now 🙈")
            if (reason.isNotEmpty()) updateKids(force = true)
            if (!kidsHome.asleep) kidsHome.firstTile()?.requestFocus()
            return
        }
        favRow.getChildAt(0)?.takeIf { it.isFocusable }?.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Launcher: Back does nothing on the home screen.
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU && !kidsOn) {
            currentFocus?.takeIf { it.parent === favRow && it.tag is String }?.let { favMenu(it); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- clock, background, OLED care ----------

    private fun onMinute() {
        val now = ZonedDateTime.now()
        clock.text = now.format(timeFmt)
        date.text = now.format(dateFmt)
        if (kidsOn) {
            kidsHome.clock.text = now.format(timeFmt)
            updateKids(force = false)
        }
        backdrop.show(cfg.background, Backgrounds.phase(this, cfg), true)
        // OLED: shift static content by a few pixels every minute (an LCD needs none of it)
        val shifts = arrayOf(0 to 0, 2 to 1, -1 to 2, -2 to -1, 1 to -2, 2 to -1, -1 to -1)
        shiftIndex = if (cfg.pixelShift) (shiftIndex + 1) % shifts.size else 0
        val (sx, sy) = shifts[shiftIndex]
        if (!cfg.pixelShift && content.translationX == 0f && content.translationY == 0f) return
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
        val w = weatherChipView
        val entity = cfg.weather
        if (w != null && entity != null) {
            Bg.run({ client.state(entity) }) { s ->
                if (s == null || !resumed || weatherChipView !== w) return@run
                val t = s.optJSONObject("attributes")?.optDouble("temperature", Double.NaN) ?: Double.NaN
                w.text = if (t.isNaN()) "–" else Fmt.num(Math.round(t).toDouble(), "°")
                val d = getDrawable(weatherIcon(s.optString("state")))!!.mutate()
                d.setBounds(0, 0, dp(13f), dp(13f))
                d.setTint(C.TEXT2)
                w.setCompoundDrawables(d, null, null, null)
            }
        }
    }

    /** A small dot on the settings button while a newer version waits on GitHub. */
    private fun markUpdate() {
        val g = gearButton ?: return
        g.foreground = if (Updates.pending(this) != null) UpdateDot(dp(4f).toFloat(), dp(3f).toFloat()) else null
    }

    /** Looks for a newer version at most every six hours. */
    private fun checkUpdates() {
        if (Updates.checkedAgo(this) < 6 * 3600_000L) return
        Bg.slow({ Updates.latest() }) { r ->
            Updates.remember(this, r)
            markUpdate()
        }
    }

    /**
     * The player on the now-playing card: the one chosen in the settings (a Roon zone), or else
     * the Music Assistant player last used on this TV's Music screen.
     */
    private fun npEntity(): String? {
        if (kidsOn) return null
        cfg.nowPlaying?.let { return it }
        val p = Prefs.of(this)
        return if (p.getBoolean("ma_present", true)) p.getString("ma_player", null) else null
    }

    private fun refreshNowPlaying() {
        val client = ha ?: return
        val entity = npEntity() ?: return
        Bg.run({ client.state(entity) }) { s ->
            if (s == null || !resumed) return@run
            val state = s.optString("state")
            val a = s.optJSONObject("attributes")
            val active = (state == "playing" || state == "paused") && a != null
            if (!active) {
                // nothing playing: no card. The Music panel is then reached from Settings › Home screen.
                if (npCard.hasFocus()) favRow.getChildAt(0)?.requestFocus()
                npCard.visibility = View.GONE
                return@run
            }
            npCard.visibility = View.VISIBLE
            npLabel.text = if (cfg.nowPlaying != null) cfg.nowPlayingLabel
                else "Now playing · " + s.optJSONObject("attributes")?.optString("friendly_name", "").orEmpty().trim()
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
                // a cover of a few thousand pixels for a 48 dp square: made small as it is read
                val size = dp(96f)
                Bg.run({ client.bitmap(pic, size) }) { b ->
                    if (npPicture != pic) return@run          // a newer song's cover is on its way
                    if (b != null) npCover.setImageBitmap(b) else npPicture = null   // tried again next time
                }
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
        val entity = npEntity() ?: return
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

    // ---------- the kids profile ----------

    private fun bindKids() {
        val apps = cfg.kids.apps.mapNotNull { Apps.one(this, it) }
        kidsHome.bind(cfg, apps) { app ->
            if (!Apps.launch(this, app.pkg)) kidsHome.say("${app.label} does not open 🙈")
        }
        kidsHome.reco.onHold = null
        kidsHome.firstTile()?.requestFocus() ?: kidsHome.avatar.requestFocus()
        updateKids(force = true)
    }

    /** Time left and the sleep screen; Home Assistant's helpers are read at most once a minute. */
    private fun updateKids(force: Boolean) {
        val c = cfg
        val app = applicationContext
        Bg.run({
            KidsTime.refresh(c, force)
            KidsTime.status(app, c)
        }) { st ->
            if (st == null || !kidsOn) return@run
            kidsHome.showStatus(st)
            sleepTvLater(st)
        }
    }

    /**
     * Ten minutes after the sleep screen came up for bedtime or the end of the day's time, the TV
     * goes to sleep too (once a day, through Home Assistant's ADB connection).
     */
    private fun sleepTvLater(st: KidsTime.Status) {
        val r = st.blocked
        if (r != KidsTime.Reason.BEDTIME && r != KidsTime.Reason.USED_UP) {
            kidsAsleepSince = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (kidsAsleepSince == 0L) kidsAsleepSince = now
        val today = java.time.LocalDate.now().toString() + r.name
        if (now - kidsAsleepSince >= 10 * 60_000L && kidsSentToSleep != today && cfg.haEnabled) {
            kidsSentToSleep = today
            Adb.run(this, cfg, "input keyevent KEYCODE_SLEEP") { }
        }
    }

    /** The avatar on the kids screen: back to the grown-ups' profile, with the PIN. */
    private fun leaveKids() {
        withPin({ kidsHome.say("That's not the PIN 🔒") }) { Profile.set(this, false) }
    }

    /**
     * Asks for the PIN (Home Assistant's newest one, read first) and runs [ok] when it is right.
     * Without a PIN [ok] runs straight away.
     */
    private fun withPin(wrong: () -> Unit, ok: () -> Unit) {
        val c = cfg
        val app = applicationContext
        Bg.run({ KidsTime.refresh(c, true); KidsTime.pin(app, c) }) { p ->
            val pin = p ?: KidsTime.pin(this, c)
            if (pin.isBlank()) {
                ok()
                return@run
            }
            PinPad.ask(this, "${c.kids.mainName}'s profile", "Enter the PIN") { entered ->
                if (entered == pin) ok() else wrong()
            }
        }
    }

    /** Shows "Who's watching?" when the TV has just woken up or started. */
    private fun askProfile() {
        if (!Profile.askPending(this, cfg)) {
            if (picker.visibility == View.VISIBLE && !cfg.kids.on) picker.visibility = View.GONE
            return
        }
        if (picker.visibility == View.VISIBLE) return
        critters?.paused = true
        picker.show(cfg.kids, kidsOn)
    }

    private fun pick(kids: Boolean) {
        fun done() {
            Profile.clearAsk(this)
            picker.hide()
            critters?.paused = expanded
            if (kids != kidsOn) Profile.set(this, kids)
        }
        if (kids) done() else withPin({ picker.say("That's not the PIN") }) { done() }
    }

    /** What the allowed apps recommend, at most two rows. */
    private fun loadKidsReco() {
        if (!cfg.recommendations || !Reco.allowed(this) || recoLoading) {
            if (!cfg.recommendations) kidsHome.reco.visibility = View.GONE
            return
        }
        recoLoading = true
        val app = applicationContext
        val allowed = cfg.kids.apps.toSet()
        val hidden = cfg.recoHidden
        Bg.run({
            Reco.load(app, cfg.kids.apps, hidden, true, emptyList()).mapNotNull { row ->
                when {
                    row.pkg.isEmpty() -> row.copy(programs = row.programs.filter { it.pkg in allowed }).takeIf { it.programs.isNotEmpty() }
                    row.pkg in allowed -> row
                    else -> null
                }
            }.take(2)
        }) { rows ->
            recoLoading = false
            if (!kidsOn) return@run
            kidsHome.reco.visibility = if (rows.isNullOrEmpty()) View.GONE else View.VISIBLE
            if (!rows.isNullOrEmpty()) kidsHome.reco.show(rows) { pkg -> AppColors.known(pkg) ?: Apps.one(this, pkg)?.let { AppColors.of(it) } ?: 0xFFE9E7E2.toInt() }
        }
    }

    // ---------- favourites ----------

    private fun favMenu(tile: View) {
        val token = tile.tag as? String ?: return
        val folder = (tile as? FolderTile)?.folder
        val title = folder?.name ?: Apps.one(this, token)?.label ?: token
        val items = if (folder != null) listOf("Move", "Rename", "Remove from favorites")
            else listOf("Move", "Remove from favorites")
        Popup.menu(this, title, items) { which ->
            when (items[which]) {
                "Open" -> tile.performClick()
                "Move" -> startMove(tile)
                "Rename" -> Popup.text(this, "Rename", folder!!.name, "Name of the folder") { name ->
                    Folders.rename(this, folder.id, name)
                    signature = ""
                    reload(favRow.indexOfChild(tile).coerceAtLeast(0))
                }
                "Remove from favorites" -> {
                    val favs = FavStore.get(this, cfg)
                    val at = favRow.indexOfChild(tile)
                    favs.remove(token)
                    FavStore.set(this, favs)
                    signature = ""
                    reload((at - 1).coerceAtLeast(0))
                }
            }
        }
    }

    // ---------- moving a favourite (as on Google TV home) ----------

    private val secretCode = intArrayOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT
    )
    private var secretAt = 0
    private var secretTime = 0L

    /** True when [code] finishes the secret sequence (each key within 1.5 s of the one before). */
    private fun secret(code: Int): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - secretTime > 1500) secretAt = 0
        secretTime = now
        secretAt = if (code == secretCode[secretAt]) secretAt + 1 else if (code == secretCode[0]) 1 else 0
        if (secretAt == secretCode.size) { secretAt = 0; return true }
        return false
    }

    private val moveArrows by lazy { MoveArrows(dp(10f).toFloat()) }

    private fun startMove(tile: View) {
        if (favRow.childCount < 2) return
        moving = tile
        tile.requestFocus()
        tile.foreground = moveArrows
        tile.animate().translationY(-dp(6f).toFloat()).setDuration(150).start()
        toast("◀ ▶ to move, OK when done")
    }

    /** One step to the left or the right: the neighbour slides over to the other side. */
    private fun shift(dir: Int) {
        val tile = moving ?: return
        val i = favRow.indexOfChild(tile)
        val j = i + dir
        if (i < 0 || j < 0 || j >= favRow.childCount) return
        val other = favRow.getChildAt(j)
        if (other.tag !is String) return
        // the tile that has the focus stays where it is in the hierarchy; the neighbour moves
        favRow.removeViewAt(j)
        favRow.addView(other, i)
        for (k in 0 until favRow.childCount) {
            val lp = favRow.getChildAt(k).layoutParams as LinearLayout.LayoutParams
            lp.leftMargin = if (k == 0) 0 else dp(12f)
        }
        favRow.requestLayout()
        val step = (tile.width + dp(12f)).toFloat()
        if (Motion.level != Motion.OFF) {
            other.translationX = dir * step
            other.animate().translationX(0f).setDuration(180).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
            tile.translationX = -dir * step
            tile.animate().translationX(0f).setDuration(180).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        }
        tile.post { tile.requestRectangleOnScreen(android.graphics.Rect(0, 0, tile.width, tile.height)) }
    }

    /** OK or Back: the favourite is put down and the new order kept. */
    private fun endMove() {
        val tile = moving ?: return
        moving = null
        tile.foreground = null
        tile.animate().translationY(0f).setDuration(150).start()
        val shown = (0 until favRow.childCount).mapNotNull { favRow.getChildAt(it).tag as? String }
        // what is not on the row (an app not installed now) keeps its place at the end
        val rest = FavStore.get(this, cfg).filter { it !in shown }
        FavStore.set(this, shown + rest)
    }

    // ---------- a folder on the favourites row ----------

    private fun openFolder(tile: FolderTile) {
        if (folderOpen === tile) { closeFolder(true); return }
        val f = Folders.get(this, tile.folder.id) ?: return
        val apps = Folders.apps(this, f)
        folderRow.removeAllViews()
        folderTitle.text = f.name
        val tileW = favRow.getChildAt(0)?.width?.takeIf { it > 0 } ?: dp(140f)
        apps.forEachIndexed { i, app ->
            val t = AppTile(this, app)
            t.setOnClickListener { if (!Apps.launch(this, app.pkg)) toast("Can't open ${app.label}") }
            t.setOnLongClickListener {
                Popup.menu(this, app.label, listOf("Open", "Take out of the folder")) { which ->
                    if (which == 0) Apps.launch(this, app.pkg) else {
                        Folders.remove(this, app.pkg)
                        signature = ""
                        reload(favRow.indexOfChild(tile).coerceAtLeast(0))
                    }
                }
                true
            }
            folderRow.addView(t, LinearLayout.LayoutParams(tileW, dp(76f)).apply { if (i > 0) leftMargin = dp(12f) })
        }
        if (apps.isEmpty()) {
            folderRow.addView(label("Empty folder: add apps on the All apps screen (Hold OK on an app)", 11f, C.TEXT2),
                LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_VERTICAL })
        }
        folderOpen = tile
        folderBox.visibility = View.VISIBLE
        folderBox.post { placeCallout() }
        if (Motion.level != Motion.OFF) {
            folderBox.alpha = 0f
            folderBox.translationY = dp(12f).toFloat()
            folderBox.animate().alpha(1f).translationY(0f).setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        }
        folderRow.getChildAt(0)?.takeIf { it.isFocusable }?.requestFocus()
    }

    private val folderCallout by lazy { FolderCallout(0f, dp(9f).toFloat(), dp(5f).toFloat()) }

    /** A band across the whole screen, its point over the folder's tile. */
    private fun placeCallout() {
        val tile = folderOpen ?: return
        val c = folderCallout
        c.left = 0f
        c.right = folderBox.width.toFloat()
        val at = IntArray(2)
        val box = IntArray(2)
        tile.getLocationInWindow(at)
        folderBox.getLocationInWindow(box)
        c.tipX = (at[0] - box[0]) + tile.width / 2f
        folderBox.invalidate()
    }

    /** The folder's row goes; with [refocus] the folder's own tile gets the focus back. */
    private fun closeFolder(refocus: Boolean) {
        val tile = folderOpen ?: return
        folderOpen = null
        folderBox.animate().cancel()
        folderBox.visibility = View.GONE
        folderRow.removeAllViews()
        if (refocus && tile.parent === favRow) tile.requestFocus()
    }

    private fun toast(msg: String) = Toast.makeText(this, Lang.tr(msg), Toast.LENGTH_SHORT).show()
}

/** The dot in the corner of a round button. */
private class UpdateDot(private val r: Float, private val inset: Float) : android.graphics.drawable.Drawable() {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = C.ON }

    override fun draw(c: android.graphics.Canvas) {
        val b = bounds
        c.drawCircle(b.right - inset - r, b.top + inset + r, r, p)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

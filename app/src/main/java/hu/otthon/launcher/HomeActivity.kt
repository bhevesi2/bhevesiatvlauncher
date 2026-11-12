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
    private lateinit var rootView: FrameLayout

    // the recommendation rows below the home screen, and the page that slides up to them
    private lateinit var page: FrameLayout
    private lateinit var recoBox: LinearLayout
    private lateinit var recoScroll: ColScroller
    private lateinit var reco: RecoPanel
    private lateinit var recoInfo: TextView
    private lateinit var recoDim: View
    private var expanded = false
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    // ---------- UI ----------

    private fun buildUi() {
        val side = dp(48f)
        val root = FrameLayout(this)
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
        buildReco()

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
        topButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(topButtons)
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
        root.addView(hint, FrameLayout.LayoutParams(-2, -2))

        setContentView(root)
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
        }
        recoInfo = label("", 12f, C.TEXT2).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(48f), 0, dp(48f), dp(6f))
        }
        recoBox.addView(recoInfo, LinearLayout.LayoutParams(-1, -2))
        recoScroll = ColScroller(this).apply { clipToPadding = false; clipChildren = false }
        reco = RecoPanel(this)
        recoScroll.addView(reco, FrameLayout.LayoutParams(-1, -2))
        recoBox.addView(recoScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        page.addView(recoBox, FrameLayout.LayoutParams(-1, 0))
        FocusGlide.install(recoBox)

        reco.onCard = { card ->
            val p = card?.program
            recoInfo.text = if (p == null) "" else listOf(p.title, p.subtitle, p.description).filter { it.isNotBlank() }.joinToString("   ·   ")
        }
        reco.onAllow = {
            if (android.os.Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(Reco.PERMISSION), 11)
        }
        reco.onHold = { card ->
            val pkg = card.program.pkg
            val app = Apps.one(this, pkg)?.label ?: pkg
            Popup.menu(this, card.program.title, listOf("Open", "Hide recommendations from $app")) { which ->
                if (which == 0) Reco.open(this, card.program, card.row)
                else {
                    ConfigStore.edit(this) { r -> ConfigStore.array(r, "reco_hidden").put(pkg) }
                    cfg = Config.load(this)
                    loadReco()
                }
            }
        }
        // keep the rows right under the bottom row, as tall as the screen minus the bottom row
        page.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeReco() }
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            if (now == null) return@addOnGlobalFocusChangeListener
            if (expanded && isInside(now, content)) collapse(false)
            if (!expanded && isInside(now, bottom)) lastBottomFocus = now
        }
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
        val top = bottom.bottom + content.top + dp(16f)
        val height = page.height - (bottom.height + dp(40f))
        val lp = recoBox.layoutParams as FrameLayout.LayoutParams
        if (lp.topMargin != top || lp.height != height) {
            lp.topMargin = top
            lp.height = height
            recoBox.layoutParams = lp
        }
    }

    private fun expand() {
        if (expanded || reco.firstFocusable == null) return
        expanded = true
        recoBox.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val shift = -(bottom.top + content.top - dp(22f)).toFloat()
        val ms = if (Motion.level == Motion.OFF) 0L else 320L
        page.animate().translationY(shift).setDuration(ms).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        recoDim.animate().alpha(1f).setDuration(ms).start()
        reco.firstFocusable?.requestFocus()
        loadReco()
    }

    private fun collapse(moveFocus: Boolean) {
        if (!expanded) return
        expanded = false
        val ms = if (Motion.level == Motion.OFF) 0L else 280L
        page.animate().translationY(0f).setDuration(ms).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        recoDim.animate().alpha(0f).setDuration(ms).start()
        if (moveFocus) (lastBottomFocus?.takeIf { it.isAttachedToWindow && isInside(it, bottom) } ?: bottom.getChildAt(0))?.requestFocus()
        recoBox.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        recoScroll.scrollTo(0, 0)
        recoInfo.text = ""
    }

    /** Reads the apps' rows in the background; the first rows are the favorites' apps. */
    private fun loadReco() {
        if (!cfg.recommendations) {
            recoBox.visibility = View.GONE
            return
        }
        recoBox.visibility = View.VISIBLE
        if (!Reco.allowed(this)) {
            reco.showAsk()
            return
        }
        if (recoLoading) return
        recoLoading = true
        val app = applicationContext
        val order = FavStore.get(this, cfg)
        val hidden = cfg.recoHidden
        Bg.run({ Reco.load(app, order, hidden) }) { rows ->
            recoLoading = false
            if (rows.isNullOrEmpty()) reco.showEmpty()
            else reco.show(rows) { pkg -> Apps.one(this, pkg)?.let { AppColors.of(it) } ?: 0xFFE9E7E2.toInt() }
            if (expanded && currentFocus?.let { isInside(it, recoBox) } != true) reco.firstFocusable?.requestFocus()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 11) loadReco()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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
            background = rounded(C.GLASS, dp(9f).toFloat(), 1, C.BORDER)
            visibility = View.GONE
            // OK plays or pauses; holding OK opens the Music panel with every zone
            isFocusable = true
            isClickable = true
            isLongClickable = true
            contentDescription = "Play / pause. Hold for music zones"
            FocusGlide.shape(this, dp(9f).toFloat())
            setOnFocusChangeListener { _, f -> paintNowPlaying(f) }
            setOnClickListener { togglePlay() }
            setOnLongClickListener { open(MusicActivity::class.java); true }
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
        npCard.background = if (f) FocusGlide.fill(npCard, dp(9f).toFloat()) else rounded(C.GLASS, dp(9f).toFloat(), 1, C.BORDER)
        npLabel.setTextColor(if (f) C.DARK2 else C.TEXT3)
        npTitle.setTextColor(if (f) C.DARK else C.TEXT)
        npArtist.setTextColor(if (f) C.DARK2 else C.TEXT2)
        npProgress.background = rounded(if (f) C.DARK else C.TEXT, dp(1f).toFloat())
        npButton.background = rounded(if (f) 0x26141413 else 0x33FFFFFF, dp(16f).toFloat())
        npButton.setColorFilter(if (f) C.DARK else C.TEXT)
    }

    /** Round icon-only button; its name shows in a small label while it has focus. */
    private fun iconButton(icon: Int, name: String, above: Boolean, onClick: () -> Unit): ImageView {
        val v = ImageView(this)
        v.setImageResource(icon)
        v.setPadding(dp(9f), dp(9f), dp(9f), dp(9f))
        v.isFocusable = true
        v.isClickable = true
        v.contentDescription = name
        val normal = rounded(C.GLASS_SOFT, dp(18f).toFloat(), 1, C.BORDER)
        v.background = normal
        v.setColorFilter(C.TEXT)
        FocusGlide.shape(v, -1f)
        v.setOnFocusChangeListener { _, f ->
            v.background = if (f) FocusGlide.fill(v, dp(18f).toFloat()) else normal
            v.setColorFilter(if (f) C.DARK else C.TEXT)
            if (f) showHint(v, name, above) else hideHint(v)
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
        val lp = LinearLayout.LayoutParams(dp(36f), dp(36f))
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
                open(AllAppsActivity::class.java)
            }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_VERTICAL })
        }

        // top right: Google TV home and launcher settings, icon only
        topButtons.removeAllViews()
        if (Apps.stockHomeIntent(this) != null) {
            val g = iconButton(R.drawable.ic_exit, "Google TV home", false) {
                if (!Apps.openStockHome(this)) toast("Google TV home is not available")
            }
            topButtons.addView(g, LinearLayout.LayoutParams(dp(36f), dp(36f)).apply { leftMargin = dp(7f) })
        }
        if (cfg.haEnabled) {
            val today = iconButton(R.drawable.ic_today, "Today", false) { open(TodayActivity::class.java) }
            topButtons.addView(today, LinearLayout.LayoutParams(dp(36f), dp(36f)).apply { leftMargin = dp(7f) })
        }
        val gear = iconButton(R.drawable.ic_gear, "Launcher settings", false) {
            open(SettingsActivity::class.java)
        }
        gearButton = gear
        markUpdate()
        topButtons.addView(gear, LinearLayout.LayoutParams(dp(36f), dp(36f)).apply { leftMargin = dp(7f) })

        // bottom row: icon buttons on the left, quick actions in the middle, inputs on the right
        bottom.removeAllViews()
        val icons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addIcon(icons, iconButton(R.drawable.ic_grid, "All apps", true) {
            open(AllAppsActivity::class.java)
        })
        addIcon(icons, iconButton(R.drawable.ic_tv_gear, "TV settings", true) {
            if (!Apps.openSystemSettings(this)) toast("Android settings are not available")
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
        }
        bottom.addView(icons)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        if (cfg.haEnabled) {
            cfg.scenes.take(6).forEachIndexed { i, sc ->
                val lp = LinearLayout.LayoutParams(-2, -2)
                if (i > 0) lp.leftMargin = dp(6f)
                actions.addView(pill(sc.title, sceneIcon(sc)) { runScene(sc) }, lp)
            }
        }
        val actionScroll = RowScroller(this)
        actionScroll.clipToPadding = true
        actionScroll.clipChildren = true
        actionScroll.isFocusable = false
        actionScroll.isFillViewport = true      // stretches the row, so its pills sit centred
        actionScroll.addView(actions)
        bottom.addView(actionScroll, LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(16f)
            rightMargin = dp(16f)
        })

        val inputs = Inputs.visible(this, cfg)
        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (Inputs.hasTv(this)) {
            inputRow.addView(pill("TV", R.drawable.ic_tv) { if (!Inputs.openTv(this)) toast("TV is not available") })
        }
        inputs.take(6).forEach { inp ->
            val lp = LinearLayout.LayoutParams(-2, -2)
            if (inputRow.childCount > 0) lp.leftMargin = dp(6f)
            val text = if (inp.device != null) "${inp.name} · ${inp.device}" else inp.name
            inputRow.addView(pill(text, null) { if (!Inputs.open(this, inp.id)) toast("Can't switch to ${inp.name}") }, lp)
        }
        bottom.addView(inputRow)

        // status chips
        chipsRow.removeAllViews()
        chipViews.clear()
        weatherChipView = null
        if (cfg.haEnabled) {
            if (cfg.weatherChip && cfg.weather != null) {
                val w = chip(R.drawable.ic_cloud, "–")
                weatherChipView = w
                chipsRow.addView(w, LinearLayout.LayoutParams(-2, -2))
            }
            cfg.status.take(4).forEach { s ->
                val v = chip(statusIcon(s), if (s.label.isBlank()) "–" else "${s.label} –")
                chipsRow.addView(v, LinearLayout.LayoutParams(-2, -2).apply { if (chipsRow.childCount > 0) leftMargin = dp(7f) })
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
        PopupService.sync(this)
        markUpdate()
        checkUpdates()
        loadReco()
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
        collapse(false)
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
        val w = weatherChipView
        val entity = cfg.weather
        if (w != null && entity != null) {
            Bg.run({ client.state(entity) }) { s ->
                if (s == null || !resumed || weatherChipView !== w) return@run
                val t = s.optJSONObject("attributes")?.optDouble("temperature", Double.NaN) ?: Double.NaN
                w.text = if (t.isNaN()) "–" else Fmt.num(Math.round(t).toDouble(), "°")
                val d = getDrawable(weatherIcon(s.optString("state")))!!.mutate()
                d.setBounds(0, 0, dp(14f), dp(14f))
                d.setTint(C.TEXT)
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
        Bg.run({ Updates.latest() }) { r ->
            Updates.remember(this, r)
            markUpdate()
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
                // nothing playing: no card. The Music panel is then reached from Settings › Home screen.
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

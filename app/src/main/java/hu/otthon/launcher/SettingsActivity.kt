package hu.otthon.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Launcher settings: a floating panel over the live background, so background changes
 * are previewed as they are made. Everything is edited here and saved to config.json.
 */
class SettingsActivity : PanelActivity() {

    private class Page(val title: String, val build: () -> Unit) {
        var focus = 0
    }

    private lateinit var cfg: Config
    private lateinit var backdrop: Backdrop
    private lateinit var previewClock: TextView
    private lateinit var previewDate: TextView
    private lateinit var pane: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var crumb: TextView
    private val navViews = ArrayList<TextView>()
    private var navIndex = 0
    private val stack = ArrayList<Page>()
    private var onPicked: ((String, String) -> Unit)? = null
    private var pendingFocus = 0

    /** The row that is currently open in place, and every such row on the page by its key. */
    private var openKey: String? = null
    private val headers = HashMap<String, RowView>()
    private var indent = 0

    /**
     * True while the page is being rebuilt. Removing the focused row makes Android hand focus
     * to the first focusable view, which is the first menu entry; without this flag that entry
     * would take it as "the user moved here" and switch the page back to Appearance.
     */
    private var rendering = false

    /** Apps that publish recommendations, read once when the Home screen page is first shown. */
    private var publishers: List<Pair<String, String>>? = null

    private val roots = listOf("Appearance", "Favorites", "Home screen", "Cameras", "Inputs", "Remote", "Dashboard", "Home Assistant", "About")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config.load(this)

        val root = FrameLayout(this)
        backdrop = Backdrop(this)
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))

        // what the home screen shows in the top-left corner, as a preview
        val preview = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        previewClock = label("", 62f, C.TEXT, light = true).apply { letterSpacing = -0.02f }
        previewDate = label("", 15f, C.TEXT2).apply { setPadding(dp(2f), dp(6f), 0, 0) }
        preview.addView(previewClock)
        preview.addView(previewDate)
        root.addView(preview, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = dp(48f); topMargin = dp(34f) })

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(C.PANEL, dp(20f).toFloat(), 1, C.BORDER)
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        FocusGlide.install(panel)
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x0DFFFFFF)
            setPadding(dp(12f), dp(20f), dp(12f), dp(16f))
        }
        nav.addView(label("Settings", 18f, C.TEXT, bold = true).apply { setPadding(dp(10f), 0, 0, dp(14f)) })
        roots.forEachIndexed { i, s ->
            val v = label(s, 12f, C.TEXT2).apply {
                id = View.generateViewId()
                isFocusable = true
                isClickable = true
                setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                FocusGlide.shape(this, dp(8f).toFloat())
                setOnFocusChangeListener { _, f ->
                    if (f && i != navIndex && !rendering) showRoot(i, false)
                    paintNav()
                }
                setOnClickListener { showRoot(i, true) }
            }
            navViews.add(v)
            nav.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3f) })
        }
        panel.addView(nav, LinearLayout.LayoutParams(dp(172f), -1))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22f), dp(20f), dp(22f), 0)
        }
        crumb = label("", 17f, C.TEXT, bold = true)
        right.addView(crumb)
        scroll = ColScroller(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, dp(6f), 0, dp(20f))
        }
        pane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(pane)
        right.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(right, LinearLayout.LayoutParams(0, -1, 1f))

        root.addView(panel, FrameLayout.LayoutParams(dp(610f), -1, Gravity.END).apply {
            topMargin = dp(26f)
            bottomMargin = dp(26f)
            rightMargin = dp(30f)
        })
        setContentView(root)
        animateIn(panel)

        stack.add(rootPage(0))
        render(-1)
        navViews[0].requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val key = openKey
        if (key != null) {
            // Back closes the open row first
            openKey = null
            render(-1)
            focusRow(headers[key]?.let { paneRows().indexOf(it) } ?: 0)
        } else if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
            render(stack.last().focus)
        } else {
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val cb = onPicked
        onPicked = null
        if (requestCode == 7 && resultCode == RESULT_OK && data != null && cb != null) {
            val entity = data.getStringExtra("entity") ?: return
            cb(entity, data.getStringExtra("name") ?: "")
        }
    }

    // ---------- navigation ----------

    private fun rootPage(i: Int): Page = when (i) {
        0 -> Page(roots[0]) { backgroundPage() }
        1 -> Page(roots[1]) { favoritesPage() }
        2 -> Page(roots[2]) { homePage() }
        3 -> Page(roots[3]) { camerasPage() }
        4 -> Page(roots[4]) { inputsPage() }
        5 -> Page(roots[5]) { remotePage() }
        6 -> Page(roots[6]) { dashboardPage() }
        7 -> Page(roots[7]) { haPage() }
        else -> Page(roots[8]) { aboutPage() }
    }

    private fun showRoot(i: Int, focusPane: Boolean) {
        navIndex = i
        openKey = null
        stack.clear()
        stack.add(rootPage(i))
        scroll.scrollTo(0, 0)
        render(if (focusPane) 0 else -1)
    }

    private fun open(page: Page) {
        stack.last().focus = focusedRow() ?: 0
        openKey = null
        stack.add(page)
        scroll.scrollTo(0, 0)
        render(0)
    }

    private fun paneRows(): List<View> {
        val out = ArrayList<View>()
        for (k in 0 until pane.childCount) {
            val c = pane.getChildAt(k)
            if (c.isFocusable) out.add(c)
        }
        return out
    }

    private fun focusedRow(): Int? {
        val f = currentFocus ?: return null
        val i = paneRows().indexOf(f)
        return if (i >= 0) i else null
    }

    private fun refresh() = render(focusedRow() ?: pendingFocus)

    /** Rebuilds the current page. [focus] is the row to focus, or -1 to leave focus alone. */
    private fun render(focus: Int) {
        cfg = Config.load(this)
        val now = ZonedDateTime.now()
        previewClock.text = now.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH))
        previewDate.text = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.ENGLISH))
        backdrop.setDim(cfg.dim)
        backdrop.show(cfg.background, Backgrounds.phase(this, cfg), true)

        // Remember whether a row had focus: it is about to be removed, and focus must come back
        // to the pane afterwards even if the caller does not ask for a particular row.
        val hadRowFocus = focusedRow()
        rendering = true
        try {
            pane.removeAllViews()
            headers.clear()
            indent = 0
            val page = stack.last()
            crumb.text = if (stack.size > 1) "‹  " + page.title else page.title
            page.build()
        } finally {
            rendering = false
        }
        paintNav()
        val rows = paneRows()
        val navId = navViews[navIndex].id
        rows.forEach { it.nextFocusLeftId = navId }
        val target = if (focus >= 0) focus else hadRowFocus ?: -1
        if (target >= 0 && rows.isNotEmpty()) rows[target.coerceIn(0, rows.size - 1)].requestFocus()
    }

    private fun focusRow(i: Int) {
        val rows = paneRows()
        if (rows.isNotEmpty()) rows[i.coerceIn(0, rows.size - 1)].requestFocus()
    }

    private fun paintNav() {
        navViews.forEachIndexed { i, v ->
            val f = v.isFocused
            v.background = when {
                f -> FocusGlide.fill(v, dp(8f).toFloat())
                i == navIndex -> rounded(0x1FFFFFFF, dp(8f).toFloat())
                else -> null
            }
            v.setTextColor(if (f) C.DARK else if (i == navIndex) C.TEXT else C.TEXT2)
        }
    }

    // ---------- building blocks ----------

    private fun note(text: String) {
        pane.addView(label(text, 11f, C.TEXT3).apply {
            setLineSpacing(dp(3f).toFloat(), 1f)
            setPadding(dp(2f), dp(6f), dp(2f), dp(2f))
        })
    }

    private fun section(title: String) {
        pane.addView(label(title, 11f, C.TEXT2, bold = true), LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(16f)
            bottomMargin = dp(4f)
            leftMargin = dp(2f)
        })
    }

    private fun add(v: View) {
        pane.addView(v, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4f)
            leftMargin = dp(16f) * indent
        })
    }

    private fun row(title: String, value: String? = null, selected: Boolean? = null, onClick: (() -> Unit)? = null): RowView {
        val r = RowView(this, title, value, selected)
        if (onClick == null) {
            // information only: the cursor skips it, so OK never lands on a row that does nothing
            r.passive = true
            r.isFocusable = false
            r.isClickable = false
        } else {
            r.setOnClickListener { onClick() }
        }
        add(r)
        return r
    }

    /** An action inside an open row. While it cannot be used it stays in place, greyed out, so the others do not jump. */
    private fun action(title: String, enabled: Boolean, onClick: () -> Unit): RowView {
        val r = RowView(this, title, null, null)
        if (enabled) r.setOnClickListener { onClick() } else r.passive = true
        add(r)
        return r
    }

    /** A row with a switch: OK flips it. [onLong] is an optional second action on Hold OK. */
    private fun toggle(title: String, on: Boolean, onLong: (() -> Unit)? = null, onChange: (Boolean) -> Unit): RowView {
        val r = row(title) { onChange(!on) }
        r.switch(on)
        if (onLong != null) r.setOnLongClickListener { onLong(); true }
        return r
    }

    /** A row that opens in place, like a drop-down: [children] adds its choices or actions. */
    private fun expand(key: String, title: String, value: String?, children: () -> Unit) {
        val open = openKey == key
        val r = row(title, value) {
            openKey = if (open) null else key
            render(-1)
            val h = headers[key]?.let { paneRows().indexOf(it) } ?: 0
            focusRow(if (open) h else h + 1)
        }
        r.chevron(open)
        headers[key] = r
        if (open) {
            indent++
            children()
            indent--
        }
    }

    /**
     * Applies a change and rebuilds the page, keeping focus where the user is: on the same
     * action of the open row (which may have moved to [moveTo]), or on its header once closed.
     */
    private fun change(close: Boolean = false, moveTo: String? = null, work: () -> Unit) {
        val f = focusedRow() ?: pendingFocus
        pendingFocus = f
        val before = paneRows()
        val h = openKey?.let { headers[it] }?.let { before.indexOf(it) } ?: -1
        val offset = if (h >= 0) f - h else 0
        if (close) openKey = null else if (moveTo != null) openKey = moveTo
        work()
        render(-1)
        val nh = openKey?.let { headers[it] }?.let { paneRows().indexOf(it) } ?: -1
        focusRow(
            when {
                nh >= 0 -> nh + offset
                h >= 0 -> h
                else -> f
            }
        )
    }

    private fun edit(close: Boolean = false, moveTo: String? = null, block: (JSONObject) -> Unit) =
        change(close, moveTo) { if (!ConfigStore.edit(this, block)) toast("Couldn't save the settings") }

    private fun pick(title: String, domains: String, cb: (String, String) -> Unit) {
        if (!cfg.haEnabled) {
            toast("Set up or switch on Home Assistant first")
            return
        }
        pendingFocus = focusedRow() ?: 0
        onPicked = cb
        open(Intent(this, PickEntityActivity::class.java).putExtra("title", title).putExtra("domains", domains), 7)
    }

    /** An editable list of entities stored in a JSON array. Each one opens in place for its actions. */
    private fun entityList(id: String, arr: (JSONObject) -> JSONArray, items: List<HaItem>, addTitle: String, domains: String, allowRo: Boolean) {
        val last = items.size - 1
        items.forEachIndexed { i, item ->
            expand("$id:$i", item.title, item.entity) {
                row("Rename") {
                    Popup.text(this, "Name", item.label) { v -> edit { r -> arr(r).getJSONObject(i).put("label", v) } }
                }
                if (allowRo) toggle("Can be switched from the TV", !item.ro) { on ->
                    edit { r -> arr(r).getJSONObject(i).put("ro", !on) }
                }
                if (allowRo && item.domain == "device_tracker") {
                    row("Picture", item.picture ?: "Automatic") {
                        pick("Choose a picture", "image,camera") { entity, _ ->
                            edit { r -> arr(r).getJSONObject(i).put("picture", entity) }
                        }
                    }
                    if (item.picture != null) row("Use the automatic picture") {
                        edit { r -> arr(r).getJSONObject(i).remove("picture") }
                    }
                }
                action("Move up", i > 0) { edit(moveTo = "$id:${i - 1}") { r -> ConfigStore.swap(arr(r), i, i - 1) } }
                action("Move down", i < last) { edit(moveTo = "$id:${i + 1}") { r -> ConfigStore.swap(arr(r), i, i + 1) } }
                row("Remove") { edit(close = true) { r -> arr(r).remove(i) } }
            }
        }
        row("+  $addTitle") {
            pick(addTitle, domains) { entity, name ->
                edit { r -> arr(r).put(JSONObject().put("entity", entity).put("label", name)) }
            }
        }
    }

    // ---------- pages ----------

    private fun backgroundPage() {
        note("Each picture set has four pictures. The launcher switches between them at dawn, during the day, at dusk and at night, following the real sunrise and sunset. The picture behind this panel is a live preview.")
        section("Background")
        val options = Backgrounds.options(this)
        expand("set", "Picture set", options.firstOrNull { it.key == cfg.background }?.title ?: cfg.background) {
            for (o in options) {
                row(o.title, o.subtitle, o.key == cfg.background) { edit(close = true) { it.put("background", o.key) } }
            }
        }
        add(SliderRow(this, "Darkening", cfg.dim, 0f, 0.9f, 0.05f, { "${Math.round(it * 100)}%" }) { v ->
            backdrop.setDim(v)
            ConfigStore.edit(this) { it.put("dim", Math.round(v * 100) / 100.0) }
        })
        val phases = listOf(Phase.HAJNAL, Phase.NAPPAL, Phase.ALKONY, Phase.EJSZAKA)
        val previewed = Prefs.phaseOverride(this)
        add(SegmentRow(this, "Preview", listOf("Auto", "Dawn", "Day", "Dusk", "Night"), if (previewed == null) 0 else phases.indexOf(previewed) + 1) { i ->
            Prefs.setPhaseOverride(this, if (i == 0) null else phases[i - 1])
            backdrop.show(cfg.background, Backgrounds.phase(this, cfg), true)
        })
        note("A preview lasts 10 minutes, then the picture follows the sun again.")
        section("Motion")
        val levels = listOf("full", "reduced", "off")
        add(SegmentRow(this, "Animations", listOf("Full", "Reduced", "Off"), levels.indexOf(cfg.animations).coerceAtLeast(0)) { i ->
            ConfigStore.edit(this) { it.put("animations", levels[i]) }
            cfg = Config.load(this)
        })
        note("Reduced keeps only short fades. Off also stops the gliding cursor and the smooth scrolling.")
        section("Little visitors")
        add(SegmentRow(this, "Bears and bunnies", listOf("Off", "A few", "Lots"), cfg.critters) { i ->
            ConfigStore.edit(this) { it.put("critters", i) }
            cfg = Config.load(this)
        })
        note("They walk along the tops of the tiles and buttons on the home screen, sit down now and then, and hop from one to the next.")
        toggle("Christmas", cfg.christmas) { on -> edit { it.put("christmas", on) } }
        note("Reindeer take the place of the bears and bunnies, Santa's sleigh flies across the screen now and then, and the app tiles light up with a string of fairy lights instead of the glow.")
    }

    private fun favoritesPage() {
        note("The scrolling row of apps on the home screen. You can also hold OK on a tile there.")
        section("Order")
        val favs = FavStore.get(this, cfg)
        val last = favs.size - 1
        favs.forEachIndexed { i, pkg ->
            val name = Apps.one(this, pkg)?.label ?: pkg
            fun move(to: Int) = change(moveTo = "fav:$to") {
                favs.removeAt(i)
                favs.add(to, pkg)
                FavStore.set(this, favs)
            }
            expand("fav:$i", "${i + 1}.  $name", null) {
                action("Move up", i > 0) { move(i - 1) }
                action("Move down", i < last) { move(i + 1) }
                row("Remove") {
                    change(close = true) {
                        favs.removeAt(i)
                        FavStore.set(this, favs)
                    }
                }
            }
        }
        section("More")
        row("+  Add apps", "All apps") { open(Intent(this, AllAppsActivity::class.java)) }
        row("Reset to the default favorites") {
            change(close = true) { FavStore.reset(this) }
            toast("Favorites reset")
        }
    }

    /** Hands the Home button to this launcher or back to Google TV home, through Home Assistant's ADB connection. */
    private fun setDefaultHome(ours: Boolean) {
        val client = if (cfg.haEnabled) HaClient(cfg.haUrl!!, cfg.haToken!!) else null
        if (client == null) {
            toast("Set up or switch on Home Assistant first: the TV only lets this be changed over ADB")
            return
        }
        if (!ours && !Apps.stockInstalled(this)) {
            toast("Google TV home is not installed on this TV")
            return
        }
        val want = if (ours) packageName else Apps.STOCK
        if (Apps.defaultHome(this) == want) {
            change(close = true) { }
            return
        }
        toast("Switching…")
        Adb.run(this, cfg, Apps.homeCommand(this, ours)) { r ->
            Bg.main.postDelayed({
                when {
                    r != Adb.Result.OK -> toast(Adb.explain(r))
                    Apps.defaultHome(this) == want -> toast(if (ours) "The Home button now opens this launcher" else "The Home button now opens Google TV home")
                    else -> toast("The TV did not switch")
                }
                change(close = true) { }
            }, 700)
        }
    }

    private fun homePage() {
        section("Home button")
        val current = Apps.defaultHome(this)
        val opens = when (current) {
            packageName -> "This launcher"
            Apps.STOCK -> "Google TV home"
            null, "android" -> "The TV asks"
            else -> Apps.one(this, current)?.label ?: current
        }
        expand("home", "Home button opens", opens) {
            row("This launcher", null, current == packageName) { setDefaultHome(true) }
            row("Google TV home", null, current == Apps.STOCK) { setDefaultHome(false) }
        }
        row("This TV in Home Assistant", cfg.adbEntity ?: "Automatic") {
            pick("Choose this TV's Android TV (ADB) player", "media_player") { entity, _ -> edit { it.put("adb_entity", entity) } }
        }
        note("The TV only allows this change over ADB, so the launcher asks Home Assistant's Android TV (ADB) integration to do it. Choosing this launcher switches the Google TV home app off, because the TV otherwise always prefers it; choosing Google TV home switches it back on. Nothing is uninstalled.")
        section("Recommendations")
        toggle("Show recommendations", cfg.recommendations) { on -> edit { it.put("recommendations", on) } }
        if (!cfg.recommendations) note("Switched off: the streaming apps' rows below the home screen, the mark at its bottom edge and their options here are hidden.")
        if (cfg.recommendations) {
            toggle("Continue watching", cfg.recoWatchNext) { on -> edit { it.put("reco_watch_next", on) } }
            val list = publishers
            when {
                !Reco.allowed(this) -> note("Press Down on the bottom row of the home screen and choose Allow; then the apps can be chosen here.")
                list == null -> {
                    row("Looking for apps…")
                    val app = applicationContext
                    Bg.run({ Reco.publishers(app) }) { found ->
                        publishers = found ?: emptyList()
                        change { }
                    }
                }
                list.isEmpty() -> row("No app has published recommendations yet")
                else -> for ((pkg, name) in list) {
                    toggle(name, pkg !in cfg.recoHidden) { on ->
                        edit { r ->
                            val set = cfg.recoHidden.toMutableSet()
                            if (on) set.remove(pkg) else set.add(pkg)
                            r.put("reco_hidden", JSONArray().apply { set.forEach { put(it) } })
                        }
                    }
                }
            }
            section("Row order")
            row("Change the order", "Hold OK on a recommendation")
            action("Reset the row order", cfg.recoOrder.isNotEmpty()) { edit { it.remove("reco_order") } }
            note("On the home screen, Down on the bottom row slides up to the rows; the mark “Recommendations” at the bottom edge shows when there are any. Hold OK on a recommendation to move its row up or down.")
        }
        section("Weather chip")
        toggle("Show the weather before the status chips", cfg.weatherChip) { on -> edit { it.put("weather_chip", on) } }
        if (cfg.weather == null) note("Choose a weather source under Dashboard › Weather first.")
        section("Today panel  ·  to-do lists")
        entityList("todo", { r ->
            if (!r.has("todo_lists")) r.put("todo_lists", JSONArray().put(JSONObject().put("entity", "todo.shopping_list").put("label", "")))
            ConfigStore.array(r, "todo_lists")
        }, cfg.todoLists, "Add to-do list", "todo", false)
        section("Status chips  ·  top right, up to 4")
        entityList("status", { r -> ConfigStore.array(r, "status") }, cfg.status, "Add status chip", "", false)
        section("Quick actions  ·  bottom row, up to 6")
        entityList(
            "scenes", { r -> ConfigStore.array(r, "scenes") }, cfg.scenes, "Add quick action",
            "scene,script,light,switch,input_boolean,button,input_button,automation,fan", false
        )
        section("Now playing card")
        row("Music zones", "Roon") { open(Intent(this, MusicActivity::class.java)) }
        note("The card only shows while its zone is playing. On the card, OK plays or pauses and Hold OK opens Music zones: every Roon zone with playback buttons, where OK on a zone puts it on the home screen.")
        row("Media player", cfg.nowPlaying ?: "None") {
            pick("Choose media player", "media_player") { entity, name ->
                edit { r -> r.put("now_playing", entity); if (r.optString("now_playing_label").isBlank()) r.put("now_playing_label", name) }
            }
        }
        if (cfg.nowPlaying != null) {
            row("Card label", cfg.nowPlayingLabel) {
                Popup.text(this, "Card label", cfg.nowPlayingLabel) { v -> edit { it.put("now_playing_label", v) } }
            }
            row("Remove the card") { edit { it.remove("now_playing") } }
        }
    }

    private fun camerasPage() {
        note("Every camera listed here is on the camera wall, in this order.")
        section("Cameras")
        entityList("cameras", { r -> ConfigStore.array(r, "cameras") }, cfg.cameras, "Add camera", "camera", false)

        section("Live video")
        toggle("Live video instead of pictures", cfg.liveVideo) { on ->
            edit { r -> ConfigStore.obj(r, "live").put("enabled", on) }
        }
        if (cfg.liveVideo) {
            row("Stream address", cfg.liveUrl) {
                Popup.text(this, "Stream address", cfg.liveUrl, "{host}: Home Assistant's address, {camera}: the camera's name") { v ->
                    edit { r -> ConfigStore.obj(r, "live").put("url", v.ifBlank { Config.DEFAULT_LIVE_URL }) }
                }
            }
            cfg.cameras.firstOrNull()?.let { cam -> row("Example", cfg.stream(cam.entity) ?: "") }
        }
        note("Full screen and the pop-ups play the cameras' video from Frigate's RTSP restream; the camera wall stays pictures. Frigate's add-on only offers it when its port 8554 is open. If a stream does not start within ten seconds, pictures are shown as before.")

        section("Pop-ups")
        toggle("Show cameras over other apps", cfg.popups) { on ->
            edit { r -> ConfigStore.obj(r, "popups").put("enabled", on) }
            PopupService.sync(this)
        }
        note("When one of the sensors below turns on, its camera appears in the bottom right corner for a while, over whatever is on. While it is up, Back closes it, OK opens the camera full screen and any other key just closes it.")
        if (cfg.popups) {
            if (!PopupService.allowed(this)) {
                row("Allow pop-ups over other apps", "needs Home Assistant") {
                    Adb.run(this, cfg, PopupService.allowCommand(this)) { r ->
                        Bg.main.postDelayed({
                            toast(if (r == Adb.Result.OK && PopupService.allowed(this)) "Pop-ups allowed" else Adb.explain(r))
                            change { }
                        }, 600)
                    }
                }
            }
            val lengths = listOf(10, 20, 30, 60)
            add(SegmentRow(this, "Show for", lengths.map { "$it s" }, lengths.indexOf(cfg.popupSeconds).let { if (it < 0) 1 else it }) { i ->
                ConfigStore.edit(this) { r -> ConfigStore.obj(r, "popups").put("seconds", lengths[i]) }
                cfg = Config.load(this)
                PopupService.sync(this)
            })
            val triggers = cfg.popupTriggers
            // the first edit writes the automatic list into the settings file, so it can be changed
            fun list(r: JSONObject): JSONArray {
                val o = ConfigStore.obj(r, "popups")
                if (!o.has("triggers")) {
                    val a = JSONArray()
                    triggers.forEach { a.put(JSONObject().put("entity", it.entity).put("camera", it.camera)) }
                    o.put("triggers", a)
                }
                return o.getJSONArray("triggers")
            }
            section("Pop up when")
            if (triggers.isEmpty()) row("No sensors yet")
            triggers.forEachIndexed { i, t ->
                expand("pop:$i", t.title, t.entity) {
                    row("Camera", t.camera) {
                        pick("Camera to show", "camera") { entity, _ ->
                            edit { r -> list(r).getJSONObject(i).put("camera", entity) }
                            PopupService.sync(this)
                        }
                    }
                    row("Show it now") { PopupService.test(this, t.camera) }
                    row("Remove") {
                        edit(close = true) { r -> list(r).remove(i) }
                        PopupService.sync(this)
                    }
                }
            }
            row("+  Add sensor", "for example a person on a camera") {
                pick("Sensor that opens the pop-up", "binary_sensor") { entity, _ ->
                    edit { r -> list(r).put(JSONObject().put("entity", entity).put("camera", PopupTrigger.cameraFor(entity))) }
                    PopupService.sync(this)
                }
            }
        }
    }

    private fun inputsPage() {
        val all = Inputs.all(this)
        note("The TV's HDMI inputs, at the bottom right of the home screen. OK shows or hides one, Hold OK renames it.")
        section("Show on the home screen")
        if (all.isEmpty()) row("This TV reports no HDMI inputs")
        for (inp in all) {
            val hidden = inp.id in cfg.inputsHidden
            val custom = cfg.inputNames[inp.id]
            toggle((custom ?: inp.name) + (inp.device?.let { "  ·  $it" } ?: ""), !hidden, onLong = {
                Popup.text(this, "Input name", custom ?: inp.name, "Empty: use the TV's name (${inp.name})") { v ->
                    edit { r -> if (v.isBlank()) ConfigStore.obj(r, "input_names").remove(inp.id) else ConfigStore.obj(r, "input_names").put(inp.id, v) }
                }
            }) { show ->
                edit { r ->
                    val set = cfg.inputsHidden.toMutableSet()
                    if (show) set.remove(inp.id) else set.add(inp.id)
                    val a = JSONArray()
                    set.forEach { a.put(it) }
                    r.put("inputs_hidden", a)
                }
            }
        }
    }

    private fun remotePage() {
        note("The red, green, yellow and blue buttons of the remote, each with a Home Assistant action: a scene, a script, a light or switch to turn on or off, a button to press, or a camera to pop up.")
        section("Colour buttons")
        for ((key, title, _) in ColorKeys.KEYS) {
            val item = cfg.colorKeys[key]
            expand("color_$key", title, item?.title ?: "Nothing") {
                row(if (item == null) "Choose an action" else "Choose another action") {
                    pick(title, ColorKeys.DOMAINS) { entity, name ->
                        edit { r -> ConfigStore.obj(r, "color_keys").put(key, JSONObject().put("entity", entity).put("label", name)) }
                    }
                }
                if (item != null) {
                    row("Name", item.title) {
                        Popup.text(this, "Name", item.label, "Shown when the button is pressed") { v ->
                            edit { r -> ConfigStore.obj(r, "color_keys").optJSONObject(key)?.put("label", v) }
                        }
                    }
                    row("Nothing") { edit { r -> ConfigStore.obj(r, "color_keys").remove(key) } }
                }
            }
        }
        section("Where they work")
        val everywhere = cfg.colorKeysEverywhere
        add(SegmentRow(this, "Colour buttons work", listOf("On the home screen", "In every app"), if (everywhere) 1 else 0) { i ->
            setColorKeysEverywhere(i == 1)
        })
        if (everywhere && !KeyService.running) note("Waiting for the TV to start the button service. If this stays, choose “In every app” again.")
        note("In every app, the launcher watches the colour buttons through an accessibility service, switched on over ADB by Home Assistant's Android TV integration. Only buttons that have an action are taken over; the others keep working as before in the apps, for example for teletext or the programme guide.")
    }

    private fun setColorKeysEverywhere(on: Boolean) {
        if (on == cfg.colorKeysEverywhere && on == KeyService.running) return
        if (!cfg.haEnabled) {
            toast("Set up or switch on Home Assistant first: the TV only lets this be switched on over ADB")
            change { }
            return
        }
        toast(if (on) "Switching on…" else "Switching off…")
        Adb.run(this, cfg, if (on) ColorKeys.enableCommand(this) else ColorKeys.disableCommand()) { r ->
            Bg.main.postDelayed({
                when {
                    r != Adb.Result.OK -> toast(Adb.explain(r))
                    on && !KeyService.running -> toast("The TV did not start the button service")
                    on -> toast("The colour buttons now work in every app")
                    else -> toast("The colour buttons now work on the home screen only")
                }
                edit { it.put("color_keys_everywhere", on && (r == Adb.Result.OK)) }
            }, 1500)
        }
    }

    private fun dashSections(r: JSONObject): JSONArray = ConfigStore.array(ConfigStore.obj(r, "dashboard"), "sections")

    private fun dashboardPage() {
        note("The Home Assistant overview that opens from the Dashboard button.")
        section("Weather")
        row("Weather source", cfg.weather ?: "None") {
            pick("Choose weather source", "weather") { entity, _ ->
                edit { r -> ConfigStore.obj(r, "dashboard").put("weather", entity) }
            }
        }
        if (cfg.weather != null) row("Remove the weather card") { edit { r -> ConfigStore.obj(r, "dashboard").remove("weather") } }
        section("Sections")
        cfg.sections.forEachIndexed { i, s ->
            val count = if (s.items.size == 1) "1 item" else "${s.items.size} items"
            row(s.title, if (s.hideWhen != null) "$count  ·  can hide" else count) { open(sectionPage(i)) }
        }
        row("+  Add section") {
            Popup.text(this, "Section name", "") { v ->
                if (v.isNotBlank()) edit { r -> dashSections(r).put(JSONObject().put("title", v).put("items", JSONArray())) }
            }
        }
    }

    private fun sectionPage(i: Int): Page = Page("Dashboard section") {
        val s = cfg.sections.getOrNull(i)
        if (s == null) {
            note("This section no longer exists.")
        } else {
            section("Section")
            row("Name", s.title) {
                Popup.text(this, "Section name", s.title) { v -> if (v.isNotBlank()) edit { r -> dashSections(r).getJSONObject(i).put("title", v) } }
            }
            row("Hide while this is on", s.hideWhen ?: "Never hidden") {
                pick("Hide while this is on", "input_boolean,binary_sensor,switch") { entity, _ ->
                    edit { r -> dashSections(r).getJSONObject(i).put("hide_when", entity) }
                }
            }
            if (s.hideWhen != null) row("Never hide") { edit { r -> dashSections(r).getJSONObject(i).remove("hide_when") } }
            section("Items")
            entityList("sec", { r -> ConfigStore.array(dashSections(r).getJSONObject(i), "items") }, s.items, "Add item", "", true)
            section("Order")
            row("Move section up", onClick = if (i > 0) ({
                stack[stack.size - 1] = sectionPage(i - 1)
                edit(close = true) { r -> ConfigStore.swap(dashSections(r), i, i - 1) }
            }) else null)
            row("Move section down", onClick = if (i < cfg.sections.size - 1) ({
                stack[stack.size - 1] = sectionPage(i + 1)
                edit(close = true) { r -> ConfigStore.swap(dashSections(r), i, i + 1) }
            }) else null)
            expand("delete", "Delete section", null) {
                row("Yes, delete “${s.title}”") {
                    openKey = null
                    ConfigStore.edit(this) { r -> dashSections(r).remove(i) }
                    onBackPressed()
                }
            }
        }
    }

    private fun haPage() {
        toggle("Use Home Assistant", cfg.haOn) { on ->
            edit { it.put("ha_on", on) }
            PopupService.sync(this)
        }
        if (!cfg.haOn) {
            note("Switched off: the launcher works on its own, without the dashboard, cameras, Today panel, quick actions, status and weather chips, the Now playing card, camera pop-ups and the colour buttons. Everything set up for them is kept, and comes back when this is switched on again. Changing the Home button and installing updates from Settings also need Home Assistant; on a TV without it, use update.bat and home-button.bat on the PC.")
            return
        }
        section("Connection")
        row("Server address", cfg.haUrl ?: "Not set") {
            Popup.text(this, "Server address", cfg.haUrl ?: "http://", "http://192.168.1.10:8123") { v -> edit { it.put("ha_url", v) } }
        }
        val token = cfg.haToken
        row("Access token", if (token == null) "Not set" else "Saved  ·  ends in " + token.takeLast(4)) {
            Popup.text(this, "Long-lived access token", "", "Paste or type the token") { v ->
                if (v.isNotBlank()) edit { it.put("ha_token", v) }
            }
        }
        val status = row("Status", if (cfg.haEnabled) "Checking…" else "Not set up")
        val client = if (cfg.haEnabled) HaClient(cfg.haUrl!!, cfg.haToken!!) else null
        if (client != null) {
            Bg.run({ client.ping() }) { v ->
                status.valueView.text = if (v != null) "Connected  ·  Home Assistant $v" else "Not reachable"
            }
        }
        section("Sunrise and sunset")
        row("Location", String.format(Locale.US, "%.2f, %.2f", cfg.lat, cfg.lon))
        if (client != null) row("Use Home Assistant's location") {
            Bg.run({ client.config() }) { c ->
                val lat = c?.optDouble("latitude", Double.NaN) ?: Double.NaN
                val lon = c?.optDouble("longitude", Double.NaN) ?: Double.NaN
                if (lat.isNaN() || lon.isNaN()) toast("Home Assistant did not return a location")
                else edit { r -> r.put("lat", Math.round(lat * 100) / 100.0); r.put("lon", Math.round(lon * 100) / 100.0) }
            }
        }
        note("Tokens are created in Home Assistant under your profile › Security › Long-lived access tokens.")
    }

    private fun aboutPage() {
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        note("A clean home screen for this TV. No ads, no recommendations, no tracking.")
        section("Shortcuts")
        if (Apps.stockHomeIntent(this) != null) row("Open Google TV home", "stock launcher") { Apps.openStockHome(this) }
        row("Open Android TV settings") {
            if (!Apps.openSystemSettings(this)) toast("Android settings are not available")
        }
        section("Updates")
        updateRow()
        section("About")
        row("Version", version)
        row("Source", "github.com/bhevesi2/bhevesiatvlauncher")
        row("Photos", "Unsplash")
        row("Settings file", "Android/data/$packageName/files/config.json")
    }

    private var release: Updates.Release? = null
    private var updating = false

    /** Checks GitHub for a newer build; OK on a found one downloads it and has Home Assistant install it. */
    private fun updateRow() {
        val r = release
        val newer = r != null && Updates.isNewer(this, r)
        val status = when {
            updating -> "Installing…"
            r == null -> "OK: check now"
            newer -> "${r.name} is ready  ·  OK: install"
            else -> "Up to date  ·  ${r.name}"
        }
        row(if (newer) "New version available" else "Check for updates", status) {
            when {
                updating -> {}
                newer -> install(r!!)
                else -> {
                    toast("Checking…")
                    Bg.run({ Updates.latest() }) { found ->
                        Updates.remember(this, found)
                        if (found == null) toast("Couldn't reach GitHub")
                        release = found
                        change { }
                    }
                }
            }
        }
        if (newer) note("The launcher closes for a few seconds while it updates, then opens again.")
    }

    private fun install(r: Updates.Release) {
        if (!cfg.haEnabled) {
            toast("Set up or switch on Home Assistant first, or run update.bat on the PC")
            return
        }
        updating = true
        change { }
        toast("Downloading ${r.name}…")
        Bg.run({ Updates.download(this, r) }) { apk ->
            if (apk == null) {
                updating = false
                toast("The download did not work")
                change { }
                return@run
            }
            toast("Installing…")
            Adb.run(this, cfg, Updates.installCommand(this, apk), 90000) { res ->
                // if this runs at all, the install did not replace the running launcher
                updating = false
                if (res != Adb.Result.OK) toast(Adb.explain(res))
                change { }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

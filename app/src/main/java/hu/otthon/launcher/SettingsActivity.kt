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

    private val roots = listOf("Appearance", "Favorites", "Home screen", "Cameras", "Inputs", "Dashboard", "Home Assistant", "About")

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
        5 -> Page(roots[5]) { dashboardPage() }
        6 -> Page(roots[6]) { haPage() }
        else -> Page(roots[7]) { aboutPage() }
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
        if (onClick == null) r.passive = true else r.setOnClickListener { onClick() }
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
            toast("Set up Home Assistant first")
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
                row("Move up", onClick = if (i > 0) ({ edit(moveTo = "$id:${i - 1}") { r -> ConfigStore.swap(arr(r), i, i - 1) } }) else null)
                row("Move down", onClick = if (i < last) ({ edit(moveTo = "$id:${i + 1}") { r -> ConfigStore.swap(arr(r), i, i + 1) } }) else null)
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
                row("Move up", onClick = if (i > 0) ({ move(i - 1) }) else null)
                row("Move down", onClick = if (i < last) ({ move(i + 1) }) else null)
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

    private fun homePage() {
        section("Status chips  ·  top right, up to 4")
        entityList("status", { r -> ConfigStore.array(r, "status") }, cfg.status, "Add status chip", "", false)
        section("Quick actions  ·  bottom row, up to 6")
        entityList(
            "scenes", { r -> ConfigStore.array(r, "scenes") }, cfg.scenes, "Add quick action",
            "scene,script,light,switch,input_boolean,button,input_button,automation,fan", false
        )
        section("Now playing card")
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
    }

    private fun inputsPage() {
        val all = Inputs.all(this)
        note("The TV's HDMI inputs, at the bottom right of the home screen. OK shows or hides one, Hold OK renames it.")
        section("Show on the home screen")
        if (all.isEmpty()) row("This TV reports no HDMI inputs")
        for (inp in all) {
            val hidden = inp.id in cfg.inputsHidden
            val custom = cfg.inputNames[inp.id]
            toggle(custom ?: inp.name, !hidden, onLong = {
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
        section("About")
        row("Version", version)
        row("Source", "github.com/bhevesi2/bhevesiatvlauncher")
        row("Photos", "Unsplash")
        row("Settings file", "Android/data/$packageName/files/config.json")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

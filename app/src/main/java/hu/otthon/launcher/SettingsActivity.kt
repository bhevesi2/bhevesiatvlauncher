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
class SettingsActivity : Activity() {

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

    private val roots = listOf("Background", "Favorites", "Home screen", "Cameras", "Inputs", "Dashboard", "Home Assistant", "About")

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
                setOnFocusChangeListener { _, f ->
                    if (f && i != navIndex) showRoot(i, false)
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
        scroll = ScrollView(this).apply {
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

        stack.add(rootPage(0))
        render(-1)
        navViews[0].requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (stack.size > 1) {
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
        stack.clear()
        stack.add(rootPage(i))
        scroll.scrollTo(0, 0)
        render(if (focusPane) 0 else -1)
    }

    private fun open(page: Page) {
        stack.last().focus = focusedRow() ?: 0
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

        pane.removeAllViews()
        val page = stack.last()
        crumb.text = if (stack.size > 1) "‹  " + page.title else page.title
        page.build()
        paintNav()
        val rows = paneRows()
        val navId = navViews[navIndex].id
        rows.forEach { it.nextFocusLeftId = navId }
        if (focus >= 0 && rows.isNotEmpty()) rows[focus.coerceIn(0, rows.size - 1)].requestFocus()
    }

    private fun paintNav() {
        navViews.forEachIndexed { i, v ->
            val f = v.isFocused
            v.background = when {
                f -> rounded(C.FOCUS_FILL, dp(8f).toFloat())
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

    private fun row(title: String, value: String? = null, selected: Boolean? = null, onClick: (() -> Unit)? = null): RowView {
        val r = RowView(this, title, value, selected)
        if (onClick == null) r.passive = true else r.setOnClickListener { onClick() }
        pane.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })
        return r
    }

    private fun edit(block: (JSONObject) -> Unit) {
        pendingFocus = focusedRow() ?: pendingFocus
        if (!ConfigStore.edit(this, block)) toast("Couldn't save the settings")
        refresh()
    }

    private fun pick(title: String, domains: String, cb: (String, String) -> Unit) {
        if (!cfg.haEnabled) {
            toast("Set up Home Assistant first")
            return
        }
        pendingFocus = focusedRow() ?: 0
        onPicked = cb
        startActivityForResult(
            Intent(this, PickEntityActivity::class.java).putExtra("title", title).putExtra("domains", domains), 7
        )
    }

    /** An editable list of entities stored in a JSON array. */
    private fun entityList(arr: (JSONObject) -> JSONArray, items: List<HaItem>, addTitle: String, domains: String, allowRo: Boolean) {
        items.forEachIndexed { i, item ->
            row(item.title, item.entity + if (item.ro) "  ·  read-only" else "") { itemMenu(arr, i, item, allowRo) }
        }
        row("+  $addTitle") {
            pick(addTitle, domains) { entity, name ->
                edit { r -> arr(r).put(JSONObject().put("entity", entity).put("label", name)) }
            }
        }
    }

    private fun itemMenu(arr: (JSONObject) -> JSONArray, i: Int, item: HaItem, allowRo: Boolean) {
        val opts = arrayListOf("Rename", "Move up", "Move down", "Remove")
        if (allowRo) opts.add(if (item.ro) "Allow control from the TV" else "Make read-only")
        Popup.menu(this, item.title, opts) { which ->
            when (which) {
                0 -> Popup.text(this, "Name", item.label) { v -> edit { r -> arr(r).getJSONObject(i).put("label", v) } }
                1 -> edit { r -> ConfigStore.swap(arr(r), i, i - 1) }
                2 -> edit { r -> ConfigStore.swap(arr(r), i, i + 1) }
                3 -> edit { r -> arr(r).remove(i) }
                else -> edit { r -> arr(r).getJSONObject(i).put("ro", !item.ro) }
            }
        }
    }

    // ---------- pages ----------

    private fun backgroundPage() {
        note("Each set has four pictures. The launcher switches between them at dawn, during the day, at dusk and at night, following the real sunrise and sunset. The picture behind this panel is a live preview.")
        section("Set")
        for (o in Backgrounds.options(this)) {
            row(o.title, o.subtitle, o.key == cfg.background) { edit { it.put("background", o.key) } }
        }
        section("Darkening")
        row("Behind the clock and tiles", "${Math.round(cfg.dim * 100)}%") {
            var d = Math.round(cfg.dim * 10) / 10f + 0.1f
            if (d > 0.91f) d = 0.3f
            edit { it.put("dim", (Math.round(d * 10) / 10.0)) }
        }
        section("Preview a time of day")
        val ov = Prefs.phaseOverride(this)
        row("Automatic", "sunrise and sunset", ov == null) { Prefs.setPhaseOverride(this, null); refresh() }
        val names = listOf(Phase.HAJNAL to "Dawn", Phase.NAPPAL to "Day", Phase.ALKONY to "Dusk", Phase.EJSZAKA to "Night")
        for ((p, n) in names) {
            row(n, "for 10 minutes", ov == p) { Prefs.setPhaseOverride(this, p); refresh() }
        }
    }

    private fun favoritesPage() {
        note("The scrolling row of apps on the home screen. You can also hold OK on a tile there.")
        section("Order")
        val favs = FavStore.get(this, cfg)
        favs.forEachIndexed { i, pkg ->
            val name = Apps.one(this, pkg)?.label ?: pkg
            row("${i + 1}.  $name") {
                Popup.menu(this, name, listOf("Move up", "Move down", "Remove")) { which ->
                    when (which) {
                        0 -> if (i > 0) { favs.removeAt(i); favs.add(i - 1, pkg) }
                        1 -> if (i < favs.size - 1) { favs.removeAt(i); favs.add(i + 1, pkg) }
                        else -> favs.removeAt(i)
                    }
                    FavStore.set(this, favs)
                    render(when (which) { 0 -> i - 1; 1 -> i + 1; else -> i })
                }
            }
        }
        section("More")
        row("+  Add apps", "All apps") { startActivity(Intent(this, AllAppsActivity::class.java)) }
        row("Reset to the default favorites") {
            FavStore.reset(this)
            toast("Favorites reset")
            refresh()
        }
    }

    private fun homePage() {
        section("Status chips  ·  top right, up to 4")
        entityList({ r -> ConfigStore.array(r, "status") }, cfg.status, "Add status chip", "", false)
        section("Quick actions  ·  Home row, up to 5")
        entityList(
            { r -> ConfigStore.array(r, "scenes") }, cfg.scenes, "Add quick action",
            "scene,script,light,switch,input_boolean,button,input_button,automation,fan", false
        )
        section("Now playing card")
        row("Media player", cfg.nowPlaying ?: "Off") {
            Popup.menu(this, "Now playing card", listOf("Choose media player", "Rename the card", "Turn off")) { which ->
                when (which) {
                    0 -> pick("Choose media player", "media_player") { entity, name ->
                        edit { r -> r.put("now_playing", entity); if (r.optString("now_playing_label").isBlank()) r.put("now_playing_label", name) }
                    }
                    1 -> Popup.text(this, "Card label", cfg.nowPlayingLabel) { v -> edit { it.put("now_playing_label", v) } }
                    else -> edit { it.remove("now_playing") }
                }
            }
        }
        if (cfg.nowPlaying != null) row("Card label", cfg.nowPlayingLabel)
    }

    private fun camerasPage() {
        note("The first four cameras appear on the home screen. All of them are on the camera wall.")
        section("Cameras")
        entityList({ r -> ConfigStore.array(r, "cameras") }, cfg.cameras, "Add camera", "camera", false)
    }

    private fun inputsPage() {
        val all = Inputs.all(this)
        note("The TV's HDMI inputs, shown in the Inputs row on the home screen.")
        section("Inputs")
        if (all.isEmpty()) row("This TV reports no HDMI inputs")
        for (inp in all) {
            val hidden = inp.id in cfg.inputsHidden
            val custom = cfg.inputNames[inp.id]
            row(custom ?: inp.name, if (hidden) "Hidden" else "Shown") {
                val opts = arrayListOf(if (hidden) "Show on the home screen" else "Hide from the home screen", "Rename")
                if (custom != null) opts.add("Use the TV's name (${inp.name})")
                Popup.menu(this, custom ?: inp.name, opts) { which ->
                    when (which) {
                        0 -> edit { r ->
                            val set = cfg.inputsHidden.toMutableSet()
                            if (hidden) set.remove(inp.id) else set.add(inp.id)
                            val a = JSONArray()
                            set.forEach { a.put(it) }
                            r.put("inputs_hidden", a)
                        }
                        1 -> Popup.text(this, "Input name", custom ?: inp.name) { v ->
                            edit { r -> if (v.isBlank()) ConfigStore.obj(r, "input_names").remove(inp.id) else ConfigStore.obj(r, "input_names").put(inp.id, v) }
                        }
                        else -> edit { r -> ConfigStore.obj(r, "input_names").remove(inp.id) }
                    }
                }
            }
        }
    }

    private fun dashSections(r: JSONObject): JSONArray = ConfigStore.array(ConfigStore.obj(r, "dashboard"), "sections")

    private fun dashboardPage() {
        note("The Home Assistant overview that opens from the Dashboard button.")
        section("Weather")
        row("Weather source", cfg.weather ?: "Off") {
            Popup.menu(this, "Weather", listOf("Choose weather source", "Turn off")) { which ->
                if (which == 0) pick("Choose weather source", "weather") { entity, _ ->
                    edit { r -> ConfigStore.obj(r, "dashboard").put("weather", entity) }
                } else edit { r -> ConfigStore.obj(r, "dashboard").remove("weather") }
            }
        }
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
                Popup.menu(this, "Hide the section", listOf("Choose a switch or sensor", "Never hide")) { which ->
                    if (which == 0) pick("Hide while this is on", "input_boolean,binary_sensor,switch") { entity, _ ->
                        edit { r -> dashSections(r).getJSONObject(i).put("hide_when", entity) }
                    } else edit { r -> dashSections(r).getJSONObject(i).remove("hide_when") }
                }
            }
            section("Items")
            entityList({ r -> ConfigStore.array(dashSections(r).getJSONObject(i), "items") }, s.items, "Add item", "", true)
            section("Order")
            if (i > 0) row("Move section up") {
                stack[stack.size - 1] = sectionPage(i - 1)
                edit { r -> ConfigStore.swap(dashSections(r), i, i - 1) }
            }
            if (i < cfg.sections.size - 1) row("Move section down") {
                stack[stack.size - 1] = sectionPage(i + 1)
                edit { r -> ConfigStore.swap(dashSections(r), i, i + 1) }
            }
            row("Delete section") {
                Popup.menu(this, "Delete “${s.title}”?", listOf("Delete", "Cancel")) { which ->
                    if (which == 0) {
                        ConfigStore.edit(this) { r -> dashSections(r).remove(i) }
                        onBackPressed()
                    }
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

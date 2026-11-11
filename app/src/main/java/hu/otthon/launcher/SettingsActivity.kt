package hu.otthon.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var nav: LinearLayout
    private lateinit var pane: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var cfg: Config
    private var current = -1
    private val sections = listOf("Background", "Favorites", "Home Assistant", "About")
    private val navViews = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0C0D10.toInt())
        }
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121317.toInt())
            setPadding(dp(36f), dp(32f), dp(16f), dp(32f))
        }
        nav.addView(label("Settings", 20f, C.TEXT, bold = true), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(18f) })
        sections.forEachIndexed { i, s ->
            val v = label(s, 12f, C.TEXT2).apply {
                isFocusable = true
                isClickable = true
                setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                setOnFocusChangeListener { _, f -> if (f) showPane(i) ; paintNav() }
                setOnClickListener { showPane(i); firstPaneRow()?.requestFocus() }
            }
            navViews.add(v)
            nav.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2).apply { topMargin = dp(3f) })
        }
        root.addView(nav, LinearLayout.LayoutParams(dp(220f), ViewGroup.LayoutParams.MATCH_PARENT))

        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(40f), dp(32f), dp(48f), dp(32f))
        }
        pane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(pane)
        root.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        setContentView(root)

        showPane(0)
        navViews[0].requestFocus()
    }

    private fun paintNav() {
        navViews.forEachIndexed { i, v ->
            val f = v.isFocused
            v.background = when {
                f -> rounded(C.FOCUS_FILL, dp(8f).toFloat())
                i == current -> rounded(0x1FFFFFFF, dp(8f).toFloat())
                else -> null
            }
            v.setTextColor(if (f) C.DARK else C.TEXT2)
        }
    }

    private fun firstPaneRow(): View? {
        for (i in 0 until pane.childCount) {
            val c = pane.getChildAt(i)
            if (c.isFocusable) return c
        }
        return null
    }

    private fun showPane(i: Int, focusIndex: Int? = null) {
        if (i == current && focusIndex == null) return
        current = i
        cfg = Config.load(this)
        pane.removeAllViews()
        when (i) {
            0 -> backgroundPane()
            1 -> favoritesPane()
            2 -> haPane()
            3 -> aboutPane()
        }
        paintNav()
        scroll.scrollTo(0, 0)
        if (focusIndex != null) {
            var n = 0
            for (k in 0 until pane.childCount) {
                val c = pane.getChildAt(k)
                if (c.isFocusable) {
                    if (n == focusIndex) { c.requestFocus(); break }
                    n++
                }
            }
        }
    }

    private fun focusedRowIndex(): Int? {
        val f = currentFocus ?: return null
        var n = 0
        for (k in 0 until pane.childCount) {
            val c = pane.getChildAt(k)
            if (c.isFocusable) {
                if (c === f) return n
                n++
            }
        }
        return null
    }

    private fun refresh() = showPane(current, focusedRowIndex() ?: 0)

    // ---------- building blocks ----------

    private fun header(title: String, text: String?) {
        pane.addView(label(title, 18f, C.TEXT, bold = true))
        if (text != null) {
            pane.addView(label(text, 11f, C.TEXT3).apply { setPadding(0, dp(6f), 0, 0) })
        }
    }

    private fun section(title: String) {
        pane.addView(label(title, 11f, C.TEXT2, bold = true), LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(20f); bottomMargin = dp(6f)
        })
    }

    private fun row(title: String, value: String? = null, selected: Boolean? = null, onClick: (() -> Unit)? = null) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(12f), dp(9f), dp(14f), dp(9f))
        }
        val radio = if (selected != null) label(if (selected) "●" else "○", 12f, C.TEXT2).apply { setPadding(0, 0, dp(10f), 0) } else null
        val t = label(title, 12f, C.TEXT)
        val v = value?.let { label(it, 11f, C.TEXT3) }
        radio?.let { r.addView(it) }
        r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        v?.let { r.addView(it) }
        fun paint(f: Boolean) {
            r.background = if (f) rounded(C.FOCUS_FILL, dp(8f).toFloat()) else rounded(0xFF16171C.toInt(), dp(8f).toFloat())
            t.setTextColor(if (f) C.DARK else C.TEXT)
            v?.setTextColor(if (f) 0xFF444441.toInt() else C.TEXT3)
            radio?.setTextColor(if (f) C.DARK else C.TEXT2)
        }
        paint(false)
        r.setOnFocusChangeListener { _, f -> paint(f) }
        r.setOnClickListener { onClick?.invoke() }
        pane.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2).apply { topMargin = dp(4f) })
    }

    // ---------- panes ----------

    private fun backgroundPane() {
        header("Background", "Each set has four images. The launcher switches between them at dawn, day, dusk and night, following the real sunrise and sunset.")
        section("Set")
        for (o in Backgrounds.options(this)) {
            row(o.title, o.subtitle, o.key == cfg.background) {
                Prefs.setBackground(this, o.key)
                refresh()
            }
        }
        section("Darkening")
        row("Darkening behind the clock and tiles", "${Math.round(cfg.dim * 100)}%") {
            var d = Math.round(cfg.dim * 10) / 10f + 0.1f
            if (d > 0.91f) d = 0.4f
            Prefs.setDim(this, d)
            refresh()
        }
        section("Preview time of day")
        val ov = Prefs.phaseOverride(this)
        row("Automatic (sunrise / sunset)", null, ov == null) { Prefs.setPhaseOverride(this, null); refresh() }
        val names = mapOf(Phase.HAJNAL to "Dawn", Phase.NAPPAL to "Day", Phase.ALKONY to "Dusk", Phase.EJSZAKA to "Night")
        for ((p, n) in names) {
            row(n, "for 10 minutes", ov == p) {
                Prefs.setPhaseOverride(this, p)
                toast("$n preview on — press Back to see it")
                refresh()
            }
        }
    }

    private fun favoritesPane() {
        header("Favorites", "The row on the home screen. You can also long-press OK on a tile.")
        section("Order")
        val favs = FavStore.get(this, cfg)
        favs.forEachIndexed { i, pkg ->
            val name = Apps.one(this, pkg)?.label ?: pkg
            row("${i + 1}.  $name", null) { favMenu(favs, i, name) }
        }
        section("More")
        row("Add apps…", "All apps list") { startActivity(Intent(this, AllAppsActivity::class.java)) }
        row("Reset to default favorites", null) {
            FavStore.reset(this)
            toast("Favorites reset")
            refresh()
        }
    }

    private fun favMenu(favs: MutableList<String>, i: Int, name: String) {
        val items = arrayOf("Move up", "Move down", "Remove")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(name)
            .setItems(items) { _, which ->
                val pkg = favs[i]
                when (which) {
                    0 -> if (i > 0) { favs.removeAt(i); favs.add(i - 1, pkg) }
                    1 -> if (i < favs.size - 1) { favs.removeAt(i); favs.add(i + 1, pkg) }
                    2 -> favs.removeAt(i)
                }
                FavStore.set(this, favs)
                val target = when (which) { 0 -> (i - 1).coerceAtLeast(0); 1 -> (i + 1).coerceAtMost(favs.size - 1); else -> i.coerceAtMost(favs.size - 1) }
                showPane(current, target.coerceAtLeast(0))
            }
            .show()
    }

    private fun haPane() {
        header("Home Assistant", "Connection and entities come from config.json on the TV.")
        section("Connection")
        if (!cfg.haEnabled) {
            row("Not configured", "add ha_url and ha_token")
        } else {
            row("Server", cfg.haUrl)
            row("Status", "checking…")
            val statusRow = pane.getChildAt(pane.childCount - 1) as LinearLayout
            val valueView = statusRow.getChildAt(statusRow.childCount - 1) as TextView
            val client = HaClient(cfg.haUrl!!, cfg.haToken!!)
            Bg.run({ client.ping() }) { v ->
                valueView.text = if (v != null) "connected · HA $v" else "not reachable"
            }
        }
        section("Status chips")
        if (cfg.status.isEmpty()) row("None", null)
        cfg.status.forEach { row(it.label.ifBlank { it.entity }, it.entity) }
        section("Now playing")
        row(cfg.nowPlayingLabel, cfg.nowPlaying ?: "not set")
        section("Scenes")
        if (cfg.scenes.isEmpty()) row("None", null)
        cfg.scenes.forEach { row(it.label.ifBlank { it.entity }, it.entity) }
        section("Config file")
        row(Config.file(this).absolutePath, null)
    }

    private fun aboutPane() {
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        header("About", "A clean home screen for this TV. No ads, no recommendations, no tracking.")
        section("Shortcuts")
        if (Apps.stockHomeIntent(this) != null) {
            row("Open Google TV home", "stock launcher") { Apps.openStockHome(this) }
        }
        row("Open Android settings", null) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: Exception) { toast("Android settings are not available") }
        }
        section("Version")
        row("Version", version)
        row("Source", "github.com/bhevesi2/bhevesiatvlauncher")
        row("Photos", "Unsplash (Unsplash License)")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

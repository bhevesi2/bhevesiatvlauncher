package hu.otthon.launcher

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** Every installed app, in the same floating panel as the settings. */
class AllAppsActivity : Activity() {

    private lateinit var frame: PanelFrame
    private lateinit var list: LinearLayout
    private lateinit var cfg: Config
    private val columns = 4

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config.load(this)
        frame = PanelFrame(this, "All apps", "Hold OK: add to or remove from favorites")

        // The side padding lives inside the scroller, so a zoomed tile at the edge is never cut.
        val scroll = ColScroller(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            setPadding(dp(22f), dp(16f), dp(22f), dp(22f))
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        scroll.addView(list)
        frame.panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(frame.root)

        Bg.run({ Apps.all(this) }) { apps -> if (apps != null) build(apps) }
    }

    override fun onResume() {
        super.onResume()
        cfg = Config.load(this)
        frame.update(cfg)
    }

    private fun build(apps: List<AppEntry>) {
        list.removeAllViews()
        var row: LinearLayout? = null
        apps.forEachIndexed { i, app ->
            if (i % columns == 0) {
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
                row = r
                val lp = LinearLayout.LayoutParams(-1, -2)
                if (i > 0) lp.topMargin = dp(14f)
                list.addView(r, lp)
            }
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
            val tile = AppTile(this, app)
            tile.setOnClickListener { if (!Apps.launch(this, app.pkg)) toast("Can't open ${app.label}") }
            tile.setOnLongClickListener { menu(app); true }
            cell.addView(tile, LinearLayout.LayoutParams(-1, dp(74f)))
            cell.addView(label(app.label, 10f, C.TEXT2).apply {
                maxLines = 1
                setPadding(dp(2f), dp(6f), 0, 0)
            })
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            if (i % columns > 0) lp.leftMargin = dp(12f)
            row!!.addView(cell, lp)
        }
        val rem = apps.size % columns
        if (rem > 0) {
            for (k in rem until columns) {
                row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(12f) })
            }
        }
        val firstRow = list.getChildAt(0) as? LinearLayout
        val firstCell = firstRow?.getChildAt(0) as? LinearLayout
        firstCell?.getChildAt(0)?.requestFocus()
    }

    private fun menu(app: AppEntry) {
        val favs = FavStore.get(this, cfg)
        val isFav = favs.contains(app.pkg)
        val items = listOf(if (isFav) "Remove from favorites" else "Add to favorites", "Open")
        Popup.menu(this, app.label, items) { which ->
            if (which == 0) {
                if (isFav) favs.remove(app.pkg) else favs.add(app.pkg)
                FavStore.set(this, favs)
                toast(if (isFav) "Removed from favorites" else "Added to favorites")
            } else {
                Apps.launch(this, app.pkg)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            (currentFocus as? AppTile)?.let { menu(it.app); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

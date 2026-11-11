package hu.otthon.launcher

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

class AllAppsActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var cfg: Config
    private val columns = 6

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0C0D10.toInt())
            setPadding(dp(48f), dp(30f), dp(48f), 0)
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        header.addView(label("All apps", 22f, C.TEXT, bold = true))
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(label("Long-press OK to add to favorites", 11f, C.TEXT3))
        root.addView(header)

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(18f), 0, dp(30f))
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        Bg.run({ Apps.all(this) }) { apps -> if (apps != null) build(apps) }
    }

    private fun build(apps: List<AppEntry>) {
        list.removeAllViews()
        var row: LinearLayout? = null
        apps.forEachIndexed { i, app ->
            if (i % columns == 0) {
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
                row = r
                list.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2).apply {
                    if (i > 0) topMargin = dp(14f)
                })
            }
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
            val tile = AppTile(this, app)
            tile.setOnClickListener { if (!Apps.launch(this, app.pkg)) toast("Can't open ${app.label}") }
            tile.setOnLongClickListener { menu(app); true }
            cell.addView(tile, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76f)))
            cell.addView(label(app.label, 10f, C.TEXT2).apply {
                maxLines = 1
                setPadding(dp(2f), dp(6f), 0, 0)
            })
            row!!.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i % columns > 0) leftMargin = dp(12f) })
        }
        val rem = apps.size % columns
        if (rem > 0) {
            for (k in rem until columns) {
                row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(12f) })
            }
        }
        (list.getChildAt(0) as? LinearLayout)?.getChildAt(0)?.let { (it as? LinearLayout)?.getChildAt(0)?.requestFocus() }
    }

    private fun menu(app: AppEntry) {
        val favs = FavStore.get(this, cfg)
        val isFav = favs.contains(app.pkg)
        val items = arrayOf(if (isFav) "Remove from favorites" else "Add to favorites", "Open")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.label)
            .setItems(items) { _, which ->
                if (which == 0) {
                    if (isFav) favs.remove(app.pkg) else favs.add(app.pkg)
                    FavStore.set(this, favs)
                    toast(if (isFav) "Removed from favorites" else "Added to favorites")
                } else {
                    Apps.launch(this, app.pkg)
                }
            }
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            (currentFocus as? AppTile)?.let { menu(it.app); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

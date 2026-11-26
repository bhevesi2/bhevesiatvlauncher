package hu.otthon.launcher

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Every installed app, in the same floating panel as the settings. The folders come first; an app
 * in a folder is shown only inside it. OK on a folder opens it in the same panel (Back returns to
 * all the apps). Hold OK on an app: favourites, folders; on a folder: favourites, rename, delete.
 */
class AllAppsActivity : PanelActivity() {

    private lateinit var frame: PanelFrame
    private lateinit var list: LinearLayout
    private lateinit var scroll: ColScroller
    private lateinit var cfg: Config
    private val columns = 4
    private var apps: List<AppEntry> = emptyList()
    /** The folder shown, or null for all the apps. */
    private var openFolder: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config.load(this)
        frame = PanelFrame(this, "All apps", "Hold OK: favorites and folders")

        // The side padding lives inside the scroller, so a zoomed tile at the edge is never cut.
        scroll = ColScroller(this).apply {
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
        animateIn(frame.panel)

        Bg.run({ Apps.all(this) }) { all -> if (all != null) afterEnter { apps = all; build() } }
    }

    override fun onResume() {
        super.onResume()
        cfg = Config.load(this)
        frame.update(cfg)
    }

    /** One cell of the grid: a tile and its name under it. */
    private class Item(val app: AppEntry?, val folder: Folders.Folder?)

    /** Builds the grid again; focus goes to [focusKey] (a package or a folder token) when it is there. */
    private fun build(focusKey: String? = null) {
        val folders = Folders.all(this)
        val folder = openFolder?.let { id -> folders.firstOrNull { it.id == id } }
        if (openFolder != null && folder == null) openFolder = null
        frame.titleView.text = Lang.tr(folder?.name ?: "All apps")
        val items: List<Item> = if (folder != null) {
            folder.apps.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }.map { Item(it, null) }
        } else {
            val inFolders = folders.flatMap { it.apps }.toHashSet()
            folders.map { Item(null, it) } + apps.filter { it.pkg !in inFolders }.map { Item(it, null) }
        }
        list.removeAllViews()
        var row: LinearLayout? = null
        var focusTo: View? = null
        var first: View? = null
        items.forEachIndexed { i, item ->
            if (i % columns == 0) {
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
                row = r
                val lp = LinearLayout.LayoutParams(-1, -2)
                if (i > 0) lp.topMargin = dp(14f)
                list.addView(r, lp)
            }
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
            val tile: View
            val name: String
            val key: String
            if (item.folder != null) {
                val f = item.folder
                tile = FolderTile(this, f, f.apps.mapNotNull { p -> apps.firstOrNull { it.pkg == p } })
                tile.setOnClickListener { openFolder = f.id; build() }
                tile.setOnLongClickListener { folderMenu(f); true }
                name = f.name
                key = Folders.token(f.id)
            } else {
                val app = item.app!!
                tile = AppTile(this, app)
                tile.setOnClickListener { if (!Apps.launch(this, app.pkg)) toast("Can't open ${app.label}") }
                tile.setOnLongClickListener { menu(app); true }
                name = app.label
                key = app.pkg
            }
            if (first == null) first = tile
            if (key == focusKey) focusTo = tile
            cell.addView(tile, LinearLayout.LayoutParams(-1, dp(74f)))
            cell.addView(label(name, 10f, C.TEXT2).apply {
                maxLines = 1
                setPadding(dp(2f), dp(6f), 0, 0)
            })
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            if (i % columns > 0) lp.leftMargin = dp(12f)
            row!!.addView(cell, lp)
        }
        val rem = items.size % columns
        if (rem > 0) {
            for (k in rem until columns) {
                row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(12f) })
            }
        }
        if (items.isEmpty()) {
            list.addView(label(if (folder != null) "This folder is empty. Hold OK on an app and choose Add to folder." else "No apps", 12f, C.TEXT2))
        }
        (focusTo ?: first)?.requestFocus()
    }

    // ---------- menus ----------

    private fun menu(app: AppEntry) {
        val favs = FavStore.get(this, cfg)
        val isFav = favs.contains(app.pkg)
        val inFolder = Folders.folderOf(Folders.all(this), app.pkg)
        val items = mutableListOf(if (isFav) "Remove from favorites" else "Add to favorites")
        items.add(if (inFolder == null) "Add to folder" else "Move to another folder")
        if (inFolder != null) items.add("Take out of the folder")
        items.add("Open")
        Popup.menu(this, app.label, items) { which ->
            when (items[which]) {
                "Remove from favorites", "Add to favorites" -> {
                    if (isFav) favs.remove(app.pkg) else favs.add(app.pkg)
                    FavStore.set(this, favs)
                    toast(if (isFav) "Removed from favorites" else "Added to favorites")
                }
                "Add to folder", "Move to another folder" -> chooseFolder(app, inFolder?.id)
                "Take out of the folder" -> {
                    Folders.remove(this, app.pkg)
                    build(app.pkg)
                }
                else -> Apps.launch(this, app.pkg)
            }
        }
    }

    /** The folders to put [app] into, and a new one. */
    private fun chooseFolder(app: AppEntry, current: String?) {
        val folders = Folders.all(this).filter { it.id != current }
        val items = folders.map { it.name } + Lang.tr("New folder…")
        Popup.menu(this, app.label, items) { which ->
            if (which < folders.size) {
                Folders.add(this, folders[which].id, app.pkg)
                toast("Moved to ${folders[which].name}")
                build(if (openFolder != null) null else Folders.token(folders[which].id))
            } else {
                val suggested = Folders.nextName(Folders.all(this), Lang.tr("Folder"))
                Popup.text(this, "New folder", suggested, "Name of the folder") { name ->
                    val id = Folders.create(this, name.ifBlank { suggested }, app.pkg)
                    build(if (openFolder != null) null else Folders.token(id))
                }
            }
        }
    }

    private fun folderMenu(f: Folders.Folder) {
        val token = Folders.token(f.id)
        val favs = FavStore.get(this, cfg)
        val isFav = favs.contains(token)
        val items = listOf("Open", if (isFav) "Remove from favorites" else "Add to favorites", "Rename", "Delete folder")
        Popup.menu(this, f.name, items) { which ->
            when (which) {
                0 -> { openFolder = f.id; build() }
                1 -> {
                    if (isFav) favs.remove(token) else favs.add(token)
                    FavStore.set(this, favs)
                    toast(if (isFav) "Removed from favorites" else "Added to favorites")
                }
                2 -> Popup.text(this, "Rename", f.name, "Name of the folder") { name ->
                    Folders.rename(this, f.id, name)
                    build(token)
                }
                3 -> Popup.menu(this, Lang.tr("Delete \"${f.name}\"? The apps stay."), listOf("Delete folder", "Cancel")) { yes ->
                    if (yes == 0) {
                        Folders.delete(this, f.id)
                        build(f.apps.firstOrNull())
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            (currentFocus as? AppTile)?.let { menu(it.app); return true }
            (currentFocus as? FolderTile)?.let { folderMenu(it.folder); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // inside a folder, Back returns to all the apps (to the folder's tile)
        val f = openFolder
        if (f != null) {
            openFolder = null
            build(Folders.token(f))
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun toast(msg: String) = Toast.makeText(this, Lang.tr(msg), Toast.LENGTH_SHORT).show()
}

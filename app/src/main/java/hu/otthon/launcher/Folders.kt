package hu.otthon.launcher

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

/**
 * App folders, made on the All apps screen. An app is in at most one folder; the apps in a folder
 * are not listed on their own on the All apps screen. A folder can go on the favourites row like an
 * app: there it is the token `folder:<id>` in the favourites list, and opening it shows its apps in
 * a row above the favourites.
 *
 * Kept in the launcher's preferences (`folders`, a JSON list), so they move with the rest of the
 * settings (see [Carry]).
 */
object Folders {
    const val PREFIX = "folder:"
    private const val KEY = "folders"

    data class Folder(val id: String, val name: String, val apps: List<String>)

    fun isToken(s: String) = s.startsWith(PREFIX)
    fun token(id: String) = PREFIX + id
    fun idOf(token: String) = token.removePrefix(PREFIX)

    // ---------- the list itself (no Android here: tested) ----------

    fun parse(json: String?): List<Folder> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val apps = o.optJSONArray("apps")
                Folder(id, o.optString("name"), if (apps == null) emptyList() else (0 until apps.length()).map { apps.optString(it) }.filter { it.isNotBlank() })
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun toJson(list: List<Folder>): String = JSONArray().apply {
        for (f in list) put(JSONObject().put("id", f.id).put("name", f.name).put("apps", JSONArray(f.apps)))
    }.toString()

    /** [pkg] taken out of every folder. */
    fun without(list: List<Folder>, pkg: String): List<Folder> =
        list.map { if (pkg in it.apps) it.copy(apps = it.apps - pkg) else it }

    /** [pkg] put into folder [id] (and out of any other). */
    fun withApp(list: List<Folder>, id: String, pkg: String): List<Folder> =
        without(list, pkg).map { if (it.id == id) it.copy(apps = it.apps + pkg) else it }

    fun folderOf(list: List<Folder>, pkg: String): Folder? = list.firstOrNull { pkg in it.apps }

    /** "Folder 1", "Folder 2"…: the first number not taken. */
    fun nextName(list: List<Folder>, word: String): String {
        var n = 1
        while (list.any { it.name == "$word $n" }) n++
        return "$word $n"
    }

    fun newId(list: List<Folder>, now: Long): String {
        var id = java.lang.Long.toString(now, 36)
        while (list.any { it.id == id }) id += "x"
        return id
    }

    // ---------- on the TV ----------

    fun all(ctx: Context): List<Folder> = parse(Prefs.of(ctx).getString(KEY, null))

    fun get(ctx: Context, id: String): Folder? = all(ctx).firstOrNull { it.id == id }

    private fun save(ctx: Context, list: List<Folder>) {
        Prefs.of(ctx).edit().putString(KEY, toJson(list)).apply()
    }

    /** A new folder, with [first] in it when given; its id. */
    fun create(ctx: Context, name: String, first: String?): String {
        val list = all(ctx)
        val id = newId(list, System.currentTimeMillis())
        val made = list + Folder(id, name.ifBlank { nextName(list, Lang.tr("Folder")) }, emptyList())
        save(ctx, if (first != null) withApp(made, id, first) else made)
        return id
    }

    fun add(ctx: Context, id: String, pkg: String) = save(ctx, withApp(all(ctx), id, pkg))

    fun remove(ctx: Context, pkg: String) = save(ctx, without(all(ctx), pkg))

    fun rename(ctx: Context, id: String, name: String) {
        if (name.isBlank()) return
        save(ctx, all(ctx).map { if (it.id == id) it.copy(name = name) else it })
    }

    /** The folder goes (its apps stay installed and are listed on their own again), also from the favourites. */
    fun delete(ctx: Context, id: String) {
        save(ctx, all(ctx).filter { it.id != id })
        val favs = Prefs.of(ctx).getString("favorites", null) ?: return
        Prefs.of(ctx).edit().putString("favorites", favs.split(',').filter { it != token(id) }.joinToString(",")).apply()
    }

    /** The folder's apps that are installed. */
    fun apps(ctx: Context, f: Folder): List<AppEntry> = f.apps.mapNotNull { Apps.one(ctx, it) }
}

/**
 * A folder's tile: the icons of its first apps and its name, the size of an app's tile. Focus
 * looks the same as on an app's tile (glow, grow).
 */
class FolderTile(ctx: Context, val folder: Folders.Folder, apps: List<AppEntry>) : FrameLayout(ctx) {
    private val radius = ctx.dp(Look.R_TILE).toFloat()
    private val glass = rounded(C.GLASS, radius)
    private val solid = rounded(0xFF17181D.toInt(), radius)

    init {
        isFocusable = true
        isClickable = true
        isLongClickable = true
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        background = glass
        FocusGlide.shape(this, radius, 0f)
        FocusGlide.glow(this, apps.firstOrNull()?.let { AppColors.of(it) } ?: 0xFFE9E7E2.toInt())
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val icons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val shown = apps.take(4)
        shown.forEachIndexed { i, a ->
            icons.addView(ImageView(ctx).apply { setImageDrawable(a.icon) },
                LinearLayout.LayoutParams(ctx.dp(20f), ctx.dp(20f)).apply { if (i > 0) leftMargin = ctx.dp(4f) })
        }
        if (shown.isEmpty()) {
            icons.addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_folder); setColorFilter(C.TEXT2) },
                LinearLayout.LayoutParams(ctx.dp(24f), ctx.dp(24f)))
        }
        col.addView(icons)
        col.addView(ctx.label(folder.name, 10f, C.TEXT, bold = true).apply {
            setPadding(ctx.dp(4f), ctx.dp(6f), ctx.dp(4f), 0)
            maxLines = 1
            gravity = Gravity.CENTER
        })
        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        contentDescription = folder.name
        setOnFocusChangeListener { v, f ->
            background = if (f) solid else glass
            Look.grow(v, f, Look.IMAGE_GROW)
            Neighbors.push(v, f)
        }
    }
}

/**
 * Drawn over a favourite while it is being moved: the tile darkens a little and shows ◀ ▶ at its
 * sides, as on Google TV home.
 */
class MoveArrows(private val size: Float) : android.graphics.drawable.Drawable() {
    private val scrim = android.graphics.Paint().apply { color = 0x66000000 }
    private val arrow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = android.graphics.Paint.Style.FILL
    }
    private val path = android.graphics.Path()

    override fun draw(c: android.graphics.Canvas) {
        val b = bounds
        c.drawRect(b, scrim)
        val cy = b.exactCenterY()
        val m = size * 0.8f
        path.reset()
        // left
        path.moveTo(b.left + m, cy)
        path.lineTo(b.left + m + size, cy - size)
        path.lineTo(b.left + m + size, cy + size)
        path.close()
        // right
        path.moveTo(b.right - m, cy)
        path.lineTo(b.right - m - size, cy - size)
        path.lineTo(b.right - m - size, cy + size)
        path.close()
        c.drawPath(path, arrow)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * Behind a folder's row opened from the favourites: a dark band across the screen with a soft
 * shadow, and a point at its bottom edge over the folder's tile, so it is clear which tile the row
 * came from.
 * [left]/[right] are the panel's sides and [tipX] the middle of the folder's tile, in the view's
 * own pixels; [tail] is the height of the point, kept free under the panel by the view's padding.
 */
class FolderCallout(private val radius: Float, private val tail: Float, private val shadow: Float) : android.graphics.drawable.Drawable() {
    var left = 0f
    var right = 0f
    var tipX = 0f
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6111216.toInt() }
    private val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val soft = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val path = android.graphics.Path()

    override fun draw(c: android.graphics.Canvas) {
        val b = bounds
        if (right <= left) return
        val top = b.top.toFloat() + shadow
        val bottom = b.bottom - tail
        // the shadow: a few wider, fainter outlines under the panel (no blur: cheap on a TV)
        for (k in 3 downTo 1) {
            val g = shadow * k / 3f
            soft.color = android.graphics.Color.argb(26, 0, 0, 0)
            c.drawRoundRect(left - g, top - g + shadow / 2, right + g, bottom + g + shadow / 2, radius + g, radius + g, soft)
        }
        val tip = tipX.coerceIn(left + radius + tail, right - radius - tail)
        path.reset()
        path.addRoundRect(left, top, right, bottom, radius, radius, android.graphics.Path.Direction.CW)
        val point = android.graphics.Path().apply {
            moveTo(tip - tail, bottom - 1f)
            lineTo(tip, bottom + tail)
            lineTo(tip + tail, bottom - 1f)
            close()
        }
        path.op(point, android.graphics.Path.Op.UNION)
        c.drawPath(path, fill)
        c.drawPath(path, edge)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

package hu.otthon.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The last 24 hours' notifications and camera alerts, for the quick settings: what came while
 * nobody was looking can be looked at there, and dismissed when it no longer matters.
 *
 * Filled by the pop-up service, as things happen and from Home Assistant's history (so what came
 * while the TV slept is there too). Kept in a small file of this TV's own.
 */
object Inbox {
    const val KEEP_MS = 24 * 3600_000L
    private const val MAX = 150

    /** One entry. [id] makes the same event, seen twice, one entry; [camera] is set for an alert. */
    class Item(
        val id: String,
        val at: Long,
        val icon: String,
        val level: String,
        val title: String,
        val text: String,
        val camera: String?
    ) {
        fun json(): JSONObject = JSONObject().put("id", id).put("at", at).put("icon", icon).put("level", level)
            .put("title", title).put("text", text).apply { if (camera != null) put("camera", camera) }

        companion object {
            fun of(o: JSONObject) = Item(
                o.getString("id"), o.getLong("at"), o.optString("icon", "bell"), o.optString("level", "info"),
                o.optString("title", ""), o.optString("text", ""), o.optString("camera", "").ifBlank { null }
            )
        }
    }

    private var items: ArrayList<Item>? = null
    /** Ids dismissed in the last day: history must not bring them back. */
    private var gone: HashMap<String, Long>? = null
    private val main = Handler(Looper.getMainLooper())

    /** Called on the main thread when the list changes (the open quick settings redraw). */
    @Volatile var onChange: (() -> Unit)? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "inbox.json")

    private fun load(ctx: Context) {
        if (items != null) return
        val list = ArrayList<Item>()
        val dismissed = HashMap<String, Long>()
        try {
            val f = file(ctx)
            if (f.exists()) {
                val o = JSONObject(f.readText())
                val a = o.optJSONArray("items") ?: JSONArray()
                for (i in 0 until a.length()) try { list.add(Item.of(a.getJSONObject(i))) } catch (e: Exception) { }
                val g = o.optJSONObject("gone") ?: JSONObject()
                for (k in g.keys()) dismissed[k] = g.optLong(k)
            }
        } catch (e: Exception) {
            CrashLog.note(e)
        }
        items = list
        gone = dismissed
    }

    private fun prune() {
        val cut = System.currentTimeMillis() - KEEP_MS
        items?.removeAll { it.at < cut }
        gone?.entries?.removeAll { it.value < cut }
        items?.let { while (it.size > MAX) it.removeAt(it.size - 1) }
    }

    private fun save(ctx: Context) {
        try {
            val a = JSONArray()
            items?.forEach { a.put(it.json()) }
            val g = JSONObject()
            gone?.forEach { (k, v) -> g.put(k, v) }
            val f = file(ctx)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(JSONObject().put("items", a).put("gone", g).toString())
            tmp.renameTo(f)
        } catch (e: Exception) {
            CrashLog.note(e)
        }
        onChange?.let { cb -> main.post { try { cb() } catch (e: Exception) { CrashLog.note(e) } } }
    }

    /** The entries of the last 24 hours, the newest first. */
    @Synchronized
    fun list(ctx: Context): List<Item> {
        load(ctx)
        prune()
        return ArrayList(items!!)
    }

    /** Adds [item] unless it is older than a day, already there, or was dismissed. True when added. */
    @Synchronized
    fun add(ctx: Context, item: Item): Boolean {
        load(ctx)
        if (item.at < System.currentTimeMillis() - KEEP_MS) return false
        val list = items!!
        if (gone!!.containsKey(item.id) || list.any { it.id == item.id }) return false
        val i = list.indexOfFirst { it.at < item.at }.let { if (it < 0) list.size else it }
        list.add(i, item)
        prune()
        save(ctx)
        return true
    }

    @Synchronized
    fun dismiss(ctx: Context, id: String) {
        load(ctx)
        val x = items!!.firstOrNull { it.id == id } ?: return
        items!!.remove(x)
        gone!![id] = x.at
        save(ctx)
    }

    @Synchronized
    fun clear(ctx: Context) {
        load(ctx)
        items!!.forEach { gone!![it.id] = it.at }
        items!!.clear()
        save(ctx)
    }

    // ---------- what goes in ----------

    /** A Home Assistant notification: the helper's "id|icon|level|title|text". Null when it is not one. */
    fun note(raw: String): Item? {
        val p = raw.split('|')
        if (p.size < 5) return null
        val sent = p[0].trim().toLongOrNull() ?: return null
        val title = p[3].trim()
        val text = p.drop(4).joinToString("|").trim()
        if (title.isBlank() && text.isBlank()) return null
        return Item("note:$sent", sent * 1000L, p[1].trim().ifBlank { "bell" }, p[2].trim().ifBlank { "info" }, title, text, null)
    }

    /** A camera's sensor that turned on at [at]. */
    fun alert(t: PopupTrigger, at: Long): Item =
        Item("cam:${t.entity}@${at / 1000}", at, "camera", "warn", t.title, "", t.camera)
}

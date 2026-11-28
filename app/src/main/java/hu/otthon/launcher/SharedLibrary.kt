package hu.otthon.launcher

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import org.json.JSONArray
import org.json.JSONObject
import java.util.EnumSet

/**
 * What the TVs found out about the films and series, kept on the NAS beside them, so every TV
 * knows the same: a TV with a TMDB key writes it after each scan, every TV reads it before looking
 * anything up itself (a TV without a key then shows TMDB's posters, stories and episodes too, and a
 * file one TV took for a film but is an episode is put right).
 *
 * One file per share: \\NAS\share\.meta\library.json (hidden from the scan, like every name
 * starting with a dot). In it, for each file (its path in the share): which TMDB title it is, film
 * or series, season and episode; the titles' data; the episodes' names, stories and pictures.
 * Only TMDB's data (positive ids): TVmaze and Wikipedia answers stay on the TV that asked. Merged
 * on writing, so two TVs with keys add to each other's. The NAS user needs write access for the
 * writing; reading works with read access. Background thread.
 */
object SharedLibrary {
    private const val DIR = ".meta"
    private const val FILE = ".meta\\library.json"

    /** The SMB sources by share (one file for all the sources on a share). */
    private fun byShare(ctx: Context): Map<String, List<MediaLib.Source>> =
        MediaLib.sources(ctx).filter { it.kind == "smb" }.groupBy { (it.host + "\\" + it.share).lowercase() }

    private fun read(s: MediaLib.SmbShare): JSONObject? {
        if (!s.share.fileExists(FILE)) return null
        val f = s.share.openFile(FILE, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
        return f.use { JSONObject(it.inputStream.readBytes().toString(Charsets.UTF_8)) }
    }

    // ---------- reading ----------

    /**
     * Takes from the NAS what is known of the files this TV does not know yet (or only from
     * TVmaze or Wikipedia). The number of files taken; a share that cannot be read is passed over.
     */
    fun take(ctx: Context): Int {
        var taken = 0
        val db = MediaLib.Db.of(ctx).writableDatabase
        for ((_, sources) in byShare(ctx)) {
            val o = try { MediaLib.openShare(ctx, sources[0]).use { read(it) } } catch (e: Exception) {
                android.util.Log.w("OtthonMedia", "shared list on ${sources[0].label}: $e"); null
            } ?: continue
            val files = o.optJSONObject("files") ?: continue
            // the paths in the share, any case (SMB does not mind it)
            val known = HashMap<String, JSONObject>()
            for (k in files.keys()) files.optJSONObject(k)?.let { known[k.lowercase()] = it }
            val titles = HashMap<String, JSONObject>()
            o.optJSONArray("titles")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { t -> titles["${t.optLong("tmdb")}|${t.optInt("series")}"] = t } }
            val shows = HashMap<Long, MutableList<JSONObject>>()
            o.optJSONArray("episodes")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { e -> shows.getOrPut(e.optLong("show")) { ArrayList() }.add(e) } }
            val ids = sources.map { it.id }
            val q = ids.joinToString(",") { "?" }
            val todo = ArrayList<Pair<Long, String>>()
            db.rawQuery("SELECT id, path FROM files WHERE source IN ($q) AND seen = 1 AND (tmdb IS NULL OR tmdb < 0)", ids.toTypedArray()).use { c ->
                while (c.moveToNext()) todo.add(c.getLong(0) to c.getString(1))
            }
            if (todo.isEmpty()) continue
            val usedTitles = HashSet<String>()
            db.beginTransaction()
            try {
                for ((id, path) in todo) {
                    val e = known[path.lowercase()] ?: continue
                    val tmdb = e.optLong("tmdb").takeIf { it > 0 } ?: continue
                    val series = e.optInt("series")
                    val key = "$tmdb|$series"
                    val t = titles[key] ?: continue
                    db.update("files", ContentValues().apply {
                        put("tmdb", tmdb); put("series", series); put("title", e.optString("title"))
                        if (e.has("season")) put("season", e.optInt("season")) else putNull("season")
                        if (e.has("episode")) put("episode", e.optInt("episode")) else putNull("episode")
                    }, "id = ?", arrayOf(id.toString()))
                    usedTitles.add(key)
                    taken++
                }
                for (key in usedTitles) {
                    val t = titles[key] ?: continue
                    db.insertWithOnConflict("titles", null, ContentValues().apply {
                        put("tmdb", t.optLong("tmdb")); put("series", t.optInt("series")); put("title", t.optString("title"))
                        put("original", t.optString("original")); put("overview", t.optString("overview"))
                        if (t.has("year")) put("year", t.optInt("year"))
                        put("rating", t.optDouble("rating", 0.0)); put("genres", t.optString("genres")); put("cast_", t.optString("cast"))
                        put("poster", t.optString("poster").ifBlank { null }); put("backdrop", t.optString("backdrop").ifBlank { null })
                        put("runtime", t.optInt("runtime")); put("fetched", t.optLong("fetched", System.currentTimeMillis()))
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                    if (t.optInt("series") == 1) for (ep in shows[t.optLong("tmdb")] ?: emptyList<JSONObject>()) {
                        db.insertWithOnConflict("episodes", null, ContentValues().apply {
                            put("show", ep.optLong("show")); put("season", ep.optInt("season")); put("episode", ep.optInt("episode"))
                            put("name", ep.optString("name")); put("overview", ep.optString("overview")); put("still", ep.optString("still").ifBlank { null })
                        }, SQLiteDatabase.CONFLICT_REPLACE)
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        return taken
    }

    // ---------- writing ----------

    /** Writes what this TV knows from TMDB to each share (merged with what is there). Why not, or null when written. */
    fun give(ctx: Context): String? {
        val db = MediaLib.Db.of(ctx).readableDatabase
        var why: String? = null
        for ((_, sources) in byShare(ctx)) {
            val ids = sources.map { it.id }
            val q = ids.joinToString(",") { "?" }
            val files = JSONObject()
            val wanted = HashSet<String>()
            db.rawQuery("SELECT path, tmdb, series, title, season, episode FROM files WHERE source IN ($q) AND seen = 1 AND tmdb > 0", ids.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val e = JSONObject().put("tmdb", c.getLong(1)).put("series", c.getInt(2)).put("title", c.getString(3) ?: "")
                    if (!c.isNull(4)) e.put("season", c.getInt(4))
                    if (!c.isNull(5)) e.put("episode", c.getInt(5))
                    files.put(c.getString(0), e)
                    wanted.add("${c.getLong(1)}|${c.getInt(2)}")
                }
            }
            if (files.length() == 0) continue
            val titles = JSONArray()
            val showIds = HashSet<Long>()
            db.rawQuery("SELECT tmdb, series, title, original, overview, year, rating, genres, cast_, poster, backdrop, runtime, fetched FROM titles WHERE tmdb > 0", null).use { c ->
                while (c.moveToNext()) {
                    if ("${c.getLong(0)}|${c.getInt(1)}" !in wanted) continue
                    val t = JSONObject().put("tmdb", c.getLong(0)).put("series", c.getInt(1)).put("title", c.getString(2) ?: "")
                        .put("original", c.getString(3) ?: "").put("overview", c.getString(4) ?: "")
                        .put("rating", c.getDouble(6)).put("genres", c.getString(7) ?: "").put("cast", c.getString(8) ?: "")
                        .put("poster", c.getString(9) ?: "").put("backdrop", c.getString(10) ?: "").put("runtime", c.getInt(11)).put("fetched", c.getLong(12))
                    if (!c.isNull(5)) t.put("year", c.getInt(5))
                    titles.put(t)
                    if (c.getInt(1) == 1) showIds.add(c.getLong(0))
                }
            }
            val episodes = JSONArray()
            if (showIds.isNotEmpty()) db.rawQuery("SELECT show, season, episode, name, overview, still FROM episodes WHERE show > 0", null).use { c ->
                while (c.moveToNext()) {
                    if (c.getLong(0) !in showIds) continue
                    episodes.put(JSONObject().put("show", c.getLong(0)).put("season", c.getInt(1)).put("episode", c.getInt(2))
                        .put("name", c.getString(3) ?: "").put("overview", c.getString(4) ?: "").put("still", c.getString(5) ?: ""))
                }
            }
            try {
                MediaLib.openShare(ctx, sources[0]).use { s ->
                    // what another TV wrote stays, ours goes over it
                    val old = try { read(s) } catch (e: Exception) { null }
                    val merged = merge(old, files, titles, episodes)
                    val text = merged.toString()
                    if (old != null && old.optJSONObject("files")?.toString() == merged.optJSONObject("files")?.toString() &&
                        old.optJSONArray("titles")?.length() == merged.optJSONArray("titles")?.length() &&
                        old.optJSONArray("episodes")?.length() == merged.optJSONArray("episodes")?.length()) return@use
                    if (!s.share.folderExists(DIR)) s.share.mkdir(DIR)
                    s.share.openFile(FILE, EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null).use { f ->
                        f.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    }
                    android.util.Log.i("OtthonMedia", "shared list written to ${sources[0].label}: ${merged.optJSONObject("files")?.length()} files")
                }
            } catch (e: Exception) {
                android.util.Log.w("OtthonMedia", "can't write the shared list to ${sources[0].label}", e)
                why = "${sources[0].name}: " + (if (e.toString().contains("ACCESS_DENIED", true)) "no write access" else e.javaClass.simpleName)
            }
        }
        return why
    }

    /** [old] (another TV's) with ours over it: files and titles by key, episodes by show/season/number. */
    private fun merge(old: JSONObject?, files: JSONObject, titles: JSONArray, episodes: JSONArray): JSONObject {
        val f = JSONObject()
        old?.optJSONObject("files")?.let { o -> for (k in o.keys()) f.put(k, o.get(k)) }
        for (k in files.keys()) f.put(k, files.get(k))
        val t = LinkedHashMap<String, JSONObject>()
        fun addT(a: JSONArray?) { if (a != null) for (i in 0 until a.length()) a.optJSONObject(i)?.let { t["${it.optLong("tmdb")}|${it.optInt("series")}"] = it } }
        addT(old?.optJSONArray("titles")); addT(titles)
        val e = LinkedHashMap<String, JSONObject>()
        fun addE(a: JSONArray?) { if (a != null) for (i in 0 until a.length()) a.optJSONObject(i)?.let { e["${it.optLong("show")}|${it.optInt("season")}|${it.optInt("episode")}"] = it } }
        addE(old?.optJSONArray("episodes")); addE(episodes)
        return JSONObject().put("version", 1).put("written", System.currentTimeMillis())
            .put("files", f).put("titles", JSONArray(t.values.toList())).put("episodes", JSONArray(e.values.toList()))
    }
}

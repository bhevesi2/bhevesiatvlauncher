package hu.otthon.launcher

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The film library's lookups that need no key, for what TMDB does not know or when there is no
 * TMDB key: a series on TVmaze (its story, genres, rating, cast, poster, wide picture and every
 * episode's name, story and picture), a film on Wikipedia (its opening and, where it has one, the
 * poster). Both CC BY-SA, named in the library and in Settings.
 *
 * A page is taken only if its name is the title looked for (and, for a film, its text is about a
 * film, and the year matches when the file has one), so a namesake does not slip in. Kept in the
 * same tables as TMDB's, with negative ids. Background thread.
 */
object OpenData {
    const val AGENT = "OtthonLauncher/1.0 (Android TV home screen; film library)"

    // ---------- TVmaze ----------

    private val gate = Any()
    private var last = 0L

    /** TVmaze allows about 20 questions in 10 s from one address: one every 0.6 s. */
    private fun maze(path: String): String? = synchronized(gate) {
        val wait = last + 600 - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        last = System.currentTimeMillis()
        get("https://api.tvmaze.com$path")
    }

    /** The series [title] on TVmaze; its id (negative) when found and kept, else null. */
    fun series(ctx: Context, title: String, year: Int?): Long? {
        val found = JSONArray(maze("/search/shows?q=" + enc(title)) ?: return null)
        val want = MusicInfo.norm(title)
        val shows = (0 until found.length()).mapNotNull { found.optJSONObject(it)?.optJSONObject("show") }
            .filter { s -> MusicInfo.norm(s.optString("name")).let { it == want || it.removePrefix("the ") == want.removePrefix("the ") } }
        fun yearOf(s: JSONObject) = s.optString("premiered").take(4).toIntOrNull()
        val show = (if (year != null) shows.firstOrNull { s -> yearOf(s)?.let { Math.abs(it - year) <= 1 } == true } else null)
            ?: shows.firstOrNull() ?: return null
        val tid = show.optLong("id").takeIf { it > 0 } ?: return null
        val full = JSONObject(maze("/shows/$tid?embed[]=episodes&embed[]=cast") ?: return null)
        val pictures = try { JSONArray(maze("/shows/$tid/images") ?: "[]") } catch (e: Exception) { JSONArray() }
        val wide = (0 until pictures.length()).mapNotNull { pictures.optJSONObject(it) }
            .filter { it.optString("type") == "background" }
            .sortedByDescending { if (it.optBoolean("main")) 1 else 0 }
            .firstOrNull()?.optJSONObject("resolutions")?.optJSONObject("original")?.optString("url")?.ifBlank { null }
        val emb = full.optJSONObject("_embedded")
        val cast = emb?.optJSONArray("cast")?.let { a -> (0 until minOf(8, a.length())).mapNotNull { a.optJSONObject(it)?.optJSONObject("person")?.optString("name") } }
            ?.joinToString(", ") ?: ""
        val genres = full.optJSONArray("genres")?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } } ?: ""
        val id = -tid
        val db = MediaLib.Db.of(ctx).writableDatabase
        db.insertWithOnConflict("titles", null, ContentValues().apply {
            put("tmdb", id); put("series", 1)
            put("title", full.optString("name")); put("original", full.optString("name"))
            put("overview", plain(full.optString("summary"))); yearOf(full)?.let { put("year", it) }
            put("rating", full.optJSONObject("rating")?.optDouble("average", 0.0)?.takeIf { !it.isNaN() } ?: 0.0)
            put("genres", genres); put("cast_", cast)
            put("poster", full.optJSONObject("image")?.let { it.optString("original").ifBlank { it.optString("medium") } }?.ifBlank { null })
            put("backdrop", wide)
            put("runtime", full.optInt("averageRuntime", full.optInt("runtime", 0))); put("fetched", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        val eps = emb?.optJSONArray("episodes")
        if (eps != null) for (k in 0 until eps.length()) {
            val e = eps.optJSONObject(k) ?: continue
            if (e.isNull("number")) continue   // specials have no number
            db.insertWithOnConflict("episodes", null, ContentValues().apply {
                put("show", id); put("season", e.optInt("season")); put("episode", e.optInt("number"))
                put("name", e.optString("name")); put("overview", plain(e.optString("summary")))
                put("still", e.optJSONObject("image")?.optString("medium")?.ifBlank { null })
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
        return id
    }

    // ---------- Wikipedia ----------

    private val filmWords = listOf("film", "movie", "filmdrama", "mozifilm", "filmvigjatek", "animacios", "dokumentumfilm")

    /** The film [title] on Wikipedia (Hungarian first when the launcher speaks Hungarian); its id (negative) or null. */
    fun film(ctx: Context, title: String, year: Int?): Long? {
        val langs = if (Lang.hu) listOf("hu", "en") else listOf("en")
        for ((li, lang) in langs.withIndex()) {
            val q = title + (if (year != null) " $year" else "") + " film"
            val url = "https://$lang.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&redirects=1" +
                "&generator=search&gsrlimit=5&gsrsearch=" + enc(q) +
                "&prop=extracts%7Cpageimages&exintro=1&explaintext=1&exlimit=5&piprop=original&pilicense=any&pilimit=5"
            val pages = JSONObject(get(url) ?: continue).optJSONObject("query")?.optJSONArray("pages") ?: continue
            val want = MusicInfo.norm(title)
            val list = (0 until pages.length()).mapNotNull { pages.optJSONObject(it) }.sortedBy { it.optInt("index", 99) }
            for (p in list) {
                // "Dune (2021 film)", "A tanú (film, 1969)": the name without what is in brackets
                val name = MusicInfo.norm(p.optString("title").replace(Regex("\\s*\\([^)]*\\)"), ""))
                if (name != want && name.removePrefix("the ") != want.removePrefix("the ")) continue
                val text = p.optString("extract").trim()
                if (text.length < 60) continue
                val n = MusicInfo.norm(text)
                if (filmWords.none { n.contains(it) }) continue
                if (year != null && listOf(year - 1, year, year + 1).none { y -> text.contains(y.toString()) || p.optString("title").contains(y.toString()) }) continue
                val page = p.optLong("pageid").takeIf { it > 0 } ?: continue
                val id = -(page * 10 + li + 1)
                val found = year ?: Regex("\\b(18[89]\\d|19\\d\\d|20[0-4]\\d)\\b").find(text)?.value?.toIntOrNull()
                MediaLib.Db.of(ctx).writableDatabase.insertWithOnConflict("titles", null, ContentValues().apply {
                    put("tmdb", id); put("series", 0)
                    put("title", p.optString("title").replace(Regex("\\s*\\([^)]*\\)"), "")); put("original", "")
                    put("overview", cut(text)); found?.let { put("year", it) }
                    put("rating", 0.0); put("genres", ""); put("cast_", "")
                    put("poster", p.optJSONObject("original")?.optString("source")?.ifBlank { null }); put("backdrop", null as String?)
                    put("runtime", 0); put("fetched", System.currentTimeMillis())
                }, SQLiteDatabase.CONFLICT_REPLACE)
                return id
            }
        }
        return null
    }

    // ---------- small things ----------

    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", AGENT)
        try {
            val code = c.responseCode
            if (code == 429 || code >= 500) throw java.io.IOException("HTTP $code")
            if (code != 200) return null
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** TVmaze's stories are HTML: as plain text. */
    fun plain(html: String): String = html
        .replace(Regex("<br\\s*/?>|</p>\\s*<p>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .trim()

    private fun cut(text: String): String {
        val t = text.replace(Regex("\\n{2,}"), "\n").trim()
        if (t.length <= 900) return t
        val end = t.lastIndexOf(". ", 900)
        return if (end > 300) t.substring(0, end + 1) else t.substring(0, 900) + "…"
    }
}

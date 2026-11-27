package hu.otthon.launcher

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Stories and pictures for the music library, the way TMDB gives them to the films: an artist's
 * biography, photo and wide background picture, an album's description, year and genre.
 *
 * From TheAudioDB (its free shared key: 30 questions a minute, so one at a time and spaced) and
 * Wikipedia: Hungarian first (TheAudioDB's Hungarian text, then the Hungarian Wikipedia), then
 * English. A Wikipedia page is taken only when its title is the name asked for and its text is
 * about music (an album's must name the artist), so a namesake does not slip in.
 *
 * Kept on the TV (filesDir/musicinfo), so each is fetched once; nothing found is asked again after
 * a week. All calls but [cached] on a background thread.
 */
object MusicInfo {
    class Info(
        val text: String,
        /** Year, genre, country: short, for the line under the name. */
        val meta: String,
        /** A square picture (the artist's photo, the album's cover). */
        val picture: String?,
        /** A wide picture for the background (the artist's fanart). */
        val backdrop: String?,
        /** Where the text is from: TheAudioDB or Wikipedia. */
        val source: String,
    )

    private const val ADB = "https://www.theaudiodb.com/api/v1/json/123/"
    private const val WEEK = 7 * 24 * 3600_000L
    private val memory = HashMap<String, Info?>()
    private val gate = Any()
    private var lastAdb = 0L

    /** What is known without asking anyone (memory only): for the first, instant fill of a page. */
    fun cached(key: String): Info? = synchronized(memory) { memory[key] }

    fun artistKey(name: String) = "artist|" + norm(name)
    fun albumKey(artist: String, album: String) = "album|" + norm(artist) + "|" + norm(bare(album))

    fun artist(ctx: Context, name: String): Info? = remember(ctx, artistKey(name)) { fetchArtist(name) }

    fun album(ctx: Context, artist: String, album: String): Info? = remember(ctx, albumKey(artist, album)) {
        val a = fetchAlbum(artist, album) ?: return@remember null
        // the artist's wide picture behind the album, when the album has none of its own
        val art = if (artist.isNotBlank()) artist(ctx, artist) else null
        Info(a.text, a.meta, a.picture, a.backdrop ?: art?.backdrop, a.source)
    }

    // ---------- the store ----------

    private fun remember(ctx: Context, key: String, fetch: () -> Info?): Info? {
        synchronized(memory) { if (memory.containsKey(key)) return memory[key] }
        val dir = File(ctx.filesDir, "musicinfo").apply { mkdirs() }
        val file = File(dir, Integer.toHexString(key.hashCode()) + "_" + key.length + ".json")
        try {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                if (o.optString("key") == key) {
                    val miss = o.optBoolean("miss")
                    if (!miss || System.currentTimeMillis() - o.optLong("at") < WEEK) {
                        val info = if (miss) null else Info(o.optString("text"), o.optString("meta"),
                            o.optString("picture").ifBlank { null }, o.optString("backdrop").ifBlank { null }, o.optString("source"))
                        synchronized(memory) { memory[key] = info }
                        return info
                    }
                }
            }
        } catch (e: Exception) { }
        val info = try { fetch() } catch (e: Exception) {
            android.util.Log.w("OtthonMusic", "info for $key: $e")
            return null   // not reachable now: not kept, asked again next time
        }
        synchronized(memory) { memory[key] = info }
        try {
            val o = JSONObject().put("key", key).put("at", System.currentTimeMillis())
            if (info == null) o.put("miss", true)
            else o.put("text", info.text).put("meta", info.meta).put("picture", info.picture ?: "").put("backdrop", info.backdrop ?: "").put("source", info.source)
            file.writeText(o.toString())
        } catch (e: Exception) { }
        return info
    }

    // ---------- TheAudioDB ----------

    private fun fetchArtist(name: String): Info? {
        val a = adb("search.php?s=" + enc(name))?.optJSONArray("artists")?.optJSONObject(0)
            ?.takeIf { same(it.optString("strArtist"), name) }
        val music = listOf("band", "singer", "musician", "rapper", "composer", "songwriter", "dj", "duo", "group", "orchestra",
            "együttes", "zenekar", "énekes", "zenész", "rapper", "zeneszerző", "előadó", "dalszerző", "duó", "zenekar")
        val hu = a?.str("strBiographyHU") ?: wiki("hu", name, name, null, music)
        val text = hu ?: a?.str("strBiography") ?: wiki("en", name, name, null, music) ?: ""
        if (a == null && text.isBlank()) return null
        val meta = listOfNotNull(a?.str("strGenre"), a?.str("strCountry"), a?.str("intFormedYear")?.let { "since $it" }).joinToString(" · ")
        val source = if (a != null && (text == a.str("strBiographyHU") || text == a.str("strBiography"))) "TheAudioDB" else "Wikipedia"
        return Info(cut(text), meta, a?.str("strArtistThumb"),
            a?.str("strArtistFanart") ?: a?.str("strArtistWideThumb") ?: a?.str("strArtistFanart2"), source)
    }

    private fun fetchAlbum(artist: String, album: String): Info? {
        val name = bare(album)
        val a = if (artist.isBlank()) null else adb("searchalbum.php?s=" + enc(artist) + "&a=" + enc(name))?.optJSONArray("album")
            ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { same(it.optString("strAlbum"), name) } }
        val hu = a?.str("strDescriptionHU") ?: wiki("hu", "$name $artist album", name, artist, null)
        val text = hu ?: a?.str("strDescription") ?: wiki("en", "$name $artist album", name, artist, null) ?: ""
        if (a == null && text.isBlank()) return null
        val meta = listOfNotNull(a?.str("intYearReleased"), a?.str("strGenre")).joinToString(" · ")
        val source = if (a != null && (text == a.str("strDescriptionHU") || text == a.str("strDescription"))) "TheAudioDB" else "Wikipedia"
        return Info(cut(text), meta, a?.str("strAlbumThumb"), null, source)
    }

    /** One question to TheAudioDB, at most one every 2.1 s (30 a minute on the free key). */
    private fun adb(path: String): JSONObject? = synchronized(gate) {
        val wait = lastAdb + 2100 - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastAdb = System.currentTimeMillis()
        get(ADB + path)?.let { JSONObject(it) }
    }

    // ---------- Wikipedia ----------

    /**
     * The opening of the Wikipedia page found for [query] in [lang], if its title is [title] (or
     * starts with it, as "Abbey Road (album)") and its text names [mustName] or one of [anyWord].
     */
    private fun wiki(lang: String, query: String, title: String, mustName: String?, anyWord: List<String>?): String? {
        val url = "https://$lang.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&redirects=1" +
            "&generator=search&gsrlimit=4&gsrsearch=" + enc(query) +
            "&prop=extracts&exintro=1&explaintext=1&exlimit=4"
        val pages = (get(url) ?: return null).let { JSONObject(it) }.optJSONObject("query")?.optJSONArray("pages") ?: return null
        val list = (0 until pages.length()).mapNotNull { pages.optJSONObject(it) }.sortedBy { it.optInt("index", 99) }
        val want = norm(title)
        for (p in list) {
            val t = norm(p.optString("title"))
            if (t != want && !t.startsWith("$want ")) continue
            val text = p.optString("extract").trim()
            if (text.length < 40) continue
            val n = norm(text)
            if (mustName != null && !n.contains(norm(mustName))) continue
            if (anyWord != null && anyWord.none { w -> Regex("\\b" + Regex.escape(norm(w)) + "\\b").containsMatchIn(n) }) continue
            return text
        }
        return null
    }

    // ---------- small things ----------

    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 8000
        // Wikipedia asks every program to say who it is
        c.setRequestProperty("User-Agent", "OtthonLauncher/1.0 (Android TV home screen; music library)")
        try {
            if (c.responseCode != 200) return null
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    private fun JSONObject.str(k: String): String? = optString(k, "").trim().takeIf { it.isNotEmpty() && it != "null" && it != "0" }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** For comparing names: no accents, no punctuation, one space. */
    fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace("&", " and ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    /** An album's name without "(Remastered 2011)", "[Deluxe]", "- 2009 Remaster". */
    fun bare(album: String): String = album
        .replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "")
        .replace(Regex("\\s+-\\s+.*(remaster|edition|version|deluxe|live|mono|stereo).*$", RegexOption.IGNORE_CASE), "")
        .trim().ifBlank { album }

    private fun same(a: String, b: String): Boolean {
        val x = norm(a); val y = norm(bare(b))
        return x == y || x.removePrefix("the ") == y.removePrefix("the ")
    }

    /** Long texts to their first ~1100 characters, at the end of a sentence. */
    private fun cut(text: String): String {
        val t = text.replace(Regex("\\n{2,}"), "\n").trim()
        if (t.length <= 1100) return t
        val end = t.lastIndexOf(". ", 1100)
        return if (end > 400) t.substring(0, end + 1) else t.substring(0, 1100) + "…"
    }
}

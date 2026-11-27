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
 * Wikipedia: English first (TheAudioDB's text, then the English Wikipedia: there is far more of
 * it), Hungarian only where there is no English. A Wikipedia page is taken only when its title is
 * the name asked for and its text is about music (an album's must name the artist), so a namesake
 * does not slip in.
 *
 * Kept on the TV (filesDir/musicinfo), so each is fetched once; nothing found is asked again after
 * a week. [prefetch] goes through the whole Music Assistant library in the background once a day,
 * so the stories are there before anyone opens the library. All calls but [cached] on a
 * background thread.
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

    /** v2: English first (the v1 store had Hungarian first; its files are no longer read). */
    fun artistKey(name: String) = "v2|artist|" + norm(name)
    fun albumKey(artist: String, album: String) = "v2|album|" + norm(artist) + "|" + norm(bare(album))

    fun artist(ctx: Context, name: String): Info? = remember(ctx, artistKey(name)) { fetchArtist(name) }

    fun album(ctx: Context, artist: String, album: String): Info? = remember(ctx, albumKey(artist, album)) {
        val a = fetchAlbum(artist, album) ?: return@remember null
        // the artist's wide picture behind the album, when the album has none of its own
        val art = if (artist.isNotBlank()) artist(ctx, artist) else null
        Info(a.text, a.meta, a.picture, a.backdrop ?: art?.backdrop, a.source)
    }

    // ---------- albums Music Assistant files under "Various Artists" ----------

    /**
     * Music Assistant puts an album under "Various Artists" when its files say so, also when every
     * song is by one artist (a missing album artist tag). The songs tell the truth: when most of
     * them are by one artist, the album is shown as that artist's. Kept per album (uri → artist;
     * "" for a real compilation) in files/musicinfo/album_artists.json.
     */
    private var albumArtists: HashMap<String, String>? = null

    fun isVarious(name: String): Boolean = norm(name) in setOf("various artists", "various", "va", "v a", "verschiedene interpreten",
        "diverse", "divers", "vegyes eloadok", "kulonbozo eloadok", "artistes divers", "varios artistas", "artisti vari")

    private fun albumArtistFile(ctx: Context) = File(File(ctx.filesDir, "musicinfo").apply { mkdirs() }, "album_artists.json")

    private fun albumArtistMap(ctx: Context): HashMap<String, String> = synchronized(this) {
        albumArtists ?: HashMap<String, String>().also { m ->
            try {
                val o = JSONObject(albumArtistFile(ctx).readText())
                for (k in o.keys()) m[k] = o.optString(k)
            } catch (e: Exception) { }
            albumArtists = m
        }
    }

    /** The real artist of a "Various Artists" album, "" for a real compilation, null if not known yet. */
    fun albumArtist(ctx: Context, uri: String): String? = synchronized(this) { albumArtistMap(ctx)[uri] }

    fun setAlbumArtist(ctx: Context, uri: String, artist: String) = synchronized(this) {
        val m = albumArtistMap(ctx)
        if (m[uri] == artist) return@synchronized
        m[uri] = artist
        try { albumArtistFile(ctx).writeText(JSONObject(m as Map<*, *>).toString()) } catch (e: Exception) { }
    }

    /** The artist of most of [artists] (the songs' artists), if one has at least 70% of them. */
    fun mainArtist(artists: List<String>): String? {
        val named = artists.map { it.trim() }.filter { it.isNotEmpty() && !isVarious(it) }
        if (named.isEmpty()) return null
        val top = named.groupBy { norm(it) }.maxByOrNull { it.value.size } ?: return null
        return if (top.value.size >= Math.ceil(artists.size * 0.7)) top.value.first() else null
    }

    // ---------- the whole library, in the background ----------

    /** Fetches in a row that could not reach the internet: the background run stops at a few. */
    @Volatile private var failures = 0

    @Volatile var prefetching = false
        private set
    /** For the settings: what the last run did. */
    @Volatile var progress = ""
        private set

    /**
     * Once a day (or when [force]d), a background thread goes through Music Assistant's artists and
     * albums and fetches what is not kept yet. Slow on purpose: TheAudioDB's free key allows 30
     * questions a minute, a library of a few hundred albums takes half an hour or so.
     */
    fun prefetch(ctx: Context, client: HaClient?, force: Boolean = false) {
        if (client == null || prefetching) return
        val p = Prefs.of(ctx)
        if (!p.getBoolean("ma_present", true)) return
        if (!force && System.currentTimeMillis() - p.getLong("music_info_at", 0L) < 24 * 3600_000L) return
        prefetching = true
        val app = ctx.applicationContext
        Thread({
            var done = 0; var found = 0; var total = 0
            try {
                val entry = client.template("{% set p = integration_entities('music_assistant') | select('match', 'media_player') | list %}" +
                    "{{ config_entry_id(p[0]) if p else '' }}").trim()
                if (entry.isNotBlank()) {
                    fun library(type: String, limit: Int): List<JSONObject> {
                        val r = client.callResponse("music_assistant", "get_library", JSONObject()
                            .put("config_entry_id", entry).put("media_type", type).put("order_by", "play_count_desc").put("limit", limit), 30000)
                        val arr = r.optJSONArray("items") ?: return emptyList()
                        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                    }
                    val artists = library("artist", 1000).map { it.optString("name") }.filter { it.isNotBlank() }
                    val all = library("album", 2000)
                    // albums under "Various Artists": their songs say whose they are
                    val player = p.getString("ma_player", null) ?: client.template(
                        "{{ (integration_entities('music_assistant') | select('match', 'media_player') | list)[0] }}").trim()
                    for (o in all) {
                        val uri = o.optString("uri")
                        val by = o.optJSONArray("artists")?.optJSONObject(0)?.optString("name") ?: ""
                        if (!isVarious(by) || albumArtist(app, uri) != null || player.isBlank()) continue
                        try {
                            val r = client.callResponse("media_player", "browse_media", JSONObject()
                                .put("entity_id", player).put("media_content_id", uri).put("media_content_type", "album"), 30000)
                            val kids = r.optJSONObject(player)?.optJSONArray("children") ?: continue
                            if (kids.length() == 0) continue
                            val artists = (0 until kids.length()).map { i ->
                                kids.optJSONObject(i)?.optString("title", "")?.substringBefore(" - ", "") ?: "" }
                            setAlbumArtist(app, uri, mainArtist(artists) ?: "")
                        } catch (e: Exception) { }
                    }
                    val albums = all.mapNotNull { o ->
                        val name = o.optString("name")
                        var by = o.optJSONArray("artists")?.optJSONObject(0)?.optString("name") ?: ""
                        if (isVarious(by)) by = albumArtist(app, o.optString("uri")) ?: ""
                        // a real compilation has no artist to look its story up by
                        if (name.isBlank() || by.isBlank()) null else by to name
                    }
                    total = artists.size + albums.size
                    // the most played first: they are the ones looked at
                    fun offline() = failures >= 5
                    for (a in artists) {
                        if (offline()) throw java.io.IOException("offline")
                        if (artist(app, a) != null) found++
                        done++
                        progress = "$done/$total"
                    }
                    for ((by, name) in albums) {
                        if (offline()) throw java.io.IOException("offline")
                        if (album(app, by, name) != null) found++
                        done++
                        progress = "$done/$total"
                    }
                    p.edit().putLong("music_info_at", System.currentTimeMillis()).apply()
                }
            } catch (e: Exception) {
                android.util.Log.w("OtthonMusic", "prefetch: $e")
            } finally {
                progress = if (total > 0) "$found of $total have a story" else progress
                p.edit().putString("music_info_result", progress).apply()
                prefetching = false
            }
        }, "otthon-music-info").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
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
            failures++
            return null   // not reachable now: not kept, asked again next time
        }
        failures = 0
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
        val text = a?.str("strBiography") ?: wiki("en", name, name, null, music)
            ?: a?.str("strBiographyHU") ?: wiki("hu", name, name, null, music) ?: ""
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
        val text = a?.str("strDescription") ?: wiki("en", "$name $artist album", name, artist, null)
            ?: a?.str("strDescriptionHU") ?: wiki("hu", "$name $artist album", name, artist, null) ?: ""
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

package hu.otthon.launcher

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * The film and series library: where the files are (shares on the NAS, USB drives plugged into
 * the TV), what they are (read from their names, then looked up on TMDB), and how far each has
 * been watched. Kept in a small database on the TV (media.db); the posters and backdrops are kept
 * in the TV's cache.
 */
object MediaLib {

    // ---------- the sources ----------

    /**
     * A place to look for films and series: a Windows (SMB) share on a NAS, or every USB drive
     * plugged in. [kind] is "smb" or "usb". The password is kept with the other secrets on the TV.
     */
    data class Source(val id: String, val name: String, val kind: String, val host: String, val share: String, val path: String, val user: String) {
        val label get() = if (kind == "usb") "USB" else "\\\\$host\\$share" + (if (path.isBlank()) "" else "\\" + path.replace('/', '\\'))

        fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind)
            .put("host", host).put("share", share).put("path", path).put("user", user)

        companion object {
            fun from(o: JSONObject): Source? {
                val id = o.optString("id").ifBlank { return null }
                return Source(id, o.optString("name"), o.optString("kind", "smb"), o.optString("host").trim(),
                    o.optString("share").trim(), o.optString("path").trim().trim('/', '\\'), o.optString("user"))
            }
        }
    }

    /**
     * A network path as typed: \\\\NAS\\Filmek\\4K, //NAS/Filmek, smb://NAS/Filmek/4K or NAS/Filmek.
     * The host, the share, and the folder inside it; null when there is no share in it.
     */
    fun parsePath(input: String): Triple<String, String, String>? {
        val t = input.trim().removePrefix("smb:").replace('\\', '/').trim('/')
        val parts = t.split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        return Triple(parts[0], parts[1], parts.drop(2).joinToString("/"))
    }

    /** Can the share be read with these? null when it can, else why not, in a few words. Background thread. */
    fun test(ctx: Context, src: Source, password: String): String? = try {
        val client = smbClient()
        client.connect(src.host).use { conn ->
            val session = conn.authenticate(AuthenticationContext(src.user, password.toCharArray(), ""))
            (session.connectShare(src.share) as DiskShare).use { share -> share.list(src.path.replace('/', '\\')) }
            session.close()
        }
        client.close()
        null
    } catch (e: Exception) {
        val m = e.toString()
        when {
            m.contains("LOGON_FAILURE", true) || m.contains("ACCESS_DENIED", true) -> "Wrong user name or password"
            m.contains("BAD_NETWORK_NAME", true) -> "No share named ${src.share}"
            m.contains("OBJECT_NAME_NOT_FOUND", true) || m.contains("OBJECT_PATH_NOT_FOUND", true) -> "No folder ${src.path}"
            else -> "Can't reach ${src.host}"
        }
    }

    private const val SOURCES = "media_sources"

    fun sources(ctx: Context): List<Source> = try {
        val a = JSONArray(Prefs.of(ctx).getString(SOURCES, "[]"))
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Source::from) }
    } catch (e: Exception) { emptyList() }

    fun saveSources(ctx: Context, list: List<Source>) {
        Prefs.of(ctx).edit().putString(SOURCES, JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    fun password(ctx: Context, id: String): String = Secrets.get(ctx, "media_pw_$id")
    fun setPassword(ctx: Context, id: String, pw: String) = Secrets.put(ctx, "media_pw_$id", pw)

    fun tmdbKey(ctx: Context): String = Secrets.get(ctx, "tmdb_key")
    fun setTmdbKey(ctx: Context, key: String) = Secrets.put(ctx, "tmdb_key", key.trim())

    // ---------- what a file is, from its name (no Android here: tested) ----------

    val VIDEO = setOf("mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "webm", "wmv", "mpg", "mpeg")

    /** What the name says: a film (title, year) or an episode (series title, season, episode). */
    data class Guess(val series: Boolean, val title: String, val year: Int?, val season: Int?, val episode: Int?,
                     val uhd: Boolean, val hdr: Boolean, val dv: Boolean)

    private val EPISODE = Regex("(?i)\\bS(\\d{1,2})[ ._-]?E(\\d{1,3})\\b|\\b(\\d{1,2})x(\\d{2,3})\\b")
    private val YEAR = Regex("(?<![0-9])(19[2-9][0-9]|20[0-4][0-9])(?![0-9])")
    private val JUNK = Regex(
        "(?i)\\b(2160p|1080p|720p|480p|4k|uhd|hdr10\\+?|hdr|dv|dovi|dolby[ ._-]?vision|x264|x265|h\\.?264|h\\.?265|hevc|avc|" +
            "bluray|blu-ray|bdrip|brrip|remux|web[ ._-]?dl|webrip|web|hdtv|dvdrip|dvd|amzn|nf|dsnp|hmax|atvp|" +
            "aac|ac3|eac3|ddp?5\\.1|dts(-hd)?|truehd|atmos|10bit|8bit|proper|repack|extended|unrated|hun|eng|multi|" +
            "sub|subs|dual|imax)\\b.*"
    )

    fun guess(path: String): Guess {
        val file = path.replace('\\', '/').substringAfterLast('/')
        val base = file.substringBeforeLast('.')
        val folder = path.replace('\\', '/').substringBeforeLast('/', "").substringAfterLast('/')
        val low = base.lowercase()
        val uhd = Regex("(?i)2160p|\\b4k\\b|uhd").containsMatchIn(base)
        val dv = Regex("(?i)\\bdv\\b|dovi|dolby[ ._-]?vision").containsMatchIn(base)
        val hdr = dv || Regex("(?i)hdr").containsMatchIn(base)
        val ep = EPISODE.find(base)
        if (ep != null) {
            val season = (ep.groupValues[1].ifBlank { ep.groupValues[3] }).toInt()
            val episode = (ep.groupValues[2].ifBlank { ep.groupValues[4] }).toInt()
            var title = clean(base.substring(0, ep.range.first))
            // "S01E02.mkv" in a folder named after the series (or after its season)
            if (title.isBlank()) {
                val parent = path.replace('\\', '/').split('/').dropLast(1).reversed()
                    .firstOrNull { !Regex("(?i)^(season|évad|evad|s)[ ._-]?\\d+$").matches(it.trim()) } ?: ""
                title = clean(parent)
            }
            val year = YEAR.find(title)?.value?.toInt()
            return Guess(true, clean(title.replace(YEAR, "")), year, season, episode, uhd, hdr, dv)
        }
        // a film: the title runs up to the year (or to the first technical word)
        val y = YEAR.findAll(base).lastOrNull { it.range.first > 0 }
        var title = if (y != null) base.substring(0, y.range.first) else base
        title = clean(title)
        if (title.length < 2 && folder.isNotBlank()) title = clean(folder.replace(YEAR, ""))
        return Guess(false, title, y?.value?.toInt(), null, null, uhd, hdr, dv || low.contains(".dv."))
    }

    private fun clean(s: String): String = s
        .replace(Regex("\\[[^]]*]|\\{[^}]*}"), " ")
        .replace(Regex("[._]"), " ")
        .replace(JUNK, " ")
        .replace(Regex("[()\\-]+\\s*$"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '-', '(', ')')

    // ---------- the database ----------

    class Db(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "media.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE files (id INTEGER PRIMARY KEY, source TEXT, path TEXT, size INTEGER, mtime INTEGER, " +
                    "series INTEGER, title TEXT, year INTEGER, season INTEGER, episode INTEGER, uhd INTEGER, hdr INTEGER, dv INTEGER, " +
                    "tmdb INTEGER, added INTEGER, position INTEGER DEFAULT 0, duration INTEGER DEFAULT 0, watched INTEGER DEFAULT 0, " +
                    "played INTEGER DEFAULT 0, seen INTEGER DEFAULT 1, UNIQUE(source, path))"
            )
            db.execSQL(
                "CREATE TABLE titles (tmdb INTEGER, series INTEGER, title TEXT, original TEXT, overview TEXT, year INTEGER, " +
                    "rating REAL, genres TEXT, cast_ TEXT, poster TEXT, backdrop TEXT, runtime INTEGER, fetched INTEGER, " +
                    "PRIMARY KEY(tmdb, series))"
            )
            db.execSQL("CREATE TABLE episodes (show INTEGER, season INTEGER, episode INTEGER, name TEXT, overview TEXT, still TEXT, PRIMARY KEY(show, season, episode))")
            db.execSQL("CREATE TABLE misses (key TEXT PRIMARY KEY, at INTEGER)")
        }

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

        companion object {
            @Volatile private var one: Db? = null
            fun of(ctx: Context): Db = one ?: synchronized(this) { one ?: Db(ctx).also { one = it } }
        }
    }

    /** A film or a series, as shown in the library. */
    data class Title(
        val key: String, val series: Boolean, val tmdb: Long, val title: String, val original: String, val overview: String,
        val year: Int?, val rating: Double, val genres: String, val cast: String, val poster: String?, val backdrop: String?,
        val runtime: Int, val uhd: Boolean, val hdr: Boolean, val dv: Boolean, val added: Long, val played: Long,
        val position: Long, val duration: Long, val watched: Boolean, val files: Int,
    )

    /** One file: a film's file or an episode. */
    data class Item(
        val id: Long, val source: String, val path: String, val series: Boolean, val title: String, val season: Int?, val episode: Int?,
        val position: Long, val duration: Long, val watched: Boolean, val name: String?, val overview: String?, val still: String?,
    )

    /** Every film and series, newest first; a series is one title with its episodes counted. */
    fun titles(ctx: Context): List<Title> {
        val db = Db.of(ctx).readableDatabase
        val out = ArrayList<Title>()
        db.rawQuery(
            "SELECT f.series, COALESCE(f.tmdb, 0), MIN(f.title), MIN(f.year), MAX(f.uhd), MAX(f.hdr), MAX(f.dv), MAX(f.added), MAX(f.played), " +
                "MAX(CASE WHEN f.position > 0 AND f.watched = 0 THEN f.position ELSE 0 END), MAX(f.duration), MIN(f.watched), COUNT(*), " +
                "t.title, t.original, t.overview, t.year, t.rating, t.genres, t.cast_, t.poster, t.backdrop, t.runtime " +
                "FROM files f LEFT JOIN titles t ON t.tmdb = f.tmdb AND t.series = f.series WHERE f.seen = 1 " +
                "GROUP BY f.series, CASE WHEN f.tmdb IS NULL THEN f.title ELSE f.tmdb END ORDER BY MAX(f.added) DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                val series = c.getInt(0) == 1
                val tmdb = c.getLong(1)
                val fileTitle = c.getString(2) ?: ""
                out.add(Title(
                    key = (if (series) "s:" else "m:") + (if (tmdb > 0) tmdb.toString() else fileTitle),
                    series = series, tmdb = tmdb,
                    title = c.getString(13) ?: fileTitle, original = c.getString(14) ?: "", overview = c.getString(15) ?: "",
                    year = if (!c.isNull(16)) c.getInt(16) else if (!c.isNull(3)) c.getInt(3) else null,
                    rating = if (c.isNull(17)) 0.0 else c.getDouble(17), genres = c.getString(18) ?: "", cast = c.getString(19) ?: "",
                    poster = c.getString(20), backdrop = c.getString(21), runtime = if (c.isNull(22)) 0 else c.getInt(22),
                    uhd = c.getInt(4) == 1, hdr = c.getInt(5) == 1, dv = c.getInt(6) == 1, added = c.getLong(7), played = c.getLong(8),
                    position = c.getLong(9), duration = c.getLong(10), watched = c.getInt(11) == 1, files = c.getInt(12),
                ))
            }
        }
        return out
    }

    /** The files of a title: a film's file(s), or a series' episodes in order. */
    fun items(ctx: Context, t: Title): List<Item> {
        val db = Db.of(ctx).readableDatabase
        val where = if (t.tmdb > 0) "f.tmdb = ? AND f.series = ?" else "f.title = ? AND f.series = ?"
        val arg = if (t.tmdb > 0) t.tmdb.toString() else t.title
        val out = ArrayList<Item>()
        db.rawQuery(
            "SELECT f.id, f.source, f.path, f.series, f.title, f.season, f.episode, f.position, f.duration, f.watched, e.name, e.overview, e.still " +
                "FROM files f LEFT JOIN episodes e ON e.show = f.tmdb AND e.season = f.season AND e.episode = f.episode " +
                "WHERE $where AND f.seen = 1 ORDER BY f.season, f.episode, f.path", arrayOf(arg, if (t.series) "1" else "0")
        ).use { c ->
            while (c.moveToNext()) out.add(Item(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getString(4) ?: "",
                if (c.isNull(5)) null else c.getInt(5), if (c.isNull(6)) null else c.getInt(6), c.getLong(7), c.getLong(8), c.getInt(9) == 1,
                c.getString(10), c.getString(11), c.getString(12)))
        }
        return out
    }

    fun item(ctx: Context, id: Long): Item? = Db.of(ctx).readableDatabase.rawQuery(
        "SELECT id, source, path, series, title, season, episode, position, duration, watched FROM files WHERE id = ?", arrayOf(id.toString())
    ).use { c ->
        if (!c.moveToFirst()) null else Item(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getString(4) ?: "",
            if (c.isNull(5)) null else c.getInt(5), if (c.isNull(6)) null else c.getInt(6), c.getLong(7), c.getLong(8), c.getInt(9) == 1, null, null, null)
    }

    /** Where playback got to; near the end counts as watched. */
    fun savePosition(ctx: Context, id: Long, position: Long, duration: Long) {
        val watched = duration > 0 && position > duration * 0.92
        Db.of(ctx).writableDatabase.update("files", ContentValues().apply {
            put("position", if (watched) 0 else position)
            put("duration", duration)
            put("played", System.currentTimeMillis())
            if (watched) put("watched", 1)
        }, "id = ?", arrayOf(id.toString()))
    }

    fun setWatched(ctx: Context, items: List<Item>, watched: Boolean) {
        val db = Db.of(ctx).writableDatabase
        for (i in items) db.update("files", ContentValues().apply { put("watched", if (watched) 1 else 0); put("position", 0) }, "id = ?", arrayOf(i.id.toString()))
    }

    // ---------- reading the sources ----------

    @Volatile var scanning = false
        private set
    @Volatile var progress = ""
        private set

    /** Looks through every source; new files are added, files gone are hidden. Background thread. */
    fun scan(ctx: Context): String {
        if (scanning) return "already running"
        scanning = true
        val app = ctx.applicationContext
        try {
            val db = Db.of(app).writableDatabase
            var found = 0
            var added = 0
            val errors = ArrayList<String>()
            for (src in sources(app)) {
                progress = src.name
                val files = ArrayList<Triple<String, Long, Long>>()   // path, size, mtime
                try {
                    if (src.kind == "usb") usbFiles(app, files) else smbFiles(app, src, files)
                } catch (e: Exception) {
                    errors.add("${src.name}: ${e.message ?: e.javaClass.simpleName}")
                    continue
                }
                db.beginTransaction()
                try {
                    val cv = ContentValues().apply { put("seen", 0) }
                    db.update("files", cv, "source = ?", arrayOf(src.id))
                    for ((path, size, mtime) in files) {
                        found++
                        val known = db.rawQuery("SELECT id FROM files WHERE source = ? AND path = ?", arrayOf(src.id, path)).use { it.moveToFirst() }
                        if (known) {
                            db.update("files", ContentValues().apply { put("seen", 1); put("size", size); put("mtime", mtime) },
                                "source = ? AND path = ?", arrayOf(src.id, path))
                        } else {
                            val g = guess(path)
                            db.insert("files", null, ContentValues().apply {
                                put("source", src.id); put("path", path); put("size", size); put("mtime", mtime)
                                put("series", if (g.series) 1 else 0); put("title", g.title); g.year?.let { put("year", it) }
                                g.season?.let { put("season", it) }; g.episode?.let { put("episode", it) }
                                put("uhd", if (g.uhd) 1 else 0); put("hdr", if (g.hdr) 1 else 0); put("dv", if (g.dv) 1 else 0)
                                put("added", if (mtime > 0) mtime else System.currentTimeMillis()); put("seen", 1)
                            })
                            added++
                        }
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            progress = "TMDB"
            val looked = enrich(app)
            Prefs.of(app).edit().putLong("media_scanned_at", System.currentTimeMillis()).apply()
            return buildString {
                append("$found files, $added new")
                if (looked > 0) append(", $looked looked up")
                if (errors.isNotEmpty()) append(" · ").append(errors.joinToString("; "))
            }
        } finally {
            scanning = false
            progress = ""
        }
    }

    private fun smbClient() = SMBClient(SmbConfig.builder().withTimeout(20, TimeUnit.SECONDS).withSoTimeout(30, TimeUnit.SECONDS)
        .withSecurityProvider(com.hierynomus.security.bc.BCSecurityProvider()).build())

    /** A connection to a source's share, for reading its files (the player keeps one open while it plays). */
    class SmbShare(val client: SMBClient, val connection: Connection, val session: Session, val share: DiskShare) : java.io.Closeable {
        override fun close() {
            try { share.close() } catch (e: Exception) { }
            try { session.close() } catch (e: Exception) { }
            try { connection.close() } catch (e: Exception) { }
            try { client.close() } catch (e: Exception) { }
        }
    }

    fun openShare(ctx: Context, src: Source): SmbShare {
        val client = smbClient()
        val conn = client.connect(src.host)
        val auth = AuthenticationContext(src.user, password(ctx, src.id).toCharArray(), "")
        val session = conn.authenticate(auth)
        val share = session.connectShare(src.share) as DiskShare
        return SmbShare(client, conn, session, share)
    }

    private fun smbFiles(ctx: Context, src: Source, out: MutableList<Triple<String, Long, Long>>) {
        openShare(ctx, src).use { s ->
            fun walk(dir: String, depth: Int) {
                if (depth > 6 || out.size > 20000) return
                for (f in s.share.list(dir)) {
                    val name = f.fileName
                    if (name == "." || name == ".." || name.startsWith(".") || name.startsWith("@") || name.startsWith("#")) continue
                    val full = if (dir.isEmpty()) name else "$dir\\$name"
                    val isDir = (f.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                    if (isDir) walk(full, depth + 1)
                    else if (name.substringAfterLast('.', "").lowercase() in VIDEO && !name.lowercase().contains("sample")) {
                        out.add(Triple(full, f.endOfFile, f.lastWriteTime.toEpochMillis()))
                    }
                }
            }
            walk(src.path.replace('/', '\\'), 0)
        }
    }

    /** The USB drives plugged into the TV (removable volumes); needs the "all files" permission. */
    fun usbRoots(ctx: Context): List<File> {
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
        val roots = ArrayList<File>()
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            for (v in sm.storageVolumes) if (v.isRemovable) v.directory?.let { roots.add(it) }
        } else {
            File("/storage").listFiles()?.forEach { if (it.name != "emulated" && it.name != "self" && it.canRead()) roots.add(it) }
        }
        return roots
    }

    private fun usbFiles(ctx: Context, out: MutableList<Triple<String, Long, Long>>) {
        fun walk(dir: File, depth: Int) {
            if (depth > 6 || out.size > 20000) return
            for (f in dir.listFiles() ?: return) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) walk(f, depth + 1)
                else if (f.extension.lowercase() in VIDEO && !f.name.lowercase().contains("sample")) out.add(Triple(f.absolutePath, f.length(), f.lastModified()))
            }
        }
        for (r in usbRoots(ctx)) walk(r, 0)
    }

    // ---------- TMDB ----------

    private const val API = "https://api.themoviedb.org/3"
    const val IMAGES = "https://image.tmdb.org/t/p/"

    private fun tmdb(ctx: Context, path: String, params: Map<String, String>): JSONObject? {
        val key = tmdbKey(ctx).ifBlank { return null }
        val bearer = key.length > 40
        val q = params.entries.joinToString("&") { "${it.key}=" + URLEncoder.encode(it.value, "UTF-8") } +
            (if (bearer) "" else "&api_key=$key")
        val c = URL("$API$path?$q").openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8000
            c.readTimeout = 15000
            if (bearer) c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode != 200) null else JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }

    /** Is the key right? A short answer for the settings page. */
    fun checkKey(ctx: Context): String = try {
        if (tmdb(ctx, "/configuration", emptyMap()) != null) "OK" else "The key was not accepted"
    } catch (e: Exception) { e.message ?: "TMDB not reachable" }

    /** Looks up the titles not known yet (and refreshes ones older than a month). Background thread. */
    fun enrich(ctx: Context): Int {
        if (tmdbKey(ctx).isBlank()) return 0
        val db = Db.of(ctx).writableDatabase
        val lang = if (Lang.hu) "hu-HU" else "en-US"
        val todo = ArrayList<Triple<Boolean, String, Int?>>()
        db.rawQuery("SELECT DISTINCT series, title, year FROM files WHERE tmdb IS NULL AND seen = 1", null).use { c ->
            while (c.moveToNext()) todo.add(Triple(c.getInt(0) == 1, c.getString(1) ?: "", if (c.isNull(2)) null else c.getInt(2)))
        }
        var done = 0
        for ((series, title, year) in todo) {
            if (title.isBlank()) continue
            val missKey = "${if (series) "s" else "m"}|$title|$year"
            val missed = db.rawQuery("SELECT at FROM misses WHERE key = ?", arrayOf(missKey)).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            if (System.currentTimeMillis() - missed < 3 * 86_400_000L) continue
            val id = try { find(ctx, series, title, year, lang) } catch (e: Exception) { null }
            if (id == null) {
                db.insertWithOnConflict("misses", null, ContentValues().apply { put("key", missKey); put("at", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE)
                continue
            }
            try { details(ctx, series, id, lang) } catch (e: Exception) { continue }
            db.update("files", ContentValues().apply { put("tmdb", id) }, "series = ? AND title = ? AND tmdb IS NULL",
                arrayOf(if (series) "1" else "0", title))
            done++
        }
        return done
    }

    private fun find(ctx: Context, series: Boolean, title: String, year: Int?, lang: String): Long? {
        val params = HashMap<String, String>()
        params["query"] = title
        params["language"] = lang
        if (year != null) params[if (series) "first_air_date_year" else "year"] = year.toString()
        var r = tmdb(ctx, if (series) "/search/tv" else "/search/movie", params)?.optJSONArray("results")
        if ((r == null || r.length() == 0) && year != null) {
            params.remove("year"); params.remove("first_air_date_year")
            r = tmdb(ctx, if (series) "/search/tv" else "/search/movie", params)?.optJSONArray("results")
        }
        return r?.optJSONObject(0)?.optLong("id")?.takeIf { it > 0 }
    }

    private fun details(ctx: Context, series: Boolean, id: Long, lang: String) {
        val j = tmdb(ctx, (if (series) "/tv/" else "/movie/") + id, mapOf("language" to lang, "append_to_response" to "credits")) ?: return
        // an empty Hungarian overview: the English one
        var overview = j.optString("overview")
        if (overview.isBlank() && lang != "en-US") overview = tmdb(ctx, (if (series) "/tv/" else "/movie/") + id, mapOf("language" to "en-US"))?.optString("overview") ?: ""
        val genres = j.optJSONArray("genres")?.let { a -> (0 until a.length()).joinToString(", ") { a.optJSONObject(it).optString("name") } } ?: ""
        val cast = j.optJSONObject("credits")?.optJSONArray("cast")?.let { a -> (0 until minOf(8, a.length())).joinToString(", ") { a.optJSONObject(it).optString("name") } } ?: ""
        val date = j.optString(if (series) "first_air_date" else "release_date")
        val runtime = if (series) j.optJSONArray("episode_run_time")?.optInt(0) ?: 0 else j.optInt("runtime")
        Db.of(ctx).writableDatabase.insertWithOnConflict("titles", null, ContentValues().apply {
            put("tmdb", id); put("series", if (series) 1 else 0)
            put("title", j.optString(if (series) "name" else "title")); put("original", j.optString(if (series) "original_name" else "original_title"))
            put("overview", overview); date.take(4).toIntOrNull()?.let { put("year", it) }
            put("rating", j.optDouble("vote_average", 0.0)); put("genres", genres); put("cast_", cast)
            put("poster", j.optString("poster_path").ifBlank { null }); put("backdrop", j.optString("backdrop_path").ifBlank { null })
            put("runtime", runtime); put("fetched", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (series) {
            // the episodes' names and pictures, season by season (only the seasons there are files of)
            val seasons = Db.of(ctx).readableDatabase.rawQuery("SELECT DISTINCT season FROM files WHERE series = 1 AND (tmdb = ? OR tmdb IS NULL)", arrayOf(id.toString())).use { c ->
                val l = ArrayList<Int>(); while (c.moveToNext()) if (!c.isNull(0)) l.add(c.getInt(0)); l
            }
            for (s in seasons) {
                val sj = tmdb(ctx, "/tv/$id/season/$s", mapOf("language" to lang)) ?: continue
                val eps = sj.optJSONArray("episodes") ?: continue
                for (k in 0 until eps.length()) {
                    val e = eps.optJSONObject(k) ?: continue
                    Db.of(ctx).writableDatabase.insertWithOnConflict("episodes", null, ContentValues().apply {
                        put("show", id); put("season", s); put("episode", e.optInt("episode_number"))
                        put("name", e.optString("name")); put("overview", e.optString("overview")); put("still", e.optString("still_path").ifBlank { null })
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
        }
    }

    // ---------- pictures ----------

    private val memory = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > (if (LowRam.on) 40 else 120)
    }

    fun cached(path: String, size: String): Bitmap? = synchronized(memory) { memory["$size$path"] }

    /** A TMDB picture ([size]: w342, w780, w1280…), from memory, the TV's storage, or the internet. Background thread. */
    fun picture(ctx: Context, path: String, size: String): Bitmap? {
        val k = "$size$path"
        synchronized(memory) { memory[k] }?.let { return it }
        val dir = File(ctx.cacheDir, "media").apply { mkdirs() }
        val f = File(dir, size + "_" + path.trim('/').replace('/', '_'))
        if (!f.exists()) {
            val c = URL(IMAGES + size + path).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 8000
                c.readTimeout = 15000
                if (c.responseCode != 200) return null
                val tmp = File(f.path + ".tmp")
                c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                tmp.renameTo(f)
            } finally {
                c.disconnect()
            }
        }
        val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }) ?: return null
        f.setLastModified(System.currentTimeMillis())
        synchronized(memory) { memory[k] = b }
        return b
    }

    fun trim() = synchronized(memory) { memory.clear() }
}

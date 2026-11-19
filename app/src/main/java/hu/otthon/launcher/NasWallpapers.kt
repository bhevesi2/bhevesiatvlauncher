package hu.otthon.launcher

import android.content.Context
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** Where the wallpapers are: a Windows (SMB) share, e.g. \\192.168.2.2\NAS\Wallpapers. */
data class NasSource(val host: String, val share: String, val path: String, val user: String, val password: String) {
    val label get() = "\\\\$host\\$share" + (if (path.isBlank()) "" else "\\" + path.replace('/', '\\'))

    companion object {
        fun from(o: JSONObject?): NasSource? {
            if (o == null) return null
            val host = o.optString("host", "").trim()
            val share = o.optString("share", "").trim()
            if (host.isBlank() || share.isBlank()) return null
            return NasSource(host, share, o.optString("path", "").trim().trim('/', '\\'), o.optString("user", ""), o.optString("password", ""))
        }
    }
}

/**
 * The "Wallpapers (NAS)" picture set: the pictures in a folder of a Windows share, read straight
 * from the TV. Whatever is in the folder now is the set; pictures added or removed show up at
 * the next look (every half hour, or Settings › Background › Check the folder now).
 *
 * Each picture belongs to a part of the day by a word in its name or in its subfolder's name:
 * dawn (hajnal, dawn, sunrise, napkelte, reggel, morning), day (nappal, day, délelőtt, délután),
 * dusk (alkony, dusk, sunset, naplemente, este, evening) or night (éjszaka, éjjel, night).
 * Pictures with none of these suit any time. Pictures with karácsony, christmas, xmas or advent
 * in their name or folder are for Christmas mode only, and replace the Christmas illustrations.
 * The pictures of a part of the day take turns, a new one every hour (Next wallpaper skips one).
 *
 * Only the picture on screen is downloaded (and kept on the TV until it leaves the folder).
 */
object NasWallpapers {
    const val KEY = "nas"

    data class Pic(val path: String, val size: Long, val mtime: Long, val phase: String?, val xmas: Boolean)

    private const val LIST = "nas_list"
    private const val LIST_AT = "nas_list_at"
    private const val LIST_ERROR = "nas_list_error"
    private const val MAX = 600
    private val EXT = setOf("jpg", "jpeg", "png", "webp")

    @Volatile private var memo: List<Pic>? = null

    /** The pictures as last read (never reads the share). */
    fun pictures(ctx: Context): List<Pic> {
        memo?.let { return it }
        val arr = try { JSONArray(Prefs.of(ctx).getString(LIST, "[]")) } catch (e: Exception) { JSONArray() }
        val list = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Pic(o.getString("p"), o.optLong("s"), o.optLong("m"), o.optString("ph", "").ifBlank { null }, o.optBoolean("x"))
        }
        memo = list
        return list
    }

    fun lastError(ctx: Context): String? = Prefs.of(ctx).getString(LIST_ERROR, null)

    fun hasXmas(ctx: Context) = pictures(ctx).any { it.xmas }

    fun describe(ctx: Context): String {
        val p = pictures(ctx)
        if (p.isEmpty()) return lastError(ctx) ?: "No pictures read yet"
        val x = p.count { it.xmas }
        return "${p.size - x} pictures" + (if (x > 0) ", $x for Christmas" else "")
    }

    // ---------- which picture ----------

    /** The picture for [phase] now; in Christmas mode one of the Christmas pictures, if there are. */
    fun pick(ctx: Context, phase: Phase, festive: Boolean): Pic? {
        val all = pictures(ctx)
        if (all.isEmpty()) return null
        val pool: List<Pic> = if (festive && all.any { it.xmas }) {
            val x = all.filter { it.xmas }
            x.filter { it.phase == phase.key }.ifEmpty { x.filter { it.phase == null } }.ifEmpty { x }
        } else {
            val normal = all.filter { !it.xmas }
            normal.filter { it.phase == phase.key }.ifEmpty { normal.filter { it.phase == null } }.ifEmpty { normal }
        }
        if (pool.isEmpty()) return null
        val sorted = pool.sortedBy { it.path.lowercase() }
        return sorted[Backgrounds.turn(ctx, sorted.size)]
    }

    // ---------- reading the share ----------

    private fun plain(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    private val DAWN = listOf("hajnal", "dawn", "sunrise", "napkelte", "reggel", "morning")
    private val DUSK = listOf("alkony", "dusk", "sunset", "naplemente", "este", "evening")
    private val NIGHT = listOf("ejszaka", "ejjel", "night", "ej")
    private val DAY = listOf("nappal", "day", "daytime", "delelott", "delutan", "noon", "afternoon", "nap")
    private val XMAS = listOf("karacsony", "karacsonyi", "christmas", "xmas", "advent")

    /** The words of a path, letters only ("Hajnal_02.jpg" → hajnal, jpg). */
    private fun words(path: String) = plain(path).split(Regex("[^a-z]+")).filter { it.isNotBlank() }

    private fun classify(path: String): Pair<String?, Boolean> {
        val w = words(path)
        fun has(list: List<String>) = w.any { word -> list.any { k -> word == k || (k.length >= 4 && word.startsWith(k)) } }
        val xmas = has(XMAS)
        // the more particular words first: "napkelte" and "naplemente" are not "nap"
        val phase = when {
            has(DAWN) -> Phase.HAJNAL.key
            has(DUSK) -> Phase.ALKONY.key
            has(NIGHT) -> Phase.EJSZAKA.key
            has(DAY) -> Phase.NAPPAL.key
            else -> null
        }
        return phase to xmas
    }

    private fun client(): SMBClient = SMBClient(
        SmbConfig.builder()
            // MD4 and the rest of NTLM come from Bouncy Castle: Android has no MD4 of its own
            .withSecurityProvider(BCSecurityProvider())
            .withTimeout(20, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .build()
    )

    private fun <T> withShare(src: NasSource, work: (DiskShare) -> T): T {
        client().use { c ->
            val conn = c.connect(src.host)
            try {
                val session = conn.authenticate(AuthenticationContext(src.user, src.password.toCharArray(), ""))
                try {
                    val share = session.connectShare(src.share) as DiskShare
                    try {
                        return work(share)
                    } finally {
                        share.close()
                    }
                } finally {
                    session.close()
                }
            } finally {
                conn.close()
            }
        }
    }

    /** Reads the folder again when the last look is older than [maxAgeMs]. Call off the main thread. */
    fun refreshIfStale(ctx: Context, cfg: Config, maxAgeMs: Long = 30 * 60_000L): Boolean {
        val at = Prefs.of(ctx).getLong(LIST_AT, 0L)
        if (System.currentTimeMillis() - at < maxAgeMs && pictures(ctx).isNotEmpty()) return false
        return refresh(ctx, cfg)
    }

    /** Reads the folder (and up to two levels of subfolders). Call off the main thread. */
    @Synchronized
    fun refresh(ctx: Context, cfg: Config): Boolean {
        val src = cfg.nas ?: return false
        val found = ArrayList<Pic>()
        val error: String? = try {
            withShare(src) { share ->
                fun walk(dir: String, depth: Int) {
                    for (f in share.list(dir)) {
                        val name = f.fileName
                        if (name == "." || name == ".." || name.startsWith(".")) continue
                        val rel = if (dir.isBlank()) name else "$dir\\$name"
                        if ((f.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L) {
                            if (depth < 2) walk(rel, depth + 1)
                        } else if (name.substringAfterLast('.', "").lowercase() in EXT && found.size < MAX) {
                            val inSet = if (src.path.isBlank()) rel else rel.removePrefix(src.path.replace('/', '\\') + "\\")
                            val (phase, xmas) = classify(inSet)
                            found.add(Pic(rel, f.endOfFile, f.lastWriteTime.toEpochMillis(), phase, xmas))
                        }
                    }
                }
                walk(src.path.replace('/', '\\'), 0)
            }
            null
        } catch (e: Exception) {
            android.util.Log.w("OtthonNas", "could not read ${src.label}", e)
            when {
                e.toString().contains("LOGON_FAILURE", true) -> "Wrong user name or password"
                e.toString().contains("BAD_NETWORK_NAME", true) -> "No share named “${src.share}”"
                e.toString().contains("OBJECT_NAME_NOT_FOUND", true) || e.toString().contains("OBJECT_PATH_NOT_FOUND", true) -> "No folder “${src.path}”"
                else -> "Can't reach ${src.host}"
            }
        }
        val prefs = Prefs.of(ctx).edit()
        if (error != null) {
            prefs.putString(LIST_ERROR, error).putLong(LIST_AT, System.currentTimeMillis() - 25 * 60_000L).apply()
            return false
        }
        val arr = JSONArray()
        for (p in found) arr.put(JSONObject().put("p", p.path).put("s", p.size).put("m", p.mtime).put("ph", p.phase ?: "").put("x", p.xmas))
        prefs.putString(LIST, arr.toString()).putLong(LIST_AT, System.currentTimeMillis()).remove(LIST_ERROR).apply()
        memo = found
        tidy(ctx, found)
        return true
    }

    // ---------- the pictures on the TV ----------

    private fun dir(ctx: Context) = File(ctx.getExternalFilesDir(null), "nas").apply { mkdirs() }

    private fun local(ctx: Context, p: Pic) =
        File(dir(ctx), p.path.replace('\\', '_').replace('/', '_') + "." + p.mtime + "." + p.size)

    /** Pictures that left the folder (or changed) leave the TV too. */
    private fun tidy(ctx: Context, list: List<Pic>) {
        val keep = list.map { local(ctx, it).name }.toSet()
        dir(ctx).listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    /** The picture on the TV, downloaded first if it is not there yet. Call off the main thread. */
    fun file(ctx: Context, cfg: Config, p: Pic): File? {
        val f = local(ctx, p)
        if (f.exists() && f.length() == p.size) return f
        val src = cfg.nas ?: return null
        val part = File(f.path + ".part")
        return try {
            withShare(src) { share ->
                val remote = share.openFile(p.path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
                try {
                    remote.inputStream.use { input -> part.outputStream().use { input.copyTo(it, 256 * 1024) } }
                } finally {
                    remote.close()
                }
            }
            if (part.renameTo(f)) f else null
        } catch (e: Exception) {
            android.util.Log.w("OtthonNas", "could not download ${p.path}", e)
            part.delete()
            null
        }
    }
}

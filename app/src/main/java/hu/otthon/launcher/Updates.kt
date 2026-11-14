package hu.otthon.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime

/**
 * Updates from the project's GitHub releases. Every build's version code is the minute it was
 * built, so a release published well after the installed build is newer, and a build made on
 * the PC after the last release is not offered an older one.
 */
object Updates {
    private const val API = "https://api.github.com/repos/bhevesi2/bhevesiatvlauncher/releases/latest"

    data class Release(val name: String, val minute: Long, val apk: String)

    /** The newest release, or null if GitHub can't be reached. Background thread only. */
    fun latest(): Release? {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 10000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode != 200) return null
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            val assets = o.optJSONArray("assets") ?: return null
            var apk: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url")
            }
            val published = OffsetDateTime.parse(o.optString("published_at")).toInstant().toEpochMilli() / 60_000L
            return Release(o.optString("name").ifBlank { o.optString("tag_name") }, published, apk ?: return null)
        } finally {
            c.disconnect()
        }
    }

    fun installedMinute(ctx: Context): Long = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode.toLong()
    } catch (e: Exception) {
        0L
    }

    /** Newer than what is installed: published more than 15 minutes after this build was made. */
    fun isNewer(ctx: Context, r: Release): Boolean = r.minute > installedMinute(ctx) + 15

    /** Remembers the last check, so the home screen can mark the settings button. */
    fun remember(ctx: Context, r: Release?) {
        val e = Prefs.of(ctx).edit().putLong("update_checked", System.currentTimeMillis())
        if (r != null && isNewer(ctx, r)) e.putString("update_name", r.name) else e.remove("update_name")
        e.apply()
    }

    fun pending(ctx: Context): String? = Prefs.of(ctx).getString("update_name", null)

    fun checkedAgo(ctx: Context): Long = System.currentTimeMillis() - Prefs.of(ctx).getLong("update_checked", 0L)

    /** Downloads the release to the app's folder, where the TV's shell can read it. Background thread only. */
    fun download(ctx: Context, r: Release): File? {
        val dir = ctx.getExternalFilesDir(null) ?: return null
        val out = File(dir, "update.apk")
        val tmp = File(dir, "update.apk.part")
        var url = r.apk
        // GitHub answers with a redirect to its file storage, sometimes more than one
        repeat(5) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 8000
            c.readTimeout = 30000
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    url = c.getHeaderField("Location") ?: return null
                    return@repeat
                }
                if (code != 200) return null
                c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (tmp.length() < 100_000) return null
                out.delete()
                return if (tmp.renameTo(out)) out else null
            } finally {
                c.disconnect()
            }
        }
        return null
    }

    /**
     * Installs over the running launcher, then opens it again. The package manager may not read
     * the app's own folder on the shared storage, so the file goes through /data/local/tmp.
     */
    fun installCommand(ctx: Context, apk: File): String =
        "cp '${apk.absolutePath}' /data/local/tmp/otthon-update.apk && pm install -r /data/local/tmp/otthon-update.apk; " +
            "rm -f /data/local/tmp/otthon-update.apk; am start -n ${ctx.packageName}/.HomeActivity"

    /**
     * Checks GitHub and, when there is a newer build, downloads it and has Home Assistant
     * install it. For a start from outside ([UpdateReceiver]); background thread only.
     */
    fun updateNow(ctx: Context, force: Boolean = false): String {
        val r = latest() ?: return "GitHub not reachable"
        remember(ctx, r)
        if (!force && !isNewer(ctx, r)) return "up to date"
        val apk = download(ctx, r) ?: return "download failed"
        // the install replaces this app, so the call may end with the process; that is fine
        val res = Adb.runHere(Config.load(ctx), installCommand(ctx, apk), 90000)
        return Adb.explain(res)
    }
}

/**
 * Lets Home Assistant or a PC start an update over ADB, on every TV at once:
 *
 *     am broadcast -n hu.otthon.launcher/.UpdateReceiver -a hu.otthon.launcher.UPDATE
 *
 * Only the TV's own shell can send it: the receiver asks for the DUMP permission, which ADB's
 * shell has and apps cannot get. Add `--ez force true` to install the latest build even when it
 * is not newer.
 */
class UpdateReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "hu.otthon.launcher.UPDATE"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val force = intent.getBooleanExtra("force", false)
        val app = ctx.applicationContext
        val pending = goAsync()
        Thread {
            try {
                android.util.Log.i("OtthonUpdate", Updates.updateNow(app, force))
            } catch (e: Exception) {
                android.util.Log.w("OtthonUpdate", "update failed", e)
            } finally {
                try { pending.finish() } catch (e: Exception) { }
            }
        }.start()
    }
}

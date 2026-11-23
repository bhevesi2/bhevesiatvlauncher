package hu.otthon.launcher

import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.tv.TvContract
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The recommendations' pictures kept on the TV's own storage, made the size they are shown at:
 * a picture is downloaded and shrunk once, and after that it is read back from storage, even
 * after a restart. At most 30 MB; the pictures used longest ago go first, and anything unused
 * for a week goes. Once a day a kept picture is checked with its server (a "has it changed?"
 * question that costs nothing when it has not).
 */
object RecoDisk {
    private const val MAX_BYTES = 30L * 1024 * 1024
    private const val MAX_AGE = 7 * 24 * 3600_000L
    private const val RECHECK = 24 * 3600_000L
    private const val QUALITY = 92

    @Volatile private var root: File? = null
    @Volatile private var sweptAt = 0L

    /**
     * Widths are rounded up to steps of 128 pixels: the cards of one shape share a file, and a
     * picture is never kept smaller than it is shown.
     */
    fun bucket(width: Int): Int = ((width.coerceAtLeast(64) + 127) / 128) * 128

    private fun dir(ctx: Context): File =
        root ?: File(ctx.applicationContext.cacheDir, "reco").also { it.mkdirs(); root = it }

    fun hash(uri: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun file(ctx: Context, uri: String, bucket: Int) = File(dir(ctx), "${hash(uri)}_$bucket.jpg")
    private fun metaFile(ctx: Context, uri: String) = File(dir(ctx), "${hash(uri)}.meta")

    /** The kept picture, marked as just used; null when there is none or it can't be read. */
    fun read(ctx: Context, uri: String, bucket: Int): Bitmap? {
        val f = file(ctx, uri, bucket)
        if (!f.exists()) return null
        val b = try {
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
        } catch (e: Throwable) { null }
        if (b == null) { f.delete(); return null }
        f.setLastModified(System.currentTimeMillis())
        return b
    }

    fun has(ctx: Context, uri: String, bucket: Int) = file(ctx, uri, bucket).exists()

    /** What the server said about the picture last time: its tags and when it was asked. */
    class Meta(val etag: String?, val modified: String?, val checked: Long)

    fun meta(ctx: Context, uri: String): Meta? = try {
        val j = JSONObject(metaFile(ctx, uri).readText())
        Meta(j.optString("etag").ifBlank { null }, j.optString("modified").ifBlank { null }, j.optLong("checked"))
    } catch (e: Exception) { null }

    fun dueForCheck(ctx: Context, uri: String): Boolean =
        uri.startsWith("http") && (meta(ctx, uri)?.checked ?: 0L) < System.currentTimeMillis() - RECHECK

    fun checked(ctx: Context, uri: String, etag: String?, modified: String?) {
        try {
            val j = JSONObject().put("uri", uri).put("checked", System.currentTimeMillis())
            etag?.let { j.put("etag", it) }
            modified?.let { j.put("modified", it) }
            metaFile(ctx, uri).writeText(j.toString())
        } catch (e: Exception) { }
    }

    /** A new picture for [uri]: the sizes kept from the old one go, this one is written. */
    fun write(ctx: Context, uri: String, bucket: Int, b: Bitmap, fresh: Boolean) {
        val d = dir(ctx)
        if (fresh) {
            val prefix = hash(uri) + "_"
            d.listFiles()?.forEach { if (it.name.startsWith(prefix)) it.delete() }
        }
        val f = file(ctx, uri, bucket)
        // a name of its own: the same picture may be fetched on two threads at once
        val tmp = File(d, f.name + "." + Thread.currentThread().id + "." + System.nanoTime() + ".tmp")
        try {
            tmp.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            if (!tmp.renameTo(f)) tmp.delete()
        } catch (e: Exception) {
            tmp.delete()
        }
        sweepSoon(ctx)
    }

    /** At most once an hour: what is a week old goes, then the oldest until 30 MB is left. */
    private fun sweepSoon(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - sweptAt < 3600_000L) return
        sweptAt = now
        sweep(ctx)
    }

    fun sweep(ctx: Context) {
        val now = System.currentTimeMillis()
        val files = dir(ctx).listFiles() ?: return
        val pics = ArrayList<File>()
        for (f in files) {
            if (now - f.lastModified() > MAX_AGE && !f.name.endsWith(".meta")) { f.delete(); continue }
            if (f.name.endsWith(".jpg")) pics.add(f)
            if (f.name.endsWith(".tmp") && now - f.lastModified() > 600_000L) f.delete()
        }
        var total = pics.sumOf { it.length() }
        if (total > MAX_BYTES) {
            for (f in pics.sortedBy { it.lastModified() }) {
                if (total <= MAX_BYTES * 9 / 10) break
                total -= f.length()
                f.delete()
            }
        }
        // a server's notes about pictures no longer kept
        val kept = dir(ctx).list()?.filter { it.endsWith(".jpg") }?.map { it.substringBefore('_') }?.toHashSet() ?: return
        dir(ctx).listFiles()?.forEach { if (it.name.endsWith(".meta") && it.name.removeSuffix(".meta") !in kept) it.delete() }
    }

    // ---------- what the apps send ----------

    private val sizes = LinkedHashMap<String, String>()

    /** Notes the size an app's pictures come in (written to the log once per app and size). */
    fun noteSize(pkg: String, w: Int, h: Int, bytes: Int) {
        val s = "${w}x$h ${bytes / 1024} kB"
        synchronized(sizes) {
            if (sizes[pkg] == s) return
            sizes[pkg] = s
        }
        android.util.Log.i("OtthonReco", "$pkg sends pictures of $s")
    }

    /** The apps and the size of their pictures, for the TV's state in Home Assistant. */
    fun sizeReport(): String = synchronized(sizes) {
        sizes.entries.joinToString("; ") { (pkg, s) -> pkg.substringAfterLast('.') + " " + s }.take(240)
    }

    // ---------- fetching ----------

    /**
     * The picture from where the app keeps it, shrunk to [bucket] pixels wide and kept. With
     * [ask] the server is asked whether it changed since the kept one (null when it has not, and
     * nothing is downloaded). Background thread only.
     */
    fun fetch(ctx: Context, uri: String, bucket: Int, pkg: String, ask: Boolean): Bitmap? {
        var etag: String? = null
        var modified: String? = null
        val bytes: ByteArray = if (uri.startsWith("http")) {
            val c = URL(uri).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 5000
                c.readTimeout = 10000
                if (ask) meta(ctx, uri)?.let { m ->
                    m.etag?.let { c.setRequestProperty("If-None-Match", it) }
                    m.modified?.let { c.setRequestProperty("If-Modified-Since", it) }
                }
                val code = c.responseCode
                if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    val m = meta(ctx, uri)
                    checked(ctx, uri, m?.etag, m?.modified)
                    return null
                }
                if (code != 200) return null
                etag = c.getHeaderField("ETag")
                modified = c.getHeaderField("Last-Modified")
                c.inputStream.use { it.readBytes() }
            } finally {
                c.disconnect()
            }
        } else {
            ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() } ?: return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        if (pkg.isNotEmpty()) noteSize(pkg, bounds.outWidth, bounds.outHeight, bytes.size)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= bucket) sample *= 2
        val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        // shrunk smoothly to the width it is shown at (never made larger)
        val sized = if (full.width > bucket * 1.1f) {
            Bitmap.createScaledBitmap(full, bucket, (full.height * bucket.toFloat() / full.width).toInt().coerceAtLeast(1), true)
                .also { if (it !== full) full.recycle() }
        } else full
        write(ctx, uri, bucket, sized, fresh = true)
        if (uri.startsWith("http")) checked(ctx, uri, etag, modified)
        // half the memory of full colour on screen; posters have no transparency to lose
        val shown = sized.copy(Bitmap.Config.RGB_565, false) ?: sized
        if (shown !== sized) sized.recycle()
        return shown
    }
}

/**
 * Listens to the TV's listings: when an app publishes new recommendations, the rows read before
 * are no longer used, so the home screen reads them again next time it shows (and not when
 * nothing changed). Changes are gathered for 30 seconds, as apps write their rows in many small
 * steps. While the TV sleeps they are only gathered; while the home screen is in front, or the
 * TV is in a menu or the screensaver, the rows are read again and their pictures fetched ahead,
 * so they are there by the time the home screen shows. Never while something is being watched.
 */
object RecoWatch {
    /** Called on the main thread once the changes settled (the home screen reloads or fetches ahead). */
    var onSettled: (() -> Unit)? = null

    @Volatile private var registered = false
    private var waiting = false

    @Synchronized
    fun start(ctx: Context) {
        if (registered || !Reco.allowed(ctx)) return
        val app = ctx.applicationContext
        val obs = object : ContentObserver(Bg.main) {
            override fun onChange(selfChange: Boolean) = changed(app)
        }
        try {
            val cr = app.contentResolver
            cr.registerContentObserver(TvContract.PreviewPrograms.CONTENT_URI, true, obs)
            cr.registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, obs)
            cr.registerContentObserver(TvContract.Channels.CONTENT_URI, true, obs)
            registered = true
        } catch (e: Exception) {
            // not allowed on this TV: the rows are read each time, as before
            android.util.Log.w("OtthonReco", "can't listen to the listings: $e")
        }
    }

    val listening: Boolean get() = registered

    private fun changed(app: Context) {
        Reco.invalidate()
        if (waiting) return
        waiting = true
        Bg.main.postDelayed({
            waiting = false
            if (TvState.state(app) == "off") return@postDelayed
            try { onSettled?.invoke() } catch (e: Throwable) { CrashLog.note(e) }
        }, 30_000)
    }
}

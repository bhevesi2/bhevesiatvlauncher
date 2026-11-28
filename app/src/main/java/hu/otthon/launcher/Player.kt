package hu.otthon.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C as M3
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import java.io.RandomAccessFile
import java.util.EnumSet

/**
 * Reads a file of the library for the player: from a NAS share (otthon://<source>/<path>, through
 * the same SMB library as the wallpapers) or from a USB drive (file://). Reads in blocks of 1 MB
 * and asks for the next block while the player is still taking the current one, so a 4K film
 * does not wait on the network for every read. The NAS connection stays open between reads and
 * seeks (a seek opens the file again, not the share: no new login each time).
 */
class LibraryDataSource(private val ctx: Context) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var sourceId: String? = null
    private var src: MediaLib.Source? = null
    private var path: String? = null
    private var share: MediaLib.SmbShare? = null
    private var smbFile: com.hierynomus.smbj.share.File? = null
    private var local: RandomAccessFile? = null
    private var position = 0L
    private var remaining = 0L
    private var size = 0L
    private var block = ByteArray(BLOCK)
    private var spare = ByteArray(BLOCK)
    private var blockAt = -1L
    private var blockLen = 0
    /** The next block, being read while the current one is used. */
    private var ahead: java.util.concurrent.Future<Int>? = null
    private var aheadAt = -1L

    override fun open(spec: DataSpec): Long {
        transferInitializing(spec)
        uri = spec.uri
        val u = spec.uri
        if (u.scheme == "otthon") {
            val src = MediaLib.sources(ctx).firstOrNull { it.id == u.host } ?: throw java.io.IOException("No source ${u.host}")
            val path = Uri.decode(u.encodedPath?.removePrefix("/") ?: "")
            sourceId = src.id
            this.src = src
            this.path = path
            var s = SharePool.take(ctx, src)
            val f = try { openOn(s, path) } catch (e: Exception) {
                // the kept connection may have gone stale (the NAS slept): once more, on a new one
                s.close()
                s = MediaLib.openShare(ctx, src)
                openOn(s, path)
            }
            share = s
            smbFile = f
            size = f.fileInformation.standardInformation.endOfFile
        } else {
            val f = RandomAccessFile(u.path ?: throw java.io.IOException("No path"), "r")
            local = f
            size = f.length()
        }
        position = spec.position
        remaining = if (spec.length != M3.LENGTH_UNSET.toLong()) spec.length else size - spec.position
        if (remaining < 0) throw java.io.EOFException()
        blockAt = -1L
        transferStarted(spec)
        return remaining
    }

    private fun openOn(s: MediaLib.SmbShare, path: String) =
        s.share.openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)

    /**
     * Reads [block] from [at]. A read that fails (the NAS dropped the connection, a read timed
     * out) is tried again on a new connection, up to three times, before the player hears of it:
     * a short break in the network should not show at all.
     */
    private fun readNow(at: Long): Int {
        var tries = 0
        while (true) {
            try {
                return (smbFile ?: throw java.io.IOException("closed")).read(block, at, 0, block.size)
            } catch (e: Exception) {
                if (interrupted(e)) throw e   // the player gave up this read itself (a seek): no retry
                tries++
                PlayerLog.w(ctx, "read at $at failed (try $tries): $e" + (e.cause?.let { " / $it" } ?: ""))
                if (tries > 3) throw java.io.IOException("Can't read from the NAS", e)
                try { Thread.sleep(250L * tries) } catch (ie: InterruptedException) { throw e }
                try { reconnect() } catch (re: Exception) { PlayerLog.w(ctx, "reconnect failed: $re") }
            }
        }
    }

    private fun interrupted(e: Throwable): Boolean {
        if (Thread.currentThread().isInterrupted) return true
        var c: Throwable? = e
        while (c != null) { if (c is InterruptedException || c is java.io.InterruptedIOException && c !is java.net.SocketTimeoutException) return true; c = c.cause }
        return false
    }

    /** A new connection and the file opened again on it (the old one is not kept). */
    private fun reconnect() {
        try { smbFile?.close() } catch (e: Exception) { }
        smbFile = null
        try { share?.close() } catch (e: Exception) { }
        share = null
        val s = MediaLib.openShare(ctx, src ?: throw java.io.IOException("No source"))
        share = s
        smbFile = openOn(s, path ?: "")
        PlayerLog.w(ctx, "connected again")
    }

    /** Makes [block] hold the bytes from [at]: the block read ahead if it is that one, else read now. */
    private fun fill(at: Long): Int {
        val a = ahead
        ahead = null
        var got = -1
        if (a != null) {
            val ready = try { a.get() } catch (e: Exception) {
                PlayerLog.w(ctx, "read ahead at $aheadAt failed: ${e.cause ?: e}")
                -1
            }   // also waits for it to stop writing into spare
            if (aheadAt == at && ready > 0) {
                val t = block; block = spare; spare = t
                got = ready
            }
        }
        if (got <= 0) got = readNow(at)
        if (got <= 0) return got
        val f = smbFile ?: return got
        blockAt = at
        blockLen = got
        read.addAndGet(got.toLong())
        val next = at + got
        if (next < size) {
            aheadAt = next
            val buf = spare
            ahead = reader.submit<Int> { f.read(buf, next, 0, buf.size) }
        }
        return got
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return M3.RESULT_END_OF_INPUT
        val want = minOf(length.toLong(), remaining).toInt()
        val n: Int
        val f = smbFile
        if (f != null) {
            if (blockAt < 0 || position < blockAt || position >= blockAt + blockLen) {
                if (fill(position) <= 0) return M3.RESULT_END_OF_INPUT
            }
            val inBlock = (position - blockAt).toInt()
            n = minOf(want, blockLen - inBlock)
            System.arraycopy(block, inBlock, buffer, offset, n)
        } else {
            val l = local ?: return M3.RESULT_END_OF_INPUT
            l.seek(position)
            n = l.read(buffer, offset, want)
            if (n <= 0) return M3.RESULT_END_OF_INPUT
            read.addAndGet(n.toLong())
        }
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        // the block being read ahead finishes first: its file is closed next
        try { ahead?.get(5, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { }
        ahead = null
        try { smbFile?.close() } catch (e: Exception) { }
        // the share is kept for the next open (a seek, the next episode)
        share?.let { s -> sourceId?.let { id -> SharePool.give(id, s) } ?: s.close() }
        try { local?.close() } catch (e: Exception) { }
        smbFile = null
        share = null
        local = null
        if (uri != null) transferEnded()
        uri = null
    }

    companion object {
        private const val BLOCK = 1 shl 20
        private val reader = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "otthon-read-ahead").apply { isDaemon = true } }
        /** Bytes read from the files so far, for the speed shown while buffering. */
        val read = java.util.concurrent.atomic.AtomicLong()

        /** The address the player reads an item from. */
        fun uriOf(source: MediaLib.Source, path: String): Uri =
            if (source.kind == "usb") Uri.fromFile(java.io.File(path))
            else Uri.parse("otthon://${source.id}/" + Uri.encode(path, "\\"))
    }
}

/**
 * One open connection per NAS source in the player's process. Opening a share means a connection
 * and a login, which took the most time of a seek; now the file is opened on a kept share.
 */
object SharePool {
    private val idle = HashMap<String, MediaLib.SmbShare>()

    fun take(ctx: Context, src: MediaLib.Source): MediaLib.SmbShare {
        val kept = synchronized(this) { idle.remove(src.id) }
        if (kept != null) {
            if (try { kept.share.isConnected() } catch (e: Exception) { false }) return kept
            kept.close()
        }
        return MediaLib.openShare(ctx, src)
    }

    fun give(id: String, s: MediaLib.SmbShare) {
        val extra = synchronized(this) { if (idle.containsKey(id)) s else { idle[id] = s; null } }
        extra?.close()
    }

    fun closeAll() {
        val all = synchronized(this) { idle.values.toList().also { idle.clear() } }
        all.forEach { it.close() }
    }
}

/**
 * The library's player, in a process of its own (":player"): if a film is too much for the TV,
 * only the player closes, never the home screen. Dolby Vision, HDR10(+) and the TV's own
 * decoders come with Media3; sound that the TV cannot decode goes to the amplifier as it is.
 *
 * ◀ ▶ skip back 10 s / forward 30 s (held: faster; the presses add up and the film jumps once,
 * when they stop), OK pauses, ▲ sound and subtitles, ▼ shows where it is, Back leaves (where it got
 * to is kept, near the end counts as watched). After an episode the next one starts.
 *
 * While the film loads, a ring in the middle shows it, with how fast the NAS is read and how much
 * is ready. A network error is tried again (three times) from where it stopped. Where the TV has a
 * screen mode with the film's frame rate (24 Hz for a 23.976 fps film), it switches to it.
 *
 * A Dolby Vision decoder is given Dolby Vision only: the Sony's (MediaTek's) takes a plain film
 * too and never shows a picture. If the film still stops for good while loading (loaded but not
 * playing), the player starts again where it was, without the decoders it had.
 */
class PlayerActivity : Activity() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var bar: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var timeText: TextView
    private lateinit var badges: TextView
    private lateinit var progress: ProgressLine
    private lateinit var loading: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var bufferText: TextView
    private var queue = longArrayOf()
    /** Decoders not to use (they got stuck on this film), and how many times it started again so. */
    private var avoid = ArrayList<String>()
    private var recovered = 0
    private var videoDecoder: String? = null
    private var audioDecoder: String? = null
    /** Started again in this process: its end must not end the process. */
    private var handedOver = false
    /**
     * AVI read through the mending layer (AviMpeg4.kt). Not at first: Media3's own reader plays
     * XviD on most TVs (the TCL); only when the TV's decoder gets stuck on an AVI, and then on
     * that TV from the start, once it has helped there.
     */
    private var mend = false
    private var index = 0
    private var item: MediaLib.Item? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        queue = intent.getLongArrayExtra("queue") ?: longArrayOf(intent.getLongExtra("id", -1))
        avoid = intent.getStringArrayListExtra("avoid") ?: ArrayList()
        recovered = intent.getIntExtra("recovered", 0)
        mend = intent.getBooleanExtra("mend", Prefs.of(this).getBoolean("avi_mend", false))
        LowRam.init(this)
        index = intent.getIntExtra("index", 0).coerceIn(0, maxOf(0, queue.size - 1))

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        view = PlayerView(this).apply {
            useController = false
            setKeepContentOnPlayerReset(true)
            setShutterBackgroundColor(0xFF000000.toInt())
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48f), dp(28f), dp(48f), dp(28f))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0x00000000))
        }
        titleText = label("", 16f, C.TEXT, bold = true)
        badges = label("", 11f, 0xFFFAC775.toInt(), bold = true)
        progress = ProgressLine(this)
        timeText = label("", 11f, C.TEXT2)
        bar.addView(titleText)
        bar.addView(badges, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4f) })
        bar.addView(progress, LinearLayout.LayoutParams(-1, dp(6f)).apply { topMargin = dp(12f) })
        bar.addView(timeText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
        bufferText = label("", 10f, C.TEXT2)
        bar.addView(bufferText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2f) })
        bar.addView(label("OK pause · ◀ ▶ skip · ▲ sound and subtitles · Back leave", 10f, C.TEXT2),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4f) })
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        // loading: a turning ring in the middle, with the speed and what is ready
        loading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22f), dp(18f), dp(22f), dp(16f))
            background = rounded(0x99000000.toInt(), dp(16f).toFloat())
            visibility = View.INVISIBLE
        }
        loading.addView(Spinner(this), LinearLayout.LayoutParams(dp(44f), dp(44f)))
        loadingText = label("", 11f, C.TEXT).apply { gravity = Gravity.CENTER }
        loading.addView(loadingText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10f) })
        root.addView(loading, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)

        Mp4vExtractors.report = { m -> PlayerLog.w(applicationContext, m) }
        val skip = avoid.toSet()
        val selector = MediaCodecSelector { mime, secure, tunneling ->
            val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
            // the TV's own decoders first (Android's software MPEG-4 decoder cannot do XviD/DivX: Advanced
            // Simple Profile comes out blocky); AVI's MPEG-4 is mended for them (see AviMpeg4.kt)
            val fit = all.filter { c -> c.name !in skip && (mime == MimeTypes.VIDEO_DOLBY_VISION || !dolbyVisionDecoder(c.name)) }
            fit.ifEmpty { all.filter { it.name !in skip }.ifEmpty { all } }
        }
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true).setMediaCodecSelector(selector)
        // a bigger buffer than the default for films from the NAS, as much as the player's memory allows,
        // and less on the TVs with less memory (the home screen must stay alive behind the film)
        val heap = Runtime.getRuntime().maxMemory()
        val most = when { LowRam.lite -> 64L shl 20; LowRam.on -> 110L shl 20; else -> 200L shl 20 }
        val bytes = (heap * 0.45).toLong().coerceIn(32L shl 20, most).toInt()
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 2_000, 4_000)
            .setTargetBufferBytes(bytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        PlayerLog.w(this, "start: buffer ${bytes shr 20} MB of ${heap shr 20} MB heap")
        player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderers)
            .setLoadControl(load)
            .setReleaseTimeoutMs(2_000)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DataSource.Factory { LibraryDataSource(applicationContext) },
                if (mend) Mp4vExtractors else androidx.media3.extractor.DefaultExtractorsFactory()))
            .build()
        view.player = player
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                videoDecoder = decoderName
                PlayerLog.w(this@PlayerActivity, "video decoder $decoderName")
            }
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                audioDecoder = decoderName
                PlayerLog.w(this@PlayerActivity, "audio decoder $decoderName")
            }
        })
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) ended()
                if (state == Player.STATE_READY) {
                    retries = 0
                    // the mending made this TV's decoder play an AVI: from now on, at once
                    if (mend && recovered > 0 && !Prefs.of(this@PlayerActivity).getBoolean("avi_mend", false)) {
                        Prefs.of(this@PlayerActivity).edit().putBoolean("avi_mend", true).apply()
                        PlayerLog.w(this@PlayerActivity, "the mended AVI plays: kept for this TV")
                    }
                }
                if (state == Player.STATE_BUFFERING) { bufferingSince = android.os.SystemClock.elapsedRealtime(); readAtBuffering = LibraryDataSource.read.get() }
                else bufferingSince = 0L
                loadingSoon(state == Player.STATE_BUFFERING)
                tick()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) { save(); show() } else hideSoon()
            }
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.w("OtthonPlayer", "can't play (${error.errorCodeName})", error)
                PlayerLog.w(this@PlayerActivity, "player error ${error.errorCodeName}: ${error.cause}" + (error.cause?.cause?.let { " / $it" } ?: ""))
                // the network (2000–2999): again from where it stopped; the first time at once and
                // without a word (a blink in the network should not show), then with the message
                if (error.errorCode in 2000..2999 && retries < 5) {
                    retries++
                    if (retries >= 2) {
                        loadingText.text = Lang.tr("Connecting again…")
                        loading.visibility = View.VISIBLE
                    }
                    main.postDelayed({ if (!isFinishing) player.prepare() }, if (retries == 1) 150L else 1000L * retries)
                    return
                }
                loading.visibility = View.INVISIBLE
                // 3003: a kind of file the player has no reader for (WMV, RealMedia…)
                val ext = item?.path?.substringAfterLast('.', "")?.lowercase() ?: ""
                titleText.text = if (error.errorCode == 3003) Lang.tr("This kind of file can't be played") + (if (ext.isNotBlank()) " (.$ext)" else "")
                    else Lang.tr("Can't play this file") + " (" + error.errorCodeName + ")"
                show()
            }
            override fun onTracksChanged(tracks: Tracks) { describe(tracks); matchFrameRate(tracks) }
        })
        start(intent.getLongExtra("start", -1L))
        main.post(ticker)
    }

    /** Plays queue[index]; [startAt] -1 means where it got to last time. */
    private fun start(startAt: Long) {
        seekTarget = -1L
        val id = queue.getOrNull(index) ?: return finish()
        val it = MediaLib.item(this, id) ?: return finish()
        item = it
        val src = MediaLib.sources(this).firstOrNull { s -> s.id == it.source } ?: return finish()
        val uri = LibraryDataSource.uriOf(src, it.path)
        titleText.text = if (it.series && it.season != null) "${it.title} · S${it.season}E${it.episode}" else it.title
        show()
        // subtitle files next to the film with the same name (.srt, .hu.srt, .ass, .vtt): looked for first
        Bg.slow({ sidecars(src, it.path) }) { found ->
            if (isFinishing || item?.id != it.id) return@slow
            val subs = (found ?: emptyList()).map { (path, mime) ->
                MediaItem.SubtitleConfiguration.Builder(LibraryDataSource.uriOf(src, path)).setMimeType(mime)
                    .setLanguage(if (path.lowercase().contains(".hu.") || path.lowercase().contains(".hun.")) "hu" else null)
                    .setLabel(path.substringAfterLast('\\').substringAfterLast('/')).build()
            }
            val media = MediaItem.Builder().setUri(uri).setSubtitleConfigurations(subs).build()
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguages("hu", "en").setPreferredTextLanguages("hu").build()
            val at = if (startAt >= 0) startAt else if (!it.watched && it.position > 30_000) it.position else 0L
            player.setMediaItem(media, at)
            player.prepare()
            player.playWhenReady = true
        }
    }

    /** The subtitle files beside [path] that start with its name. Background thread. */
    private fun sidecars(src: MediaLib.Source, path: String): List<Pair<String, String>> {
        val sep = if (src.kind == "usb") '/' else '\\'
        val dir = path.substringBeforeLast(sep, "")
        val base = path.substringAfterLast(sep).substringBeforeLast('.').lowercase()
        val names: List<String> = try {
            if (src.kind == "usb") java.io.File(dir).list()?.toList() ?: emptyList()
            else MediaLib.openShare(this, src).use { s -> s.share.list(dir).map { f -> f.fileName } }
        } catch (e: Exception) { emptyList() }
        return names.filter { n -> n.lowercase().startsWith(base) }.mapNotNull { n ->
            val mime = when (n.substringAfterLast('.').lowercase()) {
                "srt" -> MimeTypes.APPLICATION_SUBRIP
                "ass", "ssa" -> MimeTypes.TEXT_SSA
                "vtt" -> MimeTypes.TEXT_VTT
                else -> null
            } ?: return@mapNotNull null
            Pair(if (dir.isEmpty()) n else "$dir$sep$n", mime)
        }
    }
    private fun ended() {
        item?.let { store(it.id, player.duration, player.duration) }
        if (index + 1 < queue.size) { index++; start(0L) } else finish()
    }

    private fun save() {
        val it = item ?: return
        val d = player.duration
        if (d > 0) store(it.id, player.currentPosition, d)
    }

    /**
     * Where it got to, written on a thread of its own (one at a time, in order): the database is
     * shared with the launcher, a scan there may hold it a moment, and that must never stop the film.
     */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun store(id: Long, position: Long, duration: Long) {
        val app = applicationContext
        try {
            writer.execute {
                try { MediaLib.savePosition(app, id, position, duration) } catch (e: Exception) { PlayerLog.w(app, "can't save the position: $e") }
            }
        } catch (e: Exception) { }
    }

    private var lastSave = 0L
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            val now = System.currentTimeMillis()
            if (player.isPlaying && now - lastSave > 10_000) { lastSave = now; save() }
            main.postDelayed(this, 500)
        }
    }

    private var lastRead = 0L
    private var lastReadAt = 0L
    private var speed = 0.0   // MB/s, smoothed

    private fun tick() {
        val now = android.os.SystemClock.elapsedRealtime()
        val r = LibraryDataSource.read.get()
        if (lastReadAt > 0 && now > lastReadAt) {
            val mbs = (r - lastRead) / 1048576.0 / ((now - lastReadAt) / 1000.0)
            speed = if (speed == 0.0) mbs else speed * 0.6 + mbs * 0.4
        }
        lastRead = r
        lastReadAt = now
        val d = player.duration
        val p = if (seekTarget >= 0) seekTarget else player.currentPosition
        val ahead = ((player.bufferedPosition - player.currentPosition) / 1000).coerceAtLeast(0)
        val net = String.format("%.1f MB/s", speed)
        if (d > 0) {
            progress.set(p.toFloat() / d, player.bufferedPosition.toFloat() / d)
            val ends = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(System.currentTimeMillis() + (d - p)))
            timeText.text = if (seekTarget >= 0) {
                val by = seekTarget - player.currentPosition
                "→ ${clock(p)} / ${clock(d)}   ·   " + (if (by >= 0) "+" else "−") + clock(Math.abs(by))
            } else "${clock(p)} / ${clock(d)}   ·   " + Lang.tr("${clock(d - p)} left") + "   ·   " + Lang.tr("ends at $ends")
        }
        bufferText.text = Lang.tr("Ready ahead: $ahead s") + "   ·   $net"
        watch(now)
        if (loading.visibility == View.VISIBLE && retries < 2) {
            loadingText.text = Lang.tr("Loading…") + "\n$net"
        }
    }

    // ---------- a film that stops for good while loading ----------

    private var bufferingSince = 0L
    private var readAtBuffering = 0L

    /**
     * Loading that no longer loads (the player has stopped asking for data) for 12 s, or 30 s of
     * loading with 40 MB read and still no picture: a decoder that takes the film and gives
     * nothing back. Started again where it was, without that decoder (the second time without the
     * sound decoder too); twice at most, then the message.
     */
    private fun watch(now: Long) {
        if (bufferingSince == 0L || seekTarget >= 0 || !player.playWhenReady || handedOver) return
        val waited = now - bufferingSince
        val read = LibraryDataSource.read.get() - readAtBuffering
        val stuck = (waited > 12_000 && !player.isLoading) || (waited > 30_000 && read > (40L shl 20))
        if (!stuck) return
        PlayerLog.w(this, "stuck loading ${waited / 1000} s, ${read shr 20} MB read, loading ${player.isLoading}; video $videoDecoder, audio $audioDecoder")
        // what the video decoder was given and gave back
        player.videoDecoderCounters?.let { c ->
            PlayerLog.w(this, "video decoder: ${c.decoderInitCount} started, ${c.queuedInputBufferCount} frames in, ${c.renderedOutputBufferCount} shown, " +
                "${c.droppedBufferCount} dropped, ${c.skippedOutputBufferCount} skipped, ${c.skippedInputBufferCount} input skipped; format ${player.videoFormat?.let { "${it.sampleMimeType} ${it.codecs} ${it.width}x${it.height} ${it.frameRate}" }}")
        }
        if (recovered >= 3) {
            bufferingSince = 0L
            loading.visibility = View.INVISIBLE
            titleText.text = Lang.tr("This TV can't play this file")
            show()
            return
        }
        val avi = item?.path?.lowercase()?.let { it.endsWith(".avi") || it.endsWith(".divx") } == true
        val next = ArrayList(avoid)
        // an AVI on the TV's own decoder: first the same decoder with the mended stream, then another decoder
        val mendNext = mend || avi
        if (mend || !avi) {
            videoDecoder?.let { if (it !in next) next.add(it) }
            // the sound decoder too, the second time (not for an AVI: there the steps are the mending, then the picture's decoder)
            if (!avi && recovered >= 1) audioDecoder?.let { if (it !in next) next.add(it) }
        }
        save()
        handedOver = true
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra("queue", queue).putExtra("index", index).putExtra("start", player.currentPosition)
            .putStringArrayListExtra("avoid", next).putExtra("recovered", recovered + 1).putExtra("mend", mendNext))
        overridePendingTransition(0, 0)
        finish()
    }

    private fun dolbyVisionDecoder(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("dolby.vision") || n.contains(".dvhe") || n.contains(".dvav") || n.contains(".dvh1") || n.contains("dovi")
    }

    // ---------- loading ----------

    private var retries = 0
    private val showLoading = Runnable { if (player.playbackState == Player.STATE_BUFFERING) { loading.visibility = View.VISIBLE; tick() } }

    /** The ring comes after a moment of loading (a short wait shows nothing) and goes at once. */
    private fun loadingSoon(buffering: Boolean) {
        main.removeCallbacks(showLoading)
        if (buffering) main.postDelayed(showLoading, 350) else if (retries < 2 || player.playbackState == Player.STATE_READY) loading.visibility = View.INVISIBLE
    }

    // ---------- the screen's frame rate ----------

    private var modeSet = false

    /**
     * A 23.976 fps film on a 60 Hz screen stutters on every slow pan: where the TV has a mode with
     * the same size and a refresh rate that is a whole multiple of the film's (24, 48, 120 Hz…),
     * the player asks for it (the lowest such). Back on leaving, as the window goes.
     */
    private fun matchFrameRate(tracks: Tracks) {
        if (modeSet) return
        var fps = -1f
        for (g in tracks.groups) if (g.type == M3.TRACK_TYPE_VIDEO) for (i in 0 until g.length) if (g.isTrackSelected(i)) fps = g.getTrackFormat(i).frameRate
        if (fps <= 1f) return
        modeSet = true
        try {
            @Suppress("DEPRECATION") val display = windowManager.defaultDisplay
            val cur = display.mode
            val same = display.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            val rate = FrameRate.pick(fps, same.map { it.refreshRate })
            val best = same.firstOrNull { it.refreshRate == rate }
            PlayerLog.w(this, "film ${fps} fps, screen ${cur.refreshRate} Hz, chosen ${best?.refreshRate}")
            if (best != null && best.modeId != cur.modeId) {
                window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
            }
        } catch (e: Exception) { android.util.Log.w("OtthonPlayer", "frame rate", e) }
    }

    private fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** The picture's kind as badges: 4K, Dolby Vision, HDR10, and the sound. */
    private fun describe(tracks: Tracks) {
        val out = ArrayList<String>()
        for (g in tracks.groups) for (i in 0 until g.length) {
            if (!g.isTrackSelected(i)) continue
            val f = g.getTrackFormat(i)
            when (g.type) {
                M3.TRACK_TYPE_VIDEO -> {
                    if (f.height >= 2000) out.add("4K") else if (f.height >= 1000) out.add("1080p")
                    when {
                        f.sampleMimeType?.contains("dolby-vision") == true || f.codecs?.startsWith("dvh") == true -> out.add("Dolby Vision")
                        f.colorInfo?.colorTransfer == 6 -> out.add("HDR10")
                        f.colorInfo?.colorTransfer == 7 -> out.add("HLG")
                    }
                }
                M3.TRACK_TYPE_AUDIO -> out.add(audioName(f.sampleMimeType) + (if (f.channelCount > 2) " ${f.channelCount - 1}.1" else ""))
            }
        }
        badges.text = out.joinToString("  ·  ")
    }

    private fun audioName(mime: String?): String = when {
        mime == null -> ""
        mime.contains("eac3-joc") -> "Dolby Atmos"
        mime.contains("eac3") -> "Dolby Digital+"
        mime.contains("ac3") -> "Dolby Digital"
        mime.contains("true-hd") -> "Dolby TrueHD"
        mime.contains("dts") -> "DTS"
        mime.contains("aac") -> "AAC"
        else -> mime.substringAfter('/').uppercase()
    }

    private fun show() {
        bar.animate().cancel()
        bar.visibility = View.VISIBLE
        bar.animate().alpha(1f).setDuration(180).start()
        hideSoon()
    }

    private val hide = Runnable { if (player.isPlaying) bar.animate().alpha(0f).setDuration(400).withEndAction { bar.visibility = View.INVISIBLE }.start() }
    private fun hideSoon() { main.removeCallbacks(hide); main.postDelayed(hide, 4000) }

    /** ▲: the sound tracks and the subtitles, as one list. */
    private fun tracksMenu() {
        val names = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        for (g in player.currentTracks.groups) {
            if (g.type != M3.TRACK_TYPE_AUDIO && g.type != M3.TRACK_TYPE_TEXT) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i) && g.type == M3.TRACK_TYPE_TEXT) continue
                val f = g.getTrackFormat(i)
                val kind = if (g.type == M3.TRACK_TYPE_AUDIO) Lang.tr("Sound") else Lang.tr("Subtitles")
                val lang = f.label ?: f.language?.let { java.util.Locale(it).displayLanguage } ?: "?"
                val extra = if (g.type == M3.TRACK_TYPE_AUDIO) " · " + audioName(f.sampleMimeType) else ""
                names.add((if (g.isTrackSelected(i)) "● " else "") + "$kind: $lang$extra")
                val group = g.mediaTrackGroup
                val type = g.type
                actions.add {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(group, i)).build()
                }
            }
        }
        names.add(Lang.tr("Subtitles off"))
        actions.add { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(M3.TRACK_TYPE_TEXT, true).build() }
        Popup.menu(this, Lang.tr("Sound and subtitles"), names) { which -> actions.getOrNull(which)?.invoke() }
    }

    private var seekStep = 0
    /** Where the presses of ◀ ▶ have got to; the film jumps there once they stop (-1: none). */
    private var seekTarget = -1L
    private val seekNow = Runnable {
        if (seekTarget >= 0) {
            player.seekTo(seekTarget)
            seekTarget = -1L
            tick()
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                if (down && e.repeatCount == 0) { if (player.isPlaying) player.pause() else player.play(); show() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (down) player.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (down) player.pause(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                val d = player.duration
                if (d <= 0) return true
                if (down) {
                    val back = e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
                    // held down: bigger and bigger steps
                    seekStep = if (e.repeatCount == 0) 0 else seekStep + 1
                    val step = when { seekStep > 24 -> 300_000L; seekStep > 8 -> 60_000L; back -> 10_000L; else -> 30_000L }
                    val from = if (seekTarget >= 0) seekTarget else player.currentPosition
                    seekTarget = (from + if (back) -step else step).coerceIn(0L, maxOf(0L, d - 3000))
                    main.removeCallbacks(seekNow)
                    tick()
                    show()
                } else {
                    // a pause between presses still adds up; the jump comes when they stop
                    main.removeCallbacks(seekNow)
                    main.postDelayed(seekNow, 650)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> { if (down && e.repeatCount == 0) tracksMenu(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> { if (down) show(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (down && index + 1 < queue.size) { save(); index++; start(0L) }; return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                // the episode before; within its first 10 s, else back to its start
                if (down && e.repeatCount == 0) {
                    if (player.currentPosition < 10_000 && index > 0) { save(); index--; start(0L) } else { player.seekTo(0L); show() }
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> { if (!down) { save(); finish() }; return true }
            KeyEvent.KEYCODE_BACK -> { if (!down) { save(); finish() }; return true }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onStop() {
        super.onStop()
        save()
        player.pause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        save()
        player.release()
        Thread { SharePool.closeAll() }.start()
        // the last position is written before the process ends
        writer.shutdown()
        try { writer.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { }
        super.onDestroy()
        // the player's process ends with the film: its memory goes back to the TV at once
        // (not when it has just started the film again in this process)
        if (!handedOver) android.os.Process.killProcess(android.os.Process.myPid())
    }

    companion object {
        fun play(ctx: Context, queue: LongArray, index: Int, startAt: Long = -1L) {
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("queue", queue).putExtra("index", index).putExtra("start", startAt))
        }
    }
}

/**
 * The player's own log of network trouble, in the launcher's folder on the TV's storage
 * (/sdcard/Android/data/hu.otthon.launcher/files/player.log, readable over ADB): the system log
 * of a TV turns over in minutes.
 */
object PlayerLog {
    private val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)

    fun w(ctx: Context, msg: String) {
        android.util.Log.w("OtthonPlayer", msg)
        try {
            val dir = ctx.getExternalFilesDir(null) ?: return
            val f = java.io.File(dir, "player.log")
            if (f.length() > 256 * 1024) f.renameTo(java.io.File(dir, "player.old.log"))
            synchronized(this) { f.appendText(fmt.format(java.util.Date()) + " " + msg + "\n") }
        } catch (e: Exception) { }
    }
}

object FrameRate {
    /**
     * A refresh rate that is a whole multiple (1–5×) of [fps], within 0.25%: an exact one first
     * (59.94 Hz for 29.97 fps rather than 30 Hz), then the lowest. Null if none.
     */
    fun pick(fps: Float, rates: List<Float>): Float? = rates
        .map { r -> val k = r / fps; val n = Math.round(k); Triple(r, n, Math.abs(k - n) / Math.max(n, 1)) }
        .filter { (_, n, err) -> n in 1..5 && err < 0.0025f }
        .minWithOrNull(compareBy<Triple<Float, Int, Float>>({ if (it.third < 0.0005f) 0 else 1 }, { it.first }))
        ?.first
}

/** A turning arc: the film is loading. */
class Spinner(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val r = android.graphics.RectF()
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        p.strokeWidth = w * 0.09f
        val inset = p.strokeWidth
        r.set(inset, inset, w - inset, height - inset)
        p.color = 0x33FFFFFF
        c.drawArc(r, 0f, 360f, false, p)
        val t = android.os.SystemClock.uptimeMillis()
        p.color = 0xFFFAC775.toInt()
        c.drawArc(r, (t % 1000) * 0.36f, 90f + 60f * Math.sin(t / 400.0).toFloat(), false, p)
        if (isShown) postInvalidateOnAnimation()
    }
}

/** The thin line under the film: what is played, what is loaded. */
class ProgressLine(ctx: Context) : View(ctx) {
    private var played = 0f
    private var loaded = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    fun set(played: Float, loaded: Float) { this.played = played.coerceIn(0f, 1f); this.loaded = loaded.coerceIn(0f, 1f); invalidate() }
    override fun onDraw(c: Canvas) {
        val r = height / 2f
        p.color = 0x33FFFFFF
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, p)
        p.color = 0x55FFFFFF
        c.drawRoundRect(0f, 0f, width * loaded, height.toFloat(), r, r, p)
        p.color = 0xFFFAC775.toInt()
        c.drawRoundRect(0f, 0f, width * played, height.toFloat(), r, r, p)
        c.drawCircle((width * played).coerceAtLeast(r), r, r * 1.6f, p)
    }
}

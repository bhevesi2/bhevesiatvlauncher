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
 * the same SMB library as the wallpapers) or from a USB drive (file://). Reads in blocks of 1 MB,
 * so a 4K film does not wait on the network for every small read.
 */
class LibraryDataSource(private val ctx: Context) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var share: MediaLib.SmbShare? = null
    private var smbFile: com.hierynomus.smbj.share.File? = null
    private var local: RandomAccessFile? = null
    private var position = 0L
    private var remaining = 0L
    private val block = ByteArray(1 shl 20)
    private var blockAt = -1L
    private var blockLen = 0

    override fun open(spec: DataSpec): Long {
        transferInitializing(spec)
        uri = spec.uri
        val u = spec.uri
        val size: Long
        if (u.scheme == "otthon") {
            val src = MediaLib.sources(ctx).firstOrNull { it.id == u.host } ?: throw java.io.IOException("No source ${u.host}")
            val path = Uri.decode(u.encodedPath?.removePrefix("/") ?: "")
            val s = MediaLib.openShare(ctx, src)
            share = s
            val f = s.share.openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
            smbFile = f
            size = f.fileInformation.standardInformation.endOfFile
        } else {
            val f = RandomAccessFile(u.path ?: throw java.io.IOException("No path"), "r")
            local = f
            size = f.length()
        }
        position = spec.position
        remaining = if (spec.length != M3.LENGTH_UNSET) spec.length else size - spec.position
        if (remaining < 0) throw java.io.EOFException()
        blockAt = -1L
        transferStarted(spec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return M3.RESULT_END_OF_INPUT
        val want = minOf(length.toLong(), remaining).toInt()
        val n: Int
        val f = smbFile
        if (f != null) {
            if (blockAt < 0 || position < blockAt || position >= blockAt + blockLen) {
                val got = f.read(block, position, 0, block.size)
                if (got <= 0) return M3.RESULT_END_OF_INPUT
                blockAt = position
                blockLen = got
            }
            val inBlock = (position - blockAt).toInt()
            n = minOf(want, blockLen - inBlock)
            System.arraycopy(block, inBlock, buffer, offset, n)
        } else {
            val l = local ?: return M3.RESULT_END_OF_INPUT
            l.seek(position)
            n = l.read(buffer, offset, want)
            if (n <= 0) return M3.RESULT_END_OF_INPUT
        }
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try { smbFile?.close() } catch (e: Exception) { }
        try { share?.close() } catch (e: Exception) { }
        try { local?.close() } catch (e: Exception) { }
        smbFile = null
        share = null
        local = null
        if (uri != null) transferEnded()
        uri = null
    }

    companion object {
        /** The address the player reads an item from. */
        fun uriOf(source: MediaLib.Source, path: String): Uri =
            if (source.kind == "usb") Uri.fromFile(java.io.File(path))
            else Uri.parse("otthon://${source.id}/" + Uri.encode(path, "\\"))
    }
}

/**
 * The library's player, in a process of its own (":player"): if a film is too much for the TV,
 * only the player closes, never the home screen. Dolby Vision, HDR10(+) and the TV's own
 * decoders come with Media3; sound that the TV cannot decode goes to the amplifier as it is.
 *
 * ◀ ▶ skip back 10 s / forward 30 s (held: faster), OK pauses, ▲ sound and subtitles, ▼ shows
 * where it is, Back leaves (where it got to is kept, near the end counts as watched). After an
 * episode the next one starts.
 */
class PlayerActivity : Activity() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var bar: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var timeText: TextView
    private lateinit var badges: TextView
    private lateinit var progress: ProgressLine
    private var queue = longArrayOf()
    private var index = 0
    private var item: MediaLib.Item? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        queue = intent.getLongArrayExtra("queue") ?: longArrayOf(intent.getLongExtra("id", -1))
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
        bar.addView(label("OK pause · ◀ ▶ skip · ▲ sound and subtitles · Back leave", 10f, C.TEXT2),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4f) })
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DataSource.Factory { LibraryDataSource(applicationContext) }))
            .build()
        view.player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) ended()
                tick()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) { save(); show() } else hideSoon()
            }
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.w("OtthonPlayer", "can't play", error)
                titleText.text = Lang.tr("Can't play this file") + " (" + error.errorCodeName + ")"
                show()
            }
            override fun onTracksChanged(tracks: Tracks) = describe(tracks)
        })
        start(intent.getLongExtra("start", -1L))
        main.post(ticker)
    }

    /** Plays queue[index]; [startAt] -1 means where it got to last time. */
    private fun start(startAt: Long) {
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
        item?.let { MediaLib.savePosition(this, it.id, player.duration, player.duration) }
        if (index + 1 < queue.size) { index++; start(0L) } else finish()
    }

    private fun save() {
        val it = item ?: return
        val d = player.duration
        if (d > 0) MediaLib.savePosition(this, it.id, player.currentPosition, d)
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

    private fun tick() {
        val d = player.duration
        val p = player.currentPosition
        if (d > 0) {
            progress.set(p.toFloat() / d, player.bufferedPosition.toFloat() / d)
            timeText.text = "${clock(p)} / ${clock(d)}   ·   " + Lang.tr("${clock(d - p)} left")
        }
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
                if (down) {
                    val back = e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
                    // held down: bigger and bigger steps
                    seekStep = if (e.repeatCount == 0) 0 else seekStep + 1
                    val step = when { seekStep > 20 -> 300_000L; seekStep > 8 -> 60_000L; back -> 10_000L; else -> 30_000L }
                    val to = (player.currentPosition + if (back) -step else step).coerceIn(0L, maxOf(0L, player.duration - 1000))
                    player.seekTo(to)
                    tick()
                    show()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> { if (down && e.repeatCount == 0) tracksMenu(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> { if (down) show(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (down && index + 1 < queue.size) { save(); index++; start(0L) }; return true }
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
        super.onDestroy()
        // the player's process ends with the film: its memory goes back to the TV at once
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    companion object {
        fun play(ctx: Context, queue: LongArray, index: Int, startAt: Long = -1L) {
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("queue", queue).putExtra("index", index).putExtra("start", startAt))
        }
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

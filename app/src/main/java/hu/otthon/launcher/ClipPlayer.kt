package hu.otthon.launcher

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView

/**
 * Plays a recorded clip (a camera's detection) with its sound, fitted to the view. Given several
 * addresses it tries them in turn; Home Assistant's token goes along in the request headers.
 */
class ClipPlayer(ctx: Context) : TextureView(ctx), TextureView.SurfaceTextureListener {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var urls: List<String> = emptyList()
    private var attempt = 0
    private var headers: Map<String, String> = emptyMap()
    private var videoW = 0
    private var videoH = 0
    private var prepared = false

    /** The picture is showing. */
    var playing = false
        private set

    var onStarted: (() -> Unit)? = null
    var onEnded: (() -> Unit)? = null
    var onFailed: (() -> Unit)? = null

    init {
        surfaceTextureListener = this
        alpha = 0f
    }

    fun play(addresses: List<String>, token: String) {
        stop()
        urls = addresses
        attempt = 0
        headers = mapOf("Authorization" to "Bearer $token")
        if (isAvailable) start()
    }

    fun stop() {
        main.removeCallbacks(giveUp)
        urls = emptyList()
        playing = false
        prepared = false
        animate().cancel()
        alpha = 0f
        release()
    }

    /** Pauses or goes on; at the end, starts again. True when it plays now. */
    fun toggle(): Boolean {
        val p = player ?: return false
        if (!prepared) return false
        return try {
            if (p.isPlaying) {
                p.pause()
                false
            } else {
                if (p.duration > 0 && p.currentPosition >= p.duration - 300) p.seekTo(0)
                p.start()
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun seekBy(ms: Int) {
        val p = player ?: return
        if (!prepared) return
        try {
            val d = p.duration
            val to = (p.currentPosition + ms).coerceIn(0, if (d > 0) d - 200 else Int.MAX_VALUE)
            p.seekTo(to)
            if (!p.isPlaying) p.start()
        } catch (e: Exception) { }
    }

    /** Position and length in milliseconds; null before it starts. */
    fun progress(): Pair<Int, Int>? {
        val p = player ?: return null
        if (!prepared) return null
        return try { p.currentPosition to p.duration } catch (e: Exception) { null }
    }

    val paused: Boolean
        get() = try { prepared && player?.isPlaying == false } catch (e: Exception) { false }

    private val giveUp = Runnable { next() }

    private fun start() {
        val address = urls.getOrNull(attempt) ?: return
        val st = surfaceTexture ?: return
        release()
        val s = Surface(st)
        surface = s
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setSurface(s)
            mp.setDataSource(context, Uri.parse(address), headers)
            mp.setOnPreparedListener {
                if (player === it) {
                    prepared = true
                    it.start()
                }
            }
            mp.setOnVideoSizeChangedListener { p, w, h ->
                if (player === p && w > 0 && h > 0) {
                    videoW = w
                    videoH = h
                    fit()
                }
            }
            mp.setOnInfoListener { p, what, _ ->
                if (player === p && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    main.removeCallbacks(giveUp)
                    playing = true
                    animate().alpha(1f).setDuration(200).start()
                    onStarted?.invoke()
                }
                false
            }
            mp.setOnErrorListener { p, what, extra ->
                if (player === p) {
                    android.util.Log.w("OtthonClip", "can't play $address ($what/$extra)")
                    if (playing) onFailed?.invoke() else next()
                }
                true
            }
            mp.setOnCompletionListener { p -> if (player === p) onEnded?.invoke() }
            mp.prepareAsync()
            main.postDelayed(giveUp, 15_000L)
        } catch (e: Exception) {
            android.util.Log.w("OtthonClip", "can't open $address: $e")
            main.post { next() }
        }
    }

    /** The next address, or giving up. */
    private fun next() {
        if (urls.isEmpty() || playing) return
        main.removeCallbacks(giveUp)
        attempt++
        if (attempt < urls.size) {
            start()
        } else {
            val cb = onFailed
            stop()
            cb?.invoke()
        }
    }

    private fun release() {
        player?.let {
            try { it.stop() } catch (e: Exception) { }
            try { it.release() } catch (e: Exception) { }
        }
        player = null
        surface?.release()
        surface = null
    }

    private fun fit() {
        if (videoW <= 0 || videoH <= 0 || width == 0 || height == 0) return
        val vr = videoW.toFloat() / videoH
        val wr = width.toFloat() / height
        val m = Matrix()
        if (vr > wr) m.setScale(1f, wr / vr, width / 2f, height / 2f)
        else m.setScale(vr / wr, 1f, width / 2f, height / 2f)
        setTransform(m)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        if (urls.isNotEmpty() && player == null) start()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = fit()
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}

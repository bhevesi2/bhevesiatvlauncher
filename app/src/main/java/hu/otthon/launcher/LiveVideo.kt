package hu.otthon.launcher

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView

/**
 * Live camera video from an RTSP address (Frigate's restream), played by Android's own player.
 * It stays invisible until the first frame is on screen; if nothing comes within ten seconds,
 * or the stream breaks, [onFailed] runs and the caller goes back to still pictures.
 * [crop] fills the view; otherwise the whole picture is shown.
 */
class LiveVideo(ctx: Context, private val crop: Boolean) : TextureView(ctx), TextureView.SurfaceTextureListener {
    var onStarted: (() -> Unit)? = null
    var onFailed: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var url: String? = null
    private var videoW = 0
    private var videoH = 0
    var playing = false
        private set

    private val giveUp = Runnable { fail() }

    init {
        surfaceTextureListener = this
        isOpaque = false
        alpha = 0f
    }

    fun play(address: String) {
        if (address == url && player != null) return
        stop()
        url = address
        if (isAvailable) start()
    }

    fun stop() {
        main.removeCallbacks(giveUp)
        url = null
        playing = false
        animate().cancel()
        alpha = 0f
        release()
    }

    private fun start() {
        val address = url ?: return
        val st = surfaceTexture ?: return
        val s = Surface(st)
        surface = s
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setSurface(s)
            mp.setDataSource(address)
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener { if (player === it) it.start() }
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
                    animate().alpha(1f).setDuration(250).start()
                    onStarted?.invoke()
                }
                false
            }
            mp.setOnErrorListener { p, _, _ ->
                if (player === p) fail()
                true
            }
            mp.setOnCompletionListener { p -> if (player === p) fail() }
            mp.prepareAsync()
            main.postDelayed(giveUp, 10_000L)
        } catch (e: Exception) {
            fail()
        }
    }

    private fun fail() {
        if (url == null) return
        main.removeCallbacks(giveUp)
        url = null
        playing = false
        alpha = 0f
        release()
        onFailed?.invoke()
    }

    /** Lets go of the player off the main thread: closing an RTSP session can take a moment. */
    private fun release() {
        val mp = player
        val s = surface
        player = null
        surface = null
        if (mp != null) Bg.exec.execute {
            try { mp.release() } catch (e: Exception) { }
            s?.release()
        } else {
            s?.release()
        }
    }

    /** Scales the picture to fill (crop) or to fit inside the view without stretching it. */
    private fun fit() {
        if (videoW == 0 || width == 0 || height == 0) return
        val sx = width.toFloat() / videoW
        val sy = height.toFloat() / videoH
        val s = if (crop) maxOf(sx, sy) else minOf(sx, sy)
        val m = Matrix()
        // a TextureView stretches the video over the whole view; undo that, then scale evenly
        m.setScale(videoW * s / width, videoH * s / height, width / 2f, height / 2f)
        setTransform(m)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        if (url != null && player == null) start()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = fit()

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        if (url != null) fail()
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}

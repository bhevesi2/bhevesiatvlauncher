package hu.otthon.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Camera pop-ups: while the TV is on, watches the trigger sensors in Home Assistant and, when one
 * turns on (Frigate saw a person, say), shows that camera in a corner of the screen over
 * whatever is playing. While it is up it answers the remote: Back closes it, OK opens the
 * camera full screen, and any other key just closes it.
 */
class PopupService : Service() {

    companion object {
        private const val CHANNEL = "popups"
        private const val TEST = "test"

        /** True while the launcher's own camera screen is open: no pop-ups over it. */
        @Volatile var camerasOpen = false

        /** Starts or stops the watcher to match the settings. */
        fun sync(ctx: Context) {
            val cfg = Config.load(ctx)
            val want = cfg.haEnabled && cfg.popups && cfg.popupTriggers.isNotEmpty()
            val i = Intent(ctx, PopupService::class.java)
            try {
                if (want) {
                    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                } else {
                    ctx.stopService(i)
                }
            } catch (e: Exception) { }
        }

        private const val CAMERA = "camera"
        private const val CAMERA_TITLE = "camera_title"

        /** Pops up [camera] now, for a colour button of the remote. */
        fun showCamera(ctx: Context, camera: String, title: String) {
            val i = Intent(ctx, PopupService::class.java).putExtra(CAMERA, camera).putExtra(CAMERA_TITLE, title)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) { }
        }

        /** Shows the pop-up of a camera now, to see how it looks. */
        fun test(ctx: Context, camera: String) {
            val i = Intent(ctx, PopupService::class.java).putExtra(TEST, camera)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) { }
        }

        fun allowed(ctx: Context): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

        /** Lets the launcher draw over other apps; only possible over ADB on a TV. */
        fun allowCommand(ctx: Context) = "appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow"
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: HandlerThread
    private lateinit var bg: Handler
    private var cfg: Config? = null
    private var client: HaClient? = null
    private val last = HashMap<String, String>()
    private var primed = false

    private var overlay: View? = null
    private var image: ImageView? = null
    private var live: LiveVideo? = null
    private var caption: TextView? = null
    private var showing: String? = null
    private var until = 0L
    private var fetching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("popups").apply { start() }
        bg = Handler(worker.looper)
        startForeground(1, notification())
        reload()
        bg.post(poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // every startForegroundService() must be answered, even when the service already runs
        startForeground(1, notification())
        reload()
        intent?.getStringExtra(TEST)?.let { cam -> main.post { show(cam, "Test", true) } }
        intent?.getStringExtra(CAMERA)?.let { cam -> main.post { show(cam, intent.getStringExtra(CAMERA_TITLE) ?: "", true) } }
        val c = cfg
        if (c == null || !c.haEnabled || !c.popups || c.popupTriggers.isEmpty()) {
            if (intent?.getStringExtra(TEST) == null && intent?.getStringExtra(CAMERA) == null) stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        bg.removeCallbacksAndMessages(null)
        worker.quitSafely()
        main.removeCallbacksAndMessages(null)
        hide(false)
    }

    private fun reload() {
        val c = Config.load(this)
        cfg = c
        client = if (c.haEnabled) HaClient(c.haUrl!!, c.haToken!!) else null
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Camera pop-ups", NotificationManager.IMPORTANCE_MIN))
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_camera)
            .setContentTitle("Camera pop-ups")
            .setContentText("Watching the cameras for the launcher")
            .setOngoing(true)
            .build()
    }

    // ---------- watching ----------

    private val poll = object : Runnable {
        override fun run() {
            val c = cfg
            val api = client
            val screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
            if (c != null && api != null && c.popups && screenOn) {
                for (t in c.popupTriggers) {
                    val state = try { api.state(t.entity).optString("state") } catch (e: Exception) { null } ?: continue
                    val before = last.put(t.entity, state)
                    // only a change to "on" counts, not a sensor that was already on when we started
                    if (primed && state == "on" && before != null && before != "on") {
                        main.post { show(t.camera, t.title, false) }
                    } else if (state == "on" && showing == t.camera) {
                        main.post { until = maxOf(until, System.currentTimeMillis() + 5_000L) }
                    }
                }
                primed = true
            } else if (!screenOn) {
                // while the TV is off, start over: whatever happened meanwhile is old news
                primed = false
                last.clear()
            }
            bg.postDelayed(this, if (screenOn) 2000L else 10_000L)
        }
    }

    // ---------- the pop-up ----------

    /** The pop-up's frame: it takes the remote's keys while it is on screen. */
    private inner class Box : FrameLayout(this@PopupService) {
        private var downSeen = false

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            val down = event.action == KeyEvent.ACTION_DOWN
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (down) downSeen = true else if (downSeen) hide(true)
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (down) downSeen = true else if (downSeen) openFull()
                }
                // volume and mute keep working for the TV
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> return false
                else -> if (down) hide(true)
            }
            return true
        }
    }

    private fun show(camera: String, title: String, test: Boolean) {
        val c = cfg ?: return
        if (camerasOpen && !test) return
        if (!allowed(this)) return
        until = System.currentTimeMillis() + c.popupSeconds * 1000L
        caption?.text = title
        if (overlay == null) {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val d = resources.displayMetrics.density
            val box = Box().apply {
                background = rounded(0xFF101114.toInt(), 12f * d, (2 * d).toInt(), 0x66FFFFFF)
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                isFocusable = true
                isFocusableInTouchMode = true
            }
            val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            box.addView(img, FrameLayout.LayoutParams(-1, -1))
            val video = LiveVideo(this, crop = true)
            box.addView(video, FrameLayout.LayoutParams(-1, -1))
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((9 * d).toInt(), (4 * d).toInt(), (9 * d).toInt(), (5 * d).toInt())
                setBackgroundColor(0xB3060709.toInt())
            }
            bar.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_camera)
                setColorFilter(C.TEXT)
            }, LinearLayout.LayoutParams((11 * d).toInt(), (11 * d).toInt()))
            val cap = label(title, 10f, C.TEXT, bold = true).apply { setPadding((6 * d).toInt(), 0, 0, 0); maxLines = 1 }
            bar.addView(cap, LinearLayout.LayoutParams(0, -2, 1f))
            bar.addView(label("Back: close  ·  OK: full screen", 8f, C.TEXT2).apply { maxLines = 1 })
            box.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            val lp = WindowManager.LayoutParams(
                (281 * d).toInt(), (158 * d).toInt(),
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                // focusable, so Back can close it; the app underneath keeps playing
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                x = (28 * d).toInt()
                y = (28 * d).toInt()
            }
            try {
                wm.addView(box, lp)
            } catch (e: Exception) {
                return
            }
            overlay = box
            image = img
            live = video
            caption = cap
            box.requestFocus()
            box.alpha = 0f
            box.translationY = 20 * d
            box.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator(2f)).start()
            main.post(life)
        }
        if (showing != camera) {
            showing = camera
            image?.setImageDrawable(null)
            val stream = c.stream(camera)
            if (stream != null) live?.play(stream) else live?.stop()
        }
        frame()
    }

    /** OK on the pop-up: the camera full screen in the launcher. */
    private fun openFull() {
        val cam = showing
        hide(false)
        val i = cfg?.cameras?.indexOfFirst { it.entity == cam } ?: -1
        val intent = Intent(this, CamerasActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i >= 0) intent.putExtra("index", i)
        try { startActivity(intent) } catch (e: Exception) { }
    }

    /** Keeps the picture moving, about three frames a second, and closes the pop-up in time. */
    private val life = object : Runnable {
        override fun run() {
            if (overlay == null) return
            if (System.currentTimeMillis() > until) {
                hide(true)
                return
            }
            frame()
            main.postDelayed(this, 330L)
        }
    }

    private fun frame() {
        val api = client ?: return
        val cam = showing ?: return
        if (fetching || live?.playing == true) return
        fetching = true
        bg.post {
            val b: Bitmap? = try { api.bitmap("/api/camera_proxy/$cam", 720) } catch (e: Exception) { null }
            main.post {
                fetching = false
                if (b != null && showing == cam) image?.setImageBitmap(b)
            }
        }
    }

    private fun hide(animate: Boolean) {
        val v = overlay ?: return
        overlay = null
        image = null
        caption = null
        live?.stop()
        live = null
        showing = null
        main.removeCallbacks(life)
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        fun remove() {
            try { wm.removeView(v) } catch (e: Exception) { }
        }
        if (animate) v.animate().alpha(0f).setDuration(200).withEndAction { remove() }.start() else remove()
        // a test pop-up started the service while pop-ups are switched off: nothing left to do
        val c = cfg
        if (animate && (c == null || !c.haEnabled || !c.popups || c.popupTriggers.isEmpty())) stopSelf()
    }
}

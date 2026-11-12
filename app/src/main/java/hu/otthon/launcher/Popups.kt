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
 * whatever is playing. The pop-up takes no focus, so the remote keeps working in the app.
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
        val c = cfg
        if (c == null || !c.haEnabled || !c.popups || c.popupTriggers.isEmpty()) {
            if (intent?.getStringExtra(TEST) == null) stopSelf()
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

    private fun show(camera: String, title: String, test: Boolean) {
        val c = cfg ?: return
        if (camerasOpen && !test) return
        if (!allowed(this)) return
        until = System.currentTimeMillis() + c.popupSeconds * 1000L
        caption?.text = title
        if (overlay == null) {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val d = resources.displayMetrics.density
            val box = FrameLayout(this).apply {
                background = rounded(0xFF101114.toInt(), 14f * d, (2 * d).toInt(), 0x66FFFFFF)
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }
            val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            box.addView(img, FrameLayout.LayoutParams(-1, -1))
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (7 * d).toInt())
                setBackgroundColor(0xB3060709.toInt())
            }
            bar.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_camera)
                setColorFilter(C.TEXT)
            }, LinearLayout.LayoutParams((14 * d).toInt(), (14 * d).toInt()))
            val cap = label(title, 12f, C.TEXT, bold = true).apply { setPadding((8 * d).toInt(), 0, 0, 0); maxLines = 1 }
            bar.addView(cap)
            box.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            val lp = WindowManager.LayoutParams(
                (432 * d).toInt(), (243 * d).toInt(),
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                x = (30 * d).toInt()
                y = (30 * d).toInt()
            }
            try {
                wm.addView(box, lp)
            } catch (e: Exception) {
                return
            }
            overlay = box
            image = img
            caption = cap
            box.alpha = 0f
            box.translationY = 24 * d
            box.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
            main.post(life)
        }
        if (showing != camera) {
            showing = camera
            image?.setImageDrawable(null)
        }
        frame()
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
        if (fetching) return
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

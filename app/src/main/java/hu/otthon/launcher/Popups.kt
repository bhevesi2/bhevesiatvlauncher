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
 *
 * Notifications: Home Assistant's "TV notification" script writes a short message into a text
 * helper; the service shows it as a card in the top right corner for a few seconds. The card
 * takes no keys, so the remote keeps working in the app underneath.
 */
class PopupService : Service() {

    companion object {
        private const val CHANNEL = "popups"
        private const val TEST = "test"
        private const val NOTE_TEST = "note_test"

        /** True while the launcher's own camera screen is open: no pop-ups over it. */
        @Volatile var camerasOpen = false

        /** Closes the camera pop-up, if one is up (the quick settings opening). */
        @Volatile var closePopup: (() -> Unit)? = null

        /** Starts or stops the watcher to match the settings. */
        fun sync(ctx: Context) {
            val cfg = Config.load(ctx)
            val want = needed(cfg)
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

        private const val QUICK = "quick"

        /**
         * Opens or closes the quick settings in this, the launcher's main process. The button
         * service runs in a process of its own, which Android treats as background: from there
         * Home Assistant and the Wi-Fi's name were not reachable. This service is a foreground one.
         */
        fun quick(ctx: Context) {
            val i = Intent(ctx, PopupService::class.java).putExtra(QUICK, true)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {
                android.util.Log.w("OtthonQuick", "could not reach the main process", e)
            }
        }

        /** Shows a sample notification card now, to see how it looks. */
        fun testNote(ctx: Context) {
            val i = Intent(ctx, PopupService::class.java).putExtra(NOTE_TEST, true)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) { }
        }

        /**
         * True when there is something to watch: camera sensors or notifications. The sensors are
         * watched even with the pop-ups off, for the quick settings' list of the last day.
         */
        fun needed(c: Config): Boolean = c.haEnabled && (c.popupTriggers.isNotEmpty() || c.notify || c.reportState)

        private const val FRONT = "front"

        /** The button service saw another app come to the front: passed on to Home Assistant. */
        fun front(ctx: Context, pkg: String) {
            val i = Intent(ctx, PopupService::class.java).putExtra(FRONT, pkg)
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

    private var noteView: View? = null
    private var noteUntil = 0L
    private var lastNote: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /** Android 14 wants to know what a foreground service is for; this one is a special use. */
    private fun foreground() {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification())
        }
    }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        CrashLog.recover(this)
        CrashLog.checkMemoryCrash(this)
        LowRam.init(this)
        closePopup = { main.post { hide(false) } }
        worker = HandlerThread("popups").apply { start() }
        bg = Handler(worker.looper)
        foreground()
        reload()
        bg.post(poll)
        // the screensaver starting and stopping, for the TV's state in Home Assistant
        try {
            registerReceiver(dreams, android.content.IntentFilter().apply {
                addAction(Intent.ACTION_DREAMING_STARTED)
                addAction(Intent.ACTION_DREAMING_STOPPED)
                addAction(Intent.ACTION_SCREEN_OFF)
            })
        } catch (e: Exception) { CrashLog.note(e) }
    }

    private val dreams = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            // asleep: a launcher compiled after an update starts again with the compiled code
            if (intent.action == Intent.ACTION_SCREEN_OFF) Precompile.restartIfPending(this@PopupService)
            TvState.dreaming = intent.action == Intent.ACTION_DREAMING_STARTED
            val c = cfg
            val api = client
            if (c != null && api != null) bg.post { try { TvState.report(this@PopupService, c, api) } catch (e: Throwable) { CrashLog.note(e) } }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // every startForegroundService() must be answered, even when the service already runs
        foreground()
        reload()
        intent?.getStringExtra(TEST)?.let { cam -> main.post { show(cam, "Test", true) } }
        intent?.getStringExtra(CAMERA)?.let { cam -> main.post { show(cam, intent.getStringExtra(CAMERA_TITLE) ?: "", true) } }
        intent?.getStringExtra(FRONT)?.let { pkg ->
            TvState.front = pkg
            val c = cfg
            val api = client
            if (c != null && api != null) bg.post { try { TvState.report(this, c, api) } catch (e: Throwable) { CrashLog.note(e) } }
        }
        val quickAsked = intent?.getBooleanExtra(QUICK, false) == true
        if (quickAsked) main.post { QuickPanel.toggle(this) }
        val noteTest = intent?.getBooleanExtra(NOTE_TEST, false) == true
        if (noteTest) main.post { showNote(Note("bell", "info", "Test notification", "This is how Home Assistant's messages look on this TV.")) }
        val c = cfg
        if (c == null || !needed(c)) {
            if (intent?.getStringExtra(TEST) == null && intent?.getStringExtra(CAMERA) == null && !noteTest && !quickAsked && intent?.getStringExtra(FRONT) == null) stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        closePopup = null
        try { unregisterReceiver(dreams) } catch (e: Exception) { }
        bg.removeCallbacksAndMessages(null)
        worker.quitSafely()
        main.removeCallbacksAndMessages(null)
        watch?.stop()
        watch = null
        hide(false)
        hideNote(false)
    }

    private fun reload() {
        val c = Config.load(this)
        cfg = c
        client = c.client()
        // the live subscription: started again only when what it watches changed
        val want = if (c.haEnabled && needed(c)) {
            (c.popupTriggers.map { it.entity } + (if (c.notify) listOf(c.notifyEntity) else emptyList())).distinct()
        } else emptyList()
        val key = if (want.isEmpty()) null else c.haUrl + "|" + c.haToken.hashCode() + "|" + want.joinToString(",")
        if (key != watchKey) {
            watch?.stop()
            watch = if (key == null) null else HaWatch(c.haUrl!!, c.haToken!!, want) { e, s, at, first ->
                bg.post { try { onLive(e, s, at, first) } catch (x: Throwable) { CrashLog.note(x) } }
            }.start()
            watchKey = key
        }
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

    /**
     * Home Assistant tells of every change at once over a WebSocket ([HaWatch]); while that line
     * is down, the sensors are asked every two seconds as before.
     */
    private var watch: HaWatch? = null
    private var watchKey: String? = null

    /** A state from the live subscription, on the watcher's thread ([bg]). */
    private fun onLive(entity: String, state: String, at: Long, first: Boolean) {
        val c = cfg ?: return
        val screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        if (c.notify && entity == c.notifyEntity) {
            val before = lastNote
            lastNote = state
            val item = Inbox.note(state) ?: return
            Inbox.add(this, item)
            if (first || before == null || before == state) return
            if (screenOn && Math.abs(System.currentTimeMillis() - item.at) <= 120_000L) main.post { showNote(Note(item.icon, item.level, item.title, item.text)) }
            return
        }
        for (t in c.popupTriggers) {
            if (t.entity != entity) continue
            val before = last.put(t.entity, state)
            if (state == "on" && before != "on" && at > 0) Inbox.add(this, Inbox.alert(t, at))
            if (!first && screenOn && c.popups && state == "on" && before != null && before != "on") {
                main.post { show(t.camera, t.title, false) }
            } else if (state == "on" && showing == t.camera) {
                main.post { until = maxOf(until, System.currentTimeMillis() + 5_000L) }
            }
        }
    }

    private var wasOn = false
    private var backfilledAt = 0L
    @Volatile private var backfilling = false

    private val poll = object : Runnable {
        override fun run() {
            // whatever goes wrong in one round is written down; the next round comes all the same
            try {
                round()
            } catch (e: Throwable) {
                CrashLog.note(e)
            }
            val screenOn = try { (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive } catch (e: Exception) { true }
            bg.postDelayed(this, if (screenOn) 2000L else 15_000L)
        }

        private fun round() {
            val c = cfg
            val api = client
            val screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
            val live = watch?.connected == true
            if (c != null && api != null && c.notify && !live) checkNote(c, api, screenOn)
            if (c != null && api != null && c.popupTriggers.isNotEmpty() && !live) {
                for (t in c.popupTriggers) {
                    val s = try { api.state(t.entity) } catch (e: Exception) { null } ?: continue
                    val state = s.optString("state")
                    val before = last.put(t.entity, state)
                    // a sensor that turned on goes into the quick settings' list, even while the TV is off
                    if (state == "on" && before != "on") changedAt(s)?.let { Inbox.add(this@PopupService, Inbox.alert(t, it)) }
                    // only a change to "on" pops up, not a sensor that was already on when we started
                    if (primed && screenOn && c.popups && state == "on" && before != null && before != "on") {
                        main.post { show(t.camera, t.title, false) }
                    } else if (state == "on" && showing == t.camera) {
                        main.post { until = maxOf(until, System.currentTimeMillis() + 5_000L) }
                    }
                }
                primed = true
            }
            // what the TV is doing, for Home Assistant: on a change (the screen going off, say) and every few minutes
            if (c != null && api != null) TvState.report(this@PopupService, c, api)
            // what happened while the TV slept (or before the launcher started): from the history
            val now = System.currentTimeMillis()
            if (c != null && api != null && screenOn && (!wasOn || now - backfilledAt > 15 * 60_000L)) backfill(c, api)
            wasOn = screenOn
        }
    }

    private fun changedAt(s: org.json.JSONObject): Long? = try {
        java.time.OffsetDateTime.parse(s.optString("last_changed")).toInstant().toEpochMilli()
    } catch (e: Exception) { null }

    /** The last day's notifications and alerts from Home Assistant's history, into the list. */
    private fun backfill(c: Config, api: HaClient) {
        if (backfilling) return
        backfilling = true
        backfilledAt = System.currentTimeMillis()
        Thread {
            try {
                for (t in c.popupTriggers) {
                    val h = try { api.history(t.entity, 24) } catch (e: Exception) { continue }
                    var prev: String? = null
                    for ((at, st) in h) {
                        // the first entry is the state the day started with, not a change
                        if (st == "on" && prev != null && prev != "on") Inbox.add(this, Inbox.alert(t, at))
                        prev = st
                    }
                }
                if (c.notify) {
                    val h = try { api.history(c.notifyEntity, 24) } catch (e: Exception) { emptyList() }
                    for ((_, st) in h) Inbox.note(st)?.let { Inbox.add(this, it) }
                }
            } catch (e: Throwable) {
                CrashLog.note(e)
            } finally {
                backfilling = false
            }
        }.start()
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
        // over the open quick settings a 2 GB TV has no room for it (it ran out of graphics
        // memory with the home screen, the panel and the video at once): there the alert is in
        // the panel's list instead. A TV with more memory shows it as always.
        if (QuickPanel.showing && LowRam.on && !test) return
        if (!allowed(this)) return
        until = System.currentTimeMillis() + c.popupSeconds * 1000L
        caption?.text = title
        if (overlay == null) {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val d = resources.displayMetrics.density
            val box = Box().apply {
                background = rounded(0xFF101114.toInt(), Look.R_PANEL * d)
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
            val b: Bitmap? = try { api.bitmap("/api/camera_proxy/$cam", 720) } catch (e: Throwable) { null }
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
        if (animate && (c == null || !needed(c)) && noteView == null) stopSelf()
    }

    // ---------- notifications ----------

    /** One notification: an icon name (ic_<icon>), a level (info / warn / alert), a title and a text. */
    private data class Note(val icon: String, val level: String, val title: String, val text: String)

    /**
     * The helper holds "id|icon|level|title|text"; the id is the time it was sent (seconds), so the
     * same text sent twice still counts as new. A notification older than two minutes is not shown
     * (the TV was just switched on, or the service just started).
     */
    private fun checkNote(c: Config, api: HaClient, screenOn: Boolean) {
        val raw = try { api.state(c.notifyEntity).optString("state") } catch (e: Exception) { null } ?: return
        val before = lastNote
        lastNote = raw
        if (before == null || raw == before) return
        val item = Inbox.note(raw) ?: return
        Inbox.add(this, item)
        if (!screenOn || Math.abs(System.currentTimeMillis() - item.at) > 120_000L) return
        main.post { showNote(Note(item.icon, item.level, item.title, item.text)) }
    }

    private fun showNote(n: Note) {
        val c = cfg ?: return
        if (!allowed(this)) return
        hideNote(false)
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        val accent = when (n.level) {
            "alert" -> 0xFFF09595.toInt()
            "warn" -> 0xFFFAC775.toInt()
            else -> C.ON
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(C.PANEL, Look.R_PANEL * d)
            setPadding((12 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
        }
        val iconRes = resources.getIdentifier("ic_" + n.icon.lowercase().replace(Regex("[^a-z0-9_]"), ""), "drawable", packageName)
        card.addView(ImageView(this).apply {
            setImageResource(if (iconRes != 0) iconRes else R.drawable.ic_home)
            setColorFilter(accent)
        }, LinearLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt()).apply { rightMargin = (12 * d).toInt() })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (n.title.isNotBlank()) texts.addView(label(n.title, 12f, C.TEXT, bold = true).apply { maxLines = 1 })
        if (n.text.isNotBlank()) texts.addView(label(n.text, 10f, C.TEXT2).apply { maxLines = 3 })
        card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val lp = WindowManager.LayoutParams(
            (300 * d).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // never takes the remote's keys: the app underneath stays in control
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (28 * d).toInt()
            y = (28 * d).toInt()
        }
        try {
            wm.addView(card, lp)
        } catch (e: Exception) {
            return
        }
        noteView = card
        noteUntil = System.currentTimeMillis() + c.notifySeconds * 1000L
        card.alpha = 0f
        card.translationX = 24 * d
        card.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
        main.removeCallbacks(noteLife)
        main.postDelayed(noteLife, 500L)
    }

    private val noteLife = object : Runnable {
        override fun run() {
            if (noteView == null) return
            if (System.currentTimeMillis() > noteUntil) hideNote(true) else main.postDelayed(this, 500L)
        }
    }

    private fun hideNote(animate: Boolean) {
        val v = noteView ?: return
        noteView = null
        main.removeCallbacks(noteLife)
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        fun remove() {
            try { wm.removeView(v) } catch (e: Exception) { }
        }
        if (animate) v.animate().alpha(0f).setDuration(220).withEndAction { remove() }.start() else remove()
        // a test card started the service while nothing is being watched: nothing left to do
        val c = cfg
        if (animate && (c == null || !needed(c)) && overlay == null) stopSelf()
    }
}

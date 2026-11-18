package hu.otthon.launcher

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Quick settings, in the manner of Google TV's panel: it slides in from the right over whatever
 * is on screen (a film keeps playing under it), with the time, the profile, and tiles for
 * Picture, Sound, Inputs, Network, Screen saver, Sleep timer, Turn off and the launcher's
 * settings, and all of the TV's settings at the bottom. The remote's gear button opens and
 * closes it; Back closes it; it closes itself after half a minute without a key.
 *
 * From the button service it is an accessibility overlay (no extra permission needed); from the
 * launcher it is an overlay over other apps, or a panel of the home screen's own window.
 */
object QuickPanel {
    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var wm: WindowManager? = null
    private var shownAt = 0L

    val showing get() = root != null

    /** Opens the panel, or closes it when it is already open (the gear button toggles it). */
    fun toggle(ctx: Context) {
        if (root != null) hide() else show(ctx)
    }

    fun show(ctx: Context) {
        if (root != null) return
        try {
            build(ctx)
        } catch (e: Exception) {
            android.util.Log.w("OtthonQuick", "quick settings failed", e)
            root = null
            Apps.openSystemSettings(ctx)
        }
    }

    private fun build(ctx: Context) {
        val cfg = Config.load(ctx)
        val type = when {
            ctx is AccessibilityService && Build.VERSION.SDK_INT >= 22 -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            PopupService.allowed(ctx) && Build.VERSION.SDK_INT >= 26 -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            ctx is Activity -> WindowManager.LayoutParams.TYPE_APPLICATION
            else -> {
                // nowhere to draw: the TV's own settings instead
                Apps.openSystemSettings(ctx)
                return
            }
        }
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = Panel(ctx, cfg)
        val lp = WindowManager.LayoutParams(
            ctx.dp(340f + 30f), WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.TOP
            dimAmount = 0.35f
        }
        try {
            manager.addView(view, lp)
        } catch (e: Exception) {
            android.util.Log.w("OtthonQuick", "no window for quick settings (type $type)", e)
            Apps.openSystemSettings(ctx)
            return
        }
        android.util.Log.i("OtthonQuick", "shown (type $type)")
        root = view
        wm = manager
        shownAt = System.currentTimeMillis()
        view.enter()
        poke()
    }

    fun hide() {
        val v = root ?: return
        root = null
        main.removeCallbacks(timeout)
        val m = wm
        wm = null
        val d = v.resources.displayMetrics.density
        v.animate().translationX(40 * d).alpha(0f).setDuration(160).withEndAction {
            try { m?.removeView(v) } catch (e: Exception) { }
        }.start()
    }

    /** Another app came to the front (Home, an app the panel opened): the panel goes. */
    fun onAppChanged(pkg: String?, ownPkg: String) {
        if (root == null || pkg == null || pkg == ownPkg) return
        // the system's own windows (volume bar, toasts, TV overlays) come and go: only an app counts
        if (pkg == "android" || pkg.contains("systemui") || pkg.startsWith("com.tcl.") || pkg.startsWith("com.mediatek.")) return
        if (System.currentTimeMillis() - shownAt < 1500) return
        android.util.Log.i("OtthonQuick", "closed: $pkg came to the front")
        hide()
    }

    private val timeout = Runnable { hide() }

    private fun poke() {
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 30_000L)
    }

    /** Starts [intents] in order until one opens; closes the panel when one did. */
    private fun open(ctx: Context, vararg intents: Intent): Boolean {
        for (i in intents) {
            try {
                ctx.startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                hide()
                return true
            } catch (e: Exception) { }
        }
        return false
    }

    private fun cls(pkg: String, name: String) = Intent().setClassName(pkg, name)

    // ---------- the panel ----------

    /**
     * The panel: made of the same parts as the settings screen (its panel, its title, its rows,
     * its section headings and its cursor), so the two look like one thing.
     */
    private class Panel(private val ctx: Context, private val cfg: Config) : FrameLayout(ctx) {
        private val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        private var restartAsked = 0L

        init {
            isFocusable = false
            content.background = Look.panel(ctx)
            content.clipToOutline = true
            content.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            content.setPadding(ctx.dp(22f), ctx.dp(20f), ctx.dp(22f), ctx.dp(20f))
            addView(content, LayoutParams(-1, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
                topMargin = ctx.dp(26f)
                bottomMargin = ctx.dp(26f)
                rightMargin = ctx.dp(30f)
            })
            FocusGlide.install(content)

            // the title like the settings screen's, the time beside it
            val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
            head.addView(ctx.label("Quick settings", 17f, C.TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(ctx.label(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()), 17f, C.TEXT3, light = true))
            content.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(6f) })
            content.addView(list, LinearLayout.LayoutParams(-1, -2))
            showMain()
        }

        fun enter() {
            val d = resources.displayMetrics.density
            if (Motion.level != Motion.OFF) {
                content.translationX = 40 * d
                content.alpha = 0f
                content.animate().translationX(0f).alpha(1f).setDuration(220).setInterpolator(DecelerateInterpolator(2f)).start()
            }
            post { firstRow()?.requestFocus() }
        }

        private fun firstRow(): View? {
            for (i in 0 until list.childCount) list.getChildAt(i).takeIf { it.isFocusable }?.let { return it }
            return null
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            poke()
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    if (inSub) showMain() else hide()
                }
                return true
            }
            if (SettingsKey.isKey(event.keyCode)) {
                if (event.action == KeyEvent.ACTION_UP) hide()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        private var inSub = false

        private fun section(title: String) {
            list.addView(ctx.label(title, 11f, C.TEXT2, bold = true), LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = ctx.dp(14f)
                bottomMargin = ctx.dp(2f)
                leftMargin = ctx.dp(2f)
            })
        }

        private fun row(title: String, value: String? = null, onClick: (RowView) -> Unit): RowView {
            val r = RowView(ctx, title, value)
            r.setOnClickListener { onClick(r) }
            list.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4f) })
            return r
        }

        private fun showMain() {
            inSub = false
            list.removeAllViews()
            if (cfg.kids.on) {
                row("Switch to ${cfg.kids.name}'s profile") {
                    Profile.set(ctx, true)
                    open(ctx, Intent(ctx, HomeActivity::class.java))
                }
            }
            section("TV")
            row("Inputs", "›") { showInputs() }
            row("Network", network()) {
                open(ctx, Intent(android.provider.Settings.ACTION_WIFI_SETTINGS), Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
            }
            row("Sleep timer", SleepTimer.describe(ctx)) { r -> r.valueView.text = SleepTimer.next(ctx) }
            // a restart is not undone easily: the first press asks, a second one within 4 s restarts
            row("Restart", "") { r ->
                val now = System.currentTimeMillis()
                if (now - restartAsked < 4000) {
                    r.valueView.text = "Restarting…"
                    Adb.run(ctx, cfg, "reboot") { res -> if (res != Adb.Result.OK) r.valueView.text = "Couldn't restart" }
                } else {
                    restartAsked = now
                    r.valueView.text = "Press OK again"
                }
            }
            if (cfg.haEnabled && cfg.cameras.isNotEmpty()) {
                section("Home")
                row("Cameras") { open(ctx, Intent(ctx, CamerasActivity::class.java)) }
            }
            section("Settings")
            row("Launcher settings") { open(ctx, Intent(ctx, SettingsActivity::class.java)) }
            row("All TV settings") { open(ctx, Intent(android.provider.Settings.ACTION_SETTINGS)) }
            post { firstRow()?.requestFocus() }
        }

        private fun network(): String = try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "Offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                else -> "Connected"
            }
        } catch (e: Exception) { "" }

        /** Inputs, in the panel: the TV, then the HDMI inputs with what is plugged in. */
        private fun showInputs() {
            inSub = true
            list.removeAllViews()
            section("Inputs")
            var any = false
            if (Inputs.hasTv(ctx)) {
                any = true
                row("TV") { if (Inputs.openTv(ctx)) hide() }
            }
            for (inp in Inputs.visible(ctx, cfg)) {
                any = true
                row(inp.name, inp.device) { if (Inputs.open(ctx, inp.id)) hide() }
            }
            if (!any) list.addView(ctx.label("This TV reports no inputs.", 11f, C.TEXT3).apply { setPadding(ctx.dp(2f), ctx.dp(6f), 0, 0) })
            post { firstRow()?.requestFocus() }
        }
    }
}

/**
 * The sleep timer: puts the TV to sleep after 15 minutes … 2 hours, through Home Assistant's
 * ADB (an app cannot press the remote's power button). The time is kept, so the button service's
 * tick can still do it if the launcher was restarted meanwhile.
 */
object SleepTimer {
    private val STEPS = listOf(0, 15, 30, 60, 90, 120)
    private const val KEY = "sleep_at"
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private val fire = Runnable { app?.let { check(it) } }

    private fun at(ctx: Context) = Prefs.of(ctx).getLong(KEY, 0L)

    fun describe(ctx: Context): String {
        val t = at(ctx)
        val left = ((t - System.currentTimeMillis()) / 60_000L).toInt()
        return if (t == 0L || left < 0) "Off" else "${left + 1} min left"
    }

    /** One press: the next step (off → 15 → 30 → 60 → 90 → 120 → off). Returns the new value. */
    fun next(ctx: Context): String {
        val t = at(ctx)
        val left = if (t == 0L) 0 else ((t - System.currentTimeMillis()) / 60_000L).toInt() + 1
        val step = STEPS.firstOrNull { it > left } ?: 0
        set(ctx, step)
        return if (step == 0) "Off" else "$step min"
    }

    fun set(ctx: Context, minutes: Int) {
        app = ctx.applicationContext
        main.removeCallbacks(fire)
        if (minutes <= 0) {
            Prefs.of(ctx).edit().remove(KEY).apply()
            return
        }
        Prefs.of(ctx).edit().putLong(KEY, System.currentTimeMillis() + minutes * 60_000L).apply()
        main.postDelayed(fire, minutes * 60_000L + 500L)
    }

    /** Sleeps the TV when the time has come; called by the timer and by the button service's tick. */
    fun check(ctx: Context) {
        val t = at(ctx)
        if (t == 0L || System.currentTimeMillis() < t) return
        Prefs.of(ctx).edit().remove(KEY).apply()
        Adb.run(ctx.applicationContext, Config.load(ctx), "input keyevent KEYCODE_SLEEP") { }
    }
}

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
            ctx.dp(380f + 30f), WindowManager.LayoutParams.MATCH_PARENT, type,
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

    private class Panel(private val ctx: Context, private val cfg: Config) : FrameLayout(ctx) {
        private val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        private val pages = FrameLayout(ctx).apply { clipChildren = false }
        private var grid: View? = null

        init {
            isFocusable = false
            clipChildren = false
            // a floating glass card with room around it, like the settings screen's panel
            content.background = rounded(0xF0101116.toInt(), ctx.dp(20f).toFloat(), 1, C.BORDER)
            content.setPadding(ctx.dp(22f), ctx.dp(22f), ctx.dp(22f), ctx.dp(22f))
            addView(content, LayoutParams(-1, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
                topMargin = ctx.dp(26f)
                bottomMargin = ctx.dp(26f)
                rightMargin = ctx.dp(30f)
            })
            FocusGlide.install(content)

            // the time and the date, like the top of Google TV's panel
            val now = Date()
            content.addView(ctx.label(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), 30f, C.TEXT, light = true))
            content.addView(ctx.label(SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH).format(now), 12f, C.TEXT3),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(4f) })

            if (cfg.kids.on) content.addView(profileRow(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(18f) })

            content.addView(pages, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(18f) })
            showGrid()
        }

        fun enter() {
            val d = resources.displayMetrics.density
            if (Motion.level != Motion.OFF) {
                content.translationX = 60 * d
                content.alpha = 0f
                content.animate().translationX(0f).alpha(1f).setDuration(240).setInterpolator(DecelerateInterpolator(2f)).start()
            }
            post { firstTile()?.requestFocus() }
        }

        private fun firstTile(): View? = (grid as? LinearLayout)?.let { g ->
            ((g.getChildAt(0) as? LinearLayout)?.getChildAt(0))
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            poke()
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    if (grid?.visibility != View.VISIBLE) showGrid() else hide()
                }
                return true
            }
            if (SettingsKey.isKey(event.keyCode)) {
                if (event.action == KeyEvent.ACTION_UP) hide()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        /** The grown-ups' profile, and one press into the kids profile. */
        private fun profileRow(): View {
            val k = cfg.kids
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                setPadding(ctx.dp(10f), ctx.dp(9f), ctx.dp(14f), ctx.dp(9f))
            }
            row.addView(ImageView(ctx).apply { setImageDrawable(KidsLook.grownAvatar(k)) }, LinearLayout.LayoutParams(ctx.dp(34f), ctx.dp(34f)))
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val name = ctx.label(k.mainName.ifBlank { "Grown-ups" }, 14f, C.TEXT, bold = true)
            val sub = ctx.label("Switch to ${k.name}'s profile", 11f, C.TEXT3)
            texts.addView(name)
            texts.addView(sub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(2f) })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ctx.dp(12f) })
            row.addView(ImageView(ctx).apply { setImageDrawable(KidsLook.kidAvatar(k)) }, LinearLayout.LayoutParams(ctx.dp(26f), ctx.dp(26f)))
            val r = ctx.dp(16f).toFloat()
            val normal = Look.surface(ctx, r)
            row.background = normal
            FocusGlide.shape(row, r)
            row.setOnFocusChangeListener { _, f ->
                row.background = if (f) FocusGlide.fill(row, r) else normal
                name.setTextColor(if (f) C.DARK else C.TEXT)
                sub.setTextColor(if (f) C.DARK2 else C.TEXT3)
            }
            row.setOnClickListener {
                Profile.set(ctx, true)
                open(ctx, Intent(ctx, HomeActivity::class.java))
            }
            return row
        }

        private fun showGrid() {
            pages.removeAllViews()
            val g = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
            val tiles = listOf(
                Tile(R.drawable.ic_picture, "Picture", null) {
                    open(ctx, cls("com.android.tv.settings", "com.tcl.settings.PictureActivity"),
                        cls("com.android.tv.settings", "com.android.tv.settings.device.displaysound.DisplaySoundActivity"),
                        Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS))
                },
                Tile(R.drawable.ic_speaker, "Sound", null) {
                    open(ctx, cls("com.android.tv.settings", "com.tcl.settings.SoundActivity"),
                        cls("com.android.tv.settings", "com.android.tv.settings.device.displaysound.DisplaySoundActivity"),
                        Intent(android.provider.Settings.ACTION_SOUND_SETTINGS))
                },
                Tile(R.drawable.ic_input, "Inputs", null) { showInputs() },
                Tile(R.drawable.ic_wifi, "Network", network()) {
                    open(ctx, Intent(android.provider.Settings.ACTION_WIFI_SETTINGS), Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
                },
                Tile(R.drawable.ic_moon, "Screen saver", null) {
                    open(ctx, cls("com.android.systemui", "com.android.systemui.Somnambulator"), Intent(android.provider.Settings.ACTION_DREAM_SETTINGS))
                },
                Tile(R.drawable.ic_clock, "Sleep timer", SleepTimer.describe(ctx)) { t -> t.value(SleepTimer.next(ctx)) },
                Tile(R.drawable.ic_power, "Turn off", null) {
                    hide()
                    Adb.run(ctx, cfg, "input keyevent KEYCODE_SLEEP") { }
                },
                Tile(R.drawable.ic_gear, "Launcher", null) {
                    open(ctx, Intent(ctx, SettingsActivity::class.java))
                },
                Tile(R.drawable.ic_tv_gear, "All settings", null) {
                    open(ctx, Intent(android.provider.Settings.ACTION_SETTINGS))
                }
            )
            var line: LinearLayout? = null
            // three to a line, like Google TV's panel
            tiles.forEachIndexed { i, t ->
                if (i % 3 == 0) {
                    line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
                    g.addView(line, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = ctx.dp(10f) })
                }
                line!!.addView(t.view, LinearLayout.LayoutParams(0, ctx.dp(80f), 1f).apply { if (i % 3 > 0) leftMargin = ctx.dp(10f) })
            }
            grid = g
            pages.addView(g, LayoutParams(-1, -2))
            post { firstTile()?.requestFocus() }
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
            grid?.visibility = View.GONE
            val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
            list.addView(ctx.label("Inputs", 13f, C.TEXT3, bold = true).apply { letterSpacing = 0.06f },
                LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = ctx.dp(8f) })
            val rows = ArrayList<Pair<String, () -> Unit>>()
            if (Inputs.hasTv(ctx)) rows.add("TV" to { if (Inputs.openTv(ctx)) hide() })
            for (inp in Inputs.visible(ctx, cfg)) {
                rows.add((if (inp.device != null) "${inp.name} · ${inp.device}" else inp.name) to { if (Inputs.open(ctx, inp.id)) hide() })
            }
            if (rows.isEmpty()) list.addView(ctx.label("This TV reports no inputs.", 12f, C.TEXT3))
            var first: View? = null
            rows.forEachIndexed { i, (name, go) ->
                val r = RowView(ctx, name)
                r.setOnClickListener { go() }
                list.addView(r, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = ctx.dp(4f) })
                if (first == null) first = r
            }
            pages.addView(list, LayoutParams(-1, -2))
            post { first?.requestFocus() }
        }

        /** A square-ish tile: icon, name and a small value under it. */
        private inner class Tile(icon: Int, name: String, value: String?, onClick: (Tile) -> Unit) {
            val view = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                isClickable = true
                setPadding(ctx.dp(12f), ctx.dp(12f), ctx.dp(10f), ctx.dp(10f))
            }
            private val img = ImageView(ctx).apply { setImageResource(icon); setColorFilter(C.TEXT) }
            private val title = ctx.label(name, 12f, C.TEXT, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            private val sub = ctx.label(value ?: "", 10f, C.TEXT3).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            private val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            private val r = ctx.dp(16f).toFloat()
            private val normal = Look.surface(ctx, r)

            init {
                view.addView(img, LinearLayout.LayoutParams(ctx.dp(20f), ctx.dp(20f)))
                view.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
                texts.addView(title)
                texts.addView(sub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(2f) })
                sub.visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
                view.addView(texts)
                view.background = normal
                FocusGlide.shape(view, r)
                view.setOnFocusChangeListener { _, f ->
                    view.background = if (f) FocusGlide.fill(view, r) else normal
                    img.setColorFilter(if (f) C.DARK else C.TEXT)
                    title.setTextColor(if (f) C.DARK else C.TEXT)
                    sub.setTextColor(if (f) C.DARK2 else C.TEXT3)
                }
                view.setOnClickListener { onClick(this) }
            }

            fun value(v: String) {
                sub.text = v
                sub.visibility = if (v.isBlank()) View.GONE else View.VISIBLE
            }
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

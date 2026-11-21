package hu.otthon.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * The red, green, yellow and blue buttons of the remote, each with a Home Assistant action: a
 * scene, a script, a light or switch to toggle, a button to press, or a camera to pop up.
 * On the home screen the launcher sees the buttons itself; to make them work in every app,
 * [KeyService] (an accessibility service, switched on over ADB) catches them first.
 */
object ColorKeys {
    /** Config key, name shown in Settings, key code. */
    val KEYS = listOf(
        Triple("red", "Red button", KeyEvent.KEYCODE_PROG_RED),
        Triple("green", "Green button", KeyEvent.KEYCODE_PROG_GREEN),
        Triple("yellow", "Yellow button", KeyEvent.KEYCODE_PROG_YELLOW),
        Triple("blue", "Blue button", KeyEvent.KEYCODE_PROG_BLUE)
    )

    /** What a colour button may do. */
    const val DOMAINS = "scene,script,light,switch,input_boolean,button,input_button,automation,fan,camera"

    fun key(keyCode: Int): String? = KEYS.firstOrNull { it.third == keyCode }?.first

    /**
     * Not a Home Assistant entity: a colour button with this "entity" opens the quick settings,
     * for remotes without a gear button (the Sony's).
     */
    const val QUICK = "launcher.quick_settings"

    /**
     * Handles a colour button press with [cfg]'s settings. True when the button has an action
     * (both its down and up are then used up), false to leave it to whoever else wants it.
     */
    fun handle(ctx: Context, cfg: Config, event: KeyEvent): Boolean {
        val name = key(event.keyCode) ?: return false
        val item = cfg.colorKeys[name] ?: return false
        if (item.entity == QUICK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                // from the button service's own process the panel opens in the main process
                if (ctx is KeyService) PopupService.quick(ctx) else QuickPanel.toggle(ctx)
            }
            return true
        }
        if (!cfg.haEnabled) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) fire(ctx, cfg, item)
        return true
    }

    private fun fire(ctx: Context, cfg: Config, item: HaItem) {
        val app = ctx.applicationContext
        if (item.domain == "camera") {
            PopupService.showCamera(app, item.entity, item.title)
            return
        }
        val client = HaClient(cfg.haUrl!!, cfg.haToken!!)
        Bg.run({ Actions.fire(client, item); true }) { ok ->
            Toast.makeText(app, if (ok == true) "${item.title} ✓" else "Couldn't run ${item.title}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun component(ctx: Context) = "${ctx.packageName}/${KeyService::class.java.name}"

    /** Adds [KeyService] to the TV's accessibility services, keeping any others. */
    fun enableCommand(ctx: Context): String {
        val c = component(ctx)
        // AUTO_START: TCL TVs refuse to start an app's background process without it (the
        // button service has its own process); other TVs just answer that there is no such op
        return "pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS; " +
            "appops set ${ctx.packageName} AUTO_START allow 2>/dev/null; " +
            "pm grant ${ctx.packageName} android.permission.ACCESS_FINE_LOCATION 2>/dev/null; " +
            "pm grant ${ctx.packageName} android.permission.ACCESS_BACKGROUND_LOCATION 2>/dev/null; " +
            "cur=\$(settings get secure enabled_accessibility_services); " +
            "case \"\$cur\" in *KeyService*) ;; ''|null) settings put secure enabled_accessibility_services $c ;; " +
            "*) settings put secure enabled_accessibility_services \"\$cur:$c\" ;; esac; " +
            "settings put secure accessibility_enabled 1"
    }

    /** Takes [KeyService] out of the TV's accessibility services again. */
    fun disableCommand(): String =
        "cur=\$(settings get secure enabled_accessibility_services); " +
            "new=\$(echo \"\$cur\" | sed 's/[^:]*KeyService//; s/::/:/; s/^://; s/:\$//'); " +
            "if [ -z \"\$new\" ] || [ \"\$new\" = null ]; then settings delete secure enabled_accessibility_services; settings put secure accessibility_enabled 0; " +
            "else settings put secure enabled_accessibility_services \"\$new\"; fi"
}

/**
 * The remote's gear button. Google TV remotes send it as KEYCODE_NOTIFICATION (TCL) or
 * KEYCODE_SETTINGS, and Google TV home opens its settings panel for it. With Google TV home
 * switched off nobody answers it, so [KeyService] catches it and opens a settings page instead.
 */
object SettingsKey {
    /**
     * "auto": the launcher's quick settings while Google TV home is switched off; "quick": always
     * the quick settings; "display_sound" / "settings": a page of the TV's settings; "tv": always
     * leave it to the TV.
     */
    val MODES = listOf("auto", "quick", "display_sound", "settings", "tv")

    private val CODES = setOf(KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_NOTIFICATION)

    fun isKey(keyCode: Int) = keyCode in CODES

    private fun stockOn(ctx: Context): Boolean = try {
        ctx.packageManager.getApplicationInfo(Apps.stock(ctx), 0).enabled
    } catch (e: Exception) {
        false
    }

    /** True when the launcher takes the gear button over with [cfg]'s setting. */
    fun wanted(ctx: Context, cfg: Config): Boolean = when (cfg.settingsKey) {
        "tv" -> false
        "auto" -> !stockOn(ctx)
        else -> true
    }

    private fun intents(cfg: Config): List<android.content.Intent> {
        val all = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
        if (cfg.settingsKey == "settings") return listOf(all)
        return listOf(
            android.content.Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.device.displaysound.DisplaySoundActivity"),
            android.content.Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS),
            all
        )
    }

    /** Opens the chosen page; true when the key was used (its down and up both). */
    fun handle(ctx: Context, cfg: Config, event: KeyEvent): Boolean {
        if (!isKey(event.keyCode) || !wanted(ctx, cfg)) return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true
        if (cfg.settingsKey == "auto" || cfg.settingsKey == "quick") {
            android.util.Log.i("OtthonQuick", "gear ${event.keyCode} in ${ctx.javaClass.simpleName}")
            // from the button service's own process: the panel opens in the main process
            if (ctx is KeyService) PopupService.quick(ctx) else QuickPanel.toggle(ctx)
            return true
        }
        for (i in intents(cfg)) {
            try {
                ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: Exception) { }
        }
        return true
    }
}

/**
 * Android leaves an accessibility service unbound after its app is updated, although the TV's
 * settings still list it. With WRITE_SECURE_SETTINGS (granted once over ADB, kept across
 * updates) the launcher sets the list again itself, which makes Android bind it.
 */
object ServiceKeeper {
    private var lastTry = 0L
    private var lastOverlayTry = 0L

    fun ensure(ctx: Context, cfg: Config) {
        overlay(ctx, cfg)
        accessibility(ctx, cfg)
    }

    /**
     * Camera pop-ups and notifications draw over other apps, which a TV only allows over ADB.
     * When the permission is missing (a fresh install from a PC), Home Assistant's ADB gives it,
     * at most once an hour.
     */
    private fun overlay(ctx: Context, cfg: Config) {
        if (!PopupService.needed(cfg) || PopupService.allowed(ctx)) return
        val now = System.currentTimeMillis()
        if (now - lastOverlayTry < 3_600_000L) return
        lastOverlayTry = now
        Adb.run(ctx, cfg, PopupService.allowCommand(ctx)) { r -> if (r == Adb.Result.OK) PopupService.sync(ctx) }
    }

    private fun accessibility(ctx: Context, cfg: Config) {
        if (!(cfg.colorKeysEverywhere || cfg.kids.on || SettingsKey.wanted(ctx, cfg)) || KeyService.isOn(ctx)) return
        // Right after an update the installer switches the service on again itself. Doing it a
        // second time while the TV is still binding it leaves the TV waiting on a dead connection,
        // and then every key press arrives half a second late (and repeats): give it time first.
        if (android.os.Build.VERSION.SDK_INT >= 24 &&
            android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime() < 45_000L) return
        val now = System.currentTimeMillis()
        if (now - lastTry < 60_000L) return
        lastTry = now
        rebind(ctx)
    }

    /**
     * Switches the button service off and, after a pause, on again: a clean new connection.
     * Without WRITE_SECURE_SETTINGS it asks Home Assistant's ADB to do the same.
     */
    fun rebind(ctx: Context) {
        val app = ctx.applicationContext
        val me = "${app.packageName}/${KeyService::class.java.name}"
        if (app.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Adb.run(app, Config.load(app), "settings delete secure enabled_accessibility_services; sleep 2; " +
                "settings put secure enabled_accessibility_services $me; settings put secure accessibility_enabled 1") { }
            return
        }
        Thread {
            try {
                val cr = app.contentResolver
                val others = (android.provider.Settings.Secure.getString(cr, "enabled_accessibility_services") ?: "")
                    .split(':').filter { it.isNotBlank() && !it.contains("KeyService") }
                android.provider.Settings.Secure.putString(cr, "enabled_accessibility_services", others.joinToString(":"))
                Thread.sleep(2000)
                android.provider.Settings.Secure.putString(cr, "enabled_accessibility_services", (others + me).joinToString(":"))
                android.provider.Settings.Secure.putString(cr, "accessibility_enabled", "1")
            } catch (e: Exception) { }
        }.start()
    }
}

/**
 * Watches how late the remote's key presses arrive. Every key passes the button service first;
 * when the TV keeps a dead connection to it, it waits half a second for an answer that never
 * comes, and meanwhile repeats the key as if it were held (the cursor jumps several steps).
 * Here: a press that came that late counts once (its repeats are dropped), and after a few of
 * them in a row the service is switched off and on again.
 */
object KeyLag {
    private var lateCode = -1
    private var lateRun = 0
    private var lastHeal = 0L

    /** True when [e] should be dropped. Call first thing in dispatchKeyEvent. */
    fun swallow(ctx: Context, e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_UP) {
            if (e.keyCode == lateCode) lateCode = -1
            return false
        }
        if (e.action != KeyEvent.ACTION_DOWN) return false
        if (e.repeatCount > 0) return e.keyCode == lateCode
        val lag = android.os.SystemClock.uptimeMillis() - e.eventTime
        if (lag < 350) {
            lateCode = -1
            lateRun = 0
            return false
        }
        lateCode = e.keyCode
        // the TV's wait for the service is 500 ms: lags just over it are the dead connection
        if (lag in 450..900) lateRun++ else lateRun = 0
        val now = System.currentTimeMillis()
        if (lateRun >= 3 && now - lastHeal > 120_000L) {
            lastHeal = now
            lateRun = 0
            android.util.Log.w("OtthonKeys", "keys arrive ${lag} ms late: switching the button service off and on")
            ServiceKeeper.rebind(ctx)
        }
        return false
    }
}

/**
 * Sees the remote's colour buttons in every app, before the app does, and runs their actions.
 * Buttons without an action pass through untouched (teletext, the programme guide …).
 *
 * It also keeps the kids profile safe: it sees every app that comes to the front and sends the
 * TV back to the kids home screen when it is not one of the allowed apps (or the TV time is
 * over), counts the minutes spent in the allowed apps, and switches to the kids profile when the
 * TV goes to sleep, if it is set to start with it.
 */
class KeyService : AccessibilityService() {
    companion object {
        /** True inside the service's own process while it is bound. */
        @Volatile var bound = false

        /**
         * True while the TV has the service bound. It runs in a process of its own (":keys"), so
         * the launcher asks the TV's accessibility manager instead of reading a flag.
         */
        fun isOn(ctx: Context): Boolean {
            if (bound) return true
            // asked on every button press: the answer of the last few seconds will do, the
            // question itself is a trip to the system (slow on the Sony)
            val now = android.os.SystemClock.uptimeMillis()
            if (now - onAt < 3000) return onLast
            val on = try {
                val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
                am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .any { it.resolveInfo?.serviceInfo?.let { si -> si.packageName == ctx.packageName && si.name.endsWith("KeyService") } == true }
            } catch (e: Exception) {
                false
            }
            onLast = on
            onAt = now
            return on
        }

        @Volatile private var onLast = false
        @Volatile private var onAt = -10_000L
    }

    private var cfg: Config? = null
    private var cfgAt = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastCount = 0L

    private val volumeWatch by lazy { VolumeWatch(this) { config() } }

    private fun config(): Config? {
        val now = System.currentTimeMillis()
        return cfg?.takeIf { now - cfgAt < 5000 } ?: try {
            Config.load(this).also { cfg = it; cfgAt = now }
        } catch (e: Exception) {
            null
        }
    }

    private val screenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: android.content.Intent?) {
            val c = config() ?: return
            // ready for whoever comes next: the profile picker (or the kids profile) on the home screen
            if (Profile.onSleep(this@KeyService, c)) {
                try {
                    startActivity(android.content.Intent(this@KeyService, HomeActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("wake", true))
                } catch (e: Exception) { }
            }
        }
    }

    /** Twice a minute: is the time over, and one more minute watched? */
    private val tick = object : Runnable {
        override fun run() {
            handler.postDelayed(this, 30_000L)
            SleepTimer.check(this@KeyService)
            val c = config() ?: return
            if (!Profile.kids(this@KeyService, c)) return
            val front = KidsGuard.front ?: return
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isInteractive) return
            val app = applicationContext
            Bg.run({
                KidsTime.refresh(c)
                val now = System.currentTimeMillis()
                val watching = front != packageName && front in c.kids.apps
                if (watching && now - lastCount >= 59_000L) {
                    lastCount = now
                    KidsTime.countMinute(app, c)
                }
                KidsTime.status(app, c)
            }) { st ->
                if (st != null && front != packageName && KidsGuard.front == front && !KidsGuard.allowed(app, c, front, st)) {
                    KidsGuard.bounce(app, front, st.blocked)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        bound = true
        // the window changes too, not only the keys: the kids profile needs to see which app is in front
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        }
        try { registerReceiver(screenOff, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF)) } catch (e: Exception) { }
        volumeWatch.start()
        handler.postDelayed(tick, 30_000L)
        config()?.let { Profile.checkBoot(this, it) }
    }

    override fun onDestroy() {
        bound = false
        handler.removeCallbacks(tick)
        try { unregisterReceiver(screenOff) } catch (e: Exception) { }
        volumeWatch.stop()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        QuickPanel.onAppChanged(pkg, packageName)
        if (!KidsGuard.relevant(this, pkg)) return
        KidsGuard.front = pkg
        val c = config() ?: return
        if (!Profile.kids(this, c) || pkg == packageName) return
        val st = KidsTime.status(this, c)
        if (!KidsGuard.allowed(this, c, pkg, st)) KidsGuard.bounce(this, pkg, if (pkg in c.kids.apps) st.blocked else null)
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (SettingsKey.isKey(event.keyCode)) {
            val c = config() ?: return false
            if (event.action == KeyEvent.ACTION_DOWN) android.util.Log.i("OtthonQuick", "gear seen by the button service, mode ${c.settingsKey}")
            // no settings for the kids: the button stays with the TV (which, without Google TV home, ignores it)
            if (Profile.kids(this, c)) return false
            return SettingsKey.handle(this, c, event)
        }
        if (AmpVolume.isKey(event.keyCode)) {
            val c = config() ?: return false
            return AmpVolume.handle(this, c, event)
        }
        if (ColorKeys.key(event.keyCode) == null) return false
        // the settings are read again at most every few seconds, not on every key
        val c = config() ?: return false
        if (!c.colorKeysEverywhere || Profile.kids(this, c)) return false
        return ColorKeys.handle(this, c, event)
    }
}

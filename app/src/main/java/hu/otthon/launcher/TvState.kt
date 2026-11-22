package hu.otthon.launcher

import android.content.Context
import android.os.Build
import android.os.PowerManager
import org.json.JSONObject

/**
 * What this TV is doing, told to Home Assistant as a sensor of its own
 * (sensor.<the TV's name>_launcher): off, screensaver, home (the launcher in front), menu (the
 * TV's settings, the app store, the search), or watching (any other app). The launcher knows this
 * first hand, so Home Assistant no longer has to guess from the app ADB reports (a screensaver
 * counted as a film). The attributes tell the app in front, the launcher's version and the TV's
 * health: crashes in the last day, the last crash, low memory.
 *
 * Sent by the pop-up service (the main process may use the network in the background): when the
 * state changes, and every five minutes, since Home Assistant forgets such a state on restart.
 */
object TvState {
    /** The app in front, as the button service last saw it. */
    @Volatile var front: String? = null

    private var sent: String? = null
    private var sentAt = 0L

    private val MENU = setOf(
        "com.android.tv.settings", "com.google.android.tv.settings", "com.android.vending",
        "com.google.android.katniss", "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher",
        "com.spocky.projengmenu", "com.sony.dtv.sonyselect", "com.sony.dtv.helpandsupport", "com.android.settings"
    )

    /** The system's own windows come and go over apps (volume bar, toasts, the TV's overlays): they do not count. */
    fun counts(pkg: String, cls: String?, own: String): Boolean {
        if (pkg == "android" || pkg.contains("systemui") || pkg.startsWith("com.tcl.") || pkg.startsWith("com.mediatek.")) return false
        // the launcher's own overlays (quick settings, pop-ups) are not its screens
        if (pkg == own) return cls != null && cls.startsWith("$own.") && cls.endsWith("Activity")
        return true
    }

    fun isDream(pkg: String) = pkg.contains("dream", ignoreCase = true) || pkg == "com.google.android.backdrop" || pkg.contains("screensaver", ignoreCase = true)

    fun state(ctx: Context): String {
        val on = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        if (!on) return "off"
        val p = front ?: Prefs.of(ctx).getString("front_app", null) ?: return "home"
        return when {
            isDream(p) -> "screensaver"
            p == ctx.packageName -> "home"
            p in MENU -> "menu"
            else -> "watching"
        }
    }

    /** The sensor's entity id: set in the settings ("state_entity"), or from the TV's name. */
    fun entity(ctx: Context, cfg: Config): String {
        cfg.stateEntity?.let { return it }
        val name = deviceName(ctx)
        val slug = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "tv" }
        return "sensor.${slug}_launcher"
    }

    fun deviceName(ctx: Context): String = try {
        android.provider.Settings.Global.getString(ctx.contentResolver, "device_name")
    } catch (e: Exception) { null }?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** Sends the state when it changed, or when five minutes passed. Background thread only. */
    fun report(ctx: Context, cfg: Config, client: HaClient, force: Boolean = false) {
        if (!cfg.reportState) return
        val st = state(ctx)
        val now = System.currentTimeMillis()
        if (!force && st == sent && now - sentAt < 5 * 60_000L) return
        val app = front ?: Prefs.of(ctx).getString("front_app", null)
        val label = app?.let { p ->
            try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString() } catch (e: Exception) { p }
        }
        val (crashes, lastCrash) = CrashLog.recent(ctx)
        val a = JSONObject()
            .put("friendly_name", "${deviceName(ctx)} launcher")
            .put("icon", when (st) { "off" -> "mdi:television-off"; "screensaver" -> "mdi:image-filter-hdr"; "watching" -> "mdi:television-play"; else -> "mdi:television" })
            .put("app", app ?: "")
            .put("app_name", label ?: "")
            .put("version", try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "?" })
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE)
            .put("low_memory", LowRam.on)
            .put("crashes_24h", crashes)
            .put("last_crash", lastCrash ?: "")
            .put("notifications", Inbox.list(ctx).size)
            .put("updated", java.time.OffsetDateTime.now().withNano(0).toString())
        try {
            client.setState(entity(ctx, cfg), st, a)
            sent = st
            sentAt = now
        } catch (e: Exception) {
            android.util.Log.w("OtthonState", "couldn't tell Home Assistant: ${e.message}")
        }
    }
}

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
     * Handles a colour button press with [cfg]'s settings. True when the button has an action
     * (both its down and up are then used up), false to leave it to whoever else wants it.
     */
    fun handle(ctx: Context, cfg: Config, event: KeyEvent): Boolean {
        val name = key(event.keyCode) ?: return false
        val item = cfg.colorKeys[name] ?: return false
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
        return "cur=\$(settings get secure enabled_accessibility_services); " +
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
 * Sees the remote's colour buttons in every app, before the app does, and runs their actions.
 * Buttons without an action pass through untouched (teletext, the programme guide …).
 */
class KeyService : AccessibilityService() {
    companion object {
        @Volatile var running = false
    }

    private var cfg: Config? = null
    private var cfgAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (ColorKeys.key(event.keyCode) == null) return false
        // the settings are read again at most every few seconds, not on every key
        val now = System.currentTimeMillis()
        val c = cfg?.takeIf { now - cfgAt < 5000 } ?: try {
            Config.load(this).also { cfg = it; cfgAt = now }
        } catch (e: Exception) {
            return false
        }
        if (!c.colorKeysEverywhere) return false
        return ColorKeys.handle(this, c, event)
    }
}

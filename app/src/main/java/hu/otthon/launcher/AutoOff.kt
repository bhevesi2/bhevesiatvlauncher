package hu.otthon.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * Home Assistant's "nobody seems to be here" warning. Before Home Assistant switches a TV off
 * because the room looks empty, it writes a command to the helper `input_text.tv_command`:
 *
 *     id|target|auto_off|seconds      e.g. 1795725209|sensor.living_room_tv_launcher|auto_off|60
 *     id|target|cancel                the room is not empty after all: the warning goes
 *
 * `target` is the TV's state sensor (Settings › Home Assistant: the TV-state entity), so one helper
 * serves every TV. The launcher shows a countdown over whatever is on screen; any button on the
 * remote during it keeps the TV on: the key is used up (so it does not pause the film or open
 * something) and Home Assistant gets the event `otthon_tv_keep_on` ({"entity": target}), which
 * its automation waits for.
 *
 * The buttons are seen by the button service, which runs in a process of its own (":keys"): the
 * warning is announced to it with [ARM] and it answers through the pop-up service.
 */
object AutoOff {
    const val DEFAULT_ENTITY = "input_text.tv_command"
    const val EVENT = "otthon_tv_keep_on"
    const val ARM = "hu.otthon.launcher.AUTO_OFF_ARM"
    const val UNTIL = "until"

    data class Command(val id: Long, val target: String, val command: String, val seconds: Int)

    fun parse(raw: String?): Command? {
        val p = raw?.split('|') ?: return null
        if (p.size < 3) return null
        val id = p[0].trim().toLongOrNull() ?: return null
        val target = p[1].trim().takeIf { it.isNotEmpty() } ?: return null
        val seconds = p.getOrNull(3)?.trim()?.toIntOrNull()?.coerceIn(10, 300) ?: 60
        return Command(id, target, p[2].trim().lowercase(), seconds)
    }

    // ---------- in the button service's process ----------

    /** Until when a button keeps the TV on (uptime, in the ":keys" process). */
    @Volatile private var armedUntil = 0L
    /** The key whose press was used up: its release is used up too. */
    @Volatile private var swallowUp = -1

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            armedUntil = intent.getLongExtra(UNTIL, 0L)
        }
    }

    fun listen(ctx: Context) {
        val f = IntentFilter(ARM)
        try {
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
            else ctx.registerReceiver(receiver, f)
        } catch (e: Exception) { }
    }

    fun stop(ctx: Context) {
        try { ctx.unregisterReceiver(receiver) } catch (e: Exception) { }
    }

    /** A button on the remote; true when it was used up to keep the TV on. */
    fun key(ctx: Context, event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_UP && event.keyCode == swallowUp) {
            swallowUp = -1
            return true
        }
        if (event.action != android.view.KeyEvent.ACTION_DOWN) return false
        if (android.os.SystemClock.uptimeMillis() > armedUntil) return false
        armedUntil = 0L
        swallowUp = event.keyCode
        PopupService.keepOn(ctx)
        return true
    }

    /** From the pop-up service: tells the button service whether a button now keeps the TV on. */
    fun arm(ctx: Context, forMs: Long) {
        val until = if (forMs > 0) android.os.SystemClock.uptimeMillis() + forMs else 0L
        try { ctx.sendBroadcast(Intent(ARM).setPackage(ctx.packageName).putExtra(UNTIL, until)) } catch (e: Exception) { }
    }
}

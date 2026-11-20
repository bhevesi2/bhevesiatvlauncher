package hu.otthon.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * An amplifier that only listens to infrared (the Musical Fidelity M6si500), worked through a
 * Home Assistant IR remote (a Broadlink): its learnt commands are sent with remote.send_command.
 * [remote] is the remote entity, [device] the name the commands were learnt under.
 */
data class AmpRemote(val remote: String, val device: String, val up: String, val down: String, val mute: String) {
    companion object {
        const val DEVICE = "amplifier"

        fun from(o: JSONObject?): AmpRemote? {
            if (o == null) return null
            val remote = o.optString("remote", "").trim()
            if (remote.isBlank()) return null
            fun s(k: String, d: String) = o.optString(k, "").trim().ifBlank { d }
            return AmpRemote(remote, s("device", DEVICE), s("up", "volume_up"), s("down", "volume_down"), s("mute", "mute"))
        }
    }
}

/**
 * The TV remote's volume buttons, sent to the amplifier instead of the TV. [KeyService] takes
 * them in every app. Holding a button keeps stepping; steps pressed while the last ones are still
 * on their way are sent together, so the amplifier never falls behind the button.
 */
object AmpVolume {
    private val KEYS = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)

    fun isKey(code: Int) = code in KEYS

    /** At most this often while a button is held: about the pace of the amplifier's own remote. */
    private const val HOLD_STEP_MS = 140L

    private val sender = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var pending = 0          // steps still to send: up positive, down negative
    private var mutePending = false
    private var busy = false
    private var lastStepAt = 0L

    /** True when [event] was taken for the amplifier (its down and up both). */
    fun handle(ctx: Context, cfg: Config, event: KeyEvent): Boolean {
        val amp = cfg.amp ?: return false
        if (!cfg.haEnabled || !isKey(event.keyCode)) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (event.repeatCount == 0) android.util.Log.i("OtthonAmp", "volume button seen by the button service: ${KeyEvent.keyCodeToString(event.keyCode)}")
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            if (event.repeatCount > 0) return true
            synchronized(lock) { mutePending = true }
            Hud.show(ctx, Hud.MUTE)
        } else {
            if (event.repeatCount > 0 && event.eventTime - lastStepAt < HOLD_STEP_MS) return true
            lastStepAt = event.eventTime
            val d = if (code == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
            synchronized(lock) { pending += d }
            Hud.show(ctx, if (d > 0) Hud.UP else Hud.DOWN)
        }
        kick(ctx, cfg, amp)
        return true
    }

    /** Steps the amplifier [delta] steps (negative: down), for volume changes seen afterwards. */
    fun steps(ctx: Context, cfg: Config, delta: Int) {
        val amp = cfg.amp ?: return
        if (!cfg.haEnabled || delta == 0) return
        synchronized(lock) { pending += delta }
        Hud.show(ctx, if (delta > 0) Hud.UP else Hud.DOWN)
        kick(ctx, cfg, amp)
    }

    fun mute(ctx: Context, cfg: Config) {
        val amp = cfg.amp ?: return
        if (!cfg.haEnabled) return
        synchronized(lock) { mutePending = true }
        Hud.show(ctx, Hud.MUTE)
        kick(ctx, cfg, amp)
    }

    /** Sends one command now, for Settings' test rows. */
    fun test(ctx: Context, cfg: Config, command: String, done: (Boolean) -> Unit) {
        val amp = cfg.amp ?: return done(false)
        Bg.run({ send(cfg, amp, command, 1) }) { ok -> done(ok == true) }
    }

    private fun kick(ctx: Context, cfg: Config, amp: AmpRemote) {
        synchronized(lock) {
            if (busy) return
            busy = true
        }
        val app = ctx.applicationContext
        sender.execute {
            while (true) {
                val steps: Int
                val mute: Boolean
                synchronized(lock) {
                    steps = pending
                    mute = mutePending
                    pending = 0
                    mutePending = false
                    if (steps == 0 && !mute) {
                        busy = false
                        return@execute
                    }
                }
                var ok = true
                if (mute) ok = send(cfg, amp, amp.mute, 1)
                if (steps != 0) ok = send(cfg, amp, if (steps > 0) amp.up else amp.down, Math.abs(steps)) && ok
                if (!ok) Bg.main.post { Hud.show(app, Hud.FAILED) }
            }
        }
    }

    private fun send(cfg: Config, amp: AmpRemote, command: String, times: Int): Boolean = try {
        val data = JSONObject()
            .put("entity_id", amp.remote)
            .put("device", amp.device)
            .put("command", command)
        // a Broadlink waits 0.4 s between repeats unless told otherwise
        if (times > 1) data.put("num_repeats", times).put("delay_secs", 0.08)
        HaClient(cfg.haUrl!!, cfg.haToken!!).call("remote", "send_command", data, 8000)
        true
    } catch (e: Exception) {
        android.util.Log.w("OtthonAmp", "send_command $command x$times failed: $e")
        false
    }

    /** A small sign in the top right corner while the amplifier's volume is being changed. */
    private object Hud {
        const val UP = 0
        const val DOWN = 1
        const val MUTE = 2
        const val FAILED = 3

        private var view: LinearLayout? = null
        private var icon: ImageView? = null
        private var text: TextView? = null
        private val hide = Runnable { remove() }

        fun show(ctx: Context, what: Int) {
            Bg.main.post { showNow(ctx, what) }
        }

        private fun showNow(ctx: Context, what: Int) {
            if (view == null && !add(ctx)) return
            text?.text = when (what) {
                UP -> "Amplifier  ▲"
                DOWN -> "Amplifier  ▼"
                MUTE -> "Amplifier  mute"
                else -> "Couldn't reach the amplifier"
            }
            icon?.alpha = if (what == FAILED) 0.4f else 1f
            view?.animate()?.cancel()
            view?.alpha = 1f
            Bg.main.removeCallbacks(hide)
            Bg.main.postDelayed(hide, if (what == FAILED) 3000L else 1500L)
        }

        private fun add(ctx: Context): Boolean {
            val type = when {
                ctx is AccessibilityService && Build.VERSION.SDK_INT >= 22 -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                PopupService.allowed(ctx) && Build.VERSION.SDK_INT >= 26 -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else -> return false
            }
            val v = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Look.panel(ctx)
                setPadding(ctx.dp(16f), ctx.dp(10f), ctx.dp(20f), ctx.dp(10f))
            }
            val i = ImageView(ctx).apply { setImageResource(R.drawable.ic_speaker) }
            v.addView(i, LinearLayout.LayoutParams(ctx.dp(22f), ctx.dp(22f)).apply { rightMargin = ctx.dp(10f) })
            val t = ctx.label("", 14f, C.TEXT, bold = true)
            v.addView(t)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = ctx.dp(30f)
                y = ctx.dp(26f)
            }
            return try {
                (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp)
                view = v
                icon = i
                text = t
                true
            } catch (e: Exception) {
                android.util.Log.w("OtthonAmp", "no room for the volume sign: $e")
                false
            }
        }

        private fun remove() {
            val v = view ?: return
            v.animate().alpha(0f).setDuration(200).withEndAction {
                try {
                    (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
                } catch (e: Exception) { }
                if (view === v) {
                    view = null
                    icon = null
                    text = null
                }
            }.start()
        }
    }
}

/**
 * For TVs that keep the volume buttons to themselves (the TCL changes its own volume before the
 * button service hears of them): every change of the TV's volume is passed on to the amplifier as
 * steps, and the TV's volume is put straight back. The TV's volume rests one step above silent at
 * least, so that a step down is still a change it reports. Mute the same way: the amplifier mutes,
 * the TV unmutes.
 */
class VolumeWatch(private val svc: Context, private val config: () -> Config?) {
    private val audio = svc.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    private val music = android.media.AudioManager.STREAM_MUSIC
    private var anchor = -1

    private val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: android.content.Intent?) {
            i ?: return
            if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != music) return
            val cfg = config() ?: return
            if (cfg.amp == null || !cfg.haEnabled) {
                anchor = -1
                return
            }
            when (i.action) {
                VOLUME -> changed(cfg, i.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1))
                MUTE -> if (i.getBooleanExtra("android.media.EXTRA_STREAM_VOLUME_MUTED", false)) {
                    android.util.Log.i("OtthonAmp", "TV muted: muting the amplifier instead")
                    AmpVolume.mute(svc, cfg)
                    try { audio.adjustStreamVolume(music, android.media.AudioManager.ADJUST_UNMUTE, 0) } catch (e: Exception) { }
                }
            }
        }
    }

    private fun rest(): Int {
        val max = audio.getStreamMaxVolume(music)
        val now = audio.getStreamVolume(music)
        return now.coerceIn(1, maxOf(1, max - 1))
    }

    private fun changed(cfg: Config, prev: Int) {
        val now = audio.getStreamVolume(music)
        if (anchor < 0) {
            // the first change since the amplifier was chosen: the level before it is the rest
            val max = audio.getStreamMaxVolume(music)
            anchor = (if (prev >= 0) prev else now).coerceIn(1, maxOf(1, max - 1))
        }
        val delta = now - anchor
        if (delta == 0) return                     // our own putting back
        android.util.Log.i("OtthonAmp", "TV volume moved $delta: passing it to the amplifier")
        AmpVolume.steps(svc, cfg, delta)
        try { audio.setStreamVolume(music, anchor, 0) } catch (e: Exception) { }
    }

    fun start() {
        val f = android.content.IntentFilter().apply { addAction(VOLUME); addAction(MUTE) }
        try {
            if (Build.VERSION.SDK_INT >= 33) svc.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
            else svc.registerReceiver(receiver, f)
        } catch (e: Exception) { }
        // the resting level now, so the very first step down is already a change
        val cfg = config()
        if (cfg?.amp != null) {
            anchor = rest()
            if (audio.getStreamVolume(music) != anchor) try { audio.setStreamVolume(music, anchor, 0) } catch (e: Exception) { }
        }
    }

    fun stop() {
        try { svc.unregisterReceiver(receiver) } catch (e: Exception) { }
    }

    companion object {
        const val VOLUME = "android.media.VOLUME_CHANGED_ACTION"
        const val MUTE = "android.media.STREAM_MUTE_CHANGED_ACTION"
    }
}

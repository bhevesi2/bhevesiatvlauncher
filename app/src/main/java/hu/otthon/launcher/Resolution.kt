package hu.otthon.launcher

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics

/**
 * The resolution the TV draws its menus in. Many 4K TVs (the TCL among them) draw everything at
 * 1920×1080 and stretch it to the panel; `wm size` over ADB makes them draw at the panel's own
 * 3840×2160 instead. The density goes up with it, so every screen keeps its layout and only
 * gets sharper. It is the TV's setting, not the launcher's: every app's menus change with it, and
 * the TV has four times the pixels to draw, so animations may stutter more.
 *
 * Like a computer's display settings, a change goes back by itself after 20 seconds unless it
 * is kept, in case the TV does not cope with it.
 */
object Resolution {
    data class Info(val physW: Int, val physH: Int, val curW: Int, val curH: Int, val dpi: Int) {
        val uhd get() = curW >= 3000
        /** The panel has more pixels than the menus are drawn with (or is already used fully). */
        val canUhd get() = physW >= 3000
    }

    private val main = Handler(Looper.getMainLooper())
    private var revertCmd: String? = null
    private var app: Context? = null

    fun info(act: Activity): Info {
        @Suppress("DEPRECATION")
        val d = act.windowManager.defaultDisplay
        val real = DisplayMetrics()
        @Suppress("DEPRECATION")
        d.getRealMetrics(real)
        val mode = d.mode
        return Info(maxOf(mode.physicalWidth, real.widthPixels), maxOf(mode.physicalHeight, real.heightPixels), real.widthPixels, real.heightPixels, real.densityDpi)
    }

    /** The ADB command for 4K ([uhd]) or 1080p, keeping the layout (the same size in dp). */
    private fun command(i: Info, uhd: Boolean): String {
        val w = if (uhd) i.physW else 1920
        val h = if (uhd) i.physH else 1080
        val dpi = Math.round(i.dpi * w.toFloat() / i.curW)
        return "wm size ${w}x$h; wm density $dpi"
    }

    /** True while a change waits to be kept. */
    val pending get() = revertCmd != null

    fun set(act: Activity, cfg: Config, uhd: Boolean, done: (Adb.Result) -> Unit) {
        val i = info(act)
        if (i.uhd == uhd) return
        val ctx = act.applicationContext
        val back = command(i, !uhd)
        Adb.run(ctx, cfg, command(i, uhd)) { r ->
            if (r == Adb.Result.OK) {
                revertCmd = back
                app = ctx
                main.removeCallbacks(revert)
                main.postDelayed(revert, 20_000L)
            }
            done(r)
        }
    }

    fun keep() {
        revertCmd = null
        main.removeCallbacks(revert)
    }

    private val revert = Runnable {
        val cmd = revertCmd ?: return@Runnable
        val ctx = app ?: return@Runnable
        revertCmd = null
        Adb.run(ctx, Config.load(ctx), cmd) { }
    }

    /** After the screen came back in the new resolution: keep it? */
    fun askKeep(act: Activity) {
        if (!pending) return
        Popup.menu(act, "Keep this resolution?", listOf("Keep", "Go back now")) { w ->
            if (w == 0) keep() else {
                main.removeCallbacks(revert)
                revert.run()
            }
        }
    }
}

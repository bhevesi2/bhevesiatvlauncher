package hu.otthon.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * When the launcher crashes, what went wrong is written down, and shown on the home screen the
 * next time it opens: on a TV without ADB or Home Assistant (the Philips at the parents') a photo
 * of that screen is the only way to see the error. A copy stays in the launcher's files folder
 * (Android/data/hu.otthon.launcher/files/crash.txt) for ADB.
 */
object CrashLog {
    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val text = buildString {
                    append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ENGLISH).format(java.util.Date()))
                    append("  ·  ").append(try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch (x: Exception) { "?" })
                    append("  ·  Android ").append(android.os.Build.VERSION.RELEASE)
                    append("  ·  ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                    append("\nthread ").append(t.name).append('\n')
                    append(android.util.Log.getStackTraceString(e))
                }
                File(app.filesDir, "crash.txt").writeText(text)
                app.getExternalFilesDir(null)?.let { File(it, "crash.txt").writeText(text) }
            } catch (x: Throwable) { }
            previous?.uncaughtException(t, e)
        }
    }

    /** The last crash, once, on the home screen. */
    fun showIfAny(act: Activity) {
        val f = File(act.filesDir, "crash.txt")
        if (!f.exists()) return
        val text = try { f.readText() } catch (e: Exception) { return }
        f.renameTo(File(act.filesDir, "crash-shown.txt"))
        // the lines that matter: the error, and where in the launcher it happened
        val lines = text.lines()
        val short = (lines.take(4) + lines.drop(4).filter { it.contains("hu.otthon") || it.contains("Caused by") || it.contains("Exception") }.take(22))
            .joinToString("\n")
        val view = TextView(act).apply {
            this.text = short
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(C.TEXT)
            setPadding(act.dp(20f), act.dp(12f), act.dp(20f), act.dp(12f))
        }
        val scroll = ScrollView(act).apply { addView(view) }
        try {
            AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Lang.tr("The launcher stopped last time"))
                .setView(scroll)
                .setPositiveButton("OK", null)
                .show()
        } catch (e: Exception) { }
    }
}

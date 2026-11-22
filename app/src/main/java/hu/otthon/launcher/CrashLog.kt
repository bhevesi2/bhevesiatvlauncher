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
/**
 * Keeping the launcher up, on every TV:
 *  - an error on the main thread that does not break a screen being opened or drawn (a Home
 *    Assistant answer that went missing, a callback that did not expect it) is written down and
 *    the launcher goes on;
 *  - a real crash is written down, and the launcher starts again by itself: at most three times
 *    in ten minutes, so a crash that comes back at once does not loop for ever.
 * Everything goes to files/crashes.log (also in Android/data/hu.otthon.launcher/files for ADB);
 * the last crash is shown on the home screen the next time it opens, and holding OK on the gear
 * button shows the log.
 */
object CrashLog {
    @Volatile private var installed = false
    private lateinit var app: Context

    /** A launcher screen is in front: after a crash it is brought back, otherwise the app in front stays. */
    @Volatile var front = 0

    /** Main-thread errors let through recently: too many in a row means the screen is broken. */
    private val recent = ArrayDeque<Long>()

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val text = describe(t, e)
                File(app.filesDir, "crash.txt").writeText(text)
                append("CRASH", text)
                // the button service's process comes back by itself; the screens only when they were in front
                if (front > 0 && !processName().contains(':')) restartLater()
            } catch (x: Throwable) { }
            previous?.uncaughtException(t, e)
        }
        // the main thread's loop runs inside this one, which catches what may be let go
        android.os.Handler(android.os.Looper.getMainLooper()).post { guard() }
    }

    private fun guard() {
        while (true) {
            try {
                android.os.Looper.loop()
                return
            } catch (e: Throwable) {
                if (fatal(e)) throw e
                note(e)
            }
        }
    }

    /** Errors while a screen is created, resumed or drawn leave it half made: those still crash. */
    private fun fatal(e: Throwable): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        synchronized(recent) {
            recent.addLast(now)
            while (recent.isNotEmpty() && now - recent.first() > 60_000) recent.removeFirst()
            if (recent.size > 15) return true
        }
        val trace = android.util.Log.getStackTraceString(e)
        return LIFECYCLE.any { trace.contains(it) } || e is OutOfMemoryError || e is StackOverflowError
    }

    private fun processName(): String = try {
        File("/proc/self/cmdline").readText().trim('\u0000', ' ')
    } catch (e: Exception) { "" }

    private val LIFECYCLE = listOf(
        "performLaunchActivity", "handleLaunchActivity", "handleResumeActivity", "performResume",
        "handleStartActivity", "performCreate", "performStart", "handleBindApplication",
        "performTraversals", "Choreographer.doFrame", "handleCreateService", "handleReceiver",
        "handleBindService", "handleRelaunchActivity"
    )

    /** An error the launcher went on after: written down, nothing shown. */
    fun note(e: Throwable) {
        android.util.Log.w("OtthonCrash", "caught and carried on", e)
        try { append("caught", describe(Thread.currentThread(), e)) } catch (x: Throwable) { }
    }

    private fun describe(t: Thread, e: Throwable) = buildString {
        append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ENGLISH).format(java.util.Date()))
        append("  ·  ").append(try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch (x: Exception) { "?" })
        append("  ·  Android ").append(android.os.Build.VERSION.RELEASE)
        append("  ·  ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
        if (android.os.Build.VERSION.SDK_INT >= 28) append("  ·  ").append(android.app.Application.getProcessName())
        append("\nthread ").append(t.name).append('\n')
        append(android.util.Log.getStackTraceString(e))
    }

    /** The log of errors and crashes, the newest last, kept under about 100 kB. */
    @Synchronized
    private fun append(kind: String, text: String) {
        val entry = "===== $kind\n$text\n"
        for (dir in listOfNotNull(app.filesDir, app.getExternalFilesDir(null))) {
            val f = File(dir, "crashes.log")
            val old = if (f.exists()) f.readText() else ""
            val all = old + entry
            f.writeText(if (all.length > 100_000) all.substring(all.length - 80_000) else all)
        }
    }

    /**
     * Starts the launcher again a moment after the crash: at most three times in ten minutes.
     * (On the TV whose home screen it is, Android also brings it back by itself.)
     */
    private fun restartLater() {
        val p = Prefs.of(app)
        val now = System.currentTimeMillis()
        val times = p.getString("restarts", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }.filter { now - it < 600_000 }
        if (times.size >= 3) {
            p.edit().putString("restarts", times.joinToString(",")).commit()
            return
        }
        p.edit().putString("restarts", (times + now).joinToString(",")).commit()
        val intent = android.content.Intent(app, HomeActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = android.app.PendingIntent.getActivity(
            app, 7, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val am = app.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        am.set(android.app.AlarmManager.RTC, now + 1500, pi)
    }

    /** The last crash, once, on the home screen. */
    fun showIfAny(act: Activity) {
        val f = File(act.filesDir, "crash.txt")
        if (!f.exists()) {
            showFromLog(act)
            return
        }
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

    /**
     * The launcher's own log (an app may read the lines its own processes wrote, without ADB):
     * warnings and errors, newest last.
     */
    fun ownLog(max: Int = 2000): List<String> = try {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", max.toString(), "*:W"))
        val out = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        out
    } catch (e: Exception) {
        listOf("Couldn't read the log: $e")
    }

    /**
     * A crash the crash file missed (the TV stopped the launcher some other way, or a crash in
     * native code): found in the launcher's own log, shown once.
     */
    fun showFromLog(act: Activity) {
        Bg.run({
            val log = ownLog()
            val i = log.indexOfLast { it.contains("FATAL EXCEPTION") || it.contains("Fatal signal") || it.contains("AndroidRuntime: Process:") }
            if (i < 0) null else {
                val head = log[i]
                val seen = Prefs.of(act).getString("diag_seen", "")
                if (head == seen) null else {
                    Prefs.of(act).edit().putString("diag_seen", head).apply()
                    log.subList(i, minOf(log.size, i + 40)).filter { !it.contains("at android.") && !it.contains("at java.") && !it.contains("at com.android.internal") }
                }
            }
        }) { lines ->
            if (lines != null && !act.isFinishing) dialog(act, "The launcher stopped last time", (listOf(device(act)) + lines).joinToString("\n"))
        }
    }

    /** Diagnostics on demand (hold OK on the gear button): the last warnings and errors. */
    fun showLog(act: Activity) {
        Bg.run({
            val f = File(act.filesDir, "crashes.log")
            listOf(device(act)) + (if (f.exists() && f.length() > 0) f.readText().takeLast(6000).lines() else ownLog().takeLast(60))
        }) { lines ->
            if (lines != null && !act.isFinishing) dialog(act, "Launcher log", lines.joinToString("\n"))
        }
    }

    /** What the TV is: its screen as the launcher sees it, its memory. */
    fun device(ctx: Context): String {
        val m = ctx.resources.displayMetrics
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} · " +
            "${m.widthPixels}x${m.heightPixels} ${m.densityDpi} dpi · RAM ${mi.totalMem / 1048576} MB, free ${mi.availMem / 1048576} MB · " +
            "weak: ${LowRam.on} · ${try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "?" }}"
    }

    private fun dialog(act: Activity, title: String, text: String) {
        val view = TextView(act).apply {
            this.text = text
            textSize = 9f
            typeface = Typeface.MONOSPACE
            setTextColor(C.TEXT)
            setPadding(act.dp(20f), act.dp(12f), act.dp(20f), act.dp(12f))
            isFocusable = true
        }
        val scroll = ScrollView(act).apply { addView(view) }
        try {
            AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Lang.tr(title))
                .setView(scroll)
                .setPositiveButton("OK", null)
                .show()
        } catch (e: Exception) { }
    }
}

package hu.otthon.launcher

import android.content.Context
import org.json.JSONObject

/**
 * Shell commands on this TV, run by Home Assistant's Android TV (ADB) integration. Some things
 * an app may not do on its own (choosing the home app, installing an update without asking,
 * allowing pop-ups over other apps) are allowed over ADB.
 */
object Adb {
    enum class Result { OK, NO_HA, NO_TV, FAILED }

    /** Runs [command] on the TV; [done] gets the outcome on the main thread. */
    @Suppress("UNUSED_PARAMETER")
    fun run(ctx: Context, cfg: Config, command: String, timeoutMs: Int = 25000, done: (Result) -> Unit) {
        LocalAdb.init(ctx)
        if (!cfg.haEnabled) {
            if (!LocalAdb.ready()) {
                done(Result.NO_HA)
                return
            }
            Bg.run({ if (local(command)) Result.OK else Result.FAILED }) { r -> done(r ?: Result.FAILED) }
            return
        }
        val client = HaClient(cfg.haUrl!!, cfg.haToken!!)
        val configured = cfg.adbEntity
        Bg.run({
            // the TV's own ADB first; Home Assistant's when that is not (yet) allowed
            if (LocalAdb.ready() && local(command)) return@run Result.OK
            val entity = configured ?: findTv(client)
            if (entity == null) {
                Result.NO_TV
            } else {
                client.call("androidtv", "adb_command", JSONObject().put("entity_id", entity).put("command", command), timeoutMs)
                Result.OK
            }
        }) { r -> done(r ?: Result.FAILED) }
    }

    private fun local(command: String): Boolean {
        if (!LocalAdb.outlives(command)) return LocalAdb.shell(command) != null
        // the launcher may not live to hear the answer: begun is done
        LocalAdb.shell(command, readMs = 120_000)
        return true
    }

    /** The same as [run], on the calling thread: for work that already runs in the background. */
    fun runHere(cfg: Config, command: String, timeoutMs: Int = 25000): Result {
        if (LocalAdb.ready() && local(command)) return Result.OK
        if (!cfg.haEnabled) return Result.NO_HA
        val client = HaClient(cfg.haUrl!!, cfg.haToken!!)
        val entity = cfg.adbEntity ?: findTv(client) ?: return Result.NO_TV
        return try {
            client.call("androidtv", "adb_command", JSONObject().put("entity_id", entity).put("command", command), timeoutMs)
            Result.OK
        } catch (e: Exception) {
            Result.FAILED
        }
    }

    /**
     * Runs [command] and returns what it printed (background thread), or null. Home Assistant
     * keeps a command's output on the TV's player as adb_response; a marker printed first tells
     * this command's answer from an earlier one's.
     */
    fun output(cfg: Config, command: String, waitMs: Long = 8000): String? {
        if (LocalAdb.ready()) LocalAdb.shell(command)?.let { return it }
        if (!cfg.haEnabled) return null
        val client = HaClient(cfg.haUrl!!, cfg.haToken!!)
        val entity = cfg.adbEntity ?: findTv(client) ?: return null
        val mark = "@@" + java.lang.Long.toString(System.nanoTime(), 36)
        try {
            client.call("androidtv", "adb_command", JSONObject().put("entity_id", entity).put("command", "echo $mark; $command"), 25000)
        } catch (e: Exception) {
            return null
        }
        val until = System.currentTimeMillis() + waitMs
        while (true) {
            val r = try {
                client.state(entity).optJSONObject("attributes")?.optString("adb_response").orEmpty()
            } catch (e: Exception) { "" }
            if (r.startsWith(mark)) return r.substring(mark.length).trimStart('\n', '\r')
            if (System.currentTimeMillis() > until) return null
            Thread.sleep(400)
        }
    }

    /** This TV among Home Assistant's players: the ADB integration names it after its address. */
    private fun findTv(client: HaClient): String? {
        val players = client.states().filter { it.domain == "media_player" }
        val ip = Apps.localIp()?.replace('.', '_')
        return players.firstOrNull { ip != null && it.id.contains(ip) }?.id
            ?: players.filter { it.id.contains("android_tv") || it.id.contains("androidtv") }.singleOrNull()?.id
    }

    /** What to tell the user when it did not work. */
    fun explain(r: Result): String = when (r) {
        Result.OK -> "Done"
        Result.NO_HA -> "Allow the launcher on the TV's own ADB (Settings › Home screen) or set up Home Assistant: the TV only allows this over ADB"
        Result.NO_TV -> "Couldn't find this TV in Home Assistant. Choose it under Settings › Home screen › This TV in Home Assistant."
        Result.FAILED -> "Home Assistant could not run the command on the TV"
    }
}

/**
 * The apps running on the TV, as the user knows them (the ones with a tile), and closing them.
 * An app may not see other apps' processes on its own; Home Assistant's ADB can.
 */
object RunningApps {
    data class App(val pkg: String, val label: String, val mb: Int)

    /** Background thread; null when the TV could not be asked. */
    fun list(ctx: Context, cfg: Config): List<App>? {
        val out = Adb.output(cfg, "ps -A -o RSS=,NAME=") ?: return null
        val pm = ctx.packageManager
        val kb = HashMap<String, Long>()
        for (line in out.lines()) {
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size < 2) continue
            val rss = parts[0].toLongOrNull() ?: continue
            val pkg = parts[1].substringBefore(':')
            if (!pkg.contains('.') || pkg == ctx.packageName) continue
            kb[pkg] = (kb[pkg] ?: 0L) + rss
        }
        val apps = ArrayList<App>()
        for ((pkg, k) in kb) {
            // only what has a tile: services and the system stay out of it
            val launchable = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: continue
            if (launchable.component == null) continue
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
            apps.add(App(pkg, label, (k / 1024).toInt()))
        }
        return apps.sortedByDescending { it.mb }
    }

    /** Background thread. */
    fun close(cfg: Config, pkgs: List<String>): Boolean {
        if (pkgs.isEmpty()) return true
        return Adb.runHere(cfg, pkgs.joinToString("; ") { "am force-stop $it" }) == Adb.Result.OK
    }
}

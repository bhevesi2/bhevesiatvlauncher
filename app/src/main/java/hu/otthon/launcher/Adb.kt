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
        if (!cfg.haEnabled) {
            done(Result.NO_HA)
            return
        }
        val client = HaClient(cfg.haUrl!!, cfg.haToken!!)
        val configured = cfg.adbEntity
        Bg.run({
            val entity = configured ?: findTv(client)
            if (entity == null) {
                Result.NO_TV
            } else {
                client.call("androidtv", "adb_command", JSONObject().put("entity_id", entity).put("command", command), timeoutMs)
                Result.OK
            }
        }) { r -> done(r ?: Result.FAILED) }
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
        Result.NO_HA -> "Set up Home Assistant first: the TV only allows this over ADB"
        Result.NO_TV -> "Couldn't find this TV in Home Assistant. Choose it under Settings › Home screen › This TV in Home Assistant."
        Result.FAILED -> "Home Assistant could not run the command on the TV"
    }
}

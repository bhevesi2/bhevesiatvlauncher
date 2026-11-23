package hu.otthon.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Carries a TV's settings across a reinstall (the launcher's signing key changed, so Android
 * will not take the new one as an update): the settings with their secrets, and the app's own
 * stored values (the kids' profile, the music player…), in one file.
 *
 * Over ADB: `am broadcast -n hu.otthon.launcher/.CarryReceiver -a hu.otthon.launcher.EXPORT`
 * writes it to the launcher's folder on the shared storage (carry.json); it is copied away, the
 * old launcher uninstalled, the new one installed and the file put back. The new launcher takes
 * it in the first time it reads its settings, and deletes it: the token in it goes back into the
 * app's own storage.
 */
object Carry {
    const val FILE = "carry.json"

    /** Stored values that belong to this one install and must not be carried. */
    private val SKIP = setOf("local_adb", "local_adb_asked", "front", "front_app", "restarts", "restarts_at", "update_checked", "update_name")

    /** The settings and the stored values as one JSON object. */
    fun pack(config: JSONObject, prefs: Map<String, *>): JSONObject {
        val p = JSONObject()
        for ((k, v) in prefs) {
            if (k in SKIP || v == null) continue
            val e = when (v) {
                is String -> JSONObject().put("t", "s").put("v", v)
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            p.put(k, e)
        }
        return JSONObject().put("v", 1).put("config", config).put("prefs", p)
    }

    /** One stored value to put back: its key, its kind ("s", "b", "i", "l", "f", "ss") and its value. */
    class Value(val key: String, val kind: String, val value: Any)

    /** The stored values in a carried file, ready to put back. */
    fun values(carried: JSONObject): List<Value> {
        val out = ArrayList<Value>()
        val p = carried.optJSONObject("prefs") ?: return out
        for (k in p.keys()) {
            if (k in SKIP) continue
            val e = p.optJSONObject(k) ?: continue
            val v: Any = when (e.optString("t")) {
                "s" -> e.optString("v")
                "b" -> e.optBoolean("v")
                "i" -> e.optInt("v")
                "l" -> e.optLong("v")
                "f" -> e.optDouble("v").toFloat()
                "ss" -> e.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: continue
                else -> continue
            }
            out.add(Value(k, e.optString("t"), v))
        }
        return out
    }

    private fun file(ctx: Context): File? = ctx.getExternalFilesDir(null)?.let { File(it, FILE) }

    /** Writes the carried file (with the secrets). */
    fun export(ctx: Context): File? {
        val f = file(ctx) ?: return null
        val j = pack(ConfigStore.read(ctx), Prefs.of(ctx).all)
        f.writeText(j.toString(1))
        return f
    }

    /**
     * Takes in a carried file, if there is one: the settings written again (their secrets go to
     * the app's own storage), the stored values put back, the file deleted. True when it did.
     */
    @Synchronized
    fun importIfAny(ctx: Context): Boolean {
        val f = file(ctx) ?: return false
        if (!f.exists()) return false
        return try {
            val j = JSONObject(f.readText())
            j.optJSONObject("config")?.let { ConfigStore.write(ctx, it) }
            val e = Prefs.of(ctx).edit()
            for (v in values(j)) {
                @Suppress("UNCHECKED_CAST")
                when (v.kind) {
                    "s" -> e.putString(v.key, v.value as String)
                    "b" -> e.putBoolean(v.key, v.value as Boolean)
                    "i" -> e.putInt(v.key, v.value as Int)
                    "l" -> e.putLong(v.key, v.value as Long)
                    "f" -> e.putFloat(v.key, v.value as Float)
                    "ss" -> e.putStringSet(v.key, v.value as Set<String>)
                }
            }
            e.commit()
            true
        } catch (x: Exception) {
            CrashLog.note(x)
            false
        } finally {
            // never left lying on the shared storage: it holds the token
            f.delete()
        }
    }
}

/** The EXPORT broadcast (from ADB only: it needs the DUMP permission the shell has). */
class CarryReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val f = try { Carry.export(ctx) } catch (e: Exception) { CrashLog.note(e); null }
        resultData = f?.absolutePath ?: "failed"
    }
}

package hu.otthon.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object Bg {
    val exec = Executors.newFixedThreadPool(3)
    val main = Handler(Looper.getMainLooper())

    fun <T> run(work: () -> T, done: (T?) -> Unit) {
        exec.execute {
            val r = try { work() } catch (e: Exception) { null }
            main.post { done(r) }
        }
    }
}

/** Minimal Home Assistant REST client. Call only from background threads. */
class HaClient(private val base: String, private val token: String) {

    private fun open(path: String): HttpURLConnection {
        val url = if (path.startsWith("http")) path else base + path
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 6000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Content-Type", "application/json")
        return c
    }

    fun state(entity: String): JSONObject {
        val c = open("/api/states/$entity")
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }

    fun call(domain: String, service: String, entity: String) {
        val c = open("/api/services/$domain/$service")
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.outputStream.use { it.write(JSONObject().put("entity_id", entity).toString().toByteArray()) }
            c.responseCode
        } finally {
            c.disconnect()
        }
    }

    fun bitmap(path: String): Bitmap? {
        val c = open(path)
        try {
            if (c.responseCode != 200) return null
            return BitmapFactory.decodeStream(c.inputStream)
        } finally {
            c.disconnect()
        }
    }

    companion object {
        fun formatState(item: HaItem, s: JSONObject): String {
            val state = s.optString("state", "")
            val attrs = s.optJSONObject("attributes") ?: JSONObject()
            val unit = attrs.optString("unit_of_measurement", "")
            val domain = item.entity.substringBefore('.')
            val value = when {
                state == "unavailable" || state == "unknown" -> "–"
                domain == "lock" -> when (state) { "locked" -> "zárva"; "unlocked" -> "nyitva"; else -> state }
                domain == "binary_sensor" -> when (state) { "on" -> "nyitva"; "off" -> "zárva"; else -> state }
                domain == "cover" -> when (state) { "open" -> "nyitva"; "closed" -> "zárva"; else -> state }
                unit.isNotEmpty() -> {
                    val num = state.toDoubleOrNull()
                    val txt = if (num != null) {
                        if (num == Math.floor(num)) num.toLong().toString() else String.format(java.util.Locale.US, "%.1f", num)
                    } else state
                    txt.replace('.', ',') + (if (unit.startsWith("°")) unit.take(1) else " $unit")
                }
                else -> state
            }
            return if (item.label.isBlank()) value else "${item.label} $value"
        }
    }
}

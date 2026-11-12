package hu.otthon.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.Executors

object Bg {
    val exec = Executors.newFixedThreadPool(4)
    val main = Handler(Looper.getMainLooper())

    fun <T> run(work: () -> T, done: (T?) -> Unit) {
        exec.execute {
            val r = try { work() } catch (e: Exception) { null }
            main.post { done(r) }
        }
    }
}

data class EntityInfo(val id: String, val name: String, val state: String) {
    val domain: String get() = id.substringBefore('.')
}

data class Forecast(val time: Long, val condition: String, val hi: Double, val lo: Double)

/** Minimal Home Assistant REST client. Call only from background threads. */
class HaClient(private val base: String, private val token: String) {

    private fun open(path: String, readMs: Int = 6000): HttpURLConnection {
        val foreign = path.startsWith("http") && !path.startsWith(base)
        val url = if (path.startsWith("http")) path else base + path
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = readMs
        // the token is for Home Assistant only: a picture hosted elsewhere must not receive it
        if (!foreign) c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Content-Type", "application/json")
        return c
    }

    private fun getJson(path: String): JSONObject {
        val c = open(path)
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }

    fun state(entity: String): JSONObject = getJson("/api/states/$entity")

    fun call(domain: String, service: String, entity: String) {
        call(domain, service, JSONObject().put("entity_id", entity))
    }

    fun call(domain: String, service: String, data: JSONObject, readMs: Int = 6000) {
        val c = open("/api/services/$domain/$service", readMs)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.outputStream.use { it.write(data.toString().toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
        } finally {
            c.disconnect()
        }
    }

    /** Server info: version, latitude, longitude. Throws if the API does not answer. */
    fun config(): JSONObject = getJson("/api/config")

    fun ping(): String = config().optString("version", "?")

    /** Every entity with its friendly name and state, read as a stream to keep memory low. */
    fun states(): List<EntityInfo> {
        val c = open("/api/states", 20000)
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            val out = ArrayList<EntityInfo>(2048)
            JsonReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                r.beginArray()
                while (r.hasNext()) {
                    var id = ""
                    var state = ""
                    var name = ""
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "entity_id" -> id = str(r)
                            "state" -> state = str(r)
                            "attributes" -> {
                                r.beginObject()
                                while (r.hasNext()) {
                                    if (r.nextName() == "friendly_name" && r.peek() == JsonToken.STRING) name = r.nextString()
                                    else r.skipValue()
                                }
                                r.endObject()
                            }
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    if (id.isNotEmpty()) out.add(EntityInfo(id, name.ifBlank { id.substringAfter('.') }, state))
                }
            }
            return out
        } finally {
            c.disconnect()
        }
    }

    private fun str(r: JsonReader): String = when (r.peek()) {
        JsonToken.NULL -> { r.nextNull(); "" }
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        JsonToken.BOOLEAN -> r.nextBoolean().toString()
        else -> { r.skipValue(); "" }
    }

    /** State changes of one entity over the last [hours], oldest first. */
    fun history(entity: String, hours: Int): List<Pair<Long, String>> {
        val end = Instant.now()
        val start = end.minusSeconds(hours * 3600L)
        val path = "/api/history/period/$start?filter_entity_id=$entity&end_time=$end&minimal_response&no_attributes"
        val c = open(path, 45000)
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            val out = ArrayList<Pair<Long, String>>(1024)
            JsonReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                r.beginArray()
                if (r.hasNext()) {
                    r.beginArray()
                    while (r.hasNext()) {
                        var s = ""
                        var t = ""
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "state" -> s = str(r)
                                "last_changed" -> t = str(r)
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                        if (t.isNotEmpty()) {
                            val ms = try { OffsetDateTime.parse(t).toInstant().toEpochMilli() } catch (e: Exception) { 0L }
                            if (ms > 0) out.add(Pair(ms, s))
                        }
                    }
                }
            }
            return out
        } finally {
            c.disconnect()
        }
    }

    /** Daily forecast from a weather entity. */
    fun forecast(entity: String): List<Forecast> {
        val c = open("/api/services/weather/get_forecasts?return_response", 12000)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            val body = JSONObject().put("entity_id", entity).put("type", "daily")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            val root = JSONObject(c.inputStream.bufferedReader().readText())
            val arr = root.optJSONObject("service_response")?.optJSONObject(entity)?.optJSONArray("forecast")
                ?: return emptyList()
            val out = ArrayList<Forecast>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ms = try { OffsetDateTime.parse(o.optString("datetime")).toInstant().toEpochMilli() } catch (e: Exception) { continue }
                out.add(Forecast(ms, o.optString("condition", ""), o.optDouble("temperature", Double.NaN), o.optDouble("templow", Double.NaN)))
            }
            return out
        } finally {
            c.disconnect()
        }
    }

    /** Fetches an image; with maxW > 0 it is downsampled to roughly that width. */
    fun bitmap(path: String, maxW: Int = 0, readMs: Int = 6000): Bitmap? {
        val c = open(path, readMs)
        try {
            if (c.responseCode != 200) return null
            val bytes = c.inputStream.use { it.readBytes() }
            if (maxW <= 0) return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxW) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } finally {
            c.disconnect()
        }
    }
}

/** Turns Home Assistant states into short, readable text. */
object Fmt {
    fun num(v: Double, unit: String = ""): String {
        val a = Math.abs(v)
        val txt = when {
            a >= 100 || v == Math.floor(v) -> String.format(Locale.US, "%,d", Math.round(v)).replace(',', ' ')
            a >= 1 -> String.format(Locale.US, "%.1f", v).removeSuffix(".0")
            else -> String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
        }
        return when {
            unit.isEmpty() -> txt
            unit.startsWith("°") -> "$txt°"
            unit == "%" -> "$txt%"
            else -> "$txt $unit"
        }
    }

    private fun words(s: String) = s.replace('_', ' ').replaceFirstChar { it.uppercase() }

    fun isOn(domain: String, state: String): Boolean = when (domain) {
        "switch", "light", "input_boolean", "fan", "binary_sensor", "automation" -> state == "on"
        "vacuum" -> state == "cleaning"
        "climate" -> state != "off"
        else -> false
    }

    fun value(entity: String, s: JSONObject): String {
        val state = s.optString("state", "")
        val a = s.optJSONObject("attributes") ?: JSONObject()
        val domain = entity.substringBefore('.')
        if (state == "unavailable" || state == "unknown" || state.isEmpty()) return "–"
        return when (domain) {
            "climate" -> {
                val cur = a.optDouble("current_temperature", Double.NaN)
                val target = a.optDouble("temperature", Double.NaN)
                val action = a.optString("hvac_action", "")
                val parts = ArrayList<String>()
                if (!cur.isNaN()) parts.add(num(cur, "°"))
                if (!target.isNaN() && state != "off") parts.add("→ " + num(target, "°"))
                if (state == "off") parts.add("off") else if (action.isNotEmpty()) parts.add("· $action")
                parts.joinToString(" ")
            }
            "binary_sensor" -> {
                val on = state == "on"
                when (a.optString("device_class", "")) {
                    "door", "garage_door", "window", "opening" -> if (on) "Open" else "Closed"
                    "lock" -> if (on) "Unlocked" else "Locked"
                    "motion", "occupancy", "presence" -> if (on) "Detected" else "Clear"
                    "connectivity" -> if (on) "Connected" else "Offline"
                    "problem", "safety", "smoke", "gas", "moisture", "tamper" -> if (on) "Alert" else "OK"
                    else -> if (on) "On" else "Off"
                }
            }
            "switch", "light", "input_boolean", "fan", "automation" -> if (state == "on") "On" else "Off"
            "person", "device_tracker" -> when (state) { "home" -> "Home"; "not_home" -> "Away"; else -> words(state) }
            "weather" -> {
                val t = a.optDouble("temperature", Double.NaN)
                (if (!t.isNaN()) num(t, "°") + " " else "") + words(state)
            }
            "scene", "script", "button", "input_button" -> "Run"
            else -> {
                val unit = a.optString("unit_of_measurement", "")
                val n = state.toDoubleOrNull()
                if (n != null) num(n, unit) else words(state)
            }
        }
    }

    fun chip(item: HaItem, s: JSONObject): String {
        val v = value(item.entity, s)
        return if (item.label.isBlank()) v else "${item.label} $v"
    }
}

/** What pressing OK on an entity does. */
object Actions {
    enum class Kind { TOGGLE, RUN, SELECT, VACUUM, NONE }

    fun kind(item: HaItem): Kind = if (item.ro) Kind.NONE else when (item.domain) {
        "switch", "light", "input_boolean", "fan", "automation" -> Kind.TOGGLE
        "scene", "script", "button", "input_button" -> Kind.RUN
        "input_select", "select" -> Kind.SELECT
        "vacuum" -> Kind.VACUUM
        else -> Kind.NONE
    }

    /** Runs the default action for toggles and one-shot entities. Background thread only. */
    fun fire(client: HaClient, item: HaItem) {
        when (item.domain) {
            "scene", "script" -> client.call(item.domain, "turn_on", item.entity)
            "button", "input_button" -> client.call(item.domain, "press", item.entity)
            else -> client.call("homeassistant", "toggle", item.entity)
        }
    }
}

package hu.otthon.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

data class HaItem(val entity: String, val label: String, val icon: String = "", val ro: Boolean = false) {
    val domain: String get() = entity.substringBefore('.')
    val title: String get() = label.ifBlank { entity.substringAfter('.').replace('_', ' ') }
}

data class DashSection(val title: String, val hideWhen: String?, val items: List<HaItem>)

data class Config(
    val haUrl: String?,
    val haToken: String?,
    val lat: Double,
    val lon: Double,
    val favorites: List<String>,
    val status: List<HaItem>,
    val scenes: List<HaItem>,
    val cameras: List<HaItem>,
    val nowPlaying: String?,
    val nowPlayingLabel: String,
    val background: String,
    val dim: Float,
    val weather: String?,
    val sections: List<DashSection>,
    val inputsHidden: Set<String>,
    val inputNames: Map<String, String>,
    val animations: String
) {
    val haEnabled: Boolean get() = !haUrl.isNullOrBlank() && !haToken.isNullOrBlank()

    companion object {
        val DEFAULT_FAVORITES = listOf(
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.skyshowtime.skyshowtime.google",
            "com.amazon.amazonvideo.livingroom",
            "com.wbd.stream",
            "org.videolan.vlc"
        )

        fun load(ctx: Context): Config {
            ConfigStore.migrate(ctx)
            val json = ConfigStore.read(ctx)
            val dash = json.optJSONObject("dashboard") ?: JSONObject()
            val names = HashMap<String, String>()
            json.optJSONObject("input_names")?.let { o -> o.keys().forEach { k -> names[k] = o.optString(k) } }
            val animations = json.optString("animations", "full").let { if (it == "reduced" || it == "off") it else "full" }
            Motion.level = when (animations) { "off" -> Motion.OFF; "reduced" -> Motion.REDUCED; else -> Motion.FULL }
            return Config(
                haUrl = json.optString("ha_url", "").trim().trimEnd('/').ifBlank { null },
                haToken = json.optString("ha_token", "").trim().ifBlank { null },
                lat = json.optDouble("lat", 47.63),
                lon = json.optDouble("lon", 17.73),
                favorites = strings(json.optJSONArray("favorites")).ifEmpty { DEFAULT_FAVORITES },
                status = items(json.optJSONArray("status")),
                scenes = items(json.optJSONArray("scenes")),
                cameras = items(json.optJSONArray("cameras")),
                nowPlaying = json.optString("now_playing", "").ifBlank { null },
                nowPlayingLabel = json.optString("now_playing_label", "").ifBlank { "Now playing" },
                background = Backgrounds.normalize(json.optString("background", "alpine")),
                dim = json.optDouble("dim", 0.7).toFloat().coerceIn(0f, 1f),
                weather = dash.optString("weather", "").ifBlank { null },
                sections = sections(dash.optJSONArray("sections")),
                inputsHidden = strings(json.optJSONArray("inputs_hidden")).toSet(),
                inputNames = names,
                animations = animations
            )
        }

        private fun strings(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optString(it, "").ifBlank { null } }
        }

        fun items(arr: JSONArray?): List<HaItem> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val entity = o.optString("entity", "")
                if (entity.isBlank()) null
                else HaItem(entity, o.optString("label", ""), o.optString("icon", ""), o.optBoolean("ro", false))
            }
        }

        private fun sections(arr: JSONArray?): List<DashSection> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                DashSection(
                    o.optString("title", "").ifBlank { "Section ${i + 1}" },
                    o.optString("hide_when", "").ifBlank { null },
                    items(o.optJSONArray("items"))
                )
            }
        }
    }
}

/** How much the launcher animates. Follows the "animations" setting whenever it is loaded. */
object Motion {
    const val OFF = 0
    const val REDUCED = 1
    const val FULL = 2
    var level = FULL
}

/** The launcher's settings live in one JSON file that both the settings screens and adb can edit. */
object ConfigStore {
    fun file(ctx: Context): File = File(ctx.getExternalFilesDir(null), "config.json")

    fun read(ctx: Context): JSONObject = try {
        val f = file(ctx)
        if (f.exists()) JSONObject(f.readText()) else JSONObject()
    } catch (e: Exception) {
        JSONObject()
    }

    fun write(ctx: Context, json: JSONObject): Boolean = try {
        val f = file(ctx)
        f.parentFile?.mkdirs()
        f.writeText(json.toString(1))
        true
    } catch (e: Exception) {
        false
    }

    fun edit(ctx: Context, block: (JSONObject) -> Unit): Boolean {
        val j = read(ctx)
        return try {
            block(j)
            write(ctx, j)
        } catch (e: Exception) {
            false
        }
    }

    fun array(o: JSONObject, key: String): JSONArray {
        var a = o.optJSONArray(key)
        if (a == null) {
            a = JSONArray()
            o.put(key, a)
        }
        return a
    }

    fun obj(o: JSONObject, key: String): JSONObject {
        var c = o.optJSONObject(key)
        if (c == null) {
            c = JSONObject()
            o.put(key, c)
        }
        return c
    }

    fun swap(a: JSONArray, i: Int, j: Int) {
        if (i < 0 || j < 0 || i >= a.length() || j >= a.length()) return
        val t = a.get(i)
        a.put(i, a.get(j))
        a.put(j, t)
    }

    /** Older builds kept background and darkening in preferences; move them into the file once. */
    fun migrate(ctx: Context) {
        val p = Prefs.of(ctx)
        if (!p.contains("background") && !p.contains("dim")) return
        edit(ctx) { j ->
            p.getString("background", null)?.let { j.put("background", it) }
            if (p.contains("dim")) j.put("dim", p.getFloat("dim", 0.7f).toDouble())
        }
        p.edit().remove("background").remove("dim").apply()
    }
}

object Prefs {
    fun of(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** Temporary time-of-day preview, valid for 10 minutes. */
    fun setPhaseOverride(ctx: Context, p: Phase?) {
        val e = of(ctx).edit()
        if (p == null) e.remove("phase").remove("phase_until")
        else e.putString("phase", p.key).putLong("phase_until", System.currentTimeMillis() + 10 * 60_000L)
        e.apply()
    }

    fun phaseOverride(ctx: Context): Phase? {
        val p = of(ctx)
        if (p.getLong("phase_until", 0L) < System.currentTimeMillis()) return null
        val k = p.getString("phase", null) ?: return null
        return Phase.values().firstOrNull { it.key == k }
    }
}

object FavStore {
    private const val KEY = "favorites"

    fun get(ctx: Context, cfg: Config): MutableList<String> {
        val s = Prefs.of(ctx).getString(KEY, null) ?: return cfg.favorites.toMutableList()
        return s.split(',').filter { it.isNotBlank() }.toMutableList()
    }

    fun set(ctx: Context, list: List<String>) {
        Prefs.of(ctx).edit().putString(KEY, list.joinToString(",")).apply()
    }

    fun reset(ctx: Context) {
        Prefs.of(ctx).edit().remove(KEY).apply()
    }
}

/**
 * Background sets:
 *  "alpine", "hills"       – built-in vector landscapes
 *  "asset:<name>"          – photo set bundled in the APK (assets/backgrounds/<name>/)
 *  "foto:<name>"           – photo set in files/backgrounds/<name>/ on the TV
 */
object Backgrounds {
    data class Option(val key: String, val title: String, val subtitle: String)

    private val EXT = listOf("jpg", "jpeg", "png", "webp")
    private var cacheKey: String? = null
    private var cacheBmp: Bitmap? = null

    fun normalize(k: String): String = when (k) {
        "alpesi", "" -> "alpine"
        "domb" -> "hills"
        else -> k
    }

    fun phase(ctx: Context, cfg: Config): Phase =
        Prefs.phaseOverride(ctx) ?: Sun.phase(System.currentTimeMillis(), cfg.lat, cfg.lon)

    fun options(ctx: Context): List<Option> {
        val list = ArrayList<Option>()
        try {
            ctx.assets.list("backgrounds")?.sorted()?.forEach { name ->
                list.add(Option("asset:$name", title(name), "Photos"))
            }
        } catch (e: Exception) { }
        ctx.getExternalFilesDir("backgrounds")?.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach {
            list.add(Option("foto:${it.name}", title(it.name), "Your photos"))
        }
        list.add(Option("alpine", "Alpine lake", "Illustration"))
        list.add(Option("hills", "Rolling hills", "Illustration"))
        return list
    }

    private fun title(name: String) = name.replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercase() }

    fun isPhoto(key: String) = key.startsWith("asset:") || key.startsWith("foto:")

    /** Loads the photo for a phase, or null if the set has no such image. Call off the main thread. */
    @Synchronized
    fun photo(ctx: Context, key: String, phase: Phase, w: Int = 1920, h: Int = 1080): Bitmap? {
        val ck = key + "/" + phase.key
        if (ck == cacheKey && cacheBmp != null) return cacheBmp
        val opener: (() -> InputStream)? = when {
            key.startsWith("asset:") -> {
                val dir = "backgrounds/" + key.removePrefix("asset:")
                val files = try { ctx.assets.list(dir)?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
                val name = EXT.map { "${phase.key}.$it" }.firstOrNull { files.contains(it) }
                name?.let { n -> { ctx.assets.open("$dir/$n") } }
            }
            key.startsWith("foto:") -> {
                val dir = File(ctx.getExternalFilesDir("backgrounds"), key.removePrefix("foto:"))
                val f = EXT.map { File(dir, "${phase.key}.$it") }.firstOrNull { it.exists() }
                f?.let { file -> { file.inputStream() } }
            }
            else -> null
        }
        if (opener == null) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        opener().use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val bmp = opener().use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }
        if (bmp != null) {
            cacheKey = ck
            cacheBmp = bmp
        }
        return bmp
    }
}

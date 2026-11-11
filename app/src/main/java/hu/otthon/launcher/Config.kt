package hu.otthon.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

data class HaItem(val entity: String, val label: String, val icon: String)

data class Config(
    val haUrl: String?,
    val haToken: String?,
    val lat: Double,
    val lon: Double,
    val favorites: List<String>,
    val status: List<HaItem>,
    val scenes: List<HaItem>,
    val nowPlaying: String?,
    val nowPlayingLabel: String,
    val background: String,
    val dim: Float
) {
    val haEnabled: Boolean get() = !haUrl.isNullOrBlank() && !haToken.isNullOrBlank()

    override fun toString(): String =
        "Config(ha=$haUrl, fav=$favorites, status=$status, scenes=$scenes, np=$nowPlaying/$nowPlayingLabel, bg=$background, dim=$dim, $lat,$lon)"

    companion object {
        val DEFAULT_FAVORITES = listOf(
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.skyshowtime.skyshowtime.google",
            "com.amazon.amazonvideo.livingroom",
            "com.wbd.stream",
            "org.videolan.vlc"
        )

        fun file(ctx: Context): File = File(ctx.getExternalFilesDir(null), "config.json")

        fun load(ctx: Context): Config {
            val json = try {
                val f = file(ctx)
                if (f.exists()) JSONObject(f.readText()) else JSONObject()
            } catch (e: Exception) {
                JSONObject()
            }
            val p = Prefs.of(ctx)
            return Config(
                haUrl = json.optString("ha_url", "").trimEnd('/').ifBlank { null },
                haToken = json.optString("ha_token", "").ifBlank { null },
                lat = json.optDouble("lat", 47.63),
                lon = json.optDouble("lon", 17.73),
                favorites = strings(json.optJSONArray("favorites")).ifEmpty { DEFAULT_FAVORITES },
                status = items(json.optJSONArray("status")),
                scenes = items(json.optJSONArray("scenes")),
                nowPlaying = json.optString("now_playing", "").ifBlank { null },
                nowPlayingLabel = json.optString("now_playing_label", "Now playing"),
                background = Backgrounds.normalize(p.getString("background", null) ?: json.optString("background", "alpine")),
                dim = (if (p.contains("dim")) p.getFloat("dim", 0.7f) else json.optDouble("dim", 0.7).toFloat()).coerceIn(0f, 1f)
            )
        }

        private fun strings(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optString(it, "").ifBlank { null } }
        }

        private fun items(arr: JSONArray?): List<HaItem> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val entity = o.optString("entity", "")
                if (entity.isBlank()) null
                else HaItem(entity, o.optString("label", ""), o.optString("icon", ""))
            }
        }
    }
}

object Prefs {
    fun of(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun setBackground(ctx: Context, key: String) = of(ctx).edit().putString("background", key).apply()
    fun setDim(ctx: Context, v: Float) = of(ctx).edit().putFloat("dim", v).apply()

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

    fun normalize(k: String): String = when (k) {
        "alpesi", "" -> "alpine"
        "domb" -> "hills"
        else -> k
    }

    fun options(ctx: Context): List<Option> {
        val list = mutableListOf(
            Option("alpine", "Alpine lake", "Illustration"),
            Option("hills", "Rolling hills", "Illustration")
        )
        try {
            ctx.assets.list("backgrounds")?.sorted()?.forEach { name ->
                list.add(Option("asset:$name", title(name), "Photos"))
            }
        } catch (e: Exception) { }
        ctx.getExternalFilesDir("backgrounds")?.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach {
            list.add(Option("foto:${it.name}", title(it.name), "Your photos"))
        }
        return list
    }

    private fun title(name: String) = name.replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercase() }

    fun isPhoto(key: String) = key.startsWith("asset:") || key.startsWith("foto:")

    /** Loads the photo for a phase, or null if the set has no such image. Call off the main thread. */
    fun photo(ctx: Context, key: String, phase: Phase, w: Int = 1920, h: Int = 1080): Bitmap? {
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
        return opener().use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }
    }
}

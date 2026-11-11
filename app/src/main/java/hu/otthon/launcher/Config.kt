package hu.otthon.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
            return Config(
                haUrl = json.optString("ha_url", "").trimEnd('/').ifBlank { null },
                haToken = json.optString("ha_token", "").ifBlank { null },
                lat = json.optDouble("lat", 47.63),
                lon = json.optDouble("lon", 17.73),
                favorites = strings(json.optJSONArray("favorites")).ifEmpty { DEFAULT_FAVORITES },
                status = items(json.optJSONArray("status")),
                scenes = items(json.optJSONArray("scenes")),
                nowPlaying = json.optString("now_playing", "").ifBlank { null },
                nowPlayingLabel = json.optString("now_playing_label", "Most szól"),
                background = json.optString("background", "alpesi"),
                dim = json.optDouble("dim", 0.7).toFloat().coerceIn(0f, 1f)
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

object FavStore {
    private const val PREFS = "launcher"
    private const val KEY = "favorites"

    fun get(ctx: Context, cfg: Config): MutableList<String> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = p.getString(KEY, null) ?: return cfg.favorites.toMutableList()
        return s.split(',').filter { it.isNotBlank() }.toMutableList()
    }

    fun set(ctx: Context, list: List<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, list.joinToString(",")).apply()
    }
}

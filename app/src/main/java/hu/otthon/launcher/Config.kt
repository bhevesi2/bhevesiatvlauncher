package hu.otthon.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

data class HaItem(
    val entity: String,
    val label: String,
    val icon: String = "",
    val ro: Boolean = false,
    /** For a car: an image or camera entity, or a URL, to show as its picture. */
    val picture: String? = null
) {
    val domain: String get() = entity.substringBefore('.')
    val title: String get() = label.ifBlank { entity.substringAfter('.').replace('_', ' ') }
}

data class DashSection(val title: String, val hideWhen: String?, val items: List<HaItem>)

data class Config(
    val haUrl: String?,
    val haToken: String?,
    /** The main switch: off, the launcher works without Home Assistant and keeps its settings. */
    val haOn: Boolean,
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
    val animations: String,
    /** This TV's Android TV (ADB) media player in Home Assistant; null = find it by the TV's address. */
    val adbEntity: String?,
    /** Camera pop-ups over other apps. */
    val popups: Boolean,
    val popupSeconds: Int,
    val popupTriggers: List<PopupTrigger>,
    /** Home Assistant notifications as a card over other apps (script "TV notification"). */
    val notify: Boolean,
    val notifySeconds: Int,
    /** The text helper Home Assistant writes the notifications into. */
    val notifyEntity: String,
    /** The outside weather as the first chip on the home screen. */
    val weatherChip: Boolean,
    /** To-do lists shown on the Today panel. */
    val todoLists: List<HaItem>,
    /** Live camera video over RTSP instead of still pictures. */
    val liveVideo: Boolean,
    /** Stream address; {host} is Home Assistant's address, {camera} the camera's name. */
    val liveUrl: String,
    /** The streaming apps' recommendation rows below the home screen. */
    val recommendations: Boolean,
    val recoHidden: Set<String>,
    /** The "Continue watching" row at the top of the recommendations. */
    val recoWatchNext: Boolean,
    /** Recommendation rows in the order chosen on the home screen (row keys); the rest follow. */
    val recoOrder: List<String>,
    /** Bears and bunnies on the home screen: 0 none, 1 a few, 2 a whole family. */
    val critters: Int,
    /** Christmas: reindeer instead of bears and bunnies, Santa's sleigh, fairy lights as the cursor. */
    val christmas: Boolean,
    /** OLED care: the home screen moves by a few pixels every minute. Not needed on LCD TVs. */
    val pixelShift: Boolean = true,
    /** The remote's colour buttons ("red", "green", "yellow", "blue") and what each one does. */
    val colorKeys: Map<String, HaItem>,
    /** True: the colour buttons work in every app (KeyService), not only on the home screen. */
    val colorKeysEverywhere: Boolean,
    /** "standard": the app banners as tiles; "live": taller tiles showing the apps' recommendations. */
    val design: String,
    /** The Windows share with the "Wallpapers (NAS)" picture set; null: none. */
    val nas: NasSource?,
    /** The amplifier the volume buttons work, through a Home Assistant IR remote; null: the TV's own volume. */
    val amp: AmpRemote? = null,
    /** Lights on the quick settings' Home tiles; empty: the light groups of the TV's room. */
    val quickLights: List<HaItem>,
    /** What the remote's gear button opens: "auto", "display_sound", "settings" or "tv" (see [SettingsKey]). */
    val settingsKey: String,
    /** The kids profile: its apps, PIN and time limits. */
    val kids: Kids
) {
    val haEnabled: Boolean get() = haOn && !haUrl.isNullOrBlank() && !haToken.isNullOrBlank()

    /** The live stream of a camera entity, or null when live video is off. */
    fun stream(camera: String): String? {
        if (!liveVideo) return null
        val host = try { android.net.Uri.parse(haUrl ?: "").host } catch (e: Exception) { null } ?: return null
        return liveUrl.replace("{host}", host).replace("{camera}", camera.substringAfter('.'))
    }

    companion object {
        /** Frigate's RTSP restream (port 8554 of the Frigate add-on). */
        const val DEFAULT_LIVE_URL = "rtsp://{host}:8554/{camera}"

        /** Where Home Assistant's "TV notification" script puts the latest notification. */
        const val DEFAULT_NOTIFY_ENTITY = "input_text.tv_notification"

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
            FocusGlide.festive = json.optBoolean("christmas", false)
            Lang.set(json.optString("language", "en"))
            return Config(
                haUrl = json.optString("ha_url", "").trim().trimEnd('/').ifBlank { null },
                haToken = json.optString("ha_token", "").trim().ifBlank { null },
                haOn = json.optBoolean("ha_on", true),
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
                animations = animations,
                adbEntity = json.optString("adb_entity", "").ifBlank { null },
                popups = (json.optJSONObject("popups") ?: JSONObject()).optBoolean("enabled", true),
                popupSeconds = (json.optJSONObject("popups") ?: JSONObject()).optInt("seconds", 20).coerceIn(5, 120),
                popupTriggers = popupTriggers(json),
                notify = (json.optJSONObject("notify") ?: JSONObject()).optBoolean("enabled", true),
                notifySeconds = (json.optJSONObject("notify") ?: JSONObject()).optInt("seconds", 12).coerceIn(5, 60),
                notifyEntity = (json.optJSONObject("notify") ?: JSONObject()).optString("entity", "").ifBlank { DEFAULT_NOTIFY_ENTITY },
                weatherChip = json.optBoolean("weather_chip", true),
                todoLists = json.optJSONArray("todo_lists")?.let { items(it) } ?: listOf(HaItem("todo.shopping_list", "")),
                liveVideo = (json.optJSONObject("live") ?: JSONObject()).optBoolean("enabled", false),
                liveUrl = (json.optJSONObject("live") ?: JSONObject()).optString("url", "").ifBlank { DEFAULT_LIVE_URL },
                recommendations = json.optBoolean("recommendations", true),
                recoHidden = strings(json.optJSONArray("reco_hidden")).toSet(),
                recoWatchNext = json.optBoolean("reco_watch_next", true),
                recoOrder = strings(json.optJSONArray("reco_order")),
                critters = json.optInt("critters", 0).coerceIn(0, 2),
                christmas = json.optBoolean("christmas", false),
                pixelShift = json.optBoolean("pixel_shift", true),
                colorKeys = colorKeys(json.optJSONObject("color_keys")),
                colorKeysEverywhere = json.optBoolean("color_keys_everywhere", false),
                quickLights = items(json.optJSONArray("quick_lights")),
                nas = NasSource.from(json.optJSONObject("nas")),
                amp = AmpRemote.from(json.optJSONObject("amp_volume")),
                design = json.optString("design", "standard").let { if (it == "live") "live" else "standard" },
                settingsKey = json.optString("settings_key", "auto").let { if (it in SettingsKey.MODES) it else "auto" },
                kids = Kids.from(json.optJSONObject("kids"))
            )
        }

        /**
         * What opens a camera pop-up. Until the list is edited in the settings, every camera on the
         * camera wall except indoor ones pops up when Frigate sees a person on it.
         */
        fun popupTriggers(json: JSONObject): List<PopupTrigger> {
            val arr = json.optJSONObject("popups")?.optJSONArray("triggers")
            if (arr != null) {
                return (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val e = o.optString("entity", "")
                    if (e.isBlank()) null else PopupTrigger(e, o.optString("camera", "").ifBlank { null } ?: PopupTrigger.cameraFor(e))
                }
            }
            return items(json.optJSONArray("cameras"))
                .filter { !it.entity.contains("indoor") }
                .map { cam -> PopupTrigger("binary_sensor." + cam.entity.substringAfter('.') + "_person_occupancy", cam.entity) }
        }

        private fun strings(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optString(it, "").ifBlank { null } }
        }

        private fun colorKeys(o: JSONObject?): Map<String, HaItem> {
            if (o == null) return emptyMap()
            val map = LinkedHashMap<String, HaItem>()
            for (k in o.keys()) {
                val e = o.optJSONObject(k) ?: continue
                val entity = e.optString("entity", "")
                if (entity.isNotBlank()) map[k] = HaItem(entity, e.optString("label", ""))
            }
            return map
        }

        fun items(arr: JSONArray?): List<HaItem> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val entity = o.optString("entity", "")
                if (entity.isBlank()) null
                else HaItem(
                    entity, o.optString("label", ""), o.optString("icon", ""), o.optBoolean("ro", false),
                    o.optString("picture", "").ifBlank { null }
                )
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

/** A sensor that opens a camera pop-up when it turns on, and the camera to show. */
data class PopupTrigger(val entity: String, val camera: String) {
    /** "Entrance · person" from binary_sensor.entrance_camera_person_occupancy. */
    val title: String
        get() {
            val cam = camera.substringAfter('.').removeSuffix("_camera").replace('_', ' ').replaceFirstChar { it.uppercase() }
            val what = entity.substringAfter('.').removePrefix(camera.substringAfter('.')).trim('_')
                .removeSuffix("_occupancy").removeSuffix("occupancy").replace('_', ' ').trim()
            return if (what.isBlank()) cam else "$cam · $what"
        }

    companion object {
        /** binary_sensor.driveway_camera_person_occupancy → camera.driveway_camera */
        fun cameraFor(entity: String): String {
            val name = entity.substringAfter('.')
            val i = name.indexOf("_camera")
            return "camera." + if (i > 0) name.substring(0, i + 7) else name.substringBeforeLast('_')
        }
    }
}

/** How much the launcher animates. Follows the "animations" setting whenever it is loaded. */
object Motion {
    const val OFF = 0
    const val REDUCED = 1
    const val FULL = 2
    var level = FULL

    /** The "emphasized" easing: a quick start that settles slowly and softly. */
    val EASE: android.view.animation.Interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
}

/**
 * Follows a target the way a critically damped spring does: it never overshoots, and a new
 * target on the way bends the motion smoothly instead of restarting it. [stiffness] is the
 * spring's angular frequency: about 14 settles in half a second. Runs on the display's frames.
 */
class Follow(private val stiffness: Float, private val apply: (Float) -> Unit) : android.view.Choreographer.FrameCallback {
    var value = 0f
        private set
    var target = 0f
        private set
    private var velocity = 0f
    private var last = 0L
    var running = false
        private set
    /** Called once the value has come to rest on the target. */
    var onRest: (() -> Unit)? = null

    fun snap(v: Float) {
        stop()
        value = v
        target = v
        apply(v)
        onRest?.invoke()
    }

    fun to(t: Float) {
        target = t
        if (Motion.level == Motion.OFF) { snap(t); return }
        if (!running) {
            if (value == t) return
            running = true
            last = 0L
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun stop() {
        if (running) android.view.Choreographer.getInstance().removeFrameCallback(this)
        running = false
        velocity = 0f
    }

    override fun doFrame(now: Long) {
        if (!running) return
        val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0.001f, 0.05f)
        last = now
        val w = stiffness
        // the exact step of a critically damped spring, so it stays smooth on slow frames too
        val x = value - target
        val e = kotlin.math.exp(-w * dt)
        val nx = (x + (velocity + w * x) * dt) * e
        velocity = (velocity - w * (velocity + w * x) * dt) * e
        value = target + nx
        if (kotlin.math.abs(nx) < 0.4f && kotlin.math.abs(velocity) < 8f) {
            value = target
            velocity = 0f
            running = false
            apply(value)
            onRest?.invoke()
            return
        }
        apply(value)
        android.view.Choreographer.getInstance().postFrameCallback(this)
    }
}

/** The launcher's settings live in one JSON file that both the settings screens and adb can edit. */
object ConfigStore {
    fun file(ctx: Context): File = File(ctx.getExternalFilesDir(null), "config.json")

    // the file's text as last read: the screens ask for the settings often, the file seldom changes
    private var memoText: String? = null
    private var memoStamp = 0L to 0L

    /** The last good settings, kept beside the file: used when the file itself cannot be read. */
    private fun spare(ctx: Context) = File(file(ctx).path + ".bak")

    /**
     * The settings. A file that cannot be read (cut short by a power cut, say) does not count as
     * empty settings, which the next change would then write back: the spare copy is used.
     */
    fun read(ctx: Context): JSONObject {
        val f = file(ctx)
        try {
            if (f.exists()) return JSONObject(text(f))
        } catch (e: Exception) { }
        try {
            val b = spare(ctx)
            if (b.exists()) return JSONObject(b.readText())
        } catch (e: Exception) { }
        return JSONObject()
    }

    @Synchronized
    private fun text(f: File): String {
        val stamp = f.lastModified() to f.length()
        memoText?.let { if (stamp == memoStamp) return it }
        val t = f.readText()
        memoText = t
        memoStamp = stamp
        return t
    }

    /**
     * Writes the settings whole or not at all: into a new file first, which then takes the old
     * one's place, so a power cut never leaves half a file. The old one, when it was good, stays
     * as the spare.
     */
    fun write(ctx: Context, json: JSONObject): Boolean = try {
        val f = file(ctx)
        f.parentFile?.mkdirs()
        val text = json.toString(1)
        try {
            if (f.exists() && f.length() > 2) {
                JSONObject(f.readText())
                f.copyTo(spare(ctx), overwrite = true)
            }
        } catch (e: Exception) { }
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
        synchronized(this) { memoText = null }
        true
    } catch (e: Exception) {
        CrashLog.note(e)
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
    /**
     * The button service runs in a process of its own; MULTI_PROCESS makes each process read the
     * file again when the other one changed it (the profile, the PIN, the sleep timer).
     */
    @Suppress("DEPRECATION")
    fun of(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_MULTI_PROCESS)

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
 *  "nas"                   – the pictures in a folder of a Windows share ([NasWallpapers])
 */
object Backgrounds {
    data class Option(val key: String, val title: String, val subtitle: String)

    private val EXT = listOf("jpg", "jpeg", "png", "webp")
    // the last photo, key and picture together: read on a background thread, let go on the main one
    @Volatile private var cache: Pair<String, Bitmap>? = null

    /** Forgets the decoded photo; the one on screen stays with its view. */
    fun trim() { cache = null }

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
                val n = files(ctx, "asset:$name").size
                list.add(Option("asset:$name", title(name), if (n > 4) "$n photos, a new one every hour" else "Photos"))
            }
        } catch (e: Exception) { }
        ctx.getExternalFilesDir("backgrounds")?.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach {
            val n = files(ctx, "foto:${it.name}").size
            list.add(Option("foto:${it.name}", title(it.name), if (n > 4) "Your photos: $n, a new one every hour" else "Your photos"))
        }
        if (Config.load(ctx).nas != null) list.add(Option(NasWallpapers.KEY, "Wallpapers (NAS)", NasWallpapers.describe(ctx)))
        list.add(Option("alpine", "Alpine lake", "Illustration"))
        list.add(Option("hills", "Rolling hills", "Illustration"))
        return list
    }

    private fun title(name: String) = name.replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercase() }

    fun isPhoto(key: String) = key.startsWith("asset:") || key.startsWith("foto:") || key == NasWallpapers.KEY

    private const val OFFSET = "bg_offset"

    /** Which of [count] pictures is on now: a new one every hour, Next wallpaper skips ahead. */
    fun turn(ctx: Context, count: Int): Int {
        if (count <= 0) return 0
        val zone = java.util.TimeZone.getDefault()
        val now = System.currentTimeMillis()
        val hours = (now + zone.getOffset(now)) / 3_600_000L
        val skip = Prefs.of(ctx).getInt(OFFSET, 0)
        return (((hours + skip) % count + count) % count).toInt()
    }

    /** Next wallpaper: the next picture of the set, now. */
    fun next(ctx: Context) {
        val p = Prefs.of(ctx)
        p.edit().putInt(OFFSET, p.getInt(OFFSET, 0) + 1).apply()
    }

    private val listings = HashMap<String, Pair<Long, List<String>>>()

    /** File names in a set's folder. Bundled sets never change; folders on the TV are re-read every minute. */
    @Synchronized
    private fun files(ctx: Context, key: String): List<String> {
        val now = System.currentTimeMillis()
        val cached = listings[key]
        if (cached != null && (key.startsWith("asset:") || now - cached.first < 60_000L)) return cached.second
        val list: List<String> = try {
            when {
                key.startsWith("asset:") -> ctx.assets.list("backgrounds/" + key.removePrefix("asset:"))?.toList()
                key.startsWith("foto:") -> File(ctx.getExternalFilesDir("backgrounds"), key.removePrefix("foto:")).list()?.toList()
                else -> null
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        listings[key] = Pair(now, list)
        return list
    }

    /**
     * The picture to show for a phase right now, or null if the set has none for it. A set may
     * hold several per phase (hajnal.jpg, hajnal2.jpg, hajnal3.jpg ...); they take turns, a new
     * one every hour.
     */
    fun variant(ctx: Context, key: String, phase: Phase): String? {
        if (key == NasWallpapers.KEY) return NasWallpapers.pick(ctx, phase, FocusGlide.festive)?.path
        val match = Regex("^" + phase.key + "[-_]?(\\d*)\\.(jpg|jpeg|png|webp)$")
        val names = files(ctx, key).filter { match.matches(it.lowercase()) }
            .sortedWith(compareBy({ match.find(it.lowercase())?.groupValues?.get(1)?.toIntOrNull() ?: 0 }, { it }))
        if (names.isEmpty()) return null
        return names[turn(ctx, names.size)]
    }

    /** Loads the photo for a phase, or null if the set has no such image. Call off the main thread. */
    @Synchronized
    fun photo(ctx: Context, key: String, phase: Phase, w: Int = 1920, h: Int = 1080): Bitmap? {
        if (key == NasWallpapers.KEY) {
            // the folder is looked at again every half hour; only the picture on screen is fetched
            val cfg = Config.load(ctx)
            NasWallpapers.refreshIfStale(ctx, cfg)
            val pic = NasWallpapers.pick(ctx, phase, FocusGlide.festive) ?: return null
            if (NasWallpapers.file(ctx, cfg, pic) == null) return null
        }
        val name = variant(ctx, key, phase) ?: return null
        val ck = "$key/$name"
        cache?.let { if (it.first == ck) return it.second }
        val opener: (() -> InputStream)? = when {
            key.startsWith("asset:") -> {
                { ctx.assets.open("backgrounds/" + key.removePrefix("asset:") + "/" + name) }
            }
            key.startsWith("foto:") -> {
                { File(File(ctx.getExternalFilesDir("backgrounds"), key.removePrefix("foto:")), name).inputStream() }
            }
            key == NasWallpapers.KEY -> {
                val pic = NasWallpapers.pictures(ctx).firstOrNull { it.path == name } ?: return null
                val f = NasWallpapers.file(ctx, Config.load(ctx), pic) ?: return null
                ({ f.inputStream() })
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
        if (bmp != null) cache = ck to bmp
        return bmp
    }
}

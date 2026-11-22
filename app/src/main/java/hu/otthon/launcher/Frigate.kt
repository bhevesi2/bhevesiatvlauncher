package hu.otthon.launcher

import org.json.JSONArray
import org.json.JSONObject

/** A Frigate detection with its recording. Times in seconds since 1970. */
data class Detection(
    val id: String,
    val camera: String,
    val label: String,
    val subLabel: String,
    val zones: List<String>,
    val start: Double,
    val end: Double,
    val score: Double
) {
    /** "Person", "Car · Walkway 1". */
    val title: String
        get() {
            val what = (subLabel.ifBlank { label }).replace('_', ' ').replaceFirstChar { it.uppercase() }
            val where = zones.firstOrNull()?.replace('_', ' ')
            return if (where.isNullOrBlank()) what else "$what · $where"
        }

    val seconds: Int get() = if (end > start) Math.round(end - start).toInt() else 0
}

/**
 * Frigate's detections for one camera, through the Frigate integration of Home Assistant: the
 * list over the WebSocket API, the pictures and clips through its notification links.
 */
object Frigate {
    /** The Frigate camera behind a Home Assistant camera, or null when it is not Frigate's. */
    data class Cam(val instance: String, val name: String)

    private val cams = HashMap<String, Cam?>()

    /** Reads the camera's attributes once (background thread). */
    fun cam(client: HaClient, entity: String): Cam? {
        synchronized(cams) { if (cams.containsKey(entity)) return cams[entity] }
        val a = try { client.state(entity).optJSONObject("attributes") } catch (e: Exception) { return null }
        val name = a?.optString("camera_name").orEmpty()
        val c = if (name.isBlank()) null else Cam(a!!.optString("client_id").ifBlank { "frigate" }, name)
        synchronized(cams) { cams[entity] = c }
        return c
    }

    /** The newest detections with a recording, older than [before] when given (background thread). */
    fun detections(cfg: Config, cam: Cam, before: Double? = null, limit: Int = 40): List<Detection> {
        val cmd = JSONObject()
            .put("type", "frigate/events/get")
            .put("instance_id", cam.instance)
            .put("cameras", JSONArray().put(cam.name))
            .put("has_clip", true)
            .put("limit", limit)
        if (before != null) cmd.put("before", before.toLong())
        val r = HaWs.call(cfg.haUrl!!, cfg.haToken!!, cmd)
        val list = when (r) {
            is JSONArray -> r
            is String -> JSONArray(r)
            else -> JSONArray()
        }
        val out = ArrayList<Detection>()
        for (i in 0 until list.length()) {
            val e = list.optJSONObject(i) ?: continue
            val zones = e.optJSONArray("zones")
            out.add(
                Detection(
                    id = e.optString("id"),
                    camera = e.optString("camera"),
                    label = e.optString("label"),
                    subLabel = e.opt("sub_label")?.let { if (it is JSONArray) it.optString(0) else it.toString() }
                        ?.takeIf { it != "null" }.orEmpty(),
                    zones = (0 until (zones?.length() ?: 0)).map { zones!!.optString(it) },
                    start = e.optDouble("start_time", 0.0),
                    end = e.optDouble("end_time", 0.0).let { if (it.isNaN()) 0.0 else it },
                    score = e.optJSONObject("data")?.optDouble("top_score", 0.0) ?: 0.0
                )
            )
        }
        return out
    }

    fun thumbnail(cam: Cam, d: Detection) = "/api/frigate/${cam.instance}/notifications/${d.id}/thumbnail.jpg"

    /**
     * Where the recording can be played: the clip Frigate cuts for the detection, and failing
     * that the recording of the same minutes as a stream.
     */
    fun clips(cfg: Config, cam: Cam, d: Detection): List<String> {
        val base = cfg.haUrl!!.trimEnd('/')
        val from = Math.floor(d.start).toLong() - 2
        val to = Math.ceil(if (d.end > d.start) d.end else d.start + 30).toLong() + 2
        return listOf(
            "$base/api/frigate/${cam.instance}/notifications/${d.id}/clip.mp4",
            "$base/api/frigate/${cam.instance}/vod/${cam.name}/start/$from/end/$to/index.m3u8"
        )
    }
}

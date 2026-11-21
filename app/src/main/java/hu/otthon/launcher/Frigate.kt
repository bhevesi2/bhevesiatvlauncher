package hu.otthon.launcher

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom

/**
 * One command over Home Assistant's WebSocket API, for the few things it has no REST call for
 * (Frigate's detections). Connects, signs in, sends, waits for the answer, hangs up. Background
 * thread only.
 */
object HaWs {
    fun call(base: String, token: String, command: JSONObject, readMs: Int = 15000): Any? {
        val url = URL(base)
        val secure = url.protocol == "https"
        val port = if (url.port > 0) url.port else if (secure) 443 else 80
        val socket: Socket = if (secure) {
            javax.net.ssl.SSLSocketFactory.getDefault().createSocket() as Socket
        } else Socket()
        socket.connect(InetSocketAddress(url.host, port), 5000)
        socket.soTimeout = readMs
        try {
            val out = socket.getOutputStream()
            val inp = BufferedInputStream(socket.getInputStream())
            val key = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val handshake = "GET /api/websocket HTTP/1.1\r\n" +
                "Host: ${url.host}:$port\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: ${Base64.encodeToString(key, Base64.NO_WRAP)}\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n"
            out.write(handshake.toByteArray())
            out.flush()
            val status = line(inp)
            if (!status.contains(" 101")) throw IllegalStateException("no WebSocket: $status")
            while (line(inp).isNotEmpty()) { }

            fun next(): JSONObject = JSONObject(receive(inp, out))
            if (next().optString("type") != "auth_required") throw IllegalStateException("unexpected greeting")
            send(out, JSONObject().put("type", "auth").put("access_token", token).toString())
            val auth = next()
            if (auth.optString("type") != "auth_ok") throw IllegalStateException("sign-in refused")
            command.put("id", 1)
            send(out, command.toString())
            while (true) {
                val m = next()
                if (m.optInt("id") != 1 || m.optString("type") != "result") continue
                if (!m.optBoolean("success")) {
                    throw IllegalStateException(m.optJSONObject("error")?.optString("message") ?: "failed")
                }
                return m.opt("result")
            }
        } finally {
            try { socket.close() } catch (e: Exception) { }
        }
    }

    private fun line(inp: InputStream): String {
        val b = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0) throw IllegalStateException("connection closed")
            if (c == '\n'.code) break
            if (c != '\r'.code) b.append(c.toChar())
        }
        return b.toString()
    }

    private fun readFully(inp: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r < 0) throw IllegalStateException("connection closed")
            off += r
        }
        return buf
    }

    /** One whole text message, its pieces put together; pings answered on the way. */
    private fun receive(inp: InputStream, out: OutputStream): String {
        val msg = ByteArrayOutputStream()
        while (true) {
            val h = readFully(inp, 2)
            val fin = (h[0].toInt() and 0x80) != 0
            val op = h[0].toInt() and 0x0F
            var len = (h[1].toInt() and 0x7F).toLong()
            if (len == 126L) {
                val e = readFully(inp, 2)
                len = ((e[0].toInt() and 0xFF) shl 8 or (e[1].toInt() and 0xFF)).toLong()
            } else if (len == 127L) {
                val e = readFully(inp, 8)
                len = 0
                for (x in e) len = (len shl 8) or (x.toLong() and 0xFF)
            }
            if (len > 64L * 1024 * 1024) throw IllegalStateException("message too large")
            val payload = readFully(inp, len.toInt())
            when (op) {
                0, 1 -> {
                    msg.write(payload)
                    if (fin) return msg.toString("UTF-8")
                }
                8 -> throw IllegalStateException("closed by Home Assistant")
                9 -> frame(out, 0xA, payload)
            }
        }
    }

    private fun send(out: OutputStream, text: String) = frame(out, 0x1, text.toByteArray())

    /** A client frame: always masked. */
    private fun frame(out: OutputStream, op: Int, payload: ByteArray) {
        val head = ByteArrayOutputStream()
        head.write(0x80 or op)
        val n = payload.size
        when {
            n < 126 -> head.write(0x80 or n)
            n < 65536 -> {
                head.write(0x80 or 126)
                head.write(n ushr 8 and 0xFF)
                head.write(n and 0xFF)
            }
            else -> {
                head.write(0x80 or 127)
                for (i in 7 downTo 0) head.write(((n.toLong() ushr (8 * i)) and 0xFF).toInt())
            }
        }
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        head.write(mask)
        val masked = ByteArray(n) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        out.write(head.toByteArray())
        out.write(masked)
        out.flush()
    }
}

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

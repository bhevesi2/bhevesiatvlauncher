package hu.otthon.launcher

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.security.SecureRandom

/**
 * A connection to Home Assistant's WebSocket API, signed in. Background threads only. Used for
 * one command ([HaWs.call]) and for a lasting subscription ([HaWatch]).
 */
class HaSocket(base: String, private val token: String, readMs: Int) {
    private val socket: Socket
    private val inp: InputStream
    private val out: OutputStream
    private var nextId = 1

    init {
        val url = URL(base)
        val secure = url.protocol == "https"
        val port = if (url.port > 0) url.port else if (secure) 443 else 80
        socket = if (secure) javax.net.ssl.SSLSocketFactory.getDefault().createSocket() as Socket else Socket()
        try {
            socket.connect(InetSocketAddress(url.host, port), 5000)
            socket.soTimeout = readMs
            inp = BufferedInputStream(socket.getInputStream())
            out = socket.getOutputStream()
            val key = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val handshake = "GET /api/websocket HTTP/1.1\r\n" +
                "Host: ${url.host}:$port\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: ${Base64.encodeToString(key, Base64.NO_WRAP)}\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n"
            synchronized(this) {
                out.write(handshake.toByteArray())
                out.flush()
            }
            val status = line()
            if (!status.contains(" 101")) throw IllegalStateException("no WebSocket: $status")
            while (line().isNotEmpty()) { }
            if (next()?.optString("type") != "auth_required") throw IllegalStateException("unexpected greeting")
            send(JSONObject().put("type", "auth").put("access_token", token))
            if (next()?.optString("type") != "auth_ok") throw IllegalStateException("sign-in refused")
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    /** Sends [command] with a new id; returns the id. */
    fun command(command: JSONObject): Int {
        val id = synchronized(this) { nextId++ }
        send(command.put("id", id))
        return id
    }

    /** Waits for the answer to command [id] and returns its result; throws when it failed. */
    fun result(id: Int): Any? {
        while (true) {
            val m = next() ?: continue
            if (m.optInt("id") != id || m.optString("type") != "result") continue
            if (!m.optBoolean("success")) throw IllegalStateException(m.optJSONObject("error")?.optString("message") ?: "failed")
            return m.opt("result")
        }
    }

    /**
     * The next message. Null when nothing came within the read time while the line was idle
     * (a lasting subscription then pings); a time-out in the middle of a message throws.
     */
    fun next(): JSONObject? {
        val text = receive() ?: return null
        return JSONObject(text)
    }

    fun ping() = send(JSONObject().put("type", "ping").put("id", synchronized(this) { nextId++ }))

    fun close() {
        try { socket.close() } catch (e: Exception) { }
    }

    private fun send(j: JSONObject) = frame(0x1, j.toString().toByteArray())

    private fun line(): String {
        val b = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0) throw IllegalStateException("connection closed")
            if (c == '\n'.code) break
            if (c != '\r'.code) b.append(c.toChar())
        }
        return b.toString()
    }

    private fun readFully(n: Int): ByteArray {
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
    private fun receive(): String? {
        val msg = ByteArrayOutputStream()
        while (true) {
            // the first byte of a frame: here, and only here, an idle line may time out
            val b0 = try {
                inp.read()
            } catch (e: SocketTimeoutException) {
                if (msg.size() == 0) return null else throw e
            }
            if (b0 < 0) throw IllegalStateException("connection closed")
            val b1 = readFully(1)[0].toInt()
            val fin = (b0 and 0x80) != 0
            val op = b0 and 0x0F
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) {
                val e = readFully(2)
                len = ((e[0].toInt() and 0xFF) shl 8 or (e[1].toInt() and 0xFF)).toLong()
            } else if (len == 127L) {
                val e = readFully(8)
                len = 0
                for (x in e) len = (len shl 8) or (x.toLong() and 0xFF)
            }
            if (len > 64L * 1024 * 1024) throw IllegalStateException("message too large")
            val payload = readFully(len.toInt())
            when (op) {
                0, 1 -> {
                    msg.write(payload)
                    if (fin) return msg.toString("UTF-8")
                }
                8 -> throw IllegalStateException("closed by Home Assistant")
                9 -> frame(0xA, payload)
            }
        }
    }

    /** A client frame: always masked. One writer at a time. */
    @Synchronized
    private fun frame(op: Int, payload: ByteArray) {
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

/** One command over the WebSocket API, for the few things it has no REST call for (Frigate's detections). */
object HaWs {
    fun call(base: String, token: String, command: JSONObject, readMs: Int = 15000): Any? {
        val s = HaSocket(base, token, readMs)
        try {
            return s.result(s.command(command))
        } finally {
            s.close()
        }
    }
}

/**
 * A lasting subscription to the state changes of a few entities (subscribe_entities): [onState]
 * hears each new state at once, with the time it changed. Reconnects by itself, waiting longer
 * each time it fails (up to a minute); [connected] tells whether it is live, so a caller can
 * fall back to asking now and then.
 */
class HaWatch(
    private val base: String,
    private val token: String,
    private val entities: List<String>,
    private val onState: (entity: String, state: String, changedMs: Long, first: Boolean) -> Unit
) {
    @Volatile var connected = false
        private set
    @Volatile private var running = true
    @Volatile private var socket: HaSocket? = null
    private val thread = Thread({ loop() }, "ha-watch")

    fun start(): HaWatch {
        thread.isDaemon = true
        thread.start()
        return this
    }

    fun stop() {
        running = false
        socket?.close()
        thread.interrupt()
    }

    private fun loop() {
        var wait = 2_000L
        while (running) {
            try {
                // a ping every 30 s keeps the line honest: no answer within 75 s, and it is redone
                val s = HaSocket(base, token, 30_000)
                socket = s
                try {
                    val id = s.command(JSONObject().put("type", "subscribe_entities").put("entity_ids", org.json.JSONArray(entities)))
                    s.result(id)
                    connected = true
                    wait = 2_000L
                    var quiet = 0
                    while (running) {
                        val m = s.next()
                        if (m == null) {
                            if (++quiet > 2) throw IllegalStateException("no answer from Home Assistant")
                            s.ping()
                            continue
                        }
                        quiet = 0
                        if (m.optString("type") == "event" && m.optInt("id") == id) handle(m.optJSONObject("event"))
                    }
                } finally {
                    connected = false
                    socket = null
                    s.close()
                }
            } catch (e: Exception) {
                if (!running) return
                android.util.Log.w("OtthonWatch", "Home Assistant's live updates: ${e.message}; again in ${wait / 1000} s")
            } catch (e: Throwable) {
                CrashLog.note(e)
            }
            try { Thread.sleep(wait) } catch (e: InterruptedException) { return }
            wait = minOf(60_000L, wait * 2)
        }
    }

    /** "a": the states as they are now (on subscribing); "c": changes, "+" holding what is new. */
    private fun handle(ev: JSONObject?) {
        if (ev == null) return
        ev.optJSONObject("a")?.let { a ->
            for (e in a.keys()) {
                val o = a.optJSONObject(e) ?: continue
                deliver(e, o.optString("s"), o.optDouble("lc", o.optDouble("lu", 0.0)), true)
            }
        }
        ev.optJSONObject("c")?.let { c ->
            for (e in c.keys()) {
                val plus = c.optJSONObject(e)?.optJSONObject("+") ?: continue
                if (!plus.has("s")) continue        // only the attributes changed
                deliver(e, plus.optString("s"), plus.optDouble("lc", plus.optDouble("lu", System.currentTimeMillis() / 1000.0)), false)
            }
        }
    }

    private fun deliver(entity: String, state: String, changedSec: Double, first: Boolean) {
        try {
            onState(entity, state, (changedSec * 1000).toLong(), first)
        } catch (e: Throwable) {
            CrashLog.note(e)
        }
    }
}

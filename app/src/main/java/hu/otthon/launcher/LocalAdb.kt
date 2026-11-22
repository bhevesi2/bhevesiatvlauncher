package hu.otthon.launcher

import android.content.Context
import android.util.Base64
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * The TV's own ADB, reached from inside the TV (127.0.0.1:5555, the network ADB Home Assistant
 * uses too). With it the launcher does what only ADB may (closing apps, turning the TV off,
 * giving itself permissions) without Home Assistant. The first connection asks on the TV
 * ("Allow USB debugging?"); once allowed (always), it no longer asks.
 *
 * A small client of the ADB protocol: connect, sign in with the launcher's own RSA key, run one
 * shell command, read what it prints. Background threads only.
 */
object LocalAdb {
    private const val CNXN = 0x4e584e43
    private const val AUTH = 0x48545541
    private const val OPEN = 0x4e45504f
    private const val OKAY = 0x59414b4f
    private const val CLSE = 0x45534c43
    private const val WRTE = 0x45545257
    private const val VERSION = 0x01000001
    private const val MAXDATA = 256 * 1024
    private const val PORT = 5555

    private const val KEY_STATE = "local_adb"   // "ok" once the TV took the key

    @Volatile private var app: Context? = null

    fun init(ctx: Context) {
        if (app == null) app = ctx.applicationContext
    }

    /**
     * Once, on the home screen: asks the TV to allow the launcher's key, while someone is there
     * to answer. Later only from Settings.
     */
    fun askOnce(ctx: Context) {
        init(ctx)
        if (ready()) return
        val p = Prefs.of(ctx)
        if (p.getBoolean("local_adb_asked", false)) return
        p.edit().putBoolean("local_adb_asked", true).apply()
        Bg.run({ shell("echo ok", askMs = 120_000) != null }) { ok ->
            android.util.Log.i("OtthonAdb", "own ADB allowed: $ok")
        }
    }

    /** The TV has allowed the launcher's key: commands go here first. */
    fun ready(): Boolean {
        val c = app ?: return false
        return Prefs.of(c).getString(KEY_STATE, "") == "ok"
    }

    /**
     * Runs [command] in the TV's shell and returns what it printed, or null when the TV's ADB
     * could not be reached or did not let the launcher in. [askMs]: how long to wait for the
     * TV's "Allow?" question to be answered when the key is new (0: don't wait for it).
     */
    fun shell(command: String, askMs: Int = 0, readMs: Int = 20_000): String? {
        val ctx = app ?: return null
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress("127.0.0.1", PORT), 2000)
            socket.soTimeout = 5000
            val inp = DataInputStream(socket.getInputStream().buffered())
            val out = socket.getOutputStream()
            if (!signIn(ctx, inp, out, socket, askMs)) return null
            socket.soTimeout = readMs
            send(out, OPEN, 1, 0, ("shell:$command").toByteArray() + 0.toByte())
            var remote = 0
            val text = StringBuilder()
            while (true) {
                val m = read(inp)
                when (m.cmd) {
                    OKAY -> remote = m.arg0
                    WRTE -> {
                        text.append(String(m.data, Charsets.UTF_8))
                        send(out, OKAY, 1, m.arg0, ByteArray(0))
                    }
                    CLSE -> {
                        if (remote != 0) send(out, CLSE, 1, remote, ByteArray(0))
                        return text.toString()
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } catch (e: Exception) {
            android.util.Log.w("OtthonAdb", "own ADB: $e")
            null
        } finally {
            try { socket.close() } catch (e: Exception) { }
        }
    }

    /**
     * For commands that outlive the launcher (installing the launcher itself kills it midway):
     * run apart from this connection, so closing it does not stop them.
     */
    fun detached(command: String): Boolean {
        if (command.contains('\'')) return shell(command) != null
        return shell("nohup sh -c '$command' </dev/null >/data/local/tmp/otthon-adb.log 2>&1 &") != null
    }

    /** Commands that replace or stop the launcher while they run. */
    fun outlives(command: String) = command.contains("pm install") || command.contains("reboot")

    private fun signIn(ctx: Context, inp: DataInputStream, out: OutputStream, socket: Socket, askMs: Int): Boolean {
        send(out, CNXN, VERSION, MAXDATA, "host::launcher\u0000".toByteArray())
        val key = keys(ctx)
        var signed = false
        var offered = false
        while (true) {
            val m = read(inp)
            when (m.cmd) {
                CNXN -> {
                    Prefs.of(ctx).edit().putString(KEY_STATE, "ok").apply()
                    return true
                }
                AUTH -> {
                    if (m.arg0 != 1) continue
                    if (!signed) {
                        signed = true
                        send(out, AUTH, 2, 0, sign(key.first, m.data))
                    } else if (!offered) {
                        // the key is new to the TV: it asks on screen
                        if (askMs <= 0) {
                            Prefs.of(ctx).edit().putString(KEY_STATE, "").apply()
                            return false
                        }
                        offered = true
                        socket.soTimeout = askMs
                        send(out, AUTH, 3, 0, (key.second + " otthon-launcher\u0000").toByteArray())
                    } else {
                        return false
                    }
                }
            }
        }
    }

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun read(inp: DataInputStream): Msg {
        val head = ByteArray(24)
        inp.readFully(head)
        val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int
        val a0 = b.int
        val a1 = b.int
        val len = b.int
        if (len < 0 || len > 4 * MAXDATA) throw IllegalStateException("bad ADB message")
        val data = ByteArray(len)
        inp.readFully(data)
        return Msg(cmd, a0, a1, data)
    }

    private fun send(out: OutputStream, cmd: Int, a0: Int, a1: Int, data: ByteArray) {
        var sum = 0
        for (x in data) sum += x.toInt() and 0xFF
        val b = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(sum).putInt(cmd xor -1)
        b.put(data)
        out.write(b.array())
        out.flush()
    }

    // ---------- the launcher's key ----------

    /** PKCS#1 v1.5 over the token as a SHA-1 digest: what adbd checks. */
    private fun sign(key: PrivateKey, token: ByteArray): ByteArray {
        val prefix = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        c.init(Cipher.ENCRYPT_MODE, key)
        return c.doFinal(prefix + token)
    }

    /** The private key, and the public one in adbd's own format (base64). Made once. */
    @Synchronized
    private fun keys(ctx: Context): Pair<PrivateKey, String> {
        val priv = File(ctx.filesDir, "adbkey")
        val pub = File(ctx.filesDir, "adbkey.x509")
        val kf = KeyFactory.getInstance("RSA")
        if (!priv.exists() || !pub.exists()) {
            val g = KeyPairGenerator.getInstance("RSA")
            g.initialize(2048)
            val kp = g.generateKeyPair()
            priv.writeBytes(kp.private.encoded)
            pub.writeBytes(kp.public.encoded)
        }
        val p = kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes()))
        val q = kf.generatePublic(X509EncodedKeySpec(pub.readBytes())) as RSAPublicKey
        return p to Base64.encodeToString(adbPublicKey(q), Base64.NO_WRAP)
    }

    /**
     * adbd's RSAPublicKey: words in the modulus, n0inv = -1/n mod 2^32, the modulus and R² mod n
     * as little-endian 32-bit words, then the exponent.
     */
    private fun adbPublicKey(k: RSAPublicKey): ByteArray {
        val words = 2048 / 32
        val n = k.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toLong().toInt()
        val rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n)
        val b = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(words)
        b.putInt(n0inv)
        putWords(b, n, words)
        putWords(b, rr, words)
        b.putInt(k.publicExponent.toInt())
        return b.array()
    }

    private fun putWords(b: ByteBuffer, v: BigInteger, words: Int) {
        val mask = BigInteger.valueOf(0xFFFFFFFFL)
        var x = v
        for (i in 0 until words) {
            b.putInt(x.and(mask).toLong().toInt())
            x = x.shiftRight(32)
        }
    }
}

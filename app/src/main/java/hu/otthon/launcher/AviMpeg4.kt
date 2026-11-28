package hu.otthon.launcher

import androidx.media3.common.C as M3
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.avi.AviExtractor

/**
 * Media3's extractors, with the AVI one mended for MPEG-4 video (XviD, DivX), which TV hardware
 * decoders would not play from it:
 *
 *  - The decoder is given the stream's headers (VOS/VO/VOL) as its configuration. Media3's AVI
 *    reader leaves them in the first frame only; the Sony's MediaTek decoder waited for them for
 *    ever (loaded, no picture).
 *  - "Packed bitstream" (DivX 5, some XviD): two frames in one chunk and an empty placeholder in
 *    the next. The two are made two samples, the placeholder dropped.
 *  - B-frames: AVI keeps no presentation times, Media3 gives every chunk the next frame's time in
 *    the order they are stored (decode order). The times are put in display order, as a decoder
 *    that reorders the frames expects: a B-frame is shown one frame earlier, the frame before it
 *    after the B-frames.
 *
 * And for every AVI video track: the frame rate. Media3's AVI reader reads it from the file but
 * does not pass it on; the MediaTek decoder, given 0 fps, never put a frame out (and the screen's
 * frame rate could not be matched to an AVI). It is worked out from the first two chunks' times.
 *
 * Everything else (audio, MPEG-4 in MKV/MP4) goes through as it was.
 */
object Mp4vExtractors : ExtractorsFactory {
    private val default = DefaultExtractorsFactory()

    override fun createExtractors(): Array<Extractor> = mend(default.createExtractors())

    override fun createExtractors(uri: android.net.Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        mend(default.createExtractors(uri, responseHeaders))

    private fun mend(all: Array<Extractor>): Array<Extractor> =
        Array(all.size) { i -> all[i].let { if (it is AviExtractor) MendedAvi(it) else it } }
}

private class MendedAvi(private val inner: Extractor) : Extractor {
    private val tracks = ArrayList<Mp4vTrack>()

    override fun sniff(input: ExtractorInput) = inner.sniff(input)

    override fun init(output: ExtractorOutput) {
        inner.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                val t = output.track(id, type)
                return if (type == M3.TRACK_TYPE_VIDEO) Mp4vTrack(t).also { tracks.add(it) } else t
            }
            override fun endTracks() = output.endTracks()
            override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val r = inner.read(input, seekPosition)
        // at the end the frames still held back go out
        if (r == Extractor.RESULT_END_OF_INPUT) tracks.forEach { it.flush() }
        return r
    }

    override fun seek(position: Long, timeUs: Long) {
        tracks.forEach { it.reset() }
        inner.seek(position, timeUs)
    }

    override fun release() = inner.release()
}

/** The video track of an AVI: its frame rate given; MPEG-4 Part 2 mended too (see [Mp4vExtractors]). */
private class Mp4vTrack(private val out: TrackOutput) : TrackOutput {
    private var mend = false
    /** MPEG-4 Part 2: split, ordered, headers given. Else only the frame rate is added. */
    private var mp4v = false
    private var pendingFormat: Format? = null
    private var csd: ByteArray? = null
    /** The first chunks' times, for the frame rate. */
    private var firstTime = -1L
    private var frameUs = 0L
    /** Other formats' first chunks, held until the frame rate is known. */
    private val held = ArrayList<Triple<ByteArray, Long, Int>>()

    // the bytes given for the samples not yet ended
    private var buf = ByteArray(256 * 1024)
    private var len = 0

    /** VOL: how many bits a VOP's time increment takes (null until read). */
    private var timeBits: Int? = null

    private class Vop(val data: ByteArray, val type: Int, val key: Boolean) {
        var slot = 0L
    }

    /** Times of the chunks not yet given to a frame, and frames waiting for a time (packed). */
    private val slots = ArrayDeque<Long>()
    private val waiting = ArrayDeque<Vop>()
    /** A reference frame and the B-frames after it, until the next reference frame. */
    private val group = ArrayList<Vop>()

    override fun format(format: Format) {
        mp4v = format.sampleMimeType == MimeTypes.VIDEO_MP4V
        // the format waits for the frame rate (and, for MPEG-4, the headers from the first frame)
        if (pendingFormat == null && !sentFormat) { mend = true; pendingFormat = format }
        else out.format(format)
    }

    private var sentFormat = false

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (!mend) return out.sampleData(input, length, allowEndOfInput, sampleDataPart)
        room(length)
        val n = input.read(buf, len, length)
        if (n == M3.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return M3.RESULT_END_OF_INPUT
            throw java.io.EOFException()
        }
        len += n
        return n
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (!mend) return out.sampleData(data, length, sampleDataPart)
        room(length)
        data.readBytes(buf, len, length)
        len += length
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        if (!mend) return out.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        val start = len - offset - size
        if (start < 0) { len = 0; return }
        val chunk = buf.copyOfRange(start, start + size)
        // what came after this sample stays for the next
        System.arraycopy(buf, start + size, buf, 0, offset)
        len = offset
        if (firstTime < 0) firstTime = timeUs
        else if (frameUs == 0L && timeUs > firstTime) frameUs = timeUs - firstTime
        if (!mp4v) { plain(chunk, timeUs, flags); return }
        try {
            chunk(chunk, timeUs, flags and M3.BUFFER_FLAG_KEY_FRAME != 0)
        } catch (e: Exception) {
            // something not understood: this chunk as it is (better a glitch than nothing)
            android.util.Log.w("OtthonPlayer", "MPEG-4 mend: $e")
            sendFormat(null)
            out.sampleData(ParsableByteArray(chunk), chunk.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
            out.sampleMetadata(timeUs, flags, chunk.size, 0, null)
        }
    }

    /** Not MPEG-4 Part 2: each chunk as it is; the first two held until the frame rate is known. */
    private fun plain(data: ByteArray, timeUs: Long, flags: Int) {
        if (!sentFormat) {
            held.add(Triple(data, timeUs, flags))
            if (frameUs == 0L && held.size < 2) return
            sendFormat(null)
            for ((d, t, f) in held) { out.sampleData(ParsableByteArray(d), d.size, TrackOutput.SAMPLE_DATA_PART_MAIN); out.sampleMetadata(t, f, d.size, 0, null) }
            held.clear()
            return
        }
        out.sampleData(ParsableByteArray(data), data.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        out.sampleMetadata(timeUs, flags, data.size, 0, null)
    }

    private fun room(n: Int) {
        if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n))
    }

    // ---------- one AVI chunk ----------

    private fun chunk(data: ByteArray, timeUs: Long, key: Boolean) {
        if (csd == null && !sentFormat) csd = headers(data)
        slots.addLast(timeUs)
        val vops = split(data)
        var first = true
        for ((from, to) in vops) {
            val v = data.copyOfRange(if (first) 0 else from, to)   // the headers before the first VOP stay with it
            val type = vopType(data, from)
            val coded = vopCoded(data, from, to)
            first = false
            if (!coded) {
                // an empty placeholder after a packed chunk: nothing; a skipped frame: its time is not used
                if (waiting.isEmpty()) slots.removeLastOrNull()
                continue
            }
            waiting.addLast(Vop(v, type, key && type == 0))
        }
        if (vops.isEmpty()) {
            // no VOP in it at all (headers only): as it is, with its own time
            slots.removeLastOrNull()
            if (data.isNotEmpty()) { sendFormat(null); out.sampleData(ParsableByteArray(data), data.size, TrackOutput.SAMPLE_DATA_PART_MAIN); out.sampleMetadata(timeUs, if (key) M3.BUFFER_FLAG_KEY_FRAME else 0, data.size, 0, null) }
        }
        while (waiting.isNotEmpty() && slots.isNotEmpty()) {
            val v = waiting.removeFirst()
            v.slot = slots.removeFirst()
            order(v)
        }
    }

    /** B-frames (type 2) shown one frame earlier; a reference frame after the B-frames that follow it. */
    private fun order(v: Vop) {
        if (v.type != 2) {
            // the next reference frame: the one before it and its B-frames can go
            emitGroup()
        }
        group.add(v)
    }

    private fun emitGroup() {
        if (group.isEmpty()) return
        val slotsInOrder = group.map { it.slot }
        val ref = group[0]
        val bs = group.size - 1
        // the reference frame is shown after its B-frames: in the last of their slots
        val times = LongArray(group.size)
        if (ref.type == 2) {
            for (i in group.indices) times[i] = slotsInOrder[i]
        } else {
            times[0] = slotsInOrder[bs]
            for (i in 1..bs) times[i] = slotsInOrder[i - 1]
        }
        for (i in group.indices) send(group[i], times[i])
        group.clear()
    }

    private fun send(v: Vop, timeUs: Long) {
        sendFormat(null)
        out.sampleData(ParsableByteArray(v.data), v.data.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        out.sampleMetadata(timeUs, if (v.key) M3.BUFFER_FLAG_KEY_FRAME else 0, v.data.size, 0, null)
    }

    fun flush() {
        if (!mp4v && held.isNotEmpty()) {
            sendFormat(null)
            for ((d, t, f) in held) { out.sampleData(ParsableByteArray(d), d.size, TrackOutput.SAMPLE_DATA_PART_MAIN); out.sampleMetadata(t, f, d.size, 0, null) }
            held.clear()
        }
        while (waiting.isNotEmpty()) {
            val v = waiting.removeFirst()
            v.slot = slots.removeFirstOrNull() ?: (group.lastOrNull()?.slot ?: 0L)
            order(v)
        }
        emitGroup()
    }

    fun reset() {
        len = 0
        held.clear()
        slots.clear()
        waiting.clear()
        group.clear()
    }

    /** The format, once: with the frame rate from the chunks' times and, for MPEG-4, the headers. */
    private fun sendFormat(@Suppress("UNUSED_PARAMETER") unused: ByteArray?) {
        val f = pendingFormat ?: return
        pendingFormat = null
        sentFormat = true
        var b = f.buildUpon()
        val c = csd
        if (mp4v && c != null && c.isNotEmpty()) b = b.setInitializationData(listOf(c))
        if (frameUs > 0) b = b.setFrameRate(1_000_000f / frameUs)
        out.format(b.build())
    }

    // ---------- the MPEG-4 stream ----------

    /** Where each VOP (00 00 01 B6) starts and ends in [d]. */
    private fun split(d: ByteArray): List<Pair<Int, Int>> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1 && (d[i + 3].toInt() and 0xFF) == 0xB6) { starts.add(i); i += 4 } else i++
        }
        return starts.mapIndexed { k, s -> s to (if (k + 1 < starts.size) starts[k + 1] else d.size) }
    }

    /** The headers before the first VOP (VOS, VO, VOL), if a VOL is among them; its time bits are read. */
    private fun headers(d: ByteArray): ByteArray? {
        val firstVop = split(d).firstOrNull()?.first ?: d.size
        var vol = -1
        var i = 0
        while (i + 3 < firstVop) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1 && (d[i + 3].toInt() and 0xF0) == 0x20) { vol = i; break }
            i++
        }
        if (vol < 0) return null
        timeBits = try { readVol(d, vol + 4) } catch (e: Exception) { null }
        return d.copyOfRange(0, firstVop)
    }

    /** vop_coding_type: 0 I, 1 P, 2 B, 3 S (sprite: a reference like P). */
    private fun vopType(d: ByteArray, at: Int): Int = if (at + 4 < d.size) (d[at + 4].toInt() and 0xFF) shr 6 else 1

    /** vop_coded: false for the empty "not coded" VOP (a packed bitstream's placeholder, a skipped frame). */
    private fun vopCoded(d: ByteArray, from: Int, to: Int): Boolean {
        val bits = timeBits ?: return to - from > 8   // without the VOL: an empty VOP is a few bytes
        return try {
            val r = Bits(d, from + 4, to)
            r.skip(2)                          // vop_coding_type
            while (r.bit() == 1) { }           // modulo_time_base
            r.skip(1)                          // marker
            r.skip(bits)                       // vop_time_increment
            r.skip(1)                          // marker
            r.bit() == 1
        } catch (e: Exception) { true }
    }

    /** From a VOL: the number of bits of vop_time_increment. */
    private fun readVol(d: ByteArray, from: Int): Int {
        val r = Bits(d, from, d.size)
        r.skip(1)                                  // random_accessible_vol
        r.skip(8)                                  // video_object_type_indication
        var verid = 1
        if (r.bit() == 1) { verid = r.read(4); r.skip(3) }   // is_object_layer_identifier
        if (r.read(4) == 15) r.skip(16)            // aspect_ratio_info: extended PAR
        if (r.bit() == 1) {                        // vol_control_parameters
            r.skip(2); r.skip(1)                   // chroma_format, low_delay
            if (r.bit() == 1) r.skip(79)           // vbv_parameters
        }
        val shape = r.read(2)
        if (shape == 3 && verid != 1) r.skip(4)
        r.skip(1)                                  // marker
        val resolution = r.read(16)
        var bits = 1
        while ((1 shl bits) < resolution) bits++
        return bits
    }

    private class Bits(private val d: ByteArray, from: Int, private val end: Int) {
        private var pos = from * 8L
        fun bit(): Int {
            val byte = (pos ushr 3).toInt()
            if (byte >= end) throw IndexOutOfBoundsException()
            val b = (d[byte].toInt() shr (7 - (pos and 7).toInt())) and 1
            pos++
            return b
        }
        fun read(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }
        fun skip(n: Int) { pos += n }
    }
}

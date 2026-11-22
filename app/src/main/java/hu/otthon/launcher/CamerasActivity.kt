package hu.otthon.launcher

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Camera wall: a grid of all cameras, and a full-screen view of one.
 * Images are snapshots pulled from Home Assistant (camera_proxy), refreshed continuously.
 */
class CamerasActivity : PanelActivity() {

    private lateinit var cfg: Config
    private var ha: HaClient? = null
    private val exec = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var full: FrameLayout
    private lateinit var fullImage: ImageView
    private lateinit var fullLabel: TextView
    private lateinit var live: LiveVideo
    private var liveWarned = false
    private val tiles = mutableListOf<CamTile>()
    private var pending = BooleanArray(0)

    private var fullIndex = -1
    private var generation = 0
    private var active = false

    // ---- a camera's detections (Frigate): a strip at the bottom, and their recordings ----
    private lateinit var hint: TextView
    private lateinit var strip: LinearLayout
    private lateinit var stripTitle: TextView
    private lateinit var stripRow: LinearLayout
    private lateinit var stripScroll: RowScroller
    private lateinit var clip: ClipPlayer
    private lateinit var clipInfo: TextView
    private var stripOpen = false
    private var clipOpen = false
    private var stripCam: Frigate.Cam? = null
    private val detections = ArrayList<Detection>()
    private val cards = ArrayList<DetectionCard>()
    private var stripIndex = 0
    private var clipIndex = -1
    private var stripGen = 0
    private var loadingMore = false
    private var noMore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config.load(this)
        if (!cfg.haEnabled || cfg.cameras.isEmpty()) {
            Toast.makeText(this, Lang.tr("No cameras configured"), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        ha = HaClient(cfg.haUrl!!, cfg.haToken!!)
        val cams = cfg.cameras
        pending = BooleanArray(cams.size)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val frame = PanelFrame(this, "Cameras", "OK: full screen")
        frame.update(cfg)
        val root = frame.root

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        var row: LinearLayout? = null
        cams.forEachIndexed { i, cam ->
            if (i % 2 == 0) {
                val r = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                }
                row = r
                val lp = LinearLayout.LayoutParams(-1, dp(156f))
                if (i > 0) lp.topMargin = dp(12f)
                grid.addView(r, lp)
            }
            val t = CamTile(this, cam, dp(10f).toFloat(), 11f, 1.03f)
            t.setOnClickListener { showFull(i) }
            val lp = LinearLayout.LayoutParams(0, -1, 1f)
            if (i % 2 == 1) lp.leftMargin = dp(12f)
            row!!.addView(t, lp)
            tiles.add(t)
        }
        if (cams.size % 2 == 1) {
            row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(12f) })
        }
        val scroll = ColScroller(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            clipChildren = false
            setPadding(dp(22f), dp(14f), dp(22f), dp(22f))
        }
        scroll.addView(grid)
        frame.panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        full = FrameLayout(this)
        full.setBackgroundColor(Color.BLACK)
        full.visibility = View.GONE
        fullImage = ImageView(this)
        fullImage.scaleType = ImageView.ScaleType.FIT_CENTER
        full.addView(fullImage, FrameLayout.LayoutParams(-1, -1))
        // live video lies over the still picture and shows once its first frame is ready
        live = LiveVideo(this, crop = false)
        live.onFailed = {
            if (!liveWarned && fullIndex >= 0) {
                liveWarned = true
                Toast.makeText(this, Lang.tr("Live video is not available, showing pictures"), Toast.LENGTH_SHORT).show()
            }
        }
        full.addView(live, FrameLayout.LayoutParams(-1, -1))

        fullLabel = label("", 14f, C.TEXT, bold = true)
        fullLabel.setPadding(dp(12f), dp(7f), dp(14f), dp(7f))
        fullLabel.background = Look.surface(this, dp(Look.R_TILE).toFloat())
        full.addView(fullLabel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(28f)
            topMargin = dp(22f)
        })

        // a detection's recording plays over the live picture
        clip = ClipPlayer(this)
        clip.onStarted = { showClipInfo() }
        clip.onEnded = { showClipInfo() }
        clip.onFailed = {
            if (clipOpen) {
                Toast.makeText(this, Lang.tr("This recording can't be played"), Toast.LENGTH_SHORT).show()
                closeClip()
            }
        }
        full.addView(clip, FrameLayout.LayoutParams(-1, -1))

        hint = label(LIVE_HINT, 11f, C.TEXT2)
        hint.setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        hint.background = rounded(C.GLASS, dp(16f).toFloat())
        full.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(18f)
        })

        clipInfo = label("", 12f, C.TEXT2)
        clipInfo.setPadding(dp(12f), dp(6f), dp(14f), dp(6f))
        clipInfo.background = Look.surface(this, dp(Look.R_TILE).toFloat())
        clipInfo.visibility = View.GONE
        full.addView(clipInfo, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(28f)
            topMargin = dp(22f)
        })

        strip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(18f), 0, dp(20f))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xF0060709.toInt(), 0xC0060709.toInt(), 0x00060709)
            )
            visibility = View.GONE
        }
        stripTitle = label("", 13f, C.TEXT, bold = true)
        stripTitle.setPadding(dp(30f), 0, dp(30f), dp(10f))
        strip.addView(stripTitle)
        stripRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
        }
        stripScroll = RowScroller(this).apply { setPadding(dp(30f), dp(6f), dp(30f), dp(6f)) }
        stripScroll.addView(stripRow)
        strip.addView(stripScroll, LinearLayout.LayoutParams(-1, -2))
        full.addView(strip, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.addView(full, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        animateIn(frame.panel)

        val start = intent.getIntExtra("index", -1)
        if (start >= 0 && start < cams.size) showFull(start) else tiles[0].requestFocus()
    }

    override fun onResume() {
        super.onResume()
        PopupService.camerasOpen = true
        if (ha == null) return
        active = true
        generation++
        if (fullIndex >= 0) {
            fullTick(generation)
            cfg.stream(cfg.cameras[fullIndex].entity)?.let { live.play(it) }
        } else {
            gridTick(generation)
        }
    }

    override fun onPause() {
        super.onPause()
        PopupService.camerasOpen = false
        active = false
        if (::live.isInitialized) live.stop()
        if (::clip.isInitialized && clipOpen) closeClip()
    }

    override fun onDestroy() {
        super.onDestroy()
        exec.shutdownNow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (clipOpen) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> if (!down) closeClip()
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> if (down && event.repeatCount == 0) {
                    clip.toggle()
                    showClipInfo()
                }
                // the list runs newest first: left is the newer one, right the older one
                KeyEvent.KEYCODE_DPAD_LEFT -> if (down && event.repeatCount == 0 && clipIndex > 0) playDetection(clipIndex - 1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (down && event.repeatCount == 0 && clipIndex < detections.size - 1) playDetection(clipIndex + 1)
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_DPAD_UP -> if (down) clip.seekBy(10_000)
                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DPAD_DOWN -> if (down) clip.seekBy(-10_000)
                else -> return super.dispatchKeyEvent(event)
            }
            return true
        }
        if (stripOpen) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> if (!down) closeStrip()
                KeyEvent.KEYCODE_DPAD_UP -> if (down) closeStrip()
                KeyEvent.KEYCODE_DPAD_LEFT -> if (down) focusCard(stripIndex - 1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) focusCard(stripIndex + 1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!down && cards.isNotEmpty()) playDetection(stripIndex)
                KeyEvent.KEYCODE_DPAD_DOWN -> {}
                else -> return super.dispatchKeyEvent(event)
            }
            return true
        }
        if (fullIndex >= 0) {
            val n = cfg.cameras.size
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (down) showFull((fullIndex - 1 + n) % n)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (down) showFull((fullIndex + 1) % n)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (down && event.repeatCount == 0) openStrip()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> {
                    if (!down) showGrid()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showFull(i: Int) {
        fullIndex = i
        generation++
        val cam = cfg.cameras[i]
        fullLabel.text = "● " + cam.label.ifBlank { cam.entity }
        val last = (tiles[i].image.drawable as? BitmapDrawable)?.bitmap
        if (last != null) fullImage.setImageBitmap(last) else fullImage.setImageDrawable(null)
        if (full.visibility != View.VISIBLE) {
            full.visibility = View.VISIBLE
            if (Motion.level != Motion.OFF) {
                full.alpha = 0f
                full.animate().alpha(1f).setDuration(160).withLayer().start()
            }
        }
        fullTick(generation)
        val stream = cfg.stream(cam.entity)
        if (stream != null) live.play(stream) else live.stop()
    }

    private fun showGrid() {
        closeStrip()
        val i = fullIndex
        fullIndex = -1
        generation++
        live.stop()
        full.animate().cancel()
        full.alpha = 1f
        full.visibility = View.GONE
        fullImage.setImageDrawable(null)
        if (i >= 0 && i < tiles.size) tiles[i].requestFocus()
        gridTick(generation)
    }

    /** Starts a snapshot fetch for camera i; returns false if one is already running. */
    private fun fetch(i: Int, maxW: Int, done: (Bitmap?) -> Unit): Boolean {
        val client = ha ?: return false
        if (pending[i]) return false
        pending[i] = true
        val path = "/api/camera_proxy/" + cfg.cameras[i].entity
        exec.execute {
            val b: Bitmap? = try { client.bitmap(path, maxW) } catch (e: Exception) { null }
            main.post {
                pending[i] = false
                done(b)
            }
        }
        return true
    }

    private fun gridTick(gen: Int) {
        if (!active || gen != generation || fullIndex >= 0) return
        for (i in tiles.indices) {
            fetch(i, 640) { b ->
                if (b != null && active && fullIndex < 0) tiles[i].image.setImageBitmap(b)
            }
        }
        main.postDelayed({ gridTick(gen) }, 2000L)
    }

    private fun fullTick(gen: Int) {
        if (!active || gen != generation || fullIndex < 0) return
        if (live.playing || clipOpen) {
            // the video is running: no pictures needed, just look again in a while
            main.postDelayed({ fullTick(gen) }, 1000L)
            return
        }
        val i = fullIndex
        val started = fetch(i, 1920) { b ->
            if (active && gen == generation && fullIndex == i) {
                if (b != null) fullImage.setImageBitmap(b)
                main.postDelayed({ fullTick(gen) }, if (b != null) 300L else 1500L)
            }
        }
        if (!started) main.postDelayed({ fullTick(gen) }, 200L)
    }

    // ---------- detections ----------

    private fun openStrip() {
        val i = fullIndex
        if (i < 0) return
        stripOpen = true
        stripGen++
        val gen = stripGen
        detections.clear()
        cards.clear()
        stripRow.removeAllViews()
        stripIndex = 0
        noMore = false
        loadingMore = false
        stripCam = null
        val cam = cfg.cameras[i]
        stripTitle.text = "Detections · " + cam.label.ifBlank { cam.entity }
        stripRow.addView(note("Looking for detections…"))
        strip.visibility = View.VISIBLE
        hint.visibility = View.GONE
        if (Motion.level != Motion.OFF) {
            strip.translationY = dp(60f).toFloat()
            strip.alpha = 0f
            strip.animate().translationY(0f).alpha(1f).setDuration(180).start()
        }
        val client = ha ?: return
        val c = cfg
        Bg.run({
            val fc = Frigate.cam(client, cam.entity) ?: return@run "This camera is not one of Frigate's: it has no detections"
            try {
                fc to Frigate.detections(c, fc)
            } catch (e: Exception) {
                android.util.Log.w("OtthonClip", "detections of ${fc.name}: $e")
                "Couldn't reach Frigate's detections"
            }
        }) { res ->
            if (!stripOpen || gen != stripGen) return@run
            stripRow.removeAllViews()
            @Suppress("UNCHECKED_CAST")
            val r = res as? Pair<Frigate.Cam, List<Detection>>
            if (r == null) {
                stripRow.addView(note(res as? String ?: "Couldn't reach Frigate's detections"))
                return@run
            }
            stripCam = r.first
            if (r.second.isEmpty()) {
                stripRow.addView(note("No recorded detections for this camera"))
                return@run
            }
            noMore = r.second.size < 40
            addDetections(r.second)
            focusCard(0)
        }
    }

    private fun note(text: String) = label(text, 12f, C.TEXT2).apply { setPadding(0, dp(30f), 0, dp(30f)) }

    private fun closeStrip() {
        if (!stripOpen) return
        stripOpen = false
        stripGen++
        strip.animate().cancel()
        strip.visibility = View.GONE
        hint.visibility = View.VISIBLE
        stripRow.removeAllViews()
        cards.clear()
    }

    private fun addDetections(list: List<Detection>) {
        val cam = stripCam ?: return
        val client = ha ?: return
        for (d in list) {
            val idx = detections.size
            detections.add(d)
            val card = DetectionCard(this, d)
            card.setOnClickListener { playDetection(idx) }
            val lp = LinearLayout.LayoutParams(dp(200f), -2)
            if (idx > 0) lp.leftMargin = dp(12f)
            stripRow.addView(card, lp)
            cards.add(card)
            val gen = stripGen
            exec.execute {
                val b = try { client.bitmap(Frigate.thumbnail(cam, d), 400) } catch (e: Exception) { null }
                main.post { if (gen == stripGen && b != null) card.image.setImageBitmap(b) }
            }
        }
    }

    private fun focusCard(i: Int) {
        if (cards.isEmpty()) return
        val k = i.coerceIn(0, cards.size - 1)
        stripIndex = k
        cards[k].requestFocus()
        for ((j, c) in cards.withIndex()) c.mark(j == k)
        // near the end: the next, older ones
        if (k >= cards.size - 4) loadMore()
    }

    private fun loadMore() {
        if (loadingMore || noMore || detections.isEmpty()) return
        val cam = stripCam ?: return
        loadingMore = true
        val gen = stripGen
        val before = detections.last().start
        val c = cfg
        Bg.run({ try { Frigate.detections(c, cam, before) } catch (e: Exception) { null } }) { more ->
            loadingMore = false
            if (gen != stripGen || !stripOpen) return@run
            if (more == null || more.isEmpty()) {
                noMore = true
                return@run
            }
            noMore = more.size < 40
            addDetections(more.filter { m -> detections.none { it.id == m.id } })
        }
    }

    private fun playDetection(i: Int) {
        val cam = stripCam ?: return
        val d = detections.getOrNull(i) ?: return
        clipIndex = i
        if (stripOpen && i < cards.size) focusCard(i)
        clipOpen = true
        live.stop()
        strip.visibility = View.GONE
        hint.text = CLIP_HINT
        hint.visibility = View.VISIBLE
        clipInfo.visibility = View.VISIBLE
        showClipInfo()
        clip.play(Frigate.clips(cfg, cam, d), cfg.haToken!!)
        main.removeCallbacks(clipTick)
        main.postDelayed(clipTick, 500L)
    }

    private val clipTick = object : Runnable {
        override fun run() {
            if (!clipOpen) return
            showClipInfo()
            main.postDelayed(this, 500L)
        }
    }

    private fun closeClip() {
        clipOpen = false
        main.removeCallbacks(clipTick)
        clip.stop()
        clipInfo.visibility = View.GONE
        hint.text = LIVE_HINT
        if (stripOpen) {
            strip.visibility = View.VISIBLE
            hint.visibility = View.GONE
            focusCard(clipIndex)
        }
        val i = fullIndex
        if (i >= 0 && active) {
            generation++
            fullTick(generation)
            cfg.stream(cfg.cameras[i].entity)?.let { live.play(it) }
        }
    }

    private fun showClipInfo() {
        val d = detections.getOrNull(clipIndex) ?: return
        val cam = cfg.cameras.getOrNull(fullIndex)
        val head = (if (clip.paused) "❚❚  " else "▶  ") + d.title + "  ·  " + DetectionCard.whenText(d.start)
        val p = clip.progress()
        val time = if (p != null && p.second > 0) "   " + mmss(p.first) + " / " + mmss(p.second) else if (!clip.playing) "   loading…" else ""
        clipInfo.text = head + time
        fullLabel.text = "● " + (cam?.label?.ifBlank { cam.entity } ?: "") + "  ·  ${clipIndex + 1} / ${detections.size}"
    }

    private fun mmss(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        const val LIVE_HINT = "◀ ▶  switch camera   ·   ▼  detections   ·   OK / Back: all cameras"
        const val CLIP_HINT = "◀ ▶  newer / older detection   ·   OK  pause   ·   ▲ ▼  10 s   ·   Back: list"
    }
}

/** A detection in the strip: its picture, what and when. */
class DetectionCard(ctx: android.content.Context, val d: Detection) : LinearLayout(ctx) {
    val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val frame = FrameLayout(ctx)
    private val ring = rounded(0, ctx.dp(Look.R_TILE).toFloat(), ctx.dp(2f), C.TEXT)

    init {
        orientation = VERTICAL
        isFocusable = true
        isClickable = true
        frame.background = rounded(0xFF22262C.toInt(), ctx.dp(Look.R_TILE).toFloat())
        frame.clipToOutline = true
        frame.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        if (d.seconds > 0) {
            val len = ctx.label("%d:%02d".format(d.seconds / 60, d.seconds % 60), 10f, C.TEXT, bold = true)
            len.setPadding(ctx.dp(6f), ctx.dp(2f), ctx.dp(6f), ctx.dp(2f))
            len.background = rounded(0xB0060709.toInt(), ctx.dp(Look.R_SMALL).toFloat())
            frame.addView(len, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = ctx.dp(6f)
                bottomMargin = ctx.dp(6f)
            })
        }
        addView(frame, LayoutParams(-1, ctx.dp(112f)))
        val what = ctx.label(d.title, 12f, C.TEXT, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        addView(what, LayoutParams(-1, -2).apply { topMargin = ctx.dp(7f) })
        addView(ctx.label(whenText(d.start), 11f, C.TEXT2).apply { maxLines = 1 })
        setOnFocusChangeListener { _, f -> mark(f) }
    }

    fun mark(on: Boolean) {
        frame.foreground = if (on) ring else null
        frame.animate().scaleX(if (on) 1.05f else 1f).scaleY(if (on) 1.05f else 1f).setDuration(120).start()
    }

    companion object {
        private val hm = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
        private val day get() = Lang.dtf("MMM d")

        /** "Today 21:05:34", "Yesterday 18:00:02", "Nov 19 10:12:40". */
        fun whenText(epochSeconds: Double): String {
            val z = java.time.ZoneId.systemDefault()
            val t = java.time.Instant.ofEpochMilli((epochSeconds * 1000).toLong()).atZone(z)
            val today = java.time.LocalDate.now(z)
            val d = t.toLocalDate()
            val dayText = when (d) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> t.format(day)
            }
            return dayText + " " + t.format(hm)
        }
    }
}

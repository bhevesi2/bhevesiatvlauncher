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
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Camera wall: a grid of all cameras, and a full-screen view of one.
 * Images are snapshots pulled from Home Assistant (camera_proxy), refreshed continuously.
 */
class CamerasActivity : Activity() {

    private lateinit var cfg: Config
    private var ha: HaClient? = null
    private val exec = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var full: FrameLayout
    private lateinit var fullImage: ImageView
    private lateinit var fullLabel: TextView
    private val tiles = mutableListOf<CamTile>()
    private var pending = BooleanArray(0)

    private var fullIndex = -1
    private var generation = 0
    private var active = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config.load(this)
        if (!cfg.haEnabled || cfg.cameras.isEmpty()) {
            Toast.makeText(this, "No cameras configured", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        ha = HaClient(cfg.haUrl!!, cfg.haToken!!)
        val cams = cfg.cameras
        pending = BooleanArray(cams.size)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF0C0D10.toInt())

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36f), dp(22f), dp(36f), dp(24f))
            clipChildren = false
            clipToPadding = false
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        header.addView(label("Cameras", 22f, C.TEXT, bold = true))
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(label("OK: full screen   ·   Back: home", 11f, C.TEXT3))
        grid.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2).apply {
            bottomMargin = dp(14f)
        })

        var row: LinearLayout? = null
        cams.forEachIndexed { i, cam ->
            if (i % 2 == 0) {
                val r = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                }
                row = r
                grid.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    if (i > 0) topMargin = dp(12f)
                })
            }
            val t = CamTile(this, cam, dp(10f).toFloat(), 13f, 1.02f)
            t.setOnClickListener { showFull(i) }
            row!!.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (i % 2 == 1) leftMargin = dp(12f)
            })
            tiles.add(t)
        }
        if (cams.size % 2 == 1) {
            row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(12f) })
        }
        root.addView(grid, FrameLayout.LayoutParams(-1, -1))

        full = FrameLayout(this)
        full.setBackgroundColor(Color.BLACK)
        full.visibility = View.GONE
        fullImage = ImageView(this)
        fullImage.scaleType = ImageView.ScaleType.FIT_CENTER
        full.addView(fullImage, FrameLayout.LayoutParams(-1, -1))

        fullLabel = label("", 14f, C.TEXT, bold = true)
        fullLabel.setPadding(dp(12f), dp(7f), dp(14f), dp(7f))
        fullLabel.background = rounded(C.GLASS, dp(18f).toFloat(), 1, C.BORDER)
        full.addView(fullLabel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(28f)
            topMargin = dp(22f)
        })

        val hint = label("◀ ▶  switch camera   ·   OK / Back: all cameras", 11f, C.TEXT2)
        hint.setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        hint.background = rounded(C.GLASS, dp(16f).toFloat())
        full.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(18f)
        })
        root.addView(full, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        val start = intent.getIntExtra("index", -1)
        if (start >= 0 && start < cams.size) showFull(start) else tiles[0].requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (ha == null) return
        active = true
        generation++
        if (fullIndex >= 0) fullTick(generation) else gridTick(generation)
    }

    override fun onPause() {
        super.onPause()
        active = false
    }

    override fun onDestroy() {
        super.onDestroy()
        exec.shutdownNow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (fullIndex >= 0) {
            val n = cfg.cameras.size
            val down = event.action == KeyEvent.ACTION_DOWN
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (down) showFull((fullIndex - 1 + n) % n)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (down) showFull((fullIndex + 1) % n)
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
        full.visibility = View.VISIBLE
        fullTick(generation)
    }

    private fun showGrid() {
        val i = fullIndex
        fullIndex = -1
        generation++
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
            fetch(i, 960) { b ->
                if (b != null && active && fullIndex < 0) tiles[i].image.setImageBitmap(b)
            }
        }
        main.postDelayed({ gridTick(gen) }, 2000L)
    }

    private fun fullTick(gen: Int) {
        if (!active || gen != generation || fullIndex < 0) return
        val i = fullIndex
        val started = fetch(i, 1920) { b ->
            if (active && gen == generation && fullIndex == i) {
                if (b != null) fullImage.setImageBitmap(b)
                main.postDelayed({ fullTick(gen) }, if (b != null) 300L else 1500L)
            }
        }
        if (!started) main.postDelayed({ fullTick(gen) }, 200L)
    }
}

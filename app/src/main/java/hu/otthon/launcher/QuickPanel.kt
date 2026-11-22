package hu.otthon.launcher

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Quick settings, in the manner of Google TV's panel: it slides in from the right over whatever
 * is on screen (a film keeps playing under it), with the time, the profile, and tiles for
 * Picture, Sound, Inputs, Network, Screen saver, Sleep timer, Turn off and the launcher's
 * settings, and all of the TV's settings at the bottom. The remote's gear button opens and
 * closes it; Back closes it; it closes itself after half a minute without a key.
 *
 * From the button service it is an accessibility overlay (no extra permission needed); from the
 * launcher it is an overlay over other apps, or a panel of the home screen's own window.
 */
object QuickPanel {
    private val main = Handler(Looper.getMainLooper())
    /** How many of the last day's notifications show in the panel itself; the rest on their own page. */
    private const val INLINE = 4
    private var root: View? = null
    private var wm: WindowManager? = null
    private var shownAt = 0L

    val showing get() = root != null

    /** Opens the panel, or closes it when it is already open (the gear button toggles it). */
    fun toggle(ctx: Context) {
        if (root != null) hide() else show(ctx)
    }

    fun show(ctx: Context) {
        if (root != null) return
        // a camera pop-up goes first: both at once are too much for a 2 GB TV's graphics memory
        try { PopupService.closePopup?.invoke() } catch (e: Exception) { }
        try {
            build(ctx)
        } catch (e: Exception) {
            android.util.Log.w("OtthonQuick", "quick settings failed", e)
            root = null
            Apps.openSystemSettings(ctx)
        }
    }

    private fun build(ctx: Context) {
        val cfg = Config.load(ctx)
        val type = when {
            ctx is AccessibilityService && Build.VERSION.SDK_INT >= 22 -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            PopupService.allowed(ctx) && Build.VERSION.SDK_INT >= 26 -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            ctx is Activity -> WindowManager.LayoutParams.TYPE_APPLICATION
            else -> {
                // nowhere to draw: the TV's own settings instead
                Apps.openSystemSettings(ctx)
                return
            }
        }
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = Panel(ctx, cfg)
        val lp = WindowManager.LayoutParams(
            ctx.dp(340f + 30f), WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.TOP
            dimAmount = 0.35f
        }
        try {
            manager.addView(view, lp)
        } catch (e: Exception) {
            android.util.Log.w("OtthonQuick", "no window for quick settings (type $type)", e)
            Apps.openSystemSettings(ctx)
            return
        }
        android.util.Log.i("OtthonQuick", "shown (type $type)")
        root = view
        wm = manager
        shownAt = System.currentTimeMillis()
        view.enter()
        poke()
    }

    fun hide() {
        val v = root ?: return
        root = null
        main.removeCallbacks(timeout)
        val m = wm
        wm = null
        val d = v.resources.displayMetrics.density
        v.animate().translationX(40 * d).alpha(0f).setDuration(160).withEndAction {
            try { m?.removeView(v) } catch (e: Exception) { }
        }.start()
    }

    /** Another app came to the front (Home, an app the panel opened): the panel goes. */
    fun onAppChanged(pkg: String?, ownPkg: String) {
        if (root == null || pkg == null || pkg == ownPkg) return
        // the system's own windows (volume bar, toasts, TV overlays) come and go: only an app counts
        if (pkg == "android" || pkg.contains("systemui") || pkg.startsWith("com.tcl.") || pkg.startsWith("com.mediatek.")) return
        if (System.currentTimeMillis() - shownAt < 1500) return
        android.util.Log.i("OtthonQuick", "closed: $pkg came to the front")
        hide()
    }

    private val timeout = Runnable { hide() }

    private fun poke() {
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 30_000L)
    }

    /** Starts [intents] in order until one opens; closes the panel when one did. */
    private fun open(ctx: Context, vararg intents: Intent): Boolean {
        for (i in intents) {
            try {
                ctx.startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                hide()
                return true
            } catch (e: Exception) { }
        }
        return false
    }

    private fun cls(pkg: String, name: String) = Intent().setClassName(pkg, name)

    // ---------- the panel ----------

    /**
     * The panel: made of the same parts as the settings screen (its panel, its title, its rows,
     * its section headings and its cursor), so the two look like one thing. The TV and Home
     * sections are tiles two to a line, each with its name and what it is set to now.
     */
    private class Panel(private val ctx: Context, private val cfg: Config) : FrameLayout(ctx) {
        private val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        private var inSub = false
        private var popupsOn = cfg.popups
        private val ha: HaClient? = if (cfg.haEnabled) HaClient(cfg.haUrl!!, cfg.haToken!!) else null

        init {
            isFocusable = false
            content.background = Look.panel(ctx)
            content.setPadding(ctx.dp(20f), ctx.dp(18f), ctx.dp(20f), ctx.dp(18f))
            addView(content, LayoutParams(-1, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
                topMargin = ctx.dp(26f)
                bottomMargin = ctx.dp(26f)
                rightMargin = ctx.dp(30f)
            })
            FocusGlide.install(content)

            // the title like the settings screen's, the time under it; the kids' bubble on the right
            val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            titles.addView(ctx.label("Quick settings", 17f, C.TEXT, bold = true))
            titles.addView(ctx.label(Lang.sdf("HH:mm · EEEE").format(Date()), 11f, C.TEXT3),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(3f) })
            head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(powerButton(), LinearLayout.LayoutParams(ctx.dp(46f), ctx.dp(46f)))
            if (cfg.kids.on) head.addView(kidsBubble(), LinearLayout.LayoutParams(ctx.dp(46f), ctx.dp(46f)).apply { leftMargin = ctx.dp(10f) })
            content.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(4f) })
            // the list scrolls when it is taller than the screen (the notifications are at its bottom)
            val scroller = ColScroller(ctx)
            scroller.addView(list, LayoutParams(-1, -2))
            content.addView(scroller, LinearLayout.LayoutParams(-1, -2))
            showMain()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            Inbox.onChange = { inboxChanged() }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            if (Inbox.onChange != null) Inbox.onChange = null
        }

        /** The round power button beside the profile: turn off or restart. */
        private fun powerButton(): View = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_power)
            imageTintList = android.content.res.ColorStateList.valueOf(C.TEXT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = ctx.dp(12f)
            setPadding(pad, pad, pad, pad)
            isFocusable = true
            isClickable = true
            contentDescription = "Power"
            val round = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(C.ROW)
            }
            background = round
            FocusGlide.shape(this, -1f)
            setOnFocusChangeListener { v, f ->
                (v as ImageView).imageTintList = android.content.res.ColorStateList.valueOf(if (f) C.DARK else C.TEXT)
                round.setColor(if (f) C.TEXT else C.ROW)
                Look.grow(v, f, 1.08f)
            }
            setOnClickListener { showPower() }
        }

        /** The kids' picture in a bubble: one press, and the TV is theirs. Easy to spot for a child. */
        private fun kidsBubble(): View = ImageView(ctx).apply {
            setImageDrawable(KidsLook.kidAvatar(cfg.kids))
            isFocusable = true
            isClickable = true
            contentDescription = "${cfg.kids.name}'s profile"
            setPadding(ctx.dp(3f), ctx.dp(3f), ctx.dp(3f), ctx.dp(3f))
            FocusGlide.shape(this, -1f)
            FocusGlide.glow(this, KidsLook.CANDY[0])
            setOnFocusChangeListener { v, f -> Look.grow(v, f, 1.1f) }
            setOnClickListener {
                Profile.set(ctx, true)
                open(ctx, Intent(ctx, HomeActivity::class.java))
            }
        }

        fun enter() {
            val d = resources.displayMetrics.density
            if (Motion.level != Motion.OFF) {
                content.translationX = 40 * d
                content.alpha = 0f
                content.animate().translationX(0f).alpha(1f).setDuration(220).setInterpolator(DecelerateInterpolator(2f)).start()
            }
            post { firstInList()?.requestFocus() }
        }

        private fun firstInList(): View? {
            fun find(v: View): View? {
                if (v.isFocusable && v.visibility == View.VISIBLE) return v
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
                return null
            }
            return find(list)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            poke()
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    if (inSub) showMain() else hide()
                }
                return true
            }
            if (SettingsKey.isKey(event.keyCode)) {
                if (event.action == KeyEvent.ACTION_UP) hide()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        private fun section(title: String) {
            list.addView(ctx.label(title, 11f, C.TEXT2, bold = true), LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = ctx.dp(14f)
                bottomMargin = ctx.dp(2f)
                leftMargin = ctx.dp(2f)
            })
        }

        private fun row(title: String, value: String? = null, onClick: (RowView) -> Unit): RowView {
            val r = RowView(ctx, title, value)
            r.setOnClickListener { onClick(r) }
            list.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4f) })
            return r
        }

        /** Tiles two to a line. */
        private fun grid(tiles: List<Tile>) {
            var line: LinearLayout? = null
            tiles.forEachIndexed { i, t ->
                if (i % 2 == 0) {
                    line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                    list.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4f) })
                }
                line!!.addView(t, LinearLayout.LayoutParams(0, ctx.dp(54f), 1f).apply { if (i % 2 == 1) leftMargin = ctx.dp(4f) })
            }
            if (tiles.size % 2 == 1) line?.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = ctx.dp(4f) })
        }

        private fun showMain() {
            inSub = false
            inboxAll = false
            list.removeAllViews()
            section("TV")
            val inputs = Inputs.visible(ctx, cfg)
            val inputsTile = Tile("Inputs", if (inputs.isEmpty()) "TV" else "TV + ${inputs.size} HDMI") { showInputs() }
            val netTile = Tile("Network", network()) {
                open(ctx, Intent(android.provider.Settings.ACTION_WIFI_SETTINGS), Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
            }
            val sleepTile = Tile("Sleep timer", SleepTimer.describe(ctx)) { t -> t.value(SleepTimer.next(ctx)) }
            val appsTile = Tile("Running apps", "See and close") { showRunning() }
            grid(listOf(inputsTile, netTile, sleepTile, appsTile))
            // how many are running: one question to the TV over Home Assistant
            val c = cfg
            Bg.run({ RunningApps.list(ctx, c) }) { apps ->
                if (apps != null) appsTile.value(if (apps.isEmpty()) "None" else if (apps.size == 1) "1 app" else "${apps.size} apps")
            }

            if (ha != null) {
                section("Home")
                val home = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                list.addView(home, LinearLayout.LayoutParams(-1, -2))
                loadHome(home)
            }

            section("Settings")
            row("Launcher settings") { open(ctx, Intent(ctx, SettingsActivity::class.java)) }
            row("All TV settings") { open(ctx, Intent(android.provider.Settings.ACTION_SETTINGS)) }

            val items = Inbox.list(ctx)
            inboxTitle = ctx.label(if (items.isEmpty()) "Notifications" else "Notifications · ${items.size}", 11f, C.TEXT2, bold = true)
            list.addView(inboxTitle, LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = ctx.dp(14f)
                bottomMargin = ctx.dp(2f)
                leftMargin = ctx.dp(2f)
            })
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            list.addView(box, LinearLayout.LayoutParams(-1, -2))
            inboxBox = box
            fillInbox(box, items, INLINE)
            post { firstInList()?.requestFocus() }
        }

        // ---------- notifications and camera alerts of the last day ----------

        private var inboxBox: LinearLayout? = null
        private var inboxTitle: TextView? = null
        private var inboxAll = false

        /** The newest [limit] entries into [box], each with a ✕; below them "All" or "Clear all". */
        private fun fillInbox(box: LinearLayout, items: List<Inbox.Item>, limit: Int) {
            box.removeAllViews()
            inboxTitle?.text = if (items.isEmpty()) "Notifications" else "Notifications · ${items.size}"
            if (items.isEmpty()) {
                box.addView(ctx.label("Nothing in the last 24 hours.", 11f, C.TEXT3).apply { setPadding(ctx.dp(2f), ctx.dp(6f), 0, 0) })
                return
            }
            for (it in items.take(limit)) inboxRow(box, it)
            if (items.size > limit) {
                val r = RowView(ctx, "All notifications", "${items.size} items")
                r.setOnClickListener { showInbox() }
                box.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4f) })
            } else if (items.size > 1) {
                val r = RowView(ctx, "Clear all", "${items.size} items")
                r.setOnClickListener { Inbox.clear(ctx) }
                box.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4f) })
            }
        }

        /** All of the last day's, on a page of their own. */
        private fun showInbox() {
            inSub = true
            inboxAll = true
            list.removeAllViews()
            val items = Inbox.list(ctx)
            inboxTitle = ctx.label("Notifications · ${items.size}", 11f, C.TEXT2, bold = true)
            list.addView(inboxTitle, LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = ctx.dp(14f)
                bottomMargin = ctx.dp(2f)
                leftMargin = ctx.dp(2f)
            })
            if (items.size > 1) row("Clear all", "${items.size} items") { Inbox.clear(ctx); showMain() }
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            list.addView(box, LinearLayout.LayoutParams(-1, -2))
            inboxBox = box
            for (it in items) inboxRow(box, it)
            note("OK on a camera alert plays its recording; ✕ dismisses one that no longer matters.")
            post { firstInList()?.requestFocus() }
        }

        /** The list changed (a new alert came, one was dismissed): redrawn, the cursor kept near its place. */
        private fun inboxChanged() {
            val box = inboxBox ?: return
            if (!isAttachedToWindow || box.parent == null) return
            val focusAt = indexOfFocus(box)
            val items = Inbox.list(ctx)
            if (inboxAll) {
                if (items.isEmpty()) { showMain(); return }
                box.removeAllViews()
                for (it in items) inboxRow(box, it)
                inboxTitle?.text = "Notifications · ${items.size}"
            } else {
                fillInbox(box, items, INLINE)
            }
            if (focusAt >= 0) {
                val lines = (0 until box.childCount).map { box.getChildAt(it) }.filter { it.isFocusable || it is LinearLayout }
                val target = lines.getOrNull(focusAt) ?: lines.lastOrNull()
                val v = if (target is InboxLine) target.row else target
                if (v != null && v.isFocusable) v.requestFocus() else firstInList()?.requestFocus()
            }
        }

        private fun indexOfFocus(box: LinearLayout): Int {
            val f = box.findFocus() ?: return -1
            for (i in 0 until box.childCount) {
                val c = box.getChildAt(i)
                if (c === f || (c is android.view.ViewGroup && f.parent === c)) return i
            }
            return -1
        }

        /** One entry: the row (OK plays an alert's recording) and a round ✕ that dismisses it. */
        private fun inboxRow(box: LinearLayout, item: Inbox.Item) {
            val line = InboxLine(item)
            box.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4f) })
        }

        private inner class InboxLine(val item: Inbox.Item) : LinearLayout(ctx) {
            val row = InboxRow(item)

            init {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                row.setOnClickListener {
                    if (item.camera != null) {
                        val i = cfg.cameras.indexOfFirst { it.entity == item.camera }
                        val intent = Intent(ctx, CamerasActivity::class.java)
                        if (i >= 0) intent.putExtra("index", i).putExtra("at", item.at)
                        open(ctx, intent)
                    } else {
                        Inbox.dismiss(ctx, item.id)
                    }
                }
                addView(row, LayoutParams(0, -2, 1f))
                val x = ctx.label("✕", 14f, C.TEXT, bold = true).apply {
                    gravity = Gravity.CENTER
                    isFocusable = true
                    isClickable = true
                    contentDescription = "Dismiss"
                }
                val round = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(C.ROW)
                }
                x.background = round
                FocusGlide.shape(x, -1f)
                x.setOnFocusChangeListener { v, f ->
                    (v as TextView).setTextColor(if (f) C.DARK else C.TEXT)
                    round.setColor(if (f) C.TEXT else C.ROW)
                }
                x.setOnClickListener { Inbox.dismiss(ctx, item.id) }
                addView(x, LayoutParams(ctx.dp(36f), ctx.dp(36f)).apply { leftMargin = ctx.dp(8f) })
            }
        }

        /** An entry's row: its icon, title and time; the text under them, all of it while focused. */
        private inner class InboxRow(item: Inbox.Item) : LinearLayout(ctx) {
            private val accent = when (item.level) {
                "alert" -> 0xFFF09595.toInt()
                "warn" -> 0xFFFAC775.toInt()
                else -> C.ON
            }
            private val icon = ImageView(ctx)
            private val title = ctx.label(item.title.ifBlank { item.text }, 12f, C.TEXT).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            private val time = ctx.label(whenText(item.at), 10f, C.TEXT3).apply { maxLines = 1 }
            private val text = ctx.label(if (item.title.isBlank()) "" else item.text, 10f, C.TEXT3).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
            private val r = ctx.dp(Look.R_SMALL).toFloat()

            init {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                setPadding(ctx.dp(10f), ctx.dp(8f), ctx.dp(12f), ctx.dp(8f))
                val res = if (item.camera != null) R.drawable.ic_camera
                    else ctx.resources.getIdentifier("ic_" + item.icon.lowercase().replace(Regex("[^a-z0-9_]"), ""), "drawable", ctx.packageName)
                icon.setImageResource(if (res != 0) res else R.drawable.ic_bell)
                addView(icon, LayoutParams(ctx.dp(18f), ctx.dp(18f)).apply { rightMargin = ctx.dp(10f) })
                val texts = LinearLayout(ctx).apply { orientation = VERTICAL }
                val top = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                top.addView(title, LayoutParams(0, -2, 1f))
                top.addView(time, LayoutParams(-2, -2).apply { leftMargin = ctx.dp(8f) })
                texts.addView(top)
                if (text.text.isNotEmpty()) texts.addView(text, LayoutParams(-1, -2).apply { topMargin = ctx.dp(2f) })
                addView(texts, LayoutParams(0, -2, 1f))
                FocusGlide.shape(this, r)
                paint(false)
                setOnFocusChangeListener { _, f -> paint(f) }
            }

            private fun paint(f: Boolean) {
                background = if (f) FocusGlide.fill(this, r) else rounded(C.ROW, r)
                title.setTextColor(if (f) C.DARK else C.TEXT)
                time.setTextColor(if (f) C.DARK2 else C.TEXT3)
                text.setTextColor(if (f) C.DARK2 else C.TEXT3)
                text.maxLines = if (f) 6 else 2
                icon.setColorFilter(if (f) C.DARK else accent)
            }
        }

        /** "14:32" today, "Yesterday 21:10" before. */
        private fun whenText(at: Long): String {
            val t = Lang.sdf("HH:mm").format(Date(at))
            val day = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            return if (at >= day) t else "Yesterday $t"
        }

        // ---------- Home: cameras and the room's lights ----------

        private class Light(val entity: String, val name: String, val on: Boolean, val pct: Int)

        /** Cameras, all the lights of the TV's room, and the chosen lights (or the room's groups). */
        private fun loadHome(box: LinearLayout) {
            val client = ha ?: return
            val adb = cfg.adbEntity ?: ""
            val chosen = org.json.JSONArray(cfg.quickLights.map { it.entity }).toString()
            Bg.run({ try {
                val t = client.template(
                    "{% set a = area_id('$adb') %}{% set ls = (area_entities(a) | select('match', 'light\\.') | list) if a else [] %}" +
                        "{% set chosen = $chosen %}{% if chosen | length == 0 %}{% set g = namespace(c=[]) %}" +
                        "{% for l in ls if state_attr(l, 'group_entities') or state_attr(l, 'entity_id') %}{% set g.c = g.c + [l] %}{% endfor %}" +
                        "{% set chosen = g.c[:4] %}{% endif %}{% set ns = namespace(o=[]) %}" +
                        "{% for l in chosen %}{% set ns.o = ns.o + [[l, state_attr(l, 'friendly_name') or l, states(l), ((state_attr(l, 'brightness') or 0) / 2.55) | round(0) | int]] %}{% endfor %}" +
                        "{{ {'area': a or '', 'name': area_name(a) if a else '', 'on': ls | select('is_state', 'on') | list | length, 'total': ls | length, 'lights': ns.o} | tojson }}"
                )
                try {
                    org.json.JSONObject(t)
                } catch (e: Exception) {
                    android.util.Log.w("OtthonQuick", "lights: Home Assistant answered $t", e)
                    null
                }
            } catch (e: Exception) {
                android.util.Log.w("OtthonQuick", "lights: Home Assistant not reachable", e)
                null
            } }) { o ->
                if (o == null) android.util.Log.w("OtthonQuick", "lights: no answer from Home Assistant")
                if (!isAttachedToWindow) return@run
                box.removeAllViews()
                val tiles = ArrayList<Tile>()
                if (cfg.cameras.isNotEmpty()) tiles.add(Tile("Cameras", "${cfg.cameras.size} cameras") { open(ctx, Intent(ctx, CamerasActivity::class.java)) })
                // the camera pop-ups on or off; the alerts still go into the list below
                if (cfg.popupTriggers.isNotEmpty()) tiles.add(Tile("Camera alerts", if (popupsOn) "On" else "Off") { t ->
                    popupsOn = !popupsOn
                    val on = popupsOn
                    ConfigStore.edit(ctx) { r -> ConfigStore.obj(r, "popups").put("enabled", on) }
                    PopupService.sync(ctx)
                    t.value(if (on) "On" else "Off")
                })
                if (o != null) {
                    val area = o.optString("area", "")
                    val total = o.optInt("total", 0)
                    val on = o.optInt("on", 0)
                    if (area.isNotBlank() && total > 0) {
                        tiles.add(Tile("All lights", if (on == 0) "Off" else "$on of $total on") { t ->
                            val turnOn = on == 0
                            t.value(if (turnOn) "Turning on…" else "Turning off…")
                            Bg.run({ client.call("light", if (turnOn) "turn_on" else "turn_off", org.json.JSONObject().put("area_id", area)) }) {
                                Bg.main.postDelayed({ refreshHome(box) }, 700)
                            }
                        })
                    }
                    val arr = o.optJSONArray("lights") ?: org.json.JSONArray()
                    for (i in 0 until arr.length()) {
                        val a = arr.getJSONArray(i)
                        val l = Light(a.getString(0), a.getString(1), a.getString(2) == "on", a.optInt(3, 0))
                        val label = cfg.quickLights.firstOrNull { it.entity == l.entity }?.label?.takeIf { it.isNotBlank() } ?: l.name
                        tiles.add(Tile(label, if (l.on) (if (l.pct > 0) "On · ${l.pct}%" else "On") else "Off") { t ->
                            t.value(if (l.on) "Turning off…" else "Turning on…")
                            Bg.run({ client.call("light", "toggle", org.json.JSONObject().put("entity_id", l.entity)) }) {
                                Bg.main.postDelayed({ refreshHome(box) }, 700)
                            }
                        })
                    }
                }
                if (tiles.isEmpty()) {
                    box.addView(ctx.label("Nothing to show yet.", 11f, C.TEXT3).apply { setPadding(ctx.dp(2f), ctx.dp(6f), 0, 0) })
                    return@run
                }
                val focusedIndex = lastFocusedHome.also { lastFocusedHome = -1 }
                // the tiles go into the Home box, two to a line
                var line: LinearLayout? = null
                tiles.forEachIndexed { i, t ->
                    if (i % 2 == 0) {
                        line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                        box.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4f) })
                    }
                    line!!.addView(t, LinearLayout.LayoutParams(0, ctx.dp(54f), 1f).apply { if (i % 2 == 1) leftMargin = ctx.dp(4f) })
                }
                if (tiles.size % 2 == 1) line?.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = ctx.dp(4f) })
                if (focusedIndex >= 0) tiles.getOrNull(focusedIndex)?.requestFocus()
            }
        }

        private var lastFocusedHome = -1

        private fun focusedTileIndex(box: LinearLayout): Int {
            val f = box.findFocus() ?: return -1
            var i = 0
            for (r in 0 until box.childCount) {
                val line = box.getChildAt(r) as? LinearLayout ?: continue
                for (c in 0 until line.childCount) {
                    val v = line.getChildAt(c)
                    if (v is Tile) {
                        if (v === f) return i
                        i++
                    }
                }
            }
            return -1
        }

        private fun refreshHome(box: LinearLayout) {
            lastFocusedHome = focusedTileIndex(box)
            loadHome(box)
        }

        /** The network: the Wi-Fi's name (needs location access, given over ADB), or Ethernet. */
        @Suppress("DEPRECATION")
        private fun network(): String = try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "Offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                    val ssid = wm.connectionInfo?.ssid?.trim('"')
                    android.util.Log.i("OtthonQuick", "Wi-Fi name as Android tells it: $ssid")
                    if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") "Wi-Fi" else ssid
                }
                else -> "Connected"
            }
        } catch (e: Exception) {
            android.util.Log.w("OtthonQuick", "network state not readable", e)
            ""
        }

        /**
         * Power, in the panel. Turning off here shuts the TV down completely, not just to sleep:
         * a sleeping TV keeps its optical output alive, which holds a DAC that picks its input by
         * itself (the Holo Cyan 2) on the TV. The remote's power button starts it again.
         */
        private fun showPower() {
            inSub = true
            list.removeAllViews()
            section("Power")
            row("Turn off", "Completely: the optical output goes quiet too") {
                val c = cfg
                hide()
                Adb.run(ctx, c, "reboot -p") { res ->
                    if (res != Adb.Result.OK) Toast.makeText(ctx, Lang.tr("Couldn't turn the TV off: " + Adb.explain(res)), Toast.LENGTH_LONG).show()
                }
            }
            row("Restart") {
                val c = cfg
                hide()
                Adb.run(ctx, c, "reboot") { res ->
                    if (res != Adb.Result.OK) Toast.makeText(ctx, Lang.tr("Couldn't restart: " + Adb.explain(res)), Toast.LENGTH_LONG).show()
                }
            }
            post { firstInList()?.requestFocus() }
        }

        /** The running apps (the ones with a tile), the biggest first; OK closes one. */
        private fun showRunning() {
            inSub = true
            list.removeAllViews()
            section("Running apps")
            val wait = ctx.label("Asking the TV…", 11f, C.TEXT3).apply { setPadding(ctx.dp(2f), ctx.dp(6f), 0, 0) }
            list.addView(wait)
            val c = cfg
            Bg.run({ RunningApps.list(ctx, c) }) { apps ->
                if (!inSub || wait.parent == null) return@run
                list.removeView(wait)
                if (apps == null) {
                    list.addView(ctx.label("Couldn't ask the TV through Home Assistant.", 11f, C.TEXT3).apply { setPadding(ctx.dp(2f), ctx.dp(6f), 0, 0) })
                    return@run
                }
                if (apps.isEmpty()) {
                    list.addView(ctx.label("No apps are running.", 11f, C.TEXT3).apply { setPadding(ctx.dp(2f), ctx.dp(6f), 0, 0) })
                    return@run
                }
                if (apps.size > 1) {
                    row("Close all", "${apps.size} apps") { r ->
                        r.valueView.text = "Closing…"
                        Bg.run({ RunningApps.close(c, apps.map { it.pkg }) }) { showRunning() }
                    }
                }
                for (a in apps) appRow(a, c)
                note("OK brings an app to the front; ▶ then OK, or holding OK, closes it.")
                post { firstInList()?.requestFocus() }
            }
        }

        /** A running app: the row brings it to the front, the round ✕ beside it closes it. */
        private fun appRow(a: RunningApps.App, c: Config) {
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val r = RowView(ctx, a.label, if (a.mb > 0) "${a.mb} MB" else null)
            fun close() {
                r.valueView.text = "Closing…"
                Bg.run({ RunningApps.close(c, listOf(a.pkg)) }) { ok ->
                    if (ok == true) showRunning() else r.valueView.text = "Couldn't close it"
                }
            }
            r.setOnClickListener {
                val pm = ctx.packageManager
                val i = pm.getLeanbackLaunchIntentForPackage(a.pkg) ?: pm.getLaunchIntentForPackage(a.pkg)
                if (i == null || !open(ctx, i)) r.valueView.text = "Couldn't open it"
            }
            r.setOnLongClickListener { close(); true }
            line.addView(r, LinearLayout.LayoutParams(0, -2, 1f))
            val x = ctx.label("✕", 14f, C.TEXT, bold = true).apply {
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                contentDescription = "Close ${a.label}"
            }
            val round = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(C.ROW)
            }
            x.background = round
            FocusGlide.shape(x, -1f)
            x.setOnFocusChangeListener { v, f ->
                (v as TextView).setTextColor(if (f) C.DARK else C.TEXT)
                round.setColor(if (f) C.TEXT else C.ROW)
            }
            x.setOnClickListener { close() }
            line.addView(x, LinearLayout.LayoutParams(ctx.dp(36f), ctx.dp(36f)).apply { leftMargin = ctx.dp(8f) })
            list.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4f) })
        }

        private fun note(text: String) {
            list.addView(ctx.label(text, 10f, C.TEXT3).apply { setPadding(ctx.dp(2f), ctx.dp(8f), 0, 0) })
        }

        /** Inputs, in the panel: the TV, then the HDMI inputs with what is plugged in. */
        private fun showInputs() {
            inSub = true
            list.removeAllViews()
            section("Inputs")
            var any = false
            if (Inputs.hasTv(ctx)) {
                any = true
                row("TV") { if (Inputs.openTv(ctx)) hide() }
            }
            for (inp in Inputs.visible(ctx, cfg)) {
                any = true
                row(inp.name, inp.device) { if (Inputs.open(ctx, inp.id)) hide() }
            }
            if (!any) list.addView(ctx.label("This TV reports no inputs.", 11f, C.TEXT3).apply { setPadding(ctx.dp(2f), ctx.dp(6f), 0, 0) })
            post { firstInList()?.requestFocus() }
        }

        /** A tile: the name, and under it what it is set to now; the settings rows' colours and cursor. */
        inner class Tile(name: String, value: String, onClick: (Tile) -> Unit) : LinearLayout(ctx) {
            private val title = ctx.label(name, 12f, C.TEXT).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            private val sub = ctx.label(value, 11f, C.TEXT3).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            private val r = ctx.dp(Look.R_SMALL).toFloat()

            init {
                orientation = VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                setPadding(ctx.dp(12f), 0, ctx.dp(10f), 0)
                addView(title)
                addView(sub, LayoutParams(-2, -2).apply { topMargin = ctx.dp(3f) })
                FocusGlide.shape(this, r)
                paint(false)
                setOnFocusChangeListener { _, f -> paint(f) }
                setOnClickListener { onClick(this) }
            }

            private fun paint(f: Boolean) {
                background = if (f) FocusGlide.fill(this, r) else rounded(C.ROW, r)
                title.setTextColor(if (f) C.DARK else C.TEXT)
                sub.setTextColor(if (f) C.DARK2 else C.TEXT3)
            }

            fun value(v: String) {
                sub.text = v
            }
        }
    }
}

/**
 * The sleep timer: puts the TV to sleep after 15 minutes … 2 hours, through Home Assistant's
 * ADB (an app cannot press the remote's power button). The time is kept, so the button service's
 * tick can still do it if the launcher was restarted meanwhile.
 */
object SleepTimer {
    private val STEPS = listOf(0, 15, 30, 60, 90, 120)
    private const val KEY = "sleep_at"
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private val fire = Runnable { app?.let { check(it) } }

    private fun at(ctx: Context) = Prefs.of(ctx).getLong(KEY, 0L)

    fun describe(ctx: Context): String {
        val t = at(ctx)
        val left = ((t - System.currentTimeMillis()) / 60_000L).toInt()
        return if (t == 0L || left < 0) "Off" else "${left + 1} min left"
    }

    /** One press: the next step (off → 15 → 30 → 60 → 90 → 120 → off). Returns the new value. */
    fun next(ctx: Context): String {
        val t = at(ctx)
        val left = if (t == 0L) 0 else ((t - System.currentTimeMillis()) / 60_000L).toInt() + 1
        val step = STEPS.firstOrNull { it > left } ?: 0
        set(ctx, step)
        return if (step == 0) "Off" else "$step min"
    }

    fun set(ctx: Context, minutes: Int) {
        app = ctx.applicationContext
        main.removeCallbacks(fire)
        if (minutes <= 0) {
            Prefs.of(ctx).edit().remove(KEY).apply()
            return
        }
        Prefs.of(ctx).edit().putLong(KEY, System.currentTimeMillis() + minutes * 60_000L).apply()
        main.postDelayed(fire, minutes * 60_000L + 500L)
    }

    /** Sleeps the TV when the time has come; called by the timer and by the button service's tick. */
    fun check(ctx: Context) {
        val t = at(ctx)
        if (t == 0L || System.currentTimeMillis() < t) return
        Prefs.of(ctx).edit().remove(KEY).apply()
        Adb.run(ctx.applicationContext, Config.load(ctx), "input keyevent KEYCODE_SLEEP") { }
    }
}

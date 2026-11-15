package hu.otthon.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The kids profile: only the apps a grown-up chose, big friendly tiles, and leaving it needs the
 * PIN. Daily time, bedtime and wake-up come from Home Assistant ("Kids TV" helpers) when they are
 * there, so they are shared by every TV and can be changed from the phone; otherwise from the
 * settings of this TV.
 */
data class Kids(
    val on: Boolean,
    val name: String,
    /** Packages the kids profile may open. */
    val apps: List<String>,
    /** Empty: leaving the kids profile needs no PIN. */
    val pin: String,
    /** Switch to the kids profile whenever the TV goes to sleep or starts. */
    val startKids: Boolean,
    /** Minutes a day; 0 = no limit. Used when Home Assistant has no "Kids TV" helpers. */
    val limit: Int,
    /** "19:30" or null. */
    val bedtime: String?,
    /** "07:00" or null. */
    val wake: String?
) {
    companion object {
        /** A good start for the list of allowed apps: whichever of these is installed. */
        val SUGGESTED = listOf(
            "com.google.android.youtube.tvkids",
            "com.disney.disneyplus",
            "com.netflix.ninja"
        )

        fun from(o: JSONObject?): Kids {
            val j = o ?: JSONObject()
            val arr = j.optJSONArray("apps")
            val apps = if (arr == null) SUGGESTED else (0 until arr.length()).mapNotNull { arr.optString(it, "").ifBlank { null } }
            return Kids(
                on = j.optBoolean("enabled", false),
                name = j.optString("name", "").ifBlank { "Kids" },
                apps = apps,
                pin = j.optString("pin", ""),
                startKids = j.optBoolean("start_kids", false),
                limit = j.optInt("limit", 0).coerceIn(0, 600),
                bedtime = j.optString("bedtime", "").ifBlank { null },
                wake = j.optString("wake", "").ifBlank { null }
            )
        }
    }
}

/** Which profile this TV is in. Kept in the preferences, so it survives a restart. */
object Profile {
    private const val KEY = "kids_active"

    fun kids(ctx: Context, cfg: Config): Boolean = cfg.kids.on && Prefs.of(ctx).getBoolean(KEY, false)

    fun set(ctx: Context, kids: Boolean) {
        Prefs.of(ctx).edit().putBoolean(KEY, kids).apply()
        listeners.toList().forEach { it() }
    }

    /** Things that redraw when the profile changes (the home screen). */
    val listeners = ArrayList<() -> Unit>()

    /** After the TV has started: the kids profile, when it is set to start with it. */
    fun checkBoot(ctx: Context, cfg: Config) {
        val boot = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val p = Prefs.of(ctx)
        val last = p.getLong("boot_at", 0L)
        if (Math.abs(boot - last) > 120_000L) {
            p.edit().putLong("boot_at", boot).apply()
            if (cfg.kids.on && cfg.kids.startKids && last != 0L) set(ctx, true)
        }
    }
}

/**
 * Time in the kids profile: how much is used today, how much is allowed, bedtime and wake-up.
 * Every minute spent in an allowed app is counted here and in Home Assistant.
 */
object KidsTime {
    const val ALLOWED = "input_boolean.kids_tv_allowed"
    const val LIMIT = "input_number.kids_tv_daily_limit"
    const val USED = "input_number.kids_tv_minutes_today"
    const val EXTRA = "input_number.kids_tv_extra_minutes"
    const val BEDTIME = "input_datetime.kids_tv_bedtime"
    const val WAKE = "input_datetime.kids_tv_wake_up"

    enum class Reason { PAUSED, EARLY, BEDTIME, USED_UP }

    /** [left]: minutes left today, or null without a daily limit. */
    data class Status(val blocked: Reason?, val left: Int?, val bedtime: String?, val wake: String?)

    // what Home Assistant said last; null where it has no such helper
    @Volatile private var haAllowed: Boolean? = null
    @Volatile private var haLimit: Int? = null
    @Volatile private var haUsed: Int? = null
    @Volatile private var haExtra: Int? = null
    @Volatile private var haBedtime: String? = null
    @Volatile private var haWake: String? = null
    @Volatile private var haAt = 0L
    @Volatile var haReady = false
        private set

    private fun today() = LocalDate.now().toString()

    private fun localUsed(ctx: Context): Int {
        val p = Prefs.of(ctx)
        return if (p.getString("kids_day", "") == today()) p.getInt("kids_used", 0) else 0
    }

    /** Reads the "Kids TV" helpers from Home Assistant, at most once a minute (or now with [force]). Blocking. */
    fun refresh(cfg: Config, force: Boolean = false) {
        if (!cfg.haEnabled) {
            haReady = false
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - haAt < 55_000L) return
        haAt = now
        val ha = HaClient(cfg.haUrl!!, cfg.haToken!!)
        fun st(e: String): String? = try {
            ha.state(e).optString("state", "").takeIf { it.isNotBlank() && it != "unavailable" && it != "unknown" }
        } catch (x: Exception) {
            null
        }
        haAllowed = st(ALLOWED)?.let { it == "on" }
        haLimit = st(LIMIT)?.toDoubleOrNull()?.toInt()
        haUsed = st(USED)?.toDoubleOrNull()?.toInt()
        haExtra = st(EXTRA)?.toDoubleOrNull()?.toInt()
        haBedtime = st(BEDTIME)?.take(5)
        haWake = st(WAKE)?.take(5)
        haReady = haLimit != null || haUsed != null || haAllowed != null
    }

    fun status(ctx: Context, cfg: Config): Status {
        val k = cfg.kids
        val limit = haLimit ?: k.limit
        val used = maxOf(haUsed ?: 0, if (haUsed == null) localUsed(ctx) else 0)
        val extra = haExtra ?: 0
        val bed = if (haReady) haBedtime else k.bedtime
        val wake = if (haReady) haWake else k.wake
        val left = if (limit > 0) maxOf(0, limit + extra - used) else null
        val now = LocalTime.now()
        fun t(s: String?) = try { s?.let { LocalTime.parse(it.padStart(5, '0')) } } catch (e: Exception) { null }
        val bt = t(bed)
        val wt = t(wake)
        val reason = when {
            haAllowed == false -> Reason.PAUSED
            wt != null && bt != null && wt < bt && now < wt -> Reason.EARLY
            wt != null && bt == null && now < wt -> Reason.EARLY
            bt != null && !now.isBefore(bt) && (wt == null || wt < bt) -> Reason.BEDTIME
            left != null && left <= 0 -> Reason.USED_UP
            else -> null
        }
        return Status(reason, left, bed, wake)
    }

    /** One more minute watched: counted here, and added to Home Assistant's counter. Blocking. */
    fun countMinute(ctx: Context, cfg: Config) {
        val p = Prefs.of(ctx)
        val day = today()
        val used = if (p.getString("kids_day", "") == day) p.getInt("kids_used", 0) else 0
        p.edit().putString("kids_day", day).putInt("kids_used", used + 1).apply()
        if (cfg.haEnabled && haUsed != null) {
            haUsed = (haUsed ?: 0) + 1
            try {
                HaClient(cfg.haUrl!!, cfg.haToken!!).call("input_number", "increment", USED)
            } catch (e: Exception) { }
        }
    }

    fun describe(s: Status, name: String): Pair<String, String> = when (s.blocked) {
        Reason.BEDTIME -> "Good night, $name!" to "The TV is going to sleep now. See you tomorrow!"
        Reason.EARLY -> "Good morning, $name!" to "The TV is still asleep. It wakes up at ${s.wake}."
        Reason.USED_UP -> "That's all for today!" to "See you tomorrow, $name! A grown-up can add more time."
        Reason.PAUSED -> "Time for a break!" to "A grown-up has paused the TV for now."
        null -> "" to ""
    }
}

/**
 * Keeps the kids profile to its apps: called by [KeyService] whenever a new window comes up.
 * Anything that can be opened as an app, plus the settings and the stock home screens, is checked;
 * system overlays (volume, keyboard, the screensaver) are left alone.
 */
object KidsGuard {
    private val ALWAYS_BLOCKED = setOf(
        "com.android.tv.settings", "com.android.settings", "com.sony.dtv.settings",
        Apps.STOCK_ATV, Apps.STOCK_GTV, "com.android.vending", "com.google.android.katniss",
        "com.google.android.apps.tv.launcherx", "com.google.android.tvrecommendations"
    )
    private val NEVER_BLOCKED = setOf(
        "android", "com.android.systemui", "com.google.android.inputmethod.latin", "com.google.android.leanback.ime",
        // screensavers and ambient mode
        "com.google.android.backdrop", "com.google.android.apps.tv.dreamx", "com.android.dreams.basic", "com.android.dreams.phototable"
    )

    /** Last app seen in front (not counting system overlays). */
    @Volatile var front: String? = null
    private var lastBounce = 0L

    private fun isApp(ctx: Context, pkg: String): Boolean {
        val pm = ctx.packageManager
        return pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null
    }

    /** True when [pkg] counts as an app on the screen (and not an overlay). */
    fun relevant(ctx: Context, pkg: String): Boolean =
        pkg == ctx.packageName || pkg in ALWAYS_BLOCKED || (pkg !in NEVER_BLOCKED && isApp(ctx, pkg))

    fun allowed(ctx: Context, cfg: Config, pkg: String, status: KidsTime.Status): Boolean {
        if (pkg == ctx.packageName) return true
        if (pkg in ALWAYS_BLOCKED) return false
        return pkg in cfg.kids.apps && status.blocked == null
    }

    /** Back to the kids home screen, saying why. */
    fun bounce(ctx: Context, pkg: String, reason: KidsTime.Reason?) {
        val now = SystemClock.uptimeMillis()
        if (now - lastBounce < 1200L) return
        lastBounce = now
        val label = try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { "" }
        val i = Intent(ctx, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("kids_blocked", label)
            .putExtra("kids_reason", reason?.name ?: "")
        try { ctx.startActivity(i) } catch (e: Exception) { }
    }
}

// ---------- looks ----------

object KidsLook {
    /** Candy colours for the tiles' frames and the avatar. */
    val CANDY = intArrayOf(0xFFFF8FB8.toInt(), 0xFFFFD166.toInt(), 0xFF7DE2B5.toInt(), 0xFF74C0FC.toInt(), 0xFFC3A6FF.toInt(), 0xFFFFA37A.toInt())

    val headline: Typeface by lazy { Typeface.create("sans-serif-black", Typeface.NORMAL) }
}

/** A round avatar: the first letter on a candy gradient, or a person outline for the grown-ups' profile. */
class AvatarDrawable(private val letter: String?, private val color: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun draw(c: Canvas) {
        val b = bounds
        val r = minOf(b.width(), b.height()) / 2f
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        if (letter != null) {
            p.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, color, KidsLook.CANDY[(KidsLook.CANDY.indexOf(color) + 2).coerceAtLeast(0) % KidsLook.CANDY.size], Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, p)
            p.shader = null
            p.color = Color.WHITE
            p.textAlign = Paint.Align.CENTER
            p.typeface = KidsLook.headline
            p.textSize = r * 1.1f
            p.setShadowLayer(r * 0.08f, 0f, r * 0.04f, 0x40000000)
            c.drawText(letter, cx, cy + r * 0.38f, p)
            p.clearShadowLayer()
        } else {
            p.color = color
            c.drawCircle(cx, cy - r * 0.22f, r * 0.3f, p)
            path.reset()
            path.addArc(cx - r * 0.56f, cy + r * 0.12f, cx + r * 0.56f, cy + r * 1.2f, 180f, 180f)
            path.close()
            c.drawPath(path, p)
        }
    }

    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * A big, friendly app tile: the app's banner in a rounded candy-coloured frame, its name below.
 * It bounces up when the cursor arrives.
 */
class KidTile(ctx: Context, val app: AppEntry, candy: Int) : LinearLayout(ctx) {
    val card = FrameLayout(ctx)
    private val name: TextView

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
        val radius = ctx.dp(20f).toFloat()
        card.isFocusable = true
        card.isClickable = true
        card.clipToOutline = true
        card.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        card.background = rounded(0xFF20222A.toInt(), radius, ctx.dp(3f), candy)
        card.setPadding(ctx.dp(3f), ctx.dp(3f), ctx.dp(3f), ctx.dp(3f))
        card.contentDescription = app.label
        FocusGlide.shape(card, radius, 0f)
        FocusGlide.glow(card, AppColors.of(app))
        val banner = app.banner
        if (banner != null) {
            card.addView(ImageView(ctx).apply {
                setImageDrawable(banner)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                background = rounded(0xFF20222A.toInt(), radius - ctx.dp(3f))
            }, FrameLayout.LayoutParams(-1, -1))
        } else {
            card.addView(ImageView(ctx).apply { setImageDrawable(app.icon) }, FrameLayout.LayoutParams(ctx.dp(56f), ctx.dp(56f), Gravity.CENTER))
        }
        addView(card, LayoutParams(-1, 0, 1f))
        name = ctx.label(app.label, 15f, C.TEXT).apply {
            typeface = KidsLook.headline
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(ctx.dp(3f).toFloat(), 0f, ctx.dp(1f).toFloat(), 0x99000000.toInt())
            setPadding(0, ctx.dp(10f), 0, 0)
        }
        addView(name, LayoutParams(-1, -2))
        card.setOnFocusChangeListener { v, f ->
            name.setTextColor(if (f) candy else C.TEXT)
            val s = if (f) 1.1f else 1f
            val a = v.animate().scaleX(s).scaleY(s).setDuration(if (f) 380 else 200)
            a.interpolator = if (f && Motion.level == Motion.FULL) OvershootInterpolator(2.4f) else Motion.EASE
            a.start()
            name.animate().translationY(if (f) context.dp(6f).toFloat() else 0f).setDuration(220).start()
            if (f && Motion.level == Motion.FULL) wiggle(v)
        }
    }

    private fun wiggle(v: View) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            addUpdateListener {
                val t = it.animatedFraction
                v.rotation = sin(t * Math.PI.toFloat() * 3f) * 2.4f * (1f - t)
            }
            start()
        }
    }
}

/**
 * The kids home screen: a big clock and a hello, the time left, the allowed apps as big tiles,
 * and below them what those apps recommend. [onAvatar] opens the profile switch.
 */
class KidsHome(private val act: Activity, private val onAvatar: () -> Unit) : FrameLayout(act) {
    private val main = LinearLayout(act)
    val clock: TextView
    private val hello: TextView
    private val timePill: TextView
    val avatar: ImageView
    val tiles = LinearLayout(act)
    private val tileScroll = RowScroller(act)
    val reco = RecoPanel(act)
    private val recoScroll = ColScroller(act)
    private val empty: TextView
    private val bubble: TextView
    val sleep: SleepView
    private var kids: Kids? = null

    init {
        clipChildren = false
        val side = act.dp(56f)
        main.orientation = LinearLayout.VERTICAL
        main.clipChildren = false
        main.setPadding(0, act.dp(30f), 0, act.dp(16f))
        addView(main, LayoutParams(-1, -1))

        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        clock = act.label("--:--", 54f, C.TEXT).apply { typeface = KidsLook.headline; letterSpacing = -0.01f }
        hello = act.label("", 22f, KidsLook.CANDY[1]).apply {
            typeface = KidsLook.headline
            setShadowLayer(act.dp(4f).toFloat(), 0f, act.dp(1f).toFloat(), 0x80000000.toInt())
        }
        col.addView(clock)
        col.addView(hello)
        top.addView(col)
        top.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
        timePill = act.label("", 15f, C.DARK).apply {
            typeface = KidsLook.headline
            setPadding(act.dp(16f), act.dp(8f), act.dp(16f), act.dp(8f))
            visibility = View.GONE
        }
        // room for the avatar, which sits on top of everything (also over the sleep screen)
        top.addView(timePill, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = act.dp(14f + 52f) })
        avatar = ImageView(act).apply {
            isFocusable = true
            isClickable = true
            contentDescription = "Profiles"
            FocusGlide.shape(this, -1f)
            setOnClickListener { onAvatar() }
            setOnFocusChangeListener { v, f -> v.animate().scaleX(if (f) 1.12f else 1f).scaleY(if (f) 1.12f else 1f).setDuration(180).start() }
        }
        main.addView(top, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = side; rightMargin = side })

        main.addView(View(act), LinearLayout.LayoutParams(1, 0, 0.6f))

        tileScroll.setPadding(side, act.dp(14f), side, act.dp(14f))
        tiles.orientation = LinearLayout.HORIZONTAL
        tiles.clipChildren = false
        tileScroll.addView(tiles, FrameLayout.LayoutParams(-2, act.dp(170f)))
        main.addView(tileScroll, LinearLayout.LayoutParams(-1, -2))
        empty = act.label("A grown-up can choose the apps in Settings › Kids profile.", 16f, C.TEXT).apply {
            typeface = KidsLook.headline
            visibility = View.GONE
        }
        main.addView(empty, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = side; topMargin = act.dp(10f) })

        recoScroll.clipToPadding = false
        recoScroll.clipChildren = false
        recoScroll.setPadding(0, act.dp(10f), 0, act.dp(10f))
        recoScroll.addView(reco, FrameLayout.LayoutParams(-1, -2))
        recoScroll.anchor = { v -> reco.scrollFor(v) }
        main.addView(recoScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        bubble = act.label("", 17f, C.DARK).apply {
            typeface = KidsLook.headline
            background = rounded(0xF5FFF4D6.toInt(), act.dp(22f).toFloat())
            setPadding(act.dp(20f), act.dp(10f), act.dp(20f), act.dp(10f))
            alpha = 0f
            elevation = act.dp(8f).toFloat()
        }
        addView(bubble, LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = act.dp(26f) })

        sleep = SleepView(act)
        sleep.visibility = View.GONE
        addView(sleep, LayoutParams(-1, -1))
        // the avatar stays above the sleep screen: a grown-up can still switch profiles
        addView(avatar, LayoutParams(act.dp(52f), act.dp(52f), Gravity.TOP or Gravity.END).apply {
            topMargin = act.dp(44f)
            rightMargin = side
        })
    }

    fun bind(cfg: Config, apps: List<AppEntry>, launch: (AppEntry) -> Unit) {
        val k = cfg.kids
        kids = k
        hello.text = "Hi, ${k.name}!"
        avatar.setImageDrawable(AvatarDrawable(k.name.take(1).uppercase(), KidsLook.CANDY[0]))
        tiles.removeAllViews()
        val w = act.dp(250f)
        apps.forEachIndexed { i, app ->
            val t = KidTile(act, app, KidsLook.CANDY[i % KidsLook.CANDY.size])
            t.card.setOnClickListener { launch(app) }
            tiles.addView(t, LinearLayout.LayoutParams(w, -1).apply { if (i > 0) leftMargin = act.dp(22f) })
        }
        empty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
    }

    fun firstTile(): View? = (tiles.getChildAt(0) as? KidTile)?.card

    /** The time-left pill, and the sleep screen when nothing may be watched now. */
    fun showStatus(s: KidsTime.Status) {
        val k = kids ?: return
        val left = s.left
        if (s.blocked == null && left != null) {
            timePill.visibility = View.VISIBLE
            timePill.text = if (left >= 60) "⏱ ${left / 60} h ${left % 60} min left" else "⏱ $left min left"
            val c = when {
                left <= 5 -> 0xFFFF8F8F.toInt()
                left <= 15 -> KidsLook.CANDY[1]
                else -> KidsLook.CANDY[2]
            }
            timePill.background = rounded(c, act.dp(20f).toFloat())
        } else if (s.blocked == null && s.bedtime != null) {
            timePill.visibility = View.VISIBLE
            timePill.text = "🌙 Until ${s.bedtime}"
            timePill.background = rounded(KidsLook.CANDY[4], act.dp(20f).toFloat())
        } else {
            timePill.visibility = View.GONE
        }
        val asleep = s.blocked != null
        if (asleep) {
            val (title, sub) = KidsTime.describe(s, k.name)
            sleep.set(title, sub, s.blocked == KidsTime.Reason.EARLY)
        }
        if (asleep != (sleep.visibility == View.VISIBLE)) {
            sleep.visibility = if (asleep) View.VISIBLE else View.GONE
            main.descendantFocusability = if (asleep) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
            if (asleep) avatar.requestFocus() else firstTile()?.requestFocus()
        }
    }

    val asleep: Boolean get() = sleep.visibility == View.VISIBLE

    /** A friendly note at the top, for a few seconds: why an app did not open. */
    fun say(text: String) {
        bubble.text = text
        bubble.animate().cancel()
        bubble.translationY = -act.dp(20f).toFloat()
        bubble.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(OvershootInterpolator(1.6f)).withEndAction {
            bubble.animate().alpha(0f).setStartDelay(3200).setDuration(400).start()
        }.start()
    }

    /** Everything the reindeer and bears may walk on. */
    fun places(): List<View> {
        val out = ArrayList<View>()
        for (i in 0 until tiles.childCount) (tiles.getChildAt(i) as? KidTile)?.let { out.add(it.card) }
        out.add(avatar)
        if (timePill.visibility == View.VISIBLE) out.add(timePill)
        return out
    }
}

/** The screen when the TV time is over: a night sky with a sleepy moon, floating Zzz and a kind message. */
class SleepView(ctx: Context) : FrameLayout(ctx) {
    private val title: TextView
    private val sub: TextView
    private val sky = SkyView(ctx)

    init {
        isClickable = true
        addView(sky, LayoutParams(-1, -1))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        title = ctx.label("", 40f, C.TEXT).apply {
            typeface = KidsLook.headline
            gravity = Gravity.CENTER
            setShadowLayer(ctx.dp(6f).toFloat(), 0f, ctx.dp(2f).toFloat(), 0x99000000.toInt())
        }
        sub = ctx.label("", 18f, 0xFFDCE3FF.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(10f), 0, 0)
        }
        col.addView(title)
        col.addView(sub)
        addView(col, LayoutParams(-1, -2, Gravity.CENTER).apply { topMargin = ctx.dp(110f) })
    }

    fun set(t: String, s: String, morning: Boolean) {
        title.text = t
        sub.text = s
        sky.morning = morning
    }

    private class SkyView(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val d = ctx.resources.displayMetrics.density
        private val stars = FloatArray(60 * 3).also { a ->
            for (i in 0 until 60) {
                a[i * 3] = Random.nextFloat()
                a[i * 3 + 1] = Random.nextFloat() * 0.7f
                a[i * 3 + 2] = Random.nextFloat() * 6.28f
            }
        }
        private val start = SystemClock.uptimeMillis()
        var morning = false

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val t = (SystemClock.uptimeMillis() - start) / 1000f
            p.shader = LinearGradient(0f, 0f, 0f, h,
                if (morning) 0xF01B2550.toInt() else 0xF2070B1F.toInt(),
                if (morning) 0xF06A5A9E.toInt() else 0xF2213063.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            p.shader = null
            for (i in 0 until 60) {
                val tw = 0.45f + 0.55f * (0.5f + 0.5f * sin(t * 1.6f + stars[i * 3 + 2]))
                p.color = Color.WHITE
                p.alpha = (210 * tw).toInt()
                c.drawCircle(stars[i * 3] * w, stars[i * 3 + 1] * h, (1.2f + (i % 3) * 0.6f) * d, p)
            }
            // a sleepy crescent moon with a soft glow, bobbing gently
            val mx = w * 0.5f
            val my = h * 0.27f + sin(t * 0.8f) * 6f * d
            val r = 46f * d
            p.shader = RadialGradient(mx, my, r * 2.6f, 0x55FFF1B8, 0x00FFF1B8, Shader.TileMode.CLAMP)
            c.drawCircle(mx, my, r * 2.6f, p)
            p.shader = null
            p.color = 0xFFFFE9A8.toInt()
            c.drawCircle(mx, my, r, p)
            p.color = if (morning) 0xFF2A3463.toInt() else 0xFF0D1430.toInt()
            c.drawCircle(mx + r * 0.42f, my - r * 0.22f, r * 0.86f, p)
            // a closed, smiling eye and a rosy cheek on the lit side
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f * d
            p.strokeCap = Paint.Cap.ROUND
            p.color = 0xFF8A6A2E.toInt()
            c.drawArc(mx - r * 0.72f, my - r * 0.2f, mx - r * 0.36f, my + r * 0.12f, 20f, 140f, false, p)
            p.style = Paint.Style.FILL
            p.color = 0x66FF9AA8
            c.drawCircle(mx - r * 0.5f, my + r * 0.32f, r * 0.12f, p)
            // Zzz floating up and fading
            p.color = Color.WHITE
            p.typeface = KidsLook.headline
            for (i in 0 until 3) {
                val k = ((t * 0.35f + i / 3f) % 1f)
                p.alpha = (255 * sin(k * Math.PI).toFloat()).toInt().coerceIn(0, 255)
                p.textSize = (16f + 10f * k) * d
                c.drawText("Z", mx + r * 0.9f + k * 60f * d + cos(k * 6f) * 6f * d, my - r * 0.4f - k * 90f * d, p)
            }
            if (isShown) postInvalidateDelayed(50)
        }
    }
}

/**
 * Four digits, chosen with the arrows (up and down change, left and right move) or the remote's
 * number buttons; OK on the last digit is done. Works with every remote, even without numbers.
 */
object PinPad {
    fun ask(act: Activity, title: String, note: String?, done: (String) -> Unit) {
        val d = Dialog(act, android.R.style.Theme_Translucent_NoTitleBar)
        val root = FrameLayout(act).apply { setBackgroundColor(0xC0000000.toInt()) }
        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(0xF516171C.toInt(), act.dp(18f).toFloat(), 1, C.BORDER)
            setPadding(act.dp(26f), act.dp(20f), act.dp(26f), act.dp(20f))
        }
        panel.addView(act.label(title, 16f, C.TEXT, bold = true))
        if (note != null) panel.addView(act.label(note, 11f, C.TEXT3).apply { setPadding(0, act.dp(4f), 0, 0); gravity = Gravity.CENTER })
        val digits = IntArray(4)
        var pos = 0
        val boxes = ArrayList<TextView>()
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun paint() {
            boxes.forEachIndexed { i, b ->
                b.text = if (i < pos) "•" else if (i == pos) digits[i].toString() else ""
                b.background = rounded(if (i == pos) C.FOCUS_FILL else 0x1FFFFFFF, act.dp(10f).toFloat(), 1, C.BORDER)
                b.setTextColor(if (i == pos) C.DARK else C.TEXT)
            }
        }
        repeat(4) { i ->
            val b = act.label("", 26f, C.TEXT, bold = true).apply { gravity = Gravity.CENTER }
            boxes.add(b)
            row.addView(b, LinearLayout.LayoutParams(act.dp(52f), act.dp(64f)).apply { if (i > 0) leftMargin = act.dp(10f) })
        }
        panel.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = act.dp(16f) })
        panel.addView(act.label("▲▼ choose  ·  ◀▶ move  ·  OK", 10f, C.TEXT3).apply { setPadding(0, act.dp(12f), 0, 0) })
        root.addView(panel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        d.setContentView(root)
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        fun finish() {
            d.dismiss()
            done(digits.joinToString(""))
        }
        d.setOnKeyListener { _, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener code != KeyEvent.KEYCODE_BACK
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> digits[pos] = (digits[pos] + 1) % 10
                KeyEvent.KEYCODE_DPAD_DOWN -> digits[pos] = (digits[pos] + 9) % 10
                KeyEvent.KEYCODE_DPAD_LEFT -> if (pos > 0) pos--
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (pos < 3) pos++
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                    if (pos < 3) pos++ else { finish(); return@setOnKeyListener true }
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                    digits[pos] = code - KeyEvent.KEYCODE_0
                    if (pos < 3) pos++ else { finish(); return@setOnKeyListener true }
                }
                KeyEvent.KEYCODE_BACK -> { d.dismiss(); return@setOnKeyListener true }
                else -> return@setOnKeyListener false
            }
            paint()
            true
        }
        paint()
        d.show()
    }
}

/** Writes the kids profile part of config.json. */
object KidsStore {
    fun edit(ctx: Context, block: (JSONObject) -> Unit) = ConfigStore.edit(ctx) { r -> block(ConfigStore.obj(r, "kids")) }

    fun setApps(ctx: Context, apps: List<String>) = edit(ctx) { k -> k.put("apps", JSONArray().apply { apps.forEach { put(it) } }) }
}

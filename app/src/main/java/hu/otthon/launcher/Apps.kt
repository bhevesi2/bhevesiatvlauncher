package hu.otthon.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.BlurMaskFilter
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.FocusFinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Collections
import java.util.WeakHashMap

/** App tile with banner (or icon + name) and focus zoom. */
class AppTile(ctx: Context, val app: AppEntry) : FrameLayout(ctx) {
    private val radius = ctx.dp(Look.R_TILE).toFloat()
    private val glass = rounded(C.GLASS, radius)
    private val solid = rounded(0xFF17181D.toInt(), radius)
    var onFocused: (() -> Unit)? = null

    init {
        isFocusable = true
        isClickable = true
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        background = glass
        FocusGlide.shape(this, radius, 0f)
        FocusGlide.glow(this, AppColors.of(app))
        val banner = app.banner
        if (banner != null) {
            addView(ImageView(ctx).apply {
                setImageDrawable(banner)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else {
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            col.addView(ImageView(ctx).apply { setImageDrawable(app.icon) }, LinearLayout.LayoutParams(ctx.dp(28f), ctx.dp(28f)))
            col.addView(ctx.label(app.label, 10f, C.TEXT, bold = true).apply {
                setPadding(ctx.dp(4f), ctx.dp(5f), ctx.dp(4f), 0)
                maxLines = 1
                gravity = Gravity.CENTER
            })
            addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        contentDescription = app.label
        setOnFocusChangeListener { v, f ->
            // the glow around it is the frame: no ring, no lift (shadows are costly on a TV)
            background = if (f) solid else glass
            Look.grow(v, f, Look.IMAGE_GROW)
            Neighbors.push(v, f)
            if (f) onFocused?.invoke()
        }
    }
}

/** The colour an app's tile glows in when it has focus, taken from its banner like Google TV does. */
object AppColors {
    private val cache = HashMap<String, Int>()
    private const val NEUTRAL = 0xFFE9E7E2.toInt()

    /** The colour, when it is already known: no need to load the app for it. */
    fun known(pkg: String): Int? = cache[pkg]

    fun of(app: AppEntry): Int = cache.getOrPut(app.pkg) {
        try { pick(app.banner ?: app.icon) } catch (e: Exception) { NEUTRAL }
    }

    /** The most vivid colour of the picture, made bright enough to glow; a light grey for grey pictures. */
    private fun pick(src: Drawable): Int {
        val d = src.constantState?.newDrawable()?.mutate() ?: return NEUTRAL
        val w = 40
        val h = 22
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(bmp))
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val hsv = FloatArray(3)
        var x = 0.0
        var y = 0.0
        var sat = 0.0
        var weight = 0.0
        for (c in px) {
            if ((c ushr 24) < 160) continue
            Color.colorToHSV(c, hsv)
            if (hsv[2] < 0.2f || hsv[1] < 0.18f) continue
            val wgt = (hsv[1] * hsv[1] * hsv[2]).toDouble()
            val rad = Math.toRadians(hsv[0].toDouble())
            x += Math.cos(rad) * wgt
            y += Math.sin(rad) * wgt
            sat += hsv[1] * wgt
            weight += wgt
        }
        if (weight < px.size * 0.012) return NEUTRAL
        var hue = Math.toDegrees(Math.atan2(y, x)).toFloat()
        if (hue < 0) hue += 360f
        val s = (sat / weight * 1.1).toFloat().coerceIn(0.5f, 0.92f)
        return Color.HSVToColor(floatArrayOf(hue, s, 1f))
    }
}

data class AppEntry(val pkg: String, val label: String, val banner: Drawable?, val icon: Drawable)

object Apps {
    fun all(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val seen = LinkedHashMap<String, AppEntry>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == ctx.packageName || seen.containsKey(pkg)) continue
                val banner = try {
                    ri.activityInfo.loadBanner(pm) ?: pm.getApplicationBanner(pkg)
                } catch (e: Exception) { null }
                seen[pkg] = AppEntry(pkg, ri.loadLabel(pm).toString(), banner, ri.loadIcon(pm))
            }
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    /**
     * One app, by its package. Its banner and icon are loaded once per installed version and kept:
     * the home screen asks for them often (every favourite, every recommendation's glow), and
     * loading them each time held up the main thread on a slower TV. Each caller gets its own
     * drawables, sharing the pictures.
     */
    fun one(ctx: Context, pkg: String): AppEntry? {
        val pm = ctx.packageManager
        return try {
            val ai = pm.getApplicationInfo(pkg, 0)
            if (!ai.enabled) return null
            val stamp = pm.getPackageInfo(pkg, 0).lastUpdateTime
            val kept = synchronized(oneCache) { oneCache[pkg]?.takeIf { it.first == stamp }?.second }
            if (kept != null) return copy(ctx, kept)
            val banner = try { pm.getApplicationBanner(pkg) } catch (e: Exception) { null }
            val e = AppEntry(pkg, pm.getApplicationLabel(ai).toString(), banner, pm.getApplicationIcon(pkg))
            synchronized(oneCache) { oneCache[pkg] = stamp to e }
            copy(ctx, e)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private val oneCache = HashMap<String, Pair<Long, AppEntry>>()

    private fun copy(ctx: Context, e: AppEntry): AppEntry {
        fun d(x: Drawable?): Drawable? = x?.constantState?.newDrawable(ctx.resources) ?: x
        return AppEntry(e.pkg, e.label, d(e.banner), d(e.icon) ?: e.icon)
    }

    /** Android TV home (Sony and other Android TV sets). */
    const val STOCK_ATV = "com.google.android.tvlauncher"
    /** Google TV home (Chromecast with Google TV, newer TCL and other Google TV sets). */
    const val STOCK_GTV = "com.google.android.apps.tv.launcherx"
    /** Google TV's setup helper: with the home app switched off it switches it back on, so it goes too. */
    const val GTV_SETUP = "com.google.android.tungsten.setupwraith"

    private fun installed(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getApplicationInfo(pkg, android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)
        true
    } catch (e: Exception) {
        false
    }

    /** The TV's own home app: Google TV home where there is one, Android TV home otherwise. */
    fun stock(ctx: Context): String = if (installed(ctx, STOCK_GTV)) STOCK_GTV else STOCK_ATV

    /** The stock home app's home screen, to hand the Home button back to it. */
    private fun stockActivity(pkg: String) = if (pkg == STOCK_GTV) "$pkg/.home.HomeActivity" else "$pkg/.MainActivity"

    /** The package the Home button opens now; "android" means the TV would ask. */
    fun defaultHome(ctx: Context): String? {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return ctx.packageManager.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }

    /** True when the Google TV home app is on the TV at all, switched on or off. */
    fun stockInstalled(ctx: Context): Boolean = installed(ctx, stock(ctx))

    /**
     * The shell commands that hand the Home button to this launcher or back to Google TV home.
     * Google's home app is a system app with a higher priority, so while it is switched on the
     * TV always picks it; the only way to choose another one is to switch it off (it stays
     * installed, and switching it back on restores it).
     */
    fun homeCommand(ctx: Context, ours: Boolean): String {
        val me = android.content.ComponentName(ctx, HomeActivity::class.java).flattenToShortString()
        val stock = stock(ctx)
        // Google TV's setup helper would switch its home app back on, so it is switched off too
        val setup = if (stock == STOCK_GTV && installed(ctx, GTV_SETUP)) GTV_SETUP else null
        return if (ours) {
            "pm disable-user $stock; " + (setup?.let { "pm disable-user $it; " } ?: "") + "cmd package set-home-activity $me"
        } else {
            "pm enable $stock; " + (setup?.let { "pm enable $it; " } ?: "") + "cmd package set-home-activity ${stockActivity(stock)}"
        }
    }

    /** This TV's address on the local network, for finding it among Home Assistant's players. */
    fun localIp(): String? = try {
        java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            .flatMap { java.util.Collections.list(it.inetAddresses) }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }?.hostAddress
    } catch (e: Exception) {
        null
    }

    fun stockHomeIntent(ctx: Context): Intent? {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(stock(ctx))
        val ri = ctx.packageManager.resolveActivity(i, 0) ?: return null
        return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun openStockHome(ctx: Context): Boolean {
        val i = stockHomeIntent(ctx) ?: return false
        return try { ctx.startActivity(i); true } catch (e: Exception) { false }
    }

    fun openSystemSettings(ctx: Context): Boolean = try {
        ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    fun launch(ctx: Context, pkg: String): Boolean {
        val pm = ctx.packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); true } catch (e: Exception) { false }
    }
}

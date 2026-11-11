package hu.otthon.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

object C {
    const val TEXT = 0xFFF5F3EE.toInt()
    const val TEXT2 = 0xFFE4E1DA.toInt()
    const val TEXT3 = 0xFFC9C6BE.toInt()
    const val DARK = 0xFF141413.toInt()
    const val GLASS = 0x8C0A0A0E.toInt()
    const val GLASS_SOFT = 0x730A0A0E.toInt()
    const val BORDER = 0x1FFFFFFF
    const val FOCUS_FILL = 0xF0F5F3EE.toInt()
}

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

fun Context.label(text: String, sp: Float, color: Int = C.TEXT, bold: Boolean = false, light: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        typeface = when {
            light -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            bold -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
        includeFontPadding = false
    }

/** Non-focusable status chip (icon + text). */
fun Context.chip(iconRes: Int, text: String): TextView = label(text, 12f, C.TEXT).apply {
    val d = getDrawable(iconRes)!!.mutate()
    d.setBounds(0, 0, dp(13f), dp(13f))
    d.setTint(C.TEXT)
    setCompoundDrawables(d, null, null, null)
    compoundDrawablePadding = dp(5f)
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(10f), dp(6f), dp(12f), dp(6f))
    background = rounded(C.GLASS_SOFT, dp(20f).toFloat(), dp(0.5f).coerceAtLeast(1), C.BORDER)
}

/** Focusable pill button: dark glass, turns light when focused. */
fun Context.pill(text: String, iconRes: Int?, onClick: () -> Unit): TextView {
    val ctx = this
    return label(text, 11f, C.TEXT).apply {
        isFocusable = true
        isClickable = true
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12f), dp(7f), dp(14f), dp(7f))
        val icon: Drawable? = iconRes?.let { ctx.getDrawable(it)!!.mutate().apply { setBounds(0, 0, dp(12f), dp(12f)) } }
        if (icon != null) {
            setCompoundDrawables(icon, null, null, null)
            compoundDrawablePadding = dp(5f)
        }
        fun paint(focused: Boolean) {
            background = if (focused) rounded(C.FOCUS_FILL, dp(20f).toFloat())
            else rounded(C.GLASS, dp(20f).toFloat(), 1, C.BORDER)
            setTextColor(if (focused) C.DARK else C.TEXT)
            icon?.setTint(if (focused) C.DARK else C.TEXT)
        }
        paint(false)
        setOnFocusChangeListener { _, f -> paint(f) }
        setOnClickListener { onClick() }
    }
}

/** App tile with banner (or icon + name) and focus zoom. */
class AppTile(ctx: Context, val app: AppEntry) : FrameLayout(ctx) {
    private val normalBg = rounded(C.GLASS, ctx.dp(8f).toFloat(), 1, C.BORDER)
    private val ring = rounded(0, ctx.dp(8f).toFloat(), ctx.dp(2f), C.TEXT)

    init {
        isFocusable = true
        isClickable = true
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        background = normalBg
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
            foreground = if (f) ring else null
            v.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f)
                .translationZ(if (f) ctx.dp(6f).toFloat() else 0f).setDuration(140).start()
        }
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

    fun one(ctx: Context, pkg: String): AppEntry? {
        val pm = ctx.packageManager
        return try {
            val ai = pm.getApplicationInfo(pkg, 0)
            if (!ai.enabled) return null
            val banner = try { pm.getApplicationBanner(pkg) } catch (e: Exception) { null }
            AppEntry(pkg, pm.getApplicationLabel(ai).toString(), banner, pm.getApplicationIcon(pkg))
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private const val STOCK = "com.google.android.tvlauncher"

    fun stockHomeIntent(ctx: Context): Intent? {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(STOCK)
        val ri = ctx.packageManager.resolveActivity(i, 0) ?: return null
        return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun openStockHome(ctx: Context): Boolean {
        val i = stockHomeIntent(ctx) ?: return false
        return try { ctx.startActivity(i); true } catch (e: Exception) { false }
    }

    fun launch(ctx: Context, pkg: String): Boolean {
        val pm = ctx.packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); true } catch (e: Exception) { false }
    }
}

fun View.lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

/** Camera tile: latest snapshot with the camera name along the bottom edge. */
class CamTile(ctx: Context, val item: HaItem, radius: Float, labelSp: Float, zoom: Float) : FrameLayout(ctx) {
    val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val ring = rounded(0, radius, ctx.dp(2f), C.TEXT)

    init {
        isFocusable = true
        isClickable = true
        background = rounded(0xFF22262C.toInt(), radius)
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val name = item.label.ifBlank { item.entity.substringAfter('.') }
        val caption = ctx.label(name, labelSp, C.TEXT, bold = true)
        caption.maxLines = 1
        caption.setPadding(ctx.dp(labelSp * 0.6f), ctx.dp(labelSp * 0.3f), ctx.dp(labelSp * 0.6f), ctx.dp(labelSp * 0.35f))
        caption.setBackgroundColor(0x99060709.toInt())
        addView(caption, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        contentDescription = "$name camera"
        setOnFocusChangeListener { v, f ->
            foreground = if (f) ring else null
            v.animate().scaleX(if (f) zoom else 1f).scaleY(if (f) zoom else 1f)
                .translationZ(if (f) ctx.dp(6f).toFloat() else 0f).setDuration(140).start()
        }
    }
}

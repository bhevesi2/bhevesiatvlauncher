package hu.otthon.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * The "Live" design's favourite: a taller tile whose picture is one of the app's own current
 * recommendations, with the app's banner as a small badge in the bottom left corner, like a
 * Windows live tile. While the cursor is on it, it shows what the picture is and moves on to the
 * app's next recommendation every few seconds with a soft cross-fade; the other tiles stand
 * still, so only one thing on the screen moves at a time. An app that publishes nothing shows
 * its own banner, large and darkened.
 */
class LiveTile(ctx: Context, val app: AppEntry) : FrameLayout(ctx) {
    private val radius = ctx.dp(Look.R_TILE).toFloat()
    private val back = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val front = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
    private val title = ctx.label("", 12f, C.TEXT, bold = true).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(ctx.dp(4f).toFloat(), 0f, 0f, 0xCC000000.toInt())
    }
    private val sub = ctx.label("", 10f, C.TEXT2).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(ctx.dp(4f).toFloat(), 0f, 0f, 0xCC000000.toInt())
    }
    private val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; alpha = 0f }
    private var programs: List<Reco.Program> = emptyList()
    private var index = 0
    private var shownUri: String? = null
    private var focused = false
    var onFocused: (() -> Unit)? = null

    init {
        isFocusable = true
        isClickable = true
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        background = rounded(0xFF17181D.toInt(), radius)
        contentDescription = app.label
        addView(back, LayoutParams(-1, -1))
        addView(front, LayoutParams(-1, -1))
        // the darker bottom, so the badge and the title read on any picture
        addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0x00000000, 0xB3000000.toInt()))
        }, LayoutParams(-1, -1))

        val foot = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(9f), 0, ctx.dp(9f), ctx.dp(8f))
        }
        texts.addView(title)
        texts.addView(sub)
        foot.addView(texts, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(5f) })
        foot.addView(badge(ctx), LinearLayout.LayoutParams(-2, ctx.dp(20f)))
        addView(foot, LayoutParams(-1, -2, Gravity.BOTTOM))

        showFallback()
        FocusGlide.shape(this, radius, 0f)
        FocusGlide.glow(this, AppColors.of(app))
        setOnFocusChangeListener { v, f ->
            focused = f
            Look.grow(v, f, Look.IMAGE_GROW)
            Neighbors.push(v, f)
            texts.animate().alpha(if (f && title.text.isNotBlank()) 1f else 0f).setDuration(200).start()
            removeCallbacks(cycle)
            if (f) {
                postDelayed(cycle, if (LowRam.on) CYCLE_MS * 2 else CYCLE_MS)
                onFocused?.invoke()
            }
        }
    }

    /** The app's banner (its logo on its colour) small; the icon when it has no banner. */
    private fun badge(ctx: Context): View {
        val banner = app.banner
        return if (banner != null) {
            ImageView(ctx).apply {
                setImageDrawable(banner)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                background = rounded(0xFF000000.toInt(), ctx.dp(5f).toFloat())
                layoutParams = LinearLayout.LayoutParams(ctx.dp(36f), ctx.dp(20f))
                minimumWidth = ctx.dp(36f)
            }
        } else {
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(ctx).apply { setImageDrawable(app.icon) }, LinearLayout.LayoutParams(ctx.dp(18f), ctx.dp(18f)))
                addView(ctx.label(app.label, 11f, C.TEXT, bold = true).apply {
                    maxLines = 1
                    setShadowLayer(ctx.dp(4f).toFloat(), 0f, 0f, 0xCC000000.toInt())
                }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ctx.dp(6f) })
            }
        }
    }

    /** No recommendation: the banner itself, filling the tile, a little darker. */
    private fun showFallback() {
        val banner = app.banner
        if (banner != null) {
            back.setImageDrawable(banner.constantState?.newDrawable()?.mutate() ?: banner)
            back.setColorFilter(0x66000000, android.graphics.PorterDuff.Mode.SRC_ATOP)
        } else {
            back.setImageDrawable(null)
            back.setBackgroundColor(AppColors.of(app) and 0x00FFFFFF or 0x55000000)
        }
        title.text = ""
        sub.text = ""
    }

    /** The app's current recommendations, freshly read: the first picture shows at once. */
    fun setPrograms(list: List<Reco.Program>) {
        val withPictures = list.filter { !it.image.isNullOrBlank() }.distinctBy { it.image }.take(8)
        if (withPictures.map { it.image } == programs.map { it.image }) return
        programs = withPictures
        index = 0
        if (programs.isEmpty()) {
            shownUri = null
            showFallback()
            return
        }
        show(programs[0], fade = false)
        // the next one is read ahead, so the first change while focused is instant
        if (programs.size > 1) programs[1].let { n -> n.image?.let { Reco.loadPicture(context, it, pictureWidth(), n.pkg) { } } }
    }

    private fun pictureWidth() = maxOf(width, context.dp(160f)) * 5 / 4

    private fun show(p: Reco.Program, fade: Boolean) {
        val uri = p.image ?: return
        Reco.loadPicture(context, uri, pictureWidth(), p.pkg) { b ->
            if (programs.getOrNull(index)?.image != uri) return@loadPicture
            put(b, fade)
            shownUri = uri
            title.text = p.title
            sub.text = p.subtitle
            if (focused) texts.animate().alpha(if (p.title.isNotBlank()) 1f else 0f).setDuration(200).start()
        }
    }

    /** Lays [b] on top and fades it in; it then becomes the picture underneath. */
    private fun put(b: Bitmap, fade: Boolean) {
        back.clearColorFilter()
        if (!fade || Motion.level == Motion.OFF) {
            back.setImageBitmap(b)
            front.alpha = 0f
            return
        }
        front.setImageBitmap(b)
        front.animate().cancel()
        front.alpha = 0f
        front.animate().alpha(1f).setDuration(FADE_MS).withEndAction {
            back.setImageBitmap(b)
            front.alpha = 0f
            front.setImageDrawable(null)
        }.start()
    }

    private val cycle = object : Runnable {
        override fun run() {
            if (!focused || programs.size < 2 || !isAttachedToWindow) return
            index = (index + 1) % programs.size
            show(programs[index], fade = true)
            // and the one after that is read ahead
            programs[(index + 1) % programs.size].let { n -> n.image?.let { Reco.loadPicture(context, it, pictureWidth(), n.pkg) { } } }
            postDelayed(this, if (LowRam.on) CYCLE_MS * 2 else CYCLE_MS)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(cycle)
    }

    companion object {
        const val CYCLE_MS = 3500L
        const val FADE_MS = 450L
    }
}

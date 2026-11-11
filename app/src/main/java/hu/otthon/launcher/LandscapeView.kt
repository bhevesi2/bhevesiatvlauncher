package hu.otthon.launcher

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import android.view.animation.DecelerateInterpolator

data class Palette(
    val s1: Int, val s2: Int, val sun: Int, val sy: Float, val sr: Float,
    val far: Int, val mid: Int, val near: Int, val water: Int, val stars: Float
) {
    companion object {
        private fun c(hex: String) = android.graphics.Color.parseColor(hex)

        fun of(p: Phase): Palette = when (p) {
            Phase.HAJNAL -> Palette(c("#2B3A67"), c("#F2A07B"), c("#FFD9A0"), 740f, 64f, c("#6B6A8E"), c("#4A4A6E"), c("#2C2C44"), c("#8A7A98"), 0f)
            Phase.NAPPAL -> Palette(c("#3F7FC4"), c("#BFDDF2"), c("#FFF6DC"), 210f, 56f, c("#8FA6BF"), c("#5B8468"), c("#355E45"), c("#5E95BE"), 0f)
            Phase.ALKONY -> Palette(c("#2A2050"), c("#EE8659"), c("#FFB66E"), 690f, 78f, c("#6E4E6E"), c("#46334F"), c("#231A2C"), c("#A85C74"), 0f)
            Phase.EJSZAKA -> Palette(c("#060A18"), c("#1C2648"), c("#E8E6E1"), 200f, 40f, c("#1F2B46"), c("#141D33"), c("#0A1020"), c("#1B2744"), 1f)
        }

        private val argb = ArgbEvaluator()
        private fun ci(a: Int, b: Int, t: Float) = argb.evaluate(t, a, b) as Int
        private fun fi(a: Float, b: Float, t: Float) = a + (b - a) * t

        fun lerp(a: Palette, b: Palette, t: Float) = Palette(
            ci(a.s1, b.s1, t), ci(a.s2, b.s2, t), ci(a.sun, b.sun, t), fi(a.sy, b.sy, t), fi(a.sr, b.sr, t),
            ci(a.far, b.far, t), ci(a.mid, b.mid, t), ci(a.near, b.near, t), ci(a.water, b.water, t), fi(a.stars, b.stars, t)
        )
    }
}

/** Vector landscape drawn in a 1920x1080 design space, center-cropped to the view. */
class LandscapeView(context: Context) : View(context) {

    var style: String = "alpesi"
        set(value) { field = value; invalidate() }

    private var palette = Palette.of(Phase.NAPPAL)
    private var animator: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val stars = floatArrayOf(
        140f, 90f, 2f, 320f, 210f, 1.6f, 510f, 70f, 2.2f, 690f, 160f, 1.4f, 860f, 60f, 1.8f,
        980f, 250f, 1.5f, 1120f, 120f, 2f, 1260f, 40f, 1.4f, 1560f, 180f, 2f, 1700f, 80f, 1.6f,
        1840f, 230f, 1.8f, 420f, 320f, 1.3f, 1480f, 300f, 1.3f, 240f, 380f, 1.2f
    )

    private val alpFar = poly(0f, 620f, 180f, 470f, 320f, 560f, 520f, 360f, 700f, 520f, 860f, 420f, 1040f, 560f, 1240f, 330f, 1420f, 500f, 1600f, 400f, 1780f, 520f, 1920f, 450f, 1920f, 770f, 0f, 770f)
    private val alpMid = poly(0f, 700f, 240f, 560f, 420f, 650f, 640f, 520f, 860f, 680f, 1100f, 560f, 1320f, 690f, 1560f, 580f, 1760f, 680f, 1920f, 620f, 1920f, 780f, 0f, 780f)
    private val alpNear = Path().apply {
        moveTo(0f, 1080f); lineTo(0f, 930f)
        cubicTo(300f, 890f, 520f, 960f, 760f, 940f)
        cubicTo(1000f, 920f, 1200f, 980f, 1500f, 950f)
        cubicTo(1700f, 930f, 1820f, 960f, 1920f, 940f)
        lineTo(1920f, 1080f); close()
    }
    private val dombFar = Path().apply {
        moveTo(0f, 640f)
        cubicTo(300f, 560f, 520f, 620f, 760f, 580f)
        cubicTo(1000f, 540f, 1300f, 610f, 1560f, 560f)
        cubicTo(1720f, 530f, 1840f, 570f, 1920f, 560f)
        lineTo(1920f, 1080f); lineTo(0f, 1080f); close()
    }
    private val dombMid = Path().apply {
        moveTo(0f, 760f)
        cubicTo(260f, 680f, 560f, 740f, 820f, 700f)
        cubicTo(1100f, 660f, 1380f, 740f, 1640f, 690f)
        cubicTo(1780f, 665f, 1880f, 690f, 1920f, 680f)
        lineTo(1920f, 1080f); lineTo(0f, 1080f); close()
    }
    private val dombNear = Path().apply {
        moveTo(0f, 900f)
        cubicTo(320f, 820f, 640f, 880f, 960f, 850f)
        cubicTo(1260f, 820f, 1600f, 900f, 1920f, 840f)
        lineTo(1920f, 1080f); lineTo(0f, 1080f); close()
    }
    private val trees = Path().apply {
        tri(300f, 840f, 320f, 770f, 340f, 840f); tri(345f, 845f, 362f, 785f, 379f, 845f)
        tri(1500f, 860f, 1522f, 780f, 1544f, 860f); tri(1550f, 862f, 1568f, 800f, 1586f, 862f)
        tri(1590f, 858f, 1604f, 812f, 1618f, 858f)
    }

    private fun Path.tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        moveTo(x1, y1); lineTo(x2, y2); lineTo(x3, y3); close()
    }

    private fun poly(vararg p: Float): Path = Path().apply {
        moveTo(p[0], p[1])
        var i = 2
        while (i < p.size) { lineTo(p[i], p[i + 1]); i += 2 }
        close()
    }

    fun setPhase(p: Phase, animate: Boolean) {
        val target = Palette.of(p)
        animator?.cancel()
        if (!animate) { palette = target; invalidate(); return }
        val from = palette
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                palette = Palette.lerp(from, target, it.animatedValue as Float)
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val s = maxOf(w / 1920f, h / 1080f)
        canvas.save()
        canvas.translate((w - 1920f * s) / 2f, (h - 1080f * s) / 2f)
        canvas.scale(s, s)
        val p = palette

        paint.shader = LinearGradient(0f, 0f, 0f, 1080f, p.s1, p.s2, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 1920f, 1080f, paint)
        paint.shader = null

        if (p.stars > 0.01f) {
            paint.color = 0xFFF5F3EE.toInt()
            var i = 0
            while (i < stars.size) {
                paint.alpha = (p.stars * 200).toInt()
                canvas.drawCircle(stars[i], stars[i + 1], stars[i + 2], paint)
                i += 3
            }
            paint.alpha = 255
        }

        paint.color = p.sun
        paint.alpha = 46
        canvas.drawCircle(1380f, p.sy, p.sr * 2.6f, paint)
        paint.alpha = 255
        canvas.drawCircle(1380f, p.sy, p.sr, paint)

        if (style == "domb") {
            fill(canvas, dombFar, p.far)
            fill(canvas, dombMid, p.mid)
            fill(canvas, dombNear, p.near)
            fill(canvas, trees, p.near)
        } else {
            fill(canvas, alpFar, p.far)
            fill(canvas, alpMid, p.mid)
            paint.shader = LinearGradient(0f, 770f, 0f, 1080f, p.water, p.near, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 770f, 1920f, 1080f, paint)
            paint.shader = null
            paint.color = p.sun
            streak(canvas, 1260f, 800f, 240f, 4f, 0.45f)
            streak(canvas, 1220f, 840f, 320f, 4f, 0.30f)
            streak(canvas, 1290f, 885f, 180f, 3f, 0.25f)
            streak(canvas, 1180f, 930f, 400f, 3f, 0.15f)
            paint.alpha = 255
            fill(canvas, alpNear, p.near)
        }
        canvas.restore()
    }

    private fun streak(c: Canvas, x: Float, y: Float, w: Float, h: Float, a: Float) {
        paint.alpha = (a * 255).toInt()
        c.drawRoundRect(x, y, x + w, y + h, h / 2, h / 2, paint)
    }

    private fun fill(c: Canvas, path: Path, color: Int) {
        paint.shader = null
        paint.color = color
        paint.alpha = 255
        c.drawPath(path, paint)
    }
}

package hu.otthon.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// How the critters look: the bears, bunnies and their carrots, the ladders, and at Christmas Santa, the
// reindeer, the presents and the magic cookies (drawing only; what they do is in Critters.kt).

/** The sleigh with its load of presents and Santa holding the reins; runners at (0, 0). */
internal fun CritterLayer.santa(canvas: Canvas, a: Int, ph: Float) {
    val gold = 0xFFE2B84A.toInt()
    val red = 0xFFC62828.toInt()
    val white = 0xFFF7F4EF.toInt()
    // presents piled up in the back
    fun box(l: Float, t: Float, r: Float, b: Float, color: Int, ribbon: Int) {
        fill(color, a)
        canvas.drawRect(l, t, r, b, p)
        fill(ribbon, a)
        val mx = (l + r) / 2
        canvas.drawRect(mx - 0.9f, t, mx + 0.9f, b, p)
        canvas.drawRect(l, t + (b - t) * 0.4f, r, t + (b - t) * 0.4f + 1.6f, p)
        ellipse(canvas, mx - 1.6f, t - 0.6f, 1.6f, 1f)
        ellipse(canvas, mx + 1.6f, t - 0.6f, 1.6f, 1f)
    }
    box(-23f, -33f, -14f, -18f, 0xFF2E7D32.toInt(), red)
    box(-15f, -30f, -7f, -18f, 0xFF1E5AA8.toInt(), gold)
    box(-21f, -40f, -14.5f, -33.5f, 0xFFF2C230.toInt(), red)
    box(-13.5f, -36f, -8.5f, -30f, 0xFFD84343.toInt(), white)
    // Santa, sitting behind the front of the sleigh
    val waving = sleighT in 0.38f..0.62f
    fill(0xFF9E1F1F.toInt(), a)
    canvas.save()
    val farArm = if (waving) 160f + sin(ph * 9f) * 20f else -40f
    canvas.rotate(farArm, -5f, -24f)
    ellipse(canvas, -5f, -18.5f, 2.6f, 5.5f)
    fill(white, a)
    ellipse(canvas, -5f, -13.5f, 2.4f, 1.3f)
    fill(0xFF2B2B2B.toInt(), a)
    ellipse(canvas, -5f, -11.8f, 1.8f, 1.6f)
    canvas.restore()
    fill(red, a)
    ellipse(canvas, -1f, -20f, 7.5f, 8.5f)
    fill(white, a)
    canvas.drawRect(-1.3f, -28f, 0.6f, -13f, p)
    // head: face, beard, hat
    fill(0xFFF2C6A0.toInt(), a)
    ellipse(canvas, 1f, -32f, 4.3f, 4.3f)
    fill(white, a)
    ellipse(canvas, 2.2f, -28.4f, 4.6f, 4.8f)
    ellipse(canvas, 4f, -30.6f, 2.6f, 1.3f)
    fill(0xFFE88A86.toInt(), a)
    ellipse(canvas, 5.2f, -31.8f, 1.2f, 1f)
    fill(0xFF1E1714.toInt(), a)
    ellipse(canvas, 3.2f, -33.4f, 0.65f, 0.75f)
    fill(red, a)
    path.reset()
    path.moveTo(-3.6f, -34.6f)
    path.lineTo(5f, -35f)
    path.quadTo(1f, -42f, -6f, -41.5f)
    path.close()
    canvas.drawPath(path, p)
    fill(white, a)
    ellipse(canvas, 0.6f, -35f, 5.4f, 1.6f)
    ellipse(canvas, -6.6f, -41.2f, 1.8f, 1.8f)
    // near arm forward, holding the reins
    fill(red, a)
    canvas.save()
    canvas.rotate(-62f + sin(ph * 3f) * 4f, 1f, -24f)
    ellipse(canvas, 1f, -18.5f, 2.6f, 5.5f)
    fill(white, a)
    ellipse(canvas, 1f, -13.5f, 2.4f, 1.3f)
    fill(0xFF2B2B2B.toInt(), a)
    ellipse(canvas, 1f, -11.8f, 1.8f, 1.6f)
    canvas.restore()
    // the sleigh: red body with a curled front and gold trim
    path.reset()
    path.moveTo(-24f, -5f)
    path.lineTo(-24f, -22f)
    path.quadTo(-23f, -26f, -18f, -24.5f)
    path.lineTo(-15f, -15f)
    path.lineTo(8f, -15f)
    path.quadTo(15f, -15f, 17f, -21f)
    path.quadTo(19f, -26f, 23f, -23f)
    path.quadTo(24f, -9f, 12f, -5f)
    path.close()
    fill(red, a)
    canvas.drawPath(path, p)
    p.style = Paint.Style.STROKE
    p.strokeWidth = 1.2f
    p.strokeCap = Paint.Cap.ROUND
    fill(gold, a)
    canvas.drawPath(path, p)
    // runners
    path.reset()
    path.moveTo(-26f, 0f)
    path.lineTo(16f, 0f)
    path.quadTo(24f, 0f, 23f, -5f)
    path.quadTo(22f, -8f, 19.5f, -6f)
    canvas.drawPath(path, p)
    canvas.drawLine(-16f, 0f, -16f, -5f, p)
    canvas.drawLine(6f, 0f, 6f, -5f, p)
    p.style = Paint.Style.FILL
    // a bell on the front
    fill(gold, a)
    ellipse(canvas, 18f, -13f + sin(ph * 10f) * 0.6f, 1.6f, 1.6f)
}

/** A reindeer on a tile: walks, leaps across gaps and nibbles at the frost now and then. */
internal fun CritterLayer.grazingDeer(canvas: Canvas, c: CritterLayer.Critter, a: Int) {
    val walking = c.state == CritterLayer.State.WALK || (c.state == CritterLayer.State.JUMP && c.walkOver)
    val flying = c.state == CritterLayer.State.FLOAT
    val leaping = (c.state == CritterLayer.State.JUMP && !c.walkOver) || flying
    val swing = if (walking) sin(c.phase * 8f) else if (flying) sin(c.phase * 5f) * 0.35f else 0f
    val graze = if (c.eating && c.state == CritterLayer.State.IDLE) {
        // lower the head over half a second, raise it at the end
        val down = min(c.eatTotal - c.timer, c.timer).coerceIn(0f, 0.5f) * 2f
        down
    } else 0f
    canvas.save()
    canvas.scale(0.92f, 0.92f)
    if (walking) canvas.translate(0f, -abs(swing) * 0.8f)
    if (flying) canvas.rotate(sin(c.phase * 1.7f) * 5f, 0f, -14f)
    if (c.cookie && c.state == CritterLayer.State.IDLE) magicCookie(canvas, c, a)
    reindeer(canvas, c.coat, c.light, a, c.phase, swing, if (leaping) 1f else 0f, graze, true)
    if (c.carry >= 0) {
        // riding on its back, a little smaller
        canvas.save()
        canvas.translate(-1.5f, -19.6f)
        canvas.scale(0.72f, 0.72f)
        drawItem(canvas, c.carry, c.carryColor, a, c.phase)
        canvas.restore()
    }
    canvas.restore()
}

/**
 * A reindeer facing right with its hooves at (0, 0). [legs] swings the legs while walking,
 * [fly] stretches them out front and back for a leap or a flight, [head] lowers the head.
 */
internal fun CritterLayer.reindeer(canvas: Canvas, coat: Int, light: Int, a: Int, phase: Float, legs: Float, fly: Float, head: Float, bell: Boolean) {
    val dark = darker(coat)
    val hoof = 0xFF3A2A1E.toInt()
    fun leg(hx: Float, angle: Float, color: Int) {
        canvas.save()
        canvas.rotate(angle, hx, -11f)
        fill(color, a)
        ellipse(canvas, hx, -6f, 1.5f, 5.6f)
        fill(hoof, a)
        ellipse(canvas, hx, -0.8f, 1.6f, 1f)
        canvas.restore()
    }
    // far legs, darker
    leg(-7.5f, fly * 55f + legs * 22f, dark)
    leg(6f, -fly * 60f - legs * 22f, dark)
    // tail
    fill(light, a)
    ellipse(canvas, -12f, -16.5f, 2f, 1.5f)
    // body and belly
    fill(coat, a)
    ellipse(canvas, -0.5f, -14.5f, 11.5f, 5.8f)
    fill(light, a)
    ellipse(canvas, 0.5f, -11.4f, 7f, 2.6f)
    // near legs
    leg(-6f, fly * 48f - legs * 22f, coat)
    leg(7.5f, -fly * 52f + legs * 22f, coat)
    // neck and head, lowered together while grazing
    canvas.save()
    canvas.rotate(head * 60f, 8f, -16f)
    fill(coat, a)
    canvas.save()
    canvas.rotate(28f, 10f, -21f)
    ellipse(canvas, 10f, -21f, 3.2f, 6.4f)
    canvas.restore()
    fill(light, a)
    ellipse(canvas, 10.5f, -17.6f, 2.8f, 3f)
    if (bell) {
        // a red collar with a little gold bell
        fill(0xFFC62828.toInt(), a)
        canvas.save()
        canvas.rotate(28f, 10f, -19.5f)
        ellipse(canvas, 10f, -19.5f, 3.6f, 1.1f)
        canvas.restore()
        fill(0xFFE8C04A.toInt(), a)
        ellipse(canvas, 12f, -17.4f + sin(phase * 9f) * 0.3f, 1.2f, 1.2f)
    }
    // antlers: two branched stems, the far one darker
    p.style = Paint.Style.STROKE
    p.strokeCap = Paint.Cap.ROUND
    p.strokeJoin = Paint.Join.ROUND
    p.strokeWidth = 1.1f
    for (far in listOf(true, false)) {
        val o = if (far) -1.8f else 0f
        fill(if (far) 0xFFA88A5E.toInt() else 0xFFD3B482.toInt(), a)
        path.reset()
        path.moveTo(12f + o, -30f)
        path.quadTo(10f + o, -35f, 7f + o, -38.5f)
        path.moveTo(10.6f + o, -33.6f)
        path.lineTo(13.6f + o, -37f)
        path.moveTo(8.6f + o, -36.6f)
        path.lineTo(9f + o, -40.6f)
        path.moveTo(7f + o, -38.5f)
        path.lineTo(4.6f + o, -39.6f)
        canvas.drawPath(path, p)
    }
    p.style = Paint.Style.FILL
    // ear, head, muzzle, nose
    fill(dark, a)
    canvas.save()
    canvas.rotate(-55f, 9.6f, -29f)
    ellipse(canvas, 9.6f, -29f, 1.2f, 2.6f)
    canvas.restore()
    fill(coat, a)
    ellipse(canvas, 13f, -27.6f, 4f, 3.2f)
    fill(light, a)
    ellipse(canvas, 16.4f, -26.2f, 2.8f, 2.1f)
    fill(0xFF2A1D16.toInt(), a)
    ellipse(canvas, 18.8f, -26.6f, 1.1f, 0.9f)
    eyeAt(canvas, 13.4f, -28.6f, 0.9f, a, phase)
    canvas.restore()
}

/** A dark eye with a tiny highlight that blinks every few seconds. */
internal fun CritterLayer.eyeAt(canvas: Canvas, x: Float, y: Float, r: Float, a: Int, phase: Float) {
    fill(0xFF1E1714.toInt(), a)
    if ((phase % 3.7f) < 0.12f) {
        ellipse(canvas, x, y, r, r * 0.25f)
    } else {
        ellipse(canvas, x, y, r, r * 1.1f)
        fill(0xFFFFFFFF.toInt(), a * 200 / 255)
        ellipse(canvas, x + r * 0.35f, y - r * 0.4f, r * 0.35f, r * 0.35f)
    }
}

internal fun CritterLayer.darker(c: Int): Int {
    val r = ((c shr 16) and 0xFF) * 4 / 5
    val g = ((c shr 8) and 0xFF) * 4 / 5
    val b = (c and 0xFF) * 4 / 5
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/** A little decorated tree (0) or a wrapped present (1), standing at (0, 0). */
internal fun CritterLayer.drawItem(canvas: Canvas, kind: Int, color: Int, a: Int, t: Float) {
    if (kind == 0) {
        fill(0xFF6B4226.toInt(), a)
        canvas.drawRect(-1.4f, -3.4f, 1.4f, 0f, p)
        val greens = intArrayOf(0xFF2A7048.toInt(), 0xFF338556.toInt(), 0xFF3C9A62.toInt())
        val tiers = arrayOf(floatArrayOf(-8f, -3f, -13f), floatArrayOf(-6.6f, -8.6f, -18f), floatArrayOf(-5f, -13.6f, -23f))
        for ((i, tr) in tiers.withIndex()) {
            fill(greens[i], a)
            path.reset()
            path.moveTo(tr[0], tr[1])
            path.lineTo(0f, tr[2])
            path.lineTo(-tr[0], tr[1])
            path.close()
            canvas.drawPath(path, p)
            // a dab of snow on each tier
            fill(0xFFF4F8FC.toInt(), a * 3 / 4)
            ellipse(canvas, tr[0] * 0.35f, tr[1] - 1.8f, 2.2f, 0.9f)
        }
        // twinkling baubles
        val dots = floatArrayOf(-4.2f, -5.2f, 3.6f, -6.2f, -2.4f, -10.6f, 2.8f, -11.6f, -0.6f, -15.8f, 1.8f, -19.2f)
        val cols = intArrayOf(0xFFFF5A5A.toInt(), 0xFFFFD45A.toInt(), 0xFF5AA8FF.toInt(), 0xFFFF7AD0.toInt(), 0xFFFFD45A.toInt(), 0xFFFF5A5A.toInt())
        for (i in 0 until dots.size / 2) {
            val tw = 0.55f + 0.45f * sin(t * 4f + i * 1.7f)
            fill(cols[i], (a * tw).toInt())
            canvas.drawCircle(dots[i * 2], dots[i * 2 + 1], 1.1f, p)
        }
        // gold star
        fill(0xFFFFD34A.toInt(), a)
        path.reset()
        for (i in 0 until 10) {
            val ang = -Math.PI / 2 + i * Math.PI / 5
            val rr = if (i % 2 == 0) 2.8f else 1.2f
            val x = (rr * kotlin.math.cos(ang)).toFloat()
            val y = -24f + (rr * sin(ang)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, p)
    } else {
        val ribbon = if (color == 0xFFF2C230.toInt()) 0xFFD84343.toInt() else 0xFFF2D27A.toInt()
        fill(color, a)
        canvas.drawRect(-6f, -10f, 6f, 0f, p)
        fill(darker(color), a)
        canvas.drawRect(-6.8f, -12.4f, 6.8f, -9.6f, p)
        fill(ribbon, a)
        canvas.drawRect(-1f, -12.4f, 1f, 0f, p)
        canvas.drawRect(-6f, -6f, 6f, -4.4f, p)
        // the bow
        canvas.save()
        canvas.rotate(-28f, -2.2f, -13.6f)
        ellipse(canvas, -2.2f, -13.6f, 2.4f, 1.3f)
        canvas.restore()
        canvas.save()
        canvas.rotate(28f, 2.2f, -13.6f)
        ellipse(canvas, 2.2f, -13.6f, 2.4f, 1.3f)
        canvas.restore()
        ellipse(canvas, 0f, -13f, 1f, 1f)
    }
}

/** A glowing cookie on the tile in front of the muzzle, smaller with every bite. */
internal fun CritterLayer.magicCookie(canvas: Canvas, c: CritterLayer.Critter, a: Int) {
    val left = (c.timer / c.eatTotal).coerceIn(0f, 1f)
    val r = 2.8f * (0.3f + 0.7f * left)
    val x = 20.5f
    val y = -r
    fill(0xFFFFE6A0.toInt(), a / 4)
    canvas.drawCircle(x, y, r + 2.6f, p)
    fill(0xFFD9A35B.toInt(), a)
    canvas.drawCircle(x, y, r, p)
    fill(0xFF5A3420.toInt(), a)
    canvas.drawCircle(x - r * 0.4f, y - r * 0.2f, r * 0.18f, p)
    canvas.drawCircle(x + r * 0.35f, y + r * 0.3f, r * 0.16f, p)
    canvas.drawCircle(x + r * 0.1f, y - r * 0.55f, r * 0.14f, p)
    // a few twinkles around it: this one is magic
    p.style = Paint.Style.STROKE
    p.strokeWidth = 0.5f
    p.strokeCap = Paint.Cap.ROUND
    for (i in 0 until 3) {
        val tw = sin(c.phase * 5f + i * 2.1f)
        if (tw < 0f) continue
        fill(GLITTER[1 + i], (a * tw).toInt())
        val sx = x + (i - 1) * 4.2f
        val sy = y - 4.6f - (i % 2) * 2f
        val rr = 1.4f * tw
        canvas.drawLine(sx - rr, sy, sx + rr, sy, p)
        canvas.drawLine(sx, sy - rr, sx, sy + rr, p)
    }
    p.style = Paint.Style.FILL
}
/**
 * A chubby cartoon bear, drawn facing right with its feet at (0, 0): big round head, round
 * belly, short arms; it waddles on two legs and waves now and then while it rests.
 */
internal fun CritterLayer.bear(canvas: Canvas, c: CritterLayer.Critter, a: Int) {
    val dark = darker(c.coat)
    val climbing = c.state == CritterLayer.State.LADDER && c.lStage == 1
    val walking = c.state == CritterLayer.State.WALK || (c.state == CritterLayer.State.JUMP && c.walkOver) || climbing
    val step = if (walking) sin(c.phase * (if (climbing) 10f else 8f)) else 0f
    val bob = if (walking) abs(step) * 1.2f else sin(c.phase * 2f) * 0.3f
    canvas.save()
    canvas.rotate(step * 5f, 0f, 0f)
    // feet, stepping in turn
    fill(dark, a)
    ellipse(canvas, -4.5f, -2f - max(0f, step) * 2.2f, 4.2f, 2.2f)
    ellipse(canvas, 5f, -2f - max(0f, -step) * 2.2f, 4.2f, 2.2f)
    canvas.translate(0f, -bob)
    // far arm, body, belly, tail
    fill(dark, a)
    canvas.save()
    canvas.rotate(-step * 25f, -6f, -17f)
    ellipse(canvas, -7f, -13f, 2.8f, 4.8f)
    canvas.restore()
    fill(c.coat, a)
    ellipse(canvas, 0f, -10f, 11.5f, 8.5f)
    ellipse(canvas, 0f, -15f, 9.5f, 8f)
    ellipse(canvas, -11f, -10f, 2.4f, 2.4f)
    fill(c.light, a)
    ellipse(canvas, 2.5f, -11f, 6.8f, 6.6f)
    // near arm: swings while walking, waves while resting
    fill(c.coat, a)
    canvas.save()
    val arm = when {
        c.wave -> -150f + sin(c.phase * 9f) * 22f
        climbing -> -160f + step * 25f
        c.state == CritterLayer.State.LADDER -> -115f          // handling the ladder
        walking -> step * 25f
        else -> 0f
    }
    canvas.rotate(arm, 6.5f, -18f)
    ellipse(canvas, 7.5f, -13.5f, 3f, 5.2f)
    fill(dark, a)
    ellipse(canvas, 7.8f, -9.5f, 2.4f, 1.8f)
    canvas.restore()
    // head
    fill(c.coat, a)
    ellipse(canvas, -4.5f, -35.5f, 3.6f, 3.6f)
    ellipse(canvas, 8.5f, -36.5f, 3.6f, 3.6f)
    fill(c.light, a)
    ellipse(canvas, -4.5f, -35.5f, 1.9f, 1.9f)
    ellipse(canvas, 8.5f, -36.5f, 1.9f, 1.9f)
    fill(c.coat, a)
    ellipse(canvas, 2f, -27.5f, 10f, 9.4f)
    fill(c.light, a)
    ellipse(canvas, 7.6f, -24.2f, 4.8f, 3.6f)
    fill(0xFF22160F.toInt(), a)
    ellipse(canvas, 10.2f, -25.8f, 2f, 1.5f)
    // a small smile under the nose
    p.style = Paint.Style.STROKE
    p.strokeWidth = 0.7f
    p.strokeCap = Paint.Cap.ROUND
    oval.set(7.4f, -24.6f, 10.4f, -21.8f)
    canvas.drawArc(oval, 20f, 120f, false, p)
    p.style = Paint.Style.FILL
    eye(canvas, c, 4.2f, -29.4f, 1.35f)
    eye(canvas, c, 9.2f, -29.8f, 1.2f)
    fill(0xFFF0909A.toInt(), a / 2)
    ellipse(canvas, 3.6f, -24.5f, 2f, 1.2f)
    canvas.restore()
}

/** A bunny that hops rather than walks, drawn facing right with its feet at (0, 0). */
internal fun CritterLayer.bunny(canvas: Canvas, c: CritterLayer.Critter, a: Int) {
    val walking = c.state == CritterLayer.State.WALK
    val hop = if (walking) abs(sin(c.phase * 7f)) * 5f else 0f
    val twitch = if (!walking && (c.phase % 3f) < 0.3f) sin(c.phase * 40f) * 8f else 0f
    canvas.save()
    canvas.translate(0f, -hop)
    // feet
    fill(darker(c.coat), a)
    ellipse(canvas, 3f, -1.4f, 3.4f, 1.5f)
    ellipse(canvas, -4f, -1.4f, 3.4f, 1.5f)
    // body and fluffy tail
    fill(c.coat, a)
    ellipse(canvas, -1f, -8f, 8f, 6.8f)
    fill(0xFFFFFFFF.toInt(), a)
    ellipse(canvas, -8.4f, -9f, 2.8f, 2.8f)
    // ears
    for ((ex, tilt) in listOf(3.6f to -8f, 7.4f to 6f)) {
        canvas.save()
        canvas.rotate(tilt + twitch, ex, -20f)
        fill(c.coat, a)
        ellipse(canvas, ex, -26f, 2f, 6.4f)
        fill(c.light, a)
        ellipse(canvas, ex, -26f, 1f, 4.8f)
        canvas.restore()
    }
    // head
    fill(c.coat, a)
    ellipse(canvas, 6f, -16f, 5.6f, 5.2f)
    fill(0xFF2B2422.toInt(), a)
    val blink = (c.phase % 3.5f) < 0.12f
    if (blink) ellipse(canvas, 7.8f, -16.8f, 1f, 0.25f) else ellipse(canvas, 7.8f, -16.8f, 0.9f, 1f)
    fill(0xFFE88FA0.toInt(), a)
    ellipse(canvas, 11.2f, -15f, 0.9f, 0.7f)
    fill(0xFFF4A3B4.toInt(), a / 2)
    ellipse(canvas, 8.4f, -13.8f, 1.5f, 1f)
    if (c.eating && c.state == CritterLayer.State.IDLE) carrot(canvas, c, a)
    canvas.restore()
}

/** A carrot held up to the mouth, a little shorter with every bite. */
internal fun CritterLayer.carrot(canvas: Canvas, c: CritterLayer.Critter, a: Int) {
    val left = (c.timer / c.eatTotal).coerceIn(0f, 1f)
    val chew = sin(c.phase * 14f) * 0.4f
    // leaves at the bottom end, the eaten tip at the mouth
    val bx = 13.5f
    val by = -4.5f
    val tx = bx + (10.8f - bx) * (0.25f + 0.75f * left)
    val ty = by + (-13.2f - by) * (0.25f + 0.75f * left)
    fill(0xFF5DA03A.toInt(), a)
    ellipse(canvas, bx + 1.2f, by + 1.6f, 1f, 2.2f)
    ellipse(canvas, bx - 0.6f, by + 1.8f, 0.9f, 2f)
    val path = android.graphics.Path()
    path.moveTo(bx - 1.9f, by)
    path.lineTo(bx + 1.9f, by)
    path.lineTo(tx + 0.5f, ty + chew)
    path.lineTo(tx - 0.5f, ty + chew)
    path.close()
    fill(0xFFF08A24.toInt(), a)
    canvas.drawPath(path, p)
    // the paw holding it
    fill(darker(c.coat), a)
    ellipse(canvas, 11.6f, -7.6f, 1.8f, 1.5f)
}

/** A dark eye with a tiny highlight; it blinks every few seconds. */
internal fun CritterLayer.eye(canvas: Canvas, c: CritterLayer.Critter, x: Float, y: Float, r: Float) {
    fill(0xFF1E1714.toInt(), (c.alpha.coerceIn(0f, 1f) * 255).toInt())
    if ((c.phase % 3.7f) < 0.12f) {
        ellipse(canvas, x, y, r, r * 0.25f)
    } else {
        ellipse(canvas, x, y, r, r * 1.1f)
        fill(0xFFFFFFFF.toInt(), (c.alpha * 200).toInt())
        ellipse(canvas, x + r * 0.35f, y - r * 0.4f, r * 0.35f, r * 0.35f)
    }
}

/** The part of a bear's ladder that is out: growing while set up, shrinking while packed. */
internal fun CritterLayer.drawLadder(canvas: Canvas, c: CritterLayer.Critter) {
    val e = ladderEnds(c) ?: return
    val k = c.lT.coerceIn(0f, 1f)
    val (s0, s1) = when (c.lStage) {
        0 -> 0f to (1f - (1f - k) * (1f - k))
        1 -> 0f to 1f
        else -> k to 1f
    }
    val x0 = e[0] + (e[2] - e[0]) * s0
    val y0 = e[1] + (e[3] - e[1]) * s0
    val x1 = e[0] + (e[2] - e[0]) * s1
    val y1 = e[1] + (e[3] - e[1]) * s1
    val len = Math.hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
    if (len < 1f) return
    val nx = -(y1 - y0) / len * 4f * d
    val ny = (x1 - x0) / len * 4f * d
    val a = (c.alpha * 255).toInt()
    p.strokeCap = Paint.Cap.ROUND
    p.style = Paint.Style.STROKE
    // rungs, then the two rails over them
    fill(woodDark, a)
    p.strokeWidth = 1.4f * d
    var t = 5f * d
    while (t < len) {
        val f = t / len
        val cx = x0 + (x1 - x0) * f
        val cy = y0 + (y1 - y0) * f
        canvas.drawLine(cx - nx, cy - ny, cx + nx, cy + ny, p)
        t += 6f * d
    }
    fill(wood, a)
    p.strokeWidth = 1.8f * d
    canvas.drawLine(x0 - nx, y0 - ny, x1 - nx, y1 - ny, p)
    canvas.drawLine(x0 + nx, y0 + ny, x1 + nx, y1 + ny, p)
    p.style = Paint.Style.FILL
}

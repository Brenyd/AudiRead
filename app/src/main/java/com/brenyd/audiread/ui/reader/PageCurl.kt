package com.brenyd.audiread.ui.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Shader
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brenyd.audiread.data.TurnStyle
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * One page turn in progress.
 *
 * The turning sheet is [front]; the page revealed beneath it is [back]. The fold tip is (x, y): at the
 * corner (x = width) the sheet lies flat, at x = -width it has been turned all the way over.
 * Turning forward animates the tip from the right corner to the left; turning back runs it in reverse.
 */
class PageTurn(val dir: Int, val width: Float, val height: Float, val cornerY: Float) {
    var front by mutableStateOf<Bitmap?>(null)
    var back by mutableStateOf<Bitmap?>(null)
    var x by mutableFloatStateOf(if (dir > 0) width else -width)
    var y by mutableFloatStateOf(cornerY)
    var ready by mutableStateOf(false)
    var released = false
    var releaseVelocity = 0f
    var downX = 0f
    var downY = 0f

    /** Sets the fold tip, keeping the sheet attached at the spine. */
    fun setTip(px: Float, py: Float) {
        var nx = px.coerceIn(-width, width)
        var ny = py.coerceIn(-height * 0.25f, height * 1.25f)
        val dx = nx
        val dy = ny - cornerY
        val d = hypot(dx, dy)
        if (d > width) {
            nx = dx * width / d
            ny = cornerY + dy * width / d
        }
        x = nx
        y = ny
    }
}

object PageCurl {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()

    fun draw(c: Canvas, t: PageTurn, style: TurnStyle, pageColor: Int, darkTheme: Boolean) {
        val w = t.width
        val h = t.height
        val back = t.back
        val front = t.front
        if (back != null) c.drawBitmap(back, 0f, 0f, paint) else c.drawColor(pageColor)
        if (front == null) return
        if (style == TurnStyle.SLIDE) {
            drawSlide(c, front, w, h, t.x)
            return
        }

        val cx = w
        val cy = t.cornerY
        val px = t.x
        val py = t.y
        val dist = hypot(cx - px, cy - py)
        if (dist < 1f) {
            c.drawBitmap(front, 0f, 0f, paint)
            return
        }
        // Fold line: perpendicular bisector of corner→tip. n points toward the corner.
        val mx = (cx + px) / 2f
        val my = (cy + py) / 2f
        val nx = (cx - px) / dist
        val ny = (cy - py) / dist
        val side = { p: PointF -> (p.x - mx) * nx + (p.y - my) * ny }

        val rect = listOf(PointF(0f, 0f), PointF(w, 0f), PointF(w, h), PointF(0f, h))
        val visible = clip(rect) { side(it) }          // part of the sheet still flat
        val folded = clip(rect) { -side(it) }          // part that lifts and folds over
        val flap = folded.map { reflect(it, mx, my, nx, ny) }

        // 1. the sheet that is still lying flat
        if (visible.size >= 3) {
            c.save()
            c.clipPath(toPath(visible))
            c.drawBitmap(front, 0f, 0f, paint)
            c.restore()
        }

        // 2. shadow cast on the revealed page just past the fold
        val shadowW = min(dist * 0.35f, w * 0.12f).coerceAtLeast(6f)
        val beyond = clip(rect) { -side(it) }
        if (beyond.size >= 3) {
            c.save()
            c.clipPath(toPath(beyond))
            shade.shader = LinearGradient(
                mx, my, mx + nx * shadowW, my + ny * shadowW,
                intArrayOf(0x66000000, 0x00000000), null, Shader.TileMode.CLAMP,
            )
            c.drawPaint(shade)
            c.restore()
        }

        // 3. the folded-over back of the sheet
        if (flap.size >= 3) {
            val flapPath = toPath(flap)
            // soft shadow the flap casts onto the flat part of the page
            c.save()
            c.clipPath(toPath(visible))
            shade.shader = LinearGradient(
                mx, my, mx - nx * shadowW * 0.8f, my - ny * shadowW * 0.8f,
                intArrayOf(0x33000000, 0x00000000), null, Shader.TileMode.CLAMP,
            )
            c.drawPaint(shade)
            c.restore()

            c.save()
            c.clipPath(flapPath)
            fill.color = pageColor
            c.drawPaint(fill)
            // the printed side shows faintly through the paper, mirrored
            val a = 1f - 2f * nx * nx
            val b = -2f * nx * ny
            val d = -2f * nx * ny
            val e = 1f - 2f * ny * ny
            val k = 2f * (mx * nx + my * ny)
            matrix.setValues(floatArrayOf(a, b, k * nx, d, e, k * ny, 0f, 0f, 1f))
            paint.alpha = if (darkTheme) 26 else 38
            c.drawBitmap(front, matrix, paint)
            paint.alpha = 255
            // curvature shading across the flap
            val flapW = max(dist / 2f, 1f)
            shade.shader = LinearGradient(
                mx, my, mx - nx * flapW, my - ny * flapW,
                intArrayOf(0x40000000, 0x10FFFFFF, 0x18000000), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP,
            )
            c.drawPaint(shade)
            c.restore()
        }
        shade.shader = null
    }

    private fun drawSlide(c: Canvas, front: Bitmap, w: Float, h: Float, tipX: Float) {
        val progress = ((w - tipX) / (2f * w)).coerceIn(0f, 1f)
        val offset = -progress * w
        c.save()
        c.translate(offset, 0f)
        c.drawBitmap(front, 0f, 0f, paint)
        c.restore()
        val edge = offset + w
        if (edge > 0f && edge < w) {
            shade.shader = LinearGradient(edge, 0f, edge + 24f, 0f, intArrayOf(0x55000000, 0x00000000), null, Shader.TileMode.CLAMP)
            c.drawRect(edge, 0f, edge + 24f, h, shade)
            shade.shader = null
        }
    }

    /** Clips a convex polygon to the half-plane where f(p) <= 0 (Sutherland–Hodgman). */
    private fun clip(poly: List<PointF>, f: (PointF) -> Float): List<PointF> {
        val out = ArrayList<PointF>()
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val da = f(a)
            val db = f(b)
            if (da <= 0f) out += a
            if ((da <= 0f) != (db <= 0f)) {
                val t = da / (da - db)
                out += PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            }
        }
        return out
    }

    private fun reflect(p: PointF, mx: Float, my: Float, nx: Float, ny: Float): PointF {
        val s = (p.x - mx) * nx + (p.y - my) * ny
        return PointF(p.x - 2f * s * nx, p.y - 2f * s * ny)
    }

    private fun toPath(poly: List<PointF>): Path = Path().apply {
        if (poly.isEmpty()) return@apply
        moveTo(poly[0].x, poly[0].y)
        for (i in 1 until poly.size) lineTo(poly[i].x, poly[i].y)
        close()
    }
}

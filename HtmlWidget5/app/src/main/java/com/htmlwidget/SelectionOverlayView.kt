package com.htmlwidget

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class SelectionOverlayView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    // Seçim kutusu oransal koordinatlar (0-1)
    private var selLeft = 0f
    private var selTop = 0f
    private var selRight = 0.4f
    private var selBottom = 0.3f

    private enum class DragMode { NONE, MOVE, TL, TR, BL, BR }
    private var dragMode = DragMode.NONE
    private var lastTx = 0f
    private var lastTy = 0f
    private val HR = 22f // handle radius
    private val MIN = 0.03f

    // İçerik boyutu
    var contentWidth = 1000
    var contentHeight = 3000

    var onPositionChanged: ((scrollX: Int, scrollY: Int) -> Unit)? = null

    private val paintFill = Paint().apply { color = Color.argb(60, 33, 150, 243); style = Paint.Style.FILL }
    private val paintBorder = Paint().apply { color = Color.rgb(25, 118, 210); style = Paint.Style.STROKE; strokeWidth = 2.5f; isAntiAlias = true }
    private val paintHandle = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
    private val paintHandleBorder = Paint().apply { color = Color.rgb(25, 118, 210); style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true }
    private val paintDim = Paint().apply { color = Color.argb(100, 0, 0, 0); style = Paint.Style.FILL }
    private val paintLabel = Paint().apply { color = Color.WHITE; textSize = 26f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
    private val paintLabelBg = Paint().apply { color = Color.argb(190, 21, 101, 192); style = Paint.Style.FILL; isAntiAlias = true }

    override fun onDraw(canvas: Canvas) {
        val W = width.toFloat(); val H = height.toFloat()
        val l = selLeft * W; val t = selTop * H
        val r = selRight * W; val b = selBottom * H

        // Karartma — seçim dışı alan
        canvas.drawRect(0f, 0f, W, t, paintDim)
        canvas.drawRect(0f, t, l, b, paintDim)
        canvas.drawRect(r, t, W, b, paintDim)
        canvas.drawRect(0f, b, W, H, paintDim)

        // Seçim alanı
        canvas.drawRect(l, t, r, b, paintFill)
        canvas.drawRect(l, t, r, b, paintBorder)

        // Köşe tutamaçlar
        for ((cx, cy) in listOf(l to t, r to t, l to b, r to b)) {
            canvas.drawCircle(cx, cy, HR, paintHandle)
            canvas.drawCircle(cx, cy, HR, paintHandleBorder)
        }

        // Merkez taşıma noktası
        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        canvas.drawCircle(cx, cy, HR * 0.75f, paintHandle)
        canvas.drawCircle(cx, cy, HR * 0.75f, paintHandleBorder)

        // Koordinat etiketi
        val sx = (selLeft * contentWidth).toInt()
        val sy = (selTop * contentHeight).toInt()
        val sw = ((selRight - selLeft) * contentWidth).toInt()
        val sh = ((selBottom - selTop) * contentHeight).toInt()
        val lbl = "X:$sx Y:$sy  ${sw}×${sh}px"
        val lw = paintLabel.measureText(lbl)
        val lx = (l + 6f).coerceAtMost(W - lw - 10f)
        val ly = (t - 8f).coerceAtLeast(30f)
        canvas.drawRoundRect(lx - 6f, ly - 24f, lx + lw + 6f, ly + 4f, 6f, 6f, paintLabelBg)
        canvas.drawText(lbl, lx, ly, paintLabel)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val W = width.toFloat(); val H = height.toFloat()
        val tx = e.x / W; val ty = e.y / H
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = detectMode(e.x, e.y, W, H)
                lastTx = tx; lastTy = ty
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = tx - lastTx; val dy = ty - lastTy
                val bw = selRight - selLeft; val bh = selBottom - selTop
                when (dragMode) {
                    DragMode.MOVE -> {
                        selLeft = (selLeft + dx).coerceIn(0f, 1f - bw)
                        selTop = (selTop + dy).coerceIn(0f, 1f - bh)
                        selRight = selLeft + bw; selBottom = selTop + bh
                    }
                    DragMode.TL -> { selLeft = (selLeft + dx).coerceIn(0f, selRight - MIN); selTop = (selTop + dy).coerceIn(0f, selBottom - MIN) }
                    DragMode.TR -> { selRight = (selRight + dx).coerceIn(selLeft + MIN, 1f); selTop = (selTop + dy).coerceIn(0f, selBottom - MIN) }
                    DragMode.BL -> { selLeft = (selLeft + dx).coerceIn(0f, selRight - MIN); selBottom = (selBottom + dy).coerceIn(selTop + MIN, 1f) }
                    DragMode.BR -> { selRight = (selRight + dx).coerceIn(selLeft + MIN, 1f); selBottom = (selBottom + dy).coerceIn(selTop + MIN, 1f) }
                    DragMode.NONE -> {}
                }
                lastTx = tx; lastTy = ty
                invalidate()
                onPositionChanged?.invoke((selLeft * contentWidth).toInt(), (selTop * contentHeight).toInt())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragMode = DragMode.NONE
        }
        return true
    }

    private fun detectMode(ex: Float, ey: Float, W: Float, H: Float): DragMode {
        val l = selLeft * W; val t = selTop * H; val r = selRight * W; val b = selBottom * H
        val cx = (l + r) / 2f; val cy = (t + b) / 2f; val hr2 = HR * 2.2f
        return when {
            dist(ex, ey, l, t) < hr2 -> DragMode.TL
            dist(ex, ey, r, t) < hr2 -> DragMode.TR
            dist(ex, ey, l, b) < hr2 -> DragMode.BL
            dist(ex, ey, r, b) < hr2 -> DragMode.BR
            dist(ex, ey, cx, cy) < hr2 -> DragMode.MOVE
            ex in l..r && ey in t..b -> DragMode.MOVE
            else -> DragMode.NONE
        }
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float) =
        Math.sqrt(((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)).toDouble()).toFloat()

    fun setScrollPosition(sx: Int, sy: Int) {
        if (contentWidth <= 0 || contentHeight <= 0) return
        val bw = selRight - selLeft; val bh = selBottom - selTop
        selLeft = (sx.toFloat() / contentWidth).coerceIn(0f, 1f - bw)
        selTop = (sy.toFloat() / contentHeight).coerceIn(0f, 1f - bh)
        selRight = selLeft + bw; selBottom = selTop + bh
        invalidate()
    }

    /** Hücre boyutuna göre çerçeve en/boy oranını güncelle */
    fun setCellSize(cols: Int, rows: Int) {
        val W = width.toFloat().takeIf { it > 0 } ?: return
        val H = height.toFloat().takeIf { it > 0 } ?: return
        // Widget en/boy oranı
        val ratio = (cols.toFloat() * 74f) / (rows.toFloat() * 74f)
        val newW = (selRight - selLeft).coerceIn(0.1f, 0.95f)
        val newH = (newW / ratio * (W / H)).coerceIn(0.05f, 0.95f)
        val cx = (selLeft + selRight) / 2f
        val cy = (selTop + selBottom) / 2f
        selLeft = (cx - newW / 2f).coerceIn(0f, 1f - newW)
        selTop = (cy - newH / 2f).coerceIn(0f, 1f - newH)
        selRight = selLeft + newW
        selBottom = selTop + newH
        invalidate()
    }

    fun setZoomRatio(zoom: Int) {
        val scale = 100f / zoom.toFloat().coerceIn(10f, 500f)
        val cx = (selLeft + selRight) / 2f; val cy = (selTop + selBottom) / 2f
        val nw = (0.4f * scale).coerceIn(MIN, 0.98f)
        val nh = (0.3f * scale).coerceIn(MIN, 0.98f)
        selLeft = (cx - nw / 2f).coerceIn(0f, 1f - nw)
        selTop = (cy - nh / 2f).coerceIn(0f, 1f - nh)
        selRight = selLeft + nw; selBottom = selTop + nh
        invalidate()
    }
}

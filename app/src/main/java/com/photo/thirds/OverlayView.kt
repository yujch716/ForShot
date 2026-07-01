package com.photo.thirds

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

private const val DEFAULT_BOX_COLOR = "#34C759"

data class Detection(
    val label: String,
    val confidence: Float,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val color: String = DEFAULT_BOX_COLOR,
    val isPerson: Boolean = false,
    val selected: Boolean = false
)

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var detections: List<Detection> = emptyList()

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
    }

    private val textBgPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private val selHighlightPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    private val selFillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val cornerPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    fun setDetections(list: List<Detection>) {
        android.util.Log.d("OverlayView", "[5] setDetections called size=${list.size}")
        detections = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        android.util.Log.d("OverlayView", "[6] onDraw called detections.size=${detections.size} w=$width h=$height")
        val vw = width.toFloat()
        val vh = height.toFloat()
        for (det in detections) {
            val left   = (det.cx - det.w / 2f) * vw
            val top    = (det.cy - det.h / 2f) * vh
            val right  = (det.cx + det.w / 2f) * vw
            val bottom = (det.cy + det.h / 2f) * vh

            val boxColor = parseColorSafe(det.color)

            if (det.selected) {
                val pad = 6f
                // 흰 강조 테두리
                canvas.drawRect(left - pad, top - pad, right + pad, bottom + pad, selHighlightPaint)
                // 반투명 채우기
                selFillPaint.color = Color.argb(40,
                    Color.red(boxColor), Color.green(boxColor), Color.blue(boxColor))
                canvas.drawRect(left, top, right, bottom, selFillPaint)
                // 모서리 L-브래킷 (4코너, 각 arm=20px)
                val arm = 20f
                canvas.drawLines(floatArrayOf(
                    left, top + arm, left, top,
                    left, top, left + arm, top,
                    right - arm, top, right, top,
                    right, top, right, top + arm,
                    right, bottom - arm, right, bottom,
                    right, bottom, right - arm, bottom,
                    left + arm, bottom, left, bottom,
                    left, bottom, left, bottom - arm,
                ), cornerPaint)
            }

            boxPaint.color = boxColor
            canvas.drawRect(left, top, right, bottom, boxPaint)

            val label = "${det.label} ${"%.0f".format(det.confidence * 100)}%"
            val textH = textPaint.textSize
            val textW = textPaint.measureText(label)
            textBgPaint.color = Color.argb(180,
                Color.red(boxColor), Color.green(boxColor), Color.blue(boxColor))
            canvas.drawRect(left, top - textH - 4f, left + textW + 8f, top, textBgPaint)
            canvas.drawText(label, left + 4f, top - 4f, textPaint)
        }
    }

    private fun parseColorSafe(hex: String): Int = try {
        Color.parseColor(hex)
    } catch (_: Exception) {
        Color.parseColor(DEFAULT_BOX_COLOR)
    }
}

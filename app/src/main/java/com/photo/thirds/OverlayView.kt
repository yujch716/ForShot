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
    val isPerson: Boolean = false
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

    fun setDetections(list: List<Detection>) {
        detections = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val vw = width.toFloat()
        val vh = height.toFloat()
        for (det in detections) {
            val left   = (det.cx - det.w / 2f) * vw
            val top    = (det.cy - det.h / 2f) * vh
            val right  = (det.cx + det.w / 2f) * vw
            val bottom = (det.cy + det.h / 2f) * vh

            val boxColor = parseColorSafe(det.color)
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

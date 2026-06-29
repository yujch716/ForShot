package com.photo.thirds

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

data class Detection(
    val label: String,
    val confidence: Float,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float
)

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var detections: List<Detection> = emptyList()

    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.GREEN
        textSize = 40f
        isAntiAlias = true
    }

    private val textBgPaint = Paint().apply {
        color = Color.argb(160, 0, 0, 0)
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

            canvas.drawRect(left, top, right, bottom, boxPaint)

            val label = "${det.label} ${"%.0f".format(det.confidence * 100)}%"
            val textH = textPaint.textSize
            val textW = textPaint.measureText(label)
            canvas.drawRect(left, top - textH - 4f, left + textW + 8f, top, textBgPaint)
            canvas.drawText(label, left + 4f, top - 4f, textPaint)
        }
    }
}

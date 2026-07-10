package com.photo.thirds

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

private const val DEFAULT_BOX_COLOR = "#34C759"

data class SelectedTarget(
    val label: String,
    var cx: Float,
    var cy: Float,
    var missedFrames: Int = 0
)

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

    /**
     * 영상 프레임 종횡비(가로/세로). 0 이면 레터박스 없음(뷰 전체 = 폰 기존 동작).
     * 드론은 CENTER_INSIDE(fit-center)로 그려져 양옆에 검은 띠가 생기므로, 이 값으로
     * 실제 그려지는 영역을 계산해 박스/탭 좌표를 맞춘다.
     */
    private var videoAspect = 0f

    /** 영상 종횡비 설정(fw/fh). 값이 바뀔 때만 다시 그림. */
    fun setVideoAspect(aspect: Float) {
        if (aspect != videoAspect) { videoAspect = aspect; invalidate() }
    }

    /** 영상이 실제로 그려지는 사각형 [offX, offY, drawnW, drawnH] (fit-center). */
    private fun contentRect(): FloatArray {
        val vw = width.toFloat(); val vh = height.toFloat()
        if (videoAspect <= 0f || vw <= 0f || vh <= 0f) return floatArrayOf(0f, 0f, vw, vh)
        return if (vw / vh > videoAspect) {           // 뷰가 더 넓음 → 좌우 띠(pillarbox)
            val dw = vh * videoAspect
            floatArrayOf((vw - dw) / 2f, 0f, dw, vh)
        } else {                                      // 뷰가 더 좁음 → 상하 띠
            val dh = vw / videoAspect
            floatArrayOf(0f, (vh - dh) / 2f, vw, dh)
        }
    }

    /** 화면 x → 영상 정규화 x (탭 역변환, 박스와 같은 좌표계). */
    fun toNormX(rawX: Float): Float {
        val r = contentRect(); return if (r[2] > 0f) (rawX - r[0]) / r[2] else 0f
    }

    /** 화면 y → 영상 정규화 y. */
    fun toNormY(rawY: Float): Float {
        val r = contentRect(); return if (r[3] > 0f) (rawY - r[1]) / r[3] else 0f
    }

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

    // 박스/라벨 색: person = 네온 하늘색, 그 외 클래스 = 연두색.
    private val personColor = Color.parseColor("#00E5FF")
    private val otherColor = Color.parseColor("#B2FF59")

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        android.util.Log.d("OverlayView", "[6] onDraw called detections.size=${detections.size} w=$width h=$height")
        // 영상이 실제로 그려지는 영역(검은 띠 안쪽) 기준으로 매핑.
        val rect = contentRect()
        val offX = rect[0]; val offY = rect[1]; val dw = rect[2]; val dh = rect[3]
        for (det in detections) {
            val left   = offX + (det.cx - det.w / 2f) * dw
            val top    = offY + (det.cy - det.h / 2f) * dh
            val right  = offX + (det.cx + det.w / 2f) * dw
            val bottom = offY + (det.cy + det.h / 2f) * dh

            val isPerson = det.isPerson || det.label.equals("person", ignoreCase = true)
            val boxColor = if (isPerson) personColor else otherColor

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

            // 클래스명 라벨 (신뢰도 % 없이). 라벨 배경=박스색, 글씨=검정(밝은 색 위 대비).
            val label = det.label
            val textH = textPaint.textSize
            val textW = textPaint.measureText(label)
            textBgPaint.color = boxColor
            canvas.drawRect(left, top - textH - 4f, left + textW + 8f, top, textBgPaint)
            textPaint.color = Color.BLACK
            canvas.drawText(label, left + 4f, top - 4f, textPaint)
        }
    }

    private fun parseColorSafe(hex: String): Int = try {
        Color.parseColor(hex)
    } catch (_: Exception) {
        Color.parseColor(DEFAULT_BOX_COLOR)
    }
}

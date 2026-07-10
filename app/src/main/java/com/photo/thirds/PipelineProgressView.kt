package com.photo.thirds

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat

/**
 * 촬영 파이프라인 진행 표시(하늘색 네온).
 * 상단 가운데 제목('최적 구도 탐색 중') + 4개 원형 노드(아이콘)를 양옆 여백 안에 펼침.
 * 노드 사이는 선으로 연결(원과 간격). 현재 단계 노드는 40% 반투명 배경(테두리 네온)으로 채우고,
 * 진행 중(0~2)이면 펄스 애니메이션. 각 노드 아래 명칭. setStage(0..3)로 갱신, -1이면 표시 없음.
 */
class PipelineProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val neon = Color.parseColor("#00E5FF")
    private val title = "최적 구도 탐색 중"
    private val iconRes = intArrayOf(
        R.drawable.navigation, R.drawable.stack, R.drawable.tune, R.drawable.check
    )
    private val labels = arrayOf("위치 조정", "구도 조정", "미세 조정", "완료")
    private val count = iconRes.size

    private var stage = -1
    private var pulse = 0f   // 0..1

    private val den = resources.displayMetrics.density
    private val circleR = 32f * den         // 노드 반지름(확대)
    private val pulseExpand = 14f * den
    private val iconHalf = (17f * den).toInt()  // 아이콘 크기(확대)
    private val sidePad = 130f * den        // 양옆 여백(정지/메뉴 버튼 침범 방지)
    private val lineGap = 8f * den
    private val labelGap = 10f * den

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * den; color = neon
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = neon
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * den; color = neon
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * den; color = neon
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = neon; textAlign = Paint.Align.CENTER; textSize = 13f * den
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = neon; textAlign = Paint.Align.CENTER; textSize = 18f * den; isFakeBoldText = true
    }
    private val icons: List<Drawable?> = iconRes.map {
        ContextCompat.getDrawable(context, it)?.mutate()?.also { d -> d.setTint(neon) }
    }

    private var animator: ValueAnimator? = null

    fun setStage(s: Int) {
        stage = s
        if (s in 0..2) startPulse() else stopPulse()
        invalidate()
    }

    private fun startPulse() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1300L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopPulse() {
        animator?.cancel(); animator = null; pulse = 0f
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow(); stopPulse()
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || stage < 0) return
        val cx0 = width / 2f

        // 제목(상단 가운데)
        val titleTop = 8f * den
        val titleBaseline = titleTop - titlePaint.fontMetrics.ascent
        canvas.drawText(title, cx0, titleBaseline, titlePaint)

        // 노드 세로 위치: 제목 아래 + 펄스 여유
        val cy = titleBaseline + titlePaint.fontMetrics.descent + 18f * den + (circleR + pulseExpand)
        val usable = width - 2 * sidePad
        val stepX = if (count > 1) usable / (count - 1) else 0f
        val cxs = FloatArray(count) { sidePad + stepX * it }

        // 연결선 (원과 lineGap 만큼 떨어뜨림)
        for (i in 0 until count - 1) {
            val sx = cxs[i] + circleR + lineGap
            val ex = cxs[i + 1] - circleR - lineGap
            if (ex > sx) canvas.drawLine(sx, cy, ex, cy, linePaint)
        }

        // 노드
        for (i in 0 until count) {
            val cx = cxs[i]
            if (i == stage && stage in 0..2) {
                val r = circleR + pulse * pulseExpand
                pulsePaint.alpha = ((1f - pulse) * 200).toInt()
                canvas.drawCircle(cx, cy, r, pulsePaint)
                pulsePaint.alpha = 255
            }
            if (i <= stage) {
                fillPaint.alpha = 102   // 현재 + 이미 진행된 단계: 40% 네온 배경
                canvas.drawCircle(cx, cy, circleR, fillPaint)
                fillPaint.alpha = 255
            }
            canvas.drawCircle(cx, cy, circleR, borderPaint)
            icons[i]?.let { d ->
                val l = (cx - iconHalf).toInt()
                val t = (cy - iconHalf).toInt()
                d.setBounds(l, t, l + iconHalf * 2, t + iconHalf * 2)
                d.draw(canvas)
            }
            val baseline = cy + circleR + labelGap - labelPaint.fontMetrics.ascent
            canvas.drawText(labels[i], cx, baseline, labelPaint)
        }
    }
}

package com.photo.thirds

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.cos
import kotlin.math.sin

/**
 * 서버 /capture 응답의 drone_offset.
 * theta_deg: 0=오른쪽(+x), 반시계 증가(90=위, 180=왼쪽, 270/-90=아래).
 * dr_norm: 정규화된 중심 이탈량(임계값 비교용).
 */
data class DroneOffset(
    val dx: Double,
    val dy: Double,
    val dr: Double,
    val drNorm: Double,
    val thetaDeg: Double
)

/**
 * 이동 실측 로그용 텔레메트리 제공자 (DroneFrameSource 가 구현).
 */
interface DroneTelemetry {
    /** NED 좌표계 속도(m/s): x=북, y=동, z=아래(하강+). 없으면 null. */
    fun velocityNED(): Triple<Double, Double, Double>?
    /** 나침반 헤딩(도): 0=북, 시계방향. 없으면 null. */
    fun headingDeg(): Double?
    /** 현재 위치(lat/lng/alt). 없으면 null. */
    fun location(): LocationCoordinate3D?
}

/**
 * DJI MSDK V5 Virtual Stick 로 피사체를 화면 중앙으로 오게 하는 저속 스텝 이동.
 *
 * - theta_deg 방향으로 좌우(roll) + 상하(throttle) 대각선 이동만. 전후진(pitch)·회전(yaw) 없음(0 고정).
 * - Advanced 속도(m/s) 모드: sendVirtualStickAdvancedParam + RollPitchControlMode.VELOCITY.
 * - 어떤 경로(성공/실패/예외/취소)에서도 반드시 disableVirtualStick 으로 조종간을 복귀시킨다.
 * - emergencyStop(): 즉시 스틱 0 + Virtual Stick OFF (UI 스레드에서 즉시 호출 가능).
 * - 이동 중 기체 실측 속도(NED)/헤딩을 샘플링해 "명령 vs 실제" 를 로그로 남긴다(logcat 태그 DroneMover).
 *
 * ※ 부호(ROLL_SIGN/THROTTLE_SIGN)는 야외 검증 후 뒤집기 쉽게 상수로 분리.
 */
class DroneMover(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val telemetry: DroneTelemetry? = null
) {
    companion object {
        private const val TAG = "DroneMover"

        // ── 튜닝 상수 (야외 검증 후 여기만 수정) ──────────────────────────────
        /** 스텝 이동 속도 (m/s). */
        private const val STICK_SPEED = 0.3
        /** 한 번 '정렬'에 이동하는 총 시간 (ms). 0.3 m/s × 1.0s ≈ 30cm. */
        private const val MOVE_DURATION_MS = 1000L
        /** 스캔 이동 속도 (m/s). */
        private const val SCAN_SPEED = 0.3
        /**
         * 스캔 이동 총 시간 (ms). 명목 0.3 m/s × 7.333s ≈ 2.2m 이지만,
         * 반응지연+가감속(유효 ~0.66s 손실, 1m 명령→0.8m 실측에서 역산)을 보정해 **실측 ≈ 2.0m**.
         * 실측이 어긋나면 야외 로그의 '실측 이동거리 추정' 보고 이 값만 조정.
         */
        const val SCAN_TIME_MS = 7333L
        /** 세부조정 이동 속도 (m/s). 저속으로 관성 최소화. */
        private const val ADJUST_SPEED = 0.1
        /** 세부조정 1스텝 목표 이동거리 (m). 폐쇄루프: 실측 이동이 이 거리에 닿으면 정지. */
        const val ADJUST_STEP_M = 0.05
        /**
         * 세부조정 시간 상한 (ms). 폐쇄루프 안전캡 — 목표거리(5cm)에 도달하면 이 전에 멈춘다.
         * 반응지연(~0.4s)+가감속 때문에 5cm 실이동엔 개방루프 0.5s로는 턱없이 부족(실측 ~1cm).
         * 텔레메트리가 아예 없으면 개방루프 폴백 시간까지만 가고 정지(아래 계산).
         */
        const val ADJUST_TIME_MS = 2500L
        /**
         * 스틱 명령→기체 실제 반응까지 지연(ms). 실측 로그상 모든 이동에서 첫 ~0.4초는 속도 0.
         * 폐쇄루프 불가(텔레메트리 null) 시 개방루프 폴백시간 = 지연 + 목표거리/속도 계산에 사용.
         */
        private const val RESPONSE_LATENCY_MS = 400L
        /**
         * 폐쇄루프 정지 예측(s). 스틱 0을 줘도 관성으로 미끄러지므로, 현재 속도로 이 시간만큼
         * 더 갈 거리를 미리 더해 목표 도달 판정 → 오버슛 방지. 야외 로그 보고 미세조정.
         */
        private const val COAST_LOOKAHEAD_S = 0.2
        /** 촬영 전 후진 거리 (m). 서버 2배 크롭 대비 화면 넓히기용. */
        private const val BACKUP_DISTANCE = 2.0
        /** 촬영 전 후진 속도 (m/s). STICK_SPEED 수준 재사용. */
        private const val BACKUP_SPEED = 0.3
        /** 후진 총 시간 (ms) = 거리/속도. 1.0/0.3 ≈ 3333ms. (계산값이라 const 아님) */
        val BACKUP_TIME_MS = ((BACKUP_DISTANCE / BACKUP_SPEED) * 1000).toLong()
        /** 줌(전후진) 부호. +1 이면 roll+ 가 전진. 반대로 가면 -1 로 (야외 검증). */
        private const val ZOOM_SIGN = 1
        /** 스틱 값 주기 전송 간격 (ms). */
        private const val SEND_INTERVAL_MS = 40L
        /** 실측 텔레메트리 샘플 간격 (틱 단위). 3틱 ≈ 120ms. */
        private const val SAMPLE_EVERY_TICKS = 3
        /** dr_norm 이 이 값 이하면 "충분히 중앙" → 이동 안 함. */
        private const val DR_NORM_THRESHOLD = 0.03
        /**
         * GPS 신호 등급(GPSSignalLevel.value(), 0~5)이 이 값 미만이면 이동 차단.
         * 값이 높을수록 규제 강함. 실내 테스트 편의로 0(사실상 해제). 야외 복귀 시 3으로.
         */
        private const val MIN_GPS_LEVEL = 0

        // ── 부호 (검증 대상) ───────────────────────────────────────────────
        /** 좌우 부호. +1 이면 오른쪽(dx+)일 때 실제 오른쪽으로. 반대로 가면 -1 로. */
        private const val LATERAL_SIGN = 1
        /** 상하 부호. +1 이면 위(dy-)일 때 실제 상승으로. 반대로 가면 -1 로. */
        private const val VERTICAL_SIGN = 1

        // ── 축 스왑 (검증으로 확정됨) ────────────────────────────────────────
        // 실측: 이 기체에서 'roll' 필드가 전/후(fore-aft)를 구동함(DJI setRoll/setPitch 뒤바뀜).
        // 따라서 좌우(lateral)는 'pitch' 필드에 싣고 roll=0 으로 둔다.
        // 만약 재검증서 pitch 로도 좌우가 안 나오면 이 값을 false 로(1줄 되돌림).
        private const val LATERAL_ON_PITCH = true

        /** disable 등 결과가 필요 없는 호출에 쓰는 no-op 콜백. */
        private val NOOP = object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {}
            override fun onFailure(error: IDJIError) {}
        }
    }

    /** 이동 진행 중 여부 — 중복 실행 방지. */
    private val moving = AtomicBoolean(false)
    /** 비상정지 플래그 — set 되면 전송 루프 즉시 중단. */
    @Volatile private var aborted = false

    fun isMoving(): Boolean = moving.get()

    /**
     * 화면 오프셋(dx,dy) 방향으로 이동(0.3 m/s × MOVE_DURATION_MS). UI 스레드에서 호출.
     * dx: 오른쪽+, dy: 아래+ (화면 좌표). 게이트(연결/이륙/GPS/임계값/중복)에 걸리면
     * 이동하지 않고 [onResult]`(false)`만 즉시 호출.
     *
     * @param onResult moved=true 면 실제로 스틱 명령을 보냈다는 뜻,
     *                 moved=false 면 차단/실패로 안 움직였다는 뜻.
     */
    fun moveByOffset(
        dx: Double,
        dy: Double,
        drNorm: Double,
        connected: Boolean,
        flying: Boolean,
        gpsLevel: Int,
        onResult: (moved: Boolean) -> Unit
    ) = moveDirectional(dx, dy, STICK_SPEED, MOVE_DURATION_MS, drNorm, true, connected, flying, gpsLevel, 0.0, onResult)

    /**
     * 스캔 등속 이동. dx/dy 방향으로 [durationMs] 동안 SCAN_SPEED 로 계속 이동.
     * dr_norm 임계값 검사는 하지 않음(스캔은 항상 이동). 복귀는 dx/dy 를 부호 반전해 호출.
     */
    fun moveScan(
        dx: Double,
        dy: Double,
        durationMs: Long,
        connected: Boolean,
        flying: Boolean,
        gpsLevel: Int,
        onResult: (moved: Boolean) -> Unit
    ) = moveDirectional(dx, dy, SCAN_SPEED, durationMs, 0.0, false, connected, flying, gpsLevel, 0.0, onResult)

    /**
     * 세부조정 저속 이동. dx/dy 방향으로 ADJUST_SPEED 로 이동하되, **실측 이동이 ADJUST_STEP_M(5cm)에
     * 닿으면 정지**(폐쇄루프). 반응지연/가감속에 무관하게 5cm를 맞추고 오버슛을 막는다.
     * ADJUST_TIME_MS 는 안전 상한(텔레메트리 없거나 못 미칠 때만 도달).
     * dx: 오른쪽+, dy: 아래+ (화면 좌표). dr_norm 임계값 검사는 하지 않음.
     * 게이트(연결/이륙/GPS/중복)에 걸리면 이동하지 않고 [onResult]`(false)`만 호출.
     */
    fun moveAdjust(
        dx: Double,
        dy: Double,
        connected: Boolean,
        flying: Boolean,
        gpsLevel: Int,
        onResult: (moved: Boolean) -> Unit
    ) = moveDirectional(dx, dy, ADJUST_SPEED, ADJUST_TIME_MS, 0.0, false, connected, flying, gpsLevel, 0.0, onResult, ADJUST_STEP_M)

    /**
     * 줌(전후진) 저속 이동. forward=true → 전진, false → 후진. roll 필드로 약 5cm.
     * 게이트(연결/이륙/GPS/중복)·Virtual Stick OFF·비상정지는 moveDirectional 이 보장.
     */
    fun moveZoom(
        forward: Boolean,
        connected: Boolean,
        flying: Boolean,
        gpsLevel: Int,
        onResult: (moved: Boolean) -> Unit
    ) = moveDirectional(
        0.0, 0.0, ADJUST_SPEED, ADJUST_TIME_MS, 0.0, false,
        connected, flying, gpsLevel,
        if (forward) ADJUST_SPEED else -ADJUST_SPEED,
        onResult,
        ADJUST_STEP_M   // 폐쇄루프: 전후 5cm 실측되면 정지.
    )

    /**
     * 촬영 전 1m 후진. foreAft 음수 → roll 필드 음수(검증된 전후 매핑) → 실제 후진.
     * 게이트(연결/이륙/GPS/중복)·Virtual Stick OFF·비상정지(aborted)는 moveDirectional 이 보장.
     * @param onResult moved=true 면 후진 스틱을 실제로 보냄, false 면 게이트 차단/실패로 안 움직임.
     */
    fun moveBackup(
        connected: Boolean,
        flying: Boolean,
        gpsLevel: Int,
        onResult: (moved: Boolean) -> Unit
    ) = moveDirectional(
        0.0, 0.0, BACKUP_SPEED, BACKUP_TIME_MS, 0.0, false,
        connected, flying, gpsLevel,
        -BACKUP_SPEED,   // ★ 후진: foreAft 음수 (전후진 검증 방향 재사용)
        onResult
    )

    /**
     * 공통 이동 실행. UI 스레드에서 호출. 게이트(연결/이륙/GPS/(선택)임계값/중복)에 걸리면
     * 이동하지 않고 [onResult]`(false)`만 즉시 호출. 어떤 경로에서도 Virtual Stick OFF 보장.
     *
     * @param checkThreshold true 면 drNorm<=임계값일 때 "이미 중앙" 으로 차단(정렬용). 스캔은 false.
     * @param onResult moved=true 면 실제로 스틱 명령을 보냄, false 면 차단/실패.
     */
    private fun moveDirectional(
        dx: Double,
        dy: Double,
        speed: Double,
        durationMs: Long,
        drNorm: Double,
        checkThreshold: Boolean,
        connected: Boolean,
        flying: Boolean,
        gpsLevel: Int,
        foreAft: Double = 0.0,
        onResult: (moved: Boolean) -> Unit,
        /** >0 이면 폐쇄루프: 실측 이동이 이 거리(m)에 닿으면 정지. durationMs 는 안전 상한. */
        targetDistanceM: Double = 0.0
    ) {
        // ── 안전 게이트 ────────────────────────────────────────────────────
        if (!connected) { toast("드론이 연결되지 않았습니다"); onResult(false); return }
        if (!flying)    { toast("이륙(비행) 상태에서만 이동할 수 있습니다"); onResult(false); return }
        if (gpsLevel < MIN_GPS_LEVEL) {
            toast("GPS 신호가 약해 이동을 차단합니다 (level=$gpsLevel)"); onResult(false); return
        }
        if (checkThreshold && drNorm <= DR_NORM_THRESHOLD) {
            toast("이미 충분히 중앙입니다 (dr_norm=%.3f)".format(drNorm)); onResult(false); return
        }
        if (!moving.compareAndSet(false, true)) {
            toast("이동이 이미 진행 중입니다"); onResult(false); return
        }

        aborted = false
        scope.launch(Dispatchers.IO) {
            var moved = false
            try {
                val enabled = enableVirtualStick()
                if (!enabled) {
                    toast("Virtual Stick 활성화 실패")
                    return@launch
                }
                VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)
                moved = true    // 여기부터 실제로 스틱 명령을 보냄.
                runMove(dx, dy, speed, durationMs, foreAft, targetDistanceM)
            } catch (e: Exception) {
                Log.e(TAG, "move error: $e")
            } finally {
                // ★ 어떤 경우에도 Virtual Stick OFF → 조종간 복귀.
                disableVirtualStickQuietly()
                moving.set(false)
                withContext(Dispatchers.Main) { onResult(moved) }
            }
        }
    }

    /** 실제 이동 루프 + 명령/실측 로그. dx: 오른쪽+, dy: 아래+ (화면 좌표). */
    private suspend fun runMove(
        dx: Double, dy: Double, speed: Double, durationMs: Long,
        foreAft: Double = 0.0, targetDistanceM: Double = 0.0
    ) {
        // 화면좌표 → 이동방향 단위벡터. ux=오른쪽+, uy=위+ (화면 y 반전).
        val ux = dx
        val uy = -dy
        val mag = Math.hypot(ux, uy).coerceAtLeast(1e-6)
        val lateral = speed * (ux / mag) * LATERAL_SIGN     // 좌우 (오른쪽 +)
        val vertical = speed * (uy / mag) * VERTICAL_SIGN   // 상하 (상승 +)
        val fore = foreAft * ZOOM_SIGN                      // 전후 (전진 +), roll 필드
        val moveParam = buildParam(lateral, vertical, fore)
        val ticks = durationMs / SEND_INTERVAL_MS

        // 폐쇄루프(targetDistanceM>0): 명령 방향 단위벡터(body: 우/전/상)에 실측 이동을 투영해
        // 목표거리 도달 시 정지. 텔레메트리가 끝내 없으면 개방루프 폴백시간(지연+거리/속도)까지만.
        val closed = targetDistanceM > 0.0 && speed > 0.0
        val cmdMag = Math.sqrt(lateral * lateral + fore * fore + vertical * vertical).coerceAtLeast(1e-6)
        val uR = lateral / cmdMag; val uF = fore / cmdMag; val uU = vertical / cmdMag
        val fallbackTicks = if (closed)
            ((RESPONSE_LATENCY_MS + (targetDistanceM / speed) * 1000.0).toLong() / SEND_INTERVAL_MS)
            else ticks

        val startLoc = telemetry?.location()
        val startMs = SystemClock.elapsedRealtime()
        val baseVel = telemetry?.velocityNED()

        Log.d(TAG, "─────────── 이동 시작 ───────────")
        Log.d(TAG, "명령(화면→축): dx=%.0f dy=%.0f → 좌우=%+.3f m/s(%s) 상하=%+.3f m/s(%s), 전후=%+.3f m/s (%s, BODY)"
            .format(dx, dy, lateral, sideWord(lateral), vertical, upWord(vertical), fore,
                if (closed) "목표 %.0fcm/상한 %.2f초".format(targetDistanceM * 100, durationMs / 1000.0)
                else "목표 %.2f초".format(durationMs / 1000.0)))
        Log.d(TAG, "전송 param 필드: roll=%+.3f pitch=%+.3f throttle=%+.3f yaw=%+.3f (LATERAL_ON_PITCH=%b)"
            .format(moveParam.roll, moveParam.pitch, moveParam.verticalThrottle, moveParam.yaw, LATERAL_ON_PITCH))
        Log.d(TAG, "이동 전 기준속도(NED, 바람/드리프트): %s".format(fmtVel(baseVel)))
        Log.d(TAG, "시작: heading=%s, %s".format(fmtHeading(), fmtLoc(startLoc)))

        // 실측 누적(body 프레임: 우/전/상 m/s). 매 틱(40ms) 적분 → 폐쇄루프 판정 정밀도 확보.
        var sumR = 0.0; var sumF = 0.0; var sumU = 0.0; var n = 0
        var dispR = 0.0; var dispF = 0.0; var dispU = 0.0
        val tickDt = SEND_INTERVAL_MS / 1000.0

        var i = 0L
        var stopReason = if (closed) "시간 상한(목표 미도달)" else "시간 종료"
        while (i < ticks && !aborted) {
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(moveParam)
            delay(SEND_INTERVAL_MS)
            i++
            val v = telemetry?.velocityNED()
            val h = telemetry?.headingDeg()
            if (v != null && h != null) {
                val (fwd, right, up) = bodyFromNED(v.first, v.second, v.third, h)
                sumR += right; sumF += fwd; sumU += up; n++
                dispR += right * tickDt; dispF += fwd * tickDt; dispU += up * tickDt
                if (i % SAMPLE_EVERY_TICKS == 0L) {
                    val tSec = (SystemClock.elapsedRealtime() - startMs) / 1000.0
                    Log.d(TAG, "[t=%.2fs] 실측 body 우=%+.2f 전=%+.2f 상=%+.2f m/s  (NED N=%+.2f E=%+.2f D=%+.2f, hdg=%.0f°)"
                        .format(tSec, right, fwd, up, v.first, v.second, v.third, h))
                }
                if (closed) {
                    // 목표방향 투영거리 + 관성 예측(현재속도×COAST). 오버슛 전에 미리 멈춤.
                    val along = dispR * uR + dispF * uF + dispU * uU
                    val curSpeed = Math.sqrt(right * right + fwd * fwd + up * up)
                    if (along + curSpeed * COAST_LOOKAHEAD_S >= targetDistanceM) {
                        stopReason = "목표거리 도달"
                        break
                    }
                }
            }
            // 텔레메트리가 폴백시간까지 한 번도 없으면 폐쇄루프 불가 → 개방루프로 그때 정지.
            if (closed && n == 0 && i >= fallbackTicks) {
                stopReason = "텔레메트리 없음→개방루프 폴백"
                break
            }
        }
        // 정지: 0 값을 몇 번 확실히 전송.
        val zero = buildParam(0.0, 0.0)
        repeat(3) {
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(zero)
            delay(20)
        }

        val elapsed = (SystemClock.elapsedRealtime() - startMs) / 1000.0
        val endLoc = telemetry?.location()
        Log.d(TAG, "─────────── 이동 종료 (경과 %.2f초, 정지사유=%s, aborted=%b) ───────────".format(elapsed, stopReason, aborted))
        if (n > 0) {
            Log.d(TAG, "실측 평균속도 body: 우=%+.2f 전=%+.2f 상=%+.2f m/s (샘플 %d개)"
                .format(sumR / n, sumF / n, sumU / n, n))
            Log.d(TAG, "실측 이동거리 추정(속도적분): 우=%+.2fm 전=%+.2fm 상=%+.2fm"
                .format(dispR, dispF, dispU))
            Log.d(TAG, "→ 명령 좌우=%s 상하=%s 전후=%+.3f / 실측 수평=%s 수직=%s / 전후(실측)=%+.2f ※명령 전후=0이면 0에 가까워야 정상".format(
                sideWord(lateral), upWord(vertical), fore,
                if (sumR / n >= 0) "우측(+%.2f)".format(sumR / n) else "좌측(%.2f)".format(sumR / n),
                if (sumU / n >= 0) "상승(+%.2f)".format(sumU / n) else "하강(%.2f)".format(sumU / n),
                sumF / n
            ))
        } else {
            Log.d(TAG, "실측 텔레메트리 없음(velocity/heading null) — 명령값만 신뢰. (GPS 위치 델타는 0.3m 이동엔 노이즈라 무의미)")
        }
        Log.d(TAG, "끝: %s".format(fmtLoc(endLoc)))
    }

    /**
     * 비상정지. UI 스레드에서 즉시 호출 가능. 절대 예외를 던지지 않음.
     */
    fun emergencyStop() {
        aborted = true
        try {
            val zero = buildParam(0.0, 0.0)
            repeat(3) { VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(zero) }
        } catch (e: Exception) {
            Log.e(TAG, "emergencyStop send-zero error: $e")
        }
        disableVirtualStickQuietly()
        moving.set(false)
        Log.w(TAG, "EMERGENCY STOP — sticks zeroed, virtual stick disabled")
    }

    // ── 내부 ────────────────────────────────────────────────────────────────

    private suspend fun enableVirtualStick(): Boolean = suspendCancellableCoroutine { cont ->
        try {
            VirtualStickManager.getInstance().enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() { if (cont.isActive) cont.resume(true) }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "enableVirtualStick failure: $error")
                    if (cont.isActive) cont.resume(false)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "enableVirtualStick threw: $e")
            if (cont.isActive) cont.resume(false)
        }
    }

    private fun disableVirtualStickQuietly() {
        try {
            VirtualStickManager.getInstance().disableVirtualStick(NOOP)
        } catch (e: Exception) {
            Log.e(TAG, "disableVirtualStick threw: $e")
        }
    }

    /**
     * 좌우(lateral)/상하(vertical)/전후(foreAft) → param. yaw=0, BODY/VELOCITY.
     * ★ 실측상 'roll' 필드가 전후를 구동 → 좌우는 'pitch', 전후는 'roll' 필드에 싣는다(LATERAL_ON_PITCH).
     */
    private fun buildParam(lateral: Double, vertical: Double, foreAft: Double = 0.0): VirtualStickFlightControlParam =
        VirtualStickFlightControlParam().apply {
            if (LATERAL_ON_PITCH) {
                pitch = lateral      // 좌우 = pitch 필드
                roll = foreAft       // 전후 = roll 필드 (실측 스왑)
            } else {
                roll = lateral       // (되돌림용) 좌우 = roll 필드
                pitch = foreAft
            }
            verticalThrottle = vertical
            yaw = 0.0
            rollPitchControlMode = RollPitchControlMode.VELOCITY
            verticalControlMode = VerticalControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
            rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
        }

    /** NED 속도(북,동,아래) + 헤딩(deg) → body 프레임(전진, 우측, 상승) m/s. */
    private fun bodyFromNED(vN: Double, vE: Double, vD: Double, hdgDeg: Double): Triple<Double, Double, Double> {
        val h = Math.toRadians(hdgDeg)
        val fwd = vN * cos(h) + vE * sin(h)
        val right = -vN * sin(h) + vE * cos(h)
        val up = -vD
        return Triple(fwd, right, up)
    }

    private fun sideWord(v: Double) = when { v > 1e-6 -> "우"; v < -1e-6 -> "좌"; else -> "-" }
    private fun upWord(v: Double) = when { v > 1e-6 -> "상승"; v < -1e-6 -> "하강"; else -> "-" }

    private fun fmtVel(v: Triple<Double, Double, Double>?): String =
        if (v == null) "없음" else "N=%+.2f E=%+.2f D=%+.2f".format(v.first, v.second, v.third)

    private fun fmtHeading(): String = telemetry?.headingDeg()?.let { "%.0f°".format(it) } ?: "?"
    private fun fmtLoc(loc: LocationCoordinate3D?): String =
        if (loc == null) "위치 없음"
        else "lat=%.7f lng=%.7f alt=%.2fm".format(loc.latitude, loc.longitude, loc.altitude)

    private fun toast(msg: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        }
    }
}

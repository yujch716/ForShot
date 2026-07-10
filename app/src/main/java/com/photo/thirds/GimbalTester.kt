package com.photo.thirds

import android.util.Log
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * DJI Mini 4 Pro 짐벌 pitch(틸트) 최소 검증기. 이륙 없이 짐벌만 움직여 명령/실제각을 비교한다.
 * 향후 틸트 sweep 기능의 기반. Virtual Stick/이동/촬영과 무관하게 aircraft 짐벌 키만 사용.
 *
 * ★ Mini 4 Pro: yaw/roll 을 제어하면 SDK_SERVICE_GIMBAL_ROTATE_YAW_NOT_ALLOW 발생 →
 *   yawIgnored=true, rollIgnored=true 로 두고 pitch 만 구동한다.
 * pitch 부호: + 위, - 아래, 0 수평(기체 정면). ABSOLUTE_ANGLE(절대각) 사용.
 */
class GimbalTester(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "GimbalTester"
        private val IDX = ComponentIndexType.LEFT_OR_MAIN   // 메인(단일) 짐벌
        private const val MOVE_DURATION_S = 1.0             // 짐벌 이동 시간(초)
        private const val SETTLE_MS = 1500L                 // 이동 명령 후 대기(정착 + 읽기)
        // 짐벌 pitch 한계(Mini 4 Pro 대략값 — 짐벌 테스트로 실측 후 조정).
        const val MIN_PITCH_DEG = -90.0
        const val MAX_PITCH_DEG = 60.0
    }

    /** pitch 를 짐벌 한계 범위로 clamp. */
    fun clampPitch(p: Double): Double = p.coerceIn(MIN_PITCH_DEG, MAX_PITCH_DEG)

    /** 현재 짐벌 attitude(동기 read). 실패/미지원 시 null. */
    fun readAttitude(): Attitude? = try {
        KeyManager.getInstance().getValue(KeyTools.createKey(GimbalKey.KeyGimbalAttitude, IDX))
    } catch (e: Exception) {
        Log.e(TAG, "attitude read error: $e")
        null
    }

    /**
     * pitch 절대각으로 회전(yaw/roll 무시). 성공=null, 실패=IDJIError.
     * [durationS] 로 이동 시간(=대략 sweep 속도) 조절. 콜백은 명령 수락 시점에 옴(모션 완료 아님).
     */
    suspend fun moveToPitch(pitch: Double, durationS: Double = MOVE_DURATION_S): IDJIError? =
        suspendCancellableCoroutine { cont ->
            val rot = GimbalAngleRotation().apply {
                setMode(GimbalAngleRotationMode.ABSOLUTE_ANGLE)
                setPitch(pitch)
                setPitchIgnored(false)
                setRoll(0.0)
                setRollIgnored(true)
                setYaw(0.0)
                setYawIgnored(true)          // ★ Mini 4 Pro yaw 금지 회피
                setDuration(durationS)
            }
            try {
                KeyManager.getInstance().performAction(
                    KeyTools.createKey(GimbalKey.KeyRotateByAngle, IDX),
                    rot,
                    object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                        override fun onSuccess(t: EmptyMsg?) { if (cont.isActive) cont.resume(null) }
                        override fun onFailure(error: IDJIError) { if (cont.isActive) cont.resume(error) }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "rotate threw: $e")
                if (cont.isActive) cont.resume(null)
            }
        }

    /** 한 스텝: 명령 → 대기 → 실제각 읽기 → 명령/실제 비교 로그. */
    private suspend fun step(label: String, pitch: Double) {
        val err = moveToPitch(pitch)
        delay(SETTLE_MS)
        val actual = readAttitude()?.pitch
        val actualStr = actual?.let { "%+.1f".format(it) } ?: "null"
        if (err == null) {
            Log.d(TAG, "$label 명령 pitch=%+.1f → 실제 pitch=%s".format(pitch, actualStr))
        } else {
            // REQUEST_HANDLER_NOT_FOUND 등 → 키 경로 재확인 필요. yaw 관련 에러면 무시 플래그 점검.
            Log.e(
                TAG,
                "$label 명령 pitch=%+.1f 실패: code=%s desc=%s (실제=%s)"
                    .format(pitch, err.errorCode(), err.description(), actualStr)
            )
        }
    }

    /** 전체 pitch 테스트 시퀀스. ioScope(백그라운드)에서 호출할 것. */
    suspend fun runPitchTest() {
        Log.d(TAG, "─────────── 짐벌 pitch 테스트 시작 (시작 각도: ${readAttitude()?.pitch}) ───────────")
        // 1) 기본 왕복: 0 → -30(아래) → 0 → +30(위) → 0
        for (p in listOf(0.0, -30.0, 0.0, 30.0, 0.0)) step("[기본]", p)
        // 2) 위 범위 한계: +10 → +30 → +50 → +60
        for (p in listOf(10.0, 30.0, 50.0, 60.0)) step("[위]", p)
        step("[복귀]", 0.0)
        // 3) 아래 범위 한계: -30 → -60 → -90
        for (p in listOf(-30.0, -60.0, -90.0)) step("[아래]", p)
        step("[복귀]", 0.0)
        Log.d(TAG, "─────────── 짐벌 pitch 테스트 종료 ───────────")
    }
}

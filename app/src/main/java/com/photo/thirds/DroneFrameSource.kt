package com.photo.thirds

import android.app.Activity
import android.util.Log
import android.view.Surface
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.KeyManager
import dji.v5.manager.SDKManager
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.data.ObstacleAvoidanceType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.v5.manager.interfaces.SDKManagerCallback

class DroneFrameSource(private val activity: Activity) : FrameSource, DroneTelemetry {

    companion object {
        private const val TAG = "DroneFrameSource"
    }

    interface StatusListener {
        fun onConnected()
        fun onDisconnected()
        fun onStatusText(msg: String)
        fun onConnectFailed(msg: String)
    }

    private var frameCallback: FrameCallback? = null
    private var statusListener: StatusListener? = null
    @Volatile private var cachedLocation: LocationCoordinate3D? = null
    private var surface: Surface? = null
    private var surfaceW = 0
    private var surfaceH = 0
    @Volatile private var isDroneConnected = false
    // Virtual Stick 이동 게이트용 비행 상태/ GPS 신호 (aircraft 키만 사용).
    @Volatile private var flying = false
    @Volatile private var gpsLevel = 0
    // 이동 실측 로그용 텔레메트리 (NED 속도, 나침반 헤딩).
    @Volatile private var velNED: Triple<Double, Double, Double>? = null
    @Volatile private var heading: Double? = null

    fun setStatusListener(l: StatusListener) { statusListener = l }

    fun onSurfaceReady(s: Surface, w: Int, h: Int) {
        surface = s; surfaceW = w; surfaceH = h
        updateStream()
    }

    fun onSurfaceDestroyed(s: Surface) {
        MediaDataCenter.getInstance().cameraStreamManager.removeCameraStreamSurface(s)
        surface = null; surfaceW = 0; surfaceH = 0
    }

    override fun setFrameCallback(cb: FrameCallback) { frameCallback = cb }
    override fun getGpsLocation() = cachedLocation

    fun isConnected(): Boolean = isDroneConnected

    /** 이륙(비행) 상태 여부 — Virtual Stick 이동 전 필수 게이트. */
    fun isFlying(): Boolean = flying

    /** GPS 신호 등급(GPSSignalLevel.value(), 0~10). 약하면 Virtual Stick 불안정 → 이동 차단용. */
    fun gpsLevel(): Int = gpsLevel

    // ── DroneTelemetry (이동 실측 로그용) ──────────────────────────────────
    override fun velocityNED(): Triple<Double, Double, Double>? = velNED
    override fun headingDeg(): Double? = heading
    override fun location(): LocationCoordinate3D? = cachedLocation

    fun startTakeoff(callback: CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>) {
        KeyManager.getInstance().performAction(
            KeyTools.createKey(FlightControllerKey.KeyStartTakeoff), callback
        )
    }

    fun startLanding(callback: CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>) {
        KeyManager.getInstance().performAction(
            KeyTools.createKey(FlightControllerKey.KeyStartAutoLanding), callback
        )
    }

    fun init() {
        SDKManager.getInstance().init(activity, object : SDKManagerCallback {

            override fun onRegisterSuccess() {
                Log.d(TAG, "SDK register success")
                statusListener?.onStatusText("SDK 등록 완료 — 드론 연결 대기")
            }

            override fun onRegisterFailure(error: IDJIError) {
                Log.e(TAG, "SDK register failure: $error")
                statusListener?.onConnectFailed("SDK 등록에 실패했습니다.\n(${error.description()})")
            }

            override fun onProductConnect(productId: Int) {
                Log.d(TAG, "Drone connected, productId=$productId")
                isDroneConnected = true
                KeyManager.getInstance().listen(
                    KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D), activity
                ) { _, newValue ->
                    cachedLocation = newValue
                    Log.d(TAG, "GPS: lat=${newValue?.latitude} lng=${newValue?.longitude}")
                }
                KeyManager.getInstance().listen(
                    KeyTools.createKey(FlightControllerKey.KeyIsFlying), activity
                ) { _, newValue ->
                    flying = newValue ?: false
                    Log.d(TAG, "isFlying=$flying")
                }
                KeyManager.getInstance().listen(
                    KeyTools.createKey(FlightControllerKey.KeyGPSSignalLevel), activity
                ) { _, newValue ->
                    gpsLevel = newValue?.value() ?: 0
                    Log.d(TAG, "gpsLevel=$gpsLevel")
                }
                KeyManager.getInstance().listen(
                    KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity), activity
                ) { _, v ->
                    velNED = v?.let { Triple(it.x ?: 0.0, it.y ?: 0.0, it.z ?: 0.0) }
                }
                KeyManager.getInstance().listen(
                    KeyTools.createKey(FlightControllerKey.KeyCompassHeading), activity
                ) { _, h -> heading = h }
                MediaDataCenter.getInstance().cameraStreamManager
                    .addFrameListener(
                        ComponentIndexType.LEFT_OR_MAIN,
                        ICameraStreamManager.FrameFormat.NV21,
                        frameListener
                    )
                activity.runOnUiThread {
                    statusListener?.onConnected()
                    updateStream()
                }
            }

            override fun onProductDisconnect(productId: Int) {
                Log.d(TAG, "Drone disconnected, productId=$productId")
                isDroneConnected = false
                KeyManager.getInstance().cancelListen(
                    KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D), activity)
                KeyManager.getInstance().cancelListen(
                    KeyTools.createKey(FlightControllerKey.KeyIsFlying), activity)
                KeyManager.getInstance().cancelListen(
                    KeyTools.createKey(FlightControllerKey.KeyGPSSignalLevel), activity)
                KeyManager.getInstance().cancelListen(
                    KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity), activity)
                KeyManager.getInstance().cancelListen(
                    KeyTools.createKey(FlightControllerKey.KeyCompassHeading), activity)
                cachedLocation = null
                flying = false
                gpsLevel = 0
                velNED = null
                heading = null
                MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(frameListener)
                surface?.let {
                    MediaDataCenter.getInstance().cameraStreamManager.removeCameraStreamSurface(it)
                }
                activity.runOnUiThread { statusListener?.onDisconnected() }
            }

            override fun onProductChanged(productId: Int) {
                Log.d(TAG, "Product changed, productId=$productId")
            }

            override fun onInitProcess(event: DJISDKInitEvent, totalProcess: Int) {
                Log.d(TAG, "SDK init: $event ($totalProcess%)")
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                    SDKManager.getInstance().registerApp()
                }
            }

            override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                Log.d(TAG, "DB download: $current/$total")
            }
        })
    }

    override fun release() {
        KeyManager.getInstance().cancelListen(
            KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D), activity)
        KeyManager.getInstance().cancelListen(
            KeyTools.createKey(FlightControllerKey.KeyIsFlying), activity)
        KeyManager.getInstance().cancelListen(
            KeyTools.createKey(FlightControllerKey.KeyGPSSignalLevel), activity)
        KeyManager.getInstance().cancelListen(
            KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity), activity)
        KeyManager.getInstance().cancelListen(
            KeyTools.createKey(FlightControllerKey.KeyCompassHeading), activity)
        cachedLocation = null
        flying = false
        gpsLevel = 0
        velNED = null
        heading = null
        if (isDroneConnected) {
            MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(frameListener)
            surface?.let {
                MediaDataCenter.getInstance().cameraStreamManager.removeCameraStreamSurface(it)
            }
        }
        SDKManager.getInstance().destroy()
    }

    /**
     * 실내 테스트 모드 토글. enabled=true → 장애물 회피 OFF + 하방 비전 측위 ON(바닥 무늬로 위치 유지).
     * enabled=false → 장애물 회피 ON(정상 복구). [onResult] 는 안전상 중요한 '장애물 회피' 설정 결과를
     * UI 스레드로 전달(true=성공). ★ 회피 OFF 는 야외에서 충돌 위험.
     */
    fun setIndoorMode(enabled: Boolean, onResult: (ok: Boolean, err: String?) -> Unit) {
        val perception = PerceptionManager.getInstance()
        // 실내 모드 켤 때만 하방 비전 측위 ON(끌 땐 default 유지). GPS 없는 실내 위치 유지용.
        if (enabled) {
            perception.setVisionPositioningEnabled(true, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() { Log.d(TAG, "비전 측위 ON (실내)") }
                override fun onFailure(error: IDJIError) { Log.e(TAG, "비전 측위 ON 실패: ${error.description()}") }
            })
        }
        // 장애물 회피: 실내 모드면 CLOSE(끄기), 아니면 BRAKE(정상 복구). 안전 핵심이라 결과를 콜백으로 넘김.
        // ※ setOverallObstacleAvoidanceEnabled 는 MSDK 5.1.0부터 deprecated → Mini 4 Pro 에서 "지원하지 않음"
        //   에러가 남. 서브 스위치 대신 회피 '타입'을 설정하는 setObstacleAvoidanceType 로 켜고 끈다.
        //   (BRAKE=제동 회피, BYPASS=우회(APAS), CLOSE=끔. Mini 시리즈는 BRAKE 미지원 가능성 있어
        //    복구가 실패하면 아래 로그의 code/hint 로 확인 후 BYPASS 로 바꿔볼 것.)
        val avoidType = if (enabled) ObstacleAvoidanceType.CLOSE else ObstacleAvoidanceType.BRAKE
        perception.setObstacleAvoidanceType(avoidType, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                Log.d(TAG, "장애물 회피 타입=$avoidType (${if (enabled) "OFF/실내 모드" else "ON/정상 복구"})")
                activity.runOnUiThread { onResult(true, null) }
            }
            override fun onFailure(error: IDJIError) {
                // 로그엔 전체(inner 포함), 토스트용 err 엔 code+desc 만 간결히.
                Log.e(TAG, "장애물 회피 설정 실패 type=$avoidType " +
                    "code=${error.errorCode()} inner=${error.innerCode()} " +
                    "desc=${error.description()} hint=${error.hint()}")
                val err = "$avoidType 실패 [${error.errorCode()}] ${error.description()}"
                activity.runOnUiThread { onResult(false, err) }
            }
        })
    }

    private val frameListener = ICameraStreamManager.CameraFrameListener {
        frameData, offset, length, fw, fh, _ ->
        val nv21 = frameData.copyOfRange(offset, offset + length)
        frameCallback?.invoke(nv21, fw, fh)
    }

    private fun updateStream() {
        val s = surface ?: return
        if (surfaceW <= 0 || surfaceH <= 0 || !isDroneConnected) return
        MediaDataCenter.getInstance().cameraStreamManager.putCameraStreamSurface(
            ComponentIndexType.LEFT_OR_MAIN,
            s,
            surfaceW,
            surfaceH,
            ICameraStreamManager.ScaleType.CENTER_INSIDE
        )
    }
}

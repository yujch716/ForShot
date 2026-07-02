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
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.v5.manager.interfaces.SDKManagerCallback

class DroneFrameSource(private val activity: Activity) : FrameSource {

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
                cachedLocation = null
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
        cachedLocation = null
        if (isDroneConnected) {
            MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(frameListener)
            surface?.let {
                MediaDataCenter.getInstance().cameraStreamManager.removeCameraStreamSurface(it)
            }
        }
        SDKManager.getInstance().destroy()
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

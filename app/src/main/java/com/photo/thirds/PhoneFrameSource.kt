package com.photo.thirds

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import java.util.concurrent.Executors

class PhoneFrameSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) : FrameSource {

    companion object {
        private const val TAG = "PhoneFrameSource"
    }

    private var frameCallback: FrameCallback? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    // ── Phone GPS (FusedLocation) ──────────────────────────────────────────
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    @Volatile private var cachedLocation: LocationCoordinate3D? = null
    private var locationCallback: LocationCallback? = null

    override fun setFrameCallback(cb: FrameCallback) { frameCallback = cb }
    override fun getGpsLocation() = cachedLocation

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** 위치 권한이 있을 때만 폰 GPS를 확보한다. 권한 없거나 오류 시 좌표 없이 진행(크래시 없음). */
    fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            Log.d(TAG, "위치 권한 없음 — 폰 GPS 생략")
            return
        }
        // 마지막 위치로 프라임
        try {
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    cachedLocation = LocationCoordinate3D(loc.latitude, loc.longitude, loc.altitude)
                    Log.d(TAG, "lastLocation: lat=${loc.latitude} lng=${loc.longitude}")
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "lastLocation SecurityException: $e")
        }
        // 주기 업데이트로 최신 좌표 유지
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let {
                    cachedLocation = LocationCoordinate3D(it.latitude, it.longitude, it.altitude)
                }
            }
        }
        locationCallback = callback
        try {
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.w(TAG, "requestLocationUpdates SecurityException: $e")
        }
    }

    fun bindToPreview(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()

            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                try {
                    val nv21 = yuv420ToNv21(imageProxy)
                    val w = imageProxy.width
                    val h = imageProxy.height
                    frameCallback?.invoke(nv21, w, h)
                } catch (e: Exception) {
                    Log.e(TAG, "Frame analysis error: $e")
                } finally {
                    imageProxy.close()
                }
            }

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                Log.d(TAG, "CameraX bound successfully")
            } catch (e: Exception) {
                Log.e(TAG, "CameraX bind error: $e")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    override fun release() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
    }

    // YUV_420_888 → NV21 (Y plane + interleaved VU)
    private fun yuv420ToNv21(imageProxy: ImageProxy): ByteArray {
        val w = imageProxy.width
        val h = imageProxy.height
        val nv21 = ByteArray(w * h * 3 / 2)

        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uvRowStride = vPlane.rowStride
        val uvPixelStride = vPlane.pixelStride

        var pos = 0
        for (row in 0 until h) {
            for (col in 0 until w) {
                nv21[pos++] = yBuffer.get(row * yRowStride + col * yPixelStride)
            }
        }
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                val idx = row * uvRowStride + col * uvPixelStride
                nv21[pos++] = vBuffer.get(idx)
                nv21[pos++] = uBuffer.get(idx)
            }
        }
        return nv21
    }
}

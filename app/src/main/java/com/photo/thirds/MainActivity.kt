package com.photo.thirds

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.v5.manager.interfaces.SDKManagerCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "ThirdsApp"
        const val SERVER_URL = "http://192.168.0.5:8000/detect-image"
        private const val FRAME_INTERVAL_MS = 400L
        private const val TARGET_WIDTH = 640
    }

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlayView: OverlayView
    private lateinit var tvStatus: TextView
    private lateinit var btnDetect: Button

    // ── Surface state ──────────────────────────────────────────────────────
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var isDroneConnected = false

    // ── Detection state ────────────────────────────────────────────────────
    private val isDetecting = AtomicBoolean(false)
    private val inFlight = AtomicBoolean(false)
    private var lastSentMs = 0L

    // ── Networking ─────────────────────────────────────────────────────────
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()

    // ── Frame listener ─────────────────────────────────────────────────────
    private val frameListener = ICameraStreamManager.CameraFrameListener {
            frameData, offset, length, frameW, frameH, format ->
        Log.d(TAG, "Frame: ${frameW}x${frameH} fmt=$format len=$length ts=${System.currentTimeMillis()}")

        if (!isDetecting.get()) return@CameraFrameListener
        val now = System.currentTimeMillis()
        if (now - lastSentMs < FRAME_INTERVAL_MS) return@CameraFrameListener
        if (!inFlight.compareAndSet(false, true)) return@CameraFrameListener
        lastSentMs = now

        // Copy frame data: DJI may reuse the buffer after callback returns
        val copy = frameData.copyOfRange(offset, offset + length)
        val fw = frameW
        val fh = frameH

        ioScope.launch {
            try {
                val jpegBytes = nv21ToJpeg(copy, fw, fh)
                val detections = sendFrame(jpegBytes)
                if (isDetecting.get()) {
                    withContext(Dispatchers.Main) {
                        overlayView.setDetections(detections)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Detection pipeline error: $e")
            } finally {
                inFlight.set(false)
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.sv_fpv)
        overlayView = findViewById(R.id.overlay)
        tvStatus    = findViewById(R.id.tv_status)
        btnDetect   = findViewById(R.id.btn_detect)

        surfaceView.holder.addCallback(this)

        btnDetect.setOnClickListener { toggleDetection() }

        initDjiSdk()
    }

    override fun onPause() {
        super.onPause()
        stopDetection()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDetection()
        if (isDroneConnected) {
            MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(frameListener)
            surface?.let {
                MediaDataCenter.getInstance().cameraStreamManager.removeCameraStreamSurface(it)
            }
        }
        SDKManager.getInstance().destroy()
    }

    // ── Detection toggle ───────────────────────────────────────────────────

    private fun toggleDetection() {
        if (isDetecting.get()) stopDetection() else startDetection()
    }

    private fun startDetection() {
        isDetecting.set(true)
        btnDetect.text = "탐지 중지"
    }

    private fun stopDetection() {
        isDetecting.set(false)
        btnDetect.text = "탐지 시작"
        overlayView.setDetections(emptyList())
    }

    // ── SurfaceHolder.Callback ─────────────────────────────────────────────

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        updateCameraStream()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surface?.let {
            MediaDataCenter.getInstance().cameraStreamManager.removeCameraStreamSurface(it)
        }
        surfaceWidth = 0
        surfaceHeight = 0
    }

    // ── Camera stream ──────────────────────────────────────────────────────

    private fun updateCameraStream() {
        val s = surface ?: return
        if (surfaceWidth <= 0 || surfaceHeight <= 0 || !isDroneConnected) return
        MediaDataCenter.getInstance().cameraStreamManager.putCameraStreamSurface(
            ComponentIndexType.LEFT_OR_MAIN,
            s,
            surfaceWidth,
            surfaceHeight,
            ICameraStreamManager.ScaleType.CENTER_INSIDE
        )
    }

    // ── SDK init ───────────────────────────────────────────────────────────

    private fun initDjiSdk() {
        SDKManager.getInstance().init(this, object : SDKManagerCallback {

            override fun onRegisterSuccess() {
                Log.d(TAG, "SDK register success")
                runOnUiThread { tvStatus.text = "SDK 등록 완료 — 드론 연결 대기" }
            }

            override fun onRegisterFailure(error: IDJIError) {
                Log.e(TAG, "SDK register failure: $error")
                runOnUiThread { tvStatus.text = "SDK 등록 실패: $error" }
            }

            override fun onProductConnect(productId: Int) {
                Log.d(TAG, "Drone connected, productId=$productId")
                isDroneConnected = true
                MediaDataCenter.getInstance().cameraStreamManager
                    .addFrameListener(ComponentIndexType.LEFT_OR_MAIN, ICameraStreamManager.FrameFormat.NV21, frameListener)
                runOnUiThread {
                    tvStatus.text = "드론 연결됨"
                    btnDetect.isEnabled = true
                    updateCameraStream()
                }
            }

            override fun onProductDisconnect(productId: Int) {
                Log.d(TAG, "Drone disconnected, productId=$productId")
                isDroneConnected = false
                stopDetection()
                MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(frameListener)
                surface?.let {
                    MediaDataCenter.getInstance().cameraStreamManager.removeCameraStreamSurface(it)
                }
                runOnUiThread {
                    tvStatus.text = "드론 연결 해제"
                    btnDetect.isEnabled = false
                }
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

    // ── Frame → JPEG ───────────────────────────────────────────────────────

    private fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int): ByteArray {
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val rawOut = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, rawOut)

        val bitmap = BitmapFactory.decodeByteArray(rawOut.toByteArray(), 0, rawOut.size())
        val scaled = if (width > TARGET_WIDTH) {
            val scale = TARGET_WIDTH.toFloat() / width
            Bitmap.createScaledBitmap(bitmap, TARGET_WIDTH, (height * scale).toInt(), true)
        } else {
            bitmap
        }

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)

        bitmap.recycle()
        if (scaled !== bitmap) scaled.recycle()

        return out.toByteArray()
    }

    // ── Network ────────────────────────────────────────────────────────────

    private fun sendFrame(jpegBytes: ByteArray): List<Detection> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image", "frame.jpg",
                jpegBytes.toRequestBody("image/jpeg".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(SERVER_URL)
            .post(body)
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.w(TAG, "Server error: ${response.code}")
            return emptyList()
        }

        val responseBody = response.body?.string() ?: return emptyList()
        return parseDetections(responseBody)
    }

    private fun parseDetections(json: String): List<Detection> {
        return try {
            val root = JSONObject(json)
            val arr = root.getJSONArray("detections")
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val bbox = obj.getJSONArray("bbox")
                Detection(
                    label      = obj.getString("class"),
                    confidence = obj.getDouble("confidence").toFloat(),
                    cx         = bbox.getDouble(0).toFloat(),
                    cy         = bbox.getDouble(1).toFloat(),
                    w          = bbox.getDouble(2).toFloat(),
                    h          = bbox.getDouble(3).toFloat()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error: $e")
            emptyList()
        }
    }
}

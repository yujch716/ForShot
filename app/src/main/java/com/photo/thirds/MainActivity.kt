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
        private const val SERVER_BASE = "http://192.168.0.5:8000"
        private const val FRAME_INTERVAL_MS = 400L
        private const val NIMA_INTERVAL_MS = 1000L
        private const val TARGET_WIDTH = 640
    }

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlayView: OverlayView
    private lateinit var tvStatus: TextView
    private lateinit var tvNimaScore: TextView
    private lateinit var btnDetect: Button

    // ── Surface state ──────────────────────────────────────────────────────
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var isDroneConnected = false

    // ── YOLO state ─────────────────────────────────────────────────────────
    private val isDetecting = AtomicBoolean(false)
    private val inFlight = AtomicBoolean(false)
    private var lastSentMs = 0L

    // ── NIMA state ─────────────────────────────────────────────────────────
    private val isNimaRunning = AtomicBoolean(false)
    private val nimaInFlight = AtomicBoolean(false)
    private var nimaLastSentMs = 0L

    // JPEG 캐시: YOLO 인코딩 결과를 NIMA가 재사용 (600ms 이내)
    @Volatile private var cachedJpeg: ByteArray? = null
    @Volatile private var cachedJpegMs: Long = 0L

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

        val yoloWants = isDetecting.get()
        val nimaWants = isNimaRunning.get()
        if (!yoloWants && !nimaWants) return@CameraFrameListener

        val now = System.currentTimeMillis()

        // ── YOLO 게이트 ────────────────────────────────────────────────────
        if (yoloWants && now - lastSentMs >= FRAME_INTERVAL_MS
                && inFlight.compareAndSet(false, true)) {
            lastSentMs = now
            val copy = frameData.copyOfRange(offset, offset + length)
            val fw = frameW; val fh = frameH
            ioScope.launch {
                try {
                    val jpegBytes = nv21ToJpeg(copy, fw, fh)
                    cachedJpeg = jpegBytes
                    cachedJpegMs = System.currentTimeMillis()
                    val detections = sendFrame(jpegBytes)
                    if (isDetecting.get()) {
                        withContext(Dispatchers.Main) { overlayView.setDetections(detections) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "YOLO error: $e")
                } finally {
                    inFlight.set(false)
                }
            }
        }

        // ── NIMA 게이트 (YOLO와 독립) ──────────────────────────────────────
        if (nimaWants && now - nimaLastSentMs >= NIMA_INTERVAL_MS
                && nimaInFlight.compareAndSet(false, true)) {
            nimaLastSentMs = now
            val existingJpeg = cachedJpeg?.takeIf { now - cachedJpegMs < 600 }
            val rawCopy = if (existingJpeg == null) frameData.copyOfRange(offset, offset + length) else null
            val fw = frameW; val fh = frameH
            ioScope.launch {
                try {
                    val jpeg = existingJpeg ?: nv21ToJpeg(rawCopy!!, fw, fh)
                    val score = sendNimaFrame(jpeg)
                    withContext(Dispatchers.Main) {
                        if (score != null) tvNimaScore.text = "%.2f".format(score)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "NIMA error: $e")
                } finally {
                    nimaInFlight.set(false)
                }
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView  = findViewById(R.id.sv_fpv)
        overlayView  = findViewById(R.id.overlay)
        tvStatus     = findViewById(R.id.tv_status)
        tvNimaScore  = findViewById(R.id.tv_nima_score)
        btnDetect    = findViewById(R.id.btn_detect)

        surfaceView.holder.addCallback(this)
        btnDetect.setOnClickListener { toggleDetection() }

        initDjiSdk()
    }

    override fun onPause() {
        super.onPause()
        stopDetection()
        isNimaRunning.set(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDetection()
        isNimaRunning.set(false)
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
                isNimaRunning.set(true)
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
                isNimaRunning.set(false)
                stopDetection()
                MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(frameListener)
                surface?.let {
                    MediaDataCenter.getInstance().cameraStreamManager.removeCameraStreamSurface(it)
                }
                runOnUiThread {
                    tvStatus.text = "드론 연결 해제"
                    tvNimaScore.text = "--"
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

    // ── YOLO network ───────────────────────────────────────────────────────

    private fun sendFrame(jpegBytes: ByteArray): List<Detection> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image", "frame.jpg",
                jpegBytes.toRequestBody("image/jpeg".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("$SERVER_BASE/detect-image")
            .post(body)
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.w(TAG, "YOLO server error: ${response.code}")
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
                    h          = bbox.getDouble(3).toFloat(),
                    color      = obj.optString("color", "#34C759"),
                    isPerson   = obj.optBoolean("is_person", false)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error: $e")
            emptyList()
        }
    }

    // ── NIMA network ───────────────────────────────────────────────────────

    private fun sendNimaFrame(jpegBytes: ByteArray): Float? {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image", "frame.jpg",
                jpegBytes.toRequestBody("image/jpeg".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("$SERVER_BASE/nima-score")
            .post(body)
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.w(TAG, "NIMA server error: ${response.code}")
            return null
        }

        return try {
            JSONObject(response.body?.string() ?: return null)
                .getDouble("score").toFloat()
        } catch (e: Exception) {
            Log.e(TAG, "NIMA JSON parse error: $e")
            null
        }
    }
}

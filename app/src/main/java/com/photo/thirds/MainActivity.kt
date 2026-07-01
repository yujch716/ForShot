package com.photo.thirds

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.DoublePoint2D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.KeyManager
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class SelectedTarget(
    val label: String,
    var cx: Float,
    var cy: Float,
    var missedFrames: Int = 0
)

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "ThirdsApp"
        private val SERVER_BASE get() = BuildConfig.AI_SERVER_URL
        private const val FRAME_INTERVAL_MS = 400L
        private const val NIMA_INTERVAL_MS = 1000L
        private const val TARGET_WIDTH = 640
        private const val MATCH_THRESHOLD = 0.10f
        private const val MISS_LIMIT = 15
    }

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlayView: OverlayView
    private lateinit var tvStatus: TextView
    private lateinit var tvNimaScore: TextView
    private lateinit var btnDetect: Button
    private lateinit var btnCapture: FloatingActionButton

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
    private val nimaInFlight = AtomicBoolean(false)
    private var nimaLastSentMs = 0L

    // JPEG 캐시: YOLO 인코딩 결과를 NIMA가 재사용 (600ms 이내)
    @Volatile private var cachedJpeg: ByteArray? = null
    @Volatile private var cachedJpegMs: Long = 0L

    // ── Selection state (Main thread only) ────────────────────────────────
    private val selectedTargets = mutableListOf<SelectedTarget>()
    private var latestDetections: List<Detection> = emptyList()

    // ── Capture state ──────────────────────────────────────────────────────
    private val captureRequested = AtomicBoolean(false)
    @Volatile private var pendingTargetsJson: String = "[]"

    // ── Gesture ────────────────────────────────────────────────────────────
    private lateinit var gestureDetector: GestureDetector

    // ── Networking ─────────────────────────────────────────────────────────
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()
    private val captureHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    // ── Frame listener ─────────────────────────────────────────────────────
    private val frameListener = ICameraStreamManager.CameraFrameListener {
            frameData, offset, length, frameW, frameH, format ->
        Log.d(TAG, "Frame: ${frameW}x${frameH} fmt=$format len=$length ts=${System.currentTimeMillis()}")

        // ── Capture 게이트 (탐지 여부와 무관) ────────────────────────────
        if (captureRequested.compareAndSet(true, false)) {
            val frameCopy = frameData.copyOfRange(offset, offset + length)
            val fw = frameW; val fh = frameH
            val json = pendingTargetsJson
            ioScope.launch { captureAndSend(frameCopy, fw, fh, json) }
        }

        if (!isDetecting.get()) return@CameraFrameListener
        val yoloWants = true
        val nimaWants = true

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
                    val rawDetections = sendFrame(jpegBytes)
                    Log.d(TAG, "[2] parsed rawDetections.size=${rawDetections.size}  isDetecting=${isDetecting.get()}")
                    if (isDetecting.get()) {
                        withContext(Dispatchers.Main) {
                            val marked = try {
                                rebindSelections(rawDetections)
                            } catch (e: Exception) {
                                Log.e(TAG, "rebindSelections error: $e")
                                rawDetections
                            }
                            Log.d(TAG, "[3] after rebind marked.size=${marked.size}")
                            Log.d(TAG, "[4] calling overlayView.setDetections(${marked.size})")
                            latestDetections = marked
                            overlayView.setDetections(marked)
                        }
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
        enableImmersiveMode()

        surfaceView  = findViewById(R.id.sv_fpv)
        overlayView  = findViewById(R.id.overlay)
        tvStatus     = findViewById(R.id.tv_status)
        tvNimaScore  = findViewById(R.id.tv_nima_score)
        btnDetect    = findViewById(R.id.btn_detect)

        btnCapture    = findViewById(R.id.btn_capture)

        surfaceView.holder.addCallback(this)
        btnDetect.setOnClickListener { toggleDetection() }
        btnCapture.setOnClickListener {
            btnCapture.isEnabled = false
            val arr = JSONArray()
            for (det in latestDetections) {
                if (!det.selected) continue
                arr.put(JSONObject().apply {
                    put("class", det.label)
                    put("bbox", JSONArray().apply {
                        put(det.cx.toDouble()); put(det.cy.toDouble())
                        put(det.w.toDouble());  put(det.h.toDouble())
                    })
                })
            }
            pendingTargetsJson = arr.toString()
            captureRequested.set(true)
        }

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                handleTap(e.x, e.y)
                return true
            }
        })
        surfaceView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        initDjiSdk()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
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
        selectedTargets.clear()
        latestDetections = emptyList()
        overlayView.setDetections(emptyList())
        tvNimaScore.text = "--"
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
                    btnCapture.isEnabled = true
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
                    tvNimaScore.text = "--"
                    btnDetect.isEnabled = false
                    btnCapture.isEnabled = false
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

    // ── Tap handling ───────────────────────────────────────────────────────

    private fun handleTap(rawX: Float, rawY: Float) {
        val vw = overlayView.width.toFloat()
        val vh = overlayView.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val normX = rawX / vw
        val normY = rawY / vh

        // 가장 작은 박스(가장 구체적 대상) 우선 히트-테스트
        var hitDet: Detection? = null
        var hitArea = Float.MAX_VALUE
        for (det in latestDetections) {
            val l = det.cx - det.w / 2f
            val r = det.cx + det.w / 2f
            val t = det.cy - det.h / 2f
            val b = det.cy + det.h / 2f
            if (normX in l..r && normY in t..b) {
                val area = det.w * det.h
                if (area < hitArea) { hitArea = area; hitDet = det }
            }
        }

        if (hitDet != null) toggleSelection(hitDet)
        else triggerFocus(normX, normY)
    }

    private fun toggleSelection(det: Detection) {
        val existingIdx = selectedTargets.indexOfFirst { t ->
            t.label == det.label &&
            Math.hypot((t.cx - det.cx).toDouble(), (t.cy - det.cy).toDouble()) < MATCH_THRESHOLD
        }
        if (existingIdx >= 0) selectedTargets.removeAt(existingIdx)
        else selectedTargets.add(SelectedTarget(det.label, det.cx, det.cy))

        val marked = try {
            applySelections(latestDetections)
        } catch (e: Exception) {
            Log.e(TAG, "applySelections error: $e")
            latestDetections
        }
        latestDetections = marked
        overlayView.setDetections(marked)
    }

    // ── Selection tracking ─────────────────────────────────────────────────

    // 즉시 재그리기용: expiry 없이 현재 selectedTargets를 detections에 마킹
    private fun applySelections(detections: List<Detection>): List<Detection> {
        val reset = detections.map { if (it.selected) it.copy(selected = false) else it }
        if (selectedTargets.isEmpty()) return reset
        val result = reset.toMutableList()
        val used = mutableSetOf<Int>()
        for (t in selectedTargets) {
            var bestIdx = -1; var bestDist = Float.MAX_VALUE
            for (i in result.indices) {
                if (i in used || result[i].label != t.label) continue
                val d = Math.hypot(
                    (result[i].cx - t.cx).toDouble(),
                    (result[i].cy - t.cy).toDouble()
                ).toFloat()
                if (d < MATCH_THRESHOLD && d < bestDist) { bestDist = d; bestIdx = i }
            }
            if (bestIdx >= 0) {
                result[bestIdx] = result[bestIdx].copy(selected = true)
                used += bestIdx
            }
        }
        return result
    }

    // 프레임 단위 재바인딩: 좌표 갱신 + 연속 미탐지 만료
    private fun rebindSelections(rawDetections: List<Detection>): List<Detection> {
        if (selectedTargets.isEmpty()) return rawDetections
        val result = rawDetections.toMutableList()
        val used = mutableSetOf<Int>()
        val expired = mutableListOf<SelectedTarget>()
        for (t in selectedTargets) {
            var bestIdx = -1; var bestDist = Float.MAX_VALUE
            for (i in result.indices) {
                if (i in used || result[i].label != t.label) continue
                val d = Math.hypot(
                    (result[i].cx - t.cx).toDouble(),
                    (result[i].cy - t.cy).toDouble()
                ).toFloat()
                if (d < MATCH_THRESHOLD && d < bestDist) { bestDist = d; bestIdx = i }
            }
            if (bestIdx >= 0) {
                val matched = rawDetections[bestIdx]
                result[bestIdx] = result[bestIdx].copy(selected = true)
                used += bestIdx
                t.cx = matched.cx; t.cy = matched.cy; t.missedFrames = 0
            } else {
                t.missedFrames++
                if (t.missedFrames > MISS_LIMIT) expired += t
            }
        }
        selectedTargets.removeAll(expired.toSet())
        return result
    }

    // ── Tap-to-focus ───────────────────────────────────────────────────────

    private fun triggerFocus(normX: Float, normY: Float) {
        if (!isDroneConnected) return
        ioScope.launch {
            try {
                val key = KeyTools.createCameraKey(
                    CameraKey.KeyCameraFocusTarget,
                    ComponentIndexType.LEFT_OR_MAIN,
                    CameraLensType.CAMERA_LENS_ZOOM
                )
                KeyManager.getInstance().setValue(
                    key,
                    DoublePoint2D(normX.toDouble(), normY.toDouble()),
                    object : CommonCallbacks.CompletionCallback {
                        override fun onSuccess() {
                            Log.d(TAG, "Focus OK ($normX, $normY)")
                        }
                        override fun onFailure(e: IDJIError) {
                            Log.w(TAG, "Focus failed: $e")
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "이 기종은 탭 포커스 미지원",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "Focus error: $e")
            }
        }
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
        Log.d(TAG, "[1] YOLO response len=${responseBody.length} body=$responseBody")
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

    // ── Capture network ────────────────────────────────────────────────────

    private suspend fun captureAndSend(
        nv21: ByteArray, width: Int, height: Int, targetsJson: String
    ) {
        try {
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
            val jpegBytes = out.toByteArray()

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "snapshot.jpg",
                    jpegBytes.toRequestBody("image/jpeg".toMediaType()))
                .addFormDataPart("targets", targetsJson)
                .build()

            val request = Request.Builder()
                .url("$SERVER_BASE/capture")
                .post(body)
                .build()

            val response = captureHttpClient.newCall(request).execute()
            withContext(Dispatchers.Main) {
                btnCapture.isEnabled = true
                val msg = if (response.isSuccessful) "촬영 저장됨"
                          else "촬영 실패 (${response.code})"
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture error: $e")
            withContext(Dispatchers.Main) {
                btnCapture.isEnabled = true
                Toast.makeText(this@MainActivity, "촬영 전송 오류", Toast.LENGTH_SHORT).show()
            }
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

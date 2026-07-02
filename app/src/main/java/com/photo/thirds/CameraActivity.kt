package com.photo.thirds

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.DoublePoint2D
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
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

class CameraActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "ThirdsApp"
        private val SERVER_BASE get() = BuildConfig.AI_SERVER_URL
        private const val FRAME_INTERVAL_MS = 400L
        private const val NIMA_INTERVAL_MS = 1000L
        private const val TARGET_WIDTH = 640
        private const val MATCH_THRESHOLD = 0.10f
        private const val MISS_LIMIT = 15
        private const val DRONE_CONNECT_TIMEOUT_MS = 12_000L
    }

    // ── Mode ───────────────────────────────────────────────────────────────
    private lateinit var mode: String
    private lateinit var frameSource: FrameSource

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var surfaceView: SurfaceView
    private lateinit var previewPhone: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var tvStatus: TextView
    private lateinit var tvNimaScore: TextView
    private lateinit var btnDetect: Button
    private lateinit var btnCapture: FloatingActionButton
    private lateinit var btnMenu: Button

    // ── Flight (drone only) ────────────────────────────────────────────────
    private var droneConnected = false
    private val flightActionInProgress = AtomicBoolean(false)

    // ── Drone connection watchdog (drone only) ─────────────────────────────
    private val mainHandler = Handler(Looper.getMainLooper())
    private var connectionFailureHandled = false
    private val droneConnectTimeout = Runnable {
        if (!droneConnected) showDroneNotConnectedDialog(null)
    }

    // ── Surface state (drone only) ─────────────────────────────────────────
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    // ── YOLO state ─────────────────────────────────────────────────────────
    private val isDetecting = AtomicBoolean(false)
    private val inFlight = AtomicBoolean(false)
    private var lastSentMs = 0L

    // ── NIMA state ─────────────────────────────────────────────────────────
    private val nimaInFlight = AtomicBoolean(false)
    private var nimaLastSentMs = 0L

    @Volatile private var cachedJpeg: ByteArray? = null
    @Volatile private var cachedJpegMs: Long = 0L

    // ── Selection state ────────────────────────────────────────────────────
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

    // ── Camera permission launcher ─────────────────────────────────────────
    private var onCameraGranted: (() -> Unit)? = null
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onCameraGranted?.invoke()
        } else {
            tvStatus.text = "카메라 권한이 필요합니다"
            btnDetect.isEnabled = false
            btnCapture.isEnabled = false
        }
    }

    // 위치 권한(폰 모드 GPS). 거부돼도 촬영은 진행 — 좌표만 생략.
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) (frameSource as? PhoneFrameSource)?.startLocationUpdates()
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        enableImmersiveMode()

        mode = intent.getStringExtra("mode") ?: "drone"

        surfaceView  = findViewById(R.id.sv_fpv)
        previewPhone = findViewById(R.id.preview_phone)
        overlayView  = findViewById(R.id.overlay)
        tvStatus     = findViewById(R.id.tv_status)
        tvNimaScore  = findViewById(R.id.tv_nima_score)
        btnDetect    = findViewById(R.id.btn_detect)
        btnCapture   = findViewById(R.id.btn_capture)
        btnMenu      = findViewById(R.id.btn_menu)

        btnDetect.isEnabled = false
        btnCapture.isEnabled = false

        btnMenu.setOnClickListener { showFlightMenu() }
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
                        put(det.w.toDouble()); put(det.h.toDouble())
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
        overlayView.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event); true }

        if (mode == "drone") {
            surfaceView.visibility = View.VISIBLE
            previewPhone.visibility = View.GONE
            btnMenu.visibility = View.VISIBLE

            val droneSource = DroneFrameSource(this)
            frameSource = droneSource
            droneSource.setStatusListener(object : DroneFrameSource.StatusListener {
                // DJI SDK 콜백은 백그라운드 스레드에서 올 수 있으므로 항상 UI 스레드로 넘긴다.
                override fun onConnected() = runOnUiThread {
                    mainHandler.removeCallbacks(droneConnectTimeout)
                    droneConnected = true
                    btnDetect.isEnabled = true
                    btnCapture.isEnabled = true
                    tvStatus.text = "드론 연결됨"
                }
                override fun onDisconnected() = runOnUiThread {
                    droneConnected = false
                    stopDetection()
                    tvNimaScore.text = "--"
                    btnDetect.isEnabled = false
                    btnCapture.isEnabled = false
                    tvStatus.text = "드론 연결 해제"
                }
                override fun onStatusText(msg: String) = runOnUiThread { tvStatus.text = msg }
                override fun onConnectFailed(msg: String) = runOnUiThread { showDroneNotConnectedDialog(msg) }
            })
            surfaceView.holder.addCallback(this)
            try {
                droneSource.init()
                mainHandler.postDelayed(droneConnectTimeout, DRONE_CONNECT_TIMEOUT_MS)
            } catch (e: Exception) {
                Log.e(TAG, "드론 SDK 초기화 실패: $e")
                showDroneNotConnectedDialog("드론 SDK 초기화에 실패했습니다.")
            }
        } else {
            surfaceView.visibility = View.GONE
            previewPhone.visibility = View.VISIBLE
            btnMenu.visibility = View.GONE
            tvStatus.text = "폰 카메라 초기화 중..."

            val phoneSource = PhoneFrameSource(this, this)
            frameSource = phoneSource

            checkCameraPermission {
                phoneSource.bindToPreview(previewPhone)
                tvStatus.text = "폰 카메라 연결됨"
                btnDetect.isEnabled = true
                btnCapture.isEnabled = true
                ensureLocationForPhone(phoneSource)
            }
        }

        frameSource.setFrameCallback { nv21, fw, fh -> handleFrame(nv21, fw, fh) }
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
        mainHandler.removeCallbacks(droneConnectTimeout)
        super.onDestroy()
        stopDetection()
        frameSource.release()
    }

    // ── Drone connection failure (drone only) ──────────────────────────────

    private fun showDroneNotConnectedDialog(customMsg: String?) {
        if (connectionFailureHandled || isFinishing) return
        connectionFailureHandled = true
        mainHandler.removeCallbacks(droneConnectTimeout)
        AlertDialog.Builder(this)
            .setTitle("드론 연결 실패")
            .setMessage(customMsg ?: "드론이 연결되지 않았습니다.\n드론 전원과 기기 연결 상태를 확인한 뒤 다시 시도해주세요.")
            .setCancelable(false)
            .setPositiveButton("돌아가기") { _, _ -> finish() }
            .show()
    }

    // ── Camera permission ──────────────────────────────────────────────────

    private fun checkCameraPermission(onGranted: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            onCameraGranted = onGranted
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** 폰 모드 GPS: 위치 권한이 있으면 즉시 시작, 없으면 런타임 요청(거부돼도 촬영은 진행). */
    private fun ensureLocationForPhone(source: PhoneFrameSource) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            source.startLocationUpdates()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // ── SurfaceHolder.Callback (drone mode only) ───────────────────────────

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width; surfaceHeight = height
        (frameSource as? DroneFrameSource)?.onSurfaceReady(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        (frameSource as? DroneFrameSource)?.onSurfaceDestroyed(holder.surface)
        surfaceWidth = 0; surfaceHeight = 0
    }

    // ── Frame pipeline (공통) ──────────────────────────────────────────────

    private fun handleFrame(nv21: ByteArray, fw: Int, fh: Int) {
        Log.d(TAG, "Frame: ${fw}x${fh} ts=${System.currentTimeMillis()}")

        // ── Capture 게이트 ────────────────────────────────────────────────
        if (captureRequested.compareAndSet(true, false)) {
            val json = pendingTargetsJson
            val loc = frameSource.getGpsLocation()
            ioScope.launch { captureAndSend(nv21, fw, fh, json, loc) }
        }

        if (!isDetecting.get()) return
        val now = System.currentTimeMillis()

        // ── YOLO 게이트 ──────────────────────────────────────────────────
        if (now - lastSentMs >= FRAME_INTERVAL_MS && inFlight.compareAndSet(false, true)) {
            lastSentMs = now
            val copy = nv21.copyOf()
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

        // ── NIMA 게이트 ──────────────────────────────────────────────────
        if (now - nimaLastSentMs >= NIMA_INTERVAL_MS && nimaInFlight.compareAndSet(false, true)) {
            nimaLastSentMs = now
            val existingJpeg = cachedJpeg?.takeIf { now - cachedJpegMs < 600 }
            val rawCopy = if (existingJpeg == null) nv21.copyOf() else null
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

    // ── Flight menu (drone only) ───────────────────────────────────────────

    private fun showFlightMenu() {
        val popup = PopupMenu(this, btnMenu)
        popup.menu.add(0, 1, 0, "이륙").setIcon(R.drawable.ic_flight_takeoff)
        popup.menu.add(0, 2, 1, "착륙").setIcon(R.drawable.ic_flight_land)

        // PopupMenu는 기본적으로 아이콘을 숨김 → 강제 표시
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            popup.setForceShowIcon(true)
        } else {
            try {
                val field = popup.javaClass.getDeclaredField("mPopup")
                field.isAccessible = true
                val helper = field.get(popup)
                helper.javaClass
                    .getMethod("setForceShowIcon", Boolean::class.javaPrimitiveType)
                    .invoke(helper, true)
            } catch (_: Exception) { /* 아이콘 없이 텍스트만 표시(안전한 폴백) */ }
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> confirmAndRun("이륙", "정말 이륙하시겠습니까?") { doTakeoff() }
                2 -> confirmAndRun("착륙", "정말 착륙하시겠습니까?") { doLanding() }
            }
            true
        }
        popup.show()
    }

    private fun confirmAndRun(title: String, msg: String, action: () -> Unit) {
        if (!droneConnected) {
            Toast.makeText(this, "드론이 연결되지 않았습니다", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("확인") { _, _ -> action() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun doTakeoff() = runFlightAction("이륙") { cb ->
        (frameSource as? DroneFrameSource)?.startTakeoff(cb)
    }

    private fun doLanding() = runFlightAction("착륙") { cb ->
        (frameSource as? DroneFrameSource)?.startLanding(cb)
    }

    private fun runFlightAction(
        label: String,
        invoke: (CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>) -> Unit
    ) {
        if (!flightActionInProgress.compareAndSet(false, true)) return
        btnMenu.isEnabled = false
        invoke(object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            override fun onSuccess(t: EmptyMsg?) {
                Log.d(TAG, "$label 성공")
                runOnUiThread {
                    flightActionInProgress.set(false)
                    btnMenu.isEnabled = true
                    Toast.makeText(this@CameraActivity, "$label 실행됨", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onFailure(e: IDJIError) {
                Log.w(TAG, "$label 실패: $e")
                runOnUiThread {
                    flightActionInProgress.set(false)
                    btnMenu.isEnabled = true
                    Toast.makeText(this@CameraActivity, "$label 실패: ${e.description()}", Toast.LENGTH_LONG).show()
                }
            }
        })
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

    // ── Tap handling ───────────────────────────────────────────────────────

    private fun handleTap(rawX: Float, rawY: Float) {
        val vw = overlayView.width.toFloat()
        val vh = overlayView.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val normX = rawX / vw
        val normY = rawY / vh

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

    // ── Tap-to-focus (drone only) ──────────────────────────────────────────

    private fun triggerFocus(normX: Float, normY: Float) {
        if (mode != "drone") return
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
                                Toast.makeText(this@CameraActivity, "이 기종은 탭 포커스 미지원", Toast.LENGTH_SHORT).show()
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
            .addFormDataPart("image", "frame.jpg", jpegBytes.toRequestBody("image/jpeg".toMediaType()))
            .build()

        val request = Request.Builder().url("$SERVER_BASE/detect-image").post(body).build()
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

    private fun isValidGps(lat: Double?, lng: Double?): Boolean {
        if (lat == null || lng == null) return false
        if (lat.isNaN() || lng.isNaN()) return false
        if (lat == 0.0 && lng == 0.0) return false
        return lat in -90.0..90.0 && lng in -180.0..180.0
    }

    private suspend fun captureAndSend(
        nv21: ByteArray, width: Int, height: Int,
        targetsJson: String,
        location: dji.sdk.keyvalue.value.common.LocationCoordinate3D?
    ) {
        try {
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
            val jpegBytes = out.toByteArray()

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "snapshot.jpg", jpegBytes.toRequestBody("image/jpeg".toMediaType()))
                .addFormDataPart("targets", targetsJson)
                .apply {
                    val lat = location?.latitude
                    val lng = location?.longitude
                    if (isValidGps(lat, lng)) {
                        addFormDataPart("lat", lat.toString())
                        addFormDataPart("lng", lng.toString())
                        Log.d(TAG, "Capture with GPS: lat=$lat lng=$lng")
                    } else {
                        Log.d(TAG, "Capture without GPS (location=$location)")
                    }
                }
                .build()

            val request = Request.Builder().url("$SERVER_BASE/capture").post(body).build()
            val response = captureHttpClient.newCall(request).execute()
            withContext(Dispatchers.Main) {
                btnCapture.isEnabled = true
                val msg = if (response.isSuccessful) "촬영 저장됨" else "촬영 실패 (${response.code})"
                Toast.makeText(this@CameraActivity, msg, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture error: $e")
            withContext(Dispatchers.Main) {
                btnCapture.isEnabled = true
                Toast.makeText(this@CameraActivity, "촬영 전송 오류", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── NIMA network ───────────────────────────────────────────────────────

    private fun sendNimaFrame(jpegBytes: ByteArray): Float? {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", "frame.jpg", jpegBytes.toRequestBody("image/jpeg".toMediaType()))
            .build()

        val request = Request.Builder().url("$SERVER_BASE/nima-score").post(body).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.w(TAG, "NIMA server error: ${response.code}")
            return null
        }

        return try {
            JSONObject(response.body?.string() ?: return null).getDouble("score").toFloat()
        } catch (e: Exception) {
            Log.e(TAG, "NIMA JSON parse error: $e")
            null
        }
    }
}

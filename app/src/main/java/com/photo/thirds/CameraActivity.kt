package com.photo.thirds

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
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
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.DoublePoint2D
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CameraActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "ThirdsApp"
        private val SERVER_BASE get() = BuildConfig.AI_SERVER_URL
        private const val FRAME_INTERVAL_MS = 300L
        private const val TARGET_WIDTH = 640
        private const val MATCH_THRESHOLD = 0.10f
        private const val MISS_LIMIT = 15
        private const val DRONE_CONNECT_TIMEOUT_MS = 12_000L
        // 스캔 중 프레임 캡처 간격 (0.5초). 서버 peak_index 역산에도 사용.
        private const val SCAN_FRAME_INTERVAL_MS = 500L
        private const val SCAN_FRAME_INTERVAL_S = 0.5
        private const val BACKUP_SETTLE_MS = 500L      // 후진 정지 후 관성 안정화 대기
        /** 저장 시 중앙 확대 배율(중앙 1/SAVE_ZOOM 영역을 크롭). */
        private const val SAVE_ZOOM = 1.4f
        // 로그의 거리 환산용(실제 이동속도는 DroneMover.SCAN_SPEED). m/s.
        private const val SCAN_SPEED_LOG = 0.3
        // ── 세부조정(detail-refine + 30cm 스캔) ──────────────────────────────
        /** 세부조정 후진 거리 (m). detail-refine 전 화면 넓히기용. */
        private const val DETAIL_BACKUP_M = 0.5
        // ── 틸트 sweep ───────────────────────────────────────────────────────
        private const val TILT_AMPLITUDE_DEG = 10.0    // base ± 10°
        private const val TILT_NUM_FRAMES = 11         // 캡처 프레임 수(대략 2° 간격)
        private const val TILT_FRAME_INTERVAL_MS = 350L
        private const val TILT_VLM_MAX_SIDE = 768      // 서버 전송 프레임 긴 변(px)
    }

    // ── Mode ───────────────────────────────────────────────────────────────
    private lateinit var mode: String
    private lateinit var frameSource: FrameSource

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var surfaceView: SurfaceView
    private lateinit var previewPhone: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var tvStatus: TextView
    private lateinit var btnDetect: ImageButton
    private lateinit var btnCapture: ImageButton
    private lateinit var pbCapture: ProgressBar
    private lateinit var btnMenu: ImageButton
    private lateinit var btnScan: Button
    private lateinit var btnAdjust: Button
    private lateinit var btnGimbalTest: Button
    private lateinit var btnTilt: Button
    private lateinit var btnEmergencyStop: ImageButton
    private lateinit var ivFinalPhoto: ImageView
    private lateinit var tvFinalCaption: TextView

    // ── 촬영 자동 파이프라인 오버레이 뷰 ────────────────────────────────────
    private lateinit var pipelineOverlay: View
    private lateinit var llPipelineEnd: View
    private lateinit var btnPipelineClose: Button
    private lateinit var btnPipelineDownload: Button
    private lateinit var pipelineProgress: PipelineProgressView

    // ── 짐벌 pitch 테스트 (drone only, 이륙 없이 검증) ──────────────────────
    private val gimbalTester by lazy { GimbalTester(ioScope) }
    private val gimbalTestBusy = AtomicBoolean(false)

    // ── 틸트 sweep (drone only) ────────────────────────────────────────────
    private val tiltBusy = AtomicBoolean(false)
    @Volatile private var tiltCancelled = false
    @Volatile private var tiltFrameReq: CompletableDeferred<ByteArray>? = null

    // ── Flight (drone only) ────────────────────────────────────────────────
    private var droneConnected = false
    private val flightActionInProgress = AtomicBoolean(false)
    /** 실내 모드(장애물 회피 OFF + 비전 측위 ON) 켜짐 여부. 메뉴 토글로 제어. */
    private var indoorModeEnabled = false

    // ── Virtual Stick 이동 (drone only) ────────────────────────────────────
    private var droneMover: DroneMover? = null
    @Volatile private var lastDroneOffset: DroneOffset? = null

    // ── 스캔-정점 (drone only) ─────────────────────────────────────────────
    // /capture 응답의 best 최종구도(JPEG). scan-peak 요청 때 target 으로 함께 전송.
    @Volatile private var bestImageJpeg: ByteArray? = null
    private val scanBusy = AtomicBoolean(false)          // 스캔 전체 진행 중(버튼 잠금/중복 방지)
    @Volatile private var capturing = false              // 프레임 수집 창(스캔 이동 중에만 true)
    @Volatile private var scanStartMs = 0L
    @Volatile private var scanNextCaptureMs = 0L
    @Volatile private var scanCancelled = false          // 비상정지 등으로 스캔 취소됨
    private val scanFrames = java.util.Collections.synchronizedList(mutableListOf<ScanFrame>())
    // 정점 복귀 완료 후 검증 촬영 대기(다음 프레임에서 1장 잡아 /scan-result 전송). null 이면 대기 없음.
    @Volatile private var scanResultPending: ScanResultMeta? = null
    // 스캔 재사용: 구도(2m)와 세부(30cm)가 같은 스캔 로직을 씀. 스캔 시작 전에 세팅.
    @Volatile private var scanIsDetail = false                        // true = 세부(30cm) 스캔
    @Volatile private var scanDurationMs = DroneMover.SCAN_TIME_MS    // 스캔 이동 시간(거리)
    @Volatile private var detailTargetJpeg: ByteArray? = null         // /detail-refine target_image(스캔 진입 게이트)

    // ── 세부조정(detail-refine + 30cm 스캔, drone only) ─────────────────────
    @Volatile private var adjustCancelled = false        // 비상정지 등으로 취소됨(틸트/세부 공용)
    // 현재 프레임 1장을 다음 프레임에서 잡아 JPEG 로 넘겨받기 위한 요청(1회성).
    @Volatile private var adjustFrameReq: CompletableDeferred<ByteArray>? = null

    // ── Drone connection watchdog (drone only) ─────────────────────────────
    private val mainHandler = Handler(Looper.getMainLooper())
    private var connectionFailureHandled = false
    private val droneConnectTimeout = Runnable {
        if (!droneConnected) showDroneNotConnectedDialog(null)
    }
    // 정점 검증 촬영 대기가 프레임 없이 방치되면(피드 끊김 등) 스캔을 풀어준다.
    private val scanResultTimeout = Runnable {
        if (scanResultPending != null) {
            scanResultPending = null
            Toast.makeText(this, "정점 촬영 실패 (프레임 없음)", Toast.LENGTH_SHORT).show()
            finishScan()
        }
    }

    // ── Surface state (drone only) ─────────────────────────────────────────
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    // ── YOLO state ─────────────────────────────────────────────────────────
    private val isDetecting = AtomicBoolean(false)
    private val inFlight = AtomicBoolean(false)
    private var lastSentMs = 0L

    @Volatile private var cachedJpeg: ByteArray? = null
    @Volatile private var cachedJpegMs: Long = 0L

    // ── Selection state ────────────────────────────────────────────────────
    private val selectedTargets = mutableListOf<SelectedTarget>()
    // @Volatile: 촬영 게이트(카메라 스레드)에서 후진 후 최신 박스를 읽어 targets 를 재계산.
    @Volatile private var latestDetections: List<Detection> = emptyList()

    // ── Capture state ──────────────────────────────────────────────────────
    private val captureRequested = AtomicBoolean(false)
    private val captureInFlight = AtomicBoolean(false)
    /** 후진→촬영 취소 플래그(비상정지 시 set). true 면 후진 콜백에서 촬영 진행 안 함. */
    @Volatile private var captureBackupCancelled = false

    // ── 세션 + 자동 파이프라인 상태 ─────────────────────────────────────────
    /** 촬영 순간 생성되는 세션 ID(yyyyMMdd_HHmmss_SSS). 모든 서버 호출에 session_id 로 첨부. */
    @Volatile private var sessionId: String = ""
    /** 자동 파이프라인 진행 중 여부. */
    @Volatile private var pipelineActive = false
    /** 현재 단계 인덱스 0=위치,1=구도,2=세부,3=정제 (-1=없음). */
    private var pipelineStage = -1
    /** 스캔이 정점에 도달했는지(성공 판정). finishScan 에서 다음 단계 진행 여부 결정. */
    @Volatile private var scanReachedPeak = false
    /** 저장 버튼용 — 파이프라인 종료 시점의 현재 화면 비트맵. */
    private var pipelineResultBitmap: Bitmap? = null

    // ── Gesture ────────────────────────────────────────────────────────────
    private lateinit var gestureDetector: GestureDetector

    // ── Networking ─────────────────────────────────────────────────────────
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()
    // /capture 는 서버가 저장 + drone_offset(탐지·best·오프셋) 계산까지 하고 응답하므로
    // 5초로는 부족해 SocketTimeout 이 났다. 응답(=drone_offset)을 받아야 정렬이 켜지므로 넉넉히.
    private val captureHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 촬영 순간 고유 세션 ID 생성(밀리초까지). */
    private fun genSessionId(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    /** 세션 진행 중이면 모든 요청에 session_id 를 첨부(세션 밖 라이브 호출은 자동 생략). */
    private fun MultipartBody.Builder.withSession(): MultipartBody.Builder =
        apply { if (sessionId.isNotEmpty()) addFormDataPart("session_id", sessionId) }

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
        btnDetect    = findViewById(R.id.btn_detect)
        btnCapture   = findViewById(R.id.btn_capture)
        pbCapture    = findViewById(R.id.pb_capture)
        btnMenu      = findViewById(R.id.btn_menu)
        btnScan      = findViewById(R.id.btn_scan)
        btnAdjust    = findViewById(R.id.btn_adjust)
        btnGimbalTest = findViewById(R.id.btn_gimbal_test)
        btnTilt      = findViewById(R.id.btn_tilt)
        btnEmergencyStop = findViewById(R.id.btn_emergency_stop)
        ivFinalPhoto = findViewById(R.id.iv_final_photo)
        tvFinalCaption = findViewById(R.id.tv_final_caption)
        ivFinalPhoto.setOnClickListener {
            ivFinalPhoto.visibility = View.GONE
            tvFinalCaption.visibility = View.GONE
            ivFinalPhoto.setImageDrawable(null)
        }

        // 자동 파이프라인 오버레이 바인딩 + 종료 버튼.
        pipelineOverlay = findViewById(R.id.pipeline_overlay)
        llPipelineEnd = findViewById(R.id.ll_pipeline_end)
        btnPipelineClose = findViewById(R.id.btn_pipeline_close)
        btnPipelineDownload = findViewById(R.id.btn_pipeline_download)
        pipelineProgress = findViewById(R.id.pipeline_progress)
        btnPipelineClose.setOnClickListener { hidePipelineOverlay() }
        btnPipelineDownload.setOnClickListener {
            val bmp = pipelineResultBitmap
            if (bmp == null) Toast.makeText(this, "저장할 화면이 없습니다", Toast.LENGTH_SHORT).show()
            else {
                // 저장 시 중앙을 1.4배 확대(= 중앙 1/1.4 영역 크롭)한 이미지로 저장.
                val cropped = centerCropZoom(bmp, SAVE_ZOOM)
                Toast.makeText(this, if (saveBitmapToGallery(cropped)) "완료" else "저장 실패", Toast.LENGTH_SHORT).show()
            }
            hidePipelineOverlay()
        }

        btnDetect.isEnabled = false
        btnCapture.isEnabled = false

        btnMenu.setOnClickListener { showFlightMenu() }
        btnDetect.setOnClickListener { toggleDetection() }
        btnCapture.setOnClickListener {
            // in-flight 가드: 이미 요청 진행 중이면 탭을 완전히 무시(한 번에 /capture 1개).
            if (!captureInFlight.compareAndSet(false, true)) return@setOnClickListener
            setCaptureLoading(true)
            // targets(bbox)는 여기서 고정하지 않는다. 드론은 후진(위치 조정) 후 촬영하므로
            // 후진 전 박스를 쓰면 크롭이 어긋난다 → 실제 캡처 프레임에서 buildTargetsJson() 로 재계산.
            if (mode == "drone") {
                sessionId = genSessionId()    // 이 촬영 = 한 세션(모든 서버 호출에 첨부)
                startPipeline()               // 드론: 최초 사진(후진 전) → 후진 → /capture 를 파이프라인이 처리
            } else {
                startBackupThenCapture()      // 폰: 즉시 촬영(legacy)
            }
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
            // 스캔/세부조정/짐벌/틸트 버튼은 촬영 자동 파이프라인으로 대체 → 숨김.
            btnScan.visibility = View.GONE
            btnAdjust.visibility = View.GONE
            btnGimbalTest.visibility = View.GONE
            btnTilt.visibility = View.GONE
            btnEmergencyStop.visibility = View.VISIBLE

            val droneSource = DroneFrameSource(this)
            frameSource = droneSource

            // 실측 로그용 텔레메트리(droneSource)를 mover 에 넘김.
            droneMover = DroneMover(this, ioScope, droneSource)
            // 정렬/스캔 버튼: 앱 시작 시 비활성(회색). 유효한 drone_offset/best 수신 시 활성화.
            setScanEnabled(false)
            setAdjustEnabled(false)          // 세부조정: 스캔 완료 후에만 활성.
            setTiltEnabled(false)            // 틸트: 세부조정(전후진) 완료 후에만 활성.
            // 스캔/세부조정/짐벌/틸트는 촬영 버튼 자동 파이프라인이 처리(버튼 숨김, 함수는 재사용).
            // 비상정지: 이동 중에도 항상 활성. 즉시 스틱 0 + Virtual Stick OFF.
            btnEmergencyStop.setOnClickListener {
                droneMover?.emergencyStop()
                // 후진→촬영 취소: 진행 중 후진은 emergencyStop 의 aborted 로 멈추고, 대기 중이던 촬영도 취소.
                captureBackupCancelled = true
                if (!pipelineActive && captureInFlight.get()) abortBackupCapture(null)
                // 스캔 취소 (진행 중 이동 coroutine 은 emergencyStop 의 aborted 로 중단됨)
                scanCancelled = true
                capturing = false
                scanResultPending = null
                mainHandler.removeCallbacks(scanResultTimeout)
                scanBusy.set(false)
                // 세부조정 취소 (진행 중 이동은 emergencyStop 의 aborted 로 중단됨)
                adjustCancelled = true
                setAdjustBusy(false)
                // 틸트 sweep 취소 (진행 중이면 루프가 tiltCancelled 로 빠져나와 짐벌 base 복귀).
                tiltCancelled = true
                tiltBusy.set(false)
                setTiltBusy(false)
                // 비상정지 후엔 재촬영하도록 스캔/세부조정/틸트 비활성 + 저장 offset 폐기.
                lastDroneOffset = null
                setScanEnabled(false)
                setAdjustEnabled(false)
                setTiltEnabled(false)
                // 자동 파이프라인 진행 중이면 오버레이도 즉시 해제(드론 정지는 위 emergencyStop 이 수행).
                if (pipelineActive) hidePipelineOverlay()
                Toast.makeText(this, "비상정지 — 조종간 복귀", Toast.LENGTH_SHORT).show()
            }
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
                    btnDetect.isEnabled = false
                    btnCapture.isEnabled = false
                    // 연결 끊기면 스캔 비활성 + 저장 offset/best 폐기.
                    lastDroneOffset = null
                    bestImageJpeg = null
                    setScanEnabled(false)
                    setAdjustEnabled(false)
                    setTiltEnabled(false)
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
            btnScan.visibility = View.GONE
            btnAdjust.visibility = View.GONE
            btnGimbalTest.visibility = View.GONE
            btnTilt.visibility = View.GONE
            btnEmergencyStop.visibility = View.GONE
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
        // 앱이 백그라운드로 가면 즉시 이동 정지 + Virtual Stick OFF(조종간 복귀).
        droneMover?.emergencyStop()
        // 진행 중 스캔도 취소(대기 중이던 복귀 이동이 재개 후 실행되지 않도록).
        scanCancelled = true
        capturing = false
        scanResultPending = null
        // 진행 중 세부조정·틸트 sweep 취소.
        adjustCancelled = true
        tiltCancelled = true
        mainHandler.removeCallbacks(scanResultTimeout)
        // 자동 파이프라인 진행 중이면 오버레이 해제(재개 시 멈춘 채 남지 않도록).
        if (pipelineActive) hidePipelineOverlay()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(droneConnectTimeout)
        // 종료 전 반드시 Virtual Stick OFF.
        droneMover?.emergencyStop()
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

    /** 현재 선택된 탐지 박스들 → /capture targets JSON. 캡처 직전(후진 후) 프레임 기준. */
    private fun buildTargetsJson(): String {
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
        return arr.toString()
    }

    private fun handleFrame(nv21: ByteArray, fw: Int, fh: Int) {
        Log.d(TAG, "Frame: ${fw}x${fh} ts=${System.currentTimeMillis()}")

        // ── Capture 게이트 ────────────────────────────────────────────────
        if (captureRequested.compareAndSet(true, false)) {
            // 후진(위치 조정) 후 실제 캡처하는 이 프레임의 최신 선택 박스로 targets 재계산.
            // (버튼 누른 시점의 후진 전 박스를 쓰면 크롭이 어긋남. YOLO는 파이프라인 내내 돌아 최신 유지.)
            val json = buildTargetsJson()
            val loc = frameSource.getGpsLocation()
            ioScope.launch { captureAndSend(nv21, fw, fh, json, loc) }
        }

        // ── 정점 검증 촬영 게이트 (복귀 완료 후 1장) ──
        // handleFrame 은 카메라 단일 스레드라 get-and-clear 경쟁 없음.
        scanResultPending?.let { meta ->
            scanResultPending = null
            mainHandler.removeCallbacks(scanResultTimeout)
            val copy = nv21.copyOf()
            ioScope.launch { sendScanResult(copy, fw, fh, meta) }
        }

        // ── 세부조정 프레임 grab 게이트 (요청 시 다음 프레임 1장 → 640 JPEG) ──
        adjustFrameReq?.let { def ->
            adjustFrameReq = null
            val copy = nv21.copyOf()
            ioScope.launch {
                try { def.complete(nv21ToJpeg(copy, fw, fh)) }
                catch (e: Exception) { def.completeExceptionally(e) }
            }
        }

        // ── 틸트 sweep 프레임 grab 게이트 (요청 시 다음 프레임 1장 → 768 JPEG) ──
        tiltFrameReq?.let { def ->
            tiltFrameReq = null
            val copy = nv21.copyOf()
            ioScope.launch {
                try { def.complete(nv21ToJpegMaxSide(copy, fw, fh, TILT_VLM_MAX_SIDE, 80)) }
                catch (e: Exception) { def.completeExceptionally(e) }
            }
        }

        // ── 스캔 프레임 캡처 게이트 (스캔 이동 중 SCAN_FRAME_INTERVAL 마다 1장) ──
        if (capturing) {
            val nowMs = System.currentTimeMillis()
            if (nowMs >= scanNextCaptureMs) {
                scanNextCaptureMs += SCAN_FRAME_INTERVAL_MS
                val elapsedSec = (nowMs - scanStartMs) / 1000.0
                val copy = nv21.copyOf()
                ioScope.launch {
                    try {
                        val jpeg = nv21ToJpeg(copy, fw, fh)
                        scanFrames.add(ScanFrame(jpeg, elapsedSec))
                    } catch (e: Exception) {
                        Log.e(TAG, "scan frame capture error: $e")
                    }
                }
            }
        }

        // 탐지 OFF면 아무것도 안 함. 파이프라인 중에도 YOLO는 계속 돌려 박스가 객체를 추적하게 한다.
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
                            latestDetections = marked
                            // 파이프라인 중에도 항상 오버레이 갱신 → 박스가 객체를 계속 추적(배경=라이브 FPV).
                            Log.d(TAG, "[4] calling overlayView.setDetections(${marked.size})")
                            // 드론·폰 모두 fit-center(레터박스)로 그려지므로 실제 그려지는 영역 기준으로 박스를 맞춘다.
                            if (fh > 0) overlayView.setVideoAspect(fw.toFloat() / fh.toFloat())
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

    }

    // ── Flight menu (drone only) ───────────────────────────────────────────

    private fun showFlightMenu() {
        val popup = PopupMenu(this, btnMenu)
        popup.menu.add(0, 1, 0, "이륙").setIcon(R.drawable.ic_flight_takeoff)
        popup.menu.add(0, 2, 1, "착륙").setIcon(R.drawable.ic_flight_land)
        popup.menu.add(
            0, 3, 2,
            if (indoorModeEnabled) "실내 모드 끄기 (장애물 회피 켜기)" else "실내 모드 켜기 (장애물 회피 끄기)"
        )

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
                3 -> toggleIndoorMode()
            }
            true
        }
        // 메뉴 열려 있는 동안 버튼에 눌린(활성) 효과 유지, 닫히면 해제.
        btnMenu.isActivated = true
        popup.setOnDismissListener { btnMenu.isActivated = false }
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

    /** 실내 모드 토글: 장애물 회피 OFF + 비전 측위 ON ↔ 정상 복구. 켤 때만 충돌 위험 경고. */
    private fun toggleIndoorMode() {
        if (!droneConnected) {
            Toast.makeText(this, "드론이 연결되지 않았습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val ds = frameSource as? DroneFrameSource ?: return
        val target = !indoorModeEnabled
        val apply = {
            ds.setIndoorMode(target) { ok, err ->     // onResult: UI 스레드 보장
                if (ok) {
                    indoorModeEnabled = target
                    val msg = if (target) "실내 모드 ON (장애물 회피 꺼짐)" else "실내 모드 OFF (장애물 회피 켜짐)"
                    Log.d(TAG, "실내 모드 전환 성공: $msg")
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                } else {
                    // 에러 코드/설명을 그대로 노출 → 기종 제약(미지원)인지 다른 문제인지 구분 가능.
                    Log.e(TAG, "실내 모드 전환 실패: $err")
                    Toast.makeText(this, "실내 모드 전환 실패\n$err", Toast.LENGTH_LONG).show()
                }
            }
        }
        if (target) {
            AlertDialog.Builder(this)
                .setTitle("실내 모드 켜기")
                .setMessage("장애물 회피를 끄고 하방 비전 측위를 켭니다.\nGPS 없는 실내 테스트용이며, 야외에서는 충돌 위험이 있습니다.")
                .setPositiveButton("켜기") { _, _ -> apply() }
                .setNegativeButton("취소", null)
                .show()
        } else {
            apply()
        }
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
        btnDetect.isActivated = true    // 탐지 켜짐 → 버튼 네온 하늘색 활성 표시
    }

    private fun stopDetection() {
        isDetecting.set(false)
        btnDetect.isActivated = false   // 탐지 꺼짐 → 버튼 기본(검정) 복귀
        selectedTargets.clear()
        latestDetections = emptyList()
        overlayView.setDetections(emptyList())
    }

    // ── Tap handling ───────────────────────────────────────────────────────

    private fun handleTap(rawX: Float, rawY: Float) {
        val vw = overlayView.width.toFloat()
        val vh = overlayView.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        // 박스와 동일한 좌표계(영상 그려지는 영역 기준)로 역변환 → 레터박스 가로 어긋남 보정.
        val normX = overlayView.toNormX(rawX)
        val normY = overlayView.toNormY(rawY)

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
        else triggerFocus(normX.coerceIn(0f, 1f), normY.coerceIn(0f, 1f))
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

    /** nv21ToJpeg 변형: 긴 변을 [maxSide]px 로 clamp(축소만). VLM 전송용 저화질. */
    private fun nv21ToJpegMaxSide(nv21: ByteArray, width: Int, height: Int, maxSide: Int, quality: Int): ByteArray {
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val rawOut = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, rawOut)

        val bitmap = BitmapFactory.decodeByteArray(rawOut.toByteArray(), 0, rawOut.size())
        val longSide = maxOf(width, height)
        val scaled = if (longSide > maxSide) {
            val scale = maxSide.toFloat() / longSide
            Bitmap.createScaledBitmap(bitmap, (width * scale).toInt(), (height * scale).toInt(), true)
        } else {
            bitmap
        }

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)

        bitmap.recycle()
        if (scaled !== bitmap) scaled.recycle()

        return out.toByteArray()
    }

    // ── YOLO network ───────────────────────────────────────────────────────

    private fun sendFrame(jpegBytes: ByteArray): List<Detection> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", "frame.jpg", jpegBytes.toRequestBody("image/jpeg".toMediaType()))
            .withSession()
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

    /**
     * /capture 응답에서 drone_offset 파싱. 없거나 null 이면 null 반환(이동 안 함).
     * 촬영 로직과 독립 — 파싱 실패는 조용히 무시.
     */
    private fun parseDroneOffset(json: String?): DroneOffset? {
        if (json.isNullOrBlank()) return null
        return try {
            val root = JSONObject(json)
            if (!root.has("drone_offset") || root.isNull("drone_offset")) return null
            val o = root.getJSONObject("drone_offset")
            DroneOffset(
                dx = o.optDouble("dx", 0.0),
                dy = o.optDouble("dy", 0.0),
                dr = o.optDouble("dr", 0.0),
                drNorm = o.optDouble("dr_norm", 0.0),
                thetaDeg = o.optDouble("theta_deg", 0.0)
            )
        } catch (e: Exception) {
            Log.e(TAG, "drone_offset parse error: $e")
            null
        }
    }

    /**
     * /capture 응답에서 best.image_base64(최종구도 JPEG) 디코딩. best 없거나 이미지 없으면 null.
     * 촬영 로직과 독립 — 실패는 조용히 무시.
     */
    private fun parseBestImage(json: String?): ByteArray? {
        if (json.isNullOrBlank()) return null
        return try {
            val root = JSONObject(json)
            val best = root.optJSONObject("best") ?: return null
            val b64 = best.optString("image_base64", "").takeIf { it.isNotBlank() } ?: return null
            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "best image parse error: $e")
            null
        }
    }

    // ── Virtual Stick 스캔-정점 (이동+프레임+정점 복귀) ──────────────────────

    /** '스캔' 버튼: offset 방향으로 6초 등속 이동하며 프레임을 모아 /scan-peak 로 정점 탐색 후 복귀. */
    private fun onScanClicked() {
        if (!scanBusy.compareAndSet(false, true)) return
        val off = lastDroneOffset
        val target = if (scanIsDetail) detailTargetJpeg else bestImageJpeg
        val ds = frameSource as? DroneFrameSource
        val mover = droneMover
        if (off == null || target == null) {
            Toast.makeText(this, "스캔할 최종구도/방향이 없습니다 (촬영을 먼저 하세요)", Toast.LENGTH_SHORT).show()
            scanBusy.set(false)
            return
        }
        if (ds == null || mover == null) {
            scanBusy.set(false)
            return
        }
        setScanBusy(true)
        scanCancelled = false
        scanReachedPeak = false
        scanFrames.clear()
        scanStartMs = System.currentTimeMillis()
        scanNextCaptureMs = scanStartMs      // t=0 프레임부터 캡처
        capturing = true
        Log.d(TAG, "스캔 시작(${if (scanIsDetail) "세부" else "구도"}): 방향 dx=${off.dx} dy=${off.dy}, 목표 ${scanDurationMs / 1000.0}초")
        mover.moveScan(
            dx = off.dx,
            dy = off.dy,
            durationMs = scanDurationMs,
            connected = ds.isConnected(),
            flying = ds.isFlying(),
            gpsLevel = ds.gpsLevel()
        ) { moved ->
            capturing = false      // 이동 종료 → 프레임 수집 중단
            if (!moved || scanCancelled) {
                Log.d(TAG, "스캔 중단(moved=$moved, cancelled=$scanCancelled)")
                finishScan()
            } else {
                onScanMoveDone(off, target, ds, mover)
            }
        }
    }

    /** 스캔 이동 완료 → 프레임 묶음 + target 전송 → peak 받아 정점으로 복귀. */
    private fun onScanMoveDone(off: DroneOffset, target: ByteArray, ds: DroneFrameSource, mover: DroneMover) {
        val frames = synchronized(scanFrames) { scanFrames.sortedBy { it.elapsedSec } }
        Log.d(TAG, "스캔 종료: ${frames.size}프레임")
        ioScope.launch {
            val peakIndex = try { sendScanPeak(frames) } catch (e: Exception) {
                Log.e(TAG, "scan-peak error: $e"); null
            }
            withContext(Dispatchers.Main) {
                if (scanCancelled) { finishScan(); return@withContext }
                if (peakIndex == null || frames.isEmpty()) {
                    Toast.makeText(this@CameraActivity, "정점 탐색 실패", Toast.LENGTH_SHORT).show()
                    finishScan(); return@withContext
                }
                scanReachedPeak = true   // 정점 확보 → 구도 단계 성공(파이프라인 다음 단계 진행)
                val idx = peakIndex.coerceIn(0, frames.size - 1)
                val peakTime = frames.getOrNull(idx)?.elapsedSec ?: (idx * SCAN_FRAME_INTERVAL_S)
                val scanTimeS = scanDurationMs / 1000.0
                val returnS = (scanTimeS - peakTime).coerceAtLeast(0.0)
                val returnMs = (returnS * 1000).toLong()
                Log.d(TAG, "scan-peak peak_index=$peakIndex → 정점=${idx}번(peakTime=%.2fs=%.2fm), 되돌아갈 %.2fm/%.2fs"
                    .format(peakTime, SCAN_SPEED_LOG * peakTime, SCAN_SPEED_LOG * returnS, returnS))
                val meta = ScanResultMeta(
                    thetaDeg = off.thetaDeg,
                    peakIndex = peakIndex,
                    returnDist = SCAN_SPEED_LOG * returnS,
                    target = target
                )
                if (returnMs <= 0L) {
                    // 정점이 스캔 끝 부근 → 복귀 없이 현재 위치에서 검증 촬영.
                    Log.d(TAG, "정점=끝 부근, 복귀 없음 → 정점 검증 촬영")
                    requestScanResult(meta); return@withContext
                }
                // 반대 방향(-dx,-dy)으로 returnMs 등속 복귀 → 도착 후 검증 촬영.
                if (pipelineActive) Toast.makeText(this@CameraActivity, "구도 조정: 정점 이동", Toast.LENGTH_SHORT).show()
                mover.moveScan(
                    dx = -off.dx, dy = -off.dy, durationMs = returnMs,
                    connected = ds.isConnected(), flying = ds.isFlying(), gpsLevel = ds.gpsLevel()
                ) { _ ->
                    if (scanCancelled) finishScan() else requestScanResult(meta)
                }
            }
        }
    }

    /** 정점 도착 → 다음 프레임 1장을 잡아 /scan-result 로 보내도록 예약(메인 스레드). */
    private fun requestScanResult(meta: ScanResultMeta) {
        scanResultPending = meta
        // 프레임이 안 오면(피드 끊김) 2초 후 스캔을 풀어준다.
        mainHandler.removeCallbacks(scanResultTimeout)
        mainHandler.postDelayed(scanResultTimeout, 2000)
    }

    /** 스캔 종료 정리(성공/실패/취소 공통). 메인 스레드. */
    private fun finishScan() {
        val wasCancelled = scanCancelled   // 아래에서 리셋되기 전에 보관
        val wasDetail = scanIsDetail       // 세부 스캔이었는지(분기용)
        capturing = false
        scanResultPending = null
        mainHandler.removeCallbacks(scanResultTimeout)
        scanCancelled = false
        scanIsDetail = false
        scanBusy.set(false)
        setScanBusy(false)
        // 이동으로 offset 이 낡음 → 재촬영해야 다시 스캔 가능.
        lastDroneOffset = null
        setScanEnabled(false)
        if (pipelineActive) {
            when {
                wasCancelled -> { /* 비상정지 경로가 오버레이 정리까지 담당 → 여기선 아무것도 안 함 */ }
                scanReachedPeak && wasDetail -> {
                    // 세부(30cm) 스캔 완료 → 정제(4단계): 틸트 → 자동초점 → final-shot.
                    pipelineFinishRefine()
                }
                scanReachedPeak -> {
                    // 구도(2m) 스캔 완료 → 세부조정 시작(0.5m 후진 → detail-refine → 30cm 스캔).
                    setPipelineStage(2)
                    Toast.makeText(this, "세부 조정 시작", Toast.LENGTH_SHORT).show()
                    ioScope.launch { runDetailRefine() }
                }
                else -> pipelineAbort(if (wasDetail) "세부(스캔) 실패" else "구도(스캔) 실패")
            }
        } else if (droneConnected) {
            // 수동(legacy): 스캔 완료 → 세부조정 버튼 활성.
            setAdjustEnabled(true)
        }
    }

    /** 스캔 프레임들 + target(최종구도)을 /scan-peak 로 전송하고 peak_index 반환(<0/실패 시 null). */
    private fun sendScanPeak(frames: List<ScanFrame>): Int? {
        if (frames.isEmpty()) return null
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        frames.forEachIndexed { i, f ->
            builder.addFormDataPart("frames", "frame_%02d.jpg".format(i),
                f.jpeg.toRequestBody("image/jpeg".toMediaType()))
        }
        val request = Request.Builder().url("$SERVER_BASE/scan-peak").post(builder.withSession().build()).build()
        captureHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful || body.isNullOrBlank()) {
                Log.e(TAG, "scan-peak 실패 code=${response.code}")
                return null
            }
            return JSONObject(body).optInt("peak_index", -1).takeIf { it >= 0 }
        }
    }

    /**
     * 정점에서 검증 촬영 1장 + target + 메타(theta/peak_index/return_distance)를 /scan-result 로 전송.
     * 응답 saved 면 토스트. 성공/실패/예외 어느 쪽이든 finishScan 으로 스캔 종료.
     */
    private suspend fun sendScanResult(nv21: ByteArray, width: Int, height: Int, meta: ScanResultMeta) {
        try {
            // 검증 사진은 원본해상도(촬영과 동일 품질).
            val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, width, height), 90, out)
            val jpeg = out.toByteArray()

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "peak.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
                .addFormDataPart("theta_deg", meta.thetaDeg.toString())
                .addFormDataPart("peak_index", meta.peakIndex.toString())
                .addFormDataPart("return_distance", "%.3f".format(meta.returnDist))
                .withSession()
                .build()
            val request = Request.Builder().url("$SERVER_BASE/scan-result").post(body).build()
            val saved = captureHttpClient.newCall(request).execute().use { response ->
                val respBody = response.body?.string()
                if (response.isSuccessful && !respBody.isNullOrBlank())
                    try { JSONObject(respBody).optString("saved", "") } catch (e: Exception) { "" }
                else { Log.e(TAG, "scan-result 실패 code=${response.code}"); "" }
            }
            withContext(Dispatchers.Main) {
                if (saved.isNotBlank()) {
                    Log.d(TAG, "scan-result 저장됨: $saved")
                    Toast.makeText(this@CameraActivity, "정점 사진 저장됨", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@CameraActivity, "정점 사진 저장 실패", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "scan-result error: $e")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@CameraActivity, "정점 사진 전송 오류", Toast.LENGTH_SHORT).show()
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { finishScan() }
        }
    }

    /** 스캔 중 스캔/촬영 버튼 잠금. 메인 스레드. */
    private fun setScanBusy(busy: Boolean) {
        if (busy) {
            btnScan.isEnabled = false;    btnScan.alpha = 0.4f
            btnCapture.isEnabled = false; btnCapture.alpha = 0.4f
        } else {
            btnCapture.isEnabled = droneConnected; btnCapture.alpha = 1f
        }
    }

    /** 스캔 버튼 활성/비활성 토글 (비활성=회색). 메인 스레드. */
    private fun setScanEnabled(enabled: Boolean) {
        btnScan.isEnabled = enabled
        btnScan.alpha = if (enabled) 1f else 0.4f
    }

    // ── 세부조정 버튼 상태(레거시, drone only) ─────────────────────────────

    /** 세부조정 버튼 활성/비활성 토글 (비활성=회색). 메인 스레드. */
    private fun setAdjustEnabled(enabled: Boolean) {
        btnAdjust.isEnabled = enabled
        btnAdjust.alpha = if (enabled) 1f else 0.4f
    }

    /** 세부조정 진행 중 버튼 잠금. 메인 스레드. */
    private fun setAdjustBusy(busy: Boolean) {
        if (busy) {
            btnAdjust.isEnabled = false; btnAdjust.alpha = 0.4f
            btnScan.isEnabled = false;   btnScan.alpha = 0.4f
            btnCapture.isEnabled = false; btnCapture.alpha = 0.4f
        } else {
            btnCapture.isEnabled = droneConnected; btnCapture.alpha = 1f
            // 스캔/세부조정은 이동으로 offset 이 낡았으므로 자동 재활성하지 않음.
        }
    }

    /** 요청 시 다음 프레임 1장을 640 JPEG 로 받아온다(timeout 내 못 받으면 null). */
    private suspend fun grabCurrentJpeg(timeoutMs: Long = 2000): ByteArray? =
        withTimeoutOrNull(timeoutMs) {
            val def = CompletableDeferred<ByteArray>()
            adjustFrameReq = def
            def.await()
        }

    /** "짐벌 테스트" 버튼 → 이륙 없이 짐벌 pitch 검증 시퀀스 실행(로그로 명령/실제각 비교). */
    private fun onGimbalTestClicked() {
        if (!droneConnected) {
            Toast.makeText(this, "드론이 연결되지 않았습니다", Toast.LENGTH_SHORT).show()
            return
        }
        if (!gimbalTestBusy.compareAndSet(false, true)) return
        btnGimbalTest.isEnabled = false; btnGimbalTest.alpha = 0.4f
        Toast.makeText(this, "짐벌 테스트 시작 (로그 확인)", Toast.LENGTH_SHORT).show()
        ioScope.launch {
            try {
                gimbalTester.runPitchTest()
            } catch (e: Exception) {
                Log.e(TAG, "gimbal test error: $e")
            } finally {
                gimbalTestBusy.set(false)
                runOnUiThread {
                    btnGimbalTest.isEnabled = true; btnGimbalTest.alpha = 1f
                    Toast.makeText(this@CameraActivity, "짐벌 테스트 종료", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── 틸트 sweep (짐벌 연속 틸트 → 프레임 캡처 → /vlm-select) ──────────────

    /** /vlm-select 로 보낼 프레임 1장 + 캡처 순간 실제 짐벌 각도. */
    private data class TiltShot(val jpeg: ByteArray, val angle: Double)

    /** /vlm-select 응답. */
    private data class VlmResult(
        val selectedIndex: Int,
        val angle: Double,
        val reason: String,
        val imageBase64: String?
    )

    /** 틸트 버튼 활성/비활성 토글 (비활성=회색). 메인 스레드. */
    private fun setTiltEnabled(enabled: Boolean) {
        btnTilt.isEnabled = enabled
        btnTilt.alpha = if (enabled) 1f else 0.4f
    }

    /** 틸트 진행 중 자기 버튼 잠금. 메인 스레드. (짐벌만 움직이므로 최소 잠금) */
    private fun setTiltBusy(busy: Boolean) {
        btnTilt.isEnabled = !busy
        btnTilt.alpha = if (busy) 0.4f else 1f
    }

    /** 요청 시 다음 프레임 1장을 768 JPEG 로 받아온다(timeout 내 못 받으면 null). */
    private suspend fun grabTiltJpeg(timeoutMs: Long = 2000): ByteArray? =
        withTimeoutOrNull(timeoutMs) {
            val def = CompletableDeferred<ByteArray>()
            tiltFrameReq = def
            def.await()
        }

    /** "틸트" 버튼 → 짐벌 sweep 시작. 짐벌만 움직이므로 연결만 확인(비행 상태 무관). */
    private fun onTiltClicked() {
        if (!droneConnected) {
            Toast.makeText(this, "드론이 연결되지 않았습니다", Toast.LENGTH_SHORT).show()
            return
        }
        if (!tiltBusy.compareAndSet(false, true)) return
        tiltCancelled = false
        setTiltBusy(true)
        ioScope.launch { runTiltSweep() }
    }

    /**
     * 짐벌을 base+10° → base-10° 로 연속 틸트하며 프레임 캡처(실제 각도 태그) → /vlm-select →
     * 최종 사진 표시. 취소/성공/오류 공통으로 짐벌 base 복귀.
     */
    private suspend fun runTiltSweep() {
        val shots = mutableListOf<TiltShot>()
        var result: VlmResult? = null
        var base: Double? = null
        try {
            base = gimbalTester.readAttitude()?.pitch
            if (base == null) { showAdjustToast("짐벌 각도 읽기 실패"); return }

            val start = gimbalTester.clampPitch(base + TILT_AMPLITUDE_DEG)
            val end = gimbalTester.clampPitch(base - TILT_AMPLITUDE_DEG)
            val sweepDurationS = (TILT_NUM_FRAMES - 1) * TILT_FRAME_INTERVAL_MS / 1000.0
            Log.d(TAG, "틸트 sweep: base=%.1f°, %.1f~%.1f 연속, %d장 캡처(각도 태그), /vlm-select 전송"
                .format(base, start, end, TILT_NUM_FRAMES))
            showAdjustToast("틸트 시작 (base=%.0f°)".format(base))

            gimbalTester.moveToPitch(start, 1.0)      // 시작 위치로 이동
            delay(1200)                               // 정착 대기
            if (tiltCancelled) return

            gimbalTester.moveToPitch(end, sweepDurationS)   // 연속 sweep 시작(자율 이동)
            repeat(TILT_NUM_FRAMES) { i ->
                if (tiltCancelled) return@repeat
                val jpeg = grabTiltJpeg() ?: return@repeat
                val angle = gimbalTester.readAttitude()?.pitch ?: Double.NaN
                shots.add(TiltShot(jpeg, angle))
                Log.d(TAG, "틸트 프레임 ${i + 1}/$TILT_NUM_FRAMES: angle=%.1f°".format(angle))
                delay(TILT_FRAME_INTERVAL_MS)
            }
            if (tiltCancelled) return

            result = sendVlmSelect(shots)
            if (result != null) {
                Log.d(TAG, "최종 선택: index=${result.selectedIndex} angle=%.1f 이유=${result.reason}"
                    .format(result.angle))
            } else {
                Log.e(TAG, "vlm-select 결과 없음")
            }
        } catch (e: Exception) {
            Log.e(TAG, "tilt sweep error: $e")
        } finally {
            base?.let { gimbalTester.moveToPitch(it, 1.0) }   // 짐벌 base 복귀
            tiltFrameReq = null
            withContext(NonCancellable + Dispatchers.Main) {
                tiltBusy.set(false)
                setTiltBusy(false)
                val r = result
                if (r != null) {
                    showFinalPhoto(r)
                    Toast.makeText(this@CameraActivity,
                        "최종 사진 선택됨 (각도 %.0f°, %s)".format(r.angle, r.reason), Toast.LENGTH_LONG).show()
                } else if (!tiltCancelled) {
                    Toast.makeText(this@CameraActivity, "틸트 실패 — 서버 응답 없음", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 파이프라인용 짐벌 틸트 sweep: base±10° 연속 훑기 + 프레임마다 실제 각도 태그 → /tilt-peak →
     * 응답 peak_angle 로 짐벌 이동(최적각 유지). 성공=null, 실패=사유 문자열. 짐벌만 움직임.
     * 취소/오류 시 짐벌 base 복귀. (기존 runTiltSweep 패턴 재사용, 엔드포인트·후처리만 다름)
     */
    private suspend fun runTiltPeak(): String? {
        // ★ 이번 틸트 시작 — 이전 세션에서 남은 취소 플래그를 리셋한다.
        //   (onPause/비상정지가 tiltCancelled=true 로 두는데, 파이프라인 틸트는 리셋을 안 해서
        //    한 번 true 가 되면 sweep 만 하고 /tilt-peak 전에 "취소"로 빠지던 버그.)
        tiltCancelled = false
        val shots = mutableListOf<TiltShot>()
        var base: Double? = null
        try {
            base = gimbalTester.readAttitude()?.pitch
            if (base == null) {
                Log.e(TAG, "틸트 실패: 짐벌 각도 읽기 실패(readAttitude=null) — 짐벌 키 미지원/연결 확인")
                return "짐벌 각도 읽기 실패"
            }
            val start = gimbalTester.clampPitch(base + TILT_AMPLITUDE_DEG)
            val end = gimbalTester.clampPitch(base - TILT_AMPLITUDE_DEG)
            val sweepDurationS = (TILT_NUM_FRAMES - 1) * TILT_FRAME_INTERVAL_MS / 1000.0
            Log.d(TAG, "틸트 sweep: base=%.1f°, +10~-10 연속, %d장 캡처(실제 각도 태그)"
                .format(base, TILT_NUM_FRAMES))

            gimbalTester.moveToPitch(start, 1.0)?.let {         // 시작 위치(base+10)로 이동
                Log.e(TAG, "틸트 시작각 이동 실패: code=${it.errorCode()} desc=${it.description()}")
            }
            delay(1200)                               // 정착 대기
            if (tiltCancelled || adjustCancelled) return "취소"

            gimbalTester.moveToPitch(end, sweepDurationS)?.let {   // base-10 까지 연속 sweep(멈추지 않음)
                Log.e(TAG, "틸트 sweep 이동 실패: code=${it.errorCode()} desc=${it.description()}")
            }
            var frameFail = 0
            repeat(TILT_NUM_FRAMES) { i ->
                if (tiltCancelled || adjustCancelled) return@repeat
                val jpeg = grabTiltJpeg()
                if (jpeg == null) {
                    frameFail++
                    Log.w(TAG, "틸트 프레임 ${i + 1}/$TILT_NUM_FRAMES 캡처 실패(grabTiltJpeg=null)")
                    return@repeat
                }
                val angle = gimbalTester.readAttitude()?.pitch ?: Double.NaN   // 캡처 순간 실제 각도
                shots.add(TiltShot(jpeg, angle))
                Log.d(TAG, "틸트 프레임 ${i + 1}/$TILT_NUM_FRAMES: angle=%.1f°".format(angle))
                delay(TILT_FRAME_INTERVAL_MS)
            }
            if (tiltCancelled || adjustCancelled) return "취소"
            if (shots.isEmpty()) {
                Log.e(TAG, "틸트 실패: 프레임 캡처 0장(실패 $frameFail 회)")
                return "프레임 캡처 실패"
            }

            val peak = sendTiltPeak(shots) ?: return "서버 응답 없음"
            val target = gimbalTester.clampPitch(peak)
            Log.d(TAG, "최적 틸트: angle=%.1f° → 그 각도로 이동".format(target))
            showAdjustToast("최적 틸트 각도 %.0f도로 이동".format(target))
            gimbalTester.moveToPitch(target, 1.0)     // 최적각으로 이동
            delay(1000)                               // 정착(성공 → base 복귀 안 함, 최적각 유지)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "tilt-peak error: $e")
            return "오류: ${e.message}"
        } finally {
            tiltFrameReq = null
            // 취소/오류로 최적각까지 못 간 경우에만 base 복귀(성공 경로는 이미 최적각).
            if (tiltCancelled || adjustCancelled) base?.let { gimbalTester.moveToPitch(it, 1.0) }
        }
    }

    /** 프레임 N장 + 실제 각도 배열을 /tilt-peak 로 전송, 응답 peak_angle 반환(실패 시 null). */
    private fun sendTiltPeak(shots: List<TiltShot>): Double? {
        if (shots.isEmpty()) return null
        val b = MultipartBody.Builder().setType(MultipartBody.FORM)
        shots.forEachIndexed { i, s ->
            b.addFormDataPart("frames", "frame_%02d.jpg".format(i),
                s.jpeg.toRequestBody("image/jpeg".toMediaType()))
        }
        b.addFormDataPart("angles", JSONArray(shots.map { it.angle }).toString())
        val req = Request.Builder().url("$SERVER_BASE/tilt-peak").post(b.withSession().build()).build()
        captureHttpClient.newCall(req).execute().use { resp ->
            val s = resp.body?.string()
            if (!resp.isSuccessful || s.isNullOrBlank()) {
                Log.e(TAG, "tilt-peak 실패 code=${resp.code}")
                return null
            }
            val o = JSONObject(s)
            val peak = o.optDouble("peak_angle", Double.NaN)
            Log.d(TAG, "tilt-peak 응답: peak_angle=%.2f index=%d score=%.2f"
                .format(peak, o.optInt("peak_index", -1), o.optDouble("score", Double.NaN)))
            return if (peak.isNaN()) null else peak
        }
    }

    /** 프레임 N장 + 각도 리스트를 /vlm-select 로 전송(실패 시 null). */
    private fun sendVlmSelect(shots: List<TiltShot>): VlmResult? {
        if (shots.isEmpty()) return null
        val b = MultipartBody.Builder().setType(MultipartBody.FORM)
        shots.forEachIndexed { i, s ->
            b.addFormDataPart("frames", "frame_%02d.jpg".format(i),
                s.jpeg.toRequestBody("image/jpeg".toMediaType()))
        }
        b.addFormDataPart("angles", JSONArray(shots.map { it.angle }).toString())
        val req = Request.Builder().url("$SERVER_BASE/vlm-select").post(b.withSession().build()).build()
        captureHttpClient.newCall(req).execute().use { resp ->
            val s = resp.body?.string()
            if (!resp.isSuccessful || s.isNullOrBlank()) {
                Log.e(TAG, "vlm-select 실패 code=${resp.code}")
                return null
            }
            val o = JSONObject(s)
            return VlmResult(
                selectedIndex = o.optInt("selected_index", -1),
                angle = o.optDouble("angle", Double.NaN),
                reason = o.optString("reason", ""),
                imageBase64 = o.optString("image_base64", "").takeIf { it.isNotBlank() }
            )
        }
    }

    /** 최종 사진을 전체화면 오버레이로 표시(탭하면 닫힘). 메인 스레드. */
    private fun showFinalPhoto(r: VlmResult) {
        val bytes = r.imageBase64?.let {
            try { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
            catch (e: Exception) { Log.e(TAG, "final image decode error: $e"); null }
        }
        val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bmp == null) {
            Log.w(TAG, "최종 이미지 없음(image_base64 미포함) — 표시 생략")
            return
        }
        ivFinalPhoto.setImageBitmap(bmp)
        tvFinalCaption.text = "최종: 각도 %.0f° — %s".format(r.angle, r.reason)
        ivFinalPhoto.visibility = View.VISIBLE
        tvFinalCaption.visibility = View.VISIBLE
    }

    /** 세부조정 진행 토스트 참조. 다음 단계 토스트가 이전 걸 즉시 교체하도록 보관. */
    private var adjustToast: Toast? = null

    /**
     * 세부조정 진행 상황 토스트(백그라운드 루프 → 메인 스레드).
     * 이전 토스트를 cancel 하고 즉시 새 걸 띄워, 단계가 빨리 넘어가도 큐에서 대기하지 않고
     * 화면 토스트가 곧바로 현재 단계로 바뀌게 한다.
     */
    private fun showAdjustToast(msg: String) {
        runOnUiThread {
            adjustToast?.cancel()
            adjustToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }
        }
    }

    /** /detail-refine 응답. best_crop 없으면 bestCropNull=true. */
    private data class DetailRefine(
        val dx: Double,
        val dy: Double,
        val thetaDeg: Double,
        val targetImage: ByteArray?,   // 최종 crop(base64 디코딩). 스캔 SSIM target 용(서버가 session 으로 사용).
        val bestCropNull: Boolean
    )

    /**
     * 새 세부조정: 0.5m 후진 → /detail-refine 1회 → theta 방향 30cm 스캔(SSIM 정점).
     * finishScan 의 구도-완료 분기에서 ioScope 로 호출. 실패는 pipelineAbort.
     */
    private suspend fun runDetailRefine() {
        try {
            if (!pipelineActive || adjustCancelled) return
            // ① 0.5m 후진
            Log.d(TAG, "세부조정 ①: 0.5m 후진 시작")
            showAdjustToast("세부 조정: 0.5m 후진")
            val backedUp = moveBackupSuspend(DETAIL_BACKUP_M)
            Log.d(TAG, "세부조정 ①: 후진 결과 moved=$backedUp")
            if (!pipelineActive || adjustCancelled) return
            if (!backedUp) { pipelineAbortMain("세부 조정: 후진 실패"); return }
            delay(BACKUP_SETTLE_MS)   // 관성 안정화

            // ② 후진 후 프레임 → /detail-refine
            Log.d(TAG, "세부조정 ②: 프레임 grab → /detail-refine")
            showAdjustToast("세부 조정: 구도 분석 중")
            val jpeg = grabCurrentJpeg()
            if (jpeg == null) { pipelineAbortMain("세부 조정: 프레임 실패"); return }
            val dr = sendDetailRefine(jpeg)
            if (!pipelineActive || adjustCancelled) return
            if (dr == null) { pipelineAbortMain("세부 조정: 분석 실패"); return }
            if (dr.bestCropNull) { pipelineAbortMain("세부 조정: 후보 없음"); return }
            detailTargetJpeg = dr.targetImage   // 스캔 진입 게이트(서버는 session 의 target 으로 SSIM)
            if (detailTargetJpeg == null) { pipelineAbortMain("세부 조정: target 없음"); return }

            // ③ theta(dx,dy) 방향으로 30cm 스캔 → 정점 이동 (기존 스캔 재사용)
            Log.d(TAG, "세부조정 ③: 30cm 스캔 시작")
            withContext(Dispatchers.Main) {
                if (!pipelineActive || adjustCancelled) return@withContext
                showAdjustToast("세부 조정: 30cm 스캔")
                lastDroneOffset = DroneOffset(dr.dx, dr.dy, 0.0, 0.0, dr.thetaDeg)
                scanIsDetail = true
                scanDurationMs = DroneMover.SCAN_TIME_DETAIL_MS
                onScanClicked()   // 완료 시 finishScan 의 세부 분기 → pipelineFinishRefine(틸트+마무리)
            }
        } catch (e: Exception) {
            Log.e(TAG, "detail-refine error: $e")
            pipelineAbortMain("세부 조정: 오류(${e.message})")
        }
    }

    /** pipelineAbort 를 메인 스레드에서 안전 호출(백그라운드 코루틴용). */
    private suspend fun pipelineAbortMain(reason: String) {
        withContext(Dispatchers.Main) { if (pipelineActive) pipelineAbort(reason) }
    }

    /** moveBackup 콜백을 suspend 로 래핑. distanceM 만큼 후진, moved 반환. */
    private suspend fun moveBackupSuspend(distanceM: Double): Boolean =
        suspendCancellableCoroutine { cont ->
            val ds = frameSource as? DroneFrameSource
            val mover = droneMover
            if (ds == null || mover == null) { cont.resume(false); return@suspendCancellableCoroutine }
            mover.moveBackup(ds.isConnected(), ds.isFlying(), ds.gpsLevel(), distanceM) { moved ->
                if (cont.isActive) cont.resume(moved)
            }
        }

    /** 후진 후 프레임 + targets 를 /detail-refine 로 전송. dx/dy/theta_deg/target_image/best_crop 파싱. */
    private fun sendDetailRefine(jpeg: ByteArray): DetailRefine? {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", "detail.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            .addFormDataPart("targets", buildTargetsJson())
            .withSession()
            .build()
        val req = Request.Builder().url("$SERVER_BASE/detail-refine").post(body).build()
        captureHttpClient.newCall(req).execute().use { resp ->
            val s = resp.body?.string()
            if (!resp.isSuccessful || s.isNullOrBlank()) {
                Log.e(TAG, "detail-refine 실패 code=${resp.code}")
                return null
            }
            val o = JSONObject(s)
            val bestCropNull = !o.has("best_crop") || o.isNull("best_crop")
            val targetImg = o.optString("target_image", "").takeIf { it.isNotBlank() }?.let {
                try { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
                catch (e: Exception) { Log.e(TAG, "detail target_image 디코딩 실패: $e"); null }
            }
            Log.d(TAG, "detail-refine: dx=${o.optDouble("dx",0.0)} dy=${o.optDouble("dy",0.0)} theta=${o.optDouble("theta_deg",0.0)} best_crop=${!bestCropNull} target=${targetImg?.size ?: 0}B")
            return DetailRefine(
                dx = o.optDouble("dx", 0.0),
                dy = o.optDouble("dy", 0.0),
                thetaDeg = o.optDouble("theta_deg", 0.0),
                targetImage = targetImg,
                bestCropNull = bestCropNull
            )
        }
    }

    // ── Capture network ────────────────────────────────────────────────────

    /** 촬영 버튼 로딩 상태 토글 (메인 스레드에서 호출). */
    /** 촬영 버튼 → 1m 후진 → 안정화 대기 → captureRequested 로 다음 프레임 캡처. */
    private fun startBackupThenCapture() {
        captureBackupCancelled = false
        // 드론 모드가 아니거나 mover 없으면(폰 모드 등) 후진 없이 기존대로 즉시 캡처.
        val ds = frameSource as? DroneFrameSource
        val mover = droneMover
        if (mode != "drone" || ds == null || mover == null) {
            captureRequested.set(true)
            return
        }
        mover.moveBackup(
            connected = ds.isConnected(),
            flying = ds.isFlying(),
            gpsLevel = ds.gpsLevel()
        ) { moved ->                       // onResult: Main 스레드 보장
            if (!moved || captureBackupCancelled) {
                // 후진 차단/실패 또는 비상정지 → 촬영 취소, 버튼 복구.
                abortBackupCapture(if (moved) null else "후진할 수 없어 촬영을 취소했습니다")
            } else {
                // 후진 성공 → 관성 안정화 대기 후 다음 프레임 캡처.
                mainHandler.postDelayed({
                    if (captureBackupCancelled) abortBackupCapture(null)
                    else captureRequested.set(true)
                }, BACKUP_SETTLE_MS)
            }
        }
    }

    /** 후진→촬영 취소 시 버튼/로딩 상태 복구. msg 있으면 토스트. */
    private fun abortBackupCapture(msg: String?) {
        if (pipelineActive) { pipelineAbort(msg ?: "후진 실패"); return }
        setCaptureLoading(false)
        captureInFlight.set(false)
        if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ── 자동 파이프라인 오버레이 ───────────────────────────────────────────

    /** 촬영 시작 → 오버레이 표시 + ① 후진 전 최초 사진 캡처 → ② 그 다음 후진 시작. */
    private fun startPipeline() {
        pipelineActive = true
        // ★ 직전 실행에서 정지/중단으로 세팅된 취소 플래그를 반드시 초기화.
        //   안 하면 stale 한 adjustCancelled=true 때문에 runDetailRefine 첫 가드에서
        //   조용히 리턴 → 세부조정 단계에서 진행도 abort 도 없이 멈춘 것처럼 보인다.
        adjustCancelled = false
        scanCancelled = false
        tiltCancelled = false
        llPipelineEnd.visibility = View.GONE
        pipelineOverlay.visibility = View.VISIBLE
        setPipelineStage(0)
        // 배경은 뒤의 라이브 드론 FPV(sv_fpv)를 스크림 너머로 그대로 노출. 중단/메뉴는 최상단으로.
        btnEmergencyStop.bringToFront()
        btnMenu.bringToFront()
        // ★ 순서 보장: 최초 사진(후진 전 프레임)을 먼저 캡처·안내한 뒤에야 후진을 시작한다.
        //   서버 전송(/initial-shot)은 부가라 백그라운드로 돌려 후진을 막지 않는다.
        ioScope.launch {
            val jpeg = grabCurrentJpeg()   // 후진 전 현재 프레임
            withContext(Dispatchers.Main) {
                if (!pipelineActive) return@withContext
                Toast.makeText(
                    this@CameraActivity,
                    if (jpeg != null) "최초 사진 저장" else "최초 사진 캡처 실패",
                    Toast.LENGTH_SHORT
                ).show()
                Toast.makeText(this@CameraActivity, "위치 조정: 2m 후진 중", Toast.LENGTH_SHORT).show()
                startBackupThenCapture()   // 최초 사진 캡처 '후' 후진 시작
            }
            if (jpeg != null) sendInitialShot(jpeg)   // 서버 전송(부가) — 후진과 병행
            else Log.w(TAG, "initial-shot: 최초 프레임 획득 실패")
        }
    }

    /** 현재 단계 표시. 원형 노드 뷰가 active 노드 펄스 애니메이션을 처리한다. */
    private fun setPipelineStage(stage: Int) {
        pipelineStage = stage
        pipelineProgress.setStage(stage)
    }

    /**
     * 정제(placeholder) 종료: 중앙 자동초점 → 초점 맞은 최종 프레임 캡처 →
     * (부가) /final-shot 서버 전송 + 앨범 저장용 보관 → 닫기/저장 버튼 표시.
     * session_id 는 아직 유효(hidePipelineOverlay 전)한 이 시점에 전송한다.
     */
    private fun pipelineFinishRefine() {
        if (!pipelineActive) return
        // 세부 박스 점 애니는 여기서 멈추지 않는다 — 마무리 끝에 setPipelineStage(3)가 완료(✅)로 전환하며 정지.
        ioScope.launch {
            // 0. 정제(4단계): 짐벌 틸트 sweep + 최적각 이동. 실패해도 자동초점/저장은 계속.
            if (pipelineActive && !adjustCancelled) {
                showAdjustToast("정제: 틸트 sweep 중")
                val tiltFail = runTiltPeak()
                if (tiltFail != null) Log.w(TAG, "틸트 실패(무시하고 정제 계속): $tiltFail")
            }
            if (!pipelineActive || adjustCancelled) return@launch
            // 1. 화면 중앙 자동초점(best-effort) + 초점 안정화 대기.
            focusCenterAndSettle()
            // 2. 초점 맞은 최종 프레임 캡처 → 앨범 저장용 비트맵 + 서버 전송용 JPEG.
            val jpeg = grabCurrentJpeg()
            val bmp = jpeg?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
            // 3. 서버 /final-shot 전송(부가 — 실패해도 앨범 저장/닫기는 그대로 진행).
            val serverOk = if (jpeg != null) sendFinalShot(jpeg) else false
            withContext(Dispatchers.Main) {
                if (!pipelineActive) return@withContext
                pipelineResultBitmap = bmp
                setPipelineStage(3)   // 모든 단계 끝 → '완료' 박스 강조(여기서 처음 켜짐)
                Toast.makeText(
                    this@CameraActivity,
                    if (serverOk) "완료 — 서버 저장 완료" else "완료",
                    Toast.LENGTH_SHORT
                ).show()
                llPipelineEnd.visibility = View.VISIBLE
                llPipelineEnd.bringToFront()
            }
        }
    }

    /** 화면 중앙(0.5,0.5) 자동초점 후 초점 안정화까지 잠깐 대기. 실패해도 계속(best-effort). */
    private suspend fun focusCenterAndSettle() {
        if (mode == "drone") {
            try {
                val key = KeyTools.createCameraKey(
                    CameraKey.KeyCameraFocusTarget,
                    ComponentIndexType.LEFT_OR_MAIN,
                    CameraLensType.CAMERA_LENS_ZOOM
                )
                val done = CompletableDeferred<Boolean>()
                KeyManager.getInstance().setValue(
                    key,
                    DoublePoint2D(0.5, 0.5),
                    object : CommonCallbacks.CompletionCallback {
                        override fun onSuccess() {
                            Log.d(TAG, "중앙 자동초점 OK"); if (done.isActive) done.complete(true)
                        }
                        override fun onFailure(e: IDJIError) {
                            Log.w(TAG, "중앙 자동초점 실패(무시): $e"); if (done.isActive) done.complete(false)
                        }
                    }
                )
                withTimeoutOrNull(700) { done.await() }   // 초점 명령 수락 대기(최대 0.7초)
            } catch (e: Exception) {
                Log.w(TAG, "중앙 자동초점 오류(무시): $e")
            }
        }
        delay(500)   // 렌즈가 초점 잡을 시간(best-effort)
    }

    /** 최초(후진 전) 이미지 + session_id 를 /initial-shot 로 전송(부가 기능). 성공 여부 반환. */
    private fun sendInitialShot(jpeg: ByteArray): Boolean = try {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", "initial.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            .withSession()
            .build()
        val req = Request.Builder().url("$SERVER_BASE/initial-shot").post(body).build()
        captureHttpClient.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) { Log.d(TAG, "initial-shot 저장됨 (session=$sessionId)"); true }
            else { Log.e(TAG, "initial-shot 실패 code=${resp.code}"); false }
        }
    } catch (e: Exception) {
        Log.e(TAG, "initial-shot error: $e"); false
    }

    /** 최종 이미지 + session_id 를 /final-shot 로 전송(부가 기능). 성공 여부 반환. */
    private fun sendFinalShot(jpeg: ByteArray): Boolean = try {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", "final.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            .withSession()
            .build()
        val req = Request.Builder().url("$SERVER_BASE/final-shot").post(body).build()
        captureHttpClient.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) { Log.d(TAG, "final-shot 저장됨 (session=$sessionId)"); true }
            else { Log.e(TAG, "final-shot 실패 code=${resp.code}"); false }
        }
    } catch (e: Exception) {
        Log.e(TAG, "final-shot error: $e"); false
    }

    /** 오버레이 정리 + 촬영 버튼 재활성(닫기/저장/중단 공통). */
    private fun hidePipelineOverlay() {
        pipelineProgress.setStage(-1)   // 펄스 애니 정지 + 표시 제거
        pipelineOverlay.visibility = View.GONE
        llPipelineEnd.visibility = View.GONE
        pipelineResultBitmap = null
        pipelineStage = -1
        pipelineActive = false
        sessionId = ""
        captureInFlight.set(false)
        setCaptureLoading(false)
    }

    /** 스테이지 실패 → 파이프라인 중단(드론 안전정지 + 오버레이 해제). idempotent. */
    private fun pipelineAbort(reason: String) {
        if (!pipelineActive) return
        droneMover?.emergencyStop()
        scanCancelled = true; adjustCancelled = true; capturing = false
        Toast.makeText(this, "파이프라인 중단: $reason", Toast.LENGTH_LONG).show()
        hidePipelineOverlay()
    }

    /**
     * 중앙을 [zoom]배 확대한(= 중앙 1/zoom 영역을 잘라낸) 비트맵 반환. 종횡비 유지.
     * zoom<=1 이면 원본 그대로. 저장 시 중앙 크롭용.
     */
    private fun centerCropZoom(src: Bitmap, zoom: Float): Bitmap {
        if (zoom <= 1f) return src
        val cw = (src.width / zoom).toInt().coerceIn(1, src.width)
        val ch = (src.height / zoom).toInt().coerceIn(1, src.height)
        val x = (src.width - cw) / 2
        val y = (src.height - ch) / 2
        return Bitmap.createBitmap(src, x, y, cw, ch)
    }

    /** 종료 시점 화면 비트맵을 폰 앨범(Pictures/Thirds)에 저장. 성공 여부 반환. */
    private fun saveBitmapToGallery(bmp: Bitmap): Boolean {
        val name = "thirds_${sessionId.ifEmpty { genSessionId() }}.jpg"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Thirds")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                contentResolver.openOutputStream(uri)?.use {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)
                } ?: return false
                true
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.insertImage(contentResolver, bmp, name, "Thirds") != null
            }
        } catch (e: Exception) {
            Log.e(TAG, "gallery save error: $e"); false
        }
    }

    private fun setCaptureLoading(loading: Boolean) {
        if (loading) {
            btnCapture.isEnabled = false
            btnCapture.alpha = 0.4f          // 아이콘을 흐리게 → 스피너가 도드라지게
            pbCapture.visibility = View.VISIBLE
        } else {
            pbCapture.visibility = View.GONE
            btnCapture.alpha = 1f
            btnCapture.isEnabled = true
        }
    }

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
        // /capture 호출 시점(후진 완료 후) 구도 조정 시작을 알림. 파이프라인에서만.
        if (pipelineActive) withContext(Dispatchers.Main) {
            Toast.makeText(this@CameraActivity, "구도 조정 시작", Toast.LENGTH_SHORT).show()
        }
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
                .withSession()
                .build()

            val request = Request.Builder().url("$SERVER_BASE/capture").post(body).build()
            val response = captureHttpClient.newCall(request).execute()
            // 응답 본문에서 drone_offset 파싱(드론 모드에서만 의미). 파싱 실패해도 촬영 판정엔 영향 없음.
            val bodyStr = try { response.body?.string() } catch (e: Exception) { null }
            if (mode == "drone") {
                lastDroneOffset = parseDroneOffset(bodyStr)
                bestImageJpeg = parseBestImage(bodyStr)
                Log.d(TAG, "drone_offset=$lastDroneOffset, best=${bestImageJpeg?.size ?: 0}B")
            }
            withContext(Dispatchers.Main) {
                if (pipelineActive) {
                    // 자동 파이프라인: 위치(후진+촬영) 완료 → 구도(스캔)로 자동 진행.
                    if (response.isSuccessful && bestImageJpeg != null && lastDroneOffset != null) {
                        setPipelineStage(1)
                        Toast.makeText(this@CameraActivity, "구도 조정: 스캔 이동 중", Toast.LENGTH_SHORT).show()
                        scanIsDetail = false                          // 구도(2m) 스캔
                        scanDurationMs = DroneMover.SCAN_TIME_MS
                        onScanClicked()
                    } else {
                        pipelineAbort("촬영/구도 분석 실패")
                    }
                } else {
                    // 수동/폰(legacy): 저장 토스트 + 스캔 버튼 활성.
                    val msg = if (response.isSuccessful) "촬영 저장됨" else "촬영 실패 (${response.code})"
                    Toast.makeText(this@CameraActivity, msg, Toast.LENGTH_SHORT).show()
                    if (mode == "drone" && !scanBusy.get()) {
                        setScanEnabled(bestImageJpeg != null && lastDroneOffset != null)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture error: $e")
            withContext(Dispatchers.Main) {
                if (pipelineActive) pipelineAbort("촬영 전송 오류")
                else Toast.makeText(this@CameraActivity, "촬영 전송 오류", Toast.LENGTH_SHORT).show()
            }
        } finally {
            // 성공/실패/타임아웃 어느 쪽이든 버튼 로딩 해제. 세션 중엔 captureInFlight 를 계속 잠가둔다.
            withContext(NonCancellable + Dispatchers.Main) {
                setCaptureLoading(false)
                if (!pipelineActive) captureInFlight.set(false)
            }
        }
    }

}

/** 스캔 중 캡처한 프레임 1장 + 캡처 경과초. */
data class ScanFrame(val jpeg: ByteArray, val elapsedSec: Double)

/** 정점 검증 촬영(/scan-result)에 함께 보낼 메타 + target(최종구도). */
data class ScanResultMeta(
    val thetaDeg: Double,
    val peakIndex: Int,
    val returnDist: Double,
    val target: ByteArray
)

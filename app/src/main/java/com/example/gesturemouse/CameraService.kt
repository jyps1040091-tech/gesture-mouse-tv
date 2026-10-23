package com.example.gesturemouse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 前景相機服務：用前鏡頭（電視則用外接鏡頭）持續抓畫面 → MediaPipe 手部關鍵點 → GestureEngine。
 * Android 11 起背景無法使用相機，所以一定要是前景服務（type=camera），會常駐一則通知。
 */
class CameraService : LifecycleService() {

    companion object {
        private const val TAG = "GestureMouse"
        private const val CHANNEL = "gesture_mouse"
        private const val NOTIF_ID = 1
        const val ACTION_TOGGLE = "com.example.gesturemouse.TOGGLE"
        const val ACTION_STOP = "com.example.gesturemouse.STOP"
        private const val MODEL = "hand_landmarker.task"

        @Volatile var running = false
            private set
    }

    private lateinit var executor: ExecutorService
    private var landmarker: HandLandmarker? = null
    private var engine: GestureEngine? = null
    private var provider: ProcessCameraProvider? = null
    private var lastTs = 0L
    private var started = false

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_TOGGLE -> { engine?.requestToggle(); return START_NOT_STICKY }
        }
        if (started) return START_NOT_STICKY
        started = true

        // 必須在收到 startForegroundService 後 5 秒內呼叫
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        val svc = GestureAccessibilityService.instance
        if (svc == null) {
            toast("請先在「設定 → 無障礙」開啟手勢滑鼠")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            landmarker = createLandmarker()
        } catch (e: Exception) {
            Log.e(TAG, "載入手部模型失敗", e)
            toast("找不到 assets/$MODEL，請照 README 下載模型")
            stopSelf()
            return START_NOT_STICKY
        }
        val size = svc.screenSize()
        engine = GestureEngine(size.x, size.y, svc)
        bindCamera()
        running = true
        return START_NOT_STICKY
    }

    private fun createLandmarker(): HandLandmarker {
        val base = BaseOptions.builder()
            .setModelAssetPath(MODEL)
            // .setDelegate(Delegate.GPU)  // 想更省電 / 更快可以試試，部分機型不穩
            .build()
        val opts = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setNumHands(2)
            .setRunningMode(RunningMode.VIDEO)
            .setMinHandDetectionConfidence(0.6f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        return HandLandmarker.createFromOptions(this, opts)
    }

    /** 手機用前鏡頭；電視或盒子沒有前鏡頭時，改用外接 USB 鏡頭或後鏡頭。 */
    private fun pickSelector(p: ProcessCameraProvider): CameraSelector {
        val candidates = listOf(
            CameraSelector.DEFAULT_FRONT_CAMERA,
            CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_EXTERNAL).build(),
            CameraSelector.DEFAULT_BACK_CAMERA,
        )
        val names = listOf("前鏡頭", "外接鏡頭", "後鏡頭")
        for ((i, c) in candidates.withIndex()) {
            try {
                if (p.hasCamera(c)) {
                    Log.i(TAG, "使用鏡頭：${names[i]}")
                    return c
                }
            } catch (e: Exception) {
                Log.w(TAG, "檢查鏡頭失敗", e)
            }
        }
        // 都不符合就不限制方向，用系統第一個鏡頭
        Log.i(TAG, "使用鏡頭：系統第一個")
        return CameraSelector.Builder().build()
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(640, 480),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                )
                            ).build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(executor, ::analyze)
                p.unbindAll()
                p.bindToLifecycle(this, pickSelector(p), analysis)
            } catch (e: Exception) {
                Log.e(TAG, "開啟鏡頭失敗", e)
                toast("開啟鏡頭失敗：${e.message}")
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val lmk = landmarker ?: return
            val eng = engine ?: return
            val rot = proxy.imageInfo.rotationDegrees
            var bmp = proxy.toBitmap()
            if (rot != 0) {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val w = bmp.width.toFloat()
            val h = bmp.height.toFloat()

            var ts = SystemClock.uptimeMillis()
            if (ts <= lastTs) ts = lastTs + 1  // VIDEO 模式時間戳必須遞增
            lastTs = ts

            val result = lmk.detectForVideo(BitmapImageBuilder(bmp).build(), ts)
            // 前鏡頭畫面沒有鏡像，這裡手動左右翻轉（1 - x），手往右移游標才會往右
            val hands = result.landmarks().map { pts ->
                Array(21) { i -> Lm((1f - pts[i].x()) * w, pts[i].y() * h) }
            }
            eng.process(hands, w, h, System.nanoTime() / 1e9)
        } catch (e: Exception) {
            Log.e(TAG, "分析失敗", e)
        } finally {
            proxy.close()
        }
    }

    // ================= 通知 =================

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "手勢滑鼠", NotificationManager.IMPORTANCE_LOW)
            )
        }
        fun action(a: String, code: Int): PendingIntent = PendingIntent.getService(
            this, code, Intent(this, CameraService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("手勢滑鼠執行中")
            .setContentText("雙手攤開 1 秒＝啟動 / 停用（也可按下方按鈕）")
            .setOngoing(true)
            .addAction(0, "啟動 / 停用", action(ACTION_TOGGLE, 1))
            .addAction(0, "結束", action(ACTION_STOP, 2))
            .build()
    }

    private fun toast(msg: String) {
        android.os.Handler(mainLooper).post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    override fun onDestroy() {
        running = false
        try { provider?.unbindAll() } catch (_: Exception) {}
        engine?.release()
        GestureAccessibilityService.instance?.cursor(0f, 0f, false, false)
        executor.shutdown()
        try { landmarker?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}

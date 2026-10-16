package ai.neuroshield.app.video

import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import ai.neuroshield.app.MainActivity
import ai.neuroshield.app.NeuroShieldApp
import ai.neuroshield.app.R
import ai.neuroshield.app.alert.Warning
import ai.neuroshield.app.alert.Warnings
import ai.neuroshield.app.app
import ai.neuroshield.app.core.RiskLevel
import ai.neuroshield.app.core.ScreenFace
import ai.neuroshield.app.core.VideoCallCheck
import ai.neuroshield.app.core.VideoVerdict
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.flow.MutableStateFlow

data class VideoUi(val running: Boolean = false, val seconds: Int = 0, val faceFrames: Int = 0, val frames: Int = 0,
                   val result: VideoVerdict? = null, val message: String? = null)

object VideoState { val ui = MutableStateFlow(VideoUi()) }

/**
 * Looks at the SCREEN during a video call (Android screen capture, the user confirms it every time) and judges
 * whether the other person's face behaves like a live person. Frames are analysed in memory by ML Kit on the phone
 * and never saved. Call audio is not accessible this way (Android excludes call audio from screen capture).
 */
class VideoCheckService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private val frames: MutableList<ScreenFace> = java.util.Collections.synchronizedList(mutableListOf())
    private var started = 0L
    private var busy = false
    private var done = false
    private val detector by lazy {
        FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.1f).build())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { finish("Остановлено"); return START_NOT_STICKY }
        val open = PendingIntent.getActivity(this, 21, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "protection"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, NeuroShieldApp.CH_LISTEN).setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("NeuroShield проверяет видеозвонок").setContentText("Анализ лица на экране, ~30 секунд. Кадры не сохраняются")
            .setOngoing(true).setContentIntent(open).build()
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (e: Exception) { finish("Android не разрешил запуск: ${e.message}"); return START_NOT_STICKY }
        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (code != Activity.RESULT_OK || data == null) { finish("Запись экрана не разрешена"); return START_NOT_STICKY }
        try { begin(code, data) } catch (e: Exception) { finish("Не удалось начать запись экрана: ${e.message}") }
        return START_NOT_STICKY
    }

    private fun begin(code: Int, data: Intent) {
        val t = HandlerThread("ns-video").also { it.start() }; thread = t
        val h = Handler(t.looper)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("нет доступа")
        projection = p
        p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { finish(null) } }, h)
        val dm = resources.displayMetrics
        val w = 480
        val hgt = (dm.heightPixels * w.toFloat() / dm.widthPixels).toInt().coerceAtLeast(320)
        val r = ImageReader.newInstance(w, hgt, PixelFormat.RGBA_8888, 2); reader = r
        display = p.createVirtualDisplay("neuroshield-video-check", w, hgt, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h)
        started = System.currentTimeMillis()
        VideoState.ui.value = VideoUi(running = true, message = "Смотрю на лицо собеседника…")
        // A virtual display only produces a new image when the screen changes. Sample on a fixed clock instead and
        // re-use the last picture when nothing changed: an unchanged screen IS a frozen picture.
        var lastBmp: Bitmap? = null
        val tick = object : Runnable {
            override fun run() {
                if (done) return
                val now = System.currentTimeMillis()
                r.acquireLatestImage()?.let { img ->
                    try {
                        val plane = img.planes[0]
                        val rowPx = plane.rowStride / plane.pixelStride
                        val full = Bitmap.createBitmap(rowPx, img.height, Bitmap.Config.ARGB_8888)
                        full.copyPixelsFromBuffer(plane.buffer)
                        lastBmp = if (rowPx != w) Bitmap.createBitmap(full, 0, 0, w, hgt) else full
                    } finally { img.close() }
                }
                val bmp = lastBmp
                if (bmp != null && !busy) {
                    busy = true
                    detector.process(InputImage.fromBitmap(bmp, 0))
                        .addOnSuccessListener { list ->
                            val big = list.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                            val area = big?.let { it.boundingBox.width().toFloat() * it.boundingBox.height() / (w * hgt) } ?: 0f
                            frames += ScreenFace(now - started, list.size, area,
                                (big?.boundingBox?.exactCenterX() ?: 0f) / w, (big?.boundingBox?.exactCenterY() ?: 0f) / hgt,
                                big?.headEulerAngleY ?: 0f, big?.leftEyeOpenProbability, big?.rightEyeOpenProbability)
                            VideoState.ui.value = VideoState.ui.value.copy(seconds = ((now - started) / 1000).toInt(), frames = frames.size,
                                faceFrames = frames.count { it.faces > 0 && it.areaFraction >= 0.02f })
                        }
                        .addOnCompleteListener { busy = false }
                }
                if (now - started >= DURATION_MS) conclude() else h.postDelayed(this, 333)
            }
        }
        h.postDelayed(tick, 500)
        // even if the screen does not change (no new frames), finish on time
        h.postDelayed({ conclude() }, DURATION_MS + 3000)
    }

    @Synchronized private fun conclude() {
        if (done) return
        done = true
        val v = VideoCallCheck.evaluate(synchronized(frames) { frames.toList() })
        VideoState.ui.value = VideoState.ui.value.copy(running = false, result = v, message = null)
        val p = app.prefs
        Warnings.show(this, Warning(level = v.level, score = v.score, source = "video_call", number = p.voipNumber,
            title = "Видеозвонок: ${v.title.replaceFirstChar { it.lowercase() }}", reasons = v.reasons, advice = v.advice))
        finish(null)
    }

    private fun finish(message: String?) {
        if (message != null) VideoState.ui.value = VideoState.ui.value.copy(running = false, message = message)
        else if (VideoState.ui.value.running) VideoState.ui.value = VideoState.ui.value.copy(running = false)
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        display = null; reader = null; projection = null
        thread?.quitSafely(); thread = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { try { detector.close() } catch (_: Exception) {}; super.onDestroy() }

    companion object {
        const val NOTIF_ID = 2002
        const val DURATION_MS = 30_000L
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "ai.neuroshield.STOP_VIDEO"

        fun start(ctx: Context, code: Int, data: Intent) {
            VideoState.ui.value = VideoUi(running = true, message = "Запуск…")
            ctx.startForegroundService(Intent(ctx, VideoCheckService::class.java).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data))
        }
    }
}

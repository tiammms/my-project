package ai.neuroshield.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.neuroshield.app.Perms
import ai.neuroshield.app.app
import ai.neuroshield.app.core.FaceFrame
import ai.neuroshield.app.core.LivenessSession
import ai.neuroshield.app.core.LivenessState
import ai.neuroshield.app.core.RiskLevel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors

/**
 * Liveness check with the front camera: random head turns + a blink, judged by Google ML Kit face detection
 * running on the phone (bundled model, no internet). It confirms that a live person is in front of THIS phone;
 * it does not identify who the person is (no face recognition model on the device).
 */
@Composable
fun FaceScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var camOk by remember { mutableStateOf(Perms.camera(ctx)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camOk = it }
    var session by remember { mutableStateOf<LivenessSession?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var yaw by remember { mutableStateOf(0f) }
    var faces by remember { mutableIntStateOf(0) }
    var camError by remember { mutableStateOf<String?>(
        if (!ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_FRONT)) "На этом устройстве нет фронтальной камеры" else null) }

    Page("Проверка лица", "Подтверждение, что перед телефоном живой человек", onBack) {
        SectionCard(sub = "Когда пригодится: перед важным действием в приложении, если кто-то просит «подтвердить личность» по телефону — " +
            "проверку проходите только сами, в своём приложении. Фото, видео и кадры не сохраняются.") {}
        if (camError != null) {
            Verdict(RiskLevel.MEDIUM, "Камера недоступна", camError)
        } else if (!camOk) {
            Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text("Разрешить камеру") }
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
                CameraWithFaces(onError = { camError = it }, onFrame = { f ->
                    yaw = f.yawDeg; faces = f.faces
                    val s = session
                    if (s != null && s.state == LivenessState.RUNNING) { s.onFrame(f, System.currentTimeMillis()); tick++ }
                })
                val s = session
                val frameTick = tick   // re-read state so the overlay follows each analysed frame
                if (s != null && s.state == LivenessState.RUNNING) {
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC0B1016)).padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.current?.arrow ?: "", fontSize = 44.sp, color = NS.teal)
                        Text(s.current?.instruction ?: "", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = NS.text, textAlign = TextAlign.Center)
                        Text("Шаг ${s.index + 1} из ${s.steps.size}" + if (frameTick < 0) "" else "", color = NS.muted, fontSize = 12.sp)
                    }
                }
            }
            Text("Лиц в кадре: $faces · поворот головы: ${"%.0f".format(yaw)}°", color = NS.muted, fontSize = 12.sp)
            val s = session
            if (s == null || s.state != LivenessState.RUNNING) {
                Button(onClick = { session = LivenessSession(); tick++ }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (s == null) "Начать проверку" else "Пройти ещё раз")
                }
            } else OutlinedButton(onClick = { session = null }, modifier = Modifier.fillMaxWidth()) { Text("Отменить") }
            if (s != null && s.state != LivenessState.RUNNING) {
                val passed = s.state == LivenessState.PASSED
                Verdict(if (passed) RiskLevel.LOW else RiskLevel.HIGH,
                    if (passed) "Живой человек подтверждён" else "Проверка не пройдена",
                    if (passed) "Повороты и моргание выполнены в случайном порядке, который нельзя знать заранее" else s.failure)
                s.log.forEach { Bullet(it) }
                Text("Ограничения: не защищает от объёмной маски и от дипфейка, подменяющего камеру. Не определяет, кто именно перед камерой.",
                    color = NS.muted, fontSize = 12.sp)
                if (!passed) Text("Если вы держали перед камерой фото или экран — так и должно быть: проверка их не пропускает.", color = NS.text2, fontSize = 13.sp)
                LaunchedEffect(s) {
                    ctx.app.alerts.add("face", null, if (passed) RiskLevel.LOW else RiskLevel.HIGH, if (passed) 5.0 else 85.0,
                        if (passed) "Проверка лица пройдена" else "Проверка лица не пройдена", s.log.takeLast(4), null)
                }
            }
        }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraWithFaces(onError: (String) -> Unit, onFrame: (FaceFrame) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val detector = remember {
        FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.25f)
            .enableTracking()
            .build())
    }
    val main = remember { ContextCompat.getMainExecutor(ctx) }
    DisposableEffect(Unit) { onDispose { detector.close(); executor.shutdown() } }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
        val view = PreviewView(c).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        val providerFuture = ProcessCameraProvider.getInstance(c)
        providerFuture.addListener({
            val provider = try { providerFuture.get() } catch (e: Exception) {
                onError("Камера не запустилась: ${e.cause?.message ?: e.message}"); return@addListener
            }
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null) { proxy.close(); return@setAnalyzer }
                val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                detector.process(img)
                    .addOnSuccessListener { list ->
                        val f = list.firstOrNull()
                        // ML Kit: positive Y = face turned toward the image's right side, which for the
                        // (un-mirrored) analysis frame of the front camera is the person's LEFT.
                        val frame = FaceFrame(list.size, f?.trackingId, f?.headEulerAngleY ?: 0f,
                            f?.leftEyeOpenProbability, f?.rightEyeOpenProbability)
                        main.execute { onFrame(frame) }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } catch (e: Exception) { onError("Камера не запустилась: ${e.message}") }
        }, main)
        view
    })
}

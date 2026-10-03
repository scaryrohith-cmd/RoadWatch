package com.example.roadwatch

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.Locale
import java.util.concurrent.Executors

@OptIn(ExperimentalGetImage::class)
class MainActivity : AppCompatActivity() {

    private enum class Level { CLEAR, CAUTION, DANGER }

    private lateinit var previewView: PreviewView
    private lateinit var banner: TextView
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var lastSpokenAt = 0L

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .build()
    )

    private val history = HashMap<Int, ArrayDeque<Pair<Long, Float>>>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else banner.text = "Camera permission needed"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        banner = findViewById(R.id.banner)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.getDefault()
                tts.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                ttsReady = true
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, ::analyze) }
            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) { proxy.close(); return }
        val rotation = proxy.imageInfo.rotationDegrees
        val w = if (rotation % 180 == 0) proxy.width else proxy.height
        val h = if (rotation % 180 == 0) proxy.height else proxy.width
        detector.process(InputImage.fromMediaImage(media, rotation))
            .addOnSuccessListener { objects -> evaluate(objects, w, h) }
            .addOnCompleteListener { proxy.close() }
    }

    private fun evaluate(objects: List<DetectedObject>, w: Int, h: Int) {
        val now = SystemClock.elapsedRealtime()
        var worst = Level.CLEAR
        val seen = HashSet<Int>()

        for (obj in objects) {
            val id = obj.trackingId ?: continue
            seen.add(id)
            val box = obj.boundingBox
            val heightFrac = box.height().toFloat() / h
            val centerX = box.centerX().toFloat() / w

            val q = history.getOrPut(id) { ArrayDeque() }
            q.addLast(now to heightFrac)
            while (q.size > 1 && now - q.first().first > 1000) q.removeFirst()

            if (centerX !in 0.25f..0.75f) continue

            var level = Level.CLEAR
            if (heightFrac > 0.45f) level = Level.DANGER
            if (q.size >= 3) {
                val (t0, h0) = q.first()
                val dt = (now - t0) / 1000f
                val rate = (heightFrac - h0) / dt
                if (dt > 0.3f && rate > 0.02f) {
                    val ttc = heightFrac / rate
                    level = maxOf(
                        level,
                        when {
                            ttc < 2.5f -> Level.DANGER
                            ttc < 4.0f -> Level.CAUTION
                            else -> Level.CLEAR
                        }
                    )
                }
            }
            if (level > worst) worst = level
        }
        history.keys.retainAll(seen)
        showLevel(worst, now)
    }

    private fun showLevel(level: Level, now: Long) {
        when (level) {
            Level.CLEAR -> {
                banner.text = "Road clear"
                banner.setBackgroundColor(0xCC1B7F3B.toInt())
            }
            Level.CAUTION -> {
                banner.text = "CAUTION: closing in"
                banner.setBackgroundColor(0xCCC77700.toInt())
                speak("Caution, object ahead", now, 4000)
            }
            Level.DANGER -> {
                banner.text = "BRAKE! COLLISION RISK"
                banner.setBackgroundColor(0xCCC62828.toInt())
                speak("Brake! Brake!", now, 2000)
            }
        }
    }

    private fun speak(text: String, now: Long, minGapMs: Long) {
        if (!ttsReady || now - lastSpokenAt < minGapMs) return
        lastSpokenAt = now
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "roadwatch")
    }

    override fun onDestroy() {
        super.onDestroy()
        detector.close()
        tts.shutdown()
        analysisExecutor.shutdown()
    }
}

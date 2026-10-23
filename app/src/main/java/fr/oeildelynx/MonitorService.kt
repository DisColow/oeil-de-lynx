package fr.oeildelynx

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

class MonitorService : LifecycleService() {
    private lateinit var executor: ExecutorService
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: androidx.camera.video.Recording? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var lastMotionAt = 0L
    private var lastSoundAt = 0L
    private var previousLuma: ByteArray? = null
    private var lastFrameAt = 0L
    private var recordingSince = 0L
    private var maxClipMs = 10 * 60_000L
    private var useFrontCamera = false

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Initialisation de la caméra"))
        val preferences = getSharedPreferences("lynx", Context.MODE_PRIVATE)
        maxClipMs = preferences.getInt("max_clip_ms", 600_000).toLong()
        useFrontCamera = preferences.getBoolean("front_camera", false)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { stopSelf(); return }
        ProcessCameraProvider.getInstance(this).also { future ->
            future.addListener({ bindCamera(future.get()) }, ContextCompat.getMainExecutor(this))
        }
        startSoundDetection()
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.SD)).build()
        videoCapture = VideoCapture.withOutput(recorder)
        val preview = Preview.Builder().build()
        activePreview = preview
        preview.setSurfaceProvider(previewSurfaceProvider)
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(320, 240)).build()
        analysis.setAnalyzer(executor) { image -> analyzeFrame(image) }
        try {
            provider.unbindAll()
            val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            provider.bindToLifecycle(this, selector, videoCapture, analysis, preview)
        } catch (_: Exception) { stopSelf() }
    }

    private fun analyzeFrame(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        try {
            // Analyze at most one low-resolution frame every 700 ms to reduce CPU use.
            if (now - lastFrameAt < 700) return
            lastFrameAt = now
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer.duplicate(); val stride = plane.rowStride
            val pixelStride = plane.pixelStride
            val samples = ArrayList<Byte>(900)
            var changed = 0; var difference = 0L
            val previous = previousLuma
            for (y in 4 until image.height step 8) for (x in 4 until image.width step 8) {
                val index = y * stride + x * pixelStride
                if (index < buffer.limit()) {
                    val value = buffer.get(index)
                    if (previous != null && samples.size < previous.size) {
                        val delta = abs((value.toInt() and 0xff) - (previous[samples.size].toInt() and 0xff))
                        difference += delta
                        if (delta > 24) changed++
                    }
                    samples.add(value)
                }
            }
            val compared = minOf(samples.size, previous?.size ?: 0)
            if (compared > 0 && difference.toDouble() / compared > 10.0 && changed.toDouble() / compared > 0.12) {
                lastMotionAt = now
                triggerRecording("Mouvement")
            }
            previousLuma = samples.toByteArray()
            maybeStopRecording(now)
        } finally { image.close() }
    }

    private fun startSoundDetection() {
        if (recording != null || audioRecord != null) return
        val min = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return
        val rec = try { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096)) } catch (_: Exception) { return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
        audioRecord = rec
        rec.startRecording()
        audioThread = Thread {
            val samples = ShortArray(1024)
            while (!Thread.currentThread().isInterrupted && audioRecord === rec && rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val n = try { rec.read(samples, 0, samples.size) } catch (_: Exception) { -1 }
                if (n > 0) {
                    var energy = 0.0
                    for (i in 0 until n) { val v = samples[i].toDouble() / 32768.0; energy += v * v }
                    val rms = sqrt(energy / n)
                    if (rms > 0.055) { lastSoundAt = SystemClock.elapsedRealtime(); triggerRecording("Son") }
                }
            }
        }.apply { name = "lynx-audio-detector"; start() }
    }

    @Synchronized private fun triggerRecording(reason: String) {
        val capture = videoCapture ?: return
        if (recording != null) return
        stopSoundDetection()
        val dir = File(getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "Evenements").apply { mkdirs() }
        val output = File(dir, "lynx_${System.currentTimeMillis()}.mp4")
        recordingSince = SystemClock.elapsedRealtime()
        recording = capture.output.prepareRecording(this, FileOutputOptions.Builder(output).build())
            .withAudioEnabled().start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    synchronized(this) { recording = null; startSoundDetection() }
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("Actif · dernier clip: ${output.name}"))
                } else if (event is VideoRecordEvent.Start) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("Enregistrement · $reason"))
                }
            }
    }

    private fun maybeStopRecording(now: Long) {
        if (recording == null || now - recordingSince < MIN_CLIP_MS) return
        maxClipMs = getSharedPreferences("lynx", Context.MODE_PRIVATE).getInt("max_clip_ms", 600_000).toLong()
        val lastActivity = maxOf(lastMotionAt, lastSoundAt)
        if (now - lastActivity > QUIET_PERIOD_MS) recording?.stop()
        // Cap individual clips so a persistent trigger cannot fill storage indefinitely.
        if (now - recordingSince > maxClipMs) recording?.stop()
    }

    private fun stopSoundDetection() {
        val old = audioRecord ?: return
        audioRecord = null
        try { old.stop() } catch (_: Exception) { }
        old.release()
        audioThread?.interrupt(); audioThread = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL_ID, "Surveillance", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(message: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("Œil de Lynx").setContentText(message)
        .setOngoing(true).setOnlyAlertOnce(true).build()

    override fun onDestroy() {
        recording?.stop(); stopSoundDetection()
        activePreview = null
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Exception) { }
        if (::executor.isInitialized) executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FRONT_CAMERA = "front_camera"
        @Volatile var previewSurfaceProvider: Preview.SurfaceProvider? = null
        @Volatile private var activePreview: Preview? = null
        fun updatePreviewSurface(provider: Preview.SurfaceProvider?) {
            previewSurfaceProvider = provider
            activePreview?.setSurfaceProvider(provider)
        }
        private const val CHANNEL_ID = "lynx-surveillance"
        private const val NOTIFICATION_ID = 42
        private const val QUIET_PERIOD_MS = 12_000L
        private const val MIN_CLIP_MS = 5_000L
    }
}

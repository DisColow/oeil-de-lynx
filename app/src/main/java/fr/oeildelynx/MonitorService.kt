package fr.oeildelynx

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Garde la caméra et le microphone pendant la surveillance, indépendamment de l’Activity du HUD.
 * L’analyse d’image doit rester limitée pour les vieux téléphones utilisés longtemps.
 */
class MonitorService : LifecycleService() {
    private lateinit var executor: ExecutorService
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: androidx.camera.video.Recording? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var cameraProvider: ProcessCameraProvider? = null
    // Si un clip est en cours, le terminer avant de remplacer les cas d’usage CameraX.
    private var pendingCameraFacing: Boolean? = null
    private var shuttingDown = false
    private var lastMotionAt = 0L
    private var lastSoundAt = 0L
    // Échantillons de luminance de la précédente image analysée, comparés à la suivante.
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
            future.addListener({
                cameraProvider = future.get()
                val requestedFacing = pendingCameraFacing
                if (requestedFacing != null) {
                    pendingCameraFacing = null
                    rebindCamera(requestedFacing)
                } else if (!bindCamera(cameraProvider!!)) stopSelf()
            }, ContextCompat.getMainExecutor(this))
        }
        // Le son peut se déclencher avant la fin du lien CameraX ; videoCapture est vérifié au déclenchement.
        startSoundDetection()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_SWITCH_CAMERA) {
            requestCameraSwitch(intent.getBooleanExtra(EXTRA_FRONT_CAMERA, useFrontCamera))
        }
        return result
    }

    /** Lie analyse, aperçu et enregistrement ensemble pour garder la détection active pendant un clip. */
    private fun bindCamera(provider: ProcessCameraProvider): Boolean {
        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.SD)).build()
        videoCapture = VideoCapture.withOutput(recorder)
        val preview = Preview.Builder().build()
        activePreview = preview
        preview.setSurfaceProvider(previewSurfaceProvider)
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(320, 240)).build()
        analysis.setAnalyzer(executor) { image -> analyzeFrame(image) }
        return try {
            provider.unbindAll()
            val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            provider.bindToLifecycle(this, selector, videoCapture, analysis, preview)
            true
        } catch (_: Exception) {
            activePreview = null
            false
        }
    }

    /**
     * Relie à nouveau CameraX dans ce service, sans détruire puis recréer le service de premier plan.
     * Si un clip est en cours, attend son callback Finalize avant de remplacer les anciens cas d’usage.
     */
    @Synchronized private fun requestCameraSwitch(frontCamera: Boolean) {
        if (shuttingDown || (frontCamera == useFrontCamera && pendingCameraFacing == null)) return
        pendingCameraFacing = frontCamera
        if (recording != null) {
            recording?.stop()
            return
        }
        pendingCameraFacing = null
        rebindCamera(frontCamera)
    }

    @Synchronized private fun rebindCamera(frontCamera: Boolean) {
        val provider = cameraProvider ?: run { pendingCameraFacing = frontCamera; return }
        val previousFacing = useFrontCamera
        stopSoundDetection()
        provider.unbindAll()
        useFrontCamera = frontCamera
        getSharedPreferences("lynx", Context.MODE_PRIVATE).edit().putBoolean("front_camera", frontCamera).apply()
        previousLuma = null
        lastFrameAt = 0L
        lastMotionAt = 0L
        lastSoundAt = 0L

        if (!bindCamera(provider)) {
            // Si l’appareil ne possède pas cette caméra, la surveillance continue avec la caméra précédente.
            useFrontCamera = previousFacing
            getSharedPreferences("lynx", Context.MODE_PRIVATE).edit().putBoolean("front_camera", previousFacing).apply()
            if (!bindCamera(provider)) {
                stopSelf()
                return
            }
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID, notification("Caméra indisponible · retour à la caméra précédente")
            )
        } else {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID, notification(if (frontCamera) "Surveillance · caméra avant" else "Surveillance · caméra arrière")
            )
        }
        startSoundDetection()
    }

    private fun analyzeFrame(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        try {
            // Analyse au plus une image basse résolution toutes les 700 ms pour limiter le processeur.
            if (now - lastFrameAt < 700) return
            lastFrameAt = now
            // Chaque image CameraX doit être fermée, même en cas de retour anticipé (voir finally).
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
                        if (delta > MOTION_PIXEL_DELTA) changed++
                    }
                    samples.add(value)
                }
            }
            val compared = minOf(samples.size, previous?.size ?: 0)
            // Le seuil moyen et la proportion de pixels modifiés filtrent les petites variations parasites.
            if (compared > 0 && difference.toDouble() / compared > MOTION_AVERAGE_DELTA &&
                changed.toDouble() / compared > MOTION_CHANGED_RATIO) {
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
                    // Seuil RMS fixe ; l’ajuster en mesurant les niveaux du vrai téléphone si nécessaire.
                    if (rms > 0.055) { lastSoundAt = SystemClock.elapsedRealtime(); triggerRecording("Son") }
                }
            }
        }.apply { name = "lynx-audio-detector"; start() }
    }

    @Synchronized private fun triggerRecording(reason: String) {
        val capture = videoCapture ?: return
        if (recording != null) return
        // Libère le micro AudioRecord pour que CameraX puisse enregistrer la piste audio du clip.
        stopSoundDetection()
        val now = System.currentTimeMillis()
        val outputName = "lynx_$now.mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, outputName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Œil de Lynx")
        }
        // Écrit dans la collection Movies partagée pour que Photos et GalleryActivity voient le même fichier.
        val options = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()
        val detectionId = (now and 0x7fffffff).toInt().coerceAtLeast(NOTIFICATION_ID + 1)
        recordingSince = SystemClock.elapsedRealtime()
        // Un seul enregistrement est autorisé ; les images de mouvement suivantes repoussent le délai de silence.
        recording = capture.output.prepareRecording(this, options)
            .withAudioEnabled().start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    synchronized(this) {
                        recording = null
                        if (!shuttingDown) {
                            val nextFacing = pendingCameraFacing
                            if (nextFacing != null) {
                                pendingCameraFacing = null
                                rebindCamera(nextFacing)
                            } else startSoundDetection()
                        }
                    }
                    if (event.hasError()) contentResolver.delete(event.outputResults.outputUri, null, null)
                    else getSystemService(NotificationManager::class.java).notify(
                        detectionId, detectionNotification("$reason détecté · capture terminée", outputName, detectionId)
                    )
                } else if (event is VideoRecordEvent.Start) {
                    getSystemService(NotificationManager::class.java).notify(
                        detectionId, detectionNotification("$reason détecté · enregistrement en cours", outputName, detectionId)
                    )
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("Enregistrement · $reason"))
                }
            }
    }

    private fun maybeStopRecording(now: Long) {
        if (recording == null || now - recordingSince < MIN_CLIP_MS) return
        maxClipMs = getSharedPreferences("lynx", Context.MODE_PRIVATE).getInt("max_clip_ms", 600_000).toLong()
        // Arrête après une période calme, tout en appliquant la durée maximale choisie pour limiter le stockage.
        val lastActivity = maxOf(lastMotionAt, lastSoundAt)
        if (now - lastActivity > QUIET_PERIOD_MS) recording?.stop()
        // Limite chaque clip afin qu’un déclencheur permanent ne remplisse pas le stockage sans fin.
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
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Surveillance", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(DETECTION_CHANNEL_ID, "Détections", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
    private fun notification(message: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("Œil de Lynx").setContentText(message)
        .setOngoing(true).setOnlyAlertOnce(true).build()

    private fun detectionNotification(message: String, fileName: String, requestCode: Int): Notification {
        // Transmet le nom MediaStore pour que la galerie puisse retrouver le clip signalé.
        val intent = Intent(this, GalleryActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(GalleryActivity.EXTRA_FOCUS_NAME, fileName)
        val pendingIntent = PendingIntent.getActivity(
            this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, DETECTION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Activité détectée")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$message\nVidéo dans Photos > Œil de Lynx et dans la galerie de l’application."))
            .setContentIntent(pendingIntent).setAutoCancel(true).setOnlyAlertOnce(true).build()
    }

    override fun onDestroy() {
        shuttingDown = true
        pendingCameraFacing = null
        recording?.stop(); stopSoundDetection()
        activePreview = null
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Exception) { }
        if (::executor.isInitialized) executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FRONT_CAMERA = "front_camera"
        const val ACTION_SWITCH_CAMERA = "fr.oeildelynx.action.SWITCH_CAMERA"
        // L’Activity et le service ont des durées de vie distinctes ; volatile transmet leur surface d’aperçu.
        @Volatile var previewSurfaceProvider: Preview.SurfaceProvider? = null
        @Volatile private var activePreview: Preview? = null
        fun updatePreviewSurface(provider: Preview.SurfaceProvider?) {
            previewSurfaceProvider = provider
            activePreview?.setSurfaceProvider(provider)
        }
        private const val CHANNEL_ID = "lynx-surveillance"
        private const val DETECTION_CHANNEL_ID = "lynx-detections"
        private const val NOTIFICATION_ID = 42
        private const val QUIET_PERIOD_MS = 12_000L
        private const val MIN_CLIP_MS = 5_000L
        // Seuils relevés du différentiel d’images pour limiter les fausses détections de mouvement.
        private const val MOTION_PIXEL_DELTA = 32
        private const val MOTION_AVERAGE_DELTA = 16.0
        private const val MOTION_CHANGED_RATIO = 0.18
    }
}

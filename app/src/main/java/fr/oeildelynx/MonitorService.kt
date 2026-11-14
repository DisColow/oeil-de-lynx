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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
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
    private var boundCamera: androidx.camera.core.Camera? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var orientationListener: android.view.OrientationEventListener? = null
    // Surface.ROTATION_* déduite du capteur ; le service n’a pas d’écran pour la donner.
    @Volatile private var deviceRotation = android.view.Surface.ROTATION_0
    @Volatile private var recordingReason = ""
    private var liveAudio: AudioRecord? = null
    // Haut-parleur de la voix des spectateurs, créé au premier « Parler ».
    @Volatile private var talkSpeaker: TalkSpeaker? = null
    @Volatile private var startingRecording = false
    // Échantillons de luminance de la précédente image analysée, comparés à la suivante.
    private var previousLuma: ByteArray? = null
    private var lastFrameAt = 0L
    private var recordingSince = 0L
    private var maxClipMs = 10 * 60_000L
    private var useFrontCamera = false
    private var stopReason: String? = null
    // Direct vers les téléphones spectateurs appairés (null si aucun appairage).
    @Volatile private var liveServer: LiveServer? = null
    private var liveEncoder = LiveFrameEncoder()
    private var lastLiveFrameAt = 0L
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var liveViewers = 0
    private var statusText = "Initialisation de la caméra"

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Surveillance service created")
        executor = Executors.newSingleThreadExecutor()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Initialisation de la caméra"))
        val preferences = getSharedPreferences("lynx", Context.MODE_PRIVATE)
        maxClipMs = preferences.getInt("max_clip_ms", 600_000).toLong()
        useFrontCamera = preferences.getBoolean("front_camera", false)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Cannot start surveillance: camera or microphone permission is missing")
            stopWithFailure("Autorise la caméra et le micro pour démarrer la surveillance."); return
        }
        ProcessCameraProvider.getInstance(this).also { future ->
            future.addListener({
                try {
                    cameraProvider = future.get()
                    val requestedFacing = pendingCameraFacing
                    if (requestedFacing != null) {
                        pendingCameraFacing = null
                        rebindCamera(requestedFacing)
                    } else if (bindCamera(cameraProvider!!)) {
                        updateStatus(if (useFrontCamera) "Surveillance · caméra avant" else "Surveillance · caméra arrière")
                        publishMonitorState(active = true)
                        publishLiveStatus()
                    } else {
                        Log.e(TAG, "Initial CameraX binding failed for ${if (useFrontCamera) "front" else "back"} camera")
                        stopWithFailure("Impossible d’ouvrir la caméra. Vérifie ses autorisations et réessaie.")
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Could not initialize CameraX", error)
                    stopWithFailure("Impossible d’initialiser la caméra. Réessaie après avoir fermé les autres applications caméra.")
                }
            }, ContextCompat.getMainExecutor(this))
        }
        // Le son peut se déclencher avant la fin du lien CameraX ; videoCapture est vérifié au déclenchement.
        startSoundDetection()
        startLive()
        startOrientationTracking()
    }

    /**
     * Suit la position physique du téléphone (portrait, paysage, à l’envers). La rotation des images
     * d’analyse (direct) change aussitôt ; celle des clips s’applique au prochain enregistrement.
     */
    private fun startOrientationTracking() {
        orientationListener = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                val rotation = DeviceOrientation.surfaceRotation(degrees, deviceRotation)
                if (rotation == deviceRotation) return
                deviceRotation = rotation
                Log.i(TAG, "Device rotation now $rotation")
                analysisUseCase?.targetRotation = rotation
                videoCapture?.targetRotation = rotation
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        Log.i(TAG, "Service start command: ${intent?.action ?: "START"}")
        if (intent?.action == ACTION_SWITCH_CAMERA) {
            requestCameraSwitch(intent.getBooleanExtra(EXTRA_FRONT_CAMERA, useFrontCamera))
        } else if (intent?.action == ACTION_SET_EXPOSURE && !shuttingDown) {
            setExposure(intent.getIntExtra(EXTRA_EXPOSURE, 0))
        } else if (intent?.action == ACTION_LIVE_REFRESH && !shuttingDown) {
            // Nouvel appairage, nouvelle clé ou nouveau nom : relance le direct avec les réglages actuels.
            liveServer?.disconnectAll()
            startLive()
            publishLiveStatus()
        }
        return result
    }

    /** Lie analyse, aperçu et enregistrement ensemble pour garder la détection active pendant un clip. */
    private fun bindCamera(provider: ProcessCameraProvider): Boolean {
        // Demande SD pour limiter chauffe et stockage, avec LOWEST comme repli sur les appareils atypiques.
        val quality = QualitySelector.fromOrderedList(listOf(Quality.SD, Quality.LOWEST))
        val recorder = Recorder.Builder().setQualitySelector(quality).build()
        // Orientation physique du téléphone : image droite dans le direct et dans les clips, même en paysage.
        videoCapture = VideoCapture.withOutput(recorder).also { it.targetRotation = deviceRotation }
        val preview = Preview.Builder().build()
        activePreview = preview
        preview.setSurfaceProvider(previewSurfaceProvider)
        // 640 × 480 : assez net pour le direct ; la détection n’échantillonne qu’une grille de 40 × 30 points.
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(640, 480)).setTargetRotation(deviceRotation).build()
        analysisUseCase = analysis
        analysis.setAnalyzer(executor) { image -> analyzeFrame(image) }
        return try {
            provider.unbindAll()
            val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            boundCamera = provider.bindToLifecycle(this, selector, videoCapture, analysis, preview)
                .also { Exposure.apply(this, it) { publishLiveStatus() } }
            Log.i(TAG, "CameraX bound to ${if (useFrontCamera) "front" else "back"} camera")
            true
        } catch (error: Exception) {
            activePreview = null
            Log.e(TAG, "CameraX use-case binding failed for ${if (useFrontCamera) "front" else "back"} camera", error)
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
                stopWithFailure("Aucune caméra disponible pour la surveillance. Vérifie les autorisations puis réessaie.")
                return
            }
            updateStatus("Caméra indisponible · retour à la caméra précédente")
            publishMonitorState(active = true, message = "Caméra indisponible, retour à la caméra précédente.")
        } else {
            updateStatus(if (frontCamera) "Surveillance · caméra avant" else "Surveillance · caméra arrière")
            publishMonitorState(active = true)
        }
        publishLiveStatus()
        startSoundDetection()
    }

    private fun analyzeFrame(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        try {
            // Le direct n’encode des JPEG que si un spectateur est connecté, à cadence limitée.
            val server = liveServer
            if (server != null && server.hasViewers() && now - lastLiveFrameAt >= LIVE_FRAME_INTERVAL_MS) {
                lastLiveFrameAt = now
                try {
                    liveEncoder.encode(image, LIVE_JPEG_QUALITY)?.let { server.publishFrame(it, image.imageInfo.rotationDegrees) }
                } catch (error: Exception) { Log.w(TAG, "Live frame encoding failed", error) }
            }
            // Analyse au plus une image toutes les 500 ms pour limiter le processeur.
            if (now - lastFrameAt < ANALYSIS_INTERVAL_MS) return
            lastFrameAt = now
            // Chaque image CameraX doit être fermée, même en cas de retour anticipé (voir finally).
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer.duplicate(); val stride = plane.rowStride
            val pixelStride = plane.pixelStride
            // Grille d’environ 40 × 30 points quelle que soit la résolution.
            val step = maxOf(8, image.width / 40)
            val start = step / 2
            val capacity = ((image.height - start + step - 1) / step) * ((image.width - start + step - 1) / step)
            // Stocke les octets directement : évite le boxing de Byte sur chaque pixel échantillonné.
            val samples = ByteArray(capacity.coerceAtLeast(0))
            var sampleCount = 0
            for (y in start until image.height - 1 step step) for (x in start until image.width - 1 step step) {
                // Moyenne d’un carré de 2 × 2 pixels : atténue le grain du capteur, surtout la nuit.
                val index = y * stride + x * pixelStride
                val below = index + stride
                if (below + pixelStride >= buffer.limit() || sampleCount >= samples.size) continue
                val sum = (buffer.get(index).toInt() and 0xff) + (buffer.get(index + pixelStride).toInt() and 0xff) +
                    (buffer.get(below).toInt() and 0xff) + (buffer.get(below + pixelStride).toInt() and 0xff)
                samples[sampleCount++] = (sum / 4).toByte()
            }
            val currentSamples = samples.copyOf(sampleCount)
            val previous = previousLuma
            if (previous != null && MotionDetector.hasMotion(currentSamples, previous, motionSensitivity(this))) {
                lastMotionAt = now
                triggerRecording("Mouvement")
            }
            previousLuma = currentSamples
            maybeStopRecording(now)
        } finally { image.close() }
    }

    private fun startSoundDetection() {
        if (recording != null || audioRecord != null) return
        // Par défaut seul le mouvement déclenche un clip ; le son est une option des réglages.
        if (!getSharedPreferences("lynx", Context.MODE_PRIVATE).getBoolean(PREF_SOUND_TRIGGER, false)) return
        // La permission peut être révoquée depuis les réglages pendant la vie du service.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val min = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return
        val rec = try { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096)) } catch (_: Exception) { return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
        try { rec.startRecording() } catch (_: SecurityException) { rec.release(); return }
        audioRecord = rec
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        // Libère le micro AudioRecord pour que CameraX puisse enregistrer la piste audio du clip.
        stopSoundDetection()
        // Le son du direct reprend dès que CameraX a ouvert le micro (événement Start ou fin du clip).
        startingRecording = true
        stopLiveAudio()
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
        recording = try {
            capture.output.prepareRecording(this, options)
                .withAudioEnabled().start(ContextCompat.getMainExecutor(this)) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        startingRecording = false
                        if (!shuttingDown) startLiveAudio()
                        synchronized(this) {
                            recording = null
                            publishLiveStatus()
                            if (!shuttingDown) {
                                val nextFacing = pendingCameraFacing
                                if (nextFacing != null) {
                                    pendingCameraFacing = null
                                    rebindCamera(nextFacing)
                                } else startSoundDetection()
                            }
                        }
                        if (!shuttingDown) updateStatus(if (useFrontCamera) "Surveillance · caméra avant" else "Surveillance · caméra arrière")
                        if (event.hasError()) contentResolver.delete(event.outputResults.outputUri, null, null)
                        else getSystemService(NotificationManager::class.java).notify(
                            detectionId, detectionNotification("$reason détecté · capture terminée", outputName, detectionId)
                        )
                    } else if (event is VideoRecordEvent.Start) {
                        startingRecording = false
                        startLiveAudio()
                        getSystemService(NotificationManager::class.java).notify(
                            detectionId, detectionNotification("$reason détecté · enregistrement en cours", outputName, detectionId)
                        )
                        recordingReason = reason
                        updateStatus("Enregistrement · $reason")
                        publishLiveStatus()
                    }
                }
        } catch (_: SecurityException) {
            // Une permission révoquée au même instant qu’un déclenchement ne doit pas tuer le service.
            startingRecording = false
            startLiveAudio()
            startSoundDetection()
            return
        }
    }

    private fun maybeStopRecording(now: Long) {
        if (recording == null) return
        maxClipMs = getSharedPreferences("lynx", Context.MODE_PRIVATE).getInt("max_clip_ms", 600_000).toLong()
        // Le clip s’arrête quelques secondes après le dernier mouvement (le micro est occupé par le clip,
        // un clip déclenché par le son s’arrête donc aussi faute de mouvement).
        val lastActivity = maxOf(lastMotionAt, lastSoundAt)
        if (now - lastActivity > STOP_AFTER_STILL_MS) recording?.stop()
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

    /** Ouvre le direct si un téléphone spectateur a été appairé ; demande à la box d’ouvrir le port. */
    private fun startLive() {
        if (!LiveCamera.isPaired(this)) return
        if (liveServer == null) {
            val server = LiveServer(
                LiveProtocol.DEFAULT_PORT,
                secretProvider = { LiveCamera.secret(this) },
                helloProvider = { liveHello() },
                // Commandes d’un spectateur : seule l’exposition est modifiable à distance.
                onRequest = { request ->
                    val value = request["value"]?.toIntOrNull()
                    if (request["op"] == "exposure" && value != null) {
                        ContextCompat.getMainExecutor(this).execute { if (!shuttingDown) setExposure(value) }
                    }
                },
                gallery = CaptureLibrary(applicationContext),
                onTalk = { chunk -> (talkSpeaker ?: TalkSpeaker(applicationContext).also { talkSpeaker = it }).play(chunk) },
                onAudioDemandChanged = { demand ->
                    ContextCompat.getMainExecutor(this).execute { if (demand && !shuttingDown) startLiveAudio() else stopLiveAudio() }
                },
                onViewersChanged = { count ->
                    liveViewers = count
                    ContextCompat.getMainExecutor(this).execute { if (!shuttingDown) updateStatus(statusText) }
                    sendBroadcast(Intent(ACTION_LIVE_VIEWERS_CHANGED).setPackage(packageName).putExtra(EXTRA_LIVE_VIEWERS, count))
                }
            )
            if (!server.start()) {
                updateStatus("Direct indisponible : port ${LiveProtocol.DEFAULT_PORT} occupé")
                return
            }
            liveServer = server
            // Garde le Wi-Fi actif écran éteint, sinon le direct se fige au bout de quelques minutes.
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "oeildelynx:live")?.apply {
                setReferenceCounted(false); acquire()
            }
        }
        Thread({ LiveCamera.probeBox(applicationContext, openPort = true) }, "lynx-upnp").start()
    }

    /**
     * Micro du direct (16 kHz, µ-law), ouvert seulement quand un spectateur écoute. Il coexiste avec
     * l’enregistrement des clips : il est suspendu au lancement d’un clip, puis rouvert une fois que
     * CameraX tient le micro, pour ne jamais gêner la piste audio du clip.
     */
    @Synchronized private fun startLiveAudio() {
        if (liveAudio != null || liveServer?.hasAudioListeners() != true || startingRecording) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val rate = LiveProtocol.AUDIO_SAMPLE_RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return
        val rec = try { AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192)) } catch (_: Exception) { return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
        try { rec.startRecording() } catch (_: Exception) { rec.release(); return }
        liveAudio = rec
        Log.i(TAG, "Live audio started")
        Thread {
            val samples = ShortArray(1024) // 64 ms
            while (liveAudio === rec && rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val n = try { rec.read(samples, 0, samples.size) } catch (_: Exception) { -1 }
                if (n < 0) break
                if (n > 0) liveServer?.publishAudio(MuLaw.encode(samples, n))
            }
        }.apply { name = "lynx-live-audio"; start() }
    }

    @Synchronized private fun stopLiveAudio() {
        val rec = liveAudio ?: return
        liveAudio = null
        try { rec.stop() } catch (_: Exception) { }
        rec.release()
        Log.i(TAG, "Live audio stopped")
    }

    private fun stopLive() {
        stopLiveAudio()
        talkSpeaker?.release(); talkSpeaker = null
        val server = liveServer ?: return
        liveServer = null
        server.stop(sayGoodbye = true)
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        // Referme le port sur la box : rien n’écoute plus derrière.
        val context = applicationContext
        Thread({ LiveCamera.closeBoxPort(context) }, "lynx-upnp-close").start()
    }

    private fun liveHello(): Map<String, String> = linkedMapOf(
        "id" to LiveCamera.id(this),
        "n" to LiveCamera.name(this),
        "h" to LiveCamera.candidates(this).joinToString(",")
    )

    /** Correction d’exposition demandée par le HUD ou par un spectateur (thread principal). */
    private fun setExposure(index: Int) {
        Exposure.setIndex(this, index)
        // L’état est republié une fois la valeur acceptée par la caméra : le spectateur voit la valeur réelle.
        boundCamera?.let { Exposure.apply(this, it) { publishLiveStatus() } }
        publishLiveStatus()
    }

    private fun publishLiveStatus() {
        liveServer?.publishStatus(linkedMapOf(
            "rec" to if (recording != null) "1" else "0",
            "why" to if (recording != null) recordingReason else "",
            "cam" to if (useFrontCamera) "front" else "back",
            "n" to LiveCamera.name(this)
        ) + Exposure.fields(this))
    }

    /** Notification permanente : état de surveillance + nombre de spectateurs en direct, par transparence. */
    private fun updateStatus(text: String) {
        statusText = text
        val viewers = liveViewers
        val suffix = when (viewers) { 0 -> ""; 1 -> " · 1 spectateur en direct"; else -> " · $viewers spectateurs en direct" }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text + suffix))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Surveillance", NotificationManager.IMPORTANCE_LOW))
            // Nouveau canal en priorité haute (bannière + son) : un canal existant ne peut pas changer d’importance.
            manager.deleteNotificationChannel("lynx-detections")
            manager.createNotificationChannel(NotificationChannel(DETECTION_CHANNEL_ID, "Mouvements détectés", NotificationManager.IMPORTANCE_HIGH))
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
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_ALARM)
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
        stopLive()
        orientationListener?.disable(); orientationListener = null
        activePreview = null
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Exception) { }
        if (::executor.isInitialized) executor.shutdownNow()
        val wasActive = getSharedPreferences("lynx", Context.MODE_PRIVATE).getBoolean("monitoring_active", false)
        if (wasActive || stopReason != null) {
            publishMonitorState(active = false, message = stopReason)
        }
        super.onDestroy()
    }

    /** Efface l’état actif ; le message d’erreur sera envoyé après le nettoyage du service. */
    private fun stopWithFailure(message: String) {
        stopReason = message
        getSharedPreferences("lynx", Context.MODE_PRIVATE).edit()
            .putBoolean("monitoring_active", false).putBoolean("preview_shown", true).apply()
        stopSelf()
    }

    /** Publie l’état CameraX réel pour que le HUD reste synchronisé avec le service. */
    private fun publishMonitorState(active: Boolean, message: String? = null) {
        val preferences = getSharedPreferences("lynx", Context.MODE_PRIVATE)
        preferences.edit().putBoolean("monitoring_active", active)
            .apply { if (!active) putBoolean("preview_shown", true) }.apply()
        sendBroadcast(Intent(ACTION_MONITOR_STATE_CHANGED).setPackage(packageName)
            .putExtra(EXTRA_MONITORING_ACTIVE, active)
            .putExtra(EXTRA_MONITOR_MESSAGE, message))
    }

    companion object {
        const val EXTRA_FRONT_CAMERA = "front_camera"
        const val ACTION_SWITCH_CAMERA = "fr.oeildelynx.action.SWITCH_CAMERA"
        const val ACTION_MONITOR_STATE_CHANGED = "fr.oeildelynx.action.MONITOR_STATE_CHANGED"
        const val ACTION_LIVE_REFRESH = "fr.oeildelynx.action.LIVE_REFRESH"
        const val ACTION_SET_EXPOSURE = "fr.oeildelynx.action.SET_EXPOSURE"
        const val EXTRA_EXPOSURE = "exposure_index"
        const val ACTION_LIVE_VIEWERS_CHANGED = "fr.oeildelynx.action.LIVE_VIEWERS_CHANGED"
        const val EXTRA_LIVE_VIEWERS = "live_viewers"
        const val EXTRA_MONITORING_ACTIVE = "monitoring_active"
        const val EXTRA_MONITOR_MESSAGE = "monitor_message"
        // L’Activity et le service ont des durées de vie distinctes ; volatile transmet leur surface d’aperçu.
        @Volatile var previewSurfaceProvider: Preview.SurfaceProvider? = null
        @Volatile private var activePreview: Preview? = null
        fun updatePreviewSurface(provider: Preview.SurfaceProvider?) {
            previewSurfaceProvider = provider
            activePreview?.setSurfaceProvider(provider)
        }
        private const val CHANNEL_ID = "lynx-surveillance"
        private const val DETECTION_CHANNEL_ID = "lynx-motion-alerts"
        private const val NOTIFICATION_ID = 42
        private const val TAG = "LynxMonitor"
        private const val ANALYSIS_INTERVAL_MS = 500L
        // Durée sans mouvement avant de terminer le clip.
        private const val STOP_AFTER_STILL_MS = 10_000L
        const val PREF_SOUND_TRIGGER = "sound_trigger"
        const val PREF_SENSITIVITY = "motion_sensitivity"

        internal fun motionSensitivity(context: Context): MotionDetector.Sensitivity {
            val name = context.getSharedPreferences("lynx", Context.MODE_PRIVATE).getString(PREF_SENSITIVITY, null)
            return MotionDetector.Sensitivity.entries.firstOrNull { it.name == name } ?: MotionDetector.Sensitivity.NORMAL
        }
        // Direct : environ 8 images/s en JPEG qualité 60, soit ~2 à 3 Mbit/s en 640 × 480.
        private const val LIVE_FRAME_INTERVAL_MS = 120L
        private const val LIVE_JPEG_QUALITY = 60
        // Seuils relevés du différentiel d’images pour limiter les fausses détections de mouvement.
    }
}

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
    // Sirène déclenchée par un spectateur.
    @Volatile private var siren: AlarmSiren? = null
    @Volatile private var soundBoard: SoundBoard? = null
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
        armAutoAlarm() // démarrage de la surveillance : une minute pour quitter la pièce
        appliedAutoMode = preferences.getString(PREF_AUTO_ALARM, AUTO_OFF) ?: AUTO_OFF
        useFrontCamera = preferences.getBoolean("front_camera", false)
        // Une veille en cours survit au redémarrage du service : la caméra reste éteinte.
        standby = preferences.getBoolean(PREF_STANDBY, false)
        standbyReason = preferences.getString(PREF_STANDBY_REASON, "") ?: ""
        autoStandby = standbyReason == REASON_BATTERY
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
                    } else if (standby) {
                        updateStatus(standbyText())
                        publishMonitorState(active = true)
                        publishLiveStatus()
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
        // Intent « collant » : le niveau actuel arrive tout de suite, puis à chaque changement.
        ContextCompat.registerReceiver(this, batteryReceiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
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
        } else if (intent?.action == ACTION_STANDBY && !shuttingDown) {
            if (intent.getBooleanExtra(EXTRA_STANDBY, false)) enterStandby(REASON_MANUAL) else exitStandby()
        } else if (intent?.action == ACTION_CONTROL && !shuttingDown) {
            val op = intent.getStringExtra(EXTRA_OP); val value = intent.getStringExtra(EXTRA_VALUE)
            if (op != null && value != null) applyControl(mapOf("op" to op, "value" to value))
        } else if (intent?.action == ACTION_STOP_ALARM) {
            stopSiren()
        } else if (intent?.action == ACTION_SET_SOUND && !shuttingDown) {
            setSoundTrigger(intent.getBooleanExtra(EXTRA_SOUND, false))
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
                .also { camera ->
                    Exposure.apply(this, camera) { publishLiveStatus() }
                    Exposure.applyMetering(this, camera, analysis) { publishLiveStatus() }
                }
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
        if (standby) {
            // En veille : la caméra choisie sera utilisée à la reprise.
            useFrontCamera = frontCamera
            getSharedPreferences("lynx", Context.MODE_PRIVATE).edit().putBoolean("front_camera", frontCamera).apply()
            return
        }
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
                ContextCompat.getMainExecutor(this).execute { onDetection(DETECT_MOTION) }
                triggerRecording("Mouvement")
            }
            previousLuma = currentSamples
            maybeStopRecording(now)
        } finally { image.close() }
    }

    private fun startSoundDetection() {
        if (recording != null || audioRecord != null || standby) return
        // Par défaut seul le mouvement déclenche un clip ; le son est une option des réglages.
        // Le micro écoute aussi si l’alarme automatique doit réagir au son.
        if (!soundTriggersClips() && !autoAlarmOn(DETECT_SOUND)) return
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
                    // Les sons joués par la caméra elle-même (sirène, sons, voix) ne comptent pas.
                    if (rms > 0.055 && !playingOwnSound()) {
                        lastSoundAt = SystemClock.elapsedRealtime()
                        ContextCompat.getMainExecutor(this).execute { onDetection(DETECT_SOUND) }
                        if (soundTriggersClips()) triggerRecording("Son")
                    }
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
                                val nextStandby = pendingStandby
                                if (nextStandby != null) {
                                    pendingStandby = null
                                    ContextCompat.getMainExecutor(this).execute { enterStandby(nextStandby) }
                                } else if (nextFacing != null) {
                                    pendingCameraFacing = null
                                    rebindCamera(nextFacing)
                                } else startSoundDetection()
                            }
                        }
                        if (!shuttingDown && !standby && pendingStandby == null) updateStatus(if (useFrontCamera) "Surveillance · caméra avant" else "Surveillance · caméra arrière")
                        if (!event.hasError()) {
                            // Dernier clip terminé : annoncé aux spectateurs qui récupèrent les clips automatiquement.
                            lastClipId = try { android.content.ContentUris.parseId(event.outputResults.outputUri) } catch (_: Exception) { -1L }
                            publishLiveStatus()
                        }
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
        // Limite chaque clip afin qu’un déclencheur permanent ne remplisse pas le stockage sans fin
        // (0 = pas de découpe : seule la fin du mouvement arrête le clip).
        if (maxClipMs > 0 && now - recordingSince > maxClipMs) recording?.stop()
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
                    } else if (request["op"] == "fx") {
                        // Son dissuasif choisi par un spectateur (feulement, bombe d’air…).
                        DeterrentSounds.Effect.byId(request["id"])?.let { effect ->
                            ownSoundUntil = SystemClock.elapsedRealtime() + 12_000
                            (soundBoard ?: SoundBoard(applicationContext).also { soundBoard = it }).play(effect)
                        }
                    } else if (request["op"] == "meter") {
                        // Zone touchée dans le direct (x, y entre 0 et 1), ou reset=1 pour revenir en automatique.
                        val x = request["x"]?.toFloatOrNull(); val y = request["y"]?.toFloatOrNull()
                        val zone = if (request["reset"] == "1" || x == null || y == null) null else x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)
                        ContextCompat.getMainExecutor(this).execute { if (!shuttingDown) setMetering(zone) }
                    } else if (request["op"] in setOf("alarm", "sound", "standby", "threshold", "autoalarm", "autoalarmsound")) {
                        // Mêmes commandes que depuis la liste des caméras (veille / reprise comprises).
                        control(request)
                    }
                },
                onControl = { request -> control(request) },
                gallery = CaptureLibrary(applicationContext),
                onTalk = { chunk ->
                    ownSoundUntil = SystemClock.elapsedRealtime() + 2_000
                    (talkSpeaker ?: TalkSpeaker(applicationContext).also { talkSpeaker = it }).play(chunk)
                },
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
        if (liveAudio != null || liveServer?.hasAudioListeners() != true || startingRecording || standby) return
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
        siren?.stop(); siren = null
        soundBoard?.release(); soundBoard = null
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

    private fun setMetering(zone: Pair<Float, Float>?) {
        Exposure.setMeteringZone(this, zone)
        val camera = boundCamera; val analysis = analysisUseCase
        if (camera != null && analysis != null) Exposure.applyMetering(this, camera, analysis) { publishLiveStatus() }
        publishLiveStatus()
    }

    /** Correction d’exposition demandée par le HUD ou par un spectateur (thread principal). */
    private fun setExposure(index: Int) {
        Exposure.setIndex(this, index)
        // L’état est republié une fois la valeur acceptée par la caméra : le spectateur voit la valeur réelle.
        boundCamera?.let { Exposure.apply(this, it) { publishLiveStatus() } }
        publishLiveStatus()
    }

    private fun publishLiveStatus() {
        liveServer?.publishStatus(controlState() + Exposure.fields(this))
    }

    /** État envoyé aux spectateurs : enregistrement, caméra, sirène, détection du son. */
    private fun controlState(): Map<String, String> = linkedMapOf(
        "rec" to if (recording != null) "1" else "0",
        "why" to if (recording != null) recordingReason else "",
        "cam" to if (useFrontCamera) "front" else "back",
        "n" to LiveCamera.name(this),
        "alarm" to if (siren?.isRunning == true) "1" else "0",
        "sound" to if (getSharedPreferences("lynx", Context.MODE_PRIVATE).getBoolean(PREF_SOUND_TRIGGER, false)) "1" else "0",
        // Batterie du téléphone caméra, affichée chez les spectateurs.
        "bat" to batteryLevel.toString(),
        "chg" to if (batteryCharging) "1" else "0",
        "standby" to if (standby) "1" else "0",
        "standbywhy" to standbyReason,
        "thr" to standbyThreshold().toString(),
        "aa" to autoAlarmMode(),
        "aas" to autoAlarmSound(),
        // Secondes avant que l’alarme automatique soit armée (0 = armée, ou désactivée).
        "aarm" to armingSecondsLeft().toString(),
        "clip" to lastClipId.toString()
    )

    @Volatile private var batteryLevel = -1
    @Volatile private var batteryCharging = false

    /** Suit la batterie : chaque changement de pourcentage ou de branchement est envoyé aux spectateurs. */
    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val percent = if (level >= 0) level * 100 / scale else -1
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
            if (percent == batteryLevel && charging == batteryCharging) return
            batteryLevel = percent; batteryCharging = charging
            if (!shuttingDown) { checkBatteryStandby(); publishLiveStatus() }
        }
    }

    /**
     * Commandes d’un spectateur : `op=alarm|sound&value=1|0`, ou `op=settings` pour lire l’état.
     * Exécutées sur le thread principal ; renvoie l’état à jour. Appelée depuis un thread réseau.
     */
    private fun control(request: Map<String, String>): Map<String, String> {
        val task = java.util.concurrent.FutureTask { applyControl(request); controlState() }
        ContextCompat.getMainExecutor(this).execute(task)
        return try { task.get(5, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { controlState() }
    }

    /** Exécute une commande de spectateur ou du HUD (thread principal). */
    private fun applyControl(request: Map<String, String>) {
        if (shuttingDown) return
        val on = request["value"] == "1"
        when (request["op"]) {
            "alarm" -> if (on) startSiren() else stopSiren()
            "standby" -> if (on) enterStandby(REASON_REMOTE) else exitStandby()
            "autoalarm" -> request["value"]?.takeIf { it in AUTO_ALARM_MODES }?.let { setAutoAlarm(mode = it) }
            "autoalarmsound" -> request["value"]?.takeIf { it == SIREN || DeterrentSounds.Effect.byId(it) != null }?.let { setAutoAlarm(sound = it) }
            "threshold" -> request["value"]?.toIntOrNull()?.let { setStandbyThreshold(it) }
            "sound" -> setSoundTrigger(on)
        }
    }

    private fun startSiren(maxMs: Long = AlarmSiren.MAX_DURATION_MS) {
        val alarm = siren ?: AlarmSiren(applicationContext) {
            ContextCompat.getMainExecutor(this).execute { if (!shuttingDown) { updateStatus(statusText); publishLiveStatus() } }
        }.also { siren = it }
        alarm.start(maxMs)
        updateStatus(statusText)
        publishLiveStatus()
    }

    // ——— Alarme automatique : sirène ou son dissuasif joué tout seul sur mouvement et/ou son. ———
    @Volatile private var lastClipId = -1L
    @Volatile private var ownSoundUntil = 0L
    private var lastAutoAlarmAt = 0L
    // Délai d’armement : rien ne se déclenche avant cette heure (le temps de quitter la pièce).
    @Volatile private var autoAlarmArmedAt = 0L
    private var appliedAutoMode = AUTO_OFF
    private val armedHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val armedNotice = Runnable { if (!shuttingDown) publishLiveStatus() }

    /** (Re)lance le compte à rebours d’1 minute avant que l’alarme automatique puisse se déclencher. */
    private fun armAutoAlarm() {
        autoAlarmArmedAt = SystemClock.elapsedRealtime() + AUTO_ALARM_ARMING_MS
        // Les spectateurs voient le compte à rebours, puis « armée » quand il est écoulé.
        armedHandler.removeCallbacks(armedNotice)
        armedHandler.postDelayed(armedNotice, AUTO_ALARM_ARMING_MS + 500)
    }

    private fun armingSecondsLeft(): Long {
        if (autoAlarmMode() == AUTO_OFF) return 0
        val left = autoAlarmArmedAt - SystemClock.elapsedRealtime()
        return if (left > 0) (left + 999) / 1000 else 0
    }

    private fun prefs() = getSharedPreferences("lynx", Context.MODE_PRIVATE)
    private fun soundTriggersClips() = prefs().getBoolean(PREF_SOUND_TRIGGER, false)
    private fun autoAlarmMode() = prefs().getString(PREF_AUTO_ALARM, AUTO_OFF) ?: AUTO_OFF
    private fun autoAlarmSound() = prefs().getString(PREF_AUTO_ALARM_SOUND, SIREN) ?: SIREN
    private fun autoAlarmOn(kind: String) = when (autoAlarmMode()) { AUTO_BOTH -> true; kind -> true; else -> false }
    private fun playingOwnSound() = siren?.isRunning == true || SystemClock.elapsedRealtime() < ownSoundUntil

    private fun setAutoAlarm(mode: String? = null, sound: String? = null) {
        // Activation (ou changement de déclencheur) : une minute avant de pouvoir sonner.
        // (Comparé au dernier mode appliqué par le service : l’écran Réglages enregistre la préférence avant de l’envoyer.)
        if (mode != null && mode != AUTO_OFF && mode != appliedAutoMode) armAutoAlarm()
        mode?.let { appliedAutoMode = it }
        prefs().edit().apply { mode?.let { putString(PREF_AUTO_ALARM, it) }; sound?.let { putString(PREF_AUTO_ALARM_SOUND, it) } }.apply()
        // Le micro doit écouter (ou plus) selon le nouveau réglage.
        if (autoAlarmOn(DETECT_SOUND) || soundTriggersClips()) startSoundDetection() else stopSoundDetection()
        publishLiveStatus()
    }

    /** Mouvement ou son détecté : déclenche l’alarme si elle est armée pour ce type, au plus une fois par minute. */
    private fun onDetection(kind: String) {
        if (shuttingDown || standby || !autoAlarmOn(kind) || playingOwnSound()) return
        val now = SystemClock.elapsedRealtime()
        if (now < autoAlarmArmedAt) return // pas encore armée
        if (lastAutoAlarmAt != 0L && now - lastAutoAlarmAt < AUTO_ALARM_COOLDOWN_MS) return
        lastAutoAlarmAt = now
        val sound = autoAlarmSound()
        Log.i(TAG, "Automatic alarm ($kind → $sound)")
        if (sound == SIREN) {
            startSiren(AUTO_SIREN_MS)
        } else DeterrentSounds.Effect.byId(sound)?.let { effect ->
            ownSoundUntil = now + 12_000
            (soundBoard ?: SoundBoard(applicationContext).also { soundBoard = it }).play(effect)
        }
    }

    private fun stopSiren() {
        siren?.stop()
        publishLiveStatus()
    }

    /** Détection du son activée ou coupée tout de suite, sans redémarrer la surveillance. */
    private fun setSoundTrigger(on: Boolean) {
        getSharedPreferences("lynx", Context.MODE_PRIVATE).edit().putBoolean(PREF_SOUND_TRIGGER, on).apply()
        // Le micro reste ouvert si l’alarme automatique écoute le son.
        if (on || autoAlarmOn(DETECT_SOUND)) startSoundDetection() else stopSoundDetection()
        publishLiveStatus()
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
    // ——— Veille : caméra, analyse et micro coupés ; connexion des spectateurs conservée. ———
    @Volatile private var standby = false
    private var standbyReason = ""
    private var autoStandby = false
    private var pendingStandby: String? = null
    // Reprise demandée à la main sous le seuil : pas de nouvelle veille auto avant la prochaine charge.
    private var autoStandbySuppressed = false

    private fun standbyThreshold() = getSharedPreferences("lynx", Context.MODE_PRIVATE).getInt(PREF_STANDBY_THRESHOLD, DEFAULT_STANDBY_THRESHOLD)

    private fun setStandbyThreshold(percent: Int) {
        getSharedPreferences("lynx", Context.MODE_PRIVATE).edit().putInt(PREF_STANDBY_THRESHOLD, percent.coerceIn(0, 90)).apply()
        checkBatteryStandby()
        publishLiveStatus()
    }

    /** Veille automatique sous le seuil hors charge ; reprise automatique dès que le téléphone est branché. */
    private fun checkBatteryStandby() {
        val threshold = standbyThreshold()
        if (batteryCharging) autoStandbySuppressed = false
        if (!batteryCharging && threshold > 0 && batteryLevel in 0..threshold && !standby && pendingStandby == null && !autoStandbySuppressed) {
            enterStandby(REASON_BATTERY)
        } else if (batteryCharging && standby && autoStandby) {
            exitStandby()
        }
    }

    private fun standbyText() = when (standbyReason) {
        REASON_BATTERY -> "En veille · batterie faible ($batteryLevel %), reprise au branchement"
        REASON_REMOTE -> "En veille · demandée par un spectateur"
        else -> "En veille · caméra et micro coupés"
    }

    /** Met en veille ; si un clip est en cours, attend qu’il soit finalisé. */
    private fun enterStandby(reason: String) {
        if (standby || shuttingDown) return
        if (recording != null) { pendingStandby = reason; recording?.stop(); return }
        standby = true; standbyReason = reason; autoStandby = reason == REASON_BATTERY
        getSharedPreferences("lynx", Context.MODE_PRIVATE).edit().putBoolean(PREF_STANDBY, true).putString(PREF_STANDBY_REASON, reason).apply()
        stopSoundDetection(); stopLiveAudio()
        try { cameraProvider?.unbindAll() } catch (_: Exception) { }
        boundCamera = null; analysisUseCase = null; videoCapture = null; activePreview = null
        previousLuma = null
        Log.i(TAG, "Standby ($reason)")
        updateStatus(standbyText())
        publishLiveStatus()
        sendBroadcast(Intent(ACTION_STANDBY_CHANGED).setPackage(packageName))
    }

    private fun exitStandby() {
        pendingStandby = null
        if (!standby || shuttingDown) return
        standby = false; standbyReason = ""; autoStandby = false
        autoStandbySuppressed = !batteryCharging && batteryLevel in 0..standbyThreshold()
        getSharedPreferences("lynx", Context.MODE_PRIVATE).edit().putBoolean(PREF_STANDBY, false).remove(PREF_STANDBY_REASON).apply()
        Log.i(TAG, "Leaving standby")
        val provider = cameraProvider
        if (provider != null) {
            if (!bindCamera(provider)) { stopWithFailure("Impossible de rouvrir la caméra après la veille."); return }
            lastFrameAt = 0L; lastMotionAt = 0L; lastSoundAt = 0L
        }
        updateStatus(if (useFrontCamera) "Surveillance · caméra avant" else "Surveillance · caméra arrière")
        armAutoAlarm() // au sortir de la veille aussi, le temps de quitter la pièce
        startSoundDetection(); startLiveAudio()
        publishLiveStatus()
        sendBroadcast(Intent(ACTION_STANDBY_CHANGED).setPackage(packageName))
    }

    private fun notification(message: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("Œil de Lynx").setContentText(message)
        .setOngoing(true).setOnlyAlertOnce(true)
        .apply {
            // En veille : bouton pour reprendre la surveillance depuis le téléphone caméra.
            if (standby) addAction(0, "Reprendre la surveillance", PendingIntent.getService(this@MonitorService, 8,
                Intent(this@MonitorService, MonitorService::class.java).setAction(ACTION_STANDBY).putExtra(EXTRA_STANDBY, false),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            // Sirène en cours : bouton pour l’arrêter directement depuis le téléphone caméra.
            if (siren?.isRunning == true) {
                setContentText("Alarme en cours · $message")
                addAction(0, "Arrêter l’alarme", PendingIntent.getService(this@MonitorService, 7,
                    Intent(this@MonitorService, MonitorService::class.java).setAction(ACTION_STOP_ALARM), PendingIntent.FLAG_IMMUTABLE))
            }
        }.build()

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
        armedHandler.removeCallbacks(armedNotice)
        pendingCameraFacing = null
        recording?.stop(); stopSoundDetection()
        stopLive()
        orientationListener?.disable(); orientationListener = null
        try { unregisterReceiver(batteryReceiver) } catch (_: IllegalArgumentException) { }
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
        const val ACTION_STOP_ALARM = "fr.oeildelynx.action.STOP_ALARM"
        const val ACTION_STANDBY = "fr.oeildelynx.action.STANDBY"
        const val ACTION_STANDBY_CHANGED = "fr.oeildelynx.action.STANDBY_CHANGED"
        const val EXTRA_STANDBY = "standby"
        const val PREF_STANDBY = "standby"
        const val PREF_STANDBY_REASON = "standby_reason"
        const val PREF_STANDBY_THRESHOLD = "standby_threshold"
        const val DEFAULT_STANDBY_THRESHOLD = 20
        const val REASON_BATTERY = "batterie"
        const val REASON_REMOTE = "spectateur"
        const val REASON_MANUAL = "manuel"
        const val ACTION_CONTROL = "fr.oeildelynx.action.CONTROL"
        const val EXTRA_OP = "op"
        const val EXTRA_VALUE = "value"
        const val PREF_AUTO_ALARM = "auto_alarm"
        const val PREF_AUTO_ALARM_SOUND = "auto_alarm_sound"
        const val AUTO_OFF = "off"
        const val AUTO_MOTION = "motion"
        const val AUTO_SOUND = "sound"
        const val AUTO_BOTH = "both"
        val AUTO_ALARM_MODES = setOf(AUTO_OFF, AUTO_MOTION, AUTO_SOUND, AUTO_BOTH)
        const val SIREN = "siren"
        const val AUTO_ALARM_ARMING_MS = 60_000L
        private const val DETECT_MOTION = AUTO_MOTION
        private const val DETECT_SOUND = AUTO_SOUND
        private const val AUTO_ALARM_COOLDOWN_MS = 60_000L
        private const val AUTO_SIREN_MS = 30_000L
        /** Libellés des modes d’alarme automatique (écrans caméra et spectateur). */
        val AUTO_ALARM_LABELS = linkedMapOf(AUTO_OFF to "Désactivée", AUTO_MOTION to "Sur mouvement", AUTO_SOUND to "Sur son", AUTO_BOTH to "Sur mouvement ou son")
        fun autoAlarmSoundLabel(id: String) = if (id == SIREN) "Sirène (30 s)" else DeterrentSounds.Effect.byId(id)?.label ?: "Sirène (30 s)"
        const val ACTION_SET_SOUND = "fr.oeildelynx.action.SET_SOUND"
        const val EXTRA_SOUND = "sound_trigger"
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

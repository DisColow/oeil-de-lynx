package fr.oeildelynx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Sur le téléphone spectateur : garde une connexion légère (sans image) avec chaque caméra
 * dont les alertes sont activées, et affiche une notification dès qu’elle commence un clip.
 * Pour les caméras choisies, télécharge aussi chaque nouveau clip dès qu’il est terminé et le range
 * dans la galerie de ce téléphone (Photos, dossier « Œil de Lynx »).
 *
 * Il n’y a pas de serveur de notifications : c’est ce téléphone qui reste connecté directement
 * à la caméra (Wi-Fi ou box internet), comme pour le direct. D’où le service de premier plan.
 */
class AlertService : Service() {
    private inner class Watch(val cameraId: String) : LiveClient.Listener {
        val client = LiveClient(applicationContext, cameraId, this, alertsOnly = true)
        @Volatile var connected = false
        @Volatile var recording: Boolean? = null
        @Volatile var lostSince = 0L
        @Volatile var lostNotified = false

        override fun onState(text: String, connected: Boolean) {
            val wasConnected = this.connected
            this.connected = connected
            if (connected) {
                lostSince = 0L
                if (lostNotified) { lostNotified = false; cancel(lostId(cameraId)) }
            } else if (wasConnected || lostSince == 0L) {
                lastClip = null
                if (lostSince == 0L) lostSince = SystemClock.elapsedRealtime()
            }
            checkLost(this)
            refreshOngoing()
        }

        override fun onFrame(bitmap: Bitmap) = bitmap.recycle()

        override fun onStatus(fields: Map<String, String>) {
            val nowRecording = fields["rec"] == "1"
            // Une seule alerte par clip, même si la connexion tombe et revient pendant l’enregistrement.
            if (nowRecording && recording != true && cameraId in ViewerStore.alertCameras(this@AlertService)) notifyMotion(cameraId, fields["why"].orEmpty())
            // Nouveau clip terminé (ou première nouvelle depuis la connexion) : on récupère ce qui manque.
            val clip = fields["clip"]
            if (clip != null && clip != lastClip) { lastClip = clip; scheduleClipSync(cameraId) }
            recording = nowRecording
            checkBattery(fields["bat"]?.toIntOrNull(), fields["chg"] == "1")
            val asleep = fields["standby"] == "1"
            if (asleep && standby == false && fields["standbywhy"] == MonitorService.REASON_BATTERY) notifyStandby(cameraId, fields["bat"])
            standby = asleep
            // Alarme automatique de la caméra en train de sonner : notification avec « Arrêter ».
            val ringing = fields["aaring"] == "1"
            val seq = fields["aaseq"]
            val wanted = cameraId in ViewerStore.alertCameras(this@AlertService)
            if (ringing && seq != null && seq != alarmSeq) {
                alarmSeq = seq
                if (wanted) { alarmShown = true; notifyAutoAlarm(this@AlertService, cameraId, fields["aawhy"].orEmpty()) }
            } else if (!ringing && alarmShown) {
                alarmShown = false
                notifyAlarmEnded(this@AlertService, cameraId, stoppedHere = false)
            }
        }

        @Volatile var alarmSeq: String? = null
        @Volatile var alarmShown = false

        @Volatile var standby: Boolean? = null
        @Volatile var lastClip: String? = null

        @Volatile var lowBatteryNotified = false

        /** Une alerte quand la batterie passe sous 15 % hors charge ; réarmée une fois rechargée (≥ 20 % ou branchée). */
        private fun checkBattery(percent: Int?, charging: Boolean) {
            if (percent == null || percent !in 0..100) return
            if (charging || percent >= LOW_BATTERY_REARM) {
                if (lowBatteryNotified) { lowBatteryNotified = false; cancel(batteryId(cameraId)) }
                return
            }
            if (percent <= LOW_BATTERY && !lowBatteryNotified) {
                lowBatteryNotified = true
                notifyLowBattery(cameraId, percent)
            }
        }
    }

    private val watches = mutableMapOf<String, Watch>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ONGOING, "Connexion aux caméras", NotificationManager.IMPORTANCE_MIN))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Alertes de mouvement", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_CLIPS, "Clips reçus", NotificationManager.IMPORTANCE_DEFAULT))
        val notification = ongoing("Connexion aux caméras…")
        if (Build.VERSION.SDK_INT >= 34) startForeground(ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ONGOING_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(watches) {
            val wanted = ViewerStore.watchedCameras(this).filter { ViewerStore.find(this, it) != null }.toSet()
            (watches.keys - wanted).forEach { id -> watches.remove(id)?.client?.stop(); cancel(lostId(id)) }
            (wanted - watches.keys).forEach { id -> watches[id] = Watch(id).also { it.client.start() } }
            if (watches.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        }
        refreshOngoing()
        return START_STICKY
    }

    override fun onDestroy() {
        synchronized(watches) { watches.values.forEach { it.client.stop() }; watches.clear() }
        clipSync.shutdownNow()
        super.onDestroy()
    }

    private fun checkLost(watch: Watch) {
        val since = watch.lostSince
        if (since == 0L || watch.lostNotified || SystemClock.elapsedRealtime() - since < LOST_ALERT_MS) return
        watch.lostNotified = true
        val name = ViewerStore.find(this, watch.cameraId)?.name ?: "Caméra"
        post(lostId(watch.cameraId), NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("$name injoignable")
            .setContentText("Plus de nouvelles de la caméra depuis 3 minutes (surveillance arrêtée, Wi-Fi ou box coupés ?).")
            .setContentIntent(openLive(watch.cameraId)).setAutoCancel(true).build())
    }

    private fun notifyMotion(cameraId: String, reason: String) {
        val name = ViewerStore.find(this, cameraId)?.name ?: "Caméra"
        val what = if (reason.isBlank()) "Activité" else reason
        post(motionId(cameraId), NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("$what détecté · $name")
            .setContentText("Enregistrement en cours. Touche pour voir le direct.")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(System.currentTimeMillis()).setShowWhen(true)
            .setContentIntent(openLive(cameraId)).setAutoCancel(true).build())
    }

    private fun refreshOngoing() {
        val lines = synchronized(watches) {
            watches.values.map { watch ->
                val name = ViewerStore.find(this, watch.cameraId)?.name ?: "Caméra"
                "$name : " + when { watch.connected && watch.recording == true -> "enregistrement en cours"; watch.connected -> "connectée"; else -> "injoignable, nouvelle tentative…" }
            }
        }
        if (lines.isEmpty()) return
        post(ONGOING_ID, ongoing(lines.joinToString("\n")))
    }

    private fun ongoing(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ONGOING)
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setContentTitle("Caméras suivies en arrière-plan")
        .setContentText(text.lineSequence().first())
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, ViewerActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(true).setOnlyAlertOnce(true).build()

    private fun openLive(cameraId: String): PendingIntent = PendingIntent.getActivity(
        this, cameraId.hashCode(),
        Intent(this, LiveActivity::class.java).putExtra(LiveActivity.EXTRA_CAMERA_ID, cameraId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun post(id: Int, notification: Notification) {
        try { getSystemService(NotificationManager::class.java).notify(id, notification) } catch (_: SecurityException) { }
    }

    private fun cancel(id: Int) = getSystemService(NotificationManager::class.java).cancel(id)

    private fun notifyLowBattery(cameraId: String, percent: Int) {
        val name = ViewerStore.find(this, cameraId)?.name ?: "Caméra"
        post(batteryId(cameraId), NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setContentTitle("Batterie faible · $name ($percent %)")
            .setContentText("Branche le téléphone caméra, sinon la surveillance s’arrêtera bientôt.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openLive(cameraId)).setAutoCancel(true).build())
    }

    private fun notifyStandby(cameraId: String, battery: String?) {
        val name = ViewerStore.find(this, cameraId)?.name ?: "Caméra"
        post(batteryId(cameraId), NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setContentTitle("$name en veille (batterie ${battery ?: "?"} %)")
            .setContentText("Plus de détection ni d’enregistrement. Reprise automatique dès que le téléphone caméra est branché.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openLive(cameraId)).setAutoCancel(true).build())
    }

    // ——— Réception automatique des clips ———

    private val clipSync = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "lynx-clip-sync").apply { isDaemon = true } }
    private val queuedSyncs = ConcurrentHashMap.newKeySet<String>()

    private fun scheduleClipSync(cameraId: String, attempt: Int = 1, delayMs: Long = 0L) {
        if (cameraId !in ViewerStore.autoClipCameras(this)) return
        if (attempt == 1 && !queuedSyncs.add(cameraId)) return // déjà prévu : il prendra aussi ce clip
        try {
            clipSync.schedule({
                queuedSyncs.remove(cameraId)
                val ok = try { syncClips(cameraId); true } catch (_: Exception) { false }
                if (!ok && attempt < SYNC_ATTEMPTS) scheduleClipSync(cameraId, attempt + 1, SYNC_RETRY_MS)
            }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) { queuedSyncs.remove(cameraId) } // service arrêté
    }

    /** Télécharge les clips de la caméra plus récents que le dernier reçu (bloquant, hors du thread principal). */
    private fun syncClips(cameraId: String) {
        if (cameraId !in ViewerStore.autoClipCameras(this)) return
        val camera = ViewerStore.find(this, cameraId) ?: return
        LiveClient(applicationContext, cameraId, NO_LISTENER).openGallery().use { gallery ->
            val response = gallery.request(mapOf("op" to "list"))
            if (response.type != LiveProtocol.TYPE_LIST) throw java.io.IOException("Réponse inattendue")
            val clips = String(response.payload, Charsets.UTF_8).lines().filter { it.isNotBlank() }
                .map { LiveProtocol.decodeFields(it) }
                .mapNotNull { f -> val id = f["id"]; val start = f["start"]?.toLongOrNull(); if (id.isNullOrEmpty() || start == null) null else id to start }
            val last = ViewerStore.lastSyncedClip(this, cameraId)
            if (last == 0L) {
                // Première synchronisation : seuls les clips à venir seront envoyés, pas tout l’historique.
                ViewerStore.setLastSyncedClip(this, cameraId, clips.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L)
                return
            }
            val fresh = clips.filter { it.second > last }.sortedBy { it.second }.takeLast(MAX_CLIPS_PER_SYNC)
            for ((id, start) in fresh) {
                if (cameraId !in ViewerStore.autoClipCameras(this)) return
                val name = saveClip(gallery, camera.name, id, start)
                ViewerStore.setLastSyncedClip(this, cameraId, start)
                notifyClip(cameraId, camera.name, name, start)
            }
        }
    }

    /** Écrit le clip dans Films/Œil de Lynx (visible dans Photos et dans la galerie de l’application). */
    private fun saveClip(gallery: LiveClient.GallerySession, cameraName: String, id: String, start: Long): String {
        val safe = cameraName.replace(Regex("[^A-Za-z0-9À-ÿ_-]+"), "_").trim('_').ifEmpty { "camera" }.take(24)
        val name = "recu_${safe}_$start.mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Œil de Lynx")
            put(MediaStore.MediaColumns.DATE_TAKEN, start)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw java.io.IOException("Stockage indisponible")
        try {
            contentResolver.openOutputStream(uri)?.buffered()?.use { output -> gallery.download(id, output) { } }
                ?: throw java.io.IOException("Stockage indisponible")
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            try { contentResolver.delete(uri, null, null) } catch (_: Exception) { }
            throw e
        }
        return name
    }

    private fun notifyClip(cameraId: String, cameraName: String, fileName: String, start: Long) {
        val open = PendingIntent.getActivity(this, clipId(cameraId),
            Intent(this, GalleryActivity::class.java).putExtra(GalleryActivity.EXTRA_FOCUS_NAME, fileName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.FRANCE).format(java.util.Date(start))
        post(clipId(cameraId), NotificationCompat.Builder(this, CHANNEL_CLIPS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Nouveau clip reçu · $cameraName")
            .setContentText("Clip de $time enregistré dans la galerie. Touche pour le voir.")
            .setWhen(start).setShowWhen(true)
            .setContentIntent(open).setAutoCancel(true).build())
    }

    private fun clipId(cameraId: String) = 210_000 + (cameraId.hashCode() and 0xffff)

    private fun batteryId(cameraId: String) = 140_000 + (cameraId.hashCode() and 0xffff)

    private fun motionId(cameraId: String) = 1000 + (cameraId.hashCode() and 0xffff)
    private fun lostId(cameraId: String) = 70_000 + (cameraId.hashCode() and 0xffff)

    companion object {
        private const val CHANNEL_ONGOING = "lynx-viewer-link"
        internal const val CHANNEL_ALERTS = "lynx-viewer-alerts"
        private const val ONGOING_ID = 77
        private const val LOST_ALERT_MS = 180_000L
        private const val LOW_BATTERY = 15
        private const val LOW_BATTERY_REARM = 20
        private const val CHANNEL_CLIPS = "lynx-viewer-clips"
        /** Au plus autant de clips rattrapés d’un coup (après une longue coupure). */
        private const val MAX_CLIPS_PER_SYNC = 10
        private const val SYNC_RETRY_MS = 120_000L
        private const val SYNC_ATTEMPTS = 5
        private val NO_LISTENER = object : LiveClient.Listener {
            override fun onState(text: String, connected: Boolean) {}
            override fun onFrame(bitmap: Bitmap) = bitmap.recycle()
            override fun onStatus(fields: Map<String, String>) {}
        }

        internal fun alarmId(cameraId: String) = 280_000 + (cameraId.hashCode() and 0xffff)

        /** Alarme automatique déclenchée sur la caméra : notification prioritaire avec un bouton « Arrêter ». */
        internal fun notifyAutoAlarm(context: Context, cameraId: String, why: String) {
            val name = ViewerStore.find(context, cameraId)?.name ?: "Caméra"
            val stop = PendingIntent.getBroadcast(context, alarmId(cameraId),
                Intent(context, AlarmStopReceiver::class.java).putExtra(AlarmStopReceiver.EXTRA_CAMERA, cameraId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val live = PendingIntent.getActivity(context, cameraId.hashCode(),
                Intent(context, LiveActivity::class.java).putExtra(LiveActivity.EXTRA_CAMERA_ID, cameraId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(java.util.Date())
            postStatic(context, alarmId(cameraId), NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Alarme déclenchée · $name")
                .setContentText("${why.ifBlank { "Détection" }} à $time. « Arrêter » coupe le son sans désactiver l’alarme.")
                .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_HIGH)
                .addAction(android.R.drawable.ic_media_pause, "Arrêter l’alarme", stop)
                .setContentIntent(live).setAutoCancel(true).build())
        }

        internal fun notifyAlarmEnded(context: Context, cameraId: String, stoppedHere: Boolean, error: String? = null) {
            val name = ViewerStore.find(context, cameraId)?.name ?: "Caméra"
            val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(java.util.Date())
            val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setOnlyAlertOnce(true).setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
            when {
                error != null -> builder.setContentTitle("Impossible d’arrêter l’alarme · $name").setContentText(error)
                    .addAction(android.R.drawable.ic_media_pause, "Réessayer", PendingIntent.getBroadcast(context, alarmId(cameraId),
                        Intent(context, AlarmStopReceiver::class.java).putExtra(AlarmStopReceiver.EXTRA_CAMERA, cameraId),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                stoppedHere -> builder.setContentTitle("Alarme arrêtée · $name").setContentText("Arrêtée à $time. Toujours active : elle pourra resonner dans 1 minute si ça bouge encore.")
                else -> builder.setContentTitle("Alarme terminée · $name").setContentText("Le son s’est arrêté à $time. L’alarme reste active.")
            }
            postStatic(context, alarmId(cameraId), builder.build())
        }

        private fun postStatic(context: Context, id: Int, notification: Notification) {
            try { context.getSystemService(NotificationManager::class.java).notify(id, notification) } catch (_: SecurityException) { }
        }

        /** Démarre, met à jour ou arrête le service selon les caméras dont les alertes ou la réception des clips sont activées. */
        fun sync(context: Context) {
            val intent = Intent(context, AlertService::class.java)
            if (ViewerStore.watchedCameras(context).isEmpty()) context.stopService(intent)
            else try { ContextCompat.startForegroundService(context, intent) } catch (_: Exception) { }
        }
    }
}

/** Bouton « Arrêter l’alarme » d’une notification : coupe le son sur la caméra sans désactiver l’alarme. */
class AlarmStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cameraId = intent.getStringExtra(EXTRA_CAMERA) ?: return
        val pending = goAsync()
        Thread {
            try {
                LiveClient(context.applicationContext, cameraId, object : LiveClient.Listener {
                    override fun onState(text: String, connected: Boolean) {}
                    override fun onFrame(bitmap: Bitmap) = bitmap.recycle()
                    override fun onStatus(fields: Map<String, String>) {}
                }).control(mapOf("op" to "alarm", "value" to "0"))
                AlertService.notifyAlarmEnded(context, cameraId, stoppedHere = true)
            } catch (error: Exception) {
                AlertService.notifyAlarmEnded(context, cameraId, stoppedHere = false, error = error.message ?: "Caméra injoignable.")
            } finally { pending.finish() }
        }.start()
    }

    companion object { const val EXTRA_CAMERA = "camera_id" }
}

/** Relance les alertes après un redémarrage du téléphone spectateur. */
class AlertBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) AlertService.sync(context)
    }
}

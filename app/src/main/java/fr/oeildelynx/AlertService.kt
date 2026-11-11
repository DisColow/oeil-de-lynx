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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Sur le téléphone spectateur : garde une connexion légère (sans image) avec chaque caméra
 * dont les alertes sont activées, et affiche une notification dès qu’elle commence un clip.
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
                if (lostSince == 0L) lostSince = SystemClock.elapsedRealtime()
            }
            checkLost(this)
            refreshOngoing()
        }

        override fun onFrame(bitmap: Bitmap) = bitmap.recycle()

        override fun onStatus(fields: Map<String, String>) {
            val nowRecording = fields["rec"] == "1"
            // Une seule alerte par clip, même si la connexion tombe et revient pendant l’enregistrement.
            if (nowRecording && recording != true) notifyMotion(cameraId, fields["why"].orEmpty())
            recording = nowRecording
        }
    }

    private val watches = mutableMapOf<String, Watch>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ONGOING, "Connexion aux caméras", NotificationManager.IMPORTANCE_MIN))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Alertes de mouvement", NotificationManager.IMPORTANCE_HIGH))
        val notification = ongoing("Connexion aux caméras…")
        if (Build.VERSION.SDK_INT >= 34) startForeground(ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ONGOING_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(watches) {
            val wanted = ViewerStore.alertCameras(this).filter { ViewerStore.find(this, it) != null }.toSet()
            (watches.keys - wanted).forEach { id -> watches.remove(id)?.client?.stop(); cancel(lostId(id)) }
            (wanted - watches.keys).forEach { id -> watches[id] = Watch(id).also { it.client.start() } }
            if (watches.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        }
        refreshOngoing()
        return START_STICKY
    }

    override fun onDestroy() {
        synchronized(watches) { watches.values.forEach { it.client.stop() }; watches.clear() }
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
        .setContentTitle("Alertes de mouvement actives")
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

    private fun motionId(cameraId: String) = 1000 + (cameraId.hashCode() and 0xffff)
    private fun lostId(cameraId: String) = 70_000 + (cameraId.hashCode() and 0xffff)

    companion object {
        private const val CHANNEL_ONGOING = "lynx-viewer-link"
        private const val CHANNEL_ALERTS = "lynx-viewer-alerts"
        private const val ONGOING_ID = 77
        private const val LOST_ALERT_MS = 180_000L

        /** Démarre, met à jour ou arrête le service selon les caméras dont les alertes sont activées. */
        fun sync(context: Context) {
            val intent = Intent(context, AlertService::class.java)
            if (ViewerStore.alertCameras(context).isEmpty()) context.stopService(intent)
            else try { ContextCompat.startForegroundService(context, intent) } catch (_: Exception) { }
        }
    }
}

/** Relance les alertes après un redémarrage du téléphone spectateur. */
class AlertBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) AlertService.sync(context)
    }
}

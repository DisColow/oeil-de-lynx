package fr.oeildelynx

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Galerie du téléphone caméra, consultée depuis le téléphone spectateur. Les clips sont listés puis
 * téléchargés directement depuis la caméra (connexion chiffrée, sans serveur), et lus localement.
 * La caméra doit être en surveillance : c’est son service qui répond.
 */
class RemoteGalleryActivity : ComponentActivity() {
    private class Clip(val id: String, val name: String, val start: Long, val duration: Long, val size: Long)

    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var downloadRequested = false
    @Volatile private var pendingThumbs: List<Pair<Clip, ImageView>> = emptyList()
    // Miniatures déjà reçues (≤ 16 Mo), pour ne pas les redemander après un téléchargement.
    private val thumbCache = object : android.util.LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private var session: LiveClient.GallerySession? = null
    private lateinit var cameraId: String
    private lateinit var root: FrameLayout
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private var player: ClipPlayer? = null
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy · HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        cameraId = intent.getStringExtra(LiveActivity.EXTRA_CAMERA_ID) ?: run { finish(); return }
        val camera = ViewerStore.find(this, cameraId) ?: run { finish(); return }
        root = FrameLayout(this).apply { setBackgroundColor(LynxUi.BACKGROUND) }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (player != null) showList() else finish() }
        })
        buildList(camera.name)
        load()
    }

    private fun dp(value: Int) = LynxUi.dp(this, value)

    private fun buildList(cameraName: String) {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(LynxUi.header(this, "Galerie", "Clips de $cameraName",
            LynxUi.iconButton(this, R.drawable.ic_refresh, "Actualiser", 0x66223029) { load() }) { finish() })
        status = LynxUi.body(this, "Connexion à la caméra…", 13.5f).apply { setPadding(dp(20), 0, dp(20), dp(6)) }
        page.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(20)) }
        page.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.removeAllViews()
        root.addView(page)
    }

    /** (Re)connexion puis liste des clips ; les miniatures suivent une à une. */
    private fun load() {
        status.text = "Connexion à la caméra…"
        list.removeAllViews()
        worker.execute {
            try {
                val gallery = session ?: LiveClient(applicationContext, cameraId, NO_LISTENER).openGallery().also { session = it }
                val response = gallery.request(mapOf("op" to "list"))
                if (response.type != LiveProtocol.TYPE_LIST) throw java.io.IOException("Réponse inattendue de la caméra")
                val clips = String(response.payload, Charsets.UTF_8).lines().filter { it.isNotBlank() }.map { line ->
                    val fields = LiveProtocol.decodeFields(line)
                    Clip(fields["id"].orEmpty(), fields["name"].orEmpty(), fields["start"]?.toLongOrNull() ?: 0L,
                        fields["dur"]?.toLongOrNull() ?: 0L, fields["size"]?.toLongOrNull() ?: 0L)
                }
                val thumbs = runOnUiThreadAndGet { showClips(clips) }
                pendingThumbs = clips.zip(thumbs)
                fetchThumbnails()
            } catch (error: Exception) {
                session?.close(); session = null
                runOnUiThread {
                    if (!isDestroyed) status.text = "Caméra injoignable : ${error.message ?: "erreur réseau"}\n" +
                        "La galerie n’est accessible que pendant la surveillance, comme le direct."
                }
            }
        }
    }

    /**
     * Miniatures une à une, sur la même connexion que les téléchargements. Un téléchargement demandé
     * passe devant : la boucle s’arrête aussitôt et reprend après lui (les miniatures reçues sont gardées).
     */
    private fun fetchThumbnails() {
        val gallery = session ?: return
        for ((clip, view) in pendingThumbs) {
            if (downloadRequested || isDestroyed) return
            val cached = thumbCache.get(clip.id)
            if (cached != null) { runOnUiThread { view.setImageBitmap(cached) }; continue }
            val reply = try { gallery.request(mapOf("op" to "thumb", "id" to clip.id)) } catch (_: Exception) { return }
            val bitmap = if (reply.type == LiveProtocol.TYPE_THUMB && reply.payload.isNotEmpty())
                BitmapFactory.decodeByteArray(reply.payload, 0, reply.payload.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }) else null
            if (bitmap != null) {
                thumbCache.put(clip.id, bitmap)
                runOnUiThread { if (!isDestroyed) view.setImageBitmap(bitmap) }
            }
        }
    }

    private fun showClips(clips: List<Clip>): List<ImageView> {
        status.text = if (clips.isEmpty()) "Aucun clip sur la caméra." else "${clips.size} clip(s) · toucher pour télécharger et lire"
        return clips.map { clip ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(10), dp(14), dp(10))
                background = LynxUi.pressable(this@RemoteGalleryActivity, LynxUi.CARD, 20)
                setOnClickListener { download(clip) }
            }
            val frame = FrameLayout(this).apply { background = LynxUi.rounded(this@RemoteGalleryActivity, 0xff222a25.toInt(), 14); clipToOutline = true }
            val thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            frame.addView(thumb, FrameLayout.LayoutParams(-1, -1))
            frame.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_play); val pad = dp(7); setPadding(pad, pad, pad, pad); background = LynxUi.circle(0x99000000.toInt())
            }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
            row.addView(frame, LinearLayout.LayoutParams(dp(112), dp(80)))
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
            details.addView(TextView(this).apply {
                text = SimpleDateFormat("EEE d MMM", Locale.FRANCE).format(Date(clip.start)).replaceFirstChar { it.titlecase(Locale.FRANCE) }
                textSize = 15f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setTextColor(LynxUi.TEXT)
            })
            details.addView(LynxUi.body(this, SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date(clip.start)), 13f).apply { setPadding(0, dp(3), 0, 0) })
            details.addView(LynxUi.body(this, "${formatDuration(clip.duration)} · ${formatSize(clip.size)}", 12f, LynxUi.ACCENT).apply { setPadding(0, dp(6), 0, 0) })
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
            thumb
        }
    }

    /** Télécharge le clip dans le cache (réutilisé s’il est déjà là), puis le lit. */
    private fun download(clip: Clip) {
        val file = File(File(cacheDir, "remote").apply { mkdirs() }, "${cameraId.take(8)}_${clip.id}.mp4")
        // Un fichier complet n’existe qu’après un transfert réussi (le partiel porte l’extension .part).
        if (file.exists() && file.length() > 0) { play(file, clip); return }
        // Panneau de progression : pourcentage, quantité reçue, vitesse et temps restant estimé.
        val percent = LynxUi.title(this, "0 %", 34f).apply { gravity = Gravity.CENTER }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(LynxUi.ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(LynxUi.OUTLINE)
        }
        val amount = LynxUi.body(this, "0 ko sur ${TransferStats.formatSize(clip.size)}", 14f, LynxUi.TEXT)
        val speed = LynxUi.body(this, "Connexion à la caméra…", 14f, LynxUi.TEXT)
        val remaining = LynxUi.body(this, "Temps restant : calcul…", 14f, LynxUi.MUTED)
        var cancelled = false
        val card = LynxUi.card(this, 22).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(LynxUi.overline(this@RemoteGalleryActivity, "Téléchargement du clip", LynxUi.MUTED).apply { gravity = Gravity.CENTER })
            addView(LynxUi.body(this@RemoteGalleryActivity, SimpleDateFormat("EEE d MMM · HH:mm:ss", Locale.FRANCE).format(Date(clip.start)), 13f)
                .apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(10)) })
            addView(percent)
            addView(progress, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(12); bottomMargin = dp(16) })
            addView(statLine(R.drawable.ic_gallery, amount))
            addView(statLine(R.drawable.ic_wifi, speed))
            addView(statLine(R.drawable.ic_timer, remaining))
            addView(LynxUi.button(this@RemoteGalleryActivity, "Annuler", LynxUi.CARD_HIGH) {
                cancelled = true
                // Fermer la connexion interrompt le transfert en cours ; le fichier partiel est supprimé.
                val current = session
                Thread { current?.close() }.start()
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        }
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xe60b110e.toInt()); isClickable = true
            addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { leftMargin = dp(20); rightMargin = dp(20) })
        }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        // Met en pause le chargement des miniatures pour que le transfert démarre tout de suite.
        downloadRequested = true
        worker.execute {
            val partial = File(file.path + ".part")
            val stats = TransferStats(clip.size, android.os.SystemClock.elapsedRealtime())
            var lastUi = 0L
            try {
                val gallery = session ?: LiveClient(applicationContext, cameraId, NO_LISTENER).openGallery().also { session = it }
                if (cancelled) throw java.io.IOException("annulé")
                runOnUiThread { speed.text = "Démarrage du transfert…" }
                partial.outputStream().buffered().use { output ->
                    gallery.download(clip.id, output, onSize = { size -> stats.total = size }) { received ->
                        val now = android.os.SystemClock.elapsedRealtime()
                        stats.update(now, received)
                        // Jusqu’à huit rafraîchissements par seconde ; le dernier morceau est toujours affiché.
                        if (now - lastUi < 120 && received < stats.total) return@download
                        lastUi = now
                        val fraction = stats.fraction
                        val rate = stats.bytesPerSecond
                        val left = stats.remainingSeconds
                        val total = stats.total
                        runOnUiThread {
                            if (fraction != null) { percent.text = "${(fraction * 100).toInt()} %"; progress.progress = (fraction * 1000).toInt() }
                            amount.text = if (total > 0) "${TransferStats.formatSize(received)} sur ${TransferStats.formatSize(total)}"
                                else "${TransferStats.formatSize(received)} reçus"
                            if (rate != null) speed.text = TransferStats.formatSpeed(rate)
                            if (left != null) remaining.text = TransferStats.formatRemaining(left)
                        }
                    }
                }
                partial.renameTo(file)
                trimCache()
                downloadRequested = false
                // Affiche « 100 % » un court instant avant d’ouvrir la vidéo : on voit que le transfert est complet.
                runOnUiThread {
                    percent.text = "100 %"; progress.progress = 1000; remaining.text = "Terminé"
                    speed.text = stats.bytesPerSecond?.let { "Moyenne : " + TransferStats.formatSpeed(it) } ?: speed.text
                }
                Thread.sleep(600)
                runOnUiThread { root.removeView(overlay); if (!isDestroyed) play(file, clip) }
            } catch (error: Exception) {
                partial.delete(); session?.close(); session = null
                downloadRequested = false
                runOnUiThread {
                    root.removeView(overlay)
                    if (!isDestroyed) status.text = if (cancelled) "Téléchargement annulé." else "Téléchargement interrompu : ${error.message ?: "erreur réseau"}"
                }
            }
        }
    }

    /** Ligne de statistique du téléchargement : icône + valeur. */
    private fun statLine(@androidx.annotation.DrawableRes icon: Int, value: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, dp(5))
        addView(LynxUi.icon(this@RemoteGalleryActivity, icon, LynxUi.ACCENT, 18))
        addView(value, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
    }

    private fun play(file: File, clip: Clip) {
        root.removeAllViews()
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val video = ClipPlayer(this).apply {
            setBackgroundColor(Color.BLACK)
            onError = { status.text = "Impossible de lire cette vidéo." }
        }
        page.addView(LynxUi.header(this, "Lecture", SimpleDateFormat("EEE d MMM · HH:mm:ss", Locale.FRANCE).format(Date(clip.start)),
            LynxUi.iconButton(this, R.drawable.ic_rotate, "Pivoter la vidéo", 0x66223029) { video.rotateQuarter() }) { showList() })
        // Le lecteur occupe toute la place disponible et garde les proportions de la vidéo.
        page.addView(video, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(page)
        player = video
        video.play(Uri.fromFile(file))
    }

    private fun showList() {
        player?.release(); player = null
        buildList(ViewerStore.find(this, cameraId)?.name ?: "Caméra")
        load()
    }

    /** Garde au plus ~300 Mo de clips téléchargés, en supprimant les plus anciens. */
    private fun trimCache() {
        val files = File(cacheDir, "remote").listFiles()?.filter { it.extension == "mp4" }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (file in files) { total += file.length(); if (total > 300L * 1024 * 1024) file.delete() }
    }

    private fun <T> runOnUiThreadAndGet(block: () -> T): T {
        val result = java.util.concurrent.FutureTask(block)
        runOnUiThread(result)
        return result.get()
    }

    private fun formatDuration(millis: Long): String {
        val total = millis.coerceAtLeast(0) / 1000
        return String.format(Locale.getDefault(), "%02d:%02d", total / 60, total % 60)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f Mo", bytes / 1024.0 / 1024.0)
        else -> "${bytes / 1024} ko"
    }

    override fun onDestroy() {
        player?.release()
        session?.close()
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onResume() { super.onResume(); enableImmersiveMode() }

    companion object {
        private val NO_LISTENER = object : LiveClient.Listener {
            override fun onState(text: String, connected: Boolean) {}
            override fun onFrame(bitmap: Bitmap) = bitmap.recycle()
            override fun onStatus(fields: Map<String, String>) {}
        }
    }
}

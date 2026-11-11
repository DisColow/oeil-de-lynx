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
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
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
    private var session: LiveClient.GallerySession? = null
    private lateinit var cameraId: String
    private lateinit var root: FrameLayout
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private var player: VideoView? = null
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
        page.addView(LynxUi.header(this, "Galerie", "CLIPS DE ${cameraName.uppercase(Locale.getDefault())}") { finish() })
        status = LynxUi.body(this, "Connexion à la caméra…").apply { setPadding(dp(18), 0, dp(18), dp(8)) }
        page.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(16)) }
        page.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        page.addView(LynxUi.button(this, "Actualiser", LynxUi.CARD) { load() },
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), 0, dp(16), dp(16)) })
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
                for ((clip, view) in clips.zip(thumbs)) {
                    val reply = gallery.request(mapOf("op" to "thumb", "id" to clip.id))
                    val bitmap = if (reply.type == LiveProtocol.TYPE_THUMB && reply.payload.isNotEmpty())
                        BitmapFactory.decodeByteArray(reply.payload, 0, reply.payload.size) else null
                    if (bitmap != null) runOnUiThread { if (!isDestroyed) view.setImageBitmap(bitmap) }
                }
            } catch (error: Exception) {
                session?.close(); session = null
                runOnUiThread {
                    if (!isDestroyed) status.text = "Caméra injoignable : ${error.message ?: "erreur réseau"}\n" +
                        "La galerie n’est accessible que pendant la surveillance, comme le direct."
                }
            }
        }
    }

    private fun showClips(clips: List<Clip>): List<ImageView> {
        status.text = if (clips.isEmpty()) "Aucun clip sur la caméra." else "${clips.size} clip(s) · toucher pour télécharger et lire"
        return clips.map { clip ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(10), dp(12), dp(10))
                background = LynxUi.rounded(this@RemoteGalleryActivity, LynxUi.CARD, 16)
                setOnClickListener { download(clip) }
            }
            val thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xff222a25.toInt()) }
            row.addView(thumb, LinearLayout.LayoutParams(dp(102), dp(76)))
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), 0, 0, 0) }
            details.addView(TextView(this).apply {
                text = dateFormat.format(Date(clip.start)); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            })
            details.addView(LynxUi.body(this, "${formatDuration(clip.duration)} · ${formatSize(clip.size)}", 12f).apply { setPadding(0, dp(6), 0, 0) })
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
            thumb
        }
    }

    /** Télécharge le clip dans le cache (réutilisé s’il est déjà là), puis le lit. */
    private fun download(clip: Clip) {
        val file = File(File(cacheDir, "remote").apply { mkdirs() }, "${cameraId.take(8)}_${clip.id}.mp4")
        if (file.exists() && file.length() == clip.size && clip.size > 0) { play(file, clip); return }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val label = LynxUi.body(this, "Téléchargement…", 14f, Color.WHITE)
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(32), 0, dp(32), 0)
            setBackgroundColor(0xdd0f1411.toInt()); isClickable = true
            addView(label); addView(progress, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        worker.execute {
            val partial = File(file.path + ".part")
            try {
                val gallery = session ?: LiveClient(applicationContext, cameraId, NO_LISTENER).openGallery().also { session = it }
                partial.outputStream().buffered().use { output ->
                    gallery.download(clip.id, output) { received ->
                        runOnUiThread {
                            label.text = "Téléchargement… ${formatSize(received)} / ${formatSize(clip.size)}"
                            if (clip.size > 0) progress.progress = (received * 1000 / clip.size).toInt()
                        }
                    }
                }
                partial.renameTo(file)
                trimCache()
                runOnUiThread { root.removeView(overlay); if (!isDestroyed) play(file, clip) }
            } catch (error: Exception) {
                partial.delete(); session?.close(); session = null
                runOnUiThread {
                    root.removeView(overlay)
                    if (!isDestroyed) status.text = "Téléchargement interrompu : ${error.message ?: "erreur réseau"}"
                }
            }
        }
    }

    private fun play(file: File, clip: Clip) {
        root.removeAllViews()
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        page.addView(LynxUi.header(this, "Lecture", dateFormat.format(Date(clip.start))) { showList() })
        // Fond noir sur le conteneur, pas sur la VideoView : il masquerait l’image.
        val stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val video = VideoView(this)
        stage.addView(video, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        page.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(page)
        player = video
        val controller = MediaController(this).also { it.setAnchorView(video) }
        video.setMediaController(controller)
        video.setVideoURI(Uri.fromFile(file))
        video.setOnPreparedListener { video.start(); controller.show(3000) }
    }

    private fun showList() {
        player?.stopPlayback(); player = null
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
        player?.stopPlayback()
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

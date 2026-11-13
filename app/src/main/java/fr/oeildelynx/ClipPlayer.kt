package fr.oeildelynx

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.MediaController

/**
 * Lecteur vidéo des galeries : remplit l’espace disponible en gardant les proportions, et peut
 * tourner l’image d’un quart de tour (clips enregistrés couchés par les versions antérieures à 1.9.0).
 * TextureView permet cette rotation, contrairement à VideoView.
 */
internal class ClipPlayer(context: Context) : FrameLayout(context), MediaController.MediaPlayerControl {
    private val texture = TextureView(context)
    private val controller = MediaController(context)
    private var player: MediaPlayer? = null
    private var uri: Uri? = null
    private var surface: Surface? = null
    // Taille affichée de la vidéo, rotation des métadonnées déjà appliquée.
    private var videoWidth = 0
    private var videoHeight = 0
    private var userRotation = 0
    var onError: () -> Unit = {}

    init {
        addView(texture, LayoutParams(-1, -1))
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                surface = Surface(st); startIfReady()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = applyTransform()
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { release(); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        controller.setMediaPlayer(this)
        controller.setAnchorView(this)
        setOnClickListener { if (controller.isShowing) controller.hide() else controller.show(3000) }
    }

    fun play(uri: Uri) {
        this.uri = uri
        try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rotation % 180 == 0) { videoWidth = w; videoHeight = h } else { videoWidth = h; videoHeight = w }
            }
        } catch (_: Exception) { }
        startIfReady()
    }

    /** Tourne l’image d’un quart de tour dans le sens horaire. */
    fun rotateQuarter() { userRotation = (userRotation + 90) % 360; applyTransform() }

    private fun startIfReady() {
        val target = uri ?: return
        val out = surface ?: return
        if (player != null) return
        player = MediaPlayer().apply {
            try {
                setDataSource(context, target)
                setSurface(out)
                setOnPreparedListener { it.start(); applyTransform(); controller.show(3000) }
                setOnVideoSizeChangedListener { _, _, _ -> applyTransform() }
                setOnErrorListener { _, _, _ -> onError(); true }
                prepareAsync()
            } catch (_: Exception) { onError() }
        }
    }

    /** Proportions conservées et rotation choisie : le contenu, étiré à la vue, est remis à l’échelle puis tourné. */
    private fun applyTransform() {
        val w = texture.width.toFloat(); val h = texture.height.toFloat()
        if (w <= 0f || h <= 0f || videoWidth <= 0 || videoHeight <= 0) return
        val quarter = userRotation % 180 != 0
        val shownW = if (quarter) videoHeight.toFloat() else videoWidth.toFloat()
        val shownH = if (quarter) videoWidth.toFloat() else videoHeight.toFloat()
        val scale = minOf(w / shownW, h / shownH)
        val boxW = videoWidth * scale; val boxH = videoHeight * scale
        texture.setTransform(Matrix().apply {
            setScale(boxW / w, boxH / h, w / 2, h / 2)
            postRotate(userRotation.toFloat(), w / 2, h / 2)
        })
    }

    fun release() {
        controller.hide()
        try { player?.stop() } catch (_: Exception) { }
        player?.release(); player = null
        surface?.release(); surface = null
    }

    // MediaController.MediaPlayerControl
    override fun start() { player?.start() }
    override fun pause() { player?.pause() }
    override fun getDuration() = try { player?.duration ?: 0 } catch (_: Exception) { 0 }
    override fun getCurrentPosition() = try { player?.currentPosition ?: 0 } catch (_: Exception) { 0 }
    override fun seekTo(pos: Int) { player?.seekTo(pos) }
    override fun isPlaying() = try { player?.isPlaying == true } catch (_: Exception) { false }
    override fun getBufferPercentage() = 100
    override fun canPause() = true
    override fun canSeekBackward() = true
    override fun canSeekForward() = true
    override fun getAudioSessionId() = player?.audioSessionId ?: 0
}

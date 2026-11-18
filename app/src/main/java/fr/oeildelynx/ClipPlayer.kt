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
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

/**
 * Lecteur vidéo des galeries : remplit l’espace disponible en gardant les proportions, et peut
 * tourner l’image d’un quart de tour (clips enregistrés couchés par les versions antérieures à 1.9.0).
 * TextureView permet cette rotation, contrairement à VideoView.
 */
internal class ClipPlayer(context: Context) : FrameLayout(context) {
    private val texture = TextureView(context)
    // Commandes dans la même fenêtre que l’écran (pas de MediaController, qui ouvre sa propre fenêtre
    // et empêchait le geste / bouton retour d’Android 16 d’atteindre la galerie).
    private val playButton = ImageView(context)
    private val seek = SeekBar(context)
    private val time = TextView(context)
    private val controls = LinearLayout(context)
    private val hideControls = Runnable { controls.animate().alpha(0f).setDuration(250).withEndAction { controls.visibility = View.GONE } }
    private val tick = object : Runnable {
        override fun run() { refreshControls(); if (player != null) postDelayed(this, 250) }
    }
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
        buildControls()
        setOnClickListener { if (controls.visibility == View.VISIBLE) hideNow() else showControls() }
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
                setOnPreparedListener {
                    it.start(); applyTransform(); showControls()
                    removeCallbacks(tick); post(tick)
                }
                setOnCompletionListener { refreshControls(); showControls(keep = true) }
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
        removeCallbacks(tick); removeCallbacks(hideControls)
        try { player?.stop() } catch (_: Exception) { }
        player?.release(); player = null
        surface?.release(); surface = null
    }

    private fun dp(value: Int) = LynxUi.dp(context, value)

    private fun buildControls() {
        playButton.apply {
            setImageResource(R.drawable.ic_pause); imageTintList = ColorStateList.valueOf(LynxUi.TEXT)
            val pad = dp(11); setPadding(pad, pad, pad, pad); background = LynxUi.pressableCircle(0x66223029)
            contentDescription = "Lecture / pause"
            setOnClickListener { togglePlay() }
        }
        seek.apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(LynxUi.ACCENT); thumbTintList = ColorStateList.valueOf(LynxUi.ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    val p = player ?: return
                    if (fromUser) { p.seekTo((p.duration.toLong() * progress / 1000).toInt()); showControls() }
                }
                override fun onStartTrackingTouch(bar: SeekBar) { removeCallbacks(hideControls) }
                override fun onStopTrackingTouch(bar: SeekBar) { showControls() }
            })
        }
        time.apply { textSize = 12f; setTextColor(LynxUi.TEXT); setPadding(dp(8), 0, 0, 0) }
        controls.apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(16), dp(18))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xe60b110e.toInt(), 0x000b110e))
            addView(playButton, LinearLayout.LayoutParams(dp(46), dp(46)))
            addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
            addView(time)
            visibility = View.GONE
            isClickable = true // un toucher sur la barre ne masque pas les commandes
        }
        addView(controls, LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    private fun togglePlay() {
        val p = player ?: return
        try { if (p.isPlaying) p.pause() else p.start() } catch (_: Exception) { }
        refreshControls(); showControls(keep = !isPlaying())
        removeCallbacks(tick); post(tick)
    }

    private fun showControls(keep: Boolean = false) {
        removeCallbacks(hideControls)
        controls.animate().cancel(); controls.alpha = 1f; controls.visibility = View.VISIBLE
        refreshControls()
        if (!keep && isPlaying()) postDelayed(hideControls, 3_000)
    }

    private fun hideNow() { removeCallbacks(hideControls); post(hideControls) }

    private fun refreshControls() {
        val p = player ?: return
        val duration = try { p.duration } catch (_: Exception) { 0 }
        val position = try { p.currentPosition } catch (_: Exception) { 0 }
        playButton.setImageResource(if (isPlaying()) R.drawable.ic_pause else R.drawable.ic_play)
        if (duration > 0) seek.progress = (position.toLong() * 1000 / duration).toInt()
        time.text = "${format(position)} / ${format(duration)}"
    }

    private fun format(ms: Int): String { val s = (ms / 1000).coerceAtLeast(0); return String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60) }

    private fun isPlaying() = try { player?.isPlaying == true } catch (_: Exception) { false }
}

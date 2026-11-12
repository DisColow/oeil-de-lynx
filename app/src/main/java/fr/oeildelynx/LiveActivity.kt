package fr.oeildelynx

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import androidx.annotation.DrawableRes
import android.graphics.Bitmap
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Affiche le direct d’une caméra appairée, en plein écran, avec reconnexion automatique. */
class LiveActivity : ComponentActivity(), LiveClient.Listener {
    private lateinit var image: ImageView
    private lateinit var title: TextView
    private lateinit var state: TextView
    private lateinit var recBadge: View
    private lateinit var waiting: View
    private lateinit var waitingText: TextView
    private lateinit var stateDot: View
    private var client: LiveClient? = null
    private var shownBitmap: Bitmap? = null
    private var lastStatus: Map<String, String> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        // Le téléphone spectateur reste allumé pendant qu’on regarde.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dp = { value: Int -> LynxUi.dp(this, value) }
        val cameraId = intent.getStringExtra(EXTRA_CAMERA_ID)
        val camera = cameraId?.let { ViewerStore.find(this, it) }
        if (camera == null) { finish(); return }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
        // En attente de la première image : œil de la marque + état de la connexion.
        waitingText = LynxUi.body(this, "Connexion à ${camera.name}…", 14f).apply { gravity = Gravity.CENTER; setPadding(dp(32), dp(18), dp(32), 0) }
        waiting = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(LynxUi.BACKGROUND)
            addView(ImageView(this@LiveActivity).apply {
                setImageResource(R.drawable.ic_cat_eye_monochrome); imageTintList = ColorStateList.valueOf(LynxUi.ACCENT)
                background = LynxUi.circle(0xff17261d.toInt())
            }, LinearLayout.LayoutParams(dp(88), dp(88)))
            addView(waitingText)
        }
        root.addView(waiting, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(30), dp(14), dp(26))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xe60b110e.toInt(), 0x990b110e.toInt(), 0x000b110e))
        }
        top.addView(LynxUi.iconButton(this, R.drawable.ic_back, "Retour", 0x00000000) { finish() })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(6), 0) }
        title = LynxUi.title(this, camera.name, 17f).apply { maxLines = 1 }
        stateDot = View(this).apply { background = LynxUi.circle(LynxUi.MUTED) }
        state = TextView(this).apply { text = "Connexion…"; textSize = 12f; setTextColor(LynxUi.MUTED); maxLines = 1 }
        texts.addView(title)
        texts.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, 0)
            addView(stateDot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(6) })
            addView(state)
        })
        top.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        recBadge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(11), dp(5)); background = LynxUi.rounded(this@LiveActivity, 0xd9200f10.toInt(), 14); visibility = View.GONE
            addView(View(this@LiveActivity).apply { background = LynxUi.circle(LynxUi.DANGER) }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(6) })
            addView(TextView(this@LiveActivity).apply { text = "REC"; textSize = 11.5f; letterSpacing = .12f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD })
        }
        top.addView(recBadge)
        top.addView(iconButton(R.drawable.ic_sun, "Exposition") { showExposure() })
        top.addView(iconButton(R.drawable.ic_gallery, "Galerie de la caméra") {
            startActivity(Intent(this, RemoteGalleryActivity::class.java).putExtra(EXTRA_CAMERA_ID, camera.id))
        })
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)

        client = LiveClient(applicationContext, camera.id, this)
    }

    private fun iconButton(@DrawableRes icon: Int, description: String, action: () -> Unit) =
        LynxUi.iconButton(this, icon, description, 0x66223029) { action() }.apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = LynxUi.dp(this@LiveActivity, 8)
        }

    /** Règle l’exposition de la caméra à distance ; la plage vient de l’état envoyé par la caméra. */
    private fun showExposure() {
        val status = lastStatus
        val min = status["expmin"]?.toIntOrNull(); val max = status["expmax"]?.toIntOrNull()
        val step = status["expstep"]?.toFloatOrNull() ?: 0f
        if (min == null || max == null) { Toast.makeText(this, "Connexion en cours, ou caméra à mettre à jour (1.7.0).", Toast.LENGTH_LONG).show(); return }
        if (max <= min) { Toast.makeText(this, "Cette caméra ne permet pas de régler l’exposition.", Toast.LENGTH_LONG).show(); return }
        val current = status["exp"]?.toIntOrNull() ?: 0
        val value = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER; text = Exposure.label(current, step) }
        val seek = SeekBar(this).apply { this.max = max - min; progress = current - min }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) { value.text = Exposure.label(min + progress, step) }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            // Une seule commande au relâchement : évite d’inonder la connexion pendant le glissement.
            override fun onStopTrackingTouch(bar: SeekBar) { client?.sendRequest(mapOf("op" to "exposure", "value" to (min + bar.progress).toString())) }
        })
        val pad = LynxUi.dp(this, 20)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(value); addView(seek) }
        AlertDialog.Builder(this).setTitle("Exposition de la caméra").setView(content)
            .setNeutralButton("Auto") { _, _ -> client?.sendRequest(mapOf("op" to "exposure", "value" to "0")) }
            .setPositiveButton("OK", null).show()
    }

    override fun onStart() {
        super.onStart()
        client?.start()
    }

    override fun onStop() {
        // Pas de flux en arrière-plan : économise la batterie et les données mobiles.
        client?.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    override fun onState(text: String, connected: Boolean) = runOnUiThread {
        state.text = text
        state.setTextColor(if (connected) LynxUi.ACCENT else LynxUi.MUTED)
        stateDot.background = LynxUi.circle(if (connected) LynxUi.ACCENT else LynxUi.WARNING)
        if (waiting.visibility == View.VISIBLE) waitingText.text = text
        if (!connected) recBadge.visibility = View.GONE
    }

    override fun onFrame(bitmap: Bitmap) = runOnUiThread {
        if (isDestroyed) { bitmap.recycle(); return@runOnUiThread }
        val previous = shownBitmap
        image.setImageBitmap(bitmap)
        shownBitmap = bitmap
        waiting.visibility = View.GONE
        previous?.takeIf { it !== bitmap }?.recycle()
    }

    override fun onStatus(fields: Map<String, String>) = runOnUiThread {
        lastStatus = fields
        recBadge.visibility = if (fields["rec"] == "1") View.VISIBLE else View.GONE
        fields["n"]?.takeIf { it.isNotBlank() }?.let { title.text = it }
    }

    companion object { const val EXTRA_CAMERA_ID = "camera_id" }
}

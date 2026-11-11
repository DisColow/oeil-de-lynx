package fr.oeildelynx

import android.content.Intent
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
    private lateinit var recBadge: TextView
    private lateinit var waiting: TextView
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
        waiting = TextView(this).apply { text = "◉"; textSize = 52f; gravity = Gravity.CENTER; setTextColor(0xff3b4941.toInt()) }
        root.addView(waiting, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(26), dp(16), dp(12)); setBackgroundColor(0xaa101713.toInt())
        }
        top.addView(TextView(this).apply {
            text = "‹"; textSize = 34f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); contentDescription = "Retour"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(40), dp(46)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), 0, 0, 0) }
        title = TextView(this).apply { text = camera.name; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); maxLines = 1 }
        state = TextView(this).apply { text = "Connexion…"; textSize = 12f; setTextColor(LynxUi.MUTED) }
        texts.addView(title); texts.addView(state)
        top.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        recBadge = TextView(this).apply {
            text = "● REC"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xfffa4545.toInt())
            setPadding(dp(10), dp(5), dp(10), dp(5)); background = LynxUi.rounded(this@LiveActivity, 0xe61b211d.toInt()); visibility = View.GONE
        }
        top.addView(recBadge)
        top.addView(iconButton("☀", "Exposition") { showExposure() })
        top.addView(iconButton("▧", "Galerie de la caméra") {
            startActivity(Intent(this, RemoteGalleryActivity::class.java).putExtra(EXTRA_CAMERA_ID, camera.id))
        })
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)

        client = LiveClient(applicationContext, camera.id, this)
    }

    private fun iconButton(icon: String, description: String, action: () -> Unit) = TextView(this).apply {
        text = icon; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); contentDescription = description
        background = LynxUi.rounded(this@LiveActivity, 0x553b4941)
        layoutParams = LinearLayout.LayoutParams(LynxUi.dp(this@LiveActivity, 42), LynxUi.dp(this@LiveActivity, 42)).apply { leftMargin = LynxUi.dp(this@LiveActivity, 8) }
        setOnClickListener { action() }
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

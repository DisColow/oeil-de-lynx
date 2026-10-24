package fr.oeildelynx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var mask: LinearLayout
    private lateinit var maskButtonIcon: TextView
    private lateinit var maskButtonLabel: TextView
    private lateinit var previewControl: LinearLayout
    private lateinit var recordButtonIcon: TextView
    private lateinit var recordButtonLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var recBadge: LinearLayout
    private var provider: ProcessCameraProvider? = null
    private var surveillance = false
    private var previewShown = true
    private var frontCamera = false
    private var permissionForStart = false
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("lynx", MODE_PRIVATE) }
    private val autoHide = Runnable { setPreviewVisible(false) }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val granted = requiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            if (permissionForStart) startMonitoring() else openPreview()
            permissionForStart = false
        } else Toast.makeText(this, "Autorise la caméra et le micro pour utiliser la surveillance.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        frontCamera = prefs.getBoolean("front_camera", false)
        surveillance = prefs.getBoolean("monitoring_active", false)
        previewShown = !surveillance || prefs.getBoolean("preview_shown", false)
        buildHud()
        if (surveillance) {
            setPreviewVisible(previewShown, scheduleHide = previewShown)
            MonitorService.updatePreviewSurface(previewView.surfaceProvider)
            showRecordingState()
        } else openPreview()
    }

    private fun buildHud() {
        val root = FrameLayout(this).apply { setBackgroundColor(0xff0d1210.toInt()) }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        // Opaque HUD shade: it covers the image without stopping the monitoring camera session.
        mask = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(0xff101713.toInt()); visibility = View.GONE
        }
        val maskIcon = TextView(this).apply { text = "◉"; textSize = 52f; gravity = Gravity.CENTER; setTextColor(0xffa9c5b4.toInt()) }
        val maskTitle = TextView(this).apply { text = "Aperçu masqué"; textSize = 21f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(6)) }
        val maskInfo = TextView(this).apply { text = "La surveillance continue en arrière-plan"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(0xffaab5ae.toInt()) }
        mask.addView(maskIcon); mask.addView(maskTitle); mask.addView(maskInfo)
        root.addView(mask, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(30), dp(18), dp(12)); background = gradient(0xdd101713.toInt(), 0, 0, dp(20), bottom = true)
        }
        val branding = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        branding.addView(TextView(this).apply { text = "ŒIL DE LYNX"; textSize = 16f; letterSpacing = .13f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD })
        branding.addView(TextView(this).apply { text = "CAMÉRA DE SURVEILLANCE"; textSize = 10f; letterSpacing = .12f; setTextColor(0xffaab5ae.toInt()); setPadding(0, dp(3), 0, 0) })
        top.addView(branding, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(statusPill())
        val settings = TextView(this).apply {
            text = "⚙"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = roundBackground(0x553b4941); contentDescription = "Règles"
            setOnClickListener { showRules() }
        }
        top.addView(settings, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(10) })
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        recBadge = LinearLayout(this).apply {
            gravity = Gravity.CENTER; setPadding(dp(14), dp(8), dp(14), dp(8)); background = roundBackground(0xe61b211d.toInt()); visibility = View.GONE
        }
        val recDot = TextView(this).apply { text = "●"; textSize = 15f; setTextColor(0xfffa4545.toInt()); setPadding(0, 0, dp(7), 0) }
        recBadge.addView(recDot)
        recBadge.addView(TextView(this).apply { text = "REC"; textSize = 13f; letterSpacing = .13f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD })
        root.addView(recBadge, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(127) })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(20))
            background = gradient(0xef101713.toInt(), 0, 0, dp(22), bottom = true)
        }
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        val previewAction = hudButton("◉", "Masquer caméra") { togglePreview() }
        maskButtonIcon = previewAction.first; maskButtonLabel = previewAction.second; previewControl = previewAction.third
        controls.addView(previewControl, LinearLayout.LayoutParams(0, dp(76), 1f))
        controls.addView(hudButton("⇄", "Avant / arrière") { switchCamera() }.third, LinearLayout.LayoutParams(0, dp(76), 1f))
        controls.addView(hudButton("▧", "Galerie") { openGallery() }.third, LinearLayout.LayoutParams(0, dp(76), 1f))
        val record = hudButton("▶", "Démarrer") { toggleMonitoring() }
        recordButtonIcon = record.first; recordButtonLabel = record.second
        controls.addView(record.third, LinearLayout.LayoutParams(0, dp(76), 1f))
        bottom.addView(controls)
        bottom.addView(TextView(this).apply {
            text = "Les clips sont enregistrés lorsqu’un son ou un mouvement est détecté."
            textSize = 11f; gravity = Gravity.CENTER; setTextColor(0xffaab5ae.toInt()); setPadding(0, dp(8), 0, 0)
        })
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        syncControls()
    }

    private fun statusPill() = LinearLayout(this).apply {
        gravity = Gravity.CENTER; setPadding(dp(10), dp(6), dp(10), dp(6)); background = roundBackground(0x443b4941)
        addView(TextView(this@MainActivity).apply { text = "●"; textSize = 10f; setTextColor(0xff83d4a1.toInt()); setPadding(0, 0, dp(6), 0) })
        statusLabel = TextView(this@MainActivity).apply {
            text = if (surveillance) "SURVEILLANCE ACTIVE" else "PRÊT"
            textSize = 10f; letterSpacing = .08f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }
        addView(statusLabel)
    }

    private fun hudButton(icon: String, label: String, action: () -> Unit): Triple<TextView, TextView, LinearLayout> {
        val iconView = TextView(this).apply { text = icon; textSize = 25f; gravity = Gravity.CENTER; setTextColor(Color.WHITE) }
        val labelView = TextView(this).apply { text = label; textSize = 10f; gravity = Gravity.CENTER; setTextColor(0xffc6d0c9.toInt()); maxLines = 1 }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = roundBackground(0x333b4941)
            addView(iconView, LinearLayout.LayoutParams(-1, 0, 1f)); addView(labelView)
            setOnClickListener { action() }
        }
        return Triple(iconView, labelView, container)
    }

    private fun roundBackground(color: Int) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(18).toFloat(); setColor(color) }
    private fun gradient(color: Int, radius: Int, left: Int, horizontal: Int, top: Boolean = false, bottom: Boolean = false) = GradientDrawable(
        if (top) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.BOTTOM_TOP,
        intArrayOf(color, 0x00101713)
    ).apply { cornerRadii = if (top) floatArrayOf(0f,0f,0f,0f,radius.toFloat(),radius.toFloat(),radius.toFloat(),radius.toFloat()) else floatArrayOf(radius.toFloat(),radius.toFloat(),radius.toFloat(),radius.toFloat(),0f,0f,0f,0f) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun requiredPermissions() = buildList {
        add(Manifest.permission.CAMERA); add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openPreview() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionForStart = false; permissionRequest.launch(requiredPermissions().toTypedArray()); return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({ try { provider = future.get(); bindPreview() } catch (_: Exception) { toast("Impossible d’ouvrir la caméra.") } }, ContextCompat.getMainExecutor(this))
    }

    private fun bindPreview() {
        val cameraProvider = provider ?: return
        try {
            cameraProvider.unbindAll()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            cameraProvider.bindToLifecycle(this, selector(), preview)
        } catch (_: Exception) { toast("Impossible d’afficher l’aperçu caméra.") }
    }

    private fun selector() = if (frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    private fun togglePreview() {
        if (!surveillance) return
        setPreviewVisible(!previewShown, scheduleHide = !previewShown)
    }

    private fun setPreviewVisible(visible: Boolean, scheduleHide: Boolean = visible) {
        previewShown = visible
        prefs.edit().putBoolean("preview_shown", visible).apply()
        mask.visibility = if (visible) View.GONE else View.VISIBLE
        maskButtonIcon.text = if (visible) "◉̸" else "◉"
        maskButtonLabel.text = if (visible) "Masquer caméra" else "Afficher caméra"
        MonitorService.updatePreviewSurface(previewView.surfaceProvider)
        handler.removeCallbacks(autoHide)
        if (visible && surveillance && scheduleHide) handler.postDelayed(autoHide, PREVIEW_TIMEOUT_MS)
    }

    private fun switchCamera() {
        frontCamera = !frontCamera
        prefs.edit().putBoolean("front_camera", frontCamera).apply()
        if (surveillance) {
            stopService(Intent(this, MonitorService::class.java)); surveillance = false
            handler.postDelayed({ startMonitoring() }, 600)
        } else bindPreview()
    }

    private fun toggleMonitoring() {
        if (surveillance) {
            prefs.edit().putBoolean("monitoring_active", false).putBoolean("preview_shown", true).apply()
            stopService(Intent(this, MonitorService::class.java)); surveillance = false
            handler.removeCallbacks(autoHide); mask.visibility = View.GONE; previewShown = true
            MonitorService.updatePreviewSurface(null); bindPreview(); syncControls()
        } else if (requiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startMonitoring()
        else { permissionForStart = true; permissionRequest.launch(requiredPermissions().toTypedArray()) }
    }

    private fun startMonitoring() {
        prefs.edit().putBoolean("monitoring_active", true).putBoolean("preview_shown", false).apply()
        provider?.unbindAll()
        MonitorService.updatePreviewSurface(previewView.surfaceProvider)
        ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java).putExtra(MonitorService.EXTRA_FRONT_CAMERA, frontCamera))
        surveillance = true; setPreviewVisible(false); syncControls(); showRecordingState()
    }

    private fun syncControls() {
        if (!::recordButtonIcon.isInitialized) return
        recordButtonIcon.text = if (surveillance) "■" else "▶"
        recordButtonLabel.text = if (surveillance) "Arrêter" else "Démarrer"
        previewControl.isEnabled = surveillance
        previewControl.alpha = if (surveillance) 1f else .55f
        if (!surveillance) { maskButtonIcon.text = "◉"; maskButtonLabel.text = "Aperçu actif" }
        recBadge.visibility = if (surveillance) View.VISIBLE else View.GONE
        if (::statusLabel.isInitialized) statusLabel.text = if (surveillance) "SURVEILLANCE ACTIVE" else "PRÊT"
    }

    private fun showRecordingState() {
        if (!surveillance) return
        recBadge.animate().cancel(); recBadge.alpha = 1f
        recBadge.animate().alpha(.35f).setDuration(650).withEndAction {
            if (surveillance) recBadge.animate().alpha(1f).setDuration(650).withEndAction { showRecordingState() }
        }
    }

    private fun showRules() {
        val durations = intArrayOf(30_000, 90_000, 180_000, 300_000, 600_000)
        val labels = arrayOf("30 secondes", "1 minute 30", "3 minutes", "5 minutes", "10 minutes")
        val selected = durations.indexOf(prefs.getInt("max_clip_ms", 600_000)).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Règles · Durée maximale")
            .setSingleChoiceItems(labels, selected) { dialog, which -> prefs.edit().putInt("max_clip_ms", durations[which]).apply(); dialog.dismiss() }
            .setNegativeButton("Fermer", null).show()
    }

    private fun openGallery() = startActivity(Intent(this, GalleryActivity::class.java))
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        surveillance = prefs.getBoolean("monitoring_active", surveillance)
        syncControls()
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    companion object { private const val PREVIEW_TIMEOUT_MS = 90_000L }
}

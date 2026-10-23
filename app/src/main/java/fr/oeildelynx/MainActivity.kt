package fr.oeildelynx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
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
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var mask: TextView
    private lateinit var rec: TextView
    private lateinit var previewButton: Button
    private lateinit var startButton: Button
    private var provider: ProcessCameraProvider? = null
    private var surveillance = false
    private var previewShown = true
    private var frontCamera = false
    private var permissionForStart = false
    private val handler = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { setPreviewVisible(false) }
    private val prefs by lazy { getSharedPreferences("lynx", MODE_PRIVATE) }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (requiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            if (permissionForStart) startMonitoring() else openPreview()
            permissionForStart = false
        }
        else Toast.makeText(this, "Autorise la caméra et le micro pour démarrer.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        frontCamera = prefs.getBoolean("front_camera", false)
        buildHome()
        openPreview()
    }

    private fun buildHome() {
        val root = FrameLayout(this).apply { setBackgroundColor(0xff101411.toInt()) }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        mask = TextView(this).apply {
            text = "Aperçu masqué\nLa surveillance continue"
            gravity = Gravity.CENTER; textSize = 20f; setTextColor(-1); setBackgroundColor(0xff101411.toInt()); visibility = View.GONE
        }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        root.addView(mask, FrameLayout.LayoutParams(-1, -1))
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(28), dp(12), dp(8)); setBackgroundColor(0x66000000) }
        top.addView(TextView(this).apply { text = "Œil de Lynx"; textSize = 19f; setTextColor(-1) }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(actionButton("⚙", "Règles") { showRules() })
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        rec = TextView(this).apply {
            text = "REC 🔴"; textSize = 18f; setTextColor(-1); gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8)); setBackgroundColor(0xcc171717.toInt()); visibility = View.GONE
        }
        root.addView(rec, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(100) })

        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(6), dp(6), dp(6), dp(20)); setBackgroundColor(0x99000000.toInt()) }
        previewButton = actionButton("📷", "Masquer l’aperçu") { togglePreview() }
        controls.addView(previewButton, LinearLayout.LayoutParams(0, dp(68), 1f))
        controls.addView(actionButton("↻", "Avant / arrière") { switchCamera() }, LinearLayout.LayoutParams(0, dp(68), 1f))
        controls.addView(actionButton("🖼", "Galerie") { showGallery() }, LinearLayout.LayoutParams(0, dp(68), 1f))
        startButton = actionButton("▶", "Démarrer") { toggleMonitoring() }
        controls.addView(startButton, LinearLayout.LayoutParams(0, dp(68), 1f))
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun actionButton(icon: String, description: String, action: () -> Unit) = Button(this).apply {
        text = icon; textSize = 24f; contentDescription = description; isAllCaps = false
        setTextColor(-1); setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x992c3931.toInt()))
        setOnClickListener { action() }
    }

    private fun requiredPermissions() = buildList {
        add(Manifest.permission.CAMERA); add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openPreview() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                provider = future.get()
                bindPreview()
            } catch (_: Exception) { Toast.makeText(this, "Impossible d’ouvrir la caméra.", Toast.LENGTH_LONG).show() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindPreview() {
        val cameraProvider = provider ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionForStart = false
            permissionRequest.launch(requiredPermissions().toTypedArray()); return
        }
        try {
            cameraProvider.unbindAll()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            cameraProvider.bindToLifecycle(this, selector(), preview)
        } catch (_: Exception) { Toast.makeText(this, "Impossible d’afficher l’aperçu caméra.", Toast.LENGTH_LONG).show() }
    }

    private fun selector() = if (frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    private fun togglePreview() {
        if (!surveillance) return
        if (previewShown) setPreviewVisible(false) else {
            setPreviewVisible(true)
            handler.removeCallbacks(autoHide)
            handler.postDelayed(autoHide, PREVIEW_TIMEOUT_MS)
        }
    }

    private fun setPreviewVisible(visible: Boolean) {
        previewShown = visible
        previewView.visibility = if (visible) View.VISIBLE else View.GONE
        mask.visibility = if (visible) View.GONE else View.VISIBLE
        previewButton.text = if (visible) "📷̸" else "📷"
        previewButton.contentDescription = if (visible) "Masquer l’aperçu" else "Afficher l’aperçu"
        MonitorService.updatePreviewSurface(if (visible) previewView.surfaceProvider else null)
        if (visible) handler.postDelayed(autoHide, PREVIEW_TIMEOUT_MS) else handler.removeCallbacks(autoHide)
    }

    private fun switchCamera() {
        frontCamera = !frontCamera
        prefs.edit().putBoolean("front_camera", frontCamera).apply()
        if (surveillance) {
            stopService(Intent(this, MonitorService::class.java)); surveillance = false
            handler.postDelayed({ startMonitoring() }, 500)
        } else bindPreview()
    }

    private fun toggleMonitoring() {
        if (surveillance) {
            stopService(Intent(this, MonitorService::class.java)); surveillance = false
            handler.removeCallbacks(autoHide); previewView.visibility = View.VISIBLE; mask.visibility = View.GONE
            previewShown = true; rec.visibility = View.GONE; startButton.text = "▶"; startButton.contentDescription = "Démarrer"
            MonitorService.updatePreviewSurface(null)
            bindPreview()
        } else if (requiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startMonitoring()
        else { permissionForStart = true; permissionRequest.launch(requiredPermissions().toTypedArray()) }
    }

    private fun startMonitoring() {
        provider?.unbindAll()
        MonitorService.updatePreviewSurface(previewView.surfaceProvider)
        ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java).putExtra(MonitorService.EXTRA_FRONT_CAMERA, frontCamera))
        surveillance = true; previewShown = false
        previewView.visibility = View.GONE; mask.visibility = View.VISIBLE
        startButton.text = "■"; startButton.contentDescription = "Arrêter"
        rec.visibility = View.VISIBLE
        animateRec()
    }

    private fun animateRec() {
        if (!surveillance) return
        rec.animate().alpha(0.3f).setDuration(600).withEndAction { rec.animate().alpha(1f).setDuration(600).withEndAction { animateRec() } }
    }

    private fun showRules() {
        val durations = intArrayOf(30_000, 90_000, 180_000, 300_000, 600_000)
        val labels = arrayOf("30 secondes", "1 minute 30", "3 minutes", "5 minutes", "10 minutes")
        val current = prefs.getInt("max_clip_ms", 600_000)
        val selected = durations.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Règles")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                prefs.edit().putInt("max_clip_ms", durations[which]).apply()
                dialog.dismiss()
            }.setNegativeButton("Fermer", null).show()
    }

    private fun captureFolder() = File(getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "Evenements")

    private fun showGallery() {
        val captures = captureFolder().listFiles { file -> file.isFile && file.extension.equals("mp4", true) }
            ?.sortedByDescending { it.nameWithoutExtension.substringAfter("lynx_", "0").toLongOrNull() ?: 0L }.orEmpty()
        val view = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        if (captures.isEmpty()) view.addView(TextView(this).apply { text = "Aucune capture pour le moment."; setPadding(dp(12), dp(20), dp(12), dp(20)) })
        val selected = linkedSetOf<File>()
        val delete = Button(this).apply { text = "Supprimer la sélection"; visibility = View.GONE }
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = captures.size
            override fun getItem(position: Int) = captures[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, recycled: View?, parent: android.view.ViewGroup?): View {
                val file = captures[position]
                val row = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundColor(if (selected.contains(file)) 0xffd9eadf.toInt() else 0xfff4f5f4.toInt()) }
                val thumb = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xff252925.toInt()) }
                try { MediaMetadataRetriever().use { retriever -> retriever.setDataSource(file.absolutePath); thumb.setImageBitmap(retriever.getFrameAtTime(0)) } } catch (_: Exception) { }
                row.addView(thumb, LinearLayout.LayoutParams(dp(92), dp(68)))
                val text = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
                val start = file.nameWithoutExtension.substringAfter("lynx_").toLongOrNull() ?: file.lastModified()
                val duration = videoDuration(file)
                val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                text.addView(TextView(this@MainActivity).apply { this.text = "Début : ${fmt.format(Date(start))}"; textSize = 14f })
                text.addView(TextView(this@MainActivity).apply { this.text = "Fin : ${fmt.format(Date(start + duration))}"; textSize = 14f })
                row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
                row.setOnClickListener {
                    if (selected.isNotEmpty()) { if (!selected.add(file)) selected.remove(file); delete.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE; (parent as? ListView)?.invalidateViews() }
                    else openCapture(file)
                }
                row.setOnLongClickListener { selected.add(file); delete.visibility = View.VISIBLE; (parent as? ListView)?.invalidateViews(); true }
                return row
            }
        }
        val list = ListView(this).apply { setAdapter(adapter); dividerHeight = dp(1) }
        view.addView(list, LinearLayout.LayoutParams(-1, 0, 1f)); view.addView(delete)
        val dialog = AlertDialog.Builder(this).setTitle("Galerie · ${captures.size} captures").setView(view).setNegativeButton("Fermer", null).create()
        delete.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Supprimer les captures ?")
                .setMessage("${selected.size} élément(s) seront supprimés définitivement.")
                .setNegativeButton("Annuler", null).setPositiveButton("Supprimer") { _, _ ->
                    selected.forEach { it.delete() }; dialog.dismiss(); showGallery()
                }.show()
        }
        dialog.show()
    }

    private fun videoDuration(file: File): Long = try {
        MediaMetadataRetriever().use { it.setDataSource(file.absolutePath); it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
    } catch (_: Exception) { 0L }

    private fun openCapture(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) { Toast.makeText(this, "Aucune application vidéo disponible.", Toast.LENGTH_SHORT).show() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        if (surveillance) { rec.visibility = View.VISIBLE }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object { private const val PREVIEW_TIMEOUT_MS = 90_000L }
}

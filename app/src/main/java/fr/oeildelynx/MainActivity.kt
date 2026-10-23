package fr.oeildelynx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var previewView: PreviewView
    private lateinit var previewMask: TextView
    private var previewProvider: ProcessCameraProvider? = null
    private var previewPending = false
    private val handler = Handler(Looper.getMainLooper())

    private val startMonitorAfterPreview = Runnable {
        if (!previewPending) return@Runnable
        previewPending = false
        previewProvider?.unbindAll()
        previewProvider = null
        previewView.visibility = View.GONE
        previewMask.visibility = View.VISIBLE
        ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))
        status.text = "Surveillance active — aperçu masqué"
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (requiredPermissions().all { result[it] == true || ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) beginPreview()
        else status.text = "Autorise la caméra et le micro pour démarrer."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 36, 28, 20) }
        root.addView(TextView(this).apply { text = "Œil de Lynx"; textSize = 28f })
        root.addView(TextView(this).apply {
            text = "Aperçu visible 10 secondes, puis masqué automatiquement pendant la surveillance. Les clips vidéo avec audio sont enregistrés sur ce téléphone lorsqu’un mouvement ou un son est détecté."
            textSize = 16f; setPadding(0, 12, 0, 16)
        })

        val previewFrame = FrameLayout(this)
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            visibility = View.GONE
        }
        previewMask = TextView(this).apply {
            text = "Aperçu masqué\nLa surveillance continue"
            textSize = 16f; gravity = Gravity.CENTER; setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.BLACK); visibility = View.GONE
        }
        previewFrame.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        previewFrame.addView(previewMask, FrameLayout.LayoutParams(-1, -1))
        root.addView(previewFrame, LinearLayout.LayoutParams(-1, dp(210)))

        status = TextView(this).apply { textSize = 16f; text = "Surveillance arrêtée"; setPadding(0, 14, 0, 8) }
        root.addView(status)
        root.addView(Button(this).apply { text = "Démarrer la surveillance"; setOnClickListener { requestStart() } })
        root.addView(Button(this).apply { text = "Captures"; setOnClickListener { showCaptures() } })
        root.addView(Button(this).apply { text = "Arrêter"; setOnClickListener { stopMonitoring() } })
        setContentView(root)
    }

    private fun requiredPermissions() = buildList {
        add(Manifest.permission.CAMERA); add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestStart() {
        if (previewPending) return
        if (requiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) beginPreview()
        else permissions.launch(requiredPermissions().toTypedArray())
    }

    private fun beginPreview() {
        previewPending = true
        previewMask.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        status.text = "Aperçu caméra — la surveillance démarre dans 10 secondes"
        val cameraFuture = ProcessCameraProvider.getInstance(this)
        cameraFuture.addListener({
            if (!previewPending) return@addListener
            try {
                val provider = cameraFuture.get()
                previewProvider = provider
                provider.unbindAll()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                handler.removeCallbacks(startMonitorAfterPreview)
                handler.postDelayed(startMonitorAfterPreview, PREVIEW_DURATION_MS)
            } catch (_: Exception) {
                previewPending = false
                previewView.visibility = View.GONE
                status.text = "Impossible d’ouvrir la caméra. Vérifie qu’elle n’est pas utilisée ailleurs."
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopMonitoring() {
        handler.removeCallbacks(startMonitorAfterPreview)
        previewPending = false
        previewProvider?.unbindAll()
        previewProvider = null
        previewView.visibility = View.GONE
        previewMask.visibility = View.GONE
        stopService(Intent(this, MonitorService::class.java))
        status.text = "Surveillance arrêtée"
    }

    private fun showCaptures() {
        if (previewPending) stopMonitoring()
        val folder = File(getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "Evenements")
        val captures = folder.listFiles { file -> file.isFile && file.extension.equals("mp4", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        if (captures.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Captures vidéo")
                .setMessage("Aucune capture pour le moment.")
                .setPositiveButton("Fermer", null).show()
            return
        }
        val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        val labels = captures.map { "${formatter.format(Date(it.lastModified()))}  ·  ${it.name}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Captures vidéo (${captures.size})")
            .setItems(labels) { _, index -> openCapture(captures[index]) }
            .setNegativeButton("Fermer", null).show()
    }

    private fun openCapture(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            Toast.makeText(this, "Aucune application vidéo disponible.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        handler.removeCallbacks(startMonitorAfterPreview)
        if (previewPending) previewProvider?.unbindAll()
        super.onDestroy()
    }

    companion object { private const val PREVIEW_DURATION_MS = 10_000L }
}

package fr.oeildelynx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (requiredPermissions().all { result[it] == true || ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startMonitor()
        else status.text = "Autorise la caméra et le micro pour démarrer."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 48, 36, 24) }
        root.addView(TextView(this).apply { text = "Œil de Lynx"; textSize = 28f })
        root.addView(TextView(this).apply {
            text = "Surveillance locale. Les séquences sont enregistrées sur ce téléphone lorsqu’un mouvement ou un son est détecté. Laisse le téléphone branché, caméra dégagée et l’application au premier plan au démarrage."
            textSize = 16f; setPadding(0, 18, 0, 22)
        })
        status = TextView(this).apply { textSize = 16f; text = "Surveillance arrêtée" }
        root.addView(status)
        root.addView(Button(this).apply { text = "Démarrer la surveillance"; setOnClickListener { requestStart() } })
        root.addView(Button(this).apply { text = "Arrêter"; setOnClickListener { stopService(Intent(this@MainActivity, MonitorService::class.java)); status.text = "Surveillance arrêtée" } })
        setContentView(root)
    }

    private fun requiredPermissions() = buildList {
        add(Manifest.permission.CAMERA); add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    private fun requestStart() {
        if (requiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startMonitor()
        else permissions.launch(requiredPermissions().toTypedArray())
    }
    private fun startMonitor() {
        ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))
        status.text = "Surveillance active — détection mouvement et son"
    }
}

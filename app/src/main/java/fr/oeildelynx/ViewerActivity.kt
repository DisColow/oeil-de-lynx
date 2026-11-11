package fr.oeildelynx

import android.Manifest
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog

/**
 * Mode spectateur : liste des caméras appairées sur ce téléphone, ajout par QR code ou par code collé.
 * Toucher une caméra ouvre son direct.
 */
class ViewerActivity : ComponentActivity() {
    private lateinit var list: LinearLayout

    private var pendingAlertCamera: String? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val id = pendingAlertCamera ?: return@registerForActivityResult
        pendingAlertCamera = null
        if (granted) enableAlerts(id)
        else Toast.makeText(this, "Autorise les notifications pour recevoir les alertes de mouvement.", Toast.LENGTH_LONG).show()
    }

    private val scan = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra(ScanActivity.EXTRA_CODE)?.let { addCamera(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        val dp = { value: Int -> LynxUi.dp(this, value) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(LynxUi.BACKGROUND) }
        root.addView(LynxUi.header(this, "Regarder", "CAMÉRAS APPAIRÉES · DIRECT") { finish() })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(14), dp(14)) }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(18)) }
        actions.addView(LynxUi.button(this, "Scanner le QR code d’une caméra") { scan.launch(Intent(this, ScanActivity::class.java)) })
        actions.addView(LynxUi.button(this, "Coller un code d’appairage", LynxUi.CARD) { pasteCode() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(actions)
        setContentView(root)
        intent.getStringExtra(ScanActivity.EXTRA_CODE)?.let { addCamera(it) }
        AlertService.sync(this)
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
        refresh()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    private fun refresh() {
        list.removeAllViews()
        val dp = { value: Int -> LynxUi.dp(this, value) }
        val cameras = ViewerStore.cameras(this)
        if (cameras.isEmpty()) {
            list.addView(LynxUi.body(this,
                "Aucune caméra appairée.\n\n" +
                    "1. Sur le téléphone caméra : ⚙ › Appairer un téléphone spectateur.\n" +
                    "2. Ici : « Scanner le QR code d’une caméra ».\n" +
                    "3. Démarre la surveillance sur le téléphone caméra, puis touche son nom ici.\n\n" +
                    "Le flux va directement de la caméra à ce téléphone, par le Wi-Fi ou par la box internet de la caméra, sans serveur intermédiaire.",
                15f).apply { setPadding(dp(6), dp(20), dp(6), 0) })
            return
        }
        for (camera in cameras) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
                background = LynxUi.rounded(this@ViewerActivity, LynxUi.CARD)
                setOnClickListener { startActivity(Intent(this@ViewerActivity, LiveActivity::class.java).putExtra(LiveActivity.EXTRA_CAMERA_ID, camera.id)) }
                setOnLongClickListener { manage(camera); true }
            }
            row.addView(TextView(this).apply { text = "▶  ${camera.name}"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
            val alerts = camera.id in ViewerStore.alertCameras(this)
            row.addView(LynxUi.body(this, "Toucher : voir le direct · appui long : renommer, adresses, supprimer", 12f)
                .apply { setPadding(0, dp(4), 0, dp(10)) })
            // Interrupteur visible des notifications de mouvement pour cette caméra.
            row.addView(TextView(this).apply {
                text = if (alerts) "🔔  Notifications de mouvement : activées" else "🔕  Notifications de mouvement : désactivées · toucher pour activer"
                textSize = 13f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (alerts) LynxUi.ACCENT else Color.WHITE)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = LynxUi.rounded(this@ViewerActivity, if (alerts) 0xff23402f.toInt() else 0xff2a332d.toInt(), 10)
                setOnClickListener {
                    if (alerts) { ViewerStore.setAlerts(this@ViewerActivity, camera.id, false); AlertService.sync(this@ViewerActivity); refresh() }
                    else requestAlerts(camera.id)
                }
            })
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
        }
    }

    private fun addCamera(text: String) {
        val code = PairingCode.parse(text)
        if (code == null) { Toast.makeText(this, "Code d’appairage invalide.", Toast.LENGTH_LONG).show(); return }
        val isNew = ViewerStore.find(this, code.id) == null
        ViewerStore.save(this, code)
        Toast.makeText(this, "« ${code.name} » appairée.", Toast.LENGTH_LONG).show()
        refresh()
        // Les alertes de mouvement sont le cas d’usage principal : on les propose dès l’appairage.
        if (isNew) requestAlerts(code.id)
    }

    private fun pasteCode() {
        val input = EditText(this).apply {
            hint = "oeildelynx://pair?…"
            getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this@ViewerActivity)?.toString()
                ?.takeIf { it.trim().startsWith(PairingCode.PREFIX) }?.let { setText(it) }
        }
        AlertDialog.Builder(this).setTitle("Coller un code d’appairage").setView(input)
            .setPositiveButton("Ajouter") { _, _ -> addCamera(input.text.toString()) }
            .setNegativeButton("Annuler", null).show()
    }

    private fun manage(camera: PairingCode) {
        val alerts = camera.id in ViewerStore.alertCameras(this)
        val options = arrayOf(
            if (alerts) "Désactiver les alertes de mouvement" else "Activer les alertes de mouvement",
            "Renommer", "Ajouter une adresse (DynDNS, IP fixe…)", "Voir les adresses", "Supprimer"
        )
        AlertDialog.Builder(this).setTitle(camera.name).setItems(options) { _, item ->
            if (item == 0) {
                if (alerts) { ViewerStore.setAlerts(this, camera.id, false); AlertService.sync(this); refresh() } else requestAlerts(camera.id)
                return@setItems
            }
            when (item - 1) {
                0 -> prompt("Nom de la caméra", camera.name) { ViewerStore.save(this, camera.copy(name = it.ifBlank { camera.name })); refresh() }
                1 -> prompt("Adresse (hôte ou hôte:port)", "") { entry ->
                    val host = if (PairingCode.parseHost(entry.trim()) != null) entry.trim() else PairingCode.formatHost(entry.trim(), LiveProtocol.DEFAULT_PORT)
                    if (entry.isNotBlank() && PairingCode.parseHost(host) != null) {
                        ViewerStore.save(this, camera.copy(hosts = (listOf(host) + camera.hosts).distinct())); refresh()
                    } else Toast.makeText(this, "Adresse invalide.", Toast.LENGTH_LONG).show()
                }
                2 -> AlertDialog.Builder(this).setTitle("Adresses de ${camera.name}")
                    .setMessage(camera.hosts.joinToString("\n").ifBlank { "Aucune" } +
                        "\n\nElles sont mises à jour automatiquement à chaque connexion réussie.")
                    .setPositiveButton("OK", null).show()
                3 -> AlertDialog.Builder(this).setTitle("Supprimer ${camera.name} ?")
                    .setMessage("Ce téléphone ne pourra plus regarder cette caméra sans un nouvel appairage.")
                    .setPositiveButton("Supprimer") { _, _ -> ViewerStore.remove(this, camera.id); AlertService.sync(this); refresh() }
                    .setNegativeButton("Annuler", null).show()
            }
        }.show()
    }

    private fun requestAlerts(cameraId: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingAlertCamera = cameraId
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else enableAlerts(cameraId)
    }

    private fun enableAlerts(cameraId: String) {
        ViewerStore.setAlerts(this, cameraId, true)
        AlertService.sync(this)
        refresh()
        AlertDialog.Builder(this).setTitle("Alertes activées")
            .setMessage("Ce téléphone reste connecté à la caméra (sans image) et affiche une notification dès qu’elle détecte un mouvement, " +
                "ou si elle devient injoignable pendant plus de 3 minutes.\n\n" +
                "Pour ne pas être coupé par Android, exclus Œil de Lynx de l’optimisation de batterie sur ce téléphone.")
            .setPositiveButton("OK", null).show()
    }

    private fun prompt(title: String, initial: String, onDone: (String) -> Unit) {
        val input = EditText(this).apply { setText(initial); setSingleLine(); gravity = Gravity.START }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("OK") { _, _ -> onDone(input.text.toString()) }
            .setNegativeButton("Annuler", null).show()
    }
}

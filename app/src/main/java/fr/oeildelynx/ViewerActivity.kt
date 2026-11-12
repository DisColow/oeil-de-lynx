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
import android.content.res.ColorStateList
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import androidx.appcompat.widget.SwitchCompat
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
        root.addView(LynxUi.header(this, "Mes caméras", "Mode spectateur · direct et alertes") { finish() })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(16)) }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(22)) }
        actions.addView(LynxUi.button(this, "Scanner le QR code d’une caméra", iconRes = R.drawable.ic_qr) { scan.launch(Intent(this, ScanActivity::class.java)) })
        actions.addView(LynxUi.button(this, "Coller un code d’appairage", LynxUi.CARD, R.drawable.ic_paste) { pasteCode() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
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
            list.addView(emptyState())
            return
        }
        for (camera in cameras) {
            val alerts = camera.id in ViewerStore.alertCameras(this)
            val card = LynxUi.card(this, 0)
            // Partie haute : ouvre le direct (appui long : gérer la caméra).
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(16), dp(8), dp(14))
                background = LynxUi.pressable(this@ViewerActivity, 0x00000000, 20)
                setOnClickListener { startActivity(Intent(this@ViewerActivity, LiveActivity::class.java).putExtra(LiveActivity.EXTRA_CAMERA_ID, camera.id)) }
                setOnLongClickListener { manage(camera); true }
            }
            head.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_live); imageTintList = ColorStateList.valueOf(LynxUi.ACCENT)
                val pad = dp(11); setPadding(pad, pad, pad, pad); background = LynxUi.rounded(this@ViewerActivity, 0xff1f3328.toInt(), 14)
            }, LinearLayout.LayoutParams(dp(46), dp(46)))
            head.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(4), 0)
                addView(LynxUi.title(this@ViewerActivity, camera.name, 18f).apply { maxLines = 1 })
                addView(LynxUi.body(this@ViewerActivity, "Toucher pour voir le direct", 13f).apply { setPadding(0, dp(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(LynxUi.iconButton(this, R.drawable.ic_more, "Gérer ${camera.name}", 0x00000000, 40) { manage(camera) }.apply {
                imageTintList = ColorStateList.valueOf(LynxUi.MUTED)
            })
            card.addView(head)
            card.addView(View(this).apply { setBackgroundColor(LynxUi.OUTLINE) }, LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(16); rightMargin = dp(16) })
            // Partie basse : interrupteur des notifications de mouvement.
            val toggle = SwitchCompat(this).apply { isChecked = alerts; isClickable = false; isFocusable = false }
            val alertRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(12), dp(12))
                background = LynxUi.pressable(this@ViewerActivity, 0x00000000, 20)
                contentDescription = "Notifications de mouvement"
                setOnClickListener {
                    if (alerts) { ViewerStore.setAlerts(this@ViewerActivity, camera.id, false); AlertService.sync(this@ViewerActivity); refresh() }
                    else requestAlerts(camera.id)
                }
            }
            alertRow.addView(LynxUi.icon(this, if (alerts) R.drawable.ic_bell else R.drawable.ic_bell_off, if (alerts) LynxUi.ACCENT else LynxUi.MUTED, 22))
            alertRow.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0)
                addView(TextView(this@ViewerActivity).apply { text = "Notifications de mouvement"; textSize = 14.5f; setTextColor(LynxUi.TEXT) })
                addView(LynxUi.body(this@ViewerActivity, if (alerts) "Activées : prévenu à chaque mouvement" else "Désactivées", 12.5f))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            alertRow.addView(toggle)
            card.addView(alertRow)
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
    }

    /** Écran vide : comment appairer, en trois étapes. */
    private fun emptyState(): View {
        val dp = { value: Int -> LynxUi.dp(this, value) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(4), dp(28), dp(4), 0) }
        box.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_live); imageTintList = ColorStateList.valueOf(LynxUi.ACCENT)
            val pad = dp(20); setPadding(pad, pad, pad, pad); background = LynxUi.circle(0xff17261d.toInt())
        }, LinearLayout.LayoutParams(dp(80), dp(80)))
        box.addView(LynxUi.title(this, "Aucune caméra appairée", 20f).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(6)) })
        box.addView(LynxUi.body(this, "Regarde le direct et reçois une alerte à chaque mouvement, directement depuis le téléphone caméra, sans serveur.").apply {
            gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), dp(22))
        })
        val steps = LynxUi.card(this, 18)
        listOf(
            "Sur le téléphone caméra : Réglages › Appairer un téléphone spectateur.",
            "Ici : « Scanner le QR code d’une caméra ».",
            "Démarre la surveillance sur la caméra, puis touche son nom ici."
        ).forEachIndexed { index, step ->
            steps.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, if (index == 0) 0 else dp(14), 0, 0)
                addView(TextView(this@ViewerActivity).apply {
                    text = "${index + 1}"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(LynxUi.ON_ACCENT)
                    typeface = Typeface.DEFAULT_BOLD; background = LynxUi.circle(LynxUi.ACCENT)
                }, LinearLayout.LayoutParams(dp(26), dp(26)))
                addView(LynxUi.body(this@ViewerActivity, step, 14f, LynxUi.TEXT).apply { setPadding(dp(14), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
        box.addView(steps, LinearLayout.LayoutParams(-1, -2))
        return box
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
        LynxUi.sheet(this, camera.name, listOf(
            LynxUi.SheetItem(if (alerts) R.drawable.ic_bell_off else R.drawable.ic_bell,
                if (alerts) "Désactiver les alertes de mouvement" else "Activer les alertes de mouvement") {
                if (alerts) { ViewerStore.setAlerts(this, camera.id, false); AlertService.sync(this); refresh() } else requestAlerts(camera.id)
            },
            LynxUi.SheetItem(R.drawable.ic_edit, "Renommer", camera.name) {
                prompt("Nom de la caméra", camera.name) { ViewerStore.save(this, camera.copy(name = it.ifBlank { camera.name })); refresh() }
            },
            LynxUi.SheetItem(R.drawable.ic_link, "Ajouter une adresse", "DynDNS, IP fixe…") {
                prompt("Adresse (hôte ou hôte:port)", "") { entry ->
                    val host = if (PairingCode.parseHost(entry.trim()) != null) entry.trim() else PairingCode.formatHost(entry.trim(), LiveProtocol.DEFAULT_PORT)
                    if (entry.isNotBlank() && PairingCode.parseHost(host) != null) {
                        ViewerStore.save(this, camera.copy(hosts = (listOf(host) + camera.hosts).distinct())); refresh()
                    } else Toast.makeText(this, "Adresse invalide.", Toast.LENGTH_LONG).show()
                }
            },
            LynxUi.SheetItem(R.drawable.ic_globe, "Voir les adresses", "${camera.hosts.size} adresse(s) connue(s)") {
                AlertDialog.Builder(this).setTitle("Adresses de ${camera.name}")
                    .setMessage(camera.hosts.joinToString("\n").ifBlank { "Aucune" } +
                        "\n\nElles sont mises à jour automatiquement à chaque connexion réussie.")
                    .setPositiveButton("OK", null).show()
            },
            LynxUi.SheetItem(R.drawable.ic_trash, "Supprimer", danger = true) {
                AlertDialog.Builder(this).setTitle("Supprimer ${camera.name} ?")
                    .setMessage("Ce téléphone ne pourra plus regarder cette caméra sans un nouvel appairage.")
                    .setPositiveButton("Supprimer") { _, _ -> ViewerStore.remove(this, camera.id); AlertService.sync(this); refresh() }
                    .setNegativeButton("Annuler", null).show()
            }
        ))
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

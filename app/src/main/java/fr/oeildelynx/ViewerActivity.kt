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
        ViewerStore.cameras(this).forEach { fetchState(it.id) }
    }

    // État lu sur chaque caméra (sirène, détection du son) ; null = pas encore connu ou injoignable.
    private val remoteState = mutableMapOf<String, Map<String, String>?>()
    private val remoteError = mutableMapOf<String, String>()
    private val pending = mutableSetOf<String>()

    /** Interroge la caméra en arrière-plan (elle doit être en surveillance pour répondre). */
    private fun fetchState(cameraId: String) = sendControl(cameraId, mapOf("op" to "settings"), quiet = true)

    private fun sendControl(cameraId: String, fields: Map<String, String>, quiet: Boolean = false) {
        if (!pending.add(cameraId)) return
        refresh()
        Thread {
            val result = try { Result.success(LiveClient(applicationContext, cameraId, NO_LISTENER).control(fields)) } catch (error: Exception) { Result.failure(error) }
            runOnUiThread {
                pending -= cameraId
                result.onSuccess { remoteState[cameraId] = it; remoteError -= cameraId }
                result.onFailure { error ->
                    remoteState[cameraId] = null
                    remoteError[cameraId] = "Caméra injoignable : elle doit être en surveillance."
                    if (!quiet) Toast.makeText(this, error.message ?: "Caméra injoignable", Toast.LENGTH_LONG).show()
                }
                if (!isDestroyed) refresh()
            }
        }.start()
    }

    /** Sous la carte : détection du son à distance et sirène de la caméra. */
    private fun addRemoteControls(card: LinearLayout, camera: PairingCode) {
        val dp = { value: Int -> LynxUi.dp(this, value) }
        val state = remoteState[camera.id]
        val busy = camera.id in pending
        card.addView(View(this).apply { setBackgroundColor(LynxUi.OUTLINE) }, LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(16); rightMargin = dp(16) })

        val soundOn = state?.get("sound") == "1"
        val soundRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            background = LynxUi.pressable(this@ViewerActivity, 0x00000000, 20)
            contentDescription = "Détection du son sur la caméra"
            setOnClickListener {
                if (state == null) fetchState(camera.id)
                else sendControl(camera.id, mapOf("op" to "sound", "value" to if (soundOn) "0" else "1"))
            }
        }
        soundRow.addView(LynxUi.icon(this, R.drawable.ic_mic, if (soundOn) LynxUi.ACCENT else LynxUi.MUTED, 22))
        soundRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0)
            addView(TextView(this@ViewerActivity).apply { text = "Détection du son"; textSize = 14.5f; setTextColor(LynxUi.TEXT) })
            addView(LynxUi.body(this@ViewerActivity, when {
                busy -> "Interrogation de la caméra…"
                state == null -> remoteError[camera.id]?.let { "$it Toucher pour réessayer." } ?: "État inconnu · toucher pour interroger"
                soundOn -> "Activée : un bruit déclenche aussi un clip"
                else -> "Désactivée : seul le mouvement déclenche un clip"
            }, 12.5f))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (state != null) soundRow.addView(SwitchCompat(this).apply { isChecked = soundOn; isClickable = false; isFocusable = false })
        card.addView(soundRow)

        // Veille : caméra et micro coupés pour économiser la batterie, reprise à distance.
        if (state != null) {
            val asleep = state["standby"] == "1"
            val why = when (state["standbywhy"]) { MonitorService.REASON_BATTERY -> "batterie faible"; MonitorService.REASON_REMOTE -> "depuis un spectateur"; else -> "sur la caméra" }
            val threshold = state["thr"]?.toIntOrNull() ?: MonitorService.DEFAULT_STANDBY_THRESHOLD
            card.addView(LynxUi.row(this, R.drawable.ic_battery,
                if (asleep) "Surveillance en veille ($why)" else "Surveillance active",
                if (asleep) "Toucher pour reprendre la surveillance" else "Toucher pour mettre en veille et économiser la batterie", chevron = false) {
                sendControl(camera.id, mapOf("op" to "standby", "value" to if (asleep) "0" else "1"))
            })
            card.addView(LynxUi.row(this, R.drawable.ic_timer, "Veille automatique",
                if (threshold == 0) "Désactivée" else "Sous $threshold % de batterie, reprise au branchement") {
                chooseThreshold(camera, threshold)
            })
        }

        val alarmOn = state?.get("alarm") == "1"
        card.addView(LynxUi.button(this, if (alarmOn) "Arrêter l’alarme" else "Déclencher l’alarme sur la caméra",
            if (alarmOn) LynxUi.ACCENT_STRONG else LynxUi.DANGER, R.drawable.ic_bell) {
            if (alarmOn) sendControl(camera.id, mapOf("op" to "alarm", "value" to "0"))
            else confirmAlarm(camera)
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(2), dp(14), dp(14)) })
    }

    private fun chooseThreshold(camera: PairingCode, current: Int) {
        val values = intArrayOf(0, 15, 20, 30, 40)
        val labels = arrayOf("Désactivée", "Sous 15 %", "Sous 20 % (recommandé)", "Sous 30 %", "Sous 40 %")
        AlertDialog.Builder(this).setTitle("Veille automatique de ${camera.name}")
            .setSingleChoiceItems(labels, values.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                dialog.dismiss()
                sendControl(camera.id, mapOf("op" to "threshold", "value" to values[which].toString()))
            }.setNegativeButton("Fermer", null).show()
    }

    private fun confirmAlarm(camera: PairingCode) {
        AlertDialog.Builder(this).setTitle("Déclencher l’alarme ?")
            .setMessage("Une sirène stridente va retentir sur « ${camera.name} », volume au maximum, même en silencieux, pendant 2 minutes ou jusqu’à ce que tu l’arrêtes.")
            .setPositiveButton("Déclencher") { _, _ -> sendControl(camera.id, mapOf("op" to "alarm", "value" to "1")) }
            .setNegativeButton("Annuler", null).show()
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
                // Batterie de la caméra, connue dès que la caméra a répondu (en surveillance).
                val state = remoteState[camera.id]
                state?.get("bat")?.toIntOrNull()?.let { percent ->
                    val (chip, update) = LynxUi.batteryChip(this@ViewerActivity)
                    update(percent, state["chg"] == "1")
                    addView(chip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
                }
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
            addRemoteControls(card, camera)
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

    private companion object {
        val NO_LISTENER = object : LiveClient.Listener {
            override fun onState(text: String, connected: Boolean) {}
            override fun onFrame(bitmap: android.graphics.Bitmap) = bitmap.recycle()
            override fun onStatus(fields: Map<String, String>) {}
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

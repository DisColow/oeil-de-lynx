package fr.oeildelynx

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import androidx.annotation.DrawableRes
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Sur le téléphone caméra : affiche le QR code à scanner avec le téléphone spectateur,
 * l’état de la box (UPnP, IP publique) et les adresses où le direct sera joignable.
 */
class PairingActivity : ComponentActivity() {
    private lateinit var qr: ImageView
    private lateinit var nameLabel: TextView
    private lateinit var addresses: TextView
    private lateinit var boxStatus: TextView
    private lateinit var settingsCard: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        LiveCamera.ensurePaired(this)
        val dp = { value: Int -> LynxUi.dp(this, value) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(LynxUi.BACKGROUND) }
        root.addView(LynxUi.header(this, "Appairer", "Téléphone spectateur") { finish() })
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(28)) }

        // Carte QR : fond blanc pour une lecture fiable, nom de la caméra dessous.
        val qrCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(22), dp(20), dp(18)); background = LynxUi.rounded(this@PairingActivity, LynxUi.CARD, 24)
        }
        qr = ImageView(this).apply {
            val pad = dp(14); setPadding(pad, pad, pad, pad)
            background = LynxUi.rounded(this@PairingActivity, Color.WHITE, 18)
            contentDescription = "QR code d’appairage"
        }
        qrCard.addView(qr, LinearLayout.LayoutParams(dp(250), dp(250)))
        nameLabel = LynxUi.title(this, "", 19f).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(4)) }
        qrCard.addView(nameLabel)
        qrCard.addView(LynxUi.body(this, "Sur l’autre téléphone : Réglages › Regarder une caméra › Scanner le QR code.", 13.5f).apply { gravity = Gravity.CENTER })
        qrCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(14), dp(10)); background = LynxUi.rounded(this@PairingActivity, 0xff2e2614.toInt(), 14)
            addView(LynxUi.icon(this@PairingActivity, R.drawable.ic_shield, LynxUi.WARNING, 20))
            addView(LynxUi.body(this@PairingActivity, "Ce code contient la clé secrète de la caméra : ne le montre qu’à des téléphones de confiance.", 12.5f, 0xfff3d29b.toInt())
                .apply { setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        content.addView(qrCard)

        // Réseau : état de la box et adresses publiées.
        content.addView(sectionTitle("Réseau"))
        val network = LynxUi.card(this, 16)
        network.addView(infoLine(R.drawable.ic_globe, "Box internet").also { boxStatus = it.second }.first)
        network.addView(View(this).apply { setBackgroundColor(LynxUi.OUTLINE) }, LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(14); bottomMargin = dp(14) })
        network.addView(infoLine(R.drawable.ic_wifi, "Adresses du direct").also { addresses = it.second }.first)
        network.addView(LynxUi.body(this,
            "Le flux va directement de ce téléphone au spectateur : même Wi-Fi, ou Internet via la box (port TCP ${LiveProtocol.DEFAULT_PORT}). " +
                "Aucun serveur intermédiaire. Disponible pendant la surveillance.", 12f).apply { setPadding(0, dp(14), 0, 0) })
        boxStatus.text = "Recherche de la box…"
        content.addView(network)

        content.addView(sectionTitle("Options"))
        settingsCard = LynxUi.card(this, 4)
        content.addView(settingsCard)

        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
        probeBox()
        refreshService()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    private fun sectionTitle(text: String) = LynxUi.overline(this, text, LynxUi.MUTED).apply {
        setPadding(LynxUi.dp(this@PairingActivity, 4), LynxUi.dp(this@PairingActivity, 22), 0, LynxUi.dp(this@PairingActivity, 8))
    }

    /** Ligne d’information : icône, titre, texte variable (renvoyé pour mise à jour). */
    private fun infoLine(@DrawableRes iconRes: Int, title: String): Pair<View, TextView> {
        val value = LynxUi.body(this, "", 13.5f, LynxUi.TEXT)
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(LynxUi.icon(this@PairingActivity, iconRes, LynxUi.ACCENT, 22))
            addView(LinearLayout(this@PairingActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(LynxUi.dp(this@PairingActivity, 14), 0, 0, 0)
                addView(LynxUi.overline(this@PairingActivity, title, LynxUi.MUTED).apply { setPadding(0, LynxUi.dp(this@PairingActivity, 2), 0, LynxUi.dp(this@PairingActivity, 6)) })
                addView(value)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return line to value
    }

    /** Options reconstruites à chaque rendu pour afficher le nom et l’adresse actuels. */
    private fun renderSettings() {
        settingsCard.removeAllViews()
        settingsCard.addView(LynxUi.row(this, R.drawable.ic_edit, "Nom de la caméra", LiveCamera.name(this)) { editName() })
        settingsCard.addView(LynxUi.row(this, R.drawable.ic_link, "Adresse personnalisée (DynDNS)",
            LiveCamera.customHost(this).ifBlank { "Aucune · utile si l’IP de la box change" }) { editCustomHost() })
        settingsCard.addView(LynxUi.row(this, R.drawable.ic_share, "Partager le code par message", "Si le QR code ne peut pas être scanné") { shareCode() })
        settingsCard.addView(LynxUi.row(this, R.drawable.ic_trash, "Révoquer tous les spectateurs", "Nouvelle clé : chaque spectateur devra rescanner", danger = true) { confirmRevoke() })
    }

    private fun render() {
        val code = LiveCamera.pairingCode(this) ?: return
        nameLabel.text = code.name
        renderSettings()
        qr.setImageBitmap(qrBitmap(code.encode(), 600))
        addresses.text = if (code.hosts.isEmpty()) "Aucune adresse réseau : connecte ce téléphone au Wi-Fi de la box."
        else code.hosts.joinToString("\n") { "$it  ${describe(it)}" }
    }

    private fun describe(host: String): String {
        val address = PairingCode.parseHost(host)?.first ?: return ""
        return when {
            address == LiveCamera.customHost(this) || host == LiveCamera.customHost(this) -> "(personnalisée)"
            address.contains(':') -> "(IPv6, Internet si la box l’autorise)"
            LiveUpnp.isPrivateIpv4(address) -> "(même Wi-Fi)"
            address.firstOrNull()?.isDigit() == true -> "(Internet via la box)"
            else -> ""
        }
    }

    /** UPnP est bloquant : interrogation de la box hors du thread principal. */
    private fun probeBox() {
        LiveCamera.boxStatus(this)?.let { boxStatus.text = it }
        Thread {
            val result = LiveCamera.probeBox(applicationContext, openPort = false)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val running = getSharedPreferences("lynx", MODE_PRIVATE).getBoolean("monitoring_active", false)
                boxStatus.text = if (running) (LiveCamera.boxStatus(this) ?: result.message) else result.message
                render()
            }
        }.start()
    }

    /** Si la surveillance tourne déjà, le service ouvre le direct sans attendre le prochain démarrage. */
    private fun refreshService() {
        if (!getSharedPreferences("lynx", MODE_PRIVATE).getBoolean("monitoring_active", false)) return
        try { startService(Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_LIVE_REFRESH)) } catch (_: Exception) { }
    }

    private fun editName() = prompt("Nom de la caméra", LiveCamera.name(this)) { LiveCamera.setName(this, it); render(); refreshService() }

    private fun editCustomHost() = prompt(
        "Nom d’hôte DynDNS ou IP fixe (ex. maison.freeboxos.fr)", LiveCamera.customHost(this)
    ) { LiveCamera.setCustomHost(this, it); render(); refreshService() }

    private fun shareCode() {
        val code = LiveCamera.pairingCode(this) ?: return
        AlertDialog.Builder(this).setTitle("Partager le code ?")
            .setMessage("Toute personne qui reçoit ce code peut regarder cette caméra. Envoie-le uniquement par une messagerie de confiance.")
            .setPositiveButton("Partager") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, code.encode()), "Code d’appairage"))
            }
            .setNegativeButton("Annuler", null).show()
    }

    private fun confirmRevoke() {
        AlertDialog.Builder(this).setTitle("Révoquer tous les spectateurs ?")
            .setMessage("Une nouvelle clé est créée. Les téléphones déjà appairés perdent l’accès et devront scanner le nouveau QR code.")
            .setPositiveButton("Révoquer") { _, _ -> LiveCamera.regenerate(this); render(); refreshService() }
            .setNegativeButton("Annuler", null).show()
    }

    private fun prompt(title: String, initial: String, onDone: (String) -> Unit) {
        val input = EditText(this).apply { setText(initial); setSingleLine() }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("OK") { _, _ -> onDone(input.text.toString()) }
            .setNegativeButton("Annuler", null).show()
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1))
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) pixels[y * matrix.width + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }
}

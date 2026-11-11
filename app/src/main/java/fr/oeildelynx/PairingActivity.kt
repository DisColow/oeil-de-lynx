package fr.oeildelynx

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        LiveCamera.ensurePaired(this)
        val dp = { value: Int -> LynxUi.dp(this, value) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(LynxUi.BACKGROUND) }
        root.addView(LynxUi.header(this, "Appairer", "TÉLÉPHONE SPECTATEUR · DIRECT") { finish() })
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), dp(24)) }

        content.addView(LynxUi.body(this,
            "Sur l’autre téléphone, ouvre Œil de Lynx › ⚙ › Regarder une caméra › Scanner le QR code.", 15f, Color.WHITE))
        qr = ImageView(this).apply {
            setBackgroundColor(Color.WHITE); val pad = dp(12); setPadding(pad, pad, pad, pad)
            contentDescription = "QR code d’appairage"
        }
        content.addView(qr, LinearLayout.LayoutParams(dp(280), dp(280)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(16); bottomMargin = dp(10) })
        nameLabel = TextView(this).apply { textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        content.addView(nameLabel)
        content.addView(LynxUi.body(this,
            "Ce QR code contient la clé secrète de cette caméra : ne le montre qu’aux téléphones de confiance.", 12f, 0xffe0b26b.toInt())
            .apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(14)) })

        content.addView(sectionTitle("BOX INTERNET"))
        boxStatus = LynxUi.body(this, "Recherche de la box…")
        content.addView(boxStatus)
        content.addView(sectionTitle("ADRESSES DU DIRECT"))
        addresses = LynxUi.body(this, "")
        content.addView(addresses)
        content.addView(LynxUi.body(this,
            "Le flux passe directement de ce téléphone au spectateur : sur le même Wi-Fi, ou depuis Internet par la box " +
                "(port TCP ${LiveProtocol.DEFAULT_PORT}). Aucun serveur intermédiaire. Le direct n’est disponible que pendant la surveillance.", 12f)
            .apply { setPadding(0, dp(10), 0, dp(16)) })

        content.addView(LynxUi.button(this, "Nom de la caméra", LynxUi.CARD) { editName() }, buttonParams())
        content.addView(LynxUi.button(this, "Adresse personnalisée (DynDNS)", LynxUi.CARD) { editCustomHost() }, buttonParams())
        content.addView(LynxUi.button(this, "Partager le code par message", LynxUi.CARD) { shareCode() }, buttonParams())
        content.addView(LynxUi.button(this, "Révoquer tous les spectateurs", LynxUi.DANGER) { confirmRevoke() }, buttonParams())

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

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text; textSize = 10f; letterSpacing = .14f; setTextColor(LynxUi.ACCENT); typeface = Typeface.DEFAULT_BOLD
        setPadding(0, LynxUi.dp(this@PairingActivity, 14), 0, LynxUi.dp(this@PairingActivity, 5))
    }

    private fun buttonParams() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = LynxUi.dp(this@PairingActivity, 8) }

    private fun render() {
        val code = LiveCamera.pairingCode(this) ?: return
        nameLabel.text = code.name
        qr.setImageBitmap(qrBitmap(code.encode(), 600))
        addresses.text = if (code.hosts.isEmpty()) "Aucune adresse réseau : connecte ce téléphone au Wi-Fi de la box."
        else code.hosts.joinToString("\n") { "• $it  ${describe(it)}" }
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

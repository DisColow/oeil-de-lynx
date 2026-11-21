package fr.oeildelynx

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Choix du son personnalisé de l’alarme : message enregistré au micro (20 s au plus) ou fichier audio
 * du téléphone. À créer pendant l’initialisation de l’activité (enregistrement des lanceurs Android).
 * [deliver] reçoit le son et rappelle avec un message d’erreur, ou null en cas de succès.
 */
internal class CustomSoundUi(private val activity: ComponentActivity) {
    private var deliver: ((ByteArray, (String?) -> Unit) -> Unit)? = null
    private var afterPermission: (() -> Unit)? = null
    private var success = ""

    private val pickAudio = activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { sendFile(it) } }
    private val micPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val next = afterPermission; afterPermission = null
        if (granted) next?.invoke() else toast("Le micro est nécessaire pour enregistrer un message.")
    }

    /** [success] : message affiché une fois le son enregistré. */
    fun open(title: String, success: String = "Son personnalisé enregistré : il sera joué par l’alarme.", deliver: (ByteArray, (String?) -> Unit) -> Unit) {
        this.deliver = deliver
        this.success = success
        LynxUi.sheet(activity, title, listOf(
            LynxUi.SheetItem(R.drawable.ic_mic, "Enregistrer un message", "20 secondes au plus, avec le micro de ce téléphone") { recordWithPermission() },
            LynxUi.SheetItem(R.drawable.ic_volume, "Choisir un fichier audio", "MP3, M4A, OGG… 3 Mo au plus, 30 s jouées au plus") { pickAudio.launch("audio/*") }
        ))
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    private fun sendFile(uri: Uri) {
        val bytes = try {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (out.size() <= CustomAlarmSound.MAX_BYTES) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
                if (out.size() > CustomAlarmSound.MAX_BYTES) null else out.toByteArray()
            }
        } catch (_: Exception) { null }
        if (bytes == null) { toast("Fichier illisible ou trop gros (3 Mo au plus)."); return }
        confirmFile(bytes)
    }

    /** Écouter le fichier choisi sur ce téléphone avant de l’utiliser. */
    private fun confirmFile(bytes: ByteArray) {
        val temp = File(activity.cacheDir, "custom_sound_pick.audio").apply { writeBytes(bytes) }
        var player: MediaPlayer? = null
        val dialog = AlertDialog.Builder(activity).setTitle("Utiliser ce son ?")
            .setMessage("« Écouter » le joue sur ce téléphone (30 premières secondes utilisées).")
            .setPositiveButton("Utiliser ce son") { _, _ -> send(bytes) }
            .setNeutralButton("Écouter", null)
            .setNegativeButton("Annuler", null)
            .setOnDismissListener { player?.release(); player = null; temp.delete() }
            .show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            player?.release()
            player = try { MediaPlayer().apply { setDataSource(temp.absolutePath); prepare(); start() } }
                catch (_: Exception) { toast("Ce fichier ne peut pas être lu par Android."); null }
        }
    }

    private fun send(bytes: ByteArray) {
        val target = deliver ?: return
        toast("Envoi du son…")
        target(bytes) { error ->
            activity.runOnUiThread { toast(error ?: success) }
        }
    }

    private fun recordWithPermission() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) recordDialog()
        else { afterPermission = { recordDialog() }; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
    }

    /** Fenêtre d’enregistrement : Enregistrer / Arrêter, Écouter, puis Utiliser ce message. */
    private fun recordDialog() {
        val file = File(activity.cacheDir, "custom_alarm_rec.m4a").apply { delete() }
        var recorder: MediaRecorder? = null
        var preview: MediaPlayer? = null
        var startedAt = 0L
        val dp = { v: Int -> LynxUi.dp(activity, v) }
        val status = LynxUi.body(activity, "Touche « Enregistrer » puis parle près du micro.", 14f, LynxUi.TEXT).apply { gravity = Gravity.CENTER }
        val timer = LynxUi.title(activity, "0:00", 34f).apply { gravity = Gravity.CENTER }
        val recordLabel = TextView(activity)
        lateinit var dialog: AlertDialog
        val ticker = object : Runnable {
            override fun run() {
                if (recorder == null) return
                val s = (SystemClock.elapsedRealtime() - startedAt) / 1000
                timer.text = "0:%02d".format(s)
                timer.postDelayed(this, 250)
            }
        }
        fun stopRecording() {
            val r = recorder ?: return
            recorder = null
            try { r.stop() } catch (_: Exception) { file.delete() }
            r.release()
            recordLabel.text = "● Recommencer"
            val ok = file.length() > 0
            status.text = if (ok) "Message prêt. Écoute-le, puis utilise-le pour l’alarme." else "Enregistrement trop court, recommence."
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = ok
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = ok
        }
        fun startRecording() {
            preview?.release(); preview = null
            file.delete()
            val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(activity) else @Suppress("DEPRECATION") MediaRecorder()
            try {
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(44_100); r.setAudioEncodingBitRate(96_000); r.setAudioChannels(1)
                r.setMaxDuration(MAX_RECORD_MS)
                r.setOutputFile(file.absolutePath)
                r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) activity.runOnUiThread { stopRecording() } }
                r.prepare(); r.start()
            } catch (_: Exception) { r.release(); toast("Micro indisponible."); return }
            recorder = r; startedAt = SystemClock.elapsedRealtime()
            recordLabel.text = "■ Arrêter"
            status.text = "Enregistrement… (20 s au plus)"
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = false
            timer.post(ticker)
        }
        val recordButton = LinearLayout(activity).apply {
            gravity = Gravity.CENTER; setPadding(dp(18), 0, dp(18), 0)
            background = LynxUi.pressable(activity, LynxUi.DANGER_SOFT, 27)
            recordLabel.apply { text = "● Enregistrer"; textSize = 16f; setTextColor(0xffff8a8d.toInt()) }
            addView(recordLabel)
            setOnClickListener { if (recorder != null) stopRecording() else startRecording() }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(8), dp(24), dp(4))
            addView(timer)
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(16) })
            addView(recordButton, LinearLayout.LayoutParams(-1, dp(54)))
        }
        dialog = AlertDialog.Builder(activity).setTitle("Message de l’alarme").setView(content)
            .setPositiveButton("Utiliser ce message", null)
            .setNeutralButton("Écouter", null)
            .setNegativeButton("Annuler", null)
            .setOnDismissListener {
                recorder?.let { try { it.stop() } catch (_: Exception) { }; it.release() }; recorder = null
                preview?.release(); preview = null
            }
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            isEnabled = false
            setOnClickListener {
                val bytes = try { file.readBytes() } catch (_: Exception) { null }
                dialog.dismiss()
                if (bytes != null && bytes.isNotEmpty()) send(bytes) else toast("Aucun enregistrement.")
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
            isEnabled = false
            // Écouter ne ferme pas la fenêtre.
            setOnClickListener {
                preview?.release()
                preview = try { MediaPlayer().apply { setDataSource(file.absolutePath); prepare(); start() } } catch (_: Exception) { null }
            }
        }
    }

    companion object { private const val MAX_RECORD_MS = 20_000 }
}

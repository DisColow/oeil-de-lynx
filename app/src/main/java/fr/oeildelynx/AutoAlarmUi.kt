package fr.oeildelynx

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog

/**
 * Réglage à distance de l’alarme automatique d’une caméra (liste des caméras et direct du spectateur).
 * [send] reçoit les commandes à transmettre à la caméra, dans l’ordre.
 */
internal object AutoAlarmUi {
    private fun prefs(context: Context) = context.getSharedPreferences("lynx_viewer", Context.MODE_PRIVATE)

    /** Dernier déclencheur choisi pour cette caméra : réutilisé par l’interrupteur « activer ». */
    fun lastMode(context: Context, cameraId: String): String =
        prefs(context).getString("aa_last_$cameraId", null)?.takeIf { it != MonitorService.AUTO_OFF } ?: MonitorService.AUTO_MOTION

    fun remember(context: Context, cameraId: String, mode: String?) {
        if (mode != null && mode != MonitorService.AUTO_OFF && mode in MonitorService.AUTO_ALARM_MODES) prefs(context).edit().putString("aa_last_$cameraId", mode).apply()
    }

    fun isOn(state: Map<String, String>?) = state?.get("aa").let { it != null && it != MonitorService.AUTO_OFF }

    /** Ex. « Sur mouvement · Sirène (30 s) · s’arme dans 42 s ». */
    fun summary(state: Map<String, String>): String {
        val mode = state["aa"] ?: return "Inconnue"
        if (mode == MonitorService.AUTO_OFF) return "Désactivée"
        val arming = state["aarm"]?.toLongOrNull() ?: 0
        val sound = MonitorService.autoAlarmSoundLabel(state["aas"] ?: MonitorService.SIREN)
        return "${MonitorService.AUTO_ALARM_LABELS[mode] ?: mode} · $sound" + if (arming > 0) " · s’arme dans $arming s" else " · armée"
    }

    fun setMode(mode: String) = mapOf("op" to "autoalarm", "value" to mode)

    /**
     * Choix du déclencheur, puis du son si l’alarme est activée. [onCustom] ouvre l’enregistrement /
     * l’import d’un son personnalisé (null : option absente).
     */
    fun chooseMode(activity: Activity, cameraName: String, state: Map<String, String>, onCustom: (() -> Unit)?, send: (List<Map<String, String>>) -> Unit) {
        val modes = MonitorService.AUTO_ALARM_LABELS.keys.toList()
        val current = state["aa"] ?: MonitorService.AUTO_OFF
        AlertDialog.Builder(activity).setTitle("Alarme automatique · $cameraName")
            .setSingleChoiceItems(MonitorService.AUTO_ALARM_LABELS.values.toTypedArray(), modes.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                dialog.dismiss()
                val chosen = modes[which]
                if (chosen == MonitorService.AUTO_OFF) send(listOf(setMode(chosen)))
                else {
                    // Le déclencheur est appliqué tout de suite ; le son suit (ou l’envoi d’un son personnalisé).
                    send(listOf(setMode(chosen)))
                    chooseSound(activity, cameraName, state, onCustom) { picked ->
                        picked?.let { send(listOf(mapOf("op" to "autoalarmsound", "value" to it))) }
                    }
                }
            }.setNegativeButton("Fermer", null).show()
    }

    /**
     * [onChosen] reçoit le son choisi, ou null si l’utilisateur garde le son actuel. Le son personnalisé
     * n’est proposé que si la caméra en a un ; la dernière ligne en enregistre / importe un nouveau.
     */
    fun chooseSound(activity: Activity, cameraName: String, state: Map<String, String>, onCustom: (() -> Unit)?, onChosen: (String?) -> Unit) {
        val current = state["aas"] ?: MonitorService.SIREN
        val ids = listOf(MonitorService.SIREN) + DeterrentSounds.Effect.entries.map { it.id } +
            (if (state["aac"] == "1") listOf(CustomAlarmSound.ID) else emptyList())
        val labels = ids.map { MonitorService.autoAlarmSoundLabel(it) } +
            (if (onCustom != null) listOf(if (state["aac"] == "1") "＋ Remplacer le son personnalisé…" else "＋ Enregistrer ou importer un son…") else emptyList())
        var answered = false
        AlertDialog.Builder(activity).setTitle("Son de l’alarme · $cameraName")
            .setSingleChoiceItems(labels.toTypedArray(), ids.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                answered = true; dialog.dismiss()
                if (which >= ids.size) { onChosen(null); onCustom?.invoke() } else onChosen(ids[which])
            }
            .setNegativeButton("Garder ${MonitorService.autoAlarmSoundLabel(current)}", null)
            .setOnDismissListener { if (!answered) { answered = true; onChosen(null) } }
            .show()
    }

    /**
     * Envoie le son personnalisé à la caméra puis le choisit comme son de l’alarme (hors du thread principal).
     * [done] reçoit l’état de la caméra, ou un message d’erreur.
     */
    fun uploadCustom(context: Context, cameraId: String, bytes: ByteArray, done: (Map<String, String>?, String?) -> Unit) {
        Thread {
            try {
                LiveClient(context.applicationContext, cameraId, NO_LISTENER).openGallery().use { session ->
                    session.uploadSound(bytes)
                    val reply = session.request(mapOf("op" to "autoalarmsound", "value" to CustomAlarmSound.ID))
                    done(LiveProtocol.decodeFields(String(reply.payload, Charsets.UTF_8)), null)
                }
            } catch (error: Exception) {
                done(null, error.message ?: "Caméra injoignable : elle doit être en surveillance.")
            }
        }.start()
    }

    private val NO_LISTENER = object : LiveClient.Listener {
        override fun onState(text: String, connected: Boolean) {}
        override fun onFrame(bitmap: android.graphics.Bitmap) = bitmap.recycle()
        override fun onStatus(fields: Map<String, String>) {}
    }

    const val ARMING_NOTE = "Après chaque activation, l’alarme attend 1 minute avant de pouvoir se déclencher (le temps de quitter la pièce)."
}

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

    /** Choix du déclencheur, puis du son si l’alarme est activée. */
    fun chooseMode(activity: Activity, cameraName: String, state: Map<String, String>, send: (List<Map<String, String>>) -> Unit) {
        val modes = MonitorService.AUTO_ALARM_LABELS.keys.toList()
        val current = state["aa"] ?: MonitorService.AUTO_OFF
        AlertDialog.Builder(activity).setTitle("Alarme automatique · $cameraName")
            .setSingleChoiceItems(MonitorService.AUTO_ALARM_LABELS.values.toTypedArray(), modes.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                dialog.dismiss()
                val chosen = modes[which]
                if (chosen == MonitorService.AUTO_OFF) send(listOf(setMode(chosen)))
                else chooseSound(activity, cameraName, state["aas"] ?: MonitorService.SIREN) { picked ->
                    send(listOfNotNull(picked?.let { mapOf("op" to "autoalarmsound", "value" to it) }, setMode(chosen)))
                }
            }.setNegativeButton("Fermer", null).show()
    }

    /** [onChosen] reçoit le son choisi, ou null si l’utilisateur garde le son actuel. */
    fun chooseSound(activity: Activity, cameraName: String, current: String, onChosen: (String?) -> Unit) {
        val ids = listOf(MonitorService.SIREN) + DeterrentSounds.Effect.entries.map { it.id }
        var answered = false
        AlertDialog.Builder(activity).setTitle("Son de l’alarme · $cameraName")
            .setSingleChoiceItems(ids.map { MonitorService.autoAlarmSoundLabel(it) }.toTypedArray(), ids.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                answered = true; dialog.dismiss(); onChosen(ids[which])
            }
            .setNegativeButton("Garder ${MonitorService.autoAlarmSoundLabel(current)}", null)
            .setOnDismissListener { if (!answered) { answered = true; onChosen(null) } }
            .show()
    }

    const val ARMING_NOTE = "Après chaque activation, l’alarme attend 1 minute avant de pouvoir se déclencher (le temps de quitter la pièce)."
}

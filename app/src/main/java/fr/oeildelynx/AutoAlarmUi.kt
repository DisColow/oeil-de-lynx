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
    fun chooseMode(activity: Activity, cameraName: String, state: Map<String, String>, onCustom: (() -> Unit)?,
                   customFile: java.io.File? = null, send: (List<Map<String, String>>) -> Unit) {
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
                    chooseSound(activity, cameraName, state, onCustom, customFile) { picked ->
                        picked?.let { send(listOf(mapOf("op" to "autoalarmsound", "value" to it))) }
                    }
                }
            }.setNegativeButton("Fermer", null).show()
    }

    /**
     * [onChosen] reçoit le son choisi, ou null si l’utilisateur garde le son actuel. Toucher une ligne
     * sélectionne le son et le fait écouter sur ce téléphone ; « Utiliser ce son » valide. Le son
     * personnalisé n’est proposé que si la caméra en a un ([customFile] : copie locale pour l’écouter) ;
     * la dernière ligne en enregistre / importe un nouveau.
     */
    fun chooseSound(activity: Activity, cameraName: String, state: Map<String, String>, onCustom: (() -> Unit)?,
                    customFile: java.io.File? = null, onChosen: (String?) -> Unit) {
        val current = state["aas"] ?: MonitorService.SIREN
        val ids = listOf(MonitorService.SIREN) + DeterrentSounds.Effect.entries.map { it.id } +
            (if (state["aac"] == "1") listOf(CustomAlarmSound.ID) else emptyList())
        val preview = SoundPreview(activity)
        var selected = current.takeIf { it in ids } ?: MonitorService.SIREN
        var answered = false
        val dp = { v: Int -> LynxUi.dp(activity, v) }
        lateinit var dialog: AlertDialog
        val list = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(4)) }
        val marks = mutableMapOf<String, android.view.View>()
        fun refreshMarks() = marks.forEach { (id, mark) ->
            mark.background = if (id == selected) LynxUi.circle(LynxUi.ACCENT) else android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke(dp(2), LynxUi.MUTED)
            }
        }
        fun listen(id: String) {
            if (!preview.play(id, customFile)) android.widget.Toast.makeText(activity,
                "Écoute indisponible ici : ce son personnalisé a été envoyé depuis un autre téléphone.", android.widget.Toast.LENGTH_LONG).show()
        }
        ids.forEach { id ->
            val mark = android.view.View(activity)
            marks[id] = mark
            list.addView(android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(4), dp(4), dp(4))
                background = LynxUi.pressable(activity, 0x00000000, 14)
                addView(mark, android.widget.LinearLayout.LayoutParams(dp(18), dp(18)))
                addView(android.widget.TextView(activity).apply {
                    text = MonitorService.autoAlarmSoundLabel(id); textSize = 15f; setTextColor(LynxUi.TEXT); setPadding(dp(14), 0, dp(8), 0)
                }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                addView(LynxUi.iconButton(activity, R.drawable.ic_headphones, "Écouter ${MonitorService.autoAlarmSoundLabel(id)}", 0x66223029, 40) { listen(id) })
                // Toucher la ligne : la sélectionne et la fait écouter.
                setOnClickListener { selected = id; refreshMarks(); listen(id) }
            }, android.widget.LinearLayout.LayoutParams(-1, dp(52)))
        }
        if (onCustom != null) list.addView(android.widget.TextView(activity).apply {
            text = if (state["aac"] == "1") "＋ Remplacer le son personnalisé…" else "＋ Enregistrer ou importer un son…"
            textSize = 15f; setTextColor(LynxUi.ACCENT); setPadding(dp(10), dp(14), dp(10), dp(10))
            setOnClickListener { answered = true; dialog.dismiss(); onChosen(null); onCustom() }
        })
        list.addView(LynxUi.body(activity, "🎧 = écouter sur ce téléphone (volume multimédia). Sur la caméra, le son sera joué au volume maximum.", 12f)
            .apply { setPadding(dp(10), dp(8), dp(10), dp(4)) })
        refreshMarks()
        dialog = AlertDialog.Builder(activity).setTitle("Son de l’alarme · $cameraName")
            .setView(android.widget.ScrollView(activity).apply { addView(list) })
            .setPositiveButton("Utiliser ce son") { _, _ -> answered = true; onChosen(selected.takeIf { it != current }) }
            .setNegativeButton("Garder ${MonitorService.autoAlarmSoundLabel(current)}", null)
            .setOnDismissListener { preview.stop(); if (!answered) { answered = true; onChosen(null) } }
            .show()
    }

    /**
     * Envoie le son personnalisé à la caméra (hors du thread principal) ; [forAlarm] le choisit aussi comme
     * son de l’alarme automatique. [done] reçoit l’état de la caméra, ou un message d’erreur.
     */
    fun uploadCustom(context: Context, cameraId: String, bytes: ByteArray, forAlarm: Boolean = true, done: (Map<String, String>?, String?) -> Unit) {
        Thread {
            try {
                LiveClient(context.applicationContext, cameraId, NO_LISTENER).openGallery().use { session ->
                    val state = session.uploadSound(bytes)
                    // Copie locale : le son personnalisé pourra être réécouté sur ce téléphone.
                    try { SoundPreview.customCopy(context, cameraId).writeBytes(bytes) } catch (_: Exception) { }
                    if (!forAlarm) { done(state, null); return@use }
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

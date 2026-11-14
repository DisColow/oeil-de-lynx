package fr.oeildelynx

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * Volume du flux « alarme » poussé au maximum tant qu’au moins un utilisateur le demande
 * (voix d’un spectateur, sirène), puis rétabli à sa valeur d’origine quand le dernier s’arrête.
 */
internal object AlarmVolume {
    private var users = 0
    private var saved: Int? = null

    @Synchronized fun raise(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        if (users++ > 0) return
        try {
            saved = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (error: SecurityException) {
            // Mode « Ne pas déranger » : Android peut refuser ; le son sort au volume d’alarme actuel.
            Log.w("LynxAlarmVolume", "Cannot raise alarm volume", error)
        }
    }

    @Synchronized fun release(context: Context) {
        if (users == 0 || --users > 0) return
        val volume = saved ?: return
        saved = null
        try { context.getSystemService(AudioManager::class.java)?.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) } catch (_: SecurityException) { }
    }
}

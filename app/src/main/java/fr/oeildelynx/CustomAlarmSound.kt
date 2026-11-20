package fr.oeildelynx

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.File

/**
 * Son personnalisé de l’alarme (message enregistré ou fichier audio), gardé sur le téléphone caméra.
 * Il peut venir du téléphone caméra lui-même ou être envoyé par un spectateur.
 */
internal object CustomAlarmSound {
    const val ID = "custom"
    const val LABEL = "Son personnalisé"
    const val MAX_BYTES = 3 * 1024 * 1024
    /** Lecture coupée au-delà (un long morceau ne bloque pas la caméra). */
    const val MAX_PLAY_MS = 30_000L

    fun file(context: Context) = File(context.filesDir, "alarm_custom.audio")

    fun exists(context: Context) = file(context).length() > 0

    /** Enregistre le son s’il est lisible ; renvoie un message d’erreur sinon. */
    fun save(context: Context, bytes: ByteArray): String? {
        val temp = File(context.filesDir, "alarm_custom.tmp")
        check(temp, bytes)?.let { temp.delete(); return it }
        if (!temp.renameTo(file(context))) { temp.delete(); return "Impossible d’enregistrer le son." }
        return null
    }

    /** Vérifie, avant tout envoi, qu’Android sait lire ce son ; renvoie un message d’erreur sinon. */
    fun validate(context: Context, bytes: ByteArray): String? {
        val temp = File(context.cacheDir, "alarm_custom_check.tmp")
        return check(temp, bytes).also { temp.delete() }
    }

    private fun check(temp: File, bytes: ByteArray): String? {
        if (bytes.isEmpty()) return "Fichier vide."
        if (bytes.size > MAX_BYTES) return "Fichier trop gros (3 Mo au plus)."
        temp.writeBytes(bytes)
        // Lecteur réel plutôt que métadonnées seules : c’est lui qui jouera le son.
        val player = android.media.MediaPlayer()
        return try {
            player.setDataSource(temp.absolutePath)
            player.prepare()
            null
        } catch (_: Exception) {
            "Format audio non lisible par Android (essaie un MP3 ou un M4A)."
        } finally { player.release() }
    }
}

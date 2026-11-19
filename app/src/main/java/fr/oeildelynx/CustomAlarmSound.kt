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
        if (bytes.isEmpty()) return "Fichier vide."
        if (bytes.size > MAX_BYTES) return "Fichier trop gros (3 Mo au plus)."
        val temp = File(context.filesDir, "alarm_custom.tmp")
        temp.writeBytes(bytes)
        val ok = try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(temp.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes" ||
                    (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0
            }
        } catch (_: Exception) { false }
        if (!ok) { temp.delete(); return "Format audio non reconnu." }
        if (!temp.renameTo(file(context))) { temp.delete(); return "Impossible d’enregistrer le son." }
        return null
    }
}

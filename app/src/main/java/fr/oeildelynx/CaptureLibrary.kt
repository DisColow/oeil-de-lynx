package fr.oeildelynx

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Collections

/**
 * Clips de `Movies/Œil de Lynx`, exposés aux téléphones spectateurs par le serveur du direct.
 * Seuls les clips de ce dossier sont accessibles : un identifiant hors de la liste est refusé.
 */
internal class CaptureLibrary(private val context: Context) : LiveGallery {
    private val thumbnails = Collections.synchronizedMap(object : LinkedHashMap<Long, ByteArray>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 120
    })

    override fun list(): List<Map<String, String>> {
        val clips = mutableListOf<Map<String, String>>()
        val columns = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE
        )
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, columns,
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf("${Environment.DIRECTORY_MOVIES}/Œil de Lynx/"),
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext() && clips.size < MAX_CLIPS) {
                clips += linkedMapOf(
                    "id" to cursor.getLong(0).toString(),
                    "name" to (cursor.getString(1) ?: "Vidéo"),
                    "start" to (cursor.getLong(2) * 1000L).toString(),
                    "dur" to cursor.getLong(3).toString(),
                    "size" to cursor.getLong(4).toString()
                )
            }
        }
        return clips
    }

    @Volatile private var knownIds: Set<String> = emptySet()
    @Volatile private var knownAt = 0L

    /** Vérifie que l’identifiant est bien un clip du dossier ; liste gardée 10 s pour éviter une requête par miniature. */
    private fun owns(id: Long): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - knownAt > 10_000 || id.toString() !in knownIds) {
            knownIds = list().mapNotNull { it["id"] }.toSet(); knownAt = now
        }
        return id.toString() in knownIds
    }

    override fun thumbnail(id: Long): ByteArray? {
        thumbnails[id]?.let { return it }
        if (!owns(id)) return null
        val frame = try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri(id))
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                retriever.getScaledFrameAtTime(minOf(1_000L, duration / 2) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 240)
            }
        } catch (_: Exception) { null } ?: return null
        val bytes = ByteArrayOutputStream().also { frame.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
        frame.recycle()
        thumbnails[id] = bytes
        return bytes
    }

    override fun size(id: Long): Long = try {
        if (owns(id)) context.contentResolver.openFileDescriptor(uri(id), "r")?.use { it.statSize } ?: 0L else 0L
    } catch (_: Exception) { 0L }

    override fun open(id: Long): InputStream? = if (owns(id)) context.contentResolver.openInputStream(uri(id)) else null

    private fun uri(id: Long) = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

    companion object { private const val MAX_CLIPS = 200 }
}

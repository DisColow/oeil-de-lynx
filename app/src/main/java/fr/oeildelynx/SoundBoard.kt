package fr.oeildelynx

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.Executors

/**
 * Téléphone caméra : joue un son dissuasif demandé par un spectateur, sur le flux alarme
 * (audible en silencieux) avec le volume d’alarme au maximum, puis rétabli.
 * Un nouveau son interrompt le précédent ; les sons calculés sont gardés en mémoire.
 */
internal class SoundBoard(private val context: Context) {
    private val worker = Executors.newSingleThreadExecutor()
    private val cache = mutableMapOf<DeterrentSounds.Effect, ShortArray>()
    @Volatile private var current: AudioTrack? = null

    /** [onDone] est appelé (sur le fil de lecture) quand le son se termine ou est interrompu. */
    fun play(effect: DeterrentSounds.Effect, onDone: (() -> Unit)? = null) {
        // Un nouveau son coupe le précédent (enregistrement ou synthèse).
        stop()
        worker.execute { try { playNow(effect) } finally { onDone?.invoke() } }
    }

    /** Joue un fichier audio (son personnalisé) pendant [maxMs] au plus, au volume d’alarme maximal. */
    fun playFile(file: java.io.File, maxMs: Long, onDone: (() -> Unit)? = null) {
        stop()
        worker.execute {
            try { playMedia("custom", maxMs) { it.setDataSource(file.absolutePath) } } finally { onDone?.invoke() }
        }
    }

    /** Coupe le son en cours (s’il y en a un). */
    fun stop() {
        current?.let { try { it.stop() } catch (_: Exception) { } }
        currentPlayer?.let { try { it.stop() } catch (_: Exception) { } }
        currentDone?.countDown() // libère tout de suite le fil de lecture pour le son suivant
    }

    private fun playNow(effect: DeterrentSounds.Effect) {
        // Enregistrement réel en priorité ; synthèse seulement s’il manque ou échoue.
        if (playRecording(effect)) return
        val pcm = cache.getOrPut(effect) { DeterrentSounds.render(effect) }
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(DeterrentSounds.RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC).build()
        } catch (error: Exception) { Log.w(TAG, "Cannot play ${effect.id}", error); return }
        AlarmVolume.raise(context)
        try {
            track.write(pcm, 0, pcm.size)
            track.setVolume(AudioTrack.getMaxVolume())
            current = track
            track.play()
            Log.i(TAG, "Playing ${effect.id}")
            // Attend la fin (ou l’arrêt par un son suivant) avant de rendre le volume.
            val endMs = System.currentTimeMillis() + pcm.size * 1000L / DeterrentSounds.RATE + 150
            while (System.currentTimeMillis() < endMs && track.playState == AudioTrack.PLAYSTATE_PLAYING) Thread.sleep(40)
        } catch (_: Exception) {
        } finally {
            if (current === track) current = null
            try { track.stop() } catch (_: Exception) { }
            track.release()
            AlarmVolume.release(context)
        }
    }

    @Volatile private var currentPlayer: android.media.MediaPlayer? = null
    @Volatile private var currentDone: java.util.concurrent.CountDownLatch? = null

    /** Joue res/raw/<recording> sur le flux alarme, volume au maximum ; faux si indisponible. */
    private fun playRecording(effect: DeterrentSounds.Effect): Boolean {
        val name = effect.recording ?: return false
        @android.annotation.SuppressLint("DiscouragedApi") // nom de ressource porté par l’énumération
        val res = context.resources.getIdentifier(name, "raw", context.packageName)
        if (res == 0) return false
        return playMedia(effect.id, 15_000) { player ->
            context.resources.openRawResourceFd(res).use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
        }
    }

    private fun playMedia(label: String, maxMs: Long, source: (android.media.MediaPlayer) -> Unit): Boolean {
        val player = android.media.MediaPlayer()
        val done = java.util.concurrent.CountDownLatch(1)
        AlarmVolume.raise(context)
        try {
            player.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            source(player)
            player.setVolume(1f, 1f)
            player.setOnCompletionListener { done.countDown() }
            player.setOnErrorListener { _, _, _ -> done.countDown(); true }
            player.prepare()
            currentPlayer = player; currentDone = done
            player.start()
            Log.i(TAG, "Playing recording $label")
            val length = player.duration.toLong()
            done.await((if (length <= 0) maxMs else length.coerceIn(500, maxMs)) + 1_000, java.util.concurrent.TimeUnit.MILLISECONDS)
            return true
        } catch (error: Exception) {
            Log.w(TAG, "Recording $label unavailable", error)
            return false
        } finally {
            if (currentPlayer === player) { currentPlayer = null; currentDone = null }
            try { player.release() } catch (_: Exception) { }
            AlarmVolume.release(context)
        }
    }

    fun release() { current?.let { try { it.stop() } catch (_: Exception) { } }; worker.shutdownNow() }

    companion object { private const val TAG = "LynxSoundBoard" }
}

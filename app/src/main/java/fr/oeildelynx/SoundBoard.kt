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

    fun play(effect: DeterrentSounds.Effect) {
        current?.let { try { it.stop() } catch (_: Exception) { } }
        worker.execute { playNow(effect) }
    }

    private fun playNow(effect: DeterrentSounds.Effect) {
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

    fun release() { current?.let { try { it.stop() } catch (_: Exception) { } }; worker.shutdownNow() }

    companion object { private const val TAG = "LynxSoundBoard" }
}

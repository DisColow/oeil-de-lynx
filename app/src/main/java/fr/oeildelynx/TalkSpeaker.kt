package fr.oeildelynx

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Téléphone caméra : joue la voix envoyée par un spectateur (bouton « Parler »), le plus fort possible.
 *
 * Le son passe par le flux « alarme » : il reste audible si le téléphone est en silencieux ou en vibreur.
 * Le volume de ce flux est poussé au maximum pendant la prise de parole, puis remis à sa valeur
 * d’origine 1,5 s après le dernier morceau reçu.
 */
internal class TalkSpeaker(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val queue = LinkedBlockingDeque<ShortArray>()
    @Volatile private var running = true
    @Volatile private var lastChunkAt = 0L
    private var track: AudioTrack? = null
    private var savedVolume: Int? = null

    private val thread = Thread({
        while (running) {
            val chunk = try { queue.poll(250, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
            if (chunk != null) {
                val out = track ?: open() ?: continue
                try { out.write(chunk, 0, chunk.size) } catch (_: Exception) { }
            } else if (track != null && SystemClock.elapsedRealtime() - lastChunkAt > SILENCE_END_MS) {
                close()
            }
        }
        close()
    }, "lynx-talk-speaker").apply { start() }

    /** Morceau de voix µ-law reçu d’un spectateur (thread réseau). */
    fun play(chunk: ByteArray) {
        lastChunkAt = SystemClock.elapsedRealtime()
        queue.offer(MuLaw.decode(chunk))
        while (queue.size > MAX_QUEUE) queue.pollFirst() // ~0,5 s de retard au plus
    }

    fun release() { running = false; thread.interrupt() }

    private fun open(): AudioTrack? {
        try {
            // Volume au maximum, l’ancienne valeur est mémorisée pour être rétablie.
            savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (error: SecurityException) {
            Log.w(TAG, "Cannot raise alarm volume", error)
        }
        val rate = LiveProtocol.AUDIO_SAMPLE_RATE
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min, rate / 2))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
                .also { it.setVolume(AudioTrack.getMaxVolume()); it.play() }
        } catch (error: Exception) {
            Log.w(TAG, "Cannot open talk speaker", error); restoreVolume(); null
        }
        Log.i(TAG, "Viewer started talking")
        return track
    }

    private fun close() {
        val old = track ?: return
        track = null
        try { old.stop() } catch (_: Exception) { }
        old.release()
        restoreVolume()
        Log.i(TAG, "Viewer stopped talking")
    }

    private fun restoreVolume() {
        val volume = savedVolume ?: return
        savedVolume = null
        try { audio.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) } catch (_: SecurityException) { }
    }

    companion object {
        private const val TAG = "LynxTalk"
        private const val SILENCE_END_MS = 1_500L
        private const val MAX_QUEUE = 8
    }
}

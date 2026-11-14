package fr.oeildelynx

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * Sirène stridente du téléphone caméra, déclenchée à distance par un spectateur.
 * Deux tons alternés (960 / 1 650 Hz) proches d’une alarme de sécurité, au volume d’alarme maximal,
 * plus vibration. S’arrête sur demande, ou seule après [MAX_DURATION_MS] par sécurité.
 */
internal class AlarmSiren(private val context: Context, private val onStopped: () -> Unit) {
    @Volatile var isRunning = false
        private set
    private var thread: Thread? = null

    @Synchronized fun start() {
        if (isRunning) return
        isRunning = true
        AlarmVolume.raise(context)
        vibrate(true)
        thread = Thread({ play() }, "lynx-siren").apply { start() }
        Log.i(TAG, "Siren started")
    }

    @Synchronized fun stop() {
        if (!isRunning) return
        isRunning = false
        thread?.interrupt(); thread = null
    }

    private fun play() {
        val rate = 44_100
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(rate) // ~0,5 s
                .setTransferMode(AudioTrack.MODE_STREAM).build()
        } catch (error: Exception) { Log.w(TAG, "Cannot open siren", error); finish(); return }
        track.setVolume(AudioTrack.getMaxVolume())
        track.play()
        // Un demi-ton de 250 ms, tons alternés ; onde presque carrée pour un son perçant.
        val tones = listOf(960.0, 1650.0).map { tone(rate, it, 0.25) }
        val started = System.currentTimeMillis()
        var index = 0
        try {
            while (isRunning && !Thread.currentThread().isInterrupted && System.currentTimeMillis() - started < MAX_DURATION_MS) {
                val chunk = tones[index++ % 2]
                track.write(chunk, 0, chunk.size)
            }
        } catch (_: Exception) { }
        try { track.pause(); track.flush(); track.stop() } catch (_: Exception) { }
        track.release()
        finish()
    }

    private fun finish() {
        isRunning = false
        vibrate(false)
        AlarmVolume.release(context)
        Log.i(TAG, "Siren stopped")
        onStopped()
    }

    private fun vibrate(on: Boolean) {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        try {
            if (on) vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 300), 0)) else vibrator.cancel()
        } catch (_: Exception) { }
    }

    companion object {
        private const val TAG = "LynxSiren"
        const val MAX_DURATION_MS = 120_000L

        /** Échantillons d’un ton saturé (sinus écrêté) : plus strident qu’un sinus pur. */
        internal fun tone(rate: Int, frequency: Double, seconds: Double): ShortArray {
            val count = (rate * seconds).toInt()
            return ShortArray(count) { i ->
                val v = sin(2 * PI * frequency * i / rate) * 3.0
                (v.coerceIn(-1.0, 1.0) * 32000).toInt().toShort()
            }
        }
    }
}

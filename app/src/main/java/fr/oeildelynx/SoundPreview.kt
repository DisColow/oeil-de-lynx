package fr.oeildelynx

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import java.io.File

/**
 * Écoute d’un son sur CE téléphone, au volume multimédia, avant de le jouer sur la caméra ou de le
 * choisir pour l’alarme. Un nouveau son coupe le précédent.
 */
internal class SoundPreview(context: Context) {
    private val context = context.applicationContext
    private var player: MediaPlayer? = null
    private var track: AudioTrack? = null

    private val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    /** [id] : sirène, son intégré ou son personnalisé ([customFile], copie locale). Faux si rien à jouer. */
    fun play(id: String, customFile: File? = null): Boolean {
        stop()
        return try {
            when (id) {
                MonitorService.SIREN -> { playPcm(sirenSample(), 44_100); true }
                CustomAlarmSound.ID -> {
                    val file = customFile?.takeIf { it.length() > 0 } ?: return false
                    playMedia { it.setDataSource(file.absolutePath) }; true
                }
                else -> {
                    val effect = DeterrentSounds.Effect.byId(id) ?: return false
                    @android.annotation.SuppressLint("DiscouragedApi")
                    val res = effect.recording?.let { context.resources.getIdentifier(it, "raw", context.packageName) } ?: 0
                    if (res != 0) playMedia { p -> context.resources.openRawResourceFd(res).use { p.setDataSource(it.fileDescriptor, it.startOffset, it.length) } }
                    else playPcm(DeterrentSounds.render(effect), DeterrentSounds.RATE)
                    true
                }
            }
        } catch (_: Exception) { stop(); false }
    }

    fun stop() {
        player?.let { try { it.stop() } catch (_: Exception) { }; it.release() }; player = null
        track?.let { try { it.stop() } catch (_: Exception) { }; it.release() }; track = null
    }

    private fun playMedia(source: (MediaPlayer) -> Unit) {
        player = MediaPlayer().apply {
            setAudioAttributes(media)
            source(this)
            setOnCompletionListener { if (player === it) { it.release(); player = null } }
            prepare(); start()
        }
    }

    private fun playPcm(pcm: ShortArray, rate: Int) {
        track = AudioTrack.Builder().setAudioAttributes(media)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build().apply {
                write(pcm, 0, pcm.size); play()
            }
    }

    /** 3 secondes de la sirène (mêmes tons alternés que sur la caméra). */
    private fun sirenSample(): ShortArray {
        val tones = listOf(960.0, 1650.0).map { AlarmSiren.tone(44_100, it, 0.25) }
        val out = ShortArray(tones.sumOf { it.size } * 6)
        var at = 0
        repeat(12) { i -> val t = tones[i % 2]; t.copyInto(out, at); at += t.size }
        return out
    }

    companion object {
        /** Copie locale du son personnalisé envoyé à une caméra, pour pouvoir le réécouter ici. */
        fun customCopy(context: Context, cameraId: String) = File(context.filesDir, "custom_sound_${cameraId.hashCode().toUInt()}.audio")
    }
}

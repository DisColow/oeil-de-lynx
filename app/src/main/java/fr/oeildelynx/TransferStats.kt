package fr.oeildelynx

import java.util.Locale

/**
 * Vitesse et temps restant d’un téléchargement. La vitesse est mesurée sur une fenêtre glissante
 * (3 s par défaut) : assez réactive pour suivre la 4G, assez lissée pour ne pas sauter à chaque morceau.
 * Pur JVM pour être testé.
 */
internal class TransferStats(private val total: Long, startMs: Long, private val windowMs: Long = 3_000) {
    private val samples = ArrayDeque<Pair<Long, Long>>().apply { addLast(startMs to 0L) }
    var received = 0L
        private set

    fun update(nowMs: Long, bytes: Long) {
        received = bytes
        samples.addLast(nowMs to bytes)
        // Garde au moins deux points, et le plus ancien encore dans la fenêtre.
        while (samples.size > 2 && nowMs - samples[1].first >= windowMs) samples.removeFirst()
    }

    /** Octets par seconde, ou null tant que la mesure couvre moins d’une demi-seconde. */
    val bytesPerSecond: Double?
        get() {
            val (firstTime, firstBytes) = samples.first()
            val (lastTime, lastBytes) = samples.last()
            val elapsed = lastTime - firstTime
            if (elapsed < 500) return null
            return (lastBytes - firstBytes) * 1000.0 / elapsed
        }

    /** Secondes restantes estimées, ou null si la vitesse est encore inconnue ou nulle. */
    val remainingSeconds: Long?
        get() {
            val speed = bytesPerSecond ?: return null
            if (speed <= 0.0 || total <= 0) return null
            return kotlin.math.ceil((total - received).coerceAtLeast(0) / speed).toLong()
        }

    /** Fraction téléchargée entre 0 et 1, ou null si la taille totale est inconnue. */
    val fraction: Double? get() = if (total > 0) (received.toDouble() / total).coerceIn(0.0, 1.0) else null

    companion object {
        fun formatSize(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> String.format(Locale.FRANCE, "%.2f Go", bytes / 1024.0 / 1024.0 / 1024.0)
            bytes >= 1024 * 1024 -> String.format(Locale.FRANCE, "%.1f Mo", bytes / 1024.0 / 1024.0)
            bytes >= 1024 -> "${bytes / 1024} ko"
            else -> "$bytes o"
        }

        fun formatSpeed(bytesPerSecond: Double): String = when {
            bytesPerSecond >= 1024 * 1024 -> String.format(Locale.FRANCE, "%.1f Mo/s", bytesPerSecond / 1024 / 1024)
            else -> String.format(Locale.FRANCE, "%.0f ko/s", bytesPerSecond / 1024)
        }

        fun formatRemaining(seconds: Long): String = when {
            seconds < 1 -> "Presque terminé"
            seconds < 60 -> "Environ $seconds s restantes"
            seconds < 3600 -> "Environ ${seconds / 60} min ${(seconds % 60).toString().padStart(2, '0')} s restantes"
            else -> "Environ ${seconds / 3600} h ${((seconds % 3600) / 60).toString().padStart(2, '0')} min restantes"
        }
    }
}

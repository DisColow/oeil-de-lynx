package fr.oeildelynx

import kotlin.math.abs

/** Comparateur pur des échantillons de luminance, indépendant de CameraX pour rester testable. */
internal object MotionDetector {
    private const val PIXEL_DELTA = 32
    private const val AVERAGE_DELTA = 16.0
    private const val CHANGED_RATIO = 0.18

    /** Renvoie vrai uniquement si les deux seuils de changement sont franchis. */
    fun hasMotion(current: ByteArray, previous: ByteArray): Boolean {
        val compared = minOf(current.size, previous.size)
        if (compared == 0) return false

        var changed = 0
        var difference = 0L
        for (index in 0 until compared) {
            val delta = abs((current[index].toInt() and 0xff) - (previous[index].toInt() and 0xff))
            difference += delta
            if (delta > PIXEL_DELTA) changed++
        }
        return difference.toDouble() / compared > AVERAGE_DELTA &&
            changed.toDouble() / compared > CHANGED_RATIO
    }
}

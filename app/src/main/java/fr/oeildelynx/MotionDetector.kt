package fr.oeildelynx

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Comparateur pur des échantillons de luminance, indépendant de CameraX pour rester testable.
 *
 * Un mouvement = une zone de l’image qui change, même petite (personne au fond de la pièce).
 * Les variations globales de luminosité (exposition automatique, nuage) sont compensées en
 * retirant l’écart médian avant de compter les points modifiés. Un saut de luminosité très
 * fort (lumière allumée ou éteinte) est en revanche signalé comme une activité.
 */
internal object MotionDetector {
    /** Part minimale des points de la grille qui doivent changer pour parler de mouvement. */
    enum class Sensitivity(val label: String, val changedRatio: Double) {
        LOW("Faible", 0.05), NORMAL("Normale", 0.02), HIGH("Élevée", 0.008)
    }

    private const val PIXEL_DELTA = 25
    private const val LIGHTING_JUMP = 45
    private const val MIN_CHANGED_SAMPLES = 3

    fun hasMotion(current: ByteArray, previous: ByteArray, sensitivity: Sensitivity = Sensitivity.NORMAL): Boolean {
        val compared = minOf(current.size, previous.size)
        if (compared == 0) return false

        // Médiane des écarts signés via un histogramme (−255..255) : robuste à l’objet qui bouge.
        val histogram = IntArray(511)
        for (index in 0 until compared) histogram[delta(current, previous, index) + 255]++
        var median = 0
        var seen = 0
        for (bin in histogram.indices) {
            seen += histogram[bin]
            if (seen * 2 >= compared) { median = bin - 255; break }
        }
        if (abs(median) > LIGHTING_JUMP) return true

        var changed = 0
        for (index in 0 until compared) if (abs(delta(current, previous, index) - median) > PIXEL_DELTA) changed++
        val required = maxOf(MIN_CHANGED_SAMPLES, ceil(compared * sensitivity.changedRatio).toInt())
        return changed >= required
    }

    private fun delta(current: ByteArray, previous: ByteArray, index: Int) =
        (current[index].toInt() and 0xff) - (previous[index].toInt() and 0xff)
}

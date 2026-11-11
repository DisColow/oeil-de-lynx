package fr.oeildelynx

import android.content.Context
import androidx.camera.core.Camera
import java.util.Locale

/**
 * Correction d’exposition (EV) choisie par l’utilisateur, mémorisée et appliquée à chaque liaison
 * CameraX : aperçu avant surveillance, service de surveillance, et commande depuis un spectateur.
 * La plage dépend de la caméra ; elle est mémorisée pour que l’interface la connaisse sans caméra liée.
 */
internal object Exposure {
    private const val PREF_INDEX = "exposure_index"
    private const val PREF_MIN = "exposure_min"
    private const val PREF_MAX = "exposure_max"
    private const val PREF_STEP = "exposure_step"

    class Range(val min: Int, val max: Int, val step: Float, val index: Int) {
        val supported get() = max > min
    }

    private fun prefs(context: Context) = context.getSharedPreferences("lynx", Context.MODE_PRIVATE)

    fun range(context: Context): Range = prefs(context).let {
        Range(it.getInt(PREF_MIN, 0), it.getInt(PREF_MAX, 0), it.getFloat(PREF_STEP, 0f), it.getInt(PREF_INDEX, 0))
    }

    /** Mémorise la plage de cette caméra et lui applique la correction choisie (bornée). */
    fun apply(context: Context, camera: Camera) {
        val state = camera.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) {
            prefs(context).edit().putInt(PREF_MIN, 0).putInt(PREF_MAX, 0).apply(); return
        }
        val bounds = state.exposureCompensationRange
        val index = prefs(context).getInt(PREF_INDEX, 0).coerceIn(bounds.lower, bounds.upper)
        prefs(context).edit().putInt(PREF_MIN, bounds.lower).putInt(PREF_MAX, bounds.upper)
            .putFloat(PREF_STEP, state.exposureCompensationStep.toFloat()).apply()
        camera.cameraControl.setExposureCompensationIndex(index)
    }

    fun setIndex(context: Context, index: Int) {
        val range = range(context)
        val bounded = if (range.supported) index.coerceIn(range.min, range.max) else 0
        prefs(context).edit().putInt(PREF_INDEX, bounded).apply()
    }

    /** Champs envoyés aux spectateurs dans l’état du direct. */
    fun fields(context: Context): Map<String, String> = range(context).let {
        mapOf("exp" to it.index.toString(), "expmin" to it.min.toString(), "expmax" to it.max.toString(), "expstep" to it.step.toString())
    }

    fun label(index: Int, step: Float): String {
        val ev = index * step
        return if (index == 0) "0 EV (auto)" else String.format(Locale.FRANCE, "%+.1f EV", ev)
    }
}

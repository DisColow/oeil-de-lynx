package fr.oeildelynx

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.Camera
import androidx.core.content.ContextCompat
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
    private const val TAG = "LynxExposure"
    private const val PREF_REPORT = "exposure_report"
    private const val MAX_ATTEMPTS = 20
    private const val RETRY_DELAY_MS = 300L
    private val handler = Handler(Looper.getMainLooper())

    class Range(val min: Int, val max: Int, val step: Float, val index: Int) {
        val supported get() = max > min
    }

    private fun prefs(context: Context) = context.getSharedPreferences("lynx", Context.MODE_PRIVATE)

    fun range(context: Context): Range = prefs(context).let {
        Range(it.getInt(PREF_MIN, 0), it.getInt(PREF_MAX, 0), it.getFloat(PREF_STEP, 0f), it.getInt(PREF_INDEX, 0))
    }

    /**
     * Mémorise la plage de cette caméra et lui applique la correction choisie (bornée).
     * [onApplied] reçoit l’index réellement appliqué par la caméra.
     */
    fun apply(context: Context, camera: Camera, onApplied: (Int) -> Unit = {}) {
        val state = camera.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) {
            prefs(context).edit().putInt(PREF_MIN, 0).putInt(PREF_MAX, 0)
                .putString(PREF_REPORT, "La caméra de ce téléphone n’accepte pas de correction d’exposition (non prise en charge par Android/CameraX).").apply()
            return
        }
        val bounds = state.exposureCompensationRange
        val index = prefs(context).getInt(PREF_INDEX, 0).coerceIn(bounds.lower, bounds.upper)
        prefs(context).edit().putInt(PREF_MIN, bounds.lower).putInt(PREF_MAX, bounds.upper)
            .putFloat(PREF_STEP, state.exposureCompensationStep.toFloat()).apply()
        push(context.applicationContext, camera, index, MAX_ATTEMPTS, onApplied)
    }

    /**
     * CameraX refuse le réglage tant que la caméra n’est pas complètement ouverte (juste après la
     * liaison, au démarrage de la surveillance ou au changement de caméra). On réessaie donc jusqu’à
     * ce qu’elle l’accepte, sauf si l’utilisateur a choisi entre-temps une autre valeur.
     */
    private fun push(context: Context, camera: Camera, index: Int, attemptsLeft: Int, onApplied: (Int) -> Unit) {
        val future = camera.cameraControl.setExposureCompensationIndex(index)
        future.addListener({
            val applied = try { future.get(); true } catch (error: Exception) {
                Log.w(TAG, "Exposure $index not applied yet (${error.javaClass.simpleName}), ${attemptsLeft} attempt(s) left")
                false
            }
            if (applied) {
                val actual = camera.cameraInfo.exposureState.exposureCompensationIndex
                val step = camera.cameraInfo.exposureState.exposureCompensationStep.toFloat()
                prefs(context).edit().putString(PREF_REPORT, "Caméra : ${label(actual, step)} appliqué.").apply()
                Log.i(TAG, "Exposure index $actual applied")
                onApplied(actual)
            } else if (attemptsLeft > 0 && prefs(context).getInt(PREF_INDEX, 0) == index) {
                handler.postDelayed({ push(context, camera, index, attemptsLeft - 1, onApplied) }, RETRY_DELAY_MS)
            } else if (attemptsLeft == 0) {
                prefs(context).edit().putString(PREF_REPORT, "Caméra : réglage refusé après ${MAX_ATTEMPTS} essais.").apply()
                onApplied(camera.cameraInfo.exposureState.exposureCompensationIndex)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun setIndex(context: Context, index: Int) {
        val range = range(context)
        val bounded = if (range.supported) index.coerceIn(range.min, range.max) else 0
        prefs(context).edit().putInt(PREF_INDEX, bounded).apply()
    }

    /** Ce que la caméra a réellement fait du dernier réglage (affiché pour comprendre un échec). */
    fun report(context: Context): String = prefs(context).getString(PREF_REPORT, null) ?: "Aucun réglage envoyé à la caméra pour l’instant."

    /** Champs envoyés aux spectateurs dans l’état du direct. */
    fun fields(context: Context): Map<String, String> = range(context).let {
        mapOf("exp" to it.index.toString(), "expmin" to it.min.toString(), "expmax" to it.max.toString(),
            "expstep" to it.step.toString(), "expreport" to report(context))
    }

    fun label(index: Int, step: Float): String {
        val ev = index * step
        return if (index == 0) "0 EV (auto)" else String.format(Locale.FRANCE, "%+.1f EV", ev)
    }
}

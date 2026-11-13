package fr.oeildelynx

/**
 * Convertit l’angle physique du téléphone (capteur, 0–359°, sens horaire) en rotation d’écran
 * Android (Surface.ROTATION_0/90/180/270 = 0/1/2/3), comme le ferait l’écran s’il pivotait.
 *
 * Le service de surveillance n’a pas d’écran : sans ce calcul, CameraX suppose le portrait et
 * un téléphone posé en paysage produit une image couchée (direct et clips).
 * Une marge de 20° autour des diagonales évite d’osciller entre deux positions.
 */
internal object DeviceOrientation {
    const val UNKNOWN = -1
    private const val HYSTERESIS = 20

    /** Rotation à appliquer, ou [current] si l’angle est ambigu (près d’une diagonale, téléphone à plat). */
    fun surfaceRotation(degrees: Int, current: Int): Int {
        if (degrees == UNKNOWN || degrees < 0) return current
        val angle = degrees % 360
        // Angle physique de la rotation actuelle (Surface.ROTATION_90 = téléphone tourné de 270° horaire).
        if (current in 0..3) {
            val currentAngle = physicalAngle(current)
            val distance = minOf((angle - currentAngle + 360) % 360, (currentAngle - angle + 360) % 360)
            if (distance <= 45 + HYSTERESIS) return current
        }
        return when (angle) {
            in 315..359, in 0 until 45 -> 0
            in 45 until 135 -> 3
            in 135 until 225 -> 2
            else -> 1
        }
    }

    private fun physicalAngle(rotation: Int) = when (rotation) { 0 -> 0; 1 -> 270; 2 -> 180; else -> 90 }
}

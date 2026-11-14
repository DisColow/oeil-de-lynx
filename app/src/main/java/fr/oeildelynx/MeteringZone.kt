package fr.oeildelynx

/**
 * Mesure de la lumière sur une zone touchée dans le direct.
 * Le spectateur voit l’image tournée (rotation de la caméra + bouton Pivoter) ; la caméra attend
 * des coordonnées dans son image d’analyse non tournée. Pur JVM pour être testé.
 */
internal object MeteringZone {
    /**
     * Convertit un point touché (u, v ∈ [0, 1] dans l’image affichée) en point de l’image source,
     * sachant que l’image affichée est la source tournée de [rotation]° dans le sens horaire.
     */
    fun toSource(u: Float, v: Float, rotation: Int): Pair<Float, Float> = when (((rotation % 360) + 360) % 360) {
        90 -> v to 1f - u
        180 -> 1f - u to 1f - v
        270 -> 1f - v to u
        else -> u to v
    }
}

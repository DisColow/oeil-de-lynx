package fr.oeildelynx

import fr.oeildelynx.MotionDetector.Sensitivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionDetectorTest {
    /** Grille de 40 × 30 points, comme celle échantillonnée par le service. */
    private fun scene(value: Int = 80) = ByteArray(1200) { (value + (it % 7) * 3).toByte() }

    private fun withObject(base: ByteArray, from: Int, count: Int, shift: Int = 70) =
        base.copyOf().also { for (index in from until from + count) it[index] = ((it[index].toInt() and 0xff) + shift).coerceAtMost(255).toByte() }

    @Test fun identicalFramesAreNotMotion() {
        val frame = scene()
        assertFalse(MotionDetector.hasMotion(frame, frame.copyOf()))
    }

    @Test fun exposureDriftIsCompensated() {
        // Toute l’image s’éclaircit de 20 niveaux : réglage automatique, pas un intrus.
        assertFalse(MotionDetector.hasMotion(scene(100), scene(80)))
    }

    @Test fun sensorNoiseIsFiltered() {
        val previous = scene()
        val current = previous.copyOf().also { for (index in 0 until 1200 step 10) it[index] = (it[index] + 12).toByte() }
        assertFalse(MotionDetector.hasMotion(current, previous, Sensitivity.HIGH))
    }

    @Test fun smallPersonInFrameIsDetected() {
        // 40 points ≈ 3 % de l’image : quelqu’un au fond de la pièce. L’ancien seuil (18 %) l’ignorait.
        val previous = scene()
        assertTrue(MotionDetector.hasMotion(withObject(previous, 400, 40), previous))
    }

    @Test fun sensitivityChangesTheRequiredArea() {
        val previous = scene()
        val current = withObject(previous, 0, 15) // ≈ 1,25 % de l’image
        assertTrue(MotionDetector.hasMotion(current, previous, Sensitivity.HIGH))
        assertFalse(MotionDetector.hasMotion(current, previous, Sensitivity.NORMAL))
        assertFalse(MotionDetector.hasMotion(withObject(previous, 0, 40), previous, Sensitivity.LOW))
    }

    @Test fun movingObjectIsDetectedDespiteExposureChange() {
        val previous = scene(80)
        assertTrue(MotionDetector.hasMotion(withObject(scene(95), 600, 60), previous))
    }

    @Test fun lightSwitchedOnIsActivity() {
        assertTrue(MotionDetector.hasMotion(scene(160), scene(40)))
    }

    @Test fun emptyAndMismatchedFramesAreSafe() {
        assertFalse(MotionDetector.hasMotion(byteArrayOf(), byteArrayOf(1)))
        assertTrue(MotionDetector.hasMotion(ByteArray(80) { 120 }, ByteArray(100) { 40 }))
    }
}

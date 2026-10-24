package fr.oeildelynx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionDetectorTest {
    @Test fun identicalFramesAreNotMotion() {
        val frame = ByteArray(100) { 80 }
        assertFalse(MotionDetector.hasMotion(frame, frame.copyOf()))
    }

    @Test fun smallLightingChangeIsFiltered() {
        assertFalse(MotionDetector.hasMotion(ByteArray(100) { 95 }, ByteArray(100) { 80 }))
    }

    @Test fun changeBelowRequiredPixelRatioIsFiltered() {
        val previous = ByteArray(100) { 40 }
        val current = previous.copyOf().also { for (index in 0 until 18) it[index] = 120 }
        assertFalse(MotionDetector.hasMotion(current, previous))
    }

    @Test fun clearSceneChangeIsDetected() {
        assertTrue(MotionDetector.hasMotion(ByteArray(100) { 120 }, ByteArray(100) { 40 }))
    }

    @Test fun emptyAndMismatchedFramesAreSafe() {
        assertFalse(MotionDetector.hasMotion(byteArrayOf(), byteArrayOf(1)))
        assertTrue(MotionDetector.hasMotion(ByteArray(80) { 120 }, ByteArray(100) { 40 }))
    }
}

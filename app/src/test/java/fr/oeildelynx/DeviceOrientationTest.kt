package fr.oeildelynx

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceOrientationTest {
    @Test fun clearPositionsMapToSurfaceRotations() {
        assertEquals(0, DeviceOrientation.surfaceRotation(5, 0))
        // Téléphone tourné vers la gauche (haut à gauche) : angle capteur 270° → ROTATION_90.
        assertEquals(1, DeviceOrientation.surfaceRotation(270, 0))
        assertEquals(3, DeviceOrientation.surfaceRotation(90, 0))
        assertEquals(2, DeviceOrientation.surfaceRotation(180, 0))
    }

    @Test fun diagonalKeepsCurrentRotation() {
        assertEquals(0, DeviceOrientation.surfaceRotation(60, 0))
        assertEquals(3, DeviceOrientation.surfaceRotation(30, 3))
        assertEquals(3, DeviceOrientation.surfaceRotation(80, 0).let { DeviceOrientation.surfaceRotation(70, it) })
    }

    @Test fun flatPhoneKeepsCurrentRotation() {
        assertEquals(1, DeviceOrientation.surfaceRotation(DeviceOrientation.UNKNOWN, 1))
    }
}

class MuLawTest {
    @Test fun roundTripStaysClose() {
        for (sample in listOf(0, 1, -1, 100, -100, 1000, -1000, 8000, -8000, 32000, -32000, 32767, -32768)) {
            val decoded = MuLaw.decode(MuLaw.encode(sample)).toInt()
            // Erreur de quantification µ-law : relative (~3 %) pour les forts niveaux, quelques unités près de zéro.
            val tolerance = maxOf(8, kotlin.math.abs(sample) / 16)
            org.junit.Assert.assertTrue("$sample → $decoded", kotlin.math.abs(decoded - sample.coerceIn(-32635, 32635)) <= tolerance)
        }
    }

    @Test fun silenceEncodesToStandardByte() {
        assertEquals(0xff.toByte(), MuLaw.encode(0))
        assertEquals(0, MuLaw.decode(0xff.toByte()).toInt())
    }
}

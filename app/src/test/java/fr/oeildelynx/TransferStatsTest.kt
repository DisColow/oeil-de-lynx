package fr.oeildelynx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferStatsTest {
    @Test fun speedAndRemainingTime() {
        val stats = TransferStats(total = 10_000_000, startMs = 0)
        assertNull(stats.bytesPerSecond) // pas encore de mesure
        stats.update(1_000, 1_000_000)
        stats.update(2_000, 2_000_000)
        assertEquals(1_000_000.0, stats.bytesPerSecond!!, 1.0)
        assertEquals(8L, stats.remainingSeconds)
        assertEquals(0.2, stats.fraction!!, 1e-9)
    }

    @Test fun speedFollowsRecentWindow() {
        val stats = TransferStats(total = 100_000_000, startMs = 0, windowMs = 3_000)
        // Démarrage rapide (2 Mo/s) puis ralentissement à 0,5 Mo/s : la vitesse affichée suit le ralentissement.
        var bytes = 0L
        for (t in 1..5) { bytes += 2_000_000; stats.update(t * 1000L, bytes) }
        for (t in 6..12) { bytes += 500_000; stats.update(t * 1000L, bytes) }
        assertEquals(500_000.0, stats.bytesPerSecond!!, 1.0)
    }

    @Test fun shortMeasureIsIgnored() {
        val stats = TransferStats(total = 1_000, startMs = 0)
        stats.update(200, 500)
        assertNull(stats.bytesPerSecond)
        assertNull(stats.remainingSeconds)
    }

    @Test fun formatting() {
        assertEquals("4,2 Mo", TransferStats.formatSize(4_404_019))
        assertEquals("512 ko", TransferStats.formatSize(524_288))
        assertEquals("1,5 Mo/s", TransferStats.formatSpeed(1_572_864.0))
        assertEquals("300 ko/s", TransferStats.formatSpeed(307_200.0))
        assertEquals("Environ 42 s restantes", TransferStats.formatRemaining(42))
        assertEquals("Environ 2 min 05 s restantes", TransferStats.formatRemaining(125))
        assertEquals("Presque terminé", TransferStats.formatRemaining(0))
    }
}

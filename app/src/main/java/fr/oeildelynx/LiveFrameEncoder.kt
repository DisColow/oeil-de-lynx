package fr.oeildelynx

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * Convertit les images YUV d’ImageAnalysis en JPEG pour le direct. On réutilise le flux d’analyse
 * déjà ouvert pour la détection de mouvement : pas de quatrième flux caméra, que beaucoup
 * de téléphones refuseraient à côté de l’aperçu et de l’enregistrement.
 */
internal class LiveFrameEncoder {
    private var nv21 = ByteArray(0)
    private val output = ByteArrayOutputStream(64 * 1024)

    fun encode(image: ImageProxy, quality: Int): ByteArray? {
        val width = image.width; val height = image.height
        if (image.format != ImageFormat.YUV_420_888 || image.planes.size < 3 || width <= 0 || height <= 0) return null
        val evenWidth = width and 1.inv(); val evenHeight = height and 1.inv()
        val size = evenWidth * evenHeight * 3 / 2
        if (nv21.size != size) nv21 = ByteArray(size)

        // Plan Y : copie ligne par ligne en tenant compte du rowStride et du pixelStride.
        val yPlane = image.planes[0]
        val y = yPlane.buffer.duplicate().apply { rewind() }
        var out = 0
        for (row in 0 until evenHeight) {
            val rowStart = row * yPlane.rowStride
            if (yPlane.pixelStride == 1) {
                if (rowStart + evenWidth > y.limit()) return null
                y.position(rowStart); y.get(nv21, out, evenWidth); out += evenWidth
            } else {
                for (col in 0 until evenWidth) nv21[out++] = y.get(rowStart + col * yPlane.pixelStride)
            }
        }

        // Plans U et V : NV21 attend V puis U entrelacés, à demi-résolution.
        val uPlane = image.planes[1]; val vPlane = image.planes[2]
        val u = uPlane.buffer.duplicate(); val v = vPlane.buffer.duplicate()
        for (row in 0 until evenHeight / 2) {
            for (col in 0 until evenWidth / 2) {
                val vIndex = row * vPlane.rowStride + col * vPlane.pixelStride
                val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
                if (vIndex >= v.limit() || uIndex >= u.limit()) return null
                nv21[out++] = v.get(vIndex)
                nv21[out++] = u.get(uIndex)
            }
        }

        output.reset()
        YuvImage(nv21, ImageFormat.NV21, evenWidth, evenHeight, null).compressToJpeg(Rect(0, 0, evenWidth, evenHeight), quality, output)
        return output.toByteArray()
    }
}

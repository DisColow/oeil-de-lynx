package fr.oeildelynx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Lecteur du QR code d’appairage, sur le téléphone spectateur. Décodage local avec ZXing
 * (aucun service Google ni réseau requis). Renvoie le texte du code via [EXTRA_CODE].
 */
class ScanActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var executor: ExecutorService
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
    }
    @Volatile private var done = false
    @Volatile private var warned = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else { Toast.makeText(this, "La caméra est nécessaire pour scanner le QR code.", Toast.LENGTH_LONG).show(); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        executor = Executors.newSingleThreadExecutor()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        root.addView(TextView(this).apply {
            text = "Vise le QR code affiché sur le téléphone caméra\n(Réglages › Appairer un téléphone spectateur)"
            gravity = Gravity.CENTER; textSize = 15f; setTextColor(Color.WHITE)
            val pad = LynxUi.dp(this@ScanActivity, 16); setPadding(pad, pad, pad, pad)
            background = LynxUi.rounded(this@ScanActivity, 0xcc101713.toInt())
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            val margin = LynxUi.dp(this@ScanActivity, 18); setMargins(margin, margin, margin, margin * 2)
        })
        setContentView(root)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { image -> decode(image) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) {
                Toast.makeText(this, "Caméra indisponible. Si la surveillance tourne sur ce téléphone, arrête-la d’abord.", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun decode(image: ImageProxy) {
        try {
            if (done) return
            val plane = image.planes[0]
            val buffer = plane.buffer.duplicate().apply { rewind() }
            // Le plan Y suffit à ZXing ; rowStride sert de largeur de données.
            val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val width = image.width; val height = image.height
            val rowStride = plane.rowStride
            if (plane.pixelStride != 1 || data.size < rowStride * (height - 1) + width) return
            val source = PlanarYUVLuminanceSource(data, rowStride, height, 0, 0, width, height, false)
            val text = try { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text } catch (_: NotFoundException) { null } finally { reader.reset() }
            if (text != null && PairingCode.parse(text) != null && !done) {
                done = true
                runOnUiThread { setResult(RESULT_OK, Intent().putExtra(EXTRA_CODE, text)); finish() }
            } else if (text != null && !warned) {
                warned = true
                runOnUiThread { Toast.makeText(this, "Ce QR code ne vient pas d’Œil de Lynx.", Toast.LENGTH_SHORT).show() }
            }
        } catch (_: Exception) {
        } finally { image.close() }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    companion object { const val EXTRA_CODE = "pairing_code" }
}

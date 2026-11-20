package com.enzimos.remote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.enzimos.remote.databinding.ActivityPairingBinding
import com.enzimos.remote.pairing.QrCodeData
import com.enzimos.remote.pairing.QrScannerAnalyzer
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.Executors

/**
 * Scans a pairing QR code shown by `enzremote-signaling pair`.
 *
 * Returns RESULT_OK with [EXTRA_QR] holding the raw, validated payload. The
 * caller performs the pairing handshake (see `PairingFlow`).
 */
class PairingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PairingActivity"
        /** Result extra: raw QR payload (JSON). */
        const val EXTRA_QR = "qr_payload"
    }

    private lateinit var binding: ActivityPairingBinding
    private var analyzer: QrScannerAnalyzer? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var done = false
    private var lastInvalid = 0L

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startCamera() else {
            binding.tvPairingStatus.setText(R.string.pairing_error_camera)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityPairingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.header) { view, insets ->
            view.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top)
            insets
        }
        binding.toolbar.setNavigationOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            binding.tvPairingStatus.text = "No camera available for QR scanning"
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analyzer?.close()
        analysisExecutor.shutdown()
    }

    private fun onDetected(raw: String) {
        if (done) return
        val data = QrCodeData.parse(raw)
        if (data == null) {
            val now = System.currentTimeMillis()
            if (now - lastInvalid > 3_000) {
                lastInvalid = now
                Snackbar.make(binding.root, R.string.scan_invalid, Snackbar.LENGTH_SHORT).show()
            }
            return
        }
        done = true
        binding.root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        setResult(RESULT_OK, Intent().putExtra(EXTRA_QR, raw))
        finish()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = binding.previewView.surfaceProvider }
            val scannerAnalyzer = QrScannerAnalyzer(isActive = { !done }) { raw -> runOnUiThread { onDetected(raw) } }
            analyzer = scannerAnalyzer
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, scannerAnalyzer) }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
                Log.i(TAG, "Camera started for QR scanning")
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                binding.tvPairingStatus.text = "Couldn't start the camera: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }
}

package com.enzimos.remote.pairing

import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/** Processes one frame at a time and delivers detections on the main thread. */
class QrScannerAnalyzer(
    /** Whether frames should currently be decoded. */
    private val isActive: () -> Boolean = { true },
    /** Called on the main thread with each decoded QR payload. */
    private val onDetected: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "QrScannerAnalyzer"
    }

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    /** Prevents overlapping ML Kit calls. */
    @Volatile
    private var isProcessing = false

    /** Whether the analyzer has been closed. */
    @Volatile
    private var isClosed = false

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (isClosed) {
            imageProxy.close()
            return
        }

        // Skip if already processing a frame or scanning is paused.
        if (isProcessing || !isActive()) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        isProcessing = true
        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees,
        )

        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val rawValue = barcode.rawValue
                    if (rawValue != null && rawValue.isNotEmpty()) {
                        Log.d(TAG, "Barcode detected (${rawValue.length} chars)")
                        onDetected(rawValue)
                        // Only process the first valid barcode per frame.
                        break
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Barcode scanning failed", e)
            }
            .addOnCompleteListener {
                isProcessing = false
                imageProxy.close()
            }
    }

    /**
     * Release ML Kit resources.
     *
     * Call this when the camera lifecycle ends or the fragment is destroyed.
     */
    fun close() {
        isClosed = true
        scanner.close()
    }
}

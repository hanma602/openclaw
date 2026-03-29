package ai.openclaw.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import ai.openclaw.app.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * QR Code Scanner Activity using ML Kit Standalone (no Google Play Services required)
 * 
 * This activity provides QR code scanning functionality for devices without GMS.
 * It uses CameraX for camera preview and ML Kit Barcode Scanning for decoding.
 */
class QrScannerActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "QrScannerActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
    
    private lateinit var previewView: PreviewView
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private lateinit var cameraExecutor: ExecutorService
    
    private val barcodeScanner by lazy { BarcodeScanning.getClient() }
    
    private var isScanning = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.i(TAG, "QrScannerActivity created")
        
        // Set up UI
        previewView = PreviewView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        
        setContentView(previewView)
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Check permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }
    }
    
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun startCamera() {
        Log.d(TAG, "Starting camera")
        
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Camera initialization failed: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this, "Camera initialization failed", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return
        
        Log.d(TAG, "Binding camera use cases")
        
        // Preview use case
        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
        
        // Image analysis use case for barcode scanning
        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImageProxy(imageProxy)
                }
            }
        
        // Unbind all use cases before rebinding
        cameraProvider.unbindAll()
        
        try {
            // Bind use cases to camera
            cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalysis
            )
            Log.d(TAG, "Camera use cases bound successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Binding camera use cases failed: ${e.message}", e)
        }
    }
    
    private fun processImageProxy(imageProxy: ImageProxy) {
        if (isScanning) return
        
        val mediaImage = imageProxy.image ?: return
        
        // Convert ImageProxy to Bitmap using a simpler approach
        val bitmap = imageProxyToBitmap(imageProxy)
        
        // Rotate bitmap to match display orientation
        val rotation = imageProxy.imageInfo.rotationDegrees
        val rotatedBitmap = rotateBitmap(bitmap, rotation.toFloat())
        
        // Create InputImage for ML Kit
        val image = InputImage.fromBitmap(rotatedBitmap, 0)
        
        isScanning = true
        
        // Process barcode
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                isScanning = false
                for (barcode in barcodes) {
                    val rawValue = barcode.rawValue?.trim().orEmpty()
                    if (rawValue.isNotEmpty()) {
                        Log.i(TAG, "Barcode detected: ${rawValue.length} chars")
                        handleScannedCode(rawValue)
                        return@addOnSuccessListener
                    }
                }
            }
            .addOnFailureListener { e ->
                isScanning = false
                Log.e(TAG, "Barcode scanning failed: ${e.message}", e)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
    
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        
        // Convert byte array to int array for ARGB_8888 bitmap
        val intArray = IntArray(bytes.size / 4)
        for (i in 0 until intArray.size) {
            intArray[i] = ((bytes[i * 4].toInt() and 0xFF) shl 24) or
                          ((bytes[i * 4 + 1].toInt() and 0xFF) shl 16) or
                          ((bytes[i * 4 + 2].toInt() and 0xFF) shl 8) or
                          (bytes[i * 4 + 3].toInt() and 0xFF)
        }
        
        return Bitmap.createBitmap(
            intArray,
            imageProxy.width,
            imageProxy.height,
            Bitmap.Config.ARGB_8888
        )
    }
    
    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        
        val matrix = Matrix()
        matrix.postRotate(degrees)
        
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }
    
    private fun handleScannedCode(code: String) {
        Log.i(TAG, "Handling scanned code: ${code.take(20)}...")
        
        runOnUiThread {
            Toast.makeText(this, "QR code scanned successfully", Toast.LENGTH_SHORT).show()
            
            // Return the scanned code to the caller
            val resultIntent = Intent().apply {
                putExtra("setup_code", code)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Camera permission is required for QR scanning",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        barcodeScanner.close()
    }
    
    override fun onPause() {
        super.onPause()
        cameraProvider?.unbindAll()
    }
}

package com.example.qrscanner

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.io.FileOutputStream
import android.os.Handler
import android.os.Looper

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var resultText: TextView
    private lateinit var manualInput: EditText
    private lateinit var addManualButton: Button
    private val collectedCodes = mutableSetOf<String>() // store unique values
    private val recentlySavedCodes = mutableSetOf<String>()
    private val cooldownHandler = Handler(Looper.getMainLooper())
    private  val COOLDOWN_MS = 3000L // ignore saved codes for 3 seconds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        resultText = findViewById(R.id.resultText)

        manualInput = findViewById(R.id.manualInput)
        addManualButton = findViewById(R.id.addManualButton)

        addManualButton.setOnClickListener {
            val input = manualInput.text.toString().trim()
            if (input.isNotEmpty() &&
                !collectedCodes.contains(input) &&
                !recentlySavedCodes.contains(input)
            ) {
                collectedCodes.add(input)
                manualInput.text.clear()
                resultText.text = collectedCodes.joinToString(", ")

                if (collectedCodes.size >= 4) {
                    val row = collectedCodes.take(4)
                    saveQRCodesToCsv(row)
                    Toast.makeText(this, "Saved 4 serial numbers to CSV", Toast.LENGTH_SHORT).show()

                    recentlySavedCodes.addAll(row)
                    collectedCodes.clear()

                    cooldownHandler.postDelayed({
                        recentlySavedCodes.removeAll(row)
                    }, COOLDOWN_MS)
                }
            } else {
                Toast.makeText(this, "Invalid or duplicate entry", Toast.LENGTH_SHORT).show()
            }
        }

        checkCameraPermission()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = androidx.camera.core.Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analyzer = ImageAnalysis.Builder().build().also {
                it.setAnalyzer(ContextCompat.getMainExecutor(this), QRAnalyzer { results ->
                    // extract raw string from QRResult
                    val rawStrings = results.map { it.value }
                    handleDetectedCodes(rawStrings)
                })
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, analyzer)
            } catch (exc: Exception) {
                exc.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleDetectedCodes(results: List<String>) {
        results.forEach { code ->
            val serial = code.split(";").firstOrNull()
            if (serial != null &&
                !collectedCodes.contains(serial) &&
                !recentlySavedCodes.contains(serial)
            ) {
                collectedCodes.add(serial)
            }
        }

        // Update UI
        resultText.text = collectedCodes.joinToString(", ")

        // When we have 4 codes, save them
        if (collectedCodes.size >= 4) {
            val row = collectedCodes.take(4)
            saveQRCodesToCsv(row)
            Toast.makeText(this, "Saved 4 serial numbers to CSV", Toast.LENGTH_SHORT).show()

            // Move these codes to cooldown set
            recentlySavedCodes.addAll(row)

            // Clear collected codes for next batch
            collectedCodes.clear()

            // Automatically clear cooldown after COOLDOWN_MS
            cooldownHandler.postDelayed({
                recentlySavedCodes.removeAll(row)
            }, COOLDOWN_MS)
        }
    }




    private fun saveQRCodesToCsv(values: List<String>) {
        val csvLine = values.joinToString(",")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+ → Use MediaStore Downloads
            val resolver = contentResolver

            // Check if the file already exists
            val selection = "${MediaStore.Downloads.DISPLAY_NAME}=?"
            val cursor = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                selection,
                arrayOf("qrcodes.csv"),
                null
            )

            val uri = if (cursor != null && cursor.moveToFirst()) {
                // File exists, get its Uri
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                cursor.close()
                MediaStore.Downloads.EXTERNAL_CONTENT_URI.buildUpon().appendPath(id.toString()).build()
            } else {
                cursor?.close()
                // File doesn't exist, create it
                val valuesContent = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "qrcodes.csv")
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                }
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valuesContent)
            }

            uri?.let {
                // Open output stream in append mode
                resolver.openOutputStream(it, "wa")?.use { stream ->
                    stream.write("$csvLine\n".toByteArray())
                }
            }

        } else {
            // API <29 → Legacy storage
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val file = File(downloadsDir, "qrcodes.csv")
            FileOutputStream(file, true).use { fos ->  // 'true' → append mode
                fos.write("$csvLine\n".toByteArray())
            }
        }
    }


    // ✅ Permissions with Activity Result API
    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) startCamera()
            else Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
        }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
}

// Data class for storing QR value and position
data class QRResult(val value: String, val x: Float)

// Analyzer using ML Kit
class QRAnalyzer(private val onQRCodesDetected: (List<QRResult>) -> Unit) : ImageAnalysis.Analyzer {

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val mediaImage = image.image
        if (mediaImage != null) {
            val inputImage = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
            val scanner = BarcodeScanning.getClient()
            scanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    val results = barcodes.mapNotNull { barcode ->
                        val raw = barcode.rawValue
                        val box = barcode.boundingBox
                        if (raw != null && box != null) QRResult(raw, box.left.toFloat()) else null
                    }
                    if (results.isNotEmpty()) onQRCodesDetected(results)
                }
                .addOnCompleteListener { image.close() }
        } else {
            image.close()
        }
    }
}

package com.example.smartampulereader_app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.tasks.Tasks
import com.google.gson.GsonBuilder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var resultText: TextView
    private lateinit var imageCapture: ImageCapture
    private lateinit var thumbnailContainer: LinearLayout
    private lateinit var captureButton: Button
    private lateinit var extractButton: Button
    private lateinit var galleryButton: Button
    private lateinit var testButton: Button

    private val capturedImages = mutableListOf<File>()
    private val extractor = AmpouleExtractor()
    
    // Reverting back to Google ML Kit recognizer
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        }
    }

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            copyUriToFile(uri)?.let { file ->
                addImageToList(file)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        previewView = PreviewView(this)
        resultText = TextView(this)
        thumbnailContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val scrollView = HorizontalScrollView(this).apply {
            addView(thumbnailContainer)
        }

        captureButton = Button(this).apply {
            text = getString(R.string.capture_ampoule)
        }

        galleryButton = Button(this).apply {
            text = getString(R.string.upload_gallery)
        }

        testButton = Button(this).apply {
            text = getString(R.string.test_image)
        }

        extractButton = Button(this).apply {
            text = getString(R.string.extract_info)
        }

        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(captureButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(galleryButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(testButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val resultScrollView = androidx.core.widget.NestedScrollView(this).apply {
            addView(resultText)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(previewView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 300))
            addView(buttonLayout)
            addView(extractButton)
            addView(resultScrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        setContentView(layout)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        captureButton.setOnClickListener {
            captureImage()
        }

        galleryButton.setOnClickListener {
            if (capturedImages.size < 8) {
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } else {
                Toast.makeText(this, R.string.max_images_reached, Toast.LENGTH_SHORT).show()
            }
        }

        testButton.setOnClickListener {
            copyAssetToFile("test_ampoule.jpg")?.let { file ->
                addImageToList(file)
            }
        }

        extractButton.setOnClickListener {
            runExtractionAcrossAllImages()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            imageCapture = ImageCapture.Builder().build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                resultText.text = getString(R.string.camera_binding_failed, e.message)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureImage() {
        if (capturedImages.size >= 8) return

        val file = File(cacheDir, "camera_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    addImageToList(file)
                }

                override fun onError(exception: ImageCaptureException) {
                    resultText.text = getString(R.string.capture_failed, exception.message)
                }
            }
        )
    }

    private fun addImageToList(file: File) {
        if (capturedImages.size < 8) {
            capturedImages.add(file)
            updateThumbnails()
        } else {
            Toast.makeText(this, R.string.max_images_reached, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyUriToFile(uri: Uri): File? {
        return try {
            val file = File(cacheDir, "gallery_${System.currentTimeMillis()}.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    private fun copyAssetToFile(assetName: String): File? {
        return try {
            val file = File(cacheDir, "test_${System.currentTimeMillis()}.jpg")
            assets.open(assetName).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    private fun updateThumbnails() {
        thumbnailContainer.removeAllViews()
        capturedImages.forEach { file ->
            val frame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(250, 250).apply {
                    setMargins(8, 8, 8, 8)
                }
            }

            val imageView = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                setImageBitmap(bitmap)
            }

            val removeButton = Button(this).apply {
                text = getString(R.string.remove)
                layoutParams = FrameLayout.LayoutParams(120, 100).apply {
                    gravity = Gravity.TOP or Gravity.END
                }
                setOnClickListener {
                    capturedImages.remove(file)
                    file.delete()
                    updateThumbnails()
                }
            }

            frame.addView(imageView)
            frame.addView(removeButton)
            thumbnailContainer.addView(frame)
        }

        val isLimitReached = capturedImages.size >= 8
        captureButton.isEnabled = !isLimitReached
        galleryButton.isEnabled = !isLimitReached
        testButton.isEnabled = !isLimitReached
    }

    private fun runExtractionAcrossAllImages() {
        if (capturedImages.isEmpty()) {
            resultText.text = getString(R.string.no_images_error)
            return
        }

        resultText.text = getString(R.string.processing)
        
        lifecycleScope.launch {
            try {
                val allLines = mutableListOf<OcrLine>()
                
                capturedImages.forEach { file ->
                    val image = InputImage.fromFilePath(this@MainActivity, Uri.fromFile(file))
                    val visionText = withContext(Dispatchers.IO) {
                        Tasks.await(recognizer.process(image))
                    }
                    val lines = visionText.textBlocks.flatMap { block ->
                        block.lines.map { line ->
                            OcrLine(text = line.text, score = 1.0)
                        }
                    }
                    allLines.addAll(lines)
                }

                val result = extractor.extract(allLines)
                val gson = GsonBuilder().setPrettyPrinting().create()
                val linesJson = gson.toJson(allLines)
                val resultJson = gson.toJson(result)

                val output = StringBuilder().apply {
                    append("--- RAW OCR LINES ---\n")
                    append(linesJson)
                    append("\n\n--- STRUCTURED RESULT ---\n")
                    append(resultJson)
                }.toString()

                resultText.text = output
            } catch (e: Exception) {
                resultText.text = getString(R.string.ocr_failed_with_error, e.message)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close()
    }
}

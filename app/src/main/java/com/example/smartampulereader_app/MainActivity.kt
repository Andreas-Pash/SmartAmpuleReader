package com.example.smartampulereader_app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var resultText: TextView
    private lateinit var imageCapture: ImageCapture
    private lateinit var thumbnailContainer: LinearLayout
    private lateinit var captureButton: Button
    private lateinit var extractButton: Button
    private lateinit var galleryButton: Button
    private lateinit var testButton: Button

    private val capturedImageUris = mutableListOf<Uri>()
    private lateinit var extractor: AmpouleExtractor

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
            addImageToList(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        extractor = AmpouleExtractor(this)

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
            if (capturedImageUris.size < 8) {
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } else {
                Toast.makeText(this, R.string.max_images_reached, Toast.LENGTH_SHORT).show()
            }
        }

        testButton.setOnClickListener {
            val assetFiles = assets.list("")?.filter { it.endsWith(".jpg") } ?: emptyList()
            if (assetFiles.isEmpty()) {
                Toast.makeText(this, "No test images found in assets", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val checkedItems = BooleanArray(assetFiles.size)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Select Test Images")
                .setMultiChoiceItems(assetFiles.toTypedArray(), checkedItems) { _, which, isChecked ->
                    checkedItems[which] = isChecked
                }
                .setPositiveButton("Add Selected") { _, _ ->
                    for (i in checkedItems.indices) {
                        if (checkedItems[i]) {
                            val selectedAsset = assetFiles[i]
                            if (capturedImageUris.size < 8) {
                                // Create a unique target name to allow adding the same asset multiple times
                                val targetName = "${System.currentTimeMillis()}_$selectedAsset"
                                copyAssetToFile(selectedAsset, targetName)?.let { file ->
                                    addImageToList(Uri.fromFile(file))
                                }
                            }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
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
        if (capturedImageUris.size >= 8) return

        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SmartAmpuleReader")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    outputFileResults.savedUri?.let { uri ->
                        addImageToList(uri)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    resultText.text = getString(R.string.capture_failed, exception.message)
                }
            }
        )
    }

    private fun addImageToList(uri: Uri) {
        if (capturedImageUris.size < 8) {
            capturedImageUris.add(uri)
            updateThumbnails()
        } else {
            Toast.makeText(this, R.string.max_images_reached, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyAssetToFile(assetName: String, targetName: String = "test_${System.currentTimeMillis()}.jpg"): File? {
        return try {
            val file = File(cacheDir, targetName)
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

    private fun getFileNameFromUri(uri: Uri): String {
        if (uri.scheme == "file") {
            // For test assets, the filename is part of the path, but we might want the original asset name
            // However, Uri.fromFile(file) loses the "test_ampoule.jpg" name if we use a cache file
            return uri.lastPathSegment ?: "unknown_file"
        }

        var name = "unknown_media"
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                name = cursor.getString(index)
            }
        }
        return name
    }

    private fun updateThumbnails() {
        thumbnailContainer.removeAllViews()
        capturedImageUris.forEach { uri ->
            val frame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(250, 250).apply {
                    setMargins(8, 8, 8, 8)
                }
            }

            val imageView = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    setImageBitmap(bitmap)
                    inputStream?.close()
                } catch (e: Exception) {
                    // Handle error
                }
            }

            val removeButton = Button(this).apply {
                text = getString(R.string.remove)
                layoutParams = FrameLayout.LayoutParams(120, 100).apply {
                    gravity = Gravity.TOP or Gravity.END
                }
                setOnClickListener {
                    capturedImageUris.remove(uri)
                    // Optionally delete from MediaStore, but usually we just remove from the list
                    updateThumbnails()
                }
            }

            frame.addView(imageView)
            frame.addView(removeButton)
            thumbnailContainer.addView(frame)
        }

        val isLimitReached = capturedImageUris.size >= 8
        captureButton.isEnabled = !isLimitReached
        galleryButton.isEnabled = !isLimitReached
        testButton.isEnabled = !isLimitReached
    }

    private fun runExtractionAcrossAllImages() {
        if (capturedImageUris.isEmpty()) {
            resultText.text = getString(R.string.no_images_error)
            return
        }

        resultText.text = getString(R.string.processing)

        lifecycleScope.launch {
            try {
                val allLines = mutableListOf<OcrLine>()

                capturedImageUris.forEach { uri ->
                    val fileName = getFileNameFromUri(uri)
                    val image = InputImage.fromFilePath(this@MainActivity, uri)
                    val visionText = withContext(Dispatchers.IO) {
                        Tasks.await(recognizer.process(image))
                    }
                    val lines = visionText.textBlocks.flatMap { block ->
                        block.lines.map { line ->
                            OcrLine(text = line.text, sourceImage = fileName)
                        }
                    }
                    allLines.addAll(lines)
                }

                val result = withContext(Dispatchers.IO) {
                                extractor.extract(allLines)
                            }
                val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()
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
                resultText.text = "Extraction failed: ${e.message}"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close()
    }
}

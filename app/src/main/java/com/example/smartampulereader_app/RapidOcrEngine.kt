package com.example.smartampulereader_app

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class RapidOcrEngine(private val context: Context) : AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private var detSession: OrtSession? = null
    private var clsSession: OrtSession? = null
    private var recSession: OrtSession? = null
    
    private val labelList = mutableListOf<String>()

    init {
        loadModels()
    }

    private fun loadModels() {
        try {
            detSession = env.createSession(readAsset("det_model.onnx"))
            clsSession = env.createSession(readAsset("cls_model.onnx"))
            recSession = env.createSession(readAsset("rec_model.onnx"))
            
            context.assets.open("rec_dict.txt").bufferedReader().useLines { lines ->
                labelList.clear()
                labelList.add("") // Blank for CTC
                lines.forEach { labelList.add(it) }
                labelList.add(" ") // Space
            }
        } catch (e: Exception) {
            android.util.Log.e("RapidOcrEngine", "Failed to load models: ${e.message}")
        }
    }

    private fun readAsset(fileName: String): ByteArray {
        return try {
            context.assets.open(fileName).readBytes()
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    fun run(bitmap: Bitmap): List<OcrLine> {
        if (recSession == null) {
            return listOf(OcrLine("ERROR: rec_model.onnx or rec_dict.txt not found in assets", "system_error"))
        }
        
        val results = mutableListOf<OcrLine>()
        
        try {
            // Simplified: Run recognition on the whole image (or a center crop)
            // Full RapidOCR det+rec requires complex post-processing.
            val recResult = runRecognition(bitmap)
            if (recResult.text.isNotEmpty()) {
                results.add(recResult)
            }
        } catch (e: Exception) {
            android.util.Log.e("RapidOcrEngine", "Inference failed: ${e.message}")
        }
        
        return results
    }

    private fun runRecognition(bitmap: Bitmap): OcrLine {
        val resized = Bitmap.createScaledBitmap(bitmap, 320, 32, true)
        val tensor = bitmapToFloatBuffer(resized, 32, 320)
        
        val inputName = recSession?.inputNames?.firstOrNull() ?: return OcrLine("", "unknown")
        val inputTensor = OnnxTensor.createTensor(env, tensor, longArrayOf(1, 3, 32, 320))

        recSession?.run(mapOf(inputName to inputTensor)).use { result ->
            val output = result?.get(0)?.value as? Array<Array<FloatArray>> ?: return OcrLine("", "unknown")
            return decodeCTC(output[0])
        }
    }

    private fun decodeCTC(logits: Array<FloatArray>): OcrLine {
        val sb = StringBuilder()
        var lastIdx = -1
        var totalScore = 0f
        var count = 0

        for (i in logits.indices) {
            val probs = logits[i]
            val maxIdx = probs.indices.maxByOrNull { probs[it] } ?: 0
            val maxProb = probs[maxIdx]

            if (maxIdx > 0 && maxIdx != lastIdx) {
                if (maxIdx < labelList.size) {
                    sb.append(labelList[maxIdx])
                    totalScore += maxProb
                    count++
                }
            }
            lastIdx = maxIdx
        }

        // We'll use "rapid_ocr" as placeholder source since we don't have the filename here easily
        return OcrLine(sb.toString(), "rapid_ocr")
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap, height: Int, width: Int): FloatBuffer {
        val buffer = FloatBuffer.allocate(1 * 3 * height * width)
        val pixels = IntArray(height * width)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in 0 until 3) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pixel = pixels[y * width + x]
                    val value = when (i) {
                        0 -> (pixel shr 16 and 0xFF)
                        1 -> (pixel shr 8 and 0xFF)
                        else -> (pixel and 0xFF)
                    }
                    buffer.put((value / 255.0f - 0.5f) / 0.5f)
                }
            }
        }
        buffer.rewind()
        return buffer
    }

    override fun close() {
        detSession?.close()
        clsSession?.close()
        recSession?.close()
    }
}

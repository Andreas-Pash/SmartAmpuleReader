package com.example.smartampulereader_app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

class AmpouleExtractor(private val context: Context) : AutoCloseable {

    private val gson: Gson = GsonBuilder().create()
    
    private val gpuModel = File(context.filesDir, "gemma-2b-it-gpu-int4.bin")
    private val cpuModel = File(context.filesDir, "gemma-2b-it-cpu-int4.bin")

    private var llmInference: LlmInference? = null

    private fun initLlm() {
        if (llmInference != null) return
        
        val activeModel = if (gpuModel.exists()) gpuModel else cpuModel
        
        if (!activeModel.exists()) {
            throw IllegalStateException("Model file not found. Please ensure either ${gpuModel.name} or ${cpuModel.name} is in the app's files directory.")
        }

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(activeModel.absolutePath)
            .setMaxTokens(4096)
            .build()
            
        try {
            llmInference = LlmInference.createFromOptions(context, options)
        } catch (e: Exception) {
            throw RuntimeException("Failed to initialize LLM: ${e.message}")
        }
    }

    suspend fun extract(lines: List<OcrLine>): AmpouleResult {
        if (lines.isEmpty()) {
            return AmpouleResult(product_name = "Error: No text detected in images")
        }

        initLlm()

        val prompt = buildPrompt(lines)
        
        val rawResponse = try {
            llmInference?.generateResponse(prompt) 
        } catch (e: Exception) {
            throw RuntimeException("AI Inference failed: ${e.message}")
        } ?: throw RuntimeException("Empty response from AI")

        // Robust JSON cleaning: Find first { and last }
        val startIndex = rawResponse.indexOf('{')
        val endIndex = rawResponse.lastIndexOf('}')
        
        val cleanJson = if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            rawResponse.substring(startIndex, endIndex + 1)
        } else {
            rawResponse.trim()
        }

        return try {
            gson.fromJson(cleanJson, AmpouleResult::class.java)
        } catch (e: Exception) {
            // If it still fails, return a result showing exactly what the AI said for debugging
            AmpouleResult(
                product_name = "Parsing Failed",
                manufacturer = "Error: ${e.message}. Raw: $cleanJson"
            )
        }
    }

    private fun buildPrompt(lines: List<OcrLine>): String {
        val text = lines.joinToString("\n") { "[Source: ${it.sourceImage}] ${it.text}" }

        return """
            You are combining multiple OCR-extracted JSON objects from different images/views of the same medicine ampoule.
            Task: Output a flat JSON object of the ampoule label information based on the OCR Text.
            
            OCR Text:
            $text
            
            Required JSON Format:
            {
              "product_name": String or null,
              "strength": String or null,
              "volume": String or null,
              "batch_lot_number": String or null,
              "expiry_date": String or null,
              "manufacturer": String or null,
              "confidence_product_name": Number or null,
              "confidence_strength": Number or null,
              "confidence_volume": Number or null,
              "confidence_batch_lot_number": Number or null,
              "confidence_expiry_date": Number or null,
              "confidence_manufacturer": Number or null,
            }
            
            Rules:
            - Respond ONLY with the valid JSON object.
            - Do NOT use dots in keys. Use underscores only.
            - Do NOT nest objects.
            - Batch is usually near "Batch" or "LOT".
            - Expiry is near "EXP".
            
            JSON Output:
        """.trimIndent()
    }

    override fun close() {
        llmInference?.close()
        llmInference = null
    }
}

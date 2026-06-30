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
        You are a medical data extraction expert. Analyze the following OCR text from a medicine ampoule.
        Task: Extract label information into a flat JSON object.
        
        OCR Text:
        $text
        
        JSON Schema:
        {
          "product_name": "Full name of the medicine (e.g. Propofol)",
          "strength": "Concentration (e.g. 10mg/ml or 1%)",
          "volume": "Total liquid amount (e.g. 5ml or 2ml)",
          "batch_lot_number": "Batch or Lot ID",
          "expiry_date": "Expiration date exactly as shown on the label",
          "manufacturer": "Company name (e.g. Fresenius, Pfizer, etc.)",
          "confidence_product_name": 0.95,
          "confidence_strength": 0.95,
          "confidence_volume": 0.95,
          "confidence_batch_lot_number": 0.95,
          "confidence_expiry_date": 0.95,
          "confidence_manufacturer": 0.95
        }
        
        CRITICAL RULES:
        1. Confidence values MUST be a Number between 0.0 and 1.0 (e.g. 0.9). NEVER put text, dates, or symbols in confidence fields.
        2. "product_name" is the medicine itself. "manufacturer" is the company. Do not swap them.
        3. Use null for fields you cannot find.
        4. Respond ONLY with the valid JSON object. No preamble.
        
        JSON Output:
    """.trimIndent()
    }

    override fun close() {
        llmInference?.close()
        llmInference = null
    }
}

package com.example.smartampulereader_app

data class OcrLine(
    val text: String,
    val score: Double = 1.0
)
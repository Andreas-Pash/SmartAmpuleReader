package com.example.smartampulereader_app

data class AmpouleResult(
    val product_name: String? = null,
    val strength: String? = null,
    val volume: String? = null,
    val batch_lot_number: String? = null,
    val expiry_date: String? = null,
    val manufacturer: String? = null,

    val confidence_product_name: Double? = null,
    val confidence_strength: Double? = null,
    val confidence_volume: Double? = null,
    val confidence_batch_lot_number: Double? = null,
    val confidence_expiry_date: Double? = null,
    val confidence_manufacturer: Double? = null,
)
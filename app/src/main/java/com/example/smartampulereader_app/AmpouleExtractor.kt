package com.example.smartampulereader_app

class AmpouleExtractor {

    fun extract(lines: List<OcrLine>): AmpouleResult {
        val text = lines.joinToString("\n") { it.text }

        val batch = Regex("""(?i)(?:batch[:\s]*)?([A-Z0-9]{5,12})""")
            .findAll(text)
            .map { it.groupValues[1] }
            .firstOrNull { it.any(Char::isDigit) && it.any(Char::isLetter) }

        val expiry = Regex("""\b(0[1-9]|1[0-2])[/\-](20\d{2}|\d{2})\b""")
            .find(text)
            ?.value

        val volume = Regex("""\b\d+\s*ml\b""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.value

        val strength = Regex("""\b\d+\s*mg\s*/\s*ml\b|\b\d+%\b""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.value

        val pl = Regex("""PL\s*\d{5}/\d{4}""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.value

        val pa = Regex("""PA\s*\d{4}/\d{3}/\d{3}""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.value

        val legal = Regex("""\bPOM\b""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.value

        val route = if (text.contains("intravenous", ignoreCase = true)) {
            "Intravenous use"
        } else null

        val manufacturer = if (text.contains("Fresenius", ignoreCase = true) ||
            text.contains("Kabi", ignoreCase = true)
        ) {
            "Fresenius Kabi"
        } else null

        val active = if (text.contains("propofol", ignoreCase = true) ||
            text.contains("propo", ignoreCase = true)
        ) {
            "propofol"
        } else null

        val productName = when {
            active == "propofol" && strength != null -> "Propofol $strength"
            active == "propofol" -> "Propofol"
            else -> null
        }

        return AmpouleResult(
            product_name = productName,
            active_ingredient = active,
            strength = strength,
            volume = volume,
            batch_lot_number = batch,
            expiry_date = expiry,
            manufacturer = manufacturer,
            route = route,
            legal_category = legal,
            marketing_authorisation_uk = pl,
            marketing_authorisation_ie = pa,

            confidence_product_name = if (productName != null) 0.85 else null,
            confidence_active_ingredient = if (active != null) 0.9 else null,
            confidence_strength = if (strength != null) 0.9 else null,
            confidence_volume = if (volume != null) 0.9 else null,
            confidence_batch_lot_number = if (batch != null) 0.85 else null,
            confidence_expiry_date = if (expiry != null) 0.95 else null,
            confidence_manufacturer = if (manufacturer != null) 0.85 else null,
            confidence_route = if (route != null) 0.9 else null,
            confidence_legal_category = if (legal != null) 0.95 else null,
            confidence_marketing_authorisation_uk = if (pl != null) 0.95 else null,
            confidence_marketing_authorisation_ie = if (pa != null) 0.95 else null
        )
    }
}

package com.hedvig.policies.util

object PersonnummerExtractor {

    // Swedish personnummer patterns with word boundaries and month/day validation
    // Matches: YYYYMMDDXXXX, YYMMDDXXXX, YYYYMMDD-XXXX, YYMMDD-XXXX
    private val strictPattern = Regex("""\b(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{4}\b|\b\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])[-\s]?\d{4}\b""")

    // Lenient pattern: any 12-digit number or 10-digit with optional dash (for test personnummer)
    private val lenientPattern = Regex("""\b\d{12}\b|\b\d{6}[-\s]?\d{4}\b|\b\d{8}[-\s]?\d{4}\b""")

    /**
     * Extract personnummer from text
     * Returns the personnummer in normalized format (12 digits without dash)
     * First tries strict Swedish format, then falls back to lenient matching
     */
    fun extractPersonnummer(text: String): String? {
        // First try strict Swedish personnummer format
        val strictMatch = strictPattern.find(text)
        if (strictMatch != null) {
            return normalizePersonnummer(strictMatch.value)
        }

        // Fall back to lenient pattern (for test personnummer like 222222222222)
        val lenientMatch = lenientPattern.find(text)
        if (lenientMatch != null) {
            return normalizePersonnummer(lenientMatch.value)
        }

        return null
    }

    /**
     * Normalize personnummer to 12 digits without dashes
     */
    private fun normalizePersonnummer(value: String): String {
        var personnummer = value.replace(Regex("[-\\s]"), "")

        // If only 10 digits (YYMMDDXXXX), add century prefix
        if (personnummer.length == 10) {
            val yearPrefix = personnummer.substring(0, 2).toIntOrNull() ?: 0
            val century = if (yearPrefix in 0..30) "20" else "19"
            personnummer = century + personnummer
        }

        return if (personnummer.length == 12) personnummer else value.replace(Regex("[-\\s]"), "")
    }

    /**
     * Validate the date part of personnummer (strict Swedish format)
     */
    private fun isValidDatePart(personnummer: String): Boolean {
        if (personnummer.length != 12) return false

        val month = personnummer.substring(4, 6).toIntOrNull() ?: return false
        val day = personnummer.substring(6, 8).toIntOrNull() ?: return false

        return month in 1..12 && day in 1..31
    }

    /**
     * Validate basic format of personnummer (12 digits)
     * Lenient: accepts any 12-digit number
     */
    fun isValidFormat(personnummer: String): Boolean {
        return personnummer.matches(Regex("""\d{12}"""))
    }
}

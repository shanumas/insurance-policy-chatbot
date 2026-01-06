package com.hedvig.policies.util

object PersonnummerExtractor {

    // Swedish personnummer patterns with word boundaries and month/day validation
    // Matches: YYYYMMDDXXXX, YYMMDDXXXX, YYYYMMDD-XXXX, YYMMDD-XXXX
    private val personnummerPattern = Regex("""\b(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{4}\b|\b\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])[-\s]?\d{4}\b""")

    /**
     * Extract personnummer from text
     * Returns the personnummer in normalized format (12 digits without dash)
     */
    fun extractPersonnummer(text: String): String? {
        val match = personnummerPattern.find(text) ?: return null

        // Normalize the personnummer (remove dash/space, ensure 12 digits)
        var personnummer = match.value.replace(Regex("[-\\s]"), "")

        // If only 10 digits (YYMMDDXXXX), add century prefix
        if (personnummer.length == 10) {
            val yearPrefix = personnummer.substring(0, 2).toInt()
            val century = if (yearPrefix >= 0 && yearPrefix <= 30) "20" else "19"
            personnummer = century + personnummer
        }

        // Validate month and day
        if (personnummer.length == 12 && isValidDatePart(personnummer)) {
            return personnummer
        }

        return null
    }

    /**
     * Validate the date part of personnummer
     */
    private fun isValidDatePart(personnummer: String): Boolean {
        if (personnummer.length != 12) return false

        val month = personnummer.substring(4, 6).toIntOrNull() ?: return false
        val day = personnummer.substring(6, 8).toIntOrNull() ?: return false

        return month in 1..12 && day in 1..31
    }

    /**
     * Validate basic format of personnummer (12 digits)
     */
    fun isValidFormat(personnummer: String): Boolean {
        return personnummer.matches(Regex("""\d{12}""")) && isValidDatePart(personnummer)
    }
}

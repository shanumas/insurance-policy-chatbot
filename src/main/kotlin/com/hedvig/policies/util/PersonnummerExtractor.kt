package com.hedvig.policies.util

object PersonnummerExtractor {

    // Swedish personnummer patterns: YYYYMMDDXXXX or YYMMDDXXXX or YYYYMMDD-XXXX or YYMMDD-XXXX
    private val personnummerPattern = Regex("""(?:19|20)?\d{6}[-\s]?\d{4}""")

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

        return if (personnummer.length == 12) personnummer else null
    }

    /**
     * Validate basic format of personnummer (12 digits)
     */
    fun isValidFormat(personnummer: String): Boolean {
        return personnummer.matches(Regex("""\d{12}"""))
    }
}

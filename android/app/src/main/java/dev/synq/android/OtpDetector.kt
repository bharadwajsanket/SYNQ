package dev.synq.android

import java.util.regex.Pattern

data class OtpCandidate(
    val code: String,
    val sender: String,
    val timestamp: Long = System.currentTimeMillis(),
)

object OtpDetector {
    private val KEYWORD_PATTERN = Pattern.compile(
        "\\b(verification|verify|verifying|authentication|authenticate|security code|login code|one-time|one time|otp|passcode|secret code|pin code|auth code|confirmation code)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val CODE_PATTERNS = listOf(
        // e.g., "code: 123456", "code is 123456", "otp: 1234"
        Pattern.compile("(?:code|otp|pin|passcode)(?:\\s+is|:)?\\s*[:#-]?\\s*([0-9]{4,8})\\b", Pattern.CASE_INSENSITIVE),
        // e.g., "123456 is your verification code"
        Pattern.compile("\\b([0-9]{4,8})\\s+(?:is\\s+your|is\\s+the)\\b", Pattern.CASE_INSENSITIVE),
        // Generic 4-8 digit standalone number if strong keyword present
        Pattern.compile("\\b([0-9]{4,8})\\b")
    )

    private val AMOUNT_PATTERN = Pattern.compile("(?i)(?:[$€£₹]|usd|eur|inr|rs\\.?)\\s*\\d+")
    private val YEAR_PATTERN = Pattern.compile("\\b(19\\d\\d|20[2-3]\\d)\\b")
    private val TIME_PATTERN = Pattern.compile("\\b\\d{1,2}:\\d{2}(?:\\s*[ap]m)?\\b", Pattern.CASE_INSENSITIVE)
    private val PHONE_PATTERN = Pattern.compile("(?:\\+?\\d{1,3}[-.\\s]?)?\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}")

    private var lastCode: String? = null
    private var lastCodeTimestamp: Long = 0L

    @Synchronized
    fun detect(title: String, text: String, sender: String): OtpCandidate? {
        val fullContent = "$title $text".trim()
        if (fullContent.length < 6 || fullContent.length > 1000) return null

        // Must contain at least one verification-related keyword
        if (!KEYWORD_PATTERN.matcher(fullContent).find()) return null

        for (pattern in CODE_PATTERNS) {
            val matcher = pattern.matcher(fullContent)
            while (matcher.find()) {
                val candidate = matcher.group(1) ?: continue
                if (isValidCandidate(candidate, fullContent)) {
                    val now = System.currentTimeMillis()
                    // Duplicate suppression: suppress identical code within 60 seconds
                    if (candidate == lastCode && now - lastCodeTimestamp < 60_000) {
                        return null
                    }
                    lastCode = candidate
                    lastCodeTimestamp = now
                    return OtpCandidate(
                        code = candidate,
                        sender = sender.take(64).ifBlank { "Verification" },
                        timestamp = now,
                    )
                }
            }
        }
        return null
    }

    private fun isValidCandidate(code: String, context: String): Boolean {
        // Disallow trivial repetitions (e.g. "0000", "111111")
        if (code.all { it == code[0] }) return false

        // Rejection rule 1: Date/year (e.g. 2024, 2025, 2026)
        if (code.length == 4 && YEAR_PATTERN.matcher(code).matches()) {
            return false
        }

        // Rejection rule 2: If code is immediately preceded or followed by currency symbol/label
        val codeIndex = context.indexOf(code)
        if (codeIndex >= 0) {
            val prefix = context.substring(maxOf(0, codeIndex - 6), codeIndex).trim()
            val suffix = context.substring(minOf(context.length, codeIndex + code.length), minOf(context.length, codeIndex + code.length + 6)).trim()
            if (AMOUNT_PATTERN.matcher(prefix + code).find() || AMOUNT_PATTERN.matcher(code + suffix).find()) {
                return false
            }
        }

        // Rejection rule 3: If candidate is part of a longer phone number
        val phoneMatcher = PHONE_PATTERN.matcher(context)
        while (phoneMatcher.find()) {
            val phoneMatch = phoneMatcher.group()
            if (phoneMatch.contains(code) && phoneMatch.filter { it.isDigit() }.length > code.length) {
                return false
            }
        }

        return true
    }

    @Synchronized
    fun resetForTesting() {
        lastCode = null
        lastCodeTimestamp = 0L
    }
}

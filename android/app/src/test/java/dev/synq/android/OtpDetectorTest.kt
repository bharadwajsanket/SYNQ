package dev.synq.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class OtpDetectorTest {

    @Before
    fun setUp() {
        OtpDetector.resetForTesting()
    }

    @Test
    fun detectsValidOtpCodes() {
        val google = OtpDetector.detect(
            title = "Google",
            text = "G-839201 is your Google verification code.",
            sender = "Google",
        )
        assertNotNull(google)
        assertEquals("839201", google?.code)
        assertEquals("Google", google?.sender)

        OtpDetector.resetForTesting()

        val github = OtpDetector.detect(
            title = "GitHub",
            text = "Your one-time authentication code is 482910.",
            sender = "GitHub",
        )
        assertNotNull(github)
        assertEquals("482910", github?.code)

        OtpDetector.resetForTesting()

        val bank = OtpDetector.detect(
            title = "Messages",
            text = "Do not share this OTP with anyone. 7291 is your login code.",
            sender = "Chase",
        )
        assertNotNull(bank)
        assertEquals("7291", bank?.code)
    }

    @Test
    fun rejectsNonOtpNotifications() {
        // Normal conversation
        assertNull(OtpDetector.detect("Alice", "Hey, are you free tomorrow at 5?", "WhatsApp"))
        // Order notification with tracking number but no verification keyword
        assertNull(OtpDetector.detect("Amazon", "Your package 98234710 has shipped", "Amazon"))
        // Amount/transaction
        assertNull(OtpDetector.detect("Bank", "You spent $2500 at Apple Store", "Bank"))
        // Trivial repetitions
        assertNull(OtpDetector.detect("Auth", "Your verification code is 000000", "Service"))
    }

    @Test
    fun rejectsYearsAndPhoneNumbers() {
        // Date / year
        assertNull(OtpDetector.detect("Calendar", "Verification of 2026 event schedule", "Calendar"))
        // Phone number
        assertNull(OtpDetector.detect("Messages", "Verification team reached from +1 (555) 234-5678", "Messages"))
    }

    @Test
    fun suppressesDuplicatesWithinWindow() {
        val first = OtpDetector.detect("Google", "Your verification code is 654321", "Google")
        assertNotNull(first)
        // Immediate duplicate
        val second = OtpDetector.detect("Google", "Your verification code is 654321", "Google")
        assertNull(second)
    }
}

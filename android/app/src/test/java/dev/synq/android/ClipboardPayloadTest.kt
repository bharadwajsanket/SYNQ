package dev.synq.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardPayloadTest {
    @Test fun richClipboardRequiresFallbackTextAndHtml() {
        assertNull(RichClipboardContent.create("text", null))
        assertNull(RichClipboardContent.create("", "<b>text</b>"))
        assertNull(RichClipboardContent.create("text", ""))
        assertEquals("<b>text</b>", RichClipboardContent.create("text", "<b>text</b>")?.html)
    }

    @Test fun clipboardContentIsBoundedBeforeEncryption() {
        assertTrue(clipboardTextFits("x".repeat(MAX_CLIPBOARD_CONTENT_BYTES)))
        assertFalse(clipboardTextFits("x".repeat(MAX_CLIPBOARD_CONTENT_BYTES + 1)))
        assertNull(RichClipboardContent.create("x".repeat(MAX_CLIPBOARD_CONTENT_BYTES), "<b>x</b>"))
    }

    @Test fun emptyClipboardTextIsRejected() {
        assertFalse(clipboardTextFits(""))
    }

    @Test fun multibyteUtf8ByteBounds() {
        val emoji = "🔒" // 4 bytes in UTF-8
        assertEquals(4, emoji.toByteArray(Charsets.UTF_8).size)
        val fits = emoji.repeat(MAX_CLIPBOARD_CONTENT_BYTES / 4)
        assertTrue(clipboardTextFits(fits))

        val overflows = fits + "x"
        assertFalse(clipboardTextFits(overflows))
    }

    @Test fun richClipboardRejectsCombinedTextAndHtmlExceedingLimit() {
        val half = "x".repeat(MAX_CLIPBOARD_CONTENT_BYTES / 2)
        val combinedFit = RichClipboardContent.create(half, half)
        assertTrue(combinedFit != null)

        val combinedExceeded = RichClipboardContent.create(half + "x", half)
        assertNull(combinedExceeded)
    }

    @Test fun richClipboardRejectsWhitespaceOnlyHtml() {
        assertNull(RichClipboardContent.create("text", "   "))
        assertNull(RichClipboardContent.create("text", "\t\n"))
    }
}

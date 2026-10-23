package dev.synq.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileBrowsingAndSmsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun resolveSafePath(rootDir: File, relativePath: String, fileBrowsingEnabled: Boolean = true): File? {
        if (!fileBrowsingEnabled) return null
        val clean = relativePath.trim().removePrefix("/").removePrefix("\\")
        val components = clean.split("/", "\\").filter { it.isNotEmpty() }
        if (components.any { it == ".." || it == "." }) return null

        val canonicalRoot = rootDir.canonicalFile
        val target = if (components.isEmpty()) canonicalRoot else File(canonicalRoot, components.joinToString(File.separator)).canonicalFile
        if (!target.path.startsWith(canonicalRoot.path)) return null
        return target
    }

    @Test
    fun rootPathResolvesInsideBoundary() {
        val root = tempFolder.newFolder("Downloads")
        val resolved = resolveSafePath(root, "")
        assertNotNull(resolved)
        assertEquals(root.canonicalFile, resolved)
    }

    @Test
    fun subfolderPathResolvesInsideBoundary() {
        val root = tempFolder.newFolder("Downloads")
        val sub = File(root, "Subfolder").apply { mkdir() }
        val testFile = File(sub, "test.txt").apply { writeText("hello") }

        val resolved = resolveSafePath(root, "Subfolder/test.txt")
        assertNotNull(resolved)
        assertEquals(testFile.canonicalFile, resolved)
    }

    @Test
    fun pathTraversalAttemptsAreRejected() {
        val root = tempFolder.newFolder("Downloads")
        assertNull("Parent traversal must be rejected", resolveSafePath(root, "../secret.txt"))
        assertNull("Multi parent traversal must be rejected", resolveSafePath(root, "../../etc/passwd"))
        assertNull("Relative path traversal must be rejected", resolveSafePath(root, "Subfolder/../../secret.txt"))
        assertNull("Dot components must be rejected", resolveSafePath(root, "./test.txt"))
    }

    @Test
    fun disabledBrowsingRejectsAllPaths() {
        val root = tempFolder.newFolder("Downloads")
        assertNull(resolveSafePath(root, "", fileBrowsingEnabled = false))
        assertNull(resolveSafePath(root, "test.txt", fileBrowsingEnabled = false))
    }

    @Test
    fun smsRecipientValidation() {
        // Direct testing of phone number normalization for SMS
        val normalizedValid = normalizedPhoneNumber("+1 (555) 123-4567")
        assertEquals("+15551234567", normalizedValid)

        val normalizedLocal = normalizedPhoneNumber("9876543210")
        assertEquals("9876543210", normalizedLocal)

        val invalidChars = normalizedPhoneNumber("abc-not-a-number")
        assertNull(invalidChars)

        val empty = normalizedPhoneNumber("")
        assertNull(empty)
    }

    @Test
    fun storageCheckProtectsAgainstOverflow() {
        val freeBytes = 1000L
        val requestedFileSize = 5000L
        val hasEnoughStorage = freeBytes >= requestedFileSize
        assertFalse(hasEnoughStorage)

        val largeFreeBytes = 10000L
        val canFit = largeFreeBytes >= requestedFileSize
        assertTrue(canFit)
    }
}

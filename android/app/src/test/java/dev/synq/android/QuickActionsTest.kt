package dev.synq.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class QuickActionsTest {
    @Test fun encryptedRequestSequenceRejectsReplayIndependentlyOfOuterMessageId() {
        val sequence = QuickRequestSequence()
        assertTrue(sequence.accept("media", 4))
        assertFalse(sequence.accept("media", 4))
        assertFalse(sequence.accept("media", 3))
        assertTrue(sequence.accept("links", 1))
        assertTrue(sequence.accept("media", 5))
        assertFalse(sequence.accept("other", 6))
        assertFalse(sequence.accept("media", Long.MAX_VALUE))
    }
    @Test fun webLinksAllowOnlyExplicitBrowserNavigation() {
        listOf("https://example.com/path?q=one#two", "http://localhost:8080/", "https://[::1]/").forEach {
            assertEquals(it, validatedWebLink(it))
        }
        assertEquals("https://example.com", validatedWebLink("  https://example.com\n"))
        listOf("", "file:///etc/passwd", "javascript:alert(1)", "tel:+12345678", "bridgey://call", "https://",
            "https://user:pass@example.com", "https://example.com:0", "https://example.com:65536",
            "https://exa mple.com", "https://example.com/\nfoo", "https://example.com/\\evil",
            "https://example.com/\u0000", "https://example.com/" + "a".repeat(4096)).forEach {
            assertNull("Unsafe or oversized link: ${it.take(60)}", validatedWebLink(it))
        }
    }

    @Test fun smartLinkDetectionAndValidation() {
        assertEquals("https://github.com/bharadwajsanket", SmartLinkDetector.detect("Check out https://github.com/bharadwajsanket for the repo"))
        assertEquals("https://www.google.com", SmartLinkDetector.detect("Go to www.google.com right now"))
        assertEquals("http://my-mac.local:3000/api", SmartLinkDetector.detect("Dashboard: http://my-mac.local:3000/api"))
        assertEquals("http://192.168.1.1:8080/setup", SmartLinkDetector.detect("Router at 192.168.1.1:8080/setup"))
        assertEquals("http://10.0.0.5", SmartLinkDetector.detect("Test server 10.0.0.5"))
        assertEquals("http://127.0.0.1:5000/status", SmartLinkDetector.detect("Server at 127.0.0.1:5000/status"))
        assertEquals("http://[::1]:8080/info", SmartLinkDetector.detect("IPv6 http://[::1]:8080/info"))

        // False positives rejection
        assertNull(SmartLinkDetector.detect("The version is 2.5.4 today"))
        assertNull(SmartLinkDetector.detect("Hello world! How are you?"))
        assertNull(SmartLinkDetector.detect("Contact user@example.com for help"))
        assertNull(SmartLinkDetector.detect("File is located at /var/log/syslog"))
        assertNull(SmartLinkDetector.detect("random.word without domain"))
    }

    @Test fun newFeaturesAreNotAdvertisedByLegacyPeers() {
        assertFalse(featureEnabledByLegacyPeer(SYNQFeature.LINKS))
        assertFalse(featureEnabledByLegacyPeer(SYNQFeature.MEDIA))
    }

    @Test fun newComponentsCannotBeInvokedByArbitraryApplications() {
        val ns = "http://schemas.android.com/apk/res/android"
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = doc.getElementsByTagName("activity")
        val confirmation = (0 until activities.length).map { activities.item(it) }.first {
            it.attributes.getNamedItemNS(ns, "name")?.nodeValue == ".ConfirmCallActivity"
        }
        assertEquals("false", confirmation.attributes.getNamedItemNS(ns, "exported").nodeValue)
        val services = doc.getElementsByTagName("service")
        val tile = (0 until services.length).map { services.item(it) }.first {
            it.attributes.getNamedItemNS(ns, "name")?.nodeValue == ".ClipboardTileService"
        }
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tile.attributes.getNamedItemNS(ns, "permission").nodeValue)
    }

    @Test fun quickActionStatusFormatting() {
        assertEquals("Link sent to Mac", formatQuickActionStatus("links", true))
        assertEquals("Link declined — check settings or dismiss the previous link", formatQuickActionStatus("links", false))
        assertEquals("Media command completed", formatQuickActionStatus("media", true))
        assertEquals("Player unavailable or busy — check Mac media settings and try again", formatQuickActionStatus("media", false))
    }
}

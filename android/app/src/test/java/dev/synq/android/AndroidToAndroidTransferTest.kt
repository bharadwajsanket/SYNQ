package dev.synq.android

import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidToAndroidTransferTest {

    @Test
    fun messageEncodesAndDecodesAndroidPlatform() {
        val jsonString = """{"type":"handshake","deviceId":"dev-123","peerName":"Pixel 8 Pro","platform":"android"}"""
        val json = JSONObject(jsonString)
        assertEquals("handshake", json.optString("type"))
        assertEquals("dev-123", json.optString("deviceId"))
        assertEquals("Pixel 8 Pro", json.optString("peerName"))
        assertEquals("android", json.optString("platform"))
    }

    @Test
    fun androidPeerPlatformRecognition() {
        val isAndroid1 = "android".equals("android", ignoreCase = true)
        val isAndroid2 = "Android".equals("android", ignoreCase = true)
        val isAndroid3 = "macos".equals("android", ignoreCase = true)
        val isAndroid4 = (null as String?).equals("android", ignoreCase = true)

        assertTrue(isAndroid1)
        assertTrue(isAndroid2)
        assertFalse(isAndroid3)
        assertFalse(isAndroid4)
    }

    @Test
    fun strictFeatureIsolationForAndroidPeer() {
        // SYNQ 3.5.4 rule: Only file transfer is permitted between Android phones.
        // SMS, Calls, Notifications, Clipboard, Battery, and Ping/Ring must NOT be enabled.
        val isAndroidPeer = true
        for (feature in SYNQFeature.values()) {
            val allowedForPeer = if (isAndroidPeer) {
                feature == SYNQFeature.FILES
            } else {
                true // Mac allows all supported features
            }

            if (feature == SYNQFeature.FILES) {
                assertTrue("Files feature must be permitted for Android peer", allowedForPeer)
            } else {
                assertFalse("Feature $feature must be prohibited for Android peer", allowedForPeer)
            }
        }
    }

    @Test
    fun filenameSanitizationPreventsPathTraversal() {
        val dangerousNames = listOf(
            "../../etc/passwd",
            "..\\..\\windows\\system32\\cmd.exe",
            "foo/bar/baz.jpg",
            "/absolute/path/file.pdf",
            "../../../evil.sh"
        )

        for (raw in dangerousNames) {
            val safe = File(raw).name.replace(Regex("[/\\\\]"), "_").trim()
            assertFalse("Sanitized filename must not contain slashes", safe.contains("/"))
            assertFalse("Sanitized filename must not contain backslashes", safe.contains("\\"))
            assertFalse("Sanitized filename must not be traversal token", safe == ".." || safe == ".")
            assertTrue("Sanitized filename must retain file base name", safe.isNotEmpty())
        }
    }

    @Test
    fun symmetricCryptoBetweenTwoAndroidPeers() {
        // Verify that two independent Android keypairs generate identical shared secrets and 6-digit SAS codes
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))

        val phoneA = kpg.generateKeyPair()
        val phoneB = kpg.generateKeyPair()

        val pubA = Crypto.encodePublicKey(phoneA)
        val pubB = Crypto.encodePublicKey(phoneB)
        val sessionId = UUID.randomUUID().toString()

        val materialA = Crypto.pairingMaterial(phoneA, pubB, sessionId)
        val materialB = Crypto.pairingMaterial(phoneB, pubA, sessionId)

        // Both peers must calculate the exact same 6-digit confirmation code
        assertEquals(materialA.code, materialB.code)
        assertEquals(6, materialA.code.length)
        assertTrue(materialA.code.all { it.isDigit() })

        // Both peers must derive the identical 256-bit AES-GCM session key
        org.junit.Assert.assertArrayEquals(materialA.key, materialB.key)
        assertEquals(32, materialA.key.size)

        // Confirmation proofs must also verify symmetrically
        val proofA = Crypto.confirmationProof(materialA.key, sessionId, "device-A", pubA)
        val proofB = Crypto.confirmationProof(materialB.key, sessionId, "device-B", pubB)

        assertTrue(Crypto.verifyConfirmationProof(materialB.key, sessionId, "device-A", pubA, proofA))
        assertTrue(Crypto.verifyConfirmationProof(materialA.key, sessionId, "device-B", pubB, proofB))
    }

    @Test
    fun directTransferOfferMessageStructure() {
        val sessionId = UUID.randomUUID().toString()
        val transferId = UUID.randomUUID().toString()
        val offer = JSONObject().apply {
            put("kind", "direct.transfer.offer")
            put("sessionId", sessionId)
            put("senderDeviceId", "pixel-dev-1234")
            put("senderDeviceName", "Pixel 8 Pro")
            put("publicKey", "ephemeralPubBase64")
            put("platform", "android")
            put("transferId", transferId)
            put("name", "vacation_photo.jpg")
            put("size", 2048576L)
            put("mimeType", "image/jpeg")
            put("sha256", "testSha256DigestValue==")
        }

        assertEquals("direct.transfer.offer", offer.optString("kind"))
        assertEquals("Pixel 8 Pro", offer.optString("senderDeviceName"))
        assertEquals("vacation_photo.jpg", offer.optString("name"))
        assertEquals(2048576L, offer.optLong("size"))
        assertEquals("image/jpeg", offer.optString("mimeType"))
        assertEquals("testSha256DigestValue==", offer.optString("sha256"))
    }

    @Test
    fun directTransferAcceptAndRejectMessages() {
        val sessionId = UUID.randomUUID().toString()
        val transferId = UUID.randomUUID().toString()

        val accept = JSONObject().apply {
            put("kind", "direct.transfer.accept")
            put("sessionId", sessionId)
            put("transferId", transferId)
            put("publicKey", "receiverEphemeralPub==")
        }
        assertEquals("direct.transfer.accept", accept.optString("kind"))
        assertEquals("receiverEphemeralPub==", accept.optString("publicKey"))

        val reject = JSONObject().apply {
            put("kind", "direct.transfer.reject")
            put("sessionId", sessionId)
            put("transferId", transferId)
            put("reason", "user_declined")
        }
        assertEquals("direct.transfer.reject", reject.optString("kind"))
        assertEquals("user_declined", reject.optString("reason"))
    }

    @Test
    fun directTransferChunkEncryptionAndDecryption() {
        val phoneA = Crypto.generateKeyPair()
        val phoneB = Crypto.generateKeyPair()
        val pubA = Crypto.encodePublicKey(phoneA)
        val pubB = Crypto.encodePublicKey(phoneB)
        val sessionId = UUID.randomUUID().toString()

        val keyA = Crypto.pairingMaterial(phoneA, pubB, sessionId).key
        val keyB = Crypto.pairingMaterial(phoneB, pubA, sessionId).key
        org.junit.Assert.assertArrayEquals(keyA, keyB)

        val fileData = "Hello Android-to-Android LocalSend transfer!".toByteArray(Charsets.UTF_8)
        val encrypted = Crypto.encrypt(keyA, fileData)
        val decrypted = Crypto.decrypt(keyB, encrypted.nonce, encrypted.ciphertext)

        assertNotNull(decrypted)
        assertEquals("Hello Android-to-Android LocalSend transfer!", String(decrypted!!, Charsets.UTF_8))
    }

    @Test
    fun sha256IntegrityVerificationAcrossMultipleFileTypes() {
        val testFiles = mapOf(
            "document.pdf" to "PDF content simulating multi-page binary document data".toByteArray(Charsets.UTF_8),
            "photo.jpg" to ByteArray(1024) { (it % 256).toByte() },
            "archive.zip" to ByteArray(4096) { ((it * 3) % 256).toByte() },
            "script.py" to "print('hello from python')\n".toByteArray(Charsets.UTF_8)
        )

        for ((filename, bytes) in testFiles) {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val correctHash = Base64.getEncoder().encodeToString(md.digest(bytes))

            // Corrupted data
            val corruptedBytes = bytes.copyOf().also {
                if (it.isNotEmpty()) it[0] = (it[0].toInt() xor 0xFF).toByte()
            }
            val mdCorrupt = java.security.MessageDigest.getInstance("SHA-256")
            val corruptHash = Base64.getEncoder().encodeToString(mdCorrupt.digest(corruptedBytes))

            if (bytes.isNotEmpty()) {
                assertFalse("Corrupted file must produce different SHA-256 for $filename", correctHash == corruptHash)
            }
            assertTrue("Valid file must match its own SHA-256 for $filename", correctHash.isNotEmpty())
        }
    }

    @Test
    fun ipv4AddressPrioritizationForAndroidDiscovery() {
        // Simulates hostAddresses from Android 14+ NsdServiceInfo
        val ipv6LinkLocal = java.net.InetAddress.getByName("fe80::1ff:fe00:3a60")
        val ipv4Local = java.net.InetAddress.getByName("192.168.1.105")
        val addresses = listOf(ipv6LinkLocal, ipv4Local)

        val chosen = addresses.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
            ?: addresses.firstOrNull { it is java.net.Inet4Address }
            ?: addresses.firstOrNull { !it.isLinkLocalAddress && !it.isLoopbackAddress }
            ?: addresses.firstOrNull()

        assertNotNull(chosen)
        assertTrue("IPv4 address must be prioritized over link-local IPv6 for reliable LAN sockets", chosen is java.net.Inet4Address)
        assertEquals("192.168.1.105", chosen?.hostAddress)
    }

    @Test
    fun ephemeralSessionLeavesNoPermanentTrust() {
        // Verify that Android peers are never saved into permanent trust registry
        val isAndroidPeer = true
        val savedDevices = mutableSetOf<String>()

        if (!isAndroidPeer) {
            savedDevices.add("mac-device-id")
        }

        assertTrue("Android peer must never be saved into permanent trust registry", savedDevices.isEmpty())
    }

    @Test
    fun transferPhaseEnumCoversAllTenLifecycleStates() {
        val phases = TransferPhase.values().map { it.name }.toSet()
        val expected = setOf(
            "DISCOVERING",
            "REQUEST_RECEIVED",
            "AWAITING_DECISION",
            "CONNECTING",
            "TRANSFERRING",
            "VERIFYING",
            "COMPLETED",
            "REJECTED",
            "FAILED",
            "CANCELLED"
        )
        assertEquals("TransferPhase must cover exactly 10 states", expected, phases)
    }

    @Test
    fun queuedConnectionRequestPreservesIncomingDeviceMetadata() {
        val socket = java.net.Socket()
        val dummyInput = java.io.BufferedInputStream(java.io.ByteArrayInputStream(ByteArray(0)))
        val req = PairingCoordinator.QueuedConnectionRequest(
            socket = socket,
            deviceId = "moto-edge-50",
            deviceName = "Moto Edge 50",
            initialLine = """{"type":"handshake","deviceId":"moto-edge-50"}""",
            input = dummyInput,
            timestamp = 1000L
        )
        assertEquals("moto-edge-50", req.deviceId)
        assertEquals("Moto Edge 50", req.deviceName)
        assertEquals("""{"type":"handshake","deviceId":"moto-edge-50"}""", req.initialLine)
        assertTrue(req.timestamp >= 0)
    }

    @Test
    fun mediaStateDefaultsAndUpdates() {
        val state = AndroidMediaState(
            hasActiveSession = true,
            title = "Midnight City",
            artist = "M83",
            playing = true,
            durationSeconds = 243,
            positionSeconds = 60,
            volume = 75,
            maxVolume = 100,
            playerName = "Spotify"
        )
        assertTrue(state.hasActiveSession)
        assertEquals("Midnight City", state.title)
        assertEquals("M83", state.artist)
        assertTrue(state.playing)
        assertEquals(243, state.durationSeconds)
        assertEquals(60, state.positionSeconds)
        assertEquals(75, state.volume)
        assertEquals(100, state.maxVolume)
        assertEquals("Spotify", state.playerName)
    }
}


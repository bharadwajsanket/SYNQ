package dev.synq.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QRPairingTest {

    @Test
    fun parseValidUriPayload() {
        val expiry = System.currentTimeMillis() + 60_000
        val uri = "synq://pair?v=1&id=mac-device-1234&name=MacBook%20Pro&host=192.168.1.50&port=42458&token=a1b2c3d4e5f6g7h8i9j0&exp=$expiry"
        val result = QRPairingPayload.parse(uri)
        assertTrue(result.isSuccess)
        val payload = result.getOrThrow()
        assertEquals(1, payload.version)
        assertEquals("mac-device-1234", payload.deviceId)
        assertEquals("MacBook Pro", payload.deviceName)
        assertEquals("192.168.1.50", payload.host)
        assertEquals(42458, payload.port)
        assertEquals("a1b2c3d4e5f6g7h8i9j0", payload.token)
        assertEquals(expiry, payload.expiresAt)
        assertFalse(payload.isExpired())
    }

    @Test
    fun parseValidJsonPayload() {
        val expiry = System.currentTimeMillis() + 60_000
        val json = """
            {
                "v": 1,
                "id": "mac-device-5678",
                "name": "iMac Studio",
                "host": "10.0.1.25",
                "port": 42458,
                "token": "abcdef0123456789abcd",
                "exp": $expiry
            }
        """.trimIndent()
        val result = QRPairingPayload.parse(json)
        assertTrue(result.isSuccess)
        val payload = result.getOrThrow()
        assertEquals("mac-device-5678", payload.deviceId)
        assertEquals("iMac Studio", payload.deviceName)
        assertEquals("10.0.1.25", payload.host)
        assertEquals(42458, payload.port)
    }

    @Test
    fun rejectExpiredPayload() {
        val pastExpiry = System.currentTimeMillis() - 5_000
        val uri = "synq://pair?v=1&id=mac-device-1234&name=MacBook&host=192.168.1.50&port=42458&token=a1b2c3d4e5f6g7h8i9j0&exp=$pastExpiry"
        val result = QRPairingPayload.parse(uri)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("expired") == true)
    }

    @Test
    fun rejectPublicWanIpAddressesToPreventSsrf() {
        val expiry = System.currentTimeMillis() + 60_000
        val publicIps = listOf("8.8.8.8", "1.1.1.1", "142.250.190.46", "203.0.113.1")
        for (ip in publicIps) {
            val uri = "synq://pair?v=1&id=mac-device-1234&name=MacBook&host=$ip&port=42458&token=a1b2c3d4e5f6g7h8i9j0&exp=$expiry"
            val result = QRPairingPayload.parse(uri)
            assertTrue("Public IP $ip must be rejected", result.isFailure)
        }
    }

    @Test
    fun acceptPrivateAndLocalHostFormats() {
        val allowed = listOf(
            "192.168.1.1",
            "10.0.0.1",
            "172.16.0.1",
            "172.31.255.254",
            "127.0.0.1",
            "localhost",
            "my-macbook.local",
            "fe80::1"
        )
        for (host in allowed) {
            assertTrue("Host $host should be accepted as local", QRPairingPayload.isPrivateOrLocalHost(host))
        }
    }

    @Test
    fun rejectInvalidPortRanges() {
        val expiry = System.currentTimeMillis() + 60_000
        val invalidPorts = listOf(80, 443, 22, 0, 70000, 1023)
        for (port in invalidPorts) {
            val uri = "synq://pair?v=1&id=mac-device-1234&name=MacBook&host=192.168.1.10&port=$port&token=a1b2c3d4e5f6g7h8i9j0&exp=$expiry"
            val result = QRPairingPayload.parse(uri)
            assertTrue("Privileged / invalid port $port should be rejected", result.isFailure)
        }
    }
}


package dev.synq.android

import dev.synq.core.discovery.DiscoveredPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyDeviceDeduplicationTest {

    private fun filterNearby(
        peers: List<DiscoveredPeer>,
        trustedDevices: List<TrustedDevice>,
        connectedDeviceId: String? = null,
        connectedPeerName: String? = null,
    ): List<DiscoveredPeer> {
        val trustedIds = trustedDevices.map { it.id.lowercase() }.toSet()
        val trustedNames = trustedDevices.map { it.name.trim().lowercase() }.toSet()

        return peers.filter { peer ->
            val peerId = peer.deviceIdHint?.lowercase()
            val peerName = peer.deviceNameHint.trim().lowercase()
            if (peerId != null && (trustedIds.contains(peerId) || (connectedDeviceId != null && peerId == connectedDeviceId.lowercase()))) {
                return@filter false
            }
            if (trustedNames.contains(peerName) || (connectedPeerName != null && peerName == connectedPeerName.trim().lowercase())) {
                return@filter false
            }
            true
        }
    }

    @Test
    fun filtersOutPeerWithMatchingDeviceId() {
        val pairedId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        val trusted = listOf(TrustedDevice(id = pairedId, name = "Sanket's MacBook Pro"))
        val peers = listOf(
            DiscoveredPeer(
                serviceName = "SYNQ-a1b2c3d4",
                deviceIdHint = pairedId,
                deviceNameHint = "Different Name",
                platformHint = "macos",
                protocolVersionHint = 1,
                host = "192.168.1.50",
                port = 42458
            ),
            DiscoveredPeer(
                serviceName = "SYNQ-unpaired",
                deviceIdHint = "ffffffff-ffff-ffff-ffff-ffffffffffff",
                deviceNameHint = "New Mac",
                platformHint = "macos",
                protocolVersionHint = 1,
                host = "192.168.1.51",
                port = 42458
            )
        )

        val result = filterNearby(peers, trusted)
        assertEquals(1, result.size)
        assertEquals("New Mac", result.first().deviceNameHint)
    }

    @Test
    fun filtersOutPeerWithMatchingNameWhenIdMissingOrChanged() {
        val trusted = listOf(TrustedDevice(id = "original-id", name = "MacBook Air"))
        val peers = listOf(
            DiscoveredPeer(
                serviceName = "SYNQ-macbook",
                deviceIdHint = null,
                deviceNameHint = "  macbook air  ",
                platformHint = "macos",
                protocolVersionHint = 1,
                host = "192.168.1.50",
                port = 42458
            ),
            DiscoveredPeer(
                serviceName = "SYNQ-other",
                deviceIdHint = null,
                deviceNameHint = "Studio Mac",
                platformHint = "macos",
                protocolVersionHint = 1,
                host = "192.168.1.52",
                port = 42458
            )
        )

        val result = filterNearby(peers, trusted)
        assertEquals(1, result.size)
        assertEquals("Studio Mac", result.first().deviceNameHint)
    }

    @Test
    fun filtersOutCurrentlyConnectedDevice() {
        val connectedId = "conn-1234"
        val connectedName = "Connected iMac"
        val peers = listOf(
            DiscoveredPeer(
                serviceName = "SYNQ-conn",
                deviceIdHint = connectedId,
                deviceNameHint = connectedName,
                platformHint = "macos",
                protocolVersionHint = 1,
                host = "192.168.1.60",
                port = 42458
            )
        )

        val result = filterNearby(peers, emptyList(), connectedDeviceId = connectedId, connectedPeerName = connectedName)
        assertTrue(result.isEmpty())
    }

    @Test
    fun preservesUnpairedDiscoveredDevices() {
        val trusted = listOf(TrustedDevice(id = "trusted-1", name = "Home Mac"))
        val peers = listOf(
            DiscoveredPeer(
                serviceName = "SYNQ-work",
                deviceIdHint = "work-id-2",
                deviceNameHint = "Work MacBook Pro",
                platformHint = "macos",
                protocolVersionHint = 1,
                host = "192.168.1.70",
                port = 42458
            )
        )

        val result = filterNearby(peers, trusted)
        assertEquals(1, result.size)
        assertEquals("Work MacBook Pro", result.first().deviceNameHint)
    }
}

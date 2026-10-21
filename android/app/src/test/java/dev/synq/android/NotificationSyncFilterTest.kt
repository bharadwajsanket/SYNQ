package dev.synq.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSyncFilterTest {

    private val ownPackage = "dev.synq.android"

    @Test
    fun masterToggleDisabledSuppressesAllNotificationForwarding() {
        val disabledPackages = emptySet<String>()

        val shouldForward = shouldForwardNotification(
            isPrimaryUser = true,
            isSYNQEnabled = true,
            isMasterNotificationsEnabled = false,
            ownPackageName = ownPackage,
            notificationPackageName = "com.whatsapp",
            disabledPackages = disabledPackages,
        )

        assertFalse("Forwarding must be blocked when master notification switch is disabled", shouldForward)

        val registry = ForwardedNotificationRegistry()
        registry.record("id-1", "sys-1", "com.whatsapp")
        registry.record("id-2", "sys-2", "com.slack")
        val removed = registry.removeAll()

        assertEquals(listOf("id-1", "id-2"), removed)
        assertNull(registry.systemKey("id-1"))
        assertNull(registry.systemKey("id-2"))
    }

    @Test
    fun individualPackageDisabledSuppressesForwarding() {
        val disabledPackages = setOf("com.whatsapp", "com.instagram.android")

        val whatsappAllowed = shouldForwardNotification(
            isPrimaryUser = true,
            isSYNQEnabled = true,
            isMasterNotificationsEnabled = true,
            ownPackageName = ownPackage,
            notificationPackageName = "com.whatsapp",
            disabledPackages = disabledPackages,
        )

        assertFalse("Forwarding must be blocked for an individually disabled package", whatsappAllowed)

        val registry = ForwardedNotificationRegistry()
        registry.record("wa-1", "sys-wa-1", "com.whatsapp")
        registry.record("slack-1", "sys-slack-1", "com.slack")
        registry.record("wa-2", "sys-wa-2", "com.whatsapp")

        val removedIds = registry.removePackage("com.whatsapp")
        assertEquals(listOf("wa-1", "wa-2"), removedIds)
        assertNull(registry.systemKey("wa-1"))
        assertNull(registry.systemKey("wa-2"))
        assertEquals("sys-slack-1", registry.systemKey("slack-1"))
    }

    @Test
    fun individualPackageEnabledPermitsForwarding() {
        val disabledPackages = setOf("com.facebook.katana")

        val slackAllowed = shouldForwardNotification(
            isPrimaryUser = true,
            isSYNQEnabled = true,
            isMasterNotificationsEnabled = true,
            ownPackageName = ownPackage,
            notificationPackageName = "com.slack",
            disabledPackages = disabledPackages,
        )

        assertTrue("Forwarding must be permitted when package is not disabled", slackAllowed)
    }

    @Test
    fun synqSelfNotificationIsExcludedToPreventLoops() {
        val shouldForwardSelf = shouldForwardNotification(
            isPrimaryUser = true,
            isSYNQEnabled = true,
            isMasterNotificationsEnabled = true,
            ownPackageName = ownPackage,
            notificationPackageName = ownPackage,
            disabledPackages = emptySet(),
        )

        assertFalse("SYNQ's own package notifications must always be excluded to avoid loops", shouldForwardSelf)
    }

    @Test
    fun defaultBehaviorForPreviouslyUnseenPackagesIsEnabled() {
        val disabledPackages = setOf("com.old.app")
        val unseenPackage = "org.telegram.messenger"

        val unseenAllowed = shouldForwardNotification(
            isPrimaryUser = true,
            isSYNQEnabled = true,
            isMasterNotificationsEnabled = true,
            ownPackageName = ownPackage,
            notificationPackageName = unseenPackage,
            disabledPackages = disabledPackages,
        )

        assertTrue("Unseen packages must default to enabled forwarding", unseenAllowed)
    }

    @Test
    fun removedPackageHandlingPreservesRegistryIntegrity() {
        val registry = ForwardedNotificationRegistry()
        registry.record("id-telegram", "sys-telegram", "org.telegram.messenger")
        registry.record("id-signal", "sys-signal", "org.thoughtcrime.securesms")

        // Package uninstalled: remove its forwarded notifications
        val removed = registry.removePackage("org.telegram.messenger")
        assertEquals(listOf("id-telegram"), removed)
        assertNull(registry.systemKey("id-telegram"))
        assertEquals("sys-signal", registry.systemKey("id-signal"))

        // Removing a package that does not exist or was already removed returns empty list safely
        val nonExistentRemoved = registry.removePackage("com.removed.unknown")
        assertTrue(nonExistentRemoved.isEmpty())

        // Remaining package still intact
        assertEquals("sys-signal", registry.systemKey("id-signal"))
    }

    @Test
    fun notificationActionBehaviorRemainsIntactAndTargeted() {
        val registry = ForwardedNotificationRegistry()
        val notificationId = notificationToken("0|com.whatsapp|100|tag|10001")
        val systemKey = "0|com.whatsapp|100|tag|10001"
        registry.record(notificationId, systemKey, "com.whatsapp")

        // Verify systemKey lookup correctly resolves the original Android notification key
        assertEquals(systemKey, registry.systemKey(notificationId))

        // Verify action tokens are distinct per index and deterministic
        val action0 = notificationActionToken(notificationId, 0)
        val action1 = notificationActionToken(notificationId, 1)
        val action0Again = notificationActionToken(notificationId, 0)

        assertEquals("Action token must be deterministic", action0, action0Again)
        assertTrue("Different action indices must produce distinct tokens", action0 != action1)

        // Dismissal targets the exact system key
        val removedId = registry.removeSystemKey(systemKey)
        assertEquals(notificationId, removedId)
        assertNull("Notification must be cleared from registry after dismissal", registry.systemKey(notificationId))
    }
}

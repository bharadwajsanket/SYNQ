package dev.synq.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SYNQSettingsTest {
    @Test
    fun globalSwitchOverridesPerDeviceSwitch() {
        assertFalse(effectiveFeatureEnabled(globalEnabled = false, deviceEnabled = true))
        assertFalse(effectiveFeatureEnabled(globalEnabled = false, deviceEnabled = null))
    }

    @Test
    fun deviceSwitchOverridesEnabledGlobalDefault() {
        assertFalse(effectiveFeatureEnabled(globalEnabled = true, deviceEnabled = false))
        assertTrue(effectiveFeatureEnabled(globalEnabled = true, deviceEnabled = true))
        assertTrue(effectiveFeatureEnabled(globalEnabled = true, deviceEnabled = null))
    }

    @Test
    fun featureRequiresBothDevicesToOfferIt() {
        assertTrue(effectiveFeatureAvailable(localEnabled = true, remoteEnabled = true))
        assertFalse(effectiveFeatureAvailable(localEnabled = true, remoteEnabled = false))
        assertFalse(effectiveFeatureAvailable(localEnabled = false, remoteEnabled = true))
    }

    @Test
    fun newerFeaturesAreOffForLegacyPeers() {
        assertFalse(featureEnabledByLegacyPeer(SYNQFeature.CALLS))
        assertFalse(featureEnabledByLegacyPeer(SYNQFeature.PING))
        assertTrue(featureEnabledByLegacyPeer(SYNQFeature.BATTERY))
    }

    @Test
    fun quietModeStateMaintainsDefaults() {
        val defaultState = SYNQSettingsState(
            deviceName = "Android",
            globalFeatures = emptyMap(),
            deviceFeatures = emptyMap(),
            notificationApplications = emptyMap(),
            disabledNotificationPackages = emptySet(),
            directCallsEnabled = false,
        )
        assertFalse(defaultState.quietModeEnabled)
        org.junit.Assert.assertEquals(0L, defaultState.quietModeUpdatedAt)
    }

    @Test
    fun quietModeCanBeToggledWithTimestamp() {
        val state = SYNQSettingsState(
            deviceName = "Android",
            globalFeatures = emptyMap(),
            deviceFeatures = emptyMap(),
            notificationApplications = emptyMap(),
            disabledNotificationPackages = emptySet(),
            directCallsEnabled = false,
            quietModeEnabled = true,
            quietModeUpdatedAt = 12345L,
        )
        assertTrue(state.quietModeEnabled)
        org.junit.Assert.assertEquals(12345L, state.quietModeUpdatedAt)
    }
}

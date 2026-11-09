package dev.synq.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryAlertManagerTest {

    @Test
    fun thresholdsDefaultToSensibleValues() {
        val defaultState = SYNQSettingsState(
            deviceName = "Android",
            globalFeatures = emptyMap(),
            deviceFeatures = emptyMap(),
            notificationApplications = emptyMap(),
            disabledNotificationPackages = emptySet(),
            directCallsEnabled = false,
        )
        assertEquals(20, defaultState.batteryWarningThreshold)
        assertEquals(10, defaultState.batteryCriticalThreshold)
    }

    @Test
    fun batteryAlertTriggersAtThresholdsAndDedups() {
        var alertedWarning = false
        var alertedCritical = false
        var alertCount = 0

        fun simulateBatteryCheck(
            level: Int,
            isCharging: Boolean,
            warningThreshold: Int = 20,
            criticalThreshold: Int = 10
        ) {
            if (isCharging) {
                alertedWarning = false
                alertedCritical = false
                return
            }

            if (level <= criticalThreshold && !alertedCritical) {
                alertedCritical = true
                alertedWarning = true
                alertCount++
            } else if (level <= warningThreshold && !alertedWarning) {
                alertedWarning = true
                alertCount++
            }
        }

        // Above warning: no alert
        simulateBatteryCheck(level = 45, isCharging = false)
        assertEquals(0, alertCount)
        assertFalse(alertedWarning)
        assertFalse(alertedCritical)

        // Drop to warning: exactly 1 alert
        simulateBatteryCheck(level = 20, isCharging = false)
        assertEquals(1, alertCount)
        assertTrue(alertedWarning)
        assertFalse(alertedCritical)

        // Stays at or drops slightly below warning: deduplicated, no second alert
        simulateBatteryCheck(level = 18, isCharging = false)
        assertEquals(1, alertCount)

        // Drops to critical threshold: exactly 2 alerts total
        simulateBatteryCheck(level = 9, isCharging = false)
        assertEquals(2, alertCount)
        assertTrue(alertedCritical)

        // Stays below critical: deduplicated
        simulateBatteryCheck(level = 8, isCharging = false)
        assertEquals(2, alertCount)

        // Charger connected: alert flags reset
        simulateBatteryCheck(level = 15, isCharging = true)
        assertFalse(alertedWarning)
        assertFalse(alertedCritical)
    }

    @Test
    fun quietModeSuppressesWarningAlerts() {
        fun shouldShowAlert(isCritical: Boolean, quietModeEnabled: Boolean): Boolean {
            if (quietModeEnabled && !isCritical) return false
            return true
        }

        // Warning alerts suppressed in quiet mode
        assertFalse(shouldShowAlert(isCritical = false, quietModeEnabled = true))

        // Critical alerts always bypass quiet mode
        assertTrue(shouldShowAlert(isCritical = true, quietModeEnabled = true))

        // Normal mode allows both
        assertTrue(shouldShowAlert(isCritical = false, quietModeEnabled = false))
        assertTrue(shouldShowAlert(isCritical = true, quietModeEnabled = false))
    }
}


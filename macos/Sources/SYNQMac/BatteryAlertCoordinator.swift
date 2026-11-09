import AppKit
import Foundation
import UserNotifications

@MainActor
final class BatteryAlertCoordinator: ObservableObject {
    private weak var pairing: PairingCoordinator?
    private let settings: SYNQSettings

    private var hasAlertedMacWarning = false
    private var hasAlertedMacCritical = false
    private var lastMacLevel: Int?
    private var lastMacWasCharging: Bool = true

    private var hasAlertedPhoneWarning = false
    private var hasAlertedPhoneCritical = false

    private var monitorTimer: Timer?

    init(pairing: PairingCoordinator, settings: SYNQSettings) {
        self.pairing = pairing
        self.settings = settings
        startMonitoring()
    }

    deinit {
        monitorTimer?.invalidate()
    }

    func startMonitoring() {
        monitorTimer?.invalidate()
        checkMacBattery()
        monitorTimer = Timer.scheduledTimer(withTimeInterval: 60.0, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.checkMacBattery()
            }
        }
    }

    func checkMacBattery() {
        guard let status = currentMacBatteryStatus() else { return }
        let level = status.level
        let isCharging = status.isCharging

        // Reset tracking on charging or significant recovery
        if isCharging {
            hasAlertedMacWarning = false
            hasAlertedMacCritical = false
        } else if let last = lastMacLevel, level >= last + 5 {
            hasAlertedMacWarning = false
            hasAlertedMacCritical = false
        }

        lastMacLevel = level
        lastMacWasCharging = isCharging

        guard !isCharging else { return }

        let criticalThreshold = settings.batteryAlertCriticalThreshold
        let warningThreshold = settings.batteryAlertWarningThreshold

        if level <= criticalThreshold && !hasAlertedMacCritical {
            hasAlertedMacCritical = true
            hasAlertedMacWarning = true
            sendMacBatteryLowAlert(level: level, isCritical: true)
        } else if level <= warningThreshold && !hasAlertedMacWarning {
            hasAlertedMacWarning = true
            sendMacBatteryLowAlert(level: level, isCritical: false)
        }
    }

    private func sendMacBatteryLowAlert(level: Int, isCritical: Bool) {
        guard let pairing = pairing, pairing.isConnected else { return }
        pairing.sendBatteryLowAlert(type: "mac_low", level: level, isCritical: isCritical)
    }

    func handlePhoneBatteryUpdate(level: Int, isCharging: Bool) {
        if isCharging || level > settings.batteryAlertWarningThreshold + 5 {
            hasAlertedPhoneWarning = false
            hasAlertedPhoneCritical = false
        }
    }

    func handlePhoneBatteryLowAlert(level: Int, isCritical: Bool, deviceName: String) {
        if isCritical {
            if hasAlertedPhoneCritical { return }
            hasAlertedPhoneCritical = true
            hasAlertedPhoneWarning = true
        } else {
            if hasAlertedPhoneWarning { return }
            hasAlertedPhoneWarning = true
        }

        if settings.quietModeEnabled && !isCritical {
            return
        }

        let content = UNMutableNotificationContent()
        content.title = isCritical ? "\(deviceName) Battery Critical" : "\(deviceName) Battery Low"
        content.body = isCritical
            ? "\(deviceName) battery is down to \(level)%. Connect a charger soon."
            : "\(deviceName) battery is at \(level)%."
        content.sound = settings.quietModeEnabled ? nil : .default

        let request = UNNotificationRequest(
            identifier: "synq.battery.low.\(isCritical ? "critical" : "warning")",
            content: content,
            trigger: nil
        )

        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                NSLog("Failed to post battery low notification: %@", error.localizedDescription)
            }
        }
    }
}


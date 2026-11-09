import XCTest
@testable import SYNQMac

@MainActor
final class BatteryAlertCoordinatorTests: XCTestCase {

    func testBatteryAlertThresholdDefaults() {
        let settings = SYNQSettings()
        XCTAssertEqual(settings.batteryAlertWarningThreshold, 20)
        XCTAssertEqual(settings.batteryAlertCriticalThreshold, 10)
    }

    func testBatteryAlertThresholdClamping() {
        let settings = SYNQSettings()
        settings.setBatteryAlertWarningThreshold(65)
        XCTAssertEqual(settings.batteryAlertWarningThreshold, 50)

        settings.setBatteryAlertWarningThreshold(2)
        XCTAssertEqual(settings.batteryAlertWarningThreshold, 5)

        settings.setBatteryAlertCriticalThreshold(35)
        XCTAssertEqual(settings.batteryAlertCriticalThreshold, 25)

        settings.setBatteryAlertCriticalThreshold(0)
        XCTAssertEqual(settings.batteryAlertCriticalThreshold, 1)

        // Restore defaults
        settings.setBatteryAlertWarningThreshold(20)
        settings.setBatteryAlertCriticalThreshold(10)
    }

    func testCallDurationFormatting() {
        func formattedDuration(_ time: TimeInterval) -> String {
            let totalSeconds = Int(time)
            let hours = totalSeconds / 3600
            let minutes = (totalSeconds % 3600) / 60
            let seconds = totalSeconds % 60
            if hours > 0 {
                return String(format: "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                return String(format: "%02d:%02d", minutes, seconds)
            }
        }

        XCTAssertEqual(formattedDuration(0), "00:00")
        XCTAssertEqual(formattedDuration(45), "00:45")
        XCTAssertEqual(formattedDuration(65), "01:05")
        XCTAssertEqual(formattedDuration(600), "10:00")
        XCTAssertEqual(formattedDuration(3665), "1:01:05")
    }

    func testRemoteCallStatusWithConnectedAt() {
        let now = Date()
        let call = RemoteCallStatus(
            notificationID: "notif-123",
            deviceID: "dev-456",
            applicationName: "Phone",
            caller: "Mom",
            detail: "Ongoing call",
            type: "ongoing",
            actions: [],
            connectedAt: now
        )

        XCTAssertEqual(call.connectedAt, now)
        XCTAssertEqual(call.type, "ongoing")
        XCTAssertEqual(call.caller, "Mom")
    }
}

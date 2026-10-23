import XCTest
@testable import SYNQMac

final class NotificationCallTests: XCTestCase {
    func testAcceptsOnlyKnownCallTypes() {
        XCTAssertEqual(normalizedRemoteCallType("incoming"), "incoming")
        XCTAssertEqual(normalizedRemoteCallType("ongoing"), "ongoing")
        XCTAssertEqual(normalizedRemoteCallType("screening"), "screening")
        XCTAssertEqual(normalizedRemoteCallType("unknown"), "unknown")
        XCTAssertNil(normalizedRemoteCallType("decline"))
        XCTAssertNil(normalizedRemoteCallType(nil))
    }

    func testProvidesSafeCallTitles() {
        XCTAssertEqual(remoteCallStatusTitle("incoming"), "Incoming call")
        XCTAssertEqual(remoteCallStatusTitle("ongoing"), "Call in progress")
        XCTAssertEqual(remoteCallStatusTitle("screening"), "Call screening")
        XCTAssertEqual(remoteCallStatusTitle("unknown"), "Phone call")
    }

    func testRemovesStaleGenericAndroidCallDescriptionAfterStateTransition() {
        XCTAssertEqual(remoteCallDetail("Incoming call", type: "ongoing"), "")
        XCTAssertEqual(remoteCallDetail("Call in progress", type: "ongoing"), "")
        XCTAssertEqual(remoteCallDetail("  Verified caller  ", type: "incoming"), "Verified caller")
    }

    func testCallOverlayReplacesOnlySystemCallNotifications() {
        XCTAssertTrue(shouldUseSystemNotification(callType: nil))
        XCTAssertFalse(shouldUseSystemNotification(callType: "incoming"))
        XCTAssertFalse(shouldUseSystemNotification(callType: "ongoing"))
    }

    func testCallOverlayPlacesAnswerBeforeDecline() {
        let decline = RemoteCallAction(id: "decline", title: "Decline")
        let answer = RemoteCallAction(id: "answer", title: "Answer")
        XCTAssertEqual(orderedCallActions([decline, answer]), [answer, decline])

        let hangUp = RemoteCallAction(id: "hang", title: "Hang Up")
        XCTAssertEqual(orderedCallActions([hangUp]), [hangUp])
        XCTAssertEqual(orderedCallActions([decline, answer, hangUp]), [answer, hangUp, decline])
    }

    func testRecognizesCallEndingActionTitles() {
        XCTAssertTrue(isCallEndingActionTitle("Decline"))
        XCTAssertTrue(isCallEndingActionTitle("Hang Up"))
        XCTAssertTrue(isCallEndingActionTitle("Reject"))
        XCTAssertTrue(isCallEndingActionTitle("End call"))
        XCTAssertTrue(isCallEndingActionTitle("HANG UP"))
        XCTAssertFalse(isCallEndingActionTitle("Answer"))
        XCTAssertFalse(isCallEndingActionTitle("Accept"))
        XCTAssertFalse(isCallEndingActionTitle("Reply with message"))
    }

    func testQuietModeSuppressionRules() {
        // Under Quiet Mode, regular notifications (callType == nil) are suppressed from mirroring
        XCTAssertTrue(shouldSuppressNotificationInQuietMode(quietModeEnabled: true, callType: nil))
        // When Quiet Mode is off, regular notifications are NOT suppressed
        XCTAssertFalse(shouldSuppressNotificationInQuietMode(quietModeEnabled: false, callType: nil))

        // Phone calls (incoming, ongoing, screening) must NEVER be suppressed by Quiet Mode
        XCTAssertFalse(shouldSuppressNotificationInQuietMode(quietModeEnabled: true, callType: "incoming"))
        XCTAssertFalse(shouldSuppressNotificationInQuietMode(quietModeEnabled: true, callType: "ongoing"))
        XCTAssertFalse(shouldSuppressNotificationInQuietMode(quietModeEnabled: true, callType: "screening"))
        XCTAssertFalse(shouldSuppressNotificationInQuietMode(quietModeEnabled: true, callType: "unknown"))

        XCTAssertFalse(shouldSuppressNotificationInQuietMode(quietModeEnabled: false, callType: "incoming"))
    }
}

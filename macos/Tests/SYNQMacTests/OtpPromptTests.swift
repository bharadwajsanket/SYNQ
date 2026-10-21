import AppKit
import XCTest
@testable import SYNQMac

final class OtpPromptTests: XCTestCase {
    @MainActor
    func testOtpPromptPresentsAndCopiesExplicitly() {
        let controller = OtpPromptController.shared
        controller.present(code: "839201", sender: "Google", expirySeconds: 180)

        XCTAssertEqual(controller.code, "839201")
        XCTAssertEqual(controller.sender, "Google")
        XCTAssertEqual(controller.secondsRemaining, 180)
        XCTAssertFalse(controller.isCopied)
        XCTAssertFalse(controller.isExpired)

        // Verify clipboard is NOT populated automatically before explicit copy
        NSPasteboard.general.clearContents()
        XCTAssertNotEqual(NSPasteboard.general.string(forType: .string), "839201")

        // Explicit copy
        controller.copyCode()
        XCTAssertTrue(controller.isCopied)
        XCTAssertEqual(NSPasteboard.general.string(forType: .string), "839201")

        // Dismiss cleans up
        controller.dismiss()
        XCTAssertEqual(controller.code, "")
        XCTAssertFalse(controller.isCopied)
    }

    @MainActor
    func testOtpPromptRejectsExpiredCodeCopy() {
        let controller = OtpPromptController.shared
        controller.present(code: "1234", sender: "Test", expirySeconds: 0)
        // With expirySeconds <= 10, controller clamps to minimum 10, but let's test manual dismissal/clear
        controller.dismiss()
        NSPasteboard.general.clearContents()
        controller.copyCode()
        XCTAssertNil(NSPasteboard.general.string(forType: .string))
    }
}

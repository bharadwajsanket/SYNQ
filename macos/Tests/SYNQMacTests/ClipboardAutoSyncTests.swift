import AppKit
import XCTest
@testable import SYNQMac

final class ClipboardAutoSyncTests: XCTestCase {
    private var testPasteboard: NSPasteboard!

    override func setUp() {
        super.setUp()
        testPasteboard = NSPasteboard.withUniqueName()
    }

    override func tearDown() {
        testPasteboard.releaseGlobally()
        testPasteboard = nil
        super.tearDown()
    }

    func testDisconnectedSessionDoesNotSend() {
        testPasteboard.clearContents()
        testPasteboard.setString("Hello SYNQ", forType: .string)

        let result = evaluateClipboardAutoSync(
            pasteboard: testPasteboard,
            autoSyncEnabled: true,
            featureAvailable: true,
            isConnected: false,
            lastChangeCount: 0,
            lastReceivedChangeCount: -1,
            lastAutoSentChangeCount: -1,
            lastSentText: nil,
            lastSentTime: 0,
            currentTime: 100
        )

        XCTAssertFalse(result.decision.shouldSend)
        XCTAssertEqual(result.decision.reason, "Not connected")
        XCTAssertNil(result.text)
    }

    func testDisabledAutoSyncSettingDoesNotSend() {
        testPasteboard.clearContents()
        testPasteboard.setString("Hello SYNQ", forType: .string)

        let result = evaluateClipboardAutoSync(
            pasteboard: testPasteboard,
            autoSyncEnabled: false,
            featureAvailable: true,
            isConnected: true,
            lastChangeCount: 0,
            lastReceivedChangeCount: -1,
            lastAutoSentChangeCount: -1,
            lastSentText: nil,
            lastSentTime: 0,
            currentTime: 100
        )

        XCTAssertFalse(result.decision.shouldSend)
        XCTAssertEqual(result.decision.reason, "Auto-sync disabled")
        XCTAssertNil(result.text)
    }

    func testDisabledFeatureDoesNotSend() {
        testPasteboard.clearContents()
        testPasteboard.setString("Hello SYNQ", forType: .string)

        let result = evaluateClipboardAutoSync(
            pasteboard: testPasteboard,
            autoSyncEnabled: true,
            featureAvailable: false,
            isConnected: true,
            lastChangeCount: 0,
            lastReceivedChangeCount: -1,
            lastAutoSentChangeCount: -1,
            lastSentText: nil,
            lastSentTime: 0,
            currentTime: 100
        )

        XCTAssertFalse(result.decision.shouldSend)
        XCTAssertEqual(result.decision.reason, "Clipboard feature unavailable")
        XCTAssertNil(result.text)
    }

    func testUnchangedChangeCountDoesNotSend() {
        testPasteboard.clearContents()
        testPasteboard.setString("Hello SYNQ", forType: .string)
        let currentCount = testPasteboard.changeCount

        let result = evaluateClipboardAutoSync(
            pasteboard: testPasteboard,
            autoSyncEnabled: true,
            featureAvailable: true,
            isConnected: true,
            lastChangeCount: currentCount,
            lastReceivedChangeCount: -1,
            lastAutoSentChangeCount: -1,
            lastSentText: nil,
            lastSentTime: 0,
            currentTime: 100
        )

        XCTAssertFalse(result.decision.shouldSend)
        XCTAssertEqual(result.decision.reason, "Change count unchanged")
        XCTAssertNil(result.text)
    }

    func testEchoLoopPreventionSkipsReceivedFromAndroid() {
        testPasteboard.clearContents()
        testPasteboard.setString("From Android", forType: .string)
        let currentCount = testPasteboard.changeCount

        let result = evaluateClipboardAutoSync(
            pasteboard: testPasteboard,
            autoSyncEnabled: true,
            featureAvailable: true,
            isConnected: true,
            lastChangeCount: currentCount - 1,
            lastReceivedChangeCount: currentCount,
            lastAutoSentChangeCount: -1,
            lastSentText: nil,
            lastSentTime: 0,
            currentTime: 100
        )

        XCTAssertFalse(result.decision.shouldSend)
        XCTAssertEqual(result.decision.reason, "Echo loop prevented: received from Android")
        XCTAssertNil(result.text)
    }

    func testAlreadySentChangeCountDoesNotSendDuplicate() {
        testPasteboard.clearContents()
        testPasteboard.setString("Hello again", forType: .string)
        let currentCount = testPasteboard.changeCount

        let result = evaluateClipboardAutoSync(
            pasteboard: testPasteboard,
            autoSyncEnabled: true,
            featureAvailable: true,
            isConnected: true,
            lastChangeCount: currentCount - 1,
            lastReceivedChangeCount: -1,
            lastAutoSentChangeCount: currentCount,
            lastSentText: nil,
            lastSentTime: 0,
            currentTime: 100
        )

        XCTAssertFalse(result.decision.shouldSend)
        XCTAssertEqual(result.decision.reason, "Already auto-sent for this change count")
        XCTAssertNil(result.text)
    }

    func testDuplicateTextWithinSuppressionWindowSkipped() {
        testPasteboard.clearContents()
        testPasteboard.setString("Repeated copy", forType: .string)
        let currentCount = testPasteboard.changeCount

        let result = evaluateClipboardAutoSync(
            pasteboard: testPasteboard,
            autoSyncEnabled: true,
            featureAvailable: true,
            isConnected: true,
            lastChangeCount: currentCount - 1,
            lastReceivedChangeCount: -1,
            lastAutoSentChangeCount: -1,
            lastSentText: "Repeated copy",
            lastSentTime: 98.0,
            currentTime: 100.0 // 2 seconds later (< 5s threshold)
        )

        XCTAssertFalse(result.decision.shouldSend)
        XCTAssertEqual(result.decision.reason, "Duplicate text within suppression window")
    }

    func testDuplicateTextAfterSuppressionWindowSends() {
        testPasteboard.clearContents()
        testPasteboard.setString("Repeated copy", forType: .string)
        let currentCount = testPasteboard.changeCount

        let result = evaluateClipboardAutoSync(
            pasteboard: testPasteboard,
            autoSyncEnabled: true,
            featureAvailable: true,
            isConnected: true,
            lastChangeCount: currentCount - 1,
            lastReceivedChangeCount: -1,
            lastAutoSentChangeCount: -1,
            lastSentText: "Repeated copy",
            lastSentTime: 90.0,
            currentTime: 100.0 // 10 seconds later (> 5s threshold)
        )

        XCTAssertTrue(result.decision.shouldSend)
        XCTAssertEqual(result.text, "Repeated copy")
    }

    func testEmptyContentDoesNotSend() {
        testPasteboard.clearContents()
        let currentCount = testPasteboard.changeCount

        let result = evaluateClipboardAutoSync(
            pasteboard: testPasteboard,
            autoSyncEnabled: true,
            featureAvailable: true,
            isConnected: true,
            lastChangeCount: currentCount - 1,
            lastReceivedChangeCount: -1,
            lastAutoSentChangeCount: -1,
            lastSentText: nil,
            lastSentTime: 0,
            currentTime: 100
        )

        XCTAssertFalse(result.decision.shouldSend)
        XCTAssertEqual(result.decision.reason, "No text content")
    }

    func testOversizedContentDoesNotSend() {
        testPasteboard.clearContents()
        let oversized = String(repeating: "A", count: maximumClipboardContentBytes + 1)
        testPasteboard.setString(oversized, forType: .string)
        let currentCount = testPasteboard.changeCount

        let result = evaluateClipboardAutoSync(
            pasteboard: testPasteboard,
            autoSyncEnabled: true,
            featureAvailable: true,
            isConnected: true,
            lastChangeCount: currentCount - 1,
            lastReceivedChangeCount: -1,
            lastAutoSentChangeCount: -1,
            lastSentText: nil,
            lastSentTime: 0,
            currentTime: 100
        )

        XCTAssertFalse(result.decision.shouldSend)
        XCTAssertEqual(result.decision.reason, "Content exceeds size limit")
    }

    func testConcealedTypeSkipped() {
        testPasteboard.clearContents()
        let secret = "super-secret-password-12345"
        testPasteboard.setString(secret, forType: .string)
        // Add concealed marker
        testPasteboard.setData(Data(), forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType"))

        XCTAssertTrue(pasteboardContainsConcealedType(testPasteboard))

        let result = evaluateClipboardAutoSync(
            pasteboard: testPasteboard,
            autoSyncEnabled: true,
            featureAvailable: true,
            isConnected: true,
            lastChangeCount: testPasteboard.changeCount - 1,
            lastReceivedChangeCount: -1,
            lastAutoSentChangeCount: -1,
            lastSentText: nil,
            lastSentTime: 0,
            currentTime: 100
        )

        XCTAssertFalse(result.decision.shouldSend)
        XCTAssertEqual(result.decision.reason, "Concealed or confidential pasteboard item")
        XCTAssertNil(result.text)
    }

    func testOnePasswordConcealedMarkerSkipped() {
        testPasteboard.clearContents()
        testPasteboard.setString("1Password-secret", forType: .string)
        testPasteboard.setData(Data(), forType: NSPasteboard.PasteboardType("com.agilebits.onepassword"))

        XCTAssertTrue(pasteboardContainsConcealedType(testPasteboard))

        let result = evaluateClipboardAutoSync(
            pasteboard: testPasteboard,
            autoSyncEnabled: true,
            featureAvailable: true,
            isConnected: true,
            lastChangeCount: testPasteboard.changeCount - 1,
            lastReceivedChangeCount: -1,
            lastAutoSentChangeCount: -1,
            lastSentText: nil,
            lastSentTime: 0,
            currentTime: 100
        )

        XCTAssertFalse(result.decision.shouldSend)
        XCTAssertEqual(result.decision.reason, "Concealed or confidential pasteboard item")
    }

    func testValidContentTriggersSendWithRichHTML() {
        testPasteboard.clearContents()
        testPasteboard.setString("Hello World", forType: .string)
        testPasteboard.setData(Data("<b>Hello World</b>".utf8), forType: .html)
        let currentCount = testPasteboard.changeCount

        let result = evaluateClipboardAutoSync(
            pasteboard: testPasteboard,
            autoSyncEnabled: true,
            featureAvailable: true,
            isConnected: true,
            lastChangeCount: currentCount - 1,
            lastReceivedChangeCount: -1,
            lastAutoSentChangeCount: -1,
            lastSentText: nil,
            lastSentTime: 0,
            currentTime: 100
        )

        XCTAssertTrue(result.decision.shouldSend)
        XCTAssertEqual(result.text, "Hello World")
        XCTAssertEqual(result.html, "<b>Hello World</b>")
        XCTAssertEqual(result.changeCount, currentCount)
    }
}

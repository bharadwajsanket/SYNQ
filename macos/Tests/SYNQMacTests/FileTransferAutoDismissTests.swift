import AppKit
import XCTest
@testable import SYNQMac

final class FileTransferAutoDismissTests: XCTestCase {
    @MainActor
    func testSuccessfulTransferTriggersCountdownAndDismisses() async {
        var didDismiss = false
        let manager = FileTransferAutoDismissManager(
            dismissDelay: 2.0,
            tickDurationNanoseconds: 1_000_000, // 1ms per tick for instantaneous unit test
            onDismiss: { didDismiss = true }
        )

        let successfulTransfer = FileTransferRow(
            id: "tx-1",
            name: "report.pdf",
            status: "Saved report.pdf to /Users/test/Downloads",
            active: false
        )

        manager.evaluateDismissal(transfers: [successfulTransfer], presentedForActiveTransfer: true)

        XCTAssertEqual(manager.activeCountdownTransferID, "tx-1")
        XCTAssertEqual(manager.secondsRemaining, 2)
        XCTAssertFalse(didDismiss)

        // Wait for 1ms ticks to complete
        try? await Task.sleep(nanoseconds: 30_000_000)

        XCTAssertTrue(didDismiss)
        XCTAssertEqual(manager.secondsRemaining, 0)
    }

    @MainActor
    func testActiveTransferDoesNotTriggerCountdown() {
        var didDismiss = false
        let manager = FileTransferAutoDismissManager(
            dismissDelay: 2.0,
            tickDurationNanoseconds: 1_000_000,
            onDismiss: { didDismiss = true }
        )

        let activeTransfer = FileTransferRow(
            id: "tx-2",
            name: "video.mp4",
            status: "Sending video.mp4…",
            active: true
        )

        manager.evaluateDismissal(transfers: [activeTransfer], presentedForActiveTransfer: true)

        XCTAssertNil(manager.activeCountdownTransferID)
        XCTAssertNil(manager.secondsRemaining)
        XCTAssertFalse(didDismiss)
    }

    @MainActor
    func testFailedOrRetryableTransferDoesNotAutoDismiss() {
        var didDismiss = false
        let manager = FileTransferAutoDismissManager(
            dismissDelay: 2.0,
            tickDurationNanoseconds: 1_000_000,
            onDismiss: { didDismiss = true }
        )

        let failedTransfer = FileTransferRow(
            id: "tx-3",
            name: "document.pdf",
            status: "File transfer failed",
            active: false,
            retryable: true
        )

        manager.evaluateDismissal(transfers: [failedTransfer], presentedForActiveTransfer: true)

        XCTAssertNil(manager.activeCountdownTransferID)
        XCTAssertNil(manager.secondsRemaining)
        XCTAssertFalse(didDismiss)
    }

    @MainActor
    func testUserInteractionCancelsCountdown() async {
        var didDismiss = false
        let manager = FileTransferAutoDismissManager(
            dismissDelay: 2.0,
            tickDurationNanoseconds: 50_000_000,
            onDismiss: { didDismiss = true }
        )

        let successfulTransfer = FileTransferRow(
            id: "tx-4",
            name: "photo.jpg",
            status: "photo.jpg saved on Android",
            active: false
        )

        manager.evaluateDismissal(transfers: [successfulTransfer], presentedForActiveTransfer: true)
        XCTAssertEqual(manager.activeCountdownTransferID, "tx-4")

        // User interacts with window
        manager.userDidInteract()

        XCTAssertTrue(manager.userInteracted)
        XCTAssertNil(manager.activeCountdownTransferID)
        XCTAssertNil(manager.secondsRemaining)

        try? await Task.sleep(nanoseconds: 150_000_000)
        XCTAssertFalse(didDismiss)
    }

    @MainActor
    func testNewTransferCancelsPendingCountdown() async {
        var didDismiss = false
        let manager = FileTransferAutoDismissManager(
            dismissDelay: 2.0,
            tickDurationNanoseconds: 50_000_000,
            onDismiss: { didDismiss = true }
        )

        let firstTransfer = FileTransferRow(
            id: "tx-5",
            name: "file1.txt",
            status: "Saved file1.txt to Downloads",
            active: false
        )

        manager.evaluateDismissal(transfers: [firstTransfer], presentedForActiveTransfer: true)
        XCTAssertEqual(manager.activeCountdownTransferID, "tx-5")

        // New transfer starts
        manager.transferDidBegin()

        XCTAssertNil(manager.activeCountdownTransferID)
        XCTAssertNil(manager.secondsRemaining)

        try? await Task.sleep(nanoseconds: 150_000_000)
        XCTAssertFalse(didDismiss)
    }

    @MainActor
    func testPersistentHistoryViewDoesNotAutoDismiss() {
        var didDismiss = false
        let manager = FileTransferAutoDismissManager(
            dismissDelay: 2.0,
            tickDurationNanoseconds: 1_000_000,
            onDismiss: { didDismiss = true }
        )

        let pastTransfer = FileTransferRow(
            id: "tx-6",
            name: "archive.zip",
            status: "archive.zip saved on Android",
            active: false
        )

        // Window opened by user from menu bar (presentedForActiveTransfer: false)
        manager.evaluateDismissal(transfers: [pastTransfer], presentedForActiveTransfer: false)

        XCTAssertNil(manager.activeCountdownTransferID)
        XCTAssertNil(manager.secondsRemaining)
        XCTAssertFalse(didDismiss)
    }
}

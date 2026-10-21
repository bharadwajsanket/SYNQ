import AppKit
import SwiftUI
import XCTest
@testable import SYNQMac

final class MenuBarPanelLayoutTests: XCTestCase {
    @MainActor func testCompactPanelRejectsStaleTallWindowProposal() {
        let host = NSHostingController(rootView: fixture(height: 120, transfers: 10))
        let natural = host.sizeThatFits(in: NSSize(width: 340, height: 400))
        let stale = host.sizeThatFits(in: NSSize(width: 340, height: 1800))
        XCTAssertEqual(natural.width, 340, accuracy: 0.5)
        XCTAssertLessThan(natural.height, 250)
        XCTAssertEqual(stale.height, natural.height, accuracy: 0.5)
    }

    @MainActor func testPanelShrinksAfterRepeatedConnectionStateChanges() {
        let host = NSHostingController(rootView: fixture(height: 120, transfers: 10))
        let proposal = NSSize(width: 340, height: 1800)
        let compact = host.sizeThatFits(in: proposal)
        for _ in 0..<5 {
            host.rootView = fixture(height: 480, transfers: 10)
            let expanded = host.sizeThatFits(in: proposal)
            XCTAssertGreaterThan(expanded.height, compact.height + 300)
            host.rootView = fixture(height: 120, transfers: 10)
            XCTAssertEqual(host.sizeThatFits(in: proposal).height, compact.height, accuracy: 0.5)
        }
    }

    @MainActor func testTransferHistoryRowDoesNotExpandVertically() {
        let host = NSHostingController(rootView: fixture(height: 120, transfers: 0))
        let proposal = NSSize(width: 340, height: 1800)
        let withoutHistory = host.sizeThatFits(in: proposal)
        host.rootView = fixture(height: 120, transfers: 10)
        let withHistory = host.sizeThatFits(in: proposal)
        XCTAssertGreaterThan(withHistory.height, withoutHistory.height)
        XCTAssertLessThan(withHistory.height - withoutHistory.height, 60)
        host.rootView = fixture(height: 120, transfers: 0)
        XCTAssertEqual(host.sizeThatFits(in: proposal).height, withoutHistory.height, accuracy: 0.5)
    }

    @MainActor func testWindowPinnerAnchorsTopEdgeOnResize() {
        let window = NSWindow(
            contentRect: NSRect(x: 200, y: 500, width: 340, height: 300),
            styleMask: [.borderless],
            backing: .buffered,
            defer: false
        )
        window.orderFront(nil)
        XCTAssertTrue(window.isVisible)
        let initialTop = window.frame.maxY

        MenuBarWindowPinner.shared.pinWindowTop(window)

        // Simulate expansion (content grew taller)
        window.setFrame(NSRect(x: 200, y: 500, width: 340, height: 520), display: false)
        XCTAssertEqual(window.frame.maxY, initialTop, accuracy: 0.5, "Top edge must remain anchored when expanding")
        XCTAssertEqual(window.frame.height, 520, accuracy: 0.5)
        XCTAssertEqual(window.frame.origin.y, initialTop - 520, accuracy: 0.5, "Origin y must expand downward")

        // Simulate contraction (content contracted back to compact)
        window.setFrame(NSRect(x: 200, y: initialTop - 520, width: 340, height: 300), display: false)
        XCTAssertEqual(window.frame.maxY, initialTop, accuracy: 0.5, "Top edge must remain anchored when contracting")
        XCTAssertEqual(window.frame.height, 300, accuracy: 0.5)
        XCTAssertEqual(window.frame.origin.y, initialTop - 300, accuracy: 0.5, "Origin y must contract upward")

        window.orderOut(nil)
    }

    @MainActor func testWindowShrinksToFittedContentAfterPickerCloses() {
        let host = NSHostingController(rootView: fixture(height: 480, transfers: 0))
        let window = NSWindow(
            contentRect: NSRect(x: 200, y: 200, width: 340, height: 800),
            styleMask: [.borderless],
            backing: .buffered,
            defer: false
        )
        window.contentViewController = host
        window.orderFront(nil)
        let pinner = MenuBarWindowPinner()
        pinner.pinWindowTop(window)
        let expandedHeight = host.view.fittingSize.height
        XCTAssertGreaterThan(expandedHeight, 400)

        host.rootView = fixture(height: 120, transfers: 0)
        host.view.layoutSubtreeIfNeeded()
        let compactHeight = host.view.fittingSize.height
        XCTAssertLessThan(compactHeight, expandedHeight - 300)
        pinner.fitWindowToContentNow(window)

        XCTAssertEqual(window.frame.height, compactHeight, accuracy: 1)
        window.orderOut(nil)
    }

    @MainActor private func fixture(height: CGFloat, transfers: Int) -> some View {
        MenuBarPanelSurface {
            VStack(alignment: .leading, spacing: 14) {
                Color.clear.frame(height: height)
                if transfers > 0 { FileTransfersSummaryButton(count: transfers, action: {}) }
            }
            .padding(16)
        }
    }
}

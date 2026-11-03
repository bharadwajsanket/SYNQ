import AppKit
import SwiftUI
import XCTest
@testable import SYNQMac

final class OnboardingWindowTests: XCTestCase {
    private var defaultsSuite: UserDefaults!

    override func setUp() {
        super.setUp()
        defaultsSuite = UserDefaults.standard
    }

    @MainActor
    func testOnboardingWindowShownWhenNotCompleted() {
        let original = defaultsSuite.bool(forKey: "settings.onboarding.completed")
        defer {
            defaultsSuite.set(original, forKey: "settings.onboarding.completed")
        }

        defaultsSuite.set(false, forKey: "settings.onboarding.completed")
        let settings = SYNQSettings()
        XCTAssertFalse(settings.hasCompletedOnboarding)

        let discovery = BonjourDiscovery(deviceName: "TestMac", autoStart: false)
        let pairing = PairingCoordinator(deviceID: "test-id", deviceName: "TestMac", settings: settings, startListenerOnLaunch: false)
        defer { pairing.cancel() }
        let controller = OnboardingWindowController(discovery: discovery, pairing: pairing, settings: settings)

        controller.showIfNeeded()
        defer { controller.close() }

        guard let window = controller.currentWindow else {
            XCTFail("Onboarding window should have been created")
            return
        }

        XCTAssertTrue(controller.isWindowVisible)
        XCTAssertEqual(window.contentView?.frame.width ?? 0, 520, accuracy: 1.0)
        XCTAssertEqual(window.contentView?.frame.height ?? 0, 420, accuracy: 1.0)
    }

    @MainActor
    func testOnboardingWindowNotShownWhenAlreadyCompleted() {
        let original = defaultsSuite.bool(forKey: "settings.onboarding.completed")
        defer {
            defaultsSuite.set(original, forKey: "settings.onboarding.completed")
        }

        defaultsSuite.set(true, forKey: "settings.onboarding.completed")
        let settings = SYNQSettings()
        XCTAssertTrue(settings.hasCompletedOnboarding)

        let discovery = BonjourDiscovery(deviceName: "TestMac", autoStart: false)
        let pairing = PairingCoordinator(deviceID: "test-id", deviceName: "TestMac", settings: settings, startListenerOnLaunch: false)
        defer { pairing.cancel() }
        let controller = OnboardingWindowController(discovery: discovery, pairing: pairing, settings: settings)

        controller.showIfNeeded()
        XCTAssertNil(controller.currentWindow)
        XCTAssertFalse(controller.isWindowVisible)

        controller.show()
        XCTAssertNil(controller.currentWindow)
        XCTAssertFalse(controller.isWindowVisible)
    }

    @MainActor
    func testCompletingOnboardingPersistsAndClosesWindow() {
        let original = defaultsSuite.bool(forKey: "settings.onboarding.completed")
        defer {
            defaultsSuite.set(original, forKey: "settings.onboarding.completed")
        }

        defaultsSuite.set(false, forKey: "settings.onboarding.completed")
        let settings = SYNQSettings()
        XCTAssertFalse(settings.hasCompletedOnboarding)

        let discovery = BonjourDiscovery(deviceName: "TestMac", autoStart: false)
        let pairing = PairingCoordinator(deviceID: "test-id", deviceName: "TestMac", settings: settings, startListenerOnLaunch: false)
        defer { pairing.cancel() }
        let controller = OnboardingWindowController(discovery: discovery, pairing: pairing, settings: settings)

        controller.showIfNeeded()
        XCTAssertNotNil(controller.currentWindow)

        controller.completeOnboarding()
        XCTAssertTrue(settings.hasCompletedOnboarding)
        XCTAssertTrue(defaultsSuite.bool(forKey: "settings.onboarding.completed"))
        XCTAssertNil(controller.currentWindow)
        XCTAssertFalse(controller.isWindowVisible)

        // Calling showIfNeeded() again should do nothing
        controller.showIfNeeded()
        XCTAssertNil(controller.currentWindow)
    }

    @MainActor
    func testDisconnectDoesNotResetOnboarding() {
        let original = defaultsSuite.bool(forKey: "settings.onboarding.completed")
        defer {
            defaultsSuite.set(original, forKey: "settings.onboarding.completed")
        }

        defaultsSuite.set(false, forKey: "settings.onboarding.completed")
        let settings = SYNQSettings()
        let discovery = BonjourDiscovery(deviceName: "TestMac", autoStart: false)
        let pairing = PairingCoordinator(deviceID: "test-id", deviceName: "TestMac", settings: settings, startListenerOnLaunch: false)
        defer { pairing.cancel() }
        let controller = OnboardingWindowController(discovery: discovery, pairing: pairing, settings: settings)

        controller.completeOnboarding()
        XCTAssertTrue(settings.hasCompletedOnboarding)

        // Disconnecting / resetting pairing state
        pairing.cancel()
        XCTAssertEqual(pairing.state, .idle)

        // Must remain completed
        XCTAssertTrue(settings.hasCompletedOnboarding)
        controller.showIfNeeded()
        XCTAssertNil(controller.currentWindow)
    }

    @MainActor
    func testAnimatedWelcomeViewFitsInWindowBounds() {
        let view = AnimatedWelcomeView(onGetStarted: {}, onPairWithQR: {})
        let hosting = NSHostingController(rootView: view)
        let size = hosting.sizeThatFits(in: NSSize(width: 520, height: 420))
        XCTAssertLessThanOrEqual(size.height, 420, "AnimatedWelcomeView must fit within window height without requiring scrolling")
    }
}

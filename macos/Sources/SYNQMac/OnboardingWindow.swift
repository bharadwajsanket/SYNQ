import AppKit
import SwiftUI
import UserNotifications

@MainActor
final class OnboardingWindowController: NSObject, NSWindowDelegate {
    private let discovery: BonjourDiscovery
    private let pairing: PairingCoordinator
    private let settings: SYNQSettings
    private var window: NSWindow?
    private var isCompleted = false

    var currentWindow: NSWindow? { window }
    var isWindowVisible: Bool { window?.isVisible == true }

    init(discovery: BonjourDiscovery, pairing: PairingCoordinator, settings: SYNQSettings) {
        self.discovery = discovery
        self.pairing = pairing
        self.settings = settings
        super.init()
    }

    func showIfNeeded() {
        guard !settings.hasCompletedOnboarding else { return }
        show()
    }

    func show() {
        guard !settings.hasCompletedOnboarding else { return }
        if let existing = window, existing.isVisible {
            NSApp.activate(ignoringOtherApps: true)
            existing.makeKeyAndOrderFront(nil)
            return
        }

        let container = OnboardingContainerView(
            pairing: pairing,
            settings: settings,
            onComplete: { [weak self] in
                self?.completeOnboarding()
            }
        )
        let hosting = NSHostingController(rootView: container)
        let created = NSWindow(contentViewController: hosting)
        created.title = "Welcome to SYNQ"
        created.styleMask = [.titled, .closable, .miniaturizable]
        created.setContentSize(NSSize(width: 520, height: 420))
        created.minSize = NSSize(width: 520, height: 420)
        created.maxSize = NSSize(width: 520, height: 420)
        created.isReleasedWhenClosed = false
        created.center()
        created.delegate = self

        self.window = created
        NSApp.activate(ignoringOtherApps: true)
        created.makeKeyAndOrderFront(nil)
    }

    func close() {
        window?.close()
        window = nil
    }

    func windowWillClose(_ notification: Notification) {
        if !isCompleted {
            completeOnboarding()
        }
    }

    func completeOnboarding() {
        guard !isCompleted else { return }
        isCompleted = true
        settings.completeOnboarding()
        window?.close()
        window = nil
        postReadyNotification()
    }

    private func postReadyNotification() {
        guard Bundle.main.bundleURL.pathExtension == "app" else { return }
        let center = UNUserNotificationCenter.current()
        let content = UNMutableNotificationContent()
        content.title = "SYNQ is Ready"
        content.body = "SYNQ is now active in your menu bar. Click the menu bar icon anytime to manage connected devices."
        content.sound = .default
        let request = UNNotificationRequest(
            identifier: "dev.synq.onboarding.ready",
            content: content,
            trigger: nil
        )
        center.add(request)
    }
}

struct OnboardingContainerView: View {
    @ObservedObject var pairing: PairingCoordinator
    @ObservedObject var settings: SYNQSettings
    let onComplete: () -> Void

    @State private var screen: OnboardingScreen = .welcome

    enum OnboardingScreen {
        case welcome
        case qrPairing
    }

    var body: some View {
        ZStack {
            switch screen {
            case .welcome:
                AnimatedWelcomeView(
                    onGetStarted: onComplete,
                    onPairWithQR: {
                        pairing.qrPairingManager.startSession(
                            deviceID: pairing.localDeviceID,
                            deviceName: pairing.localDeviceName,
                            port: pairing.listeningPort
                        )
                        withAnimation(.easeInOut(duration: 0.2)) {
                            screen = .qrPairing
                        }
                    }
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .transition(.opacity)
            case .qrPairing:
                VStack(spacing: 0) {
                    QRPairingPopoverView(
                        manager: pairing.qrPairingManager,
                        onConfirm: {
                            pairing.confirm()
                        },
                        onBack: {
                            pairing.qrPairingManager.cancelSession()
                            withAnimation(.easeInOut(duration: 0.2)) {
                                screen = .welcome
                            }
                        }
                    )
                }
                .padding(20)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .transition(.opacity)
            }
        }
        .frame(width: 520, height: 420)
        .background(Color(NSColor.windowBackgroundColor))
        .onReceive(pairing.$state) { state in
            if case .connected = state {
                onComplete()
            }
        }
    }
}

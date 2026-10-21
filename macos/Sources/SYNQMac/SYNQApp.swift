import AppKit
import SwiftUI

func makeMenuBarIcon(connected: Bool) -> NSImage {
    let size = NSSize(width: 18, height: 18)
    let image = NSImage(size: size, flipped: false) { _ in
        guard let context = NSGraphicsContext.current?.cgContext else { return false }

        context.setAllowsAntialiasing(true)
        context.setShouldAntialias(true)
        context.setLineCap(.round)
        context.setLineJoin(.round)

        // 1. Mac Stand Foot & Neck
        let standFoot = CGPath(roundedRect: CGRect(x: 3.5, y: 1.8, width: 5.5, height: 1.2), cornerWidth: 0.6, cornerHeight: 0.6, transform: nil)
        context.addPath(standFoot)
        context.setFillColor(NSColor.black.cgColor)
        context.fillPath()

        let standNeck = CGPath(roundedRect: CGRect(x: 5.5, y: 2.8, width: 1.5, height: 1.6), cornerWidth: 0.5, cornerHeight: 0.5, transform: nil)
        context.addPath(standNeck)
        context.fillPath()

        // 2. Mac Monitor Frame
        let macRect = CGRect(x: 1.2, y: 4.2, width: 10.6, height: 8.2)
        let macPath = CGPath(roundedRect: macRect, cornerWidth: 2.0, cornerHeight: 2.0, transform: nil)
        context.addPath(macPath)
        context.setLineWidth(1.25)
        context.setStrokeColor(NSColor.black.cgColor)
        context.strokePath()

        // 3. Knockout behind Phone for clean overlay
        let phoneCutout = CGPath(roundedRect: CGRect(x: 8.6, y: 1.2, width: 8.4, height: 13.2), cornerWidth: 2.8, cornerHeight: 2.8, transform: nil)
        context.saveGState()
        context.setBlendMode(.clear)
        context.addPath(phoneCutout)
        context.fillPath()
        context.restoreGState()

        // 4. Phone Frame
        let phoneRect = CGRect(x: 9.5, y: 2.0, width: 6.8, height: 11.8)
        let phonePath = CGPath(roundedRect: phoneRect, cornerWidth: 2.2, cornerHeight: 2.2, transform: nil)
        context.addPath(phonePath)
        context.setLineWidth(1.25)
        context.setStrokeColor(NSColor.black.cgColor)
        context.strokePath()

        // 5. Connecting Sync Bridge between Mac and Phone
        let bridgePath = CGMutablePath()
        bridgePath.move(to: CGPoint(x: 5.4, y: 8.5))
        bridgePath.addCurve(to: CGPoint(x: 12.9, y: 7.6),
                            control1: CGPoint(x: 7.8, y: 9.8),
                            control2: CGPoint(x: 10.2, y: 5.8))

        if connected {
            context.addPath(bridgePath)
            context.setLineWidth(1.5)
            context.setStrokeColor(NSColor.black.cgColor)
            context.strokePath()

            context.addArc(center: CGPoint(x: 5.4, y: 8.5), radius: 1.0, startAngle: 0, endAngle: .pi * 2, clockwise: true)
            context.fillPath()

            context.addArc(center: CGPoint(x: 12.9, y: 7.6), radius: 1.0, startAngle: 0, endAngle: .pi * 2, clockwise: true)
            context.fillPath()
        } else {
            let dashes: [CGFloat] = [2.0, 1.4]
            context.setLineDash(phase: 0, lengths: dashes)
            context.addPath(bridgePath)
            context.setLineWidth(1.0)
            context.setStrokeColor(NSColor.black.withAlphaComponent(0.55).cgColor)
            context.strokePath()
        }
        return true
    }
    image.isTemplate = true
    return image
}

func makeSynqDeviceIcon(size: CGFloat) -> NSImage {
    let imgSize = NSSize(width: size, height: size)
    return NSImage(size: imgSize, flipped: false) { rect in
        guard let context = NSGraphicsContext.current?.cgContext else { return false }
        context.setAllowsAntialiasing(true)
        context.setShouldAntialias(true)

        // Squircle tile
        let corner = size * 0.22
        let tilePath = CGPath(roundedRect: rect, cornerWidth: corner, cornerHeight: corner, transform: nil)
        context.saveGState()
        context.addPath(tilePath)
        context.clip()

        // Background gradient: deep navy to dark blue
        let bgColors = [
            NSColor(calibratedRed: 13/255, green: 42/255, blue: 122/255, alpha: 1.0).cgColor,
            NSColor(calibratedRed: 6/255, green: 22/255, blue: 79/255, alpha: 1.0).cgColor
        ] as CFArray
        if let grad = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: bgColors, locations: [0.0, 1.0]) {
            context.drawLinearGradient(grad, start: CGPoint(x: size / 2, y: size), end: CGPoint(x: size / 2, y: 0), options: [])
        }

        // Top edge specular line
        context.setLineWidth(1.0)
        context.setStrokeColor(NSColor.white.withAlphaComponent(0.2).cgColor)
        context.addPath(tilePath)
        context.strokePath()

        // Scale factors for Mac & Phone inside the squircle
        let s = size / 100.0

        // Mac Stand
        let standFoot = CGPath(roundedRect: CGRect(x: 28 * s, y: 22 * s, width: 22 * s, height: 2.5 * s), cornerWidth: 1.2 * s, cornerHeight: 1.2 * s, transform: nil)
        context.addPath(standFoot)
        context.setFillColor(NSColor(calibratedRed: 74/255, green: 111/255, blue: 165/255, alpha: 0.9).cgColor)
        context.fillPath()

        let standNeck = CGPath(roundedRect: CGRect(x: 37 * s, y: 24 * s, width: 4 * s, height: 12 * s), cornerWidth: 1 * s, cornerHeight: 1 * s, transform: nil)
        context.addPath(standNeck)
        context.fillPath()

        // Mac Monitor Frame
        let macRect = CGRect(x: 16 * s, y: 35 * s, width: 46 * s, height: 32 * s)
        let macPath = CGPath(roundedRect: macRect, cornerWidth: 3.5 * s, cornerHeight: 3.5 * s, transform: nil)
        context.addPath(macPath)
        context.setFillColor(NSColor(calibratedRed: 16/255, green: 36/255, blue: 85/255, alpha: 1.0).cgColor)
        context.fillPath()
        context.addPath(macPath)
        context.setLineWidth(1.8 * s)
        context.setStrokeColor(NSColor(calibratedRed: 123/255, green: 158/255, blue: 204/255, alpha: 0.9).cgColor)
        context.strokePath()

        // Phone Frame
        let phoneRect = CGRect(x: 52 * s, y: 18 * s, width: 26 * s, height: 48 * s)
        let phonePath = CGPath(roundedRect: phoneRect, cornerWidth: 5 * s, cornerHeight: 5 * s, transform: nil)
        context.saveGState()
        context.setShadow(offset: CGSize(width: -2 * s, height: -2 * s), blur: 4 * s, color: NSColor.black.withAlphaComponent(0.5).cgColor)
        context.addPath(phonePath)
        context.setFillColor(NSColor(calibratedRed: 10/255, green: 26/255, blue: 68/255, alpha: 1.0).cgColor)
        context.fillPath()
        context.restoreGState()

        context.addPath(phonePath)
        context.setLineWidth(1.8 * s)
        context.setStrokeColor(NSColor(calibratedRed: 123/255, green: 158/255, blue: 204/255, alpha: 0.9).cgColor)
        context.strokePath()

        // Connecting Sync Bridge
        let bridgePath = CGMutablePath()
        bridgePath.move(to: CGPoint(x: 35 * s, y: 51 * s))
        bridgePath.addCurve(to: CGPoint(x: 65 * s, y: 42 * s),
                            control1: CGPoint(x: 46 * s, y: 58 * s),
                            control2: CGPoint(x: 54 * s, y: 35 * s))

        context.saveGState()
        context.setShadow(offset: .zero, blur: 4 * s, color: NSColor(calibratedRed: 0/255, green: 229/255, blue: 255/255, alpha: 0.8).cgColor)
        context.addPath(bridgePath)
        context.setLineWidth(2.5 * s)
        context.setLineCap(.round)
        context.setStrokeColor(NSColor(calibratedRed: 0/255, green: 229/255, blue: 255/255, alpha: 1.0).cgColor)
        context.strokePath()
        context.restoreGState()

        for pt in [CGPoint(x: 35 * s, y: 51 * s), CGPoint(x: 65 * s, y: 42 * s)] {
            context.addArc(center: pt, radius: 2.2 * s, startAngle: 0, endAngle: .pi * 2, clockwise: true)
            context.setFillColor(NSColor(calibratedRed: 0/255, green: 229/255, blue: 255/255, alpha: 1.0).cgColor)
            context.fillPath()
            context.addArc(center: pt, radius: 1.0 * s, startAngle: 0, endAngle: .pi * 2, clockwise: true)
            context.setFillColor(NSColor.white.cgColor)
            context.fillPath()
        }

        context.restoreGState()
        return true
    }
}

@main
struct SYNQApp: App {
    @NSApplicationDelegateAdaptor(PhoneURLHandler.self) private var phoneURLHandler
    @StateObject private var discovery: BonjourDiscovery
    @StateObject private var pairing: PairingCoordinator
    @StateObject private var settings: SYNQSettings
    @StateObject private var favourites = FavouriteContactsStore()
    private let settingsWindow: SettingsWindowController
    private let servicesProvider: AppServicesProvider

    init() {
        LegacyPreferences.migrateIfNeeded()
        let settings = SYNQSettings()
        _settings = StateObject(wrappedValue: settings)
        let discovery = BonjourDiscovery(deviceName: settings.deviceName)
        _discovery = StateObject(wrappedValue: discovery)
        let pairing = PairingCoordinator(deviceID: discovery.localDeviceID, deviceName: discovery.localDeviceName, settings: settings)
        pairing.observe(discovery)
        _pairing = StateObject(wrappedValue: pairing)
        let servicesProvider = AppServicesProvider(
            onCall: { [weak pairing] number in pairing?.sendCallWhenConnected(number) },
            onShareFiles: { [weak pairing] urls in pairing?.confirmAndSendFiles(urls) }
        )
        self.servicesProvider = servicesProvider
        NSApplication.shared.servicesProvider = servicesProvider
                NSRegisterServicesProvider(servicesProvider, "SYNQ")
        NSUpdateDynamicServices()
        let settingsWindow = SettingsWindowController(discovery: discovery, pairing: pairing, settings: settings)
        self.settingsWindow = settingsWindow
        pairing.configureShareConfirmation(onOpenSettings: { settingsWindow.show() })
        phoneURLHandler.configure { [weak pairing] number in pairing?.sendCallWhenConnected(number) }
        phoneURLHandler.configureFiles { [weak pairing] urls in pairing?.confirmAndSendFiles(urls) }
        pairing.onSyncedContactsReceived = { [weak favourites] contacts in
            favourites?.setSyncedContacts(contacts)
        }
        pairing.onContactsSyncError = { [weak favourites] message in
            favourites?.setSyncError(message)
        }
        NSApplication.shared.applicationIconImage = makeSynqDeviceIcon(size: 512)
    }

    var body: some Scene {
        MenuBarExtra {
            SYNQPanel(
                discovery: discovery,
                pairing: pairing,
                settings: settings,
                favourites: favourites,
                onOpenSettings: settingsWindow.show
            )
        } label: {
            Image(nsImage: menuBarIcon)
        }
        .menuBarExtraStyle(.window)
    }

    private var menuBarIcon: NSImage {
        let isConnected: Bool
        if case .connected = pairing.state {
            isConnected = true
        } else {
            isConnected = false
        }
        return makeMenuBarIcon(connected: isConnected)
    }
}

private struct SYNQPanel: View {
    @ObservedObject var discovery: BonjourDiscovery
    @ObservedObject var pairing: PairingCoordinator
    @ObservedObject var settings: SYNQSettings
    @ObservedObject var favourites: FavouriteContactsStore
    let onOpenSettings: () -> Void

    @State private var showCallPicker = false

    var body: some View {
        MenuBarPanelSurface {
            panelContent
        }
        .onAppear { pairing.refreshNotificationAuthorization() }
    }

    private var panelContent: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                Image(nsImage: makeSynqDeviceIcon(size: 34))
                    .frame(width: 34, height: 34)
                VStack(alignment: .leading, spacing: 1) {
                    Text("SYNQ").font(.headline)
                    Text(headerSubtitle).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Circle()
                    .fill(isConnected ? Color.green : Color.secondary.opacity(0.45))
                    .frame(width: 8, height: 8)
            }

            if !settings.hasCompletedOnboarding {
                welcomeCard
            } else {
                switch pairing.state {
                case let .connected(_, name): connectedCard(name: name)
                case let .connecting(name): statusCard(title: "Connecting to \(name)", detail: "Establishing a secure session…", progress: true)
                case let .verification(name, code): verificationCard(name: name, code: code)
                case let .failed(message): failureCard(message)
                case .idle: nearbyDevices
                }
            }

            if !pairing.fileTransfers.isEmpty {
                Divider()
                FileTransfersSummaryButton(count: pairing.fileTransfers.count) {
                    pairing.showFileTransferWindow()
                }
            }

            Divider()
            HStack {
                Button(action: onOpenSettings) {
                    Label("Settings", systemImage: "gearshape")
                }
                .buttonStyle(.plain)
                .help("Settings")

                Spacer()

                Button(action: { pairing.showGuestTransferWindow() }) {
                    Label("Guest Transfer", systemImage: "person.crop.circle.badge.plus")
                }
                .buttonStyle(.plain)
                .help("Receive files from nearby guests via QR code")

                Spacer()

                Button("Quit SYNQ") { NSApplication.shared.terminate(nil) }
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
            }
            .font(.caption)
        }
        .padding(16)
    }

    private var isConnected: Bool {
        if case .connected = pairing.state { return true }
        return false
    }

    private var headerSubtitle: String {
        if case .connected = pairing.state { return "Connected securely" }
        return "Ready on your local network"
    }

    @ViewBuilder
    private func connectedCard(name: String) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .fill(Color.accentColor.opacity(0.12))
                        Image(systemName: "iphone.gen3")
                            .font(.system(size: 18, weight: .medium))
                            .foregroundStyle(Color.accentColor)
                    }
                    .frame(width: 40, height: 40)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(name).font(.system(size: 13, weight: .semibold)).lineLimit(1)
                        if pairing.isFeatureAvailable(.battery) {
                            if let battery = pairing.remoteBattery {
                                Label(
                                    "\(battery.level)%\(battery.isCharging ? " · Charging" : "")",
                                    systemImage: battery.isCharging ? "battery.100percent.bolt" : batterySymbol(battery.level)
                                )
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            } else {
                                Text("Waiting for battery status…").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    Spacer()
                    Button { pairing.dismiss() } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 9, weight: .bold))
                            .frame(width: 18, height: 18)
                            .background(Circle().fill(.quaternary))
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
                    .disabled(pairing.fileTransferActive)
                    .help("Disconnect")
                }
                .padding(10)
                .modifier(NativeLiquidGlassCardModifier(cornerRadius: 12))

                if let call = pairing.remoteCall {
                    VStack(alignment: .leading, spacing: 8) {
                        Label(remoteCallStatusTitle(call.type), systemImage: "phone.arrow.down.left.fill")
                            .font(.headline)
                            .foregroundStyle(Color.accentColor)
                        Text(call.caller.isEmpty ? call.applicationName : call.caller)
                            .font(.title3).fontWeight(.semibold).lineLimit(2)
                        if !call.detail.isEmpty && call.detail != call.caller {
                            Text(call.detail).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                        }
                        if !call.actions.isEmpty {
                            HStack(spacing: 6) {
                                ForEach(Array(call.actions.prefix(3))) { action in
                                    Button(action.title) { pairing.performRemoteCallAction(action) }
                                        .buttonStyle(.bordered)
                                        .lineLimit(1)
                                        .frame(maxWidth: .infinity)
                                }
                            }
                        } else {
                            Text("Use the phone to control this call.")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .padding(12)
                    .background(Color.accentColor.opacity(0.1), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }

                LazyVGrid(columns: quickActionColumns, spacing: 8) {
                    if pairing.isFeatureAvailable(.clipboard) {
                        actionButton("Clipboard", icon: "doc.on.clipboard") { pairing.sendClipboard() }
                    }
                    if pairing.isFeatureAvailable(.files) {
                        actionButton("File", icon: "paperplane") { pairing.chooseAndSendFile() }
                    }
                    if pairing.isFeatureAvailable(.findDevice) {
                        actionButton(
                            pairing.macRinging || pairing.androidRinging ? "Stop" : "Ring",
                            icon: pairing.macRinging || pairing.androidRinging ? "stop.circle" : "bell"
                        ) {
                            if pairing.macRinging || pairing.androidRinging { pairing.stopFinding() }
                            else { pairing.findAndroid() }
                        }
                    }
                    if pairing.isFeatureAvailable(.ping) {
                        actionButton("Ping", icon: "wave.3.right") { pairing.sendPing() }
                            .help("Play a short alert on Android")
                    }
                    if pairing.isFeatureAvailable(.calls) {
                        actionButton(
                            "Call",
                            icon: showCallPicker ? "phone.down.fill" : "phone.arrow.up.right"
                        ) {
                            withAnimation(.easeInOut(duration: 0.2)) {
                                showCallPicker.toggle()
                            }
                        }
                        .help(showCallPicker ? "Close call picker" : "Call a contact on Android")
                    }
                    if pairing.isFeatureAvailable(.links) {
                        actionButton("Link", icon: "link") { pairing.quickActions.sendClipboardLink() }
                    }
                }
                QuickActionsPanel(actions: pairing.quickActions)

                // ── Call contact picker ───────────────────────────────────
                if showCallPicker && pairing.isFeatureAvailable(.calls) {
                    CallContactPickerView(
                        favourites: favourites,
                        onDial: { number in
                            pairing.sendCall(number)
                            withAnimation(.easeInOut(duration: 0.2)) { showCallPicker = false }
                        },
                        onManualDial: {
                            pairing.sendCallFromClipboard()
                            withAnimation(.easeInOut(duration: 0.2)) { showCallPicker = false }
                        },
                        onSyncContacts: {
                            pairing.requestContactsSync()
                        },
                        onClose: {
                            withAnimation(.easeInOut(duration: 0.2)) { showCallPicker = false }
                        }
                    )
                    .transition(.opacity.combined(with: .scale(scale: 0.98, anchor: .top)))
                }
                if pairing.isFeatureAvailable(.files) {
                    Button { pairing.showFileDropWindow() } label: {
                        Label(
                            L10n.text("drop.open", fallback: "Open file drop window"),
                            systemImage: "rectangle.portrait.and.arrow.forward"
                        )
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 7)
                    }
                    .buttonStyle(NativeLiquidGlassActionButtonStyle())
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .accessibilityHint("Opens a window that stays visible while you drag a file from Finder")
                }
                if !pairing.isFeatureAvailable(.clipboard) &&
                    !pairing.isFeatureAvailable(.files) &&
                    !pairing.isFeatureAvailable(.findDevice) &&
                    !pairing.isFeatureAvailable(.ping) &&
                    !pairing.isFeatureAvailable(.links) &&
                    !pairing.isFeatureAvailable(.calls) {
                    Text("Quick actions are turned off in Settings on one of your devices.")
                        .font(.caption).foregroundStyle(.secondary)
                }

                if let status = pairing.clipboardStatus {
                    Label(status, systemImage: status == "Delivered" ? "checkmark.circle.fill" : "clock")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if let status = pairing.callStatus {
                    Label(status, systemImage: status == "Call started on Android" ? "phone.fill" : "phone.badge.clock")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if let status = pairing.pingStatus {
                    Label(status, systemImage: status == "Ping delivered" ? "checkmark.circle.fill" : "wave.3.right")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if pairing.isFeatureAvailable(.notifications) && !pairing.notificationsAuthorized {
                    Button { pairing.enableNotifications() } label: {
                        Label(
                            pairing.notificationPermissionDetermined ? "Open notification settings" : "Enable Mac notifications",
                            systemImage: "bell.badge"
                        )
                    }
                    .buttonStyle(.plain)
                    .font(.caption)
                    .foregroundStyle(Color.accentColor)
                }
            }
        }

    private var welcomeCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(L10n.text("welcome.title", fallback: "Welcome to SYNQ"), systemImage: "link.circle.fill")
                .font(.headline)
            Text(L10n.text(
                "welcome.body",
                fallback: "Keep your Mac and phone on the same local network. Choose a device, compare the pairing code, and confirm it on both devices. Optional permissions are requested only when you use the related feature."
            ))
            .font(.caption)
            .foregroundStyle(.secondary)
            Button(L10n.text("welcome.continue", fallback: "Get started")) {
                settings.completeOnboarding()
            }
            .buttonStyle(.borderedProminent)
            .accessibilityHint("Closes the welcome guide and starts device discovery")
        }
    }

    private func actionButton(_ title: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.system(size: 16, weight: .medium))
                    .frame(width: 22, height: 20)
                    .foregroundStyle(Color.accentColor)
                Text(title)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 52)
            .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .buttonStyle(NativeLiquidGlassActionButtonStyle())
        .accessibilityLabel(title)
        .accessibilityHint("Runs the \(title.lowercased()) action for the connected device")
    }

    @ViewBuilder
    private var nearbyDevices: some View {
        VStack(alignment: .leading, spacing: 9) {
            Text("Nearby devices").font(.subheadline.weight(.semibold))
            if discovery.peers.isEmpty {
                HStack(spacing: 10) {
                    ProgressView().controlSize(.small)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Looking for Android devices")
                        Text("Keep both devices on the same Wi‑Fi network.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 8)
            } else {
                ForEach(discovery.peers) { peer in
                    Button {
                        if let host = peer.host, let port = peer.port {
                            pairing.pair(host: host, port: port, peerName: peer.deviceNameHint)
                        }
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: "smartphone").frame(width: 24)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(peer.deviceNameHint).lineLimit(1)
                                Text(peer.deviceIDHint.map(pairing.trustedDeviceIDs.contains) == true ? "Paired · click to reconnect" : "Click to pair")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .padding(.vertical, 4)
                }
            }
        }
    }

    private func statusCard(title: String, detail: String, progress: Bool) -> some View {
        HStack(spacing: 12) {
            if progress { ProgressView().controlSize(.small) }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).fontWeight(.medium)
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(14).frame(maxWidth: .infinity, alignment: .leading)
        .modifier(NativeLiquidGlassCardModifier(cornerRadius: 12))
    }

    private func verificationCard(name: String, code: String) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Verify \(name)").font(.headline)
            Text(code).font(.system(size: 32, weight: .semibold, design: .monospaced))
            Text("Confirm only if the same code appears on both devices.").font(.caption).foregroundStyle(.secondary)
            HStack { Button("Codes Match") { pairing.confirm() }.buttonStyle(.borderedProminent); Button("Cancel") { pairing.cancel() } }
        }
        .padding(14)
        .modifier(NativeLiquidGlassCardModifier(cornerRadius: 12))
    }

    private func failureCard(_ message: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Couldn’t connect", systemImage: "exclamationmark.triangle.fill").foregroundStyle(.red)
            Text(message).font(.caption).foregroundStyle(.secondary)
            HStack {
                if message.contains("Local Network") {
                    Button("Open Local Network Settings") {
                        NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork")!)
                    }
                }
                Button("Dismiss") { pairing.cancel() }
            }
        }
        .padding(14).frame(maxWidth: .infinity, alignment: .leading)
        .modifier(NativeLiquidGlassCardModifier(cornerRadius: 12))
    }

    private func batterySymbol(_ level: Int) -> String {
        switch level {
        case 76...100: "battery.100percent"
        case 51...75: "battery.75percent"
        case 26...50: "battery.50percent"
        case 1...25: "battery.25percent"
        default: "battery.0percent"
        }
    }

    private var quickActionColumns: [GridItem] {
        [GridItem(.flexible(), spacing: 8), GridItem(.flexible())]
    }
}

enum SettingsCategory: String, CaseIterable, Identifiable {
    case general = "General"
    case appearance = "Appearance"
    case devices = "Devices & Connection"
    case clipboard = "Clipboard"
    case notifications = "Notifications"
    case transfers = "Transfers"
    case privacy = "Privacy"
    case about = "About"

    var id: String { rawValue }
    var title: String { rawValue }

    var icon: String {
        switch self {
        case .general: return "gearshape"
        case .appearance: return "paintpalette"
        case .devices: return "laptopcomputer.and.iphone"
        case .clipboard: return "doc.on.clipboard"
        case .notifications: return "bell.badge"
        case .transfers: return "arrow.left.arrow.right"
        case .privacy: return "hand.raised"
        case .about: return "info.circle"
        }
    }
}

@MainActor
private final class SettingsWindowController {
    private let discovery: BonjourDiscovery
    private let pairing: PairingCoordinator
    private let settings: SYNQSettings
    private var window: NSWindow?

    init(discovery: BonjourDiscovery, pairing: PairingCoordinator, settings: SYNQSettings) {
        self.discovery = discovery
        self.pairing = pairing
        self.settings = settings
    }

    func show() {
        if window == nil {
            let content = SettingsView(discovery: discovery, pairing: pairing, settings: settings)
            let created = NSWindow(contentViewController: NSHostingController(rootView: content))
            created.title = "SYNQ Settings"
            created.styleMask = [.titled, .closable, .miniaturizable, .resizable]
            created.setContentSize(NSSize(width: 740, height: 530))
            created.minSize = NSSize(width: 660, height: 460)
            created.isReleasedWhenClosed = false
            created.setFrameAutosaveName("SYNQSettingsWindow")
            created.center()
            window = created
        }
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }
}

private struct SettingsView: View {
    @ObservedObject var discovery: BonjourDiscovery
    @ObservedObject var pairing: PairingCoordinator
    @ObservedObject var settings: SYNQSettings
    @State private var selectedCategory: SettingsCategory? = .general
    @State private var editedName = ""
    @State private var phoneLinkStatus: String?
    @AppStorage("settings.appearance") private var appearancePreference: String = "system"

    var body: some View {
        NavigationSplitView {
            List(SettingsCategory.allCases, selection: $selectedCategory) { category in
                NavigationLink(value: category) {
                    Label(category.title, systemImage: category.icon)
                }
            }
            .listStyle(.sidebar)
            .navigationSplitViewColumnWidth(min: 190, ideal: 210, max: 240)
        } detail: {
            detailContent(for: selectedCategory ?? .general)
                .frame(minWidth: 460, minHeight: 460)
        }
        .onAppear {
            editedName = settings.deviceName
            pairing.refreshNotificationAuthorization()
            settings.refreshLoginItemStatus()
        }
    }

    @ViewBuilder
    private func detailContent(for category: SettingsCategory) -> some View {
        Form {
            switch category {
            case .general:
                generalSection
            case .appearance:
                appearanceSection
            case .devices:
                devicesSection
            case .clipboard:
                clipboardSection
            case .notifications:
                notificationsSection
            case .transfers:
                transfersSection
            case .privacy:
                privacySection
            case .about:
                aboutSection
            }
        }
        .formStyle(.grouped)
    }

    // MARK: - Sections

    @ViewBuilder
    private var generalSection: some View {
        Section("Device Name") {
            HStack {
                TextField("Device name", text: $editedName)
                Button("Save") {
                    settings.setDeviceName(editedName)
                    pairing.updateDeviceName(settings.deviceName)
                    discovery.updateDeviceName(settings.deviceName)
                }
                .disabled(editedName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || editedName == settings.deviceName)
            }
            Text("This name is visible to nearby devices during discovery and pairing.")
                .font(.caption).foregroundStyle(.secondary)
        }

        Section("Startup") {
            Toggle("Launch at login", isOn: Binding(
                get: { settings.launchAtLogin },
                set: { settings.setLaunchAtLogin($0) }
            ))
            if let message = settings.loginItemMessage {
                Text(message).font(.caption).foregroundStyle(.red)
            }
        }

        MediaSettingsView(media: pairing.mediaController)
        ShortcutSettingsView(shortcuts: pairing.shortcuts)
    }

    @ViewBuilder
    private var appearanceSection: some View {
        Section("Theme") {
            Picker("Theme mode", selection: $appearancePreference) {
                Text("System").tag("system")
                Text("Light").tag("light")
                Text("Dark").tag("dark")
            }
            .pickerStyle(.radioGroup)
            .onChange(of: appearancePreference) { newTheme in
                applyAppearance(newTheme)
            }

            Text("Choose whether SYNQ follows macOS system appearance or stays in light or dark mode.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    private func applyAppearance(_ theme: String) {
        switch theme {
        case "dark":
            NSApp.appearance = NSAppearance(named: .darkAqua)
        case "light":
            NSApp.appearance = NSAppearance(named: .aqua)
        default:
            NSApp.appearance = nil
        }
    }

    @ViewBuilder
    private var devicesSection: some View {
        Section("Paired Devices") {
            if pairing.trustedDevices.isEmpty {
                Text("No paired devices").foregroundStyle(.secondary)
            }
            ForEach(pairing.trustedDevices) { device in
                DisclosureGroup(device.name) {
                    Text("Choose what this Mac may use with this device.")
                        .font(.caption).foregroundStyle(.secondary)
                    ForEach(SYNQFeature.allCases) { feature in
                        Toggle(isOn: Binding(
                            get: {
                                settings.globalFeatures[feature] != false &&
                                    settings.deviceFeatures[device.id]?[feature] != false
                            },
                            set: {
                                settings.setForDevice(device.id, feature: feature, enabled: $0)
                                if feature == .findDevice && !$0,
                                   case let .connected(connectedID, _) = pairing.state,
                                   connectedID == device.id { pairing.stopFinding() }
                            }
                        )) {
                            VStack(alignment: .leading, spacing: 1) {
                                Text(feature.title)
                                if connectedDeviceID == device.id && pairing.remoteFeatures[feature] == false {
                                    Text("Off on \(device.name)").font(.caption2).foregroundStyle(.orange)
                                }
                            }
                        }
                        .disabled(settings.globalFeatures[feature] == false)
                    }
                    Button("Forget device", role: .destructive) { pairing.forget(deviceID: device.id) }
                }
            }
        }

        Section("Current Connection") {
            if case let .connected(_, name) = pairing.state {
                Label("Connected to \(name)", systemImage: "checkmark.circle.fill")
                    .foregroundStyle(.green)
            } else {
                Label("Not currently connected. Keep both devices on the same Wi-Fi network.", systemImage: "network.slash")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private var clipboardSection: some View {
        Section("Clipboard Synchronisation") {
            Toggle("Enable clipboard sharing", isOn: Binding(
                get: { settings.globalFeatures[.clipboard] != false },
                set: { settings.setGlobal(.clipboard, enabled: $0) }
            ))
            if settings.globalFeatures[.clipboard] != false {
                Toggle("Auto-sync copied text to Android", isOn: Binding(
                    get: { settings.clipboardAutoSync },
                    set: { settings.setClipboardAutoSync($0) }
                ))
                .padding(.leading, 18)

                Text("When enabled, plain text copied on your Mac is sent directly to your connected Android phone without needing to click the Clipboard button.")
                    .font(.caption).foregroundStyle(.secondary)
                    .padding(.leading, 18)
            }
        }
    }

    @ViewBuilder
    private var notificationsSection: some View {
        Section("Notification Sync") {
            Toggle("Sync Android notifications", isOn: Binding(
                get: { settings.globalFeatures[.notifications] != false },
                set: { settings.setGlobal(.notifications, enabled: $0) }
            ))
            if settings.globalFeatures[.notifications] != false && !pairing.notificationsAuthorized {
                Button(pairing.notificationPermissionDetermined ? "Open notification settings" : "Enable Mac notifications") {
                    pairing.enableNotifications()
                }
            }
        }

        Section("Notification History") {
            Toggle("Keep a private local history", isOn: Binding(
                get: { settings.notificationHistoryEnabled },
                set: { settings.setNotificationHistoryEnabled($0) }
            ))
            Text("Off by default. When enabled, SYNQ keeps at most 200 notifications for 7 days on this Mac. Turning it off deletes the history.")
                .font(.caption).foregroundStyle(.secondary)

            if settings.notificationHistoryEnabled {
                if pairing.notificationHistory.isEmpty {
                    Text("No forwarded notifications yet.").foregroundStyle(.secondary)
                } else {
                    ForEach(Array(pairing.notificationHistory.prefix(20))) { item in
                        VStack(alignment: .leading, spacing: 2) {
                            HStack {
                                Text(item.applicationName).font(.headline)
                                Spacer()
                                Text(item.receivedAt, format: .dateTime.day().month().hour().minute())
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            if !item.title.isEmpty { Text(item.title).fontWeight(.medium) }
                            if !item.text.isEmpty {
                                Text(item.text).lineLimit(3).foregroundStyle(.secondary)
                            }
                        }
                        .textSelection(.enabled)
                    }
                    Button("Clear history", role: .destructive) { pairing.clearNotificationHistory() }
                }
            }
        }
    }

    @ViewBuilder
    private var transfersSection: some View {
        Section("File Transfers") {
            Toggle("Enable file transfers", isOn: Binding(
                get: { settings.globalFeatures[.files] != false },
                set: { settings.setGlobal(.files, enabled: $0) }
            ))
            HStack {
                LabeledContent("Received files folder", value: settings.receiveFolderPath)
                Button("Choose…") { settings.chooseReceiveFolder() }
            }
        }

        Section("Guest Transfers") {
            Text("Allow someone without SYNQ installed to send files to your Mac over local Wi-Fi using a temporary QR code or synq.local.")
                .font(.caption).foregroundStyle(.secondary)
            Button("Start Guest Transfer Session…") {
                pairing.showGuestTransferWindow()
            }
        }
    }

    @ViewBuilder
    private var privacySection: some View {
        Section("Local Network & Encryption") {
            Text("SYNQ communicates directly over your local network using authenticated AES-GCM encryption. No accounts, cloud servers, or third-party telemetry are involved.")
                .font(.caption).foregroundStyle(.secondary)
        }

        Section("Phone Links") {
            Text("Make SYNQ handle tel: links from browsers and other Mac applications. The browser still asks before opening SYNQ.")
                .font(.caption).foregroundStyle(.secondary)
            Button("Use SYNQ for phone links") { makeDefaultPhoneLinkHandler() }
            if let phoneLinkStatus {
                Text(phoneLinkStatus)
                    .font(.caption)
                    .foregroundStyle(phoneLinkStatus.hasPrefix("Could not") ? .red : .secondary)
            }
        }

        Section("Optional Features") {
            Text("Control individual permissions for other local peer capabilities:")
                .font(.caption).foregroundStyle(.secondary)
            ForEach([SYNQFeature.findDevice, .ping, .links, .calls, .battery]) { feature in
                Toggle(feature.title, isOn: Binding(
                    get: { settings.globalFeatures[feature] != false },
                    set: {
                        settings.setGlobal(feature, enabled: $0)
                        if feature == .findDevice && !$0 { pairing.stopFinding() }
                    }
                ))
            }
        }
    }

    @ViewBuilder
    private var aboutSection: some View {
        Section {
            VStack(spacing: 12) {
                Image(nsImage: makeSynqDeviceIcon(size: 64))
                    .frame(width: 64, height: 64)
                    .padding(.top, 8)
                Text("SYNQ")
                    .font(.title2)
                    .fontWeight(.bold)
                Text("Sync Your Nodes, Unified")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Text("Version \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.5.4")")
                    .font(.caption)
                    .foregroundStyle(.tertiary)

                Divider().padding(.vertical, 8)

                Text("A private, local-first bridge connecting your Mac and Android phone seamlessly over encrypted Wi-Fi.\nNo cloud, no accounts, zero telemetry.")
                    .font(.caption)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
            }
            .frame(maxWidth: .infinity, alignment: .center)
        }
    }

    private var connectedDeviceID: String? {
        if case let .connected(deviceID, _) = pairing.state { return deviceID }
        return nil
    }

    private func makeDefaultPhoneLinkHandler() {
        let applicationURL = Bundle.main.bundleURL
        guard applicationURL.pathExtension == "app" else {
            phoneLinkStatus = "Could not register: run the installed SYNQ.app bundle."
            return
        }
        NSWorkspace.shared.setDefaultApplication(
            at: applicationURL,
            toOpenURLsWithScheme: "tel"
        ) { error in
            DispatchQueue.main.async {
                phoneLinkStatus = error.map { "Could not register SYNQ: \($0.localizedDescription)" }
                    ?? "SYNQ will now open phone links."
            }
        }
    }
}

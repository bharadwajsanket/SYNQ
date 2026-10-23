import AppKit
import SwiftUI

@MainActor
final class FileDropWindowController: NSWindowController {
    init(pairing: PairingCoordinator) {
        let root = FileDropView(pairing: pairing)
        let window = NSWindow(contentViewController: NSHostingController(rootView: root))
        window.title = "SYNQ File Drop"
        window.styleMask = [.titled, .closable, .miniaturizable]
        window.setContentSize(NSSize(width: 460, height: 280))
        window.minSize = NSSize(width: 400, height: 240)
        window.isReleasedWhenClosed = false
        window.hidesOnDeactivate = false
        window.level = .floating
        window.collectionBehavior.insert(.canJoinAllSpaces)
        window.setFrameAutosaveName("SYNQFileDropWindow")
        window.center()
        super.init(window: window)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func show() {
        NSApp.activate(ignoringOtherApps: true)
        showWindow(nil)
        window?.makeKeyAndOrderFront(nil)
    }
}

private struct FileDropView: View {
    @ObservedObject var pairing: PairingCoordinator
    @State private var isTargeted = false

    private var canSend: Bool {
        guard case .connected = pairing.state else { return false }
        return pairing.isFeatureAvailable(.files)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(L10n.text("drop.title", fallback: "Drop a file here to send"))
                .font(.title2.weight(.semibold))
            Text(L10n.text("drop.detail", fallback: "This window stays open while you switch to Finder."))
                .foregroundStyle(.secondary)

            VStack(spacing: 10) {
                Image(systemName: isTargeted ? "arrow.down.doc.fill" : "doc.badge.arrow.up")
                    .font(.system(size: 36))
                    .foregroundStyle(canSend ? Color.accentColor : Color.secondary)
                Text(canSend
                     ? L10n.text("drop.ready", fallback: "Drop files anywhere in this area")
                     : L10n.text("drop.unavailable", fallback: "Connect an Android device and enable File transfer first."))
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(24)
            .background(
                (isTargeted ? Color.accentColor.opacity(0.16) : Color.accentColor.opacity(0.07)),
                in: RoundedRectangle(cornerRadius: 16, style: .continuous)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(
                        Color.accentColor.opacity(canSend ? 0.6 : 0.25),
                        style: StrokeStyle(lineWidth: 2, dash: [7])
                    )
            )
            .dropDestination(for: URL.self) { urls, _ in
                guard canSend, !urls.isEmpty else { return false }
                return pairing.sendDroppedFiles(urls)
            } isTargeted: { isTargeted = $0 }
            .accessibilityLabel("File drop area")
            .accessibilityHint("Drop files to send them to the connected Android device")
        }
        .padding(24)
        .frame(minWidth: 400, minHeight: 240)
    }
}

/// File sharing shown in the menu-bar panel alongside Call and Guest Share.
struct FileDropPopoverView: View {
    @ObservedObject var pairing: PairingCoordinator
    let onBack: () -> Void
    @State private var isTargeted = false

    private var canSend: Bool {
        guard case .connected = pairing.state else { return false }
        return pairing.isFeatureAvailable(.files)
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button(action: onBack) {
                    Label("Back", systemImage: "chevron.left")
                        .font(.system(size: 12, weight: .medium))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 7)
                        .background(Color.primary.opacity(0.08), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.accentColor)
                .accessibilityLabel("Back to SYNQ")
                Spacer()
                Label("File Drop", systemImage: "arrow.down.doc")
                    .font(.headline)
                Spacer()
                Button {
                    pairing.chooseAndSendFile()
                } label: {
                    Label("Choose", systemImage: "folder")
                        .font(.system(size: 11, weight: .semibold))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 7)
                        .background(Color.accentColor.opacity(0.13), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.accentColor)
                .disabled(!canSend)
            }
            .padding(.bottom, 12)

            Divider()

            Text(canSend
                 ? "Send a file to your connected phone"
                 : "Connect your phone and enable File transfer to send files.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 12)
                .padding(.bottom, 10)

            VStack(spacing: 10) {
                Image(systemName: isTargeted ? "arrow.down.doc.fill" : "doc.badge.arrow.up")
                    .font(.system(size: 34, weight: .regular))
                    .foregroundStyle(canSend ? Color.accentColor : Color.secondary)
                Text(canSend ? "Drop a file here" : "File sharing unavailable")
                    .font(.subheadline.weight(.semibold))
                Text(canSend
                     ? "Or use Choose above to select a file."
                     : "Reconnect your Android device to continue.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(18)
            .background(
                (isTargeted ? Color.accentColor.opacity(0.17) : Color.accentColor.opacity(0.07)),
                in: RoundedRectangle(cornerRadius: 24, style: .continuous)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .strokeBorder(
                        Color.accentColor.opacity(canSend ? 0.55 : 0.22),
                        style: StrokeStyle(lineWidth: 1.5, dash: [7])
                    )
            }
            .dropDestination(for: URL.self) { urls, _ in
                guard canSend, !urls.isEmpty else { return false }
                return pairing.sendDroppedFiles(urls)
            } isTargeted: { isTargeted = $0 }
            .accessibilityLabel("File drop area")
            .accessibilityHint("Drop files to send them to the connected Android device")
            .padding(.bottom, 12)

            Divider()

            HStack(spacing: 7) {
                Image(systemName: canSend ? "checkmark.circle.fill" : "info.circle")
                    .foregroundStyle(canSend ? Color.green : Color.secondary)
                Text(canSend ? "Securely connected" : "No active phone connection")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Spacer()
                if let status = pairing.fileTransferStatus {
                    Text(status)
                        .font(.caption2)
                        .lineLimit(1)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.top, 10)
        }
        .padding(.horizontal, 16)
        .padding(.top, 14)
        .padding(.bottom, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

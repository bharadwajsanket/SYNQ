import AppKit
import SwiftUI

@MainActor
public final class GuestTransferWindowController: NSWindowController, NSWindowDelegate {
    private let manager: GuestTransferManager

    public init(manager: GuestTransferManager) {
        self.manager = manager
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 620, height: 440),
            styleMask: [.titled, .closable, .miniaturizable],
            backing: .buffered,
            defer: false
        )
        window.title = "SYNQ — Receive from Guest"
        window.center()
        window.isReleasedWhenClosed = false
        super.init(window: window)
        window.delegate = self
        window.contentView = NSHostingView(rootView: GuestTransferView(manager: manager, onClose: { [weak self] in
            self?.close()
        }))
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    public func show() {
        if !manager.isSessionActive {
            manager.startSession()
        }
        window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    public func windowWillClose(_ notification: Notification) {
        manager.cancelSession()
    }
}

public struct GuestTransferView: View {
    @ObservedObject var manager: GuestTransferManager
    let onClose: () -> Void

    public init(manager: GuestTransferManager, onClose: @escaping () -> Void) {
        self.manager = manager
        self.onClose = onClose
    }

    public var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Receive from Guest")
                        .font(.title2)
                        .fontWeight(.semibold)
                    Text("Allow anyone on this Wi-Fi network to send you files via browser.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if manager.isSessionActive {
                    HStack(spacing: 6) {
                        Circle()
                            .fill(Color(nsColor: .systemGreen))
                            .frame(width: 8, height: 8)
                        Text(formattedTime(manager.timeRemaining))
                            .font(.system(.body, design: .monospaced))
                            .fontWeight(.medium)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(Color.secondary.opacity(0.12), in: Capsule())
                }
            }
            .padding(20)

            Divider()

            // Content Split
            HStack(alignment: .top, spacing: 24) {
                // Left Column: QR and Link
                VStack(spacing: 14) {
                    if let qrImage = manager.qrCodeImage {
                        Image(nsImage: qrImage)
                            .interpolation(.none)
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(width: 170, height: 170)
                            .padding(10)
                            .background(Color.white)
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 14, style: .continuous)
                                    .stroke(Color.secondary.opacity(0.2), lineWidth: 1)
                            )
                    } else {
                        Rectangle()
                            .fill(Color.secondary.opacity(0.1))
                            .frame(width: 170, height: 170)
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }

                    if let urlStr = manager.sessionURLString {
                        VStack(spacing: 8) {
                            Text(urlStr)
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundStyle(.primary)
                                .lineLimit(1)
                                .truncationMode(.middle)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 4)
                                .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 6))

                            HStack(spacing: 6) {
                                Button("Copy Link") {
                                    NSPasteboard.general.clearContents()
                                    NSPasteboard.general.setString(urlStr, forType: .string)
                                }
                                .buttonStyle(.bordered)
                                .controlSize(.small)
                            }
                        }
                    }

                    Text("Scan with phone camera.\nNo app needed.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .frame(width: 200)

                Divider()

                // Right Column: Uploads
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Text("Received Files")
                            .font(.headline)
                        Spacer()
                        if manager.pendingUploads.contains(where: { $0.status == .pending }) {
                            Button("Accept All") {
                                manager.acceptAll()
                            }
                            .controlSize(.small)
                        }
                    }

                    if manager.pendingUploads.isEmpty {
                        VStack(spacing: 12) {
                            Spacer()
                            ProgressView()
                                .controlSize(.small)
                            Text("Waiting for files…")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                            Spacer()
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    } else {
                        ScrollView {
                            LazyVStack(spacing: 10) {
                                ForEach(manager.pendingUploads) { upload in
                                    HStack(spacing: 12) {
                                        Image(systemName: "doc")
                                            .font(.title2)
                                            .foregroundStyle(.secondary)

                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(upload.filename)
                                                .fontWeight(.medium)
                                                .lineLimit(1)
                                            Text(ByteCountFormatter.string(fromByteCount: upload.fileSize, countStyle: .file))
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                        }

                                        Spacer()

                                        switch upload.status {
                                        case .pending:
                                            HStack(spacing: 6) {
                                                Button("Reject", role: .destructive) {
                                                    manager.rejectUpload(upload)
                                                }
                                                .controlSize(.small)

                                                Button("Accept") {
                                                    manager.acceptUpload(upload)
                                                }
                                                .controlSize(.small)
                                                .buttonStyle(.borderedProminent)
                                            }
                                        case .accepted:
                                            HStack(spacing: 4) {
                                                Image(systemName: "checkmark.circle.fill")
                                                    .foregroundStyle(.green)
                                                Text("Saved to Downloads")
                                                    .font(.caption)
                                                    .foregroundStyle(.secondary)
                                            }
                                        case .rejected:
                                            Text("Rejected")
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                    .padding(10)
                                    .background(Color.secondary.opacity(0.06), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                                }
                            }
                            .padding(.trailing, 4)
                        }
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .padding(20)

            Divider()

            // Footer
            HStack {
                if let err = manager.errorMessage {
                    Text(err)
                        .font(.caption)
                        .foregroundStyle(.red)
                }
                Spacer()
                Button("End Session") {
                    manager.cancelSession()
                    onClose()
                }
                .keyboardShortcut(.cancelAction)
            }
            .padding(16)
        }
        .frame(width: 620, height: 440)
    }

    private func formattedTime(_ interval: TimeInterval) -> String {
        let seconds = max(0, Int(interval))
        let minutes = seconds / 60
        let remainingSeconds = seconds % 60
        return String(format: "%02d:%02d", minutes, remainingSeconds)
    }
}

/// Compact guest-receive experience embedded directly in the menu-bar panel.
struct GuestTransferPopoverView: View {
    @ObservedObject var manager: GuestTransferManager
    let onBack: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 8) {
                Button(action: onBack) {
                    Label("Back", systemImage: "chevron.left")
                        .font(.system(size: 12, weight: .medium))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 7)
                        .background(Color.primary.opacity(0.08), in: Capsule())
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.accentColor)
                .accessibilityLabel("Back to SYNQ")
                Spacer()
                Text("Guest Share").font(.headline)
                Spacer()
                if manager.isSessionActive {
                    Label(formattedTime(manager.timeRemaining), systemImage: "clock")
                        .labelStyle(.titleOnly)
                        .font(.system(size: 11, weight: .medium, design: .monospaced))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 7)
                        .background(Color.green.opacity(0.12), in: Capsule())
                        .foregroundStyle(.green)
                } else {
                    Color.clear.frame(width: 54, height: 1)
                }
            }
            .padding(.bottom, 12)

            Divider()

            if let image = manager.qrCodeImage, manager.isSessionActive {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(alignment: .center, spacing: 12) {
                        Image(nsImage: image)
                            .interpolation(.none)
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(width: 116, height: 116)
                            .padding(7)
                            .background(.white, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        VStack(alignment: .leading, spacing: 7) {
                            Label("Ready to receive", systemImage: "checkmark.circle.fill")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(.green)
                            Text("Let a guest scan this code with their camera. No SYNQ app needed.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                            if let url = manager.sessionURLString {
                                Text(url)
                                    .font(.system(size: 9, design: .monospaced))
                                    .lineLimit(1)
                                    .truncationMode(.middle)
                                    .textSelection(.enabled)
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 6)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .background(Color.primary.opacity(0.06), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                            }
                        }
                    }

                    HStack(spacing: 8) {
                        if let url = manager.sessionURLString {
                            guestActionPill("Copy link", icon: "link") {
                                NSPasteboard.general.clearContents()
                                NSPasteboard.general.setString(url, forType: .string)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity)
                }
                .padding(.vertical, 12)
            } else {
                VStack(spacing: 8) {
                    Image(systemName: "qrcode")
                        .font(.system(size: 26, weight: .regular))
                        .foregroundStyle(Color.accentColor)
                    Text(manager.errorMessage ?? "Start a temporary session to receive files.")
                        .font(.caption)
                        .foregroundStyle(manager.errorMessage == nil ? Color.secondary : Color.red)
                        .multilineTextAlignment(.center)
                    Button("Start Guest Session") { manager.startSession() }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.small)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
            }

            Divider()

            HStack {
                Label("Received files", systemImage: "tray.and.arrow.down")
                    .font(.subheadline.weight(.semibold))
                Text("\(manager.pendingUploads.count)")
                    .font(.caption2.weight(.semibold).monospacedDigit())
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(Color.primary.opacity(0.08), in: Capsule())
                Spacer()
                if manager.pendingUploads.contains(where: { $0.status == .pending }) {
                    guestActionPill("Accept all", icon: "checkmark") { manager.acceptAll() }
                }
            }
            .padding(.top, 12)
            .padding(.bottom, 8)

            Group {
                if manager.pendingUploads.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "tray")
                            .font(.system(size: 22, weight: .regular))
                            .foregroundStyle(.secondary)
                        Text("Files shared by guests will appear here")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.secondary)
                        Text("You’ll review each file before it’s saved.")
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.primary.opacity(0.035), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                } else {
                    ScrollView {
                        LazyVStack(spacing: 7) {
                            ForEach(manager.pendingUploads) { upload in
                                HStack(spacing: 7) {
                                    Image(systemName: "doc")
                                        .foregroundStyle(.secondary)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(upload.filename).font(.caption.weight(.medium)).lineLimit(1)
                                        Text(ByteCountFormatter.string(fromByteCount: upload.fileSize, countStyle: .file))
                                            .font(.caption2).foregroundStyle(.secondary)
                                    }
                                    Spacer(minLength: 4)
                                    switch upload.status {
                                    case .pending:
                                        guestActionPill("Reject", icon: "xmark", destructive: true) { manager.rejectUpload(upload) }
                                        guestActionPill("Accept", icon: "checkmark") { manager.acceptUpload(upload) }
                                    case .accepted:
                                        Label("Saved", systemImage: "checkmark.circle.fill")
                                            .font(.caption2).foregroundStyle(.green)
                                    case .rejected:
                                        Text("Rejected").font(.caption2).foregroundStyle(.secondary)
                                    }
                                }
                                .padding(8)
                                .background(Color.primary.opacity(0.045), in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                            }
                        }
                    }
                    .frame(maxHeight: .infinity)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.bottom, 10)

            Divider()

            HStack {
                if let error = manager.errorMessage, manager.isSessionActive {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .font(.caption2).foregroundStyle(.red).lineLimit(1)
                }
                Spacer()
                guestActionPill(manager.isSessionActive ? "End session" : "Close", icon: "stop.fill", destructive: true) {
                    manager.cancelSession()
                }
                .disabled(!manager.isSessionActive)
            }
            .padding(.top, 10)
        }
        .padding(.horizontal, 16)
        .padding(.top, 14)
        .padding(.bottom, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .onAppear {
            if !manager.isSessionActive { manager.startSession() }
        }
    }

    private func guestActionPill(
        _ title: String,
        icon: String,
        destructive: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Label(title, systemImage: icon)
                .font(.system(size: 10, weight: .semibold))
                .lineLimit(1)
                .padding(.horizontal, 9)
                .padding(.vertical, 6)
                .foregroundStyle(destructive ? Color.red : Color.accentColor)
                .background(
                    (destructive ? Color.red : Color.accentColor).opacity(0.12),
                    in: Capsule()
                )
        }
        .buttonStyle(.plain)
    }

    private func formattedTime(_ interval: TimeInterval) -> String {
        let seconds = max(0, Int(interval))
        return String(format: "%02d:%02d", seconds / 60, seconds % 60)
    }
}

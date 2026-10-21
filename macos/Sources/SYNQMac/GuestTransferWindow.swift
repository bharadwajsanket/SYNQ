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
                                if manager.localIP != "127.0.0.1" {
                                    Button(manager.hostPreference == .friendly ? "Use IP" : "Use synq.local") {
                                        manager.hostPreference = (manager.hostPreference == .friendly) ? .ip : .friendly
                                    }
                                    .buttonStyle(.bordered)
                                    .controlSize(.small)
                                    .help(manager.hostPreference == .friendly ? "Switch to direct local IP address (\(manager.localIP))" : "Switch to friendly synq.local hostname")
                                }

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

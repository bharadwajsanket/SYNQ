import AppKit
import SwiftUI

@MainActor
final class FileTransferAutoDismissManager: ObservableObject {
    @Published private(set) var secondsRemaining: Int?
    @Published private(set) var activeCountdownTransferID: String?
    @Published var userInteracted: Bool = false

    private var countdownTask: Task<Void, Never>?
    let dismissDelay: TimeInterval
    private let tickDurationNanoseconds: UInt64
    private let onDismiss: () -> Void

    init(
        dismissDelay: TimeInterval = 10.0,
        tickDurationNanoseconds: UInt64 = 1_000_000_000,
        onDismiss: @escaping () -> Void
    ) {
        self.dismissDelay = dismissDelay
        self.tickDurationNanoseconds = tickDurationNanoseconds
        self.onDismiss = onDismiss
    }

    func userDidInteract() {
        userInteracted = true
        cancelCountdown()
    }

    func cancelCountdown() {
        countdownTask?.cancel()
        countdownTask = nil
        secondsRemaining = nil
        activeCountdownTransferID = nil
    }

    func transferDidBegin() {
        cancelCountdown()
    }

    func evaluateDismissal(
        transfers: [FileTransferRow],
        presentedForActiveTransfer: Bool
    ) {
        // Only auto-dismiss if window was presented for active transfer and user hasn't explicitly interacted
        guard presentedForActiveTransfer, !userInteracted else {
            cancelCountdown()
            return
        }

        // Do not auto-dismiss while any transfer is in progress
        if transfers.contains(where: { $0.active }) {
            cancelCountdown()
            return
        }

        // Do not auto-dismiss if any transfer failed or requires user attention
        if transfers.contains(where: { $0.retryable || isFailureStatus($0.status) }) {
            cancelCountdown()
            return
        }

        // Find the most recently finished transfer
        guard let latest = transfers.sorted(by: { $0.startedAt > $1.startedAt }).first else {
            cancelCountdown()
            return
        }

        // Must be a successfully completed transfer
        guard isSuccessStatus(latest.status) else {
            cancelCountdown()
            return
        }

        // If countdown is already running for this exact transfer, let it continue
        if activeCountdownTransferID == latest.id && countdownTask != nil {
            return
        }

        // Cancel any competing countdown task
        cancelCountdown()

        activeCountdownTransferID = latest.id
        let targetID = latest.id
        let totalSeconds = max(1, Int(dismissDelay))
        secondsRemaining = totalSeconds

        countdownTask = Task { [weak self] in
            guard let self else { return }
            var remaining = totalSeconds
            while remaining > 0 {
                if self.tickDurationNanoseconds > 0 {
                    try? await Task.sleep(nanoseconds: self.tickDurationNanoseconds)
                }
                if Task.isCancelled { return }
                remaining -= 1
                self.secondsRemaining = remaining
            }

            guard !Task.isCancelled,
                  !self.userInteracted,
                  self.activeCountdownTransferID == targetID else {
                return
            }

            self.onDismiss()
        }
    }

    func isSuccessStatus(_ status: String) -> Bool {
        !isFailureStatus(status) &&
        (status.hasPrefix("Saved ") || status.contains("saved on Android") || status.localizedCaseInsensitiveContains("complete"))
    }

    func isFailureStatus(_ status: String) -> Bool {
        status.localizedCaseInsensitiveContains("cancel") ||
        status.localizedCaseInsensitiveContains("fail") ||
        status.localizedCaseInsensitiveContains("error")
    }

    deinit {
        countdownTask?.cancel()
    }
}

@MainActor
final class FileTransferWindowController: NSWindowController, NSWindowDelegate {
    private let pairing: PairingCoordinator
    let dismissManager: FileTransferAutoDismissManager
    private(set) var wasPresentedForActiveTransfer: Bool = false

    init(
        pairing: PairingCoordinator,
        dismissDelay: TimeInterval = 10.0,
        tickDurationNanoseconds: UInt64 = 1_000_000_000
    ) {
        self.pairing = pairing
        var holder: FileTransferWindowController?
        let manager = FileTransferAutoDismissManager(
            dismissDelay: dismissDelay,
            tickDurationNanoseconds: tickDurationNanoseconds,
            onDismiss: {
                holder?.window?.close()
            }
        )
        self.dismissManager = manager

        let root = FileTransferView(pairing: pairing, dismissManager: manager)
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 620, height: 360),
            styleMask: [.titled, .closable, .miniaturizable],
            backing: .buffered,
            defer: false
        )
        window.contentViewController = NSHostingController(rootView: root)
        window.title = "SYNQ File Transfer"
        window.minSize = NSSize(width: 520, height: 260)
        window.isReleasedWhenClosed = false
        super.init(window: window)
        holder = self
        window.delegate = self
        window.center()
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func show(forActiveTransfer: Bool = false) {
        if forActiveTransfer {
            wasPresentedForActiveTransfer = true
            dismissManager.userInteracted = false
        }
        NSApp.activate(ignoringOtherApps: true)
        showWindow(nil)
        window?.makeKeyAndOrderFront(nil)
        checkAutoDismiss()
    }

    func checkAutoDismiss() {
        dismissManager.evaluateDismissal(
            transfers: Array(pairing.fileTransfers.values),
            presentedForActiveTransfer: wasPresentedForActiveTransfer
        )
    }

    func windowWillClose(_ notification: Notification) {
        dismissManager.cancelCountdown()
    }
}

private struct FileTransferView: View {
    @ObservedObject var pairing: PairingCoordinator
    @ObservedObject var dismissManager: FileTransferAutoDismissManager

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .center) {
                Text("File Transfer").font(.title2).fontWeight(.semibold)
                Spacer()
                if let remaining = dismissManager.secondsRemaining, remaining > 0 {
                    HStack(spacing: 8) {
                        Text("Auto-closing in \(remaining)s")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Button("Keep Open") {
                            dismissManager.userDidInteract()
                        }
                        .controlSize(.small)
                        .buttonStyle(.bordered)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(Color.secondary.opacity(0.12), in: Capsule())
                }
            }

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 14) {
                    if pairing.fileTransfers.isEmpty {
                        Text("No recent file transfers").foregroundStyle(.secondary)
                    } else {
                        ForEach(pairing.fileTransfers.values.sorted(by: { $0.startedAt > $1.startedAt })) { transfer in
                            HStack(alignment: .center, spacing: 12) {
                                if transfer.active { ProgressView().controlSize(.small) }
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(transfer.name).fontWeight(.medium)
                                    Text(transfer.status).font(.caption.monospacedDigit()).foregroundStyle(.secondary).lineLimit(2)
                                }
                                Spacer()
                                if transfer.active {
                                    Button("Cancel", role: .destructive) {
                                        dismissManager.userDidInteract()
                                        pairing.cancelFileTransfer(id: transfer.id)
                                    }
                                } else if transfer.retryable {
                                    Button("Retry") {
                                        dismissManager.userDidInteract()
                                        pairing.retryFileTransfer(id: transfer.id)
                                    }
                                }
                            }
                            .contentShape(Rectangle())
                            .onTapGesture {
                                dismissManager.userDidInteract()
                            }
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .simultaneousGesture(TapGesture().onEnded {
                dismissManager.userDidInteract()
            })

            Divider()
            HStack {
                if pairing.fileTransferActive {
                    Button("Cancel All", role: .destructive) {
                        dismissManager.userDidInteract()
                        pairing.cancelFileTransfer()
                    }
                }
                if pairing.fileTransfers.values.contains(where: { !$0.active }) {
                    Button("Clear History") {
                        dismissManager.userDidInteract()
                        pairing.clearTransferHistory()
                    }
                }
                Spacer()
                Button("Close") {
                    dismissManager.cancelCountdown()
                    NSApp.keyWindow?.close()
                }
            }
        }
        .padding(24)
        .frame(minWidth: 500, minHeight: 240)
    }
}

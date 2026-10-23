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
                holder?.pairing.clearTransferHistory()
                holder?.window?.close()
            }
        )
        self.dismissManager = manager

        let root = FileTransferView(pairing: pairing, dismissManager: manager, onClose: { [weak holder] in
            holder?.pairing.clearTransferHistory()
            holder?.window?.close()
        })
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 280, height: 360),
            styleMask: [.titled, .closable, .miniaturizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        window.contentViewController = NSHostingController(rootView: root)
        window.title = "Transfers"
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.isMovableByWindowBackground = true
        window.minSize = NSSize(width: 270, height: 350)
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

    func windowShouldClose(_ sender: NSWindow) -> Bool {
        dismissManager.cancelCountdown()
        pairing.clearTransferHistory()
        return true
    }

    func windowWillClose(_ notification: Notification) {
        dismissManager.cancelCountdown()
        pairing.clearTransferHistory()
    }
}

private struct VisualEffectBackground: NSViewRepresentable {
    func makeNSView(context: Context) -> NSVisualEffectView {
        let view = NSVisualEffectView()
        view.blendingMode = .behindWindow
        view.state = .active
        view.material = .hudWindow
        return view
    }

    func updateNSView(_ nsView: NSVisualEffectView, context: Context) {}
}

private struct FileTransferView: View {
    @ObservedObject var pairing: PairingCoordinator
    @ObservedObject var dismissManager: FileTransferAutoDismissManager
    let onClose: () -> Void

    @State private var selectedIndex: Int = 0
    @State private var showListView: Bool = false

    private var transfers: [FileTransferRow] {
        pairing.fileTransfers.values.sorted(by: { $0.startedAt > $1.startedAt })
    }

    private var peerName: String {
        if case let .connected(_, name) = pairing.state {
            return name
        }
        return "Device"
    }

    var body: some View {
        ZStack {
            VisualEffectBackground()
                .ignoresSafeArea()

            if transfers.isEmpty {
                emptyView
            } else if showListView {
                listView
            } else {
                heroView
            }
        }
        .frame(minWidth: 270, minHeight: 350)
        .simultaneousGesture(TapGesture().onEnded {
            dismissManager.userDidInteract()
        })
    }

    private var heroView: some View {
        let current = transfers.indices.contains(selectedIndex) ? transfers[selectedIndex] : transfers[0]
        return FileTransferHeroCard(
            transfer: current,
            peerName: peerName,
            totalCount: transfers.count,
            currentIndex: selectedIndex,
            dismissManager: dismissManager,
            onCloseWindow: onClose,
            onPrevious: {
                if selectedIndex < transfers.count - 1 {
                    selectedIndex += 1
                }
            },
            onNext: {
                if selectedIndex > 0 {
                    selectedIndex -= 1
                }
            },
            onToggleList: {
                showListView = true
            },
            onCancel: {
                dismissManager.userDidInteract()
                pairing.cancelFileTransfer(id: current.id)
            },
            onRetry: {
                dismissManager.userDidInteract()
                pairing.retryFileTransfer(id: current.id)
            }
        )
    }

    private var listView: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Button {
                    showListView = false
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 10, weight: .bold))
                        Text("Back")
                            .font(.system(size: 11, weight: .medium))
                    }
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(Color.primary.opacity(0.08), in: Capsule())
                }
                .buttonStyle(.plain)

                Spacer()

                Text("All Transfers (\(transfers.count))")
                    .font(.system(size: 12, weight: .semibold))

                Spacer()

                if transfers.contains(where: { !$0.active }) {
                    Button("Clear") {
                        dismissManager.userDidInteract()
                        pairing.clearTransferHistory()
                        if pairing.fileTransfers.isEmpty {
                            showListView = false
                        }
                    }
                    .buttonStyle(.plain)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 14)
            .padding(.top, 12)

            ScrollView {
                LazyVStack(spacing: 6) {
                    ForEach(Array(transfers.enumerated()), id: \.element.id) { index, transfer in
                        FileTransferListRow(
                            transfer: transfer,
                            peerName: peerName,
                            onSelect: {
                                selectedIndex = index
                                showListView = false
                            },
                            onCancel: {
                                dismissManager.userDidInteract()
                                pairing.cancelFileTransfer(id: transfer.id)
                            },
                            onRetry: {
                                dismissManager.userDidInteract()
                                pairing.retryFileTransfer(id: transfer.id)
                            }
                        )
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 2)
            }
        }
    }

    private var emptyView: some View {
        VStack(spacing: 10) {
            HStack {
                Spacer().frame(width: 68)
                Spacer()
            }
            .frame(height: 32)

            Spacer()

            Image(systemName: "arrow.down.circle")
                .font(.system(size: 40))
                .foregroundStyle(Color.accentColor.opacity(0.8))

            Text("No Recent Transfers")
                .font(.system(size: 14, weight: .semibold))

            Text("Files sent from \(peerName) will appear here instantly.")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)

            Spacer()

            Button("Done") {
                dismissManager.cancelCountdown()
                onClose()
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.small)
            .padding(.bottom, 16)
        }
    }
}

private struct FileTransferHeroCard: View {
    let transfer: FileTransferRow
    let peerName: String
    let totalCount: Int
    let currentIndex: Int
    @ObservedObject var dismissManager: FileTransferAutoDismissManager
    let onCloseWindow: () -> Void
    let onPrevious: () -> Void
    let onNext: () -> Void
    let onToggleList: () -> Void
    let onCancel: () -> Void
    let onRetry: () -> Void

    @State private var previewImage: NSImage?
    @State private var copiedFeedback: Bool = false

    private var isImageFile: Bool {
        let ext = (transfer.name as NSString).pathExtension.lowercased()
        return ["png", "jpg", "jpeg", "heic", "heif", "webp", "gif", "tiff", "bmp"].contains(ext)
    }

    private var fileIcon: NSImage {
        if let dest = transfer.destinationURL {
            let icon = NSWorkspace.shared.icon(forFile: dest.path)
            icon.size = NSSize(width: 64, height: 64)
            return icon
        }
        let ext = (transfer.name as NSString).pathExtension
        let icon = NSWorkspace.shared.icon(forFileType: ext)
        icon.size = NSSize(width: 64, height: 64)
        return icon
    }

    private var formattedSize: String {
        guard let size = transfer.fileSize, size > 0 else { return "" }
        return ByteCountFormatter.string(fromByteCount: size, countStyle: .file)
    }

    private var isCompleted: Bool {
        !transfer.active && (transfer.destinationURL != nil || transfer.status.hasPrefix("Saved ") || transfer.status.contains("saved on Android") || transfer.status.localizedCaseInsensitiveContains("complete"))
    }

    private var isFailed: Bool {
        !transfer.active && (transfer.status.localizedCaseInsensitiveContains("fail") || transfer.status.localizedCaseInsensitiveContains("cancel") || transfer.status.localizedCaseInsensitiveContains("error"))
    }

    private var subtitleText: String {
        if transfer.active {
            return "Transferring from \(peerName)…"
        } else if isCompleted {
            return "Received from \(peerName)"
        } else if isFailed {
            return transfer.status
        } else {
            return transfer.status
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            // Top Navigation / Header bar
            HStack(alignment: .center) {
                // Keep space for macOS traffic lights on the left
                Spacer().frame(width: 68)

                if totalCount > 1 {
                    HStack(spacing: 5) {
                        Button(action: onPrevious) {
                            Image(systemName: "chevron.left")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundStyle(currentIndex < totalCount - 1 ? Color.primary : Color.secondary.opacity(0.3))
                        }
                        .buttonStyle(.plain)
                        .disabled(currentIndex >= totalCount - 1)

                        Text("\(currentIndex + 1) of \(totalCount)")
                            .font(.system(size: 10, weight: .medium).monospacedDigit())
                            .foregroundStyle(.secondary)

                        Button(action: onNext) {
                            Image(systemName: "chevron.right")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundStyle(currentIndex > 0 ? Color.primary : Color.secondary.opacity(0.3))
                        }
                        .buttonStyle(.plain)
                        .disabled(currentIndex == 0)
                    }
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Color.primary.opacity(0.06), in: Capsule())
                }

                Spacer()

                if let remaining = dismissManager.secondsRemaining, remaining > 0 {
                    HStack(spacing: 4) {
                        Text("Closing in \(remaining)s")
                            .font(.system(size: 10, weight: .medium))
                            .foregroundStyle(.secondary)
                        Button("Keep Open") {
                            dismissManager.userDidInteract()
                        }
                        .buttonStyle(.plain)
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(Color.accentColor)
                    }
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Color.primary.opacity(0.06), in: Capsule())
                } else if totalCount > 1 {
                    Button(action: onToggleList) {
                        Image(systemName: "list.bullet")
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                    .help("View all transfers")
                }
            }
            .padding(.horizontal, 14)
            .padding(.top, 10)
            .frame(height: 30)

            Spacer(minLength: 6)

            // Center Preview Card (Draggable!)
            ZStack(alignment: .bottom) {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(Color.primary.opacity(0.05))
                    .overlay(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .stroke(Color.primary.opacity(0.08), lineWidth: 1)
                    )
                    .frame(width: 195, height: 160)

                // Preview content
                Group {
                    if let img = previewImage {
                        Image(nsImage: img)
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(maxWidth: 180, maxHeight: 145)
                            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                            .shadow(color: .black.opacity(0.2), radius: 5, x: 0, y: 2)
                    } else {
                        VStack(spacing: 8) {
                            Image(nsImage: fileIcon)
                                .resizable()
                                .frame(width: 64, height: 64)
                                .shadow(color: .black.opacity(0.15), radius: 4, x: 0, y: 2)

                            if !formattedSize.isEmpty && !isCompleted {
                                Text(formattedSize)
                                    .font(.caption2.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                .frame(width: 195, height: 160)

                // Bottom badge overlapping bottom border of preview
                if isCompleted {
                    ZStack {
                        Circle()
                            .fill(Color(nsColor: .systemGreen))
                            .frame(width: 34, height: 34)
                            .shadow(color: .black.opacity(0.25), radius: 3, x: 0, y: 2)
                        Image(systemName: "checkmark")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(.white)
                    }
                    .offset(y: 17)
                    .allowsHitTesting(false)
                } else if transfer.active {
                    ZStack {
                        Circle()
                            .fill(Color.accentColor)
                            .frame(width: 34, height: 34)
                            .shadow(color: .black.opacity(0.25), radius: 3, x: 0, y: 2)
                        if let percent = transfer.progressPercent {
                            Text("\(Int(percent))%")
                                .font(.system(size: 10, weight: .bold).monospacedDigit())
                                .foregroundStyle(.white)
                        } else {
                            ProgressView()
                                .controlSize(.small)
                                .colorInvert()
                        }
                    }
                    .offset(y: 17)
                    .allowsHitTesting(false)
                } else if isFailed {
                    ZStack {
                        Circle()
                            .fill(Color(nsColor: .systemRed))
                            .frame(width: 34, height: 34)
                            .shadow(color: .black.opacity(0.25), radius: 3, x: 0, y: 2)
                        Image(systemName: "exclamationmark")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(.white)
                    }
                    .offset(y: 17)
                    .allowsHitTesting(false)
                }
            }
            .frame(width: 195, height: 177)
            .contentShape(Rectangle())
            .onDrag {
                guard let url = transfer.destinationURL else { return NSItemProvider() }
                dismissManager.userDidInteract()
                return NSItemProvider(contentsOf: url) ?? NSItemProvider(item: url as NSURL, typeIdentifier: "public.file-url")
            }
            .help(transfer.destinationURL != nil ? "Drag to Finder, Desktop, or apps" : "")

            Spacer(minLength: 12)

            // Filename pill badge & source info
            VStack(spacing: 4) {
                Text(transfer.name)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.white)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
                    .truncationMode(.middle)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(Color.accentColor, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .frame(maxWidth: 220)

                Text(subtitleText)
                    .font(.system(size: 12, weight: .regular))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 12)

            // Bottom Action Buttons
            HStack(spacing: 10) {
                if transfer.active {
                    Button(action: onCancel) {
                        Text("Cancel")
                            .font(.system(size: 12, weight: .medium))
                            .frame(maxWidth: .infinity)
                            .frame(height: 35)
                            .background(Color.primary.opacity(0.1), in: Capsule())
                    }
                    .buttonStyle(.plain)
                } else if isFailed && transfer.retryable {
                    Button(action: onRetry) {
                        Text("Retry")
                            .font(.system(size: 12, weight: .medium))
                            .frame(maxWidth: .infinity)
                            .frame(height: 35)
                            .background(Color.accentColor, in: Capsule())
                            .foregroundStyle(.white)
                    }
                    .buttonStyle(.plain)
                } else {
                    // Copy button (subtle pill)
                    Button {
                        copyFileToClipboard()
                    } label: {
                        HStack(spacing: 5) {
                            if copiedFeedback {
                                Image(systemName: "checkmark")
                                    .font(.system(size: 11, weight: .bold))
                                Text("Copied")
                                    .font(.system(size: 12, weight: .medium))
                            } else {
                                Text("Copy")
                                    .font(.system(size: 12, weight: .medium))
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 35)
                        .foregroundStyle(.primary)
                        .background(Color.primary.opacity(0.12), in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(transfer.destinationURL == nil)

                    // Open button (vibrant blue capsule with split chevron)
                    HStack(spacing: 0) {
                        Button {
                            openFile()
                        } label: {
                            Text("Open")
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(.white)
                                .frame(maxWidth: .infinity)
                                .frame(height: 35)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)

                        Divider()
                            .frame(width: 1, height: 18)
                            .background(Color.white.opacity(0.3))

                        Menu {
                            Button {
                                openFile()
                            } label: {
                                Label("Open with Default App", systemImage: "arrow.up.forward.app")
                            }
                            Button {
                                revealInFinder()
                            } label: {
                                Label("Show in Finder", systemImage: "folder")
                            }
                            Button {
                                copyFilePath()
                            } label: {
                                Label("Copy File Path", systemImage: "doc.on.doc")
                            }
                        } label: {
                            Image(systemName: "chevron.down")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundStyle(.white)
                                .frame(width: 28, height: 35)
                                .contentShape(Rectangle())
                        }
                        .menuStyle(.borderlessButton)
                        .menuIndicator(.hidden)
                    }
                    .background(Color.accentColor, in: Capsule())
                    .frame(maxWidth: .infinity)
                    .disabled(transfer.destinationURL == nil)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 14)
        }
        .task(id: transfer.destinationURL) {
            loadPreview()
        }
    }

    private func loadPreview() {
        guard let url = transfer.destinationURL, isImageFile else {
            previewImage = nil
            return
        }
        Task.detached(priority: .userInitiated) {
            let img = NSImage(contentsOf: url)
            await MainActor.run {
                self.previewImage = img
            }
        }
    }

    private func copyFileToClipboard() {
        dismissManager.userDidInteract()
        guard let url = transfer.destinationURL else { return }
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        var items: [NSPasteboardWriting] = [url as NSURL]
        if let img = previewImage ?? NSImage(contentsOf: url) {
            items.append(img)
        }
        pasteboard.writeObjects(items)
        withAnimation(.easeInOut(duration: 0.15)) {
            copiedFeedback = true
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            withAnimation {
                copiedFeedback = false
            }
        }
    }

    private func openFile() {
        dismissManager.cancelCountdown()
        guard let url = transfer.destinationURL else { return }
        NSWorkspace.shared.open(url)
        onCloseWindow()
    }

    private func revealInFinder() {
        dismissManager.cancelCountdown()
        guard let url = transfer.destinationURL else { return }
        NSWorkspace.shared.activateFileViewerSelecting([url])
        onCloseWindow()
    }

    private func copyFilePath() {
        dismissManager.userDidInteract()
        guard let url = transfer.destinationURL else { return }
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(url.path, forType: .string)
    }
}

private struct FileTransferListRow: View {
    let transfer: FileTransferRow
    let peerName: String
    let onSelect: () -> Void
    let onCancel: () -> Void
    let onRetry: () -> Void

    private var fileIcon: NSImage {
        let ext = (transfer.name as NSString).pathExtension
        let icon = NSWorkspace.shared.icon(forFileType: ext)
        icon.size = NSSize(width: 28, height: 28)
        return icon
    }

    private var formattedSize: String {
        guard let size = transfer.fileSize, size > 0 else { return "" }
        return ByteCountFormatter.string(fromByteCount: size, countStyle: .file)
    }

    private var isCompleted: Bool {
        !transfer.active && (transfer.destinationURL != nil || transfer.status.hasPrefix("Saved ") || transfer.status.contains("saved on Android") || transfer.status.localizedCaseInsensitiveContains("complete"))
    }

    var body: some View {
        HStack(spacing: 8) {
            Image(nsImage: fileIcon)
                .resizable()
                .frame(width: 28, height: 28)

            VStack(alignment: .leading, spacing: 2) {
                Text(transfer.name)
                    .font(.system(size: 12, weight: .medium))
                    .lineLimit(1)
                HStack(spacing: 5) {
                    if !formattedSize.isEmpty {
                        Text(formattedSize)
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.secondary)
                        Text("·")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    if isCompleted {
                        Text("Saved")
                            .font(.caption2.weight(.medium))
                            .foregroundStyle(Color.green)
                    } else if transfer.active {
                        Text(transfer.progressPercent.map { "\(Int($0))%" } ?? "Transferring…")
                            .font(.caption2.monospacedDigit().weight(.medium))
                            .foregroundStyle(Color.accentColor)
                    } else {
                        Text(transfer.status)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
            }

            Spacer()

            if let dest = transfer.destinationURL {
                Button {
                    NSWorkspace.shared.open(dest)
                } label: {
                    Text("Open")
                        .font(.caption2.weight(.semibold))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Color.accentColor, in: Capsule())
                        .foregroundStyle(.white)
                }
                .buttonStyle(.plain)
            } else if transfer.active {
                Button(action: onCancel) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 14))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            } else if transfer.retryable {
                Button("Retry", action: onRetry)
                    .controlSize(.mini)
            }
        }
        .padding(8)
        .background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .contentShape(Rectangle())
        .onTapGesture(perform: onSelect)
    }
}

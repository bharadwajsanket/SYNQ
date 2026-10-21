import AppKit
import SwiftUI

@MainActor
final class OtpPromptController: ObservableObject {
    static let shared = OtpPromptController()

    @Published private(set) var code: String = ""
    @Published private(set) var sender: String = ""
    @Published private(set) var secondsRemaining: Int = 0
    @Published private(set) var isCopied: Bool = false
    @Published private(set) var isExpired: Bool = false

    private var window: NSPanel?
    private var timer: Timer?
    private var autoDismissTask: Task<Void, Never>?

    private init() {}

    func present(code: String, sender: String, expirySeconds: Int = 180) {
        timer?.invalidate()
        autoDismissTask?.cancel()

        self.code = code
        self.sender = sender
        self.secondsRemaining = max(10, min(600, expirySeconds))
        self.isCopied = false
        self.isExpired = false

        if window == nil {
            let panel = NSPanel(
                contentRect: NSRect(x: 0, y: 0, width: 320, height: 165),
                styleMask: [.titled, .closable, .nonactivatingPanel, .fullSizeContentView],
                backing: .buffered,
                defer: false
            )
            panel.isFloatingPanel = true
            panel.level = .floating
            panel.titleVisibility = .hidden
            panel.titlebarAppearsTransparent = true
            panel.isMovableByWindowBackground = true
            panel.backgroundColor = .clear
            panel.isOpaque = false
            panel.hasShadow = true
            panel.contentViewController = NSHostingController(rootView: OtpPromptView(controller: self))
            window = panel
        }

        positionWindow()
        window?.orderFrontRegardless()
        NSSound(named: NSSound.Name("Glass"))?.play()

        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                if self.secondsRemaining > 1 {
                    self.secondsRemaining -= 1
                } else {
                    self.secondsRemaining = 0
                    self.isExpired = true
                    self.timer?.invalidate()
                    self.timer = nil
                    // Auto-dismiss 3 seconds after expiry
                    self.autoDismissTask = Task {
                        try? await Task.sleep(nanoseconds: 3_000_000_000)
                        self.dismiss()
                    }
                }
            }
        }
    }

    func copyCode() {
        guard !isExpired, !code.isEmpty else { return }
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(code, forType: .string)
        isCopied = true

        autoDismissTask?.cancel()
        autoDismissTask = Task {
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            self.dismiss()
        }
    }

    func dismiss() {
        timer?.invalidate()
        timer = nil
        autoDismissTask?.cancel()
        autoDismissTask = nil
        code = ""
        sender = ""
        isCopied = false
        isExpired = false
        window?.orderOut(nil)
    }

    private func positionWindow() {
        guard let panel = window, let screen = NSScreen.main else { return }
        let screenRect = screen.visibleFrame
        let x = screenRect.maxX - panel.frame.width - 24
        let y = screenRect.maxY - panel.frame.height - 24
        panel.setFrameOrigin(NSPoint(x: x, y: y))
    }
}

struct OtpPromptView: View {
    @ObservedObject var controller: OtpPromptController
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 7) {
                Image(nsImage: makeSynqDeviceIcon(size: 18))
                    .frame(width: 18, height: 18)
                Text("SYNQ")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.primary)
                Text("•")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                Text("Verification Code")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                if !controller.sender.isEmpty {
                    Text("(\(controller.sender))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer()
                Button(action: controller.dismiss) {
                    Image(systemName: "xmark")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(.secondary)
                        .padding(5)
                        .background(Color.primary.opacity(0.08), in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Dismiss")
            }

            if controller.isCopied {
                HStack(spacing: 8) {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                        .font(.title2)
                    Text("Copied to clipboard!")
                        .font(.headline)
                        .foregroundStyle(.primary)
                }
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.vertical, 10)
            } else {
                HStack {
                    Text(controller.code)
                        .font(.system(size: 26, weight: .bold, design: .monospaced))
                        .tracking(4)
                        .foregroundStyle(controller.isExpired ? .secondary : .primary)
                    Spacer()
                }

                HStack {
                    if controller.isExpired {
                        Text("Code expired")
                            .font(.caption)
                            .foregroundStyle(.red)
                    } else {
                        let mins = controller.secondsRemaining / 60
                        let secs = controller.secondsRemaining % 60
                        Text("Expires in \(mins):\(String(format: "%02d", secs))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("Dismiss", action: controller.dismiss)
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                    Button(action: controller.copyCode) {
                        Label("Copy", systemImage: "doc.on.doc")
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
                    .disabled(controller.isExpired)
                }
            }
        }
        .padding(14)
        .frame(width: 320)
        .background {
            if reduceTransparency {
                Color(nsColor: .windowBackgroundColor)
            } else {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(.ultraThinMaterial)
            }
        }
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(Color.primary.opacity(0.08), lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

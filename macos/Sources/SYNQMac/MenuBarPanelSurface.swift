import SwiftUI
import AppKit

/// A menu-bar surface that relies on the native system window material to avoid double-borders or shadows,
/// while providing genuine Liquid Glass interaction to its component cards and actions.
struct MenuBarPanelSurface<Content: View>: View {
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        content
            .frame(width: 340, alignment: .topLeading)
            .fixedSize(horizontal: false, vertical: true)
            .background(MenuBarWindowAccessorRepresentable())
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.12), lineWidth: 0.5)
            )
    }
}

private struct MenuBarWindowAccessorRepresentable: NSViewRepresentable {
    func makeNSView(context: Context) -> MenuBarWindowAccessorView {
        MenuBarWindowAccessorView()
    }

    func updateNSView(_ nsView: MenuBarWindowAccessorView, context: Context) {
        nsView.configureWindowIfNeeded()
    }
}

final class MenuBarWindowAccessorView: NSView {
    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        configureWindowIfNeeded()
    }

    func configureWindowIfNeeded() {
        guard let window = self.window else { return }
        window.isOpaque = false
        window.backgroundColor = .clear
        window.contentView?.wantsLayer = true
        window.contentView?.layer?.cornerRadius = 16
        window.contentView?.layer?.masksToBounds = true
        window.hasShadow = true
        window.invalidateShadow()

        MenuBarWindowPinner.shared.pinWindowTop(window)
    }
}

final class MenuBarWindowPinner: NSObject {
    static let shared = MenuBarWindowPinner()
    private var lastTopYByWindow: [ObjectIdentifier: CGFloat] = [:]

    func pinWindowTop(_ window: NSWindow) {
        let id = ObjectIdentifier(window)
        if lastTopYByWindow[id] == nil {
            if window.isVisible && window.frame.height > 50 {
                lastTopYByWindow[id] = window.frame.maxY
            }
            NotificationCenter.default.addObserver(
                self,
                selector: #selector(windowDidResize(_:)),
                name: NSWindow.didResizeNotification,
                object: window
            )
            NotificationCenter.default.addObserver(
                self,
                selector: #selector(windowDidMove(_:)),
                name: NSWindow.didMoveNotification,
                object: window
            )
            NotificationCenter.default.addObserver(
                self,
                selector: #selector(windowWillClose(_:)),
                name: NSWindow.willCloseNotification,
                object: window
            )
        }
    }

    @objc private func windowDidMove(_ notification: Notification) {
        guard let window = notification.object as? NSWindow else { return }
        let id = ObjectIdentifier(window)
        // If window is positioned or moved by AppKit/StatusItem, record its top
        if window.isVisible && window.frame.height > 50 {
            lastTopYByWindow[id] = window.frame.maxY
        }
    }

    @objc private func windowDidResize(_ notification: Notification) {
        guard let window = notification.object as? NSWindow else { return }
        let id = ObjectIdentifier(window)
        guard let targetTop = lastTopYByWindow[id] else {
            if window.isVisible && window.frame.height > 50 {
                lastTopYByWindow[id] = window.frame.maxY
            }
            return
        }
        if window.isVisible && window.frame.height > 50 && abs(window.frame.maxY - targetTop) >= 0.5 {
            let newY = targetTop - window.frame.height
            window.setFrameOrigin(NSPoint(x: window.frame.origin.x, y: newY))
        }
    }

    @objc private func windowWillClose(_ notification: Notification) {
        guard let window = notification.object as? NSWindow else { return }
        let id = ObjectIdentifier(window)
        lastTopYByWindow.removeValue(forKey: id)
        NotificationCenter.default.removeObserver(self, name: NSWindow.didResizeNotification, object: window)
        NotificationCenter.default.removeObserver(self, name: NSWindow.didMoveNotification, object: window)
        NotificationCenter.default.removeObserver(self, name: NSWindow.willCloseNotification, object: window)
    }
}

/// A view modifier that applies authentic Apple Liquid Glass (`glassEffect`) where supported (macOS 26+),
/// with native-feeling depth, translucency, and edge light refraction, falling back gracefully on macOS 13+.
struct NativeGlassBackgroundModifier: ViewModifier {
    let isPressed: Bool
    let isHovered: Bool
    let cornerRadius: CGFloat
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        if #available(macOS 26.0, *) {
            let glass: Glass = isPressed
                ? .regular.tint(Color.accentColor.opacity(0.3)).interactive()
                : (isHovered ? .regular.tint(Color.white.opacity(0.12)).interactive() : .regular.interactive())
            content
                .glassEffect(glass, in: shape)
        } else {
            content
                .background {
                    if reduceTransparency {
                        shape.fill(isPressed ? Color(nsColor: .selectedControlColor) : (isHovered ? Color(nsColor: .controlColor) : Color(nsColor: .controlBackgroundColor)))
                    } else {
                        shape.fill(isPressed ? Color.accentColor.opacity(0.22) : (isHovered ? Color.primary.opacity(0.10) : Color.primary.opacity(0.05)))
                    }
                }
        }
    }
}

/// A container card modifier utilizing native Liquid Glass on macOS 26+ without artificial strokes or double borders.
struct NativeLiquidGlassCardModifier: ViewModifier {
    let cornerRadius: CGFloat
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        if #available(macOS 26.0, *) {
            content
                .glassEffect(.regular, in: shape)
        } else {
            content
                .background(
                    reduceTransparency ? Color(nsColor: .controlBackgroundColor) : Color.primary.opacity(0.05),
                    in: shape
                )
        }
    }
}

/// Button style for menu bar quick actions with native hover, pressed states, and genuine Liquid Glass gating.
struct NativeLiquidGlassActionButtonStyle: ButtonStyle {
    @State private var isHovered = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .modifier(NativeGlassBackgroundModifier(isPressed: configuration.isPressed, isHovered: isHovered, cornerRadius: 10))
            .onHover { isHovered = $0 }
    }
}

struct FileTransfersSummaryButton: View {
    let count: Int
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: "arrow.left.arrow.right.circle.fill")
                    .foregroundStyle(Color.accentColor)
                Text("File transfers")
                    .fontWeight(.medium)
                Spacer()
                Text("\(count)")
                    .font(.caption2.monospacedDigit())
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Color.secondary.opacity(0.15), in: Capsule())
                Image(systemName: "chevron.right")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
        }
        .buttonStyle(NativeLiquidGlassActionButtonStyle())
    }
}

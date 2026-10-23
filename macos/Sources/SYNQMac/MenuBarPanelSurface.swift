import SwiftUI
import AppKit

/// Keep the MenuBarExtra's own window as the only glass surface. Wrapping the
/// content in a second NSGlassEffectView draws a second rim and clips the
/// system popover's naturally rounded silhouette.
struct MenuBarPanelSurface<Content: View>: View {
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        let panelShape = RoundedRectangle(cornerRadius: 32, style: .continuous)
        content
            .frame(width: 340, alignment: .topLeading)
            .background {
                if #available(macOS 26.0, *) {
                    Color.clear
                        .glassEffect(.regular, in: panelShape)
                } else {
                    panelShape.fill(.regularMaterial)
                }
            }
            .background(MenuBarWindowAccessorRepresentable())
    }
}

private struct MenuBarWindowAccessorRepresentable: NSViewRepresentable {
    func makeNSView(context: Context) -> MenuBarWindowAccessorView {
        let view = MenuBarWindowAccessorView()
        // This representable exists only to configure the hosting popover.
        // Keep its own backing layer transparent; otherwise its rectangular
        // view can show through the curved corners when the home screen is
        // restored after disconnecting.
        view.wantsLayer = true
        view.layer?.isOpaque = false
        view.layer?.backgroundColor = NSColor.clear.cgColor
        return view
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
        if #available(macOS 26.0, *) {
            // The SwiftUI glass shape draws the panel. Let its rounded corners
            // reveal the desktop instead of the rectangular popover backing.
            window.isOpaque = false
            window.backgroundColor = .clear
            window.hasShadow = false
            if let contentView = window.contentView {
                contentView.wantsLayer = true
                contentView.layer?.isOpaque = false
                contentView.layer?.backgroundColor = NSColor.clear.cgColor
                contentView.layer?.cornerRadius = 32
                contentView.layer?.cornerCurve = .continuous
                contentView.layer?.masksToBounds = true
            }
        }
        MenuBarWindowPinner.shared.pinWindowTop(window)
        MenuBarWindowPinner.shared.fitWindowToContent(window)
    }
}

final class MenuBarWindowPinner: NSObject {
    static let shared = MenuBarWindowPinner()
    private var lastTopYByWindow: [ObjectIdentifier: CGFloat] = [:]
    private var fitWorkItems: [ObjectIdentifier: DispatchWorkItem] = [:]

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
                selector: #selector(windowWillClose(_:)),
                name: NSWindow.willCloseNotification,
                object: window
            )
        }
    }

    func fitWindowToContent(_ window: NSWindow) {
        let id = ObjectIdentifier(window)
        fitWorkItems[id]?.cancel()
        let work = DispatchWorkItem { [weak self, weak window] in
            guard let self, let window,
                  window.contentView != nil else { return }
            self.fitWindowToContentNow(window)
        }
        fitWorkItems[id] = work
        DispatchQueue.main.async(execute: work)
    }

    func fitWindowToContentNow(_ window: NSWindow) {
        guard let contentView = window.contentView else { return }
        contentView.layoutSubtreeIfNeeded()
        let fittedHeight = (window.contentViewController?.view.fittingSize ?? contentView.fittingSize).height
        guard fittedHeight.isFinite, fittedHeight > 50,
              abs(window.frame.height - fittedHeight) > 1 else { return }

        let id = ObjectIdentifier(window)
        let top = lastTopYByWindow[id] ?? window.frame.maxY
        lastTopYByWindow[id] = top
        var frame = window.frame
        frame.origin.y = top - fittedHeight
        frame.size.height = fittedHeight
        window.setFrame(frame, display: true, animate: false)
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
        fitWorkItems.removeValue(forKey: id)?.cancel()
        NotificationCenter.default.removeObserver(self, name: NSWindow.didResizeNotification, object: window)
        NotificationCenter.default.removeObserver(self, name: NSWindow.willCloseNotification, object: window)
    }
}

/// Content cards stay clear on current macOS so they sit directly on the native
/// MenuBarExtra glass surface. Older systems get a restrained material fallback.
struct NativeCardMaterialFallbackModifier: ViewModifier {
    let cornerRadius: CGFloat

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        if #available(macOS 26.0, *) {
            content
        } else {
            content
                .background(.regularMaterial, in: shape)
        }
    }
}

/// Use Apple's own glass button style on macOS 26+, with standard native
/// bordered controls as the fallback for earlier deployment targets.
struct NativeLiquidGlassButtonModifier: ViewModifier {
    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(macOS 26.0, *) {
            content
                .buttonStyle(.glass)
                .buttonBorderShape(.capsule)
        } else if #available(macOS 14.0, *) {
            content
                .buttonStyle(.bordered)
                .buttonBorderShape(.capsule)
        } else {
            content.buttonStyle(.bordered)
        }
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
        .modifier(NativeLiquidGlassButtonModifier())
    }
}

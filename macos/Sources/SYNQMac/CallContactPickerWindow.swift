import AppKit
import SwiftUI

@MainActor
final class CallContactPickerWindowController: NSWindowController {
    init(
        favourites: FavouriteContactsStore,
        onDial: @escaping (String) -> Void,
        onManualDial: @escaping () -> Void,
        onSyncContacts: @escaping () -> Void
    ) {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 500, height: 560),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.title = "Call from Mac"
        window.minSize = NSSize(width: 420, height: 480)
        window.isReleasedWhenClosed = false
        window.setFrameAutosaveName("SYNQCallContactPickerWindow")
        window.center()

        super.init(window: window)

        window.contentView = NSHostingView(rootView: CallContactPickerView(
            favourites: favourites,
            onDial: { [weak self] number in
                onDial(number)
                self?.close()
            },
            onManualDial: { [weak self] in
                onManualDial()
                self?.close()
            },
            onSyncContacts: onSyncContacts,
            onClose: { [weak self] in self?.close() }
        ))
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func show() {
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }
}

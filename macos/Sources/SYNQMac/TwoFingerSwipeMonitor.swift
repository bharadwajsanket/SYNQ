import SwiftUI
import AppKit

extension Notification.Name {
    static let synqSwipeBack = Notification.Name("dev.synq.swipeBack")
}

final class TwoFingerSwipeMonitor {
    static let shared = TwoFingerSwipeMonitor()
    private var monitor: Any?
    private var accumulatedDeltaX: CGFloat = 0
    private var accumulatedDeltaY: CGFloat = 0
    private var hasTriggeredInCurrentGesture = false

    init() {}

    func start() {
        guard monitor == nil else { return }
        monitor = NSEvent.addLocalMonitorForEvents(matching: [.scrollWheel, .swipe]) { [weak self] event in
            guard let self else { return event }

            if event.type == .swipe {
                // event.deltaX == 1.0 indicates a two-finger left-to-right swipe (Back)
                if event.deltaX > 0 {
                    DispatchQueue.main.async {
                        NotificationCenter.default.post(name: .synqSwipeBack, object: nil)
                    }
                    return nil
                }
                return event
            }

            if event.type == .scrollWheel, event.hasPreciseScrollingDeltas {
                let phase = event.phase
                if phase == .began {
                    self.accumulatedDeltaX = 0
                    self.accumulatedDeltaY = 0
                    self.hasTriggeredInCurrentGesture = false
                } else if phase == .changed {
                    let deltaX = event.isDirectionInvertedFromDevice ? event.scrollingDeltaX : -event.scrollingDeltaX
                    self.accumulatedDeltaX += deltaX
                    self.accumulatedDeltaY += event.scrollingDeltaY

                    // If user swiped predominantly left-to-right past threshold:
                    if !self.hasTriggeredInCurrentGesture &&
                       self.accumulatedDeltaX > 35 &&
                       self.accumulatedDeltaX > abs(self.accumulatedDeltaY) * 1.3 {
                        self.hasTriggeredInCurrentGesture = true
                        DispatchQueue.main.async {
                            NotificationCenter.default.post(name: .synqSwipeBack, object: nil)
                        }
                    }
                } else if phase == .ended || phase == .cancelled {
                    self.accumulatedDeltaX = 0
                    self.accumulatedDeltaY = 0
                    self.hasTriggeredInCurrentGesture = false
                }
            }
            return event
        }
    }

    func stop() {
        if let monitor {
            NSEvent.removeMonitor(monitor)
            self.monitor = nil
        }
    }

    deinit {
        stop()
    }
}

struct SwipeBackGestureModifier: ViewModifier {
    let onSwipeBack: () -> Void

    func body(content: Content) -> some View {
        content
            .gesture(
                DragGesture(minimumDistance: 25)
                    .onEnded { value in
                        if value.translation.width > 45 && abs(value.translation.height) < 35 {
                            onSwipeBack()
                        }
                    }
            )
    }
}

extension View {
    func onSwipeBack(perform action: @escaping () -> Void) -> some View {
        modifier(SwipeBackGestureModifier(onSwipeBack: action))
    }
}


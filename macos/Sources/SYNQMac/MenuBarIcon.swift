import AppKit

public enum MenuBarConnectionState: Equatable {
    case connected
    case connecting(pulse: Bool)
    case disconnected
}

public func makeMenuBarIcon(state: MenuBarConnectionState = .connected) -> NSImage {
    let size = NSSize(width: 18, height: 18)
    let image = NSImage(size: size, flipped: false) { _ in
        guard let context = NSGraphicsContext.current?.cgContext else { return false }

        context.setAllowsAntialiasing(true)
        context.setShouldAntialias(true)
        context.setLineCap(.round)
        context.setLineJoin(.round)

        // 1. Mac Stand Foot & Neck
        let standFoot = CGPath(roundedRect: CGRect(x: 3.5, y: 1.8, width: 5.5, height: 1.2), cornerWidth: 0.6, cornerHeight: 0.6, transform: nil)
        context.addPath(standFoot)
        context.setFillColor(NSColor.black.cgColor)
        context.fillPath()

        let standNeck = CGPath(roundedRect: CGRect(x: 5.5, y: 2.8, width: 1.5, height: 1.6), cornerWidth: 0.5, cornerHeight: 0.5, transform: nil)
        context.addPath(standNeck)
        context.fillPath()

        // 2. Mac Monitor Frame
        let macRect = CGRect(x: 1.2, y: 4.2, width: 10.6, height: 8.2)
        let macPath = CGPath(roundedRect: macRect, cornerWidth: 2.0, cornerHeight: 2.0, transform: nil)
        context.addPath(macPath)
        context.setLineWidth(1.25)
        context.setStrokeColor(NSColor.black.cgColor)
        context.strokePath()

        // 3. Knockout behind Phone for clean overlay
        let phoneCutout = CGPath(roundedRect: CGRect(x: 8.6, y: 1.2, width: 8.4, height: 13.2), cornerWidth: 2.8, cornerHeight: 2.8, transform: nil)
        context.saveGState()
        context.setBlendMode(.clear)
        context.addPath(phoneCutout)
        context.fillPath()
        context.restoreGState()

        // 4. Phone Frame
        let phoneRect = CGRect(x: 9.5, y: 2.0, width: 6.8, height: 11.8)
        let phonePath = CGPath(roundedRect: phoneRect, cornerWidth: 2.2, cornerHeight: 2.2, transform: nil)
        context.addPath(phonePath)
        context.setLineWidth(1.25)
        context.setStrokeColor(NSColor.black.cgColor)
        context.strokePath()

        // 5. Connecting Sync Bridge between Mac and Phone
        let bridgePath = CGMutablePath()
        bridgePath.move(to: CGPoint(x: 5.4, y: 8.5))
        bridgePath.addCurve(to: CGPoint(x: 12.9, y: 7.6),
                            control1: CGPoint(x: 7.8, y: 9.8),
                            control2: CGPoint(x: 10.2, y: 5.8))

        switch state {
        case .connected:
            context.addPath(bridgePath)
            context.setLineWidth(1.5)
            context.setStrokeColor(NSColor.black.cgColor)
            context.strokePath()

            context.addArc(center: CGPoint(x: 5.4, y: 8.5), radius: 1.0, startAngle: 0, endAngle: .pi * 2, clockwise: true)
            context.fillPath()

            context.addArc(center: CGPoint(x: 12.9, y: 7.6), radius: 1.0, startAngle: 0, endAngle: .pi * 2, clockwise: true)
            context.fillPath()

        case let .connecting(pulse):
            let dashes: [CGFloat] = [2.0, 1.4]
            context.setLineDash(phase: 0, lengths: dashes)
            context.addPath(bridgePath)
            context.setLineWidth(1.2)
            let alpha: CGFloat = pulse ? 0.9 : 0.2
            context.setStrokeColor(NSColor.black.withAlphaComponent(alpha).cgColor)
            context.strokePath()

        case .disconnected:
            // Connection line is completely hidden
            break
        }
        return true
    }
    image.isTemplate = true
    return image
}

public func makeMenuBarIcon(connected: Bool) -> NSImage {
    makeMenuBarIcon(state: connected ? .connected : .disconnected)
}


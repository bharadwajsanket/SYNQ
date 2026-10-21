import AppKit

func drawMasterIcon() -> NSImage {
    let size = NSSize(width: 1024, height: 1024)
    let image = NSImage(size: size)
    image.lockFocus()
    guard let context = NSGraphicsContext.current?.cgContext else {
        fatalError("No CGContext")
    }

    context.setAllowsAntialiasing(true)
    context.setShouldAntialias(true)

    // Base squircle
    let iconRect = CGRect(x: 100, y: 100, width: 824, height: 824)
    let squircle = CGPath(roundedRect: iconRect, cornerWidth: 185, cornerHeight: 185, transform: nil)

    // Subtle drop shadow
    context.saveGState()
    context.setShadow(offset: CGSize(width: 0, height: -18), blur: 32, color: NSColor.black.withAlphaComponent(0.35).cgColor)
    context.addPath(squircle)
    context.setFillColor(NSColor(calibratedRed: 6/255, green: 22/255, blue: 79/255, alpha: 1.0).cgColor)
    context.fillPath()
    context.restoreGState()

    // Background Gradient inside squircle
    context.saveGState()
    context.addPath(squircle)
    context.clip()

    let bgColors = [
        NSColor(calibratedRed: 13/255, green: 42/255, blue: 122/255, alpha: 1.0).cgColor,
        NSColor(calibratedRed: 6/255, green: 22/255, blue: 79/255, alpha: 1.0).cgColor
    ] as CFArray
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    if let gradient = CGGradient(colorsSpace: colorSpace, colors: bgColors, locations: [0.0, 1.0]) {
        context.drawLinearGradient(gradient, start: CGPoint(x: 512, y: 924), end: CGPoint(x: 512, y: 100), options: [])
    }

    // Top inner shine / highlight border
    context.addPath(squircle)
    context.setLineWidth(2.0)
    context.setStrokeColor(NSColor.white.withAlphaComponent(0.18).cgColor)
    context.strokePath()

    // Connected Device Symbol: Mac and Phone

    // 1. Mac Stand
    let standFootRect = CGRect(x: 320, y: 260, width: 180, height: 18)
    let standFoot = CGPath(roundedRect: standFootRect, cornerWidth: 9, cornerHeight: 9, transform: nil)
    context.saveGState()
    context.addPath(standFoot)
    context.setFillColor(NSColor(calibratedRed: 30/255, green: 55/255, blue: 110/255, alpha: 0.9).cgColor)
    context.fillPath()

    let standNeckRect = CGRect(x: 385, y: 275, width: 50, height: 105)
    let standNeck = CGPath(roundedRect: standNeckRect, cornerWidth: 8, cornerHeight: 8, transform: nil)
    context.addPath(standNeck)
    context.setFillColor(NSColor(calibratedRed: 22/255, green: 45/255, blue: 95/255, alpha: 0.95).cgColor)
    context.fillPath()
    context.restoreGState()

    // 2. Mac Screen Frame
    let macFrameRect = CGRect(x: 190, y: 375, width: 440, height: 290)
    let macFrame = CGPath(roundedRect: macFrameRect, cornerWidth: 26, cornerHeight: 26, transform: nil)
    context.saveGState()
    context.setShadow(offset: CGSize(width: 0, height: -12), blur: 24, color: NSColor.black.withAlphaComponent(0.45).cgColor)
    context.addPath(macFrame)
    context.setFillColor(NSColor(calibratedRed: 16/255, green: 36/255, blue: 85/255, alpha: 1.0).cgColor)
    context.fillPath()
    context.restoreGState()

    // Mac Frame Border
    context.saveGState()
    context.addPath(macFrame)
    context.setLineWidth(3.0)
    context.setStrokeColor(NSColor(calibratedRed: 45/255, green: 85/255, blue: 175/255, alpha: 0.7).cgColor)
    context.strokePath()
    context.restoreGState()

    // Mac Inner Screen
    let macInnerRect = CGRect(x: 206, y: 391, width: 408, height: 258)
    let macInner = CGPath(roundedRect: macInnerRect, cornerWidth: 18, cornerHeight: 18, transform: nil)
    context.saveGState()
    context.addPath(macInner)
    context.clip()

    let macScreenColors = [
        NSColor(calibratedRed: 12/255, green: 28/255, blue: 70/255, alpha: 1.0).cgColor,
        NSColor(calibratedRed: 5/255, green: 14/255, blue: 40/255, alpha: 1.0).cgColor
    ] as CFArray
    if let macGrad = CGGradient(colorsSpace: colorSpace, colors: macScreenColors, locations: [0.0, 1.0]) {
        context.drawLinearGradient(macGrad, start: CGPoint(x: 410, y: 649), end: CGPoint(x: 410, y: 391), options: [])
    }

    // Subtle Mac radar / signal rings
    context.setLineWidth(1.5)
    context.setStrokeColor(NSColor(calibratedRed: 24/255, green: 225/255, blue: 231/255, alpha: 0.15).cgColor)
    for radius in [50.0, 95.0, 140.0] {
        context.addArc(center: CGPoint(x: 360, y: 520), radius: radius, startAngle: 0, endAngle: .pi * 2, clockwise: true)
        context.strokePath()
    }
    context.restoreGState()

    // 3. Phone Frame (Overlapping at bottom right)
    let phoneFrameRect = CGRect(x: 530, y: 230, width: 240, height: 430)
    let phoneFrame = CGPath(roundedRect: phoneFrameRect, cornerWidth: 44, cornerHeight: 44, transform: nil)
    context.saveGState()
    context.setShadow(offset: CGSize(width: -10, height: -12), blur: 32, color: NSColor.black.withAlphaComponent(0.6).cgColor)
    context.addPath(phoneFrame)
    context.setFillColor(NSColor(calibratedRed: 14/255, green: 32/255, blue: 80/255, alpha: 1.0).cgColor)
    context.fillPath()
    context.restoreGState()

    // Phone Frame Border
    context.saveGState()
    context.addPath(phoneFrame)
    context.setLineWidth(3.0)
    context.setStrokeColor(NSColor(calibratedRed: 60/255, green: 110/255, blue: 215/255, alpha: 0.8).cgColor)
    context.strokePath()
    context.restoreGState()

    // Phone Inner Screen
    let phoneInnerRect = CGRect(x: 546, y: 246, width: 208, height: 398)
    let phoneInner = CGPath(roundedRect: phoneInnerRect, cornerWidth: 32, cornerHeight: 32, transform: nil)
    context.saveGState()
    context.addPath(phoneInner)
    context.clip()

    let phoneScreenColors = [
        NSColor(calibratedRed: 15/255, green: 35/255, blue: 85/255, alpha: 1.0).cgColor,
        NSColor(calibratedRed: 7/255, green: 18/255, blue: 48/255, alpha: 1.0).cgColor
    ] as CFArray
    if let phoneGrad = CGGradient(colorsSpace: colorSpace, colors: phoneScreenColors, locations: [0.0, 1.0]) {
        context.drawLinearGradient(phoneGrad, start: CGPoint(x: 650, y: 644), end: CGPoint(x: 650, y: 246), options: [])
    }

    // Phone speaker pill
    let speakerRect = CGRect(x: 618, y: 616, width: 64, height: 8)
    let speakerPath = CGPath(roundedRect: speakerRect, cornerWidth: 4, cornerHeight: 4, transform: nil)
    context.addPath(speakerPath)
    context.setFillColor(NSColor(calibratedRed: 25/255, green: 50/255, blue: 110/255, alpha: 0.9).cgColor)
    context.fillPath()

    // Phone home bar
    let homeRect = CGRect(x: 624, y: 260, width: 52, height: 5)
    let homePath = CGPath(roundedRect: homeRect, cornerWidth: 2.5, cornerHeight: 2.5, transform: nil)
    context.addPath(homePath)
    context.setFillColor(NSColor.white.withAlphaComponent(0.35).cgColor)
    context.fillPath()
    context.restoreGState()

    // 4. The Sync Bridge (Glowing Connection linking Mac and Phone)
    let bridgePath = CGMutablePath()
    bridgePath.move(to: CGPoint(x: 360, y: 520))
    bridgePath.addCurve(to: CGPoint(x: 650, y: 445),
                        control1: CGPoint(x: 465, y: 585),
                        control2: CGPoint(x: 545, y: 380))

    // Bridge outer glow
    context.saveGState()
    context.setShadow(offset: .zero, blur: 28, color: NSColor(calibratedRed: 24/255, green: 225/255, blue: 231/255, alpha: 0.75).cgColor)
    context.addPath(bridgePath)
    context.setLineCap(.round)
    context.setLineWidth(22.0)
    context.setStrokeColor(NSColor(calibratedRed: 24/255, green: 225/255, blue: 231/255, alpha: 0.9).cgColor)
    context.strokePath()
    context.restoreGState()

    // Bridge gradient stroke
    context.saveGState()
    let strokedBridge = bridgePath.copy(strokingWithWidth: 20.0, lineCap: .round, lineJoin: .round, miterLimit: 10)
    context.addPath(strokedBridge)
    context.clip()

    let bridgeColors = [
        NSColor(calibratedRed: 24/255, green: 225/255, blue: 231/255, alpha: 1.0).cgColor,
        NSColor(calibratedRed: 0/255, green: 180/255, blue: 240/255, alpha: 1.0).cgColor,
        NSColor(calibratedRed: 23/255, green: 107/255, blue: 255/255, alpha: 1.0).cgColor
    ] as CFArray
    if let bridgeGrad = CGGradient(colorsSpace: colorSpace, colors: bridgeColors, locations: [0.0, 0.45, 1.0]) {
        context.drawLinearGradient(bridgeGrad, start: CGPoint(x: 360, y: 520), end: CGPoint(x: 650, y: 445), options: [])
    }
    context.restoreGState()

    // 5. Radiant Anchor Nodes at both ends
    let nodes: [CGPoint] = [CGPoint(x: 360, y: 520), CGPoint(x: 650, y: 445)]
    for node in nodes {
        // Outer halo
        context.saveGState()
        context.setShadow(offset: .zero, blur: 20, color: NSColor(calibratedRed: 24/255, green: 225/255, blue: 231/255, alpha: 0.8).cgColor)
        context.addArc(center: node, radius: 18, startAngle: 0, endAngle: .pi * 2, clockwise: true)
        context.setFillColor(NSColor(calibratedRed: 24/255, green: 225/255, blue: 231/255, alpha: 1.0).cgColor)
        context.fillPath()
        context.restoreGState()

        // White core
        context.saveGState()
        context.addArc(center: node, radius: 8, startAngle: 0, endAngle: .pi * 2, clockwise: true)
        context.setFillColor(NSColor.white.cgColor)
        context.fillPath()
        context.restoreGState()
    }

    context.restoreGState() // squircle clip
    image.unlockFocus()
    return image
}

func savePNG(image: NSImage, targetSize: Int, to url: URL) {
    let rep = NSBitmapImageRep(
        bitmapDataPlanes: nil,
        pixelsWide: targetSize,
        pixelsHigh: targetSize,
        bitsPerSample: 8,
        samplesPerPixel: 4,
        hasAlpha: true,
        isPlanar: false,
        colorSpaceName: .deviceRGB,
        bytesPerRow: 0,
        bitsPerPixel: 0
    )!
    rep.size = NSSize(width: targetSize, height: targetSize)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    NSGraphicsContext.current?.imageInterpolation = .high
    image.draw(in: NSRect(x: 0, y: 0, width: targetSize, height: targetSize),
               from: NSRect(x: 0, y: 0, width: image.size.width, height: image.size.height),
               operation: .copy,
               fraction: 1.0)
    NSGraphicsContext.restoreGraphicsState()

    let data = rep.representation(using: .png, properties: [:])!
    try! data.write(to: url)
}

func drawAndroidMasterIcon() -> NSImage {
    let size = NSSize(width: 512, height: 512)
    let image = NSImage(size: size)
    image.lockFocus()
    guard let context = NSGraphicsContext.current?.cgContext else {
        fatalError("No CGContext")
    }

    context.setAllowsAntialiasing(true)
    context.setShouldAntialias(true)

    let iconRect = CGRect(x: 16, y: 16, width: 480, height: 480)
    let circle = CGPath(ellipseIn: iconRect, transform: nil)

    context.saveGState()
    context.addPath(circle)
    context.clip()

    let bgColors = [
        NSColor(calibratedRed: 12/255, green: 38/255, blue: 110/255, alpha: 1.0).cgColor,
        NSColor(calibratedRed: 6/255, green: 22/255, blue: 79/255, alpha: 1.0).cgColor
    ] as CFArray
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    if let gradient = CGGradient(colorsSpace: colorSpace, colors: bgColors, locations: [0.0, 1.0]) {
        context.drawLinearGradient(gradient, start: CGPoint(x: 256, y: 496), end: CGPoint(x: 256, y: 16), options: [])
    }

    let scale: CGFloat = 3.8
    let offsetX: CGFloat = 50.0
    let offsetY: CGFloat = 50.0

    context.setLineCap(.round)
    context.setLineWidth(3.5 * scale)
    context.setStrokeColor(NSColor(calibratedRed: 74/255, green: 111/255, blue: 165/255, alpha: 1.0).cgColor)
    context.move(to: CGPoint(x: offsetX + 38.5 * scale, y: offsetY + (108 - 64) * scale))
    context.addLine(to: CGPoint(x: offsetX + 52.5 * scale, y: offsetY + (108 - 64) * scale))
    context.move(to: CGPoint(x: offsetX + 45.5 * scale, y: offsetY + (108 - 56) * scale))
    context.addLine(to: CGPoint(x: offsetX + 45.5 * scale, y: offsetY + (108 - 64) * scale))
    context.strokePath()

    let macRect = CGRect(x: offsetX + 31.5 * scale, y: offsetY + (108 - 56) * scale, width: 28 * scale, height: 24 * scale)
    let macPath = CGPath(roundedRect: macRect, cornerWidth: 4 * scale, cornerHeight: 4 * scale, transform: nil)
    context.addPath(macPath)
    context.setFillColor(NSColor(calibratedRed: 12/255, green: 34/255, blue: 78/255, alpha: 1.0).cgColor)
    context.fillPath()
    context.addPath(macPath)
    context.setStrokeColor(NSColor(calibratedRed: 123/255, green: 158/255, blue: 204/255, alpha: 1.0).cgColor)
    context.strokePath()

    let phoneRect = CGRect(x: offsetX + 56.5 * scale, y: offsetY + (108 - 76) * scale, width: 22 * scale, height: 36 * scale)
    let phonePath = CGPath(roundedRect: phoneRect, cornerWidth: 5 * scale, cornerHeight: 5 * scale, transform: nil)
    context.addPath(phonePath)
    context.setFillColor(NSColor(calibratedRed: 6/255, green: 22/255, blue: 79/255, alpha: 1.0).cgColor)
    context.fillPath()
    context.addPath(phonePath)
    context.setStrokeColor(NSColor(calibratedRed: 123/255, green: 158/255, blue: 204/255, alpha: 1.0).cgColor)
    context.strokePath()

    let bridge = CGMutablePath()
    bridge.move(to: CGPoint(x: offsetX + 45.5 * scale, y: offsetY + (108 - 44) * scale))
    bridge.addCurve(to: CGPoint(x: offsetX + 67.5 * scale, y: offsetY + (108 - 58) * scale),
                    control1: CGPoint(x: offsetX + 51.5 * scale, y: offsetY + (108 - 44) * scale),
                    control2: CGPoint(x: offsetX + 57.5 * scale, y: offsetY + (108 - 50) * scale))
    context.addPath(bridge)
    context.setLineWidth(4.5 * scale)
    context.setStrokeColor(NSColor(calibratedRed: 0/255, green: 229/255, blue: 255/255, alpha: 1.0).cgColor)
    context.strokePath()

    let p1 = CGPoint(x: offsetX + 45.5 * scale, y: offsetY + (108 - 44) * scale)
    let p2 = CGPoint(x: offsetX + 67.5 * scale, y: offsetY + (108 - 58) * scale)
    for p in [p1, p2] {
        context.addArc(center: p, radius: 3.5 * scale, startAngle: 0, endAngle: .pi * 2, clockwise: true)
        context.setFillColor(NSColor(calibratedRed: 0/255, green: 229/255, blue: 255/255, alpha: 1.0).cgColor)
        context.fillPath()
        context.addArc(center: p, radius: 1.5 * scale, startAngle: 0, endAngle: .pi * 2, clockwise: true)
        context.setFillColor(NSColor.white.cgColor)
        context.fillPath()
    }

    context.restoreGState()
    image.unlockFocus()
    return image
}

let master = drawMasterIcon()
let androidMaster = drawAndroidMasterIcon()
let fileManager = FileManager.default

let currentURL = URL(fileURLWithPath: fileManager.currentDirectoryPath)
let macosDir: URL = currentURL.lastPathComponent == "macos" ? currentURL : currentURL.appendingPathComponent("macos")
let resourcesDir = macosDir.appendingPathComponent("Resources")
let iconsetDir = resourcesDir.appendingPathComponent("AppIcon.iconset")
try? fileManager.createDirectory(at: iconsetDir, withIntermediateDirectories: true)

let sizes: [(String, Int)] = [
    ("icon_16x16.png", 16),
    ("icon_16x16@2x.png", 32),
    ("icon_32x32.png", 32),
    ("icon_32x32@2x.png", 64),
    ("icon_128x128.png", 128),
    ("icon_128x128@2x.png", 256),
    ("icon_256x256.png", 256),
    ("icon_256x256@2x.png", 512),
    ("icon_512x512.png", 512),
    ("icon_512x512@2x.png", 1024),
]

for (filename, size) in sizes {
    let dest = iconsetDir.appendingPathComponent(filename)
    savePNG(image: master, targetSize: size, to: dest)
    print("Generated \(filename) (\(size)x\(size))")
}

let androidMipmaps: [(String, Int)] = [
    ("mipmap-mdpi/ic_launcher.png", 48),
    ("mipmap-mdpi/ic_launcher_round.png", 48),
    ("mipmap-hdpi/ic_launcher.png", 72),
    ("mipmap-hdpi/ic_launcher_round.png", 72),
    ("mipmap-xhdpi/ic_launcher.png", 96),
    ("mipmap-xhdpi/ic_launcher_round.png", 96),
    ("mipmap-xxhdpi/ic_launcher.png", 144),
    ("mipmap-xxhdpi/ic_launcher_round.png", 144),
    ("mipmap-xxxhdpi/ic_launcher.png", 192),
    ("mipmap-xxxhdpi/ic_launcher_round.png", 192),
]

let androidResDir = macosDir.deletingLastPathComponent().appendingPathComponent("android/app/src/main/res")
for (subpath, size) in androidMipmaps {
    let dest = androidResDir.appendingPathComponent(subpath)
    try? fileManager.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
    savePNG(image: androidMaster, targetSize: size, to: dest)
    print("Generated Android \(subpath) (\(size)x\(size))")
}



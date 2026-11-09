import AppKit
import AVFoundation
import AudioToolbox
import CoreMedia
import SwiftUI
import VideoToolbox

// MARK: - Mirror Session State & Models

enum MirrorQuality: String, CaseIterable, Identifiable {
    case autoAdaptive = "Auto (Adaptive Smooth 60fps)"
    case ultra1080p60 = "1080p 60fps (Ultra 9Mbps)"
    case high1080p60 = "1080p 60fps (High 5Mbps)"
    case smooth720p60 = "720p 60fps (Smooth 3.5Mbps)"
    case saver720p30 = "720p 30fps (Battery Saver 2Mbps)"

    var id: String { rawValue }

    var width: Int {
        switch self {
        case .autoAdaptive, .ultra1080p60, .high1080p60: return 1080
        case .smooth720p60, .saver720p30: return 720
        }
    }

    var height: Int {
        switch self {
        case .autoAdaptive, .ultra1080p60, .high1080p60: return 2400
        case .smooth720p60, .saver720p30: return 1600
        }
    }

    var fps: Int {
        switch self {
        case .saver720p30: return 30
        default: return 60
        }
    }

    var bitrate: Int {
        switch self {
        case .autoAdaptive: return 6_000_000
        case .ultra1080p60: return 9_000_000
        case .high1080p60: return 5_000_000
        case .smooth720p60: return 3_500_000
        case .saver720p30: return 2_000_000
        }
    }
}

enum MirrorConnectionState: Equatable {
    case disconnected
    case connecting
    case connected(fps: Double, bitrateMbps: Double)
    case reconnecting
    case error(String)
}

struct MirrorOfferPayload: Codable {
    let version: Int
    let sessionId: String
    let width: Int
    let height: Int
    let fps: Int
    let bitrate: Int
    let captureAppOnly: Bool
}

struct MirrorInputPayload: Codable {
    let version: Int
    let sessionId: String
    let action: String   // "tap", "double_tap", "swipe", "scroll", "nav", "text"
    let x: Double?
    let y: Double?
    let deltaX: Double?
    let deltaY: Double?
    let keyCode: String?
    let text: String?
}

// MARK: - Mirror Viewer Window Controller

@MainActor
final class MirrorViewerWindowController: NSWindowController, NSWindowDelegate {
    private weak var pairing: PairingCoordinator?
    private var mirrorView: MirrorFrameNSView!
    private var hudHosting: NSHostingView<MirrorTopHudBar>!
    private var dragBarView: MirrorDragBarView!
    private var toolbarState: MirrorToolbarState?
    private var isAlwaysOnTop = false
    private var currentSessionID: String?
    private var remoteWidth: CGFloat = 1080
    private var remoteHeight: CGFloat = 2400

    private var audioEngine: AVAudioEngine?
    private var audioPlayerNode: AVAudioPlayerNode?
    private let audioPlaybackFormat = AVAudioFormat(standardFormatWithSampleRate: 48000, channels: 2)
    private var isAudioMuted = false

    private var hudAutoHideTimer: Timer?
    private var trackingArea: NSTrackingArea?

    var onSessionStop: (() -> Void)?

    init(pairing: PairingCoordinator) {
        self.pairing = pairing

        let window = NSWindow(
            contentRect: NSRect(x: 140, y: 140, width: 380, height: 820),
            styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        window.title = "SYNQ View"
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.isMovableByWindowBackground = false
        window.backgroundColor = .clear
        window.isOpaque = false
        window.hasShadow = true
        window.minSize = NSSize(width: 280, height: 420)
        window.collectionBehavior = [.fullScreenPrimary]
        window.setFrameAutosaveName("SYNQMirrorWindow")

        // Hide standard window traffic lights so they don't merge with phone status bar
        for buttonType in [NSWindow.ButtonType.closeButton, .miniaturizeButton, .zoomButton] {
            if let btn = window.standardWindowButton(buttonType) {
                btn.isHidden = true
                btn.alphaValue = 0.0
            }
        }

        super.init(window: window)
        window.delegate = self

        setupViews(window: window)
        setupAudioPlayback()
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    private func setupViews(window: NSWindow) {
        let container = NSView(frame: window.contentView!.bounds)
        container.autoresizingMask = [.width, .height]
        container.wantsLayer = true
        container.layer?.backgroundColor = NSColor.black.cgColor
        container.layer?.cornerRadius = 28
        container.layer?.masksToBounds = true
        container.layer?.borderWidth = 1.0
        container.layer?.borderColor = NSColor.white.withAlphaComponent(0.14).cgColor

        // Video View
        mirrorView = MirrorFrameNSView(frame: container.bounds)
        mirrorView.autoresizingMask = [.width, .height]
        mirrorView.wantsLayer = true
        mirrorView.layer?.cornerRadius = 28
        mirrorView.layer?.masksToBounds = true
        mirrorView.onInputEvent = { [weak self] payload in
            self?.sendInput(payload)
        }
        mirrorView.onTopEdgeHover = { [weak self] isNearTop in
            if isNearTop {
                self?.showHud()
            } else {
                self?.hideHudWithDelay()
            }
        }
        container.addSubview(mirrorView)

        // Top Drag Handle Bar (dedicated grab zone to move window smoothly)
        dragBarView = MirrorDragBarView()
        dragBarView.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(dragBarView)

        // Top Floating Glass Controls HUD (Hidden by default, revealed on top-edge hover)
        let toolbarState = MirrorToolbarState(
            onBack: { [weak self] in self?.sendNavAction("back") },
            onHome: { [weak self] in self?.sendNavAction("home") },
            onRecents: { [weak self] in self?.sendNavAction("recents") },
            onTogglePin: { [weak self] in self?.toggleAlwaysOnTop() },
            onToggleAudio: { [weak self] in self?.toggleAudioMute() },
            onSelectAudioOutput: { [weak self] name in self?.selectAudioOutput(name) },
            onToggleOrientation: { [weak self] in self?.toggleOrientation() },
            onToggleFullscreen: { [weak self] in self?.toggleFullscreen() },
            onQualityChanged: { [weak self] q in self?.changeQuality(q) },
            onDisconnect: { [weak self] in self?.requestStopConfirmation() }
        )
        self.toolbarState = toolbarState
        updateAvailableAudioOutputs()

        hudHosting = NSHostingView(rootView: MirrorTopHudBar(state: toolbarState))
        hudHosting.translatesAutoresizingMaskIntoConstraints = false
        hudHosting.wantsLayer = true
        hudHosting.alphaValue = 0.0
        hudHosting.isHidden = true
        container.addSubview(hudHosting)

        NSLayoutConstraint.activate([
            // Top Grab Bar Handle
            dragBarView.centerXAnchor.constraint(equalTo: container.centerXAnchor),
            dragBarView.topAnchor.constraint(equalTo: container.topAnchor, constant: 6),
            dragBarView.widthAnchor.constraint(equalToConstant: 120),
            dragBarView.heightAnchor.constraint(equalToConstant: 12),

            // Top Floating HUD (Revealed on top-edge cursor hover, comfortably below phone status bar)
            hudHosting.centerXAnchor.constraint(equalTo: container.centerXAnchor),
            hudHosting.topAnchor.constraint(equalTo: container.topAnchor, constant: 30),
            hudHosting.heightAnchor.constraint(equalToConstant: 44),
            hudHosting.leadingAnchor.constraint(greaterThanOrEqualTo: container.leadingAnchor, constant: 4),
            hudHosting.trailingAnchor.constraint(lessThanOrEqualTo: container.trailingAnchor, constant: -4)
        ])

        window.contentView = container
        updateAspectRatio(width: remoteWidth, height: remoteHeight)
    }

    private func showHud() {
        hudAutoHideTimer?.invalidate()
        hudAutoHideTimer = nil
        guard let hud = hudHosting else { return }
        hud.isHidden = false
        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = 0.20
            hud.animator().alphaValue = 1.0
        }
    }

    private func hideHudWithDelay() {
        hudAutoHideTimer?.invalidate()
        hudAutoHideTimer = Timer.scheduledTimer(withTimeInterval: 1.2, repeats: false) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self = self, let hud = self.hudHosting else { return }
                NSAnimationContext.runAnimationGroup({ ctx in
                    ctx.duration = 0.35
                    hud.animator().alphaValue = 0.0
                }, completionHandler: {
                    if hud.alphaValue == 0.0 {
                        hud.isHidden = true
                    }
                })
            }
        }
    }

    func show(sessionID: String, deviceName: String, width: Int = 1080, height: Int = 2400) {
        self.currentSessionID = sessionID
        self.remoteWidth = CGFloat(width)
        self.remoteHeight = CGFloat(height)
        updateAspectRatio(width: remoteWidth, height: remoteHeight)
        mirrorView.setStreamDimensions(width: remoteWidth, height: remoteHeight)

        setupAudioPlayback()

        window?.title = "SYNQ View — \(deviceName)"
        if let w = window?.frame.width {
            toolbarState?.containerWidth = w
        }
        window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        showHud()
        hideHudWithDelay()
    }

    func hide() {
        window?.orderOut(nil)
    }

    func windowShouldClose(_ sender: NSWindow) -> Bool {
        requestStopConfirmation()
        return false
    }

    func windowWillClose(_ notification: Notification) {
        stopMirroring()
    }

    func requestStopConfirmation() {
        guard let window = window else { return }
        let alert = NSAlert()
        alert.messageText = "End SYNQ View Session?"
        alert.informativeText = "Stopping will end interactive screen mirroring on your Mac and stop capture on your Android device."
        alert.alertStyle = .warning
        alert.addButton(withTitle: "Stop Session")
        alert.addButton(withTitle: "Cancel")

        alert.beginSheetModal(for: window) { [weak self] response in
            if response == .alertFirstButtonReturn {
                self?.stopMirroring()
            }
        }
    }

    func windowDidResize(_ notification: Notification) {
        if let w = window?.frame.width {
            toolbarState?.containerWidth = w
        }
    }

    func windowDidEnterFullScreen(_ notification: Notification) {
        toolbarState?.isFullscreen = true
    }

    func windowDidExitFullScreen(_ notification: Notification) {
        toolbarState?.isFullscreen = false
        updateAspectRatio(width: remoteWidth, height: remoteHeight)
    }

    func updateDimensions(width: Int, height: Int) {
        guard width > 0, height > 0 else { return }
        let oldIsLandscape = remoteWidth > remoteHeight
        let newIsLandscape = width > height
        self.remoteWidth = CGFloat(width)
        self.remoteHeight = CGFloat(height)
        toolbarState?.isLandscape = newIsLandscape
        updateAspectRatio(width: remoteWidth, height: remoteHeight)
        mirrorView.setStreamDimensions(width: CGFloat(width), height: CGFloat(height))

        if oldIsLandscape != newIsLandscape {
            adaptWindowOrientation(toLandscape: newIsLandscape)
        }
    }

    private func updateAspectRatio(width: CGFloat, height: CGFloat) {
        guard let window = window, height > 0 else { return }
        window.contentAspectRatio = NSSize(width: width, height: height)
    }

    private func toggleOrientation() {
        let targetLandscape = !(remoteWidth > remoteHeight)
        toolbarState?.isLandscape = targetLandscape
        adaptWindowOrientation(toLandscape: targetLandscape)
        sendInput(MirrorInputPayload(
            version: 1,
            sessionId: "",
            action: "rotate",
            x: nil,
            y: nil,
            deltaX: nil,
            deltaY: nil,
            keyCode: targetLandscape ? "landscape" : "portrait",
            text: nil
        ))
    }

    private func adaptWindowOrientation(toLandscape: Bool) {
        guard let window = window else { return }
        guard !window.styleMask.contains(.fullScreen) else { return }

        let screenFrame = window.screen?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
        let currentFrame = window.frame

        let targetWidth: CGFloat
        let targetHeight: CGFloat

        if toLandscape {
            let maxW = min(960.0, screenFrame.width * 0.85)
            let aspect = remoteWidth > remoteHeight ? (remoteWidth / remoteHeight) : (remoteHeight / remoteWidth)
            targetWidth = maxW
            targetHeight = round(maxW / max(1.0, aspect))
        } else {
            let maxH = min(840.0, screenFrame.height * 0.88)
            let aspect = remoteHeight > remoteWidth ? (remoteHeight / remoteWidth) : (remoteWidth / remoteHeight)
            targetHeight = maxH
            targetWidth = round(maxH / max(1.0, aspect))
        }

        let newX = max(screenFrame.minX + 20, min(currentFrame.midX - targetWidth / 2.0, screenFrame.maxX - targetWidth - 20))
        let newY = max(screenFrame.minY + 20, min(currentFrame.midY - targetHeight / 2.0, screenFrame.maxY - targetHeight - 20))
        let newFrame = NSRect(x: newX, y: newY, width: targetWidth, height: targetHeight)

        window.contentAspectRatio = NSSize(
            width: toLandscape ? max(remoteWidth, remoteHeight) : min(remoteWidth, remoteHeight),
            height: toLandscape ? min(remoteWidth, remoteHeight) : max(remoteWidth, remoteHeight)
        )

        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = 0.35
            ctx.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
            window.animator().setFrame(newFrame, display: true)
        }
    }

    private func toggleFullscreen() {
        guard let window = window else { return }
        window.toggleFullScreen(nil)
    }

    func receiveVideoFrame(nalData: Data, isKeyframe: Bool, pts: Int64, width: Int, height: Int) {
        if width > 0 && height > 0 && (CGFloat(width) != remoteWidth || CGFloat(height) != remoteHeight) {
            updateDimensions(width: width, height: height)
        }
        mirrorView.decodeAndDisplay(nalData: nalData, isKeyframe: isKeyframe, width: width, height: height)
    }

    // MARK: - Input Forwarding

    private func sendInput(_ input: MirrorInputPayload) {
        guard let pairing = pairing, let sid = currentSessionID else { return }
        let payload = MirrorInputPayload(
            version: 1,
            sessionId: sid,
            action: input.action,
            x: input.x,
            y: input.y,
            deltaX: input.deltaX,
            deltaY: input.deltaY,
            keyCode: input.keyCode,
            text: input.text
        )
        pairing.sendMirrorInput(payload)
    }

    private func sendNavAction(_ key: String) {
        sendInput(MirrorInputPayload(
            version: 1,
            sessionId: "",
            action: "nav",
            x: nil,
            y: nil,
            deltaX: nil,
            deltaY: nil,
            keyCode: key,
            text: nil
        ))
    }

    // MARK: - Actions

    private func toggleAlwaysOnTop() {
        isAlwaysOnTop.toggle()
        if isAlwaysOnTop {
            window?.level = .floating
            window?.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .fullScreenPrimary]
        } else {
            window?.level = .normal
            window?.collectionBehavior = [.fullScreenPrimary]
        }
        toolbarState?.isAlwaysOnTop = isAlwaysOnTop
    }

    private func updateAvailableAudioOutputs() {
        var propertyAddress = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDevices,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        var dataSize: UInt32 = 0
        let status = AudioObjectGetPropertyDataSize(AudioObjectID(kAudioObjectSystemObject), &propertyAddress, 0, nil, &dataSize)
        guard status == noErr, dataSize > 0 else {
            toolbarState?.availableAudioOutputs = ["System Default Output"]
            return
        }

        let deviceCount = Int(dataSize) / MemoryLayout<AudioDeviceID>.size
        var deviceIDs = [AudioDeviceID](repeating: 0, count: deviceCount)
        AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &propertyAddress, 0, nil, &dataSize, &deviceIDs)

        var names: [String] = ["System Default Output"]
        for deviceID in deviceIDs {
            var streamAddress = AudioObjectPropertyAddress(
                mSelector: kAudioDevicePropertyStreams,
                mScope: kAudioDevicePropertyScopeOutput,
                mElement: kAudioObjectPropertyElementMain
            )
            var streamSize: UInt32 = 0
            if AudioObjectGetPropertyDataSize(deviceID, &streamAddress, 0, nil, &streamSize) == noErr && streamSize > 0 {
                var nameAddress = AudioObjectPropertyAddress(
                    mSelector: kAudioDevicePropertyDeviceNameCFString,
                    mScope: kAudioObjectPropertyScopeGlobal,
                    mElement: kAudioObjectPropertyElementMain
                )
                var nameCF: CFString?
                var nameSize = UInt32(MemoryLayout<CFString?>.size)
                if AudioObjectGetPropertyData(deviceID, &nameAddress, 0, nil, &nameSize, &nameCF) == noErr, let name = nameCF as String? {
                    if !names.contains(name) {
                        names.append(name)
                    }
                }
            }
        }
        toolbarState?.availableAudioOutputs = names
    }

    private func selectAudioOutput(_ name: String) {
        toolbarState?.selectedAudioOutput = name
    }

    private func changeQuality(_ quality: MirrorQuality) {
        guard let currentSessionID = currentSessionID else { return }
        pairing?.sendMirrorQualityChange(sessionId: currentSessionID, quality: quality)
    }

    private func stopMirroring() {
        guard let currentSessionID = currentSessionID else { return }
        pairing?.stopScreenMirror(sessionId: currentSessionID)
        self.currentSessionID = nil
        teardownAudio()
        hide()
        onSessionStop?()
    }

    // MARK: - Audio Handling

    private func setupAudioPlayback() {
        guard audioEngine == nil else { return }
        guard let format = audioPlaybackFormat else { return }

        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        engine.attach(player)

        engine.connect(player, to: engine.mainMixerNode, format: format)

        do {
            try engine.start()
            player.play()
            self.audioEngine = engine
            self.audioPlayerNode = player
        } catch {
            NSLog("Failed to start audio playback engine: %@", error.localizedDescription)
        }
    }

    func receiveAudioChunk(pcmData: Data) {
        guard !isAudioMuted, let player = audioPlayerNode, player.isPlaying else { return }
        guard let format = audioPlaybackFormat else { return }

        // Android sends 16-bit stereo interleaved PCM (4 bytes per audio frame: 2 channels * 2 bytes)
        let frameCount = pcmData.count / 4
        guard frameCount > 0,
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: UInt32(frameCount)) else { return }
        buffer.frameLength = UInt32(frameCount)

        pcmData.withUnsafeBytes { raw in
            guard let int16Ptr = raw.bindMemory(to: Int16.self).baseAddress,
                  let left = buffer.floatChannelData?[0],
                  let right = buffer.floatChannelData?[1] else { return }
            for i in 0..<frameCount {
                left[i] = Float(int16Ptr[i * 2]) / 32768.0
                right[i] = Float(int16Ptr[i * 2 + 1]) / 32768.0
            }
        }
        // Calibrated 45ms jitter/presentation delay aligns audio playback with H.264 video frame display clock
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(45)) { [weak self] in
            guard let self = self, !self.isAudioMuted, let player = self.audioPlayerNode, player.isPlaying else { return }
            player.scheduleBuffer(buffer, completionHandler: nil)
        }
    }

    private func toggleAudioMute() {
        isAudioMuted.toggle()
        audioEngine?.mainMixerNode.outputVolume = isAudioMuted ? 0.0 : 1.0
    }

    private func teardownAudio() {
        audioPlayerNode?.stop()
        audioEngine?.stop()
        audioPlayerNode = nil
        audioEngine = nil
    }
}

// MARK: - Modern Dedicated Grab / Drag Handle Bar

final class MirrorDragBarView: NSView {
    private var isHovered = false

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        wantsLayer = true
        let tracking = NSTrackingArea(
            rect: bounds,
            options: [.mouseEnteredAndExited, .activeAlways, .inVisibleRect],
            owner: self,
            userInfo: nil
        )
        addTrackingArea(tracking)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func mouseEntered(with event: NSEvent) {
        isHovered = true
        needsDisplay = true
    }

    override func mouseExited(with event: NSEvent) {
        isHovered = false
        needsDisplay = true
    }

    override func mouseDown(with event: NSEvent) {
        window?.performDrag(with: event)
    }

    override func mouseDragged(with event: NSEvent) {
        window?.performDrag(with: event)
    }

    override func resetCursorRects() {
        addCursorRect(bounds, cursor: .openHand)
    }

    override func draw(_ dirtyRect: NSRect) {
        super.draw(dirtyRect)
        let pillW: CGFloat = 38
        let pillH: CGFloat = 3.5
        let rect = NSRect(
            x: (bounds.width - pillW) / 2.0,
            y: (bounds.height - pillH) / 2.0,
            width: pillW,
            height: pillH
        )
        let path = NSBezierPath(roundedRect: rect, xRadius: pillH / 2.0, yRadius: pillH / 2.0)
        let alpha: CGFloat = isHovered ? 0.70 : 0.28
        NSColor.white.withAlphaComponent(alpha).setFill()
        path.fill()
    }
}

// MARK: - Modern Gesture Home Bar

// MARK: - Mirror Frame NSView with Hardware VideoToolbox Decode & Rich Gestures

final class MirrorFrameNSView: NSView {
    private var displayLayer: AVSampleBufferDisplayLayer!
    private var formatDescription: CMVideoFormatDescription?
    private var spsData: Data?
    private var ppsData: Data?

    var onInputEvent: ((MirrorInputPayload) -> Void)?
    var onTopEdgeHover: ((Bool) -> Void)?

    private var streamWidth: CGFloat = 1080
    private var streamHeight: CGFloat = 2400

    private var dragStartPoint: NSPoint = .zero
    private var lastDragDispatchedPoint: NSPoint = .zero
    private var dragStartTime: Date = Date()
    private var isDragging = false
    private var hasSwipedForCurrentGesture = false
    private var scrollAccumulatorY: CGFloat = 0
    private var scrollAccumulatorX: CGFloat = 0
    private var lastMouseWheelTime = Date.distantPast

    func setStreamDimensions(width: CGFloat, height: CGFloat) {
        if width > 0 && height > 0 {
            self.streamWidth = width
            self.streamHeight = height
        }
    }

    func actualVideoRect() -> CGRect {
        let viewW = bounds.width
        let viewH = bounds.height
        guard viewW > 0, viewH > 0, streamWidth > 0, streamHeight > 0 else {
            return bounds
        }

        let streamAR = streamWidth / streamHeight
        let viewAR = viewW / viewH

        if viewAR > streamAR {
            // View is wider than video stream -> pillarboxing (black bars on left/right)
            let actualW = viewH * streamAR
            let xOffset = (viewW - actualW) / 2.0
            return CGRect(x: xOffset, y: 0, width: actualW, height: viewH)
        } else {
            // View is taller than video stream -> letterboxing (black bars on top/bottom)
            let actualH = viewW / streamAR
            let yOffset = (viewH - actualH) / 2.0
            return CGRect(x: 0, y: yOffset, width: viewW, height: actualH)
        }
    }

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        wantsLayer = true

        displayLayer = AVSampleBufferDisplayLayer()
        displayLayer.videoGravity = .resizeAspect
        displayLayer.frame = bounds
        displayLayer.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
        layer?.addSublayer(displayLayer)

        let trackingArea = NSTrackingArea(
            rect: bounds,
            options: [.mouseMoved, .mouseEnteredAndExited, .activeAlways, .inVisibleRect],
            owner: self,
            userInfo: nil
        )
        addTrackingArea(trackingArea)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override var acceptsFirstResponder: Bool { true }

    func decodeAndDisplay(nalData: Data, isKeyframe: Bool, width: Int, height: Int) {
        if width > 0 && height > 0 {
            self.streamWidth = CGFloat(width)
            self.streamHeight = CGFloat(height)
        }
        let nals = parseNalUnits(from: nalData)
        for nal in nals {
            guard !nal.isEmpty else { continue }
            let nalType = nal[0] & 0x1F

            if nalType == 7 { // SPS
                spsData = nal
            } else if nalType == 8 { // PPS
                ppsData = nal
            }
        }

        if let sps = spsData, let pps = ppsData {
            if formatDescription == nil || isKeyframe {
                createFormatDescription(sps: sps, pps: pps)
            }
        }

        guard let formatDesc = formatDescription else { return }

        let avccData = convertToAvcc(nals: nals)
        guard !avccData.isEmpty else { return }

        let count = avccData.count
        guard let memory = malloc(count) else { return }
        avccData.copyBytes(to: memory.assumingMemoryBound(to: UInt8.self), count: count)

        var blockBuffer: CMBlockBuffer?
        var status = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: memory,
            blockLength: count,
            blockAllocator: kCFAllocatorMalloc,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: count,
            flags: 0,
            blockBufferOut: &blockBuffer
        )
        guard status == noErr, let buffer = blockBuffer else {
            free(memory)
            return
        }

        var sampleBuffer: CMSampleBuffer?
        var sampleSize = avccData.count
        var timing = CMSampleTimingInfo(
            duration: CMTime(value: 1, timescale: 60),
            presentationTimeStamp: CMClockGetTime(CMClockGetHostTimeClock()),
            decodeTimeStamp: .invalid
        )

        status = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: buffer,
            formatDescription: formatDesc,
            sampleCount: 1,
            sampleTimingEntryCount: 1,
            sampleTimingArray: &timing,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &sampleSize,
            sampleBufferOut: &sampleBuffer
        )

        guard status == noErr, let sample = sampleBuffer else { return }

        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true) {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(
                dict,
                Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                Unmanaged.passUnretained(kCFBooleanTrue).toOpaque()
            )
            if !isKeyframe {
                CFDictionarySetValue(
                    dict,
                    Unmanaged.passUnretained(kCMSampleAttachmentKey_NotSync).toOpaque(),
                    Unmanaged.passUnretained(kCFBooleanTrue).toOpaque()
                )
            }
        }

        if displayLayer.status == .failed {
            displayLayer.flush()
        }
        displayLayer.enqueue(sample)
    }

    private func createFormatDescription(sps: Data, pps: Data) {
        sps.withUnsafeBytes { spsRaw in
            pps.withUnsafeBytes { ppsRaw in
                guard let spsPtr = spsRaw.baseAddress?.assumingMemoryBound(to: UInt8.self),
                      let ppsPtr = ppsRaw.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return }
                let pointers: [UnsafePointer<UInt8>] = [spsPtr, ppsPtr]
                let sizes: [Int] = [sps.count, pps.count]

                var desc: CMVideoFormatDescription?
                let status = CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault,
                    parameterSetCount: 2,
                    parameterSetPointers: pointers,
                    parameterSetSizes: sizes,
                    nalUnitHeaderLength: 4,
                    formatDescriptionOut: &desc
                )
                if status == noErr {
                    self.formatDescription = desc
                }
            }
        }
    }

    private func parseNalUnits(from data: Data) -> [Data] {
        var nals: [Data] = []
        var index = 0
        let count = data.count

        while index < count {
            var start = -1
            while index + 2 < count {
                if data[index] == 0 && data[index + 1] == 0 {
                    if data[index + 2] == 1 {
                        start = index + 3
                        index += 3
                        break
                    } else if index + 3 < count && data[index + 2] == 0 && data[index + 3] == 1 {
                        start = index + 4
                        index += 4
                        break
                    }
                }
                index += 1
            }

            guard start != -1 else { break }

            var end = count
            var search = start
            while search + 2 < count {
                if data[search] == 0 && data[search + 1] == 0 {
                    if data[search + 2] == 1 || (search + 3 < count && data[search + 2] == 0 && data[search + 3] == 1) {
                        end = search
                        break
                    }
                }
                search += 1
            }

            if end > start {
                nals.append(data.subdata(in: start..<end))
            }
            index = end
        }

        return nals
    }

    private func convertToAvcc(nals: [Data]) -> Data {
        var avcc = Data()
        for nal in nals {
            guard !nal.isEmpty else { continue }
            let nalType = nal[0] & 0x1F
            if nalType == 7 || nalType == 8 { continue }

            var length = UInt32(nal.count).bigEndian
            avcc.append(Data(bytes: &length, count: 4))
            avcc.append(nal)
        }
        return avcc
    }

    // MARK: - Input Coordinate Mapping (Pixel-Accurate to Visible Video Content)

    private func normalizedCoordinates(for event: NSEvent) -> (x: Double, y: Double)? {
        let location = convert(event.locationInWindow, from: nil)
        let vRect = actualVideoRect()
        guard vRect.width > 0 && vRect.height > 0 else { return nil }

        let relX = (location.x - vRect.origin.x) / vRect.width
        let relY = (location.y - vRect.origin.y) / vRect.height

        let nx = max(0.0, min(1.0, Double(relX)))
        let ny = max(0.0, min(1.0, Double(1.0 - relY)))
        return (nx, ny)
    }

    override func mouseMoved(with event: NSEvent) {
        let location = convert(event.locationInWindow, from: nil)
        let isNearTopEdge = location.y >= (bounds.height - 36)
        onTopEdgeHover?(isNearTopEdge)
    }

    // MARK: - Mouse & Touch Gestures

    override func mouseDown(with event: NSEvent) {
        dragStartPoint = convert(event.locationInWindow, from: nil)
        lastDragDispatchedPoint = dragStartPoint
        dragStartTime = Date()
        isDragging = false

        if event.clickCount == 2, let (x, y) = normalizedCoordinates(for: event) {
            onInputEvent?(MirrorInputPayload(
                version: 1,
                sessionId: "",
                action: "double_tap",
                x: x,
                y: y,
                deltaX: nil,
                deltaY: nil,
                keyCode: nil,
                text: nil
            ))
        }
    }

    override func mouseDragged(with event: NSEvent) {
        let current = convert(event.locationInWindow, from: nil)
        let totalDist = hypot(current.x - dragStartPoint.x, current.y - dragStartPoint.y)
        if totalDist > 8 {
            isDragging = true
        }

        guard isDragging else { return }

        // Continuous real-time drag dispatch: moves screen while mouse is held down
        let stepDist = hypot(current.x - lastDragDispatchedPoint.x, current.y - lastDragDispatchedPoint.y)
        if stepDist >= 10 {
            let vRect = actualVideoRect()
            guard vRect.width > 0 && vRect.height > 0 else { return }

            let startNormX = max(0.0, min(1.0, Double((lastDragDispatchedPoint.x - vRect.origin.x) / vRect.width)))
            let startNormY = max(0.0, min(1.0, Double(1.0 - ((lastDragDispatchedPoint.y - vRect.origin.y) / vRect.height))))
            let endNormX = max(0.0, min(1.0, Double((current.x - vRect.origin.x) / vRect.width)))
            let endNormY = max(0.0, min(1.0, Double(1.0 - ((current.y - vRect.origin.y) / vRect.height))))

            onInputEvent?(MirrorInputPayload(
                version: 1,
                sessionId: "",
                action: "drag_step",
                x: startNormX,
                y: startNormY,
                deltaX: endNormX,
                deltaY: endNormY,
                keyCode: "35",
                text: nil
            ))
            lastDragDispatchedPoint = current
        }
    }

    override func mouseUp(with event: NSEvent) {
        guard let (endX, endY) = normalizedCoordinates(for: event) else { return }

        if isDragging {
            let totalDist = hypot(event.locationInWindow.x - dragStartPoint.x, event.locationInWindow.y - dragStartPoint.y)
            let elapsed = Date().timeIntervalSince(dragStartTime)
            let velocity = totalDist / max(0.05, elapsed)

            // If released with fast flick/fling, dispatch momentum fling!
            if velocity > 380 {
                let vRect = actualVideoRect()
                let startNormX = max(0.0, min(1.0, Double((dragStartPoint.x - vRect.origin.x) / max(1.0, vRect.width))))
                let startNormY = max(0.0, min(1.0, Double(1.0 - ((dragStartPoint.y - vRect.origin.y) / max(1.0, vRect.height)))))
                let flingDuration = max(90, min(220, Int(elapsed * 1000)))
                onInputEvent?(MirrorInputPayload(
                    version: 1,
                    sessionId: "",
                    action: "swipe",
                    x: startNormX,
                    y: startNormY,
                    deltaX: endX,
                    deltaY: endY,
                    keyCode: "\(flingDuration)",
                    text: nil
                ))
            }
        } else if event.clickCount == 1 {
            onInputEvent?(MirrorInputPayload(
                version: 1,
                sessionId: "",
                action: "tap",
                x: endX,
                y: endY,
                deltaX: nil,
                deltaY: nil,
                keyCode: nil,
                text: nil
            ))
        }
        isDragging = false
    }

    override func rightMouseDown(with event: NSEvent) {
        // Two-finger tap / Right-click = Android Press and Hold (Long Press)
        guard let (x, y) = normalizedCoordinates(for: event) else { return }
        onInputEvent?(MirrorInputPayload(
            version: 1,
            sessionId: "",
            action: "long_press",
            x: x,
            y: y,
            deltaX: nil,
            deltaY: nil,
            keyCode: nil,
            text: nil
        ))
    }

    // MARK: - Mac Trackpad Gestures (Ghost-Touch-Free Scrolling)

    override func scrollWheel(with event: NSEvent) {
        // Ignore macOS momentum phase to avoid uncontrolled runaway scrolling
        if !event.momentumPhase.isEmpty {
            return
        }

        let now = Date()

        // Trackpad gesture handling: only scroll during active touches
        if !event.phase.isEmpty {
            if event.phase == .began {
                scrollAccumulatorX = 0
                scrollAccumulatorY = 0
                return
            }

            if event.phase == .ended || event.phase == .cancelled {
                scrollAccumulatorX = 0
                scrollAccumulatorY = 0
                return
            }

            scrollAccumulatorX += event.scrollingDeltaX
            scrollAccumulatorY += event.scrollingDeltaY

            // Throttle & threshold guard:
            // 1. Throttle to 70ms ensures a continuous, decisive drag stroke
            // 2. Minimum 8.0pt displacement prevents tiny wiggles from triggering micro-strokes
            guard now.timeIntervalSince(lastMouseWheelTime) >= 0.070 else { return }

            let dist = hypot(Double(scrollAccumulatorX), Double(scrollAccumulatorY))
            guard dist >= 8.0 else { return }

            lastMouseWheelTime = now

            let signY = scrollAccumulatorY >= 0 ? 1.0 : -1.0
            let signX = scrollAccumulatorX >= 0 ? 1.0 : -1.0
            let absDy = abs(Double(scrollAccumulatorY))
            let absDx = abs(Double(scrollAccumulatorX))

            // Bounded acceleration
            let moveY = min(absDy <= 8.0 ? absDy * 1.2 : 9.6 + pow(absDy - 8.0, 1.08) * 1.1, 75.0) * signY
            let moveX = min(absDx <= 8.0 ? absDx * 1.2 : 9.6 + pow(absDx - 8.0, 1.08) * 1.1, 75.0) * signX

            scrollAccumulatorX = 0
            scrollAccumulatorY = 0

            // Safe neutral viewport center (0.5, 0.5):
            // Swiping in the center of the viewport NEVER lands on buttons, play icons, or cards under the cursor!
            onInputEvent?(MirrorInputPayload(
                version: 1,
                sessionId: "",
                action: "scroll",
                x: 0.5,
                y: 0.5,
                deltaX: moveX,
                deltaY: moveY,
                keyCode: "85",
                text: nil
            ))
        } else {
            // Mouse scroll wheel
            let dy = Double(event.scrollingDeltaY)
            guard abs(dy) >= 1.0 else { return }
            guard now.timeIntervalSince(lastMouseWheelTime) >= 0.08 else { return }
            lastMouseWheelTime = now

            let moveY = min(abs(dy) * 4.5, 60.0) * (dy >= 0 ? 1.0 : -1.0)
            onInputEvent?(MirrorInputPayload(
                version: 1,
                sessionId: "",
                action: "scroll",
                x: 0.5,
                y: 0.5,
                deltaX: 0,
                deltaY: moveY,
                keyCode: "85",
                text: nil
            ))
        }
    }

    // MARK: - Keyboard Controls

    override func keyDown(with event: NSEvent) {
        // Backspace (51) & Forward Delete (117) -> key action backspace
        if event.keyCode == 51 || event.keyCode == 117 {
            onInputEvent?(MirrorInputPayload(version: 1, sessionId: "", action: "key", x: nil, y: nil, deltaX: nil, deltaY: nil, keyCode: "backspace", text: nil))
            return
        }

        // Return / Enter (36 / 76): Shift+Enter inserts newline, Enter submits/sends
        if event.keyCode == 36 || event.keyCode == 76 {
            let isShift = event.modifierFlags.contains(.shift)
            onInputEvent?(MirrorInputPayload(version: 1, sessionId: "", action: "key", x: nil, y: nil, deltaX: nil, deltaY: nil, keyCode: isShift ? "shift_enter" : "enter", text: nil))
            return
        }

        // ESC or Cmd+[ -> Back
        if event.keyCode == 53 || (event.modifierFlags.contains(.command) && event.charactersIgnoringModifiers == "[") {
            onInputEvent?(MirrorInputPayload(version: 1, sessionId: "", action: "nav", x: nil, y: nil, deltaX: nil, deltaY: nil, keyCode: "back", text: nil))
            return
        }

        // Cmd+H -> Home
        if event.modifierFlags.contains(.command) && event.charactersIgnoringModifiers == "h" {
            onInputEvent?(MirrorInputPayload(version: 1, sessionId: "", action: "nav", x: nil, y: nil, deltaX: nil, deltaY: nil, keyCode: "home", text: nil))
            return
        }

        // Cmd+3 or Cmd+Tab -> Recents
        if event.modifierFlags.contains(.command) && (event.charactersIgnoringModifiers == "3" || event.keyCode == 48) {
            onInputEvent?(MirrorInputPayload(version: 1, sessionId: "", action: "nav", x: nil, y: nil, deltaX: nil, deltaY: nil, keyCode: "recents", text: nil))
            return
        }

        // Cmd / Ctrl Shortcuts: Select All (Cmd+A), Copy (Cmd+C), Paste (Cmd+V), Cut (Cmd+X), Undo (Cmd+Z)
        if event.modifierFlags.contains(.command) || event.modifierFlags.contains(.control) {
            let key = event.charactersIgnoringModifiers?.lowercased()
            switch key {
            case "a":
                onInputEvent?(MirrorInputPayload(version: 1, sessionId: "", action: "shortcut", x: nil, y: nil, deltaX: nil, deltaY: nil, keyCode: "select_all", text: nil))
                return
            case "c":
                onInputEvent?(MirrorInputPayload(version: 1, sessionId: "", action: "shortcut", x: nil, y: nil, deltaX: nil, deltaY: nil, keyCode: "copy", text: nil))
                return
            case "v":
                if let str = NSPasteboard.general.string(forType: .string), !str.isEmpty {
                    onInputEvent?(MirrorInputPayload(version: 1, sessionId: "", action: "text", x: nil, y: nil, deltaX: nil, deltaY: nil, keyCode: nil, text: str))
                } else {
                    onInputEvent?(MirrorInputPayload(version: 1, sessionId: "", action: "shortcut", x: nil, y: nil, deltaX: nil, deltaY: nil, keyCode: "paste", text: nil))
                }
                return
            case "x":
                onInputEvent?(MirrorInputPayload(version: 1, sessionId: "", action: "shortcut", x: nil, y: nil, deltaX: nil, deltaY: nil, keyCode: "cut", text: nil))
                return
            case "z":
                onInputEvent?(MirrorInputPayload(version: 1, sessionId: "", action: "shortcut", x: nil, y: nil, deltaX: nil, deltaY: nil, keyCode: "undo", text: nil))
                return
            default:
                break
            }
        }

        // Up arrow -> swipe up (next reel)
        if event.keyCode == 126 {
            onInputEvent?(MirrorInputPayload(version: 1, sessionId: "", action: "swipe", x: 0.5, y: 0.72, deltaX: 0.5, deltaY: 0.28, keyCode: "200", text: nil))
            return
        }

        // Down arrow -> swipe down (prev reel)
        if event.keyCode == 125 {
            onInputEvent?(MirrorInputPayload(version: 1, sessionId: "", action: "swipe", x: 0.5, y: 0.28, deltaX: 0.5, deltaY: 0.72, keyCode: "200", text: nil))
            return
        }

        // Left arrow -> swipe left
        if event.keyCode == 123 {
            onInputEvent?(MirrorInputPayload(version: 1, sessionId: "", action: "swipe", x: 0.75, y: 0.5, deltaX: 0.25, deltaY: 0.5, keyCode: "200", text: nil))
            return
        }

        // Right arrow -> swipe right
        if event.keyCode == 124 {
            onInputEvent?(MirrorInputPayload(version: 1, sessionId: "", action: "swipe", x: 0.25, y: 0.5, deltaX: 0.75, deltaY: 0.5, keyCode: "200", text: nil))
            return
        }

        // Printable text characters: filter out ASCII control characters
        if let characters = event.characters, !characters.isEmpty {
            let printable = characters.filter { char in
                if let ascii = char.asciiValue {
                    return ascii >= 32 && ascii != 127
                }
                return true
            }
            if !printable.isEmpty {
                onInputEvent?(MirrorInputPayload(
                    version: 1,
                    sessionId: "",
                    action: "text",
                    x: nil,
                    y: nil,
                    deltaX: nil,
                    deltaY: nil,
                    keyCode: "\(event.keyCode)",
                    text: printable
                ))
            }
        }
    }
}

// MARK: - Modern Floating Top HUD

final class MirrorToolbarState: ObservableObject {
    @Published var isAudioMuted: Bool = false
    @Published var isAlwaysOnTop: Bool = false
    @Published var isFullscreen: Bool = false
    @Published var isLandscape: Bool = false
    @Published var quality: MirrorQuality = .autoAdaptive
    @Published var availableAudioOutputs: [String] = ["System Default Output"]
    @Published var selectedAudioOutput: String = "System Default Output"
    @Published var containerWidth: CGFloat = 380

    let onBack: () -> Void
    let onHome: () -> Void
    let onRecents: () -> Void
    let onTogglePin: () -> Void
    let onToggleAudio: () -> Void
    let onSelectAudioOutput: (String) -> Void
    let onToggleOrientation: () -> Void
    let onToggleFullscreen: () -> Void
    let onQualityChanged: (MirrorQuality) -> Void
    let onDisconnect: () -> Void

    init(
        onBack: @escaping () -> Void,
        onHome: @escaping () -> Void,
        onRecents: @escaping () -> Void,
        onTogglePin: @escaping () -> Void,
        onToggleAudio: @escaping () -> Void,
        onSelectAudioOutput: @escaping (String) -> Void,
        onToggleOrientation: @escaping () -> Void,
        onToggleFullscreen: @escaping () -> Void,
        onQualityChanged: @escaping (MirrorQuality) -> Void,
        onDisconnect: @escaping () -> Void
    ) {
        self.onBack = onBack
        self.onHome = onHome
        self.onRecents = onRecents
        self.onTogglePin = onTogglePin
        self.onToggleAudio = onToggleAudio
        self.onSelectAudioOutput = onSelectAudioOutput
        self.onToggleOrientation = onToggleOrientation
        self.onToggleFullscreen = onToggleFullscreen
        self.onQualityChanged = onQualityChanged
        self.onDisconnect = onDisconnect
    }
}

struct QualityMenuButton: NSViewRepresentable {
    let title: String
    let isCompact: Bool
    let selectedQuality: MirrorQuality
    let onSelect: (MirrorQuality) -> Void

    func makeNSView(context: Context) -> NSPopUpButton {
        let button = NSPopUpButton(frame: .zero, pullsDown: true)
        button.isBordered = false
        button.wantsLayer = true
        button.target = context.coordinator
        button.action = #selector(Coordinator.menuSelected(_:))
        return button
    }

    func updateNSView(_ button: NSPopUpButton, context: Context) {
        context.coordinator.onSelect = onSelect
        button.menu?.removeAllItems()

        let headerItem = NSMenuItem()
        let attributedTitle = NSAttributedString(
            string: "\(title) ▾",
            attributes: [
                .foregroundColor: NSColor.white.withAlphaComponent(0.95),
                .font: NSFont.systemFont(ofSize: isCompact ? 10 : 11.5, weight: .semibold)
            ]
        )
        headerItem.attributedTitle = attributedTitle
        button.menu?.addItem(headerItem)

        let infoItem = NSMenuItem(title: "(i) Mac display: 60Hz", action: nil, keyEquivalent: "")
        infoItem.isEnabled = false
        button.menu?.addItem(infoItem)
        button.menu?.addItem(NSMenuItem.separator())

        for q in MirrorQuality.allCases {
            let item = NSMenuItem(title: q.rawValue, action: #selector(Coordinator.menuSelected(_:)), keyEquivalent: "")
            item.target = context.coordinator
            item.representedObject = q
            item.state = (q == selectedQuality) ? .on : .off
            button.menu?.addItem(item)
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onSelect: onSelect)
    }

    final class Coordinator: NSObject {
        var onSelect: (MirrorQuality) -> Void

        init(onSelect: @escaping (MirrorQuality) -> Void) {
            self.onSelect = onSelect
        }

        @objc func menuSelected(_ sender: NSMenuItem) {
            if let q = sender.representedObject as? MirrorQuality {
                onSelect(q)
            }
        }
    }
}

struct MirrorTopHudBar: View {
    @ObservedObject var state: MirrorToolbarState

    var isCompact: Bool {
        state.containerWidth > 0 && state.containerWidth < 345
    }

    var qualityShortTitle: String {
        switch state.quality {
        case .autoAdaptive: return "Auto"
        case .ultra1080p60: return "1080p+"
        case .high1080p60: return "1080p"
        case .smooth720p60: return "720p"
        case .saver720p30: return "Saver"
        }
    }

    var body: some View {
        HStack(spacing: isCompact ? 3.5 : 7.5) {
            // Audio Output Menu & Mute Toggle
            Menu {
                Button(state.isAudioMuted ? "Unmute Audio (Cmd+M)" : "Mute Audio (Cmd+M)") {
                    state.isAudioMuted.toggle()
                    state.onToggleAudio()
                }
                Divider()
                Text("Mac Audio Output").font(.caption).foregroundStyle(.secondary)
                ForEach(state.availableAudioOutputs, id: \.self) { output in
                    Button {
                        state.onSelectAudioOutput(output)
                    } label: {
                        HStack {
                            Text(output)
                            if output == state.selectedAudioOutput {
                                Spacer()
                                Image(systemName: "checkmark")
                            }
                        }
                    }
                }
            } label: {
                Image(systemName: state.isAudioMuted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                    .font(.system(size: isCompact ? 11 : 13, weight: .medium))
                    .foregroundStyle(!state.isAudioMuted ? Color.accentColor : Color.white.opacity(0.85))
                    .frame(width: isCompact ? 20 : 26, height: isCompact ? 20 : 26)
                    .contentShape(Rectangle())
            }
            .menuStyle(.borderlessButton)
            .menuIndicator(.hidden)
            .help(state.isAudioMuted ? "Audio Muted (Click for Output Devices)" : "Audio Playing (Click for Output Devices)")

            // Quality Switcher Pull-Down Button (Clean native AppKit pill - never truncated to "..." or glitched with "v ...")
            QualityMenuButton(
                title: qualityShortTitle,
                isCompact: isCompact,
                selectedQuality: state.quality,
                onSelect: { q in
                    state.quality = q
                    state.onQualityChanged(q)
                }
            )
            .frame(width: isCompact ? 48 : 58, height: isCompact ? 20 : 26)
            .background(Color.white.opacity(0.12), in: Capsule())

            // Divider
            Rectangle()
                .fill(Color.white.opacity(0.2))
                .frame(width: 1, height: isCompact ? 12 : 16)
                .padding(.horizontal, isCompact ? 0 : 1)

            // Rotate Orientation (Landscape / Portrait)
            Button(action: state.onToggleOrientation) {
                Image(systemName: "rotate.right")
                    .font(.system(size: isCompact ? 11 : 13, weight: .medium))
            }
            .buttonStyle(ModernHudIconStyle(isActive: state.isLandscape, isCompact: isCompact))
            .help(state.isLandscape ? "Switch to Portrait" : "Switch to Landscape")

            // Always on Top & Follow Across All Spaces / Workspaces
            Button {
                state.onTogglePin()
            } label: {
                Image(systemName: state.isAlwaysOnTop ? "pin.fill" : "pin")
                    .font(.system(size: isCompact ? 11 : 13, weight: .medium))
            }
            .buttonStyle(ModernHudIconStyle(isActive: state.isAlwaysOnTop, isCompact: isCompact))
            .help(state.isAlwaysOnTop ? "Unpin Window" : "Pin to Top & Follow Workspaces")

            // Fullscreen
            Button(action: state.onToggleFullscreen) {
                Image(systemName: state.isFullscreen ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right")
                    .font(.system(size: isCompact ? 11 : 13, weight: .medium))
            }
            .buttonStyle(ModernHudIconStyle(isActive: state.isFullscreen, isCompact: isCompact))
            .help(state.isFullscreen ? "Exit Fullscreen" : "Enter Fullscreen")

            // Disconnect / Close (With Safe Confirmation Dialog)
            Button(action: state.onDisconnect) {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: isCompact ? 12 : 14, weight: .semibold))
                    .foregroundStyle(.red.opacity(0.9))
            }
            .buttonStyle(.plain)
            .frame(width: isCompact ? 20 : 26, height: isCompact ? 20 : 26)
            .help("End SYNQ View (Cmd+W)")
        }
        .padding(.horizontal, isCompact ? 6 : 12)
        .frame(height: isCompact ? 36 : 44)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay(Capsule().strokeBorder(Color.white.opacity(0.24), lineWidth: 0.75))
        .shadow(color: .black.opacity(0.35), radius: 10, x: 0, y: 3)
    }
}

private struct ModernHudIconStyle: ButtonStyle {
    var isActive: Bool = false
    var isCompact: Bool = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(isActive ? Color.accentColor : Color.white.opacity(0.85))
            .opacity(configuration.isPressed ? 0.6 : 1.0)
            .frame(width: isCompact ? 20 : 26, height: isCompact ? 20 : 26)
    }
}

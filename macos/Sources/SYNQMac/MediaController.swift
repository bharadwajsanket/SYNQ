import AppKit
import Foundation
import ImageIO
import UniformTypeIdentifiers

// MARK: - MediaPlayer enum (Settings choice)

enum MediaPlayer: String, CaseIterable, Identifiable {
    case auto, music, spotify, off
    var id: String { rawValue }
    var title: String {
        switch self {
        case .auto: "Auto-detect"
        case .music: "Music"
        case .spotify: "Spotify"
        case .off: "Off"
        }
    }
    var bundleID: String? {
        switch self {
        case .auto: nil
        case .music: "com.apple.Music"
        case .spotify: "com.spotify.client"
        case .off: nil
        }
    }
}

// MARK: - MediaSourceItem

struct MediaSourceItem: Codable, Equatable, Identifiable {
    var id: String
    var name: String
    var title: String
    var artist: String
    var playing: Bool
    var position: Int
    var duration: Int
    var volume: Int
}

// MARK: - MediaSnapshot

struct MediaSnapshot: Equatable {
    var title = ""
    var artist = ""
    var appName = ""
    var playing = false
    var position = 0
    var duration = 0
    var volume = 0
    var detail = ""
    var artwork: String? = nil
    var selectedSourceId: String = ""
    var sources: [MediaSourceItem] = []

    func playerLabel(for player: MediaPlayer) -> String {
        if !appName.isEmpty { return appName }
        return player == .auto ? "Media" : player.title
    }

    func payload(player: MediaPlayer) -> [String: Any] {
        var value: [String: Any] = [
            "version": 1,
            "player": playerLabel(for: player),
            "title": title,
            "artist": artist,
            "playing": playing,
            "position": position,
            "duration": duration,
            "volume": volume,
            "detail": detail,
            "selectedSourceId": selectedSourceId,
            "sources": sources.map { [
                "id": $0.id,
                "name": $0.name,
                "title": $0.title,
                "artist": $0.artist,
                "playing": $0.playing,
                "position": $0.position,
                "duration": $0.duration,
                "volume": $0.volume,
            ] as [String: Any] },
        ]
        if let artwork { value["artwork"] = artwork }
        return value
    }
}

// MARK: - AppleScript helpers (Music / Spotify)

func mediaCommand(_ action: String, value: String) -> String? {
    switch action {
    case "toggle": return "playpause"
    case "pause": return "pause"
    case "next": return "next track"
    case "previous": return "previous track"
    case "seek":
        guard let seconds = Int(value), (0...604800).contains(seconds) else { return nil }
        return "set player position to \(seconds)"
    case "volume":
        guard let volume = Int(value), (0...100).contains(volume) else { return nil }
        return "set sound volume to \(volume)"
    default: return nil
    }
}

// Only fixed scripts and validated integer operands reach osascript. No remote source code.
private func runMediaScript(_ source: String, limit: Int = 8192) -> String? {
    let process = Process()
    let pipe = Pipe()
    process.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
    process.arguments = ["-e", source]
    process.standardOutput = pipe
    process.standardError = FileHandle.nullDevice
    let deadline = DispatchWorkItem { if process.isRunning { process.terminate() } }
    do { try process.run() } catch { return nil }
    DispatchQueue.global().asyncAfter(deadline: .now() + 4, execute: deadline)
    var data = Data()
    while let chunk = try? pipe.fileHandleForReading.read(upToCount: 8192), !chunk.isEmpty {
        guard data.count + chunk.count <= limit else {
            pipe.fileHandleForReading.closeFile()
            process.terminate()
            process.waitUntilExit()
            deadline.cancel()
            return nil
        }
        data.append(chunk)
    }
    process.waitUntilExit()
    deadline.cancel()
    guard process.terminationStatus == 0 else { return nil }
    return String(data: data, encoding: .utf8)?.trimmingCharacters(in: .newlines)
}

// osascript prints raw Apple-event data as «data TYPE<hex>». Never read player files directly.
func mediaArtworkData(_ descriptor: String) -> Data? {
    guard descriptor.utf8.count <= 2_100_000, descriptor.hasPrefix("«data "), descriptor.hasSuffix("»") else { return nil }
    let hex = descriptor.dropFirst(10).dropLast()
    guard !hex.isEmpty, hex.count.isMultiple(of: 2) else { return nil }
    var bytes = Data()
    var index = hex.startIndex
    while index < hex.endIndex {
        let end = hex.index(index, offsetBy: 2)
        guard let byte = UInt8(hex[index..<end], radix: 16) else { return nil }
        bytes.append(byte); index = end
    }
    return bytes
}

private func smallMediaArtwork(_ descriptor: String) -> String? {
    guard let data = mediaArtworkData(descriptor) else { return nil }
    return smallArtworkFromData(data)
}

private func smallArtworkFromData(_ data: Data) -> String? {
    guard let source = CGImageSourceCreateWithData(data as CFData, nil),
          let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: 128,
            kCGImageSourceCreateThumbnailWithTransform: true,
          ] as CFDictionary) else { return nil }
    let output = NSMutableData()
    guard let destination = CGImageDestinationCreateWithData(output, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
    CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.7] as CFDictionary)
    guard CGImageDestinationFinalize(destination), output.length <= 16384 else { return nil }
    return (output as Data).base64EncodedString()
}

// MARK: - MediaController

@MainActor
final class MediaController: ObservableObject {
    @Published var player: MediaPlayer {
        didSet {
            UserDefaults.standard.set(player.rawValue, forKey: "media.player")
            generation += 1
            refresh()
        }
    }
    @Published var pauseForCalls: Bool {
        didSet { UserDefaults.standard.set(pauseForCalls, forKey: "media.pauseForCalls") }
    }
    @Published private(set) var snapshot = MediaSnapshot()
    @Published var manualSelectedSourceId: String? = nil

    var onState: ([String: Any]) -> Void = { _ in }
    var allowed: () -> Bool = { false }

    // Internal state
    private var busy = false
    private var generation = 0
    private var callActive = false
    private var pendingPause = false
    private var playbackTimer: Timer?
    private var notificationObservers: [NSObjectProtocol] = []
    private let queue = DispatchQueue(label: "dev.synq.media")

    // Artwork cache
    private var lastArtworkKey: String = ""
    private var lastArtworkValue: String? = nil

    init() {
        player = MediaPlayer(rawValue: UserDefaults.standard.string(forKey: "media.player") ?? "") ?? .auto
        pauseForCalls = UserDefaults.standard.bool(forKey: "media.pauseForCalls")
        setupNotifications()
    }

    deinit {
        for observer in notificationObservers {
            DistributedNotificationCenter.default().removeObserver(observer)
        }
    }

    private func setupNotifications() {
        let center = DistributedNotificationCenter.default()
        let musicObserver = center.addObserver(
            forName: NSNotification.Name("com.apple.Music.playerInfo"),
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in self?.refresh() }
        }
        let spotifyObserver = center.addObserver(
            forName: NSNotification.Name("com.spotify.client.PlaybackStateChanged"),
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in self?.refresh() }
        }
        notificationObservers = [musicObserver, spotifyObserver]
    }

    // MARK: - Public API

    func refresh() { perform(nil, completion: { _ in }) }

    func resolvedPlayer() -> MediaPlayer? {
        switch player {
        case .off: return nil
        case .music:
            return NSRunningApplication.runningApplications(withBundleIdentifier: "com.apple.Music").isEmpty ? nil : .music
        case .spotify:
            return NSRunningApplication.runningApplications(withBundleIdentifier: "com.spotify.client").isEmpty ? nil : .spotify
        case .auto:
            return nil
        }
    }

    func selectSource(id: String) {
        if id == "auto" || id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            manualSelectedSourceId = nil
        } else {
            manualSelectedSourceId = id
        }
        generation += 1
        refresh()
    }

    func command(action: String, value: String, completion: @escaping (Bool) -> Void) {
        guard allowed() else { completion(false); return }

        if action == "selectSource" {
            selectSource(id: value)
            completion(true)
            return
        }

        // Determine target player (Apple Music or Spotify only)
        let targetBundleID: String
        if snapshot.selectedSourceId == "com.spotify.client" {
            targetBundleID = "com.spotify.client"
        } else if snapshot.selectedSourceId == "com.apple.Music" {
            targetBundleID = "com.apple.Music"
        } else if player == .spotify {
            targetBundleID = "com.spotify.client"
        } else {
            targetBundleID = "com.apple.Music"
        }

        guard let cmd = mediaCommand(action, value: value) else { completion(false); return }
        performAppleScript(bundleID: targetBundleID, command: cmd, completion: completion)
    }

    func callChanged(active: Bool) {
        let shouldPause = active && !callActive && pauseForCalls
        callActive = active
        if !active { pendingPause = false }
        if shouldPause {
            if busy { pendingPause = true }
            else { command(action: "pause", value: "", completion: { _ in }) }
        }
    }

    func reset() {
        generation += 1
        pendingPause = false
        updatePlaybackTimer(isPlaying: false)
        snapshot = MediaSnapshot()
        if allowed() {
            onState(snapshot.payload(player: player))
        }
    }

    // MARK: - Playback Timer

    private func updatePlaybackTimer(isPlaying: Bool) {
        playbackTimer?.invalidate()
        playbackTimer = nil
        if isPlaying {
            playbackTimer = Timer.scheduledTimer(withTimeInterval: 2.5, repeats: true) { [weak self] _ in
                Task { @MainActor [weak self] in
                    self?.refresh()
                }
            }
        }
    }

    // MARK: - Core Supported-Source Gathering (Apple Music & Spotify)

    private func perform(_ command: String?, completion: @escaping (Bool) -> Void) {
        guard allowed() else {
            snapshot = MediaSnapshot()
            updatePlaybackTimer(isPlaying: false)
            completion(false)
            return
        }

        guard player != .off else {
            snapshot = MediaSnapshot(detail: "Select Music or Spotify in Mac Settings → Media")
            updatePlaybackTimer(isPlaying: false)
            onState(snapshot.payload(player: .off))
            completion(false)
            return
        }

        guard !busy else { completion(false); return }
        busy = true
        let epoch = generation
        let currentPlayer = player
        let currentManualId = manualSelectedSourceId
        let cachedArtworkKey = lastArtworkKey
        let cachedArtworkValue = lastArtworkValue

        let musicRunning = !NSRunningApplication.runningApplications(withBundleIdentifier: "com.apple.Music").isEmpty
        let spotifyRunning = !NSRunningApplication.runningApplications(withBundleIdentifier: "com.spotify.client").isEmpty

        guard musicRunning || spotifyRunning else {
            let fallbackName = currentPlayer == .spotify ? "Spotify" : (currentPlayer == .music ? "Music" : "Music or Spotify")
            let emptySnapshot = MediaSnapshot(
                detail: "Open \(fallbackName) on your Mac",
                selectedSourceId: "",
                sources: []
            )
            DispatchQueue.main.async {
                self.busy = false
                guard epoch == self.generation, self.allowed() else {
                    if command == nil { completion(false) }
                    return
                }
                self.snapshot = emptySnapshot
                self.updatePlaybackTimer(isPlaying: false)
                self.onState(emptySnapshot.payload(player: self.player))
                completion(false)
            }
            return
        }

        let asyncQueue = DispatchQueue.global(qos: .userInitiated)
        asyncQueue.async { [weak self] in
            guard let self else { return }

            var detectedSources: [MediaSourceItem] = []
            let lock = NSLock()
            let group = DispatchGroup()

            // 1) Query Apple Music (if running)
            if musicRunning {
                group.enter()
                asyncQueue.async {
                    let script = """
                    with timeout of 2 seconds
                    tell application id "com.apple.Music"
                        set sep to ASCII character 31
                        set t to ""
                        set a to ""
                        set d to 0
                        set p to 0
                        set s to (player state as text)
                        try
                            set t to name of current track
                            set a to artist of current track
                            set d to duration of current track
                            set p to player position
                        end try
                        if (count t) > 256 then set t to text 1 thru 256 of t
                        if (count a) > 256 then set a to text 1 thru 256 of a
                        return t & sep & a & sep & s & sep & (p as integer) & sep & (d as integer) & sep & sound volume
                    end tell
                    end timeout
                    """
                    if let output = runMediaScript(script) {
                        let fields = output.components(separatedBy: "\u{1f}")
                        if fields.count == 6 {
                            let title = fields[0]
                            let artist = fields[1]
                            let isPlaying = fields[2] == "playing"
                            let pos = max(0, Int(fields[3]) ?? 0)
                            let dur = max(0, Int(fields[4]) ?? 0)
                            let vol = max(0, min(100, Int(fields[5]) ?? 100))
                            if !title.isEmpty || isPlaying {
                                lock.lock()
                                detectedSources.append(MediaSourceItem(
                                    id: "com.apple.Music",
                                    name: "Music",
                                    title: title,
                                    artist: artist,
                                    playing: isPlaying,
                                    position: pos,
                                    duration: dur,
                                    volume: vol
                                ))
                                lock.unlock()
                            }
                        }
                    }
                    group.leave()
                }
            }

            // 2) Query Spotify (if running)
            if spotifyRunning {
                group.enter()
                asyncQueue.async {
                    let script = """
                    with timeout of 2 seconds
                    tell application id "com.spotify.client"
                        set sep to ASCII character 31
                        set t to ""
                        set a to ""
                        set d to 0
                        set p to 0
                        set s to (player state as text)
                        try
                            set t to name of current track
                            set a to artist of current track
                            set d to (duration of current track) / 1000
                            set p to player position
                        end try
                        if (count t) > 256 then set t to text 1 thru 256 of t
                        if (count a) > 256 then set a to text 1 thru 256 of a
                        return t & sep & a & sep & s & sep & (p as integer) & sep & (d as integer) & sep & sound volume
                    end tell
                    end timeout
                    """
                    if let output = runMediaScript(script) {
                        let fields = output.components(separatedBy: "\u{1f}")
                        if fields.count == 6 {
                            let title = fields[0]
                            let artist = fields[1]
                            let isPlaying = fields[2] == "playing"
                            let pos = max(0, Int(fields[3]) ?? 0)
                            let dur = max(0, Int(fields[4]) ?? 0)
                            let vol = max(0, min(100, Int(fields[5]) ?? 100))
                            if !title.isEmpty || isPlaying {
                                lock.lock()
                                detectedSources.append(MediaSourceItem(
                                    id: "com.spotify.client",
                                    name: "Spotify",
                                    title: title,
                                    artist: artist,
                                    playing: isPlaying,
                                    position: pos,
                                    duration: dur,
                                    volume: vol
                                ))
                                lock.unlock()
                            }
                        }
                    }
                    group.leave()
                }
            }

            group.notify(queue: asyncQueue) {
                lock.lock()
                let sources = detectedSources
                lock.unlock()

                // Determine active/selected source:
                var chosen: MediaSourceItem? = nil

                // 1. Manual user selection
                if let manualId = currentManualId, let match = sources.first(where: { $0.id == manualId }) {
                    chosen = match
                }

                // 2. Settings player preference
                if chosen == nil {
                    if currentPlayer == .music, let match = sources.first(where: { $0.id == "com.apple.Music" }) {
                        chosen = match
                    } else if currentPlayer == .spotify, let match = sources.first(where: { $0.id == "com.spotify.client" }) {
                        chosen = match
                    }
                }

                // 3. Auto-detect: Prioritize ACTUALLY PLAYING sources
                if chosen == nil {
                    let playingSources = sources.filter { $0.playing }
                    if let firstPlaying = playingSources.first {
                        chosen = firstPlaying
                    } else {
                        chosen = sources.first(where: { !$0.title.isEmpty }) ?? sources.first
                    }
                }

                // Fetch artwork for chosen source (Apple Music or Spotify)
                var finalArtwork: String? = nil
                var newArtworkKey: String? = nil
                var newArtworkValue: String? = nil
                if let active = chosen, !active.title.isEmpty {
                    let cacheKey = "\(active.id)|\(active.title)|\(active.artist)"
                    if cachedArtworkKey == cacheKey {
                        finalArtwork = cachedArtworkValue
                    } else if active.id == "com.apple.Music" {
                        let artScript = "tell application id \"com.apple.Music\" to return raw data of artwork 1 of current track"
                        if let artRaw = runMediaScript(artScript, limit: 2_100_000) {
                            finalArtwork = smallMediaArtwork(artRaw)
                            newArtworkKey = cacheKey
                            newArtworkValue = finalArtwork
                        }
                    } else if active.id == "com.spotify.client" {
                        let artUrlScript = "tell application id \"com.spotify.client\" to return artwork url of current track"
                        if let urlString = runMediaScript(artUrlScript),
                           let url = URL(string: urlString),
                           url.scheme == "https" || url.scheme == "http" {
                            let config = URLSessionConfiguration.ephemeral
                            config.timeoutIntervalForRequest = 2.5
                            config.timeoutIntervalForResource = 2.5
                            let session = URLSession(configuration: config)
                            var imgData: Data? = nil
                            let sema = DispatchSemaphore(value: 0)
                            let task = session.dataTask(with: url) { data, response, _ in
                                if let httpResp = response as? HTTPURLResponse, httpResp.statusCode == 200, let d = data, d.count <= 2_097_152 {
                                    imgData = d
                                }
                                sema.signal()
                            }
                            task.resume()
                            _ = sema.wait(timeout: .now() + 2.5)
                            session.finishTasksAndInvalidate()
                            if let data = imgData, let b64 = smallArtworkFromData(data) {
                                finalArtwork = b64
                                newArtworkKey = cacheKey
                                newArtworkValue = b64
                            }
                        }
                    }
                }

                let snapshot: MediaSnapshot
                if let active = chosen {
                    snapshot = MediaSnapshot(
                        title: active.title,
                        artist: active.artist,
                        appName: active.name,
                        playing: active.playing,
                        position: active.position,
                        duration: active.duration,
                        volume: active.volume,
                        detail: active.title.isEmpty ? "\(active.name) is ready" : "",
                        artwork: finalArtwork,
                        selectedSourceId: active.id,
                        sources: sources
                    )
                } else {
                    let fallbackName = currentPlayer == .spotify ? "Spotify" : (currentPlayer == .music ? "Music" : "Music or Spotify")
                    snapshot = MediaSnapshot(
                        detail: "Open \(fallbackName) on your Mac",
                        selectedSourceId: "",
                        sources: sources
                    )
                }

                DispatchQueue.main.async {
                    self.busy = false
                    guard epoch == self.generation, self.allowed() else {
                        if command == nil { completion(false) }
                        return
                    }

                    if let key = newArtworkKey {
                        self.lastArtworkKey = key
                        self.lastArtworkValue = newArtworkValue
                    }

                    self.snapshot = snapshot
                    self.updatePlaybackTimer(isPlaying: snapshot.playing)
                    self.onState(snapshot.payload(player: self.player))
                    completion(true)

                    if self.pendingPause && self.callActive && self.pauseForCalls {
                        self.pendingPause = false
                        self.command(action: "pause", value: "", completion: { _ in })
                    }
                }
            }
        }
    }

    private func performAppleScript(bundleID: String, command: String, completion: @escaping (Bool) -> Void) {
        queue.async { [weak self] in
            guard let self else { return }
            let prefix = "with timeout of 3 seconds\ntell application id \"\(bundleID)\"\n"
            let suffix = "\nend tell\nend timeout"
            let success = runMediaScript(prefix + command + suffix) != nil
            DispatchQueue.main.async {
                completion(success)
                if success {
                    Task { @MainActor in
                        try? await Task.sleep(nanoseconds: 200_000_000)
                        self.refresh()
                    }
                }
            }
        }
    }
}

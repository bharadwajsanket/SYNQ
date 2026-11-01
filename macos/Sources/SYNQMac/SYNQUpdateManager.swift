import AppKit
import CryptoKit
import Foundation

public struct MacOSUpdateManifest: Equatable {
    public let version: String
    public let buildNumber: String
    public let releaseDate: String
    public let releaseNotes: String
    public let downloadUrl: URL
    public let sha256: String
    public let size: Int64

    public init(
        version: String,
        buildNumber: String,
        releaseDate: String,
        releaseNotes: String,
        downloadUrl: URL,
        sha256: String,
        size: Int64
    ) {
        self.version = version
        self.buildNumber = buildNumber
        self.releaseDate = releaseDate
        self.releaseNotes = releaseNotes
        self.downloadUrl = downloadUrl
        self.sha256 = sha256
        self.size = size
    }
}

public enum UpdatePreference: String, CaseIterable, Identifiable {
    case downloadAndInstall = "download_and_install"
    case downloadOnly = "download_only"
    case informOnly = "inform_only"

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .downloadAndInstall: return "Download & Install"
        case .downloadOnly: return "Only Download"
        case .informOnly: return "Inform Only"
        }
    }

    public var description: String {
        switch self {
        case .downloadAndInstall:
            return "Automatically download verified updates and mount installer."
        case .downloadOnly:
            return "Download verified disk image to Downloads and reveal in Finder."
        case .informOnly:
            return "Notify when updates are available without downloading."
        }
    }
}

@MainActor
public final class SYNQUpdateManager: ObservableObject {
    public static let shared = SYNQUpdateManager()

    public enum Status: Equatable {
        case idle
        case checking
        case updateAvailable(manifest: MacOSUpdateManifest)
        case downloading(progress: Double, bytesReceived: Int64, totalBytes: Int64, target: DownloadTarget)
        case verifying
        case readyToInstall(dmgURL: URL, version: String)
        case downloaded(dmgURL: URL, version: String)
        case upToDate(version: String)
        case error(String)

        public static func == (lhs: Status, rhs: Status) -> Bool {
            switch (lhs, rhs) {
            case (.idle, .idle), (.checking, .checking), (.verifying, .verifying):
                return true
            case let (.updateAvailable(m1), .updateAvailable(m2)):
                return m1.version == m2.version && m1.sha256 == m2.sha256
            case let (.downloading(p1, b1, t1, m1), .downloading(p2, b2, t2, m2)):
                return p1 == p2 && b1 == b2 && t1 == t2 && m1 == m2
            case let (.readyToInstall(u1, v1), .readyToInstall(u2, v2)):
                return u1 == u2 && v1 == v2
            case let (.downloaded(u1, v1), .downloaded(u2, v2)):
                return u1 == u2 && v1 == v2
            case let (.upToDate(v1), .upToDate(v2)):
                return v1 == v2
            case let (.error(e1), .error(e2)):
                return e1 == e2
            default:
                return false
            }
        }
    }

    public enum DownloadTarget: Equatable {
        case install
        case downloadOnly
    }

    @Published public private(set) var status: Status = .idle
    @Published public private(set) var lastCheckDate: Date?
    @Published public private(set) var lastCheckMessage: String?
    @Published public var preference: UpdatePreference {
        didSet {
            UserDefaults.standard.set(preference.rawValue, forKey: "SYNQUpdatePreference")
        }
    }

    public static let manifestURL = URL(string: "https://raw.githubusercontent.com/bharadwajsanket/SYNQ/main/release-manifest.json")!

    public var currentVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "3.5.4"
    }

    public var currentBuild: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "35"
    }

    private var downloadTask: Task<Void, Never>?

    public init() {
        if let saved = UserDefaults.standard.string(forKey: "SYNQUpdatePreference"),
           let pref = UpdatePreference(rawValue: saved) {
            self.preference = pref
        } else {
            self.preference = .downloadAndInstall
        }
    }

    public func checkForUpdates(informOnly: Bool = false) {
        guard status != .checking else { return }
        status = .checking
        lastCheckMessage = nil

        Task {
            do {
                var request = URLRequest(url: Self.manifestURL)
                request.timeoutInterval = 10
                request.cachePolicy = .reloadIgnoringLocalCacheData
                request.setValue("SYNQ-macOS/\(currentVersion)", forHTTPHeaderField: "User-Agent")

                let (data, response) = try await URLSession.shared.data(for: request)
                guard let http = response as? HTTPURLResponse else {
                    let err = "Invalid server response received."
                    status = .error(err)
                    lastCheckMessage = err
                    return
                }

                if http.statusCode == 404 {
                    // Remote manifest not yet published (e.g. before GitHub push)
                    lastCheckDate = Date()
                    let msg = "SYNQ \(currentVersion) is up to date (no remote updates published)."
                    status = .upToDate(version: currentVersion)
                    lastCheckMessage = msg
                    return
                }

                guard (200...299).contains(http.statusCode) else {
                    let err = "Server responded with HTTP \(http.statusCode)"
                    status = .error(err)
                    lastCheckMessage = err
                    return
                }

                guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                    let err = "Release manifest could not be parsed."
                    status = .error(err)
                    lastCheckMessage = err
                    return
                }

                var macosDict: [String: Any]?
                if let platforms = json["platforms"] as? [String: Any],
                   let mac = platforms["macos"] as? [String: Any] {
                    macosDict = mac
                } else if let mac = json["macos"] as? [String: Any] {
                    macosDict = mac
                }

                guard let mac = macosDict,
                      let version = mac["version"] as? String,
                      let urlStr = mac["downloadUrl"] as? String,
                      let url = URL(string: urlStr) else {
                    let err = "No macOS release information in manifest."
                    status = .error(err)
                    lastCheckMessage = err
                    return
                }

                let build = (mac["buildNumber"] as? String) ?? (mac["versionCode"] as? String) ?? "35"
                let notes = (mac["releaseNotes"] as? String) ?? (json["releaseNotes"] as? String) ?? ""
                let sha = (mac["sha256"] as? String)?.lowercased().trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                let size = (mac["size"] as? Int64) ?? Int64((mac["size"] as? Int) ?? 0)
                let releaseDate = (json["releaseDate"] as? String) ?? ""

                let manifest = MacOSUpdateManifest(
                    version: version,
                    buildNumber: build,
                    releaseDate: releaseDate,
                    releaseNotes: notes,
                    downloadUrl: url,
                    sha256: sha,
                    size: size
                )

                lastCheckDate = Date()

                if isVersion(version, newerThan: currentVersion) {
                    status = .updateAvailable(manifest: manifest)
                    lastCheckMessage = "New version \(version) available."
                    if !informOnly && preference == .downloadAndInstall {
                        downloadAndInstall(manifest: manifest)
                    } else if !informOnly && preference == .downloadOnly {
                        downloadOnly(manifest: manifest)
                    }
                } else {
                    let msg = "SYNQ \(currentVersion) is currently the newest version available."
                    status = .upToDate(version: currentVersion)
                    lastCheckMessage = msg
                }
            } catch {
                lastCheckDate = Date()
                let nsErr = error as NSError
                let userFriendlyMsg: String
                if nsErr.domain == NSURLErrorDomain {
                    userFriendlyMsg = "Could not check for updates: \(error.localizedDescription)"
                } else {
                    userFriendlyMsg = error.localizedDescription
                }
                status = .error(userFriendlyMsg)
                lastCheckMessage = userFriendlyMsg
            }
        }
    }

    public func downloadAndInstall(manifest: MacOSUpdateManifest) {
        startDownload(manifest: manifest, target: .install)
    }

    public func downloadOnly(manifest: MacOSUpdateManifest) {
        startDownload(manifest: manifest, target: .downloadOnly)
    }

    public func checkOnly() {
        checkForUpdates(informOnly: true)
    }

    public func cancelDownload() {
        downloadTask?.cancel()
        downloadTask = nil
        status = .idle
    }

    private func startDownload(manifest: MacOSUpdateManifest, target: DownloadTarget) {
        downloadTask?.cancel()
        downloadTask = Task {
            await performDownload(manifest: manifest, target: target)
        }
    }

    private func performDownload(manifest: MacOSUpdateManifest, target: DownloadTarget) async {
        let cachesDir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
            .appendingPathComponent("dev.synq.mac", isDirectory: true)
            .appendingPathComponent("updates", isDirectory: true)

        try? FileManager.default.createDirectory(at: cachesDir, withIntermediateDirectories: true)

        let partURL = cachesDir.appendingPathComponent("SYNQ-\(manifest.version).dmg.part")
        let cacheFinalURL = cachesDir.appendingPathComponent("SYNQ-\(manifest.version).dmg")

        try? FileManager.default.removeItem(at: partURL)

        status = .downloading(progress: 0.0, bytesReceived: 0, totalBytes: manifest.size, target: target)

        do {
            var request = URLRequest(url: manifest.downloadUrl)
            request.setValue("SYNQ-macOS/\(currentVersion)", forHTTPHeaderField: "User-Agent")

            let (bytes, response) = try await URLSession.shared.bytes(for: request)
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
                throw NSError(
                    domain: "SYNQUpdate",
                    code: 1,
                    userInfo: [NSLocalizedDescriptionKey: "Download failed with HTTP \((response as? HTTPURLResponse)?.statusCode ?? 0)"]
                )
            }

            let totalBytes = response.expectedContentLength > 0 ? response.expectedContentLength : manifest.size
            var downloadedBytes: Int64 = 0
            var hasher = SHA256()

            let handle = try FileHandle(forWritingTo: {
                FileManager.default.createFile(atPath: partURL.path, contents: nil)
                return partURL
            }())

            defer { try? handle.close() }

            var buffer = Data()
            buffer.reserveCapacity(64 * 1024)
            var lastUpdate = Date()

            for try await byte in bytes {
                if Task.isCancelled {
                    try? FileManager.default.removeItem(at: partURL)
                    status = .idle
                    return
                }

                buffer.append(byte)
                downloadedBytes += 1

                if buffer.count >= 64 * 1024 {
                    hasher.update(data: buffer)
                    try handle.write(contentsOf: buffer)
                    buffer.removeAll(keepingCapacity: true)

                    let now = Date()
                    if now.timeIntervalSince(lastUpdate) >= 0.15 {
                        lastUpdate = now
                        let pct = totalBytes > 0 ? Double(downloadedBytes) / Double(totalBytes) : 0.0
                        status = .downloading(progress: pct, bytesReceived: downloadedBytes, totalBytes: totalBytes, target: target)
                    }
                }
            }

            if !buffer.isEmpty {
                hasher.update(data: buffer)
                try handle.write(contentsOf: buffer)
            }

            status = .verifying

            // Verify file size
            if manifest.size > 0 && downloadedBytes != manifest.size {
                try? FileManager.default.removeItem(at: partURL)
                throw NSError(
                    domain: "SYNQUpdate",
                    code: 2,
                    userInfo: [NSLocalizedDescriptionKey: "File size mismatch: downloaded \(downloadedBytes) bytes, expected \(manifest.size)"]
                )
            }

            // Verify SHA-256
            let computedDigest = hasher.finalize().compactMap { String(format: "%02x", $0) }.joined()
            if !manifest.sha256.isEmpty && computedDigest.lowercased() != manifest.sha256.lowercased() {
                try? FileManager.default.removeItem(at: partURL)
                throw NSError(
                    domain: "SYNQUpdate",
                    code: 3,
                    userInfo: [NSLocalizedDescriptionKey: "Cryptographic hash mismatch: expected \(manifest.sha256), got \(computedDigest)"]
                )
            }

            try? FileManager.default.removeItem(at: cacheFinalURL)
            try FileManager.default.moveItem(at: partURL, to: cacheFinalURL)

            if target == .downloadOnly {
                // Move to user's ~/Downloads directory
                let downloadsDir = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first ?? cachesDir
                let userFinalURL = downloadsDir.appendingPathComponent("SYNQ-\(manifest.version).dmg")
                try? FileManager.default.removeItem(at: userFinalURL)
                do {
                    try FileManager.default.copyItem(at: cacheFinalURL, to: userFinalURL)
                    status = .downloaded(dmgURL: userFinalURL, version: manifest.version)
                    revealDownloadedFile(dmgURL: userFinalURL)
                } catch {
                    status = .downloaded(dmgURL: cacheFinalURL, version: manifest.version)
                    revealDownloadedFile(dmgURL: cacheFinalURL)
                }
            } else {
                status = .readyToInstall(dmgURL: cacheFinalURL, version: manifest.version)
                installUpdate(dmgURL: cacheFinalURL)
            }
        } catch {
            try? FileManager.default.removeItem(at: partURL)
            status = .error(error.localizedDescription)
            lastCheckMessage = error.localizedDescription
        }
    }

    public func installUpdate(dmgURL: URL) {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/hdiutil")
        process.arguments = ["attach", "-nobrowse", "-readonly", dmgURL.path]

        let pipe = Pipe()
        process.standardOutput = pipe

        do {
            try process.run()
            process.waitUntilExit()

            let output = String(data: pipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
            let lines = output.components(separatedBy: .newlines)
            var mountPoint: URL?
            for line in lines {
                if let range = line.range(of: "/Volumes/") {
                    let path = String(line[range.lowerBound...]).trimmingCharacters(in: .whitespacesAndNewlines)
                    mountPoint = URL(fileURLWithPath: path)
                    break
                }
            }

            if let mount = mountPoint {
                NSWorkspace.shared.open(mount)
            } else {
                NSWorkspace.shared.open(dmgURL)
            }
        } catch {
            NSWorkspace.shared.open(dmgURL)
        }
    }

    public func revealDownloadedFile(dmgURL: URL) {
        NSWorkspace.shared.activateFileViewerSelecting([dmgURL])
    }

    private func isVersion(_ v1: String, newerThan v2: String) -> Bool {
        let parts1 = v1.split(separator: ".").compactMap { Int($0) }
        let parts2 = v2.split(separator: ".").compactMap { Int($0) }
        let maxCount = max(parts1.count, parts2.count)
        for i in 0..<maxCount {
            let p1 = i < parts1.count ? parts1[i] : 0
            let p2 = i < parts2.count ? parts2[i] : 0
            if p1 > p2 { return true }
            if p1 < p2 { return false }
        }
        return false
    }
}

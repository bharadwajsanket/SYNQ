import AppKit
import CryptoKit
import Foundation

public struct MacOSUpdateManifest {
    public let version: String
    public let buildNumber: String
    public let releaseDate: String
    public let releaseNotes: String
    public let downloadUrl: URL
    public let sha256: String
    public let size: Int64
}

@MainActor
public final class SYNQUpdateManager: ObservableObject {
    public static let shared = SYNQUpdateManager()

    public enum Status: Equatable {
        case idle
        case checking
        case updateAvailable(manifest: MacOSUpdateManifest)
        case downloading(progress: Double, bytesReceived: Int64, totalBytes: Int64)
        case verifying
        case readyToInstall(dmgURL: URL, version: String)
        case upToDate(version: String)
        case error(String)

        public static func == (lhs: Status, rhs: Status) -> Bool {
            switch (lhs, rhs) {
            case (.idle, .idle), (.checking, .checking), (.verifying, .verifying):
                return true
            case let (.updateAvailable(m1), .updateAvailable(m2)):
                return m1.version == m2.version && m1.sha256 == m2.sha256
            case let (.downloading(p1, b1, t1), .downloading(p2, b2, t2)):
                return p1 == p2 && b1 == b2 && t1 == t2
            case let (.readyToInstall(u1, v1), .readyToInstall(u2, v2)):
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

    @Published public private(set) var status: Status = .idle

    public static let manifestURL = URL(string: "https://raw.githubusercontent.com/bharadwajsanket/SYNQ/main/release-manifest.json")!

    public var currentVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "3.5.4"
    }

    public var currentBuild: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "35"
    }

    private var downloadTask: Task<Void, Never>?

    public init() {}

    public func checkForUpdates(silent: Bool = false) {
        guard status != .checking else { return }
        status = .checking

        Task {
            do {
                let (data, response) = try await URLSession.shared.data(from: Self.manifestURL)
                guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
                    let err = "Failed to fetch update manifest: HTTP \((response as? HTTPURLResponse)?.statusCode ?? 0)"
                    status = .error(err)
                    if !silent { showStatusAlert(title: "Update Check Failed", message: err) }
                    return
                }

                guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                    let err = "Invalid manifest response format"
                    status = .error(err)
                    if !silent { showStatusAlert(title: "Update Check Failed", message: err) }
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
                    let err = "No macOS release found in manifest"
                    status = .error(err)
                    if !silent { showStatusAlert(title: "Update Check Failed", message: err) }
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

                if isVersion(version, newerThan: currentVersion) {
                    status = .updateAvailable(manifest: manifest)
                    promptForUpdate(manifest: manifest)
                } else {
                    status = .upToDate(version: currentVersion)
                    if !silent {
                        showStatusAlert(
                            title: "You're Up to Date",
                            message: "SYNQ \(currentVersion) is currently the newest version available."
                        )
                    }
                }
            } catch {
                let err = error.localizedDescription
                status = .error(err)
                if !silent { showStatusAlert(title: "Update Check Failed", message: err) }
            }
        }
    }

    public func startDownload(manifest: MacOSUpdateManifest) {
        downloadTask?.cancel()
        downloadTask = Task {
            await performDownload(manifest: manifest)
        }
    }

    public func cancelDownload() {
        downloadTask?.cancel()
        downloadTask = nil
        status = .idle
    }

    private func performDownload(manifest: MacOSUpdateManifest) async {
        let cachesDir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
            .appendingPathComponent("dev.synq.mac", isDirectory: true)
            .appendingPathComponent("updates", isDirectory: true)

        try? FileManager.default.createDirectory(at: cachesDir, withIntermediateDirectories: true)

        let partURL = cachesDir.appendingPathComponent("SYNQ-\(manifest.version).dmg.part")
        let finalURL = cachesDir.appendingPathComponent("SYNQ-\(manifest.version).dmg")

        try? FileManager.default.removeItem(at: partURL)

        status = .downloading(progress: 0.0, bytesReceived: 0, totalBytes: manifest.size)

        do {
            var request = URLRequest(url: manifest.downloadUrl)
            request.setValue("SYNQ-macOS/\(currentVersion)", forHTTPHeaderField: "User-Agent")

            let (bytes, response) = try await URLSession.shared.bytes(for: request)
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
                throw NSError(domain: "SYNQUpdate", code: 1, userInfo: [NSLocalizedDescriptionKey: "Download failed with HTTP \((response as? HTTPURLResponse)?.statusCode ?? 0)"])
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
                        status = .downloading(progress: pct, bytesReceived: downloadedBytes, totalBytes: totalBytes)
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
                throw NSError(domain: "SYNQUpdate", code: 2, userInfo: [NSLocalizedDescriptionKey: "File size mismatch: downloaded \(downloadedBytes) bytes, expected \(manifest.size)"])
            }

            // Verify SHA-256
            let computedDigest = hasher.finalize().compactMap { String(format: "%02x", $0) }.joined()
            if !manifest.sha256.isEmpty && computedDigest.lowercased() != manifest.sha256.lowercased() {
                try? FileManager.default.removeItem(at: partURL)
                throw NSError(domain: "SYNQUpdate", code: 3, userInfo: [NSLocalizedDescriptionKey: "Cryptographic hash mismatch: expected \(manifest.sha256), got \(computedDigest)"])
            }

            try? FileManager.default.removeItem(at: finalURL)
            try FileManager.default.moveItem(at: partURL, to: finalURL)

            status = .readyToInstall(dmgURL: finalURL, version: manifest.version)
            promptToInstall(dmgURL: finalURL, version: manifest.version)
        } catch {
            try? FileManager.default.removeItem(at: partURL)
            status = .error(error.localizedDescription)
            showStatusAlert(title: "Update Download Failed", message: error.localizedDescription)
        }
    }

    public func installUpdate(dmgURL: URL) {
        // Mount the verified DMG image
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/hdiutil")
        process.arguments = ["attach", "-nobrowse", "-readonly", dmgURL.path]

        let pipe = Pipe()
        process.standardOutput = pipe

        do {
            try process.run()
            process.waitUntilExit()

            let output = String(data: pipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
            // Locate mount point from hdiutil output (last column)
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

    private func promptForUpdate(manifest: MacOSUpdateManifest) {
        let alert = NSAlert()
        alert.messageText = "SYNQ \(manifest.version) is Available"
        let sizeMB = Double(manifest.size) / (1024.0 * 1024.0)
        let sizeStr = sizeMB > 0 ? String(format: " (%.1f MB)", sizeMB) : ""
        alert.informativeText = manifest.releaseNotes.isEmpty
            ? "A new version of SYNQ is ready for download\(sizeStr)."
            : "\(manifest.releaseNotes)\n\nWould you like to download it now\(sizeStr)?"
        alert.alertStyle = .informational
        alert.addButton(withTitle: "Download & Install")
        alert.addButton(withTitle: "Later")

        let response = alert.runModal()
        if response == .alertFirstButtonReturn {
            startDownload(manifest: manifest)
        }
    }

    private func promptToInstall(dmgURL: URL, version: String) {
        let alert = NSAlert()
        alert.messageText = "SYNQ \(version) Ready to Install"
        alert.informativeText = "The update has been downloaded and cryptographically verified.\n\nClick Install to open the update package."
        alert.alertStyle = .informational
        alert.addButton(withTitle: "Install")
        alert.addButton(withTitle: "Later")

        let response = alert.runModal()
        if response == .alertFirstButtonReturn {
            installUpdate(dmgURL: dmgURL)
        }
    }

    private func showStatusAlert(title: String, message: String) {
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = message
        alert.alertStyle = .informational
        alert.addButton(withTitle: "OK")
        alert.runModal()
    }
}

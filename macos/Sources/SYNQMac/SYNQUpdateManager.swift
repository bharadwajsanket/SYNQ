import AppKit
import Foundation

public struct MacOSUpdateInfo {
    public let version: String
    public let buildNumber: String
    public let releaseDate: String
    public let releaseNotes: String
    public let downloadUrl: URL
    public let sha256: String
}

@MainActor
public final class SYNQUpdateManager: ObservableObject {
    public static let shared = SYNQUpdateManager()

    public enum Status: Equatable {
        case idle
        case checking
        case updateAvailable(version: String, notes: String, url: URL)
        case upToDate(version: String)
        case error(String)
    }

    @Published public private(set) var status: Status = .idle

    public static let manifestURL = URL(string: "https://raw.githubusercontent.com/bharadwajsanket/SYNQ/main/release-manifest.json")!

    public var currentVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "3.5.4"
    }

    public var currentBuild: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "35"
    }

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

                let notes = (mac["releaseNotes"] as? String) ?? (json["releaseNotes"] as? String) ?? ""

                if isVersion(version, newerThan: currentVersion) {
                    status = .updateAvailable(version: version, notes: notes, url: url)
                    promptForUpdate(version: version, notes: notes, url: url)
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

    private func promptForUpdate(version: String, notes: String, url: URL) {
        let alert = NSAlert()
        alert.messageText = "SYNQ \(version) is Available"
        alert.informativeText = notes.isEmpty
            ? "A new version of SYNQ is ready for download."
            : "\(notes)\n\nWould you like to download it now?"
        alert.alertStyle = .informational
        alert.addButton(withTitle: "Download Update")
        alert.addButton(withTitle: "Later")

        let response = alert.runModal()
        if response == .alertFirstButtonReturn {
            NSWorkspace.shared.open(url)
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

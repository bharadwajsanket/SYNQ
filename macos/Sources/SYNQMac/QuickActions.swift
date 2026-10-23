import AppKit
import Foundation
import UserNotifications

struct QuickRequestSequence {
    private var last: [String: Int] = [:]
    mutating func accept(feature: String, sequence: Int) -> Bool {
        guard ["links", "media"].contains(feature), (1...9_007_199_254_740_991).contains(sequence),
              sequence > last[feature, default: 0] else { return false }
        last[feature] = sequence
        return true
    }
}

public func detectSmartLink(_ text: String) -> String? {
    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty, trimmed.utf8.count <= 4096 else { return nil }

    if let direct = normalizeAndValidateCandidate(trimmed) {
        return direct
    }

    let tokens = trimmed.components(separatedBy: .whitespacesAndNewlines)
    for token in tokens {
        let clean = token.trimmingCharacters(in: CharacterSet(charactersIn: "<>\"'()[],;"))
        let lower = clean.lowercased()
        if lower.hasPrefix("http://") || lower.hasPrefix("https://") || lower.hasPrefix("www.") || isLocalOrPrivateHost(clean) {
            if let candidate = normalizeAndValidateCandidate(clean) {
                return candidate
            }
        }
    }
    return nil
}

private func normalizeAndValidateCandidate(_ raw: String) -> String? {
    var candidate = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    while candidate.hasSuffix(".") || candidate.hasSuffix(",") || candidate.hasSuffix(";") || candidate.hasSuffix(":") {
        candidate.removeLast()
    }
    guard !candidate.isEmpty, candidate.utf8.count <= 4096 else { return nil }
    guard !candidate.unicodeScalars.contains(where: { CharacterSet.whitespacesAndNewlines.contains($0) || $0.value < 32 || $0 == "\\" }) else { return nil }

    if candidate.contains("@") {
        return nil
    }

    if candidate.lowercased().hasPrefix("www.") {
        candidate = "https://" + candidate
    }

    let lower = candidate.lowercased()
    if !lower.hasPrefix("http://") && !lower.hasPrefix("https://") {
        if isLocalOrPrivateHost(candidate) {
            candidate = "http://" + candidate
        } else {
            return nil
        }
    }

    guard let components = URLComponents(string: candidate),
          let scheme = components.scheme?.lowercased(),
          ["http", "https"].contains(scheme),
          let host = components.host, !host.isEmpty,
          components.user == nil, components.password == nil,
          components.port == nil || (1...65535).contains(components.port!) else {
        return nil
    }

    if !isValidDestinationHost(host) {
        return nil
    }

    return candidate
}

private func isLocalOrPrivateHost(_ candidate: String) -> Bool {
    var hostPart = candidate
    if let slashIdx = hostPart.firstIndex(of: "/") {
        hostPart = String(hostPart[..<slashIdx])
    }
    if let colonIdx = hostPart.firstIndex(of: ":") {
        hostPart = String(hostPart[..<colonIdx])
    }
    let lower = hostPart.lowercased()
    if lower == "localhost" || lower.hasSuffix(".local") {
        return true
    }
    return isIPv4Address(lower)
}

private func isIPv4Address(_ host: String) -> Bool {
    let parts = host.split(separator: ".", omittingEmptySubsequences: false)
    guard parts.count == 4 else { return false }
    for part in parts {
        guard let num = Int(part), (0...255).contains(num) else { return false }
    }
    return true
}

private func isValidDestinationHost(_ host: String) -> Bool {
    let lower = host.lowercased()
    if lower == "localhost" || lower.hasSuffix(".local") { return true }
    if isIPv4Address(lower) { return true }
    if lower.hasPrefix("[") && lower.hasSuffix("]") { return true }
    if lower.contains(":") { return true }

    let parts = lower.split(separator: ".")
    guard parts.count >= 2 else { return false }
    guard let tld = parts.last, tld.count >= 2, tld.allSatisfy({ $0.isLetter }) else { return false }
    for part in parts {
        guard !part.isEmpty, part.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" }) else { return false }
    }
    return true
}

func validatedWebLink(_ value: String) -> String? {
    let text = value.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !text.isEmpty, text.utf8.count <= 4096,
          !text.unicodeScalars.contains(where: { CharacterSet.whitespacesAndNewlines.contains($0) || $0.value < 32 || $0 == "\\" }) else { return nil }
    return normalizeAndValidateCandidate(text)
}

@MainActor
final class QuickActions: ObservableObject {
    @Published private(set) var receivedLink: String?
    @Published private(set) var status: String?
    @Published private(set) var recentLinks: [String] = []
    var available: (SYNQFeature) -> Bool = { _ in false }
    var send: (String, [String: Any]) -> Bool = { _, _ in false }
    var pending: String?
    private var timeout: Task<Void, Never>?
    private var sequence = 0
    private var lastNotifiedLink: String?
    private var lastNotifiedTime: TimeInterval = 0

    func sendClipboardLink() {
        guard let link = NSPasteboard.general.string(forType: .string).flatMap(validatedWebLink) else {
            status = "Copy a valid web or local network link first"; return
        }
        guard available(.links) else { status = "Web links are unavailable on one of your devices"; return }
        guard pending == nil else { return }
        let id = UUID().uuidString.lowercased()
        pending = id
        sequence += 1
        status = "Sending link…"
        if !send("quick.request", ["version": 1, "requestId": id, "feature": "links", "action": "offer", "value": link, "sequence": sequence]) {
            pending = nil; status = "Not connected — link not sent"; return
        }
        timeout?.cancel()
        timeout = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 8_000_000_000)
            guard !Task.isCancelled, self?.pending == id else { return }
            self?.pending = nil
            self?.status = "Android did not confirm delivery"
        }
    }

    func handleReceivedClipboardLink(_ link: String) {
        let now = ProcessInfo.processInfo.systemUptime
        if lastNotifiedLink == link && (now - lastNotifiedTime) < 60 {
            return
        }
        lastNotifiedLink = link
        lastNotifiedTime = now
        receivedLink = link
        if !recentLinks.contains(link) {
            recentLinks.insert(link, at: 0)
            if recentLinks.count > 10 { recentLinks.removeLast() }
        }
        status = "Link detected in clipboard"
        postLinkNotification(link, title: "Link from Android")
    }

    func receive(_ kind: String, payload: [String: Any]) {
        guard payload["version"] as? Int == 1,
              let id = payload["requestId"] as? String, UUID(uuidString: id) != nil else { return }
        if kind == "quick.result" {
            guard id == pending, payload["feature"] as? String == "links" else { return }
            pending = nil; timeout?.cancel()
            status = payload["accepted"] as? Bool == true
                ? "Link sent to Android"
                : "Link declined — check settings or dismiss the previous link"
        } else if kind == "quick.event" {
            if payload["feature"] as? String == "links", payload["event"] as? String == "opened" {
                status = "Link opened on Android"
            }
        } else if kind == "quick.request" {
            let link = (payload["value"] as? String).flatMap(validatedWebLink)
            let accepted = available(.links) && payload["feature"] as? String == "links" &&
                payload["action"] as? String == "offer" && link != nil && receivedLink == nil
            if accepted, let link {
                receivedLink = link
                if !recentLinks.contains(link) {
                    recentLinks.insert(link, at: 0)
                    if recentLinks.count > 10 { recentLinks.removeLast() }
                }
                status = "Link received — open it below"
                NSSound(named: NSSound.Name("Glass"))?.play()
                postLinkNotification(link)
            }
            _ = send("quick.result", ["version": 1, "requestId": id, "feature": "links", "accepted": accepted])
        }
    }

    func openLink() {
        guard available(.links), let link = receivedLink.flatMap(validatedWebLink), let url = URL(string: link) else { return }
        if NSWorkspace.shared.open(url) {
            receivedLink = nil
            _ = send("quick.event", [
                "version": 1,
                "requestId": UUID().uuidString.lowercased(),
                "feature": "links",
                "event": "opened"
            ])
        } else {
            status = "Could not open your browser"
        }
    }

    func openRecentLink(_ link: String) {
        guard available(.links), let valid = validatedWebLink(link), let url = URL(string: valid) else { return }
        _ = NSWorkspace.shared.open(url)
    }

    func dismissLink() { receivedLink = nil }
    func clearRecentLinks() { recentLinks.removeAll() }
    func reset() { timeout?.cancel(); pending = nil; receivedLink = nil; status = nil }

    func postLinkNotification(_ link: String, title: String = "Webpage from Android") {
        let center = UNUserNotificationCenter.current()
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = link
        content.categoryIdentifier = "synq.link.notification"
        content.userInfo = ["url": link]
        let request = UNNotificationRequest(identifier: "synq.link.\(link.hashValue)", content: content, trigger: nil)
        center.add(request)
    }
}

import AppKit
import Foundation

func phoneNumberFromTelURL(_ value: String) -> String? {
    guard value.lowercased().hasPrefix("tel:") else { return nil }
    var number = String(value.dropFirst(4))
    if number.hasPrefix("//") { number.removeFirst(2) }
    guard let decoded = number.removingPercentEncoding else { return nil }
    return normalizedPhoneNumber(decoded)
}

final class PendingPhoneCallRouter {
    private var onCall: ((String) -> Void)?
    private var pendingNumbers: [String] = []

    func configure(onCall: @escaping (String) -> Void) {
        self.onCall = onCall
        let queued = pendingNumbers
        pendingNumbers.removeAll()
        queued.forEach(onCall)
    }

    func receive(_ value: String) {
        guard let number = phoneNumberFromTelURL(value) else { return }
        if let onCall {
            onCall(number)
        } else if pendingNumbers.count < 8 {
            pendingNumbers.append(number)
        }
    }
}

@MainActor
final class PhoneURLHandler: NSObject, NSApplicationDelegate {
    private let router = PendingPhoneCallRouter()
    private var onOpenFiles: (([URL]) -> Void)?
    private var pendingFileURLs: [URL] = []

    func configure(onCall: @escaping (String) -> Void) {
        router.configure(onCall: onCall)
    }

    func configureFiles(onOpenFiles: @escaping ([URL]) -> Void) {
        self.onOpenFiles = onOpenFiles
        let queued = pendingFileURLs
        pendingFileURLs.removeAll()
        if !queued.isEmpty {
            onOpenFiles(queued)
        }
    }

    func application(_: NSApplication, open urls: [URL]) {
        var fileURLs: [URL] = []
        for url in urls {
            if url.isFileURL {
                fileURLs.append(url)
            } else if url.scheme?.lowercased() == "tel" {
                router.receive(url.absoluteString)
            }
        }
        if !fileURLs.isEmpty {
            if let onOpenFiles {
                onOpenFiles(fileURLs)
            } else {
                pendingFileURLs.append(contentsOf: fileURLs)
            }
        }
    }

    func application(_ app: NSApplication, openFiles filenames: [String]) {
        let urls = filenames.map { URL(fileURLWithPath: $0) }
        guard !urls.isEmpty else { return }
        if let onOpenFiles {
            onOpenFiles(urls)
        } else {
            pendingFileURLs.append(contentsOf: urls)
        }
    }
}

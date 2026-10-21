import AppKit
import Foundation

func fileURLs(from pasteboard: NSPasteboard) -> [URL] {
    // 1. Read NSURL objects from pasteboard (standard macOS file URLs)
    if let urls = pasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL], !urls.isEmpty {
        return urls
    }
    // 2. Read legacy NSFilenamesPboardType
    let filenamesType = NSPasteboard.PasteboardType("NSFilenamesPboardType")
    if let filenames = pasteboard.propertyList(forType: filenamesType) as? [String], !filenames.isEmpty {
        return filenames.map { URL(fileURLWithPath: $0) }
    }
    // 3. Read string URL if it has a file scheme
    if let str = pasteboard.string(forType: .fileURL), let url = URL(string: str), url.isFileURL {
        return [url]
    }
    return []
}

func filterRegularFiles(_ urls: [URL]) -> (valid: [URL], directories: [URL], unreadable: [URL]) {
    var valid: [URL] = []
    var directories: [URL] = []
    var unreadable: [URL] = []

    for url in urls {
        guard url.isFileURL else {
            unreadable.append(url)
            continue
        }
        let didAccess = url.startAccessingSecurityScopedResource()
        defer { if didAccess { url.stopAccessingSecurityScopedResource() } }

        var isDir: ObjCBool = false
        if FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir) {
            if isDir.boolValue {
                directories.append(url)
            } else {
                valid.append(url)
            }
        } else {
            unreadable.append(url)
        }
    }

    return (valid: valid, directories: directories, unreadable: unreadable)
}

@MainActor
final class FileShareServiceProvider: NSObject {
    private let onFilesShared: ([URL]) -> Void

    init(onFilesShared: @escaping ([URL]) -> Void) {
        self.onFilesShared = onFilesShared
    }

    @objc func sendFilesWithSYNQ(
        _ pasteboard: NSPasteboard,
        userData: String?,
        error: AutoreleasingUnsafeMutablePointer<NSString?>
    ) {
        let urls = fileURLs(from: pasteboard)
        guard !urls.isEmpty else {
            error.pointee = "No valid files were found to send."
            return
        }
        onFilesShared(urls)
    }

}

@MainActor
final class AppServicesProvider: NSObject {
    private let callProvider: CallServiceProvider
    private let fileShareProvider: FileShareServiceProvider

    init(
        onCall: @escaping (String) -> Void,
        onShareFiles: @escaping ([URL]) -> Void
    ) {
        self.callProvider = CallServiceProvider(onCall: onCall)
        self.fileShareProvider = FileShareServiceProvider(onFilesShared: onShareFiles)
    }

    @objc func callWithSYNQ(
        _ pasteboard: NSPasteboard,
        userData: String?,
        error: AutoreleasingUnsafeMutablePointer<NSString?>
    ) {
        callProvider.callWithSYNQ(pasteboard, userData: userData, error: error)
    }

    @objc func sendFilesWithSYNQ(
        _ pasteboard: NSPasteboard,
        userData: String?,
        error: AutoreleasingUnsafeMutablePointer<NSString?>
    ) {
        fileShareProvider.sendFilesWithSYNQ(pasteboard, userData: userData, error: error)
    }
}

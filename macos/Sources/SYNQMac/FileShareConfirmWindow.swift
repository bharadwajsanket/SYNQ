import AppKit
import SwiftUI
import QuickLookThumbnailing
import PDFKit

enum FileTypeCategory: String {
    case image
    case video
    case pdf
    case audio
    case apk
    case archive
    case document
    case other
}

func categoryForURL(_ url: URL) -> FileTypeCategory {
    switch url.pathExtension.lowercased() {
    case "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "tiff", "bmp", "svg":
        return .image
    case "mp4", "mov", "m4v", "mkv", "webm", "avi":
        return .video
    case "pdf":
        return .pdf
    case "mp3", "m4a", "aac", "wav", "flac", "ogg":
        return .audio
    case "apk":
        return .apk
    case "zip", "tar", "gz", "tgz", "bz2", "7z", "rar":
        return .archive
    case "txt", "md", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf", "csv", "json":
        return .document
    default:
        return .other
    }
}

struct SharedFileItem: Identifiable {
    let id = UUID()
    let url: URL
    let name: String
    let isDirectory: Bool
    let size: Int64
    let formattedSize: String
    let icon: NSImage
    let category: FileTypeCategory

    var categoryLabel: String {
        switch category {
        case .image: return "Image"
        case .video: return "Video"
        case .pdf: return "PDF"
        case .audio: return "Audio"
        case .apk: return "Android App"
        case .archive: return "Archive"
        case .document: return "Document"
        case .other: return isDirectory ? "Folder" : "File"
        }
    }

    var categoryColor: Color {
        switch category {
        case .image: return .teal
        case .video: return .indigo
        case .pdf: return .red
        case .audio: return .purple
        case .apk: return .green
        case .archive: return .orange
        case .document: return .blue
        case .other: return .secondary
        }
    }
}

func generateThumbnail(for url: URL, category: FileTypeCategory, size: CGSize = CGSize(width: 48, height: 48), completion: @escaping (NSImage?) -> Void) {
    if category == .pdf {
        DispatchQueue.global(qos: .userInitiated).async {
            if let doc = PDFDocument(url: url), let page = doc.page(at: 0) {
                let img = page.thumbnail(of: size, for: .mediaBox)
                DispatchQueue.main.async { completion(img) }
                return
            }
            DispatchQueue.main.async { completion(nil) }
        }
        return
    }

    let scale = NSScreen.main?.backingScaleFactor ?? 2.0
    let request = QLThumbnailGenerator.Request(
        fileAt: url,
        size: size,
        scale: scale,
        representationTypes: .thumbnail
    )
    QLThumbnailGenerator.shared.generateBestRepresentation(for: request) { thumbnail, error in
        DispatchQueue.main.async {
            if let nsImage = thumbnail?.nsImage {
                completion(nsImage)
            } else {
                completion(nil)
            }
        }
    }
}

func inspectSharedFiles(_ urls: [URL]) -> [SharedFileItem] {
    urls.map { url in
        let didAccess = url.startAccessingSecurityScopedResource()
        defer { if didAccess { url.stopAccessingSecurityScopedResource() } }

        let name = url.lastPathComponent
        var isDir: ObjCBool = false
        let exists = FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir)
        let isDirectory = exists && isDir.boolValue
        let size = Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        let icon = NSWorkspace.shared.icon(forFile: url.path)
        icon.size = NSSize(width: 28, height: 28)
        let formattedSize = isDirectory
            ? "Folder — skipped"
            : ByteCountFormatter.string(fromByteCount: size, countStyle: .file)
        let category = isDirectory ? .other : categoryForURL(url)

        return SharedFileItem(
            url: url,
            name: name,
            isDirectory: isDirectory,
            size: size,
            formattedSize: formattedSize,
            icon: icon,
            category: category
        )
    }
}

@MainActor
final class FileShareConfirmWindowController: NSWindowController {
    private var hostingController: NSHostingController<FileShareConfirmView>?
    private weak var pairing: PairingCoordinator?
    private var onOpenSettings: (() -> Void)?

    init(pairing: PairingCoordinator, onOpenSettings: (() -> Void)? = nil) {
        self.pairing = pairing
        self.onOpenSettings = onOpenSettings

        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 460, height: 380),
            styleMask: [.titled, .closable, .miniaturizable],
            backing: .buffered,
            defer: false
        )
        window.title = "Send to Android — SYNQ"
        window.minSize = NSSize(width: 420, height: 320)
        window.isReleasedWhenClosed = false
        window.hidesOnDeactivate = false
        window.level = .floating
        window.collectionBehavior.insert(.canJoinAllSpaces)
        window.center()
        super.init(window: window)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func present(with urls: [URL], onOpenSettings: (() -> Void)? = nil) {
        guard let pairing else { return }
        if let onOpenSettings {
            self.onOpenSettings = onOpenSettings
        }
        let items = inspectSharedFiles(urls)
        let rootView = FileShareConfirmView(
            items: items,
            pairing: pairing,
            onSend: { [weak self, weak pairing] validURLs in
                self?.window?.close()
                pairing?.sendFiles(validURLs)
                pairing?.showFileTransferWindow()
            },
            onCancel: { [weak self] in
                self?.window?.close()
            },
            onOpenSettings: self.onOpenSettings
        )
        if hostingController == nil {
            hostingController = NSHostingController(rootView: rootView)
            window?.contentViewController = hostingController
        } else {
            hostingController?.rootView = rootView
        }

        NSApp.activate(ignoringOtherApps: true)
        showWindow(nil)
        window?.makeKeyAndOrderFront(nil)
    }
}

private struct FileShareConfirmView: View {
    let items: [SharedFileItem]
    @ObservedObject var pairing: PairingCoordinator
    let onSend: ([URL]) -> Void
    let onCancel: () -> Void
    let onOpenSettings: (() -> Void)?

    @State private var selectedDeviceID: String?

    private var validURLs: [URL] {
        items.filter { !$0.isDirectory }.map(\.url)
    }

    private var totalBytes: Int64 {
        items.filter { !$0.isDirectory }.reduce(0) { $0 + $1.size }
    }

    private var totalFormattedSize: String {
        ByteCountFormatter.string(fromByteCount: totalBytes, countStyle: .file)
    }

    private var connectedDevice: (id: String, name: String)? {
        if case let .connected(id, name) = pairing.state {
            return (id: id, name: name)
        }
        return nil
    }

    private var isConnectedToSelected: Bool {
        guard let connected = connectedDevice else { return false }
        if let selected = selectedDeviceID {
            return selected == connected.id
        }
        return true
    }

    private var canSend: Bool {
        !validURLs.isEmpty && isConnectedToSelected && pairing.isFeatureAvailable(.files)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            // Header
            HStack(spacing: 12) {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 32))
                    .foregroundStyle(Color.accentColor)
                VStack(alignment: .leading, spacing: 2) {
                    Text(validURLs.count == 1
                         ? "Send 1 File to Android"
                         : "Send \(validURLs.count) Files to Android")
                        .font(.headline)
                    Text("\(totalFormattedSize) total")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }

            // File items list
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 6) {
                    ForEach(items) { item in
                        SharedFileRowView(item: item)
                    }
                }
                .padding(2)
            }
            .frame(maxHeight: 140)
            .padding(6)
            .background(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(Color(nsColor: .controlBackgroundColor))
            )

            // Destination Device Card
            destinationCard

            Spacer(minLength: 0)

            // Actions
            HStack {
                Button("Cancel", action: onCancel)
                    .keyboardShortcut(.cancelAction)

                Spacer()

                Button {
                    onSend(validURLs)
                } label: {
                    let targetName = connectedDevice?.name ?? "Android"
                    Text("Send to \(targetName)")
                        .fontWeight(.semibold)
                }
                .buttonStyle(.borderedProminent)
                .disabled(!canSend)
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(minWidth: 420, minHeight: 320)
        .onAppear {
            if let connected = connectedDevice {
                selectedDeviceID = connected.id
            } else if let first = pairing.trustedDevices.first {
                selectedDeviceID = first.id
            }
        }
        .onChange(of: connectedDevice?.id) { newID in
            if let newID {
                selectedDeviceID = newID
            }
        }
    }

    @ViewBuilder
    private var destinationCard: some View {
        if pairing.trustedDevices.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                    Text("No Paired Devices")
                        .font(.subheadline.weight(.semibold))
                }
                Text("You must pair an Android phone running SYNQ before sending files.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if let onOpenSettings {
                    Button("Open Settings…", action: onOpenSettings)
                        .controlSize(.small)
                        .padding(.top, 2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(Color.orange.opacity(0.1))
            )
        } else if let connected = connectedDevice {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("Destination Device")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    Spacer()
                    HStack(spacing: 4) {
                        Circle()
                            .fill(Color.green)
                            .frame(width: 6, height: 6)
                        Text("Connected · Ready to send")
                            .font(.caption2.weight(.medium))
                            .foregroundStyle(.green)
                    }
                }

                if pairing.trustedDevices.count > 1 {
                    Picker("Device", selection: Binding(
                        get: { selectedDeviceID ?? connected.id },
                        set: { selectedDeviceID = $0 }
                    )) {
                        ForEach(pairing.trustedDevices) { device in
                            let isCurrent = device.id == connected.id
                            Text(isCurrent ? "\(device.name) (Connected)" : "\(device.name) (Not connected)")
                                .tag(device.id)
                        }
                    }
                    .pickerStyle(.menu)

                    if !isConnectedToSelected {
                        Text("Selected device is not currently connected. Connect via the SYNQ menu to send files.")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                } else {
                    HStack(spacing: 10) {
                        Image(systemName: "smartphone")
                            .font(.system(size: 20))
                            .foregroundStyle(Color.accentColor)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(connected.name)
                                .font(.system(size: 13, weight: .medium))
                            Text("Local network transfer")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(Color(nsColor: .controlBackgroundColor))
            )
        } else {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    Image(systemName: "wifi.slash")
                        .foregroundStyle(.secondary)
                    Text("No Android Device Connected")
                        .font(.subheadline.weight(.semibold))
                }
                Text("Ensure SYNQ is open on your Android phone and on the same Wi-Fi network.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                HStack {
                    Button("Retry Connection") {
                        pairing.reconnectNow()
                    }
                    .controlSize(.small)
                }
                .padding(.top, 2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(Color.secondary.opacity(0.1))
            )
        }
    }
}

private struct SharedFileRowView: View {
    let item: SharedFileItem
    @State private var thumbnail: NSImage? = nil

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(Color.primary.opacity(0.06))
                    .frame(width: 36, height: 36)

                if let thumb = thumbnail {
                    Image(nsImage: thumb)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(maxWidth: 34, maxHeight: 34)
                        .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
                } else {
                    categoryIcon
                }
            }
            .frame(width: 36, height: 36)

            VStack(alignment: .leading, spacing: 2) {
                Text(item.name)
                    .font(.system(size: 13, weight: .medium))
                    .lineLimit(1)
                    .truncationMode(.middle)
                HStack(spacing: 6) {
                    Text(item.categoryLabel)
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(item.categoryColor)
                        .padding(.horizontal, 4)
                        .padding(.vertical, 1)
                        .background(item.categoryColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 4, style: .continuous))
                    Text(item.formattedSize)
                        .font(.caption)
                        .foregroundStyle(item.isDirectory ? Color.orange : Color.secondary)
                }
            }
            Spacer()
        }
        .padding(.vertical, 4)
        .padding(.horizontal, 8)
        .background(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(Color.primary.opacity(0.03))
        )
        .onAppear {
            if item.category == .image || item.category == .video || item.category == .pdf {
                generateThumbnail(for: item.url, category: item.category) { img in
                    self.thumbnail = img
                }
            }
        }
    }

    @ViewBuilder
    private var categoryIcon: some View {
        switch item.category {
        case .image:
            Image(systemName: "photo")
                .foregroundStyle(Color.teal)
        case .video:
            Image(systemName: "film")
                .foregroundStyle(Color.indigo)
        case .pdf:
            Image(systemName: "doc.richtext")
                .foregroundStyle(Color.red)
        case .audio:
            Image(systemName: "music.note")
                .foregroundStyle(Color.purple)
        case .apk:
            Image(systemName: "shippingbox.fill")
                .foregroundStyle(Color.green)
        case .archive:
            Image(systemName: "doc.zipper")
                .foregroundStyle(Color.orange)
        case .document:
            Image(systemName: "doc.text.fill")
                .foregroundStyle(Color.blue)
        case .other:
            Image(nsImage: item.icon)
                .resizable()
                .frame(width: 24, height: 24)
        }
    }
}

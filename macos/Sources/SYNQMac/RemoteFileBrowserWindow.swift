import AppKit
import SwiftUI

@MainActor
final class RemoteFileBrowserWindowController: NSWindowController, NSWindowDelegate {
    private let pairing: PairingCoordinator

    init(pairing: PairingCoordinator) {
        self.pairing = pairing
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 680, height: 460),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.title = "Android Files"
        window.minSize = NSSize(width: 520, height: 340)
        window.isReleasedWhenClosed = false
        window.setFrameAutosaveName("SYNQRemoteFileBrowserWindow")
        window.center()

        super.init(window: window)
        window.delegate = self
        window.contentView = NSHostingView(rootView: RemoteFileBrowserView(pairing: pairing))
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func show() {
        NSApp.activate(ignoringOtherApps: true)
        showWindow(nil)
        window?.makeKeyAndOrderFront(nil)
    }
}

struct RemoteFileBrowserView: View {
    @ObservedObject var pairing: PairingCoordinator
    @State private var currentPath: String = ""
    @State private var pathHistory: [String] = []
    @State private var searchQuery: String = ""
    @State private var selectedEntryID: String?

    private var filteredEntries: [RemoteFsEntry] {
        let entries = pairing.remoteFileEntries
        if searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return entries.sorted { lhs, rhs in
                if lhs.isDirectory != rhs.isDirectory {
                    return lhs.isDirectory && !rhs.isDirectory
                }
                return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
            }
        }
        let query = searchQuery.lowercased()
        return entries.filter { $0.name.lowercased().contains(query) }
            .sorted { lhs, rhs in
                if lhs.isDirectory != rhs.isDirectory {
                    return lhs.isDirectory && !rhs.isDirectory
                }
                return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
            }
    }

    var body: some View {
        VStack(spacing: 0) {
            // Top navigation toolbar
            HStack(spacing: 10) {
                Button {
                    navigateUp()
                } label: {
                    Image(systemName: "chevron.backward")
                        .font(.system(size: 12, weight: .semibold))
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .disabled(currentPath.isEmpty)
                .help("Go up one folder")

                // Breadcrumb path display
                HStack(spacing: 4) {
                    Image(systemName: "folder")
                        .foregroundStyle(Color.accentColor)
                    Text(breadcrumbTitle)
                        .font(.system(size: 12, weight: .medium))
                        .lineLimit(1)
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: 6))

                Spacer()

                // Search field
                HStack(spacing: 6) {
                    Image(systemName: "magnifyingglass")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    TextField("Search folder…", text: $searchQuery)
                        .font(.caption)
                        .textFieldStyle(.plain)
                        .frame(width: 140)
                    if !searchQuery.isEmpty {
                        Button {
                            searchQuery = ""
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 6)
                .padding(.vertical, 4)
                .background(Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: 6))

                Button {
                    pairing.browseRemoteFiles(path: currentPath)
                } label: {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 12))
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .help("Refresh folder")
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(Color.primary.opacity(0.02))

            Divider()

            // Main content area
            if pairing.isBrowsingFiles && pairing.remoteFileEntries.isEmpty {
                VStack(spacing: 12) {
                    ProgressView()
                    Text("Loading files from Android…")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let error = pairing.remoteFileBrowserError {
                VStack(spacing: 12) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 32))
                        .foregroundStyle(.orange)
                    Text("Cannot Browse Android Files")
                        .font(.headline)
                    Text(error)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 40)
                    Button("Try Again") {
                        pairing.browseRemoteFiles(path: currentPath)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if filteredEntries.isEmpty {
                VStack(spacing: 10) {
                    Image(systemName: "folder")
                        .font(.system(size: 36))
                        .foregroundStyle(.tertiary)
                    Text(searchQuery.isEmpty ? "This folder is empty" : "No files matching \"\(searchQuery)\"")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(selection: $selectedEntryID) {
                    ForEach(filteredEntries) { entry in
                        HStack(spacing: 10) {
                            Image(systemName: iconForEntry(entry))
                                .font(.system(size: 15))
                                .foregroundStyle(entry.isDirectory ? Color.accentColor : Color.secondary)
                                .frame(width: 20)

                            Text(entry.name)
                                .font(.system(size: 12, weight: entry.isDirectory ? .medium : .regular))
                                .lineLimit(1)

                            Spacer()

                            Text(formatDate(entry.lastModified))
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .frame(width: 130, alignment: .trailing)

                            Text(entry.isDirectory ? "—" : formatBytes(entry.size))
                                .font(.caption2.monospacedDigit())
                                .foregroundStyle(.secondary)
                                .frame(width: 75, alignment: .trailing)

                            if !entry.isDirectory {
                                Button {
                                    pairing.pullRemoteFile(path: entry.path)
                                } label: {
                                    HStack(spacing: 4) {
                                        Image(systemName: "arrow.down.circle")
                                        Text("Download")
                                    }
                                    .font(.caption2)
                                }
                                .buttonStyle(.bordered)
                                .controlSize(.small)
                                .help("Download \(entry.name) to Mac")
                            } else {
                                Button {
                                    navigateToFolder(entry.path)
                                } label: {
                                    Image(systemName: "chevron.forward")
                                        .font(.caption2)
                                }
                                .buttonStyle(.plain)
                                .foregroundStyle(.secondary)
                                .padding(.horizontal, 14)
                            }
                        }
                        .contentShape(Rectangle())
                        .onTapGesture(count: 2) {
                            if entry.isDirectory {
                                navigateToFolder(entry.path)
                            } else {
                                pairing.pullRemoteFile(path: entry.path)
                            }
                        }
                    }
                }
                .listStyle(.inset(alternatesRowBackgrounds: true))
            }

            Divider()

            // Bottom status bar
            HStack {
                Text("\(filteredEntries.count) items")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Spacer()
                Text("Files are transferred locally over your secure SYNQ connection")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(Color.primary.opacity(0.02))
        }
        .frame(minWidth: 540, minHeight: 360)
        .onAppear {
            if pairing.remoteFileEntries.isEmpty {
                pairing.browseRemoteFiles(path: currentPath)
            }
        }
    }

    private var breadcrumbTitle: String {
        if currentPath.isEmpty {
            return "Downloads"
        }
        return "Downloads / " + currentPath.replacingOccurrences(of: "/", with: " / ")
    }

    private func navigateToFolder(_ path: String) {
        pathHistory.append(currentPath)
        currentPath = path
        searchQuery = ""
        pairing.browseRemoteFiles(path: path)
    }

    private func navigateUp() {
        if let previous = pathHistory.popLast() {
            currentPath = previous
            searchQuery = ""
            pairing.browseRemoteFiles(path: previous)
        } else if !currentPath.isEmpty {
            let parent = currentPath.components(separatedBy: "/").dropLast().joined(separator: "/")
            currentPath = parent
            searchQuery = ""
            pairing.browseRemoteFiles(path: parent)
        }
    }

    private func iconForEntry(_ entry: RemoteFsEntry) -> String {
        if entry.isDirectory { return "folder.fill" }
        let ext = URL(fileURLWithPath: entry.name).pathExtension.lowercased()
        switch ext {
        case "jpg", "jpeg", "png", "gif", "webp", "heic", "svg": return "photo"
        case "mp4", "mov", "mkv", "avi", "webm": return "film"
        case "mp3", "m4a", "wav", "flac", "aac", "ogg": return "music.note"
        case "pdf": return "doc.richtext"
        case "zip", "tar", "gz", "7z", "rar": return "archivebox"
        case "txt", "md", "csv", "json", "xml": return "doc.text"
        default: return "doc"
        }
    }

    private func formatBytes(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        formatter.allowedUnits = [.useBytes, .useKB, .useMB, .useGB]
        return formatter.string(fromByteCount: bytes)
    }

    private func formatDate(_ millis: Int64) -> String {
        guard millis > 0 else { return "—" }
        let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1000.0)
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}

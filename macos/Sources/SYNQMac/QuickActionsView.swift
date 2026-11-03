import SwiftUI

struct QuickActionsPanel: View {
    @ObservedObject var actions: QuickActions
    var body: some View {
        if let link = actions.receivedLink {
            VStack(alignment: .leading, spacing: 8) {
                Text("Link from Android").font(.headline)
                Text(link).font(.caption).lineLimit(3).textSelection(.enabled)
                HStack {
                    Button("Open in browser", action: actions.openLink)
                    Button("Dismiss", action: actions.dismissLink)
                }
            }
        }
        if let status = actions.status { Text(status).font(.caption).foregroundStyle(.secondary) }
    }
}

struct MediaSettingsView: View {
    @ObservedObject var media: MediaController
    @ObservedObject var settings: SYNQSettings

    var body: some View {
        Section("Media") {
            Toggle("Allow connected devices to control media", isOn: Binding(
                get: { settings.globalFeatures[.media] != false },
                set: { enabled in
                    settings.setGlobal(.media, enabled: enabled)
                    if !enabled {
                        media.reset()
                    } else {
                        media.refresh()
                    }
                }
            ))
            Text("Let paired devices view and control media playing on this Mac.")
                .font(.caption).foregroundStyle(.secondary)

            if settings.globalFeatures[.media] != false {
                Picker("Player", selection: $media.player) {
                    ForEach(MediaPlayer.allCases) { Text($0.title).tag($0) }
                }
                Toggle("Pause media during phone calls", isOn: $media.pauseForCalls)
                Text("Pauses the selected player when a call rings or becomes active. Playback stays paused after the call; resume it when ready.")
                    .font(.caption).foregroundStyle(.secondary)
                if !media.snapshot.detail.isEmpty { Text(media.snapshot.detail).font(.caption).foregroundStyle(.secondary) }
                Button("Refresh player", action: media.refresh)
            }
        }
    }
}

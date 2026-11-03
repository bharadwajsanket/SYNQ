import SwiftUI
import AppKit
import CryptoKit

func syntheticCallActionToken(_ notificationID: String, action: String) -> String {
    let input = Data("\(notificationID)\u{0}\(action)".utf8)
    return SHA256.hash(data: input).map { String(format: "%02x", $0) }.joined()
}

struct AppleCallControlsView: View {
    let call: RemoteCallStatus
    @ObservedObject var pairing: PairingCoordinator

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            ForEach(resolvedAppleCallActions(for: call)) { action in
                AppleCallActionButton(action: action, pairing: pairing)
                    .frame(maxWidth: .infinity)
            }
        }
        .padding(.top, 4)
    }
}

struct AppleCallActionButton: View {
    let action: RemoteCallAction
    @ObservedObject var pairing: PairingCoordinator

    private var isMute: Bool {
        action.title.localizedCaseInsensitiveContains("mute")
    }

    private var isSpeaker: Bool {
        action.title.localizedCaseInsensitiveContains("speaker")
    }

    private var isEndOrDecline: Bool {
        isCallEndingActionTitle(action.title)
    }

    private var isAnswer: Bool {
        action.title.localizedCaseInsensitiveContains("answer") ||
        action.title.localizedCaseInsensitiveContains("accept")
    }

    private var isActiveToggle: Bool {
        if isMute { return pairing.isMuteActive }
        if isSpeaker { return pairing.isSpeakerActive }
        return false
    }

    private var iconName: String {
        if isMute {
            return pairing.isMuteActive ? "mic.slash.fill" : "mic.fill"
        }
        if isSpeaker {
            return pairing.isSpeakerActive ? "speaker.wave.3.fill" : "speaker.wave.2.fill"
        }
        if isEndOrDecline {
            return "phone.down.fill"
        }
        if isAnswer {
            return "phone.fill"
        }
        return "ellipsis"
    }

    private var circleBackground: Color {
        if isAnswer {
            return Color(red: 0.22, green: 0.80, blue: 0.38)
        }
        if isEndOrDecline {
            return Color(red: 0.95, green: 0.26, blue: 0.24)
        }
        if isActiveToggle {
            return Color.white
        }
        return Color.white.opacity(0.14)
    }

    private var iconForeground: Color {
        if isAnswer || isEndOrDecline {
            return Color.white
        }
        if isActiveToggle {
            return Color.black
        }
        return Color.white
    }

    private var captionTitle: String {
        if isMute {
            return pairing.isMuteActive ? "Muted" : "Mute"
        }
        if isSpeaker {
            return "Speaker"
        }
        if action.title.localizedCaseInsensitiveContains("hang") ||
           action.title.localizedCaseInsensitiveContains("end") {
            return "End"
        }
        if action.title.localizedCaseInsensitiveContains("decline") ||
           action.title.localizedCaseInsensitiveContains("reject") {
            return "Decline"
        }
        if isAnswer {
            return "Answer"
        }
        return action.title
    }

    private var tooltipText: String {
        if isMute {
            return pairing.isMuteActive ? "Unmute microphone" : "Mute microphone"
        }
        if isSpeaker {
            return pairing.isSpeakerActive ? "Switch to normal handset audio" : "Switch to speakerphone"
        }
        if isEndOrDecline {
            return "End call"
        }
        if isAnswer {
            return "Answer call"
        }
        return action.title
    }

    var body: some View {
        Button {
            pairing.performRemoteCallAction(action)
        } label: {
            VStack(spacing: 6) {
                ZStack {
                    Circle()
                        .fill(circleBackground)
                        .frame(width: 44, height: 44)

                    Image(systemName: iconName)
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundStyle(iconForeground)
                }

                Text(captionTitle)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(isActiveToggle ? Color.white : Color.white.opacity(0.75))
                    .lineLimit(1)
            }
        }
        .buttonStyle(AppleCallButtonStyle())
        .disabled(pairing.inFlightCallActionToken != nil)
        .opacity(pairing.inFlightCallActionToken == action.id ? 0.7 : 1.0)
        .help(tooltipText)
    }
}

struct AppleCallButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.92 : 1.0)
            .opacity(configuration.isPressed ? 0.82 : 1.0)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

func resolvedAppleCallActions(for call: RemoteCallStatus) -> [RemoteCallAction] {
    if call.type == "ongoing" {
        let actions = call.actions
        let muteAction = actions.first(where: { $0.title.localizedCaseInsensitiveContains("mute") })
            ?? RemoteCallAction(id: syntheticCallActionToken(call.notificationID, action: "mute"), title: "Mute")
        let speakerAction = actions.first(where: { $0.title.localizedCaseInsensitiveContains("speaker") })
            ?? RemoteCallAction(id: syntheticCallActionToken(call.notificationID, action: "speaker"), title: "Speaker")
        let endAction = actions.first(where: { isCallEndingActionTitle($0.title) })
            ?? RemoteCallAction(id: syntheticCallActionToken(call.notificationID, action: "hangup"), title: "End")
        return [muteAction, speakerAction, endAction]
    }
    return orderedAppleCallActions(call.actions)
}

func orderedAppleCallActions(_ actions: [RemoteCallAction]) -> [RemoteCallAction] {
    actions.prefix(3).enumerated().sorted { left, right in
        let leftRank = appleCallActionRank(left.element.title)
        let rightRank = appleCallActionRank(right.element.title)
        return leftRank == rightRank ? left.offset < right.offset : leftRank < rightRank
    }.map(\.element)
}

func appleCallActionRank(_ title: String) -> Int {
    let lower = title.lowercased()
    if lower.contains("mute") { return 10 }
    if lower.contains("speaker") { return 20 }
    if lower.contains("decline") || lower.contains("reject") { return 30 }
    if lower.contains("hang") || lower.contains("end") { return 40 }
    if lower.contains("answer") || lower.contains("accept") { return 50 }
    return 100
}


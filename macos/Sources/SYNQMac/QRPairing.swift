import AppKit
import Combine
import Foundation
import Network
import SwiftUI

public enum QRPairingError: String, LocalizedError, Equatable {
    case invalidScheme = "Invalid QR code format for SYNQ"
    case unsupportedVersion = "Unsupported QR pairing protocol version"
    case invalidDeviceID = "Invalid device ID in pairing code"
    case untrustedHost = "Host address is not a private local network address"
    case invalidPort = "Invalid network port"
    case invalidToken = "Invalid pairing session token"
    case expired = "Pairing code has expired. Please refresh the QR code on your Mac."
    case malformedPayload = "Malformed QR pairing payload"

    public var errorDescription: String? { rawValue }
}

public struct QRPairingPayload: Equatable {
    public let version: Int
    public let deviceID: String
    public let deviceName: String
    public let host: String
    public let port: Int
    public let token: String
    public let expiresAt: Date

    public var isExpired: Bool {
        Date() >= expiresAt
    }

    public init(
        version: Int = 1,
        deviceID: String,
        deviceName: String,
        host: String,
        port: Int = 42458,
        token: String,
        expiresAt: Date
    ) {
        self.version = version
        self.deviceID = deviceID
        self.deviceName = deviceName
        self.host = host
        self.port = port
        self.token = token
        self.expiresAt = expiresAt
    }

    public func toURIString() -> String {
        var components = URLComponents()
        components.scheme = "synq"
        components.host = "pair"
        components.queryItems = [
            URLQueryItem(name: "v", value: String(version)),
            URLQueryItem(name: "id", value: deviceID),
            URLQueryItem(name: "name", value: deviceName),
            URLQueryItem(name: "host", value: host),
            URLQueryItem(name: "port", value: String(port)),
            URLQueryItem(name: "token", value: token),
            URLQueryItem(name: "exp", value: String(Int64(expiresAt.timeIntervalSince1970)))
        ]
        return components.url?.absoluteString ?? "synq://pair?v=\(version)&id=\(deviceID)&token=\(token)"
    }

    public static func parse(_ string: String, now: Date = Date()) throws -> QRPairingPayload {
        let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.starts(with: "{") {
            return try parseJSON(trimmed, now: now)
        } else {
            return try parseURI(trimmed, now: now)
        }
    }

    private static func parseURI(_ uriString: String, now: Date) throws -> QRPairingPayload {
        guard let url = URL(string: uriString),
              url.scheme?.lowercased() == "synq",
              url.host?.lowercased() == "pair" else {
            throw QRPairingError.invalidScheme
        }

        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let queryItems = components.queryItems else {
            throw QRPairingError.malformedPayload
        }

        func queryValue(for name: String) -> String? {
            queryItems.first(where: { $0.name == name })?.value
        }

        guard let vStr = queryValue(for: "v"), let version = Int(vStr) else {
            throw QRPairingError.unsupportedVersion
        }
        guard version == 1 else { throw QRPairingError.unsupportedVersion }

        guard let id = queryValue(for: "id"), isValidDeviceID(id) else {
            throw QRPairingError.invalidDeviceID
        }

        let name = queryValue(for: "name")?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "Mac"
        let effectiveName = name.isEmpty ? "Mac" : String(name.prefix(64))

        guard let host = queryValue(for: "host"), isPrivateNetworkHost(host) else {
            throw QRPairingError.untrustedHost
        }

        let port = queryValue(for: "port").flatMap(Int.init) ?? 42458
        guard port >= 1024 && port <= 65535 else {
            throw QRPairingError.invalidPort
        }

        guard let token = queryValue(for: "token"), isValidToken(token) else {
            throw QRPairingError.invalidToken
        }

        guard let expStr = queryValue(for: "exp"), let expVal = Double(expStr) else {
            throw QRPairingError.malformedPayload
        }

        let timestamp = expVal > 10_000_000_000 ? expVal / 1000.0 : expVal
        let expiresAt = Date(timeIntervalSince1970: timestamp)
        guard expiresAt > now else {
            throw QRPairingError.expired
        }

        return QRPairingPayload(
            version: version,
            deviceID: id.lowercased(),
            deviceName: effectiveName,
            host: host,
            port: port,
            token: token,
            expiresAt: expiresAt
        )
    }

    private static func parseJSON(_ jsonString: String, now: Date) throws -> QRPairingPayload {
        guard let data = jsonString.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw QRPairingError.malformedPayload
        }

        if let action = json["action"] as? String {
            guard action == "pair" else { throw QRPairingError.invalidScheme }
        }

        let version = (json["version"] as? Int) ?? (json["v"] as? Int) ?? 0
        guard version == 1 else { throw QRPairingError.unsupportedVersion }

        guard let id = (json["deviceId"] as? String) ?? (json["id"] as? String), isValidDeviceID(id) else {
            throw QRPairingError.invalidDeviceID
        }

        let name = ((json["deviceName"] as? String) ?? (json["name"] as? String) ?? "Mac").trimmingCharacters(in: .whitespacesAndNewlines)
        let effectiveName = name.isEmpty ? "Mac" : String(name.prefix(64))

        guard let host = json["host"] as? String, isPrivateNetworkHost(host) else {
            throw QRPairingError.untrustedHost
        }

        let port = (json["port"] as? Int) ?? 42458
        guard port >= 1024 && port <= 65535 else {
            throw QRPairingError.invalidPort
        }

        guard let token = json["token"] as? String, isValidToken(token) else {
            throw QRPairingError.invalidToken
        }

        let expTimestamp: TimeInterval
        if let expNum = json["expiresAt"] as? NSNumber {
            expTimestamp = expNum.doubleValue
        } else if let expNum = json["exp"] as? NSNumber {
            expTimestamp = expNum.doubleValue
        } else {
            throw QRPairingError.malformedPayload
        }

        let jsonTimestamp = expTimestamp > 10_000_000_000 ? expTimestamp / 1000.0 : expTimestamp
        let expiresAt = Date(timeIntervalSince1970: jsonTimestamp)
        guard expiresAt > now else {
            throw QRPairingError.expired
        }

        return QRPairingPayload(
            version: version,
            deviceID: id.lowercased(),
            deviceName: effectiveName,
            host: host,
            port: port,
            token: token,
            expiresAt: expiresAt
        )
    }

    public static func isValidDeviceID(_ id: String) -> Bool {
        let trimmed = id.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if UUID(uuidString: trimmed) != nil { return true }
        guard trimmed.count >= 6 && trimmed.count <= 64 else { return false }
        let allowed = CharacterSet(charactersIn: "0123456789abcdefghijklmnopqrstuvwxyz-")
        return CharacterSet(charactersIn: trimmed).isSubset(of: allowed)
    }

    public static func isValidToken(_ token: String) -> Bool {
        let trimmed = token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= 16 && trimmed.count <= 64 else { return false }
        let allowed = CharacterSet(charactersIn: "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ-_")
        return CharacterSet(charactersIn: trimmed).isSubset(of: allowed)
    }

    public static func isPrivateNetworkHost(_ host: String) -> Bool {
        let trimmed = host.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if trimmed.isEmpty { return false }

        if trimmed == "127.0.0.1" || trimmed == "::1" || trimmed == "localhost" {
            return true
        }

        if trimmed.hasSuffix(".local") {
            return true
        }

        let parts = trimmed.split(separator: ".")
        if parts.count == 4, let p0 = Int(parts[0]), let p1 = Int(parts[1]), let p2 = Int(parts[2]), let p3 = Int(parts[3]) {
            guard (0...255).contains(p0), (0...255).contains(p1), (0...255).contains(p2), (0...255).contains(p3) else {
                return false
            }
            if p0 == 10 { return true }
            if p0 == 172 && (16...31).contains(p1) { return true }
            if p0 == 192 && p1 == 168 { return true }
            if p0 == 169 && p1 == 254 { return true }
            return false
        }

        if trimmed.hasPrefix("fe80:") || trimmed.hasPrefix("fc") || trimmed.hasPrefix("fd") {
            return true
        }

        return false
    }
}

public enum QRPairingUIState: Equatable {
    case idle
    case waitingForScan(timeRemaining: TimeInterval)
    case connecting(peerName: String)
    case verifying(code: String, peerName: String)
    case paired(peerName: String)
    case failed(message: String)
    case expired
}

@MainActor
public final class QRPairingManager: ObservableObject {
    @Published public private(set) var uiState: QRPairingUIState = .idle
    @Published public private(set) var qrCodeImage: NSImage?
    @Published public private(set) var payloadURI: String = ""
    @Published public private(set) var timeRemaining: TimeInterval = 120

    public private(set) var currentPayload: QRPairingPayload?
    public private(set) var isTokenConsumed = false
    private var timer: Timer?

    public init() {}

    public var isSessionActive: Bool {
        if case .waitingForScan = uiState { return true }
        if case .connecting = uiState { return true }
        if case .verifying = uiState { return true }
        return false
    }

    public var activeToken: String? {
        guard !isTokenConsumed else { return nil }
        guard let payload = currentPayload, payload.expiresAt > Date() else { return nil }
        return payload.token
    }

    public func startSession(deviceID: String, deviceName: String, port: Int = 42458, duration: TimeInterval = 120) {
        cancelSession()
        let hostIP = GuestTransferSecurity.getLocalIPAddress()
        let token = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        let expiresAt = Date().addingTimeInterval(duration)
        
        let payload = QRPairingPayload(
            version: 1,
            deviceID: deviceID,
            deviceName: deviceName,
            host: hostIP,
            port: port,
            token: token,
            expiresAt: expiresAt
        )
        self.currentPayload = payload
        self.isTokenConsumed = false
        self.timeRemaining = duration
        let uri = payload.toURIString()
        self.payloadURI = uri
        self.qrCodeImage = GuestTransferSecurity.generateQRCode(from: uri, size: CGSize(width: 240, height: 240))
        self.uiState = .waitingForScan(timeRemaining: duration)

        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                let rem = expiresAt.timeIntervalSinceNow
                if rem <= 0 {
                    self.expireSession()
                } else {
                    self.timeRemaining = rem
                    if case .waitingForScan = self.uiState {
                        self.uiState = .waitingForScan(timeRemaining: rem)
                    }
                }
            }
        }
    }

    public func consumeTokenIfMatching(_ token: String) -> Bool {
        guard let active = activeToken, active == token else { return false }
        isTokenConsumed = true
        return true
    }

    public func restartSession() {
        if let payload = currentPayload {
            startSession(deviceID: payload.deviceID, deviceName: payload.deviceName, port: payload.port)
        }
    }

    public func setConnecting(peer: String) {
        uiState = .connecting(peerName: peer)
    }

    public func setVerifying(code: String, peer: String) {
        uiState = .verifying(code: code, peerName: peer)
    }

    public func setPaired(peer: String) {
        uiState = .paired(peerName: peer)
    }

    public func setFailed(message: String) {
        uiState = .failed(message: message)
    }

    public func setIdle() {
        if !isSessionActive {
            uiState = .idle
        }
    }

    public func cancelSession() {
        timer?.invalidate()
        timer = nil
        currentPayload = nil
        isTokenConsumed = false
        qrCodeImage = nil
        payloadURI = ""
        uiState = .idle
    }

    public func invalidateSession(reason: String) {
        timer?.invalidate()
        timer = nil
        isTokenConsumed = true
        qrCodeImage = nil
        uiState = .failed(message: reason)
    }

    private func expireSession() {
        timer?.invalidate()
        timer = nil
        isTokenConsumed = true
        qrCodeImage = nil
        uiState = .expired
    }
}

public struct QRPairingPopoverView: View {
    @ObservedObject var manager: QRPairingManager
    var onConfirm: (() -> Void)? = nil
    let onBack: () -> Void

    public init(manager: QRPairingManager, onConfirm: (() -> Void)? = nil, onBack: @escaping () -> Void) {
        self.manager = manager
        self.onConfirm = onConfirm
        self.onBack = onBack
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Header with Back button and Countdown
            HStack(spacing: 8) {
                Button(action: {
                    manager.cancelSession()
                    onBack()
                }) {
                    Label("Back", systemImage: "chevron.left")
                        .font(.system(size: 12, weight: .medium))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 7)
                        .background(Color.primary.opacity(0.08), in: Capsule())
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.accentColor)

                Spacer()
                Text("Scan to Pair").font(.headline)
                Spacer()

                if case .waitingForScan = manager.uiState {
                    HStack(spacing: 4) {
                        Circle()
                            .fill(Color.green)
                            .frame(width: 6, height: 6)
                        Text(formatRemaining(manager.timeRemaining))
                            .font(.system(size: 11, weight: .medium, design: .monospaced))
                            .foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 5)
                    .background(Color.secondary.opacity(0.12), in: Capsule())
                } else {
                    Color.clear.frame(width: 48, height: 1)
                }
            }
            .padding(.horizontal, 14)
            .padding(.top, 14)
            .padding(.bottom, 10)

            Divider()

            VStack(spacing: 14) {
                switch manager.uiState {
                case .waitingForScan:
                    Text("Scan this QR code using SYNQ on Android.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 20)
                        .padding(.top, 10)

                    if let qrImage = manager.qrCodeImage {
                        Image(nsImage: qrImage)
                            .interpolation(.none)
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(width: 170, height: 170)
                            .padding(10)
                            .background(Color.white)
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 14, style: .continuous)
                                    .stroke(Color.secondary.opacity(0.2), lineWidth: 1)
                            )
                            .shadow(color: .black.opacity(0.05), radius: 6, y: 2)
                    }

                    HStack(spacing: 6) {
                        Image(systemName: "wifi")
                            .font(.caption2)
                            .foregroundStyle(Color.accentColor)
                        Text("Both devices must be on the same local Wi‑Fi")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.top, 4)

                case let .connecting(peer):
                    Spacer()
                    ProgressView()
                        .controlSize(.regular)
                    Text("Connecting to \(peer)…")
                        .font(.headline)
                    Text("Establishing encrypted P‑256 session")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()

                case let .verifying(code, peer):
                    Spacer()
                    VStack(spacing: 12) {
                        Text("Compare verification code")
                            .font(.headline)
                        Text("Check that this 6‑digit code appears on \(peer):")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text(code)
                            .font(.system(size: 32, weight: .bold, design: .monospaced))
                            .tracking(4)
                            .padding(.horizontal, 20)
                            .padding(.vertical, 8)
                            .background(Color.primary.opacity(0.06), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                        
                        Button("Codes match") {
                            onConfirm?()
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.regular)
                    }
                    Spacer()

                case let .paired(peer):
                    Spacer()
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 40))
                        .foregroundStyle(.green)
                    Text("Paired with \(peer)")
                        .font(.headline)
                    Text("Your devices can now share files, clipboard, and links securely.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 20)
                    Button("Done") {
                        manager.cancelSession()
                        onBack()
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.regular)
                    Spacer()

                case let .failed(msg):
                    Spacer()
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 36))
                        .foregroundStyle(.orange)
                    Text("Pairing failed")
                        .font(.headline)
                    Text(msg)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 20)
                    Button("Try Again") {
                        manager.restartSession()
                    }
                    .buttonStyle(.bordered)
                    Spacer()

                case .expired:
                    Spacer()
                    Image(systemName: "clock.badge.exclamationmark")
                        .font(.system(size: 36))
                        .foregroundStyle(.secondary)
                    Text("Pairing Code Expired")
                        .font(.headline)
                    Text("Pairing codes expire after 2 minutes for security.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Button("Generate New Code") {
                        manager.restartSession()
                    }
                    .buttonStyle(.borderedProminent)
                    Spacer()

                case .idle:
                    EmptyView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.bottom, 14)
        }
    }

    private func formatRemaining(_ remaining: TimeInterval) -> String {
        let mins = Int(remaining) / 60
        let secs = Int(remaining) % 60
        return String(format: "%02d:%02d", mins, secs)
    }
}

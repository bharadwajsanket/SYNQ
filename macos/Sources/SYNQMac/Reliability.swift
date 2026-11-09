import Foundation
import Network

let maximumProtocolFrameBytes = 4 * 1024 * 1024
let maximumTransferHistory = 20
private let protocolJSONDecoder = JSONDecoder()

func reconnectDelay(attempt: Int) -> TimeInterval {
    let boundedAttempt = min(max(attempt, 0), 30)
    return min(pow(2.0, Double(boundedAttempt)), 30.0)
}

func heartbeatExpired(
    supported: Bool,
    lastReceivedAt: Date,
    now: Date = Date(),
    timeout: TimeInterval = 30
) -> Bool {
    supported && now.timeIntervalSince(lastReceivedAt) >= timeout
}

func localNetworkPermissionDenied(_ error: NWError) -> Bool {
    switch error {
    case .posix(.EACCES), .dns(-65570): true
    default: false
    }
}

func decodeProtocolMessage(_ data: Data) throws -> PairingMessage {
    guard !data.isEmpty, data.count <= maximumProtocolFrameBytes else {
        throw PairingError.invalidMessage
    }
    let message = try protocolJSONDecoder.decode(PairingMessage.self, from: data)
    guard !message.kind.isEmpty, message.kind.utf8.count <= 64,
          !message.sessionId.isEmpty, message.sessionId.utf8.count <= 128 else {
        throw PairingError.invalidMessage
    }
    return message
}

func recoverInterruptedTransfers(_ transfers: [String: FileTransferRow]) -> [String: FileTransferRow] {
    Dictionary(uniqueKeysWithValues: transfers.values
        .map { transfer in
            guard transfer.active else { return transfer }
            return FileTransferRow(
                id: transfer.id,
                name: transfer.name,
                status: "Transfer interrupted — reconnect to retry",
                active: false,
                startedAt: transfer.startedAt,
                retryable: transfer.retryable
            )
        }
        .sorted { $0.startedAt > $1.startedAt }
        .prefix(maximumTransferHistory)
        .map { ($0.id, $0) })
}

let secondaryInitiatorFallbackDelay: TimeInterval = 4.0

func shouldInitiateConnectionImmediately(localDeviceID: String, remoteDeviceID: String) -> Bool {
    localDeviceID.lowercased() < remoteDeviceID.lowercased()
}

func resolveConnectionEndpoint(
    discoveredPeers: [DiscoveredPeer],
    trustedDeviceIDs: Set<String>,
    lastTrustedEndpoint: (host: String, port: Int, name: String)?
) -> (host: String, port: Int, name: String)? {
    if let peer = discoveredPeers.first(where: {
        guard let id = $0.deviceIDHint?.lowercased() else { return false }
        return trustedDeviceIDs.contains(id) && $0.host != nil && $0.port != nil
    }), let host = peer.host, let port = peer.port {
        return (host, port, peer.deviceNameHint)
    }
    return lastTrustedEndpoint
}

func uniqueDestinationURL(_ requested: URL, fileManager: FileManager = .default) -> URL {
    let isAvailable: (URL) -> Bool = { candidate in
        let partURL = candidate.appendingPathExtension("bridgey-part")
        return !fileManager.fileExists(atPath: candidate.path) && !fileManager.fileExists(atPath: partURL.path)
    }
    guard !isAvailable(requested) else { return requested }
    let extensionPart = requested.pathExtension
    let stem = requested.deletingPathExtension().lastPathComponent
    for number in 2...10_000 {
        let name = extensionPart.isEmpty ? "\(stem) \(number)" : "\(stem) \(number).\(extensionPart)"
        let candidate = requested.deletingLastPathComponent().appendingPathComponent(name)
        if isAvailable(candidate) { return candidate }
    }
    return requested.deletingLastPathComponent().appendingPathComponent(UUID().uuidString)
}

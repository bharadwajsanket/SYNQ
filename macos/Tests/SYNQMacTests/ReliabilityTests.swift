import Foundation
import Network
import XCTest
@testable import SYNQMac

final class ReliabilityTests: XCTestCase {
    func testMalformedAndOversizedFramesAreRejected() throws {
        XCTAssertThrowsError(try decodeProtocolMessage(Data("{}".utf8)))
        XCTAssertThrowsError(try decodeProtocolMessage(Data(repeating: 0x78, count: maximumProtocolFrameBytes + 1)))
        let longSession = Data((#"{"kind":"features.update","sessionId":""# + String(repeating: "x", count: 129) + #""}"#).utf8)
        XCTAssertThrowsError(try decodeProtocolMessage(longSession))
        let valid = Data(#"{"kind":"features.update","sessionId":"session"}"#.utf8)
        XCTAssertEqual(try decodeProtocolMessage(valid).kind, "features.update")
    }

    func testReconnectBackoffIsBounded() {
        XCTAssertEqual((0...6).map(reconnectDelay), [1, 2, 4, 8, 16, 30, 30])
    }

    func testHeartbeatTimeoutRequiresNegotiatedSupport() {
        let now = Date(timeIntervalSince1970: 100)
        XCTAssertFalse(heartbeatExpired(supported: false, lastReceivedAt: .distantPast, now: now))
        XCTAssertFalse(heartbeatExpired(supported: true, lastReceivedAt: Date(timeIntervalSince1970: 80), now: now))
        XCTAssertTrue(heartbeatExpired(supported: true, lastReceivedAt: Date(timeIntervalSince1970: 70), now: now))
    }

    func testLocalNetworkPermissionErrorsAreRecognized() {
        XCTAssertTrue(localNetworkPermissionDenied(.posix(.EACCES)))
        XCTAssertTrue(localNetworkPermissionDenied(.dns(-65570)))
        XCTAssertFalse(localNetworkPermissionDenied(.posix(.ECONNREFUSED)))
    }

    func testInterruptedTransferBecomesHistoryEntry() {
        let transfer = FileTransferRow(
            id: "transfer",
            name: "file",
            status: "Receiving",
            active: true,
            startedAt: Date(timeIntervalSince1970: 1),
            retryable: false
        )

        let recovered = recoverInterruptedTransfers([transfer.id: transfer])

        XCTAssertFalse(recovered[transfer.id]!.active)
        XCTAssertEqual(recovered[transfer.id]!.status, "Transfer interrupted — reconnect to retry")
    }

    func testUniqueDestinationURLAvoidsPartFileCollision() throws {
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let target = tempDir.appendingPathComponent("document.pdf")
        let inFlightPart = tempDir.appendingPathComponent("document.pdf.bridgey-part")
        FileManager.default.createFile(atPath: inFlightPart.path, contents: Data("in-flight".utf8))

        let resolved = uniqueDestinationURL(target)
        XCTAssertEqual(resolved.lastPathComponent, "document 2.pdf")

        let inFlightPart2 = tempDir.appendingPathComponent("document 2.pdf.bridgey-part")
        FileManager.default.createFile(atPath: inFlightPart2.path, contents: Data("in-flight-2".utf8))
        let resolved2 = uniqueDestinationURL(target)
        XCTAssertEqual(resolved2.lastPathComponent, "document 3.pdf")
    }

    func testResolveConnectionEndpointPrefersFreshDiscoveredPeerOverStaleEndpoint() {
        let peer = DiscoveredPeer(
            serviceName: "SYNQ-pixel",
            deviceIDHint: "trusted-phone-id",
            deviceNameHint: "Pixel 9 Pro",
            platformHint: "android",
            protocolVersionHint: 1,
            host: "192.168.43.20",
            port: 42458
        )
        let staleEndpoint = (host: "192.168.1.100", port: 42458, name: "Old Pixel")
        let resolved = resolveConnectionEndpoint(
            discoveredPeers: [peer],
            trustedDeviceIDs: ["trusted-phone-id"],
            lastTrustedEndpoint: staleEndpoint
        )
        XCTAssertEqual(resolved?.host, "192.168.43.20")
        XCTAssertEqual(resolved?.name, "Pixel 9 Pro")
    }

    func testResolveConnectionEndpointFallsBackToLastTrustedWhenNoPeersDiscovered() {
        let staleEndpoint = (host: "192.168.1.100", port: 42458, name: "Pixel")
        let resolved = resolveConnectionEndpoint(
            discoveredPeers: [],
            trustedDeviceIDs: ["trusted-phone-id"],
            lastTrustedEndpoint: staleEndpoint
        )
        XCTAssertEqual(resolved?.host, "192.168.1.100")
    }

    func testConnectionInitiatorTieBreakIsDeterministic() {
        XCTAssertTrue(shouldInitiateConnectionImmediately(localDeviceID: "aaaa-1111", remoteDeviceID: "bbbb-2222"))
        XCTAssertFalse(shouldInitiateConnectionImmediately(localDeviceID: "bbbb-2222", remoteDeviceID: "aaaa-1111"))
        XCTAssertTrue(shouldInitiateConnectionImmediately(localDeviceID: "AAAA-1111", remoteDeviceID: "bbbb-2222"))
        XCTAssertEqual(secondaryInitiatorFallbackDelay, 4.0)
    }

    func testResolveConnectionEndpointCaseInsensitiveMatch() {
        let peer = DiscoveredPeer(
            serviceName: "SYNQ-pixel",
            deviceIDHint: "ABC-123",
            deviceNameHint: "Pixel",
            platformHint: "android",
            protocolVersionHint: 1,
            host: "192.168.1.50",
            port: 42458
        )
        let resolved = resolveConnectionEndpoint(
            discoveredPeers: [peer],
            trustedDeviceIDs: ["abc-123"],
            lastTrustedEndpoint: nil
        )
        XCTAssertEqual(resolved?.host, "192.168.1.50")
    }

    func testResolveConnectionEndpointIgnoresPeerWithoutHostOrPort() {
        let peerWithoutHost = DiscoveredPeer(
            serviceName: "SYNQ-pixel",
            deviceIDHint: "trusted-id",
            deviceNameHint: "Pixel",
            platformHint: "android",
            protocolVersionHint: 1,
            host: nil,
            port: nil
        )
        let fallback = (host: "192.168.1.10", port: 42458, name: "Fallback")
        let resolved = resolveConnectionEndpoint(
            discoveredPeers: [peerWithoutHost],
            trustedDeviceIDs: ["trusted-id"],
            lastTrustedEndpoint: fallback
        )
        XCTAssertEqual(resolved?.host, "192.168.1.10")
    }
}

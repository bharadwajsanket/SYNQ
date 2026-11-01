import Foundation
import XCTest
@testable import SYNQMac

final class QRPairingTests: XCTestCase {

    func testValidURIParsing() throws {
        let now = Date()
        let expiry = now.addingTimeInterval(120)
        let uri = "synq://pair?v=1&id=mac-unit-test-123&name=MacBook%20Pro&host=192.168.1.100&port=42458&token=abcdef1234567890abcdef&exp=\(Int64(expiry.timeIntervalSince1970))"

        let parsed = try QRPairingPayload.parse(uri)
        XCTAssertEqual(parsed.version, 1)
        XCTAssertEqual(parsed.deviceID, "mac-unit-test-123")
        XCTAssertEqual(parsed.deviceName, "MacBook Pro")
        XCTAssertEqual(parsed.host, "192.168.1.100")
        XCTAssertEqual(parsed.port, 42458)
        XCTAssertEqual(parsed.token, "abcdef1234567890abcdef")
        XCTAssertFalse(parsed.isExpired)
    }

    func testValidJSONParsing() throws {
        let expiry = Date().addingTimeInterval(120)
        let json = """
        {
            "v": 1,
            "id": "mac-device-5678",
            "name": "iMac Studio",
            "host": "10.0.1.25",
            "port": 42458,
            "token": "abcdef0123456789abcd",
            "exp": \(Int64(expiry.timeIntervalSince1970))
        }
        """

        let parsed = try QRPairingPayload.parse(json)
        XCTAssertEqual(parsed.deviceID, "mac-device-5678")
        XCTAssertEqual(parsed.deviceName, "iMac Studio")
        XCTAssertEqual(parsed.host, "10.0.1.25")
        XCTAssertEqual(parsed.port, 42458)
    }

    func testRejectPublicWANIPAddresses() {
        let expiry = Int64(Date().addingTimeInterval(120).timeIntervalSince1970)
        let publicIPs = ["8.8.8.8", "1.1.1.1", "142.250.190.46", "203.0.113.5"]
        for ip in publicIPs {
            let uri = "synq://pair?v=1&id=mac-device-123&name=Mac&host=\(ip)&port=42458&token=abcdef1234567890abcdef&exp=\(expiry)"
            XCTAssertThrowsError(try QRPairingPayload.parse(uri)) { error in
                guard let qrError = error as? QRPairingError else {
                    return XCTFail("Expected QRPairingError")
                }
                XCTAssertEqual(qrError, .untrustedHost)
            }
        }
    }

    func testRejectExpiredSession() {
        let expiredTimestamp = Int64(Date().addingTimeInterval(-10).timeIntervalSince1970)
        let uri = "synq://pair?v=1&id=mac-device-123&name=Mac&host=192.168.1.5&port=42458&token=abcdef1234567890abcdef&exp=\(expiredTimestamp)"
        XCTAssertThrowsError(try QRPairingPayload.parse(uri)) { error in
            guard let qrError = error as? QRPairingError else {
                return XCTFail("Expected QRPairingError")
            }
            XCTAssertEqual(qrError, .expired)
        }
    }

    @MainActor
    func testSingleUseTokenConsumption() {
        let manager = QRPairingManager()
        manager.startSession(deviceID: "device-123456", deviceName: "MacBook", port: 42458, duration: 60)
        guard let token = manager.currentPayload?.token else {
            return XCTFail("Missing payload token")
        }

        XCTAssertTrue(manager.isSessionActive)
        XCTAssertFalse(manager.consumeTokenIfMatching("invalid-token-123"))
        XCTAssertFalse(manager.isTokenConsumed)

        XCTAssertTrue(manager.consumeTokenIfMatching(token))
        XCTAssertTrue(manager.isTokenConsumed)

        // Token replay attempt must fail
        XCTAssertFalse(manager.consumeTokenIfMatching(token))
    }
}


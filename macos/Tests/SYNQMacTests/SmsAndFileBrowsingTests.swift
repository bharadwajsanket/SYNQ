import XCTest
@testable import SYNQMac

final class SmsAndFileBrowsingTests: XCTestCase {
    func testSmsSendPayloadSerialization() throws {
        let payload = SmsSendPayload(sendId: "sms-1234", recipient: "+15551234567", body: "Hello from Mac")
        let data = try JSONEncoder().encode(payload)
        let decoded = try JSONDecoder().decode(SmsSendPayload.self, from: data)
        XCTAssertEqual(decoded.sendId, "sms-1234")
        XCTAssertEqual(decoded.recipient, "+15551234567")
        XCTAssertEqual(decoded.body, "Hello from Mac")
    }

    func testSmsResultPayloadSerialization() throws {
        let payload = SmsResultPayload(sendId: "sms-5678", status: "sent", error: nil)
        let data = try JSONEncoder().encode(payload)
        let decoded = try JSONDecoder().decode(SmsResultPayload.self, from: data)
        XCTAssertEqual(decoded.sendId, "sms-5678")
        XCTAssertEqual(decoded.status, "sent")
        XCTAssertNil(decoded.error)

        let failurePayload = SmsResultPayload(sendId: "sms-9999", status: "permission_denied", error: "SMS permission denied on Android")
        let failData = try JSONEncoder().encode(failurePayload)
        let failDecoded = try JSONDecoder().decode(SmsResultPayload.self, from: failData)
        XCTAssertEqual(failDecoded.sendId, "sms-9999")
        XCTAssertEqual(failDecoded.status, "permission_denied")
        XCTAssertEqual(failDecoded.error, "SMS permission denied on Android")
    }

    func testFsListPayloadSerialization() throws {
        let payload = FsListPayload(path: "Documents/Photos")
        let data = try JSONEncoder().encode(payload)
        let decoded = try JSONDecoder().decode(FsListPayload.self, from: data)
        XCTAssertEqual(decoded.path, "Documents/Photos")
    }

    func testFsListResponsePayloadSerialization() throws {
        let item1 = RemoteFsEntry(name: "Photo.jpg", path: "Photo.jpg", isDirectory: false, size: 2048576, lastModified: 1700000000000, mimeType: "image/jpeg")
        let item2 = RemoteFsEntry(name: "Subfolder", path: "Subfolder", isDirectory: true, size: 0, lastModified: 1700000000000, mimeType: "")
        let payload = FsListResponsePayload(path: "Downloads", entries: [item1, item2])
        
        let data = try JSONEncoder().encode(payload)
        let decoded = try JSONDecoder().decode(FsListResponsePayload.self, from: data)
        XCTAssertEqual(decoded.path, "Downloads")
        XCTAssertEqual(decoded.entries.count, 2)
        XCTAssertEqual(decoded.entries[0].name, "Photo.jpg")
        XCTAssertFalse(decoded.entries[0].isDirectory)
        XCTAssertEqual(decoded.entries[0].size, 2048576)
        XCTAssertEqual(decoded.entries[1].name, "Subfolder")
        XCTAssertTrue(decoded.entries[1].isDirectory)
    }

    func testFsPullPayloadSerialization() throws {
        let payload = FsPullPayload(path: "Downloads/document.pdf")
        let data = try JSONEncoder().encode(payload)
        let decoded = try JSONDecoder().decode(FsPullPayload.self, from: data)
        XCTAssertEqual(decoded.path, "Downloads/document.pdf")
    }

    func testFsErrorPayloadSerialization() throws {
        let payload = FsErrorPayload(path: "Downloads", error: "Path traversal rejected")
        let data = try JSONEncoder().encode(payload)
        let decoded = try JSONDecoder().decode(FsErrorPayload.self, from: data)
        XCTAssertEqual(decoded.path, "Downloads")
        XCTAssertEqual(decoded.error, "Path traversal rejected")
    }

    func testSmsErrorAndFsErrorLocalizedDescription() {
        let smsErr = SmsError("Device disconnected")
        XCTAssertEqual(smsErr.localizedDescription, "Device disconnected")
        XCTAssertEqual(smsErr.message, "Device disconnected")

        let fsErr = FsError("Access outside Downloads directory prohibited")
        XCTAssertEqual(fsErr.localizedDescription, "Access outside Downloads directory prohibited")
        XCTAssertEqual(fsErr.message, "Access outside Downloads directory prohibited")
    }

    func testSmartLinkDetectionAndNormalization() {
        // Direct valid HTTP/HTTPS URLs
        XCTAssertEqual(detectSmartLink("https://github.com/bharadwajsanket/SYNQ"), "https://github.com/bharadwajsanket/SYNQ")
        XCTAssertEqual(detectSmartLink("http://example.com/api/v1"), "http://example.com/api/v1")

        // Auto-prefix www. with https://
        XCTAssertEqual(detectSmartLink("www.google.com"), "https://www.google.com")
        XCTAssertEqual(detectSmartLink("www.apple.com/mac"), "https://www.apple.com/mac")

        // Local network .local hostnames
        XCTAssertEqual(detectSmartLink("http://macbook.local:8080/"), "http://macbook.local:8080/")
        XCTAssertEqual(detectSmartLink("nas.local:5000"), "http://nas.local:5000")

        // Private / local IPv4 addresses with and without port
        XCTAssertEqual(detectSmartLink("192.168.1.100:8080"), "http://192.168.1.100:8080")
        XCTAssertEqual(detectSmartLink("http://192.168.0.1"), "http://192.168.0.1")
        XCTAssertEqual(detectSmartLink("10.0.0.1:3000/app"), "http://10.0.0.1:3000/app")
        XCTAssertEqual(detectSmartLink("localhost:8000"), "http://localhost:8000")

        // IPv6
        XCTAssertEqual(detectSmartLink("http://[::1]:8080"), "http://[::1]:8080")

        // Extraction from sentence with prose
        XCTAssertEqual(detectSmartLink("Here is the project: https://github.com/synq check it out"), "https://github.com/synq")
        XCTAssertEqual(detectSmartLink("Check out www.apple.com for details."), "https://www.apple.com")

        // False positives rejection
        XCTAssertNil(detectSmartLink("just ordinary text without any links"))
        XCTAssertNil(detectSmartLink("readme.txt"))
        XCTAssertNil(detectSmartLink("version.1.0"))
        XCTAssertNil(detectSmartLink("user.name = John"))
        XCTAssertNil(detectSmartLink("foo.bar"))
        XCTAssertNil(detectSmartLink("https://user:pass@evil.com"))
        XCTAssertNil(detectSmartLink("https://example.com/\\evil"))
    }
}

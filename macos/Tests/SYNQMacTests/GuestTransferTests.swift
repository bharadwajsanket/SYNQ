import XCTest
@testable import SYNQMac

@MainActor
final class GuestTransferTests: XCTestCase {
    var tempDirectory: URL!

    override func setUp() async throws {
        try await super.setUp()
        tempDirectory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: tempDirectory, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: tempDirectory)
        try await super.tearDown()
    }

    func testSecureTokenGeneration() {
        let token1 = GuestTransferSecurity.generateSecureToken()
        let token2 = GuestTransferSecurity.generateSecureToken()

        XCTAssertEqual(token1.count, 32)
        XCTAssertEqual(token2.count, 32)
        XCTAssertNotEqual(token1, token2)
        XCTAssertTrue(token1.allSatisfy { $0.isHexDigit })
    }

    func testFilenameSanitization() {
        XCTAssertEqual(GuestTransferSecurity.sanitizeFilename("../../etc/passwd"), "passwd")
        XCTAssertEqual(GuestTransferSecurity.sanitizeFilename("..\\..\\windows\\system32"), "system32")
        XCTAssertEqual(GuestTransferSecurity.sanitizeFilename("/private/var/log/system.log"), "system.log")
        XCTAssertEqual(GuestTransferSecurity.sanitizeFilename(".bash_profile"), "bash_profile")
        XCTAssertEqual(GuestTransferSecurity.sanitizeFilename("photo:image.jpg"), "photoimage.jpg")

        // Test empty name fallback
        let fallback = GuestTransferSecurity.sanitizeFilename("   ")
        XCTAssertTrue(fallback.hasPrefix("guest_upload_"))
        XCTAssertTrue(fallback.hasSuffix(".bin"))

        // Test long name truncation preserving extension
        let longName = String(repeating: "a", count: 200) + ".pdf"
        let sanitizedLong = GuestTransferSecurity.sanitizeFilename(longName)
        XCTAssertTrue(sanitizedLong.hasSuffix(".pdf"))
        XCTAssertLessThanOrEqual(sanitizedLong.count, 128)
    }

    func testUniqueDestinationURLCollisionAvoidance() throws {
        let filename = "document.txt"
        let file1 = tempDirectory.appendingPathComponent(filename)
        try "Content 1".write(to: file1, atomically: true, encoding: .utf8)

        let resolved = GuestTransferSecurity.uniqueDestinationURL(for: filename, in: tempDirectory)
        XCTAssertEqual(resolved.lastPathComponent, "document (1).txt")

        try "Content 2".write(to: resolved, atomically: true, encoding: .utf8)
        let resolved2 = GuestTransferSecurity.uniqueDestinationURL(for: filename, in: tempDirectory)
        XCTAssertEqual(resolved2.lastPathComponent, "document (2).txt")
    }

    func testQRCodeGeneration() {
        let testURL = "http://192.168.1.5:42460/guest?token=0123456789abcdef0123456789abcdef"
        let qr = GuestTransferSecurity.generateQRCode(from: testURL)
        XCTAssertNotNil(qr)
        XCTAssertGreaterThan(qr?.size.width ?? 0, 0)
        XCTAssertGreaterThan(qr?.size.height ?? 0, 0)
    }

    func testManagerLifecycleAndCleanup() {
        let manager = GuestTransferManager()
        XCTAssertFalse(manager.isSessionActive)
        XCTAssertNil(manager.sessionToken)

        manager.startSession(duration: 60)
        XCTAssertTrue(manager.isSessionActive)
        XCTAssertNotNil(manager.sessionToken)
        XCTAssertNotNil(manager.sessionURLString)
        XCTAssertNotNil(manager.qrCodeImage)
        XCTAssertEqual(manager.timeRemaining, 60)

        manager.cancelSession()
        XCTAssertFalse(manager.isSessionActive)
        XCTAssertNil(manager.sessionToken)
        XCTAssertNil(manager.sessionURLString)
        XCTAssertNil(manager.qrCodeImage)
        XCTAssertEqual(manager.timeRemaining, 0)
    }

    func testManagerAcceptAndRejectOperations() throws {
        let manager = GuestTransferManager()
        manager.startSession(duration: 120)

        let stagedFile = tempDirectory.appendingPathComponent("test_upload.png")
        try "fake image data".write(to: stagedFile, atomically: true, encoding: .utf8)

        let upload = GuestPendingUpload(
            id: UUID(),
            filename: "photo.png",
            fileSize: 15,
            stagedURL: stagedFile,
            status: .pending
        )

        // Reflection or direct addition for testing
        // Test Accept
        let destinationDir = tempDirectory.appendingPathComponent("downloads", isDirectory: true)
        try FileManager.default.createDirectory(at: destinationDir, withIntermediateDirectories: true)

        let destURL = GuestTransferSecurity.uniqueDestinationURL(for: upload.filename, in: destinationDir)
        try FileManager.default.moveItem(at: upload.stagedURL, to: destURL)

        XCTAssertTrue(FileManager.default.fileExists(atPath: destURL.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: stagedFile.path))

        // Test Reject on a second file
        let stagedFile2 = tempDirectory.appendingPathComponent("test_upload_2.png")
        try "fake image data 2".write(to: stagedFile2, atomically: true, encoding: .utf8)

        try FileManager.default.removeItem(at: stagedFile2)
        XCTAssertFalse(FileManager.default.fileExists(atPath: stagedFile2.path))

        manager.cancelSession()
    }

    func testHostnamePreferenceAndURLSwitching() {
        let manager = GuestTransferManager()
        manager.startSession(duration: 120)

        XCTAssertTrue(manager.isSessionActive)
        XCTAssertTrue(manager.sessionURLString?.contains(manager.localIP) == true)
        XCTAssertNotNil(manager.qrCodeImage)
        XCTAssertFalse(manager.sessionURLString?.contains(".local") == true)

        manager.cancelSession()
    }

    func testStrictPathTraversalSanitization() {
        XCTAssertEqual(GuestTransferSecurity.sanitizeFilename("....//....//etc//passwd"), "passwd")
        XCTAssertEqual(GuestTransferSecurity.sanitizeFilename("\0malicious.sh"), "malicious.sh")
        XCTAssertEqual(GuestTransferSecurity.sanitizeFilename("...hidden_file.txt"), "hidden_file.txt")
        XCTAssertEqual(GuestTransferSecurity.sanitizeFilename("dir/sub/target.pdf"), "target.pdf")
        XCTAssertEqual(GuestTransferSecurity.sanitizeFilename("C:\\Users\\Admin\\Desktop\\secrets.env"), "secrets.env")
    }
}

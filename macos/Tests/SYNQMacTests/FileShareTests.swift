import AppKit
import XCTest
@testable import SYNQMac

@MainActor
final class FileShareTests: XCTestCase {
    private var tempDirectory: URL!

    override func setUpWithError() throws {
        try super.setUpWithError()
        tempDirectory = FileManager.default.temporaryDirectory.appendingPathComponent("synq-tests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDirectory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let tempDirectory {
            try? FileManager.default.removeItem(at: tempDirectory)
        }
        try super.tearDownWithError()
    }

    func testExtractsURLsFromPasteboardWithNSURLObjects() throws {
        let file1 = tempDirectory.appendingPathComponent("doc1.pdf")
        let file2 = tempDirectory.appendingPathComponent("doc2.png")
        try Data("content1".utf8).write(to: file1)
        try Data("content2".utf8).write(to: file2)

        let pboard = NSPasteboard(name: NSPasteboard.Name(UUID().uuidString))
        pboard.clearContents()
        XCTAssertTrue(pboard.writeObjects([file1 as NSURL, file2 as NSURL]))

        let extracted = fileURLs(from: pboard)
        XCTAssertEqual(extracted.count, 2)
        XCTAssertEqual(extracted.map(\.lastPathComponent), ["doc1.pdf", "doc2.png"])
    }

    func testExtractsURLsFromPasteboardWithLegacyFilenames() throws {
        let file = tempDirectory.appendingPathComponent("legacy.txt")
        try Data("legacy content".utf8).write(to: file)

        let pboard = NSPasteboard(name: NSPasteboard.Name(UUID().uuidString))
        pboard.clearContents()
        let filenamesType = NSPasteboard.PasteboardType("NSFilenamesPboardType")
        pboard.declareTypes([filenamesType], owner: nil)
        pboard.setPropertyList([file.path], forType: filenamesType)

        let extracted = fileURLs(from: pboard)
        XCTAssertEqual(extracted.count, 1)
        XCTAssertEqual(extracted.first?.lastPathComponent, "legacy.txt")
    }

    func testExtractsURLsFromPasteboardWithFileURLString() throws {
        let file = tempDirectory.appendingPathComponent("direct.jpg")
        try Data("image".utf8).write(to: file)

        let pboard = NSPasteboard(name: NSPasteboard.Name(UUID().uuidString))
        pboard.clearContents()
        pboard.declareTypes([.fileURL], owner: nil)
        pboard.setString(file.absoluteString, forType: .fileURL)

        let extracted = fileURLs(from: pboard)
        XCTAssertEqual(extracted.count, 1)
        XCTAssertEqual(extracted.first?.lastPathComponent, "direct.jpg")
    }

    func testEmptyPasteboardReturnsEmptyList() {
        let pboard = NSPasteboard(name: NSPasteboard.Name(UUID().uuidString))
        pboard.clearContents()

        let extracted = fileURLs(from: pboard)
        XCTAssertTrue(extracted.isEmpty)
    }

    func testFilterRegularFilesSeparatesFilesAndDirectories() throws {
        let regularFile = tempDirectory.appendingPathComponent("file.txt")
        try Data("data".utf8).write(to: regularFile)

        let subDir = tempDirectory.appendingPathComponent("SubFolder")
        try FileManager.default.createDirectory(at: subDir, withIntermediateDirectories: true)

        let nonExistent = tempDirectory.appendingPathComponent("missing.txt")

        let result = filterRegularFiles([regularFile, subDir, nonExistent])
        XCTAssertEqual(result.valid.count, 1)
        XCTAssertEqual(result.valid.first?.lastPathComponent, "file.txt")
        XCTAssertEqual(result.directories.count, 1)
        XCTAssertEqual(result.directories.first?.lastPathComponent, "SubFolder")
        XCTAssertEqual(result.unreadable.count, 1)
        XCTAssertEqual(result.unreadable.first?.lastPathComponent, "missing.txt")
    }

    func testInspectSharedFilesCalculatesSizesAndIcons() throws {
        let testFile = tempDirectory.appendingPathComponent("sample.txt")
        let sampleData = Data(repeating: 0x41, count: 2048) // 2 KB
        try sampleData.write(to: testFile)

        let subDir = tempDirectory.appendingPathComponent("Directory")
        try FileManager.default.createDirectory(at: subDir, withIntermediateDirectories: true)

        let items = inspectSharedFiles([testFile, subDir])
        XCTAssertEqual(items.count, 2)

        let fileItem = try XCTUnwrap(items.first(where: { !$0.isDirectory }))
        XCTAssertEqual(fileItem.name, "sample.txt")
        XCTAssertEqual(fileItem.size, 2048)
        XCTAssertFalse(fileItem.formattedSize.isEmpty)
        XCTAssertNotNil(fileItem.icon)

        let dirItem = try XCTUnwrap(items.first(where: { $0.isDirectory }))
        XCTAssertEqual(dirItem.name, "Directory")
        XCTAssertTrue(dirItem.isDirectory)
        XCTAssertEqual(dirItem.formattedSize, "Folder — skipped")
    }

    func testFileShareServiceProviderDispatchesWhenValidFilesPresent() throws {
        let file = tempDirectory.appendingPathComponent("share.pdf")
        try Data("pdf".utf8).write(to: file)

        let pboard = NSPasteboard(name: NSPasteboard.Name(UUID().uuidString))
        pboard.clearContents()
        pboard.writeObjects([file as NSURL])

        var receivedURLs: [URL]?
        let provider = FileShareServiceProvider { urls in
            receivedURLs = urls
        }

        var errorString: NSString?
        provider.sendFilesWithSYNQ(pboard, userData: "", error: &errorString)

        XCTAssertNil(errorString)
        XCTAssertEqual(receivedURLs?.count, 1)
        XCTAssertEqual(receivedURLs?.first?.lastPathComponent, "share.pdf")
    }

    func testFileShareServiceProviderSetsErrorOnEmptyPasteboard() {
        let pboard = NSPasteboard(name: NSPasteboard.Name(UUID().uuidString))
        pboard.clearContents()

        var invoked = false
        let provider = FileShareServiceProvider { _ in
            invoked = true
        }

        var errorString: NSString?
        provider.sendFilesWithSYNQ(pboard, userData: "", error: &errorString)

        XCTAssertFalse(invoked)
        XCTAssertEqual(errorString as String?, "No valid files were found to send.")
    }

    func testAppServicesProviderDelegatesCallAndFileShare() throws {
        var callDispatched = false
        var shareDispatched = false

        let appProvider = AppServicesProvider(
            onCall: { number in
                if number == "+15551234567" {
                    callDispatched = true
                }
            },
            onShareFiles: { urls in
                if !urls.isEmpty {
                    shareDispatched = true
                }
            }
        )

        // Test call delegation
        let callPboard = NSPasteboard(name: NSPasteboard.Name(UUID().uuidString))
        callPboard.clearContents()
        callPboard.declareTypes([.string], owner: nil)
        callPboard.setString("+15551234567", forType: .string)

        var callError: NSString?
        appProvider.callWithSYNQ(callPboard, userData: "", error: &callError)
        XCTAssertNil(callError)
        XCTAssertTrue(callDispatched)

        // Test file share delegation
        let file = tempDirectory.appendingPathComponent("delegated.txt")
        try Data("test".utf8).write(to: file)
        let filePboard = NSPasteboard(name: NSPasteboard.Name(UUID().uuidString))
        filePboard.clearContents()
        filePboard.writeObjects([file as NSURL])

        var shareError: NSString?
        appProvider.sendFilesWithSYNQ(filePboard, userData: "", error: &shareError)
        XCTAssertNil(shareError)
        XCTAssertTrue(shareDispatched)

        // Test with nil userData (standard Cocoa runtime invocation without NSUserData)
        var nilUserDataShareDispatched = false
        let appProvider2 = AppServicesProvider(
            onCall: { _ in },
            onShareFiles: { _ in nilUserDataShareDispatched = true }
        )
        appProvider2.sendFilesWithSYNQ(filePboard, userData: nil, error: &shareError)
        XCTAssertNil(shareError)
        XCTAssertTrue(nilUserDataShareDispatched)
    }

    func testPhoneURLHandlerDispatchesFileOpenEvents() throws {
        let file1 = tempDirectory.appendingPathComponent("opened.txt")
        try Data("opened".utf8).write(to: file1)

        let handler = PhoneURLHandler()
        var openedURLs: [URL] = []
        handler.configureFiles { urls in
            openedURLs = urls
        }

        handler.application(NSApplication.shared, open: [file1])
        XCTAssertEqual(openedURLs.count, 1)
        XCTAssertEqual(openedURLs.first?.lastPathComponent, "opened.txt")

        openedURLs.removeAll()
        handler.application(NSApplication.shared, openFiles: [file1.path])
        XCTAssertEqual(openedURLs.count, 1)
        XCTAssertEqual(openedURLs.first?.lastPathComponent, "opened.txt")
    }
}

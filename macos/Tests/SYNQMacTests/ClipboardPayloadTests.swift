import XCTest
@testable import SYNQMac

final class ClipboardPayloadTests: XCTestCase {
    func testRichClipboardRequiresPlainFallbackAndHTML() {
        XCTAssertNil(RichClipboardContent(text: "text", html: nil))
        XCTAssertNil(RichClipboardContent(text: "", html: "<b>text</b>"))
        XCTAssertNil(RichClipboardContent(text: "text", html: ""))
        XCTAssertEqual(RichClipboardContent(text: "text", html: "<b>text</b>")?.html, "<b>text</b>")
    }

    func testClipboardContentIsBounded() {
        XCTAssertTrue(clipboardTextFits(String(repeating: "x", count: maximumClipboardContentBytes)))
        XCTAssertFalse(clipboardTextFits(String(repeating: "x", count: maximumClipboardContentBytes + 1)))
        XCTAssertNil(RichClipboardContent(
            text: String(repeating: "x", count: maximumClipboardContentBytes),
            html: "<b>x</b>"
        ))
    }

    func testEmptyPayloadIsRejected() {
        XCTAssertFalse(clipboardTextFits(""))
        XCTAssertNil(RichClipboardContent(text: "", html: nil))
    }

    func testMultibyteUtf8ByteBounds() {
        // Emojis are 4 bytes each in UTF-8
        let emoji = "🔒" // 4 bytes
        XCTAssertEqual(emoji.utf8.count, 4)
        let fits = String(repeating: emoji, count: maximumClipboardContentBytes / 4)
        XCTAssertTrue(clipboardTextFits(fits))

        let overflows = fits + "x"
        XCTAssertFalse(clipboardTextFits(overflows))
    }

    func testRichClipboardRoundtripAndValidation() throws {
        let original = RichClipboardContent(text: "SYNQ clipboard", html: "<p>SYNQ <b>clipboard</b></p>")!
        let encoded = try JSONEncoder().encode(original)
        let decoded = try JSONDecoder().decode(RichClipboardContent.self, from: encoded)

        XCTAssertEqual(original, decoded)
        XCTAssertEqual(decoded.validated(), original)
    }
}

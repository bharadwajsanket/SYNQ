import XCTest
@testable import SYNQMac

final class QuickActionsTests: XCTestCase {
    func testEncryptedSequenceRejectsReplayIndependentlyOfOuterMessageID() {
        var sequence = QuickRequestSequence()
        XCTAssertTrue(sequence.accept(feature: "media", sequence: 4))
        XCTAssertFalse(sequence.accept(feature: "media", sequence: 4))
        XCTAssertFalse(sequence.accept(feature: "media", sequence: 3))
        XCTAssertTrue(sequence.accept(feature: "links", sequence: 1))
        XCTAssertTrue(sequence.accept(feature: "media", sequence: 5))
        XCTAssertFalse(sequence.accept(feature: "other", sequence: 6))
        XCTAssertFalse(sequence.accept(feature: "media", sequence: Int.max))
    }
    func testBrowserLinkValidation() {
        for link in ["https://example.com/path?q=one#two", "http://localhost:8080/", "https://[::1]/"] {
            XCTAssertEqual(validatedWebLink(link), link)
        }
        XCTAssertEqual(validatedWebLink(" https://example.com\n"), "https://example.com")
        for link in ["", "file:///etc/passwd", "javascript:alert(1)", "tel:+12345678", "bridgey://call", "https://",
                     "https://user:pass@example.com", "https://example.com:0", "https://example.com:65536",
                     "https://exa mple.com", "https://example.com/\nfoo", "https://example.com/\\evil",
                     "https://example.com/\u{0}", "https://example.com/" + String(repeating: "a", count: 4096)] {
            XCTAssertNil(validatedWebLink(link), link)
        }
    }

    func testMediaCommandsCannotContainRemoteCode() {
        XCTAssertEqual(mediaCommand("toggle", value: ""), "playpause")
        XCTAssertEqual(mediaCommand("pause", value: ""), "pause")
        XCTAssertEqual(mediaCommand("next", value: ""), "next track")
        XCTAssertEqual(mediaCommand("previous", value: ""), "previous track")
        XCTAssertEqual(mediaCommand("seek", value: "123"), "set player position to 123")
        XCTAssertEqual(mediaCommand("volume", value: "100"), "set sound volume to 100")
        XCTAssertNil(mediaCommand("volume", value: "101"))
        XCTAssertNil(mediaCommand("seek", value: "-1"))
        XCTAssertNil(mediaCommand("seek", value: "604801"))
        XCTAssertNil(mediaCommand("seek", value: "1\n do shell script \"open /tmp\""))
        XCTAssertNil(mediaCommand("do shell script", value: "anything"))
    }

    func testArtworkDescriptorBoundsAndHexValidation() {
        XCTAssertEqual(mediaArtworkData("«data PNGf89504E47»"), Data([0x89, 0x50, 0x4e, 0x47]))
        XCTAssertNil(mediaArtworkData("not data"))
        XCTAssertNil(mediaArtworkData("«data PNGfXX»"))
        XCTAssertNil(mediaArtworkData("«data PNGf123»"))
        XCTAssertNil(mediaArtworkData("«data PNGf»"))
        XCTAssertNil(mediaArtworkData("«data PNGf" + String(repeating: "F", count: 2_100_000) + "»"))
    }

    @MainActor func testDisabledLinksRejectWithoutOpeningOrRetaining() {
        let actions = QuickActions()
        var replies: [[String: Any]] = []
        actions.send = { _, payload in replies.append(payload); return true }
        actions.receive("quick.request", payload: ["version": 1, "requestId": UUID().uuidString,
            "feature": "links", "action": "offer", "value": "https://example.com"])
        XCTAssertNil(actions.receivedLink)
        XCTAssertEqual(replies.first?["accepted"] as? Bool, false)
        actions.reset()
        XCTAssertNil(actions.status)
    }

    func testNewCapabilitiesFailClosedForOldPeers() {
        XCTAssertFalse(featureEnabledByLegacyPeer(.links))
        XCTAssertFalse(featureEnabledByLegacyPeer(.media))
    }

    @MainActor func testSmartLinkNeutralDeliveredStatusAndOpenedEvent() {
        let actions = QuickActions()
        actions.available = { _ in true }
        let pendingId = UUID().uuidString.lowercased()
        actions.pending = pendingId

        // 1. Android accepts link offer
        actions.receive("quick.result", payload: [
            "version": 1,
            "requestId": pendingId,
            "feature": "links",
            "accepted": true
        ])
        XCTAssertEqual(actions.status, "Link sent to Android")

        // 2. Android reports that the link was opened
        actions.receive("quick.event", payload: [
            "version": 1,
            "requestId": UUID().uuidString.lowercased(),
            "feature": "links",
            "event": "opened"
        ])
        XCTAssertEqual(actions.status, "Link opened on Android")
    }

    func testMediaPlayerPropertiesAndDefaults() {
        XCTAssertEqual(MediaPlayer.allCases, [.auto, .music, .spotify, .off])
        XCTAssertEqual(MediaPlayer.auto.title, "Auto-detect")
        XCTAssertNil(MediaPlayer.auto.bundleID)
        XCTAssertEqual(MediaPlayer.music.title, "Music")
        XCTAssertEqual(MediaPlayer.music.bundleID, "com.apple.Music")
        XCTAssertEqual(MediaPlayer.spotify.title, "Spotify")
        XCTAssertEqual(MediaPlayer.spotify.bundleID, "com.spotify.client")
        XCTAssertEqual(MediaPlayer.off.title, "Off")
        XCTAssertNil(MediaPlayer.off.bundleID)
    }

    func testMediaSnapshotPayloadFormatting() {
        let source1 = MediaSourceItem(
            id: "com.google.Chrome",
            name: "Chrome",
            title: "YouTube Video",
            artist: "Creator",
            playing: true,
            position: 12,
            duration: 180,
            volume: 75
        )
        let source2 = MediaSourceItem(
            id: "com.apple.Music",
            name: "Apple Music",
            title: "Album Song",
            artist: "Band",
            playing: false,
            position: 0,
            duration: 210,
            volume: 60
        )
        let snapshot = MediaSnapshot(
            title: "YouTube Video",
            artist: "Creator",
            appName: "Chrome",
            playing: true,
            position: 12,
            duration: 180,
            volume: 75,
            detail: "",
            artwork: "base64artwork",
            selectedSourceId: "com.google.Chrome",
            sources: [source1, source2]
        )
        let payload = snapshot.payload(player: .auto)
        XCTAssertEqual(payload["version"] as? Int, 1)
        XCTAssertEqual(payload["player"] as? String, "Chrome")
        XCTAssertEqual(payload["title"] as? String, "YouTube Video")
        XCTAssertEqual(payload["artist"] as? String, "Creator")
        XCTAssertEqual(payload["playing"] as? Bool, true)
        XCTAssertEqual(payload["position"] as? Int, 12)
        XCTAssertEqual(payload["duration"] as? Int, 180)
        XCTAssertEqual(payload["volume"] as? Int, 75)
        XCTAssertEqual(payload["artwork"] as? String, "base64artwork")
        XCTAssertEqual(payload["selectedSourceId"] as? String, "com.google.Chrome")
        let sources = payload["sources"] as? [[String: Any]]
        XCTAssertEqual(sources?.count, 2)
        XCTAssertEqual(sources?[0]["name"] as? String, "Chrome")
        XCTAssertEqual(sources?[1]["name"] as? String, "Apple Music")
    }

    @MainActor func testMediaControllerOffPlayerReturnsNilResolution() {
        let controller = MediaController()
        controller.player = .off
        XCTAssertNil(controller.resolvedPlayer())
    }

    @MainActor func testMediaFeatureEnabledByDefaultInSettings() {
        let settings = SYNQSettings()
        XCTAssertEqual(settings.globalFeatures[.media], true)
    }

    @MainActor func testMediaPermissionToggleGatesCommands() {
        let controller = MediaController()
        var allowed = false
        controller.allowed = { allowed }

        var executed = false
        controller.command(action: "toggle", value: "") { success in
            executed = success
        }
        XCTAssertFalse(executed, "Commands must be rejected when media permission is false")

        allowed = true
        controller.command(action: "selectSource", value: "com.google.Chrome") { success in
            executed = success
        }
        XCTAssertTrue(executed, "selectSource should succeed when allowed")
        XCTAssertEqual(controller.manualSelectedSourceId, "com.google.Chrome")

        controller.command(action: "selectSource", value: "auto") { _ in }
        XCTAssertNil(controller.manualSelectedSourceId, "Selecting auto clears manual selection")
    }

    @MainActor func testManualSourceSelectionPersistsAndDisappearsCleanly() {
        let controller = MediaController()
        controller.allowed = { true }
        controller.selectSource(id: "com.pauldavis.crest")
        XCTAssertEqual(controller.manualSelectedSourceId, "com.pauldavis.crest")

        controller.selectSource(id: "auto")
        XCTAssertNil(controller.manualSelectedSourceId)
    }

    func testMediaSnapshotMissingArtworkAndDurationHandled() {
        let snapshot = MediaSnapshot(
            title: "Stream Track",
            artist: "Live Radio",
            appName: "Safari",
            playing: true,
            position: 0,
            duration: 0,
            volume: 100,
            detail: "",
            artwork: nil
        )
        let payload = snapshot.payload(player: .auto)
        XCTAssertNil(payload["artwork"])
        XCTAssertEqual(payload["duration"] as? Int, 0)
        XCTAssertEqual(payload["player"] as? String, "Safari")
    }
}

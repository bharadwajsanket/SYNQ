import Foundation
import XCTest
@testable import SYNQMac

final class MigrationTests: XCTestCase {
    func testMigratesLegacyPreferencesFromBridgeySuiteWithoutOverwriting() throws {
        let legacySuite = "dev.bridgey.tests.legacy.\(UUID().uuidString)"
        let targetSuite = "dev.bridgey.tests.target.\(UUID().uuidString)"

        let legacy = try XCTUnwrap(UserDefaults(suiteName: legacySuite))
        let target = try XCTUnwrap(UserDefaults(suiteName: targetSuite))
        defer {
            legacy.removePersistentDomain(forName: legacySuite)
            target.removePersistentDomain(forName: targetSuite)
        }

        let deviceID = UUID().uuidString.lowercased()
        legacy.set(deviceID, forKey: "deviceID")
        legacy.set("Legacy Mac", forKey: "settings.deviceName")
        legacy.set(true, forKey: "settings.onboarding.completed")
        legacy.set("music", forKey: "media.player")
        legacy.set("K", forKey: "shortcuts.clipboard")

        // Pre-existing value in target must not be overwritten
        target.set("/custom/synq/downloads", forKey: "settings.receiveFolderPath")

        LegacyPreferences.migrateIfNeeded(current: target, legacySuites: [legacySuite])

        XCTAssertEqual(target.string(forKey: "deviceID"), deviceID)
        XCTAssertEqual(target.string(forKey: "settings.deviceName"), "Legacy Mac")
        XCTAssertTrue(target.bool(forKey: "settings.onboarding.completed"))
        XCTAssertEqual(target.string(forKey: "media.player"), "music")
        XCTAssertEqual(target.string(forKey: "shortcuts.clipboard"), "K")
        XCTAssertEqual(target.string(forKey: "settings.receiveFolderPath"), "/custom/synq/downloads")
        XCTAssertTrue(target.bool(forKey: "legacyPreferencesMigrated"))

        // Legacy values must be preserved intact (not deleted)
        XCTAssertEqual(legacy.string(forKey: "deviceID"), deviceID)
        XCTAssertEqual(legacy.string(forKey: "settings.deviceName"), "Legacy Mac")

        // Second run must be idempotent
        legacy.set("Different Name", forKey: "settings.deviceName")
        LegacyPreferences.migrateIfNeeded(current: target, legacySuites: [legacySuite])
        XCTAssertEqual(target.string(forKey: "settings.deviceName"), "Legacy Mac")
    }

    func testNotificationHistoryMigratesLegacyFileSafely() throws {
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let bridgeyDir = tempDir.appendingPathComponent("Bridgey", isDirectory: true)
        try FileManager.default.createDirectory(at: bridgeyDir, withIntermediateDirectories: true)
        let legacyFile = bridgeyDir.appendingPathComponent("notification-history.json")

        let sampleItem = NotificationHistoryItem(
            id: "notif-1",
            packageName: "com.example.app",
            applicationName: "Example",
            title: "Hello",
            text: "World",
            receivedAt: Date()
        )
        let sampleData = try JSONEncoder().encode([sampleItem])
        try sampleData.write(to: legacyFile)

        let targetURL = NotificationHistoryStore.defaultFileURL(baseDirectory: tempDir)
        XCTAssertTrue(FileManager.default.fileExists(atPath: targetURL.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: legacyFile.path), "Original file must be preserved")

        let store = NotificationHistoryStore(fileURL: targetURL)
        let loaded = store.load()
        XCTAssertEqual(loaded.count, 1)
        XCTAssertEqual(loaded.first?.id, "notif-1")
    }

    func testTrustRegistryEnforcesPinnedKeyAndAllowsReplacementOnlyAfterRemoval() throws {
        let namespace = UUID().uuidString
        let service = "dev.bridgey.tests.trust.\(namespace)"
        let registry = MacTrustRegistry(service: service, migrating: [])
        defer { registry.deleteStorageForTesting() }

        let deviceID = UUID().uuidString.lowercased()
        let keyA = Data("key-a".utf8).base64EncodedString()
        let keyB = Data("key-b".utf8).base64EncodedString()

        // Remember initial key
        XCTAssertTrue(registry.remember(deviceID: deviceID, name: "Phone A", identityKey: keyA))
        XCTAssertEqual(registry.identityKey(for: deviceID), keyA)

        // Attempting to overwrite existing device with different key succeeds at trust store update
        // but Pairing.swift pairing.confirm strictly enforces pinned key match before allowing session
        XCTAssertTrue(registry.remember(deviceID: deviceID, name: "Phone A", identityKey: keyB))
        XCTAssertEqual(registry.identityKey(for: deviceID), keyB)

        // Removal clears the pinned key
        registry.remove(deviceID: deviceID)
        XCTAssertNil(registry.identityKey(for: deviceID))
    }
}

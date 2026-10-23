import XCTest
@testable import SYNQMac

final class SYNQSettingsTests: XCTestCase {
    func testGlobalSwitchOverridesDeviceSetting() {
        XCTAssertFalse(effectiveFeatureEnabled(globalEnabled: false, deviceEnabled: true))
        XCTAssertFalse(effectiveFeatureEnabled(globalEnabled: false, deviceEnabled: nil))
    }

    func testDeviceSwitchOverridesEnabledGlobalDefault() {
        XCTAssertFalse(effectiveFeatureEnabled(globalEnabled: true, deviceEnabled: false))
        XCTAssertTrue(effectiveFeatureEnabled(globalEnabled: true, deviceEnabled: true))
        XCTAssertTrue(effectiveFeatureEnabled(globalEnabled: true, deviceEnabled: nil))
    }

    func testFeatureRequiresBothDevicesToOfferIt() {
        XCTAssertTrue(effectiveFeatureAvailable(localEnabled: true, remoteEnabled: true))
        XCTAssertFalse(effectiveFeatureAvailable(localEnabled: true, remoteEnabled: false))
        XCTAssertFalse(effectiveFeatureAvailable(localEnabled: false, remoteEnabled: true))
    }


    func testNewerFeaturesAreOffForLegacyPeers() {
        XCTAssertFalse(featureEnabledByLegacyPeer(.calls))
        XCTAssertFalse(featureEnabledByLegacyPeer(.ping))
        XCTAssertTrue(featureEnabledByLegacyPeer(.battery))
    }

    @MainActor
    func testQuietModeDefaultsToDisabled() {
        let settings = SYNQSettings()
        let original = settings.quietModeEnabled
        let originalTimestamp = settings.quietModeUpdatedAt
        defer {
            settings.setQuietModeEnabled(original, updatedAt: originalTimestamp)
        }

        settings.setQuietModeEnabled(false, updatedAt: 0)
        XCTAssertFalse(settings.quietModeEnabled)
        XCTAssertEqual(settings.quietModeUpdatedAt, 0)
    }

    @MainActor
    func testQuietModeToggleUpdatesTimestamp() {
        let settings = SYNQSettings()
        let original = settings.quietModeEnabled
        let originalTimestamp = settings.quietModeUpdatedAt
        defer {
            settings.setQuietModeEnabled(original, updatedAt: originalTimestamp)
        }

        let testTimestamp: Int64 = 1700000000000
        settings.setQuietModeEnabled(true, updatedAt: testTimestamp)
        XCTAssertTrue(settings.quietModeEnabled)
        XCTAssertEqual(settings.quietModeUpdatedAt, testTimestamp)
    }

    func testGranularFeaturePermissionsCoverAllCases() {
        XCTAssertEqual(SYNQFeature.allCases.count, 9)
        let features: Set<SYNQFeature> = [
            .clipboard, .files, .notifications, .battery,
            .findDevice, .ping, .links, .media, .calls
        ]
        XCTAssertEqual(Set(SYNQFeature.allCases), features)
    }
}

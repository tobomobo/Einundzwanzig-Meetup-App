import XCTest

final class AppSmokeTests: XCTestCase {
    @MainActor func testIdentityWalletPersistenceAndOfflineFailure() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--reset-ui-test-data", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        let nickname = app.textFields["profile.nickname"]
        XCTAssertTrue(nickname.waitForExistence(timeout: 15))
        nickname.tap(); nickname.typeText("Fixture Pleb")
        tap(app.buttons["onboarding.continue"], in: app)
        tap(app.buttons["fixture.open"], in: app)
        tap(app.buttons["fixture.identity"], in: app)
        fill(app.secureTextFields["identity.newPassword"], "fixture-password-2026", in: app)
        fill(app.secureTextFields["identity.confirmPassword"], "fixture-password-2026", in: app)
        tap(app.buttons["identity.create"], in: app)
        XCTAssertTrue(app.buttons["identity.lock"].waitForExistence(timeout: 60), "Creating a real local vault must reach the unlocked state")
        back(app)
        let collect = app.buttons["fixture.collect"]
        XCTAssertTrue(wait(collect, predicate: "enabled == true", timeout: 20))
        tap(collect, in: app)
        XCTAssertTrue(wait(app.staticTexts["fixture.status"], predicate: "label CONTAINS 'Fixture collected with signed claim'", timeout: 30))
        tap(app.buttons["fixture.verify"], in: app)
        XCTAssertTrue(wait(app.staticTexts["fixture.status"], predicate: "label CONTAINS 'Stored signature and collector claim verified'", timeout: 15))
        tap(app.buttons["fixture.wallet"], in: app)
        XCTAssertTrue(app.staticTexts["Fixture City, AT"].waitForExistence(timeout: 15))
        tap(app.buttons["Synchronize encrypted wallet"], in: app)
        XCTAssertTrue(app.staticTexts["No relay completed the request."].waitForExistence(timeout: 15))
        app.terminate()

        // Relaunch without the reset argument: use the actual persisted
        // simulator Keychain vault and encrypted wallet, not seeded app state.
        app.launchArguments = ["--ui-testing", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        tap(app.buttons["fixture.open"], in: app)
        tap(app.buttons["fixture.identity"], in: app)
        fill(app.secureTextFields["identity.password"], "wrong-password", in: app)
        tap(app.buttons["identity.unlock"], in: app)
        XCTAssertTrue(app.staticTexts["The password is incorrect or the encrypted data is damaged."].waitForExistence(timeout: 60))
        XCTAssertFalse(app.buttons["identity.lock"].exists, "Wrong password must not unlock the signer")
        app.terminate(); app.launch()
        tap(app.buttons["fixture.open"], in: app)
        tap(app.buttons["fixture.identity"], in: app)
        fill(app.secureTextFields["identity.password"], "fixture-password-2026", in: app)
        tap(app.buttons["identity.unlock"], in: app)
        XCTAssertTrue(app.buttons["identity.lock"].waitForExistence(timeout: 60))
        back(app)
        XCTAssertTrue(wait(app.staticTexts["fixture.walletCount"], predicate: "label == 'Wallet count: 1'", timeout: 20))
        tap(app.buttons["fixture.verify"], in: app)
        XCTAssertTrue(wait(app.staticTexts["fixture.status"], predicate: "label CONTAINS 'Stored signature and collector claim verified'", timeout: 15))
        tap(app.buttons["fixture.offlinePortal"], in: app)
        XCTAssertTrue(wait(app.staticTexts["fixture.status"], predicate: "label CONTAINS 'Offline request failed closed'", timeout: 20))
    }
    @MainActor func testProductionSettingsRoutesAndEditorDraftSurviveVenueRoundtrip() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--reset-ui-test-data", "--seed-navigation-fixtures", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launchEnvironment["TZ"] = "America/Los_Angeles"
        app.launch()
        fill(app.textFields["profile.nickname"], "Navigation Pleb", in: app)
        tap(app.buttons["onboarding.continue"], in: app)
        tap(app.buttons["root.settings"], in: app)
        tap(app.buttons["settings.profile"], in: app)
        fill(app.textFields["profile.fullName"], "Unsaved full name", in: app)
        tap(app.buttons["Choose favorite meetups"], in: app)
        tap(app.buttons.containing(.staticText, identifier: "Fixture Meetup").firstMatch, in: app)
        XCTAssertTrue(app.navigationBars["Fixture City"].waitForExistence(timeout: 10), "Value-based detail navigation must resolve inside Settings")
        back(app); back(app)
        XCTAssertEqual(app.textFields["profile.fullName"].value as? String, "Unsaved full name")
        back(app); tap(app.buttons["Done"], in: app)
        tap(app.tabBars.buttons["Events"], in: app)
        tap(app.buttons["Nostr calendar and publishing"], in: app)
        tap(app.buttons.containing(.staticText, identifier: "Fixture civil date").firstMatch, in: app)
        XCTAssertTrue(wait(app.staticTexts["calendar.civilDate"], predicate: "label CONTAINS '25'", timeout: 10), "The UTC civil day must remain October 25 west of UTC")
        tap(app.buttons["Edit event"], in: app)
        replace(app.textFields["calendar.title"], "Edited fixture title", in: app)
        tap(app.buttons["calendar.chooseLocation"], in: app)
        replace(app.textFields["venue.name"], "New offline venue", in: app)
        replace(app.textFields["venue.latitude"], "49.1", in: app)
        replace(app.textFields["venue.longitude"], "16.4", in: app)
        back(app)
        XCTAssertEqual(app.textFields["calendar.title"].value as? String, "Edited fixture title")
        XCTAssertEqual(app.textFields["calendar.location"].value as? String, "New offline venue")
        app.terminate()
        app.launchArguments = ["--ui-testing", "--open-bitcoin", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        tap(app.buttons["Converter"], in: app)
        XCTAssertTrue(app.navigationBars["Converter"].waitForExistence(timeout: 10), "Widget Bitcoin modal must register value-based routes")
    }

    @MainActor func testProductionBackupReviewRestoreRelaunchAndExport() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--reset-ui-test-data", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        fill(app.textFields["profile.nickname"], "Backup Pleb", in: app)
        tap(app.buttons["onboarding.continue"], in: app)
        tap(app.buttons["root.settings"], in: app); tap(app.buttons["settings.identity"], in: app)
        fill(app.secureTextFields["identity.backupPassword"], "fixture-backup-password", in: app)
        fill(app.secureTextFields["identity.restoreVaultPassword"], "fixture-restored-vault", in: app)
        tap(app.buttons["Paste backup text"], in: app)
        // Public scalar 1 only; tests never accept or reveal a user's secret.
        let backup: [String: Any] = ["version": 5, "user": ["nickname": "Restored UI Pleb", "fullName": "Restored full name"], "badges": [], "nostr": [
            "nsec": "nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsmhltgl",
            "npub": "npub10xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqpkge6d",
            "priv_hex": String(repeating: "0", count: 63) + "1", "has_key": true, "signing_mode": "local"]]
        fill(app.textViews["identity.backupText"], String(decoding: try JSONSerialization.data(withJSONObject: backup), as: UTF8.self), in: app)
        tap(app.buttons["identity.reviewBackup"], in: app)
        XCTAssertTrue(app.staticTexts["identity.backupPreview"].waitForExistence(timeout: 20))
        tap(app.buttons["identity.confirmRestore"], in: app)
        XCTAssertTrue(app.buttons["identity.lock"].waitForExistence(timeout: 60))
        app.terminate(); app.launchArguments = ["--ui-testing", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]; app.launch()
        tap(app.buttons["root.settings"], in: app); tap(app.buttons["settings.profile"], in: app)
        XCTAssertEqual(app.textFields["profile.fullName"].value as? String, "Restored full name")
        back(app); tap(app.buttons["settings.identity"], in: app)
        fill(app.secureTextFields["identity.password"], "fixture-restored-vault", in: app)
        tap(app.buttons["identity.unlock"], in: app)
        XCTAssertTrue(app.buttons["identity.lock"].waitForExistence(timeout: 60))
        fill(app.secureTextFields["identity.currentVaultPassword"], "fixture-restored-vault", in: app)
        fill(app.secureTextFields["identity.exportPassword"], "fixture-export-password", in: app)
        tap(app.buttons["identity.exportBackup"], in: app)
        let saveReview = app.buttons.matching(NSPredicate(format: "label IN %@", ["Save", "Export", "Move"])).firstMatch
        XCTAssertTrue(saveReview.waitForExistence(timeout: 60), "Production encrypted exporter must reach the system save review")
        app.buttons["Cancel"].firstMatch.tap()
    }

    @MainActor private func replace(_ field: XCUIElement, _ text: String, in app: XCUIApplication) {
        reveal(field, in: app)
        field.tap()
        let existing = field.value as? String ?? ""
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: existing.count) + text)
    }
    @MainActor private func reveal(_ element: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<8 where !element.isHittable { app.swipeUp() }
        for _ in 0..<8 where !element.isHittable { app.swipeDown() }
        XCTAssertTrue(element.waitForExistence(timeout: 15)); XCTAssertTrue(element.isHittable)
    }
    @MainActor private func fill(_ field: XCUIElement, _ text: String, in app: XCUIApplication) {
        reveal(field, in: app)
        field.tap(); field.typeText(text)
    }
    @MainActor private func tap(_ element: XCUIElement, in app: XCUIApplication) {
        reveal(element, in: app)
        XCTAssertTrue(element.isHittable); element.tap()
    }
    @MainActor private func back(_ app: XCUIApplication) { app.navigationBars.buttons.element(boundBy: 0).tap() }
    @MainActor private func wait(_ element: XCUIElement, predicate: String, timeout: TimeInterval) -> Bool {
        XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: predicate), object: element)], timeout: timeout) == .completed
    }
}

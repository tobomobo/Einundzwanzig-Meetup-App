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
    @MainActor private func fill(_ field: XCUIElement, _ text: String, in app: XCUIApplication) {
        XCTAssertTrue(field.waitForExistence(timeout: 15))
        for _ in 0..<6 where !field.isHittable { app.swipeUp() }
        field.tap(); field.typeText(text)
    }
    @MainActor private func tap(_ element: XCUIElement, in app: XCUIApplication) {
        XCTAssertTrue(element.waitForExistence(timeout: 20))
        for _ in 0..<6 where !element.isHittable { app.swipeUp() }
        XCTAssertTrue(element.isHittable); element.tap()
    }
    @MainActor private func back(_ app: XCUIApplication) { app.navigationBars.buttons.element(boundBy: 0).tap() }
    @MainActor private func wait(_ element: XCUIElement, predicate: String, timeout: TimeInterval) -> Bool {
        XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: predicate), object: element)], timeout: timeout) == .completed
    }
}

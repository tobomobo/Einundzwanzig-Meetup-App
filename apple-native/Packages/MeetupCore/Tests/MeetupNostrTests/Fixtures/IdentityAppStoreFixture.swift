import Foundation
import Observation
import Testing
import MeetupCore
import MeetupNostr
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

enum ColorScheme { case light, dark }
@MainActor final class NativeMediaPlayer {}
enum NativeBackgroundRefresh { static func saveWidget(snapshot: BitcoinSnapshot) async {} }
@MainActor enum UITestFixtureMode {
    static let enabled = false
    static var directory = URL(fileURLWithPath: "/tmp/app-store-continuity-unprepared", isDirectory: true)
    static func prepare() throws {}
    static func relay() -> NostrRelayClient { NostrRelayClient { _ in throw URLError(.notConnectedToInternet) } }
    static var http: any ProductHTTPTransport { IdentityOfflineHTTP() }
}
private struct IdentityOfflineHTTP: ProductHTTPTransport {
    func send(_ request: URLRequest) async throws -> ProductHTTPResponse { throw URLError(.notConnectedToInternet) }
}
enum IdentityStorageFailure: Error { case read, write, delete }
final class IdentityKeychainFixture: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String: Data] = [:]
    private var failedDeletePrefix: String?
    private var failedWrite: String?
    func reset() { lock.withLock { values = [:]; failedDeletePrefix = nil; failedWrite = nil } }
    func failDeletes(_ prefix: String?) { lock.withLock { failedDeletePrefix = prefix } }
    func failWrites(_ account: String?) { lock.withLock { failedWrite = account } }
    func read(_ key: String) -> Data? { lock.withLock { values[key] } }
    func write(_ data: Data, _ key: String) throws { try lock.withLock { if failedWrite == key { throw IdentityStorageFailure.write }; values[key] = data } }
    func delete(_ key: String) throws { try lock.withLock {
        if let failedDeletePrefix, key.hasPrefix(failedDeletePrefix) { throw IdentityStorageFailure.delete }
        values.removeValue(forKey: key)
    } }
    func flutter() -> [String: Data] { lock.withLock { Dictionary(uniqueKeysWithValues: values.filter { $0.key.hasPrefix("flutter/") }.map { (String($0.key.dropFirst(8)), $0.value) }) } }
}
enum KeychainStore {
    static let fixture = IdentityKeychainFixture()
    static func read(account: String) throws -> Data? { fixture.read(account) }
    static func write(_ data: Data, account: String) throws { try fixture.write(data, account) }
    static func delete(account: String) throws { try fixture.delete(account) }
    static func readFlutter(account: String) throws -> Data? { fixture.read("flutter/" + account) }
    static func readAllFlutter() throws -> [String: Data] { fixture.flutter() }
    static func deleteFlutter(account: String) throws { try fixture.delete("flutter/" + account) }
}

@Suite(.serialized) @MainActor struct IdentityAppStoreContinuityTests {
    let vaultPassword = "fixture-native-vault"
    func fresh() throws -> UserDefaults {
        KeychainStore.fixture.reset()
        UITestFixtureMode.directory = FileManager.default.temporaryDirectory.appendingPathComponent("app-continuity-" + UUID().uuidString, isDirectory: true)
        return try #require(UserDefaults(suiteName: "app-continuity-" + UUID().uuidString))
    }
    func fixture(_ name: String, _ ext: String) throws -> Data {
        try Data(contentsOf: #require(Bundle.module.url(forResource: name, withExtension: ext, subdirectory: "Fixtures/BackupInterop")))
    }
    func password() throws -> String { try #require(JSONDecoder().decode([String: String].self, from: fixture("provenance", "json"))["password"]) }
    func exported(_ app: AppStore) async throws -> BackupDocument {
        let payload = try await app.identity.exportBackup(password: vaultPassword, vaultPassword: vaultPassword)
        return try BackupDocument.decode(BackupEncryption.decrypt(payload, password: vaultPassword))
    }
    @Test func actualFlutterImportRelaunchAndExportPreserveWholePublicUserAndWallet() async throws {
        let defaults = try fresh(), original = try BackupDocument.decode(fixture("flutter-v5", "json"))
        let app = AppStore(legacyDefaults: defaults)
        try await app.identity.restoreBackup(String(decoding: fixture("flutter-v5", "21bkp"), as: UTF8.self), password: password(), vaultPassword: vaultPassword)
        #expect(app.profile.fullName == "Änne 😀")
        #expect(app.profile.telegramHandle == "synthetic_test")
        #expect(app.profile.homeMeetupID == "Aschaffenburg")
        #expect(app.profile.favoriteMeetupIDs == ["Aschaffenburg", "Würzburg"])
        #expect(app.preferences.favoriteIDs == ["Aschaffenburg", "Würzburg"])
        #expect(app.badgeWallet.badges.count == 2)
        await app.identity.lock(); await app.synchronizeIdentity()
        let relaunched = AppStore(legacyDefaults: defaults)
        #expect(relaunched.profile == app.profile)
        #expect(relaunched.identity.state == .locked)
        try await relaunched.identity.unlock(password: vaultPassword); await relaunched.synchronizeIdentity()
        let backup = try await exported(relaunched)
        var expected = original.fields["user"]!.object!
        for name in ["isAdmin", "isAdminVerified", "isNostrVerified", "adminViaPortal", "adminViaVouch", "adminViaSeed"] { expected[name] = .bool(false) }
        #expect(backup.fields["user"] == .object(expected))
        #expect(backup.fields["badges"]?.array?.count == 2)
        #expect(relaunched.badgeWallet.badges.first(where: { $0.id == "interop-event" })?.isEvent == true)
        if let output = ProcessInfo.processInfo.environment["APP_STORE_BACKUP_INTEROP_OUTPUT"] {
            let payload = try await relaunched.identity.exportBackup(password: password(), vaultPassword: vaultPassword)
            try payload.write(toFile: output, atomically: true, encoding: .utf8)
        }
    }
    @Test func additiveUnknownFieldsAndPrecisionSurviveActualAppPersistence() async throws {
        let defaults = try fresh(), app = AppStore(legacyDefaults: defaults)
        var document = try BackupDocument.decode(fixture("flutter-v5", "json"))
        var user = document.fields["user"]!.object!
        user["futurePublic"] = try JSONDecoder().decode(BackupValue.self, from: Data("{\"identifier\":9007199254740993,\"name\":\"kept 😀\",\"auth_token\":\"drop\",\"isAdmin\":true}".utf8))
        user["twitterHandle"] = .string("device-twitter"); document.fields["user"] = .object(user)
        try await app.identity.restoreBackup(String(decoding: document.encoded(), as: UTF8.self), password: vaultPassword, vaultPassword: vaultPassword)
        var incoming = user; incoming["nickname"] = .string("Incoming"); incoming["fullName"] = .string("Do not overwrite")
        incoming["telegramHandle"] = .string("incoming-telegram"); incoming["favoriteMeetupIds"] = .array([.string("Berlin")])
        incoming["futurePublic"] = .object(["name": .string("incoming"), "extra": .string("new field")])
        document.fields["user"] = .object(incoming)
        try await app.identity.restoreBackup(String(decoding: document.encoded(), as: UTF8.self), password: vaultPassword, vaultPassword: vaultPassword)
        let relaunch = AppStore(legacyDefaults: defaults)
        try await relaunch.identity.unlock(password: vaultPassword); await relaunch.synchronizeIdentity()
        let out = try await exported(relaunch), fields = out.fields["user"]!.object!, future = fields["futurePublic"]!.object!
        #expect(relaunch.profile.nickname == "Interop Ada")
        #expect(relaunch.profile.fullName == "Änne 😀")
        #expect(relaunch.profile.telegramHandle == "synthetic_test")
        #expect(relaunch.profile.favoriteMeetupIDs == ["Aschaffenburg", "Würzburg", "Berlin"])
        #expect(future["name"] == .string("kept 😀")); #expect(future["extra"] == .string("new field"))
        #expect(future["auth_token"] == nil); #expect(future["isAdmin"] == .bool(false))
        #expect(future["identifier"] == .decimal(Decimal(string: "9007199254740993")!))
        #expect(String(decoding: try out.encoded(), as: UTF8.self).contains("9007199254740993"))
    }
    @Test func oldThreeFieldProfileRemainsReadableAndEditorPreservesImportedOptionalFields() async throws {
        let defaults = try fresh()
        let oldData = Data("{\"nickname\":\"Older\",\"npub\":\"\",\"onboarded\":true}".utf8)
        let old = try JSONDecoder().decode(LocalProfile.self, from: oldData)
        #expect(old.fullName.isEmpty); #expect(old.favoriteMeetupIDs.isEmpty)
        #if os(macOS)
        // Seed the actual macOS profile file with the historical three-field format.
        try FileManager.default.createDirectory(at: UITestFixtureMode.directory, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        try oldData.write(to: UITestFixtureMode.directory.appendingPathComponent("public-profile.json"), options: .atomic)
        #else
        // iOS and the portable Linux adapter exercise the Keychain profile branch.
        try KeychainStore.write(oldData, account: "local-public-profile-v1")
        #endif
        let app = AppStore(legacyDefaults: defaults)
        #expect(app.profile.nickname == "Older")
        #expect(app.saveProfile(nickname: "Edited", npub: "", fullName: "Full name", homeMeetupID: "Vienna", favoriteMeetupIDs: ["Vienna"], telegramHandle: "telegram", twitterHandle: "twitter"))
        #expect(app.saveProfile(nickname: "Edited again", npub: ""))
        let relaunch = AppStore(legacyDefaults: defaults)
        #expect(relaunch.profile.fullName == "Full name")
        #expect(relaunch.profile.twitterHandle == "twitter")
        #expect(relaunch.preferences.favoriteIDs == ["Vienna"])
        #if os(macOS)
        #expect(try KeychainStore.read(account: "local-public-profile-v1") == nil)
        #else
        #expect(!FileManager.default.fileExists(atPath: UITestFixtureMode.directory.appendingPathComponent("public-profile.json").path))
        #endif
    }
    private func seedFlutter(_ defaults: UserDefaults) throws -> (BackupDocument, Data, String) {
        let document = try BackupDocument.decode(fixture("flutter-v5", "json")), block = document.fields["nostr"]!.object!
        for (account, value) in [("nostr_nsec_key", block["nsec"]!.string!), ("nostr_npub_key", block["npub"]!.string!), ("nostr_priv_hex", block["priv_hex"]!.string!)] {
            try KeychainStore.fixture.write(Data(value.utf8), "flutter/" + account)
        }
        let wallet = try JSONDecoder().decode([String: String].self, from: fixture("flutter-wallet-legacy", "json"))
        let blob = wallet["blob"]!, wrapped = Data([0, 255, 16, 32, 128, 1])
        try KeychainStore.fixture.write(Data(String(repeating: "41", count: 32).utf8), "flutter/badge_encryption_key")
        try KeychainStore.fixture.write(Data(wallet["keyHex"]!.utf8), "flutter/badge_encryption_key_BACKUP")
        try KeychainStore.fixture.write(wrapped, "flutter/fss.wrapped.badge_encryption_key")
        try KeychainStore.fixture.write(Data(blob.utf8), "flutter/badges_encrypted")
        defaults.set(blob, forKey: "flutter.badges_encrypted")
        defaults.set("Legacy nickname", forKey: "flutter.nickname"); defaults.set("Legacy Full 😀", forKey: "flutter.full_name")
        defaults.set("Aschaffenburg", forKey: "flutter.home_meetup")
        defaults.set(["Aschaffenburg", "Würzburg"], forKey: "flutter.favorite_meetups")
        defaults.set("legacyTelegram", forKey: "flutter.telegram"); defaults.set("legacyTwitter", forKey: "flutter.twitter")
        var row = document.fields["badges"]!.array![0].object!
        row["id"] = .string("legacy-cleartext"); row["meetupEventId"] = .string("legacy-event-third")
        row["meetupName"] = .string("Legacy third meetup")
        defaults.set([String(decoding: try JSONEncoder().encode(BackupValue.object(row)), as: UTF8.self)], forKey: "flutter.badges")
        defaults.set(true, forKey: "flutter.is_admin"); defaults.set("do-not-import", forKey: "flutter.auth_token")
        return (document, wrapped, blob)
    }
    @Test func sameAppUpgradeDetectsAndMigratesEncryptedAndCleartextWalletThenRelaunches() async throws {
        let defaults = try fresh(), (document, wrapped, blob) = try seedFlutter(defaults)
        let app = AppStore(legacyDefaults: defaults)
        #expect(app.identity.state == .recoveryRequired); #expect(app.identity.legacyAvailable)
        await #expect(throws: IdentityError.migrationPending) { try await app.identity.create(vaultPassword: vaultPassword) }
        let review = try app.identity.reviewFlutterInstallation()
        #expect(review.fields["badges"]?.array?.count == 3)
        #expect(review.identityNpub == document.identityNpub)
        try await app.identity.migrateFlutterInstallation(vaultPassword: vaultPassword)
        #expect(!app.identity.legacyAvailable); #expect(app.badgeWallet.badges.count == 3)
        #expect(app.profile.fullName == "Legacy Full 😀"); #expect(app.profile.twitterHandle == "legacyTwitter")
        #expect(try KeychainStore.readFlutter(account: "fss.wrapped.badge_encryption_key") == wrapped)
        #expect(defaults.string(forKey: "flutter.badges_encrypted") == blob)
        let archive = try #require(try KeychainStore.read(account: "flutter-installation-archive-v1"))
        let journal = try JSONDecoder().decode(FlutterUpgradeJournal.Pending.self, from: archive)
        let retained = try JSONDecoder().decode(FlutterUpgradeSource.Snapshot.self, from: BackupEncryption.decrypt(journal.encryptedSnapshot, password: vaultPassword))
        #expect(retained.keychain["fss.wrapped.badge_encryption_key"] == wrapped)
        await app.identity.lock(); await app.synchronizeIdentity()
        let relaunch = AppStore(legacyDefaults: defaults)
        #expect(!relaunch.identity.legacyAvailable)
        try await relaunch.identity.unlock(password: vaultPassword); await relaunch.synchronizeIdentity()
        let exported = try await exported(relaunch)
        #expect(exported.fields["badges"]?.array?.count == 3)
        #expect(exported.fields["user"]?.object?["fullName"] == .string("Legacy Full 😀"))
        #expect(exported.fields["user"]?.object?["auth_token"] == nil)
        #expect(exported.fields["user"]?.object?["is_admin"] == .bool(false))
        #expect(relaunch.preferences.favoriteIDs == ["Aschaffenburg", "Würzburg"])
    }
    @Test func unreadableLegacyWalletBlocksReplacementAndPreservesOriginals() async throws {
        let defaults = try fresh(), (_, wrapped, _) = try seedFlutter(defaults)
        defaults.set("broken:wallet", forKey: "flutter.badges_encrypted")
        let app = AppStore(legacyDefaults: defaults)
        #expect(app.identity.legacyAvailable)
        #expect(throws: FlutterUpgradeError.unreadableSource) { try app.identity.reviewFlutterInstallation() }
        await #expect(throws: FlutterUpgradeError.unreadableSource) { try await app.identity.migrateFlutterInstallation(vaultPassword: vaultPassword) }
        #expect(try IdentityVault.read() == nil)
        #expect(try KeychainStore.readFlutter(account: "fss.wrapped.badge_encryption_key") == wrapped)
        #expect(defaults.string(forKey: "flutter.badges_encrypted") == "broken:wallet")
        try await app.identity.recoverFlutterInstallation(String(decoding: fixture("flutter-v5", "21bkp"), as: UTF8.self),
            backupPassword: password(), vaultPassword: vaultPassword)
        #expect(!app.identity.legacyAvailable)
        #expect(app.profile.fullName == "Änne 😀"); #expect(app.badgeWallet.badges.count == 2)
        #expect(defaults.string(forKey: "flutter.badges_encrypted") == "broken:wallet")
        #expect(try KeychainStore.readFlutter(account: "fss.wrapped.badge_encryption_key") == wrapped)
    }
    @Test func legacyDeletionFailureIsPendingProtectedAndResumesAfterNativeDataIsDurable() async throws {
        let defaults = try fresh(), _ = try seedFlutter(defaults), app = AppStore(legacyDefaults: defaults)
        KeychainStore.fixture.failDeletes("flutter/")
        await #expect(throws: FlutterUpgradeError.deletionFailed) {
            try await app.identity.migrateFlutterInstallation(vaultPassword: vaultPassword, removeOriginals: true)
        }
        #expect(try FlutterUpgradeJournal.read()?.nativeCommitted == true)
        #expect(try KeychainStore.readFlutter(account: "nostr_nsec_key") != nil)
        #expect(app.profile.fullName == "Legacy Full 😀")
        let relaunch = AppStore(legacyDefaults: defaults)
        #expect(relaunch.identity.legacyMigrationPending)
        KeychainStore.fixture.failDeletes(nil)
        try await relaunch.identity.resumeFlutterInstallation(vaultPassword: vaultPassword)
        #expect(try FlutterUpgradeJournal.read() == nil)
        #expect(try KeychainStore.readAllFlutter().isEmpty)
        #expect(defaults.object(forKey: "flutter.nickname") == nil)
        try await relaunch.identity.unlock(password: vaultPassword); await relaunch.synchronizeIdentity()
        #expect(relaunch.badgeWallet.badges.count == 3)
        #expect(try await exported(relaunch).fields["user"]?.object?["telegramHandle"] == .string("legacyTelegram"))
    }
    @Test func profileWriteFailureLeavesFlutterOriginalsAndCompleteProtectedPendingCopy() async throws {
        let defaults = try fresh(), (_, wrapped, _) = try seedFlutter(defaults), app = AppStore(legacyDefaults: defaults)
        #if os(macOS)
        // Collide with the real file destination after init, so reads remain healthy
        // but an atomic profile write fails even when running with elevated privileges.
        let profileFile = UITestFixtureMode.directory.appendingPathComponent("public-profile.json", isDirectory: true)
        try FileManager.default.createDirectory(at: profileFile, withIntermediateDirectories: false)
        let blocker = profileFile.appendingPathComponent("fixture-write-blocker")
        try Data([1]).write(to: blocker)
        #else
        KeychainStore.fixture.failWrites("local-public-profile-v1")
        #endif
        await #expect(throws: IdentityError.invalidBackup) { try await app.identity.migrateFlutterInstallation(vaultPassword: vaultPassword) }
        #expect(try FlutterUpgradeJournal.read()?.nativeCommitted == false)
        #expect(try KeychainStore.readFlutter(account: "fss.wrapped.badge_encryption_key") == wrapped)
        #expect(defaults.string(forKey: "flutter.nickname") == "Legacy nickname")
        #if os(macOS)
        #expect(try Data(contentsOf: blocker) == Data([1]))
        try FileManager.default.removeItem(at: profileFile)
        #else
        KeychainStore.fixture.failWrites(nil)
        #endif
        try await app.identity.resumeFlutterInstallation(vaultPassword: vaultPassword)
        #expect(app.profile.fullName == "Legacy Full 😀"); #expect(app.badgeWallet.badges.count == 3)
    }
    @Test func replacingProfileClearsFormerFieldsAndUnidentifiedImportMergesPublicData() async throws {
        let defaults = try fresh(), app = AppStore(legacyDefaults: defaults)
        var document = try BackupDocument.decode(fixture("flutter-v5", "json"))
        try await app.identity.restoreBackup(String(decoding: document.encoded(), as: UTF8.self), password: vaultPassword, vaultPassword: vaultPassword)
        document.fields.removeValue(forKey: "nostr")
        document.fields["user"] = .object(["nickname": .string("Ignored"), "fullName": .string("Ignored"),
            "twitterHandle": .string("fill-empty"), "favoriteMeetupIds": .array([.string("Berlin")]), "newPublic": .string("kept")])
        try await app.identity.restoreBackup(String(decoding: document.encoded(), as: UTF8.self), password: vaultPassword, vaultPassword: vaultPassword, allowUnidentified: true)
        #expect(app.profile.nickname == "Interop Ada"); #expect(app.profile.twitterHandle == "fill-empty")
        #expect(app.profile.favoriteMeetupIDs == ["Aschaffenburg", "Würzburg", "Berlin"])
        let secret = try IdentityKey.parse(String(repeating: "02", count: 32)), key = try IdentityKey.publicKey(secret)
        document.fields["nostr"] = .object(["has_key": .bool(true), "nsec": .string(try NIP19.encodeSecretKey(secret)),
            "priv_hex": .string(IdentityKey.hex(secret)), "npub": .string(try NIP19.encodePublicKey(key))])
        document.fields["user"] = .object(["nickname": .string("Replacement"), "fullName": .string(""), "favoriteMeetupIds": .array([.string("Vienna")])])
        document.fields["badges"] = .array([])
        try await app.identity.restoreBackup(String(decoding: document.encoded(), as: UTF8.self), password: vaultPassword, vaultPassword: vaultPassword, allowReplacement: true)
        let relaunch = AppStore(legacyDefaults: defaults)
        #expect(relaunch.profile.nickname == "Replacement"); #expect(relaunch.profile.fullName.isEmpty)
        #expect(relaunch.profile.twitterHandle.isEmpty); #expect(relaunch.preferences.favoriteIDs == ["Vienna"])
        #expect(try UserContinuity.savedFields(relaunch.profile)["newPublic"] == nil)
    }
    @Test func demoModeRevokesRealSignerAndKeepsWalletAndPortalUnconfigured() async throws {
        let defaults = try fresh(), app = AppStore(legacyDefaults: defaults)
        try await app.identity.importKey(String(repeating: "01", count: 32), vaultPassword: vaultPassword)
        await app.synchronizeIdentity()
        let retained = try #require(app.identity.signer)
        app.setDemo(true)
        let deadline = ContinuousClock.now.advanced(by: .seconds(3))
        while app.identity.signer != nil, ContinuousClock.now < deadline { await Task.yield() }
        await app.synchronizeIdentity()
        #expect(app.workflow.signer == nil); #expect(app.badgeWallet.signer == nil)
        await #expect(throws: IdentityError.locked) { try await retained.publicKey() }
        // Even an explicit unlock during preview cannot configure active stores.
        try await app.identity.unlock(password: vaultPassword); await app.synchronizeIdentity()
        #expect(app.workflow.signer == nil); #expect(app.badgeWallet.signer == nil)
    }
}

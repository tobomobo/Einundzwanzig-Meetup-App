#if IDENTITY_APP_LIFECYCLE_TESTS
import Foundation
import Testing
import MeetupNostr

// Run Fixtures/IdentityLifecycleHarness.py to compile the real App store/vault
// alongside this fixture adapter. Apple Keychain SDK behavior is tested on Apple;
// these tests execute actual state, cleanup, and recovery logic offline on Linux.
private enum IdentityFixtureError: Error, Equatable { case cleanup, write, apply }
enum UITestFixtureMode { static let enabled = true }
private final class IdentityKeychainFixture: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String: Data] = [:]
    private var writeFailure: String?
    func reset() { lock.withLock { values = [:]; writeFailure = nil } }
    func failWrite(_ account: String?) { lock.withLock { writeFailure = account } }
    func read(_ account: String) -> Data? { lock.withLock { values[account] } }
    func write(_ data: Data, _ account: String) throws {
        try lock.withLock { if writeFailure == account { throw IdentityFixtureError.write }; values[account] = data }
    }
    func delete(_ account: String) { lock.withLock { _ = values.removeValue(forKey: account) } }
}
enum KeychainStore {
    fileprivate static let fixture = IdentityKeychainFixture()
    static func read(account: String) throws -> Data? { fixture.read(account) }
    static func write(_ data: Data, account: String) throws { try fixture.write(data, account) }
    static func delete(account: String) throws { fixture.delete(account) }
    static func readFlutter(account: String) throws -> Data? { fixture.read("flutter/" + account) }
    static func readAllFlutter() throws -> [String: Data] { [:] }
    static func deleteFlutter(account: String) throws { fixture.delete("flutter/" + account) }
}
private struct IdentityOfflineTransport: NostrRelayTransport {
    func query(_ filter: NostrFilter, relays: [URL], timeout: TimeInterval) async throws -> [NostrEvent] { [] }
    func publish(_ event: NostrEvent, relays: [URL], signer: (any NostrSigner)?) async throws { throw NIP46Error.disconnected }
}
@MainActor private final class IdentityBadgeWalletFixture: BadgeWalletPersistence {
    var data: Data?
    func read() throws -> Data? { data }
    func write(_ data: Data) throws { self.data = data }
    func delete() throws { data = nil }
}
private actor IdentityCleanupGate {
    private var entered = false
    private var waiter: CheckedContinuation<Void, Never>?
    private var ready: CheckedContinuation<Void, Never>?
    func wait() async { entered = true; ready?.resume(); ready = nil; await withCheckedContinuation { waiter = $0 } }
    func waitUntilEntered() async { if entered { return }; await withCheckedContinuation { ready = $0 } }
    func release() { waiter?.resume(); waiter = nil }
}

@Suite(.serialized) @MainActor struct IdentityAppLifecycleTests {
    private let password = "fixture-vault-password"
    private func store() -> IdentityStore {
        KeychainStore.fixture.reset()
        let store = IdentityStore(transport: IdentityOfflineTransport())
        store.beforeIdentityChange = {}
        return store
    }
    private func key(_ byte: String) -> String { String(repeating: byte, count: 32) }
    private func backup(_ byte: String) throws -> BackupDocument {
        let secret = try IdentityKey.parse(key(byte))
        return try BackupDocument(fields: ["version": .number(5), "user": .object(["nickname": .string("fixture")]), "badges": .array([]),
            "nostr": .object(["nsec": .string(try NIP19.encodeSecretKey(secret)), "priv_hex": .string(key(byte)),
                              "npub": .string(try NIP19.encodePublicKey(IdentityKey.publicKey(secret))), "has_key": .bool(true)])])
    }
    @Test func cleanupFailureRevokesRetainedSignerAndDoesNotReplaceVault() async throws {
        let store = store()
        try await store.importKey(key("01"), vaultPassword: password)
        let retained = try #require(store.signer), before = try #require(try IdentityVault.read())
        store.beforeIdentityChange = { throw IdentityFixtureError.cleanup }
        await #expect(throws: IdentityFixtureError.cleanup) { try await store.importKey(key("02"), vaultPassword: password) }
        await #expect(throws: IdentityError.locked) { try await retained.publicKey() }
        #expect(store.signer == nil)
        #expect(store.state == .locked)
        #expect(try IdentityVault.read()?.publicKey == before.publicKey)
    }
    @Test func lockingWhileCleanupSuspendsFencesThePendingCommit() async throws {
        let store = store()
        try await store.importKey(key("01"), vaultPassword: password)
        let original = store.publicKey, gate = IdentityCleanupGate()
        store.beforeIdentityChange = { await gate.wait() }
        let task = Task { try await store.importKey(key("02"), vaultPassword: password) }
        await gate.waitUntilEntered()
        await store.lock(); await gate.release()
        await #expect(throws: IdentityError.identityChanged) { try await task.value }
        #expect(try IdentityVault.read()?.publicKey == original)
        #expect(store.signer == nil)
    }
    @Test func restoreFailureProtectsPendingCopyAndRetryKeepsOriginalReplaceDecision() async throws {
        let store = store()
        try await store.importKey(key("01"), vaultPassword: password)
        let document = try backup("02"), raw = String(decoding: try document.encoded(), as: UTF8.self)
        store.restoreBackupData = { _, _ in throw IdentityFixtureError.apply }
        await #expect(throws: IdentityFixtureError.apply) {
            try await store.restoreBackup(raw, password: password, vaultPassword: password, allowReplacement: true)
        }
        #expect(store.state == .recoveryRequired)
        #expect(store.signer == nil)
        #expect(try IdentityVault.pendingRestore()?.decision == .replacesIdentity)
        await #expect(throws: IdentityError.migrationPending) { try await store.create(vaultPassword: password) }
        var seen: BackupDocument.RestoreDecision?
        store.restoreBackupData = { _, decision in seen = decision }
        try await store.resumePendingRestore(password: password, vaultPassword: password)
        #expect(seen == .replacesIdentity)
        #expect(try IdentityVault.pendingRestore() == nil)
        #expect(store.state == .unlocked)
        #expect(store.npub == document.identityNpub)
    }
    @Test func migrationWriteFailurePreservesLegacyKeyAndEncryptedPendingRecovery() async throws {
        let store = store(), document = try backup("02"), block = try #require(document.fields["nostr"]?.object)
        for (account, value) in zip(IdentityVault.flutterAccounts, [block["nsec"]!.string!, block["npub"]!.string!, block["priv_hex"]!.string!]) {
            try KeychainStore.fixture.write(Data(value.utf8), "flutter/" + account)
        }
        KeychainStore.fixture.failWrite("signing-identity-v1")
        await #expect(throws: IdentityFixtureError.write) { try await store.migrateFlutterKeychain(vaultPassword: password) }
        #expect(try IdentityVault.flutterKey() != nil)
        #expect(try IdentityVault.pendingMigration()?.sealedSecret.hasPrefix("ncryptsec1") == true)
        #expect(store.state == .recoveryRequired)
        KeychainStore.fixture.failWrite(nil)
        try await store.resumePendingMigration(vaultPassword: password)
        #expect(try IdentityVault.flutterKey() == nil)
        #expect(try IdentityVault.pendingMigration() == nil)
        #expect(store.npub == document.identityNpub)
    }
    @Test func repeatedUnlockCannotLeaveAnOlderSignerAliveAfterLock() async throws {
        let store = store()
        try await store.importKey(key("01"), vaultPassword: password)
        let retained = try #require(store.signer)
        try await store.unlock(password: password)
        await store.lock()
        await #expect(throws: IdentityError.locked) { try await retained.publicKey() }
    }
    @Test func lockingDuringMigrationDerivationPreventsItsLaterCommit() async throws {
        let store = store(), document = try backup("02"), block = try #require(document.fields["nostr"]?.object)
        for (account, value) in zip(IdentityVault.flutterAccounts, [block["nsec"]!.string!, block["npub"]!.string!, block["priv_hex"]!.string!]) {
            try KeychainStore.fixture.write(Data(value.utf8), "flutter/" + account)
        }
        let task = Task { try await store.migrateFlutterKeychain(vaultPassword: password) }
        while !store.busy { await Task.yield() }
        await store.lock()
        await #expect(throws: IdentityError.identityChanged) { try await task.value }
        #expect(try IdentityVault.read() == nil)
        #expect(try IdentityVault.pendingMigration() == nil)
        #expect(try IdentityVault.flutterKey() != nil)
    }
    @Test func JSONMigrationRecoveryDoesNotDeleteAnUnrelatedFlutterKeychainIdentity() async throws {
        let store = store(), original = try backup("01").fields["nostr"]!.object!
        for (account, value) in zip(IdentityVault.flutterAccounts, [original["nsec"]!.string!, original["npub"]!.string!, original["priv_hex"]!.string!]) {
            try KeychainStore.fixture.write(Data(value.utf8), "flutter/" + account)
        }
        let incoming = try backup("02").encoded()
        KeychainStore.fixture.failWrite("signing-identity-v1")
        await #expect(throws: IdentityFixtureError.write) { try await store.migrateFlutterJSON(incoming, vaultPassword: password) }
        #expect(try IdentityVault.pendingMigration()?.removesFlutterKey == false)
        KeychainStore.fixture.failWrite(nil)
        try await store.resumePendingMigration(vaultPassword: password)
        #expect(try IdentityVault.flutterKey() == IdentityKey.parse(key("01")))
    }
    @Test func actualFlutterExportRestoresItsKeyAndDeliversFullWalletProfileToAppCallback() async throws {
        let store = store()
        let persistence = IdentityBadgeWalletFixture(), wallet = BadgeStore(relay: IdentityOfflineTransport(), persistence: persistence)
        store.beforeIdentityChange = { try await wallet.prepareIdentityChange() }
        let base = try #require(Bundle.module.url(forResource: "flutter-v5", withExtension: "21bkp", subdirectory: "Fixtures/BackupInterop"))
        let json = try #require(Bundle.module.url(forResource: "flutter-v5", withExtension: "json", subdirectory: "Fixtures/BackupInterop"))
        let provenance = try #require(Bundle.module.url(forResource: "provenance", withExtension: "json", subdirectory: "Fixtures/BackupInterop"))
        let password = try #require(JSONDecoder().decode([String: String].self, from: Data(contentsOf: provenance))["password"])
        let document = try BackupDocument.decode(Data(contentsOf: json))
        var applied: BackupDocument?
        store.restoreBackupData = { value, decision in
            #expect(decision == .fresh); applied = value
            await wallet.configure(signer: store.signer)
            try await wallet.restoreBackup(value, decision: decision)
            #expect(value.fields["badges"]?.array?.count == 2)
            #expect(value.fields["user"]?.object?["fullName"]?.string == "Änne 😀")
        }
        try await store.restoreBackup(String(contentsOf: base, encoding: .utf8), password: password, vaultPassword: self.password)
        #expect(applied == document.pendingRevalidation())
        #expect(store.npub == document.identityNpub)
        #expect(wallet.badges.count == 2)
        #expect(wallet.badges.first(where: { $0.id == "interop-meetup" })?.presenceVerified == true)
        #expect(wallet.badges.first(where: { $0.id == "interop-event" })?.isEvent == true)
        #expect(wallet.pendingBackup["nostr"] == nil)
        #expect(persistence.data != nil)
        let signer = try #require(store.signer), key = try await signer.publicKey()
        let walletValue = try #require(applied?.fields["badges"])
        let plaintext = String(decoding: try JSONEncoder().encode(walletValue), as: UTF8.self)
        let encrypted = try await signer.encrypt(plaintext, to: key)
        #expect(try await signer.decrypt(encrypted, from: key) == plaintext)
        #expect(try IdentityVault.pendingRestore() == nil)
        await store.lock(); await wallet.configure(signer: nil)
        #expect(wallet.badges.isEmpty)
        try await store.unlock(password: self.password)
        await wallet.configure(signer: store.signer)
        #expect(wallet.badges.count == 2)
        #expect(!wallet.walletUnreadable)
    }
}
#endif

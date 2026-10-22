import Foundation
import Observation
import MeetupNostr

/// The app's sole active signing identity. Secret records are encrypted and
/// committed to Keychain; a retained signer is revoked on lock or replacement.
@MainActor @Observable
final class IdentityStore {
    enum State: Equatable { case empty, locked, unlocked, connecting, recoveryRequired }
    private(set) var state: State = .empty
    private(set) var signer: (any NostrSigner)?
    private(set) var publicKey = ""
    var npub: String { (try? NIP19.encodePublicKey(publicKey)) ?? "" }
    private(set) var generation = UUID()
    private(set) var busy = false
    private(set) var authURL: URL?
    private(set) var issue: String?
    private(set) var legacyAvailable = false
    private(set) var legacyMigrationPending = false
    var beforeIdentityChange: (@MainActor @Sendable () async throws -> Void)?
    var backupSnapshot: (@MainActor @Sendable () async throws -> BackupDocument)?
    var restoreBackupData: (@MainActor @Sendable (BackupDocument, BackupDocument.RestoreDecision) async throws -> Void)?
    @ObservationIgnored private let transport: any NostrRelayTransport
    @ObservationIgnored private var localSigner: LocalIdentitySigner?
    @ObservationIgnored private var remoteSigner: NIP46Signer?
    @ObservationIgnored private var connectingSigner: NIP46Signer?
    @ObservationIgnored private var record: IdentityVault.Record?
    @ObservationIgnored private let legacySource: FlutterUpgradeSource

    init(transport: any NostrRelayTransport, legacySource: FlutterUpgradeSource? = nil) {
        self.transport = transport
        self.legacySource = legacySource ?? FlutterUpgradeSource(allowAccess: !UITestFixtureMode.enabled)
        do {
            record = try IdentityVault.read()
            publicKey = record?.publicKey ?? ""
            state = record == nil ? .empty : .locked
            if try IdentityVault.hasPendingRestore() { state = .recoveryRequired }
            if try FlutterUpgradeJournal.read() != nil { legacyAvailable = true; legacyMigrationPending = true; state = .recoveryRequired }
            else if record == nil, try !self.legacySource.snapshot().isEmpty { legacyAvailable = true; state = .recoveryRequired }
        } catch { if record == nil { legacyAvailable = true }; state = .recoveryRequired; issue = error.localizedDescription }
    }

    func create(vaultPassword: String) async throws {
        try begin(); defer { busy = false }
        try await installLocal(IdentityKey.generate(), password: vaultPassword)
    }
    func importKey(_ input: String, keyPassword: String = "", vaultPassword: String) async throws {
        try begin(); defer { busy = false }
        let stamp = generation
        let secret = try await Task.detached(priority: .userInitiated) {
            input.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().hasPrefix("ncryptsec1") ?
                try NIP49.decrypt(input, password: keyPassword) : try IdentityKey.parse(input)
        }.value
        guard stamp == generation else { throw IdentityError.identityChanged }
        try await installLocal(secret, password: vaultPassword)
    }
    func unlock(password: String) async throws {
        try begin(); defer { busy = false }
        if signer != nil { return }
        try await activateStoredRecord(password: password)
    }
    private func activateStoredRecord(password: String) async throws {
        guard let record else { throw IdentityError.missingIdentity }
        let stamp = generation
        let secret = try await decryptRecord(record, password: password)
        if record.kind == .local {
            guard try IdentityKey.publicKey(secret) == record.publicKey else { throw IdentityError.inconsistentBackup }
            let next = try LocalIdentitySigner(privateKey: secret)
            guard stamp == generation else { await next.invalidate(); throw IdentityError.identityChanged }
            localSigner = next; signer = next
        } else {
            guard let pointer = record.bunker else { throw IdentityError.invalidBackup }
            state = .connecting
            do {
                let (next, key) = try await remote(pointer, secret: secret)
                guard stamp == generation, key == record.publicKey else { await next.disconnect(); throw IdentityError.identityChanged }
                remoteSigner = next; signer = next
            } catch { state = .locked; throw error }
        }
        state = .unlocked; generation = UUID(); issue = nil
    }
    func lock() async {
        await revoke()
        do { state = try IdentityVault.hasPendingRestore() || legacyAvailable || FlutterUpgradeJournal.read() != nil ? .recoveryRequired : record == nil ? .empty : .locked }
        catch { state = .recoveryRequired; issue = error.localizedDescription }
        authURL = nil
    }
    func connectBunker(_ uri: String, vaultPassword: String) async throws {
        try begin(); defer { busy = false; authURL = nil }
        let stamp = generation, pointer = try BunkerPointer(uri), clientSecret = try IdentityKey.generate()
        state = .connecting
        do {
            let (next, key) = try await remote(pointer, secret: clientSecret)
            do {
                let sealed = try await seal(clientSecret, password: vaultPassword)
                guard stamp == generation else { throw IdentityError.identityChanged }
                let nextRecord = IdentityVault.Record(kind: .bunker, publicKey: key, sealedSecret: sealed, bunker: pointer.withoutSecret)
                try await replace(nextRecord)
                remoteSigner = next; signer = next; state = .unlocked; generation = UUID()
            } catch { await next.disconnect(); throw error }
        } catch {
            if state != .recoveryRequired { state = signer != nil ? .unlocked : record == nil ? .empty : .locked }
            throw error
        }
    }
    func disconnectBunker() async throws { try await reset() }
    func reset() async throws {
        try begin(); defer { busy = false }
        try await prepareReplacement()
        do {
            try IdentityVault.remove()
            record = nil; publicKey = ""; state = .empty; generation = UUID()
        } catch { state = .recoveryRequired; throw error }
    }
    func exportNIP49(vaultPassword: String, exportPassword: String) async throws -> String {
        try begin(); defer { busy = false }
        guard let record, record.kind == .local else { throw IdentityError.missingIdentity }
        let stamp = generation, secret = try await decryptRecord(record, password: vaultPassword)
        let result = try await Task.detached(priority: .userInitiated) { try NIP49.encrypt(secret, password: exportPassword) }.value
        guard stamp == generation else { throw IdentityError.identityChanged }
        return result
    }
    func exportNsec(vaultPassword: String) async throws -> String {
        try begin(); defer { busy = false }
        guard let record, record.kind == .local else { throw IdentityError.missingIdentity }
        let stamp = generation, secret = try await decryptRecord(record, password: vaultPassword)
        guard stamp == generation else { throw IdentityError.identityChanged }
        return try NIP19.encodeSecretKey(secret)
    }
    func changePassword(current: String, replacement: String) async throws {
        try begin(); defer { busy = false }
        guard let record else { throw IdentityError.missingIdentity }
        let stamp = generation, secret = try await decryptRecord(record, password: current)
        let sealed = try await seal(secret, password: replacement)
        guard stamp == generation else { throw IdentityError.identityChanged }
        var updated = IdentityVault.Record(kind: record.kind, publicKey: record.publicKey, sealedSecret: sealed, bunker: record.bunker)
        if let retained = record.retainedLocalSecret {
            let local = try await Task.detached(priority: .userInitiated) { try NIP49.decrypt(retained, password: current) }.value
            updated.retainedLocalSecret = try await seal(local, password: replacement)
        }
        guard stamp == generation else { throw IdentityError.identityChanged }
        try IdentityVault.write(updated); self.record = updated
    }
    func exportBackup(password: String, vaultPassword: String) async throws -> String {
        try begin(); defer { busy = false }
        guard let snapshot = backupSnapshot else { throw IdentityError.cleanupRequired }
        // A locked/unconfigured collection is not an empty complete wallet.
        guard state == .unlocked, signer != nil, let record else { throw IdentityError.locked }
        let stamp = generation, owner = publicKey
        var document = try await snapshot()
        guard stamp == generation, owner == publicKey, signer != nil else { throw IdentityError.identityChanged }
        let secret = try await decryptRecord(record, password: vaultPassword)
        guard stamp == generation else { throw IdentityError.identityChanged }
        var block: [String: BackupValue] = ["npub": .string(npub), "has_key": .bool(record.kind == .local),
            "signing_mode": .string(record.kind == .local ? "local" : "nip46"), "active_npub": .string("")]
        var localSecret: Data? = record.kind == .local ? secret : nil
        if let retained = record.retainedLocalSecret {
            localSecret = try await Task.detached(priority: .userInitiated) { try NIP49.decrypt(retained, password: vaultPassword) }.value
            guard stamp == generation else { throw IdentityError.identityChanged }
        }
        if let localSecret {
            block["nsec"] = .string(try NIP19.encodeSecretKey(localSecret)); block["priv_hex"] = .string(IdentityKey.hex(localSecret))
            block["npub"] = .string(try NIP19.encodePublicKey(IdentityKey.publicKey(localSecret))); block["has_key"] = .bool(true)
        } else { block["nsec"] = .string(""); block["priv_hex"] = .string("") }
        if record.kind == .bunker {
            block["active_npub"] = .string(npub); block["nip46_bunker_uri"] = .string(record.bunker?.uri ?? "")
        }
        document.fields["nostr"] = .object(block)
        let data = try document.encoded()
        let payload = try await Task.detached(priority: .userInitiated) { try BackupEncryption.encrypt(data, password: password) }.value
        guard stamp == generation else { throw IdentityError.identityChanged }
        return payload
    }
    func previewBackup(_ payload: String, password: String) async throws -> BackupDocument {
        try await Task.detached(priority: .userInitiated) {
            let data = payload.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("{") ? Data(payload.utf8) :
                try BackupEncryption.decrypt(payload, password: password)
            let document = try BackupDocument.decode(data); _ = try document.localPrivateKey()
            return document
        }.value
    }
    func restoreBackup(_ payload: String, password: String, vaultPassword: String,
                       allowReplacement: Bool = false, allowUnidentified: Bool = false) async throws {
        try begin(); defer { busy = false }
        let stamp = generation
        let document = try await previewBackup(payload, password: password)
        guard stamp == generation else { throw IdentityError.identityChanged }
        try await restore(document, password: password, vaultPassword: vaultPassword,
                          allowReplacement: allowReplacement, allowUnidentified: allowUnidentified, alreadyStaged: false)
    }
    func resumePendingRestore(password: String, vaultPassword: String) async throws {
        try begin(allowPending: true); defer { busy = false }
        let stamp = generation
        guard let pending = try IdentityVault.pendingRestore() else { throw IdentityError.invalidBackup }
        let document = try await previewBackup(pending.payload, password: password)
        guard stamp == generation else { throw IdentityError.identityChanged }
        try await restore(document, password: password, vaultPassword: vaultPassword,
                          allowReplacement: true, allowUnidentified: true, alreadyStaged: true, originalDecision: pending.decision)
    }
    /// Explicit user recovery action. The active encrypted identity is retained.
    func discardPendingRecovery() async throws {
        try begin(allowPending: true); defer { busy = false }
        guard !legacyAvailable, try FlutterUpgradeJournal.read() == nil else { throw IdentityError.migrationPending }
        let stored = try IdentityVault.read()
        await revoke()
        try IdentityVault.clearPendingRestore(); try IdentityVault.clearPendingMigration()
        record = stored; publicKey = stored?.publicKey ?? ""
        state = record == nil ? .empty : .locked; issue = nil
    }
    func retryVaultRead() async throws {
        try begin(allowPending: true); defer { busy = false }
        let oldKey = publicKey
        await revoke()
        let stored = try IdentityVault.read()
        if (stored?.publicKey ?? "") != oldKey {
            guard let cleanup = beforeIdentityChange else { throw IdentityError.cleanupRequired }
            let fence = generation
            try await cleanup()
            guard generation == fence else { throw IdentityError.identityChanged }
        }
        record = stored; publicKey = stored?.publicKey ?? ""
        state = try IdentityVault.hasPendingRestore() || legacyAvailable || FlutterUpgradeJournal.read() != nil ? .recoveryRequired : stored == nil ? .empty : .locked
    }
    func migrateFlutterKeychain(vaultPassword: String) async throws {
        try begin(); defer { busy = false }
        guard let secret = try IdentityVault.flutterKey() else { throw IdentityError.missingIdentity }
        let stamp = generation
        let sealed = try await seal(secret, password: vaultPassword)
        guard stamp == generation else { throw IdentityError.identityChanged }
        // Preserve a protected recovery copy BEFORE changing identities or
        // deleting legacy records. The original remains if any step fails.
        try IdentityVault.stageMigration(sealed, removesFlutterKey: true)
        do {
            try await installLocal(secret, password: vaultPassword)
            try IdentityVault.removeFlutterKey()
            try IdentityVault.clearPendingMigration()
        } catch { await revoke(); state = .recoveryRequired; throw error }
    }
    func migrateFlutterJSON(_ data: Data, vaultPassword: String) async throws {
        try begin(); defer { busy = false }
        let secret = try FlutterIdentityMigration.privateKey(json: data)
        let stamp = generation, sealed = try await seal(secret, password: vaultPassword)
        guard stamp == generation else { throw IdentityError.identityChanged }
        try IdentityVault.stageMigration(sealed, removesFlutterKey: false)
        do { try await installLocal(secret, password: vaultPassword); try IdentityVault.clearPendingMigration() }
        catch { await revoke(); state = .recoveryRequired; throw error }
    }
    func resumePendingMigration(vaultPassword: String) async throws {
        try begin(allowPending: true); defer { busy = false }
        let stamp = generation
        guard let pending = try IdentityVault.pendingMigration() else { throw IdentityError.invalidBackup }
        let secret = try await Task.detached(priority: .userInitiated) { try NIP49.decrypt(pending.sealedSecret, password: vaultPassword) }.value
        guard stamp == generation else { throw IdentityError.identityChanged }
        do {
            try await installLocal(secret, password: vaultPassword)
            if pending.removesFlutterKey { try IdentityVault.removeFlutterKey() }
            try IdentityVault.clearPendingMigration()
        } catch { await revoke(); state = .recoveryRequired; throw error }
    }
    func usePasskey() throws { throw IdentityError.passkeyNotProvisioned }

    func reviewFlutterInstallation() throws -> BackupDocument {
        if try FlutterUpgradeJournal.read() != nil { throw IdentityError.migrationPending }
        let source = try legacySource.snapshot()
        guard !source.isEmpty else { throw IdentityError.missingIdentity }
        return try source.document()
    }
    func migrateFlutterInstallation(vaultPassword: String, removeOriginals: Bool = false) async throws {
        try begin(allowPending: true); defer { busy = false }
        guard try FlutterUpgradeJournal.read() == nil, try !IdentityVault.hasPendingRestore() else { throw IdentityError.migrationPending }
        guard vaultPassword.count >= 8 else { throw IdentityError.invalidEncoding }
        let stamp = generation, source = try legacySource.snapshot()
        guard !source.isEmpty else { throw IdentityError.missingIdentity }
        var document = try source.document()
        if try document.localPrivateKey() == nil, document.identityNpub.isEmpty { document = try source.document(newPrivateKey: IdentityKey.generate()) }
        do { try await stageFlutterMigration(document, source: source, stamp: stamp, password: vaultPassword, removeOriginals: removeOriginals) }
        catch { legacyAvailable = true; await revoke(); state = .recoveryRequired; throw error }
    }
    func recoverFlutterInstallation(_ payload: String, backupPassword: String, vaultPassword: String) async throws {
        try begin(allowPending: true); defer { busy = false }
        guard try FlutterUpgradeJournal.read() == nil, try !IdentityVault.hasPendingRestore() else { throw IdentityError.migrationPending }
        let stamp = generation, source = try legacySource.snapshot()
        let document = try await previewBackup(payload, password: backupPassword)
        // Originals are always retained when recovery uses a different source.
        do { try await stageFlutterMigration(document, source: source, stamp: stamp, password: vaultPassword, removeOriginals: false) }
        catch { legacyAvailable = true; await revoke(); state = .recoveryRequired; throw error }
    }
    private func stageFlutterMigration(_ document: BackupDocument, source: FlutterUpgradeSource.Snapshot, stamp: UUID,
                                       password: String, removeOriginals: Bool) async throws {
        guard password.count >= 8 else { throw IdentityError.invalidEncoding }
        let bytes = try JSONEncoder().encode(source), documentBytes = try document.encoded()
        let encrypted = try await Task.detached(priority: .userInitiated) { try BackupEncryption.encrypt(bytes, password: password) }.value
        let encryptedDocument = try await Task.detached(priority: .userInitiated) { try BackupEncryption.encrypt(documentBytes, password: password) }.value
        guard stamp == generation else { throw IdentityError.identityChanged }
        let pending = FlutterUpgradeJournal.Pending(encryptedSnapshot: encrypted, encryptedDocument: encryptedDocument,
            decision: document.decision(existingNpub: npub), removeOriginals: removeOriginals, nativeCommitted: false)
        try FlutterUpgradeJournal.write(pending)
        legacyMigrationPending = true
        try await completeFlutterMigration(pending, source: source, password: password)
    }
    func resumeFlutterInstallation(vaultPassword: String) async throws {
        try begin(allowPending: true); defer { busy = false }
        guard let pending = try FlutterUpgradeJournal.read() else { throw FlutterUpgradeError.noPendingMigration }
        let stamp = generation
        let bytes = try await Task.detached(priority: .userInitiated) { try BackupEncryption.decrypt(pending.encryptedSnapshot, password: vaultPassword) }.value
        guard stamp == generation else { throw IdentityError.identityChanged }
        let source = try JSONDecoder().decode(FlutterUpgradeSource.Snapshot.self, from: bytes)
        do { try await completeFlutterMigration(pending, source: source, password: vaultPassword) }
        catch { legacyAvailable = true; await revoke(); state = .recoveryRequired; throw error }
    }
    private func completeFlutterMigration(_ staged: FlutterUpgradeJournal.Pending, source: FlutterUpgradeSource.Snapshot, password: String) async throws {
        var pending = staged
        if !pending.nativeCommitted {
            let stamp = generation, encryptedDocument = pending.encryptedDocument
            let bytes = try await Task.detached(priority: .userInitiated) { try BackupEncryption.decrypt(encryptedDocument, password: password) }.value
            guard stamp == generation else { throw IdentityError.identityChanged }
            let document = try BackupDocument.decode(bytes)
            let existingPending = try IdentityVault.pendingRestore()
            try await restore(document, password: password, vaultPassword: password, allowReplacement: true, allowUnidentified: true,
                alreadyStaged: existingPending != nil, originalDecision: pending.decision)
            pending.nativeCommitted = true
            try FlutterUpgradeJournal.write(pending)
        }
        if pending.removeOriginals { try legacySource.removeOriginals(matching: source) }
        try FlutterUpgradeJournal.finish(pending)
        legacyAvailable = false
        legacyMigrationPending = false
        state = signer != nil ? .unlocked : record == nil ? .empty : .locked
    }

    private func begin(allowPending: Bool = false) throws {
        guard !busy else { throw IdentityError.busy }
        if !allowPending {
            if legacyAvailable { throw IdentityError.migrationPending }
            if try FlutterUpgradeJournal.read() != nil { throw IdentityError.migrationPending }
        }
        if !allowPending, try IdentityVault.hasPendingRestore() { throw IdentityError.migrationPending }
        busy = true; issue = nil
    }
    private func seal(_ secret: Data, password: String) async throws -> String {
        guard password.count >= 8 else { throw IdentityError.invalidEncoding }
        return try await Task.detached(priority: .userInitiated) { try NIP49.encrypt(secret, password: password) }.value
    }
    private func decryptRecord(_ record: IdentityVault.Record, password: String) async throws -> Data {
        let secret = try await Task.detached(priority: .userInitiated) { try NIP49.decrypt(record.sealedSecret, password: password) }.value
        if record.kind == .local, try IdentityKey.publicKey(secret) != record.publicKey { throw IdentityError.inconsistentBackup }
        return secret
    }
    private func installLocal(_ secret: Data, password: String) async throws {
        let stamp = generation, key = try IdentityKey.publicKey(secret)
        let sealed = try await seal(secret, password: password)
        guard stamp == generation else { throw IdentityError.identityChanged }
        let next = try LocalIdentitySigner(privateKey: secret)
        do { try await replace(IdentityVault.Record(kind: .local, publicKey: key, sealedSecret: sealed, bunker: nil)) }
        catch { await next.invalidate(); throw error }
        localSigner = next; signer = next; state = .unlocked; generation = UUID()
    }
    private func revoke() async {
        generation = UUID(); signer = nil
        let oldLocal = localSigner, oldRemote = remoteSigner, connecting = connectingSigner
        localSigner = nil; remoteSigner = nil; connectingSigner = nil
        await oldLocal?.invalidate(); await oldRemote?.disconnect(); await connecting?.disconnect()
    }
    private func prepareReplacement() async throws {
        guard let cleanup = beforeIdentityChange else { throw IdentityError.cleanupRequired }
        try Task.checkCancellation()
        await revoke()
        let fence = generation
        do { try await cleanup(); guard generation == fence else { throw IdentityError.identityChanged } }
        catch { state = record == nil ? .empty : .locked; throw error }
    }
    private func replace(_ next: IdentityVault.Record) async throws {
        try await prepareReplacement()
        do { try IdentityVault.write(next); record = next; publicKey = next.publicKey; state = .locked }
        catch { state = .recoveryRequired; throw error }
    }
    private func remote(_ pointer: BunkerPointer, secret: Data) async throws -> (NIP46Signer, String) {
        let stamp = generation
        let next = try NIP46Signer(pointer: pointer, clientPrivateKey: secret, transport: transport, onAuthURL: { [weak self] url in
            await MainActor.run { if self?.generation == stamp { self?.authURL = url } }
        })
        connectingSigner = next
        defer { if connectingSigner === next { connectingSigner = nil } }
        do { return (next, try await next.connect()) }
        catch { await next.disconnect(); throw error }
    }
    private func restore(_ document: BackupDocument, password: String, vaultPassword: String,
                         allowReplacement: Bool, allowUnidentified: Bool, alreadyStaged: Bool,
                         originalDecision: BackupDocument.RestoreDecision? = nil) async throws {
        guard let apply = restoreBackupData else { throw IdentityError.cleanupRequired }
        let decision = originalDecision ?? document.decision(existingNpub: npub), stamp = generation
        guard decision != .replacesIdentity || allowReplacement,
              decision != .addToIdentity || allowUnidentified else { throw IdentityError.confirmationRequired }
        let secret = try document.localPrivateKey()
        let mode = document.fields["nostr"]?.object?["signing_mode"]?.string ?? "local"
        guard mode == "local" || mode == "nip46" else { throw IdentityError.missingIdentity }
        if mode == "local", let secret, !document.identityNpub.isEmpty,
           try NIP19.encodePublicKey(IdentityKey.publicKey(secret)) != document.identityNpub { throw IdentityError.inconsistentBackup }
        if mode == "nip46" {
            guard let uri = document.fields["nostr"]?.object?["nip46_bunker_uri"]?.string, !uri.isEmpty,
                  !document.identityNpub.isEmpty else { throw IdentityError.missingIdentity }
            _ = try BunkerPointer(uri)
        }
        if secret == nil, record == nil, (document.fields["nostr"]?.object?["nip46_bunker_uri"]?.string ?? "").isEmpty {
            throw IdentityError.missingIdentity
        }
        if secret == nil, decision == .replacesIdentity, (document.fields["nostr"]?.object?["nip46_bunker_uri"]?.string ?? "").isEmpty {
            throw IdentityError.missingIdentity
        }
        if !alreadyStaged {
            let bytes = try document.encoded()
            let protected = try await Task.detached(priority: .userInitiated) { try BackupEncryption.encrypt(bytes, password: password) }.value
            guard stamp == generation else { throw IdentityError.identityChanged }
            try IdentityVault.stageRestore(protected, decision: decision)
        }
        do {
            if decision == .fresh || decision == .replacesIdentity {
                if mode == "local", let secret { try await installLocal(secret, password: vaultPassword) }
                else if let uri = document.fields["nostr"]?.object?["nip46_bunker_uri"]?.string, !uri.isEmpty {
                    let pointer = try BunkerPointer(uri), clientSecret = try IdentityKey.generate()
                    let (next, key) = try await remote(pointer, secret: clientSecret)
                    do {
                        guard try NIP19.encodePublicKey(key) == document.identityNpub else { throw IdentityError.inconsistentBackup }
                        let sealed = try await seal(clientSecret, password: vaultPassword)
                        guard generation == stamp else { throw IdentityError.identityChanged }
                        var nextRecord = IdentityVault.Record(kind: .bunker, publicKey: key, sealedSecret: sealed, bunker: pointer.withoutSecret)
                        if let secret { nextRecord.retainedLocalSecret = try await seal(secret, password: vaultPassword) }
                        guard generation == stamp else { throw IdentityError.identityChanged }
                        try await replace(nextRecord)
                        remoteSigner = next; signer = next; state = .unlocked; generation = UUID()
                    } catch { await next.disconnect(); throw error }
                } else if !document.identityNpub.isEmpty { throw IdentityError.missingIdentity }
            } else if signer == nil {
                try await activateStoredRecord(password: vaultPassword)
            }
            guard document.identityNpub.isEmpty || document.identityNpub == npub else { throw IdentityError.inconsistentBackup }
            // A same-identity import must also retain its alternate local key.
            if mode == "nip46", let secret, var current = record, current.kind == .bunker, current.retainedLocalSecret == nil {
                let fence = generation
                // An unlocked signer does not authenticate a password. Both
                // ciphertexts in this record must use the current vault password.
                _ = try await decryptRecord(current, password: vaultPassword)
                guard generation == fence else { throw IdentityError.identityChanged }
                current.retainedLocalSecret = try await seal(secret, password: vaultPassword)
                guard generation == fence else { throw IdentityError.identityChanged }
                try IdentityVault.write(current); record = current
            }
            let applyFence = generation
            try await apply(document.pendingRevalidation(), decision)
            guard generation == applyFence else { throw IdentityError.identityChanged }
            try IdentityVault.clearPendingRestore()
            state = signer != nil ? .unlocked : record == nil ? .empty : .locked
        } catch { await revoke(); state = .recoveryRequired; throw error }
    }
}

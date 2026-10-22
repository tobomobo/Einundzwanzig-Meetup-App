import Foundation
import MeetupNostr

enum IdentityVault {
    struct Record: Codable, Sendable {
        enum Kind: String, Codable, Sendable { case local, bunker }
        var version = 1
        let kind: Kind
        let publicKey: String
        let sealedSecret: String
        let bunker: BunkerPointer?
        /// Alternate Flutter local key, encrypted separately from the NIP46 client key.
        var retainedLocalSecret: String? = nil
    }
    struct PendingRestore: Codable, Sendable {
        let payload: String
        let decision: BackupDocument.RestoreDecision
    }
    struct PendingMigration: Codable, Sendable {
        let sealedSecret: String
        let removesFlutterKey: Bool
    }
    private static let account = "signing-identity-v1"
    private static let pending = "signing-identity-pending-restore-v1"
    private static let migration = "signing-identity-pending-migration-v1"
    static func read() throws -> Record? {
        guard let bytes = try KeychainStore.read(account: account) else { return nil }
        let record = try JSONDecoder().decode(Record.self, from: bytes)
        guard record.version == 1, record.sealedSecret.hasPrefix("ncryptsec1"),
              (record.kind == .bunker) == (record.bunker != nil) else { throw IdentityError.invalidBackup }
        if let retained = record.retainedLocalSecret, !retained.hasPrefix("ncryptsec1") { throw IdentityError.invalidBackup }
        _ = try NIP19.encodePublicKey(record.publicKey)
        return record
    }
    static func write(_ record: Record) throws {
        let bytes = try JSONEncoder().encode(record)
        try KeychainStore.write(bytes, account: account)
        guard try KeychainStore.read(account: account) == bytes else { throw IdentityError.authenticationFailed }
    }
    static func remove() throws { try KeychainStore.delete(account: account) }
    static func stageRestore(_ encrypted: String, decision: BackupDocument.RestoreDecision) throws {
        let data = try JSONEncoder().encode(PendingRestore(payload: encrypted, decision: decision))
        try KeychainStore.write(data, account: pending)
        guard try KeychainStore.read(account: pending) == data else { throw IdentityError.authenticationFailed }
    }
    static func pendingRestore() throws -> PendingRestore? {
        guard let bytes = try KeychainStore.read(account: pending) else { return nil }
        let value = try JSONDecoder().decode(PendingRestore.self, from: bytes)
        guard value.payload.hasPrefix("enc_v2:") else { throw IdentityError.invalidBackup }
        return value
    }
    static func hasPendingRestore() throws -> Bool {
        try KeychainStore.read(account: pending) != nil || KeychainStore.read(account: migration) != nil
    }
    static func clearPendingRestore() throws { try KeychainStore.delete(account: pending) }
    static func stageMigration(_ encrypted: String, removesFlutterKey: Bool) throws {
        let data = try JSONEncoder().encode(PendingMigration(sealedSecret: encrypted, removesFlutterKey: removesFlutterKey))
        try KeychainStore.write(data, account: migration)
        guard try KeychainStore.read(account: migration) == data else { throw IdentityError.authenticationFailed }
    }
    static func pendingMigration() throws -> PendingMigration? {
        guard let data = try KeychainStore.read(account: migration) else { return nil }
        let value = try JSONDecoder().decode(PendingMigration.self, from: data)
        guard value.sealedSecret.hasPrefix("ncryptsec1") else { throw IdentityError.invalidBackup }
        return value
    }
    static func clearPendingMigration() throws { try KeychainStore.delete(account: migration) }
    static let flutterAccounts = ["nostr_nsec_key", "nostr_npub_key", "nostr_priv_hex"]
    static func flutterKey() throws -> Data? {
        let values = try flutterAccounts.map { try KeychainStore.readFlutter(account: $0).flatMap { String(data: $0, encoding: .utf8) } }
        if values.allSatisfy({ $0 == nil }) { return nil }
        guard let nsec = values[0], let npub = values[1], let hex = values[2] else { throw IdentityError.inconsistentBackup }
        let a = try IdentityKey.parse(nsec), b = try IdentityKey.parse(hex)
        guard a == b, try IdentityKey.publicKey(a) == NIP19.decodePublicKey(npub) else { throw IdentityError.inconsistentBackup }
        return a
    }
    static func removeFlutterKey() throws {
        for account in flutterAccounts { try KeychainStore.deleteFlutter(account: account) }
    }
}

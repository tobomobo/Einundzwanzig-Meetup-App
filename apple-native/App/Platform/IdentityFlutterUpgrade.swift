import Foundation
import MeetupNostr

enum FlutterUpgradeError: Error, Equatable, LocalizedError {
    case unreadableSource, sourceChanged, deletionFailed, wrappedKeyRequiresFlutterExport, noPendingMigration
    var errorDescription: String? {
        switch self {
        case .unreadableSource: "Existing Flutter data could not be read completely. Originals remain protected; recover them in Flutter or import a complete backup."
        case .sourceChanged: "Flutter data changed during migration. Its originals and the protected migration copy remain."
        case .deletionFailed: "The native copy is saved, but original Flutter data could not be removed. Resume protected migration cleanup."
        case .wrappedKeyRequiresFlutterExport: "This Flutter key uses platform wrapping that requires the original app. Export a .21bkp there; the wrapped key and its original bytes remain intact."
        case .noPendingMigration: "There is no protected Flutter migration to resume."
        }
    }
}

@MainActor final class FlutterUpgradeSource {
    struct Snapshot: Codable {
        let keychain: [String: Data]
        let preferences: [String: BackupValue]
        var isEmpty: Bool { keychain.isEmpty && preferences.isEmpty }
        func text(_ account: String) throws -> String? {
            guard let bytes = keychain[account] else { return nil }
            guard let value = String(data: bytes, encoding: .utf8) else { throw FlutterUpgradeError.wrappedKeyRequiresFlutterExport }
            return value
        }
        func document(newPrivateKey: Data? = nil) throws -> BackupDocument {
            func preference(_ name: String) -> BackupValue? { preferences["flutter." + name] }
            func secretValue(_ account: String) throws -> String? { try text(account) ?? preference(account)?.string }
            if keychain["badge_reset_pending"] != nil || preference("badges_encrypted_incoming") != nil || keychain["badges_encrypted_incoming"] != nil {
                throw FlutterUpgradeError.unreadableSource // Finish an interrupted Flutter reset/restore there first.
            }
            let nsec = try secretValue("nostr_nsec_key"), npub = try secretValue("nostr_npub_key"), hex = try secretValue("nostr_priv_hex")
            var key: Data? = newPrivateKey
            if nsec != nil || npub != nil || hex != nil {
                guard let nsec, let npub, let hex else { throw IdentityError.inconsistentBackup }
                let a = try IdentityKey.parse(nsec), b = try IdentityKey.parse(hex)
                guard a == b, try IdentityKey.publicKey(a) == NIP19.decodePublicKey(npub) else { throw IdentityError.inconsistentBackup }
                key = a
            }
            var user: [String: BackupValue] = [:]
            let known = ["nickname": "nickname", "full_name": "fullName", "home_meetup": "homeMeetupId", "favorite_meetups": "favoriteMeetupIds",
                         "telegram": "telegramHandle", "twitter": "twitterHandle", "nostr": "nostrNpub"]
            let nonUser: Set<String> = ["nostr_nsec_key", "nostr_npub_key", "nostr_priv_hex", "badges", "badges_pending"]
            for (name, value) in preferences {
                let short = String(name.dropFirst("flutter.".count))
                if nonUser.contains(short) || short.hasPrefix("badges_") || short.hasPrefix("badge_") || short.contains("encryption_key") { continue }
                user[known[short] ?? short] = value
            }
            if user["nickname"] == nil { user["nickname"] = .string("Anon") }
            if user["favoriteMeetupIds"] == nil, let home = user["homeMeetupId"]?.string, !home.isEmpty { user["favoriteMeetupIds"] = .array([.string(home)]) }
            var rows: [BackupValue] = []
            let keyNames = ["badge_encryption_key", "badge_encryption_key_BACKUP", "badge_encryption_key_suspect", "badge_pending_key", "badge_pending_key_suspect"]
            let keys = try keyNames.compactMap { try text($0) } + keychain.values.compactMap { data -> String? in
                guard let value = String(data: data, encoding: .utf8), value.count == 64 else { return nil }; return value
            }
            let blobNames = ["badges_encrypted", "badges_encrypted_preserved", "badges_encrypted_preserved_older", "badges_pending_encrypted"]
            var blobs = Set<String>()
            for name in blobNames {
                if let value = preference(name)?.string, !value.isEmpty { blobs.insert(value) }
                if let value = try text(name), !value.isEmpty { blobs.insert(value) }
            }
            for blob in blobs {
                var decoded: [BackupValue]?
                for candidate in keys { if let opened = try? FlutterIdentityMigration.decryptWallet(blob, keyHex: candidate) { decoded = opened; break } }
                guard let decoded else { throw FlutterUpgradeError.unreadableSource }
                rows += decoded
            }
            for name in ["badges", "badges_pending"] {
                if let value = preference(name) {
                    guard let list = value.array else { throw FlutterUpgradeError.unreadableSource }
                    for row in list {
                        if let string = row.string { rows.append(try JSONDecoder().decode(BackupValue.self, from: Data(string.utf8))) }
                        else if row.object != nil { rows.append(row) }
                        else { throw FlutterUpgradeError.unreadableSource }
                    }
                }
            }
            var fields: [String: BackupValue] = ["version": .number(5), "user": .object(user), "badges": .array(rows)]
            if let key {
                let publicKey = try IdentityKey.publicKey(key)
                fields["nostr"] = .object(["nsec": .string(try NIP19.encodeSecretKey(key)), "priv_hex": .string(IdentityKey.hex(key)),
                    "npub": .string(try NIP19.encodePublicKey(publicKey)), "has_key": .bool(true), "signing_mode": .string("local")])
            } else if let publicKey = user["nostrNpub"]?.string, !publicKey.isEmpty {
                throw IdentityError.missingIdentity // External signer must be reconnected explicitly.
            }
            for name in ["platform_proofs", "humanity_proof", "admin_registry", "my_vouches", "co_attendance"] {
                if let value = preference(name) {
                    if let string = value.string { fields[name] = try JSONDecoder().decode(BackupValue.self, from: Data(string.utf8)) }
                    else { fields[name] = value }
                }
            }
            return try BackupDocument(fields: fields)
        }
    }
    private let defaults: UserDefaults
    private let allowAccess: Bool
    init(defaults: UserDefaults = .standard, allowAccess: Bool = true) { self.defaults = defaults; self.allowAccess = allowAccess }
    func snapshot() throws -> Snapshot {
        guard allowAccess else { return Snapshot(keychain: [:], preferences: [:]) }
        var preferences: [String: BackupValue] = [:]
        for (key, value) in defaults.dictionaryRepresentation() where key.hasPrefix("flutter.") {
            let data: Data
            do { data = try JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed]) }
            catch { throw FlutterUpgradeError.unreadableSource }
            preferences[key] = try JSONDecoder().decode(BackupValue.self, from: data)
        }
        return Snapshot(keychain: try KeychainStore.readAllFlutter(), preferences: preferences)
    }
    func removeOriginals(matching original: Snapshot) throws {
        guard allowAccess else { throw FlutterUpgradeError.deletionFailed }
        // Only values captured in the protected copy are eligible for deletion.
        for (name, bytes) in original.keychain {
            if let current = try KeychainStore.readFlutter(account: name), current != bytes { throw FlutterUpgradeError.sourceChanged }
        }
        for (name, value) in original.preferences {
            if let current = defaults.object(forKey: name) {
                let bytes = try JSONSerialization.data(withJSONObject: current, options: [.fragmentsAllowed])
                guard try JSONDecoder().decode(BackupValue.self, from: bytes) == value else { throw FlutterUpgradeError.sourceChanged }
            }
        }
        do {
            for name in original.keychain.keys { try KeychainStore.deleteFlutter(account: name) }
            for name in original.preferences.keys {
                defaults.removeObject(forKey: name)
                guard defaults.object(forKey: name) == nil else { throw FlutterUpgradeError.deletionFailed }
            }
        } catch { throw FlutterUpgradeError.deletionFailed }
    }
}

enum FlutterUpgradeJournal {
    struct Pending: Codable {
        let encryptedSnapshot: String
        let encryptedDocument: String
        let decision: BackupDocument.RestoreDecision
        let removeOriginals: Bool
        var nativeCommitted: Bool
    }
    private static let pendingAccount = "flutter-installation-pending-v1"
    static func read() throws -> Pending? {
        guard let bytes = try KeychainStore.read(account: pendingAccount) else { return nil }
        return try JSONDecoder().decode(Pending.self, from: bytes)
    }
    static func write(_ value: Pending) throws {
        let data = try JSONEncoder().encode(value)
        try KeychainStore.write(data, account: pendingAccount)
        guard try KeychainStore.read(account: pendingAccount) == data else { throw IdentityError.authenticationFailed }
    }
    static func finish(_ value: Pending) throws {
        // Retain the password-encrypted source, including companion wrapped keys.
        try KeychainStore.write(JSONEncoder().encode(value), account: "flutter-installation-archive-v1")
        try KeychainStore.delete(account: pendingAccount)
    }
}

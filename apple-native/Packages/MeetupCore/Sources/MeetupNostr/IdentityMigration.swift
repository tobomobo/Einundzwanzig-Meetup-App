import Foundation
import CryptoSwift

/// Explicit import of legacy Flutter preference JSON. Keychain migration uses
/// the same names and consistency checks; no verified/admin flags are imported.
public enum FlutterIdentityMigration {
    /// Flutter MeetupBadge._encryptBlob uses IV(base64):AES-GCM(ciphertext||tag).
    public static func decryptWallet(_ blob: String, keyHex: String) throws -> [BackupValue] {
        guard blob.utf8.count <= 12 * 1024 * 1024 else { throw IdentityError.resourceLimit }
        let parts = blob.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2, let iv = Data(base64Encoded: String(parts[0])), [12, 16].contains(iv.count),
              let sealed = Data(base64Encoded: String(parts[1])), (16...(8 * 1024 * 1024 + 16)).contains(sealed.count)
        else { throw IdentityError.invalidBackup }
        let key = try NostrHex.decode(keyHex.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(), byteCount: 32, field: "Flutter wallet key")
        let data: Data
        do { data = Data(try AES(key: Array(key), blockMode: GCM(iv: Array(iv), mode: .combined), padding: .noPadding).decrypt(Array(sealed))) }
        catch { throw IdentityError.authenticationFailed }
        return try JSONDecoder().decode([BackupValue].self, from: data)
    }
    public static func privateKey(json data: Data) throws -> Data {
        guard data.count <= 64 * 1024 else { throw IdentityError.resourceLimit }
        let fields: [String: BackupValue]
        do { fields = try JSONDecoder().decode([String: BackupValue].self, from: data) }
        catch { throw IdentityError.invalidBackup }
        if fields["version"] != nil {
            guard let key = try BackupDocument(fields: fields).localPrivateKey() else { throw IdentityError.missingIdentity }
            return key
        }
        func value(_ key: String) -> String? { fields[key]?.string ?? fields["flutter." + key]?.string }
        guard let nsec = value("nostr_nsec_key"), let npub = value("nostr_npub_key"), let hex = value("nostr_priv_hex") else {
            throw IdentityError.invalidBackup
        }
        let fromNsec = try IdentityKey.parse(nsec), fromHex = try IdentityKey.parse(hex)
        guard fromNsec == fromHex, try IdentityKey.publicKey(fromNsec) == NIP19.decodePublicKey(npub) else {
            throw IdentityError.inconsistentBackup
        }
        return fromNsec
    }
}

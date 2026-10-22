import Foundation

/// Lossless JSON data for Flutter backup sections. Authority flags are never
/// promoted to verified state by decoding or restoring this document.
public enum BackupValue: Codable, Sendable, Equatable {
    case object([String: BackupValue]), array([BackupValue]), string(String), number(Double), decimal(Decimal), bool(Bool), null
    public init(from decoder: any Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let x = try? c.decode(Bool.self) { self = .bool(x) }
        else if let x = try? c.decode(String.self) { self = .string(x) }
        else if let exact = try? c.decode(Decimal.self) {
            let value = NSDecimalNumber(decimal: exact).doubleValue
            if value.isFinite, Decimal(string: String(value), locale: Locale(identifier: "en_US_POSIX")) == exact { self = .number(value) }
            else { self = .decimal(exact) }
        }
        else if let x = try? c.decode([BackupValue].self) { self = .array(x) }
        else { self = .object(try c.decode([String: BackupValue].self)) }
    }
    public func encode(to encoder: any Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .object(let x): try c.encode(x)
        case .array(let x): try c.encode(x)
        case .string(let x): try c.encode(x)
        case .number(let x): try c.encode(x)
        case .decimal(let x): try c.encode(x)
        case .bool(let x): try c.encode(x)
        case .null: try c.encodeNil()
        }
    }
    public var string: String? { if case .string(let x) = self { x } else { nil } }
    public var object: [String: BackupValue]? { if case .object(let x) = self { x } else { nil } }
    public var array: [BackupValue]? { if case .array(let x) = self { x } else { nil } }
    public var bool: Bool? { if case .bool(let x) = self { x } else { nil } }
}

public struct BackupDocument: Sendable, Equatable {
    public var fields: [String: BackupValue]
    public init(fields: [String: BackupValue]) throws {
        guard case .number(let version) = fields["version"], version.rounded() == version, (1...5).contains(version),
              fields["user"]?.object != nil, fields["badges"]?.array != nil else { throw IdentityError.invalidBackup }
        self.fields = fields
    }
    public static func decode(_ data: Data) throws -> Self {
        guard data.count <= 8 * 1024 * 1024 else { throw IdentityError.resourceLimit }
        do { return try Self(fields: JSONDecoder().decode([String: BackupValue].self, from: data)) }
        catch let e as IdentityError { throw e }
        catch { throw IdentityError.invalidBackup }
    }
    public func encoded() throws -> Data {
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        let data = try encoder.encode(fields)
        guard data.count <= 8 * 1024 * 1024 else { throw IdentityError.resourceLimit }
        return data
    }
    public var identityNpub: String {
        let n = fields["nostr"]?.object ?? [:]
        let active = n["active_npub"]?.string ?? ""
        return active.isEmpty ? n["npub"]?.string ?? "" : active
    }
    /// Validates all three Flutter representations before any storage writes.
    public func localPrivateKey() throws -> Data? {
        guard let n = fields["nostr"]?.object else { return nil }
        let nsec = n["nsec"]?.string ?? "", hex = n["priv_hex"]?.string ?? "", npub = n["npub"]?.string ?? ""
        if nsec.isEmpty && hex.isEmpty {
            if n["has_key"]?.bool == true { throw IdentityError.inconsistentBackup }
            return nil
        }
        guard !nsec.isEmpty, !hex.isEmpty, !npub.isEmpty else { throw IdentityError.inconsistentBackup }
        let a = try IdentityKey.parse(nsec), b = try IdentityKey.parse(hex)
        guard a == b, try NIP19.decodePublicKey(npub) == IdentityKey.publicKey(a) else { throw IdentityError.inconsistentBackup }
        return a
    }
    /// Public staging only. Full encrypted backups and recovery journals keep
    /// their original signing-key block; they must not use this projection.
    public func publicPendingSnapshot() -> Self {
        var copy = self
        copy.fields = Self.redactedSecretMaterial(.object(fields)).object ?? [:]
        return copy.pendingRevalidation()
    }
    /// One recursive secret-key policy for public proof/account staging. Keep
    /// unknown public JSON and exact decimal values without decoding them again.
    public static func redactedSecretMaterial(_ value: BackupValue) -> BackupValue {
        switch value {
        case .object(let fields):
            var publicFields: [String: BackupValue] = [:]
            for (key, item) in fields {
                let name = key.lowercased().filter { $0.isLetter || $0.isNumber }
                if ["nostr", "nsec", "nostrnseckey", "privhex", "nostrprivhex", "privkey", "nip46clientsk", "password", "key", "jwt", "bearer", "credential", "credentials", "session", "auth", "authentication", "authorization", "apikey", "badgependingkey", "clientsk", "clientsessionkey", "wrappedkey", "mnemonic", "seed", "passphrase", "recoveryphrase"].contains(name) ||
                    name.contains("private") || name.contains("secret") || name.contains("token") || name.contains("encryptionkey") || name.contains("password") { continue }
                publicFields[key] = redactedSecretMaterial(item)
            }
            return .object(publicFields)
        case .array(let values): return .array(values.map(redactedSecretMaterial))
        default: return value
        }
    }
    /// Keep imported proof material for relay revalidation, never its claims of
    /// authority. This also covers old backups with admin tokens in user data.
    public func pendingRevalidation() -> Self {
        var copy = self
        if var user = copy.fields["user"]?.object {
            for key in ["isAdmin", "isAdminVerified", "isNostrVerified", "adminViaPortal", "adminViaVouch", "adminViaSeed"] { user[key] = .bool(false) }
            for key in ["portal_token", "auth_token", "access_token", "token"] { user.removeValue(forKey: key) }
            copy.fields["user"] = .object(user)
        }
        copy.fields.removeValue(forKey: "admin_registry")
        copy.fields.removeValue(forKey: "my_vouches")
        copy.fields["humanity_proof"] = Self.mergePendingHumanity(nil, incoming: copy.fields["humanity_proof"])
        return copy
    }
    /// Revalidation flags are not receipt evidence. A summary without a receipt
    /// ID cannot replace a receipt-bearing record, even after normalization.
    public static func mergePendingHumanity(_ previous: BackupValue?, incoming: BackupValue?) -> BackupValue? {
        let old = previous?.object ?? [:], new = incoming?.object ?? [:]
        let flags: Set<String> = ["verified", "needs_reverification"]
        let receiptFields = flags.union(["receipt_id", "receipt_event_id", "first_zap_at", "checked_at", "method"])
        func hasReceipt(_ fields: [String: BackupValue]) -> Bool {
            ["receipt_id", "receipt_event_id"].contains { !(fields[$0]?.string?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "").isEmpty }
        }
        func hasContent(_ value: BackupValue) -> Bool {
            switch value {
            case .null: false
            case .string(let text): !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            case .number(let number): number > 0
            case .decimal(let number): number > 0
            case .array(let values): !values.isEmpty
            case .object(let fields): !fields.isEmpty
            case .bool(let value): value
            }
        }
        func hasEvidence(_ fields: [String: BackupValue]) -> Bool {
            fields.contains { !flags.contains($0.key) && hasContent($0.value) }
        }
        var merged = hasEvidence(new) ? new : old
        if hasReceipt(old), !hasReceipt(new) {
            merged = old
            // Preserve additional public evidence without replacing the known
            // receipt's identity/time with defaults or unrelated metadata.
            for (key, value) in new where !receiptFields.contains(key) && merged[key] == nil && hasContent(value) {
                merged[key] = value
            }
        }
        guard hasEvidence(merged) else { return nil }
        merged["verified"] = .bool(false); merged["needs_reverification"] = .bool(true)
        return .object(merged)
    }
    public enum RestoreDecision: String, Codable, Sendable { case fresh, sameIdentity, addToIdentity, replacesIdentity }
    public func decision(existingNpub: String) -> RestoreDecision {
        if existingNpub.isEmpty { return .fresh }
        if identityNpub.isEmpty { return .addToIdentity }
        return existingNpub.lowercased() == identityNpub.lowercased() ? .sameIdentity : .replacesIdentity
    }
}

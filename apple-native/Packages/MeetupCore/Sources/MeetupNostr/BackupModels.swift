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
        if var human = copy.fields["humanity_proof"]?.object {
            human["verified"] = .bool(false); human["needs_reverification"] = .bool(true)
            copy.fields["humanity_proof"] = .object(human)
        }
        return copy
    }
    public enum RestoreDecision: String, Codable, Sendable { case fresh, sameIdentity, addToIdentity, replacesIdentity }
    public func decision(existingNpub: String) -> RestoreDecision {
        if existingNpub.isEmpty { return .fresh }
        if identityNpub.isEmpty { return .addToIdentity }
        return existingNpub.lowercased() == identityNpub.lowercased() ? .sameIdentity : .replacesIdentity
    }
}

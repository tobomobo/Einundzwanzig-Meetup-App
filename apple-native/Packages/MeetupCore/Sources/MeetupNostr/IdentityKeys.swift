import Foundation
import P256K

public enum IdentityError: Error, Equatable, Sendable, LocalizedError {
    case invalidEncoding, invalidSecret, locked, missingIdentity, identityChanged, busy
    case authenticationFailed, unsupportedVersion, resourceLimit, invalidBackup, inconsistentBackup
    case migrationPending, cleanupRequired, passkeyNotProvisioned, confirmationRequired
    public var errorDescription: String? {
        switch self {
        case .invalidEncoding: "The key encoding or checksum is invalid."
        case .invalidSecret: "The private key is outside the secp256k1 range."
        case .locked: "Unlock the identity vault first."
        case .missingIdentity: "Create or import an identity first."
        case .identityChanged: "The identity changed. Please retry the operation."
        case .busy: "Another identity operation is in progress."
        case .authenticationFailed: "The password is incorrect or the encrypted data is damaged."
        case .unsupportedVersion: "This encrypted format version is not supported."
        case .resourceLimit: "This file requests more resources than this device permits."
        case .invalidBackup: "The backup is incomplete or has an invalid format."
        case .inconsistentBackup: "The backup's private and public keys do not match."
        case .migrationPending: "A protected migration is pending. Complete or discard it before continuing."
        case .cleanupRequired: "Identity cleanup has not been configured."
        case .passkeyNotProvisioned: "Passkeys are unavailable until the relying party and associated domain are verified."
        case .confirmationRequired: "Review and confirm the identity replacement before restoring."
        }
    }
}

/// Classic Bech32 for NIP-19 and NIP-49. The Nostr payload is not subject to
/// Bitcoin addresses' 90-character limit. Decoding rejects noncanonical padding.
public enum NIP19 {
    public static func encodePublicKey(_ hex: String) throws -> String {
        encode(prefix: "npub", bytes: try NostrHex.decode(hex.lowercased(), byteCount: 32, field: "public key"))
    }
    public static func decodePublicKey(_ npub: String) throws -> String {
        NostrHex.encode(try decode(npub, prefix: "npub", count: 32))
    }
    public static func encodeSecretKey(_ bytes: Data) throws -> String {
        try IdentityKey.validate(bytes)
        return encode(prefix: "nsec", bytes: bytes)
    }
    public static func decodeSecretKey(_ nsec: String) throws -> Data {
        let bytes = try decode(nsec, prefix: "nsec", count: 32)
        try IdentityKey.validate(bytes)
        return bytes
    }
    private static let alphabet = Array("qpzry9x8gf2tvdw0s3jn54khce6mua7l".utf8)
    static func encode(prefix: String, bytes: Data) -> String {
        let hrp = Array(prefix.utf8)
        let words = (try? convert(Array(bytes), from: 8, to: 5, pad: true)) ?? []
        let checksum = polymod(expand(hrp) + words + Array(repeating: 0, count: 6)) ^ 1
        let sum = (0..<6).map { UInt8((checksum >> (5 * (5 - $0))) & 31) }
        return prefix + "1" + String(decoding: (words + sum).map { alphabet[Int($0)] }, as: UTF8.self)
    }
    static func decode(_ input: String, prefix: String, count: Int) throws -> Data {
        let value = input.trimmingCharacters(in: .whitespacesAndNewlines)
        let chars = Array(value.utf8)
        guard chars.count == prefix.utf8.count + 7 + (count * 8 + 4) / 5,
              chars.allSatisfy({ (33...126).contains($0) }),
              !(chars.contains { (97...122).contains($0) } && chars.contains { (65...90).contains($0) })
        else { throw IdentityError.invalidEncoding }
        let lower = Array(value.lowercased().utf8)
        guard lower.starts(with: Array((prefix + "1").utf8)), lower.lastIndex(of: 49) == prefix.utf8.count else {
            throw IdentityError.invalidEncoding
        }
        let words = try lower.dropFirst(prefix.utf8.count + 1).map { c -> UInt8 in
            guard let n = alphabet.firstIndex(of: c) else { throw IdentityError.invalidEncoding }
            return UInt8(n)
        }
        guard polymod(expand(Array(prefix.utf8)) + words) == 1 else { throw IdentityError.invalidEncoding }
        let decoded = try convert(Array(words.dropLast(6)), from: 5, to: 8, pad: false)
        guard decoded.count == count else { throw IdentityError.invalidEncoding }
        return Data(decoded)
    }
    private static func expand(_ hrp: [UInt8]) -> [UInt8] { hrp.map { $0 >> 5 } + [0] + hrp.map { $0 & 31 } }
    private static func polymod(_ values: [UInt8]) -> UInt32 {
        let g: [UInt32] = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]
        var chk: UInt32 = 1
        for value in values {
            let top = chk >> 25; chk = (chk & 0x1ffffff) << 5 ^ UInt32(value)
            for i in 0..<5 where (top >> i) & 1 != 0 { chk ^= g[i] }
        }
        return chk
    }
    private static func convert(_ values: [UInt8], from: Int, to: Int, pad: Bool) throws -> [UInt8] {
        var acc: UInt32 = 0; var bits = 0; var out: [UInt8] = []
        let mask = UInt32((1 << to) - 1)
        for value in values {
            guard Int(value) >> from == 0 else { throw IdentityError.invalidEncoding }
            acc = ((acc << from) | UInt32(value)) & 0xffff; bits += from
            while bits >= to { bits -= to; out.append(UInt8((acc >> bits) & mask)) }
        }
        if pad { if bits > 0 { out.append(UInt8((acc << (to - bits)) & mask)) } }
        else if bits >= from || ((acc << (to - bits)) & mask) != 0 { throw IdentityError.invalidEncoding }
        return out
    }
}

public enum IdentityKey {
    public static func generate() throws -> Data { try P256K.Schnorr.PrivateKey().dataRepresentation }
    public static func parse(_ input: String) throws -> Data {
        let value = input.trimmingCharacters(in: .whitespacesAndNewlines)
        let bytes = value.lowercased().hasPrefix("nsec1") ? try NIP19.decodeSecretKey(value) :
            try NostrHex.decode(value.lowercased(), byteCount: 32, field: "private key")
        try validate(bytes)
        return bytes
    }
    public static func validate(_ bytes: Data) throws {
        // Do not pass invalid scalars to provider code that assumes a valid key.
        let order = try NostrHex.decode("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", byteCount: 32, field: "order")
        guard bytes.count == 32, bytes.contains(where: { $0 != 0 }), bytes.lexicographicallyPrecedes(order) else {
            throw IdentityError.invalidSecret
        }
    }
    public static func publicKey(_ bytes: Data) throws -> String {
        try validate(bytes)
        return NostrHex.encode(Data(try P256K.Schnorr.PrivateKey(dataRepresentation: bytes).xonly.bytes))
    }
    public static func hex(_ bytes: Data) -> String { NostrHex.encode(bytes) }
}

enum IdentityRandom {
    static func bytes(_ count: Int) -> Data {
        // Swift's system generator uses arc4random_buf on Apple and getrandom
        // on Linux. No seeded PRNG or fallback to predictable randomness.
        var generator = SystemRandomNumberGenerator()
        return Data((0..<count).map { _ in UInt8.random(in: .min ... .max, using: &generator) })
    }
}

/// Revocable signer: retaining it across a lock/identity change does not retain
/// permission to sign. The store calls invalidate before replacing the vault.
public actor LocalIdentitySigner: NostrSigner {
    private var secret: Data?
    private let key: String
    private let crypto = NostrCrypto()
    public init(privateKey: Data) throws { key = try IdentityKey.publicKey(privateKey); secret = privateKey }
    public func publicKey() throws -> String { guard secret != nil else { throw IdentityError.locked }; return key }
    public func invalidate() {
        let count = secret?.count ?? 0
        secret?.resetBytes(in: 0..<count)
        secret = nil
    }
    public func sign(kind: Int, tags: [[String]], content: String, createdAt: Int) async throws -> NostrEvent {
        try Task.checkCancellation()
        guard let secret else { throw IdentityError.locked }
        let event = try await crypto.sign(UnsignedNostrEvent(pubkey: key, createdAt: createdAt, kind: kind, tags: tags, content: content), privateKey: secret)
        guard self.secret != nil else { throw IdentityError.identityChanged }
        try Task.checkCancellation()
        return event
    }
    public func encrypt(_ plaintext: String, to publicKey: String) throws -> String {
        try Task.checkCancellation()
        guard let secret else { throw IdentityError.locked }
        return try NIP44.encrypt(plaintext, conversationKey: NIP44.conversationKey(privateKey: secret, publicKey: publicKey))
    }
    public func decrypt(_ ciphertext: String, from publicKey: String) throws -> String {
        try Task.checkCancellation()
        guard let secret else { throw IdentityError.locked }
        return try NIP44.decrypt(ciphertext, conversationKey: NIP44.conversationKey(privateKey: secret, publicKey: publicKey))
    }
}

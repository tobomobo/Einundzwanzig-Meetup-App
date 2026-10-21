import Foundation

/// A public Nostr reference with canonical lowercase hex and NIP-19 npub forms.
/// Checks format, byte length and checksum only, not curve membership, key ownership
/// or signatures. A Bech32 checksum detects transcription errors; it is not a signature.
public struct PublicIdentity: Codable, Hashable, Sendable {
    public let hex: String
    public let npub: String

    public enum ValidationError: Error, Equatable, Sendable {
        case invalidHex
        case invalidLength
        case invalidCharacter
        case mixedCase
        case unsupportedPrefix
        case invalidChecksum
        case invalidPadding
    }

    /// Accepts exactly 64 ASCII hex digits (without 0x), or a classic Bech32 npub.
    /// Surrounding whitespace is trimmed. The optional, case-insensitive `nostr:`
    /// scheme is accepted only for npub; the payload itself must not mix cases.
    public init(_ input: String) throws {
        var value = input.trimmingCharacters(in: .whitespacesAndNewlines)
        let bytes: [UInt8]
        if value.prefix(6).lowercased() == "nostr:" {
            value = String(value.dropFirst(6))
            bytes = try NpubBech32.decode(value)
        } else if value.utf8.count == 64 {
            bytes = try Self.decodeHex(value)
        } else {
            bytes = try NpubBech32.decode(value)
        }
        hex = Self.encodeHex(bytes)
        npub = NpubBech32.encode(bytes)
    }

    public static func normalize(_ input: String) -> PublicIdentity? {
        try? PublicIdentity(input)
    }

    private enum CodingKeys: String, CodingKey { case hex, npub }

    /// Persisted representations must both be valid and refer to the same bytes.
    /// Custom decoding prevents Codable from bypassing the initializer's invariants.
    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let hexValue = try container.decode(String.self, forKey: .hex)
        let npubValue = try container.decode(String.self, forKey: .npub)
        do {
            let bytes = try Self.decodeHex(hexValue)
            guard bytes == (try NpubBech32.decode(npubValue)) else {
                throw DecodingError.dataCorruptedError(
                    forKey: .npub, in: container,
                    debugDescription: "Public identity representations disagree."
                )
            }
            hex = Self.encodeHex(bytes)
            npub = NpubBech32.encode(bytes)
        } catch is ValidationError {
            throw DecodingError.dataCorruptedError(
                forKey: .npub, in: container,
                debugDescription: "Invalid public identity format or checksum."
            )
        }
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(hex, forKey: .hex)
        try container.encode(npub, forKey: .npub)
    }

    private static func decodeHex(_ value: String) throws -> [UInt8] {
        guard value.utf8.count == 64 else { throw ValidationError.invalidLength }
        var bytes: [UInt8] = []
        var highNibble: UInt8?
        for character in value.utf8 {
            let nibble: UInt8
            switch character {
            case 48...57: nibble = character - 48
            case 65...70: nibble = character - 65 + 10
            case 97...102: nibble = character - 97 + 10
            default: throw ValidationError.invalidHex
            }
            if let high = highNibble {
                bytes.append(high << 4 | nibble)
                highNibble = nil
            } else {
                highNibble = nibble
            }
        }
        return bytes
    }

    private static func encodeHex(_ bytes: [UInt8]) -> String {
        let alphabet = Array("0123456789abcdef".utf8)
        return String(decoding: bytes.flatMap { [alphabet[Int($0 >> 4)], alphabet[Int($0 & 15)]] }, as: UTF8.self)
    }
}

/// Deliberately limited to npub. No secret-key or generic NIP-19 decoder is exposed.
/// NIP-19: https://github.com/nostr-protocol/nips/blob/master/19.md
/// BIP-173: https://github.com/bitcoin/bips/blob/master/bip-0173.mediawiki
private enum NpubBech32 {
    private static let alphabet = Array("qpzry9x8gf2tvdw0s3jn54khce6mua7l".utf8)
    private static let hrp = Array("npub".utf8)
    private static let generators: [UInt32] = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]

    static func encode(_ bytes: [UInt8]) -> String {
        var words: [UInt8] = []
        var accumulator: UInt32 = 0
        var bits = 0
        for byte in bytes {
            accumulator = ((accumulator << 8) | UInt32(byte)) & 0xfff
            bits += 8
            while bits >= 5 {
                bits -= 5
                words.append(UInt8((accumulator >> bits) & 31))
            }
        }
        if bits > 0 { words.append(UInt8((accumulator << (5 - bits)) & 31)) }
        let checksum = polymod(expandedHRP + words + [UInt8](repeating: 0, count: 6)) ^ 1
        let checksumWords = (0..<6).map { UInt8((checksum >> (5 * (5 - $0))) & 31) }
        return String(decoding: hrp + [49] + (words + checksumWords).map { alphabet[Int($0)] }, as: UTF8.self)
    }

    static func decode(_ value: String) throws -> [UInt8] {
        // 4 HRP + separator + ceil(256 / 5) payload symbols + 6 checksum symbols.
        // A strict length also excludes excess padding and Bech32 insertion variants.
        guard value.utf8.count == 63 else { throw PublicIdentity.ValidationError.invalidLength }
        let characters = Array(value.utf8)
        guard characters.allSatisfy({ (33...126).contains($0) }) else {
            throw PublicIdentity.ValidationError.invalidCharacter
        }
        let hasLower = characters.contains { (97...122).contains($0) }
        let hasUpper = characters.contains { (65...90).contains($0) }
        guard !(hasLower && hasUpper) else { throw PublicIdentity.ValidationError.mixedCase }
        let lower = characters.map { (65...90).contains($0) ? $0 + 32 : $0 }
        guard lower.starts(with: hrp + [49]), lower.lastIndex(of: 49) == 4 else {
            throw PublicIdentity.ValidationError.unsupportedPrefix
        }
        let words = try lower.dropFirst(5).map { character -> UInt8 in
            guard let index = alphabet.firstIndex(of: character) else {
                throw PublicIdentity.ValidationError.invalidCharacter
            }
            return UInt8(index)
        }
        // NIP-19 uses classic Bech32's constant 1, never Bech32m's 0x2bc830a3.
        guard polymod(expandedHRP + words) == 1 else { throw PublicIdentity.ValidationError.invalidChecksum }

        var bytes: [UInt8] = []
        var accumulator: UInt32 = 0
        var bits = 0
        for word in words.dropLast(6) {
            accumulator = ((accumulator << 5) | UInt32(word)) & 0xfff
            bits += 5
            if bits >= 8 {
                bits -= 8
                bytes.append(UInt8((accumulator >> bits) & 255))
            }
        }
        // Incomplete groups must be fewer than five bits and entirely zero.
        guard bits < 5, accumulator & ((1 << bits) - 1) == 0 else {
            throw PublicIdentity.ValidationError.invalidPadding
        }
        guard bytes.count == 32 else { throw PublicIdentity.ValidationError.invalidLength }
        return bytes
    }

    private static var expandedHRP: [UInt8] {
        hrp.map { $0 >> 5 } + [0] + hrp.map { $0 & 31 }
    }

    private static func polymod(_ values: [UInt8]) -> UInt32 {
        var checksum: UInt32 = 1
        for value in values {
            let top = checksum >> 25
            checksum = ((checksum & 0x1ffffff) << 5) ^ UInt32(value)
            for index in 0..<5 where (top >> index) & 1 != 0 {
                checksum ^= generators[index]
            }
        }
        return checksum
    }
}

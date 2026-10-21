import Foundation

public enum NostrCryptoError: Error, Equatable, Sendable {
    case invalidLength(field: String, expected: Int, actual: Int)
    case invalidHex(field: String)
    case invalidPrivateKey
    case publicKeyMismatch
    case signingFailed
    case invalidCreatedAt
    case invalidKind
    case emptyTag
    case invalidJSON
    case limitExceeded(field: String)
}

/// Resource bounds for this foundation, independent of relay filters and author trust.
public enum NostrEventLimits {
    public static let maximumCreatedAt = 9_007_199_254_740_991
    public static let maximumKind = 65_535
    public static let maximumContentBytes = 65_536
    public static let maximumTagCount = 2_048
    public static let maximumTagElementCount = 64
    public static let maximumTagElementBytes = 8_192
    public static let maximumCanonicalBytes = 131_072
    public static let maximumJSONBytes = 262_144
}

enum NostrHex {
    private static let alphabet = Array("0123456789abcdef".utf8)

    static func encode(_ data: Data) -> String {
        String(decoding: data.flatMap { [alphabet[Int($0 >> 4)], alphabet[Int($0 & 15)]] }, as: UTF8.self)
    }

    /// NIP-01 wire fields use exact-length, lowercase ASCII hex; no normalization.
    static func decode(_ text: String, byteCount: Int, field: String) throws -> Data {
        guard text.utf8.count == byteCount * 2 else { throw NostrCryptoError.invalidHex(field: field) }
        var result = Data()
        result.reserveCapacity(byteCount)
        let bytes = Array(text.utf8)
        for index in stride(from: 0, to: bytes.count, by: 2) {
            guard let high = nibble(bytes[index]), let low = nibble(bytes[index + 1]) else {
                throw NostrCryptoError.invalidHex(field: field)
            }
            result.append(high << 4 | low)
        }
        return result
    }

    private static func nibble(_ byte: UInt8) -> UInt8? {
        switch byte {
        case 48...57: byte - 48
        case 97...102: byte - 87
        default: nil
        }
    }
}

/// Only the NIP-01 array shape is serialized here. Content is an opaque string;
/// objects inside it are never decoded, reordered, or re-encoded.
enum NostrCanonicalJSON {
    static func serialize(_ event: UnsignedNostrEvent) throws -> Data {
        var output = Data()
        func append(_ text: String) throws {
            guard output.count + text.utf8.count <= NostrEventLimits.maximumCanonicalBytes else {
                throw NostrCryptoError.limitExceeded(field: "canonical serialization")
            }
            output.append(contentsOf: text.utf8)
        }
        func string(_ value: String) throws {
            try append("\"")
            // Escape exactly the JSON control range, quote, and backslash. In
            // particular '/', DEL, Unicode and U+2028/U+2029 remain literal UTF-8.
            for byte in value.utf8 {
                switch byte {
                case 0x22: try append("\\\"")
                case 0x5c: try append("\\\\")
                case 0x08: try append("\\b")
                case 0x09: try append("\\t")
                case 0x0a: try append("\\n")
                case 0x0c: try append("\\f")
                case 0x0d: try append("\\r")
                case 0x00...0x1f:
                    let hex = Array("0123456789abcdef".utf8)
                    try append("\\u00" + String(decoding: [hex[Int(byte >> 4)], hex[Int(byte & 15)]], as: UTF8.self))
                default:
                    guard output.count < NostrEventLimits.maximumCanonicalBytes else {
                        throw NostrCryptoError.limitExceeded(field: "canonical serialization")
                    }
                    output.append(byte)
                }
            }
            try append("\"")
        }
        try append("[0,")
        try string(event.pubkey)
        try append(",\(event.createdAt),\(event.kind),[")
        for (index, tag) in event.tags.enumerated() {
            if index > 0 { try append(",") }
            try append("[")
            for (elementIndex, element) in tag.enumerated() {
                if elementIndex > 0 { try append(",") }
                try string(element)
            }
            try append("]")
        }
        try append("],")
        try string(event.content)
        try append("]")
        return output
    }
}

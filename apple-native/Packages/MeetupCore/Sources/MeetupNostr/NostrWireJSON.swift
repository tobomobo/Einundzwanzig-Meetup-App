import Foundation

enum NostrWireJSON {
    static func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        guard data.count <= NostrEventLimits.maximumJSONBytes else {
            throw NostrCryptoError.limitExceeded(field: "event JSON")
        }
        // JSONDecoder lazily skips unused fields without validating every string
        // escape/number. JSONSerialization eagerly validates the whole document.
        // Its parsed object is discarded; it never enters canonical serialization.
        _ = try JSONSerialization.jsonObject(with: data)
        var scanner = IntegerTokenScanner(bytes: Array(data))
        try scanner.validate()
        return try JSONDecoder().decode(type, from: data)
    }

    /// Only locates top-level numeric tokens; it never interprets event content
    /// or replaces the JSON decoder. No floating-point conversion is involved.
    private struct IntegerTokenScanner {
        let bytes: [UInt8]
        var index = 0

        mutating func validate() throws {
            whitespace()
            try consume(0x7b) // {
            whitespace()
            if current == 0x7d { return }
            while index < bytes.count {
                whitespace()
                let keyStart = index
                try skipString()
                let name = try JSONDecoder().decode(String.self, from: Data(bytes[keyStart..<index]))
                whitespace()
                try consume(0x3a) // :
                whitespace()
                let start = index
                try skipValue()
                var end = index
                while end > start && isWhitespace(bytes[end - 1]) { end -= 1 }
                if name.utf8.elementsEqual("created_at".utf8) || name.utf8.elementsEqual("kind".utf8) {
                    let token = bytes[start..<end]
                    // Wire integer policy uses ordinary nonnegative decimal
                    // integer tokens, with no fraction, exponent, sign or quotes.
                    guard !token.isEmpty, token.count <= 16,
                          token.allSatisfy({ (48...57).contains($0) }),
                          token.count == 1 || token.first != 48 else {
                        if name == "kind" { throw NostrCryptoError.invalidKind }
                        throw NostrCryptoError.invalidCreatedAt
                    }
                }
                whitespace()
                if current == 0x7d { return }
                try consume(0x2c) // ,
            }
            throw NostrCryptoError.invalidJSON
        }

        var current: UInt8? { index < bytes.count ? bytes[index] : nil }

        mutating func consume(_ expected: UInt8) throws {
            guard current == expected else { throw NostrCryptoError.invalidJSON }
            index += 1
        }

        mutating func whitespace() {
            while let byte = current, isWhitespace(byte) { index += 1 }
        }

        func isWhitespace(_ byte: UInt8) -> Bool { byte == 32 || byte == 9 || byte == 10 || byte == 13 }

        mutating func skipString() throws {
            try consume(0x22)
            while let byte = current {
                index += 1
                if byte == 0x22 { return }
                if byte == 0x5c {
                    guard index < bytes.count else { throw NostrCryptoError.invalidJSON }
                    index += 1 // Escaped byte; full escape validation is Foundation's job.
                }
            }
            throw NostrCryptoError.invalidJSON
        }

        mutating func skipValue() throws {
            var depth = 0
            while let byte = current {
                if byte == 0x22 { try skipString(); continue }
                if byte == 0x7b || byte == 0x5b { depth += 1 }
                if byte == 0x7d || byte == 0x5d {
                    if depth == 0 { return }
                    depth -= 1
                }
                if byte == 0x2c && depth == 0 { return }
                index += 1
            }
            throw NostrCryptoError.invalidJSON
        }
    }
}

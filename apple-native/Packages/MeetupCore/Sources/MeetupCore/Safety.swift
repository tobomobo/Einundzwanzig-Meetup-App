import Foundation

public enum SafeURL {
    /// Accepts absolute web URLs only. No implicit scheme or app deep links.
    public static func web(_ text: String) -> URL? {
        validated(text, schemes: ["http", "https"])
    }

    /// Relays require TLS, a host and no embedded credentials or fragment.
    public static func relay(_ text: String) -> URL? {
        guard let url = validated(text, schemes: ["wss"]), url.fragment == nil else { return nil }
        return url
    }

    private static func validated(_ text: String, schemes: Set<String>) -> URL? {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty,
              !value.unicodeScalars.contains(where: { CharacterSet.whitespacesAndNewlines.union(.controlCharacters).contains($0) }),
              !value.contains("\\"),
              let parts = URLComponents(string: value),
              let scheme = parts.scheme?.lowercased(), schemes.contains(scheme),
              let host = parts.host, !host.isEmpty,
              !host.contains("%"),
              !host.unicodeScalars.contains(where: { CharacterSet.whitespacesAndNewlines.union(.controlCharacters).contains($0) }),
              parts.user == nil, parts.password == nil,
              parts.port.map({ (1...65535).contains($0) }) ?? true,
              let url = parts.url else { return nil }
        guard validHost(host), validHost(url.host ?? host) else { return nil }
        return url
    }

    private static func validHost(_ host: String) -> Bool {
        if host.hasPrefix("[") || host.hasSuffix("]") {
            guard host.hasPrefix("["), host.hasSuffix("]") else { return false }
            return validIPv6(String(host.dropFirst().dropLast()))
        }
        if host.contains(":") { return validIPv6(host) }
        // URL.host supplies IDNA ASCII on Foundation. Raw Unicode labels are checked below too.
        var name = host
        if name.hasSuffix(".") { name.removeLast() }
        guard !name.isEmpty, name.utf8.count <= 253 else { return false }
        if name.utf8.allSatisfy({ (48...57).contains($0) || $0 == 46 }), name.contains(".") {
            return validIPv4(name)
        }
        return name.split(separator: ".", omittingEmptySubsequences: false).allSatisfy { label in
            guard !label.isEmpty, label.utf8.count <= 63, label.first != "-", label.last != "-" else { return false }
            return label.unicodeScalars.allSatisfy { scalar in
                scalar == "-" || CharacterSet.letters.union(.decimalDigits).contains(scalar)
            }
        }
    }

    private static func validIPv4(_ host: String) -> Bool {
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        return parts.count == 4 && parts.allSatisfy { part in
            !part.isEmpty && part.count <= 3 && part.utf8.allSatisfy { (48...57).contains($0) }
                && UInt16(part).map { $0 <= 255 } == true
        }
    }

    private static func validIPv6(_ host: String) -> Bool {
        guard !host.isEmpty else { return false }
        let sides = host.components(separatedBy: "::")
        guard sides.count <= 2 else { return false }
        var count = 0
        for (sideIndex, side) in sides.enumerated() {
            if side.isEmpty { continue }
            let groups = side.split(separator: ":", omittingEmptySubsequences: false)
            for (index, group) in groups.enumerated() {
                if group.contains(".") {
                    guard sideIndex == sides.count - 1, index == groups.count - 1, validIPv4(String(group)) else { return false }
                    count += 2
                } else {
                    guard !group.isEmpty, group.count <= 4,
                          group.utf8.allSatisfy({ (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0) }) else { return false }
                    count += 1
                }
            }
        }
        return sides.count == 2 ? count < 8 : count == 8
    }
}

public enum SecurityCapabilities {
    public static let signingAvailable = false
    public static let verificationAvailable = false
    public static let reason = "App signing, badge verification, claims and trusted reputation remain unavailable. The separate MeetupNostr foundation has tested Schnorr/NIP-01 primitives, but no app key lifecycle, organizer registry or collector binding is connected. Imported data is unverified."
}

public enum BitcoinConversionError: Error, Equatable, Sendable {
    case invalidNumber
    case excessivePrecision
    case overflow
}

public enum BitcoinConverter {
    /// Exact base-10 conversion; at most eight decimal places, no rounding or exponent notation.
    public static func sats(fromBTC text: String) throws -> Int64 {
        var input = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let negative = input.first == "-"
        if input.first == "-" || input.first == "+" { input.removeFirst() }
        let pieces = input.split(separator: ".", omittingEmptySubsequences: false)
        guard (1...2).contains(pieces.count), !pieces[0].isEmpty,
              pieces.allSatisfy({ $0.utf8.allSatisfy { (48...57).contains($0) } }),
              pieces.count == 1 || !pieces[1].isEmpty else { throw BitcoinConversionError.invalidNumber }
        let fraction = pieces.count == 2 ? String(pieces[1]) : ""
        guard fraction.count <= 8 else { throw BitcoinConversionError.excessivePrecision }
        let digits = String(pieces[0]) + fraction + String(repeating: "0", count: 8 - fraction.count)
        var magnitude: UInt64 = 0
        let limit = negative ? UInt64(Int64.max) + 1 : UInt64(Int64.max)
        for digit in digits.utf8 {
            let (timesTen, overflow1) = magnitude.multipliedReportingOverflow(by: 10)
            let (next, overflow2) = timesTen.addingReportingOverflow(UInt64(digit - 48))
            guard !overflow1, !overflow2, next <= limit else { throw BitcoinConversionError.overflow }
            magnitude = next
        }
        if negative, magnitude == UInt64(Int64.max) + 1 { return Int64.min }
        return negative ? -Int64(magnitude) : Int64(magnitude)
    }

    /// Locale-independent decimal BTC, with insignificant fractional zeros removed.
    public static func btc(fromSats sats: Int64) -> String {
        let magnitude = sats.magnitude
        let whole = magnitude / 100_000_000
        let remainder = magnitude % 100_000_000
        let sign = sats < 0 ? "-" : ""
        guard remainder != 0 else { return sign + String(whole) }
        var fraction = String(remainder)
        fraction = String(repeating: "0", count: 8 - fraction.count) + fraction
        while fraction.last == "0" { fraction.removeLast() }
        return "\(sign)\(whole).\(fraction)"
    }

    public static func fiat(sats: Int64, price: Double) -> Double {
        Double(sats) / 100_000_000 * price
    }
}

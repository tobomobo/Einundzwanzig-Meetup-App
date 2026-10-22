import Foundation
import P256K

public enum BadgeProtocolError: Error, LocalizedError, Equatable, Sendable {
    case malformed, unsupportedVersion, invalidSignature, expired, staleQR, unauthorized, duplicate, invalidClaim, oversized, incompleteSync, tooFarAway, locationUnavailable
    public var errorDescription: String? {
        switch self {
        case .malformed: "The badge or proof format is invalid."
        case .unsupportedVersion: "Legacy shared-secret badges are not accepted."
        case .invalidSignature: "The source signature could not be verified."
        case .expired: "The badge has expired. Ask the organizer for a new badge."
        case .staleQR: "The rolling QR is too old. Scan the organizer’s current screen."
        case .unauthorized: "The issuer is not authorized by the verified registry or event."
        case .duplicate: "This event is already in the wallet."
        case .invalidClaim: "The collector claim does not match the badge and identity."
        case .oversized: "This wallet or proof exceeds the supported size."
        case .incompleteSync: "The relay returned no readable wallet chunks."
        case .tooFarAway: "The measured location is too far from this meetup or event."
        case .locationUnavailable: "A current location and this event’s reference location are required for issuance."
        }
    }
}

/// JSON values retain integers and exact UTF-8 strings for Dart wire formats.
public struct BadgeJSONMember: Codable, Equatable, Sendable {
    public let key: String
    public let value: BadgeJSON
    public init(_ key: String, _ value: BadgeJSON) { self.key = key; self.value = value }
}
public enum BadgeJSON: Codable, Equatable, Sendable {
    case object([String: BadgeJSON]), orderedObject([BadgeJSONMember]), array([BadgeJSON]), string(String), int(Int), number(Double), bool(Bool), null
    public init(from decoder: any Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let v = try? c.decode(Bool.self) { self = .bool(v) }
        else if let v = try? c.decode(Int.self) { self = .int(v) }
        else if let v = try? c.decode(Double.self), v.isFinite { self = .number(v) }
        else if let v = try? c.decode(String.self) { self = .string(v) }
        else if let v = try? c.decode([BadgeJSON].self) { self = .array(v) }
        else { self = .object(try c.decode([String: BadgeJSON].self)) }
    }
    public func encode(to encoder: any Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .object(let v): try c.encode(v)
        case .orderedObject(let v): try c.encode(Dictionary(uniqueKeysWithValues: v.map { ($0.key, $0.value) }))
        case .array(let v): try c.encode(v)
        case .string(let v): try c.encode(v)
        case .int(let v): try c.encode(v)
        case .number(let v): try c.encode(v)
        case .bool(let v): try c.encode(v)
        case .null: try c.encodeNil()
        }
    }
    public var object: [String: BadgeJSON] {
        switch self { case .object(let v): v; case .orderedObject(let v): Dictionary(uniqueKeysWithValues: v.map { ($0.key, $0.value) }); default: [:] }
    }
    public var array: [BadgeJSON] { if case .array(let v) = self { v } else { [] } }
    public var string: String { if case .string(let v) = self { v } else { "" } }
    public var integer: Int? { if case .int(let v) = self { v } else { nil } }
    public var double: Double? { switch self { case .number(let v): v; case .int(let v): Double(v); default: nil } }
    public var boolean: Bool? { if case .bool(let v) = self { v } else { nil } }
}

public enum BadgeWire {
    public static func parse(_ text: String, limit: Int = 4_194_304) throws -> BadgeJSON {
        guard text.utf8.count <= limit else { throw BadgeProtocolError.oversized }
        var parser = Parser(bytes: Array(text.utf8))
        let value = try parser.value(depth: 0)
        parser.whitespace()
        guard parser.index == parser.bytes.count else { throw BadgeProtocolError.malformed }
        return value
    }
    /// Dart jsonEncode does not escape forward slashes or non-ASCII Unicode.
    public static func quote(_ text: String) -> String {
        var out = "\""
        for scalar in text.unicodeScalars {
            switch scalar.value {
            case 34: out += "\\\""
            case 92: out += "\\\\"
            case 8: out += "\\b"
            case 9: out += "\\t"
            case 10: out += "\\n"
            case 12: out += "\\f"
            case 13: out += "\\r"
            case 0...31: out += String(format: "\\u%04x", scalar.value)
            default: out.unicodeScalars.append(scalar)
            }
        }
        return out + "\""
    }
    public static func json(_ value: BadgeJSON) -> String {
        switch value {
        case .object(let v): ordered(v.keys.sorted().map { ($0, v[$0]!) })
        case .orderedObject(let v): ordered(v.map { ($0.key, $0.value) })
        case .array(let v): "[" + v.map(json).joined(separator: ",") + "]"
        case .string(let v): quote(v)
        case .int(let v): String(v)
        case .number(let v): doubleText(v)
        case .bool(let v): v ? "true" : "false"
        case .null: "null"
        }
    }
    public static func ordered(_ fields: [(String, BadgeJSON)]) -> String {
        "{" + fields.map { quote($0.0) + ":" + json($0.1) }.joined(separator: ",") + "}"
    }
    public static func canonical(_ map: [String: BadgeJSON]) -> String { json(.object(map)) }
    /// Dart uses decimal notation in [1e-6,1e21) and shortest scientific
    /// notation elsewhere, without zero-padded exponents.
    public static func doubleText(_ value: Double) -> String {
        guard value.isFinite else { return "null" }
        if value == 0 { return value.sign == .minus ? "-0.0" : "0.0" }
        let raw = String(value).lowercased(), parts = raw.components(separatedBy: "e")
        guard parts.count == 2, let exponent = Int(parts[1]) else { return raw }
        let magnitude = abs(value)
        if magnitude >= 0.000001 && magnitude < 1e21 {
            let negative = parts[0].hasPrefix("-"), mantissa = parts[0].trimmingCharacters(in: CharacterSet(charactersIn: "-"))
            let components = mantissa.components(separatedBy: "."), digits = components.joined(), point = components[0].count + exponent
            let number: String
            if point <= 0 { number = "0." + String(repeating: "0", count: -point) + digits }
            else if point >= digits.count { number = digits + String(repeating: "0", count: point - digits.count) + ".0" }
            else { let split = digits.index(digits.startIndex, offsetBy: point); number = String(digits[..<split]) + "." + digits[split...] }
            return (negative ? "-" : "") + number
        }
        let mantissa = parts[0].hasSuffix(".0") ? String(parts[0].dropLast(2)) : parts[0]
        return mantissa + "e" + (exponent >= 0 ? "+" : "-") + String(abs(exponent))
    }
    /// Strict lexical numbers prevent 100.0/1e2 timestamps from silently
    /// decoding as integer 100. Object order is retained below the canonical
    /// top level, exactly as Dart's canonicalJsonEncode does.
    private struct Parser {
        let bytes: [UInt8]; var index = 0
        mutating func whitespace() { while index < bytes.count, [UInt8(9),10,13,32].contains(bytes[index]) { index += 1 } }
        mutating func consume(_ byte: UInt8) throws { whitespace(); guard index < bytes.count, bytes[index] == byte else { throw BadgeProtocolError.malformed }; index += 1 }
        mutating func string() throws -> String {
            whitespace(); let start = index; try consume(34)
            while index < bytes.count {
                let byte = bytes[index]; index += 1
                if byte == 34 { return try JSONDecoder().decode(String.self, from: Data(bytes[start..<index])) }
                if byte == 92 { guard index < bytes.count else { throw BadgeProtocolError.malformed }; index += 1 }
            }
            throw BadgeProtocolError.malformed
        }
        mutating func value(depth: Int) throws -> BadgeJSON {
            guard depth <= 64 else { throw BadgeProtocolError.oversized }; whitespace()
            guard index < bytes.count else { throw BadgeProtocolError.malformed }
            switch bytes[index] {
            case 34: return .string(try string())
            case 123:
                index += 1; whitespace(); var members: [BadgeJSONMember] = [], seen: Set<String> = []
                if index < bytes.count, bytes[index] == 125 { index += 1; return .orderedObject([]) }
                while true {
                    let key = try string(); guard seen.insert(key).inserted else { throw BadgeProtocolError.malformed }
                    try consume(58); members.append(BadgeJSONMember(key, try value(depth: depth + 1))); whitespace()
                    guard index < bytes.count else { throw BadgeProtocolError.malformed }
                    if bytes[index] == 125 { index += 1; return .orderedObject(members) }; try consume(44)
                }
            case 91:
                index += 1; whitespace(); var values: [BadgeJSON] = []
                if index < bytes.count, bytes[index] == 93 { index += 1; return .array([]) }
                while true {
                    values.append(try value(depth: depth + 1)); whitespace()
                    guard index < bytes.count else { throw BadgeProtocolError.malformed }
                    if bytes[index] == 93 { index += 1; return .array(values) }; try consume(44)
                }
            case 116: try literal("true"); return .bool(true)
            case 102: try literal("false"); return .bool(false)
            case 110: try literal("null"); return .null
            default:
                let start = index
                while index < bytes.count, ![UInt8(9),10,13,32,44,93,125].contains(bytes[index]) { index += 1 }
                let data = Data(bytes[start..<index]), text = String(decoding: data, as: UTF8.self)
                guard !data.isEmpty else { throw BadgeProtocolError.malformed }
                let number = try JSONDecoder().decode(Double.self, from: data)
                guard number.isFinite else { throw BadgeProtocolError.malformed }
                if !text.contains("."), !text.lowercased().contains("e"), let integer = Int(text) { return .int(integer) }
                return .number(number)
            }
        }
        mutating func literal(_ text: String) throws {
            let literal = Array(text.utf8)
            guard index + literal.count <= bytes.count, bytes[index..<(index + literal.count)].elementsEqual(literal) else { throw BadgeProtocolError.malformed }
            index += literal.count
        }
    }
    public static func sha256(_ text: String) -> String { NostrHex.encode(Data(SHA256.hash(data: Data(text.utf8)))) }
    public static func isoDate(_ date: Date) -> String {
        let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.string(from: date)
    }
    public static func date(_ text: String) -> Date? {
        let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: text) { return d }
        f.formatOptions = [.withInternetDateTime]
        if let d = f.date(from: text) { return d }
        // Flutter local DateTime.toIso8601String omits the timezone suffix.
        let local = DateFormatter(); local.locale = Locale(identifier: "en_US_POSIX")
        local.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS"
        if let d = local.date(from: text) { return d }
        local.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS"
        return local.date(from: text)
    }
}

/// Full Flutter wallet schema. No signature or claim field is discarded.
public struct CollectedBadge: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var meetupName: String
    public var date: String
    public var iconPath = ""
    public var coverUrl = ""
    public var blockHeight = 0
    public var signerNpub = ""
    public var meetupEventId = ""
    public var delivery = "nfc"
    public var sig = ""
    public var sigId = ""
    public var adminPubkey = ""
    public var sigVersion = 0
    public var sigContent = ""
    public var claimSig = ""
    public var claimEventId = ""
    public var claimPubkey = ""
    public var claimTimestamp = 0
    public var isRetroactive = false
    public var presenceVerified = true
    public var isOrganizer = false
    public var isEvent = false
    public var lat: Double = 0
    public var lng: Double = 0
    public var dateValue: Date { BadgeWire.date(date) ?? .distantPast }
    public var identityKey: String {
        if !meetupEventId.isEmpty { return "evt:" + meetupEventId }
        if !sigId.isEmpty { return "sig:" + sigId }
        return "nd:" + meetupName.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() + "|" + String(date.prefix(10))
    }
    public var unionKey: String { (isOrganizer ? "org:" : "") + identityKey }
    public var claimProofId: String { claimSig.isEmpty || claimPubkey.isEmpty ? "" : BadgeWire.sha256(sig + "|" + claimSig + "|" + claimPubkey) }
    public init(id: String, meetupName: String, date: String) { self.id = id; self.meetupName = meetupName; self.date = date }
    public init(json: [String: BadgeJSON]) throws {
        guard let id = json["id"], case .string = id, let name = json["meetupName"], case .string = name,
              let date = json["date"], BadgeWire.date(date.string) != nil else { throw BadgeProtocolError.malformed }
        self.init(id: id.string, meetupName: name.string, date: date.string)
        iconPath = json["iconPath"]?.string ?? ""; coverUrl = json["coverUrl"]?.string ?? ""
        blockHeight = json["blockHeight"]?.integer ?? 0; signerNpub = json["signerNpub"]?.string ?? ""
        meetupEventId = json["meetupEventId"]?.string ?? ""; delivery = json["delivery"]?.string ?? "nfc"
        sig = json["sig"]?.string ?? ""; sigId = json["sigId"]?.string ?? ""
        adminPubkey = json["adminPubkey"]?.string ?? ""; sigVersion = json["sigVersion"]?.integer ?? 0
        sigContent = json["sigContent"]?.string ?? ""; claimSig = json["claimSig"]?.string ?? ""
        claimEventId = json["claimEventId"]?.string ?? ""; claimPubkey = json["claimPubkey"]?.string ?? ""
        claimTimestamp = json["claimTimestamp"]?.integer ?? 0; isRetroactive = json["isRetroactive"]?.boolean ?? false
        presenceVerified = json["presenceVerified"]?.boolean ?? true; isOrganizer = json["isOrganizer"]?.boolean ?? false
        isEvent = json["isEvent"]?.boolean ?? false; lat = json["lat"]?.double ?? 0; lng = json["lng"]?.double ?? 0
        guard lat.isFinite, lng.isFinite, abs(lat) <= 90, abs(lng) <= 180 else { throw BadgeProtocolError.malformed }
    }
    public var fields: [(String, BadgeJSON)] { [
        ("id", .string(id)), ("meetupName", .string(meetupName)), ("date", .string(date)),
        ("iconPath", .string(iconPath)), ("coverUrl", .string(coverUrl)), ("blockHeight", .int(blockHeight)),
        ("signerNpub", .string(signerNpub)), ("meetupEventId", .string(meetupEventId)), ("delivery", .string(delivery)),
        ("sig", .string(sig)), ("sigId", .string(sigId)), ("adminPubkey", .string(adminPubkey)),
        ("sigVersion", .int(sigVersion)), ("sigContent", .string(sigContent)), ("claimSig", .string(claimSig)),
        ("claimEventId", .string(claimEventId)), ("claimPubkey", .string(claimPubkey)), ("claimTimestamp", .int(claimTimestamp)),
        ("isRetroactive", .bool(isRetroactive)), ("presenceVerified", .bool(presenceVerified)),
        ("isOrganizer", .bool(isOrganizer)), ("isEvent", .bool(isEvent)), ("lat", .number(lat)), ("lng", .number(lng))
    ] }
    public var wireJSON: String { BadgeWire.ordered(fields) }
    public var json: BadgeJSON { .object(Dictionary(uniqueKeysWithValues: fields)) }
    public static func merge(_ base: [Self], _ extra: [Self]) -> [Self] {
        var result: [String: Self] = [:]
        for badge in base + extra {
            guard let old = result[badge.unionKey] else { result[badge.unionKey] = badge; continue }
            let replace: Bool
            if old.claimSig.isEmpty != badge.claimSig.isEmpty { replace = old.claimSig.isEmpty }
            else if old.presenceVerified != badge.presenceVerified { replace = badge.presenceVerified }
            else if old.dateValue != badge.dateValue { replace = badge.dateValue < old.dateValue }
            else if old.claimTimestamp != badge.claimTimestamp { replace = badge.claimTimestamp < old.claimTimestamp }
            else { replace = badge.id < old.id }
            if replace { result[badge.unionKey] = badge }
        }
        return result.values.sorted { $0.dateValue == $1.dateValue ? $0.id < $1.id : $0.dateValue < $1.dateValue }
    }
}

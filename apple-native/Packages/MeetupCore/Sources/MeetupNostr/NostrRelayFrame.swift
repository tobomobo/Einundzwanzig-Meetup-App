import Foundation

/// Extracts array members from original UTF-8. Event numbers must reach the
/// strict NIP-01 decoder without a Foundation floating-point round trip.
enum NostrRelayFrame {
    case event(String, NostrEvent), auth(String), ok(String, Bool, String), eose(String), closed(String, String), ignored
    static func decode(_ data: Data) throws -> Self {
        guard data.count <= 1024 * 1024 else { throw NostrRelayError.invalidFrame }
        _ = try JSONSerialization.jsonObject(with: data)
        let members = try split(data)
        guard let first = members.first, let kind = try? JSONDecoder().decode(String.self, from: first) else { return .ignored }
        func string(_ index: Int) throws -> String {
            guard index < members.count else { throw NostrRelayError.invalidFrame }
            return try JSONDecoder().decode(String.self, from: members[index])
        }
        switch kind {
        case "EVENT":
            guard members.count == 3 else { return .ignored }
            guard let event = try? NostrEvent.decodeJSON(members[2]) else { return .ignored }
            return .event(try string(1), event)
        case "AUTH": return .auth(try string(1))
        case "OK":
            guard members.count >= 4 else { throw NostrRelayError.invalidFrame }
            return .ok(try string(1), try JSONDecoder().decode(Bool.self, from: members[2]), try string(3))
        case "EOSE": return .eose(try string(1))
        case "CLOSED": return .closed(try string(1), try string(2))
        default: return .ignored
        }
    }
    private static func split(_ data: Data) throws -> [Data] {
        let bytes = Array(data)
        var index = 0
        func whitespace(_ c: UInt8) -> Bool { [9,10,13,32].contains(c) }
        while index < bytes.count, whitespace(bytes[index]) { index += 1 }
        guard index < bytes.count, bytes[index] == 91 else { throw NostrRelayError.invalidFrame }
        index += 1
        var start = index, depth = 0, quoted = false, escaped = false
        var output: [Data] = []
        while index < bytes.count {
            let c = bytes[index]
            if quoted {
                if escaped { escaped = false }
                else if c == 92 { escaped = true }
                else if c == 34 { quoted = false }
            } else if c == 34 { quoted = true }
            else if c == 123 || c == 91 { depth += 1 }
            else if c == 125 || c == 93 {
                if c == 93, depth == 0 {
                    if index > start { output.append(Data(bytes[start..<index])) }
                    return output
                }
                depth -= 1
            } else if c == 44, depth == 0 { output.append(Data(bytes[start..<index])); start = index + 1 }
            guard depth >= 0, depth <= 64, output.count < 16 else { throw NostrRelayError.invalidFrame }
            index += 1
        }
        throw NostrRelayError.invalidFrame
    }
}

import Foundation

/// A single-event RFC 5545 document, independent of app and UI models.
struct CalendarICS: Sendable {
    enum EncodingError: Error, Sendable {
        case invalidDate
    }

    private let uid: String
    private let title: String
    private let description: String
    private let start: Date
    private let end: Date?
    private let location: String
    private let url: URL?

    init(
        uid: String,
        title: String,
        description: String,
        start: Date,
        end: Date?,
        location: String,
        url: URL?
    ) {
        self.uid = uid
        self.title = title
        self.description = description
        self.start = start
        self.end = end
        self.location = location
        self.url = url
    }

    /// Throws for required dates outside finite Gregorian years 1...9999 CE.
    func data(timestamp: Date = Date()) throws -> Data {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyyMMdd'T'HHmmss'Z'"

        guard let startValue = Self.utcDate(start, using: formatter),
              let stampValue = Self.utcDate(timestamp, using: formatter) else {
            throw EncodingError.invalidDate
        }

        // Base64URL is deterministic and lossless, even for empty or hostile IDs.
        // The caller's event identity, rather than its export time, determines UID.
        let safeID = Data(uid.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        var lines = [
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Einundzwanzig//Meetup App//EN",
            "BEGIN:VEVENT",
            "UID:meetup-\(safeID)@einundzwanzig.space",
            "DTSTAMP:\(stampValue)",
            "DTSTART:\(startValue)",
        ]

        // RFC 5545 has second precision: do not emit an equal end after truncation.
        if let end, end > start,
           let endValue = Self.utcDate(end, using: formatter), endValue > startValue {
            lines.append("DTEND:\(endValue)")
        }
        lines.append("SUMMARY:\(Self.escapeText(title))")
        lines.append("DESCRIPTION:\(Self.escapeText(description))")
        lines.append("LOCATION:\(Self.escapeText(location))")
        if let url, let value = Self.uriValue(url) {
            lines.append("URL:\(value)")
        }
        lines.append(contentsOf: ["END:VEVENT", "END:VCALENDAR"])

        return Data((lines.map(Self.fold).joined(separator: "\r\n") + "\r\n").utf8)
    }

    private static func utcDate(_ date: Date, using formatter: DateFormatter) -> String? {
        guard date.timeIntervalSinceReferenceDate.isFinite else { return nil }
        var calendar = formatter.calendar!
        calendar.timeZone = formatter.timeZone
        let parts = calendar.dateComponents([.era, .year], from: date)
        guard parts.era == 1, let year = parts.year, (1...9999).contains(year) else { return nil }
        return formatter.string(from: date)
    }

    // RFC 5545 §3.3.11. Normalize before escaping so CRLF is one newline.
    private static func escapeText(_ text: String) -> String {
        let normalized = text.replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
        var result = ""
        for scalar in normalized.unicodeScalars {
            switch scalar.value {
            case 0x5C: result += "\\\\"
            case 0x0A: result += "\\n"
            case 0x3B: result += "\\;"
            case 0x2C: result += "\\,"
            case 0x00...0x08, 0x0B...0x1F, 0x7F: break // Unsupported ASCII controls.
            default: result.unicodeScalars.append(scalar)
            }
        }
        return result
    }

    private static func uriValue(_ url: URL) -> String? {
        let absolute = url.absoluteURL
        guard absolute.scheme != nil else { return nil }
        // RFC 3986 URI characters, including existing percent escapes. URL values
        // use URI encoding, not TEXT escaping (RFC 5545 §3.3.13).
        let allowed = CharacterSet(charactersIn:
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?#[]@!$&'()*+,;=%"
        )
        return absolute.absoluteString.addingPercentEncoding(withAllowedCharacters: allowed)
    }

    // RFC 5545 §3.1. Iterate scalars: even one grapheme can exceed 75 octets.
    private static func fold(_ line: String) -> String {
        var result = ""
        var octets = 0
        for scalar in line.unicodeScalars {
            let value = String(scalar)
            let count = value.utf8.count
            if octets + count > 75 {
                result += "\r\n "
                octets = 1 // The continuation space consumes one octet.
            }
            result += value
            octets += count
        }
        return result
    }
}

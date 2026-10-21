#if CALENDAR_ICS_TESTS
import Foundation

// Portable executable tests; no XCTest, app module, or Apple SDK required.
@main
private enum CalendarICSTests {
    private struct Failure: Error, CustomStringConvertible {
        let description: String
    }

    static func main() throws {
        let tests: [(String, () throws -> Void)] = [
            ("envelope and CRLF", envelope),
            ("hostile TEXT escaping and injection", hostileText),
            ("unsupported ASCII controls", controls),
            ("stable lossless UID encoding", stableUID),
            ("Unicode folding and unfolding", unicodeFolding),
            ("75-octet folding boundaries", foldingBoundaries),
            ("UTC dates and explicit timestamp", utcDates),
            ("invalid required dates throw", invalidRequiredDates),
            ("optional and invalid end dates", endDates),
            ("URL encoding and injection", urls),
        ]
        for (name, test) in tests {
            try test()
            print("PASS \(name)")
        }
        print("CalendarICS: \(tests.count) tests passed")
    }

    private static func require(_ condition: @autoclosure () -> Bool, _ message: String) throws {
        if !condition() { throw Failure(description: message) }
    }

    private static func document(
        uid: String = "abc", title: String = "Meetup", description: String = "Discussion",
        start: Date = Date(timeIntervalSince1970: 0), end: Date? = nil,
        location: String = "Vienna", url: URL? = nil,
        timestamp: Date = Date(timeIntervalSince1970: 0)
    ) throws -> Data {
        try CalendarICS(uid: uid, title: title, description: description, start: start,
                        end: end, location: location, url: url).data(timestamp: timestamp)
    }

    // Check bytes before decoding: neither CRLF nor UTF-8 scalar boundaries may
    // be repaired by a forgiving decoder or by unfolding an invalid byte split.
    private static func physicalLines(_ data: Data) throws -> [String] {
        let bytes = Array(data)
        try require(bytes.count >= 2 && bytes.suffix(2) == [13, 10], "Missing final CRLF")
        var result: [String] = []
        var start = 0
        var index = 0
        while index < bytes.count {
            if bytes[index] == 13 {
                try require(index + 1 < bytes.count && bytes[index + 1] == 10, "Bare CR")
                let line = bytes[start..<index]
                try require(line.count <= 75, "Physical line exceeds 75 octets: \(line.count)")
                guard let text = String(bytes: line, encoding: .utf8) else {
                    throw Failure(description: "Fold split a UTF-8 code point")
                }
                result.append(text)
                index += 2
                start = index
            } else {
                try require(bytes[index] != 10, "Bare LF")
                index += 1
            }
        }
        try require(start == bytes.count, "Unterminated content line")
        return result
    }

    private static func logicalLines(_ data: Data) throws -> [String] {
        var result: [String] = []
        for line in try physicalLines(data) {
            if line.utf8.first == 32 || line.utf8.first == 9 {
                try require(!result.isEmpty, "Orphaned continuation")
                // Remove a single byte: a leading space and combining mark can
                // form one Swift Character, but unfolding must retain the mark.
                result[result.count - 1] += String(decoding: line.utf8.dropFirst(), as: UTF8.self)
            } else {
                result.append(line)
            }
        }
        return result
    }

    private static func value(_ name: String, in data: Data) throws -> String {
        let prefix = name + ":"
        let matches = try logicalLines(data).filter { $0.hasPrefix(prefix) }
        try require(matches.count == 1, "Expected one \(name), found \(matches.count)")
        return String(decoding: matches[0].utf8.dropFirst(prefix.utf8.count), as: UTF8.self)
    }

    private static func decodeText(_ value: String) throws -> String {
        var result = ""
        var scalars = value.unicodeScalars.makeIterator()
        while let scalar = scalars.next() {
            if scalar.value != 92 {
                result.unicodeScalars.append(scalar)
                continue
            }
            guard let escaped = scalars.next() else { throw Failure(description: "Trailing escape") }
            switch escaped.value {
            case 78, 110: result += "\n"
            case 92, 44, 59: result.unicodeScalars.append(escaped)
            default: throw Failure(description: "Invalid TEXT escape")
            }
        }
        return result
    }

    private static func envelope() throws {
        let lines = try logicalLines(document())
        try require(lines.count == 12, "Unexpected envelope or fabricated properties")
        try require(lines.first == "BEGIN:VCALENDAR" && lines.last == "END:VCALENDAR", "Bad calendar envelope")
        try require(lines.contains("VERSION:2.0") && lines.contains(where: { $0.hasPrefix("PRODID:") }), "Required calendar metadata missing")
        try require(lines.filter { $0 == "BEGIN:VEVENT" }.count == 1, "Expected one event")
        try require(!lines.contains(where: { $0.hasPrefix("DTEND:") || $0.hasPrefix("DURATION:") || $0.contains("TZID=") }), "Unexpected date properties")
    }

    private static func hostileText() throws {
        let data = try document(
            uid: "id\r\nBEGIN:VEVENT\r\nX-EVIL:uid",
            title: "Path\\next; one,two: \"quoted\"\r\nEND:VEVENT\rBEGIN:VTODO\nX-EVIL:yes",
            description: "first\r\nsecond\rthird\nfourth\\n",
            location: "Vienna; AT, room\\3\r\nX-EVIL:yes"
        )
        let title = try value("SUMMARY", in: data)
        let description = try value("DESCRIPTION", in: data)
        let location = try value("LOCATION", in: data)
        try require(title == #"Path\\next\; one\,two: "quoted"\nEND:VEVENT\nBEGIN:VTODO\nX-EVIL:yes"#, "Incorrect hostile title escaping")
        try require(description == #"first\nsecond\nthird\nfourth\\n"#, "Mixed newlines or literal backslash-n corrupted")
        try require(location == #"Vienna\; AT\, room\\3\nX-EVIL:yes"#, "Incorrect location escaping")
        let lines = try logicalLines(data)
        try require(lines.count == 12, "Input injected a content line")
        try require(lines.filter { $0 == "END:VEVENT" }.count == 1, "Input closed the event")
        try require(!lines.contains(where: { $0.hasPrefix("X-EVIL:") || $0 == "BEGIN:VTODO" }), "Injected property survived")
    }

    private static func controls() throws {
        let forbidden = (0...31).filter { ![9, 10, 13].contains($0) } + [127]
        let text = forbidden.map { String(UnicodeScalar($0)!) }.joined()
        let result = try value("SUMMARY", in: document(title: "A\(text)B\tC"))
        try require(result == "AB\tC", "Unsupported controls survived or HTAB was lost")
    }

    private static func stableUID() throws {
        let known = try value("UID", in: document())
        try require(known == "meetup-YWJj@einundzwanzig.space", "UID format changed")
        let changed = try value("UID", in: document(title: "Changed", start: Date(timeIntervalSince1970: 300), timestamp: Date(timeIntervalSince1970: 900)))
        try require(known == changed, "UID depends on export time or event content")
        let ids = ["", "a", "A", "a/b", "a?b", "a+b", "a_b", "a-b", "\\", "\r\nBEGIN:VEVENT", "é", "e\u{301}", "👩🏽‍💻", String(repeating: "x", count: 512)]
        var seen: Set<String> = []
        for id in ids {
            let encoded = try value("UID", in: document(uid: id))
            try require(seen.insert(encoded).inserted, "Distinct raw IDs collided")
            try require(encoded.unicodeScalars.allSatisfy { $0.isASCII && (CharacterSet.alphanumerics.contains($0) || "-_@.".unicodeScalars.contains($0)) }, "Unsafe UID alphabet")
            let payload = encoded.dropFirst("meetup-".count).dropLast("@einundzwanzig.space".count)
            var base64 = payload.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
            base64 += String(repeating: "=", count: (4 - base64.utf8.count % 4) % 4)
            try require(Data(base64Encoded: base64) == Data(id.utf8), "UID lost raw UTF-8 bytes")
        }
    }

    private static func unicodeFolding() throws {
        // Include a single grapheme larger than the line budget, and a scalar
        // that cannot fit into the first line's remaining single octet.
        let title = String(repeating: "a", count: 66) + String(repeating: "ä界🟠", count: 45)
            + "e" + String(repeating: "\u{301}", count: 100)
        let description = String(repeating: "👩🏽‍💻\\;,,\n", count: 40)
        let data = try document(title: title, description: description)
        let physical = try physicalLines(data)
        try require(physical.filter { $0.utf8.first == 32 }.count > 10, "Unicode fixture was not folded")
        let summary = try value("SUMMARY", in: data)
        let decoded = try decodeText(value("DESCRIPTION", in: data))
        try require(Array(summary.utf8) == Array(title.utf8), "Unfolding changed Unicode bytes")
        try require(Array(decoded.utf8) == Array(description.utf8), "Escaped Unicode did not round-trip")
    }

    private static func foldingBoundaries() throws {
        for (count, expected) in [(67, [75]), (68, [75, 2]), (142, [75, 75, 2])] {
            let lines = try physicalLines(document(title: String(repeating: "a", count: count)))
            let index = lines.firstIndex { $0.hasPrefix("SUMMARY:") }!
            var lengths = [lines[index].utf8.count]
            var next = index + 1
            while next < lines.count && lines[next].utf8.first == 32 {
                lengths.append(lines[next].utf8.count)
                next += 1
            }
            try require(lengths == expected, "Wrong folding boundary for \(count): \(lengths)")
        }
    }

    private static func utcDates() throws {
        let parser = ISO8601DateFormatter()
        let start = parser.date(from: "2026-10-21T19:42:03+05:30")!
        let end = parser.date(from: "2026-10-21T21:12:04+05:30")!
        let data = try document(start: start, end: end)
        let stamp = try value("DTSTAMP", in: data)
        let startValue = try value("DTSTART", in: data)
        let endValue = try value("DTEND", in: data)
        try require(stamp == "19700101T000000Z", "DTSTAMP ignored explicit timestamp")
        try require(startValue == "20261021T141203Z", "DTSTART was not UTC")
        try require(endValue == "20261021T154204Z", "DTEND was not UTC")
        let rollover = parser.date(from: "2024-12-31T23:59:59-02:00")!
        let rolloverValue = try value("DTSTART", in: document(start: rollover))
        try require(rolloverValue == "20250101T015959Z", "UTC conversion missed year rollover")
    }

    private static func invalidRequiredDates() throws {
        let invalid = [Date(timeIntervalSince1970: .nan), Date(timeIntervalSince1970: .infinity),
                       Date(timeIntervalSince1970: -.infinity),
                       Date(timeIntervalSince1970: 253_402_300_800), // Year 10000.
                       Date(timeIntervalSince1970: -100_000_000_000)] // Before CE.
        for date in invalid {
            do {
                _ = try document(start: date)
                throw Failure(description: "Invalid start did not throw")
            } catch CalendarICS.EncodingError.invalidDate {
                // The serializer must fail without terminating the process.
            }
            do {
                _ = try document(timestamp: date)
                throw Failure(description: "Invalid timestamp did not throw")
            } catch CalendarICS.EncodingError.invalidDate {
                // The timestamp has the same validity requirements as DTSTART.
            }
        }
    }

    private static func endDates() throws {
        let start = Date(timeIntervalSince1970: 100.25)
        let invalid: [Date?] = [nil, start, start.addingTimeInterval(-1),
                               Date(timeIntervalSince1970: 100.75),
                               Date(timeIntervalSince1970: .nan), Date(timeIntervalSince1970: .infinity),
                               Date(timeIntervalSince1970: -.infinity),
                               Date(timeIntervalSince1970: 253_402_300_800)] // Year 10000.
        for end in invalid {
            let lines = try logicalLines(document(start: start, end: end))
            try require(!lines.contains(where: { $0.hasPrefix("DTEND:") || $0.hasPrefix("DURATION:") }), "Invalid/absent end produced a duration")
        }
        let endValue = try value("DTEND", in: document(start: start, end: Date(timeIntervalSince1970: 101.25)))
        try require(endValue == "19700101T000141Z", "Valid end missing or fabricated")
    }

    private static func urls() throws {
        let original = "https://example.com/a,b;c?q=x,y;z&escaped=%2F#fragment"
        let actual = try value("URL", in: document(url: URL(string: original)!))
        try require(actual == original, "URI delimiters or existing escapes were changed")
        var components = URLComponents(string: "https://example.com")!
        components.path = "/meetup\r\nX-EVIL:yes/ä "
        let data = try document(url: components.url!)
        let url = try value("URL", in: data)
        try require(url.contains("%0D%0A") && url.contains("%C3%A4%20"), "Unsafe URL characters were not percent encoded")
        try require(!url.contains("\\") && !url.contains("\r") && !url.contains("\n"), "URL used TEXT escaping or raw newlines")
        let lines = try logicalLines(data)
        try require(lines.count == 13 && !lines.contains(where: { $0.hasPrefix("X-EVIL:") }), "URL injected a property")
        let relativeLines = try logicalLines(document(url: URL(string: "relative/path")!))
        try require(!relativeLines.contains(where: { $0.hasPrefix("URL:") }), "Relative URL produced an invalid URI property")
        let based = URL(string: "relative", relativeTo: URL(string: "https://example.com/base/")!)!
        let absolute = try value("URL", in: document(url: based))
        try require(absolute == "https://example.com/base/relative", "Base URL was not resolved")
    }
}
#endif

import Foundation

enum DateParsing {
    /// Portal database dates without an offset are UTC, never device-local time.
    static func portal(_ text: String?) -> Date? {
        guard let text else { return nil }
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let pattern = #"^([0-9]{4})-([0-9]{2})-([0-9]{2})(?:[Tt ]([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:\.([0-9]{1,9}))?)?)?([Zz]|[+-][0-9]{2}:?[0-9]{2})?$"#
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: value, range: NSRange(value.startIndex..., in: value)) else { return nil }
        func group(_ index: Int) -> String? {
            guard let range = Range(match.range(at: index), in: value) else { return nil }
            return String(value[range])
        }
        guard let year = group(1).flatMap(Int.init), year >= 1,
              let month = group(2).flatMap(Int.init),
              let day = group(3).flatMap(Int.init) else { return nil }
        let hour = group(4).flatMap(Int.init) ?? 0
        let minute = group(5).flatMap(Int.init) ?? 0
        let second = group(6).flatMap(Int.init) ?? 0
        guard (1...12).contains(month), (1...31).contains(day),
              (0...23).contains(hour), (0...59).contains(minute), (0...59).contains(second) else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let parts = DateComponents(year: year, month: month, day: day, hour: hour, minute: minute, second: second)
        guard let naive = calendar.date(from: parts) else { return nil }
        let actual = calendar.dateComponents([.year, .month, .day], from: naive)
        guard actual.year == year, actual.month == month, actual.day == day else { return nil }
        var offset = 0
        if let zone = group(8), zone != "Z", zone != "z" {
            let digits = zone.dropFirst().replacingOccurrences(of: ":", with: "")
            guard let hours = Int(digits.prefix(2)), let minutes = Int(digits.suffix(2)),
                  hours <= 23, minutes <= 59 else { return nil }
            offset = (hours * 3600 + minutes * 60) * (zone.first == "-" ? -1 : 1)
        }
        let fraction = group(7).flatMap { Double("0." + $0) } ?? 0
        return naive.addingTimeInterval(fraction - Double(offset))
    }

    static func rss(_ text: String?) -> Date? {
        guard let text else { return nil }
        if let iso = portal(text) { return iso }
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let pattern = #"^(?:[A-Za-z]{3},?\s+)?([0-9]{1,2})\s+([A-Za-z]{3})\s+([0-9]{2,4})\s+([0-9]{2}):([0-9]{2})(?::([0-9]{2}))?\s+([A-Za-z]{1,3}|[+-][0-9]{2}:?[0-9]{2})$"#
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: value, range: NSRange(value.startIndex..., in: value)) else { return nil }
        func group(_ index: Int) -> String? {
            Range(match.range(at: index), in: value).map { String(value[$0]) }
        }
        let months = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"]
        guard let day = group(1).flatMap(Int.init), let monthText = group(2)?.lowercased(),
              let month = months.firstIndex(of: monthText).map({ $0 + 1 }),
              let yearText = group(3), var year = Int(yearText),
              let hour = group(4).flatMap(Int.init), let minute = group(5).flatMap(Int.init),
              let zone = group(7)?.uppercased() else { return nil }
        if yearText.count == 2 { year += year < 50 ? 2000 : 1900 }
        if yearText.count == 3 { year += 1900 }
        let second = group(6).flatMap(Int.init) ?? 0
        let namedZones = ["UT": 0, "GMT": 0, "Z": 0, "EST": -300, "EDT": -240, "CST": -360,
                          "CDT": -300, "MST": -420, "MDT": -360, "PST": -480, "PDT": -420]
        let suffix: String
        if let offset = namedZones[zone] {
            suffix = String(format: "%@%02d:%02d", offset < 0 ? "-" : "+", abs(offset) / 60, abs(offset) % 60)
        } else if zone.first == "+" || zone.first == "-" { suffix = zone }
        else { return nil }
        return portal(String(format: "%04d-%02d-%02dT%02d:%02d:%02d", year, month, day, hour, minute, second) + suffix)
    }
}

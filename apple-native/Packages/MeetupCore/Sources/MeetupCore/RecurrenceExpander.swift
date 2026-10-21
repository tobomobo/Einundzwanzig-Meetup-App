import Foundation

struct RecurrenceExpansion {
    var starts: [Date]
    var warnings: [CalendarFeedWarning]

    fileprivate mutating func warn(_ cause: CalendarFeedWarning.Cause) {
        if !warnings.contains(where: { $0.cause == cause }) {
            warnings.append(CalendarFeedWarning(source: .ical, cause: cause))
        }
    }
}

/// Conservative, dependency-free expansion of the meetup feed's weekly/monthly rules.
/// Unlike Flutter's Duration arithmetic, occurrences retain local wall time (including
/// seconds), COUNT=1 means only DTSTART, and datetime UNTIL retains its exact time.
/// DTSTART always survives. Results are oldest-first, including for old series; hitting
/// either output/work budget is explicit rather than silently dropping old occurrences.
enum RecurrenceExpander {
    private static let maximumOccurrences = 200
    private static let maximumPeriods = 4_096

    static func expand(start: Date, rule: String, timeZone: TimeZone, now: Date,
                       isAllDay: Bool = false) -> RecurrenceExpansion {
        var result = RecurrenceExpansion(starts: [start], warnings: [])
        let text = rule.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            result.warn(.malformedRecurrence)
            return result
        }

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let parsed: Rule
        do {
            parsed = try Rule(text, calendar: calendar)
        } catch let failure as RuleFailure {
            result.warn(failure.cause)
            return result
        } catch {
            result.warn(.malformedRecurrence)
            return result
        }

        guard supportedDate(start, calendar: calendar), supportedDate(now, calendar: calendar),
              let horizon = calendar.date(byAdding: .day, value: 366, to: now),
              supportedDate(horizon, calendar: calendar) else {
            result.warn(.invalidDate)
            return result
        }
        // Validate the complete rule before this early return, even for COUNT=1.
        if parsed.count == 1 { return result }
        let horizonIsLimit = parsed.until.map { $0.date > horizon } ?? true
        let limit = horizonIsLimit ? Limit(date: horizon, inclusive: true) : parsed.until!
        func finish(limited: Bool = false) -> RecurrenceExpansion {
            if limited { result.warn(.recurrenceLimit) }
            result.starts = Array(Set(result.starts)).sorted()
            return result
        }
        guard limit.contains(start) else { return finish(limited: horizonIsLimit) }

        // UTC anchors represent civil dates only. Construct every actual occurrence in
        // the supplied zone; this also handles zones that skipped an entire civil day.
        var civil = Calendar(identifier: .gregorian)
        civil.timeZone = TimeZone(secondsFromGMT: 0)!
        let base = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: start)
        // DATE UNTIL uses an exclusive next-day boundary. Exclude that civil day before
        // constructing candidates, so a skipped day outside the rule cannot cause a warning.
        let lastIncludedInstant = limit.inclusive ? limit.date : limit.date.addingTimeInterval(-1)
        let end = calendar.dateComponents([.year, .month, .day], from: lastIncludedInstant)
        guard let baseDay = civil.date(from: DateComponents(year: base.year, month: base.month, day: base.day)),
              let endDay = civil.date(from: end) else {
            result.warn(.invalidDate)
            return result
        }
        let fraction = isAllDay ? 0 : start.timeIntervalSinceReferenceDate - floor(start.timeIntervalSinceReferenceDate)
        let weekday = mondayWeekday(civil.component(.weekday, from: baseDay))
        var anchor: Date
        switch parsed.frequency {
        case .weekly:
            guard let date = civil.date(byAdding: .day, value: 1 - weekday, to: baseDay) else {
                result.warn(.invalidDate)
                return result
            }
            anchor = date
        case .monthly:
            guard let date = civil.date(from: DateComponents(year: base.year, month: base.month, day: 1)) else {
                result.warn(.invalidDate)
                return result
            }
            anchor = date
        }

        // Scan active weeks/months, never days between DTSTART and today. Dense old
        // series stop at the occurrence cap; sparse/impossible patterns stop at this
        // independent work cap. Huge INTERVALs are compared before any multiplication.
        for _ in 0..<maximumPeriods {
            if anchor > endDay { return finish(limited: horizonIsLimit) }
            var candidateDays: [Date] = []
            switch parsed.frequency {
            case .weekly:
                let weekdays = parsed.days.isEmpty ? [weekday] : Array(Set(parsed.days.map(\.weekday))).sorted()
                for day in weekdays {
                    if let date = civil.date(byAdding: .day, value: day - 1, to: anchor) {
                        candidateDays.append(date)
                    } else { result.warn(.invalidDate) }
                }
            case .monthly:
                let month = civil.dateComponents([.year, .month], from: anchor)
                guard let range = civil.range(of: .day, in: .month, for: anchor) else {
                    result.warn(.invalidDate)
                    return result
                }
                let last = range.count
                var days = Set<Int>()
                if parsed.days.isEmpty {
                    if let day = base.day, day <= last { days.insert(day) }
                } else {
                    let firstWeekday = mondayWeekday(civil.component(.weekday, from: anchor))
                    let lastWeekday = (firstWeekday - 1 + last - 1) % 7 + 1
                    for selector in parsed.days {
                        let first = 1 + (selector.weekday - firstWeekday + 7) % 7
                        if let ordinal = selector.ordinal {
                            let day = ordinal > 0
                                ? first + (ordinal - 1) * 7
                                : last - (lastWeekday - selector.weekday + 7) % 7 + (ordinal + 1) * 7
                            if (1...last).contains(day) { days.insert(day) }
                        } else {
                            days.formUnion(stride(from: first, through: last, by: 7))
                        }
                    }
                }
                for day in days.sorted() {
                    if let date = civil.date(from: DateComponents(year: month.year, month: month.month, day: day)) {
                        candidateDays.append(date)
                    } else { result.warn(.invalidDate) }
                }
            }

            // Select chronologically before applying COUNT, independent of BYDAY order.
            for day in candidateDays where day >= baseDay && day <= endDay {
                let parts = civil.dateComponents([.year, .month, .day], from: day)
                // DATE recurs by civil day, not by DTSTART's clock hour. Some days start at
                // 01:00; that neither removes the occurrence nor shifts later days to 01:00.
                let candidate = isAllDay
                    ? ICalendarDates.startOfDay(year: parts.year!, month: parts.month!, day: parts.day!, calendar: calendar)
                    : strictDate(year: parts.year!, month: parts.month!, day: parts.day!,
                                 hour: base.hour!, minute: base.minute!, second: base.second!, calendar: calendar)
                guard let date = candidate else {
                    result.warn(.invalidDate)
                    continue
                }
                let occurrence = date.addingTimeInterval(fraction)
                guard occurrence > start, limit.contains(occurrence) else { continue }
                guard !result.starts.contains(occurrence) else { continue }
                if result.starts.count == maximumOccurrences { return finish(limited: true) }
                result.starts.append(occurrence)
                if result.starts.count == parsed.count { return finish() }
            }

            let component: Calendar.Component
            let step: Int
            switch parsed.frequency {
            case .weekly:
                let remaining = civil.dateComponents([.day], from: anchor, to: endDay).day ?? 0
                guard parsed.interval <= remaining / 7 else { return finish(limited: horizonIsLimit) }
                component = .day
                step = parsed.interval * 7
            case .monthly:
                let current = civil.dateComponents([.year, .month], from: anchor)
                let remaining = (end.year! - current.year!) * 12 + end.month! - current.month!
                guard parsed.interval <= remaining else { return finish(limited: horizonIsLimit) }
                component = .month
                step = parsed.interval
            }
            guard let next = civil.date(byAdding: component, value: step, to: anchor), next > anchor else {
                result.warn(.invalidDate)
                return result
            }
            anchor = next
        }
        return finish(limited: true)
    }

    private enum Frequency: String { case weekly = "WEEKLY", monthly = "MONTHLY" }
    private struct Day: Hashable { var weekday: Int; var ordinal: Int? }
    private struct Limit {
        var date: Date
        var inclusive: Bool
        func contains(_ value: Date) -> Bool { inclusive ? value <= date : value < date }
    }
    private struct RuleFailure: Error { var cause: CalendarFeedWarning.Cause }
    private struct Rule {
        var frequency: Frequency
        var interval: Int
        var count: Int?
        var until: Limit?
        var days: [Day]

        init(_ text: String, calendar: Calendar) throws {
            let clean = text.uppercased().hasPrefix("RRULE:") ? String(text.dropFirst(6)) : text
            var fields: [String: String] = [:]
            for token in clean.split(separator: ";", omittingEmptySubsequences: false) {
                let pair = token.split(separator: "=", omittingEmptySubsequences: false)
                guard pair.count == 2 else { throw RuleFailure(cause: .malformedRecurrence) }
                let key = pair[0].trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
                let value = pair[1].trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
                guard !key.isEmpty, !value.isEmpty, fields[key] == nil else {
                    throw RuleFailure(cause: .malformedRecurrence)
                }
                fields[key] = value
            }
            guard fields.keys.allSatisfy({ ["FREQ", "INTERVAL", "BYDAY", "COUNT", "UNTIL"].contains($0) }) else {
                throw RuleFailure(cause: .unsupportedRecurrence)
            }
            guard let rawFrequency = fields["FREQ"] else { throw RuleFailure(cause: .malformedRecurrence) }
            guard let frequency = Frequency(rawValue: rawFrequency) else {
                throw RuleFailure(cause: .unsupportedRecurrence)
            }
            self.frequency = frequency
            interval = try fields["INTERVAL"].map(Self.positiveInteger) ?? 1
            count = try fields["COUNT"].map(Self.positiveInteger)
            // RFC 5545 disallows combining these bounds; do not guess which one wins.
            guard count == nil || fields["UNTIL"] == nil else { throw RuleFailure(cause: .malformedRecurrence) }
            until = try fields["UNTIL"].map { try Self.parseUntil($0, calendar: calendar) }
            var days = Set<Day>()
            if let byDay = fields["BYDAY"] {
                let weekdays = ["MO", "TU", "WE", "TH", "FR", "SA", "SU"]
                for token in byDay.split(separator: ",", omittingEmptySubsequences: false) {
                    let value = token.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard value.count >= 2, let index = weekdays.firstIndex(of: String(value.suffix(2))) else {
                        throw RuleFailure(cause: .malformedRecurrence)
                    }
                    let prefix = String(value.dropLast(2))
                    var ordinal: Int?
                    if !prefix.isEmpty {
                        let digits = prefix.first == "-" || prefix.first == "+" ? String(prefix.dropFirst()) : prefix
                        guard frequency == .monthly, Self.isDigits(digits), let number = Int(prefix),
                              (-5...5).contains(number), number != 0 else {
                            throw RuleFailure(cause: .malformedRecurrence)
                        }
                        ordinal = number
                    }
                    days.insert(Day(weekday: index + 1, ordinal: ordinal))
                }
            }
            self.days = days.sorted {
                $0.weekday == $1.weekday ? ($0.ordinal ?? 0) < ($1.ordinal ?? 0) : $0.weekday < $1.weekday
            }
        }

        private static func isDigits(_ value: String) -> Bool {
            !value.isEmpty && value.utf8.allSatisfy { (48...57).contains($0) }
        }
        private static func positiveInteger(_ text: String) throws -> Int {
            guard isDigits(text), let value = Int(text), value > 0 else {
                throw RuleFailure(cause: .malformedRecurrence)
            }
            return value
        }
        private static func parseUntil(_ text: String, calendar: Calendar) throws -> Limit {
            let bytes = Array(text.utf8)
            let isDate = bytes.count == 8
            guard isDate || bytes.count == 15 || (bytes.count == 16 && bytes.last == 90),
                  isDate || bytes[8] == 84 else { throw RuleFailure(cause: .malformedRecurrence) }
            let digits = bytes.enumerated().filter { isDate || ($0.offset != 8 && $0.offset != 15) }.map(\.element)
            guard digits.allSatisfy({ (48...57).contains($0) }) else {
                throw RuleFailure(cause: .malformedRecurrence)
            }
            func number(_ range: Range<Int>) -> Int {
                bytes[range].reduce(0) { $0 * 10 + Int($1 - 48) }
            }
            let year = number(0..<4), month = number(4..<6), day = number(6..<8)
            var zoneCalendar = calendar
            if bytes.count == 16 { zoneCalendar.timeZone = TimeZone(secondsFromGMT: 0)! }
            if isDate {
                // A next-day exclusive bound includes the entire local day, fractions
                // and DST changes included, instead of Flutter's UTC 23:59:59 shortcut.
                guard (1...9999).contains(year), (1...12).contains(month), (1...31).contains(day),
                      let date = calendar.date(from: DateComponents(year: year, month: month, day: day)),
                      calendar.component(.year, from: date) == year,
                      calendar.component(.month, from: date) == month,
                      calendar.component(.day, from: date) == day,
                      let next = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: date)) else {
                    throw RuleFailure(cause: .invalidDate)
                }
                return Limit(date: calendar.startOfDay(for: next), inclusive: false)
            }
            guard let date = strictDate(year: year, month: month, day: day, hour: number(9..<11),
                                        minute: number(11..<13), second: number(13..<15), calendar: zoneCalendar) else {
                throw RuleFailure(cause: .invalidDate)
            }
            return Limit(date: date, inclusive: true)
        }
    }

    private static func mondayWeekday(_ weekday: Int) -> Int { (weekday + 5) % 7 + 1 }

    private static func supportedDate(_ date: Date, calendar: Calendar) -> Bool {
        let seconds = date.timeIntervalSince1970
        // Reject NaN/infinity/outlandish dates before passing them into Foundation/ICU.
        guard seconds.isFinite, (-62_135_683_200...253_402_387_200).contains(seconds) else { return false }
        let parts = calendar.dateComponents([.era, .year], from: date)
        return parts.era == 1 && (1...9999).contains(parts.year ?? 0)
    }

    private static func strictDate(year: Int, month: Int, day: Int, hour: Int, minute: Int,
                                   second: Int, calendar: Calendar) -> Date? {
        guard (1...9999).contains(year), (1...12).contains(month), (1...31).contains(day),
              (0...23).contains(hour), (0...59).contains(minute), (0...59).contains(second),
              let seed = calendar.date(from: DateComponents(year: year, month: month, day: day,
                                                           hour: hour, minute: minute, second: second)),
              let date = calendar.date(bySettingHour: hour, minute: minute, second: second,
                                       of: calendar.startOfDay(for: seed), matchingPolicy: .strict,
                                       repeatedTimePolicy: .first, direction: .forward) else { return nil }
        let actual = calendar.dateComponents([.era, .year, .month, .day, .hour, .minute, .second], from: date)
        guard actual.era == 1, actual.year == year, actual.month == month, actual.day == day,
              actual.hour == hour, actual.minute == minute, actual.second == second else { return nil }
        // A repeated local time produces one deterministic occurrence: its first instant.
        return date
    }
}

import Foundation

struct ICalendarResult: Sendable {
    var events: [MeetupEvent]
    var warnings: [CalendarFeedWarning]
}

struct ICalendarProperty: Sendable {
    var name: String
    var parameters: [String: String]
    var value: String

    init(_ line: String) throws {
        var quoted = false
        var separator: String.Index?
        for index in line.indices {
            if line[index] == "\"" { quoted.toggle() }
            if line[index] == ":" && !quoted { separator = index; break }
        }
        guard let separator, !quoted else { throw CalendarFeedError.invalidCalendar(.invalidContentLine) }
        let header = String(line[..<separator])
        var parts: [String] = []
        var part = ""
        quoted = false
        for character in header {
            if character == "\"" { quoted.toggle() }
            if character == ";" && !quoted { parts.append(part); part = "" }
            else { part.append(character) }
        }
        parts.append(part)
        name = parts[0].uppercased()
        guard !name.isEmpty, name.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-") }) else {
            throw CalendarFeedError.invalidCalendar(.invalidContentLine)
        }
        parameters = [:]
        for part in parts.dropFirst() {
            guard let equals = part.firstIndex(of: "=") else { throw CalendarFeedError.invalidCalendar(.invalidContentLine) }
            let key = String(part[..<equals]).uppercased()
            var value = String(part[part.index(after: equals)...])
            guard !key.isEmpty, !value.isEmpty, parameters[key] == nil else {
                throw CalendarFeedError.invalidCalendar(.invalidContentLine)
            }
            if value.hasPrefix("\""), value.hasSuffix("\"") { value = String(value.dropFirst().dropLast()) }
            parameters[key] = value
        }
        value = String(line[line.index(after: separator)...])
    }
}

struct ICalendarDate: Sendable {
    var date: Date
    var timeZone: TimeZone
    var isDate: Bool
}

enum ICalendarDateError: Error, Equatable { case invalidDate, unknownTimeZone }

enum ICalendarDates {
    /// DATE values use their civil day's actual start, which can be 01:00 after a DST jump.
    /// UTC stays UTC; known TZIDs are honored instead of treating every TZID as device-local.
    static func parse(_ value: String, parameters: [String: String] = [:], defaultTimeZone: TimeZone) throws -> ICalendarDate {
        let raw = value.trimmingCharacters(in: .whitespaces)
        let isUTC = raw.uppercased().hasSuffix("Z")
        let text = isUTC ? String(raw.dropLast()) : raw
        let isDate = text.count == 8
        let valueType = parameters["VALUE"]?.uppercased()
        guard valueType == nil || valueType == "DATE" || valueType == "DATE-TIME",
              !(valueType == "DATE" && !isDate), !(valueType == "DATE-TIME" && isDate),
              !(isDate && isUTC), !(isUTC && parameters["TZID"] != nil) else { throw ICalendarDateError.invalidDate }
        let zone: TimeZone
        if isUTC { zone = TimeZone(secondsFromGMT: 0)! }
        else if let identifier = parameters["TZID"] {
            guard let known = TimeZone(identifier: identifier) else { throw ICalendarDateError.unknownTimeZone }
            zone = known
        } else { zone = defaultTimeZone }
        let digits = Array(text.utf8)
        guard isDate || digits.count == 13 || digits.count == 15 else { throw ICalendarDateError.invalidDate }
        for (index, digit) in digits.enumerated() {
            if !isDate && index == 8 {
                guard digit == 84 || digit == 116 else { throw ICalendarDateError.invalidDate }
            } else if !(48...57).contains(digit) { throw ICalendarDateError.invalidDate }
        }
        func number(_ range: Range<Int>) -> Int { range.reduce(0) { $0 * 10 + Int(digits[$1] - 48) } }
        let year = number(0..<4), month = number(4..<6), day = number(6..<8)
        let hour = isDate ? 0 : number(9..<11)
        let minute = isDate ? 0 : number(11..<13)
        let second = digits.count == 15 ? number(13..<15) : 0
        guard (1...9999).contains(year), (1...12).contains(month), (1...31).contains(day),
              (0...23).contains(hour), (0...59).contains(minute), (0...59).contains(second) else {
            throw ICalendarDateError.invalidDate
        }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        if isDate {
            guard let date = startOfDay(year: year, month: month, day: day, calendar: calendar) else {
                throw ICalendarDateError.invalidDate
            }
            return ICalendarDate(date: date, timeZone: zone, isDate: true)
        }
        let components = DateComponents(year: year, month: month, day: day, hour: hour, minute: minute, second: second)
        guard let seed = calendar.date(from: components),
              let date = calendar.date(bySettingHour: hour, minute: minute, second: second,
                                       of: calendar.startOfDay(for: seed), matchingPolicy: .strict,
                                       repeatedTimePolicy: .first, direction: .forward) else {
            throw ICalendarDateError.invalidDate
        }
        let check = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        // Reject normalized impossible dates/DST gaps. Repeated hours use the first instant,
        // matching the recurrence expander so occurrence IDs remain deterministic at fall-back.
        guard check.year == year, check.month == month, check.day == day,
              check.hour == hour, check.minute == minute, check.second == second else { throw ICalendarDateError.invalidDate }
        return ICalendarDate(date: date, timeZone: zone, isDate: isDate)
    }

    static func startOfDay(year: Int, month: Int, day: Int, calendar: Calendar) -> Date? {
        guard (1...9999).contains(year), (1...12).contains(month), (1...31).contains(day),
              let seed = calendar.date(from: DateComponents(year: year, month: month, day: day)) else { return nil }
        let date = calendar.startOfDay(for: seed)
        let actual = calendar.dateComponents([.era, .year, .month, .day], from: date)
        // A midnight gap is valid; an impossible date or a skipped entire civil day is not.
        guard actual.era == 1, actual.year == year, actual.month == month, actual.day == day else { return nil }
        return date
    }
}

enum ICalendarParser {
    static let maximumBytes = 4 * 1024 * 1024
    static let maximumLineBytes = 64 * 1024
    static let maximumLines = 100_000
    static let maximumEvents = 10_000
    static let maximumOccurrences = 50_000

    static func parse(_ data: Data, now: Date, timeZone: TimeZone) throws -> ICalendarResult {
        guard data.count <= maximumBytes else { throw CalendarFeedError.invalidCalendar(.sizeLimit) }
        guard var text = String(data: data, encoding: .utf8) else { throw CalendarFeedError.invalidCalendar(.invalidUTF8) }
        if text.hasPrefix("\u{FEFF}") { text.removeFirst() }
        let lines = try unfolded(text)
        var stack: [String] = []
        var calendarSeen = false
        var calendarClosed = false
        var versionSeen = false
        var productSeen = false
        var properties: [ICalendarProperty] = []
        var eventCount = 0
        var validCount = 0
        var events: [String: MeetupEvent] = [:]
        var warnings: [CalendarFeedWarning] = []
        for line in lines where !line.isEmpty {
            let property = try ICalendarProperty(line)
            if property.name == "BEGIN" {
                let component = property.value.uppercased()
                guard !component.isEmpty, stack.count < 16, !calendarClosed else {
                    throw CalendarFeedError.invalidCalendar(.invalidStructure)
                }
                if stack.isEmpty {
                    guard component == "VCALENDAR", !calendarSeen else { throw CalendarFeedError.invalidCalendar(.missingCalendar) }
                    calendarSeen = true
                } else if component == "VCALENDAR" { throw CalendarFeedError.invalidCalendar(.invalidStructure) }
                if component == "VEVENT" {
                    guard stack == ["VCALENDAR"], eventCount < maximumEvents else {
                        throw CalendarFeedError.invalidCalendar(eventCount >= maximumEvents ? .sizeLimit : .invalidStructure)
                    }
                    eventCount += 1
                    properties = []
                }
                stack.append(component)
            } else if property.name == "END" {
                guard let component = stack.last, component == property.value.uppercased() else {
                    throw CalendarFeedError.invalidCalendar(.invalidStructure)
                }
                if component == "VEVENT" {
                    let parsed = parseEvent(properties, index: eventCount, now: now, timeZone: timeZone)
                    if parsed.valid { validCount += 1 }
                    warnings += parsed.result.warnings
                    for event in parsed.result.events {
                        if events[event.id] != nil {
                            warnings.append(CalendarFeedWarning(source: .ical, cause: .duplicateOccurrence, eventIndex: eventCount))
                        } else { events[event.id] = event }
                    }
                    guard events.count <= maximumOccurrences else { throw CalendarFeedError.invalidCalendar(.sizeLimit) }
                }
                stack.removeLast()
                if component == "VCALENDAR" { calendarClosed = true }
            } else {
                guard !stack.isEmpty else { throw CalendarFeedError.invalidCalendar(.missingCalendar) }
                // In particular, DTSTART inside VTIMEZONE/DAYLIGHT/STANDARD is never an event.
                if stack.last == "VEVENT" { properties.append(property) }
                else if stack == ["VCALENDAR"], property.name == "VERSION" {
                    guard !versionSeen, property.value == "2.0" else { throw CalendarFeedError.invalidCalendar(.invalidStructure) }
                    versionSeen = true
                } else if stack == ["VCALENDAR"], property.name == "PRODID" {
                    guard !productSeen, !property.value.trimmingCharacters(in: .whitespaces).isEmpty else {
                        throw CalendarFeedError.invalidCalendar(.invalidStructure)
                    }
                    productSeen = true
                }
                else if stack.last == "VTIMEZONE", property.name == "TZID", TimeZone(identifier: property.value) == nil {
                    warnings.append(CalendarFeedWarning(source: .ical, cause: .unsupportedTimeZoneDefinition))
                }
            }
        }
        guard calendarSeen, calendarClosed, stack.isEmpty, versionSeen, productSeen else { throw CalendarFeedError.invalidCalendar(.missingCalendar) }
        guard eventCount == 0 || validCount > 0 else { throw CalendarFeedError.invalidCalendar(.noValidEvents) }
        let sorted = events.values.sorted { $0.start == $1.start ? $0.id < $1.id : $0.start < $1.start }
        return ICalendarResult(events: sorted, warnings: warnings)
    }

    private static func unfolded(_ text: String) throws -> [String] {
        let physical = text.replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n").components(separatedBy: "\n")
        guard physical.count <= maximumLines else { throw CalendarFeedError.invalidCalendar(.sizeLimit) }
        var result: [String] = []
        for line in physical {
            guard line.utf8.count <= maximumLineBytes else { throw CalendarFeedError.invalidCalendar(.sizeLimit) }
            if line.hasPrefix(" ") || line.hasPrefix("\t") {
                guard !result.isEmpty else { throw CalendarFeedError.invalidCalendar(.invalidContentLine) }
                result[result.count - 1] += line.dropFirst()
                guard result[result.count - 1].utf8.count <= maximumLineBytes else { throw CalendarFeedError.invalidCalendar(.sizeLimit) }
            } else { result.append(line) }
        }
        return result
    }

    private static func parseEvent(_ properties: [ICalendarProperty], index: Int, now: Date,
                                   timeZone: TimeZone) -> (result: ICalendarResult, valid: Bool) {
        var warnings: [CalendarFeedWarning] = []
        func warn(_ cause: CalendarFeedWarning.Cause) { warnings.append(CalendarFeedWarning(source: .ical, cause: cause, eventIndex: index)) }
        func all(_ name: String) -> [ICalendarProperty] { properties.filter { $0.name == name } }
        func first(_ name: String) -> ICalendarProperty? { properties.first { $0.name == name } }
        if first("RECURRENCE-ID") != nil || first("RDATE") != nil || first("EXRULE") != nil { warn(.unsupportedException) }
        if first("STATUS")?.value.uppercased() == "CANCELLED" {
            return (ICalendarResult(events: [], warnings: warnings), true)
        }
        guard all("DTSTART").count == 1, let startProperty = first("DTSTART") else {
            warn(.invalidEvent); return (ICalendarResult(events: [], warnings: warnings), false)
        }
        let start: ICalendarDate
        do { start = try ICalendarDates.parse(startProperty.value, parameters: startProperty.parameters, defaultTimeZone: timeZone) }
        catch {
            warn((error as? ICalendarDateError) == .unknownTimeZone ? .unknownTimeZone : .invalidDate)
            return (ICalendarResult(events: [], warnings: warnings), false)
        }
        var end: ICalendarDate?
        if let property = first("DTEND") {
            do {
                let parsed = try ICalendarDates.parse(property.value, parameters: property.parameters, defaultTimeZone: start.timeZone)
                if parsed.date < start.date || parsed.isDate != start.isDate || all("DTEND").count > 1 { warn(.invalidEnd) }
                else { end = parsed }
            } catch {
                warn((error as? ICalendarDateError) == .unknownTimeZone ? .unknownTimeZone : .invalidEnd)
            }
        } else if first("DURATION") != nil { warn(.invalidEnd) }
        let title = first("SUMMARY").map { unescape($0.value) } ?? "Meetup"
        let description = first("DESCRIPTION").map { unescape($0.value) } ?? ""
        let location = first("LOCATION").map { unescape($0.value) } ?? ""
        var url: URL?
        if let raw = first("URL")?.value {
            url = SafeURL.web(unescape(raw))
            if url == nil { warn(.invalidURL) }
        }
        var latitude: Double?, longitude: Double?
        if let raw = first("GEO")?.value {
            let pair = raw.split(separator: ";", omittingEmptySubsequences: false)
            if pair.count == 2, let lat = Double(pair[0]), let lon = Double(pair[1]),
               lat.isFinite, lon.isFinite, (-90...90).contains(lat), (-180...180).contains(lon) {
                latitude = lat; longitude = lon
            } else { warn(.invalidCoordinates) }
        }
        let uid: String
        if let raw = first("UID")?.value, !raw.trimmingCharacters(in: .whitespaces).isEmpty { uid = unescape(raw) }
        else {
            // No random UUIDs or process-random Hasher: reorderings and refreshes keep stable IDs.
            uid = "content-" + fingerprint([title, description, location, startProperty.value, url?.absoluteString ?? ""].joined(separator: "\u{0}"))
            warn(.missingUID)
        }
        var starts = [start.date]
        let rules = all("RRULE")
        if rules.count > 1 { warn(.unsupportedRecurrence) }
        else if let rule = rules.first {
            let expansion = RecurrenceExpander.expand(start: start.date, rule: rule.value, timeZone: start.timeZone,
                                                       now: now, isAllDay: start.isDate)
            starts = expansion.starts
            warnings += expansion.warnings.map { warning in
                var warning = warning; warning.eventIndex = index; return warning
            }
        }
        var exclusions = Set<Date>()
        for property in all("EXDATE") {
            for value in property.value.split(separator: ",", omittingEmptySubsequences: false) {
                do {
                    let date = try ICalendarDates.parse(String(value), parameters: property.parameters, defaultTimeZone: start.timeZone)
                    guard date.isDate == start.isDate else { throw ICalendarDateError.invalidDate }
                    exclusions.insert(date.date)
                } catch {
                    warn(.unsupportedException)
                    warn((error as? ICalendarDateError) == .unknownTimeZone ? .unknownTimeZone : .invalidDate)
                }
            }
        }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = start.timeZone
        var civil = Calendar(identifier: .gregorian)
        civil.timeZone = TimeZone(secondsFromGMT: 0)!
        // Timed DTEND retains elapsed duration. DATE DTEND retains calendar days across DST.
        // Flutter loses DTEND entirely when cloning recurrence; both are deliberate corrections.
        // Count DATE duration in civil dates, independently of a midnight gap's missing hour.
        let dayDuration = end.flatMap { end -> Int? in
            guard start.isDate,
                  let first = civil.date(from: calendar.dateComponents([.year, .month, .day], from: start.date)),
                  let last = civil.date(from: calendar.dateComponents([.year, .month, .day], from: end.date)) else { return nil }
            return civil.dateComponents([.day], from: first, to: last).day
        }
        let duration = end.map { $0.date.timeIntervalSince(start.date) }
        let encodedUID = Data(uid.utf8).base64EncodedString()
        // The live feed publishes the exact portal event identity in these UIDs. Preserve local
        // plans when switching sources, but never infer a portal ID from arbitrary UIDs or series.
        // Series use UID + instant (or civil date for DATE values) because one portal ID cannot
        // identify multiple occurrences. Civil-date IDs survive a device time-zone change.
        // Matching a group is separate and never inferred from this UID.
        let portalID = rules.isEmpty && first("RECURRENCE-ID") == nil && first("RDATE") == nil
            ? portalEventID(uid) : nil
        let groupURL = MeetupEventMatcher.portalIdentity(url) != nil ? url : nil
        let events = starts.filter { !exclusions.contains($0) }.map { occurrence in
            let occurrenceEnd: Date?
            if let days = dayDuration {
                let day = calendar.dateComponents([.year, .month, .day], from: occurrence)
                if let first = civil.date(from: day), let last = civil.date(byAdding: .day, value: days, to: first) {
                    let endDay = civil.dateComponents([.year, .month, .day], from: last)
                    occurrenceEnd = ICalendarDates.startOfDay(year: endDay.year!, month: endDay.month!, day: endDay.day!, calendar: calendar)
                } else { occurrenceEnd = nil }
                if occurrenceEnd == nil { warn(.invalidEnd) }
            } else { occurrenceEnd = duration.map { occurrence.addingTimeInterval($0) } }
            let occurrenceIdentity: String
            if start.isDate {
                let day = calendar.dateComponents([.year, .month, .day], from: occurrence)
                occurrenceIdentity = String(format: "date:%04d%02d%02d", day.year!, day.month!, day.day!)
            } else { occurrenceIdentity = String(Int64(occurrence.timeIntervalSince1970)) }
            return MeetupEvent(id: portalID ?? "ical:\(encodedUID):\(occurrenceIdentity)",
                               title: title, description: description, start: occurrence, end: occurrenceEnd,
                               location: location, latitude: latitude, longitude: longitude, url: url,
                               meetupPortalURL: groupURL, isAllDay: start.isDate,
                               calendarTimeZoneID: start.isDate ? start.timeZone.identifier : nil)
        }
        return (ICalendarResult(events: events, warnings: warnings), true)
    }

    static func unescape(_ value: String) -> String {
        var result = ""
        var escaped = false
        for character in value {
            if escaped {
                switch character {
                case "n", "N": result.append("\n")
                case "\\", ",", ";": result.append(character)
                default: result.append("\\"); result.append(character)
                }
                escaped = false
            } else if character == "\\" { escaped = true }
            else { result.append(character) }
        }
        if escaped { result.append("\\") }
        return result
    }

    private static func fingerprint(_ value: String) -> String {
        // Noncryptographic FNV-1a is used only for a deterministic identity, never for security.
        var hash: UInt64 = 14_695_981_039_346_656_037
        for byte in value.utf8 { hash = (hash ^ UInt64(byte)) &* 1_099_511_628_211 }
        return String(hash, radix: 16)
    }

    private static func portalEventID(_ uid: String) -> String? {
        let prefix = "meetup-event-", suffix = "@einundzwanzig.space"
        guard uid.hasPrefix(prefix), uid.hasSuffix(suffix) else { return nil }
        let digits = String(uid.dropFirst(prefix.count).dropLast(suffix.count))
        guard !digits.isEmpty, digits.utf8.allSatisfy({ (48...57).contains($0) }),
              let number = Int64(digits), number > 0, String(number) == digits else { return nil }
        return digits
    }
}

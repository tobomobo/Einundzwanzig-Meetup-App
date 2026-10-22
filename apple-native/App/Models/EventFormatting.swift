import Foundation
import MeetupCore

enum CivilDateBridge {
    static func rebase(_ date: Date, from source: TimeZone, to destination: TimeZone) -> Date? {
        var input = Calendar(identifier: .gregorian); input.timeZone = source
        var output = Calendar(identifier: .gregorian); output.timeZone = destination
        let components = input.dateComponents([.year, .month, .day], from: date)
        guard let rebased = output.date(from: components), output.dateComponents([.year, .month, .day], from: rebased) == components else { return nil }
        return rebased
    }
}

extension MeetupEvent {
    var civilTimeZone: TimeZone { calendarTimeZoneID.flatMap(TimeZone.init(identifier:)) ?? .current }
    func displayFormat(_ format: Date.FormatStyle) -> Date.FormatStyle {
        var value = format
        if isAllDay { value.timeZone = civilTimeZone }
        return value
    }
    /// EventKit all-day editors use the device's civil calendar. Preserve the source
    /// year/month/day rather than converting its midnight into a different day.
    var forNativeCalendar: MeetupEvent {
        guard isAllDay else { return self }
        var source = Calendar(identifier: .gregorian)
        source.timeZone = civilTimeZone
        var destination = Calendar(identifier: .gregorian)
        destination.timeZone = .current
        func localDate(_ date: Date) -> Date {
            destination.date(from: source.dateComponents([.year, .month, .day], from: date)) ?? date
        }
        var value = self
        value.start = localDate(start)
        value.end = end.map(localDate)
        value.calendarTimeZoneID = TimeZone.current.identifier
        return value
    }
}

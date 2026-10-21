import Foundation
import MeetupCore

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

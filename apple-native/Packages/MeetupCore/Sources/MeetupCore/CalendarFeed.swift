import Foundation

public enum CalendarFeedSource: String, Codable, Hashable, Sendable {
    case portal, ical
}

public enum CalendarDisplayWindow {
    /// A DATE is a civil day, not an appointment that expires six hours after midnight.
    public static func includes(_ event: MeetupEvent, now: Date = Date()) -> Bool {
        guard event.isAllDay else { return event.start >= now.addingTimeInterval(-21_600) }
        if let end = event.end, end > event.start { return end > now }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = event.calendarTimeZoneID.flatMap(TimeZone.init(identifier:)) ?? .current
        guard let next = calendar.date(byAdding: .day, value: 1, to: event.start) else { return false }
        return calendar.startOfDay(for: next) > now
    }

    /// Compare a DATE's source civil Y/M/D in the user's requested date-filter zone.
    public static func filterDate(for event: MeetupEvent, timeZone: TimeZone = .current) -> Date {
        guard event.isAllDay else { return event.start }
        var source = Calendar(identifier: .gregorian)
        source.timeZone = event.calendarTimeZoneID.flatMap(TimeZone.init(identifier:)) ?? timeZone
        var destination = Calendar(identifier: .gregorian)
        destination.timeZone = timeZone
        return destination.date(from: source.dateComponents([.year, .month, .day], from: event.start)) ?? event.start
    }
}

/// Deliberately contains no response body, event text, URL credentials, or localized transport errors.
public enum CalendarFeedFailure: Codable, Hashable, Sendable {
    case httpStatus(Int)
    case timeout
    case network
    case invalidResponse
    case invalidData
    case responseTooLarge
    case invalidURL
    case unknown

    public var message: String {
        switch self {
        case .httpStatus(let code): "HTTP \(code)."
        case .timeout: "The request timed out."
        case .network: "The network request failed."
        case .invalidResponse: "The server returned an invalid response."
        case .invalidData: "The server returned invalid calendar data."
        case .responseTooLarge: "The response exceeded the calendar size limit."
        case .invalidURL: "The calendar URL was invalid."
        case .unknown: "The request failed."
        }
    }

    static func classify(_ error: any Error) -> Self {
        if let error = error as? MeetupCoreError {
            switch error {
            case .httpStatus(let code): return .httpStatus(code)
            case .invalidResponse: return .invalidResponse
            case .invalidData: return .invalidData
            case .responseTooLarge: return .responseTooLarge
            case .invalidURL: return .invalidURL
            }
        }
        if let error = error as? URLError {
            return error.code == .timedOut ? .timeout : .network
        }
        if error is CalendarFeedError { return .invalidData }
        return .unknown
    }
}

public struct CalendarFeedWarning: Codable, Hashable, Sendable {
    public enum Cause: String, Codable, Hashable, Sendable {
        case portalFailed, noUpcomingPortalEvents
        case invalidEvent, invalidDate, unknownTimeZone, invalidEnd
        case invalidURL, invalidCoordinates, missingUID, duplicateOccurrence
        case unsupportedRecurrence, malformedRecurrence, recurrenceLimit
        case unsupportedException, unsupportedTimeZoneDefinition
    }

    public var source: CalendarFeedSource
    public var cause: Cause
    /// One-based VEVENT position; avoids putting untrusted titles or UIDs into diagnostics.
    public var eventIndex: Int?
    public var failure: CalendarFeedFailure?

    public init(source: CalendarFeedSource, cause: Cause, eventIndex: Int? = nil,
                failure: CalendarFeedFailure? = nil) {
        self.source = source; self.cause = cause
        self.eventIndex = eventIndex; self.failure = failure
    }

    public var message: String {
        switch cause {
        case .portalFailed:
            "The complete three-month portal calendar could not be loaded. Using iCalendar fallback. " + (failure?.message ?? "")
        case .noUpcomingPortalEvents:
            "The portal returned no usable events since six hours ago. Using iCalendar fallback."
        case .invalidEvent: "An iCalendar event was invalid and was omitted; this calendar may be incomplete."
        case .invalidDate: "An iCalendar date was invalid or nonexistent in its time zone; this calendar may be incomplete."
        case .unknownTimeZone: "An iCalendar time zone was unknown. Affected dates were omitted; this calendar may be incomplete."
        case .invalidEnd: "An iCalendar end date was invalid and was omitted."
        case .invalidURL: "An iCalendar event link was invalid and was omitted."
        case .invalidCoordinates: "An iCalendar venue position was invalid and was omitted."
        case .missingUID: "An iCalendar event had no UID; a deterministic content identity was used."
        case .duplicateOccurrence: "Duplicate iCalendar occurrence identities were found and merged."
        case .unsupportedRecurrence: "An iCalendar recurrence rule is unsupported. Only its base event is available; this calendar is incomplete."
        case .malformedRecurrence: "An iCalendar recurrence rule is malformed. Only its base event is available; this calendar is incomplete."
        case .recurrenceLimit: "iCalendar recurrence expansion reached the 366-day horizon or 200-occurrence limit."
        case .unsupportedException: "Some iCalendar recurrence exceptions are unsupported; this calendar may be incomplete."
        case .unsupportedTimeZoneDefinition: "A custom iCalendar time-zone definition is unsupported; affected events may be incomplete."
        }
    }
}

public struct CalendarFeedSnapshot: Codable, Hashable, Sendable {
    public var events: [MeetupEvent]
    public var source: CalendarFeedSource
    /// Actual completion time, independent of the injected clock used for filtering and recurrence.
    public var fetchedAt: Date
    public var warnings: [CalendarFeedWarning]

    public init(events: [MeetupEvent], source: CalendarFeedSource, fetchedAt: Date,
                warnings: [CalendarFeedWarning] = []) {
        self.events = events; self.source = source
        self.fetchedAt = fetchedAt; self.warnings = warnings
    }
}

public enum CalendarFeedError: Error, Equatable, Sendable, LocalizedError {
    public enum InvalidCalendar: String, Codable, Sendable {
        case invalidUTF8, invalidStructure, invalidContentLine, missingCalendar
        case noValidEvents, sizeLimit
    }
    case invalidCalendar(InvalidCalendar)
    case unavailable(portal: CalendarFeedWarning, ical: CalendarFeedFailure)

    public var errorDescription: String? {
        switch self {
        case .invalidCalendar(let reason): "The iCalendar feed could not be read (\(reason.rawValue))."
        case .unavailable(let portal, let failure):
            portal.message + " The iCalendar fallback also failed. " + failure.message
        }
    }
}

public actor CalendarClient {
    public static let calendarURL = URL(string: "https://portal.einundzwanzig.space/stream-calendar")!
    private let portal: PortalClient
    private let transport: any HTTPTransport

    public init() {
        let transport = URLSessionTransport()
        self.transport = transport
        portal = PortalClient(transport: transport)
    }

    init(transport: any HTTPTransport) {
        self.transport = transport
        portal = PortalClient(transport: transport)
    }

    public func snapshot(now: Date = Date(), timeZone: TimeZone = .current) async throws -> CalendarFeedSnapshot {
        try Task.checkCancellation()
        let fallback: CalendarFeedWarning
        do {
            // PortalClient owns today + next two month requests and throws on ANY failed month.
            let events = try await portal.events(now: now).filter { CalendarDisplayWindow.includes($0, now: now) }
            try Task.checkCancellation()
            if !events.isEmpty {
                return CalendarFeedSnapshot(events: events, source: .portal, fetchedAt: Date())
            }
            fallback = CalendarFeedWarning(source: .portal, cause: .noUpcomingPortalEvents)
        } catch {
            try Self.propagateCancellation(error)
            fallback = CalendarFeedWarning(source: .portal, cause: .portalFailed,
                                           failure: .classify(error))
        }
        do {
            let data = try await transport.get(Self.calendarURL, accept: "text/calendar")
            try Task.checkCancellation()
            let parsed = try ICalendarParser.parse(data, now: now, timeZone: timeZone)
            try Task.checkCancellation()
            // Timed dates share a six-hour window. All-day dates remain through their
            // exclusive civil end, including days that do not begin at midnight.
            return CalendarFeedSnapshot(events: parsed.events.filter { CalendarDisplayWindow.includes($0, now: now) }, source: .ical,
                                        fetchedAt: Date(), warnings: [fallback] + parsed.warnings)
        } catch {
            try Self.propagateCancellation(error)
            throw CalendarFeedError.unavailable(portal: fallback, ical: .classify(error))
        }
    }

    private static func propagateCancellation(_ error: any Error) throws {
        if error is CancellationError || (error as? URLError)?.code == .cancelled { throw CancellationError() }
        try Task.checkCancellation()
    }
}

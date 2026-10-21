import Foundation
import XCTest
@testable import MeetupCore

private actor CalendarTestTransport: HTTPTransport {
    enum Response: Sendable { case body(String), failure(MeetupCoreError), cancelled }
    private let responses: [String: Response]
    private var requests: [(String, String)] = []

    init(_ responses: [String: Response]) { self.responses = responses }

    func get(_ url: URL, accept: String) async throws -> Data {
        requests.append((url.path, accept))
        guard let response = responses[url.path] else { throw MeetupCoreError.httpStatus(404) }
        switch response {
        case .body(let body): return Data(body.utf8)
        case .failure(let error): throw error
        case .cancelled: throw CancellationError()
        }
    }

    func paths() -> [String] { requests.map(\.0) }
    func accepts() -> [String] { requests.map(\.1) }
}

final class CalendarFeedTests: XCTestCase, @unchecked Sendable {
    private let zone = TimeZone(identifier: "Europe/Vienna")!
    private var now: Date { DateParsing.portal("2026-10-21T12:00:00Z")! }
    private let paths = ["/api/meetup-events/2026-10-21", "/api/meetup-events/2026-11-01", "/api/meetup-events/2026-12-01"]

    private func calendar(_ events: String) -> String {
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:Calendar tests\r\n\(events)\r\nEND:VCALENDAR\r\n"
    }

    private func event(_ properties: String = "UID:fallback\nDTSTART:20261022T180000Z\nSUMMARY:Fallback") -> String {
        "BEGIN:VEVENT\n\(properties)\nEND:VEVENT"
    }

    private func parsed(_ properties: String) throws -> ICalendarResult {
        try ICalendarParser.parse(Data(calendar(event(properties)).utf8), now: now, timeZone: zone)
    }

    private func parsedInSantiago(_ properties: String) throws -> ICalendarResult {
        try ICalendarParser.parse(Data(calendar(event(properties)).utf8),
                                  now: DateParsing.portal("2026-09-01T00:00:00Z")!,
                                  timeZone: TimeZone(identifier: "America/Santiago")!)
    }

    func testCompletePortalAggregateWinsAndFreshnessIsActualCompletionTime() async throws {
        let transport = CalendarTestTransport([
            paths[0]: .body(#"[{"id":1,"start":"2026-10-21 05:59"},{"id":2,"start":"2026-10-21 06:00"}]"#),
            paths[1]: .body(#"[{"id":3,"start":"2026-11-21 18:00"}]"#),
            paths[2]: .body(#"[{"id":4,"start":"2026-12-21 18:00"}]"#)
        ])
        let before = Date()
        let snapshot = try await CalendarClient(transport: transport).snapshot(now: now, timeZone: zone)
        XCTAssertEqual(snapshot.source, .portal)
        XCTAssertEqual(snapshot.events.map(\.id), ["2", "3", "4"])
        XCTAssertEqual(snapshot.warnings, [])
        XCTAssertGreaterThanOrEqual(snapshot.fetchedAt, before)
        XCTAssertLessThanOrEqual(snapshot.fetchedAt, Date())
        let requests = await transport.paths()
        XCTAssertEqual(requests, paths)
        XCTAssertEqual(try JSONDecoder().decode(CalendarFeedSnapshot.self, from: JSONEncoder().encode(snapshot)), snapshot)
    }

    func testPartialPortalMonthFailureDiscardsWholeAggregateAndUsesICS() async throws {
        let transport = CalendarTestTransport([
            paths[0]: .body(#"[{"id":1,"start":"2026-10-22 18:00"}]"#),
            paths[1]: .failure(.httpStatus(503)),
            "/stream-calendar": .body(calendar(event()))
        ])
        let snapshot = try await CalendarClient(transport: transport).snapshot(now: now, timeZone: zone)
        XCTAssertEqual(snapshot.source, .ical)
        XCTAssertEqual(snapshot.events.map(\.title), ["Fallback"])
        XCTAssertEqual(snapshot.warnings, [CalendarFeedWarning(source: .portal, cause: .portalFailed, failure: .httpStatus(503))])
        XCTAssertEqual(try JSONDecoder().decode(CalendarFeedSnapshot.self, from: JSONEncoder().encode(snapshot)), snapshot)
        let requests = await transport.paths(), accepts = await transport.accepts()
        XCTAssertEqual(requests, [paths[0], paths[1], "/stream-calendar"])
        XCTAssertEqual(accepts.last, "text/calendar")
    }

    func testEmptyOrEntirelyPastPortalFallsBackAfterAllThreeMonths() async throws {
        for body in ["[]", #"[{"id":1,"start":"2026-10-21 05:59"}]"#] {
            var responses = Dictionary(uniqueKeysWithValues: paths.map { ($0, CalendarTestTransport.Response.body(body)) })
            responses["/stream-calendar"] = .body(calendar(event()))
            let transport = CalendarTestTransport(responses)
            let snapshot = try await CalendarClient(transport: transport).snapshot(now: now, timeZone: zone)
            XCTAssertEqual(snapshot.source, .ical)
            XCTAssertEqual(snapshot.warnings.first?.cause, .noUpcomingPortalEvents)
            let requests = await transport.paths()
            XCTAssertEqual(requests, paths + ["/stream-calendar"])
        }
    }

    func testInvalidNonemptyICSAndDoubleNetworkFailureThrowSanitizedSourceErrors() async throws {
        for response in [CalendarTestTransport.Response.body("This is not a calendar"), .failure(.httpStatus(502))] {
            let transport = CalendarTestTransport([
                paths[0]: .failure(.invalidData("private title and user payload")), "/stream-calendar": response
            ])
            do {
                _ = try await CalendarClient(transport: transport).snapshot(now: now, timeZone: zone)
                XCTFail("Invalid fallback must throw")
            } catch CalendarFeedError.unavailable(let portal, let failure) {
                XCTAssertEqual(portal.source, .portal)
                XCTAssertEqual(portal.cause, .portalFailed)
                XCTAssertEqual(portal.failure, .invalidData)
                XCTAssertTrue([CalendarFeedFailure.invalidData, .httpStatus(502)].contains(failure))
                XCTAssertFalse(portal.message.contains("private title"))
            }
        }
    }

    func testCancellationDoesNotInitiateFallback() async throws {
        let transport = CalendarTestTransport([paths[0]: .cancelled])
        do {
            _ = try await CalendarClient(transport: transport).snapshot(now: now, timeZone: zone)
            XCTFail("Cancellation must propagate")
        } catch is CancellationError {}
        let requests = await transport.paths()
        XCTAssertEqual(requests, [paths[0]])
    }

    func testFallbackAppliesSameSixHourCutoffAndValidEmptyFeedIsExplicitlyICAL() async throws {
        for contents in [event("UID:past\nDTSTART:20261021T055900Z") + "\n" + event("UID:boundary\nDTSTART:20261021T060000Z"), ""] {
            let transport = CalendarTestTransport([
                paths[0]: .failure(.httpStatus(503)), "/stream-calendar": .body(calendar(contents))
            ])
            let snapshot = try await CalendarClient(transport: transport).snapshot(now: now, timeZone: zone)
            XCTAssertEqual(snapshot.source, .ical)
            XCTAssertEqual(snapshot.events.count, contents.isEmpty ? 0 : 1)
            XCTAssertEqual(snapshot.warnings.first?.failure, .httpStatus(503))
        }
    }

    func testFoldingEscapesURLsAndActualVenueCoordinates() throws {
        let result = try parsed(#"""
        UID:escaped
        DTSTART;TZID="Europe/Berlin":20261022T183000
        DTEND;TZID=Europe/Berlin:20261022T203000
        SUMMARY:Bitcoin\, Wien
        DESCRIPTION:First\nsecond\; path \\
         server\nthird
        LOCATION:Pub\, North
        URL:https://example.org/event?q=a:b
        GEO:48.2;16.3
        """#)
        let item = try XCTUnwrap(result.events.first)
        XCTAssertEqual(item.start, DateParsing.portal("2026-10-22T16:30:00Z"))
        XCTAssertEqual(item.end, DateParsing.portal("2026-10-22T18:30:00Z"))
        XCTAssertEqual(item.title, "Bitcoin, Wien")
        XCTAssertEqual(item.description, "First\nsecond; path \\server\nthird")
        XCTAssertEqual(item.location, "Pub, North")
        XCTAssertEqual(item.url?.absoluteString, "https://example.org/event?q=a:b")
        XCTAssertEqual(item.latitude, 48.2)
        XCTAssertEqual(item.longitude, 16.3)
        XCTAssertNil(item.meetupID)
        XCTAssertFalse(item.isAllDay)
        XCTAssertNil(item.calendarTimeZoneID)
        XCTAssertEqual(result.warnings, [])
    }

    func testUTCNamedFloatingAndDateOnlyDates() throws {
        let cases: [(String, String)] = [
            ("DTSTART:20261022T183000Z", "2026-10-22T18:30:00Z"),
            ("DTSTART;TZID=Europe/Berlin:20261022T183000", "2026-10-22T16:30:00Z"),
            ("DTSTART:20261022T183000", "2026-10-22T16:30:00Z"),
            ("DTSTART;TZID=Europe/Berlin:20261025T023000", "2026-10-25T00:30:00Z"),
            ("DTSTART;VALUE=DATE:20261022", "2026-10-21T22:00:00Z"),
            ("DTSTART;TZID=America/New_York:20261022T1830", "2026-10-22T22:30:00Z")
        ]
        for (property, expected) in cases {
            let result = try parsed("UID:date\n\(property)")
            XCTAssertEqual(result.events.first?.start, DateParsing.portal(expected), property)
        }
    }

    func testUnknownZonesAndImpossibleDatesNeverInventFutureEvents() throws {
        for line in ["DTSTART;TZID=MadeUp/Private:20261022T180000", "DTSTART:20260230T180000Z", "DTSTART:20261022T256000Z", "DTSTART;TZID=Europe/Berlin:20260329T023000", "DTSTART:garbage"] {
            XCTAssertThrowsError(try parsed("UID:invalid\n\(line)"), line)
            let text = calendar(event("UID:valid\nDTSTART:20261022T180000Z") + "\n" + event("UID:invalid\n\(line)"))
            let mixed = try ICalendarParser.parse(Data(text.utf8), now: now, timeZone: zone)
            XCTAssertEqual(mixed.events.count, 1)
            XCTAssertTrue(mixed.warnings.contains { [.invalidDate, .unknownTimeZone].contains($0.cause) })
            XCTAssertEqual(mixed.warnings.first?.eventIndex, 2)
        }
    }

    func testMalformedStructureEncodingBoundsAndEmptyCalendar() throws {
        for text in ["", "hello", "BEGIN:VCALENDAR\nBEGIN:VEVENT\nEND:VCALENDAR", "BEGIN:VCALENDAR\nBROKEN\nEND:VCALENDAR", "BEGIN:VCALENDAR\nEND:VCALENDAR\nSUMMARY:outside", " BEGIN:VCALENDAR\nEND:VCALENDAR"] {
            XCTAssertThrowsError(try ICalendarParser.parse(Data(text.utf8), now: now, timeZone: zone))
        }
        XCTAssertThrowsError(try ICalendarParser.parse(Data([0xFF, 0xFE]), now: now, timeZone: zone))
        XCTAssertThrowsError(try ICalendarParser.parse(Data(repeating: 32, count: ICalendarParser.maximumBytes + 1), now: now, timeZone: zone))
        XCTAssertThrowsError(try ICalendarParser.parse(Data(calendar("SUMMARY:" + String(repeating: "x", count: ICalendarParser.maximumLineBytes)).utf8), now: now, timeZone: zone))
        let empty = try ICalendarParser.parse(Data(calendar("").utf8), now: now, timeZone: zone)
        XCTAssertEqual(empty.events, [])
    }

    func testTimeZoneObservanceDatesAreNotMeetupEvents() throws {
        let text = calendar("""
        BEGIN:VTIMEZONE
        TZID:Europe/Berlin
        BEGIN:DAYLIGHT
        DTSTART:20260329T020000
        END:DAYLIGHT
        BEGIN:STANDARD
        DTSTART:20261025T030000
        END:STANDARD
        END:VTIMEZONE
        \(event())
        """)
        let result = try ICalendarParser.parse(Data(text.utf8), now: now, timeZone: zone)
        XCTAssertEqual(result.events.count, 1)
        XCTAssertEqual(result.events.first?.title, "Fallback")
        XCTAssertEqual(result.warnings, [])
    }

    func testEXDATEAndOccurrenceIdentityAndDurationAcrossDST() throws {
        let result = try parsed("""
        UID:weekly
        DTSTART;TZID=Europe/Berlin:20261018T183000
        DTEND;TZID=Europe/Berlin:20261018T203000
        RRULE:FREQ=WEEKLY;COUNT=4;BYDAY=SU
        EXDATE;TZID=Europe/Berlin:20261025T183000,20261101T183000
        """)
        XCTAssertEqual(result.events.map(\.start), [DateParsing.portal("2026-10-18T16:30:00Z")!, DateParsing.portal("2026-11-08T17:30:00Z")!])
        XCTAssertTrue(result.events.allSatisfy { $0.end?.timeIntervalSince($0.start) == 7200 })
        XCTAssertEqual(Set(result.events.map(\.id)).count, 2)
        XCTAssertEqual(result.warnings, [])
        let unsupported = try parsed("UID:weekly\nDTSTART:20261022T180000Z\nRRULE:FREQ=DAILY\nEXDATE:invalid\nRDATE:20261111T180000Z")
        XCTAssertEqual(unsupported.events.count, 1)
        XCTAssertTrue(unsupported.warnings.contains { $0.cause == .unsupportedRecurrence })
        XCTAssertTrue(unsupported.warnings.contains { $0.cause == .unsupportedException })
    }

    func testInvalidOptionalValuesWarnAndNoVenueCoordinatesAreInvented() throws {
        let result = try parsed("UID:optional\nDTSTART:20261022T180000Z\nDTEND:20261021T180000Z\nURL:javascript:bad\nGEO:NaN;999")
        let item = try XCTUnwrap(result.events.first)
        XCTAssertNil(item.end)
        XCTAssertNil(item.url)
        XCTAssertNil(item.latitude)
        XCTAssertNil(item.longitude)
        XCTAssertEqual(Set(result.warnings.map(\.cause)), [.invalidEnd, .invalidURL, .invalidCoordinates])
        let noGEO = try parsed("UID:no-geo\nDTSTART:20261022T180000Z\nLOCATION:Wien")
        XCTAssertNil(noGEO.events.first?.latitude)
        XCTAssertNil(noGEO.events.first?.longitude)
    }

    func testDateOnlyRecurrenceRetainsCalendarDayEndAcrossDST() throws {
        let result = try parsed("""
        UID:whole-day
        DTSTART;VALUE=DATE:20261018
        DTEND;VALUE=DATE:20261019
        RRULE:FREQ=WEEKLY;COUNT=3
        """)
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        XCTAssertEqual(result.events.count, 3)
        for item in result.events {
            let end = try XCTUnwrap(item.end)
            XCTAssertTrue(item.isAllDay)
            XCTAssertEqual(item.calendarTimeZoneID, zone.identifier)
            XCTAssertEqual(calendar.component(.hour, from: item.start), 0)
            XCTAssertEqual(calendar.component(.hour, from: end), 0)
            XCTAssertEqual(calendar.dateComponents([.day], from: item.start, to: end).day, 1)
        }
        XCTAssertEqual(result.events[1].end?.timeIntervalSince(result.events[1].start), 25 * 3600)
        let restored = try JSONDecoder().decode([MeetupEvent].self, from: JSONEncoder().encode(result.events))
        XCTAssertEqual(restored, result.events)
        XCTAssertEqual(result.warnings, [])
    }

    func testDateSingletonAcceptsCivilDayWhoseMidnightDoesNotExist() throws {
        let santiago = TimeZone(identifier: "America/Santiago")!
        let date = try ICalendarDates.parse("20260906", parameters: ["VALUE": "DATE"], defaultTimeZone: santiago)
        XCTAssertEqual(date.date, DateParsing.portal("2026-09-06T04:00:00Z")) // Local 01:00 is the day's first instant.
        XCTAssertTrue(date.isDate)
        let result = try parsedInSantiago("UID:midnight-gap\nDTSTART;VALUE=DATE:20260906\nSUMMARY:All day")
        let item = try XCTUnwrap(result.events.first)
        XCTAssertEqual(item.start, date.date)
        XCTAssertTrue(item.isAllDay)
        XCTAssertEqual(item.calendarTimeZoneID, santiago.identifier)
        XCTAssertEqual(result.events.count, 1)
        XCTAssertEqual(result.warnings, [])
    }

    func testDateWeeklyCountIncludesDayStartingAtOneAndThenReturnsToMidnight() throws {
        let result = try parsedInSantiago("UID:weekly-civil\nDTSTART;VALUE=DATE:20260830\nDTEND;VALUE=DATE:20260831\nRRULE:FREQ=WEEKLY;COUNT=3")
        XCTAssertEqual(result.events.map(\.start), [
            DateParsing.portal("2026-08-30T04:00:00Z")!, // Local Aug 30 00:00.
            DateParsing.portal("2026-09-06T04:00:00Z")!, // Local Sep 6 01:00, not an invalid civil day.
            DateParsing.portal("2026-09-13T03:00:00Z")!  // Local Sep 13 00:00.
        ])
        XCTAssertEqual(result.events.map(\.end), [
            DateParsing.portal("2026-08-31T04:00:00Z"),
            DateParsing.portal("2026-09-07T03:00:00Z"),
            DateParsing.portal("2026-09-14T03:00:00Z")
        ])
        XCTAssertTrue(result.events.allSatisfy { $0.isAllDay && $0.calendarTimeZoneID == "America/Santiago" })
        XCTAssertEqual(Set(result.events.map(\.id)).count, 3)
        XCTAssertEqual(result.warnings, [])
    }

    func testDateSeriesStartingAtOneDoesNotCarryClockHourIntoLaterStartsOrEnds() throws {
        let result = try parsedInSantiago("UID:first-day-gap\nDTSTART;VALUE=DATE:20260906\nDTEND;VALUE=DATE:20260907\nRRULE:FREQ=WEEKLY;COUNT=3")
        XCTAssertEqual(result.events.map(\.start), [
            DateParsing.portal("2026-09-06T04:00:00Z")!,
            DateParsing.portal("2026-09-13T03:00:00Z")!,
            DateParsing.portal("2026-09-20T03:00:00Z")!
        ])
        XCTAssertEqual(result.events.map(\.end), [
            DateParsing.portal("2026-09-07T03:00:00Z"),
            DateParsing.portal("2026-09-14T03:00:00Z"),
            DateParsing.portal("2026-09-21T03:00:00Z")
        ])
        XCTAssertEqual(result.events[0].end?.timeIntervalSince(result.events[0].start), 23 * 3600)
        XCTAssertEqual(result.warnings, [])
    }

    func testDateMonthlyCountKeepsCivilDateThroughMidnightGap() throws {
        let result = try parsedInSantiago("UID:monthly-civil\nDTSTART;VALUE=DATE:20260806\nRRULE:FREQ=MONTHLY;COUNT=3")
        XCTAssertEqual(result.events.map(\.start), [
            DateParsing.portal("2026-08-06T04:00:00Z")!,
            DateParsing.portal("2026-09-06T04:00:00Z")!,
            DateParsing.portal("2026-10-06T03:00:00Z")!
        ])
        XCTAssertEqual(result.warnings, [])
    }

    func testDateExclusionAndUntilRecognizeCivilDayWhoseMidnightIsMissing() throws {
        let excluded = try parsedInSantiago("UID:excluded-gap\nDTSTART;VALUE=DATE:20260830\nRRULE:FREQ=WEEKLY;COUNT=3\nEXDATE;VALUE=DATE:20260906")
        XCTAssertEqual(excluded.events.map(\.start), [DateParsing.portal("2026-08-30T04:00:00Z")!, DateParsing.portal("2026-09-13T03:00:00Z")!])
        XCTAssertEqual(excluded.warnings, [])
        let until = try parsedInSantiago("UID:until-gap\nDTSTART;VALUE=DATE:20260830\nRRULE:FREQ=WEEKLY;UNTIL=20260906")
        XCTAssertEqual(until.events.map(\.start), [DateParsing.portal("2026-08-30T04:00:00Z")!, DateParsing.portal("2026-09-06T04:00:00Z")!])
        XCTAssertEqual(until.warnings, [])
    }

    func testTimedMidnightGapRemainsInvalidAndTimedRecurrenceStillWarns() throws {
        XCTAssertThrowsError(try parsedInSantiago("UID:timed-gap\nDTSTART:20260906T000000"))
        let timed = try parsedInSantiago("UID:weekly-timed\nDTSTART:20260830T000000\nRRULE:FREQ=WEEKLY;COUNT=3")
        XCTAssertEqual(timed.events.map(\.start), [
            DateParsing.portal("2026-08-30T04:00:00Z")!,
            DateParsing.portal("2026-09-13T03:00:00Z")!,
            DateParsing.portal("2026-09-20T03:00:00Z")!
        ])
        XCTAssertTrue(timed.events.allSatisfy { !$0.isAllDay })
        XCTAssertEqual(timed.warnings.map(\.cause), [.invalidDate])
    }

    func testDateStillRejectsImpossibleOrEntirelySkippedCivilDay() throws {
        let apia = TimeZone(identifier: "Pacific/Apia")!
        for value in ["20111230", "20260230"] {
            XCTAssertThrowsError(try ICalendarDates.parse(value, parameters: ["VALUE": "DATE"], defaultTimeZone: apia)) {
                XCTAssertEqual($0 as? ICalendarDateError, .invalidDate)
            }
        }
        let data = Data(calendar(event("UID:skipped-day\nDTSTART;VALUE=DATE:20111230")).utf8)
        XCTAssertThrowsError(try ICalendarParser.parse(data, now: now, timeZone: apia)) {
            XCTAssertEqual($0 as? CalendarFeedError, .invalidCalendar(.noValidEvents))
        }
    }

    func testDateUntilDoesNotWarnAboutSkippedCivilDayOutsideRule() throws {
        let apia = TimeZone(identifier: "Pacific/Apia")!
        let data = Data(calendar(event("UID:until-before-skipped-day\nDTSTART;VALUE=DATE:20111223\nRRULE:FREQ=WEEKLY;UNTIL=20111229")).utf8)
        let result = try ICalendarParser.parse(data, now: DateParsing.portal("2011-12-23T00:00:00Z")!, timeZone: apia)
        XCTAssertEqual(result.events.map(\.start), [DateParsing.portal("2011-12-23T10:00:00Z")!])
        XCTAssertEqual(result.warnings, [])
    }

    func testUnsupportedExceptionsAndCustomTimeZoneAreVisible() throws {
        let result = try parsed("UID:exception\nDTSTART:20261022T180000Z\nRECURRENCE-ID:20261022T180000Z\nEXRULE:FREQ=YEARLY")
        XCTAssertEqual(result.events.count, 1)
        XCTAssertTrue(result.warnings.contains { $0.cause == .unsupportedException })
        let text = calendar("BEGIN:VTIMEZONE\nTZID:Private/Zone\nEND:VTIMEZONE\n" + event())
        let custom = try ICalendarParser.parse(Data(text.utf8), now: now, timeZone: zone)
        XCTAssertEqual(custom.events.count, 1)
        XCTAssertEqual(custom.warnings.first?.cause, .unsupportedTimeZoneDefinition)
    }

    func testDateOccurrenceIdentityUsesCivilDateWhenDeviceTimeZoneChanges() throws {
        let data = Data(calendar(event("UID:civil-day\nDTSTART;VALUE=DATE:20261022\nRRULE:FREQ=WEEKLY;COUNT=2")).utf8)
        let vienna = try ICalendarParser.parse(data, now: now, timeZone: zone)
        let newYork = try ICalendarParser.parse(data, now: now, timeZone: TimeZone(identifier: "America/New_York")!)
        XCTAssertEqual(vienna.events.map(\.id), newYork.events.map(\.id))
        XCTAssertNotEqual(vienna.events.first?.start, newYork.events.first?.start)
        XCTAssertEqual(vienna.events.first?.calendarTimeZoneID, "Europe/Vienna")
        XCTAssertEqual(newYork.events.first?.calendarTimeZoneID, "America/New_York")
    }

    func testDuplicateUIDOccurrencesMergeAndIDsSurviveOrderChanges() throws {
        let a = event("UID:same\nDTSTART:20261022T180000Z\nSUMMARY:One")
        let b = event("UID:same\nDTSTART:20261029T180000Z\nSUMMARY:Two")
        let first = try ICalendarParser.parse(Data(calendar(a + "\n" + b + "\n" + a).utf8), now: now, timeZone: zone)
        let second = try ICalendarParser.parse(Data(calendar(b + "\n" + a).utf8), now: now, timeZone: zone)
        XCTAssertEqual(first.events.map(\.id), second.events.map(\.id))
        XCTAssertEqual(first.events.count, 2)
        XCTAssertTrue(first.warnings.contains { $0.cause == .duplicateOccurrence })
        let missing = "DTSTART:20261022T180000Z\nSUMMARY:No UID"
        XCTAssertEqual(try parsed(missing).events.first?.id, try parsed(missing).events.first?.id)
        XCTAssertEqual(try parsed(missing).warnings.first?.cause, .missingUID)
    }

    func testOnlyExactNonrecurringPortalUIDPreservesPortalIdentityAndExplicitGroupLink() throws {
        let item = try XCTUnwrap(parsed("UID:meetup-event-2826@einundzwanzig.space\nDTSTART:20261022T180000Z\nURL:https://portal.einundzwanzig.space/de/meetup/wien").events.first)
        XCTAssertEqual(item.id, "2826")
        XCTAssertEqual(item.meetupPortalURL?.lastPathComponent, "wien")
        XCTAssertNil(item.meetupID)
        for uid in ["2826", "meetup-event-02826@einundzwanzig.space", "meetup-event-2826@evil.example", "meetup-event-0@einundzwanzig.space"] {
            XCTAssertTrue(try parsed("UID:\(uid)\nDTSTART:20261022T180000Z").events[0].id.hasPrefix("ical:"))
        }
        let series = try parsed("UID:meetup-event-2826@einundzwanzig.space\nDTSTART:20261022T180000Z\nRRULE:FREQ=WEEKLY;COUNT=2")
        XCTAssertEqual(series.events.count, 2)
        XCTAssertTrue(series.events.allSatisfy { $0.id.hasPrefix("ical:") })
        XCTAssertNil(try parsed("UID:venue\nDTSTART:20261022T180000Z\nURL:https://example.org/de/meetup/wien").events.first?.meetupPortalURL)
    }

    func testLiveFixtureWhenProvided() throws {
        guard let path = ProcessInfo.processInfo.environment["MEETUP_LIVE_ICS_FIXTURE"] else {
            throw XCTSkip("Set MEETUP_LIVE_ICS_FIXTURE to characterize an independently captured live feed.")
        }
        let result = try ICalendarParser.parse(Data(contentsOf: URL(fileURLWithPath: path)), now: now, timeZone: zone)
        XCTAssertEqual(result.events.count, 629)
        XCTAssertEqual(result.warnings, [])
        XCTAssertEqual(Set(result.events.map(\.id)).count, result.events.count)
        XCTAssertTrue(result.events.allSatisfy { $0.latitude == nil && $0.longitude == nil })
    }
}

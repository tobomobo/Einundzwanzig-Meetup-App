import Foundation
import XCTest
@testable import MeetupCore

final class RecurrenceTests: XCTestCase {
    private let utc = TimeZone(secondsFromGMT: 0)!

    private func date(_ text: String, file: StaticString = #filePath, line: UInt = #line) throws -> Date {
        try XCTUnwrap(DateParsing.portal(text), file: file, line: line)
    }

    private func expand(_ start: String, _ rule: String, now: String = "2026-01-01T00:00:00Z",
                        zone: String = "UTC") throws -> RecurrenceExpansion {
        RecurrenceExpander.expand(start: try date(start), rule: rule,
                                  timeZone: try XCTUnwrap(TimeZone(identifier: zone)), now: try date(now))
    }

    private func assertStarts(_ result: RecurrenceExpansion, _ expected: [String],
                              file: StaticString = #filePath, line: UInt = #line) throws {
        XCTAssertEqual(result.starts, try expected.map { try date($0) }, file: file, line: line)
        XCTAssertEqual(result.starts, Array(Set(result.starts)).sorted(), file: file, line: line)
        XCTAssertTrue(result.warnings.allSatisfy { $0.source == .ical && $0.eventIndex == nil }, file: file, line: line)
    }

    func testWeeklyDefaultAndIntervalFixtures() throws {
        let weekly = try expand("2026-01-06T19:00:00Z", "FREQ=WEEKLY;COUNT=4")
        try assertStarts(weekly, ["2026-01-06T19:00:00Z", "2026-01-13T19:00:00Z",
                                  "2026-01-20T19:00:00Z", "2026-01-27T19:00:00Z"])
        XCTAssertTrue(weekly.warnings.isEmpty)
        let interval = try expand("2026-01-05T18:30:00Z", "RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TH;COUNT=5")
        try assertStarts(interval, ["2026-01-05T18:30:00Z", "2026-01-08T18:30:00Z", "2026-01-19T18:30:00Z",
                                    "2026-01-22T18:30:00Z", "2026-02-02T18:30:00Z"])
        XCTAssertTrue(interval.warnings.isEmpty)
    }

    func testWeeklyByDayIsSortedAndUniqueBeforeCount() throws {
        let first = try expand("2026-01-05T18:30:00Z", "FREQ=WEEKLY;BYDAY=TH,MO,MO;COUNT=3")
        let second = try expand("2026-01-05T18:30:00Z", "FREQ=WEEKLY;BYDAY=MO,TH;COUNT=3")
        try assertStarts(first, ["2026-01-05T18:30:00Z", "2026-01-08T18:30:00Z", "2026-01-12T18:30:00Z"])
        XCTAssertEqual(first.starts, second.starts)
        XCTAssertTrue(first.warnings.isEmpty)
    }

    func testWeeklyMondayAnchorAcrossNewYearAndUnmatchedBase() throws {
        let result = try expand("2025-12-28T19:00:00Z", "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,FR;COUNT=4")
        try assertStarts(result, ["2025-12-28T19:00:00Z", "2026-01-05T19:00:00Z",
                                  "2026-01-09T19:00:00Z", "2026-01-19T19:00:00Z"])
        XCTAssertTrue(result.warnings.isEmpty)
    }

    func testCountOneIsOnlyBaseAndCountTwoHasOneAdditionalStart() throws {
        for frequency in ["WEEKLY", "MONTHLY"] {
            let result = try expand("2026-01-06T19:00:00Z", "FREQ=\(frequency);COUNT=1")
            try assertStarts(result, ["2026-01-06T19:00:00Z"])
            XCTAssertTrue(result.warnings.isEmpty)
        }
        let result = try expand("2026-01-06T19:00:00Z", "FREQ=WEEKLY;COUNT=2")
        try assertStarts(result, ["2026-01-06T19:00:00Z", "2026-01-13T19:00:00Z"])
    }

    func testMonthlyOrdinalsAndLastWeekday() throws {
        let fixtures: [(String, String, [String])] = [
            ("2026-01-15", "3TH", ["2026-01-15", "2026-02-19", "2026-03-19"]),
            ("2026-01-30", "-1FR", ["2026-01-30", "2026-02-27", "2026-03-27"]),
            ("2026-01-20", "-2TU", ["2026-01-20", "2026-02-17", "2026-03-24"]),
            ("2026-01-13", "+2TU", ["2026-01-13", "2026-02-10", "2026-03-10"])
        ]
        for (start, selector, expected) in fixtures {
            let result = try expand(start + "T19:00:00Z", "FREQ=MONTHLY;BYDAY=\(selector);COUNT=3")
            try assertStarts(result, expected.map { $0 + "T19:00:00Z" })
            XCTAssertTrue(result.warnings.isEmpty, selector)
        }
    }

    func testMonthlyShortMonthsAreSkippedWithoutInvalidDateWarnings() throws {
        let result = try expand("2026-01-31T19:00:00Z", "FREQ=MONTHLY;COUNT=3")
        try assertStarts(result, ["2026-01-31T19:00:00Z", "2026-03-31T19:00:00Z", "2026-05-31T19:00:00Z"])
        XCTAssertTrue(result.warnings.isEmpty)
        let fifth = try expand("2026-01-29T19:00:00Z", "FREQ=MONTHLY;BYDAY=5TH;COUNT=3")
        try assertStarts(fifth, ["2026-01-29T19:00:00Z", "2026-04-30T19:00:00Z", "2026-07-30T19:00:00Z"])
        XCTAssertTrue(fifth.warnings.isEmpty)
    }

    func testMonthlyLeapDayAndIntervalKeepOriginalAnchor() throws {
        let leap = try expand("2024-01-29T19:00:00Z", "FREQ=MONTHLY;COUNT=3", now: "2024-01-01T00:00:00Z")
        try assertStarts(leap, ["2024-01-29T19:00:00Z", "2024-02-29T19:00:00Z", "2024-03-29T19:00:00Z"])
        let interval = try expand("2025-12-31T19:00:00Z", "FREQ=MONTHLY;INTERVAL=2;COUNT=3")
        try assertStarts(interval, ["2025-12-31T19:00:00Z", "2026-08-31T19:00:00Z", "2026-10-31T19:00:00Z"])
        XCTAssertTrue(interval.warnings.isEmpty)
    }

    func testMonthlyBareWeekdaysAndOverlappingSelectorsIncludeBaseMonth() throws {
        let result = try expand("2026-01-05T19:00:00Z", "FREQ=MONTHLY;BYDAY=-1MO,MO,1MO;COUNT=6")
        try assertStarts(result, ["2026-01-05T19:00:00Z", "2026-01-12T19:00:00Z", "2026-01-19T19:00:00Z",
                                  "2026-01-26T19:00:00Z", "2026-02-02T19:00:00Z", "2026-02-09T19:00:00Z"])
        XCTAssertTrue(result.warnings.isEmpty)
        let ordinals = try expand("2026-01-05T19:00:00Z", "FREQ=MONTHLY;BYDAY=-1FR,1MO;COUNT=4")
        try assertStarts(ordinals, ["2026-01-05T19:00:00Z", "2026-01-30T19:00:00Z",
                                    "2026-02-02T19:00:00Z", "2026-02-27T19:00:00Z"])
    }

    func testUntilUTCDateTimeIsInclusiveAndRetainsTime() throws {
        let excluded = try expand("2026-01-06T19:00:00Z", "FREQ=WEEKLY;UNTIL=20260120T000000Z")
        try assertStarts(excluded, ["2026-01-06T19:00:00Z", "2026-01-13T19:00:00Z"])
        let included = try expand("2026-01-06T19:00:00Z", "FREQ=WEEKLY;UNTIL=20260120T190000Z")
        try assertStarts(included, ["2026-01-06T19:00:00Z", "2026-01-13T19:00:00Z", "2026-01-20T19:00:00Z"])
        XCTAssertTrue(excluded.warnings.isEmpty)
        XCTAssertTrue(included.warnings.isEmpty)
    }

    func testUntilFloatingDateTimeUsesSuppliedZone() throws {
        let result = try expand("2026-01-06T19:00:00+01:00", "FREQ=WEEKLY;UNTIL=20260120T185959", zone: "Europe/Vienna")
        try assertStarts(result, ["2026-01-06T19:00:00+01:00", "2026-01-13T19:00:00+01:00"])
        XCTAssertTrue(result.warnings.isEmpty)
    }

    func testUntilDateIncludesWholeLocalDayAndFractionalSeconds() throws {
        let result = try expand("2026-01-06T23:59:59.5-05:00", "FREQ=WEEKLY;UNTIL=20260120", zone: "America/New_York")
        try assertStarts(result, ["2026-01-06T23:59:59.5-05:00", "2026-01-13T23:59:59.5-05:00",
                                  "2026-01-20T23:59:59.5-05:00"])
        XCTAssertTrue(result.warnings.isEmpty)
    }

    func testWeeklyAndMonthlyPreserveWallTimeAndPrecisionAcrossDST() throws {
        let weekly = try expand("2026-03-22T19:15:42.125+01:00", "FREQ=WEEKLY;COUNT=3", zone: "Europe/Vienna")
        try assertStarts(weekly, ["2026-03-22T19:15:42.125+01:00", "2026-03-29T19:15:42.125+02:00",
                                  "2026-04-05T19:15:42.125+02:00"])
        let monthly = try expand("2026-09-25T19:15:42.125+02:00", "FREQ=MONTHLY;COUNT=3", zone: "Europe/Vienna")
        try assertStarts(monthly, ["2026-09-25T19:15:42.125+02:00", "2026-10-25T19:15:42.125+01:00",
                                   "2026-11-25T19:15:42.125+01:00"])
        XCTAssertTrue(weekly.warnings.isEmpty)
        XCTAssertTrue(monthly.warnings.isEmpty)
    }

    func testDSTGapIsSkippedWithWarningAndDoesNotConsumeCount() throws {
        let result = try expand("2026-03-22T02:30:00+01:00", "FREQ=WEEKLY;COUNT=3", zone: "Europe/Vienna")
        try assertStarts(result, ["2026-03-22T02:30:00+01:00", "2026-04-05T02:30:00+02:00", "2026-04-12T02:30:00+02:00"])
        XCTAssertEqual(result.warnings.map(\.cause), [.invalidDate])
        let monthly = try expand("2026-01-29T02:30:00+01:00", "FREQ=MONTHLY;COUNT=4", zone: "Europe/Vienna")
        try assertStarts(monthly, ["2026-01-29T02:30:00+01:00", "2026-04-29T02:30:00+02:00",
                                   "2026-05-29T02:30:00+02:00", "2026-06-29T02:30:00+02:00"])
        XCTAssertEqual(monthly.warnings.map(\.cause), [.invalidDate])
    }

    func testDSTFoldChoosesFirstInstantOnce() throws {
        let result = try expand("2026-10-18T02:30:00+02:00", "FREQ=WEEKLY;COUNT=3", zone: "Europe/Vienna")
        try assertStarts(result, ["2026-10-18T02:30:00+02:00", "2026-10-25T02:30:00+02:00", "2026-11-01T02:30:00+01:00"])
        XCTAssertTrue(result.warnings.isEmpty)
    }

    func testUnsupportedRulesRetainExactlyBaseAndNeverExposePayload() throws {
        let start = "2026-01-06T19:00:00Z"
        for rule in ["FREQ=DAILY", "FREQ=YEARLY;COUNT=1", "FREQ=WEEKLY;BYMONTH=3", "FREQ=WEEKLY;WKST=SU",
                     "FREQ=MONTHLY;BYSETPOS=-1", "FREQ=WEEKLY;COUNT=1;PRIVATE=secret-user-payload"] {
            let result = try expand(start, rule)
            try assertStarts(result, [start])
            XCTAssertEqual(result.warnings.map(\.cause), [.unsupportedRecurrence], rule)
            XCTAssertFalse(result.warnings[0].message.contains("secret-user-payload"))
        }
    }

    func testMalformedRulesAreAtomicIncludingExplicitEmptyRule() throws {
        let start = "2026-01-06T19:00:00Z"
        let rules = ["", "  \n  ", "RRULE:", "COUNT=3", "FREQ=", "FREQ=WEEKLY;", "FREQ=WEEKLY;;COUNT=2",
                     "FREQ=WEEKLY;COUNT=2=3", "FREQ=WEEKLY;BROKEN", "FREQ=WEEKLY;FREQ=MONTHLY",
                     "FREQ=WEEKLY;INTERVAL=0", "FREQ=MONTHLY;INTERVAL=-1", "FREQ=WEEKLY;INTERVAL=1.5",
                     "FREQ=WEEKLY;INTERVAL=9999999999999999999999999", "FREQ=WEEKLY;COUNT=0",
                     "FREQ=WEEKLY;COUNT=-1", "FREQ=WEEKLY;COUNT=abc", "FREQ=WEEKLY;BYDAY=",
                     "FREQ=WEEKLY;BYDAY=TU,BOGUS", "FREQ=WEEKLY;BYDAY=MO,", "FREQ=WEEKLY;BYDAY=1TU",
                     "FREQ=MONTHLY;BYDAY=0MO", "FREQ=MONTHLY;BYDAY=-6FR", "FREQ=MONTHLY;BYDAY=--1FR",
                     "FREQ=WEEKLY;UNTIL=tomorrow", "FREQ=WEEKLY;UNTIL=20260120T1900Z",
                     "FREQ=WEEKLY;COUNT=1;UNTIL=20260120", "FREQ=WEEKLY;COUNT=1;BYDAY=TU,INVALID"]
        for rule in rules {
            let result = try expand(start, rule)
            try assertStarts(result, [start])
            XCTAssertEqual(result.warnings.map(\.cause), [.malformedRecurrence], rule)
        }
    }

    func testInvalidAndNonexistentUntilDatesRetainBaseWithWarning() throws {
        let start = "2026-01-06T19:00:00Z"
        for until in ["20260230", "20261301", "20260120T240000Z", "20260329T023000"] {
            let result = try expand(start, "FREQ=WEEKLY;UNTIL=\(until)", zone: "Europe/Vienna")
            try assertStarts(result, [start])
            XCTAssertEqual(result.warnings.map(\.cause), [.invalidDate], until)
        }
    }

    func testRuleCaseAndSurroundingWhitespaceAreAccepted() throws {
        let result = try expand("2026-01-05T19:00:00Z", " rrule: freq = weekly ; byday = th, mo ; count = 3 ")
        try assertStarts(result, ["2026-01-05T19:00:00Z", "2026-01-08T19:00:00Z", "2026-01-12T19:00:00Z"])
        XCTAssertTrue(result.warnings.isEmpty)
    }

    func testBaseSurvivesUntilBeforeBaseAndHorizonBeforeBase() throws {
        let until = try expand("2026-01-06T19:00:00Z", "FREQ=WEEKLY;UNTIL=20250101")
        try assertStarts(until, ["2026-01-06T19:00:00Z"])
        XCTAssertTrue(until.warnings.isEmpty)
        let horizon = try expand("2030-01-06T19:00:00Z", "FREQ=WEEKLY")
        try assertStarts(horizon, ["2030-01-06T19:00:00Z"])
        XCTAssertEqual(horizon.warnings.map(\.cause), [.recurrenceLimit])
    }

    func testHorizonUsesCalendarDaysAndIncludesExactBoundary() throws {
        let now = "2025-03-28T19:00:00+01:00"
        let result = try expand("2026-03-22T19:00:00+01:00", "FREQ=WEEKLY", now: now, zone: "Europe/Vienna")
        try assertStarts(result, ["2026-03-22T19:00:00+01:00", "2026-03-29T19:00:00+02:00"])
        XCTAssertEqual(result.warnings.map(\.cause), [.recurrenceLimit])
        let justPast = try expand("2026-03-22T19:00:00.125+01:00", "FREQ=WEEKLY", now: now, zone: "Europe/Vienna")
        try assertStarts(justPast, ["2026-03-22T19:00:00.125+01:00"])
    }

    func testOldDenseSeriesCapIsDeterministicAndExplicit() throws {
        let start = try date("1900-01-01T19:00:00Z")
        let now = try date("2026-01-01T00:00:00Z")
        let rule = "FREQ=WEEKLY;BYDAY=SU,SA,FR,TH,WE,TU,MO"
        let result = RecurrenceExpander.expand(start: start, rule: rule, timeZone: utc, now: now)
        XCTAssertEqual(result.starts.count, 200)
        XCTAssertEqual(result.starts.first, start)
        XCTAssertEqual(result.starts.last, try date("1900-07-19T19:00:00Z"))
        XCTAssertEqual(result.starts, Array(Set(result.starts)).sorted())
        XCTAssertEqual(result.warnings.map(\.cause), [.recurrenceLimit])
        XCTAssertEqual(result.starts, RecurrenceExpander.expand(start: start, rule: rule, timeZone: utc, now: now).starts)
    }

    func testNaturalCountCompletionAtCapDoesNotWarn() throws {
        let days = "MO,TU,WE,TH,FR,SA,SU"
        let natural = try expand("2026-01-05T19:00:00Z", "FREQ=WEEKLY;BYDAY=\(days);COUNT=200")
        XCTAssertEqual(natural.starts.count, 200)
        XCTAssertTrue(natural.warnings.isEmpty)
        let capped = try expand("2026-01-05T19:00:00Z", "FREQ=WEEKLY;BYDAY=\(days);COUNT=201")
        XCTAssertEqual(capped.starts, natural.starts)
        XCTAssertEqual(capped.warnings.map(\.cause), [.recurrenceLimit])
    }

    func testOldSparsePatternsAndExtremeIntervalHaveBoundedWork() throws {
        // Only leap February can contain five Mondays. The work budget must stop
        // this sparse series before either the horizon or output cap is reached.
        let sparse = try expand("1601-02-01T19:00:00Z", "FREQ=MONTHLY;INTERVAL=12;BYDAY=5MO", now: "9990-01-01T00:00:00Z")
        XCTAssertEqual(sparse.starts.first, try date("1601-02-01T19:00:00Z"))
        XCTAssertGreaterThan(sparse.starts.count, 1)
        XCTAssertLessThan(sparse.starts.count, 200)
        XCTAssertEqual(sparse.starts, Array(Set(sparse.starts)).sorted())
        XCTAssertLessThan(try XCTUnwrap(sparse.starts.last), try date("5697-01-01T00:00:00Z"))
        XCTAssertEqual(sparse.warnings.map(\.cause), [.recurrenceLimit])
        // February 2026 has 28 days, and its Gregorian 400-year cycle repeats.
        let impossible = try expand("2026-02-01T19:00:00Z", "FREQ=MONTHLY;INTERVAL=4800;BYDAY=5MO", now: "9990-01-01T00:00:00Z")
        try assertStarts(impossible, ["2026-02-01T19:00:00Z"])
        XCTAssertEqual(impossible.warnings.map(\.cause), [.recurrenceLimit])
        for frequency in ["WEEKLY", "MONTHLY"] {
            let huge = try expand("2026-01-06T19:00:00Z", "FREQ=\(frequency);INTERVAL=\(Int.max)")
            try assertStarts(huge, ["2026-01-06T19:00:00Z"])
            XCTAssertEqual(huge.warnings.map(\.cause), [.recurrenceLimit])
        }
    }

    func testInvalidInjectedDateWarnsWithoutPassingNaNToCalendar() throws {
        let start = try date("2026-01-06T19:00:00Z")
        let result = RecurrenceExpander.expand(start: start, rule: "FREQ=WEEKLY", timeZone: utc,
                                               now: Date(timeIntervalSince1970: .nan))
        XCTAssertEqual(result.starts, [start])
        XCTAssertEqual(result.warnings.map(\.cause), [.invalidDate])
    }
}

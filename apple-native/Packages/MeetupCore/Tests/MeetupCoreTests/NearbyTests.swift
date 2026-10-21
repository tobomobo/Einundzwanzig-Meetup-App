import Foundation
import XCTest
@testable import MeetupCore

final class NearbyTests: XCTestCase {
    private func group(_ id: String, _ name: String, _ city: String, _ slug: String) -> Meetup {
        Meetup(id: id, name: name, city: city, latitude: 48.2082, longitude: 16.3738,
               portalURL: URL(string: "https://portal.einundzwanzig.space/de/meetup/" + slug))
    }
    private func event(_ title: String, id: String? = nil, link: String? = nil, location: String = "") -> MeetupEvent {
        MeetupEvent(id: "event", title: title, start: Date(timeIntervalSince1970: 100), location: location,
                    meetupID: id, meetupPortalURL: link.flatMap(URL.init(string:)))
    }

    func testExplicitIdentityNeverFallsThrough() {
        let a = group("107", "Einundzwanzig Südniedersachsen", "Göttingen", "suedniedersachsen")
        let b = group("32", "Einundzwanzig Sachsen", "Sachsen", "sachsen")
        let values = [a, b]
        XCTAssertEqual(MeetupEventMatcher.resolve(event(a.name, id: "32", link: a.portalURL!.absoluteString), among: values)?.id, "32")
        XCTAssertNil(MeetupEventMatcher.resolve(event(a.name, id: "unknown", link: a.portalURL!.absoluteString), among: values))
        XCTAssertNil(MeetupEventMatcher.resolve(event(a.name, link: "https://example.org/de/meetup/suedniedersachsen"), among: values))
        XCTAssertNil(MeetupEventMatcher.resolve(event(a.name, link: "https://portal.einundzwanzig.space/de/meetup/unknown"), among: values))
    }
    func testPortalGroupURLNormalizesLocaleQueryAndSlash() {
        let a = group("107", "Einundzwanzig Südniedersachsen", "Göttingen", "suedniedersachsen")
        let value = event("Other title", link: "https://portal.einundzwanzig.space/en/meetup/suedniedersachsen/?source=calendar")
        XCTAssertEqual(MeetupEventMatcher.resolve(value, among: [a])?.id, a.id)
    }
    func testExactNamesAmbiguousCityAndUnicodeBoundaries() {
        let a = group("1", "BitcoinWalk Würzburg", "Würzburg", "walk")
        let b = group("2", "Würzburg Meetup", "Würzburg", "meetup")
        XCTAssertEqual(MeetupEventMatcher.resolve(event(a.name), among: [a, b])?.id, a.id)
        XCTAssertNil(MeetupEventMatcher.resolve(event("Treffen Würzburg"), among: [a, b]))
        let saxony = group("3", "Einundzwanzig Sachsen", "Sachsen", "sachsen")
        XCTAssertNil(MeetupEventMatcher.resolve(event("Südniedersachsen"), among: [saxony]))
        XCTAssertNil(MeetupEventMatcher.resolve(event("ÜSachsen"), among: [saxony]))
        XCTAssertEqual(MeetupEventMatcher.resolve(event("Treffen in Sachsen"), among: [saxony])?.id, saxony.id)
        let frankfurt = group("4", "Bitcoin Frankfurt", "Frankfurt", "frankfurt")
        XCTAssertNil(MeetupEventMatcher.resolve(event("Frankfurter Straße"), among: [frankfurt]))
    }
    func testAssociationPreservesRawSourceAndEventVenue() {
        let a = group("1", "Bitcoin Wien", "Wien", "wien")
        let original = event(a.name)
        let associated = MeetupEventMatcher.associated([original], with: [a])[0]
        XCTAssertNil(original.meetupID)
        XCTAssertEqual(associated.meetupID, a.id)
        XCTAssertNil(associated.latitude)
        XCTAssertNil(associated.longitude)
    }
    func testVenueDoesNotFallBackToGroupCoordinate() throws {
        let parsed = try PortalParsing.events(Data(#"[{"id":1,"start":"2026-10-21 18:00","meetup":{"id":2,"latitude":48.2,"longitude":16.3,"portalLink":"https://portal.einundzwanzig.space/de/meetup/wien"}}]"#.utf8))[0]
        XCTAssertNil(parsed.latitude)
        XCTAssertNil(parsed.longitude)
        XCTAssertNotNil(parsed.meetupPortalURL)
    }
    func testGeodesicBoundaryDatelineAndInvalidCoordinates() {
        let vienna = GeoPoint(latitude: 48.2082, longitude: 16.3738)!
        let berlin = GeoPoint(latitude: 52.52, longitude: 13.405)!
        XCTAssertEqual(vienna.kilometers(to: vienna), 0)
        XCTAssertEqual(vienna.kilometers(to: berlin), 524.2, accuracy: 2)
        XCTAssertLessThan(GeoPoint(latitude: 0, longitude: 179.9)!.kilometers(to: GeoPoint(latitude: 0, longitude: -179.9)!), 23)
        XCTAssertNil(GeoPoint(latitude: .nan, longitude: 0))
        XCTAssertNil(GeoPoint(latitude: 91, longitude: 0))
    }
    func testDateIntervalExcludesNextMidnightAcrossDST() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Vienna")!
        let start = calendar.date(from: DateComponents(year: 2026, month: 3, day: 29))!
        let end = calendar.date(byAdding: .day, value: 1, to: start)!
        XCTAssertEqual(end.timeIntervalSince(start), 23 * 3600)
        let group = group("1", "Wien", "Wien", "wien")
        let a = MeetupEvent(id: "a", title: "Wien", start: start, meetupID: "1")
        let b = MeetupEvent(id: "b", title: "Wien", start: end, meetupID: "1")
        let result = NearbySearch.search(meetups: [group], events: [a, b], center: nil, radiusKilometers: 25, interval: DateInterval(start: start, end: end))
        XCTAssertEqual(result.first?.events.map(\.id), ["a"])
    }
    func testRadiusPreservesDifferentGroupsAndUndatedGroups() {
        let a = group("1", "Group one", "Wien", "one")
        let b = group("2", "Group two", "Wien", "two")
        let center = GeoPoint(latitude: 48.2082, longitude: 16.3738)!
        let results = NearbySearch.search(meetups: [a, b], events: [], center: center, radiusKilometers: 25)
        XCTAssertEqual(Set(results.map(\.id)), ["1", "2"])
        XCTAssertTrue(NearbySearch.search(meetups: [a, b], events: [], center: center, radiusKilometers: 25, interval: DateInterval(start: .distantPast, end: .distantFuture)).isEmpty)
        XCTAssertTrue(NearbySearch.search(meetups: [a], events: [], center: center, radiusKilometers: .nan).isEmpty)
    }
    func testAllDaySurvivesAfternoonAndUsesExclusiveCivilEnd() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/Santiago")!
        let start = calendar.date(from: DateComponents(year: 2026, month: 9, day: 6))!
        let end = calendar.date(from: DateComponents(year: 2026, month: 9, day: 7))!
        let afternoon = calendar.date(from: DateComponents(year: 2026, month: 9, day: 6, hour: 18))!
        let allDay = MeetupEvent(id: "day", start: start, isAllDay: true, calendarTimeZoneID: calendar.timeZone.identifier)
        XCTAssertTrue(CalendarDisplayWindow.includes(allDay, now: afternoon))
        XCTAssertFalse(CalendarDisplayWindow.includes(allDay, now: end))
        XCTAssertFalse(CalendarDisplayWindow.includes(MeetupEvent(id: "timed", start: start), now: afternoon))
    }
    func testAllDayDateFilterSurvivesDeviceTimeZoneChange() {
        var source = Calendar(identifier: .gregorian)
        source.timeZone = TimeZone(identifier: "Europe/Berlin")!
        let start = source.date(from: DateComponents(year: 2026, month: 10, day: 25))!
        var target = Calendar(identifier: .gregorian)
        target.timeZone = TimeZone(identifier: "America/New_York")!
        let filterStart = target.date(from: DateComponents(year: 2026, month: 10, day: 25))!
        let filterEnd = target.date(byAdding: .day, value: 1, to: filterStart)!
        let group = group("1", "Group", "Wien", "group")
        let event = MeetupEvent(id: "day", start: start, meetupID: "1", isAllDay: true, calendarTimeZoneID: source.timeZone.identifier)
        let matches = NearbySearch.search(meetups: [group], events: [event], center: nil, radiusKilometers: 25,
                                          interval: DateInterval(start: filterStart, end: filterEnd), timeZone: target.timeZone)
        XCTAssertEqual(matches.first?.events.map(\.id), ["day"])
    }
}

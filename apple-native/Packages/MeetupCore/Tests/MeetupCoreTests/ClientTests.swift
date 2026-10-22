import Foundation
import XCTest
@testable import MeetupCore

actor RecordingTransport: HTTPTransport {
    private let responses: [String: Data]
    private let failure: MeetupCoreError?
    private var requests: [URL] = []

    init(_ responses: [String: String] = [:], failure: MeetupCoreError? = nil) {
        self.responses = responses.mapValues { Data($0.utf8) }
        self.failure = failure
    }

    func get(_ url: URL, accept: String) async throws -> Data {
        requests.append(url)
        if let failure { throw failure }
        guard let data = responses[url.path] else { throw MeetupCoreError.invalidData("Unexpected test request: \(url.path)") }
        return data
    }

    func paths() -> [String] { requests.map(\.path) }
}

final class ClientTests: XCTestCase, @unchecked Sendable {
    func testDirectoryAliasesNullsAndIdentity() throws {
        let data = Data(#"""
        [
          {"id":1,"name":"Wien Nord","city":"Wien","country":"AT","latitude":"48.25","longitude":16.3,
           "intro":"Hello","url":"https://t.me/example","logo":"/storage/logo.png","slug":"wien / nord",
           "next_event_start":"2026-10-21 12:00","next_event_start_iso":"2026-10-21T17:00:00+02:00"},
          {"id":"2","name":"Wien Süd","city":"Wien","lat":48,"lon":"16.5","logo":null,"country":null},
          {"id":1,"name":"Duplicate ID","city":"Other"},
          {"id":3,"name":"No position","lat":true,"longitude":"NaN","lng":"17.1","url":"javascript:bad()"}
        ]
        """#.utf8)
        let meetups = try PortalParsing.meetups(data)
        XCTAssertEqual(meetups.map(\.id), ["1", "2", "3"])
        XCTAssertEqual(meetups.filter { $0.city == "Wien" }.count, 2)
        XCTAssertEqual(meetups[0].latitude, 48.25)
        XCTAssertEqual(meetups[1].longitude, 16.5)
        XCTAssertEqual(meetups[0].description, "Hello")
        XCTAssertEqual(meetups[0].telegramURL?.host, "t.me")
        XCTAssertEqual(meetups[0].logoURL?.absoluteString, "https://portal.einundzwanzig.space/storage/logo.png")
        XCTAssertEqual(meetups[0].portalURL?.absoluteString, "https://portal.einundzwanzig.space/de/meetup/wien%20%2F%20nord")
        XCTAssertEqual(meetups[0].nextEventStart, DateParsing.portal("2026-10-21T15:00:00Z"))
        XCTAssertNil(meetups[1].logoURL)
        XCTAssertNil(meetups[1].nextEventStart)
        XCTAssertNil(meetups[2].latitude)
        XCTAssertEqual(meetups[2].longitude, 17.1)
        XCTAssertNil(meetups[2].telegramURL)
    }

    func testEventFlatNestedFieldsAndOptionalValues() throws {
        let data = Data(#"""
        {"data":[
          {"id":70,"title":null,"start":"2026-10-21 12:00","start_iso":"2026-10-21T17:00:00+02:00",
           "end":"2026-10-21 18:00","end_iso":"2026-10-21T18:30:00Z","location":"Venue",
           "meetup.id":8,"meetup.name":"Flat meetup","meetup.portalLink":"https://example.org/meetup",
           "meetup.latitude":48,"meetup.longitude":16,"osm_lat":"48.2","osm_lon":"16.2","nostr_address":"31923:key:event"},
          {"id":"71","title":"Custom title","start":"2026-10-22 16:00",
           "meetup":{"id":"9","name":"Nested meetup","portalLink":"https://example.org/nested","lat":"49","lng":"17"}},
          {"id":72,"start":"2026-10-23 18:00","meetup_id":10}
        ]}
        """#.utf8)
        let events = try PortalParsing.events(data)
        XCTAssertEqual(events[0].title, "Flat meetup")
        XCTAssertEqual(events[0].meetupID, "8")
        XCTAssertEqual(events[0].start, DateParsing.portal("2026-10-21T15:00:00Z"))
        XCTAssertEqual(events[0].end, DateParsing.portal("2026-10-21T18:30:00Z"))
        XCTAssertEqual(events[0].latitude, 48.2)
        XCTAssertEqual(events[0].longitude, 16.2)
        XCTAssertEqual(events[0].url?.absoluteString, "https://example.org/meetup")
        XCTAssertEqual(events[0].nostrAddress, "31923:key:event")
        XCTAssertEqual(events[1].meetupName, "Nested meetup")
        XCTAssertNil(events[1].latitude, "Group coordinates are not an event venue")
        XCTAssertEqual(events[1].title, "Custom title")
        XCTAssertEqual(events[2].meetupID, "10")
        XCTAssertNil(events[2].end)
        XCTAssertNil(events[2].url)
        XCTAssertEqual(events[2].description, "")
    }

    func testPortalRequestsTodayAndNextTwoMonthStartsAcrossYearBoundary() async throws {
        let transport = RecordingTransport([
            "/api/mobile/meetups": #"[{"id":1,"name":"One"}]"#,
            "/api/meetup-events/2026-12-31": #"[{"id":1,"start":"2027-01-02 18:00"}]"#,
            "/api/meetup-events/2027-01-01": #"{"data":[{"id":1,"title":"Updated","start":"2027-01-02 18:00"},{"id":2,"start":"2027-01-02 18:00"}]}"#,
            "/api/meetup-events/2027-02-01": #"[{"id":3,"start":"2027-02-01 18:00"}]"#
        ])
        let client = PortalClient(transport: transport)
        let meetups = try await client.meetups()
        XCTAssertEqual(meetups.first?.name, "One")
        let events = try await client.events(now: try XCTUnwrap(DateParsing.portal("2026-12-31T23:59:00Z")))
        XCTAssertEqual(events.map(\.id), ["1", "2", "3"])
        XCTAssertEqual(events[0].title, "Updated")
        let paths = await transport.paths()
        XCTAssertEqual(paths, ["/api/mobile/meetups", "/api/meetup-events/2026-12-31", "/api/meetup-events/2027-01-01", "/api/meetup-events/2027-02-01"])
    }

    func testPortalRejectsMalformedPayloadInsteadOfInventingEvents() {
        for input in ["not JSON", "{}", "[true]", #"[{"name":"no ID"}]"#] {
            XCTAssertThrowsError(try PortalParsing.meetups(Data(input.utf8)))
        }
        for input in [#"[{"id":true,"start":"2026-10-21 18:00"}]"#, #"[{"id":1}]"#, #"[{"id":1,"start":"bad date"}]"#] {
            XCTAssertThrowsError(try PortalParsing.events(Data(input.utf8)))
        }
    }

    func testNetworkErrorsPropagateAcrossAllClients() async throws {
        let transport = RecordingTransport(failure: .httpStatus(503))
        do { _ = try await PortalClient(transport: transport).meetups(); XCTFail("Expected an error") }
        catch { XCTAssertEqual(error as? MeetupCoreError, .httpStatus(503)) }
        do { _ = try await PortalClient(transport: transport).events(); XCTFail("Expected an error") }
        catch { XCTAssertEqual(error as? MeetupCoreError, .httpStatus(503)) }
        do { _ = try await NewsClient(transport: transport).articles(); XCTFail("Expected an error") }
        catch { XCTAssertEqual(error as? MeetupCoreError, .httpStatus(503)) }
        do { _ = try await MempoolClient(transport: transport).snapshot(); XCTFail("Expected an error") }
        catch { XCTAssertEqual(error as? MeetupCoreError, .httpStatus(503)) }
    }

    func testMempoolSnapshotAndCustomHost() async throws {
        let transport = RecordingTransport([
            "/node/api/blocks/tip/height": " 900000\n",
            "/node/api/v1/prices": #"{"USD":100000.5,"EUR":90000.25}"#,
            "/node/api/v1/fees/recommended": #"{"fastestFee":3,"halfHourFee":2,"hourFee":0}"#
        ])
        let before = Date()
        let snapshot = try await MempoolClient(transport: transport).snapshot(baseURL: try XCTUnwrap(URL(string: "https://node.example/node/api/")))
        XCTAssertEqual(snapshot.height, 900000)
        XCTAssertEqual(snapshot.usd, 100000.5)
        XCTAssertEqual(snapshot.eur, 90000.25)
        XCTAssertEqual(snapshot.fastestFee, 3)
        XCTAssertEqual(snapshot.halfHourFee, 2)
        XCTAssertEqual(snapshot.hourFee, 0)
        XCTAssertGreaterThanOrEqual(snapshot.fetchedAt, before)
        let paths = await transport.paths()
        XCTAssertEqual(Set(paths), ["/node/api/blocks/tip/height", "/node/api/v1/prices", "/node/api/v1/fees/recommended"])
    }

    func testMempoolRejectsMissingInvalidAndFractionalData() async throws {
        for (height, prices, fees) in [
            ("0", #"{"USD":1,"EUR":1}"#, #"{"fastestFee":1,"halfHourFee":1,"hourFee":1}"#),
            ("1", #"{"USD":1}"#, #"{"fastestFee":1,"halfHourFee":1,"hourFee":1}"#),
            ("1", #"{"USD":true,"EUR":1}"#, #"{"fastestFee":1,"halfHourFee":1,"hourFee":1}"#),
            ("1", #"{"USD":1,"EUR":1}"#, #"{"fastestFee":1.5,"halfHourFee":1,"hourFee":1}"#),
            ("1", #"{"USD":1,"EUR":1}"#, #"{"fastestFee":1,"halfHourFee":1,"hourFee":-1}"#),
            ("1", #"{"USD":1,"EUR":1}"#, #"{"fastestFee":1e100,"halfHourFee":1,"hourFee":1}"#)
        ] {
            let transport = RecordingTransport([
                "/api/blocks/tip/height": height,
                "/api/v1/prices": prices,
                "/api/v1/fees/recommended": fees
            ])
            do { _ = try await MempoolClient(transport: transport).snapshot(); XCTFail("Expected invalid data") }
            catch { XCTAssertTrue(error is MeetupCoreError) }
        }
    }
}

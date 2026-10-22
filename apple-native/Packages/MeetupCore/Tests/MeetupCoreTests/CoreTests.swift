import Foundation
import XCTest
@testable import MeetupCore

final class CoreTests: XCTestCase {
    func testExactBitcoinConversionAndInt64Boundaries() throws {
        let samples: [Int64] = [0, 1, -1, 21_000_000 * 100_000_000, 12_345_678, Int64.max, Int64.min]
        for value in samples {
            XCTAssertEqual(try BitcoinConverter.sats(fromBTC: BitcoinConverter.btc(fromSats: value)), value)
        }
        XCTAssertEqual(try BitcoinConverter.sats(fromBTC: "  +0.00000001  "), 1)
        XCTAssertEqual(try BitcoinConverter.sats(fromBTC: "0.29"), 29_000_000)
        XCTAssertEqual(BitcoinConverter.btc(fromSats: 123_450_000), "1.2345")
        XCTAssertEqual(BitcoinConverter.fiat(sats: 50_000_000, price: 100_000), 50_000)
        for invalid in ["", "1e-8", "NaN", "inf", ".1", "1.", "1,000", "1 000", "--1", "１", "0.000000001", "92233720368.54775808", "-92233720368.54775809"] {
            XCTAssertThrowsError(try BitcoinConverter.sats(fromBTC: invalid), invalid)
        }
    }

    func testSafeURLPolicy() {
        XCTAssertEqual(SafeURL.web(" HTTPS://example.org/a?b=c ")?.host, "example.org")
        XCTAssertNotNil(SafeURL.web("http://localhost:8080"))
        XCTAssertNotNil(SafeURL.web("https://[::1]:443/path"))
        XCTAssertNotNil(SafeURL.web("https://münchen.de/path"))
        XCTAssertNotNil(SafeURL.relay("wss://[::ffff:192.0.2.1]:443/path"))
        XCTAssertNotNil(SafeURL.relay(" wss://relay.example.org/path?x=1 "))
        for bad in ["javascript:alert(1)", "file:///tmp/a", "data:text/plain,a", "https:///", "t.me/test", "/a", "//example.org", "https://user:pass@example.org", "https://exa mple.org", "https://example.org:0", "https://example.org:65536", "https://example.org\\evil", "https://example.org/\npath"] {
            XCTAssertNil(SafeURL.web(bad), bad)
        }
        for bad in ["ws://relay.example.org", "https://relay.example.org", "wss://user@relay.example.org", "wss://relay.example.org/#fragment", "wss://"] {
            XCTAssertNil(SafeURL.relay(bad), bad)
        }
        for bad in ["https://[not-ipv6]", "https://example.org%2F.evil", "https://[:::1]", "https://[::ffff:999.0.0.1]", "https://example..org", "https://-example.org", "https://example.org%0a.evil"] {
            XCTAssertNil(SafeURL.web(bad), bad)
        }
    }

    func testDatesAreUTCAndPreserveExplicitOffsets() throws {
        let naive = try XCTUnwrap(DateParsing.portal("2026-10-21 15:00"))
        XCTAssertEqual(naive, DateParsing.portal("2026-10-21T15:00:00Z"))
        XCTAssertEqual(naive, DateParsing.portal("2026-10-21T17:00:00+02:00"))
        XCTAssertEqual(naive, DateParsing.portal("2026-10-21T10:00:00-0500"))
        XCTAssertEqual(DateParsing.portal("2026-03-29 02:30"), DateParsing.portal("2026-03-29T02:30:00Z"))
        XCTAssertEqual(try XCTUnwrap(DateParsing.portal("2026-10-21T15:00:00.125Z")).timeIntervalSince(naive), 0.125, accuracy: 0.00001)
        for bad in ["", "yesterday", "2026-02-30 12:00", "2026-13-01", "2026-10-21 25:00", "2026-10-21T15:00:00+25:00", "2026-10-21 garbage"] {
            XCTAssertNil(DateParsing.portal(bad), bad)
        }
        XCTAssertEqual(DateParsing.rss("Wed, 21 Oct 2026 10:51:16 GMT")?.timeIntervalSince1970, 1_792_579_876)
        XCTAssertEqual(DateParsing.rss("Mon, 01 Jan 2024 02:00:00 +0200"), DateParsing.portal("2024-01-01T00:00:00Z"))
        XCTAssertEqual(DateParsing.rss("1 Jan 24 02:00:00 +0200"), DateParsing.portal("2024-01-01T00:00:00Z"))
        XCTAssertEqual(DateParsing.rss("1 Jan 99 00:00:00 GMT"), DateParsing.portal("1999-01-01T00:00:00Z"))
        XCTAssertEqual(DateParsing.rss("1 Jan 2024 02:00:00 EST"), DateParsing.portal("2024-01-01T07:00:00Z"))
        XCTAssertNil(DateParsing.rss("1 Jan 2024 02:00:00 XYZ"))
        XCTAssertNil(DateParsing.rss("bad date"))
    }

    func testModelsRoundTripCodable() throws {
        let meetup = Meetup(id: "m1", name: "Example", city: "Wien", latitude: 48.2, nextEventStart: Date(timeIntervalSince1970: 123))
        XCTAssertEqual(try JSONDecoder().decode(Meetup.self, from: JSONEncoder().encode(meetup)), meetup)
        let event = MeetupEvent(id: "e1", start: Date(timeIntervalSince1970: 123), meetupID: "m1")
        XCTAssertEqual(try JSONDecoder().decode(MeetupEvent.self, from: JSONEncoder().encode(event)), event)
        let article = NewsArticle(id: "n1", url: try XCTUnwrap(URL(string: "https://example.org")))
        XCTAssertEqual(try JSONDecoder().decode(NewsArticle.self, from: JSONEncoder().encode(article)), article)
    }
}

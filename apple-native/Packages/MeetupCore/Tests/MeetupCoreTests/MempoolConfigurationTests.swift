import Foundation
import XCTest
@testable import MeetupCore

final class MempoolConfigurationTests: XCTestCase {
    func testMatchingCurrencyOnlyAndMissingForeignRateNeverUsesUSD() throws {
        let snapshot = BitcoinSnapshot(height: 900000, usd: 65000, eur: 60000, fastestFee: 4, halfHourFee: 3, hourFee: 2)
        for currency in ["GBP", "CHF", "CAD", "AUD", "JPY"] {
            XCTAssertNil(BitcoinDashboard.price(for: currency, dashboard: BitcoinDashboard(prices: ["USD": 65000]), snapshot: snapshot))
            XCTAssertEqual(BitcoinDashboard.price(for: currency, dashboard: BitcoinDashboard(prices: [currency: 123]), snapshot: snapshot), 123)
        }
        XCTAssertEqual(BitcoinDashboard.price(for: "EUR", dashboard: nil, snapshot: snapshot), 60000)
        XCTAssertEqual(BitcoinDashboard.price(for: "USD", dashboard: nil, snapshot: snapshot), 65000)
        XCTAssertNil(BitcoinDashboard.price(for: "GBP", dashboard: BitcoinDashboard(prices: ["GBP": .nan]), snapshot: snapshot))
    }
    func testProductionEndpointNormalizationAndOnionBudget() throws {
        XCTAssertEqual(try MempoolConfiguration.normalize("").absoluteString, MempoolConfiguration.clearnet)
        XCTAssertEqual(try MempoolConfiguration.normalize(" mempool.space/API/// ").absoluteString, MempoolConfiguration.clearnet)
        XCTAssertEqual(try MempoolConfiguration.normalize("EXAMPLE.ONION/api/").absoluteString, "http://example.onion")
        XCTAssertEqual(try MempoolConfiguration.normalize("http://127.0.0.1:8080/tunnel/api/").absoluteString, "http://127.0.0.1:8080/tunnel")
        XCTAssertEqual(try MempoolConfiguration.normalize("http://[::1]:8080/api").absoluteString, "http://[::1]:8080")
        XCTAssertEqual(MempoolConfiguration.timeout(for: try MempoolConfiguration.normalize(MempoolConfiguration.onion)), 45)
        XCTAssertEqual(MempoolConfiguration.timeout(for: try MempoolConfiguration.normalize(MempoolConfiguration.clearnet)), 20)
        for invalid in ["http://alice:secret@node.local", "http://localhost/#fragment", "http://localhost/?token=secret", "file:///tmp/node", "http://bad host", "http://localhost:65536", "http://localhost/\r\nX: bad"] {
            XCTAssertThrowsError(try MempoolConfiguration.normalize(invalid), invalid)
        }
    }
    func testFragmentedLengthChunkedAndCloseDelimitedResponses() throws {
        let fixed = Data("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello".utf8)
        for size in 0..<fixed.count { XCTAssertNil(try MempoolHTTPResponse.body(Data(fixed.prefix(size)), eof: false)) }
        XCTAssertEqual(try MempoolHTTPResponse.body(fixed, eof: false), Data("hello".utf8))
        let chunked = Data("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2;fixture=yes\r\nhe\r\n3\r\nllo\r\n0\r\n\r\n".utf8)
        for size in 0..<chunked.count { XCTAssertNil(try MempoolHTTPResponse.body(Data(chunked.prefix(size)), eof: false)) }
        XCTAssertEqual(try MempoolHTTPResponse.body(chunked, eof: false), Data("hello".utf8))
        let closed = Data("HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\n\r\nhello".utf8)
        XCTAssertNil(try MempoolHTTPResponse.body(closed, eof: false))
        XCTAssertEqual(try MempoolHTTPResponse.body(closed, eof: true), Data("hello".utf8))
    }
    func testTruncationAmbiguousFramingRedirectAndBoundsFailClosed() throws {
        let invalid = [
            "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhi",
            "HTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\nextra",
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nContent-Length: 0\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nx\r\n0\r\n\r\n",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\nx\r\n",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip, chunked\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n\r\n",
            "HTTP/1.1 302 Found\r\nLocation: http://foreign.invalid\r\nContent-Length: 0\r\n\r\n",
            "HTTP/1.1 200 OK\r\n Folded: bad\r\n\r\n"
        ]
        for value in invalid { XCTAssertThrowsError(try MempoolHTTPResponse.body(Data(value.utf8), eof: true), value) }
        for value in ["HTTP/1.1 200 OK\r\nContent-Length: 262145\r\n\r\n", "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n40001\r\n"] {
            XCTAssertThrowsError(try MempoolHTTPResponse.body(Data(value.utf8), eof: false))
        }
        XCTAssertThrowsError(try MempoolHTTPResponse.body(Data(repeating: 65, count: 32769), eof: false))
        var body = Data("HTTP/1.0 200 OK\r\n\r\n".utf8); body.append(Data(repeating: 65, count: 262145))
        XCTAssertThrowsError(try MempoolHTTPResponse.body(body, eof: false))
    }
}

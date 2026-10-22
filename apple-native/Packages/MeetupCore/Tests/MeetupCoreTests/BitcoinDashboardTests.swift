import Foundation
import XCTest
@testable import MeetupCore

private struct DashboardFixtureTransport: HTTPTransport {
    func get(_ url: URL, accept: String) async throws -> Data {
        if url.path.hasSuffix("prices") { return Data("{\"USD\":68000,\"EUR\":62000,\"CHF\":59000,\"time\":1800000000}".utf8) }
        if url.path.hasSuffix("mempool") { return Data("{\"count\":123,\"vsize\":456,\"total_fee\":789}".utf8) }
        throw MeetupCoreError.httpStatus(503)
    }
}
final class BitcoinDashboardTests: XCTestCase {
    func testPartialDashboardPreservesIndependentPricesAndMempool() async throws {
        let dashboard = try await MempoolClient(transport: DashboardFixtureTransport()).dashboard()
        XCTAssertEqual(dashboard.prices["CHF"], 59000)
        XCTAssertEqual(dashboard.values["count"], 123)
        XCTAssertEqual(dashboard.failedSources, ["difficulty", "lightning", "mining"])
    }
    func testUnitsAndReverseFiatEnforceSatoshiPrecision() throws {
        XCTAssertEqual(try BitcoinAmountUnit.mBTC.sats("1.23456"), 123456)
        XCTAssertEqual(try BitcoinAmountUnit.microBTC.sats("1.23"), 123)
        XCTAssertEqual(try BitcoinAmountUnit.fiat.sats("62.00", price: 62000), 100000)
        XCTAssertThrowsError(try BitcoinAmountUnit.microBTC.sats("0.001"))
        XCTAssertThrowsError(try BitcoinAmountUnit.sats.sats("1e9"))
        XCTAssertThrowsError(try BitcoinAmountUnit.fiat.sats("1", price: 0))
    }
}

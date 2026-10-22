import Foundation
import XCTest
@testable import MeetupCore

private actor PublicBitcoinJourneyTransport: HTTPTransport {
    private(set) var requests: [URL] = []
    let lightning: String
    init(lightning: String) { self.lightning = lightning }
    func get(_ url: URL, accept: String) async throws -> Data {
        requests.append(url)
        switch url.path {
        case "/custom/api/v1/prices": return Data(#"{"EUR":62000,"USD":68000,"CHF":59000}"#.utf8)
        case "/custom/api/mempool": return Data(#"{"count":123,"vsize":456,"total_fee":789}"#.utf8)
        case "/custom/api/v1/lightning/statistics/latest": return Data(lightning.utf8)
        default: throw MeetupCoreError.httpStatus(503)
        }
    }
}

final class PublicBitcoinJourneyParityTests: XCTestCase, @unchecked Sendable {
    func testSwitchingUnitsPreservesTheEnteredSatoshis() throws {
        let sats: Int64 = 123_456_789
        for unit in [BitcoinAmountUnit.sats, .btc, .mBTC, .microBTC, .fiat] {
            let text = try unit.input(sats: sats, price: 59_000)
            XCTAssertEqual(try unit.sats(text, price: 59_000), sats, "Round-trip \(unit)")
        }
        XCTAssertEqual(try BitcoinAmountUnit.btc.input(sats: 100_000), "0.001")
        XCTAssertEqual(try BitcoinAmountUnit.sats.input(sats: BitcoinAmountUnit.btc.sats("0,001")), "100000")
        XCTAssertThrowsError(try BitcoinAmountUnit.fiat.input(sats: sats))
    }

    func testPremiumAndDiscountMatchFlutterForBothInputDirections() throws {
        let premium = try BitcoinPremiumQuote(amount: "62,00", unit: .fiat, price: 62_000, percentage: "+2,5")
        XCTAssertEqual(premium.baseFiat, 62, accuracy: 0.000001)
        XCTAssertEqual(premium.adjustedFiat, 63.55, accuracy: 0.000001)
        XCTAssertEqual(premium.adjustedSats, 102_500)
        let discount = try BitcoinPremiumQuote(amount: "0.001", unit: .btc, price: 62_000, percentage: "-20")
        XCTAssertEqual(discount.adjustedFiat, 49.6, accuracy: 0.000001)
        XCTAssertEqual(discount.adjustedSats, 80_000)
        let noPremium = try BitcoinPremiumQuote(amount: "100000", unit: .sats, price: 59_000, percentage: "")
        XCTAssertEqual(noPremium.adjustedFiat, 59, accuracy: 0.000001)
        XCTAssertEqual(noPremium.adjustedSats, 100_000)
    }

    func testFiatPremiumKeepsOriginalAmountBeforeSatoshiRounding() throws {
        // The base fiat amount must not be rounded through a single satoshi.
        let quote = try BitcoinPremiumQuote(amount: "0.0006", unit: .fiat, price: 100_000, percentage: "20")
        XCTAssertEqual(quote.baseFiat, 0.0006, accuracy: 0.000000001)
        XCTAssertEqual(quote.adjustedFiat, 0.00072, accuracy: 0.000000001)
        XCTAssertEqual(quote.adjustedSats, 1)
        XCTAssertEqual(try BitcoinPremiumQuote(amount: "10", unit: .fiat, price: 62_000, percentage: "-100").adjustedSats, 0)
    }

    func testPremiumRejectsInvalidInputsAndOverflow() throws {
        for text in ["-100.5", "NaN", "inf", "1e3", "1.2.3", "+", "--2"] {
            XCTAssertThrowsError(try BitcoinPremiumQuote(amount: "1", unit: .btc, price: 62_000, percentage: text), text)
        }
        XCTAssertThrowsError(try BitcoinPremiumQuote(amount: "1", unit: .btc, price: 0, percentage: "2"))
        XCTAssertThrowsError(try BitcoinPremiumQuote(amount: String(Int64.max), unit: .sats, price: 62_000, percentage: "20"))
    }

    func testLightningUsesConfiguredHostAndRetainsOtherSources() async throws {
        let transport = PublicBitcoinJourneyTransport(lightning: #"{"latest":{"total_capacity":543210000000,"node_count":17321,"channel_count":49321}}"#)
        let dashboard = try await MempoolClient(transport: transport).dashboard(baseURL: URL(string: "http://node.onion:8080/custom")!)
        XCTAssertEqual(dashboard.values["lightningCapacityBTC"], 5432.1)
        XCTAssertEqual(dashboard.values["lightningNodes"], 17321)
        XCTAssertEqual(dashboard.values["lightningChannels"], 49321)
        XCTAssertEqual(dashboard.prices["CHF"], 59000)
        XCTAssertEqual(dashboard.failedSources, ["difficulty", "mining"])
        let requests = await transport.requests
        XCTAssertEqual(requests.count, 5)
        XCTAssertTrue(requests.allSatisfy { $0.scheme == "http" && $0.host == "node.onion" && $0.port == 8080 })
    }

    func testLightningRootShapeAndMalformedMetricsStayIndependent() async throws {
        let valid = try await MempoolClient(transport: PublicBitcoinJourneyTransport(lightning: #"{"total_capacity":100000000,"node_count":10,"channel_count":20}"#)).dashboard(baseURL: URL(string: "https://fixture.invalid/custom")!)
        XCTAssertEqual(valid.values["lightningCapacityBTC"], 1)
        let partial = try await MempoolClient(transport: PublicBitcoinJourneyTransport(lightning: #"{"latest":{"total_capacity":-1,"node_count":true,"channel_count":20}}"#)).dashboard(baseURL: URL(string: "https://fixture.invalid/custom")!)
        XCTAssertNil(partial.values["lightningCapacityBTC"])
        XCTAssertNil(partial.values["lightningNodes"])
        XCTAssertEqual(partial.values["lightningChannels"], 20)
        XCTAssertTrue(partial.failedSources.contains("lightning"))
        XCTAssertEqual(partial.values["count"], 123)
        XCTAssertEqual(partial.prices["CHF"], 59000)
    }

    func testSupplyHalvingAndMoscowTimeAreDerivedFromRealInputs() throws {
        XCTAssertEqual(BitcoinDashboard.circulatingSupply(atHeight: 210_000), 10_500_000)
        XCTAssertEqual(BitcoinDashboard.circulatingSupply(atHeight: 840_000), 19_687_500)
        XCTAssertEqual(BitcoinDashboard.circulatingSupply(atHeight: 840_001), 19_687_503)
        XCTAssertNil(BitcoinDashboard.circulatingSupply(atHeight: 0))
        XCTAssertEqual(BitcoinDashboard.moscowTime(usdPrice: 50_000), "20:00")
        XCTAssertEqual(BitcoinDashboard.moscowTime(usdPrice: 100_000), "10:00")
        XCTAssertNil(BitcoinDashboard.moscowTime(usdPrice: nil))
        XCTAssertNil(BitcoinDashboard.moscowTime(usdPrice: 0))
    }
}

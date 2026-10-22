import Foundation

public struct BitcoinDashboard: Codable, Sendable, Equatable {
    public var values: [String: Double]
    public var prices: [String: Double]
    public var failedSources: [String]
    public var fetchedAt: Date
    public init(values: [String: Double] = [:], prices: [String: Double] = [:], failedSources: [String] = [], fetchedAt: Date = Date()) {
        self.values = values; self.prices = prices; self.failedSources = failedSources; self.fetchedAt = fetchedAt
    }
}
public enum BitcoinAmountUnit: String, CaseIterable, Sendable {
    case sats, btc, mBTC, microBTC, fiat
    public func sats(_ input: String, price: Double? = nil) throws -> Int64 {
        let source = input.replacingOccurrences(of: ",", with: ".")
        guard let value = Decimal(string: source, locale: Locale(identifier: "en_US_POSIX")), value >= 0,
              !source.isEmpty, source.utf8.allSatisfy({ (48...57).contains($0) || $0 == 46 }), source.filter({ $0 == "." }).count <= 1 else {
            throw BitcoinConversionError.invalidNumber
        }
        let scaled: Decimal
        switch self {
        case .sats: scaled = value
        case .btc: return try BitcoinConverter.sats(fromBTC: source)
        case .mBTC: scaled = value * 100_000
        case .microBTC: scaled = value * 100
        case .fiat:
            guard let price, price.isFinite, price > 0 else { throw BitcoinConversionError.invalidNumber }
            scaled = value / Decimal(price) * 100_000_000
        }
        var copy = scaled; var rounded = Decimal()
        NSDecimalRound(&rounded, &copy, 0, self == .fiat ? .plain : .down)
        guard self == .fiat || rounded == scaled else { throw BitcoinConversionError.excessivePrecision }
        guard rounded <= Decimal(Int64.max), rounded >= 0 else { throw BitcoinConversionError.overflow }
        return NSDecimalNumber(decimal: rounded).int64Value
    }
}

import Foundation

public struct BitcoinDashboard: Codable, Sendable, Equatable {
    public var values: [String: Double]
    public var prices: [String: Double]
    public var failedSources: [String]
    public var fetchedAt: Date
    public init(values: [String: Double] = [:], prices: [String: Double] = [:], failedSources: [String] = [], fetchedAt: Date = Date()) {
        self.values = values; self.prices = prices; self.failedSources = failedSources; self.fetchedAt = fetchedAt
    }
    /// Same whole-coin issuance estimate displayed by the Flutter dashboard.
    public static func circulatingSupply(atHeight height: Int) -> Int64? {
        guard height > 0 else { return nil }
        var blocks = height
        var reward = 50.0
        var supply = 0.0
        while blocks > 0 && reward >= 0.00000001 {
            let count = min(blocks, 210_000)
            supply += Double(count) * reward
            blocks -= count
            reward /= 2
        }
        return Int64(supply.rounded())
    }

    public static func moscowTime(usdPrice: Double?) -> String? {
        guard let usdPrice, usdPrice.isFinite, usdPrice > 0,
              let sats = Int64(exactly: (100_000_000 / usdPrice).rounded()) else { return nil }
        let digits = String(sats)
        let padded = String(repeating: "0", count: max(0, 4 - digits.count)) + digits
        return String(padded.dropLast(2)) + ":" + padded.suffix(2)
    }

    public static func price(for currency: String, dashboard: BitcoinDashboard?, snapshot: BitcoinSnapshot?) -> Double? {
        if let value = dashboard?.prices[currency], value.isFinite, value > 0 { return value }
        switch currency {
        case "EUR": return snapshot?.eur
        case "USD": return snapshot?.usd
        default: return nil
        }
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


public extension BitcoinAmountUnit {
    /// Preserve the entered value when changing units, without locale grouping or
    /// a two-decimal fiat round-trip that could lose satoshis.
    func input(sats: Int64, price: Double? = nil) throws -> String {
        guard sats >= 0 else { throw BitcoinConversionError.invalidNumber }
        let value: Decimal
        switch self {
        case .sats: return String(sats)
        case .btc: return BitcoinConverter.btc(fromSats: sats)
        case .mBTC: value = Decimal(sats) / 100_000
        case .microBTC: value = Decimal(sats) / 100
        case .fiat:
            guard let price, price.isFinite, price > 0 else { throw BitcoinConversionError.invalidNumber }
            value = Decimal(sats) / 100_000_000 * Decimal(price)
        }
        return NSDecimalNumber(decimal: value).stringValue
    }
}

public struct BitcoinPremiumQuote: Sendable, Equatable {
    public let baseFiat: Double
    public let adjustedFiat: Double
    public let adjustedSats: Int64

    public static func percentage(_ input: String) throws -> Double {
        let source = input.replacingOccurrences(of: ",", with: ".")
        if source.isEmpty { return 0 }
        var digits = source
        if digits.first == "+" || digits.first == "-" { digits.removeFirst() }
        guard !digits.isEmpty, digits.contains(where: { $0.isNumber }),
              digits.utf8.allSatisfy({ (48...57).contains($0) || $0 == 46 }),
              digits.filter({ $0 == "." }).count <= 1,
              let value = Double(source), value.isFinite, value >= -100 else { throw BitcoinConversionError.invalidNumber }
        return value
    }

    public init(amount: String, unit: BitcoinAmountUnit, price: Double, percentage: String) throws {
        let sats = try unit.sats(amount, price: price)
        guard price.isFinite, price > 0 else { throw BitcoinConversionError.invalidNumber }
        // Fiat input stays exact: the base amount is not first rounded to sats.
        let base = unit == .fiat
            ? Decimal(string: amount.replacingOccurrences(of: ",", with: "."), locale: Locale(identifier: "en_US_POSIX"))!
            : Decimal(sats) / 100_000_000 * Decimal(price)
        let percent = try Self.percentage(percentage)
        let adjusted = base * (1 + Decimal(percent) / 100)
        var satValue = adjusted / Decimal(price) * 100_000_000
        var rounded = Decimal()
        NSDecimalRound(&rounded, &satValue, 0, .plain)
        let baseNumber = NSDecimalNumber(decimal: base).doubleValue
        let adjustedNumber = NSDecimalNumber(decimal: adjusted).doubleValue
        guard baseNumber.isFinite, adjustedNumber.isFinite,
              !rounded.isNaN, rounded >= 0, rounded <= Decimal(Int64.max) else { throw BitcoinConversionError.overflow }
        baseFiat = baseNumber
        adjustedFiat = adjustedNumber
        adjustedSats = NSDecimalNumber(decimal: rounded).int64Value
    }
}

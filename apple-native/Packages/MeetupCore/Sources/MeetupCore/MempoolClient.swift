import Foundation

public actor MempoolClient {
    public static let defaultBaseURL = URL(string: "https://mempool.space")!
    private let transport: any HTTPTransport

    public init() { transport = MempoolHTTPTransport() }
    init(transport: any HTTPTransport) { self.transport = transport }

    /// All three sources must succeed. No invented zero values, cached prices or hidden host fallback.
    public func snapshot(baseURL: URL = MempoolClient.defaultBaseURL) async throws -> BitcoinSnapshot {
        let root = try MempoolConfiguration.normalize(baseURL.absoluteString)
        async let heightData = transport.get(root.appendingPathComponent("api/blocks/tip/height"), accept: "text/plain")
        async let pricesData = transport.get(root.appendingPathComponent("api/v1/prices"), accept: "application/json")
        async let feesData = transport.get(root.appendingPathComponent("api/v1/fees/recommended"), accept: "application/json")
        let (heightBody, pricesBody, feesBody) = try await (heightData, pricesData, feesData)
        guard let text = String(data: heightBody, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !text.isEmpty, text.utf8.allSatisfy({ (48...57).contains($0) }), let height = Int(text), height > 0 else {
            throw MeetupCoreError.invalidData("Expected a positive block height.")
        }
        let prices = try object(pricesBody)
        let fees = try object(feesBody)
        guard let usd = JSONFields.number(prices["USD"]), usd > 0,
              let eur = JSONFields.number(prices["EUR"]), eur > 0 else {
            throw MeetupCoreError.invalidData("Expected positive USD and EUR prices.")
        }
        func fee(_ key: String) throws -> Int {
            guard let value = JSONFields.number(fees[key]), value >= 0,
                  value.rounded(.towardZero) == value, let result = Int(exactly: value) else {
                throw MeetupCoreError.invalidData("Expected a nonnegative integer \(key).")
            }
            return result
        }
        return try BitcoinSnapshot(height: height, usd: usd, eur: eur,
                                   fastestFee: fee("fastestFee"), halfHourFee: fee("halfHourFee"), hourFee: fee("hourFee"))
    }

    /// Each source has an independent result, so one unavailable endpoint never
    /// discards fresh prices or metrics returned by the others.
    public func dashboard(baseURL: URL = MempoolClient.defaultBaseURL) async throws -> BitcoinDashboard {
        let root = try MempoolConfiguration.normalize(baseURL.absoluteString)
        let sources = ["prices": "api/v1/prices", "mempool": "api/mempool", "mining": "api/v1/mining/hashrate/3d", "difficulty": "api/v1/difficulty-adjustment",
                       "lightning": "api/v1/lightning/statistics/latest"]
        let transport = transport
        return await withTaskGroup(of: (String, Data?).self) { group in
            for (name, path) in sources {
                group.addTask { (name, try? await transport.get(root.appendingPathComponent(path), accept: "application/json")) }
            }
            var dashboard = BitcoinDashboard()
            for await (source, body) in group {
                guard let body, let object = try? JSONSerialization.jsonObject(with: body) as? [String: Any] else {
                    dashboard.failedSources.append(source); continue
                }
                if source == "prices" {
                    for (currency, value) in object where currency.count == 3 {
                        if let number = JSONFields.number(value), number > 0 { dashboard.prices[currency] = number }
                    }
                    if dashboard.prices.isEmpty { dashboard.failedSources.append(source) }
                } else if source == "lightning" {
                    let latest = object["latest"] as? [String: Any] ?? object
                    let fields = ["total_capacity": "lightningCapacityBTC", "node_count": "lightningNodes", "channel_count": "lightningChannels"]
                    var found = 0
                    for (field, key) in fields {
                        guard let value = JSONFields.number(latest[field]), value.isFinite, value >= 0,
                              value.rounded(.towardZero) == value else { continue }
                        dashboard.values[key] = field == "total_capacity" ? value / 100_000_000 : value
                        found += 1
                    }
                    if found < fields.count { dashboard.failedSources.append(source) }
                } else {
                    let keys: [String]
                    switch source {
                    case "mempool": keys = ["count", "vsize", "total_fee"]
                    case "mining": keys = ["currentHashrate", "currentDifficulty"]
                    default: keys = ["progressPercent", "difficultyChange", "remainingBlocks", "estimatedRetargetDate", "timeAvg"]
                    }
                    var found = false
                    for key in keys { if let n = JSONFields.number(object[key]), n.isFinite { dashboard.values[key] = n; found = true } }
                    if !found { dashboard.failedSources.append(source) }
                }
            }
            dashboard.failedSources.sort()
            return dashboard
        }
    }

    private func object(_ data: Data) throws -> [String: Any] {
        guard let result = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw MeetupCoreError.invalidData("Expected a JSON object.")
        }
        return result
    }
}

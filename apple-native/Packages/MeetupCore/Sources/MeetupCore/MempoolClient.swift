import Foundation

public actor MempoolClient {
    public static let defaultBaseURL = URL(string: "https://mempool.space")!
    private let transport: any HTTPTransport

    public init() { transport = URLSessionTransport(maximumBytes: 256 * 1024) }
    init(transport: any HTTPTransport) { self.transport = transport }

    /// All three sources must succeed. No invented zero values, cached prices or hidden host fallback.
    public func snapshot(baseURL: URL = MempoolClient.defaultBaseURL) async throws -> BitcoinSnapshot {
        guard SafeURL.web(baseURL.absoluteString) != nil,
              baseURL.query == nil, baseURL.fragment == nil else { throw MeetupCoreError.invalidURL }
        let root = baseURL.lastPathComponent == "api" ? baseURL.deletingLastPathComponent() : baseURL
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

    private func object(_ data: Data) throws -> [String: Any] {
        guard let result = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw MeetupCoreError.invalidData("Expected a JSON object.")
        }
        return result
    }
}

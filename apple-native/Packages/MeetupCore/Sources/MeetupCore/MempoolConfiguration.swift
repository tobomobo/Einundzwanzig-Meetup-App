import Foundation

/// Explicit public Bitcoin endpoint selection. Tor routing/DNS is supplied by
/// the user's system VPN; the app never silently falls back from an onion host.
public enum MempoolConfiguration {
    public enum Mode: String, CaseIterable, Sendable { case clearnet, tor, custom }
    public static let clearnet = "https://mempool.space"
    public static let onion = "http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion"
    public static func normalize(_ input: String) throws -> URL {
        var text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { text = clearnet }
        if !text.contains("://") { text = (text.lowercased().contains(".onion") ? "http://" : "https://") + text }
        guard var components = URLComponents(string: text), let url = SafeURL.web(text), url.query == nil, url.fragment == nil else { throw MeetupCoreError.invalidURL }
        while components.percentEncodedPath.hasSuffix("/") { components.percentEncodedPath.removeLast() }
        if components.percentEncodedPath.lowercased().hasSuffix("/api") { components.percentEncodedPath.removeLast(4) }
        while components.percentEncodedPath.hasSuffix("/") { components.percentEncodedPath.removeLast() }
        components.scheme = components.scheme?.lowercased(); components.host = components.host?.lowercased()
        guard let normalized = components.url else { throw MeetupCoreError.invalidURL }
        return normalized
    }
    public static func mode(for url: URL) -> Mode {
        if url.absoluteString == clearnet { return .clearnet }
        if url.absoluteString == onion { return .tor }
        return .custom
    }
    public static func timeout(for url: URL) -> TimeInterval {
        url.host?.lowercased().hasSuffix(".onion") == true ? 45 : 20
    }
}

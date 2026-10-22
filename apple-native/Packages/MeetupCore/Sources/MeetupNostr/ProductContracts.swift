import Foundation

/// One signing identity. Callers verify returned events and fence asynchronous
/// results against the public key that started the operation.
public protocol NostrSigner: Sendable {
    func publicKey() async throws -> String
    func sign(kind: Int, tags: [[String]], content: String, createdAt: Int) async throws -> NostrEvent
    func encrypt(_ plaintext: String, to publicKey: String) async throws -> String
    func decrypt(_ ciphertext: String, from publicKey: String) async throws -> String
}

public struct NostrFilter: Codable, Sendable, Equatable {
    public var ids: [String]?
    public var authors: [String]?
    public var kinds: [Int]?
    public var since: Int?
    public var until: Int?
    public var limit: Int?
    public var tags: [String: [String]]
    public init(ids: [String]? = nil, authors: [String]? = nil, kinds: [Int]? = nil,
                since: Int? = nil, until: Int? = nil, limit: Int? = nil, tags: [String: [String]] = [:]) {
        self.ids = ids; self.authors = authors; self.kinds = kinds
        self.since = since; self.until = until; self.limit = limit; self.tags = tags
    }
    public func matches(_ event: NostrEvent) -> Bool {
        NostrReadPolicy.matches(event, ids: ids.map(Set.init), kinds: kinds.map(Set.init), authors: authors.map(Set.init),
                                since: since, until: until, tagFilters: tags.map { key, values in
            NostrTagFilter(name: key.hasPrefix("#") ? String(key.dropFirst()) : key, values: values)
        })
    }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: Key.self)
        try c.encodeIfPresent(ids, forKey: Key("ids")); try c.encodeIfPresent(authors, forKey: Key("authors"))
        try c.encodeIfPresent(kinds, forKey: Key("kinds")); try c.encodeIfPresent(since, forKey: Key("since"))
        try c.encodeIfPresent(until, forKey: Key("until")); try c.encodeIfPresent(limit, forKey: Key("limit"))
        for (key, values) in tags { try c.encode(values, forKey: Key(key.hasPrefix("#") ? key : "#" + key)) }
    }
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: Key.self)
        ids = try c.decodeIfPresent([String].self, forKey: Key("ids")); authors = try c.decodeIfPresent([String].self, forKey: Key("authors"))
        kinds = try c.decodeIfPresent([Int].self, forKey: Key("kinds")); since = try c.decodeIfPresent(Int.self, forKey: Key("since"))
        until = try c.decodeIfPresent(Int.self, forKey: Key("until")); limit = try c.decodeIfPresent(Int.self, forKey: Key("limit"))
        tags = [:]
        for key in c.allKeys where key.stringValue.hasPrefix("#") { tags[key.stringValue] = try c.decode([String].self, forKey: key) }
    }
    private struct Key: CodingKey {
        var stringValue: String; var intValue: Int? { nil }
        init(_ value: String) { stringValue = value }
        init?(stringValue: String) { self.stringValue = stringValue }
        init?(intValue: Int) { return nil }
    }
}

public protocol NostrRelayTransport: Sendable {
    func query(_ filter: NostrFilter, relays: [URL], timeout: TimeInterval) async throws -> [NostrEvent]
    func queryAuthenticated(_ filter: NostrFilter, relays: [URL], timeout: TimeInterval, signer: any NostrSigner) async throws -> [NostrEvent]
    func publish(_ event: NostrEvent, relays: [URL], signer: (any NostrSigner)?) async throws
}

public extension NostrRelayTransport {
    /// Custom transports that do not require NIP-42 can retain their normal
    /// query implementation. The product WebSocket transport honors this key.
    func queryAuthenticated(_ filter: NostrFilter, relays: [URL], timeout: TimeInterval, signer: any NostrSigner) async throws -> [NostrEvent] {
        try await query(filter, relays: relays, timeout: timeout)
    }
}

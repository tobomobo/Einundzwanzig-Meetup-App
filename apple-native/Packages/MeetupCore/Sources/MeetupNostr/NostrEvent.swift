import Foundation
import P256K

/// Immutable NIP-01 fields. Construction checks wire shape and resource bounds;
/// it makes no assertion about authorship, trust, relay relevance, or event time.
public struct UnsignedNostrEvent: Codable, Hashable, Sendable {
    public let pubkey: String
    public let createdAt: Int
    public let kind: Int
    public let tags: [[String]]
    public let content: String

    public init(pubkey: String, createdAt: Int, kind: Int, tags: [[String]], content: String) throws {
        _ = try NostrHex.decode(pubkey, byteCount: 32, field: "pubkey")
        guard (0...NostrEventLimits.maximumCreatedAt).contains(createdAt) else {
            throw NostrCryptoError.invalidCreatedAt
        }
        guard (0...NostrEventLimits.maximumKind).contains(kind) else { throw NostrCryptoError.invalidKind }
        guard content.utf8.count <= NostrEventLimits.maximumContentBytes else {
            throw NostrCryptoError.limitExceeded(field: "content")
        }
        guard tags.count <= NostrEventLimits.maximumTagCount else {
            throw NostrCryptoError.limitExceeded(field: "tags")
        }
        var tagBytes = 0
        for tag in tags {
            guard !tag.isEmpty else { throw NostrCryptoError.emptyTag }
            guard tag.count <= NostrEventLimits.maximumTagElementCount else {
                throw NostrCryptoError.limitExceeded(field: "tag elements")
            }
            for element in tag {
                guard element.utf8.count <= NostrEventLimits.maximumTagElementBytes else {
                    throw NostrCryptoError.limitExceeded(field: "tag element")
                }
                tagBytes += element.utf8.count
                guard tagBytes <= NostrEventLimits.maximumCanonicalBytes else {
                    throw NostrCryptoError.limitExceeded(field: "tags bytes")
                }
            }
        }
        self.pubkey = pubkey
        self.createdAt = createdAt
        self.kind = kind
        self.tags = tags
        self.content = content
        // Includes JSON escaping expansion and separators in the total bound.
        _ = try canonicalSerialization()
    }

    public func canonicalSerialization() throws -> Data { try NostrCanonicalJSON.serialize(self) }

    /// Use for untrusted JSON: bounds input and validates integer tokens before
    /// Foundation can round them. Generic Decoder APIs cannot expose raw tokens.
    public static func decodeJSON(_ data: Data) throws -> Self { try NostrWireJSON.decode(Self.self, from: data) }

    /// One ordinary SHA-256 over the exact UTF-8 NIP-01 array.
    public func eventID() throws -> Data {
        Data(SHA256.hash(data: try canonicalSerialization()))
    }

    // Swift String equality normalizes canonically equivalent Unicode. NIP-01
    // hashes exact UTF-8 bytes, so model equality/hashing must preserve them too.
    public static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.pubkey == rhs.pubkey && lhs.createdAt == rhs.createdAt && lhs.kind == rhs.kind &&
        lhs.content.utf8.elementsEqual(rhs.content.utf8) && lhs.tags.count == rhs.tags.count &&
        zip(lhs.tags, rhs.tags).allSatisfy { left, right in
            left.count == right.count && zip(left, right).allSatisfy { $0.utf8.elementsEqual($1.utf8) }
        }
    }

    public func hash(into hasher: inout Hasher) {
        hasher.combine(pubkey)
        hasher.combine(createdAt)
        hasher.combine(kind)
        hasher.combine(Data(content.utf8))
        hasher.combine(tags.count)
        for tag in tags {
            hasher.combine(tag.count)
            for element in tag { hasher.combine(Data(element.utf8)) }
        }
    }

    enum CodingKeys: String, CodingKey {
        case pubkey, kind, tags, content
        case createdAt = "created_at"
    }

    public init(from decoder: any Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        try self.init(
            pubkey: values.decode(String.self, forKey: .pubkey),
            createdAt: values.decode(Int.self, forKey: .createdAt),
            kind: values.decode(Int.self, forKey: .kind),
            tags: values.decode([[String]].self, forKey: .tags),
            content: values.decode(String.self, forKey: .content)
        )
    }
}

/// A structurally valid signed event. Use `NostrCrypto.verify` to check its ID
/// and BIP-340 signature; Codable decoding alone does not verify a signature.
public struct NostrEvent: Codable, Hashable, Sendable {
    public let id: String
    public let sig: String
    public let unsigned: UnsignedNostrEvent
    public var pubkey: String { unsigned.pubkey }
    public var createdAt: Int { unsigned.createdAt }
    public var kind: Int { unsigned.kind }
    public var tags: [[String]] { unsigned.tags }
    public var content: String { unsigned.content }

    public init(id: String, pubkey: String, createdAt: Int, kind: Int, tags: [[String]], content: String, sig: String) throws {
        try self.init(unsigned: UnsignedNostrEvent(pubkey: pubkey, createdAt: createdAt, kind: kind, tags: tags, content: content), id: id, sig: sig)
    }

    public init(unsigned: UnsignedNostrEvent, id: String, sig: String) throws {
        _ = try NostrHex.decode(id, byteCount: 32, field: "id")
        _ = try NostrHex.decode(sig, byteCount: 64, field: "sig")
        self.unsigned = unsigned
        self.id = id
        self.sig = sig
    }

    /// Bounds input and validates original integer tokens before JSON decoding.
    /// Use this entry point when consuming untrusted event data.
    public static func decodeJSON(_ data: Data) throws -> NostrEvent {
        try NostrWireJSON.decode(Self.self, from: data)
    }

    enum CodingKeys: String, CodingKey {
        case id, pubkey, kind, tags, content, sig
        case createdAt = "created_at"
    }

    public init(from decoder: any Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        try self.init(
            id: values.decode(String.self, forKey: .id),
            pubkey: values.decode(String.self, forKey: .pubkey),
            createdAt: values.decode(Int.self, forKey: .createdAt),
            kind: values.decode(Int.self, forKey: .kind),
            tags: values.decode([[String]].self, forKey: .tags),
            content: values.decode(String.self, forKey: .content),
            sig: values.decode(String.self, forKey: .sig)
        )
    }

    public func encode(to encoder: any Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(id, forKey: .id)
        try values.encode(pubkey, forKey: .pubkey)
        try values.encode(createdAt, forKey: .createdAt)
        try values.encode(kind, forKey: .kind)
        try values.encode(tags, forKey: .tags)
        try values.encode(content, forKey: .content)
        try values.encode(sig, forKey: .sig)
    }
}

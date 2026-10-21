import Foundation
import Testing
@testable import MeetupNostr

func fixtureData(_ name: String) throws -> Data {
    let url = try #require(Bundle.module.url(forResource: name, withExtension: nil, subdirectory: "Fixtures"))
    return try Data(contentsOf: url)
}

func bytes(_ hex: String) throws -> Data {
    try NostrHex.decode(hex.lowercased(), byteCount: hex.utf8.count / 2, field: "fixture")
}

struct BIP340Vector {
    let index: Int
    let secretKey: Data
    let publicKey: Data
    let auxiliary: Data
    let message: Data
    let signature: Data
    let valid: Bool

    static func load() throws -> [Self] {
        let csv = String(decoding: try fixtureData("bip340-test-vectors.csv"), as: UTF8.self)
        return try csv.split(whereSeparator: \.isNewline).dropFirst().map { row in
            let values = row.split(separator: ",", omittingEmptySubsequences: false)
            return try Self(
                index: #require(Int(values[0])), secretKey: bytes(String(values[1])),
                publicKey: bytes(String(values[2])), auxiliary: bytes(String(values[3])),
                message: bytes(String(values[4])), signature: bytes(String(values[5])), valid: values[6] == "TRUE"
            )
        }
    }
}

struct DartVectors: Decodable {
    let validSignedEvents: [NostrEvent]
    let eventIDEscaping: EscapingVector
    enum CodingKeys: String, CodingKey {
        case validSignedEvents = "valid_signed_events"
        case eventIDEscaping = "event_id_escaping"
    }
    struct EscapingVector: Decodable {
        let pubkey: String
        let createdAt: Int
        let kind: Int
        let tags: [[String]]
        let content: String
        let expectedSerialization: String
        let expectedID: String
        enum CodingKeys: String, CodingKey {
            case pubkey, kind, tags, content
            case createdAt = "created_at"
            case expectedSerialization = "expected_serialization_utf8"
            case expectedID = "expected_id"
        }
        var event: UnsignedNostrEvent {
            get throws { try UnsignedNostrEvent(pubkey: pubkey, createdAt: createdAt, kind: kind, tags: tags, content: content) }
        }
    }
    static func load() throws -> Self { try JSONDecoder().decode(Self.self, from: fixtureData("dart-nostr-vectors.json")) }
}

let fixturePublicKey = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
let fixturePrivateKey = Data(repeating: 0, count: 31) + Data([1])

func unsignedFixture(
    pubkey: String = fixturePublicKey, createdAt: Int = 1_700_000_000,
    kind: Int = 31_923, tags: [[String]] = [["d", "test-meetup"], ["title", "Echtes Meetup"]],
    content: String = ""
) throws -> UnsignedNostrEvent {
    try UnsignedNostrEvent(pubkey: pubkey, createdAt: createdAt, kind: kind, tags: tags, content: content)
}

import Foundation
import Testing
@testable import MeetupNostr

@Suite struct NostrEventTests {
    @Test func officialNIP01HashFromNIP59UnsignedExample() throws {
        // Primary published ID: https://github.com/nostr-protocol/nips/blob/master/59.md#1-create-an-event
        let event = try UnsignedNostrEvent(
            pubkey: "611df01bfcf85c26ae65453b772d8f1dfd25c264621c0277e1fc1518686faef9",
            createdAt: 1_691_518_405, kind: 1, tags: [], content: "Are you going to the party tonight?"
        )
        let canonical = #"[0,"611df01bfcf85c26ae65453b772d8f1dfd25c264621c0277e1fc1518686faef9",1691518405,1,[],"Are you going to the party tonight?"]"#
        #expect(try event.canonicalSerialization() == Data(canonical.utf8))
        #expect(NostrHex.encode(try event.eventID()) == "9dd003c6d3b73b74a85a9ab099469ce251653a7af76f523671ab828acd2a0ef9")
    }

    @Test func existingDartCompatibleUTF8EscapingFixture() throws {
        // Copied unchanged from android-native/spec/fixtures/07-nostr-infra-vectors.json.
        let vector = try DartVectors.load().eventIDEscaping
        let event = try vector.event
        #expect(try event.canonicalSerialization() == Data(vector.expectedSerialization.utf8))
        #expect(NostrHex.encode(try event.eventID()) == vector.expectedID)
        #expect(!vector.expectedSerialization.contains("\\/"))
    }

    @Test func everyControlEscapeUnicodeAndOpaqueContentOrder() throws {
        let controls = String(String.UnicodeScalarView((0...31).map { Unicode.Scalar($0)! }))
        let event = try unsignedFixture(tags: [["x", "https://example.org/ä", "\"\\\n"]], content: controls + "\u{7f} ä€😀\u{2028}\u{2029} </script>&")
        let serialized = String(decoding: try event.canonicalSerialization(), as: UTF8.self)
        let escaped = #"\u0000\u0001\u0002\u0003\u0004\u0005\u0006\u0007\b\t\n\u000b\f\r\u000e\u000f\u0010\u0011\u0012\u0013\u0014\u0015\u0016\u0017\u0018\u0019\u001a\u001b\u001c\u001d\u001e\u001f"#
        #expect(serialized.contains(escaped + "\u{7f} ä€😀\u{2028}\u{2029} </script>&"))
        #expect(serialized.contains("https://example.org/ä"))
        let first = try unsignedFixture(content: #"{"a":1,"b":2}"#)
        let second = try unsignedFixture(content: #"{"b":2,"a":1}"#)
        #expect(try first.eventID() != second.eventID())
        let composed = try unsignedFixture(content: "é")
        let decomposed = try unsignedFixture(content: "e\u{301}")
        #expect(try composed.eventID() != decomposed.eventID())
        #expect(composed != decomposed)
        #expect(Set([composed, decomposed]).count == 2)
        let composedTag = try unsignedFixture(tags: [["d", "é"]])
        let decomposedTag = try unsignedFixture(tags: [["d", "e\u{301}"]])
        #expect(try composedTag.eventID() != decomposedTag.eventID())
        #expect(Set([composedTag, decomposedTag]).count == 2)
    }

    @Test func existingSignedFixturesVerifyWithoutResigning() async throws {
        let crypto = NostrCrypto()
        let events = try DartVectors.load().validSignedEvents
        #expect(events.count == 2)
        for event in events {
            #expect(NostrHex.encode(try event.unsigned.eventID()) == event.id)
            #expect(await crypto.verify(event))
        }
    }

    @Test func signRoundtripAndRejectEveryTamperedField() async throws {
        // Mirrors Dart test/relay_event_signature_test.dart's calendar-event cases.
        let crypto = NostrCrypto()
        let event = try await crypto.sign(unsignedFixture(content: "Meetup / Wien"), privateKey: fixturePrivateKey)
        #expect(await crypto.verify(event))
        let data = try JSONEncoder().encode(event)
        #expect(try NostrEvent.decodeJSON(data) == event)
        #expect(try JSONDecoder().decode(UnsignedNostrEvent.self, from: JSONEncoder().encode(event.unsigned)) == event.unsigned)
        let fields = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
        #expect(Set(fields.keys) == ["id", "pubkey", "created_at", "kind", "tags", "content", "sig"])
        let changes: [(String, Any)] = [
            ("id", String(repeating: "ab", count: 32)),
            ("pubkey", "c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"),
            ("created_at", event.createdAt + 1), ("kind", 0),
            ("tags", [["d", "test-meetup"], ["title", "Gefälschtes Meetup"]]),
            ("content", "Meetup / Graz"), ("sig", String(repeating: "00", count: 64))
        ]
        for (key, value) in changes {
            var altered = fields
            altered[key] = value
            let candidate = try NostrEvent.decodeJSON(JSONSerialization.data(withJSONObject: altered))
            #expect(await crypto.verify(candidate) == false, "Tampered \(key)")
        }
        await #expect(throws: NostrCryptoError.publicKeyMismatch) {
            try await crypto.sign(unsignedFixture(pubkey: String(repeating: "ab", count: 32)), privateKey: fixturePrivateKey)
        }
        // Object key order and JSON slash escaping on the transport do not enter the event ID.
        let alternateTransport = try JSONSerialization.data(withJSONObject: fields, options: [.prettyPrinted, .sortedKeys])
        #expect(await crypto.verify(try NostrEvent.decodeJSON(alternateTransport)))
    }

    @Test func decodingRejectsMalformedTypesAndWireHex() throws {
        let valid = try NostrEvent(unsigned: unsignedFixture(), id: String(repeating: "ab", count: 32), sig: String(repeating: "ab", count: 64))
        let data = try JSONEncoder().encode(valid)
        let fields = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let malformed: [(String, Any)] = [
            ("id", ""), ("id", String(repeating: "AB", count: 32)),
            ("id", String(repeating: "zz", count: 32)),
            ("pubkey", String(repeating: "ab", count: 31)),
            ("pubkey", String(repeating: "Ａ", count: 64)),
            ("pubkey", String(repeating: "AB", count: 32)),
            ("sig", String(repeating: "ab", count: 63)), ("sig", String(repeating: "ab", count: 65)),
            ("sig", String(repeating: "z0", count: 64)),
            ("created_at", -1), ("created_at", 1.5), ("created_at", true), ("created_at", "1700000000"),
            ("created_at", Int.max), ("kind", -1), ("kind", 65_536), ("kind", 1.5), ("kind", true),
            ("tags", [["d", 1]]), ("tags", [NSNull()]), ("tags", ["d"]), ("tags", [[]]),
            ("content", 42), ("content", NSNull())
        ]
        for (key, value) in malformed {
            var invalid = fields
            invalid[key] = value
            let encoded = try JSONSerialization.data(withJSONObject: invalid)
            #expect(throws: (any Error).self, "Malformed \(key): \(value)") { try NostrEvent.decodeJSON(encoded) }
        }
        for key in fields.keys {
            var missing = fields
            missing.removeValue(forKey: key)
            #expect(throws: (any Error).self) { try NostrEvent.decodeJSON(JSONSerialization.data(withJSONObject: missing)) }
        }
        for literal in ["NaN", "Infinity", "-Infinity", "1e999"] {
            let invalid = String(decoding: data, as: UTF8.self).replacingOccurrences(of: "1700000000", with: literal)
            #expect(throws: (any Error).self) { try NostrEvent.decodeJSON(Data(invalid.utf8)) }
        }
    }

    @Test func resourceBoundsAndIntegerBoundaries() throws {
        #expect(throws: NostrCryptoError.invalidCreatedAt) { try unsignedFixture(createdAt: -1) }
        #expect(throws: NostrCryptoError.invalidCreatedAt) { try unsignedFixture(createdAt: NostrEventLimits.maximumCreatedAt + 1) }
        #expect(throws: NostrCryptoError.invalidKind) { try unsignedFixture(kind: -1) }
        #expect(throws: NostrCryptoError.invalidKind) { try unsignedFixture(kind: 65_536) }
        _ = try unsignedFixture(createdAt: NostrEventLimits.maximumCreatedAt, kind: 65_535)
        _ = try unsignedFixture(createdAt: 0, kind: 0)
        #expect(throws: NostrCryptoError.emptyTag) { try unsignedFixture(tags: [[]]) }
        _ = try unsignedFixture(content: String(repeating: "a", count: NostrEventLimits.maximumContentBytes))
        #expect(throws: NostrCryptoError.limitExceeded(field: "content")) {
            try unsignedFixture(content: String(repeating: "é", count: NostrEventLimits.maximumContentBytes / 2 + 1))
        }
        #expect(throws: NostrCryptoError.limitExceeded(field: "tags")) {
            try unsignedFixture(tags: Array(repeating: ["t"], count: NostrEventLimits.maximumTagCount + 1))
        }
        #expect(throws: NostrCryptoError.limitExceeded(field: "tag elements")) {
            try unsignedFixture(tags: [Array(repeating: "t", count: NostrEventLimits.maximumTagElementCount + 1)])
        }
        #expect(throws: NostrCryptoError.limitExceeded(field: "tag element")) {
            try unsignedFixture(tags: [[String(repeating: "t", count: NostrEventLimits.maximumTagElementBytes + 1)]])
        }
        #expect(throws: NostrCryptoError.limitExceeded(field: "tags bytes")) {
            try unsignedFixture(tags: [Array(repeating: String(repeating: "t", count: 8_192), count: 17)])
        }
        #expect(throws: NostrCryptoError.limitExceeded(field: "canonical serialization")) {
            try unsignedFixture(content: String(repeating: "\u{0000}", count: NostrEventLimits.maximumContentBytes))
        }
        #expect(throws: NostrCryptoError.limitExceeded(field: "event JSON")) {
            try NostrEvent.decodeJSON(Data(repeating: 32, count: NostrEventLimits.maximumJSONBytes + 1))
        }
    }

    @Test func rawNumericTokensCannotRoundIntoValidIntegers() throws {
        // Literal tokens are essential: JSONSerialization can round before encoding.
        func json(_ createdAt: String, _ kind: String) -> Data {
            Data(#"{"id":"\#(String(repeating: "ab", count: 32))","pubkey":"\#(fixturePublicKey)","created_at":\#(createdAt),"kind":\#(kind),"tags":[["d","value"]],"content":"","sig":"\#(String(repeating: "ab", count: 64))"}"#.utf8)
        }
        for (createdAt, kind) in [
            ("9007199254740991.1", "1"), ("1e-999", "1"),
            ("1700000000.000000000000000000000000000000000001", "1"),
            ("1", "65535.000000000001"), ("1", "1e-999"),
            ("1", "1.000000000000000000000000000000000000000000001"),
            ("1.0", "1"), ("1e0", "1"), ("1", "1.0"), ("1", "1e0"),
            ("-0", "1"), ("01", "1"), ("1", "+1"), ("1", "00"),
            ("null", "1"), ("true", "1"), ("{}", "1"), ("[]", "1")
        ] {
            let data = json(createdAt, kind)
            #expect(throws: (any Error).self) { try NostrEvent.decodeJSON(data) }
            #expect(throws: (any Error).self) { try UnsignedNostrEvent.decodeJSON(data) }
        }
        let valid = json("9007199254740991", "65535")
        #expect(try NostrEvent.decodeJSON(valid).createdAt == NostrEventLimits.maximumCreatedAt)
        #expect(try UnsignedNostrEvent.decodeJSON(valid).kind == 65_535)
        let escapedKey = String(decoding: json("1e-999", "1"), as: UTF8.self)
            .replacingOccurrences(of: "created_at", with: #"created_\u0061t"#)
        #expect(throws: (any Error).self) { try NostrEvent.decodeJSON(Data(escapedKey.utf8)) }
        let duplicate = String(decoding: json("1", "1"), as: UTF8.self)
            .replacingOccurrences(of: #""created_at":1"#, with: #""created_at":1,"created_at":1e-999"#)
        #expect(throws: (any Error).self) { try NostrEvent.decodeJSON(Data(duplicate.utf8)) }
        let nested = String(decoding: json("1", "1"), as: UTF8.self)
            .replacingOccurrences(of: #""content":"""#, with: #""unknown":{"created_at":1e-999,"nested":["} , \"",{}]},"content":"kind:1e-999""#)
        #expect(try NostrEvent.decodeJSON(Data(nested.utf8)).content == "kind:1e-999")
        for invalid in ["{} trailing", "{", "[]", "null", #"{"created_at":1,"kind":1"#, #"{"created_at":,"kind":1}"#] {
            #expect(throws: (any Error).self) { try NostrEvent.decodeJSON(Data(invalid.utf8)) }
        }
    }

    @Test func ignoredFieldsStillRequireValidJSONGrammar() throws {
        let event = try NostrEvent(unsigned: unsignedFixture(), id: String(repeating: "ab", count: 32), sig: String(repeating: "ab", count: 64))
        let encoded = String(decoding: try JSONEncoder().encode(event), as: UTF8.self)
        for value in [#""\q""#, "01", "\"\u{01}\"", #"{"nested":["\q"]}"#, #"{"nested":01}"#] {
            let invalid = Data(("{\"unknown\":" + value + "," + encoded.dropFirst()).utf8)
            #expect(throws: (any Error).self) { try NostrEvent.decodeJSON(invalid) }
            #expect(throws: (any Error).self) { try UnsignedNostrEvent.decodeJSON(invalid) }
        }
    }

    @Test func genericCodableSupportsJSONAndPropertyListRoundtrips() throws {
        let unsigned = try unsignedFixture(createdAt: NostrEventLimits.maximumCreatedAt, kind: 65_535, tags: [["d", "cafe\u{301}"]], content: "ä / 😀")
        let event = try NostrEvent(unsigned: unsigned, id: String(repeating: "ab", count: 32), sig: String(repeating: "ab", count: 64))
        #expect(try JSONDecoder().decode(UnsignedNostrEvent.self, from: JSONEncoder().encode(unsigned)) == unsigned)
        #expect(try JSONDecoder().decode(NostrEvent.self, from: JSONEncoder().encode(event)) == event)
        for format in [PropertyListSerialization.PropertyListFormat.binary, .xml] {
            let encoder = PropertyListEncoder()
            encoder.outputFormat = format
            #expect(try PropertyListDecoder().decode(UnsignedNostrEvent.self, from: encoder.encode(unsigned)) == unsigned)
            #expect(try PropertyListDecoder().decode(NostrEvent.self, from: encoder.encode(event)) == event)
        }
    }
}

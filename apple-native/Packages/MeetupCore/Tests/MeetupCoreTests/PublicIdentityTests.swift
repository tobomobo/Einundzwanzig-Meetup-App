import Foundation
import XCTest
@testable import MeetupCore

final class PublicIdentityTests: XCTestCase {
    // Official NIP-19 examples, not an encoder/decoder self-consistency oracle.
    // https://github.com/nostr-protocol/nips/blob/master/19.md
    private let officialHex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
    private let officialNpub = "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"
    private let zeroNpub = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqzqujme"

    func testOfficialNIP19PublicKeyVectors() throws {
        let vectors = [
            (officialHex, officialNpub),
            ("7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e",
             "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg")
        ]
        for (hex, npub) in vectors {
            XCTAssertEqual(try PublicIdentity(hex).npub, npub)
            XCTAssertEqual(try PublicIdentity(npub).hex, hex)
            XCTAssertEqual(try PublicIdentity(npub), try PublicIdentity(hex))
        }
    }

    func testZeroAllFFAndLeadingZeroBytesHaveNoCurveValidation() throws {
        // These are byte-format fixtures, not assertions of curve membership.
        // Expected encodings were calculated independently with BIP-173's algorithm.
        let vectors = [
            (String(repeating: "00", count: 32), zeroNpub),
            (String(repeating: "ff", count: 32),
             "npub1lllllllllllllllllllllllllllllllllllllllllllllllllllsq7lrjw"),
            ("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
             "npub1qqqsyqcyq5rqwzqfpg9scrgwpugpzysnzs23v9ccrydpk8qarc0st5hsmq")
        ]
        for (hex, npub) in vectors {
            XCTAssertEqual(try PublicIdentity(hex).npub, npub)
            XCTAssertEqual(try PublicIdentity(npub).hex, hex)
        }
    }

    func testHexCaseAndEquivalentInputsNormalizeAndHashEqually() throws {
        let mixedHex = "3bF0c63FCb93463407Af97a5E5ee64FA883d107ef9E558472c4eB9aAAefA459D"
        let inputs = [officialHex, officialHex.uppercased(), mixedHex, officialNpub,
                      officialNpub.uppercased(), "nostr:" + officialNpub,
                      "NOSTR:" + officialNpub.uppercased()]
        let identities = try inputs.map { try PublicIdentity($0) }
        XCTAssertEqual(Set(identities).count, 1)
        for identity in identities {
            XCTAssertEqual(identity.hex, officialHex)
            XCTAssertEqual(identity.npub, officialNpub)
        }
        XCTAssertEqual(PublicIdentity.normalize(" \n" + officialHex + "\t"), identities.first)
        XCTAssertNil(PublicIdentity.normalize("invalid"))
    }

    func testNostrSchemeAndSurroundingWhitespacePolicy() throws {
        for input in [" \n" + officialNpub + "\t", "nostr:" + officialNpub,
                      "nostr:" + officialNpub.uppercased(), "NoStR:" + officialNpub,
                      " \tnostr:" + officialNpub + "\r\n"] {
            XCTAssertEqual(try PublicIdentity(input).npub, officialNpub)
        }
        for input in ["nostr:" + officialHex, "nostr: " + officialNpub,
                      "nostr://" + officialNpub, "nostr:nostr:" + officialNpub,
                      "https:" + officialNpub, officialNpub + "?relay=wss://example.org",
                      officialNpub + "#fragment", "nostr:"] {
            XCTAssertThrowsError(try PublicIdentity(input), input)
        }
    }

    func test0xPrefixesAreExplicitlyRejected() {
        for input in ["0x" + officialHex, "0X" + officialHex.uppercased(),
                      "nostr:0x" + officialHex] {
            XCTAssertThrowsError(try PublicIdentity(input))
        }
    }

    func testHexRequiresExactly64ASCIIHexDigits() {
        let invalid = ["", String(officialHex.dropLast()), officialHex + "0",
                       "02" + officialHex, "g" + officialHex.dropFirst(),
                       "-" + officialHex.dropFirst(), " " + officialHex.dropFirst(),
                       String(repeating: "０", count: 64), String(repeating: "é", count: 32),
                       String(repeating: "0", count: 31) + "\n" + String(repeating: "0", count: 32),
                       String(repeating: "0", count: 63) + "\u{0000}"]
        for input in invalid { XCTAssertThrowsError(try PublicIdentity(input)) }
    }

    func testBIP173AllUppercaseAcceptedAndMixedCaseRejected() throws {
        // BIP-173 requires lowercase output, accepts uppercase input, and rejects mixed case.
        // https://github.com/bitcoin/bips/blob/master/bip-0173.mediawiki#bech32
        XCTAssertEqual(try PublicIdentity(officialNpub.uppercased()).npub, officialNpub)
        let mixed = ["N" + officialNpub.dropFirst(),
                     "NPUB1" + officialNpub.dropFirst(5),
                     String(officialNpub.prefix(5)) + "Q" + officialNpub.dropFirst(6),
                     String(officialNpub.dropLast()) + "W"]
        for input in mixed {
            XCTAssertThrowsError(try PublicIdentity(input)) { error in
                XCTAssertEqual(error as? PublicIdentity.ValidationError, .mixedCase)
            }
        }
    }

    func testForbiddenAlphabetCharactersSeparatorsAndUnicodeAreRejected() {
        for character in ["b", "i", "o", "1", "!", " ", "\t", "\n", "\u{0000}", "\u{007f}", "ｑ", "🟠", "\u{0301}"] {
            let input = String(officialNpub.prefix(12)) + character + officialNpub.dropFirst(13)
            XCTAssertThrowsError(try PublicIdentity(input))
        }
        for input in ["npub" + officialNpub.dropFirst(5),
                      "npub11" + officialNpub.dropFirst(5),
                      "npub:" + officialNpub.dropFirst(5),
                      String(repeating: "q", count: 63)] {
            XCTAssertThrowsError(try PublicIdentity(input))
        }
    }

    func testEverySingleDataAndChecksumCharacterSubstitutionIsRejected() {
        let alphabet = Array("qpzry9x8gf2tvdw0s3jn54khce6mua7l".utf8)
        let original = Array(officialNpub.utf8)
        for index in 5..<original.count {
            for replacement in alphabet where replacement != original[index] {
                var changed = original
                changed[index] = replacement
                XCTAssertThrowsError(try PublicIdentity(String(decoding: changed, as: UTF8.self))) { error in
                    XCTAssertEqual(error as? PublicIdentity.ValidationError, .invalidChecksum)
                }
            }
        }
        // HRP is included in the checksum, and this API additionally requires npub.
        XCTAssertThrowsError(try PublicIdentity("mpub" + officialNpub.dropFirst(4)))
    }

    func testChecksumValidNonzeroPaddingIsRejected() {
        // The last payload word is p (00001), instead of q (00000).
        // Checksum was recomputed independently, so this exercises padding validation.
        let badPadding = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqplkg8xt"
        XCTAssertThrowsError(try PublicIdentity(badPadding)) { error in
            XCTAssertEqual(error as? PublicIdentity.ValidationError, .invalidPadding)
        }
    }

    func testChecksumValidWrongLengthsAndExcessPaddingAreRejected() {
        // BIP-173 checksums are valid; decoded payloads contain 31 and 33 zero bytes.
        // The 33-byte encoding also represents 32 zero bytes plus an extra zero symbol.
        let invalid = [
            "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqkxnxjx",
            "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqnenctv",
            String(officialNpub.dropLast()), officialNpub + "q", "npub1qqqqqq",
            "npub1" + String(repeating: "q", count: 5000)
        ]
        for input in invalid { XCTAssertThrowsError(try PublicIdentity(input)) }
    }

    func testBech32mIsRejected() {
        // Same 32 zero bytes as zeroNpub, with the BIP-350 constant 0x2bc830a3.
        let bech32m = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqhuv77m"
        XCTAssertThrowsError(try PublicIdentity(bech32m)) { error in
            XCTAssertEqual(error as? PublicIdentity.ValidationError, .invalidChecksum)
        }
    }

    func testOtherNIP19TypesIncludingPrivateKeysAreRejected() {
        // nsec and nprofile are public test examples from NIP-19; neither is imported.
        let invalid = [
            "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5",
            "note1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqn2l0z3",
            "nprofile1qqsrhuxx8l9ex335q7he0f09aej04zpazpl0ne2cgukyawd24mayt8gpp4mhxue69uhhytnc9e3k7mgpz4mhxue69uhkg6nzv9ejuumpv34kytnrdaksjlyr9p"
        ]
        for input in invalid {
            XCTAssertThrowsError(try PublicIdentity(input))
            XCTAssertThrowsError(try PublicIdentity(input.uppercased()))
            XCTAssertThrowsError(try PublicIdentity("nostr:" + input))
        }
    }

    func testDeterministicByteRoundTripsPreserveLeadingZeros() throws {
        for seed in 0..<256 {
            let bytes = (0..<32).map { UInt8((seed + $0 * 73) & 255) }
            let hex = bytes.map { String(format: "%02x", $0) }.joined()
            let identity = try PublicIdentity(hex)
            XCTAssertEqual(identity.hex.utf8.count, 64)
            XCTAssertEqual(identity.npub.utf8.count, 63)
            XCTAssertEqual(try PublicIdentity(identity.npub).hex, hex)
        }
    }

    func testCodableRoundTripAndCaseNormalization() throws {
        let identity = try PublicIdentity(officialNpub)
        let encoded = try JSONEncoder().encode(identity)
        XCTAssertEqual(try JSONDecoder().decode(PublicIdentity.self, from: encoded), identity)
        let fields = try JSONDecoder().decode([String: String].self, from: encoded)
        XCTAssertEqual(fields, ["hex": officialHex, "npub": officialNpub])
        let upper = try JSONEncoder().encode(["hex": officialHex.uppercased(), "npub": officialNpub.uppercased()])
        XCTAssertEqual(try JSONDecoder().decode(PublicIdentity.self, from: upper), identity)
    }

    func testCodableRejectsMalformedOrContradictoryRepresentations() throws {
        let invalidFields = [
            ["hex": "bad", "npub": officialNpub],
            ["hex": officialHex, "npub": "bad"],
            ["hex": officialHex, "npub": zeroNpub],
            ["hex": officialNpub, "npub": officialNpub],
            ["hex": officialHex, "npub": officialHex],
            ["hex": officialHex, "npub": "nostr:" + officialNpub],
            ["hex": officialHex, "npub": "N" + officialNpub.dropFirst()],
            ["hex": officialHex], ["npub": officialNpub], [:]
        ]
        for fields in invalidFields {
            let encoded = try JSONEncoder().encode(fields)
            XCTAssertThrowsError(try JSONDecoder().decode(PublicIdentity.self, from: encoded))
        }
    }

    func testPublicEncodingRetainsTheSameReferenceWithoutAnOwnershipClaim() {
        XCTAssertEqual(PublicIdentity.normalize(officialNpub)?.npub, officialNpub)
        XCTAssertEqual(PublicIdentity.normalize(officialNpub)?.hex, officialHex)
    }
}

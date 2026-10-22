import Foundation
import Testing
@testable import MeetupNostr

@Suite struct NostrReadPolicyTests {
    @Test func cryptographicValidityDoesNotImplyFilterMembership() async throws {
        let crypto = NostrCrypto()
        let event = try await crypto.sign(unsignedFixture(tags: [["d", "test-meetup", "ignored"], ["A", "value"]]), privateKey: fixturePrivateKey)
        #expect(await crypto.verify(event))
        #expect(NostrReadPolicy.matches(event, kinds: [31_923], authors: [fixturePublicKey], since: event.createdAt, until: event.createdAt, tagFilters: [.init(name: "d", values: ["test-meetup"]), .init(name: "A", values: ["value"])]))
        #expect(NostrReadPolicy.matches(event, ids: [event.id]))
        #expect(!NostrReadPolicy.matches(event, ids: [String(repeating: "0", count: 64)]))
        #expect(!NostrReadPolicy.matches(event, kinds: [0]))
        #expect(!NostrReadPolicy.matches(event, authors: [String(repeating: "ab", count: 32)]))
        #expect(!NostrReadPolicy.matches(event, since: event.createdAt + 1))
        #expect(!NostrReadPolicy.matches(event, until: event.createdAt - 1))
        #expect(!NostrReadPolicy.matches(event, tagFilters: [.init(name: "d", values: ["ignored"])]))
        #expect(!NostrReadPolicy.matches(event, tagFilters: [.init(name: "a", values: ["value"])]))
        #expect(!NostrReadPolicy.matches(event, tagFilters: [.init(name: "d", values: [])]))
        #expect(!NostrReadPolicy.matches(event, kinds: []))
        let invalidSignature = try NostrEvent(unsigned: event.unsigned, id: event.id, sig: String(repeating: "00", count: 64))
        #expect(NostrReadPolicy.matches(invalidSignature, kinds: [31_923]))
        #expect(await crypto.verify(invalidSignature) == false)
    }

    @Test func tagNamesAndValuesPreserveExactUTF8WithoutCollectionCollapsing() async throws {
        let crypto = NostrCrypto()
        let composed = "café"
        let decomposed = "cafe\u{301}"
        let event = try await crypto.sign(unsignedFixture(tags: [["d", composed], [composed, "value"]]), privateKey: fixturePrivateKey)
        #expect(await crypto.verify(event))
        #expect(!NostrReadPolicy.matches(event, tagFilters: [.init(name: "d", values: [decomposed])]))
        #expect(NostrReadPolicy.matches(event, tagFilters: [.init(name: "d", values: [decomposed, composed])]))
        #expect(!NostrReadPolicy.matches(event, tagFilters: [.init(name: decomposed, values: ["value"])]))
        #expect(NostrReadPolicy.matches(event, tagFilters: [.init(name: composed, values: ["value"])]))
        let names: [NostrTagFilter] = [.init(name: composed, values: ["value"]), .init(name: decomposed, values: ["value"])]
        #expect(names.count == 2)
        #expect(Set(names).count == 2)
        #expect(!NostrReadPolicy.matches(event, tagFilters: names))
        let both = try await crypto.sign(unsignedFixture(tags: [[composed, "value"], [decomposed, "value"]]), privateKey: fixturePrivateKey)
        #expect(NostrReadPolicy.matches(both, tagFilters: names))
        let values = [NostrTagFilter(name: "d", values: [composed]), NostrTagFilter(name: "d", values: [decomposed])]
        #expect(Set(values).count == 2)
        #expect(!NostrReadPolicy.matches(event, tagFilters: [.init(name: "", values: ["value"])]))
        let kelvin = try await crypto.sign(unsignedFixture(tags: [["K", "value"]]), privateKey: fixturePrivateKey)
        #expect(await crypto.verify(kelvin))
        #expect(!NostrReadPolicy.matches(kelvin, tagFilters: [.init(name: "K", values: ["value"])]))
        #expect(NostrReadPolicy.matches(kelvin, tagFilters: [.init(name: "K", values: ["value"])]))
    }

    @Test func futureTimestampIsReadPolicyAndNeverCryptographicTrust() async throws {
        let crypto = NostrCrypto()
        let now = 1_700_000_000
        let event = try await crypto.sign(unsignedFixture(createdAt: now + 601, kind: 0), privateKey: fixturePrivateKey)
        #expect(await crypto.verify(event))
        #expect(NostrReadPolicy.replaceableCreatedAt(event, now: now) == nil)
        #expect(NostrReadPolicy.replaceableCreatedAt(event, now: now + 1) == event.createdAt)
        #expect(NostrReadPolicy.replaceableCreatedAt(event, now: -1) == nil)
        #expect(NostrReadPolicy.replaceableCreatedAt(event, now: now, maximumFutureSkew: -1) == nil)
        #expect(NostrReadPolicy.replaceableCreatedAt(event, now: Int.max) == nil)
    }
}

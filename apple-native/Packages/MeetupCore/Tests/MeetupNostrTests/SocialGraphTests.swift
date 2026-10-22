import Foundation
import Testing
@testable import MeetupNostr

private actor ObservedRelayFixture: NostrRelayTransport {
    let events: [NostrEvent]
    let fail: Bool
    var requests: [NostrFilter] = []
    init(events: [NostrEvent], fail: Bool = false) { self.events = events; self.fail = fail }
    func query(_ filter: NostrFilter, relays: [URL], timeout: TimeInterval) async throws -> [NostrEvent] {
        requests.append(filter)
        if fail { throw BadgeProtocolError.incompleteSync }
        // Deliberately ignore filters: the product consumer must enforce them.
        return events
    }
    func publish(_ event: NostrEvent, relays: [URL], signer: (any NostrSigner)?) async throws { Issue.record("Read-only graph attempted publication") }
    func filters() -> [NostrFilter] { requests }
}
private func socialSigner(_ scalar: UInt8) throws -> LocalIdentitySigner { try LocalIdentitySigner(privateKey: Data(repeating: 0, count: 31) + Data([scalar])) }
private func socialAttendance(_ signer: any NostrSigner, key: String, timestamp: Int = 100, bodyKey: String? = nil) async throws -> NostrEvent {
    let content = BadgeWire.canonical(["event": .string(bodyKey ?? key), "meetup": .string("Fixture"), "t": .int(timestamp)])
    return try await signer.sign(kind: 30079, tags: [["d", key], ["e_ref", String(repeating: "a", count: 64)]], content: content, createdAt: timestamp)
}
private func bridgeFixture() async throws -> (events: [NostrEvent], keys: [String]) {
    let signers = try (1...6).map { try socialSigner(UInt8($0)) }
    var keys: [String] = []
    for signer in signers { keys.append(try await signer.publicKey()) }
    let a = "wien-2026-01-01@issuer", b = "graz-2026-02-01@issuer", c = "linz-2026-03-01@issuer", event = "ev|mega-2026-04-01"
    var events: [NostrEvent] = []
    for (person, session) in [(0,a),(1,a),(1,b),(2,b),(2,c),(3,c),(0,event),(4,event),(5,"unrelated-2026-05-01@other")] {
        events.append(try await socialAttendance(signers[person], key: session))
    }
    return (events, keys)
}

struct SocialGraphTests {
    @Test func signedLocalBridgeProducesThreeDegreesAndNoLargeEventPersonalEdge() async throws {
        let fixture = try await bridgeFixture()
        var snapshot = await SocialNetwork.ingest(fixture.events, now: 1000); snapshot.owner = fixture.keys[0]
        let network = snapshot.network()
        #expect(network.byDegree[1]?.map(\.publicKey) == [fixture.keys[1]])
        #expect(network.byDegree[2]?.map(\.publicKey) == [fixture.keys[2]])
        #expect(network.byDegree[3]?.map(\.publicKey) == [fixture.keys[3]])
        #expect(network.pathTo(fixture.keys[3]) == Array(fixture.keys.prefix(4)))
        #expect(network.byDegree[3]?.first?.bridges == [fixture.keys[1]])
        #expect(network.pathTo(fixture.keys[4]).isEmpty)
        #expect(network.verifyPerson(fixture.keys[4]).targetInObservedNetwork)
        #expect(snapshot.sharedMeetups(fixture.keys[0], fixture.keys[4], events: true) == ["ev|mega-2026-04-01"])
        #expect(network.verifyPerson(fixture.keys[0]).degree == 0)
    }
    @Test func loaderExpandsOnlyMatchingAuthorsAndSessionKeysAndUsesIncrementalSince() async throws {
        let fixture = try await bridgeFixture(), relay = ObservedRelayFixture(events: fixture.events)
        let loader = SocialGraphLoader(relay: relay)
        let first = try await loader.load(owner: fixture.keys[0], relays: [], now: 1000)
        #expect(first.network().pathTo(fixture.keys[3]) == Array(fixture.keys.prefix(4)))
        #expect(!first.attendance.contains(where: { $0.event.pubkey == fixture.keys[5] }))
        #expect(!first.stale)
        #expect(first.knownAuthors.contains(fixture.keys[1]))
        let second = try await loader.load(owner: fixture.keys[0], previous: first, relays: [], now: 2000)
        #expect(second.network().pathTo(fixture.keys[3]) == Array(fixture.keys.prefix(4)))
        let filters = await relay.filters()
        #expect(filters.count <= 20)
        #expect(filters.contains { $0.since == 0 && $0.kinds == [30079] })
        #expect(filters.filter { $0.kinds == [30079] }.allSatisfy { $0.authors != nil || !$0.tags.isEmpty })
    }
    @Test func olderReplacementsInvalidSignaturesAndOutOfFilterAreIgnored() async throws {
        let signer = try socialSigner(1), key = try await signer.publicKey(), session = "wien-2026-01-01@issuer"
        let old = try await socialAttendance(signer, key: session, timestamp: 100)
        let newer = try await socialAttendance(signer, key: session, timestamp: 200)
        let bad = try NostrEvent(id: newer.id, pubkey: newer.pubkey, createdAt: newer.createdAt, kind: newer.kind, tags: newer.tags, content: newer.content, sig: String(repeating: "0", count: 128))
        let filter = NostrFilter(authors: [key], kinds: [30079], tags: ["d": [session]])
        let unrelated = try await socialAttendance(signer, key: "other-2026-01-01@issuer", timestamp: 300)
        let snapshot = await SocialNetwork.ingest([newer, old, bad, unrelated], now: 1000, filter: filter)
        #expect(snapshot.attendance.map(\.event.id) == [newer.id])
        let tombstone = try await socialAttendance(signer, key: session, timestamp: 400, bodyKey: "mismatched-2026-01-01")
        let replaced = await SocialNetwork.ingest(snapshot.sourceEvents + [tombstone], now: 1000)
        #expect(replaced.attendance.isEmpty)
        let followed = try await signer.sign(kind: 3, tags: [["d", "ignored-kind3-d"], ["p", fixturePublicKey]], content: "", createdAt: 100)
        let latest = try await signer.sign(kind: 3, tags: [], content: "", createdAt: 200)
        let contacts = await SocialNetwork.ingest([followed, latest], now: 1000)
        #expect(contacts.follows[key]?.isEmpty == true)
    }
    @Test func unavailableRelayPreservesReverifiedCacheAndMarksStale() async throws {
        let fixture = try await bridgeFixture()
        var cache = await SocialNetwork.ingest(fixture.events, now: 1000); cache.owner = fixture.keys[0]; cache.updatedAt = 1000
        let relay = ObservedRelayFixture(events: [], fail: true)
        let restored = try await SocialGraphLoader(relay: relay).load(owner: fixture.keys[0], previous: cache, relays: [], now: 2000)
        #expect(restored.stale); #expect(restored.updatedAt == 1000)
        #expect(restored.network().pathTo(fixture.keys[3]) == Array(fixture.keys.prefix(4)))
        let foreign = try await SocialGraphLoader(relay: relay).load(owner: fixture.keys[5], previous: cache, relays: [], now: 2000)
        #expect(foreign.attendance.isEmpty)
    }
    @Test func organizerEndorsementRequiresOrganizerFollowingTarget() {
        var snapshot = SocialGraphSnapshot()
        snapshot.follows = ["me": [], "target": ["organizer"], "organizer": []]
        #expect(snapshot.score("me", "target", organizers: ["organizer"]) == 0)
        snapshot.follows["organizer"] = ["target"]
        #expect(snapshot.score("me", "target", organizers: ["organizer"]) == 0.5)
    }
}

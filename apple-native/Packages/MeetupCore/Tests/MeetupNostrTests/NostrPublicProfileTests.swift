import Foundation
import Testing
@testable import MeetupNostr

private actor ProfileScriptedRelay: NostrRelayTransport {
    var responses: [[NostrEvent]]
    var requests: [(NostrFilter, [URL])] = []
    init(_ responses: [[NostrEvent]]) { self.responses = responses }
    func query(_ filter: NostrFilter, relays: [URL], timeout: TimeInterval) async throws -> [NostrEvent] {
        requests.append((filter, relays))
        return responses.isEmpty ? [] : responses.removeFirst()
    }
    func publish(_ event: NostrEvent, relays: [URL], signer: (any NostrSigner)?) async throws { throw CancellationError() }
    func calls() -> [(NostrFilter, [URL])] { requests }
}

@Suite struct NostrPublicProfileTests {
    let now = 1_800_000_000
    private func signer(_ byte: UInt8 = 1) throws -> LocalIdentitySigner {
        try LocalIdentitySigner(privateKey: Data(repeating: 0, count: 31) + Data([byte]))
    }
    @Test func discoversOnlyPublicWriteRelaysAndValidatesLatestMetadata() async throws {
        let signer = try signer(), key = try await signer.publicKey()
        let list = try await signer.sign(kind: 10002, tags: [["r", "wss://author.example", "write"],
            ["r", "wss://reader.example", "read"], ["r", "wss://127.0.0.1", "write"], ["r", "wss://server.local", "write"]], content: "", createdAt: now - 2)
        let profile = try await signer.sign(kind: 0, tags: [], content: "{\"picture\":\"https://images.example/ava.png\",\"display_name\":\"Alice\",\"nip05\":\"alice@example.com\"}", createdAt: now - 1)
        let relay = ProfileScriptedRelay([[list], [profile]])
        let result = await NostrPublicProfileLookup(relay: relay).load(publicKey: key, relays: [], now: now)
        #expect(result?.pictureURL?.absoluteString == "https://images.example/ava.png")
        #expect(result?.displayName == "Alice")
        #expect(result?.nip05 == "alice@example.com")
        let calls = await relay.calls()
        #expect(calls.count == 2)
        #expect(calls.first?.1 == [NostrPublicProfileLookup.indexRelay])
        #expect(calls.last?.1.map(\.absoluteString) == ["wss://author.example"])
        #expect(calls.allSatisfy { $0.0.authors == [key] && $0.0.kinds == [0, 10002] })
    }
    @Test func newerEmptyPictureRemovesOldAvatarAndTieUsesLowerEventID() async throws {
        let signer = try signer(), key = try await signer.publicKey()
        let old = try await signer.sign(kind: 0, tags: [], content: "{\"picture\":\"https://images.example/old.png\"}", createdAt: now - 10)
        let removed = try await signer.sign(kind: 0, tags: [], content: "{}", createdAt: now - 1)
        let relay = ProfileScriptedRelay([[old, removed]])
        let result = await NostrPublicProfileLookup(relay: relay).load(publicKey: key, relays: [], cached: .init(profile: old), now: now)
        #expect(result?.profile?.id == removed.id)
        #expect(result?.pictureURL == nil)
        let a = try await signer.sign(kind: 0, tags: [], content: "{\"name\":\"A\"}", createdAt: now)
        let b = try await signer.sign(kind: 0, tags: [], content: "{\"name\":\"B\"}", createdAt: now)
        let tied = await NostrPublicProfileLookup(relay: ProfileScriptedRelay([[a, b]])).load(publicKey: key, relays: [], now: now)
        #expect(tied?.profile?.id == min(a.id, b.id))
    }
    @Test func rejectsForeignFutureMalformedAndInvalidlySignedMetadata() async throws {
        let signer = try signer(), key = try await signer.publicKey(), other = try self.signer(2)
        let good = try await signer.sign(kind: 0, tags: [], content: "{}", createdAt: now)
        let invalid = try NostrEvent(unsigned: good.unsigned, id: good.id, sig: String(repeating: "0", count: 128))
        let foreign = try await other.sign(kind: 0, tags: [], content: "{}", createdAt: now)
        let future = try await signer.sign(kind: 0, tags: [], content: "{}", createdAt: now + 1)
        let malformed = try await signer.sign(kind: 0, tags: [], content: "[]", createdAt: now)
        let result = await NostrPublicProfileLookup(relay: ProfileScriptedRelay([[invalid, foreign, future, malformed]])).load(publicKey: key, relays: [], cached: .init(profile: invalid, fetchedAt: now), now: now)
        #expect(result == nil)
    }
    @Test func freshCacheIsReverifiedAndOutageKeepsExpiredRemoval() async throws {
        let signer = try signer(), key = try await signer.publicKey()
        let empty = try await signer.sign(kind: 0, tags: [], content: "{}", createdAt: now - 100)
        let relay = ProfileScriptedRelay([])
        let lookup = NostrPublicProfileLookup(relay: relay)
        let fresh = await lookup.load(publicKey: key, relays: [], cached: .init(profile: empty, fetchedAt: now - 1), now: now)
        #expect(fresh?.profile == empty)
        #expect(await relay.calls().isEmpty)
        let expired = await lookup.load(publicKey: key, relays: [], cached: .init(profile: empty, fetchedAt: now - 50_000), now: now)
        #expect(expired?.profile == empty)
        #expect(expired?.pictureURL == nil)
        #expect(expired?.fetchedAt == now - 50_000)
    }
    @Test func relayDiscoveryHasBoundsAndRejectsPrivateHints() async throws {
        for host in ["localhost", "127.1", "10.0.0.1", "172.16.1.1", "192.168.1.1", "100.64.1.1", "[::1]", "relay.local", "relay.localhost"] {
            #expect(NostrPublicProfileLookup.relayURL("wss://" + host, discovered: true) == nil)
        }
        let signer = try signer(), key = try await signer.publicKey()
        let list = try await signer.sign(kind: 10002, tags: (0..<20).map { ["r", "wss://relay\($0).example", "write"] }, content: "", createdAt: now)
        let relay = ProfileScriptedRelay([[list], []])
        _ = await NostrPublicProfileLookup(relay: relay).load(publicKey: key, relays: (0..<20).map { URL(string: "wss://configured\($0).example")! }, now: now)
        let calls = await relay.calls()
        #expect(calls[0].1.count == 8)
        #expect(calls[1].1.count == 4)
    }
}

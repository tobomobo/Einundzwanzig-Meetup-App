import Foundation
import Testing
@testable import MeetupNostr

private actor PublicNewsReactionRelay: NostrRelayTransport {
    var events: [NostrEvent]
    var rejectPublish = false
    var offline = false
    var queries = 0
    var published: [NostrEvent] = []
    init(events: [NostrEvent] = []) { self.events = events }
    func query(_ filter: NostrFilter, relays: [URL], timeout: TimeInterval) throws -> [NostrEvent] {
        queries += 1
        if offline { throw URLError(.notConnectedToInternet) }
        return events
    }
    func publish(_ event: NostrEvent, relays: [URL], signer: (any NostrSigner)?) throws {
        if rejectPublish { throw URLError(.cannotConnectToHost) }
        published.append(event)
    }
    func setRejected(_ rejected: Bool) { rejectPublish = rejected }
    func goOffline() { offline = true }
}
private actor PublicNewsIdentityFence {
    var checks = 0
    let allowedChecks: Int
    init(allowedChecks: Int) { self.allowedChecks = allowedChecks }
    func allows(_ key: String) -> Bool { checks += 1; return checks <= allowedChecks }
}
private struct PublicNewsWrongAuthorSigner: NostrSigner {
    let advertised: LocalIdentitySigner
    let actual: LocalIdentitySigner
    func publicKey() async throws -> String { try await advertised.publicKey() }
    func sign(kind: Int, tags: [[String]], content: String, createdAt: Int) async throws -> NostrEvent {
        try await actual.sign(kind: kind, tags: tags, content: content, createdAt: createdAt)
    }
    func encrypt(_ plaintext: String, to publicKey: String) async throws -> String { throw IdentityError.locked }
    func decrypt(_ ciphertext: String, from publicKey: String) async throws -> String { throw IdentityError.locked }
}

@Suite struct PublicNewsReactionJourneyParityTests {
    private func signer(_ byte: UInt8 = 1) throws -> LocalIdentitySigner {
        try LocalIdentitySigner(privateKey: Data(repeating: 0, count: 31) + Data([byte]))
    }
    @Test func acceptedHeartSurvivesOmittedRelayResponseAndRelaunchWithoutRepublishing() async throws {
        let signer = try signer(), key = try await signer.publicKey()
        let address = "30023:" + key + ":article:one"
        let defaultsKey = "public-news-parity-" + UUID().uuidString
        defer { UserDefaults.standard.removeObject(forKey: defaultsKey) }
        let relay = PublicNewsReactionRelay()
        let service = NewsNostrService(relay: relay, localLikesKey: defaultsKey)
        try await service.like(address, signer: signer, relays: [])
        #expect(await service.hasLikedLocally(address, publicKey: key))
        #expect(try await service.likes(address, relays: []) == [key])
        let restored = NewsNostrService(relay: relay, localLikesKey: defaultsKey)
        #expect(await restored.hasLikedLocally(address, publicKey: key))
        #expect(await !restored.hasLikedLocally(address, publicKey: String(repeating: "b", count: 64)))
        await relay.goOffline()
        try await restored.like(address, signer: signer, relays: [])
        #expect(await relay.published.count == 1)
        let event = try #require(await relay.published.first)
        #expect(await NostrCrypto().verify(event))
        #expect(event.kind == 7 && event.content == "+")
        #expect(event.tags == [["a", address], ["p", key], ["k", "30023"]])
    }

    @Test func rejectedHeartIsNotRememberedAndCanBeRetried() async throws {
        let signer = try signer(), key = try await signer.publicKey()
        let address = "30023:" + key + ":rejected"
        let relay = PublicNewsReactionRelay(), service = NewsNostrService(relay: relay, localLikesKey: nil)
        await relay.setRejected(true)
        await #expect(throws: URLError.self) { try await service.like(address, signer: signer, relays: []) }
        #expect(await !service.hasLikedLocally(address, publicKey: key))
        await relay.setRejected(false)
        try await service.like(address, signer: signer, relays: [])
        #expect(await service.hasLikedLocally(address, publicKey: key))
        #expect(await relay.published.count == 1)
    }

    @Test func identityChangeBeforeSigningOrBeforePublishingPreventsHeartWrite() async throws {
        let signer = try signer(), key = try await signer.publicKey()
        let address = "30023:" + key + ":identity"
        for allowedChecks in [0, 1, 2] {
            let relay = PublicNewsReactionRelay(), service = NewsNostrService(relay: relay, localLikesKey: nil)
            let fence = PublicNewsIdentityFence(allowedChecks: allowedChecks)
            await #expect(throws: CalendarChatError.identityMismatch) {
                try await service.like(address, signer: signer, relays: [], isCurrentIdentity: { await fence.allows($0) })
            }
            #expect(await relay.published.isEmpty)
            #expect(await !service.hasLikedLocally(address, publicKey: key))
        }
    }

    @Test func foreignSignerResponseDoesNotPublishOrRememberHeart() async throws {
        let signer = try signer(), other = try self.signer(2), key = try await signer.publicKey()
        let address = "30023:" + key + ":signer"
        let relay = PublicNewsReactionRelay(), service = NewsNostrService(relay: relay, localLikesKey: nil)
        await #expect(throws: (any Error).self) {
            try await service.like(address, signer: PublicNewsWrongAuthorSigner(advertised: signer, actual: other), relays: [])
        }
        #expect(await relay.published.isEmpty)
        #expect(await !service.hasLikedLocally(address, publicKey: key))
    }

    @Test func customPositiveEmojiCountsOnceAndNegativeOrInvalidReactionsDoNotCount() async throws {
        let signer = try signer(), other = try self.signer(2), key = try await signer.publicKey()
        let address = "30023:" + key + ":emoji"
        let positive = try await signer.sign(kind: 7, tags: [["a", address]], content: "🧡", createdAt: 100)
        let duplicate = try await signer.sign(kind: 7, tags: [["a", address]], content: "₿", createdAt: 101)
        let negative = try await other.sign(kind: 7, tags: [["a", address]], content: " - ", createdAt: 102)
        let invalidUnsigned = try UnsignedNostrEvent(pubkey: await other.publicKey(), createdAt: 103, kind: 7, tags: [["a", address]], content: "+")
        let invalid = try NostrEvent(unsigned: invalidUnsigned, id: NostrHex.encode(invalidUnsigned.eventID()), sig: String(repeating: "0", count: 128))
        let relay = PublicNewsReactionRelay(events: [positive, duplicate, negative, invalid])
        #expect(try await NewsNostrService(relay: relay, localLikesKey: nil).likes(address, relays: []) == [key])
        #expect(await relay.published.isEmpty)
    }

    @Test func localHeartLedgerIsBoundedToLatestFiveHundredEntries() async throws {
        let signer = try signer(), key = try await signer.publicKey()
        let prefix = key + "|30023:" + key + ":"
        let defaultsKey = "public-news-parity-" + UUID().uuidString
        defer { UserDefaults.standard.removeObject(forKey: defaultsKey) }
        UserDefaults.standard.set((0..<501).map { prefix + String($0) }, forKey: defaultsKey)
        let relay = PublicNewsReactionRelay(), service = NewsNostrService(relay: relay, localLikesKey: defaultsKey)
        #expect(await !service.hasLikedLocally("30023:" + key + ":0", publicKey: key))
        #expect(await service.hasLikedLocally("30023:" + key + ":500", publicKey: key))
        try await service.like("30023:" + key + ":new", signer: signer, relays: [])
        #expect(UserDefaults.standard.stringArray(forKey: defaultsKey)?.count == 500)
    }
}

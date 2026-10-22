import Foundation
import Testing
import MeetupNostr

private enum FixtureError: Error { case persistence }
@MainActor private final class MemoryBadgePersistence: BadgeWalletPersistence {
    var data: Data?
    var writes = 0
    var deletes = 0
    var failDelete = false
    func read() throws -> Data? { data }
    func write(_ data: Data) throws { writes += 1; self.data = data }
    func delete() throws { if failDelete { throw FixtureError.persistence }; deletes += 1; data = nil }
}
private actor FixtureBadgeRelay: NostrRelayTransport {
    var events: [NostrEvent] = []
    var published: [NostrEvent] = []
    var holding = false
    var queryFailure = false
    var started = false
    var resume: CheckedContinuation<Void, Never>?
    var startWaiters: [CheckedContinuation<Void, Never>] = []
    func setEvents(_ events: [NostrEvent]) { self.events = events }
    func hold() { holding = true }
    func failQueries() { queryFailure = true }
    func awaitQuery() async {
        if started { return }
        await withCheckedContinuation { startWaiters.append($0) }
    }
    func release() { resume?.resume(); resume = nil; holding = false }
    func query(_ filter: NostrFilter, relays: [URL], timeout: TimeInterval) async throws -> [NostrEvent] {
        if queryFailure { throw FixtureError.persistence }
        started = true; for waiter in startWaiters { waiter.resume() }; startWaiters = []
        if holding { await withCheckedContinuation { resume = $0 } }
        return events.filter(filter.matches)
    }
    func publish(_ event: NostrEvent, relays: [URL], signer: (any NostrSigner)?) async throws { published.append(event) }
    func publishCount() -> Int { published.count }
}
private let privateOne = Data(repeating: 0, count: 31) + Data([1])
private let privateTwo = Data(repeating: 0, count: 31) + Data([2])
private func badgePayload(signer: any NostrSigner) async throws -> String {
    let now = Int(Date().timeIntervalSince1970)
    return BadgeWire.canonical(try await BadgeSecurity.signCompact(meetupID: "wien-at", blockHeight: 900000, expiresAt: now + 3600, signer: signer, createdAt: now))
}
private func sealedChunk(_ env: BadgeSyncEnvelope, signer: any NostrSigner) async throws -> NostrEvent {
    let key = try await signer.publicKey(), now = Int(Date().timeIntervalSince1970)
    let encrypted = try await signer.encrypt(try BadgeGzip.deflate(Data(env.json.utf8)).base64EncodedString(), to: key)
    return try await signer.sign(kind: 30078, tags: [["d", BadgeSyncFormat.dTag(env.index)]], content: encrypted, createdAt: now)
}

@Suite(.serialized) @MainActor struct BadgeLifecycleTests {
    @Test func nativeGzipStreamForIndependentDecoder() throws {
        let plaintext = Data(String(repeating: "Swift gzip interop fixture äöü. ", count: 500).utf8)
        let compressed = try BadgeGzip.deflate(plaintext)
        #expect(compressed.count < plaintext.count / 4)
        #expect(try BadgeGzip.inflate(compressed) == plaintext)
        if let output = ProcessInfo.processInfo.environment["BADGE_GZIP_FIXTURE_OUTPUT"] {
            try compressed.write(to: URL(fileURLWithPath: output))
        }
    }
    @Test func organizerToCollectorPresenceWalletReputationAndEncryptedSyncJourney() async throws {
        let issuer = try LocalIdentitySigner(privateKey: privateOne), collector = try LocalIdentitySigner(privateKey: privateTwo)
        let issuerMemory = MemoryBadgePersistence(), collectorMemory = MemoryBadgePersistence(), relay = FixtureBadgeRelay()
        let organizer = BadgeStore(relay: relay, persistence: issuerMemory, relays: [])
        let wallet = BadgeStore(relay: relay, persistence: collectorMemory, relays: [])
        let wien = try BadgeCoordinate(latitude: 48.2, longitude: 16.37)
        let issuerKey = try await issuer.publicKey()
        organizer.meetupReference = { id in id == "wien-at" ? wien : nil }
        organizer.ownMeetupAuthority = { key, id in key == issuerKey && id == "wien-at" }
        wallet.meetupReference = { id in id == "wien-at" ? wien : nil }
        wallet.portalAuthority = { source in source.event.pubkey == issuerKey && source.meetupID == "wien-at" }
        await organizer.configure(signer: issuer); await wallet.configure(signer: collector)
        try await organizer.startSession(meetupID: "wien-at", blockHeight: 900000, deviceLocation: wien)
        let session = try #require(organizer.session)
        var payload = try BadgeWire.parse(session.qr(now: Int(Date().timeIntervalSince1970))).object
        payload["la"] = .number(52.52); payload["lo"] = .number(13.4)
        let badge = try await wallet.importBadge(BadgeWire.canonical(payload), deviceLocation: wien)
        #expect(badge.presenceVerified); #expect(badge.lat == 48.2); #expect(wallet.trust.badgeCount == 1)
        #expect(badge.meetupEventId.hasPrefix("wien-")); #expect(!badge.meetupEventId.hasPrefix("wien-at-"))
        await #expect(throws: BadgeProtocolError.duplicate) { try await wallet.importBadge(BadgeWire.canonical(payload), deviceLocation: wien) }
        try await wallet.generateReputationQR(nickname: "Fixture")
        let verified = try await wallet.verifyReputation(wallet.reputationQR, badgeEvidence: [badge])
        #expect(verified.badgeEvidenceVerified)
        try await wallet.sync(); #expect(await relay.publishCount() == 1)
        await wallet.configure(signer: collector)
        #expect(wallet.badges.count == 1); #expect(wallet.trust.badgeCount == 1)
        try await organizer.endSession(); #expect(organizer.session == nil)
    }
    @Test func observedNetworkCacheIsEncryptedReverifiedAndPreservedOffline() async throws {
        let persistence = MemoryBadgePersistence(), relay = FixtureBadgeRelay()
        let signer = try LocalIdentitySigner(privateKey: privateOne), other = try LocalIdentitySigner(privateKey: privateTwo)
        let key = try await signer.publicKey(), otherKey = try await other.publicKey(), now = Int(Date().timeIntervalSince1970)
        let session = "wien-2026-10-22@fixture"
        let content = BadgeWire.canonical(["event": .string(session), "meetup": .string("Wien"), "t": .int(now)])
        let tags = [["d", session], ["e_ref", String(repeating: "a", count: 64)]]
        let mine = try await signer.sign(kind: 30079, tags: tags, content: content, createdAt: now)
        let theirs = try await other.sign(kind: 30079, tags: tags, content: content, createdAt: now)
        await relay.setEvents([mine, theirs])
        let store = BadgeStore(relay: relay, persistence: persistence, relays: [])
        await store.configure(signer: signer); try await store.refreshSocial()
        #expect(store.social.network().pathTo(otherKey) == [key, otherKey]); #expect(store.trust.badgeCount == 0)
        let sealed = try #require(persistence.data)
        #expect(!String(decoding: sealed, as: UTF8.self).contains(session))
        #expect(!String(decoding: sealed, as: UTF8.self).contains(otherKey))
        await store.configure(signer: nil); await store.configure(signer: signer)
        #expect(store.social.network().pathTo(otherKey) == [key, otherKey])
        await relay.failQueries(); try await store.refreshSocial()
        #expect(store.social.stale); #expect(store.social.network().pathTo(otherKey) == [key, otherKey])
        // Remove signed source events but leave cached derived relationships.
        // Reload must reconstruct from sources rather than trust cached names.
        var envelope = try BadgeWire.parse(String(decoding: persistence.data!, as: UTF8.self)).object
        var plain = Data()
        for chunk in envelope["chunks"]!.array {
            plain.append(try #require(Data(base64Encoded: await signer.decrypt(chunk.string, from: key))))
        }
        var value = try BadgeWire.parse(String(decoding: plain, as: UTF8.self)).object
        var social = value["social"]!.object; social["sourceEvents"] = .array([]); value["social"] = .object(social)
        let ciphertext = try await signer.encrypt(Data(BadgeWire.canonical(value).utf8).base64EncodedString(), to: key)
        envelope["chunks"] = .array([.string(ciphertext)]); persistence.data = Data(BadgeWire.canonical(envelope).utf8)
        await store.configure(signer: signer)
        #expect(store.social.network().totalReach == 0); #expect(store.social.follows.isEmpty)
        try await store.prepareIdentityChange(); #expect(store.social.sourceEvents.isEmpty)
    }
    @Test func encryptedPersistenceReloadAndUnknownAuthorityRemainUntrusted() async throws {
        let persistence = MemoryBadgePersistence(), relay = FixtureBadgeRelay(), signer = try LocalIdentitySigner(privateKey: privateOne)
        let store = BadgeStore(relay: relay, persistence: persistence, relays: [])
        await store.configure(signer: signer)
        let payload = try await badgePayload(signer: signer)
        let badge = try await store.importBadge(payload, allowUnknownIssuer: true)
        #expect(!badge.claimSig.isEmpty); #expect(store.badges.count == 1); #expect(store.trust.badgeCount == 0)
        let encrypted = try #require(persistence.data)
        let encoded = String(decoding: encrypted, as: UTF8.self)
        #expect(!encoded.contains("meetupName")); #expect(!encoded.contains("wien-at")); #expect(!encoded.contains(badge.claimSig))
        await store.configure(signer: nil)
        #expect(store.badges.isEmpty)
        await store.configure(signer: signer)
        #expect(store.badges == [badge]); #expect(!store.walletUnreadable)
    }
    @Test func failedDeletionAbortsIdentitySwitchAndRetainsOldWallet() async throws {
        let persistence = MemoryBadgePersistence(), relay = FixtureBadgeRelay(), signer = try LocalIdentitySigner(privateKey: privateOne)
        let store = BadgeStore(relay: relay, persistence: persistence, relays: [])
        await store.configure(signer: signer)
        _ = try await store.importBadge(badgePayload(signer: signer), allowUnknownIssuer: true)
        let original = persistence.data, key = store.publicKey
        persistence.failDelete = true
        do { try await store.prepareIdentityChange(); Issue.record("Deletion failure was ignored") }
        catch BadgeStoreError.persistenceDeletion { }
        #expect(persistence.data == original); #expect(store.publicKey == key); #expect(store.badges.count == 1)
        #expect(!store.busy)
        persistence.failDelete = false; try await store.prepareIdentityChange()
        #expect(persistence.data == nil); #expect(store.publicKey.isEmpty); #expect(store.badges.isEmpty)
    }
    @Test func unreadableWalletNeverOverwritesExistingCiphertext() async throws {
        let persistence = MemoryBadgePersistence(), relay = FixtureBadgeRelay(), signer = try LocalIdentitySigner(privateKey: privateOne)
        let original = Data("unreadable-but-preserved".utf8); persistence.data = original
        let store = BadgeStore(relay: relay, persistence: persistence, relays: [])
        await store.configure(signer: signer); #expect(store.walletUnreadable)
        await #expect(throws: BadgeStoreError.unreadableWallet) { try await store.importBadge(badgePayload(signer: signer), allowUnknownIssuer: true) }
        await #expect(throws: BadgeStoreError.unreadableWallet) { try await store.sync() }
        #expect(persistence.data == original); #expect(persistence.writes == 0); #expect(await relay.publishCount() == 0)
    }
    @Test func delayedSyncCannotWriteOrPublishAfterIdentityChange() async throws {
        let persistence = MemoryBadgePersistence(), relay = FixtureBadgeRelay()
        let signer = try LocalIdentitySigner(privateKey: privateOne), next = try LocalIdentitySigner(privateKey: privateTwo)
        let store = BadgeStore(relay: relay, persistence: persistence, relays: [])
        await store.configure(signer: signer); await relay.hold()
        let sync = Task { try await store.sync() }
        await relay.awaitQuery(); try await store.prepareIdentityChange(); await store.configure(signer: next)
        await relay.release()
        do { try await sync.value; Issue.record("Stale sync completed") } catch BadgeStoreError.identityChanged { }
        #expect(persistence.writes == 0); #expect(await relay.publishCount() == 0)
        #expect(store.publicKey == (try await next.publicKey())); #expect(!store.busy)
    }
    @Test func mixedAndUnreadableSyncNeverPersistOrRepublish() async throws {
        let persistence = MemoryBadgePersistence(), relay = FixtureBadgeRelay(), signer = try LocalIdentitySigner(privateKey: privateOne)
        let store = BadgeStore(relay: relay, persistence: persistence, relays: [])
        await store.configure(signer: signer)
        let first = try await sealedChunk(BadgeSyncEnvelope(index: 0, total: 2, generation: 2, badges: []), signer: signer)
        let old = try await sealedChunk(BadgeSyncEnvelope(index: 1, total: 2, generation: 1, badges: []), signer: signer)
        await relay.setEvents([first, old])
        await #expect(throws: BadgeProtocolError.incompleteSync) { try await store.sync() }
        #expect(persistence.writes == 0); #expect(await relay.publishCount() == 0)
        let malformed = try await signer.sign(kind: 30078, tags: [["d", "badges-0"]], content: "not-nip44", createdAt: Int(Date().timeIntervalSince1970))
        await relay.setEvents([malformed])
        await #expect(throws: BadgeProtocolError.incompleteSync) { try await store.sync() }
        #expect(persistence.writes == 0); #expect(await relay.publishCount() == 0)
    }
    @Test func backupRejectsForgedClaimAndRedactsNestedSecretsWithoutAuthority() async throws {
        let persistence = MemoryBadgePersistence(), relay = FixtureBadgeRelay(), signer = try LocalIdentitySigner(privateKey: privateOne)
        let store = BadgeStore(relay: relay, persistence: persistence, relays: [])
        await store.configure(signer: signer)
        let badge = try await store.importBadge(badgePayload(signer: signer), allowUnknownIssuer: true)
        let raw = try #require(store.backupBadges().first)
        let document = try BackupDocument(fields: ["version": .number(5), "user": .object([:]), "badges": .array([raw]),
            "nostr": .object(["nsec": .string("must-not-be-staged")]),
            "platform_proofs": .object(["username": .string("fixture"), "nsec": .string("must-not-be-staged"), "nested": .object(["priv_hex": .string("secret"), "access_token": .string("token"), "proof_sig": .string("pending")])]),
            "humanity_proof": .object(["verified": .bool(true), "receipt_id": .string("untrusted")])])
        try await store.restoreBackup(document, decision: .sameIdentity)
        #expect(store.badges.count == 1); #expect(store.humanity == nil); #expect(store.trust.badgeCount == 0)
        let pending = String(decoding: try JSONEncoder().encode(store.pendingBackup), as: UTF8.self)
        #expect(!pending.contains("must-not-be-staged")); #expect(!pending.contains("priv_hex")); #expect(!pending.contains("access_token")); #expect(pending.contains("proof_sig"))
        var forged = badge; forged.claimSig = String(repeating: "0", count: 128)
        let forgedRaw = try JSONDecoder().decode(BackupValue.self, from: Data(forged.wireJSON.utf8))
        let invalid = try BackupDocument(fields: ["version": .number(5), "user": .object([:]), "badges": .array([forgedRaw])])
        let writes = persistence.writes
        await #expect(throws: BadgeProtocolError.invalidClaim) { try await store.restoreBackup(invalid, decision: .sameIdentity) }
        #expect(persistence.writes == writes)
    }
}

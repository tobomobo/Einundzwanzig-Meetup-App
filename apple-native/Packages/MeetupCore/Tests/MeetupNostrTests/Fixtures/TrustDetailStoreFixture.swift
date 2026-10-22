import Foundation
import Testing
import MeetupNostr

@MainActor final class DetailPersistence: BadgeWalletPersistence {
    var data: Data?
    func read() throws -> Data? { data }
    func write(_ data: Data) throws { self.data = data }
    func delete() throws { data = nil }
}
@Suite(.serialized) @MainActor struct TrustDetailStoreParityTests {
    @Test func thirdPartyActivityAndReputationUseIndependentPublicRelayWithoutChangingWalletTrust() async throws {
        let signer = try detailSigner(1), person = try detailSigner(2), provider = try detailSigner(3)
        let key = try await person.publicKey(), now = Int(Date().timeIntervalSince1970)
        let receipt = try await detailZap(sender: signer, recipient: key, provider: provider, at: now - 20)
        let profile = try await [person].asyncDetailProfiles(now: now)
        let reputation = try await person.sign(kind: 30078, tags: [["d", ReputationCard.dTag]],
            content: ReputationCard.relayContent(badges: [], score: TrustScore(), nickname: "Person", platformProofs: [], humanity: nil, now: now), createdAt: now)
        let main = DetailRelay(), publicRelay = DetailRelay([receipt, reputation] + profile)
        let store = BadgeStore(relay: main, persistence: DetailPersistence(), relays: [], proofHTTP: DetailHTTP(try await provider.publicKey()), publicRelay: publicRelay)
        await store.configure(signer: signer)
        let before = store.trust
        let activity = try await store.personZapActivity("nostr:" + NIP19.encodePublicKey(key))
        #expect(activity.receivedCount == 1); #expect(activity.sentCount == 0)
        let statement = try await store.verifyReputation("  nostr:" + NIP19.encodePublicKey(key) + "\n")
        #expect(statement.nickname == "Person"); #expect(!statement.badgeEvidenceVerified)
        try await store.refreshSocial(target: key)
        #expect(await main.recordedFilters().isEmpty)
        #expect(!(await publicRelay.recordedFilters()).isEmpty)
        #expect(store.trust == before); #expect(store.humanity == nil); #expect(store.badges.isEmpty)
    }
    @Test func identityReplacementFencesActivityAndDisablesOldShareSummary() async throws {
        let signer = try detailSigner(1), person = try detailSigner(2), key = try await person.publicKey()
        let relay = DetailRelay(); await relay.hold()
        let store = BadgeStore(relay: DetailRelay(), persistence: DetailPersistence(), relays: [], publicRelay: relay)
        await store.configure(signer: signer)
        let summary = try store.reputationImageSummary(nickname: "Public nickname")
        #expect(summary.publicKey == (try await signer.publicKey())); #expect(summary.badgeCount == 0)
        let loading = Task { try await store.personZapActivity(key) }
        await relay.awaitQuery(); try await store.prepareIdentityChange(); await relay.release()
        await #expect(throws: BadgeStoreError.identityChanged) { try await loading.value }
        #expect(throws: BadgeStoreError.noSigner) { try store.reputationImageSummary(nickname: "New nickname") }
        #expect(store.publicKey.isEmpty); #expect(!store.busy)
    }
    @Test func shareSummaryUsesVerifiedCollectorEvidenceAndExcludesOrganizerMarkers() async throws {
        let issuer = try detailSigner(1), collector = try detailSigner(2), key = try await issuer.publicKey()
        let point = try BadgeCoordinate(latitude: 48.2, longitude: 16.37)
        let organizer = BadgeStore(relay: DetailRelay(), persistence: DetailPersistence(), relays: [])
        organizer.meetupReference = { _ in point }; organizer.ownMeetupAuthority = { author, _ in author == key }
        await organizer.configure(signer: issuer)
        try await organizer.startSession(meetupID: "wien-at", blockHeight: 900000, deviceLocation: point)
        #expect(organizer.badges.count == 1); #expect(organizer.badges[0].isOrganizer)
        #expect(try organizer.reputationImageSummary(nickname: "Organizer").badgeCount == 0)
        let wallet = BadgeStore(relay: DetailRelay(), persistence: DetailPersistence(), relays: [])
        wallet.meetupReference = { _ in point }; wallet.portalAuthority = { $0.event.pubkey == key }
        await wallet.configure(signer: collector)
        let session = try #require(organizer.session), now = Int(Date().timeIntervalSince1970)
        _ = try await wallet.importBadge(session.qr(now: now), deviceLocation: point)
        let summary = try wallet.reputationImageSummary(nickname: String(repeating: "a", count: 100))
        #expect(summary.badgeCount == 1); #expect(summary.meetupCount == 1); #expect(summary.signerCount == 1)
        #expect(summary.score == wallet.trust.total); #expect(summary.nickname.count == 80)
        #expect(summary.publicKey == (try await collector.publicKey()))
    }
}

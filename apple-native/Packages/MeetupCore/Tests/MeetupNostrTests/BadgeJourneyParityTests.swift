import Foundation
import Testing
@testable import MeetupNostr

struct BadgeJourneyProtocolParityTests {
    @Test func personLookupAcceptsNostrAddressAndAuthenticatedQRButRejectsTamperedIdentity() async throws {
        let signer = try LocalIdentitySigner(privateKey: fixturePrivateKey), now = 1_760_000_000
        let npub = try NIP19.encodePublicKey(fixturePublicKey)
        #expect(try await SocialNetwork.personPublicKey("  nostr:" + npub + "\n", now: now) == fixturePublicKey)
        #expect(try await SocialNetwork.personPublicKey(npub.uppercased(), now: now) == fixturePublicKey)
        #expect(try await SocialNetwork.personPublicKey(fixturePublicKey, now: now) == fixturePublicKey)
        let payload = try ReputationCard.qrPayload(badges: [], score: TrustScore(), nickname: "Fixture", publicKey: fixturePublicKey, platformProofs: [], now: now)
        let qr = try await ReputationCard.signQR(payload, signer: signer, now: now)
        #expect(try await SocialNetwork.personPublicKey(qr, now: now) == fixturePublicKey)
        var parts = qr.components(separatedBy: ".")
        parts[1] = String(repeating: "0", count: 128)
        await #expect(throws: (any Error).self) { try await SocialNetwork.personPublicKey(parts.joined(separator: "."), now: now) }
        await #expect(throws: (any Error).self) { try await SocialNetwork.personPublicKey("nostr:invalid", now: now) }
        let other = try LocalIdentitySigner(privateKey: Data(repeating: 0, count: 31) + Data([2]))
        let otherPayload = try ReputationCard.qrPayload(badges: [], score: TrustScore(), nickname: "Wrong identity", publicKey: try await other.publicKey(), platformProofs: [], now: now)
        let mismatched = try await ReputationCard.signQR(otherPayload, signer: signer, now: now)
        await #expect(throws: BadgeProtocolError.invalidSignature) { try await SocialNetwork.personPublicKey(mismatched, now: now) }
    }
    @Test func eventOrganizerMarkerRetainsEventSeparationAndRejectsWrongOwner() async throws {
        let signer = try LocalIdentitySigner(privateKey: fixturePrivateKey), now = 1_760_000_000
        let id = "evt:31923:" + fixturePublicKey + ":fixture-event:bitcoin-meetup"
        let payload = try await BadgeSecurity.signCompact(meetupID: id, blockHeight: 900001, expiresAt: now + 3600, signer: signer, createdAt: now)
        let session = BadgeOrganizerSession(owner: fixturePublicKey, meetupID: id, expiresAt: now + 3600, payload: payload)
        let marker = try await session.attendanceMarker(signer: signer, location: nil, now: now)
        #expect(marker.isOrganizer); #expect(marker.isEvent); #expect(!marker.presenceVerified)
        #expect(SocialNetwork.attendanceKey(marker).hasPrefix("ev|"))
        #expect(await BadgeSecurity.verifyStored(marker, collector: fixturePublicKey, now: now))
        let other = try LocalIdentitySigner(privateKey: Data(repeating: 0, count: 31) + Data([2]))
        await #expect(throws: BadgeProtocolError.invalidClaim) { try await session.attendanceMarker(signer: other, location: nil, now: now) }
        let swapped = BadgeOrganizerSession(owner: fixturePublicKey, meetupID: "elsewhere-at", expiresAt: now + 3600, payload: payload)
        await #expect(throws: BadgeProtocolError.invalidClaim) { try await swapped.attendanceMarker(signer: signer, location: nil, now: now) }
    }
}


private struct PersonMappingHTTP: ProofHTTPClient {
    let key: String
    var unavailable = false
    func get(_ url: URL) async throws -> BadgeJSON {
        if unavailable { throw CancellationError() }
        return .object(["names": .object(["alice": .string(key)])])
    }
}
private actor PersonMappingRelay: NostrRelayTransport {
    let events: [NostrEvent]
    init(_ events: [NostrEvent]) { self.events = events }
    func query(_ filter: NostrFilter, relays: [URL], timeout: TimeInterval) async throws -> [NostrEvent] { events }
    func publish(_ event: NostrEvent, relays: [URL], signer: (any NostrSigner)?) async throws { throw CancellationError() }
}
struct PersonIdentityMappingParityTests {
    @Test func signedNameNeverSubstitutesForDomainMappingAndForgedMetadataIsIgnored() async throws {
        let signer = try LocalIdentitySigner(privateKey: fixturePrivateKey), now = 1_760_000_000
        let content = #"{"display_name":"Alice","nip05":"alice@example.test"}"#
        let profile = try await signer.sign(kind: 0, tags: [], content: content, createdAt: now)
        let relay = PersonMappingRelay([profile])
        let mismatch = try await ProofVerifier.personIdentity(publicKey: fixturePublicKey, relay: relay, relays: [],
            http: PersonMappingHTTP(key: String(repeating: "0", count: 64)), now: now)
        #expect(mismatch.profile?.displayName == "Alice"); #expect(mismatch.nip05Status == .mismatch)
        let unavailable = try await ProofVerifier.personIdentity(publicKey: fixturePublicKey, relay: relay, relays: [],
            http: PersonMappingHTTP(key: fixturePublicKey, unavailable: true), now: now)
        #expect(unavailable.nip05Status == .unavailable)
        let forged = try NostrEvent(id: profile.id, pubkey: profile.pubkey, createdAt: now, kind: 0, tags: [],
            content: #"{"display_name":"Forged","nip05":"alice@example.test"}"#, sig: profile.sig)
        let rejected = try await ProofVerifier.personIdentity(publicKey: fixturePublicKey, relay: PersonMappingRelay([forged]), relays: [],
            http: PersonMappingHTTP(key: fixturePublicKey), now: now)
        #expect(rejected.profile == nil); #expect(rejected.nip05Status == .absent)
    }
}

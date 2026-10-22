import Foundation
import Testing
@testable import MeetupNostr

// Fixed synthetic fixture identities only; invoices carry a fixture checksum
// and description hash, and never represent a payable invoice.
func detailSigner(_ byte: UInt8) throws -> LocalIdentitySigner {
    try LocalIdentitySigner(privateKey: Data(repeating: 0, count: 31) + Data([byte]))
}
func detailZap(sender: LocalIdentitySigner, recipient: String, provider: LocalIdentitySigner, at: Int,
               indexedSender: String? = nil, wrongInvoice: Bool = false) async throws -> NostrEvent {
    let request = try await sender.sign(kind: 9734, tags: [["p", recipient]], content: "offline fixture", createdAt: at - 1)
    let description = String(decoding: try JSONEncoder().encode(request), as: UTF8.self)
    var tags = [["p", recipient], ["description", description], ["bolt11", detailInvoice(description: wrongInvoice ? "mismatch" : description)]]
    if let indexedSender { tags.append(["P", indexedSender]) }
    return try await provider.sign(kind: 9735, tags: tags, content: "", createdAt: at)
}
actor DetailHTTP: ProofHTTPClient {
    let provider: String
    var requests: [URL] = []
    var cancelled = false
    init(_ provider: String, cancelled: Bool = false) { self.provider = provider; self.cancelled = cancelled }
    func get(_ url: URL) async throws -> BadgeJSON {
        requests.append(url)
        if cancelled { throw CancellationError() }
        return .object(["allowsNostr": .bool(true), "nostrPubkey": .string(provider)])
    }
    func count() -> Int { requests.count }
}
enum DetailFailure: Error { case unavailable }
actor DetailRelay: NostrRelayTransport {
    let events: [NostrEvent]
    var filters: [NostrFilter] = []
    var destinations: [[URL]] = []
    var failTag: String?
    var unfiltered = false
    var holding = false
    var started = false
    var continuation: CheckedContinuation<Void, Never>?
    var waiters: [CheckedContinuation<Void, Never>] = []
    init(_ events: [NostrEvent] = [], failTag: String? = nil, unfiltered: Bool = false) {
        self.events = events; self.failTag = failTag; self.unfiltered = unfiltered
    }
    func query(_ filter: NostrFilter, relays: [URL], timeout: TimeInterval) async throws -> [NostrEvent] {
        filters.append(filter); destinations.append(relays); started = true
        waiters.forEach { $0.resume() }; waiters = []
        if holding { await withCheckedContinuation { continuation = $0 } }
        if let failTag, filter.tags[failTag] != nil { throw DetailFailure.unavailable }
        return unfiltered ? events : events.filter(filter.matches)
    }
    func publish(_ event: NostrEvent, relays: [URL], signer: (any NostrSigner)?) async throws {
        Issue.record("A public detail journey must never publish")
        throw DetailFailure.unavailable
    }
    func recordedFilters() -> [NostrFilter] { filters }
    func maximumRelayCount() -> Int { destinations.map(\.count).max() ?? 0 }
    func hold() { holding = true }
    func awaitQuery() async { if !started { await withCheckedContinuation { waiters.append($0) } } }
    func release() { holding = false; continuation?.resume(); continuation = nil }
}
func detailInvoice(description: String) -> String {
    let hash = Array(BadgeWire.sha256(description).utf8)
    let hex = String(decoding: hash, as: UTF8.self)
    let bytes = stride(from: 0, to: hex.count, by: 2).map { index -> UInt8 in
        let start = hex.index(hex.startIndex, offsetBy: index), end = hex.index(start, offsetBy: 2)
        return UInt8(hex[start..<end], radix: 16)!
    }
    var acc: UInt32 = 0, bits = 0, h: [Int] = []
    for byte in bytes { acc = acc << 8 | UInt32(byte); bits += 8; while bits >= 5 { bits -= 5; h.append(Int(acc >> bits & 31)) } }
    if bits > 0 { h.append(Int(acc << (5 - bits) & 31)) }
    var words = Array(repeating: 0, count: 7) + [23, 1, 20] + h + Array(repeating: 0, count: 104)
    let hrp = Array("lnbc10n".utf8), alphabet = Array("qpzry9x8gf2tvdw0s3jn54khce6mua7l")
    var checksum: UInt32 = 1
    let generators: [UInt32] = [0x3b6a57b2,0x26508e6d,0x1ea119fa,0x3d4233dd,0x2a1462b3]
    for value in hrp.map({ Int($0 >> 5) }) + [0] + hrp.map({ Int($0 & 31) }) + words + Array(repeating: 0, count: 6) {
        let top = checksum >> 25; checksum = (checksum & 0x1ffffff) << 5 ^ UInt32(value)
        for i in 0..<5 where top >> i & 1 != 0 { checksum ^= generators[i] }
    }
    checksum ^= 1
    words += (0..<6).map { Int(checksum >> (5 * (5 - $0)) & 31) }
    return "lnbc10n1" + String(words.map { alphabet[$0] })
}

struct TrustDetailActivityParityTests {
    @Test func countsAuthenticatedDirectionsDiversityAndSpanWithoutTrustingIndexOrAggregates() async throws {
        let target = try detailSigner(1), peer = try detailSigner(2), provider = try detailSigner(3), stranger = try detailSigner(4)
        let key = try await target.publicKey(), peerKey = try await peer.publicKey(), providerKey = try await provider.publicKey()
        let now = 1_780_000_000
        let sent = try await detailZap(sender: target, recipient: peerKey, provider: provider, at: now - 61 * 86400, indexedSender: key)
        let received = try await detailZap(sender: peer, recipient: key, provider: provider, at: now - 86400, indexedSender: peerKey)
        let spoofedIndex = try await detailZap(sender: stranger, recipient: peerKey, provider: provider, at: now - 20, indexedSender: key)
        let selfZap = try await detailZap(sender: target, recipient: key, provider: provider, at: now - 21, indexedSender: key)
        let old = try await detailZap(sender: peer, recipient: key, provider: provider, at: now - 181 * 86400)
        let future = try await detailZap(sender: peer, recipient: key, provider: provider, at: now + 1)
        let invalidInvoice = try await detailZap(sender: peer, recipient: key, provider: provider, at: now - 22, wrongInvoice: true)
        let ownIssuer = try await detailZap(sender: peer, recipient: key, provider: peer, at: now - 23)
        let wrongProvider = try await detailZap(sender: peer, recipient: key, provider: stranger, at: now - 24)
        let forged = try NostrEvent(id: received.id, pubkey: received.pubkey, createdAt: received.createdAt, kind: received.kind, tags: received.tags, content: "forged", sig: received.sig)
        let profiles = try await [target, peer].asyncDetailProfiles(now: now)
        let relay = DetailRelay([forged, sent, received, received, spoofedIndex, selfZap, old, future, invalidInvoice, ownIssuer, wrongProvider] + profiles, unfiltered: true)
        let http = DetailHTTP(providerKey)
        let result = try await PersonZapActivityLoader(relay: relay, http: http).load(publicKey: key,
            relays: (1...5).map { URL(string: "wss://relay\($0).example.test")! }, now: now)
        #expect(result.sentCount == 1); #expect(result.receivedCount == 1)
        #expect(result.uniqueRecipients == 1); #expect(result.uniqueSenders == 1); #expect(result.activeMonths == 2)
        #expect(!result.incomplete); #expect(!result.truncated); #expect(await http.count() == 2)
        let filters = await relay.recordedFilters()
        #expect(filters[0].tags == ["p": [key]]); #expect(filters[1].tags == ["P": [key]])
        #expect(filters[0].since == now - 180 * 86400); #expect(filters[0].until == now)
        #expect(await relay.maximumRelayCount() == 3)
    }
    @Test func partialFailureAndRecordLimitRemainVisibleAndProviderCancellationPropagates() async throws {
        let target = try detailSigner(1), peer = try detailSigner(2), provider = try detailSigner(3)
        let key = try await target.publicKey(), now = 1_780_000_000
        let receipt = try await detailZap(sender: peer, recipient: key, provider: provider, at: now - 10)
        let metadata = try await [target].asyncDetailProfiles(now: now)
        let http = DetailHTTP(try await provider.publicKey())
        let partial = try await PersonZapActivityLoader(relay: DetailRelay([receipt] + metadata, failTag: "P"), http: http).load(publicKey: key, relays: [], now: now)
        #expect(partial.receivedCount == 1); #expect(partial.incomplete)
        let bounded = try await PersonZapActivityLoader(relay: DetailRelay(Array(repeating: receipt, count: 101) + metadata), http: http).load(publicKey: key, relays: [], now: now)
        #expect(bounded.receivedCount == 1); #expect(bounded.truncated)
        let unavailable = try await PersonZapActivityLoader(relay: DetailRelay([receipt]), http: http).load(publicKey: key, relays: [], now: now)
        #expect(unavailable.totalCount == 0); #expect(unavailable.incomplete)
        await #expect(throws: CancellationError.self) {
            try await PersonZapActivityLoader(relay: DetailRelay([receipt] + metadata), http: DetailHTTP(try await provider.publicKey(), cancelled: true)).load(publicKey: key, relays: [], now: now)
        }
    }
    @Test func capsProviderLookupsAndNeverUsesForgedRecipientMetadata() async throws {
        let target = try detailSigner(1), provider = try detailSigner(3), key = try await target.publicKey(), now = 1_780_000_000
        var events: [NostrEvent] = []
        for byte in UInt8(10)...UInt8(25) {
            let recipient = try detailSigner(byte), recipientKey = try await recipient.publicKey()
            events.append(try await detailZap(sender: target, recipient: recipientKey, provider: provider, at: now - Int(byte), indexedSender: key))
            events += try await [recipient].asyncDetailProfiles(now: now)
        }
        let http = DetailHTTP(try await provider.publicKey())
        let limited = try await PersonZapActivityLoader(relay: DetailRelay(events), http: http).load(publicKey: key, relays: [], now: now)
        #expect(limited.sentCount == 15); #expect(limited.uniqueRecipients == 15); #expect(limited.truncated)
        #expect(await http.count() == 15)
        let profile = try await target.sign(kind: 0, tags: [], content: #"{"lud16":"target@example.test"}"#, createdAt: now - 1)
        let forged = try NostrEvent(id: profile.id, pubkey: key, createdAt: profile.createdAt, kind: 0, tags: [], content: #"{"lud16":"attacker@example.test"}"#, sig: profile.sig)
        let incoming = try await detailZap(sender: provider, recipient: key, provider: detailSigner(2), at: now - 2)
        let rejected = try await PersonZapActivityLoader(relay: DetailRelay([forged, incoming]), http: http).load(publicKey: key, relays: [], now: now)
        #expect(rejected.totalCount == 0); #expect(rejected.incomplete)
    }
}
extension Array where Element == LocalIdentitySigner {
    func asyncDetailProfiles(now: Int) async throws -> [NostrEvent] {
        var events: [NostrEvent] = []
        for signer in self { events.append(try await signer.sign(kind: 0, tags: [], content: #"{"lud16":"fixture@example.test"}"#, createdAt: now - 1)) }
        return events
    }
}

struct TrustDetailDisplayNameParityTests {
    @Test func reputationNicknameOnlyFillsAbsentProfileAndNeverResurrectsRemovedName() async throws {
        let signer = try detailSigner(1), other = try detailSigner(2), key = try await signer.publicKey(), now = 1_780_000_000
        func card(_ name: String) async throws -> VerifiedReputationCard {
            let content = ReputationCard.relayContent(badges: [], score: TrustScore(), nickname: name, platformProofs: [], humanity: nil, now: now)
            let event = try await signer.sign(kind: 30078, tags: [["d", ReputationCard.dTag]], content: content, createdAt: now)
            return try await ReputationCard.verifyEvent(event, now: now)
        }
        let named = try await card("  App nickname  ")
        let absent = PersonIdentityEvidence(profile: nil, nip05Status: .absent)
        #expect(absent.displayName(for: key, reputation: named) == "App nickname")
        #expect(absent.displayName(for: try await other.publicKey(), reputation: named) == nil)
        for value in ["", "Anon", " aNoN "] { #expect(absent.displayName(for: key, reputation: try await card(value)) == nil) }
        let older = try await signer.sign(kind: 0, tags: [], content: #"{"display_name":"Old name"}"#, createdAt: now - 2)
        let removed = try await signer.sign(kind: 0, tags: [], content: #"{"display_name":"","name":""}"#, createdAt: now - 1)
        let evidence = try await ProofVerifier.personIdentity(publicKey: key, relay: DetailRelay([older, removed]), relays: [], http: DetailHTTP(key), now: now)
        #expect(evidence.profile?.profile?.id == removed.id)
        #expect(evidence.displayName(for: key, reputation: named) == nil)
        #expect(evidence.nip05Status == .absent)
        let current = PersonIdentityEvidence(profile: NostrPublicProfile(profile: older), nip05Status: .absent)
        #expect(current.displayName(for: key, reputation: named) == "Old name")
    }
}

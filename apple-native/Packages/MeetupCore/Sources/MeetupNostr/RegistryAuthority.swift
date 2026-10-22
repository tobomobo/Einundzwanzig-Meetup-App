import Foundation

public struct AdminRegistryEntry: Codable, Hashable, Sendable, Identifiable {
    public var npub: String
    public var meetup: String
    public var name: String
    public var addedAt: Int
    public var id: String { npub }
    public var publicKey: String? { try? NIP19.decodePublicKey(npub) }
    public var isLegacyOrganic: Bool { name.range(of: #"^Organic \(\d+ Badges\)$"#, options: .regularExpression) != nil }
    enum CodingKeys: String, CodingKey { case npub, meetup, name; case addedAt = "added_at" }
    public init(npub: String, meetup: String, name: String = "", addedAt: Int = 0) {
        self.npub = npub; self.meetup = meetup; self.name = name; self.addedAt = addedAt
    }
    public init(json: BadgeJSON) {
        let j = json.object; npub = j["npub"]?.string ?? ""; meetup = j["meetup"]?.string ?? ""
        name = j["name"]?.string ?? ""; addedAt = j["added_at"]?.integer ?? 0
    }
    public var json: BadgeJSON { .object(["npub": .string(npub), "meetup": .string(meetup), "name": .string(name), "added_at": .int(addedAt)]) }
}

public struct RegistrySnapshot: Codable, Sendable {
    public var events: [NostrEvent]
    public var admins: [AdminRegistryEntry]
    public var sunsetAuthorEvidence: [NostrEvent]
    public var promotionClaims: [NostrEvent]
    public var organicAdmins: [AdminRegistryEntry]
    public var updatedAt: Int
    public var sunsetActive: Bool { Set(sunsetAuthorEvidence.map(\.pubkey)).count >= 20 }
    public var publicKeys: Set<String> { Set((admins + organicAdmins).compactMap(\.publicKey)) }
    public init(events: [NostrEvent] = [], admins: [AdminRegistryEntry] = [], sunsetAuthorEvidence: [NostrEvent] = [], promotionClaims: [NostrEvent] = [], organicAdmins: [AdminRegistryEntry] = [], updatedAt: Int = 0) {
        self.events = events; self.admins = admins; self.sunsetAuthorEvidence = sunsetAuthorEvidence; self.updatedAt = updatedAt
        self.promotionClaims = promotionClaims
        self.organicAdmins = organicAdmins
    }
    enum CodingKeys: String, CodingKey { case events, admins, sunsetAuthorEvidence, promotionClaims, organicAdmins, updatedAt }
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        events = try c.decodeIfPresent([NostrEvent].self, forKey: .events) ?? []
        admins = try c.decodeIfPresent([AdminRegistryEntry].self, forKey: .admins) ?? []
        sunsetAuthorEvidence = try c.decodeIfPresent([NostrEvent].self, forKey: .sunsetAuthorEvidence) ?? []
        promotionClaims = try c.decodeIfPresent([NostrEvent].self, forKey: .promotionClaims) ?? []
        organicAdmins = try c.decodeIfPresent([AdminRegistryEntry].self, forKey: .organicAdmins) ?? []
        updatedAt = try c.decodeIfPresent(Int.self, forKey: .updatedAt) ?? 0
    }
    public func isAuthorized(_ publicKey: String) -> Bool {
        publicKeys.contains(publicKey) || (!sunsetActive && publicKey == RegistryAuthority.bootstrapPublicKey)
    }
}

public enum RegistryAuthority {
    public static let dTag = "einundzwanzig-admins"
    public static let bootstrapNpub = "npub1lf0rga7j66uj6enae2mxezamz5nsz3vechhvmh25tcarn4u8qf5q534jzc"
    public static var bootstrapPublicKey: String { (try? NIP19.decodePublicKey(bootstrapNpub)) ?? "" }
    public static func entries(_ event: NostrEvent) throws -> [AdminRegistryEntry] {
        guard event.kind == 30078, event.tags.contains(["d", dTag]) else { throw BadgeProtocolError.malformed }
        let map = try BadgeWire.parse(event.content, limit: 65_536).object
        guard case .array(let raw) = map["admins"] else { throw BadgeProtocolError.malformed }
        return raw.map(AdminRegistryEntry.init(json:)).filter { !$0.isLegacyOrganic && $0.publicKey != nil }
    }
    /// Only cryptographically verified author lists can extend authority. A
    /// caller-supplied admins array or advertised counts never grants authority.
    public static func resolve(_ incoming: [NostrEvent], previous: RegistrySnapshot = RegistrySnapshot(), now: Int) async -> RegistrySnapshot {
        await resolve(incoming, previous: previous, now: now, bootstrapKey: bootstrapPublicKey)
    }
    // Test-only trust domain injection is internal to the package. Production
    // callers always start from the pinned bootstrap identity above.
    static func resolve(_ incoming: [NostrEvent], previous: RegistrySnapshot, now: Int, bootstrapKey: String) async -> RegistrySnapshot {
        let crypto = NostrCrypto()
        var latest: [String: NostrEvent] = [:]
        for event in previous.events + incoming {
            guard event.createdAt <= now + 300, (try? entries(event)) != nil, await crypto.verify(event) else { continue }
            if let old = latest[event.pubkey], old.createdAt > event.createdAt || (old.createdAt == event.createdAt && old.id < event.id) { continue }
            latest[event.pubkey] = event
        }
        var accepted: Set<String> = [bootstrapKey]
        var admins: [String: AdminRegistryEntry] = [:]
        var evidence: [String: NostrEvent] = [:]
        // Reconstruct the bootstrap path from signed evidence, never from
        // cached admin names. Historical signed lists establish sunset authors.
        let historical = previous.sunsetAuthorEvidence
        var historicalLatest: [String: NostrEvent] = [:]
        for event in historical where event.createdAt <= now + 300 {
            guard await crypto.verify(event), let list = try? entries(event), !list.isEmpty else { continue }
            historicalLatest[event.pubkey] = event
        }
        // Historical signatures may preserve an already reached sunset. Until
        // that point, old bootstrap lists cannot resurrect a revoked vouch.
        var historicAccepted: Set<String> = [bootstrapKey], historicEvidence: [String: NostrEvent] = [:]
        for _ in 0..<64 {
            let old = historicAccepted
            for author in Array(historicAccepted) {
                guard let event = historicalLatest[author], let list = try? entries(event) else { continue }
                if !list.isEmpty { historicEvidence[author] = event }
                for entry in list { if let key = entry.publicKey { historicAccepted.insert(key) } }
            }
            let historicRegistry = RegistrySnapshot(admins: historicAccepted.compactMap { key in
                (try? NIP19.encodePublicKey(key)).map { AdminRegistryEntry(npub: $0, meetup: "") }
            })
            for claim in previous.promotionClaims {
                if let entry = await verifyPromotion(claim, registry: historicRegistry, now: now), let key = entry.publicKey { historicAccepted.insert(key) }
            }
            if old == historicAccepted { break }
        }
        let previouslySunset = historicEvidence.count >= 20
        if previouslySunset { accepted = historicAccepted; evidence = historicEvidence }
        for _ in 0..<64 {
            let old = accepted
            for author in Array(accepted) {
                if previouslySunset && author == bootstrapKey { continue }
                guard let event = latest[author], let list = try? entries(event) else { continue }
                if !list.isEmpty { evidence[author] = event }
                for entry in list { if let key = entry.publicKey { accepted.insert(key) } }
            }
            if old == accepted { break }
        }
        let sunset = evidence.count >= 20
        let queryAuthors: Set<String> = sunset ? accepted.subtracting([bootstrapKey]) : [bootstrapKey]
        // Before sunset only the bootstrap list is authoritative, matching
        // Flutter. Afterwards known peers publish their own vouch lists.
        for author in queryAuthors {
            guard let event = latest[author], let list = try? entries(event) else { continue }
            if !list.isEmpty { evidence[author] = event }
            for entry in list { admins[entry.npub] = entry }
        }
        let snapshot = RegistrySnapshot(events: latest.values.sorted { $0.pubkey < $1.pubkey }, admins: admins.values.sorted { $0.npub < $1.npub },
                                        sunsetAuthorEvidence: evidence.values.sorted { $0.pubkey < $1.pubkey }, updatedAt: now)
        var promoted = await applyingPromotions(previous.promotionClaims + incoming.filter { $0.kind == 30021 }, to: snapshot, now: now)
        for author in promoted.organicAdmins.compactMap(\.publicKey) {
            guard let event = latest[author], let list = try? entries(event), !list.isEmpty else { continue }
            evidence[author] = event
        }
        promoted.sunsetAuthorEvidence = evidence.values.sorted { $0.pubkey < $1.pubkey }
        if promoted.sunsetActive, !sunset {
            var peerAdmins: [String: AdminRegistryEntry] = [:]
            for author in accepted.union(promoted.organicAdmins.compactMap(\.publicKey)).subtracting([bootstrapKey]) {
                guard let event = latest[author], let list = try? entries(event) else { continue }
                for entry in list { peerAdmins[entry.npub] = entry }
            }
            promoted.admins = peerAdmins.values.sorted { $0.npub < $1.npub }
        }
        return promoted
    }
    public static func applyingPromotions(_ claims: [NostrEvent], to base: RegistrySnapshot, now: Int) async -> RegistrySnapshot {
        var snapshot = base, latest: [String: NostrEvent] = [:]
        snapshot.organicAdmins = []
        for claim in claims {
            guard claim.kind == 30021, claim.tags.contains(["d", "einundzwanzig-admin-claim"]), claim.createdAt <= now + 300,
                  await NostrCrypto().verify(claim) else { continue }
            if let old = latest[claim.pubkey], old.createdAt > claim.createdAt || (old.createdAt == claim.createdAt && old.id < claim.id) { continue }
            latest[claim.pubkey] = claim
        }
        var accepted: Set<String> = []
        for _ in 0..<16 {
            let count = accepted.count
            for claim in latest.values where !accepted.contains(claim.id) {
                guard let entry = await verifyPromotion(claim, registry: snapshot, now: now) else { continue }
                accepted.insert(claim.id)
                if !snapshot.admins.contains(where: { $0.npub == entry.npub }) { snapshot.organicAdmins.append(entry) }
            }
            if accepted.count == count { break }
        }
        snapshot.promotionClaims = latest.values.filter { accepted.contains($0.id) }.sorted { $0.pubkey < $1.pubkey }
        snapshot.organicAdmins.sort { $0.npub < $1.npub }
        return snapshot
    }
    public static func listContent(_ entries: [AdminRegistryEntry], date: Date) -> String {
        BadgeWire.ordered([("admins", .array(entries.filter { !$0.isLegacyOrganic }.map(\.json))), ("updated_at", .string(BadgeWire.isoDate(date)))])
    }
    public static func verifyPromotion(_ claim: NostrEvent, registry: RegistrySnapshot, now: Int) async -> AdminRegistryEntry? {
        guard claim.kind == 30021, claim.tags.contains(["d", "einundzwanzig-admin-claim"]), claim.createdAt <= now + 300,
              now - claim.createdAt <= 180 * 86400, await NostrCrypto().verify(claim),
              let data = try? BadgeWire.parse(claim.content), data.object["type"]?.string == "admin_claim" else { return nil }
        var sessions: Set<String> = [], signatures: Set<String> = [], count = 0
        for proof in data.object["proofs"]?.array ?? [] {
            let p = proof.object
            guard let source = try? await BadgeSecurity.verify(p["sig_content"]?.string ?? "", now: now, checkExpiry: false, checkRolling: false),
                  registry.isAuthorized(source.event.pubkey), source.event.pubkey == p["admin_pubkey"]?.string,
                  source.event.sig == p["sig"]?.string,
                  source.event.id == p["sig_id"]?.string || (p["sig_id"]?.string == "" && source.payload["p"] != nil),
                  signatures.insert(source.event.sig).inserted else { continue }
            var badge = CollectedBadge(id: source.event.id, meetupName: source.meetupName, date: BadgeWire.isoDate(Date(timeIntervalSince1970: Double(source.event.createdAt))))
            badge.sig = source.event.sig; badge.sigId = p["sig_id"]?.string ?? ""; badge.adminPubkey = source.event.pubkey
            badge.blockHeight = p["block"]?.integer ?? 0; badge.claimSig = p["claim_sig"]?.string ?? ""
            badge.claimEventId = p["claim_event_id"]?.string ?? ""; badge.claimTimestamp = p["claim_timestamp"]?.integer ?? 0
            badge.claimPubkey = claim.pubkey
            guard let binding = try? BadgeClaim.event(for: badge), await NostrCrypto().verify(binding) else { continue }
            sessions.insert(source.event.pubkey + "|" + source.meetupID + "|" + String(source.event.createdAt / 86400)); count += 1
        }
        guard count >= 3, sessions.count >= 3, let npub = try? NIP19.encodePublicKey(claim.pubkey) else { return nil }
        return AdminRegistryEntry(npub: npub, meetup: data.object["meetup"]?.string ?? "", name: "Verified promotion", addedAt: claim.createdAt)
    }
}

import Foundation
import CryptoSwift

public struct VerifiedBadgeSource: Sendable {
    public let event: NostrEvent
    public let payload: [String: BadgeJSON]
    public let meetupID: String
    public let meetupName: String
    public let eventAddress: String?
    public let blockHeight: Int
    public var identityMeetupID: String { eventAddress == nil ? BadgeSecurity.normalizedMeetupID(meetupID) : meetupID }
}

public enum BadgeSecurity {
    public static let compactSignedKeys: Set<String> = ["v", "t", "m", "b", "x"]
    public static func sourceEvent(_ data: [String: BadgeJSON]) throws -> NostrEvent {
        guard (data["v"]?.integer ?? 1) >= 2 else { throw BadgeProtocolError.unsupportedVersion }
        if data["p"] != nil, data["s"] != nil, data["sig"] == nil {
            guard let created = data["c"]?.integer, created > 0,
                  let meetup = data["m"], case .string = meetup, data["t"]?.string == "B",
                  data["b"] == nil || (data["b"]?.integer ?? -1) >= 0,
                  data["x"] == nil || (data["x"]?.integer ?? -1) >= 0 else { throw BadgeProtocolError.malformed }
            let content = BadgeWire.canonical(data.filter { compactSignedKeys.contains($0.key) })
            let unsigned = try UnsignedNostrEvent(pubkey: data["p"]?.string ?? "", createdAt: created, kind: 21000,
                                                  tags: [["t", "badge"], ["m", meetup.string]], content: content)
            return try NostrEvent(unsigned: unsigned, id: NostrHex.encode(try unsigned.eventID()), sig: data["s"]?.string ?? "")
        }
        guard let created = data["created_at"]?.integer, created > 0 else { throw BadgeProtocolError.malformed }
        let excluded: Set<String> = ["sig", "sig_id", "admin_pubkey", "_verified_by", "created_at"]
        let content = BadgeWire.canonical(data.filter { !excluded.contains($0.key) })
        return try NostrEvent(id: data["sig_id"]?.string ?? "", pubkey: data["admin_pubkey"]?.string ?? "", createdAt: created,
                              kind: 21000, tags: [["t", (data["type"]?.string ?? "").lowercased()],
                                                 ["meetup", data["meetup_id"]?.string ?? ""],
                                                 ["block", String(data["block_height"]?.integer ?? 0)]],
                              content: content, sig: data["sig"]?.string ?? "")
    }
    public static func verify(_ text: String, now: Int = Int(Date().timeIntervalSince1970),
                              checkExpiry: Bool = true, checkRolling: Bool = true) async throws -> VerifiedBadgeSource {
        let data = try BadgeWire.parse(text, limit: 65_536).object
        return try await verify(data, now: now, checkExpiry: checkExpiry, checkRolling: checkRolling)
    }
    public static func verify(_ data: [String: BadgeJSON], now: Int,
                              checkExpiry: Bool = true, checkRolling: Bool = true) async throws -> VerifiedBadgeSource {
        let event = try sourceEvent(data)
        guard await NostrCrypto().verify(event) else { throw BadgeProtocolError.invalidSignature }
        guard event.createdAt <= now + 300 else { throw BadgeProtocolError.malformed }
        let expiry = data["x"]?.integer ?? data["expires_at"]?.integer ?? 0
        if checkExpiry, expiry > 0, now > expiry { throw BadgeProtocolError.expired }
        if checkRolling { try validateRolling(data, now: now) }
        let compact = data["p"] != nil && data["sig"] == nil
        let rawID = compact ? data["m"]?.string ?? "" : data["meetup_id"]?.string ?? ""
        var meetupID = rawID, name = compact ? displayName(rawID) : data["meetup_name"]?.string ?? rawID
        var address: String?
        if rawID.hasPrefix("evt:") {
            let p = rawID.components(separatedBy: ":")
            if p.count >= 5 {
                address = p[1...3].joined(separator: ":"); meetupID = "evt:" + p[3]
                name = displayName(p.dropFirst(4).joined(separator: ":"), countrySuffix: false)
            }
        }
        guard !meetupID.isEmpty else { throw BadgeProtocolError.malformed }
        return VerifiedBadgeSource(event: event, payload: data, meetupID: meetupID, meetupName: name,
                                   eventAddress: address, blockHeight: data["b"]?.integer ?? data["block_height"]?.integer ?? 0)
    }
    /// Flutter checks time proximity only; the scanner does not know the HMAC seed.
    public static func validateRolling(_ data: [String: BadgeJSON], now: Int) throws {
        let nonce = data["n"] ?? data["qr_nonce"]
        let rawStep = data["ts"] ?? data["qr_time_step"]
        guard let nonce, let rawStep else { return }
        guard case .string = nonce, let step = rawStep.integer else { throw BadgeProtocolError.malformed }
        guard step >= 0, abs(now / 10 - step) <= 1 else { throw BadgeProtocolError.staleQR }
    }
    public static func signCompact(meetupID: String, blockHeight: Int, expiresAt: Int,
                                   signer: any NostrSigner, createdAt: Int) async throws -> [String: BadgeJSON] {
        var payload: [String: BadgeJSON] = ["v": .int(2), "t": .string("B"), "m": .string(meetupID), "b": .int(blockHeight), "x": .int(expiresAt)]
        let content = BadgeWire.canonical(payload)
        let event = try await signer.sign(kind: 21000, tags: [["t", "badge"], ["m", meetupID]], content: content, createdAt: createdAt)
        try await requireSigned(event, signer: signer, kind: 21000, tags: [["t", "badge"], ["m", meetupID]], content: content, createdAt: createdAt)
        payload["c"] = .int(event.createdAt); payload["p"] = .string(event.pubkey); payload["s"] = .string(event.sig)
        return payload
    }
    public static func requireSigned(_ event: NostrEvent, signer: any NostrSigner, kind: Int,
                                     tags: [[String]], content: String, createdAt: Int) async throws {
        let key = try await signer.publicKey()
        guard event.pubkey == key, event.kind == kind, event.createdAt == createdAt, event.tags == tags,
              event.content.utf8.elementsEqual(content.utf8), await NostrCrypto().verify(event) else { throw BadgeProtocolError.invalidSignature }
    }
    public static func verifyStored(_ badge: CollectedBadge, collector: String?, now: Int) async -> Bool {
        guard badge.sigVersion == 2,
              let source = try? await verify(badge.sigContent, now: now, checkExpiry: false, checkRolling: false),
              source.event.pubkey == badge.adminPubkey, source.event.sig == badge.sig,
              source.event.id == badge.sigId || (badge.sigId.isEmpty && source.payload["p"] != nil && source.payload["sig"] == nil) else { return false }
        if let collector {
            guard collector == badge.claimPubkey, badge.claimTimestamp > 0, badge.claimTimestamp <= now + 300,
                  let claim = try? BadgeClaim.event(for: badge), await NostrCrypto().verify(claim) else { return false }
        }
        return true
    }
    public static func meetupID(city: String, country: String) -> String {
        let slug = city.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().replacingOccurrences(of: " ", with: "-")
        return validCountry(country) ? slug + "-" + country.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() : slug
    }
    public static func normalizedMeetupID(_ signedID: String) -> String {
        let p = signedID.components(separatedBy: "-")
        return p.count > 1 && validCountry(p.last ?? "") ? p.dropLast().joined(separator: "-") : signedID
    }
    private static func validCountry(_ country: String) -> Bool {
        let c = country.trimmingCharacters(in: .whitespacesAndNewlines)
        return c.count == 2 && Locale.Region.isoRegions.contains { $0.identifier.lowercased() == c.lowercased() }
    }
    private static func displayName(_ slug: String, countrySuffix: Bool = true) -> String {
        var words = slug.components(separatedBy: "-")
        var country = ""
        if countrySuffix, let last = words.last, validCountry(last) { country = last.uppercased(); words.removeLast() }
        return words.filter { !$0.isEmpty }.map { String($0.prefix(1)).uppercased() + $0.dropFirst() }.joined(separator: " ") + (country.isEmpty ? "" : ", " + country)
    }
}

public enum BadgeClaim {
    public static func content(for badge: CollectedBadge, timestamp: Int) -> String {
        BadgeWire.canonical(["action": .string("claim_badge"), "org_sig": .string(badge.sig),
                             "org_event_id": .string(badge.sigId), "org_pubkey": .string(badge.adminPubkey),
                             "block_height": .int(badge.blockHeight), "claimed_at": .int(timestamp)])
    }
    public static func tags(for badge: CollectedBadge) -> [[String]] {
        [["t", "badge_claim"], ["p", badge.adminPubkey], ["block", String(badge.blockHeight)]]
    }
    public static func event(for badge: CollectedBadge) throws -> NostrEvent {
        try NostrEvent(id: badge.claimEventId, pubkey: badge.claimPubkey, createdAt: badge.claimTimestamp,
                       kind: 21002, tags: tags(for: badge), content: content(for: badge, timestamp: badge.claimTimestamp), sig: badge.claimSig)
    }
    public static func bind(_ badge: CollectedBadge, signer: any NostrSigner, timestamp: Int, retroactive: Bool = false) async throws -> CollectedBadge {
        guard await BadgeSecurity.verifyStored(badge, collector: nil, now: timestamp) else { throw BadgeProtocolError.invalidSignature }
        let content = content(for: badge, timestamp: timestamp), tags = tags(for: badge)
        let event = try await signer.sign(kind: 21002, tags: tags, content: content, createdAt: timestamp)
        try await BadgeSecurity.requireSigned(event, signer: signer, kind: 21002, tags: tags, content: content, createdAt: timestamp)
        var bound = badge
        bound.claimSig = event.sig; bound.claimEventId = event.id; bound.claimPubkey = event.pubkey
        bound.claimTimestamp = timestamp; bound.isRetroactive = retroactive
        return bound
    }
}

public struct BadgeOrganizerSession: Codable, Equatable, Sendable {
    public let owner: String
    public let meetupID: String
    public let expiresAt: Int
    public let payload: [String: BadgeJSON]
    /// Stored only inside the encrypted wallet, never in a QR or sync event.
    public let seed: String
    public init(owner: String, meetupID: String, expiresAt: Int, payload: [String: BadgeJSON], seed: String = UUID().uuidString + UUID().uuidString) {
        self.owner = owner; self.meetupID = meetupID; self.expiresAt = expiresAt; self.payload = payload; self.seed = seed
    }
    public func qr(now: Int) throws -> String {
        guard now <= expiresAt else { throw BadgeProtocolError.expired }
        let step = now / 10
        let digest = try HMAC(key: Array(seed.utf8), variant: .sha2(.sha256)).authenticate(Array(String(step).utf8))
        var qr = payload
        qr["n"] = .string(NostrHex.encode(Data(digest)).prefix(16).description)
        qr["ts"] = .int(step); qr["d"] = .string("rolling_qr")
        return BadgeWire.canonical(qr)
    }
}

import Foundation

public struct SocialAttendance: Codable, Sendable, Equatable, Identifiable {
    public let event: NostrEvent
    public let sessionKey: String
    public let meetup: String
    public let attendedAt: Int
    public var id: String { event.id }
    public var isEvent: Bool { sessionKey.hasPrefix("ev|") }
}
public struct SocialGraphSnapshot: Codable, Sendable, Equatable {
    public var follows: [String: Set<String>] = [:]
    public var attendance: [SocialAttendance] = []
    public var sourceEvents: [NostrEvent] = []
    public var owner = ""
    public var updatedAt = 0
    public var fullSyncAt = 0
    public var knownAuthors: Set<String> = []
    public var knownKeys: Set<String> = []
    public var stale = false
    public var truncated = false
    public init() {}
    public func mutualContacts(_ a: String, _ b: String) -> Set<String> { (follows[a] ?? []).intersection(follows[b] ?? []) }
    public func sharedMeetups(_ a: String, _ b: String, events: Bool = false) -> Set<String> {
        let left = Set(attendance.filter { $0.event.pubkey == a && $0.isEvent == events }.map(\.sessionKey))
        let right = Set(attendance.filter { $0.event.pubkey == b && $0.isEvent == events }.map(\.sessionKey))
        return SocialNetwork.sharedKeys(left, right)
    }
    public func score(_ a: String, _ b: String, organizers: Set<String>) -> Double {
        let mine = follows[a] ?? [], theirs = follows[b] ?? []
        let relationship = mine.contains(b) && theirs.contains(a) ? 1.0 : (mine.contains(b) || theirs.contains(a) ? 0.3 : 0)
        let common = Double(mine.intersection(theirs).count)
        let endorsements = organizers.filter { follows[$0]?.contains(b) == true }.count
        return min(3, relationship + common / (common + 5) + min(1, Double(endorsements) * 0.5))
    }
    public func network(maximumDepth: Int = 3) -> SocialObservedNetwork { SocialNetwork.network(self, owner: owner, maximumDepth: maximumDepth) }
}

public enum SocialNetwork {
    /// Resolve an address for a person lookup. A signed reputation QR must
    /// authenticate its embedded identity before it can select a person.
    public static func personPublicKey(_ input: String, now: Int) async throws -> String {
        var text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.hasPrefix("21v3:") { return try await ReputationCard.verifyQR(text, now: now).event.pubkey }
        if text.lowercased().hasPrefix("nostr:") { text = String(text.dropFirst(6)) }
        if text.lowercased().hasPrefix("npub1") { return try NIP19.decodePublicKey(text.lowercased()) }
        _ = try NostrHex.decode(text, byteCount: 32, field: "person")
        return text.lowercased()
    }
    public static func attendanceKey(_ badge: CollectedBadge) -> String {
        if badge.isEvent { return "ev|" + badge.meetupEventId }
        let npub = badge.signerNpub.trimmingCharacters(in: .whitespacesAndNewlines)
        return badge.meetupEventId + (npub.isEmpty ? "" : "@" + npub.suffix(12))
    }
    public static func sharedKeys(_ a: Set<String>, _ b: Set<String>) -> Set<String> {
        var out: Set<String> = []
        for left in a {
            let lb = left.components(separatedBy: "@")[0]
            for right in b {
                let rb = right.components(separatedBy: "@")[0]
                if left == right { out.insert(left) }
                else if lb == rb, !left.contains("@") || !right.contains("@") { out.insert(lb) }
            }
        }
        return out
    }
    public static func ingest(_ events: [NostrEvent], now: Int, filter: NostrFilter? = nil) async -> SocialGraphSnapshot {
        var snapshot = SocialGraphSnapshot(), latest: [String: NostrEvent] = [:]
        for event in events {
            guard [3,30079].contains(event.kind), filter?.matches(event) != false, event.createdAt <= now + 300, await NostrCrypto().verify(event) else { continue }
            let d = event.kind == 30079 ? event.tags.first { $0.count > 1 && $0[0] == "d" }?[1] ?? "" : ""
            let key = event.pubkey + ":\(event.kind):" + d
            if let old = latest[key], old.createdAt > event.createdAt || (old.createdAt == event.createdAt && old.id < event.id) { continue }
            latest[key] = event
        }
        for event in latest.values {
            if event.kind == 3 {
                snapshot.follows[event.pubkey] = Set(event.tags.compactMap { t in
                    t.count > 1 && t[0] == "p" && (try? NostrHex.decode(t[1], byteCount: 32, field: "p")) != nil ? t[1] : nil
                }); continue
            }
            guard let map = try? BadgeWire.parse(event.content).object, let key = map["event"]?.string, !key.isEmpty,
                  let t = map["t"]?.integer, t >= 0, t <= now + 300, event.tags.contains(["d", key]),
                  key.hasPrefix("ev|") || key.components(separatedBy: "@")[0].range(of: #"^.+-\d{4}-\d{2}-\d{2}$"#, options: .regularExpression) != nil,
                  event.tags.contains(where: { $0.count > 1 && $0[0] == "e_ref" && (try? NostrHex.decode($0[1], byteCount: 32, field: "e_ref")) != nil }) else { continue }
            // This is an authenticated self-attestation. Its e_ref alone is
            // not proof of a badge or a source of organizer authority.
            snapshot.attendance.append(SocialAttendance(event: event, sessionKey: key, meetup: map["meetup"]?.string ?? "", attendedAt: t))
        }
        snapshot.attendance.sort { $0.id < $1.id }
        snapshot.sourceEvents = latest.values.sorted { $0.id < $1.id }
        snapshot.updatedAt = now
        return snapshot
    }
    public static func signedAttendance(_ badge: CollectedBadge, signer: any NostrSigner, now: Int) async throws -> NostrEvent {
        let collector = try await signer.publicKey()
        guard !badge.meetupEventId.isEmpty, await BadgeSecurity.verifyStored(badge, collector: collector, now: now) else { throw BadgeProtocolError.invalidClaim }
        let key = attendanceKey(badge)
        let content = BadgeWire.ordered([("event", .string(key)), ("meetup", .string(badge.meetupName)), ("t", .int(Int(badge.dateValue.timeIntervalSince1970)))])
        let tags = [["d", key], ["e_ref", badge.sigId], ["client", "einundzwanzig-meetup-app"]]
        let event = try await signer.sign(kind: 30079, tags: tags, content: content, createdAt: now)
        try await BadgeSecurity.requireSigned(event, signer: signer, kind: 30079, tags: tags, content: content, createdAt: now)
        return event
    }
}

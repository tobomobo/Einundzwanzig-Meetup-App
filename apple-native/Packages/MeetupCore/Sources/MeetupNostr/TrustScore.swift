import Foundation

public struct TrustThresholds: Sendable, Equatable {
    public let phase: String
    public let badges: Int
    public let meetups: Int
    public let signers: Int
    public let days: Int
    public let score: Double
    public static func forSigners(_ count: Int) -> Self {
        if count <= 1 { Self(phase: "Keimphase", badges: 3, meetups: 2, signers: 1, days: 14, score: 5) }
        else if count <= 5 { Self(phase: "Wachstum", badges: 4, meetups: 3, signers: 2, days: 30, score: 10) }
        else { Self(phase: "Stabil", badges: 5, meetups: 3, signers: 2, days: 60, score: 15) }
    }
}
public struct TrustScore: Sendable, Equatable {
    public var total: Double = 0
    public var activity: Double = 0
    public var diversity: Double = 0
    public var maturity: Double = 0
    public var quality: Double = 0
    public var badgeCount = 0
    public var meetupCount = 0
    public var signerCount = 0
    public var accountAgeDays = 0
    public var trustedBadgeIDs: Set<String> = []
    public var rejectedBadgeIDs: Set<String> = []
    public var verifiedBadges: [CollectedBadge] = []
    public var thresholds = TrustThresholds.forSigners(0)
    public var canOrganize: Bool {
        badgeCount >= thresholds.badges && meetupCount >= thresholds.meetups && signerCount >= thresholds.signers &&
        accountAgeDays >= thresholds.days && total.rounded() >= thresholds.score
    }
    public var level: String {
        if total >= 40 { "VETERAN" } else if total >= 20 { "ETABLIERT" } else if total >= 10 { "AKTIV" } else if total >= 3 { "STARTER" } else { "NEU" }
    }
    public init() {}
}
public struct TrustCoAttestors: Sendable {
    public var attendees: Int
    public var veterans: Int
    public init(attendees: Int = 1, veterans: Int = 0) { self.attendees = max(1, attendees); self.veterans = max(0, veterans) }
}

public enum TrustEvaluator {
    /// Registry authority and both signatures are verified before any numeric
    /// score is calculated. Shape-only wallet flags never imply trust.
    public static func evaluate(_ badges: [CollectedBadge], collector: String, registry: RegistrySnapshot,
                                eventAuthorities: Set<String> = [], coAttestors: [String: TrustCoAttestors] = [:], now: Int) async -> TrustScore {
        var score = TrustScore(), accepted: [(CollectedBadge, VerifiedBadgeSource)] = [], seen: Set<String> = []
        for badge in badges.sorted(by: { $0.dateValue < $1.dateValue }) where !badge.isOrganizer {
            guard await BadgeSecurity.verifyStored(badge, collector: collector, now: now),
                  let source = try? await BadgeSecurity.verify(badge.sigContent, now: now, checkExpiry: false, checkRolling: false),
                  registry.isAuthorized(source.event.pubkey) || eventAuthorities.contains(source.event.id) else {
                score.rejectedBadgeIDs.insert(badge.id); continue
            }
            let identity = source.meetupID + "|" + String(source.event.createdAt / 86400)
            guard seen.insert(identity).inserted else { continue }
            accepted.append((badge, source)); score.trustedBadgeIDs.insert(badge.id)
        }
        score.badgeCount = accepted.count
        score.verifiedBadges = accepted.map { $0.0 }
        let signers = Set(accepted.map { $0.1.event.pubkey })
        let meetups = Set(accepted.filter { $0.1.eventAddress == nil }.map { $0.1.meetupID })
        score.meetupCount = meetups.count; score.signerCount = signers.count
        score.thresholds = .forSigners(signers.count)
        guard !accepted.isEmpty else { return score }
        let first = accepted.map { $0.1.event.createdAt }.min() ?? now
        score.accountAgeDays = max(0, (now - first) / 86400)
        score.maturity = min(1, Double(score.accountAgeDays) / 180)
        let verified = Set(accepted.filter { $0.0.presenceVerified && $0.1.eventAddress == nil }.map { $0.1.meetupID })
        let effective = Double(verified.count) + 0.5 * Double(meetups.subtracting(verified).count)
        score.diversity = log2(effective + 1) * log2(Double(max(1, signers.count)) + 1) * 1.5
        var weekly: [Int: Int] = [:], qualitySum: Double = 0
        let epoch2024 = 1_704_067_200
        for (badge, source) in accepted.sorted(by: { $0.1.event.createdAt > $1.1.event.createdAt }) {
            let week = (source.event.createdAt - epoch2024) / (7 * 86400)
            weekly[week, default: 0] += 1
            guard weekly[week, default: 0] <= 2 else { continue }
            let co = coAttestors[badge.meetupEventId] ?? TrustCoAttestors()
            let bonus = 1 + Double(co.veterans) * 0.3
            let ageWeeks = Double(max(0, (now - source.event.createdAt) / 86400)) / 7
            let decay = pow(0.5, ageWeeks / 26)
            score.activity += log2(Double(co.attendees) + 1) * bonus * decay * (badge.presenceVerified ? 1 : 0.7)
            qualitySum += bonus
        }
        score.quality = qualitySum / Double(accepted.count) * 1.2
        score.total = score.activity * (1 + score.maturity) * (1 + score.diversity * 0.1)
        return score
    }
}

public enum BadgeProofHash {
    public static func v2(_ badges: [CollectedBadge]) -> String {
        let bound = badges.filter { !$0.claimSig.isEmpty && !$0.claimPubkey.isEmpty && !$0.sig.isEmpty && $0.sigVersion > 0 }
            .enumerated().sorted { $0.element.claimTimestamp == $1.element.claimTimestamp ? $0.offset < $1.offset : $0.element.claimTimestamp < $1.element.claimTimestamp }.map(\.element)
        guard !bound.isEmpty else { return "" }
        return BadgeWire.sha256(bound.map(\.claimProofId).joined(separator: "|"))
    }
    public static func v1(_ badges: [CollectedBadge]) -> String {
        guard !badges.isEmpty else { return "" }
        return BadgeWire.sha256(badges.sorted { $0.dateValue < $1.dateValue }.map { b in
            if !b.sigId.isEmpty { return b.sigId }
            if !b.sig.isEmpty { return String(b.sig.prefix(64)) }
            return BadgeWire.sha256("\(b.id)-\(b.meetupName)-\(b.date)-\(b.blockHeight)")
        }.joined(separator: "|"))
    }
}

import Foundation

public struct VerifiedReputationCard: Sendable {
    public let event: NostrEvent
    public let payload: [String: BadgeJSON]
    public let nickname: String
    public let advertisedBadges: Int
    public let advertisedScore: Double
    public let badgeProof: String
    public let proofVersion: Int
    /// Signature verification authenticates the author's statement. Badge
    /// ownership/issuer authority requires the separately presented badges.
    public let badgeEvidenceVerified: Bool
}

public enum ReputationCard {
    public static let dTag = "einundzwanzig-reputation"
    public static func relayContent(badges: [CollectedBadge], score: TrustScore, nickname: String,
                                    platformProofs: [PlatformIdentityProof], humanity: HumanityReceipt?, now: Int) -> String {
        let oldest = badges.map(\.date).min() ?? ""
        let bound = badges.filter { !$0.claimSig.isEmpty }
        let basic = min(10, min(3, Double(bound.count) * 0.3) + min(2, Double(score.meetupCount) * 0.5) +
                        min(2, Double(score.signerCount) * 0.7) + min(2, Double(score.accountAgeDays) / 30 * 0.3) + (bound.count == badges.count && !badges.isEmpty ? 1 : 0))
        let level = basic >= 8 ? "VETERAN" : (basic >= 5 ? "ETABLIERT" : (basic >= 2 ? "AKTIV" : (basic > 0 ? "STARTER" : "NEU")))
        let stats: BadgeJSON = .object(["score": .number((basic * 10).rounded() / 10), "level": .string(level),
                                      "total_badges": .int(badges.count), "verified_badges": .int(badges.count),
                                      "bound_badges": .int(bound.count), "retroactive_badges": .int(bound.filter(\.isRetroactive).count),
                                      "meetup_count": .int(score.meetupCount), "signer_count": .int(score.signerCount),
                                      "account_age_days": .int(score.accountAgeDays), "since": .string(String(oldest.prefix(7)))])
        let v2 = BadgeProofHash.v2(badges)
        var fields: [(String, BadgeJSON)] = [("version", .int(2)), ("identity", .object(["nickname": .string(nickname.isEmpty ? "Anon" : nickname)])),
                                            ("stats", stats), ("proof", .object(["badge_proof_hash": .string(v2.isEmpty ? BadgeProofHash.v1(badges) : v2), "proof_version": .int(v2.isEmpty ? 1 : 2)])),
                                            ("updated_at", .int(now))]
        if !platformProofs.isEmpty { fields.append(("platform_proofs", .object(Dictionary(uniqueKeysWithValues: platformProofs.map { ($0.platform, $0.json) })))) }
        if let humanity { fields.append(("humanity_proof", humanity.publicSummary)) }
        return BadgeWire.ordered(fields)
    }
    public static func qrPayload(badges: [CollectedBadge], score: TrustScore, nickname: String, publicKey: String,
                                 platformProofs: [PlatformIdentityProof], now: Int) throws -> String {
        let names = Array(Set(badges.filter { !$0.isEvent }.map(\.meetupName))).sorted()
        let v2 = BadgeProofHash.v2(badges), hash = v2.isEmpty ? BadgeProofHash.v1(badges) : v2
        let rp: BadgeJSON = .object(["sc": .number((score.total * 10).rounded() / 10), "lv": .string(score.level),
                                   "bc": .int(badges.count), "vc": .int(badges.count), "mc": .int(score.meetupCount),
                                   "si": .int(score.signerCount), "ad": .int(score.accountAgeDays), "bb": .int(badges.count),
                                   "ml": .array(names.prefix(10).map { .string(String(BadgeWire.sha256("21meetup:" + $0).prefix(12))) })])
        var fields: [(String, BadgeJSON)] = [("v", .int(6)), ("id", .object(["n": .string(nickname.isEmpty ? "Anon" : nickname), "np": .string(try NIP19.encodePublicKey(publicKey))])),
                                            ("rp", rp), ("pf", .object(["bp": .string(hash), "pv": .int(v2.isEmpty ? 1 : 2),
                                                                       "vc": .int(badges.count), "tc": .int(badges.count), "bb": .int(badges.count)]))]
        if !platformProofs.isEmpty {
            fields.append(("pp", .object(Dictionary(uniqueKeysWithValues: platformProofs.map { ($0.platform, .object(["u": .string($0.username), "s": .string($0.proofSig)])) }))))
        }
        fields.append(("t", .int(now * 1000)))
        return BadgeWire.ordered(fields)
    }
    public static func signQR(_ payload: String, signer: any NostrSigner, now: Int) async throws -> String {
        let event = try await signer.sign(kind: 21001, tags: [], content: payload, createdAt: now)
        try await BadgeSecurity.requireSigned(event, signer: signer, kind: 21001, tags: [], content: payload, createdAt: now)
        return "21v3:" + Data(payload.utf8).base64EncodedString() + ".\(event.sig).\(event.id).\(event.createdAt).\(event.pubkey)"
    }
    public static func verifyQR(_ text: String, badges: [CollectedBadge]? = nil, registry: RegistrySnapshot = RegistrySnapshot(),
                                authorizedSources: Set<String> = [], now: Int) async throws -> VerifiedReputationCard {
        guard text.hasPrefix("21v3:"), text.utf8.count <= 262_144 else { throw BadgeProtocolError.unsupportedVersion }
        let p = text.dropFirst(5).split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        guard p.count == 5, let data = Data(base64Encoded: p[0]), let content = String(data: data, encoding: .utf8), let createdAt = Int(p[3]) else { throw BadgeProtocolError.malformed }
        let event = try NostrEvent(id: p[2], pubkey: p[4], createdAt: createdAt, kind: 21001, tags: [], content: content, sig: p[1])
        return try await verifyEvent(event, badges: badges, registry: registry, authorizedSources: authorizedSources, now: now)
    }
    public static func verifyEvent(_ event: NostrEvent, badges: [CollectedBadge]? = nil,
                                    registry: RegistrySnapshot = RegistrySnapshot(), authorizedSources: Set<String> = [], now: Int) async throws -> VerifiedReputationCard {
        guard (event.kind == 21001 && event.tags.isEmpty) || (event.kind == 30078 && event.tags.contains(["d", dTag])),
              event.createdAt <= now + 300, await NostrCrypto().verify(event) else { throw BadgeProtocolError.invalidSignature }
        let p = try BadgeWire.parse(event.content).object, qr = event.kind == 21001
        let identity = p[qr ? "id" : "identity"]?.object ?? [:]
        if let npub = identity["np"]?.string, !npub.isEmpty {
            guard try NIP19.decodePublicKey(npub) == event.pubkey else { throw BadgeProtocolError.invalidSignature }
        }
        let stats = p[qr ? "rp" : "stats"]?.object ?? [:], proof = p[qr ? "pf" : "proof"]?.object ?? [:]
        let hash = proof[qr ? "bp" : "badge_proof_hash"]?.string ?? "", version = proof[qr ? "pv" : "proof_version"]?.integer ?? 1
        let count = stats[qr ? "bc" : "total_badges"]?.integer ?? 0
        var verified = false
        if let badges {
            let score = await TrustEvaluator.evaluate(badges, collector: event.pubkey, registry: registry, eventAuthorities: authorizedSources, now: now)
            let trusted = score.verifiedBadges
            let actual = version == 2 ? BadgeProofHash.v2(trusted) : BadgeProofHash.v1(trusted)
            verified = !hash.isEmpty && hash == actual && trusted.count == count && score.rejectedBadgeIDs.isEmpty
        }
        return VerifiedReputationCard(event: event, payload: p, nickname: identity[qr ? "n" : "nickname"]?.string ?? "Anon",
                                      advertisedBadges: count, advertisedScore: stats[qr ? "sc" : "score"]?.double ?? 0,
                                      badgeProof: hash, proofVersion: version, badgeEvidenceVerified: verified)
    }
}

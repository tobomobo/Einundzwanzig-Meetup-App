import Foundation
import Testing
@testable import MeetupNostr

private func badgeGolden() throws -> [String: BadgeJSON] {
    try BadgeWire.parse(String(decoding: fixtureData("BadgeDartGolden.json"), as: UTF8.self)).object
}
private func goldenBadge() throws -> CollectedBadge {
    let g = try badgeGolden(), source = g["compactBadge"]!.object, claim = g["claim"]!.object
    var b = CollectedBadge(id: "fixture", meetupName: "Bad Neuenahr, DE", date: "2025-10-09T08:55:23.000Z")
    b.sig = source["sig"]!.string; b.adminPubkey = fixturePublicKey; b.sigVersion = 2
    b.sigContent = source["base_payload_json"]!.string; b.sigId = ""; b.blockHeight = 917001
    b.claimSig = claim["sig"]!.string; b.claimEventId = claim["id"]!.string
    b.claimPubkey = g["keys"]!.object["attendee"]!.object["pub"]!.string; b.claimTimestamp = claim["created_at"]!.integer!
    b.meetupEventId = "bad-neuenahr-2025-10-09"
    return b
}

struct BadgeProtocolTests {
    @Test func exactDartTopLevelCanonicalNestedOrderDoubleFormattingAndISO() throws {
        let g = try badgeGolden()
        for vector in g["canonicalJson"]!.array {
            #expect(BadgeWire.canonical(vector.object["input_insertion_order"]!.object) == vector.object["expected"]!.string)
        }
        for vector in g["dartIso8601"]!.array { #expect(BadgeWire.date(vector.object["expected"]!.string) != nil) }
        #expect(try BadgeWire.parse("100.0").integer == nil)
        #expect(try BadgeWire.parse("1e2").integer == nil)
        #expect(throws: (any Error).self) { try BadgeWire.parse(#"{"c":1,"c":2}"#) }
    }
    @Test func dartCompactAndCollectorGoldenVerifyAcrossFormats() async throws {
        let g = try badgeGolden(), compact = g["compactBadge"]!.object
        let source = try await BadgeSecurity.verify(compact["base_payload_json"]!.string, now: 1_760_000_123)
        #expect(source.event.id == compact["id"]!.string)
        #expect(source.event.content == compact["content"]!.string)
        #expect(String(decoding: try source.event.unsigned.canonicalSerialization(), as: UTF8.self) == compact["serialized_for_id"]!.string)
        let badge = try goldenBadge()
        #expect(BadgeClaim.content(for: badge, timestamp: badge.claimTimestamp) == g["claim"]!.object["content"]!.string)
        #expect(await BadgeSecurity.verifyStored(badge, collector: badge.claimPubkey, now: 1_800_000_000))
        #expect(badge.claimProofId == g["proofs"]!.object["claimProofId"]!.string)
        #expect(BadgeProofHash.v2([badge]) == g["proofs"]!.object["badgeProofV2_single_bound_badge"]!.string)
        #expect(BadgeProofHash.v1([badge]) == g["proofs"]!.object["badgeProofV1_single_badge_sigId_empty"]!.string)
        var changed = badge; changed.blockHeight += 1
        #expect(!(await BadgeSecurity.verifyStored(changed, collector: badge.claimPubkey, now: 1_800_000_000)))
        #expect(!(await BadgeSecurity.verifyStored(badge, collector: fixturePublicKey, now: 1_800_000_000)))
    }
    @Test func unsignedCoordinatesAndRollingNonceNeverEnterSignature() async throws {
        let g = try badgeGolden(), rolling = g["rollingQr"]!.object
        var map = try BadgeWire.parse(rolling["qr_string"]!.string).object
        map["la"] = .number(-80); map["lo"] = .number(170); map["n"] = .string("arbitrary")
        let source = try await BadgeSecurity.verify(map, now: 1_760_000_000)
        #expect(source.event.id == g["compactBadge"]!.object["id"]!.string)
        try BadgeSecurity.validateRolling(map, now: 1_760_000_010)
        #expect(throws: BadgeProtocolError.staleQR) { try BadgeSecurity.validateRolling(map, now: 1_760_000_020) }
        map["b"] = .int(1)
        await #expect(throws: BadgeProtocolError.invalidSignature) { try await BadgeSecurity.verify(map, now: 1_760_000_000) }
    }
    @Test func expiryLegacyAndMissingTimestampFailClosed() async throws {
        let g = try badgeGolden()
        var compact = try BadgeWire.parse(g["compactBadge"]!.object["base_payload_json"]!.string).object
        await #expect(throws: BadgeProtocolError.expired) { try await BadgeSecurity.verify(compact, now: 1_760_014_401) }
        compact.removeValue(forKey: "c")
        #expect(throws: (any Error).self) { try BadgeSecurity.sourceEvent(compact) }
        await #expect(throws: BadgeProtocolError.unsupportedVersion) { try await BadgeSecurity.verify(["v": .int(1), "sig": .string("dummy")], now: 1_760_000_000) }
    }
    @Test func dartRollingHMACOrganizerMatchesGoldenButScannerOnlyChecksTime() throws {
        let g = try badgeGolden(), rolling = g["rollingQr"]!.object
        let base = try BadgeWire.parse(g["compactBadge"]!.object["base_payload_json"]!.string).object
        let session = BadgeOrganizerSession(owner: fixturePublicKey, meetupID: "bad-neuenahr-de", expiresAt: 1_760_014_400, payload: base, seed: rolling["seed_hex_string"]!.string)
        let qr = try BadgeWire.parse(session.qr(now: 1_760_000_000)).object
        #expect(qr["n"]?.string == rolling["nonce"]?.string)
        #expect(qr["ts"]?.integer == 176_000_000)
        #expect(qr["la"] == nil)
    }
    @Test func dartReputationQRCryptoverifiesButDoesNotGrantBadgeEvidence() async throws {
        let g = try badgeGolden(), vector = g["reputationQrV3"]!.object
        let result = try await ReputationCard.verifyQR(vector["qr_string"]!.string, now: 1_760_000_200)
        #expect(result.event.id == vector["id"]!.string)
        #expect(!result.badgeEvidenceVerified)
        var bad = vector["qr_string"]!.string
        bad.replaceSubrange(bad.index(before: bad.endIndex)..., with: bad.last == "0" ? "1" : "0")
        await #expect(throws: (any Error).self) { try await ReputationCard.verifyQR(bad, now: 1_760_000_200) }
    }
    @Test func fullWalletWireRoundtripRetainsEveryFieldAndMergeIdentity() throws {
        var badge = try goldenBadge(); badge.isEvent = true; badge.presenceVerified = false; badge.coverUrl = "https://example.test/ä.png"; badge.lat = 48.2; badge.lng = 16.37
        let restored = try CollectedBadge(json: BadgeWire.parse(badge.wireJSON).object)
        #expect(restored == badge)
        let env = BadgeSyncEnvelope(index: 0, total: 1, generation: 123, badges: [badge])
        #expect(try BadgeSyncEnvelope.parse(env.json).badges == [badge])
        var duplicate = badge; duplicate.id = "other"; duplicate.claimSig = ""; duplicate.presenceVerified = true
        #expect(CollectedBadge.merge([badge], [duplicate]).count == 1)
        #expect(CollectedBadge.merge([badge], [duplicate])[0] == badge)
        duplicate.isOrganizer = true
        #expect(CollectedBadge.merge([badge], [duplicate]).count == 2)
    }
    @Test func gzipDynamicFixedStoredAndCorruptionVectors() throws {
        let fixture = try JSONDecoder().decode(BadgeJSON.self, from: fixtureData("BadgeGzipGolden.json")).object
        let expected = Data(fixture["plaintext"]!.string.utf8)
        for name in ["dynamic", "fixed"] {
            let compressed = try #require(Data(base64Encoded: fixture[name]!.string))
            #expect(try BadgeGzip.inflate(compressed) == expected)
            #expect(throws: BadgeProtocolError.oversized) { try BadgeGzip.inflate(compressed, limit: 100) }
            var corrupt = compressed; corrupt[corrupt.count - 8] ^= 1
            #expect(throws: (any Error).self) { try BadgeGzip.inflate(corrupt) }
        }
        #expect(try BadgeGzip.inflate(BadgeGzip.deflate(expected)) == expected)
        #expect(try BadgeGzip.inflate(BadgeGzip.deflate(Data())) == Data())
    }
    @Test func coherentSyncRejectsMissingMixedDuplicateAndInconsistentTotals() throws {
        let b = try goldenBadge()
        let zero = BadgeSyncEnvelope(index: 0, total: 2, generation: 2, badges: [b])
        let old = BadgeSyncEnvelope(index: 1, total: 2, generation: 1, badges: [b])
        #expect(throws: BadgeProtocolError.incompleteSync) { try BadgeSyncFormat.coherentSnapshot([zero, old]) }
        #expect(throws: BadgeProtocolError.incompleteSync) { try BadgeSyncFormat.coherentSnapshot([zero, zero]) }
        #expect(throws: BadgeProtocolError.incompleteSync) { try BadgeSyncFormat.coherentSnapshot([zero, BadgeSyncEnvelope(index: 1, total: 3, generation: 2, badges: [b])]) }
        #expect(try BadgeSyncFormat.coherentSnapshot([zero, BadgeSyncEnvelope(index: 1, total: 2, generation: 2, badges: [b])]).count == 1)
        #expect(try BadgeSyncFormat.coherentSnapshot([BadgeSyncEnvelope(index: 0, total: 1, generation: 3, badges: [b]), old]).count == 1)
    }
    @Test func cryptographicFlagsAndSpoofedAdminMetadataDoNotCreateTrust() async throws {
        let badge = try goldenBadge()
        let rejected = await TrustEvaluator.evaluate([badge], collector: badge.claimPubkey, registry: RegistrySnapshot(), now: 1_760_010_000)
        #expect(rejected.badgeCount == 0)
        let authorized = RegistrySnapshot(admins: [AdminRegistryEntry(npub: try NIP19.encodePublicKey(fixturePublicKey), meetup: "fixture")])
        let score = await TrustEvaluator.evaluate([badge, badge], collector: badge.claimPubkey, registry: authorized, now: 1_760_010_000)
        #expect(score.badgeCount == 1)
        var spoofed = badge; spoofed.sig = String(repeating: "1", count: 128)
        let failed = await TrustEvaluator.evaluate([spoofed], collector: badge.claimPubkey, registry: authorized, now: 1_760_010_000)
        #expect(failed.total == 0)
        var marker = badge; marker.isOrganizer = true
        let markerScore = await TrustEvaluator.evaluate([marker], collector: badge.claimPubkey, registry: authorized, now: 1_760_010_000)
        #expect(markerScore.badgeCount == 0)
    }
    @Test func registryRejectsUntrustedAuthorsAndLegacyOrganicAndHonorsRevocation() async throws {
        let root = try LocalIdentitySigner(privateKey: fixturePrivateKey), other = try LocalIdentitySigner(privateKey: Data(repeating: 0, count: 31) + Data([2]))
        let otherKey = try await other.publicKey(), npub = try NIP19.encodePublicKey(otherKey)
        let content = RegistryAuthority.listContent([AdminRegistryEntry(npub: npub, meetup: "Wien")], date: Date(timeIntervalSince1970: 100))
        let untrusted = try await other.sign(kind: 30078, tags: [["d", RegistryAuthority.dTag]], content: content, createdAt: 100)
        let bad = await RegistryAuthority.resolve([untrusted], previous: RegistrySnapshot(), now: 100, bootstrapKey: fixturePublicKey)
        #expect(bad.admins.isEmpty)
        let signed = try await root.sign(kind: 30078, tags: [["d", RegistryAuthority.dTag]], content: content, createdAt: 100)
        let good = await RegistryAuthority.resolve([signed], previous: RegistrySnapshot(), now: 100, bootstrapKey: fixturePublicKey)
        #expect(good.publicKeys == [otherKey]); #expect(!good.sunsetActive)
        let revoked = try await root.sign(kind: 30078, tags: [["d", RegistryAuthority.dTag]], content: RegistryAuthority.listContent([], date: Date(timeIntervalSince1970: 101)), createdAt: 101)
        let updated = await RegistryAuthority.resolve([revoked], previous: good, now: 101, bootstrapKey: fixturePublicKey)
        #expect(updated.admins.isEmpty)
        let organic = try await root.sign(kind: 30078, tags: [["d", RegistryAuthority.dTag]], content: RegistryAuthority.listContent([AdminRegistryEntry(npub: npub, meetup: "Wien", name: "Organic (3 Badges)")], date: Date()), createdAt: 102)
        let filtered = await RegistryAuthority.resolve([organic], previous: RegistrySnapshot(), now: 102, bootstrapKey: fixturePublicKey)
        #expect(filtered.admins.isEmpty); #expect(filtered.sunsetAuthorEvidence.isEmpty)
    }
    @Test func platformProofRequiresNestedSignatureAndExpectedUsername() async throws {
        let signer = try LocalIdentitySigner(privateKey: fixturePrivateKey)
        let proof = try await PlatformIdentityProof.create(platform: "telegram", username: "fixture_user", signer: signer, now: 100)
        let content = BadgeWire.canonical(["platform_proofs": .object(["telegram": proof.json])])
        let rep = try await signer.sign(kind: 30078, tags: [["d", ReputationCard.dTag]], content: content, createdAt: 101)
        let text = try proof.verifyString(publicKey: fixturePublicKey)
        #expect(try await PlatformIdentityProof.verify(text, reputation: rep, expectedUsername: "fixture_user", now: 102) == proof)
        await #expect(throws: (any Error).self) { try await PlatformIdentityProof.verify(text, reputation: rep, expectedUsername: "someone_else", now: 102) }
    }
    @Test func socialSessionSuffixAndLargeEventSeparation() throws {
        #expect(SocialNetwork.sharedKeys(["wien-2026-01-01@a"], ["wien-2026-01-01@b"]).isEmpty)
        #expect(SocialNetwork.sharedKeys(["wien-2026-01-01"], ["wien-2026-01-01@b"]) == ["wien-2026-01-01"])
        var badge = try goldenBadge(); badge.isEvent = true
        #expect(SocialNetwork.attendanceKey(badge).hasPrefix("ev|"))
    }
}

private struct FixtureProofHTTP: ProofHTTPClient {
    let value: BadgeJSON
    func get(_ url: URL) async throws -> BadgeJSON { value }
}
private func fixtureInvoice(description: String) -> String {
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

struct ProofAndAuthorityRegressionTests {
    @Test func twentyListedNamesCannotTriggerSunsetButDistinctAuthenticatedAuthorsCan() async throws {
        let root = try LocalIdentitySigner(privateKey: fixturePrivateKey)
        var peers: [LocalIdentitySigner] = [], entries: [AdminRegistryEntry] = []
        for index in 2...21 {
            let signer = try LocalIdentitySigner(privateKey: Data(repeating: 0, count: 31) + Data([UInt8(index)]))
            peers.append(signer); entries.append(AdminRegistryEntry(npub: try NIP19.encodePublicKey(await signer.publicKey()), meetup: "fixture"))
        }
        let rootEvent = try await root.sign(kind: 30078, tags: [["d", RegistryAuthority.dTag]], content: RegistryAuthority.listContent(entries, date: Date()), createdAt: 100)
        let initial = await RegistryAuthority.resolve([rootEvent], previous: RegistrySnapshot(), now: 100, bootstrapKey: fixturePublicKey)
        #expect(initial.admins.count == 20); #expect(!initial.sunsetActive)
        var signed = [rootEvent]
        for peer in peers {
            signed.append(try await peer.sign(kind: 30078, tags: [["d", RegistryAuthority.dTag]], content: RegistryAuthority.listContent([entries[0]], date: Date()), createdAt: 101))
        }
        let sunset = await RegistryAuthority.resolve(signed, previous: initial, now: 101, bootstrapKey: fixturePublicKey)
        #expect(sunset.sunsetActive); #expect(Set(sunset.sunsetAuthorEvidence.map(\.pubkey)).count == 21)
        let rogue = try NIP19.encodePublicKey(IdentityKey.publicKey(Data(repeating: 0, count: 31) + Data([22])))
        let rootAfterSunset = try await root.sign(kind: 30078, tags: [["d", RegistryAuthority.dTag]], content: RegistryAuthority.listContent([AdminRegistryEntry(npub: rogue, meetup: "rogue")], date: Date()), createdAt: 102)
        let result = await RegistryAuthority.resolve([rootAfterSunset], previous: sunset, now: 102, bootstrapKey: fixturePublicKey)
        #expect(result.sunsetActive); #expect(!result.admins.contains(where: { $0.npub == rogue }))
    }
    @Test func eventIssuerChainHonorsSignedFormerIssuerTimestampAndCreatorAuthority() async throws {
        let signer = try LocalIdentitySigner(privateKey: fixturePrivateKey)
        let other = try IdentityKey.publicKey(Data(repeating: 0, count: 31) + Data([2]))
        let event = try await signer.sign(kind: 31923, tags: [["d", "fixture-event"], ["badge", "true"], ["p", other, "", "former_issuer", "150"], ["g", "48.2", "16.37"]], content: "", createdAt: 100)
        let registry = RegistrySnapshot(admins: [AdminRegistryEntry(npub: try NIP19.encodePublicKey(fixturePublicKey), meetup: "fixture")])
        let address = "31923:\(fixturePublicKey):fixture-event"
        #expect(await BadgeEventAuthority.verify(address: address, signer: other, issuedAt: 149, event: event, registry: registry, now: 200))
        #expect(!(await BadgeEventAuthority.verify(address: address, signer: other, issuedAt: 151, event: event, registry: registry, now: 200)))
        #expect(!(await BadgeEventAuthority.verify(address: address, signer: other, issuedAt: 149, event: event, registry: RegistrySnapshot(), now: 200)))
    }
    @Test func humanityNeedsSignedRequestInvoiceBindingAndIndependentRecipientProvider() async throws {
        let recipient = try LocalIdentitySigner(privateKey: fixturePrivateKey)
        let sender = try LocalIdentitySigner(privateKey: Data(repeating: 0, count: 31) + Data([2]))
        let provider = try LocalIdentitySigner(privateKey: Data(repeating: 0, count: 31) + Data([3]))
        let request = try await sender.sign(kind: 9734, tags: [["p", fixturePublicKey]], content: "fixture zap", createdAt: 100)
        let description = String(decoding: try JSONEncoder().encode(request), as: UTF8.self)
        let invoice = fixtureInvoice(description: description)
        #expect(try ProofVerifier.descriptionHash(invoice) == BadgeWire.sha256(description))
        let tags = [["p", fixturePublicKey], ["description", description], ["bolt11", invoice]]
        let receipt = try await provider.sign(kind: 9735, tags: tags, content: "", createdAt: 101)
        let metadata = try await recipient.sign(kind: 0, tags: [], content: #"{"lud16":"recipient@example.test"}"#, createdAt: 99)
        let http = FixtureProofHTTP(value: .object(["allowsNostr": .bool(true), "nostrPubkey": .string(try await provider.publicKey())]))
        let result = try await ProofVerifier.humanity(receipt, identity: await sender.publicKey(), recipientMetadata: metadata, http: http, now: 102)
        #expect(result.sender == request.pubkey); #expect(result.recipient == fixturePublicKey)
        let wrongProvider = FixtureProofHTTP(value: .object(["allowsNostr": .bool(true), "nostrPubkey": .string(fixturePublicKey)]))
        await #expect(throws: BadgeProtocolError.unauthorized) { try await ProofVerifier.humanity(receipt, identity: sender.publicKey(), recipientMetadata: metadata, http: wrongProvider, now: 102) }
        let selfSigned = try await sender.sign(kind: 9735, tags: tags, content: "", createdAt: 101)
        await #expect(throws: BadgeProtocolError.unauthorized) { try await ProofVerifier.humanity(selfSigned, identity: sender.publicKey(), recipientMetadata: metadata, http: http, now: 102) }
        let mismatched = try await provider.sign(kind: 9735, tags: [["p", fixturePublicKey], ["description", description], ["bolt11", fixtureInvoice(description: "different")]], content: "", createdAt: 101)
        await #expect(throws: BadgeProtocolError.invalidSignature) { try await ProofVerifier.localReceipt(mismatched, now: 102) }
    }
    @Test func locationUnknownIsUnverifiedAndMeasuredFarIsRejected() throws {
        let wien = try BadgeCoordinate(latitude: 48.2, longitude: 16.37), berlin = try BadgeCoordinate(latitude: 52.52, longitude: 13.4)
        #expect(try !BadgePresence.participant(device: nil, reference: wien))
        #expect(try !BadgePresence.participant(device: wien, reference: nil))
        #expect(try BadgePresence.participant(device: wien, reference: wien))
        #expect(throws: BadgeProtocolError.tooFarAway) { try BadgePresence.participant(device: berlin, reference: wien) }
        #expect(throws: BadgeProtocolError.locationUnavailable) { try BadgePresence.issuer(device: nil, reference: wien, event: true) }
        #expect(BadgeSecurity.normalizedMeetupID("bad-neuenahr") == "bad-neuenahr")
        #expect(BadgeSecurity.normalizedMeetupID("bad-neuenahr-de") == "bad-neuenahr")
        #expect(BadgeSecurity.meetupID(city: "Bad Neuenahr", country: "DE") == "bad-neuenahr-de")
    }
}

struct ReputationEvidenceRegressionTests {
    @Test func fullEvidenceVerifiesHashAndDuplicateSessionsCannotInflateSignedCounts() async throws {
        let issuer = try LocalIdentitySigner(privateKey: fixturePrivateKey)
        let collector = try LocalIdentitySigner(privateKey: Data(repeating: 0, count: 31) + Data([2]))
        let payload = try await BadgeSecurity.signCompact(meetupID: "wien-at", blockHeight: 900000, expiresAt: 14400, signer: issuer, createdAt: 100)
        let source = try await BadgeSecurity.verify(payload, now: 200)
        var badge = CollectedBadge(id: "one", meetupName: source.meetupName, date: "1970-01-01T00:03:20.000Z")
        badge.sig = source.event.sig; badge.sigId = source.event.id; badge.adminPubkey = source.event.pubkey
        badge.sigContent = BadgeWire.canonical(payload); badge.sigVersion = 2; badge.blockHeight = 900000
        badge = try await BadgeClaim.bind(badge, signer: collector, timestamp: 200)
        let registry = RegistrySnapshot(admins: [AdminRegistryEntry(npub: try NIP19.encodePublicKey(fixturePublicKey), meetup: "fixture")])
        let key = try await collector.publicKey()
        let score = await TrustEvaluator.evaluate([badge], collector: key, registry: registry, now: 201)
        let json = try ReputationCard.qrPayload(badges: [badge], score: score, nickname: "Fixture", publicKey: key, platformProofs: [], now: 201)
        let qr = try await ReputationCard.signQR(json, signer: collector, now: 201)
        let verified = try await ReputationCard.verifyQR(qr, badges: [badge], registry: registry, now: 202)
        #expect(verified.badgeEvidenceVerified)
        let inflated = try ReputationCard.qrPayload(badges: [badge, badge], score: score, nickname: "Fixture", publicKey: key, platformProofs: [], now: 201)
        let inflatedQR = try await ReputationCard.signQR(inflated, signer: collector, now: 201)
        let failed = try await ReputationCard.verifyQR(inflatedQR, badges: [badge, badge], registry: registry, now: 202)
        #expect(!failed.badgeEvidenceVerified)
    }
    @Test func organicPromotionNeedsThreeDistinctAuthorizedSourceSessionsAndCollectorBindings() async throws {
        let issuer = try LocalIdentitySigner(privateKey: fixturePrivateKey)
        let collector = try LocalIdentitySigner(privateKey: Data(repeating: 0, count: 31) + Data([2]))
        var proofs: [BadgeJSON] = []
        for day in 0..<3 {
            let timestamp = 100 + day * 86400
            let payload = try await BadgeSecurity.signCompact(meetupID: "wien-at", blockHeight: 900000, expiresAt: timestamp + 14400, signer: issuer, createdAt: timestamp)
            let source = try await BadgeSecurity.verify(payload, now: timestamp)
            var b = CollectedBadge(id: String(day), meetupName: "Wien, AT", date: BadgeWire.isoDate(Date(timeIntervalSince1970: Double(timestamp))))
            b.sig = source.event.sig; b.sigId = ""; b.adminPubkey = fixturePublicKey; b.sigVersion = 2
            b.sigContent = BadgeWire.canonical(payload); b.blockHeight = 900001
            b = try await BadgeClaim.bind(b, signer: collector, timestamp: 300000)
            proofs.append(.object(["meetup": .string(b.meetupName), "date": .string(b.date), "block": .int(b.blockHeight),
                "sig": .string(b.sig), "sig_id": .string(b.sigId), "admin_pubkey": .string(b.adminPubkey),
                "sig_version": .int(2), "sig_content": .string(b.sigContent), "claim_sig": .string(b.claimSig),
                "claim_event_id": .string(b.claimEventId), "claim_timestamp": .int(b.claimTimestamp)]))
        }
        let registry = RegistrySnapshot(admins: [AdminRegistryEntry(npub: try NIP19.encodePublicKey(fixturePublicKey), meetup: "fixture")])
        let content = BadgeWire.canonical(["type": .string("admin_claim"), "proofs": .array(proofs), "meetup": .string("Fixture")])
        let claim = try await collector.sign(kind: 30021, tags: [["d", "einundzwanzig-admin-claim"]], content: content, createdAt: 300000)
        #expect(await RegistryAuthority.verifyPromotion(claim, registry: registry, now: 300001) != nil)
        let applied = await RegistryAuthority.applyingPromotions([claim], to: registry, now: 300001)
        #expect(applied.organicAdmins.count == 1)
        let duplicateContent = BadgeWire.canonical(["type": .string("admin_claim"), "proofs": .array([proofs[0], proofs[0], proofs[0]]), "meetup": .string("Fixture")])
        let invalid = try await collector.sign(kind: 30021, tags: [["d", "einundzwanzig-admin-claim"]], content: duplicateContent, createdAt: 300002)
        #expect(await RegistryAuthority.verifyPromotion(invalid, registry: registry, now: 300003) == nil)
        let replaced = await RegistryAuthority.applyingPromotions([invalid], to: applied, now: 300003)
        #expect(replaced.organicAdmins.isEmpty)
        #expect(await RegistryAuthority.verifyPromotion(claim, registry: RegistrySnapshot(), now: 300001) == nil)
    }
}

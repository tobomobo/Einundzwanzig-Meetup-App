import Foundation
import Testing
import MeetupCore
import MeetupNostr

/// Real NIP46 encrypted RPC over an in-memory relay. All keys are synthetic;
/// the actual identity store and wallet use this transport without network I/O.
private actor SecurityBunkerRelay: NostrRelayTransport {
    let server: LocalIdentitySigner
    let user: LocalIdentitySigner
    let serverKey: String
    let userKey: String
    var events: [NostrEvent] = []
    init() throws {
        let serverSecret = Data(repeating: 2, count: 32), userSecret = Data(repeating: 3, count: 32)
        server = try LocalIdentitySigner(privateKey: serverSecret)
        user = try LocalIdentitySigner(privateKey: userSecret)
        serverKey = try IdentityKey.publicKey(serverSecret); userKey = try IdentityKey.publicKey(userSecret)
    }
    nonisolated var uri: String { "bunker://\(serverKey)?relay=wss%3A%2F%2Ffixture.invalid" }
    func query(_ filter: NostrFilter, relays: [URL], timeout: TimeInterval) -> [NostrEvent] { events.filter(filter.matches) }
    func publish(_ event: NostrEvent, relays: [URL], signer: (any NostrSigner)?) async throws {
        let request = try JSONDecoder().decode([String: BackupValue].self, from: Data(try await server.decrypt(event.content, from: event.pubkey).utf8))
        let id = try #require(request["id"]?.string), method = try #require(request["method"]?.string)
        let params = request["params"]?.array?.compactMap(\.string) ?? []
        let result: String
        switch method {
        case "connect": result = "ack"
        case "get_public_key": result = userKey
        case "nip44_encrypt": result = try await user.encrypt(params[1], to: params[0])
        case "nip44_decrypt": result = try await user.decrypt(params[1], from: params[0])
        case "sign_event":
            let unsigned = try UnsignedNostrEvent.decodeJSON(Data(params[0].utf8))
            let signed = try await user.sign(kind: unsigned.kind, tags: unsigned.tags, content: unsigned.content, createdAt: unsigned.createdAt)
            result = String(decoding: try JSONEncoder().encode(signed), as: UTF8.self)
        default: throw NIP46Error.invalidReply
        }
        let reply = try JSONEncoder().encode(["id": BackupValue.string(id), "result": .string(result)])
        let encrypted = try await server.encrypt(String(decoding: reply, as: UTF8.self), to: event.pubkey)
        events.append(try await server.sign(kind: 24133, tags: [["p", event.pubkey]], content: encrypted, createdAt: Int(Date().timeIntervalSince1970)))
    }
}

private struct SecurityHumanityRelay: NostrRelayTransport {
    let events: [NostrEvent]
    func query(_ filter: NostrFilter, relays: [URL], timeout: TimeInterval) -> [NostrEvent] { events.filter(filter.matches) }
    func publish(_ event: NostrEvent, relays: [URL], signer: (any NostrSigner)?) async throws { throw NIP46Error.disconnected }
}
private struct SecurityHumanityHTTP: ProofHTTPClient {
    let provider: String
    func get(_ url: URL) async throws -> BadgeJSON {
        guard url.absoluteString == "https://humanity.invalid/.well-known/lnurlp/recipient" else { throw BadgeProtocolError.malformed }
        return .object(["allowsNostr": .bool(true), "nostrPubkey": .string(provider)])
    }
}
/// Same public synthetic BOLT11 description-hash fixture as
/// BadgeProtocolTests.fixtureInvoice; this is not a payable invoice.
private func securityHumanityInvoice(description: String) -> String {
    let hex = BadgeWire.sha256(description)
    let bytes = stride(from: 0, to: hex.count, by: 2).map { index -> UInt8 in
        let start = hex.index(hex.startIndex, offsetBy: index), end = hex.index(start, offsetBy: 2)
        return UInt8(hex[start..<end], radix: 16)!
    }
    var acc: UInt32 = 0, bits = 0, hashWords: [Int] = []
    for byte in bytes {
        acc = acc << 8 | UInt32(byte); bits += 8
        while bits >= 5 { bits -= 5; hashWords.append(Int(acc >> bits & 31)) }
    }
    if bits > 0 { hashWords.append(Int(acc << (5 - bits) & 31)) }
    var words = Array(repeating: 0, count: 7) + [23, 1, 20] + hashWords + Array(repeating: 0, count: 104)
    let hrp = Array("lnbc10n".utf8), alphabet = Array("qpzry9x8gf2tvdw0s3jn54khce6mua7l")
    var checksum: UInt32 = 1
    let generators: [UInt32] = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]
    for value in hrp.map({ Int($0 >> 5) }) + [0] + hrp.map({ Int($0 & 31) }) + words + Array(repeating: 0, count: 6) {
        let top = checksum >> 25; checksum = (checksum & 0x1ffffff) << 5 ^ UInt32(value)
        for i in 0..<5 where top >> i & 1 != 0 { checksum ^= generators[i] }
    }
    checksum ^= 1
    words += (0..<6).map { Int(checksum >> (5 * (5 - $0)) & 31) }
    return "lnbc10n1" + String(words.map { alphabet[$0] })
}

extension IdentityAppStoreContinuityTests {
    private func securityDocument() throws -> BackupDocument { try BackupDocument.decode(fixture("flutter-v5", "json")) }
    private func restoreSecurity(_ document: BackupDocument, into app: AppStore, allowReplacement: Bool = false) async throws {
        try await app.identity.restoreBackup(String(decoding: document.encoded(), as: UTF8.self), password: vaultPassword,
                                             vaultPassword: vaultPassword, allowReplacement: allowReplacement)
    }
    private func mixedSecurityDocument(_ relay: SecurityBunkerRelay) throws -> BackupDocument {
        var document = try securityDocument(), nostr = document.fields["nostr"]!.object!
        nostr["signing_mode"] = .string("nip46"); nostr["active_npub"] = .string(try NIP19.encodePublicKey(relay.userKey))
        nostr["nip46_bunker_uri"] = .string(relay.uri); document.fields["nostr"] = .object(nostr)
        return document
    }
    private func historicalAndCurrentBadges() async throws -> [BackupValue] {
        let issuer = try LocalIdentitySigner(privateKey: Data(repeating: 4, count: 32))
        let now = Int(Date().timeIntervalSince1970)
        let payload = try await BadgeSecurity.signCompact(meetupID: "fixture-at", blockHeight: 900000, expiresAt: now + 3600, signer: issuer, createdAt: now)
        let source = try await BadgeSecurity.verify(BadgeWire.canonical(payload))
        var current = CollectedBadge(id: source.event.id, meetupName: "Current fixture", date: BadgeWire.isoDate(Date()))
        current.sigVersion = 2; current.sig = source.event.sig; current.sigId = source.event.id
        current.adminPubkey = source.event.pubkey; current.sigContent = BadgeWire.canonical(source.payload)
        var legacy = CollectedBadge(id: "legacy-hmac", meetupName: "Historical fixture", date: "2020-01-02T12:00:00.000Z")
        legacy.sigVersion = 1; legacy.sig = String(repeating: "ab", count: 32); legacy.sigContent = "historical HMAC material"
        return try [legacy, current].map { try JSONDecoder().decode(BackupValue.self, from: Data($0.wireJSON.utf8)) }
    }
    @Test func completeBackupFailsClosedAfterLockAndRelaunchThenLoadsCorrectOwner() async throws {
        let defaults = try fresh(), app = AppStore(legacyDefaults: defaults)
        try await restoreSecurity(securityDocument(), into: app)
        let original = try #require(try KeychainStore.read(account: "badge-wallet-v1"))
        #expect(app.badgeWallet.badges.count == 2)
        await app.identity.lock(); await app.synchronizeIdentity()
        await #expect(throws: IdentityError.locked) { try await app.identity.exportBackup(password: vaultPassword, vaultPassword: vaultPassword) }
        let relaunch = AppStore(legacyDefaults: defaults)
        await #expect(throws: IdentityError.locked) { try await relaunch.identity.exportBackup(password: vaultPassword, vaultPassword: vaultPassword) }
        #expect(try KeychainStore.read(account: "badge-wallet-v1") == original)
        try await relaunch.identity.unlock(password: vaultPassword)
        // No explicit synchronize call: complete export must await configuration.
        let backup = try await exported(relaunch)
        #expect(backup.fields["badges"]?.array?.count == 2)
        #expect(relaunch.badgeWallet.walletLoaded)
        #expect(relaunch.badgeWallet.publicKey == relaunch.identity.publicKey)
    }
    @Test func unreadableWalletCannotProduceCompleteBackupAndCiphertextIsRetained() async throws {
        let defaults = try fresh(), app = AppStore(legacyDefaults: defaults)
        try await restoreSecurity(securityDocument(), into: app)
        await app.identity.lock(); await app.synchronizeIdentity()
        let broken = Data("unreadable encrypted wallet fixture".utf8)
        try KeychainStore.write(broken, account: "badge-wallet-v1")
        let relaunch = AppStore(legacyDefaults: defaults)
        await #expect(throws: IdentityError.locked) { try await relaunch.identity.exportBackup(password: vaultPassword, vaultPassword: vaultPassword) }
        try await relaunch.identity.unlock(password: vaultPassword)
        await #expect(throws: BadgeStoreError.unreadableWallet) { try await exported(relaunch) }
        #expect(relaunch.badgeWallet.walletUnreadable); #expect(!relaunch.badgeWallet.walletLoaded)
        #expect(try KeychainStore.read(account: "badge-wallet-v1") == broken)
    }
    @Test func mixedFlutterBackupActivatesReviewedBAndExportsProtectedAlternateAAfterPasswordChange() async throws {
        let defaults = try fresh(), relay = try SecurityBunkerRelay(), document = try mixedSecurityDocument(relay)
        let app = AppStore(legacyDefaults: defaults, identityTransport: relay)
        let preview = try await app.identity.previewBackup(String(decoding: document.encoded(), as: UTF8.self), password: vaultPassword)
        #expect(preview.identityNpub == (try NIP19.encodePublicKey(relay.userKey)))
        try await restoreSecurity(document, into: app)
        #expect(app.identity.npub == preview.identityNpub); #expect(app.profile.npub == preview.identityNpub)
        #expect(try IdentityVault.read()?.kind == .bunker)
        let out = try await exported(app)
        #expect(out.identityNpub == preview.identityNpub); #expect(try out.localPrivateKey() == document.localPrivateKey())
        #expect(out.fields["nostr"]?.object?["signing_mode"] == .string("nip46"))
        try await app.identity.changePassword(current: vaultPassword, replacement: "replacement-fixture-password")
        await app.identity.lock(); await app.synchronizeIdentity()
        let relaunch = AppStore(legacyDefaults: defaults, identityTransport: relay)
        try await relaunch.identity.unlock(password: "replacement-fixture-password")
        let payload = try await relaunch.identity.exportBackup(password: vaultPassword, vaultPassword: "replacement-fixture-password")
        let after = try BackupDocument.decode(BackupEncryption.decrypt(payload, password: vaultPassword))
        #expect(after.identityNpub == preview.identityNpub); #expect(try after.localPrivateKey() == document.localPrivateKey())
        #expect(after.fields["badges"]?.array?.count == 2)
    }
    @Test func sameAppMixedFlutterMigrationHonorsRemoteModeAndPreservesHMACAndCurrentRows() async throws {
        let defaults = try fresh(), relay = try SecurityBunkerRelay(), document = try mixedSecurityDocument(relay)
        let nostr = document.fields["nostr"]!.object!, rows = try await historicalAndCurrentBadges()
        for (account, field) in [("nostr_nsec_key", "nsec"), ("nostr_npub_key", "npub"), ("nostr_priv_hex", "priv_hex")] {
            try KeychainStore.fixture.write(Data(nostr[field]!.string!.utf8), "flutter/" + account)
        }
        defaults.set("nip46", forKey: "flutter.signing_mode"); defaults.set(document.identityNpub, forKey: "flutter.nip46_npub")
        defaults.set(document.identityNpub, forKey: "flutter.nostr"); defaults.set(relay.uri, forKey: "flutter.nip46_bunker_uri")
        defaults.set("Mixed migration", forKey: "flutter.nickname")
        defaults.set(try rows.map { String(decoding: try JSONEncoder().encode($0), as: UTF8.self) }, forKey: "flutter.badges")
        let app = AppStore(legacyDefaults: defaults, identityTransport: relay)
        let review = try app.identity.reviewFlutterInstallation()
        #expect(review.identityNpub == document.identityNpub); #expect(review.fields["nostr"]?.object?["signing_mode"] == .string("nip46"))
        try await app.identity.migrateFlutterInstallation(vaultPassword: vaultPassword)
        #expect(app.identity.npub == review.identityNpub); #expect(app.profile.npub == review.identityNpub)
        #expect(app.badgeWallet.badges.count == 2); #expect(app.badgeWallet.trust.badgeCount == 0)
        let out = try await exported(app)
        #expect(try out.localPrivateKey() == document.localPrivateKey()); #expect(out.identityNpub == document.identityNpub)
        #expect(out.fields["badges"]?.array?.contains(where: { $0.object?["sigVersion"] == .number(1) && $0.object?["sig"] == .string(String(repeating: "ab", count: 32)) }) == true)
        #expect(try KeychainStore.readFlutter(account: "nostr_nsec_key") != nil)
    }
    @Test func mixedLegacyHMACRestoreRetainsHistoryButRejectsTamperedV2() async throws {
        let defaults = try fresh(), app = AppStore(legacyDefaults: defaults)
        var document = try securityDocument(); document.fields["badges"] = .array(try await historicalAndCurrentBadges())
        try await restoreSecurity(document, into: app)
        #expect(app.badgeWallet.badges.count == 2); #expect(app.badgeWallet.trust.badgeCount == 0)
        let legacy = try #require(app.badgeWallet.badges.first(where: { $0.sigVersion == 1 }))
        #expect(!legacy.sig.isEmpty); #expect(await BadgeSecurity.verifyStored(legacy, collector: nil, now: Int(Date().timeIntervalSince1970)) == false)
        let out = try await exported(app); #expect(out.fields["badges"]?.array?.count == 2)
        var rows = document.fields["badges"]!.array!, forged = rows[1].object!
        forged["sig"] = .string(String(repeating: "00", count: 64)); rows[1] = .object(forged); document.fields["badges"] = .array(rows)
        await #expect(throws: BadgeProtocolError.invalidSignature) { try await restoreSecurity(document, into: app) }
        #expect(app.identity.state == .recoveryRequired); #expect(try IdentityVault.hasPendingRestore())
    }
    @Test func currentProofExportRelaunchRestoreAndRemovalOverrideStagedCopy() async throws {
        let defaults = try fresh(), app = AppStore(legacyDefaults: defaults)
        try await restoreSecurity(securityDocument(), into: app)
        let exact = try await app.badgeWallet.createPlatformProof(platform: "twitter", username: "synthetic-one")
        let first = try await exported(app)
        #expect(first.fields["platform_proofs"]?.array?.contains(where: { $0.object?["username"] == .string("synthetic-one") }) == true)
        await app.identity.lock(); await app.synchronizeIdentity()
        let relaunch = AppStore(legacyDefaults: defaults)
        try await relaunch.identity.unlock(password: vaultPassword)
        let durable = try await exported(relaunch)
        let proof = try #require(relaunch.badgeWallet.platformProofs.first(where: { $0.platform == "twitter" }))
        #expect(try proof.verifyString(publicKey: relaunch.identity.publicKey) == exact)
        let restoredDefaults = try fresh()
        let restored = AppStore(legacyDefaults: restoredDefaults)
        try await restoreSecurity(durable, into: restored)
        #expect(try restored.badgeWallet.platformProofs.first(where: { $0.platform == "twitter" })?.verifyString(publicKey: restored.identity.publicKey) == exact)
        _ = try await restored.badgeWallet.createPlatformProof(platform: "twitter", username: "synthetic-two")
        let edited = try await exported(restored)
        #expect(edited.fields["platform_proofs"]?.array?.filter { $0.object?["platform"] == .string("twitter") }.count == 1)
        #expect(edited.fields["platform_proofs"]?.array?.contains(where: { $0.object?["username"] == .string("synthetic-two") }) == true)
        try await restored.badgeWallet.removePlatformProof("twitter")
        let removed = try await exported(restored)
        #expect(removed.fields["platform_proofs"]?.array?.contains(where: { $0.object?["platform"] == .string("twitter") }) != true)
    }
    @Test func additiveImportWithoutProofsPreservesEarlierStagingAcrossRelaunchAndExport() async throws {
        let defaults = try fresh(), app = AppStore(legacyDefaults: defaults)
        var document = try securityDocument()
        let staged = BackupValue.object(["platform": .string("future-platform"), "username": .string("pending-user"), "proof_sig": .string("historical"), "created_at": .number(1)])
        document.fields["platform_proofs"] = .array([staged])
        try await restoreSecurity(document, into: app)
        document.fields.removeValue(forKey: "platform_proofs"); document.fields.removeValue(forKey: "humanity_proof")
        try await restoreSecurity(document, into: app)
        document.fields["platform_proofs"] = .array([])
        try await restoreSecurity(document, into: app)
        let pending = try BackupDocument.decode(#require(try KeychainStore.read(account: "pending-imported-proofs-v1")))
        #expect(pending.fields["platform_proofs"]?.array?.contains(staged) == true)
        await app.identity.lock(); await app.synchronizeIdentity()
        let relaunch = AppStore(legacyDefaults: defaults)
        try await relaunch.identity.unlock(password: vaultPassword)
        #expect(try await exported(relaunch).fields["platform_proofs"]?.array?.contains(staged) == true)
        #expect(relaunch.badgeWallet.platformProofs.isEmpty)
    }
    @Test func importedCityFavoritesResolveAfterDirectoryArrivesAndSurviveRelaunchWithoutMergingGroups() async throws {
        let defaults = try fresh(), app = AppStore(legacyDefaults: defaults)
        try await restoreSecurity(securityDocument(), into: app)
        #expect(app.favorites.isEmpty)
        var cache = DataCache()
        cache.meetups = [Meetup(id: "7", name: "Main group", city: "ASCHAFFENBURG", country: "DE"),
            Meetup(id: "8", name: "Group one", city: "Würzburg", country: "DE"),
            Meetup(id: "9", name: "Group two", city: "Würzburg", country: "DE")]
        cache.events = [MeetupEvent(id: "favorite-event", start: .now.addingTimeInterval(3600), meetupID: "7"),
            MeetupEvent(id: "other-event", start: .now.addingTimeInterval(7200), meetupID: "9")]
        try JSONEncoder().encode(cache).write(to: UITestFixtureMode.directory.appendingPathComponent("public-cache.json"))
        let relaunch = AppStore(legacyDefaults: defaults)
        #expect(relaunch.isFavorite("7")); #expect(!relaunch.isFavorite("8")); #expect(!relaunch.isFavorite("9"))
        #expect(relaunch.favorites.map(\.id) == ["7"])
        let filtered = relaunch.events.filter { $0.meetupID.map(relaunch.favoriteIDs.contains) == true }
        #expect(filtered.map(\.id) == ["favorite-event"])
        relaunch.toggleFavorite("8")
        #expect(relaunch.isFavorite("8")); #expect(!relaunch.isFavorite("9"))
        relaunch.toggleFavorite("7")
        #expect(!relaunch.isFavorite("7")); #expect(!relaunch.preferences.favoriteIDs.contains("Aschaffenburg"))
        let again = AppStore(legacyDefaults: defaults)
        #expect(again.isFavorite("8")); #expect(!again.isFavorite("9")); #expect(!again.isFavorite("7"))
    }

    @Test func roundTwoSameIdentityRetainedKeyRequiresCurrentVaultPasswordWhileUnlocked() async throws {
        let defaults = try fresh(), relay = try SecurityBunkerRelay(), mixed = try mixedSecurityDocument(relay)
        let app = AppStore(legacyDefaults: defaults, identityTransport: relay)
        try await app.identity.connectBunker(relay.uri, vaultPassword: vaultPassword)
        await app.synchronizeIdentity()
        #expect(app.identity.npub == mixed.identityNpub); #expect(app.identity.state == .unlocked)
        #expect(try IdentityVault.read()?.retainedLocalSecret == nil)
        let ciphertext = try #require(try KeychainStore.read(account: "signing-identity-v1"))
        await #expect(throws: IdentityError.authenticationFailed) {
            try await app.identity.restoreBackup(String(decoding: mixed.encoded(), as: UTF8.self), password: vaultPassword,
                                                  vaultPassword: "different-fixture-vault-password")
        }
        #expect(try KeychainStore.read(account: "signing-identity-v1") == ciphertext)
        #expect(try IdentityVault.read()?.retainedLocalSecret == nil)
        #expect(app.identity.state == .recoveryRequired)
        let protected = try #require(try IdentityVault.pendingRestore())
        let recovery = try BackupDocument.decode(BackupEncryption.decrypt(protected.payload, password: vaultPassword))
        #expect(recovery.identityNpub == mixed.identityNpub); #expect(try recovery.localPrivateKey() == mixed.localPrivateKey())
        // Explicitly discard the failed attempt; the original B vault remains usable.
        try await app.identity.discardPendingRecovery()
        try await app.identity.unlock(password: vaultPassword)
        let unchanged = try await exported(app)
        #expect(unchanged.identityNpub == mixed.identityNpub); #expect(try unchanged.localPrivateKey() == nil)
        try await restoreSecurity(mixed, into: app)
        #expect(!((try IdentityVault.hasPendingRestore())))
        let complete = try await exported(app)
        #expect(complete.identityNpub == mixed.identityNpub); #expect(try complete.localPrivateKey() == mixed.localPrivateKey())
        let rotatedPassword = "rotated-fixture-vault-password"
        try await app.identity.changePassword(current: vaultPassword, replacement: rotatedPassword)
        await app.identity.lock(); await app.synchronizeIdentity()
        let relaunch = AppStore(legacyDefaults: defaults, identityTransport: relay)
        try await relaunch.identity.unlock(password: rotatedPassword)
        let rotated = try await relaunch.identity.exportBackup(password: vaultPassword, vaultPassword: rotatedPassword)
        let after = try BackupDocument.decode(BackupEncryption.decrypt(rotated, password: vaultPassword))
        #expect(after.identityNpub == mixed.identityNpub); #expect(try after.localPrivateKey() == mixed.localPrivateKey())
    }
    @Test func roundTwoNativeHumanityRemainsUnverifiedEvidenceAfterReloadMutationAndAdditiveImport() async throws {
        let defaults = try fresh(), app = AppStore(legacyDefaults: defaults)
        try await app.identity.importKey(String(repeating: "01", count: 32), vaultPassword: vaultPassword)
        await app.synchronizeIdentity()
        let signer = try #require(app.identity.signer), owner = app.identity.publicKey
        let recipient = try LocalIdentitySigner(privateKey: Data(repeating: 5, count: 32))
        let provider = try LocalIdentitySigner(privateKey: Data(repeating: 6, count: 32))
        let recipientKey = try await recipient.publicKey(), providerKey = try await provider.publicKey()
        let timestamp = Int(Date().timeIntervalSince1970) - 10
        let request = try await signer.sign(kind: 9734, tags: [["p", recipientKey]], content: "synthetic humanity continuity", createdAt: timestamp - 1)
        let description = String(decoding: try JSONEncoder().encode(request), as: UTF8.self)
        let receipt = try await provider.sign(kind: 9735,
            tags: [["p", recipientKey], ["P", owner], ["description", description], ["bolt11", securityHumanityInvoice(description: description)]],
            content: "", createdAt: timestamp)
        let metadata = try await recipient.sign(kind: 0, tags: [], content: #"{"lud16":"recipient@humanity.invalid"}"#, createdAt: timestamp - 2)
        let relay = SecurityHumanityRelay(events: [receipt, metadata]), http = SecurityHumanityHTTP(provider: providerKey)
        let native = BadgeStore(relay: relay, persistence: KeychainBadgeWalletPersistence(), relays: [], proofHTTP: http)
        await native.configure(signer: signer)
        try await native.checkHumanity()
        #expect(native.humanity?.receipt.id == receipt.id)
        #expect(native.pendingBackup["humanity_proof"] == nil)
        #expect(try KeychainStore.read(account: "pending-imported-proofs-v1") == nil)
        func expectPending(_ fields: [String: BackupValue]) {
            let summary = fields["humanity_proof"]?.object
            #expect(summary?["receipt_event_id"] == .string(receipt.id))
            #expect(summary?["receipt_id"] == .string(receipt.id))
            #expect(summary?["first_zap_at"] == .number(Double(timestamp)))
            #expect(summary?["verified"] == .bool(false)); #expect(summary?["needs_reverification"] == .bool(true))
        }
        await app.identity.lock(); await app.synchronizeIdentity()
        let relaunch = AppStore(legacyDefaults: defaults)
        try await relaunch.identity.unlock(password: vaultPassword)
        expectPending(try await exported(relaunch).fields)
        #expect(relaunch.badgeWallet.humanity == nil)
        _ = try await relaunch.badgeWallet.createPlatformProof(platform: "fixture", username: "retains-humanity")
        await relaunch.identity.lock(); await relaunch.synchronizeIdentity()
        let afterMutation = AppStore(legacyDefaults: defaults)
        try await afterMutation.identity.unlock(password: vaultPassword)
        expectPending(try await exported(afterMutation).fields)
        #expect(afterMutation.badgeWallet.humanity == nil)
        // Revalidate natively, then import absent/empty sections while the
        // verified receipt is still live. That transition must also stage it.
        let active = BadgeStore(relay: relay, persistence: KeychainBadgeWalletPersistence(), relays: [], proofHTTP: http)
        await active.configure(signer: afterMutation.identity.signer)
        try await active.checkHumanity()
        #expect(active.humanity?.receipt.id == receipt.id); #expect(active.pendingBackup["humanity_proof"] == nil)
        var additive = try BackupDocument(fields: ["version": .number(5), "user": .object([:]), "badges": .array([])])
        for section: BackupValue? in [nil, .object([:]), .null, .array([])] {
            additive.fields["humanity_proof"] = section
            try await active.restoreBackup(additive, decision: .sameIdentity)
            expectPending(try active.backupSnapshot(owner: owner)); #expect(active.humanity == nil)
        }
        await afterMutation.identity.lock(); await afterMutation.synchronizeIdentity()
        let final = AppStore(legacyDefaults: defaults)
        try await final.identity.unlock(password: vaultPassword)
        expectPending(try await exported(final).fields); #expect(final.badgeWallet.humanity == nil)
    }

    @Test func roundThreeNormalizedEmptyHumanityImportPreservesNativeReceiptThroughActualAppStore() async throws {
        let defaults = try fresh(), app = AppStore(legacyDefaults: defaults)
        try await app.identity.importKey(String(repeating: "01", count: 32), vaultPassword: vaultPassword)
        await app.synchronizeIdentity()
        let signer = try #require(app.identity.signer), owner = app.identity.publicKey
        let recipient = try LocalIdentitySigner(privateKey: Data(repeating: 5, count: 32))
        let provider = try LocalIdentitySigner(privateKey: Data(repeating: 6, count: 32))
        let recipientKey = try await recipient.publicKey(), providerKey = try await provider.publicKey()
        let timestamp = Int(Date().timeIntervalSince1970) - 10
        let request = try await signer.sign(kind: 9734, tags: [["p", recipientKey]], content: "synthetic normalized import", createdAt: timestamp - 1)
        let description = String(decoding: try JSONEncoder().encode(request), as: UTF8.self)
        let tags = [["p", recipientKey], ["P", owner], ["description", description], ["bolt11", securityHumanityInvoice(description: description)]]
        let receipt = try await provider.sign(kind: 9735, tags: tags, content: "", createdAt: timestamp)
        let metadata = try await recipient.sign(kind: 0, tags: [], content: #"{"lud16":"recipient@humanity.invalid"}"#, createdAt: timestamp - 2)
        let native = BadgeStore(relay: SecurityHumanityRelay(events: [receipt, metadata]), persistence: KeychainBadgeWalletPersistence(),
                                relays: [], proofHTTP: SecurityHumanityHTTP(provider: providerKey))
        await native.configure(signer: signer); try await native.checkHumanity()
        #expect(native.humanity?.receipt.id == receipt.id)
        #expect(try KeychainStore.read(account: "pending-imported-proofs-v1") == nil)
        func requirePending(_ fields: [String: BackupValue], id: String, time: Int, context: String) throws {
            let summary = fields["humanity_proof"]?.object
            try #require(summary?["receipt_event_id"] == .string(id), "Receipt event ID lost: \(context)")
            try #require(summary?["receipt_id"] == .string(id), "Receipt ID lost: \(context)")
            try #require(summary?["first_zap_at"] == .number(Double(time)), "Receipt time lost: \(context)")
            #expect(summary?["verified"] == .bool(false)); #expect(summary?["needs_reverification"] == .bool(true))
        }
        await app.identity.lock(); await app.synchronizeIdentity()
        let current = AppStore(legacyDefaults: defaults)
        try await current.identity.unlock(password: vaultPassword)
        let baseline = try await exported(current)
        try requirePending(baseline.fields, id: receipt.id, time: timestamp, context: "native reload")
        #expect(current.badgeWallet.humanity == nil)
        // Seed the separate imported-proof account through the actual restore
        // callback too, so wallet and legacy staging must agree on empty merges.
        try await restoreSecurity(baseline, into: current)
        let cases: [(String, BackupValue?)] = [
            ("empty object", .object([:])), ("absent", nil), ("null", .null), ("empty array", .array([])),
            ("flags only", .object(["verified": .bool(true), "needs_reverification": .bool(false)])),
            ("empty receipt fields", .object(["receipt_id": .string(""), "receipt_event_id": .string("  "), "first_zap_at": .number(0)])),
            ("timestamp without receipt", .object(["receipt_event_id": .string(""), "first_zap_at": .number(Double(timestamp + 1))])),
            ("unknown safe evidence", .object(["future_public_evidence": .object(["source": .string("retained")])]))
        ]
        for (name, section) in cases {
            var incoming = baseline; incoming.fields["humanity_proof"] = section
            // This route includes IdentityStore.pendingRevalidation and the
            // real AppStore callback. No raw BadgeStore restore is used here.
            try await restoreSecurity(incoming, into: current)
            try requirePending(try await exported(current).fields, id: receipt.id, time: timestamp, context: name)
            let staged = try BackupDocument.decode(#require(try KeychainStore.read(account: "pending-imported-proofs-v1")))
            try requirePending(staged.fields, id: receipt.id, time: timestamp, context: "import staging: " + name)
            #expect(current.badgeWallet.humanity == nil); #expect(try !IdentityVault.hasPendingRestore())
        }
        let afterEmpty = try await exported(current)
        #expect(afterEmpty.fields["humanity_proof"]?.object?["future_public_evidence"] == .object(["source": .string("retained")]))
        await current.identity.lock(); await current.synchronizeIdentity()
        let relaunch = AppStore(legacyDefaults: defaults)
        try await relaunch.identity.unlock(password: vaultPassword)
        try requirePending(try await exported(relaunch).fields, id: receipt.id, time: timestamp, context: "durable empty merges")
        #expect(relaunch.badgeWallet.humanity == nil)
        // Receipt-bearing incoming evidence may replace a pending summary,
        // but its restored verified flag still cannot grant provider authority.
        let newer = try await provider.sign(kind: 9735, tags: tags, content: "", createdAt: timestamp + 1)
        var incoming = baseline
        incoming.fields["humanity_proof"] = .object(["receipt_id": .string(newer.id), "receipt_event_id": .string(newer.id),
            "first_zap_at": .number(Double(newer.createdAt)), "verified": .bool(true), "needs_reverification": .bool(false)])
        try await restoreSecurity(incoming, into: relaunch)
        try requirePending(try await exported(relaunch).fields, id: newer.id, time: newer.createdAt, context: "meaningful receipt replacement")
        #expect(relaunch.badgeWallet.humanity == nil)
        await relaunch.identity.lock(); await relaunch.synchronizeIdentity()
        let final = AppStore(legacyDefaults: defaults)
        try await final.identity.unlock(password: vaultPassword)
        try requirePending(try await exported(final).fields, id: newer.id, time: newer.createdAt, context: "durable meaningful replacement")
        #expect(final.badgeWallet.humanity == nil)
    }

    @Test func roundFourActualRestoreRedactsNestedSecretsFromPublicStagingAndStaleExports() async throws {
        let defaults = try fresh(), app = AppStore(legacyDefaults: defaults)
        var document = try securityDocument()
        let identitySecret = try #require(try document.localPrivateKey())
        // Public synthetic fixture keys only. Failure assertions report a
        // Boolean, never a key, token, password or serialized private payload.
        let bogus = Data(repeating: 7, count: 32)
        let bogusNsec = try NIP19.encodeSecretKey(bogus), bogusHex = IdentityKey.hex(bogus)
        let publicKey = try IdentityKey.publicKey(bogus), publicNpub = try NIP19.encodePublicKey(publicKey)
        let markers = [bogusNsec, bogusHex, "round-four-synthetic-token", "round-four-synthetic-password"]
        let precise = BackupValue.decimal(try #require(Decimal(string: "9007199254740993")))
        let nested: BackupValue = .object(["nsec": .string(bogusNsec), "priv_hex": .string(bogusHex),
            "token": .string(markers[2]), "password": .string(markers[3]), "client_sk": .string(bogusHex),
            "nostr_nsec_key": .string(bogusNsec), "nostr_priv_hex": .string(bogusHex),
            "nip46_client_sk": .string(bogusHex), "privkey": .string(bogusHex),
            "npub": .string(publicNpub), "publickey": .string(publicKey), "receipt_hash": .string(String(repeating: "c", count: 64)),
            "public_text": .string("nsec1-public-example-label"),
            "public_note": .string("kept public metadata"), "identifier": precise,
            "children": .array([.object(["api-token": .string(markers[2]), "private key": .string(bogusHex),
                "label": .string("nested array kept")])])])
        document.fields["humanity_proof"] = .object(["receipt_event_id": .string(String(repeating: "a", count: 64)),
            "first_zap_at": .number(1), "verified": .bool(true), "nested": nested])
        document.fields["platform_proofs"] = .array([.object(["platform": .string("future-platform"),
            "username": .string("synthetic"), "proof_sig": .string("unverified historical metadata"), "created_at": .number(1), "nested": nested])])
        document.fields["reputation"] = .object(["nested": nested])
        document.fields["social_graph"] = .object(["nodes": .array([nested])])
        document.fields["future_public_section"] = .object(["nested": nested])
        document.fields["auth_token"] = .string(markers[2])
        var user = document.fields["user"]!.object!; user["futurePublic"] = nested; document.fields["user"] = .object(user)
        func requireClean(_ fields: [String: BackupValue], context: String) throws {
            let text = String(decoding: try JSONEncoder().encode(fields), as: UTF8.self)
            let containsNoSecretMarkers = markers.allSatisfy { !text.contains($0) }
            try #require(containsNoSecretMarkers, "Nested secret retained in \(context)")
        }
        func requireMetadata(_ fields: [String: BackupValue], context: String) throws {
            for name in ["humanity_proof", "reputation", "future_public_section"] {
                let metadata = fields[name]?.object?["nested"]?.object
                try #require(metadata?["public_note"] == .string("kept public metadata"), "Public metadata missing: \(context)/\(name)")
                #expect(metadata?["identifier"] == precise)
                #expect(metadata?["npub"] == .string(publicNpub)); #expect(metadata?["publickey"] == .string(publicKey))
                #expect(metadata?["receipt_hash"] == .string(String(repeating: "c", count: 64)))
                #expect(metadata?["public_text"] == .string("nsec1-public-example-label"))
                #expect(metadata?["children"]?.array?.first?.object?["label"] == .string("nested array kept"))
            }
            #expect(fields["platform_proofs"]?.array?.first?.object?["nested"]?.object?["identifier"] == precise)
            #expect(fields["social_graph"]?.object?["nodes"]?.array?.first?.object?["identifier"] == precise)
        }
        // A failed public-stage write must leave the full original recoverable
        // ONLY inside the password-encrypted restore journal.
        KeychainStore.fixture.failWrites("pending-imported-proofs-v1")
        await #expect(throws: IdentityStorageFailure.self) { try await restoreSecurity(document, into: app) }
        KeychainStore.fixture.failWrites(nil)
        let protected = try #require(try IdentityVault.pendingRestore())
        let recovered = try BackupDocument.decode(BackupEncryption.decrypt(protected.payload, password: vaultPassword))
        let recoveryIdentityMatches = try recovered.localPrivateKey() == identitySecret
        let protectedPayloadMatches = recovered.fields["future_public_section"] == document.fields["future_public_section"]
        #expect(recoveryIdentityMatches); #expect(protectedPayloadMatches)
        try await app.identity.resumePendingRestore(password: vaultPassword, vaultPassword: vaultPassword)
        let staged = try BackupDocument.decode(#require(try KeychainStore.read(account: "pending-imported-proofs-v1")))
        try requireClean(staged.fields, context: "pending account")
        try requireMetadata(staged.fields, context: "pending account")
        #expect(staged.fields["nostr"] == nil)
        try requireClean(app.badgeWallet.pendingBackup, context: "wallet staging")
        #expect(app.badgeWallet.pendingBackup["humanity_proof"]?.object?["nested"]?.object?["identifier"] == precise)
        #expect(app.badgeWallet.pendingBackup["platform_proofs"]?.array?.first?.object?["nested"]?.object?["identifier"] == precise)
        let vault = try #require(try IdentityVault.read())
        let vaultIdentityMatches = try NIP49.decrypt(vault.sealedSecret, password: vaultPassword) == identitySecret
        #expect(vaultIdentityMatches); #expect(try !IdentityVault.hasPendingRestore())
        // Existing versions may already have a dirty legacy public-stage copy.
        // Reading it for export must redact both output and the stored copy.
        var stale = document; stale.fields.removeValue(forKey: "nostr")
        try KeychainStore.write(stale.encoded(), account: "pending-imported-proofs-v1")
        let output = try await exported(app)
        var publicExport = output.fields; publicExport.removeValue(forKey: "nostr")
        try requireClean(publicExport, context: "complete export from stale staging")
        try requireMetadata(publicExport, context: "complete export")
        let exportIdentityMatches = try output.localPrivateKey() == identitySecret
        #expect(exportIdentityMatches)
        let cleaned = try BackupDocument.decode(#require(try KeychainStore.read(account: "pending-imported-proofs-v1")))
        try requireClean(cleaned.fields, context: "stale account readback")
        // A later additive import must sanitize the previous account before
        // merging it and must retain safe unknown public sections.
        try KeychainStore.write(stale.encoded(), account: "pending-imported-proofs-v1")
        let thin = try BackupDocument(fields: ["version": .number(5), "user": .object(["nickname": .string("Synthetic")]),
            "badges": .array([]), "nostr": document.fields["nostr"]!])
        try await restoreSecurity(thin, into: app)
        let merged = try BackupDocument.decode(#require(try KeychainStore.read(account: "pending-imported-proofs-v1")))
        try requireClean(merged.fields, context: "merged stale account"); try requireMetadata(merged.fields, context: "merged stale account")
        await app.identity.lock(); await app.synchronizeIdentity()
        let relaunch = AppStore(legacyDefaults: defaults)
        try await relaunch.identity.unlock(password: vaultPassword)
        let durable = try await exported(relaunch)
        var durablePublic = durable.fields; durablePublic.removeValue(forKey: "nostr")
        try requireClean(durablePublic, context: "durable complete export"); try requireMetadata(durablePublic, context: "durable complete export")
        try requireClean(relaunch.badgeWallet.pendingBackup, context: "durable wallet staging")
        let durableIdentityMatches = try durable.localPrivateKey() == identitySecret
        #expect(durableIdentityMatches); #expect(relaunch.badgeWallet.humanity == nil)
    }
}

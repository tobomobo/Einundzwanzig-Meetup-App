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
}

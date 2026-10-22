import Foundation
import Observation
import MeetupNostr

/// The adapter stores ciphertext only. A failed read or delete must throw.
@MainActor protocol BadgeWalletPersistence {
    func read() throws -> Data?
    func write(_ data: Data) throws
    func delete() throws
}

@Observable @MainActor final class BadgeStore {
    private let relay: any NostrRelayTransport
    private let persistence: any BadgeWalletPersistence
    private let proofHTTP: any ProofHTTPClient
    /// Never receives the wallet signer, including for NIP-42 relay AUTH.
    private let publicRelay: any NostrRelayTransport
    var relays: [URL]
    private(set) var signer: (any NostrSigner)?
    private(set) var publicKey = ""
    private(set) var generation = 0
    private(set) var badges: [CollectedBadge] = []
    private(set) var registry = RegistrySnapshot()
    private(set) var session: BadgeOrganizerSession?
    private(set) var platformProofs: [PlatformIdentityProof] = []
    private(set) var humanity: HumanityReceipt?
    private(set) var social = SocialGraphSnapshot()
    private(set) var socialLoadingDepth = 0
    private(set) var reputationQR = ""
    private(set) var status = ""
    private(set) var busy = false
    private(set) var walletUnreadable = false
    private(set) var walletLoaded = false
    private(set) var trust = TrustScore()
    private(set) var myVouches: [AdminRegistryEntry] = []
    private(set) var pendingBackup: [String: BackupValue] = [:]
    private var eventEvidence: [NostrEvent] = []
    private var lastSyncHashes: [String] = []
    private var lastPublishAt = 0
    /// Portal-backed authority must be scoped to the source's signed meetup.
    /// The lead may wire an authenticated portal resolver; nil grants nothing.
    var portalAuthority: (@MainActor @Sendable (VerifiedBadgeSource) async throws -> Bool)?
    var meetupReference: (@MainActor @Sendable (String) -> BadgeCoordinate?)?
    var ownMeetupAuthority: (@MainActor @Sendable (String, String) async throws -> Bool)?
    private let crypto = NostrCrypto()

    init(relay: any NostrRelayTransport, persistence: any BadgeWalletPersistence,
         relays: [URL] = [URL(string: "wss://relay.damus.io")!, URL(string: "wss://nos.lol")!],
         proofHTTP: any ProofHTTPClient = ProofURLSessionClient(),
         publicRelay: any NostrRelayTransport = NostrRelayClient()) {
        self.relay = relay; self.persistence = persistence; self.relays = relays; self.proofHTTP = proofHTTP; self.publicRelay = publicRelay
    }

    func configure(signer: (any NostrSigner)?) async {
        generation += 1
        let epoch = generation
        self.signer = signer
        badges = []; platformProofs = []; humanity = nil; session = nil
        registry = RegistrySnapshot(); social = SocialGraphSnapshot(); socialLoadingDepth = 0; reputationQR = ""
        trust = TrustScore(); myVouches = []; pendingBackup = [:]; eventEvidence = []; lastSyncHashes = []; busy = false
        publicKey = ""; walletUnreadable = false; walletLoaded = false
        guard let signer else { return }
        do {
            let key = try await signer.publicKey()
            guard epoch == generation else { return }
            publicKey = key
            social.owner = key
            try await loadWallet(signer: signer, key: key, epoch: epoch)
            try assertCurrent(epoch, key: key)
            walletLoaded = true
        } catch { if epoch == generation { walletUnreadable = true; status = error.localizedDescription } }
    }

    /// Called before identity replacement. Persistence failure keeps the old
    /// identity active; incrementing first fences every in-flight operation.
    func prepareIdentityChange() async throws {
        generation += 1
        walletLoaded = false
        do { try persistence.delete() }
        catch { busy = false; throw BadgeStoreError.persistenceDeletion(error.localizedDescription) }
        signer = nil; publicKey = ""; badges = []; platformProofs = []
        humanity = nil; session = nil; registry = RegistrySnapshot()
        social = SocialGraphSnapshot(); socialLoadingDepth = 0; reputationQR = ""; walletUnreadable = false
        trust = TrustScore(); myVouches = []; pendingBackup = [:]; eventEvidence = []; lastSyncHashes = []
        status = ""; busy = false
    }

    private struct Wallet: Codable {
        var version = 1
        var owner: String
        var badges: [CollectedBadge]
        var registry: RegistrySnapshot
        var session: BadgeOrganizerSession?
        var platformProofs: [PlatformIdentityProof]
        var humanity: HumanityReceipt?
        var myVouches: [AdminRegistryEntry]
        var pendingBackup: [String: BackupValue]
        var eventEvidence: [NostrEvent]
        var social: SocialGraphSnapshot?
    }
    private struct SealedWallet: Codable { var version = 1; let owner: String; let chunks: [String] }
    private var wallet: Wallet { Wallet(owner: publicKey, badges: badges, registry: registry, session: session,
                                        platformProofs: platformProofs, humanity: humanity, myVouches: myVouches,
                                        pendingBackup: pendingBackup, eventEvidence: eventEvidence, social: social) }
    private func assertCurrent(_ epoch: Int, key: String) throws {
        guard generation == epoch, publicKey == key, signer != nil, !Task.isCancelled else { throw BadgeStoreError.identityChanged }
    }
    private func begin() throws -> (any NostrSigner, String, Int) {
        guard !busy else { throw IdentityError.busy }
        guard !walletUnreadable else { throw BadgeStoreError.unreadableWallet }
        guard let signer, !publicKey.isEmpty, walletLoaded else { throw BadgeStoreError.noSigner }
        busy = true; return (signer, publicKey, generation)
    }
    private func finish(_ epoch: Int) { if epoch == generation { busy = false } }
    private func loadWallet(signer: any NostrSigner, key: String, epoch: Int) async throws {
        guard let data = try persistence.read() else { return }
        guard data.count <= 10_485_760 else { throw BadgeProtocolError.oversized }
        let sealed = try JSONDecoder().decode(SealedWallet.self, from: data)
        guard sealed.version == 1, sealed.owner == key, !sealed.chunks.isEmpty, sealed.chunks.count <= 256 else { throw BadgeStoreError.unreadableWallet }
        var plain = Data()
        for chunk in sealed.chunks {
            let text = try await signer.decrypt(chunk, from: key)
            try assertCurrent(epoch, key: key)
            guard let decoded = Data(base64Encoded: text), plain.count + decoded.count <= 8_388_608 else { throw BadgeStoreError.unreadableWallet }
            plain.append(decoded)
        }
        var value = try JSONDecoder().decode(Wallet.self, from: plain)
        guard value.version == 1, value.owner == key else { throw BadgeStoreError.unreadableWallet }
        value.pendingBackup = Self.safeProofMetadata(.object(value.pendingBackup)).object ?? [:]
        value.registry = await RegistryAuthority.resolve([], previous: value.registry, now: Self.now)
        // Cached humanity and platform flags are not authority. Platform
        // signatures can be checked locally; provider evidence is re-fetched.
        Self.stageHumanityForRevalidation(in: &value)
        var verifiedProofs: [PlatformIdentityProof] = []
        for proof in value.platformProofs {
            if let event = try? proof.event(publicKey: key), await crypto.verify(event) { verifiedProofs.append(proof) }
        }
        value.platformProofs = verifiedProofs
        if let cached = value.social, cached.owner == key {
            var checked = await SocialNetwork.ingest(cached.sourceEvents, now: Self.now)
            checked.owner = key; checked.updatedAt = min(Self.now, cached.updatedAt)
            checked.fullSyncAt = min(Self.now, cached.fullSyncAt); checked.knownAuthors = cached.knownAuthors; checked.knownKeys = cached.knownKeys
            checked.stale = cached.stale; checked.truncated = cached.truncated; value.social = checked
        } else { var empty = SocialGraphSnapshot(); empty.owner = key; value.social = empty }
        if value.session?.owner != key || (value.session?.expiresAt ?? 0) < Self.now { value.session = nil }
        try assertCurrent(epoch, key: key); apply(value)
        await recalculate(epoch: epoch, key: key)
    }
    private func persist(_ value: Wallet, signer: any NostrSigner, epoch: Int) async throws {
        guard !walletUnreadable else { throw BadgeStoreError.unreadableWallet }
        let data = try JSONEncoder().encode(value)
        guard data.count <= 24_000 * 256 else { throw BadgeProtocolError.oversized }
        var chunks: [String] = []
        for start in stride(from: 0, to: data.count, by: 24_000) {
            let part = data.subdata(in: start..<min(start + 24_000, data.count)).base64EncodedString()
            chunks.append(try await signer.encrypt(part, to: value.owner))
            try assertCurrent(epoch, key: value.owner)
        }
        let sealed = try JSONEncoder().encode(SealedWallet(owner: value.owner, chunks: chunks))
        try assertCurrent(epoch, key: value.owner)
        try persistence.write(sealed)
        apply(value)
    }
    private func apply(_ value: Wallet) {
        badges = value.badges; registry = value.registry; session = value.session
        platformProofs = value.platformProofs; humanity = value.humanity; myVouches = value.myVouches
        pendingBackup = value.pendingBackup; eventEvidence = value.eventEvidence
        social = value.social ?? SocialGraphSnapshot()
    }
    private func recalculate(epoch: Int, key: String) async {
        var authorizedEvents: Set<String> = []
        for badge in badges {
            guard let source = try? await BadgeSecurity.verify(badge.sigContent, now: Self.now, checkExpiry: false, checkRolling: false) else { continue }
            if let address = source.eventAddress {
                for event in eventEvidence where await BadgeEventAuthority.verify(address: address, signer: source.event.pubkey, issuedAt: source.event.createdAt, event: event, registry: registry, now: Self.now) {
                    authorizedEvents.insert(source.event.id)
                }
            } else if !registry.isAuthorized(source.event.pubkey), let portalAuthority, (try? await portalAuthority(source)) == true {
                authorizedEvents.insert(source.event.id)
            }
        }
        let result = await TrustEvaluator.evaluate(badges, collector: key, registry: registry, eventAuthorities: authorizedEvents, now: Self.now)
        guard epoch == generation, key == publicKey else { return }; trust = result
    }
    private static var now: Int { Int(Date().timeIntervalSince1970) }

    @discardableResult func importBadge(_ input: String, allowUnknownIssuer: Bool = false,
                                       deviceLocation: BadgeCoordinate? = nil) async throws -> CollectedBadge {
        let (signer, key, epoch) = try begin(); defer { finish(epoch) }
        let source = try await BadgeSecurity.verify(input)
        try assertCurrent(epoch, key: key)
        var next = wallet
        var authorized = next.registry.isAuthorized(source.event.pubkey)
        var reference = meetupReference?(source.meetupID)
        if let address = source.eventAddress {
            let filter = try BadgeEventAuthority.filter(for: address)
            let events: [NostrEvent]
            do { events = try await relay.query(filter, relays: relays, timeout: 8) }
            catch { if !allowUnknownIssuer { throw error }; events = [] }
            if let event = await newestVerified(events, filter: filter) {
                authorized = await BadgeEventAuthority.verify(address: address, signer: source.event.pubkey, issuedAt: source.event.createdAt,
                                                              event: event, registry: registry, now: Self.now)
                next.eventEvidence.removeAll { $0.kind == event.kind && $0.pubkey == event.pubkey && $0.tags.first(where: { $0.first == "d" }) == event.tags.first(where: { $0.first == "d" }) }
                next.eventEvidence.append(event)
                reference = BadgePresence.eventCoordinate(event)
            } else { authorized = false }
        } else if !authorized, let portalAuthority {
            do { authorized = try await portalAuthority(source) }
            catch { if !allowUnknownIssuer { throw error }; authorized = false }
        }
        try assertCurrent(epoch, key: key)
        guard authorized || allowUnknownIssuer else { throw BadgeProtocolError.unauthorized }
        let day = String(BadgeWire.isoDate(Date(timeIntervalSince1970: Double(source.event.createdAt))).prefix(10))
        var badge = CollectedBadge(id: source.event.id, meetupName: source.meetupName, date: BadgeWire.isoDate(Date()))
        badge.meetupEventId = source.identityMeetupID + "-" + day; badge.sig = source.event.sig; badge.sigId = source.event.id
        badge.adminPubkey = source.event.pubkey; badge.signerNpub = try NIP19.encodePublicKey(source.event.pubkey)
        badge.sigVersion = 2; badge.sigContent = BadgeWire.canonical(source.payload); badge.blockHeight = source.blockHeight
        badge.isEvent = source.eventAddress != nil
        badge.delivery = source.payload["d"]?.string == "qr" ? "rolling_qr" : source.payload["d"]?.string ?? source.payload["delivery"]?.string ?? "nfc"
        // The signed compact payload has no location coordinates. Only caller
        // supplied, independently checked portal/device positions may mark it.
        badge.presenceVerified = try BadgePresence.participant(device: deviceLocation, reference: reference)
        badge.lat = deviceLocation?.latitude ?? 0; badge.lng = deviceLocation?.longitude ?? 0
        guard !next.badges.contains(where: { $0.unionKey == badge.unionKey }) else { throw BadgeProtocolError.duplicate }
        badge = try await BadgeClaim.bind(badge, signer: signer, timestamp: Self.now)
        try assertCurrent(epoch, key: key)
        next.badges = CollectedBadge.merge(next.badges, [badge])
        try await persist(next, signer: signer, epoch: epoch)
        await recalculate(epoch: epoch, key: key)
        status = authorized ? "Badge and collector claim saved." : "Badge saved. Issuer authority remains unverified."
        return badge
    }
    func claimUnbound() async throws {
        let (signer, key, epoch) = try begin(); defer { finish(epoch) }
        var next = wallet
        for i in next.badges.indices where next.badges[i].claimSig.isEmpty && !next.badges[i].isOrganizer {
            next.badges[i] = try await BadgeClaim.bind(next.badges[i], signer: signer, timestamp: Self.now, retroactive: true)
            try assertCurrent(epoch, key: key)
        }
        try await persist(next, signer: signer, epoch: epoch); await recalculate(epoch: epoch, key: key)
    }
    func deleteBadge(_ id: String) async throws {
        let (signer, key, epoch) = try begin(); defer { finish(epoch) }
        var next = wallet; next.badges.removeAll { $0.id == id }
        try await persist(next, signer: signer, epoch: epoch); await recalculate(epoch: epoch, key: key)
        status = "Badge removed locally. Other device copies may return during sync."
    }
    func refreshRegistry() async throws {
        let (signer, key, epoch) = try begin(); defer { finish(epoch) }
        var snapshot = registry
        var queried: Set<String> = []
        for _ in 0..<4 {
            let authors = snapshot.publicKeys.union([RegistryAuthority.bootstrapPublicKey]).subtracting(queried)
            guard !authors.isEmpty else { break }; queried.formUnion(authors)
            let events = try await relay.query(NostrFilter(authors: Array(authors.prefix(100)), kinds: [30078], limit: 500,
                                                          tags: ["d": [RegistryAuthority.dTag]]), relays: relays, timeout: 8)
            snapshot = await RegistryAuthority.resolve(events, previous: snapshot, now: Self.now)
            try assertCurrent(epoch, key: key)
        }
        let promotions = try await relay.query(NostrFilter(kinds: [30021], limit: 300, tags: ["d": ["einundzwanzig-admin-claim"]]), relays: relays, timeout: 8)
        snapshot = await RegistryAuthority.resolve(promotions, previous: snapshot, now: Self.now)
        try assertCurrent(epoch, key: key)
        var next = wallet; next.registry = snapshot
        try await persist(next, signer: signer, epoch: epoch); await recalculate(epoch: epoch, key: key)
        status = "Registry updated from signed source events."
    }
    func sync() async throws {
        let (signer, key, epoch) = try begin(); defer { finish(epoch) }
        let filter = NostrFilter(authors: [key], kinds: [30078], limit: 200, tags: ["d": (0..<16).map(BadgeSyncFormat.dTag)])
        let events = try await relay.query(filter, relays: relays, timeout: 10)
        var latest: [String: NostrEvent] = [:]
        for event in events where filter.matches(event) {
            guard event.createdAt <= Self.now + 300, await crypto.verify(event), let d = event.tags.first(where: { $0.count > 1 && $0[0] == "d" })?[1] else { continue }
            if let old = latest[d], old.createdAt > event.createdAt || (old.createdAt == event.createdAt && old.id < event.id) { continue }; latest[d] = event
        }
        var envelopes: [BadgeSyncEnvelope] = []
        for (d, event) in latest {
            guard let env = try? await BadgeSyncFormat.decode(event.content, signer: signer, publicKey: key), BadgeSyncFormat.dTag(env.index) == d else { throw BadgeProtocolError.incompleteSync }
            envelopes.append(env)
        }
        try assertCurrent(epoch, key: key)
        let remote = try BadgeSyncFormat.coherentSnapshot(envelopes)
        var next = wallet; next.badges = CollectedBadge.merge(next.badges, remote)
        try await persist(next, signer: signer, epoch: epoch)
        let plaintexts = try BadgeSyncFormat.plaintexts(next.badges, generation: Self.now)
        var hashes: [String] = []
        for (i, plain) in plaintexts.enumerated() {
            let env = try BadgeSyncEnvelope.parse(plain)
            let hash = BadgeWire.sha256(env.badges.map(\.wireJSON).joined(separator: ",")); hashes.append(hash)
            if i < lastSyncHashes.count, lastSyncHashes[i] == hash { continue }
            let content = try await signer.encrypt(try BadgeGzip.deflate(Data(plain.utf8)).base64EncodedString(), to: key)
            try assertCurrent(epoch, key: key)
            let now = Self.now, tags = [["d", BadgeSyncFormat.dTag(i)], ["client", "einundzwanzig-meetup-app"]]
            let event = try await signer.sign(kind: 30078, tags: tags, content: content, createdAt: now)
            try await BadgeSecurity.requireSigned(event, signer: signer, kind: 30078, tags: tags, content: content, createdAt: now)
            try assertCurrent(epoch, key: key)
            try await relay.publish(event, relays: relays, signer: signer)
            try assertCurrent(epoch, key: key)
        }
        lastSyncHashes = hashes; await recalculate(epoch: epoch, key: key); status = "Encrypted wallet synchronized."
    }
    private func newestVerified(_ events: [NostrEvent], filter: NostrFilter) async -> NostrEvent? {
        for event in events.sorted(by: { $0.createdAt == $1.createdAt ? $0.id < $1.id : $0.createdAt > $1.createdAt }) {
            if filter.matches(event), event.createdAt <= Self.now + 300, await crypto.verify(event) { return event }
        }
        return nil
    }

    func startSession(meetupID: String, blockHeight: Int, hours: Int = 4, deviceLocation: BadgeCoordinate? = nil) async throws {
        let (signer, key, epoch) = try begin(); defer { finish(epoch) }
        let id = meetupID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty, id.utf8.count <= 1024, blockHeight >= 0, (1...4).contains(hours) else { throw BadgeProtocolError.malformed }
        var allowed = registry.isAuthorized(key) || trust.canOrganize
        if !allowed, let ownMeetupAuthority { allowed = try await ownMeetupAuthority(key, id) }
        guard allowed else { throw BadgeProtocolError.unauthorized }
        try BadgePresence.issuer(device: deviceLocation, reference: meetupReference?(id), event: false)
        let now = Self.now
        let payload = try await BadgeSecurity.signCompact(meetupID: id, blockHeight: blockHeight, expiresAt: now + hours * 3600, signer: signer, createdAt: now)
        var next = wallet
        let session = BadgeOrganizerSession(owner: key, meetupID: id, expiresAt: now + hours * 3600, payload: payload)
        let marker = try await session.attendanceMarker(signer: signer, location: deviceLocation, now: now)
        try assertCurrent(epoch, key: key)
        next.session = session; next.badges = CollectedBadge.merge(next.badges, [marker])
        try await persist(next, signer: signer, epoch: epoch)
        status = "Signed organizer session created."
    }
    func startEventSession(event: NostrEvent, title: String, blockHeight: Int, deviceLocation: BadgeCoordinate? = nil) async throws {
        let (signer, key, epoch) = try begin(); defer { finish(epoch) }
        guard let d = event.tags.first(where: { $0.count > 1 && $0[0] == "d" })?[1] else { throw BadgeProtocolError.malformed }
        let address = "\(event.kind):\(event.pubkey):\(d)", now = Self.now
        guard await BadgeEventAuthority.verify(address: address, signer: key, issuedAt: now, event: event, registry: registry, now: now) else { throw BadgeProtocolError.unauthorized }
        try BadgePresence.issuer(device: deviceLocation, reference: BadgePresence.eventCoordinate(event), event: true)
        let start = event.tags.first(where: { $0.count > 1 && $0[0] == "start" })?[1] ?? ""
        let end = event.tags.first(where: { $0.count > 1 && $0[0] == "end" })?[1] ?? start
        let startsAt = Int(start) ?? Int((BadgeWire.date(start + "T00:00:00.000Z") ?? .distantFuture).timeIntervalSince1970)
        let endsAt = Int(end) ?? Int((BadgeWire.date(end + "T23:59:59.000Z") ?? .distantPast).timeIntervalSince1970)
        let calendar = Calendar.current
        let firstDay = calendar.startOfDay(for: Date(timeIntervalSince1970: Double(startsAt)))
        let lastDay = calendar.startOfDay(for: Date(timeIntervalSince1970: Double(max(startsAt, endsAt))))
        guard let until = calendar.date(byAdding: .day, value: 1, to: lastDay) else { throw BadgeProtocolError.malformed }
        guard Date(timeIntervalSince1970: Double(now)) >= firstDay, Date(timeIntervalSince1970: Double(now)) < until else { throw BadgeProtocolError.expired }
        let slug = title.lowercased().replacingOccurrences(of: #"[^a-z0-9äöüß\s-]"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"\s+"#, with: "-", options: .regularExpression)
        let id = "evt:" + address + ":" + slug
        let expiry = Int(until.timeIntervalSince1970)
        let payload = try await BadgeSecurity.signCompact(meetupID: id, blockHeight: blockHeight, expiresAt: expiry, signer: signer, createdAt: now)
        let session = BadgeOrganizerSession(owner: key, meetupID: id, expiresAt: expiry, payload: payload)
        let marker = try await session.attendanceMarker(signer: signer, location: deviceLocation, now: now)
        try assertCurrent(epoch, key: key)
        var next = wallet; next.session = session; next.badges = CollectedBadge.merge(next.badges, [marker])
        next.eventEvidence.append(event); try await persist(next, signer: signer, epoch: epoch)
        status = "Signed organizer session created."
    }
    func endSession() async throws {
        let (signer, _, epoch) = try begin(); defer { finish(epoch) }
        var next = wallet; next.session = nil; try await persist(next, signer: signer, epoch: epoch)
    }
    func addVouch(npub: String, meetup: String, name: String) async throws {
        let (signer, key, epoch) = try begin(); defer { finish(epoch) }
        let publicKey = try NIP19.decodePublicKey(npub)
        guard publicKey != key else { throw BadgeProtocolError.unauthorized }
        var next = wallet
        guard !next.myVouches.contains(where: { $0.npub == npub }) else { return }
        next.myVouches.append(AdminRegistryEntry(npub: try NIP19.encodePublicKey(publicKey), meetup: meetup, name: name, addedAt: Self.now))
        try await persist(next, signer: signer, epoch: epoch)
    }
    func removeVouch(_ npub: String) async throws {
        let (signer, _, epoch) = try begin(); defer { finish(epoch) }
        var next = wallet; next.myVouches.removeAll { $0.npub == npub }; try await persist(next, signer: signer, epoch: epoch)
    }
    func publishVouches(revokeAll: Bool = false) async throws {
        let (signer, key, epoch) = try begin(); defer { finish(epoch) }
        var next = wallet; if revokeAll { next.myVouches = [] }
        let content = RegistryAuthority.listContent(next.myVouches, date: Date()), now = Self.now, tags = [["d", RegistryAuthority.dTag]]
        let event = try await signer.sign(kind: 30078, tags: tags, content: content, createdAt: now)
        try await BadgeSecurity.requireSigned(event, signer: signer, kind: 30078, tags: tags, content: content, createdAt: now)
        try assertCurrent(epoch, key: key)
        try await relay.publish(event, relays: relays, signer: signer)
        try assertCurrent(epoch, key: key)
        next.registry = await RegistryAuthority.resolve([event], previous: next.registry, now: now)
        try await persist(next, signer: signer, epoch: epoch); await recalculate(epoch: epoch, key: key)
        status = "Signed vouch list acknowledged by relay."
    }
    func recoverVouches() async throws {
        let (signer, key, epoch) = try begin(); defer { finish(epoch) }
        let filter = NostrFilter(authors: [key], kinds: [30078], limit: 20, tags: ["d": [RegistryAuthority.dTag]])
        let events = try await relay.query(filter, relays: relays, timeout: 8)
        guard let event = await newestVerified(events, filter: filter) else { status = "No signed vouch list found."; return }
        let entries = try RegistryAuthority.entries(event)
        guard !entries.isEmpty else { status = "The relay list is empty; local unpublished vouches were preserved."; return }
        var next = wallet; next.myVouches = entries; try await persist(next, signer: signer, epoch: epoch)
    }
    func publishPromotion(meetup: String) async throws {
        let (signer, key, epoch) = try begin(); defer { finish(epoch) }
        guard trust.canOrganize else { throw BadgeProtocolError.unauthorized }
        let counted = trust.verifiedBadges
        let proofs = counted.map { b -> BadgeJSON in .object([
            "meetup": .string(b.meetupName), "date": .string(b.date), "block": .int(b.blockHeight), "sig": .string(b.sig),
            "sig_id": .string(b.sigId), "admin_pubkey": .string(b.adminPubkey), "sig_version": .int(b.sigVersion),
            "sig_content": .string(b.sigContent), "claim_sig": .string(b.claimSig), "claim_event_id": .string(b.claimEventId), "claim_timestamp": .int(b.claimTimestamp)]) }
        let now = Self.now, tags = [["d", "einundzwanzig-admin-claim"], ["meetup", meetup]]
        let content = BadgeWire.ordered([("type", .string("admin_claim")), ("version", .int(1)), ("claimer_npub", .string(try NIP19.encodePublicKey(key))),
                                        ("meetup", .string(meetup)), ("verified_badges", .int(counted.count)), ("unique_signers", .int(trust.signerCount)),
                                        ("proofs", .array(proofs)), ("claimed_at", .int(now))])
        let event = try await signer.sign(kind: 30021, tags: tags, content: content, createdAt: now)
        try await BadgeSecurity.requireSigned(event, signer: signer, kind: 30021, tags: tags, content: content, createdAt: now)
        try assertCurrent(epoch, key: key); try await relay.publish(event, relays: relays, signer: signer)
        try assertCurrent(epoch, key: key); status = "Promotion proof published; other clients verify the badge evidence."
    }
    func publishAttendance(_ badge: CollectedBadge) async throws {
        let (signer, key, epoch) = try begin(); defer { finish(epoch) }
        let event = try await SocialNetwork.signedAttendance(badge, signer: signer, now: Self.now)
        try assertCurrent(epoch, key: key); try await relay.publish(event, relays: relays, signer: signer)
        try assertCurrent(epoch, key: key); status = "Opt-in attendance acknowledged by relay."
    }
    func refreshSocial(target: String = "", forceFull: Bool = false) async throws {
        let (signer, key, epoch) = try begin(); defer { finish(epoch) }
        let trimmed = target.trimmingCharacters(in: .whitespacesAndNewlines)
        let other: String? = trimmed.isEmpty ? nil : try await SocialNetwork.personPublicKey(trimmed, now: Self.now)
        try assertCurrent(epoch, key: key)
        socialLoadingDepth = 0
        let result = try await SocialGraphLoader(relay: other == nil ? relay : publicRelay).load(owner: key, target: other, organizers: registry.publicKeys,
            previous: social, forceFull: forceFull, relays: relays, now: Self.now) { [weak self] snapshot, depth in
                await MainActor.run {
                    guard let self, self.generation == epoch, self.publicKey == key else { return }
                    self.social = snapshot; self.socialLoadingDepth = depth
                }
            }
        try assertCurrent(epoch, key: key)
        var next = wallet; next.social = result; try await persist(next, signer: signer, epoch: epoch)
        status = result.stale ? "Relay observations are incomplete; the encrypted cached network was preserved." : "Signed social self-attestations loaded."
    }
    func createPlatformProof(platform: String, username: String) async throws -> String {
        let (signer, key, epoch) = try begin(); defer { finish(epoch) }
        let proof = try await PlatformIdentityProof.create(platform: platform, username: username, signer: signer, now: Self.now)
        var next = wallet; next.platformProofs.removeAll { $0.platform == platform }; next.platformProofs.append(proof)
        Self.removePendingPlatform(platform, from: &next.pendingBackup)
        try await persist(next, signer: signer, epoch: epoch)
        reputationQR = ""
        return try proof.verifyString(publicKey: key)
    }
    func removePlatformProof(_ id: String) async throws {
        let (signer, _, epoch) = try begin(); defer { finish(epoch) }
        var next = wallet; next.platformProofs.removeAll { $0.id == id }
        Self.removePendingPlatform(id, from: &next.pendingBackup)
        try await persist(next, signer: signer, epoch: epoch)
        reputationQR = ""
    }
    func verifyPlatformProof(_ text: String, expectedUsername: String?) async throws -> PlatformIdentityProof {
        let (_, key, epoch) = try begin(); defer { finish(epoch) }
        let parts = text.components(separatedBy: "::")
        guard parts.count == 5 else { throw BadgeProtocolError.malformed }
        let author = try NIP19.decodePublicKey(parts[1])
        let filter = NostrFilter(authors: [author], kinds: [30078], limit: 20, tags: ["d": [ReputationCard.dTag]])
        guard let event = await newestVerified(try await publicRelay.query(filter, relays: relays, timeout: 8), filter: filter) else { throw BadgeProtocolError.invalidSignature }
        let proof = try await PlatformIdentityProof.verify(text, reputation: event, expectedUsername: expectedUsername, now: Self.now)
        try assertCurrent(epoch, key: key); return proof
    }
    func personIdentity(_ target: String) async throws -> PersonIdentityEvidence {
        let (_, key, epoch) = try begin(); defer { finish(epoch) }
        let person = try await SocialNetwork.personPublicKey(target, now: Self.now)
        try assertCurrent(epoch, key: key)
        let result = try await ProofVerifier.personIdentity(publicKey: person, relay: publicRelay, relays: relays, http: proofHTTP, now: Self.now)
        try assertCurrent(epoch, key: key)
        return result
    }
    func personZapActivity(_ target: String) async throws -> PersonZapActivity {
        let (_, key, epoch) = try begin(); defer { finish(epoch) }
        let person = try await SocialNetwork.personPublicKey(target, now: Self.now)
        try assertCurrent(epoch, key: key)
        let result = try await PersonZapActivityLoader(relay: publicRelay, http: proofHTTP).load(publicKey: person, relays: relays, now: Self.now)
        try assertCurrent(epoch, key: key)
        return result
    }
    func reputationImageSummary(nickname: String) throws -> ReputationImageSummary {
        guard !walletUnreadable else { throw BadgeStoreError.unreadableWallet }
        guard walletLoaded, signer != nil, !publicKey.isEmpty else { throw BadgeStoreError.noSigner }
        try assertCurrent(generation, key: publicKey)
        return ReputationImageSummary(publicKey: publicKey, nickname: nickname, trust: trust)
    }
    func checkNIP05(_ identifier: String, author: String? = nil) async throws -> Bool {
        let (_, key, epoch) = try begin(); defer { finish(epoch) }
        let result = try await ProofVerifier.verifyNIP05(identifier, publicKey: author ?? key, http: proofHTTP)
        try assertCurrent(epoch, key: key); return result
    }
    func checkHumanity() async throws {
        let (signer, key, epoch) = try begin(); defer { finish(epoch) }
        var receipts = try await relay.query(NostrFilter(kinds: [9735], limit: 30, tags: ["p": [key]]), relays: relays, timeout: 8)
        receipts += try await relay.query(NostrFilter(kinds: [9735], limit: 30, tags: ["P": [key]]), relays: relays, timeout: 8)
        for receipt in receipts.sorted(by: { $0.createdAt < $1.createdAt }) {
            guard let local = try? await ProofVerifier.localReceipt(receipt, now: Self.now) else { continue }
            let filter = NostrFilter(authors: [local.recipient], kinds: [0], limit: 20)
            let events = try await relay.query(filter, relays: relays, timeout: 6)
            guard let metadata = await newestVerified(events, filter: filter),
                  let checked = try? await ProofVerifier.humanity(receipt, identity: key, recipientMetadata: metadata, http: proofHTTP, now: Self.now) else { continue }
            var next = wallet; next.humanity = checked; next.pendingBackup.removeValue(forKey: "humanity_proof")
            try await persist(next, signer: signer, epoch: epoch); status = "Zap receipt and recipient provider verified."; return
        }
        try assertCurrent(epoch, key: key)
        var next = wallet; Self.stageHumanityForRevalidation(in: &next)
        try await persist(next, signer: signer, epoch: epoch)
        status = "No verified provider receipt was found."
    }
    func publishReputation(nickname: String, force: Bool = false) async throws {
        let (signer, key, epoch) = try begin(); defer { finish(epoch) }
        guard force || Self.now - lastPublishAt >= 300 else { status = "Reputation was published less than five minutes ago."; return }
        let trusted = trust.verifiedBadges
        guard !trusted.isEmpty else { throw BadgeProtocolError.invalidClaim }
        let now = max(Self.now, lastPublishAt + 1), tags = [["d", ReputationCard.dTag], ["t", "einundzwanzig"], ["t", "reputation"]]
        let content = ReputationCard.relayContent(badges: trusted, score: trust, nickname: nickname, platformProofs: platformProofs, humanity: humanity, now: now)
        let event = try await signer.sign(kind: 30078, tags: tags, content: content, createdAt: now)
        try await BadgeSecurity.requireSigned(event, signer: signer, kind: 30078, tags: tags, content: content, createdAt: now)
        try assertCurrent(epoch, key: key); try await relay.publish(event, relays: relays, signer: signer)
        try assertCurrent(epoch, key: key); lastPublishAt = now; status = "Signed reputation acknowledged by relay."
    }
    func generateReputationQR(nickname: String) async throws {
        let (signer, key, epoch) = try begin(); defer { finish(epoch) }
        let trusted = trust.verifiedBadges
        guard !trusted.isEmpty else { throw BadgeProtocolError.invalidClaim }
        let payload = try ReputationCard.qrPayload(badges: trusted, score: trust, nickname: nickname, publicKey: key, platformProofs: platformProofs, now: Self.now)
        let qr = try await ReputationCard.signQR(payload, signer: signer, now: Self.now)
        try assertCurrent(epoch, key: key); reputationQR = qr
    }
    func verifyReputation(_ input: String, badgeEvidence: [CollectedBadge]? = nil) async throws -> VerifiedReputationCard {
        let (_, key, epoch) = try begin(); defer { finish(epoch) }
        var authorizedSources: Set<String> = []
        for badge in badgeEvidence ?? [] {
            guard let source = try? await BadgeSecurity.verify(badge.sigContent, now: Self.now, checkExpiry: false, checkRolling: false) else { continue }
            if let address = source.eventAddress, let filter = try? BadgeEventAuthority.filter(for: address),
               let events = try? await publicRelay.query(filter, relays: relays, timeout: 8), let event = await newestVerified(events, filter: filter) {
                if await BadgeEventAuthority.verify(address: address, signer: source.event.pubkey, issuedAt: source.event.createdAt, event: event, registry: registry, now: Self.now) { authorizedSources.insert(source.event.id) }
            } else if source.eventAddress == nil, let portalAuthority, (try? await portalAuthority(source)) == true { authorizedSources.insert(source.event.id) }
            try assertCurrent(epoch, key: key)
        }
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        let result: VerifiedReputationCard
        if text.hasPrefix("21v3:") { result = try await ReputationCard.verifyQR(text, badges: badgeEvidence, registry: registry, authorizedSources: authorizedSources, now: Self.now) }
        else {
            let author = try await SocialNetwork.personPublicKey(text, now: Self.now)
            let filter = NostrFilter(authors: [author], kinds: [30078], limit: 20, tags: ["d": [ReputationCard.dTag]])
            guard let event = await newestVerified(try await publicRelay.query(filter, relays: relays, timeout: 8), filter: filter) else { throw BadgeProtocolError.invalidSignature }
            result = try await ReputationCard.verifyEvent(event, badges: badgeEvidence, registry: registry, authorizedSources: authorizedSources, now: Self.now)
        }
        try assertCurrent(epoch, key: key); return result
    }

    /// Full evidence is deliberately exported only by an explicit user action.
    /// This array can also be presented to the reputation verifier.
    func exportBadgeEvidence() throws -> String {
        _ = try backupBadges()
        return "[" + badges.map(\.wireJSON).joined(separator: ",") + "]"
    }
    func platformProofString(_ id: String) throws -> String {
        guard !walletUnreadable else { throw BadgeStoreError.unreadableWallet }
        guard walletLoaded, signer != nil, !publicKey.isEmpty else { throw BadgeStoreError.noSigner }
        guard let proof = platformProofs.first(where: { $0.id == id }) else { throw BadgeProtocolError.malformed }
        return try proof.verifyString(publicKey: publicKey)
    }
    func backupBadges() throws -> [BackupValue] {
        guard !walletUnreadable else { throw BadgeStoreError.unreadableWallet }
        guard walletLoaded, signer != nil, !publicKey.isEmpty else { throw BadgeStoreError.noSigner }
        return try badges.map { try JSONDecoder().decode(BackupValue.self, from: Data($0.wireJSON.utf8)) }
    }
    /// A synchronous, owner-fenced snapshot taken only after configuration has
    /// finished loading durable ciphertext. Pending evidence grants no authority.
    func backupSnapshot(owner: String) throws -> [String: BackupValue] {
        try assertCurrent(generation, key: owner)
        var fields = Self.safeProofMetadata(.object(pendingBackup)).object ?? [:]
        fields["badges"] = .array(try backupBadges())
        var proofs = fields["platform_proofs"]?.array ?? []
        for proof in platformProofs {
            proofs.removeAll { $0.object?["platform"]?.string == proof.platform }
            proofs.append(try JSONDecoder().decode(BackupValue.self, from: JSONEncoder().encode(proof)))
        }
        if fields["platform_proofs"] != nil || !proofs.isEmpty { fields["platform_proofs"] = .array(proofs) }
        if let humanity { fields["humanity_proof"] = Self.pendingHumanity(humanity) }
        return fields
    }
    private static func pendingHumanity(_ proof: HumanityReceipt) -> BackupValue {
        .object(["verified": .bool(false), "needs_reverification": .bool(true), "method": .string("lightning_zap"),
                 "first_zap_at": .number(Double(proof.timestamp)), "receipt_id": .string(proof.receipt.id),
                 "receipt_event_id": .string(proof.receipt.id)])
    }
    /// Preserve historical evidence while dropping cached provider authority.
    private static func stageHumanityForRevalidation(in value: inout Wallet) {
        if let proof = value.humanity { value.pendingBackup["humanity_proof"] = pendingHumanity(proof) }
        value.humanity = nil
    }
    private static func removePendingPlatform(_ platform: String, from fields: inout [String: BackupValue]) {
        if let proofs = fields["platform_proofs"]?.array {
            fields["platform_proofs"] = .array(proofs.filter { $0.object?["platform"]?.string != platform })
        }
    }
    func restoreBackup(_ document: BackupDocument, decision: BackupDocument.RestoreDecision) async throws {
        let (signer, key, epoch) = try begin(); defer { finish(epoch) }
        var incoming: [CollectedBadge] = []
        for raw in document.fields["badges"]?.array ?? [] {
            let data = try JSONEncoder().encode(raw)
            let value = try JSONDecoder().decode(BadgeJSON.self, from: data)
            let badge = try CollectedBadge(json: value.object)
            // Flutter v1 HMAC rows are historical collection data, never v2
            // authority. All purported current Schnorr sources remain strict.
            if badge.sigVersion >= 2 || (badge.sigVersion != 1 && !badge.sig.isEmpty) {
                guard await BadgeSecurity.verifyStored(badge, collector: nil, now: Self.now) else { throw BadgeProtocolError.invalidSignature }
            }
            if !badge.claimSig.isEmpty {
                guard let claim = try? BadgeClaim.event(for: badge), await crypto.verify(claim) else { throw BadgeProtocolError.invalidClaim }
            }
            incoming.append(badge)
        }
        var next = wallet
        next.pendingBackup = Self.safeProofMetadata(.object(next.pendingBackup)).object ?? [:]
        next.badges = (decision == .fresh || decision == .replacesIdentity) ? CollectedBadge.merge(incoming, []) : CollectedBadge.merge(next.badges, incoming)
        // Restored registry/admin booleans are never promoted to authority.
        // Preserve raw evidence for a later explicit relay revalidation.
        for name in ["humanity_proof", "platform_proofs", "admin_registry", "my_vouches", "co_attendance"] {
            if let value = document.fields[name] {
                let safe = Self.safeProofMetadata(value)
                if name == "humanity_proof" {
                    next.pendingBackup[name] = BackupDocument.mergePendingHumanity(next.pendingBackup[name], incoming: safe)
                    continue
                }
                if let old = next.pendingBackup[name]?.array, let incoming = safe.array {
                    next.pendingBackup[name] = .array(old + incoming.filter { !old.contains($0) })
                } else if safe != .null { next.pendingBackup[name] = safe }
            }
        }
        // Recheck proof signatures for this owner locally. Provider/authority
        // claims remain pending; preserving an exact signature is not trust.
        for raw in document.fields["platform_proofs"]?.array ?? [] {
            guard let proof = try? JSONDecoder().decode(PlatformIdentityProof.self, from: JSONEncoder().encode(raw)),
                  let event = try? proof.event(publicKey: key), await crypto.verify(event) else { continue }
            if !next.platformProofs.contains(where: { $0.platform == proof.platform }) { next.platformProofs.append(proof) }
        }
        Self.stageHumanityForRevalidation(in: &next)
        try assertCurrent(epoch, key: key); try await persist(next, signer: signer, epoch: epoch)
        await recalculate(epoch: epoch, key: key); status = "Badge signatures restored; imported authority claims await revalidation."
    }
    private static func safeProofMetadata(_ value: BackupValue) -> BackupValue {
        BackupDocument.redactedSecretMaterial(value)
    }
}

enum BadgeStoreError: LocalizedError, Equatable {
    case persistenceDeletion(String), identityChanged, noSigner, unreadableWallet
    var errorDescription: String? {
        switch self {
        case .persistenceDeletion(let reason): "Wallet could not be removed: \(reason)"
        case .identityChanged: "The signing identity changed. Please try again."
        case .noSigner: "Unlock a signing identity first."
        case .unreadableWallet: "The existing encrypted wallet could not be read. It has been preserved."
        }
    }
}

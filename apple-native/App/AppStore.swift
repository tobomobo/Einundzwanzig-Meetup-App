import Foundation
import Observation
#if !APP_STORE_LIFECYCLE_TESTS
import SwiftUI
#endif
import MeetupCore
import MeetupNostr

@MainActor @Observable
final class AppStore {
    private(set) var profile = LocalProfile()
    private(set) var preferences = LocalPreferences()
    private(set) var cache = DataCache()
    private(set) var bitcoin: BitcoinSnapshot?
    private(set) var bitcoinDashboard: BitcoinDashboard?
    private(set) var loading = false
    private(set) var bitcoinLoading = false
    private(set) var associatedEvents: [MeetupEvent] = []
    private(set) var calendarRefreshedThisSession = false
    private(set) var calendarLastAttempt: Date?
    let learning: LearningStore
    let media = NativeMediaPlayer()
    let relay: NostrRelayClient
    let identity: IdentityStore
    let badgeWallet: BadgeStore
    let workflow: WorkflowStore
    let leadership = PortalLeadership()
    private(set) var networkIssues: [String] = []
    private(set) var diagnostics: [String] = []
    var persistenceIssue: String?
    var selectedSection: AppSection = .home
    var scanPresented = false
    var settingsPresented = false
    var bitcoinPresented = false
    private var stateReadable = true
    private var profileReadable = true
    private var refreshGeneration = UUID()
    private var bitcoinGeneration = UUID()
    @ObservationIgnored private var identityConfiguration: (generation: UUID, task: Task<Void, Never>)?
    private let portal = PortalClient()
    private let newsClient = NewsClient()
    private let mempool = MempoolClient()
    private let calendarClient = CalendarClient()
    private let directory: URL

    init(legacyDefaults: UserDefaults = .standard, identityTransport: (any NostrRelayTransport)? = nil) {
        learning = LearningStore(cacheFile: UITestFixtureMode.directory.appendingPathComponent("public-learning-cache.json"))
        let fixturePreparationError: String?
        do { try UITestFixtureMode.prepare(); fixturePreparationError = nil } catch { fixturePreparationError = error.localizedDescription }
        relay = UITestFixtureMode.relay()
        identity = IdentityStore(transport: identityTransport ?? relay, legacySource: FlutterUpgradeSource(defaults: legacyDefaults, allowAccess: !UITestFixtureMode.enabled))
        badgeWallet = BadgeStore(relay: relay, persistence: KeychainBadgeWalletPersistence())
        workflow = WorkflowStore(relay: relay, transport: UITestFixtureMode.http)
        directory = UITestFixtureMode.directory
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                                                    attributes: [.posixPermissions: 0o700])
            if FileManager.default.fileExists(atPath: file("preferences.json").path) {
                preferences = try JSONDecoder().decode(LocalPreferences.self, from: Data(contentsOf: file("preferences.json")))
            }
        } catch {
            stateReadable = false
            persistenceIssue = text("error.stateRead")
        }
        do {
            #if os(macOS)
            // The unsigned Mac prototype stores PUBLIC profile fields with normal
            // file protection. macOS data-protection Keychain requires provisioning.
            if FileManager.default.fileExists(atPath: file("public-profile.json").path) {
                profile = try JSONDecoder().decode(LocalProfile.self, from: Data(contentsOf: file("public-profile.json")))
            }
            #else
            if let data = try KeychainStore.read(account: "local-public-profile-v1") {
                profile = try JSONDecoder().decode(LocalProfile.self, from: data)
            }
            #endif
        } catch {
            profileReadable = false
            persistenceIssue = text("error.profileRead")
        }
        if let data = try? Data(contentsOf: file("public-cache.json")),
           let cached = try? JSONDecoder().decode(DataCache.self, from: data) {
            cache = cached
        }
        associatedEvents = MeetupEventMatcher.associated(cache.events, with: cache.meetups)
        if let fixturePreparationError { persistenceIssue = fixturePreparationError }
        if UITestFixtureMode.enabled { preferences.language = "en" }
        badgeWallet.meetupReference = { [weak self] signedID in
            guard let self, let meetup = MeetupIdentifier.resolve(signedID, among: meetups),
                  let latitude = meetup.latitude, let longitude = meetup.longitude else { return nil }
            return try? BadgeCoordinate(latitude: latitude, longitude: longitude)
        }
        badgeWallet.ownMeetupAuthority = { [weak self] key, signedID in
            guard let self, let meetup = MeetupIdentifier.resolve(signedID, among: meetups) else { return false }
            return try await leadership.isLeader(publicKey: key, meetupID: meetup.id)
        }
        badgeWallet.portalAuthority = { [weak self] source in
            guard let self, let meetup = MeetupIdentifier.resolve(source.meetupID, among: meetups) else { return false }
            return try await leadership.isLeader(publicKey: source.event.pubkey, meetupID: meetup.id)
        }
        #if DEBUG
        if UITestFixtureMode.enabled {
            badgeWallet.portalAuthority = { source in
                source.meetupID == "fixture-city-at" && source.event.pubkey == "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
            }
        }
        #endif
        identity.beforeIdentityChange = { [weak self] in
            guard let self else { throw IdentityError.identityChanged }
            // Revoke network credentials before deleting identity-scoped proof data.
            try await workflow.clearIdentity()
            try await badgeWallet.prepareIdentityChange()
            try KeychainStore.delete(account: "pending-imported-proofs-v1")
            await relay.setSigner(nil)
        }
        identity.backupSnapshot = { [weak self] in
            guard let self else { throw IdentityError.identityChanged }
            let epoch = identity.generation, owner = identity.publicKey
            guard identity.state == .unlocked, identity.signer != nil, !preferences.demo else { throw IdentityError.locked }
            await synchronizeIdentity()
            guard epoch == identity.generation, owner == identity.publicKey else { throw IdentityError.identityChanged }
            var fields = try badgeWallet.backupSnapshot(owner: owner)
            fields["version"] = .number(5); fields["exported_at"] = .string(ISO8601DateFormatter().string(from: .now))
            fields["user"] = .object(try UserContinuity.fields(for: profile, favoriteIDs: preferences.favoriteIDs, activeNpub: identity.npub))
            if let pending = try KeychainStore.read(account: "pending-imported-proofs-v1"),
               let original = try? BackupDocument.decode(pending) {
                let saved = original.publicPendingSnapshot()
                if saved != original { try KeychainStore.write(saved.encoded(), account: "pending-imported-proofs-v1") }
                // The loaded wallet owns these sections; a stale imported copy
                // cannot resurrect removed proofs. Other public sections survive.
                let owned: Set<String> = ["version", "exported_at", "user", "badges", "humanity_proof", "platform_proofs", "admin_registry", "my_vouches", "co_attendance"]
                for (key, value) in saved.fields where !owned.contains(key) && fields[key] == nil { fields[key] = value }
            }
            return try BackupDocument(fields: fields)
        }
        identity.restoreBackupData = { [weak self] document, decision in
            guard let self else { throw IdentityError.identityChanged }
            let epoch = identity.generation
            await synchronizeIdentity()
            guard epoch == identity.generation else { throw IdentityError.identityChanged }
            try await badgeWallet.restoreBackup(document, decision: decision)
            guard epoch == identity.generation else { throw IdentityError.identityChanged }
            var safe = document.publicPendingSnapshot()
            safe.fields["user"] = .object(UserContinuity.sanitize(safe.fields["user"]?.object ?? [:]))
            // Missing/empty proof sections in an additive import cannot erase
            // evidence staged by an earlier restore. Current wallet data wins.
            if decision == .sameIdentity || decision == .addToIdentity,
               let bytes = try KeychainStore.read(account: "pending-imported-proofs-v1") {
                let previous = try BackupDocument.decode(bytes).publicPendingSnapshot()
                safe.fields["humanity_proof"] = BackupDocument.mergePendingHumanity(previous.fields["humanity_proof"], incoming: safe.fields["humanity_proof"])
                for key in ["platform_proofs", "reputation", "social_graph"] {
                    if safe.fields[key] == nil || safe.fields[key] == .null || safe.fields[key] == .array([]) {
                        safe.fields[key] = previous.fields[key]
                    }
                }
                let owned: Set<String> = ["version", "exported_at", "user", "badges", "humanity_proof", "platform_proofs", "admin_registry", "my_vouches", "co_attendance"]
                for (key, value) in previous.fields where !owned.contains(key) && safe.fields[key] == nil { safe.fields[key] = value }
            }
            try KeychainStore.write(safe.publicPendingSnapshot().encoded(), account: "pending-imported-proofs-v1")
            let restored = try UserContinuity.restore(safe.fields["user"]?.object ?? [:], current: profile,
                favoriteIDs: preferences.favoriteIDs, decision: decision, activeNpub: identity.npub)
            guard saveProfile(nickname: restored.nickname, npub: restored.npub, fullName: restored.fullName,
                homeMeetupID: restored.homeMeetupID, favoriteMeetupIDs: restored.favoriteMeetupIDs,
                telegramHandle: restored.telegramHandle, twitterHandle: restored.twitterHandle, publicUserFields: restored.publicUserFields)
            else { throw IdentityError.invalidBackup }
        }
    }

    func synchronizeIdentity() async {
        let epoch = identity.generation
        if let existing = identityConfiguration, existing.generation == epoch { await existing.task.value; return }
        identityConfiguration?.task.cancel()
        let signer = preferences.demo ? nil : identity.signer
        let task = Task { @MainActor [weak self] in
            guard let self else { return }
            await relay.setSigner(signer)
            guard epoch == identity.generation, !Task.isCancelled else { return }
            await workflow.configure(signer: signer)
            guard epoch == identity.generation, !Task.isCancelled else { return }
            await badgeWallet.configure(signer: signer)
        }
        identityConfiguration = (epoch, task)
        await task.value
    }

    func synchronizeRelays() {
        let relays = preferences.relays.compactMap(URL.init(string:))
        workflow.relays = relays
        badgeWallet.relays = relays
    }

    var meetups: [Meetup] { preferences.demo ? PreviewFixtures.meetups : cache.meetups }
    var events: [MeetupEvent] { preferences.demo ? PreviewFixtures.events : associatedEvents }
    var articles: [NewsArticle] { preferences.demo ? PreviewFixtures.articles : cache.articles }
    var badges: [BadgePreview] { preferences.demo ? PreviewFixtures.badges : preferences.badgePreviews }
    var favorites: [Meetup] {
        meetups.filter { isFavorite($0.id) }.sorted {
            let a = nextEvent(for: $0.id)?.start ?? $0.nextEventStart ?? .distantFuture
            let b = nextEvent(for: $1.id)?.start ?? $1.nextEventStart ?? .distantFuture
            return a == b ? $0.name < $1.name : a < b
        }
    }
    var plans: [LocalPlan] {
        Array(preferences.demo ? preferences.demoPlans.values : preferences.plans.values).sorted { $0.event.start < $1.event.start }
    }
    var locale: Locale { preferences.language == "system" ? .autoupdatingCurrent : Locale(identifier: preferences.language) }
    var colorScheme: ColorScheme? {
        switch preferences.appearance { case "light": .light; case "dark": .dark; default: nil }
    }
    func text(_ key: String) -> String { AppLocalization.text(key, language: preferences.language) }
    func errorMessage(_ error: any Error) -> String {
        if let local = error as? LocalDataError { return text(local.localizationKey) }
        return error.localizedDescription
    }

    func nextEvent(for meetupID: String) -> MeetupEvent? {
        events.filter { $0.meetupID == meetupID && CalendarDisplayWindow.includes($0) }
            .min { $0.start < $1.start }
    }
    func plan(for id: String) -> LocalPlan? { preferences.demo ? preferences.demoPlans[id] : preferences.plans[id] }
    var favoriteIDs: Set<String> {
        UserContinuity.resolveFavoriteIDs(preferences.demo ? preferences.demoFavoriteIDs : preferences.favoriteIDs, among: meetups)
    }
    func isFavorite(_ id: String) -> Bool { favoriteIDs.contains(id) }
    func toggleFavorite(_ id: String) {
        let aliases = Set((preferences.demo ? preferences.demoFavoriteIDs : preferences.favoriteIDs).filter {
            UserContinuity.resolveFavorite($0, among: meetups)?.id == id || $0 == id
        })
        updatePreferences { value in
            if value.demo {
                if !aliases.isEmpty { value.demoFavoriteIDs.subtract(aliases) }
                else { value.demoFavoriteIDs.insert(id) }
            }
            else if !aliases.isEmpty { value.favoriteIDs.subtract(aliases) }
            else { value.favoriteIDs.insert(id) }
        }
    }
    @discardableResult
    func saveProfile(nickname: String, npub: String, fullName: String? = nil, homeMeetupID: String? = nil,
                     favoriteMeetupIDs: [String]? = nil, telegramHandle: String? = nil, twitterHandle: String? = nil,
                     publicUserFields: Data? = nil) -> Bool {
        guard profileReadable else { persistenceIssue = text("error.profileRead"); return false }
        let name = nickname.trimmingCharacters(in: .whitespacesAndNewlines)
        let input = npub.trimmingCharacters(in: .whitespacesAndNewlines)
        let identity = input.isEmpty ? nil : try? PublicIdentity(input)
        guard !name.isEmpty, name.count <= 40,
              input.isEmpty || identity != nil
        else { persistenceIssue = text("error.profile"); return false }
        var next = profile
        next.nickname = name; next.npub = identity?.npub ?? ""; next.onboarded = true
        if let fullName { next.fullName = fullName }
        if let homeMeetupID { next.homeMeetupID = homeMeetupID }
        if let favoriteMeetupIDs { next.favoriteMeetupIDs = UserContinuity.ordered(favoriteMeetupIDs) }
        if let telegramHandle { next.telegramHandle = telegramHandle }
        if let twitterHandle { next.twitterHandle = twitterHandle }
        do {
            if let publicUserFields {
                next.publicUserFields = try JSONEncoder().encode(UserContinuity.sanitize(JSONDecoder().decode([String: BackupValue].self, from: publicUserFields)))
            }
            #if os(macOS)
            try persist(next, name: "public-profile.json")
            #else
            try KeychainStore.write(JSONEncoder().encode(next), account: "local-public-profile-v1")
            #endif
            profile = next
            if favoriteMeetupIDs != nil, !updatePreferences({ $0.favoriteIDs = Set(next.favoriteMeetupIDs) }) { return false }
            persistenceIssue = nil
            return true
        } catch {
            persistenceIssue = text("error.profileSave")
            return false
        }
    }

    @discardableResult
    func updatePreferences(_ change: (inout LocalPreferences) -> Void) -> Bool {
        guard stateReadable else { persistenceIssue = text("error.stateRead"); return false }
        var next = preferences
        change(&next)
        do {
            try persist(next, name: "preferences.json")
            if next.mempoolServer != preferences.mempoolServer {
                bitcoinGeneration = UUID()
                bitcoinLoading = false
                bitcoin = nil
                bitcoinDashboard = nil
                networkIssues.removeAll { $0 == "source.bitcoin.failed" }
            }
            preferences = next
            UserDefaults.standard.set(next.mempoolServer, forKey: "public-mempool-server")
            persistenceIssue = nil
            return true
        } catch {
            persistenceIssue = text("error.persistence")
            return false
        }
    }

    func setDemo(_ enabled: Bool) {
        if updatePreferences({ $0.demo = enabled }) {
            identityConfiguration?.task.cancel()
            identityConfiguration = nil
            Task { @MainActor [weak self] in
                guard let self else { return }
                if enabled { await identity.lock() }
                await synchronizeIdentity()
            }
            // An in-flight live refresh cannot replace the demo badge/source label.
            refreshGeneration = UUID()
            learning.invalidateRequests()
            loading = false
            networkIssues.removeAll { $0 != "source.bitcoin.failed" }
            if !enabled { Task { await refresh() } }
        }
    }
    @discardableResult
    func savePlan(event: MeetupEvent, status: PlanStatus?, note: String = "") -> Bool {
        updatePreferences {
            if $0.demo {
                if let status { $0.demoPlans[event.id] = LocalPlan(event: event, status: status, note: String(note.prefix(1000))) }
                else { $0.demoPlans.removeValue(forKey: event.id) }
            } else {
                if let status { $0.plans[event.id] = LocalPlan(event: event, status: status, note: String(note.prefix(1000))) }
                else { $0.plans.removeValue(forKey: event.id) }
            }
        }
    }
    @discardableResult
    func importPreview(_ text: String) throws -> BadgePreview {
        guard !preferences.demo else { throw LocalDataError.unavailable }
        let preview = try PreviewImport.parse(text)
        guard preferences.badgePreviews.count < 500 else { throw LocalDataError.tooLarge }
        guard !preferences.badgePreviews.contains(where: { $0.name == preview.name && $0.blockHeight == preview.blockHeight })
        else { throw LocalDataError.duplicate }
        guard updatePreferences({ $0.badgePreviews.append(preview) }) else { throw LocalDataError.unavailable }
        return preview
    }
    func deletePreview(_ id: UUID) { updatePreferences { $0.badgePreviews.removeAll { $0.id == id } } }
    func markRead(_ article: NewsArticle) { updatePreferences { $0.lastReadArticleIDs.insert(article.id) } }

    func refresh() async {
        guard !loading, !preferences.demo, !UITestFixtureMode.enabled else { return }
        loading = true
        let generation = UUID()
        refreshGeneration = generation
        networkIssues.removeAll { $0 != "source.bitcoin.failed" }
        let portal = self.portal
        let newsClient = self.newsClient
        let calendarClient = self.calendarClient
        calendarLastAttempt = Date()
        async let directoryResult = capture { try await portal.meetups() }
        async let eventsResult = capture { try await calendarClient.snapshot() }
        async let newsResult = capture { try await newsClient.articles() }
        let (directoryResponse, eventResponse, newsResponse) = await (directoryResult, eventsResult, newsResult)
        guard generation == refreshGeneration else { return }
        var updated = cache
        switch directoryResponse {
        case .success(let values): updated.meetups = values
        case .failure: networkIssues.append("source.meetups.failed")
        }
        switch eventResponse {
        case .success(let snapshot):
            updated.events = snapshot.events
            updated.calendarSnapshot = snapshot
            calendarRefreshedThisSession = true
        case .failure: networkIssues.append("source.events.failed")
        }
        switch newsResponse {
        case .success(let values): updated.articles = values
        case .failure: networkIssues.append("source.news.failed")
        }
        // A timestamp is only fresh for a complete refresh; partial refreshes are labelled.
        let portalSucceeded = !networkIssues.contains { $0 == "source.meetups.failed" || $0 == "source.news.failed" }
        if portalSucceeded { updated.fetchedAt = Date() }
        cache = updated
        associatedEvents = MeetupEventMatcher.associated(updated.events, with: updated.meetups)
        do { try persist(updated, name: "public-cache.json") }
        catch { persistenceIssue = text("error.cacheSave") }
        diagnostics.insert("\(Date().formatted()) · portal/rss · \(portalSucceeded ? "OK" : "partial/offline")", at: 0)
        diagnostics = Array(diagnostics.prefix(50))
        loading = false
    }

    func refreshBitcoin() async {
        guard !UITestFixtureMode.enabled else { return }
        guard !bitcoinLoading, let url = SafeURL.web(preferences.mempoolServer) else { return }
        let generation = UUID()
        bitcoinGeneration = generation
        let server = preferences.mempoolServer
        bitcoinLoading = true
        defer { if generation == bitcoinGeneration { bitcoinLoading = false } }
        do {
            async let dashboard = mempool.dashboard(baseURL: url)
            let snapshot = try await mempool.snapshot(baseURL: url)
            let detail = try await dashboard
            guard generation == bitcoinGeneration, preferences.mempoolServer == server else { return }
            bitcoin = snapshot
            bitcoinDashboard = detail
            await NativeBackgroundRefresh.saveWidget(snapshot: snapshot)
            networkIssues.removeAll { $0 == "source.bitcoin.failed" }
        } catch {
            guard generation == bitcoinGeneration, preferences.mempoolServer == server else { return }
            let detail = try? await mempool.dashboard(baseURL: url)
            guard generation == bitcoinGeneration, preferences.mempoolServer == server else { return }
            bitcoinDashboard = detail
            if !networkIssues.contains("source.bitcoin.failed") { networkIssues.append("source.bitcoin.failed") }
        }
    }

    private func file(_ name: String) -> URL { directory.appendingPathComponent(name) }
    private func persist<T: Encodable>(_ value: T, name: String) throws {
        let data = try JSONEncoder().encode(value)
        let url = file(name)
        #if os(iOS)
        try data.write(to: url, options: [.atomic, .completeFileProtection])
        #else
        try data.write(to: url, options: .atomic)
        #endif
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutableURL = url
        try mutableURL.setResourceValues(values)
    }

    /// Deletion errors abort reset and preserve recovery state.
    func resetLocalData() async {
        do {
            try await identity.reset()
            try await media.reset()
            try learning.reset()
            try ProfilePictureStore.resetFiles(in: directory)
            #if os(iOS)
            try KeychainStore.delete(account: "local-public-profile-v1")
            #endif
            for name in ["preferences.json", "public-cache.json", "public-profile.json"] where FileManager.default.fileExists(atPath: file(name).path) {
                try FileManager.default.removeItem(at: file(name))
            }
            refreshGeneration = UUID()
            bitcoinGeneration = UUID()
            profile = LocalProfile()
            preferences = LocalPreferences()
            cache = DataCache()
            associatedEvents = []
            calendarRefreshedThisSession = false
            calendarLastAttempt = nil
            bitcoin = nil
            bitcoinDashboard = nil
            bitcoinLoading = false
            stateReadable = true
            profileReadable = true
            loading = false
            networkIssues = []
            diagnostics = []
            persistenceIssue = nil
            selectedSection = .home
        } catch { persistenceIssue = text("error.persistence") }
    }
}

private func capture<T: Sendable>(_ operation: @Sendable () async throws -> T) async -> Result<T, Error> {
    do { return .success(try await operation()) } catch { return .failure(error) }
}

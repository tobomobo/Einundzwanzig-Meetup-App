import Foundation
import Observation
import SwiftUI
import MeetupCore

@MainActor @Observable
final class AppStore {
    private(set) var profile = LocalProfile()
    private(set) var preferences = LocalPreferences()
    private(set) var cache = DataCache()
    private(set) var bitcoin: BitcoinSnapshot?
    private(set) var loading = false
    private(set) var bitcoinLoading = false
    private(set) var networkIssues: [String] = []
    private(set) var diagnostics: [String] = []
    var persistenceIssue: String?
    var selectedSection: AppSection = .home
    var scanPresented = false
    var settingsPresented = false
    private var stateReadable = true
    private var profileReadable = true
    private var refreshGeneration = UUID()
    private var bitcoinGeneration = UUID()
    private let portal = PortalClient()
    private let newsClient = NewsClient()
    private let mempool = MempoolClient()
    private let directory: URL

    init() {
        directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("EinundzwanzigApple", isDirectory: true)
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
    }

    var meetups: [Meetup] { preferences.demo ? PreviewFixtures.meetups : cache.meetups }
    var events: [MeetupEvent] { preferences.demo ? PreviewFixtures.events : cache.events }
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
        events.filter { $0.meetupID == meetupID && $0.start >= Date().addingTimeInterval(-21_600) }
            .min { $0.start < $1.start }
    }
    func plan(for id: String) -> LocalPlan? { preferences.demo ? preferences.demoPlans[id] : preferences.plans[id] }
    func isFavorite(_ id: String) -> Bool { (preferences.demo ? preferences.demoFavoriteIDs : preferences.favoriteIDs).contains(id) }
    func toggleFavorite(_ id: String) {
        updatePreferences { value in
            if value.demo {
                if value.demoFavoriteIDs.contains(id) { value.demoFavoriteIDs.remove(id) }
                else { value.demoFavoriteIDs.insert(id) }
            }
            else if value.favoriteIDs.contains(id) { value.favoriteIDs.remove(id) }
            else { value.favoriteIDs.insert(id) }
        }
    }
    @discardableResult
    func saveProfile(nickname: String, npub: String) -> Bool {
        guard profileReadable else { persistenceIssue = text("error.profileRead"); return false }
        let name = nickname.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = npub.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let alphabet = Set("023456789acdefghjklmnpqrstuvwxyz")
        guard !name.isEmpty, name.count <= 40,
              key.isEmpty || (key.hasPrefix("npub1") && key.count == 63 && key.dropFirst(5).allSatisfy { alphabet.contains($0) })
        else { persistenceIssue = text("error.profile"); return false }
        let next = LocalProfile(nickname: name, npub: key, onboarded: true)
        do {
            #if os(macOS)
            try persist(next, name: "public-profile.json")
            #else
            try KeychainStore.write(JSONEncoder().encode(next), account: "local-public-profile-v1")
            #endif
            profile = next
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
                networkIssues.removeAll { $0 == "source.bitcoin.failed" }
            }
            preferences = next
            persistenceIssue = nil
            return true
        } catch {
            persistenceIssue = text("error.persistence")
            return false
        }
    }

    func setDemo(_ enabled: Bool) {
        if updatePreferences({ $0.demo = enabled }) {
            // An in-flight live refresh cannot replace the demo badge/source label.
            refreshGeneration = UUID()
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
        guard !loading, !preferences.demo else { return }
        loading = true
        let generation = UUID()
        refreshGeneration = generation
        networkIssues.removeAll { $0 != "source.bitcoin.failed" }
        let portal = self.portal
        let newsClient = self.newsClient
        async let directoryResult = capture { try await portal.meetups() }
        async let eventsResult = capture { try await portal.events() }
        async let newsResult = capture { try await newsClient.articles() }
        let (directoryResponse, eventResponse, newsResponse) = await (directoryResult, eventsResult, newsResult)
        guard generation == refreshGeneration else { return }
        var updated = cache
        switch directoryResponse {
        case .success(let values): updated.meetups = values
        case .failure: networkIssues.append("source.meetups.failed")
        }
        switch eventResponse {
        case .success(let values): updated.events = values
        case .failure: networkIssues.append("source.events.failed")
        }
        switch newsResponse {
        case .success(let values): updated.articles = values
        case .failure: networkIssues.append("source.news.failed")
        }
        // A timestamp is only fresh for a complete refresh; partial refreshes are labelled.
        let portalSucceeded = !networkIssues.contains { $0 != "source.bitcoin.failed" }
        if portalSucceeded { updated.fetchedAt = Date() }
        cache = updated
        do { try persist(updated, name: "public-cache.json") }
        catch { persistenceIssue = text("error.cacheSave") }
        diagnostics.insert("\(Date().formatted()) · portal/rss · \(portalSucceeded ? "OK" : "partial/offline")", at: 0)
        diagnostics = Array(diagnostics.prefix(50))
        loading = false
    }

    func refreshBitcoin() async {
        guard !bitcoinLoading, let url = SafeURL.web(preferences.mempoolServer) else { return }
        let generation = UUID()
        bitcoinGeneration = generation
        let server = preferences.mempoolServer
        bitcoinLoading = true
        defer { if generation == bitcoinGeneration { bitcoinLoading = false } }
        do {
            let snapshot = try await mempool.snapshot(baseURL: url)
            guard generation == bitcoinGeneration, preferences.mempoolServer == server else { return }
            bitcoin = snapshot
            networkIssues.removeAll { $0 == "source.bitcoin.failed" }
        } catch {
            guard generation == bitcoinGeneration, preferences.mempoolServer == server else { return }
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

    /// Clear Apple prototype data only; never reads or migrates the Flutter containers.
    func resetLocalData() {
        do {
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
            bitcoin = nil
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

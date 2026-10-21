import Foundation
import MeetupCore

struct LocalProfile: Codable, Equatable {
    var nickname = ""
    /// A user-entered public reference. Never treated as proof of key ownership.
    var npub = ""
    var onboarded = false
}

enum PlanStatus: String, Codable, CaseIterable, Identifiable {
    case going, maybe
    var id: String { rawValue }
    var title: String { self == .going ? "plan.going" : "plan.maybe" }
}

struct LocalPlan: Codable, Identifiable {
    var id: String { event.id }
    let event: MeetupEvent
    var status: PlanStatus
    var note: String
}

struct BadgePreview: Codable, Identifiable, Hashable {
    let id: UUID
    let name: String
    let importedAt: Date
    let blockHeight: Int?
    let isDemo: Bool
    // Deliberately no verified flag, trust score, private keys or raw scan storage.
    // Preview metadata cannot be promoted to a credential by changing preferences.
}

struct LocalPreferences: Codable {
    var version = 1
    var language = "system"
    var currency = "EUR"
    var appearance = "system"
    var demo = false
    var demoFavoriteIDs: Set<String> = []
    var demoPlans: [String: LocalPlan] = [:]
    var favoriteIDs: Set<String> = []
    var plans: [String: LocalPlan] = [:]
    var badgePreviews: [BadgePreview] = []
    var relays = ["wss://relay.damus.io", "wss://nos.lol", "wss://relay.nostr.band", "wss://nostr.einundzwanzig.space"]
    var mempoolServer = "https://mempool.space"
    var lastReadArticleIDs: Set<String> = []
    var dashboardOrder = DashboardTile.allCases
    var hiddenTiles: Set<DashboardTile> = []
}

enum DashboardTile: String, Codable, CaseIterable, Identifiable, Hashable {
    case meetups, plans, reputation, community, news, bitcoin, converter, resources
    var id: String { rawValue }
    var title: String { "nav.\(rawValue)" }
    var symbol: String {
        switch self {
        case .meetups: "map"
        case .plans: "calendar.badge.clock"
        case .reputation: "person.crop.circle.badge.checkmark"
        case .community: "person.3"
        case .news: "newspaper"
        case .bitcoin: "bitcoinsign.circle"
        case .converter: "arrow.left.arrow.right"
        case .resources: "square.grid.2x2"
        }
    }
}

enum AppSection: String, CaseIterable, Identifiable {
    case home, badges, events, meetups, community, bitcoin
    var id: String { rawValue }
    var title: String { "nav.\(rawValue)" }
    var symbol: String {
        switch self {
        case .home: "house"
        case .badges: "seal"
        case .events: "calendar"
        case .meetups: "map"
        case .community: "person.3"
        case .bitcoin: "bitcoinsign.circle"
        }
    }
}

enum AppRoute: Hashable {
    case meetups, meetup(String), events(String?), event(String), plans
    case badges, collection(String), badge(UUID), badgeMap, reputation
    case community, news, article(String), bitcoin, converter, resources, glossary
    case courses, course(Int), lecturer(Int)
    case unavailable(Integration)
}

enum Integration: String, CaseIterable, Identifiable, Hashable {
    case signing, verification, organizer, chat, proofs, humanity, socialGraph
    case eventPublishing, courses, plebrap, donations, backup
    var id: String { rawValue }
    var title: String { "integration.\(rawValue)" }
    var explanation: String { "integration.\(rawValue).body" }
}

struct DataCache: Codable {
    var meetups: [Meetup] = []
    var events: [MeetupEvent] = []
    var articles: [NewsArticle] = []
    var fetchedAt: Date?
    var calendarSnapshot: CalendarFeedSnapshot?
}

enum LocalDataError: LocalizedError {
    case unavailable, invalidProfile, invalidImport, tooLarge, duplicate
    var localizationKey: String {
        switch self {
        case .unavailable: "error.persistence"
        case .invalidProfile: "error.profile"
        case .invalidImport: "error.import"
        case .tooLarge: "error.tooLarge"
        case .duplicate: "error.duplicate"
        }
    }
    var errorDescription: String? { NSLocalizedString(localizationKey, comment: "") }
}

enum PreviewImport {
    /// Only extracts display metadata. No signature or claim is ever accepted here.
    static func parse(_ text: String) throws -> BadgePreview {
        guard text.utf8.count <= 65_536 else { throw LocalDataError.tooLarge }
        guard let data = text.data(using: .utf8),
              let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        else { throw LocalDataError.invalidImport }
        let allowed: Set<String> = ["v", "t", "m", "b", "x", "c", "s", "p", "e", "sig", "sigId", "adminPubkey", "sigVersion", "sigContent", "n", "ts", "d", "la", "lo", "qr_nonce", "qr_time_step", "id", "meetupName", "date", "blockHeight", "signerNpub", "meetupEventId", "delivery", "iconPath", "coverUrl", "claimSig", "claimEventId", "claimPubkey", "claimTimestamp", "isRetroactive", "isOrganizer", "presenceVerified", "isEvent", "lat", "lng"]
        guard Set(json.keys).isSubset(of: allowed),
              let name = (json["meetupName"] as? String) ?? (json["m"] as? String),
              !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              name.count <= 200 else { throw LocalDataError.invalidImport }
        let block = (json["blockHeight"] as? Int) ?? (json["b"] as? Int)
        guard block == nil || (block! >= 0 && block! <= 100_000_000) else { throw LocalDataError.invalidImport }
        return BadgePreview(id: UUID(), name: name, importedAt: Date(), blockHeight: block, isDemo: false)
    }
}

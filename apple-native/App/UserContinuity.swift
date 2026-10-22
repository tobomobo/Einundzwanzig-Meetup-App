import Foundation
import MeetupNostr

/// Public profile continuity only. Stored flags never confer signing, portal,
/// organizer, or proof authority; those capabilities come from verified stores.
enum UserContinuity {
    static func sanitize(_ fields: [String: BackupValue]) -> [String: BackupValue] {
        var output: [String: BackupValue] = [:]
        for (key, value) in fields {
            let name = key.lowercased().filter { $0.isLetter || $0.isNumber }
            if ["nostr", "nsec", "privhex", "password", "key", "jwt", "bearer", "credential", "credentials", "session", "auth", "authentication", "authorization", "apikey", "badgependingkey", "clientsk", "clientsessionkey", "wrappedkey"].contains(name) ||
                name.contains("private") || name.contains("secret") || name.contains("token") || name.contains("encryptionkey") { continue }
            if ["isadmin", "isadminverified", "isnostrverified", "isadmincryptoverified", "isverified", "verified", "hasnostrkey", "isreviewdemo",
                "adminviaportal", "adminviavouch", "adminviaseed", "adminverified", "nostrverified", "trusted"].contains(name) ||
                (value.bool != nil && name.contains("verified")) {
                output[key] = .bool(false); continue
            }
            if ["promotionsource", "adminsource", "role", "roles", "permissions", "trustscore", "authority"].contains(name) { continue }
            output[key] = sanitized(value)
        }
        return output
    }
    private static func sanitized(_ value: BackupValue) -> BackupValue {
        switch value {
        case .object(let fields): .object(sanitize(fields))
        case .array(let items): .array(items.map(sanitized))
        default: value
        }
    }
    static func fields(for profile: LocalProfile, favoriteIDs: Set<String>, activeNpub: String) throws -> [String: BackupValue] {
        var fields = try savedFields(profile)
        let favorites = ordered(profile.favoriteMeetupIDs.filter(favoriteIDs.contains) + favoriteIDs.sorted())
        let home = favoriteIDs.contains(profile.homeMeetupID) ? profile.homeMeetupID : favorites.first ?? ""
        fields["nickname"] = .string(profile.nickname); fields["fullName"] = .string(profile.fullName)
        fields["homeMeetupId"] = .string(home); fields["favoriteMeetupIds"] = .array(favorites.map(BackupValue.string))
        fields["telegramHandle"] = .string(profile.telegramHandle); fields["twitterHandle"] = .string(profile.twitterHandle)
        fields["nostrNpub"] = .string(activeNpub.isEmpty ? profile.npub : activeNpub)
        if fields["npub"] != nil { fields["npub"] = fields["nostrNpub"] }
        fields["isAdmin"] = .bool(false); fields["isAdminVerified"] = .bool(false); fields["isNostrVerified"] = .bool(false)
        return sanitize(fields)
    }
    static func restore(_ user: [String: BackupValue], current: LocalProfile, favoriteIDs: Set<String>,
                        decision: BackupDocument.RestoreDecision, activeNpub: String) throws -> LocalProfile {
        let incoming = sanitize(user), additive = decision == .sameIdentity || decision == .addToIdentity
        let existing = try fields(for: current, favoriteIDs: favoriteIDs, activeNpub: current.npub)
        var merged = additive ? merge(existing, incoming) : incoming
        func field(_ key: String, _ fallback: String = "") -> String { merged[key]?.string ?? fallback }
        let incomingNickname = incoming["nickname"]?.string ?? incoming["name"]?.string ?? ""
        let customNickname = !current.nickname.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && current.nickname.trimmingCharacters(in: .whitespacesAndNewlines) != "Anon"
        let nickname = additive && customNickname ? current.nickname : incomingNickname
        let oldFavorites = ordered(current.favoriteMeetupIDs.filter(favoriteIDs.contains) + favoriteIDs.sorted())
        let importedHome = incoming["homeMeetupId"]?.string ?? ""
        let importedFavorites = incoming["favoriteMeetupIds"]?.array?.compactMap(\.string) ?? (importedHome.isEmpty ? [] : [importedHome])
        var favorites = ordered((additive ? oldFavorites : []) + importedFavorites)
        let oldHome = existing["homeMeetupId"]?.string ?? ""
        let home = additive && !oldHome.isEmpty ? oldHome : importedHome.isEmpty ? favorites.first ?? "" : importedHome
        if !home.isEmpty, !favorites.contains(home) { favorites.insert(home, at: 0) }
        merged["nickname"] = .string(nickname); merged["homeMeetupId"] = .string(home)
        merged["favoriteMeetupIds"] = .array(favorites.map(BackupValue.string)); merged["nostrNpub"] = .string(activeNpub)
        return LocalProfile(nickname: nickname.isEmpty ? "Anon" : nickname, npub: activeNpub, onboarded: true,
            fullName: field("fullName"), homeMeetupID: home, favoriteMeetupIDs: favorites,
            telegramHandle: field("telegramHandle"), twitterHandle: field("twitterHandle"), publicUserFields: try JSONEncoder().encode(merged))
    }
    static func savedFields(_ profile: LocalProfile) throws -> [String: BackupValue] {
        guard let data = profile.publicUserFields else { return [:] }
        guard data.count <= 8 * 1024 * 1024 else { throw IdentityError.resourceLimit }
        return sanitize(try JSONDecoder().decode([String: BackupValue].self, from: data))
    }
    private static func merge(_ current: [String: BackupValue], _ incoming: [String: BackupValue]) -> [String: BackupValue] {
        var merged = current
        for (key, value) in incoming {
            if let a = current[key]?.object, let b = value.object { merged[key] = .object(merge(a, b)) }
            else if current[key] == nil || empty(current[key]!) { merged[key] = value }
        }
        return merged
    }
    private static func empty(_ value: BackupValue) -> Bool {
        switch value {
        case .null: true
        case .string(let text): text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .array(let items): items.isEmpty
        case .object(let fields): fields.isEmpty
        default: false
        }
    }
    static func ordered(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && seen.insert($0).inserted }
    }
}

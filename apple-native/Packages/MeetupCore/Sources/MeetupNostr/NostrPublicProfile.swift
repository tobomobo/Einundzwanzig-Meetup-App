import Foundation
import MeetupCore

/// Public signed metadata only. A newer empty picture is a removal, not a miss.
public struct NostrPublicProfile: Codable, Sendable, Equatable {
    public var profile: NostrEvent?
    public var relayList: NostrEvent?
    public var fetchedAt: Int
    public init(profile: NostrEvent? = nil, relayList: NostrEvent? = nil, fetchedAt: Int = 0) {
        self.profile = profile; self.relayList = relayList; self.fetchedAt = fetchedAt
    }
    public var pictureURL: URL? {
        guard let profile, let values = try? JSONSerialization.jsonObject(with: Data(profile.content.utf8)) as? [String: Any],
              let picture = values["picture"] as? String else { return nil }
        return SafeURL.web(picture)
    }
    public var nip05: String? {
        guard let profile, let values = try? JSONSerialization.jsonObject(with: Data(profile.content.utf8)) as? [String: Any],
              let value = values["nip05"] as? String else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : String(trimmed.prefix(256))
    }
    public var displayName: String? {
        guard let profile, let values = try? JSONSerialization.jsonObject(with: Data(profile.content.utf8)) as? [String: Any] else { return nil }
        for key in ["display_name", "name"] {
            if let value = values[key] as? String, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return String(value.trimmingCharacters(in: .whitespacesAndNewlines).prefix(160))
            }
        }
        return nil
    }
}

/// Mirrors Flutter's read-only kind-0/NIP-65 avatar discovery. This service never
/// obtains a signer, publishes an event or trusts unverified cached metadata.
public struct NostrPublicProfileLookup: Sendable {
    public static let cacheSeconds = 12 * 60 * 60
    public static let indexRelay = URL(string: "wss://purplepag.es")!
    private let relay: any NostrRelayTransport
    public init(relay: any NostrRelayTransport) { self.relay = relay }

    public func load(publicKey: String, relays: [URL], cached: NostrPublicProfile? = nil,
                     now: Int = Int(Date().timeIntervalSince1970), force: Bool = false) async -> NostrPublicProfile? {
        guard publicKey.utf8.count == 64, publicKey.utf8.allSatisfy({ (48...57).contains($0) || (97...102).contains($0) }) else { return nil }
        let oldProfile = await valid(cached?.profile, owner: publicKey, kind: 0, now: now)
        let oldRelays = await valid(cached?.relayList, owner: publicKey, kind: 10002, now: now)
        if !force, oldProfile != nil, let cached, cached.fetchedAt > 0,
           cached.fetchedAt <= now, now - cached.fetchedAt < Self.cacheSeconds {
            return NostrPublicProfile(profile: oldProfile, relayList: oldRelays, fetchedAt: cached.fetchedAt)
        }
        var initial: [URL] = []
        for url in relays {
            guard let safe = Self.relayURL(url.absoluteString), safe != Self.indexRelay,
                  !initial.contains(safe), initial.count < 7 else { continue }
            initial.append(safe)
        }
        initial.append(Self.indexRelay)
        var events = await query(publicKey, relays: initial, now: now)
        let relayList = Self.newest(events.filter { $0.kind == 10002 } + [oldRelays].compactMap { $0 })
        var discovered: [URL] = []
        for tag in relayList?.tags ?? [] {
            guard tag.count >= 2, tag[0] == "r", tag.count == 2 || tag[2] == "write",
                  let url = Self.relayURL(tag[1], discovered: true), !initial.contains(url),
                  !discovered.contains(url), discovered.count < 4 else { continue }
            discovered.append(url)
        }
        if !discovered.isEmpty { events += await query(publicKey, relays: discovered, now: now) }
        guard !Task.isCancelled else { return nil }
        let profiles = events.filter { $0.kind == 0 }
        let newest = Self.newest(profiles + [oldProfile].compactMap { $0 })
        let latestRelays = Self.newest(events.filter { $0.kind == 10002 } + [oldRelays].compactMap { $0 })
        guard newest != nil || latestRelays != nil else { return nil }
        return NostrPublicProfile(profile: newest, relayList: latestRelays,
                                  fetchedAt: profiles.isEmpty ? (cached?.fetchedAt ?? 0) : now)
    }

    private func query(_ owner: String, relays: [URL], now: Int) async -> [NostrEvent] {
        let filter = NostrFilter(authors: [owner], kinds: [0, 10002], until: now, limit: 2)
        guard let found = try? await relay.query(filter, relays: relays, timeout: 6) else { return [] }
        var result: [NostrEvent] = []
        // Bound verification even for injected/custom transports.
        for event in found.prefix(relays.count * 8) {
            if let event = await valid(event, owner: owner, kind: event.kind, now: now) { result.append(event) }
        }
        return result
    }
    private func valid(_ event: NostrEvent?, owner: String, kind: Int, now: Int) async -> NostrEvent? {
        guard let event, [0, 10002].contains(kind), event.kind == kind, event.pubkey == owner,
              event.createdAt <= now, event.content.utf8.count <= 64 * 1024,
              await NostrCrypto().verify(event) else { return nil }
        if kind == 0 {
            guard (try? JSONSerialization.jsonObject(with: Data(event.content.utf8))) is [String: Any] else { return nil }
        }
        return event
    }
    private static func newest(_ events: [NostrEvent]) -> NostrEvent? {
        events.min { $0.createdAt == $1.createdAt ? $0.id < $1.id : $0.createdAt > $1.createdAt }
    }
    public static func relayURL(_ value: String, discovered: Bool = false) -> URL? {
        guard var components = URLComponents(string: value.trimmingCharacters(in: .whitespacesAndNewlines)),
              let url = SafeURL.relay(value), let host = url.host?.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: ".")) else { return nil }
        if discovered {
            guard host.contains("."), !host.contains(":"), !host.contains("["),
                  host != "localhost", ![".local", ".localdomain", ".localhost"].contains(where: host.hasSuffix) else { return nil }
            let parts = host.split(separator: ".", omittingEmptySubsequences: false).map { Int($0) }
            if parts.allSatisfy({ $0 != nil }) {
                guard parts.count == 4, parts.allSatisfy({ (0...255).contains($0!) }) else { return nil }
                let a = parts[0]!, b = parts[1]!
                guard a != 0, a != 10, a != 127, a < 224,
                      !(a == 169 && b == 254), !(a == 172 && (16...31).contains(b)),
                      !(a == 192 && b == 168), !(a == 100 && (64...127).contains(b)) else { return nil }
            }
        }
        if components.path == "/", components.query == nil { components.path = "" }
        return components.url
    }
}

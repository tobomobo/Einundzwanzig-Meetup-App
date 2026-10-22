import Foundation

public struct SocialObservedContact: Codable, Equatable, Sendable, Identifiable {
    public let publicKey: String
    public let degree: Int
    public let sharedMeetups: Set<String>
    public let bridges: Set<String>
    public let parent: String
    public var id: String { publicKey }
}
public struct SocialPersonConnection: Equatable, Sendable {
    public let target: String
    public let degree: Int
    public let path: [String]
    public let sharedMeetups: Set<String>
    public let targetInObservedNetwork: Bool
}
/// Edges mean two authenticated self-attestations name the same local session.
/// They do not assert a verified source badge, identity proof or personal trust.
public struct SocialObservedNetwork: Equatable, Sendable {
    public let owner: String
    public let byDegree: [Int: [SocialObservedContact]]
    public let meetupsOf: [String: Set<String>]
    public let stale: Bool
    public let truncated: Bool
    public var totalReach: Int { byDegree.values.reduce(0) { $0 + $1.count } }
    public func pathTo(_ publicKey: String) -> [String] {
        if publicKey == owner { return [owner] }
        let contacts = Dictionary(uniqueKeysWithValues: byDegree.values.flatMap { $0 }.map { ($0.publicKey, $0) })
        var chain = [publicKey], current = publicKey
        for _ in 0..<3 {
            guard let contact = contacts[current] else { return [] }
            chain.append(contact.parent)
            if contact.parent == owner { return chain.reversed() }; current = contact.parent
        }
        return []
    }
    public func sharedBetween(_ a: String, _ b: String) -> Set<String> { SocialNetwork.sharedKeys(meetupsOf[a] ?? [], meetupsOf[b] ?? []) }
    public func verifyPerson(_ target: String) -> SocialPersonConnection {
        let path = pathTo(target)
        return SocialPersonConnection(target: target, degree: path.isEmpty ? -1 : path.count - 1, path: path,
                                      sharedMeetups: sharedBetween(owner, target), targetInObservedNetwork: meetupsOf[target] != nil)
    }
}

extension SocialNetwork {
    public static func network(_ snapshot: SocialGraphSnapshot, owner: String, maximumDepth: Int = 3) -> SocialObservedNetwork {
        let maximumDepth = min(3, max(0, maximumDepth))
        var meetups: [String: Set<String>] = [:], buckets: [String: [(String, String)]] = [:]
        for attendance in snapshot.attendance {
            if meetups[attendance.event.pubkey] == nil { meetups[attendance.event.pubkey] = [] }
            if !attendance.isEvent { meetups[attendance.event.pubkey, default: []].insert(attendance.sessionKey) }
        }
        for author in meetups.keys.filter({ !(meetups[$0] ?? []).isEmpty }).sorted().prefix(1000) {
            for key in meetups[author] ?? [] { buckets[key.components(separatedBy: "@")[0], default: []].append((author, key)) }
        }
        var adjacency: [String: Set<String>] = [:], truncated = snapshot.truncated
        for members in buckets.values {
            let members = members.sorted { $0.0 == $1.0 ? $0.1 < $1.1 : $0.0 < $1.0 }
            if members.count > 200 { truncated = true }
            let limited = Array(members.prefix(200))
            for i in limited.indices {
                for j in (i + 1)..<limited.count where limited[i].0 != limited[j].0 {
                    guard !sharedKeys([limited[i].1], [limited[j].1]).isEmpty else { continue }
                    adjacency[limited[i].0, default: []].insert(limited[j].0)
                    adjacency[limited[j].0, default: []].insert(limited[i].0)
                }
            }
        }
        var degrees: [String: Int] = [owner: 0], parents: [String: String] = [:], bridges: [String: Set<String>] = [:]
        var queue = [owner], offset = 0
        while offset < queue.count, queue.count <= 1000 {
            let current = queue[offset]; offset += 1
            let degree = degrees[current] ?? 0
            guard degree < maximumDepth else { continue }
            let neighbors = (adjacency[current] ?? []).sorted { a, b in
                if current == owner {
                    let x = sharedKeys(meetups[a] ?? [], meetups[owner] ?? []).count
                    let y = sharedKeys(meetups[b] ?? [], meetups[owner] ?? []).count
                    if x != y { return x > y }
                }
                return a < b
            }
            for neighbor in neighbors {
                if degrees[neighbor] == nil { degrees[neighbor] = degree + 1; parents[neighbor] = current; queue.append(neighbor) }
                guard degrees[neighbor] == degree + 1 else { continue }
                if degree == 0 { bridges[neighbor] = [neighbor] }
                else { bridges[neighbor, default: []].formUnion(bridges[current] ?? []) }
            }
        }
        var byDegree: [Int: [SocialObservedContact]] = [1: [], 2: [], 3: []]
        for (key, degree) in degrees where degree > 0 && degree <= maximumDepth {
            byDegree[degree, default: []].append(SocialObservedContact(publicKey: key, degree: degree,
                sharedMeetups: degree == 1 ? sharedKeys(meetups[owner] ?? [], meetups[key] ?? []) : [],
                bridges: degree == 1 ? [] : bridges[key] ?? [], parent: parents[key] ?? ""))
        }
        for degree in 1...3 {
            byDegree[degree]?.sort {
                let left = degree == 1 ? $0.sharedMeetups.count : $0.bridges.count, right = degree == 1 ? $1.sharedMeetups.count : $1.bridges.count
                return left == right ? $0.publicKey < $1.publicKey : left > right
            }
        }
        return SocialObservedNetwork(owner: owner, byDegree: byDegree, meetupsOf: meetups,
                                     stale: snapshot.stale, truncated: truncated || queue.count > 1000)
    }
}

/// Bounded incremental relay expansion: authors -> their local sessions ->
/// attendees -> those authors' other sessions, for at most three stages.
public struct SocialGraphLoader: Sendable {
    public let relay: any NostrRelayTransport
    public init(relay: any NostrRelayTransport) { self.relay = relay }
    public func load(owner: String, target: String? = nil, organizers: Set<String> = [], previous: SocialGraphSnapshot = SocialGraphSnapshot(),
                     forceFull: Bool = false, relays: [URL], now: Int,
                     onStage: (@Sendable (SocialGraphSnapshot, Int) async -> Void)? = nil) async throws -> SocialGraphSnapshot {
        _ = try NostrHex.decode(owner, byteCount: 32, field: "network owner")
        if let target { _ = try NostrHex.decode(target, byteCount: 32, field: "network target") }
        let reusable = previous.owner == owner
        var snapshot = await SocialNetwork.ingest(reusable ? previous.sourceEvents : [], now: now)
        snapshot.owner = owner; snapshot.knownAuthors = reusable ? previous.knownAuthors : []
        snapshot.knownKeys = reusable ? previous.knownKeys : []; snapshot.fullSyncAt = reusable ? previous.fullSyncAt : 0
        let full = forceFull || !reusable || now - snapshot.fullSyncAt >= 86400
        let since = full ? nil : max(0, previous.updatedAt - 3600)
        var answered = 0, failed = false, truncated = false
        func bounded(_ events: [NostrEvent]) -> [NostrEvent] {
            var bytes = 0, result: [NostrEvent] = []
            for event in events.sorted(by: { $0.createdAt > $1.createdAt }).prefix(2500) {
                let size = (try? JSONEncoder().encode(event).count) ?? 262144
                if size > 2_097_152 - bytes { truncated = true; continue }
                result.append(event); bytes += size
            }
            if result.count < events.count { truncated = true }; return result
        }
        func query(_ filter: NostrFilter) async throws {
            try Task.checkCancellation()
            do {
                let raw = try await relay.query(filter, relays: relays, timeout: 8)
                let verified = await SocialNetwork.ingest(raw, now: now, filter: filter)
                let combined = await SocialNetwork.ingest(snapshot.sourceEvents + verified.sourceEvents, now: now)
                snapshot.follows = combined.follows; snapshot.attendance = combined.attendance
                snapshot.sourceEvents = bounded(combined.sourceEvents)
                if snapshot.sourceEvents.count != combined.sourceEvents.count {
                    let limited = await SocialNetwork.ingest(snapshot.sourceEvents, now: now)
                    snapshot.follows = limited.follows; snapshot.attendance = limited.attendance
                }
                answered += 1
                if raw.count >= (filter.limit ?? 1000) { truncated = true }
            } catch is CancellationError { throw CancellationError() }
            catch { failed = true }
        }
        func fetchAuthors(_ authors: Set<String>) async throws {
            let knownBefore = snapshot.knownAuthors
            for known in [false, true] {
                let values = authors.filter { knownBefore.contains($0) == known }.sorted()
                for start in stride(from: 0, to: values.count, by: 100) {
                    let part = Array(values[start..<min(values.count, start + 100)])
                    let before = answered
                    try await query(NostrFilter(authors: part, kinds: [30079], since: known ? since : nil, limit: 1000))
                    if answered > before { snapshot.knownAuthors.formUnion(part) }
                }
            }
        }
        let seeds = Set([owner] + (target.map { [$0] } ?? []))
        try await query(NostrFilter(authors: Array(seeds.union(organizers.sorted().prefix(10))), kinds: [3], limit: 100))
        try await fetchAuthors(seeds)
        var seenPeople = seeds, seenKeys: Set<String> = []
        var frontier = Set(snapshot.attendance.filter { seeds.contains($0.event.pubkey) && !$0.isEvent }.map(\.sessionKey))
        for depth in 1...3 {
            let newKeys = frontier.subtracting(seenKeys).sorted()
            if newKeys.count > 60 { truncated = true }; let keys = Set(newKeys.prefix(60))
            guard !keys.isEmpty else { break }; seenKeys.formUnion(keys)
            let knownBefore = snapshot.knownKeys
            for known in [false, true] {
                let group = keys.filter { knownBefore.contains($0) == known }.sorted()
                guard !group.isEmpty else { continue }
                let wanted = Set(group + group.map { $0.components(separatedBy: "@")[0] })
                let before = answered
                try await query(NostrFilter(kinds: [30079], since: known ? since : nil, limit: 1000, tags: ["d": Array(wanted)]))
                if answered > before { snapshot.knownKeys.formUnion(group) }
            }
            let attendees = Set(snapshot.attendance.filter { record in
                !record.isEvent && !SocialNetwork.sharedKeys([record.sessionKey], keys).isEmpty
            }.map { $0.event.pubkey }).subtracting(seenPeople).sorted()
            if attendees.count > 200 { truncated = true }; let newPeople = Set(attendees.prefix(200)); seenPeople.formUnion(newPeople)
            snapshot.stale = failed; snapshot.truncated = truncated; snapshot.updatedAt = answered > 0 ? now : previous.updatedAt
            await onStage?(snapshot, depth)
            guard depth < 3, !newPeople.isEmpty else { break }
            try await fetchAuthors(newPeople)
            frontier = Set(snapshot.attendance.filter { newPeople.contains($0.event.pubkey) && !$0.isEvent }.map(\.sessionKey))
        }
        snapshot.stale = failed || answered == 0; snapshot.truncated = truncated
        snapshot.updatedAt = answered > 0 ? now : previous.updatedAt
        if full && !snapshot.stale && !truncated { snapshot.fullSyncAt = now }
        return snapshot
    }
}

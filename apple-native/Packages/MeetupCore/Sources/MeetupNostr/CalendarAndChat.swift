import Foundation

public struct NostrCalendarEntry: Identifiable, Codable, Sendable, Equatable {
    public let event: NostrEvent
    public let identifier: String
    public let title: String
    public let start: Date
    public let end: Date?
    public let allDay: Bool
    public let location: String
    public var id: String { "\(event.kind):\(event.pubkey):\(identifier)" }
    public init(event: NostrEvent) throws {
        func tag(_ name: String) -> String? { event.tags.first { $0.count > 1 && $0[0] == name }?[1] }
        guard [31922, 31923].contains(event.kind), let d = tag("d"), !d.isEmpty,
              let title = tag("title") ?? tag("name"), !title.isEmpty, let rawStart = tag("start") else { throw CalendarChatError.invalidEvent }
        allDay = event.kind == 31922
        if allDay {
            guard let start = Self.date(rawStart) else { throw CalendarChatError.invalidEvent }
            self.start = start
            if let raw = tag("end") { guard let end = Self.date(raw), end > start else { throw CalendarChatError.invalidEvent }; self.end = end }
            else { end = nil }
        } else {
            guard let unix = Int(rawStart), unix >= 0, unix <= NostrEventLimits.maximumCreatedAt else { throw CalendarChatError.invalidEvent }
            start = Date(timeIntervalSince1970: TimeInterval(unix))
            if let raw = tag("end") {
                guard let unixEnd = Int(raw), unixEnd > unix, unixEnd <= NostrEventLimits.maximumCreatedAt else { throw CalendarChatError.invalidEvent }
                end = Date(timeIntervalSince1970: TimeInterval(unixEnd))
            } else { end = nil }
        }
        self.event = event; identifier = d; self.title = title; location = tag("location") ?? ""
    }
    private static func date(_ text: String) -> Date? {
        guard text.utf8.count == 10 else { return nil }
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = TimeZone(secondsFromGMT: 0)
        f.dateFormat = "yyyy-MM-dd"; f.isLenient = false
        guard let d = f.date(from: text), f.string(from: d) == text else { return nil }; return d
    }
}
public enum CalendarChatError: Error, Sendable, Equatable, LocalizedError {
    case invalidEvent, identityMismatch, emptyMessage, notAuthor
    public var errorDescription: String? {
        switch self {
        case .invalidEvent: "The calendar event is invalid."
        case .identityMismatch: "The signing identity changed. Try again."
        case .emptyMessage: "Enter a message of at most 16,000 characters."
        case .notAuthor: "Only the event author can edit or cancel this event."
        }
    }
}
public struct CalendarEventDraft: Sendable {
    public var title: String
    public var description: String
    public var start: Date
    public var end: Date?
    public var allDay: Bool
    public var location: String
    public var identifier: String
    public var badgeEnabled: Bool
    public var badgeImage: String
    public var issuers: [String]
    public var formerIssuers: [String: Int]
    public var coordinate: BadgeCoordinate?
    public init(title: String, description: String = "", start: Date, end: Date? = nil, allDay: Bool = false,
                location: String = "", identifier: String = UUID().uuidString, badgeEnabled: Bool = false,
                badgeImage: String = "", issuers: [String] = [], formerIssuers: [String: Int] = [:], coordinate: BadgeCoordinate? = nil) {
        self.title = title; self.description = description; self.start = start; self.end = end; self.allDay = allDay
        self.location = location; self.identifier = identifier; self.badgeEnabled = badgeEnabled; self.badgeImage = badgeImage
        self.issuers = issuers; self.formerIssuers = formerIssuers; self.coordinate = coordinate
    }
    public func tags() throws -> [[String]] {
        guard !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, title.count <= 500, !identifier.isEmpty,
              end == nil || end! > start, issuers.count <= 100 else { throw CalendarChatError.invalidEvent }
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = TimeZone(secondsFromGMT: 0); f.dateFormat = "yyyy-MM-dd"
        func stamp(_ date: Date) -> String { allDay ? f.string(from: date) : String(Int(date.timeIntervalSince1970)) }
        var tags = [["d", identifier], ["title", title], ["start", stamp(start)], ["client", "einundzwanzig-meetup-app"]]
        if let end { tags.append(["end", stamp(end)]) }
        if !location.isEmpty { tags.append(["location", location]) }
        if !allDay {
            tags.append(["start_tzid", TimeZone.current.identifier])
            let first = Int(start.timeIntervalSince1970) / 86400
            let last = Int((end ?? start).timeIntervalSince1970) / 86400
            guard last - first <= 366 else { throw CalendarChatError.invalidEvent }
            for day in first...last { tags.append(["D", String(day)]) }
        }
        if badgeEnabled {
            guard let coordinate else { throw CalendarChatError.invalidEvent }
            tags.append(["g", String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), coordinate.latitude), String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), coordinate.longitude)])
            tags.append(["badge", "true"])
            if !badgeImage.isEmpty { tags.append(["badge_image", badgeImage]) }
            for key in issuers { _ = try NostrHex.decode(key, byteCount: 32, field: "issuer"); tags.append(["p", key, "", "issuer"]) }
            for (key, until) in formerIssuers.sorted(by: { $0.key < $1.key }) {
                _ = try NostrHex.decode(key, byteCount: 32, field: "issuer"); tags.append(["p", key, "", "former_issuer", String(until)])
            }
        }
        return tags
    }
}

public actor CalendarChatService {
    public static let groupRelay = URL(string: "wss://group.einundzwanzig.space")!
    private let relay: any NostrRelayTransport
    private let crypto = NostrCrypto()
    public init(relay: any NostrRelayTransport) { self.relay = relay }
    public func calendar(relays: [URL]) async throws -> [NostrCalendarEntry] {
        let events = try await relay.query(NostrFilter(kinds: [31922,31923], limit: 1000), relays: relays, timeout: 10)
        var latest: [String: NostrCalendarEntry] = [:]
        for event in events where await crypto.verify(event) {
            guard event.createdAt <= Int(Date().timeIntervalSince1970) + 600, let entry = try? NostrCalendarEntry(event: event) else { continue }
            if let old = latest[entry.id], old.event.createdAt > event.createdAt || (old.event.createdAt == event.createdAt && old.event.id < event.id) { continue }
            latest[entry.id] = entry
        }
        if !latest.isEmpty {
            let deletions = try await relay.query(NostrFilter(authors: Array(Set(latest.values.map { $0.event.pubkey })), kinds: [5], limit: 1000), relays: relays, timeout: 8)
            for deletion in deletions where deletion.kind == 5 && deletion.createdAt <= Int(Date().timeIntervalSince1970) + 600 {
                guard await crypto.verify(deletion) else { continue }
                for (address, entry) in latest where deletion.pubkey == entry.event.pubkey && deletion.createdAt >= entry.event.createdAt {
                    if deletion.tags.contains(["a", address]) || deletion.tags.contains(["e", entry.event.id]) { latest.removeValue(forKey: address) }
                }
            }
        }
        return latest.values.sorted { $0.start < $1.start }
    }
    public func publish(_ draft: CalendarEventDraft, existing: NostrCalendarEntry? = nil, signer: any NostrSigner, relays: [URL]) async throws -> NostrCalendarEntry {
        let key = try await signer.publicKey()
        if let existing, existing.event.pubkey != key || existing.identifier != draft.identifier { throw CalendarChatError.notAuthor }
        let tags = try draft.tags()
        let event = try await signed(kind: draft.allDay ? 31922 : 31923, tags: tags, content: draft.description, signer: signer)
        let result = try NostrCalendarEntry(event: event)
        try await relay.publish(event, relays: relays, signer: signer)
        return result
    }
    public func cancel(_ entry: NostrCalendarEntry, signer: any NostrSigner, relays: [URL]) async throws {
        guard try await signer.publicKey() == entry.event.pubkey else { throw CalendarChatError.notAuthor }
        let deletion = try await signed(kind: 5, tags: [["a", entry.id], ["e", entry.event.id], ["k", String(entry.event.kind)]], content: "cancelled", signer: signer)
        try await relay.publish(deletion, relays: relays, signer: signer)
    }
    public func rsvp(_ entry: NostrCalendarEntry, status: String, signer: any NostrSigner, relays: [URL]) async throws {
        guard ["accepted", "declined", "tentative"].contains(status) else { throw CalendarChatError.invalidEvent }
        let event = try await signed(kind: 31925, tags: [["d", entry.id], ["a", entry.id], ["e", entry.event.id], ["p", entry.event.pubkey], ["status", status]], content: "", signer: signer)
        try await relay.publish(event, relays: relays, signer: signer)
    }
    public func rsvps(_ entry: NostrCalendarEntry, relays: [URL]) async throws -> [NostrEvent] {
        let filter = NostrFilter(kinds: [31925], limit: 1000, tags: ["a": [entry.id]])
        let events = try await relay.query(filter, relays: relays, timeout: 10)
        var newest: [String: NostrEvent] = [:]
        for event in events where filter.matches(event) && event.createdAt <= Int(Date().timeIntervalSince1970) + 600 {
            guard await crypto.verify(event) else { continue }
            guard event.tags.contains(where: { $0.count > 1 && $0[0] == "a" && $0[1] == entry.id }) else { continue }
            if let old = newest[event.pubkey], old.createdAt > event.createdAt || (old.createdAt == event.createdAt && old.id < event.id) { continue }
            newest[event.pubkey] = event
        }
        return Array(newest.values)
    }
    public func rooms() async throws -> [NostrEvent] {
        let filter = NostrFilter(kinds: [39000], limit: 500, tags: ["t": ["meetup"]])
        let events = try await relay.query(filter, relays: [Self.groupRelay], timeout: 10)
        return await verified(events, filter: filter)
    }
    public func messages(room: String? = nil, eventAddress: String? = nil, relays: [URL]) async throws -> [NostrEvent] {
        let filter: NostrFilter
        let targets: [URL]
        if let room { filter = NostrFilter(kinds: [9], limit: 200, tags: ["h": [room]]); targets = [Self.groupRelay] }
        else if let eventAddress { filter = NostrFilter(kinds: [1111], limit: 200, tags: ["A": [eventAddress]]); targets = relays }
        else { throw CalendarChatError.invalidEvent }
        let events = try await relay.query(filter, relays: targets, timeout: 10)
        return await verified(events, filter: filter).sorted { $0.createdAt < $1.createdAt }
    }
    public func membership(room: String, join: Bool, signer: any NostrSigner) async throws {
        let event = try await signed(kind: join ? 9021 : 9022, tags: [["h", room]], content: "", signer: signer)
        try await relay.publish(event, relays: [Self.groupRelay], signer: signer)
    }
    public func isMember(room: String, publicKey: String) async throws -> Bool {
        let filter = NostrFilter(kinds: [39002], limit: 5, tags: ["d": [room]])
        let raw = try await relay.query(filter, relays: [Self.groupRelay], timeout: 10)
        let events = await verified(raw, filter: filter)
        return events.max(by: { $0.createdAt < $1.createdAt })?.tags.contains { $0.count > 1 && $0[0] == "p" && $0[1] == publicKey } == true
    }
    public func send(text: String, room: String? = nil, entry: NostrCalendarEntry? = nil, signer: any NostrSigner, relays: [URL]) async throws {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, text.count <= 16000 else { throw CalendarChatError.emptyMessage }
        let tags: [[String]]; let kind: Int; let targets: [URL]
        if let room { tags = [["h", room]]; kind = 9; targets = [Self.groupRelay] }
        else if let entry {
            let root = String(entry.event.kind)
            tags = [["A", entry.id], ["K", root], ["P", entry.event.pubkey], ["a", entry.id], ["k", root], ["p", entry.event.pubkey]]
            kind = 1111; targets = relays
        } else { throw CalendarChatError.invalidEvent }
        let event = try await signed(kind: kind, tags: tags, content: text, signer: signer)
        try await relay.publish(event, relays: targets, signer: signer)
    }
    private func verified(_ events: [NostrEvent], filter: NostrFilter) async -> [NostrEvent] {
        var result: [String: NostrEvent] = [:]
        for event in events where filter.matches(event) && event.createdAt <= Int(Date().timeIntervalSince1970) + 600 {
            if await crypto.verify(event) { result[event.id] = event }
        }
        return Array(result.values)
    }
    private func signed(kind: Int, tags: [[String]], content: String, signer: any NostrSigner) async throws -> NostrEvent {
        let key = try await signer.publicKey(); let now = Int(Date().timeIntervalSince1970)
        let event = try await signer.sign(kind: kind, tags: tags, content: content, createdAt: now)
        guard event.pubkey == key, event.kind == kind, event.tags == tags, event.content == content, event.createdAt == now,
              key == (try await signer.publicKey()), await crypto.verify(event) else { throw CalendarChatError.identityMismatch }
        return event
    }
}

import Foundation
import CoreFoundation

public actor PortalClient {
    public static let baseURL = URL(string: "https://portal.einundzwanzig.space")!
    private let transport: any HTTPTransport

    public init() { transport = URLSessionTransport() }
    init(transport: any HTTPTransport) { self.transport = transport }

    public func meetups() async throws -> [Meetup] {
        let data = try await transport.get(Self.baseURL.appendingPathComponent("api/mobile/meetups"), accept: "application/json")
        return try PortalParsing.meetups(data)
    }

    /// Fetches today, then the first of each of the next two months. Dates use the UTC calendar.
    /// Any failed request throws; no partial response is presented as a complete calendar.
    public func events(now: Date = Date()) async throws -> [MeetupEvent] {
        var result: [String: MeetupEvent] = [:]
        for path in Self.eventPaths(now: now) {
            let data = try await transport.get(Self.baseURL.appendingPathComponent(path), accept: "application/json")
            for event in try PortalParsing.events(data) { result[event.id] = event }
        }
        return result.values.sorted { $0.start == $1.start ? $0.id < $1.id : $0.start < $1.start }
    }

    static func eventPaths(now: Date) -> [String] {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let month = calendar.date(from: calendar.dateComponents([.year, .month], from: now))!
        let days = [now, calendar.date(byAdding: .month, value: 1, to: month)!, calendar.date(byAdding: .month, value: 2, to: month)!]
        return days.map { date in
            let parts = calendar.dateComponents([.year, .month, .day], from: date)
            return String(format: "api/meetup-events/%04d-%02d-%02d", parts.year!, parts.month!, parts.day!)
        }
    }
}

enum JSONFields {
    static func string(_ value: Any?) -> String? {
        if let text = value as? String {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }
        if let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() { return number.stringValue }
        return nil
    }

    static func number(_ value: Any?) -> Double? {
        guard let text = string(value), let number = Double(text), number.isFinite else { return nil }
        return number
    }

    static func firstString(_ values: Any?...) -> String? { values.compactMap(string).first }
    static func firstNumber(_ values: Any?...) -> Double? { values.compactMap(number).first }

    static func rows(_ data: Data) throws -> [[String: Any]] {
        let root: Any
        do { root = try JSONSerialization.jsonObject(with: data) }
        catch { throw MeetupCoreError.invalidData("Expected a JSON array: \(error.localizedDescription)") }
        let payload = (root as? [String: Any])?["data"] ?? root
        guard let rows = payload as? [[String: Any]] else { throw MeetupCoreError.invalidData("Expected an array of objects.") }
        return rows
    }
}

enum PortalParsing {
    static func web(_ text: String?) -> URL? {
        guard let text else { return nil }
        if let url = SafeURL.web(text) { return url }
        guard !text.hasPrefix("//"), let parts = URLComponents(string: text),
              parts.scheme == nil, parts.host == nil,
              let url = URL(string: text, relativeTo: PortalClient.baseURL)?.absoluteURL else { return nil }
        return SafeURL.web(url.absoluteString)
    }

    static func meetups(_ data: Data) throws -> [Meetup] {
        var ids = Set<String>()
        return try JSONFields.rows(data).compactMap { row in
            guard let id = JSONFields.string(row["id"]) else { throw MeetupCoreError.invalidData("Meetup has no ID.") }
            guard ids.insert(id).inserted else { return nil }
            let name = JSONFields.string(row["name"]) ?? ""
            var portal = web(JSONFields.string(row["portalLink"]))
            if portal == nil, let slug = JSONFields.string(row["slug"]) {
                let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))
                if let encoded = slug.addingPercentEncoding(withAllowedCharacters: allowed) {
                    portal = URL(string: "https://portal.einundzwanzig.space/de/meetup/" + encoded)
                }
            }
            return Meetup(
                id: id, name: name, city: JSONFields.string(row["city"]) ?? name,
                country: JSONFields.string(row["country"]) ?? "",
                latitude: JSONFields.firstNumber(row["latitude"], row["lat"]),
                longitude: JSONFields.firstNumber(row["longitude"], row["lon"], row["lng"]),
                description: JSONFields.firstString(row["intro"], row["description"]) ?? "",
                logoURL: web(JSONFields.string(row["logo"])), portalURL: portal,
                telegramURL: JSONFields.firstString(row["url"], row["telegram"]).flatMap(SafeURL.web),
                nextEventStart: DateParsing.portal(JSONFields.string(row["next_event_start_iso"]))
                    ?? DateParsing.portal(JSONFields.string(row["next_event_start"]))
            )
        }
    }

    static func events(_ data: Data) throws -> [MeetupEvent] {
        try JSONFields.rows(data).map { row in
            func meetup(_ key: String) -> Any? {
                let flat = row["meetup." + key]
                if let flat, !(flat is NSNull) { return flat }
                return (row["meetup"] as? [String: Any])?[key]
            }
            guard let id = JSONFields.string(row["id"]) else { throw MeetupCoreError.invalidData("Event has no ID.") }
            guard let start = DateParsing.portal(JSONFields.string(row["start_iso"]))
                    ?? DateParsing.portal(JSONFields.string(row["start"])) else {
                throw MeetupCoreError.invalidData("Event \(id) has no valid start date.")
            }
            let name = JSONFields.string(meetup("name"))
            return MeetupEvent(
                id: id, title: JSONFields.string(row["title"]) ?? name ?? "Meetup",
                description: JSONFields.string(row["description"]) ?? "", start: start,
                end: DateParsing.portal(JSONFields.string(row["end_iso"])) ?? DateParsing.portal(JSONFields.string(row["end"])),
                location: JSONFields.string(row["location"]) ?? "",
                meetupID: JSONFields.firstString(meetup("id"), row["meetup_id"]), meetupName: name,
                latitude: JSONFields.firstNumber(row["latitude"], row["lat"], row["osm_lat"], meetup("latitude"), meetup("lat")),
                longitude: JSONFields.firstNumber(row["longitude"], row["lon"], row["lng"], row["osm_lon"], meetup("longitude"), meetup("lon"), meetup("lng")),
                url: web(JSONFields.firstString(row["link"], row["url"], meetup("portalLink"))),
                nostrAddress: JSONFields.string(row["nostr_address"])
            )
        }
    }
}

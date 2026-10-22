import Foundation

/// Directory association only, never a verification of organizer authority or attendance.
public enum MeetupEventMatcher {
    public static func portalIdentity(_ url: URL?) -> String? {
        guard let url, SafeURL.web(url.absoluteString) != nil,
              url.host?.lowercased() == "portal.einundzwanzig.space" else { return nil }
        let parts = url.path.split(separator: "/").map(String.init)
        guard let index = parts.firstIndex(of: "meetup"), index == parts.count - 2 else { return nil }
        return parts.last
    }

    public static func resolve(_ event: MeetupEvent, among meetups: [Meetup]) -> Meetup? {
        Resolver(meetups).resolve(event)
    }

    /// Always resolve original source events. Do not persist derived IDs as authoritative input.
    public static func associated(_ events: [MeetupEvent], with meetups: [Meetup]) -> [MeetupEvent] {
        let resolver = Resolver(meetups)
        return events.map { original in
            var event = original
            if let group = resolver.resolve(original) { event.meetupID = group.id }
            return event
        }
    }

    private struct Resolver {
        let byID: [String: [Meetup]]
        let byPortal: [String: [Meetup]]
        let byName: [String: [Meetup]]
        let cities: [(expression: NSRegularExpression, groups: [Meetup])]

        init(_ meetups: [Meetup]) {
            byID = Dictionary(grouping: meetups, by: \.id)
            byPortal = Dictionary(grouping: meetups.filter { portalIdentity($0.portalURL) != nil }, by: { portalIdentity($0.portalURL)! })
            byName = Dictionary(grouping: meetups.filter { !clean($0.name).isEmpty }, by: { clean($0.name) })
            let ignored: Set<String> = ["bitcoin", "meetup", "einundzwanzig", "stammtisch"]
            let cityGroups = Dictionary(grouping: meetups, by: { clean($0.city) })
            cities = cityGroups.compactMap { city, groups in
                guard city.count >= 3, !ignored.contains(city),
                      let expression = try? NSRegularExpression(pattern: "(^|[^\\p{L}\\p{N}])" + NSRegularExpression.escapedPattern(for: city) + "($|[^\\p{L}\\p{N}])") else { return nil }
                return (expression, groups)
            }
        }
        func resolve(_ event: MeetupEvent) -> Meetup? {
            func unique(_ values: [Meetup]?) -> Meetup? { values?.count == 1 ? values?.first : nil }
            if let id = event.meetupID, !id.isEmpty { return unique(byID[id]) }
            if let link = event.meetupPortalURL {
                guard let identity = portalIdentity(link) else { return nil }
                return unique(byPortal[identity])
            }
            if let named = byName[clean(event.title)] { return unique(named) }
            let haystack = clean(event.title + " " + event.location)
            let range = NSRange(haystack.startIndex..., in: haystack)
            let matching = cities.filter { $0.expression.firstMatch(in: haystack, range: range) != nil }.flatMap(\.groups)
            return unique(matching)
        }
    }
    private static func clean(_ text: String) -> String { text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
}

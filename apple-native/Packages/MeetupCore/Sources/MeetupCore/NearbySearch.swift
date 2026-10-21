import Foundation

public struct GeoPoint: Hashable, Sendable {
    public let latitude: Double
    public let longitude: Double
    public init?(latitude: Double, longitude: Double) {
        guard latitude.isFinite, longitude.isFinite, (-90...90).contains(latitude), (-180...180).contains(longitude) else { return nil }
        self.latitude = latitude
        self.longitude = longitude
    }
    /// Same spherical distance constant as the source application; straight-line distance, not travel time.
    public func kilometers(to other: GeoPoint) -> Double {
        let radians = Double.pi / 180
        let deltaLat = (other.latitude - latitude) * radians
        let deltaLon = (other.longitude - longitude) * radians
        let a = pow(sin(deltaLat / 2), 2) + cos(latitude * radians) * cos(other.latitude * radians) * pow(sin(deltaLon / 2), 2)
        return 6378.137 * 2 * asin(sqrt(min(1, max(0, a))))
    }
}

public struct NearbyMeetupMatch: Identifiable, Hashable, Sendable {
    public var id: String { meetup.id }
    public let meetup: Meetup
    public let distanceKilometers: Double?
    public let events: [MeetupEvent]
    public var nextEvent: MeetupEvent? { events.first }
}

public enum NearbySearch {
    /// Dates are a half-open interval, so local midnight boundaries also work on DST days.
    /// Group coordinates locate the group; they are not an event venue or presence proof.
    public static func search(meetups: [Meetup], events: [MeetupEvent], center: GeoPoint?, radiusKilometers: Double,
                              interval: DateInterval? = nil, now: Date = Date(), timeZone: TimeZone = .current) -> [NearbyMeetupMatch] {
        guard radiusKilometers.isFinite, radiusKilometers > 0 else { return [] }
        let associated = MeetupEventMatcher.associated(events, with: meetups)
        let byGroup = Dictionary(grouping: associated.compactMap { event -> MeetupEvent? in
            guard event.meetupID != nil else { return nil }
            if let interval {
                let date = CalendarDisplayWindow.filterDate(for: event, timeZone: timeZone)
                return date >= interval.start && date < interval.end ? event : nil
            }
            if event.isAllDay { return CalendarDisplayWindow.includes(event, now: now) ? event : nil }
            return event.start > now.addingTimeInterval(-43_200) ? event : nil
        }, by: { $0.meetupID! })
        return meetups.compactMap { meetup in
            var distance: Double?
            if let center {
                guard let lat = meetup.latitude, let lon = meetup.longitude, !(lat == 0 && lon == 0),
                      let point = GeoPoint(latitude: lat, longitude: lon) else { return nil }
                distance = center.kilometers(to: point)
                guard distance! <= radiusKilometers else { return nil }
            }
            let matching = (byGroup[meetup.id] ?? []).sorted { $0.start == $1.start ? $0.id < $1.id : $0.start < $1.start }
            var seen: Set<String> = []
            let uniqueEvents = matching.filter { seen.insert("\($0.title)|\($0.start.timeIntervalSince1970)|\($0.location)").inserted }
            guard interval == nil || !uniqueEvents.isEmpty else { return nil }
            return NearbyMeetupMatch(meetup: meetup, distanceKilometers: distance, events: uniqueEvents)
        }.sorted {
            if let a = $0.distanceKilometers, let b = $1.distanceKilometers, a != b { return a < b }
            return $0.meetup.name.localizedStandardCompare($1.meetup.name) == .orderedAscending
        }
    }
}

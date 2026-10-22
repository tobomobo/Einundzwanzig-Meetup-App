import Foundation

public struct Meetup: Codable, Hashable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var city: String
    public var country: String
    public var latitude: Double?
    public var longitude: Double?
    public var description: String
    public var logoURL: URL?
    public var portalURL: URL?
    public var telegramURL: URL?
    public var nextEventStart: Date?

    public init(id: String = UUID().uuidString, name: String = "", city: String = "",
                country: String = "", latitude: Double? = nil, longitude: Double? = nil,
                description: String = "", logoURL: URL? = nil, portalURL: URL? = nil,
                telegramURL: URL? = nil, nextEventStart: Date? = nil) {
        self.id = id; self.name = name; self.city = city; self.country = country
        self.latitude = latitude; self.longitude = longitude; self.description = description
        self.logoURL = logoURL; self.portalURL = portalURL; self.telegramURL = telegramURL
        self.nextEventStart = nextEventStart
    }
}

public struct MeetupEvent: Codable, Hashable, Sendable, Identifiable {
    public var id: String
    public var title: String
    public var description: String
    public var start: Date
    public var end: Date?
    public var location: String
    public var meetupID: String?
    public var meetupName: String?
    public var latitude: Double?
    public var longitude: Double?
    public var url: URL?
    public var nostrAddress: String?
    /// Hosting group link, distinct from the event/join URL. Never inferred from venue coordinates.
    public var meetupPortalURL: URL?
    public var isAllDay: Bool
    /// Source civil time zone for DATE values; preserves calendar dates when exporting elsewhere.
    public var calendarTimeZoneID: String?

    public init(id: String = UUID().uuidString, title: String = "", description: String = "",
                start: Date = Date(), end: Date? = nil, location: String = "",
                meetupID: String? = nil, meetupName: String? = nil,
                latitude: Double? = nil, longitude: Double? = nil, url: URL? = nil,
                nostrAddress: String? = nil, meetupPortalURL: URL? = nil,
                isAllDay: Bool = false, calendarTimeZoneID: String? = nil) {
        self.id = id; self.title = title; self.description = description
        self.start = start; self.end = end; self.location = location
        self.meetupID = meetupID; self.meetupName = meetupName
        self.latitude = latitude; self.longitude = longitude; self.url = url
        self.nostrAddress = nostrAddress
        self.meetupPortalURL = meetupPortalURL
        self.isAllDay = isAllDay
        self.calendarTimeZoneID = calendarTimeZoneID
    }

    private enum CodingKeys: String, CodingKey {
        case id, title, description, start, end, location, meetupID, meetupName, latitude, longitude, url, nostrAddress, meetupPortalURL, isAllDay, calendarTimeZoneID
    }
    public init(from decoder: any Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        title = try values.decodeIfPresent(String.self, forKey: .title) ?? ""
        description = try values.decodeIfPresent(String.self, forKey: .description) ?? ""
        start = try values.decode(Date.self, forKey: .start)
        end = try values.decodeIfPresent(Date.self, forKey: .end)
        location = try values.decodeIfPresent(String.self, forKey: .location) ?? ""
        meetupID = try values.decodeIfPresent(String.self, forKey: .meetupID)
        meetupName = try values.decodeIfPresent(String.self, forKey: .meetupName)
        latitude = try values.decodeIfPresent(Double.self, forKey: .latitude)
        longitude = try values.decodeIfPresent(Double.self, forKey: .longitude)
        url = try values.decodeIfPresent(URL.self, forKey: .url)
        nostrAddress = try values.decodeIfPresent(String.self, forKey: .nostrAddress)
        meetupPortalURL = try values.decodeIfPresent(URL.self, forKey: .meetupPortalURL)
        isAllDay = try values.decodeIfPresent(Bool.self, forKey: .isAllDay) ?? false
        calendarTimeZoneID = try values.decodeIfPresent(String.self, forKey: .calendarTimeZoneID)
    }
}

public struct NewsArticle: Codable, Hashable, Sendable, Identifiable {
    public var id: String
    public var title: String
    public var summary: String
    public var url: URL
    public var published: Date?
    public var author: String?
    public var imageURL: URL?

    public init(id: String = UUID().uuidString, title: String = "", summary: String = "",
                url: URL, published: Date? = nil, author: String? = nil, imageURL: URL? = nil) {
        self.id = id; self.title = title; self.summary = summary; self.url = url
        self.published = published; self.author = author; self.imageURL = imageURL
    }
}

public struct BitcoinSnapshot: Codable, Hashable, Sendable {
    public var height: Int
    public var usd: Double
    public var eur: Double
    public var fastestFee: Int
    public var halfHourFee: Int
    public var hourFee: Int
    public var fetchedAt: Date

    public init(height: Int, usd: Double, eur: Double, fastestFee: Int,
                halfHourFee: Int, hourFee: Int, fetchedAt: Date = Date()) {
        self.height = height; self.usd = usd; self.eur = eur
        self.fastestFee = fastestFee; self.halfHourFee = halfHourFee
        self.hourFee = hourFee; self.fetchedAt = fetchedAt
    }
}

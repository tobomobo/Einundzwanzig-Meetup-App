import Foundation

public struct Course: Codable, Hashable, Sendable, Identifiable {
    public var id: Int
    public var name: String
    public var description: String
    public var shortDescription: String?
    public var imageURL: URL?
    public var url: URL?
    public var lecturers: [LecturerSummary]
    public var events: [CourseDate]

    public init(id: Int, name: String = "", description: String = "", shortDescription: String? = nil,
                imageURL: URL? = nil, url: URL? = nil, lecturers: [LecturerSummary] = [], events: [CourseDate] = []) {
        self.id = id; self.name = name; self.description = description; self.shortDescription = shortDescription
        self.imageURL = imageURL; self.url = url; self.lecturers = lecturers; self.events = events
    }

    public var summary: CourseSummary {
        CourseSummary(id: id, name: name, imageURL: imageURL, url: url,
                      shortDescription: shortDescription ?? (description.isEmpty ? nil : description))
    }
}

public struct Lecturer: Codable, Hashable, Sendable, Identifiable {
    public var id: Int
    public var name: String
    public var description: String
    public var intro: String?
    public var imageURL: URL?
    public var url: URL?
    public var websiteURL: URL?
    public var twitterURL: URL?
    public var nostrURL: URL?
    public var courses: [CourseSummary]

    public init(id: Int, name: String = "", description: String = "", intro: String? = nil,
                imageURL: URL? = nil, url: URL? = nil, websiteURL: URL? = nil,
                twitterURL: URL? = nil, nostrURL: URL? = nil, courses: [CourseSummary] = []) {
        self.id = id; self.name = name; self.description = description; self.intro = intro
        self.imageURL = imageURL; self.url = url; self.websiteURL = websiteURL
        self.twitterURL = twitterURL; self.nostrURL = nostrURL; self.courses = courses
    }

    public var summary: LecturerSummary {
        LecturerSummary(id: id, name: name, imageURL: imageURL, url: url, shortDescription: intro)
    }
}

public struct CourseSummary: Codable, Hashable, Sendable, Identifiable {
    public var id: Int
    public var name: String
    public var imageURL: URL?
    public var url: URL?
    public var shortDescription: String?

    public init(id: Int, name: String = "", imageURL: URL? = nil, url: URL? = nil, shortDescription: String? = nil) {
        self.id = id; self.name = name; self.imageURL = imageURL; self.url = url; self.shortDescription = shortDescription
    }
}

public struct LecturerSummary: Codable, Hashable, Sendable, Identifiable {
    public var id: Int
    public var name: String
    public var imageURL: URL?
    public var url: URL?
    public var shortDescription: String?

    public init(id: Int, name: String = "", imageURL: URL? = nil, url: URL? = nil, shortDescription: String? = nil) {
        self.id = id; self.name = name; self.imageURL = imageURL; self.url = url; self.shortDescription = shortDescription
    }
}

public struct CourseDate: Codable, Hashable, Sendable, Identifiable {
    public var id: String
    public var title: String
    public var description: String
    public var start: Date
    public var end: Date?
    public var location: String
    public var joinURL: URL?
    public var url: URL?
    public var latitude: Double?
    public var longitude: Double?

    public init(id: String, title: String = "", description: String = "", start: Date, end: Date? = nil,
                location: String = "", joinURL: URL? = nil, url: URL? = nil,
                latitude: Double? = nil, longitude: Double? = nil) {
        self.id = id; self.title = title; self.description = description; self.start = start; self.end = end
        self.location = location; self.joinURL = joinURL; self.url = url
        self.latitude = latitude; self.longitude = longitude
    }
}

public enum PublicDetailSource: String, Codable, Sendable {
    case fresh
    case cache
    case unavailable
}

public enum PublicDetailFailure: Error, Equatable, Sendable, LocalizedError {
    case notFound
    case httpStatus(Int)
    case invalidData(String)
    case transport(String)

    public var errorDescription: String? {
        switch self {
        case .notFound: "The requested public detail was not found."
        case .httpStatus(let status): "The server returned HTTP \(status)."
        case .invalidData(let message): "The server returned invalid data: \(message)"
        case .transport(let message): message
        }
    }
}

/// A cache fallback always carries the failure that prevented a fresh detail response.
/// List entries and related summaries can supply a fallback; they are not proof of a full detail fetch.
public struct PublicDetailResult<Value: Sendable>: Sendable {
    public let value: Value?
    public let source: PublicDetailSource
    public let failure: PublicDetailFailure?

    public init(value: Value?, source: PublicDetailSource, failure: PublicDetailFailure? = nil) {
        self.value = value; self.source = source; self.failure = failure
    }
}

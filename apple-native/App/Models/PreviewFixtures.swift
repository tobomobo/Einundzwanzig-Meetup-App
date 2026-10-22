import Foundation
import MeetupCore

/// Fictional sample sessions and metadata. Never credentials, attendance or network data.
enum PreviewFixtures {
    static let course = Course(id: 9_000_001, name: "Preview · Bitcoin basics", description: "Fictional offline preview course. No registration or real appointment.", lecturers: [LecturerSummary(id: 9_000_002, name: "Preview · Community educator")], events: [CourseDate(id: "demo-course-date", title: "Preview · Bitcoin basics", start: events[0].start, end: events[0].end, location: "Preview · Wien")])
    static let lecturer = Lecturer(id: 9_000_002, name: "Preview · Community educator", description: "Fictional offline preview profile.", courses: [course.summary])
    static let meetups: [Meetup] = [
        Meetup(id: "demo-vienna", name: "Preview · Bitcoin Wien", city: "Wien", country: "AT", latitude: 48.2082, longitude: 16.3738, description: "Fictional preview group. Not a portal listing."),
        Meetup(id: "demo-berlin", name: "Preview · Berlin Mitte", city: "Berlin", country: "DE", latitude: 52.5200, longitude: 13.4050, description: "Fictional preview group. Not a portal listing."),
        Meetup(id: "demo-madrid", name: "Preview · Bitcoin Madrid", city: "Madrid", country: "ES", latitude: 40.4168, longitude: -3.7038, description: "Fictional preview group. Not a portal listing.")
    ]
    // Stable within a launch, future on every fresh install; no invented real-world schedule.
    static let events: [MeetupEvent] = meetups.enumerated().map { index, meetup in
        let start = Calendar.current.nextDate(after: Date().addingTimeInterval(Double(index + 1) * 86_400), matching: DateComponents(hour: 19), matchingPolicy: .nextTime) ?? Date().addingTimeInterval(172_800)
        return MeetupEvent(id: "demo-event-\(index)", title: meetup.name, description: "Fictional preview session. Local planning and calendar export can be explored without a backend.", start: start, end: start.addingTimeInterval(7200), location: meetup.city, meetupID: meetup.id, meetupName: meetup.name, latitude: meetup.latitude, longitude: meetup.longitude)
    }
    static let articles: [NewsArticle] = [
        NewsArticle(id: "demo-news", title: "Preview · Deine Bitcoin Community", summary: "This is an offline preview article. Switch off preview mode in Settings to load the actual Einundzwanzig RSS feed.", url: URL(string: "https://media.einundzwanzig.space/s/einundzwanzig-news")!, published: nil, author: "Preview")
    ]
    static let badges: [BadgePreview] = [
        BadgePreview(id: UUID(uuidString: "00000000-0000-4000-8000-000000000001")!, name: "Preview · Bitcoin Wien", importedAt: Date(), blockHeight: nil, isDemo: true),
        BadgePreview(id: UUID(uuidString: "00000000-0000-4000-8000-000000000002")!, name: "Preview · Bitcoin Wien", importedAt: Date().addingTimeInterval(-2_592_000), blockHeight: nil, isDemo: true),
        BadgePreview(id: UUID(uuidString: "00000000-0000-4000-8000-000000000003")!, name: "Preview · Berlin Mitte", importedAt: Date().addingTimeInterval(-604_800), blockHeight: nil, isDemo: true)
    ]
}

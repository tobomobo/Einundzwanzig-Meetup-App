import Foundation
import XCTest
@testable import MeetupCore

private actor CourseTransport: HTTPTransport {
    enum Reply: Sendable {
        case json(String)
        case failure(MeetupCoreError)
        case offline
        case cancelled
    }
    private var replies: [String: [Reply]]
    private var requests: [(URL, String)] = []

    init(_ replies: [String: [Reply]]) { self.replies = replies }

    func get(_ url: URL, accept: String) async throws -> Data {
        requests.append((url, accept))
        guard var queue = replies[url.path], !queue.isEmpty else {
            throw MeetupCoreError.invalidData("Unexpected request: \(url.path)")
        }
        let reply = queue.removeFirst(); replies[url.path] = queue
        switch reply {
        case .json(let body): return Data(body.utf8)
        case .failure(let error): throw error
        case .offline: throw URLError(.notConnectedToInternet)
        case .cancelled: throw CancellationError()
        }
    }

    func recorded() -> [(URL, String)] { requests }
}

final class CourseTests: XCTestCase, @unchecked Sendable {
    private let vienna = TimeZone(identifier: "Europe/Vienna")!

    // Field names and shapes are taken from the captured public Portal course 44 / lecturer 144.
    private let course44 = #"""
    {"id":44,"name":"21-Neumarkt Basiskurs Tag 1 - Grundlagen","description":"Am ersten Kurstag legen wir das Fundament.",
     "image":"https://portal.einundzwanzig.space/storage/2027/conversions/course-preview.jpg",
     "portalLink":"https://portal.einundzwanzig.space/de/course/44",
     "lecturer":{"id":144,"name":"Johannes","subtitle":"21neumarkt.de","image":"/storage/lecturer-thumb.jpg"},
     "events":[{"id":108,"course_id":44,"city_id":361,
       "location":"Bürgerhaus Neumarkt Oberpfalz, Fischergasse 1 92318 Neumarkt",
       "osm_type":null,"osm_id":null,"osm_name":null,"osm_address":null,"osm_lat":null,"osm_lon":null,
       "from":"2026-11-25T17:30:00.000000Z","to":"2026-11-25T19:00:00.000000Z","link":"https://21Neumarkt.de",
       "city":{"id":361,"name":"92318 Neumarkt in der Oberpfalz","country_id":1,"country":{"id":1,"name":"Germany","code":"de"}}}]}
    """#

    private let lecturer144 = #"""
    {"id":144,"name":"Johannes","subtitle":"21neumarkt.de","intro":"Fachinformatiker und Mitgründer.",
     "description":"Johannes gibt sein Wissen weiter.","image":"/storage/lecturer-preview.jpg","active":true,
     "nostr":null,"website":"https://21neumarkt.de","twitter_username":null,"lightning_address":null,
     "courses":[{"id":44,"name":"21-Neumarkt Basiskurs Tag 1 - Grundlagen","image":"/storage/course-thumb.jpg","next_event":"2026-11-25 17:30:00"},
       {"id":45,"name":"Praxiswissen","image":"/storage/course45-thumb.jpg","next_event":"2026-12-02 17:30:00"}]}
    """#

    func testCapturedDetailContractsAndNinetyMinuteEnd() async throws {
        let transport = CourseTransport([
            "/api/courses/44": [.json(course44)],
            "/api/lecturers/144": [.json("{\"data\":" + lecturer144 + "}")]
        ])
        let client = CoursesClient(transport: transport, floatingTimeZone: vienna)
        let courseResult = try await client.courseDetail(id: 44)
        XCTAssertEqual(courseResult.source, .fresh)
        XCTAssertNil(courseResult.failure)
        let course = try XCTUnwrap(courseResult.value)
        XCTAssertEqual(course.id, 44)
        XCTAssertEqual(course.lecturers.first?.id, 144)
        XCTAssertEqual(course.lecturers.first?.shortDescription, "21neumarkt.de")
        XCTAssertEqual(course.lecturers.first?.imageURL?.absoluteString, "https://portal.einundzwanzig.space/storage/lecturer-thumb.jpg")
        XCTAssertEqual(course.url?.path, "/de/course/44")
        let event = try XCTUnwrap(course.events.first)
        XCTAssertEqual(event.id, "108")
        XCTAssertEqual(event.title, course.name)
        XCTAssertEqual(event.start, DateParsing.portal("2026-11-25T17:30:00Z"))
        XCTAssertEqual(try XCTUnwrap(event.end).timeIntervalSince(event.start), 90 * 60)
        XCTAssertEqual(event.joinURL?.host?.lowercased(), "21neumarkt.de")
        XCTAssertNil(event.latitude)
        XCTAssertNil(event.longitude)

        let lecturerResult = try await client.lecturerDetail(id: 144)
        XCTAssertEqual(lecturerResult.source, .fresh)
        let lecturer = try XCTUnwrap(lecturerResult.value)
        XCTAssertEqual(lecturer.courses.map(\.id), [44, 45])
        XCTAssertEqual(lecturer.intro, "Fachinformatiker und Mitgründer.")
        XCTAssertEqual(lecturer.websiteURL?.host, "21neumarkt.de")
        XCTAssertNil(lecturer.url) // Actual lecturer detail omits portalLink.
        XCTAssertNil(lecturer.nostrURL)
    }

    func testListWrappersQueriesAndMissingOptionalFields() async throws {
        for (index, wrap) in ["[%@]", "{\"data\":[%@]}", "{\"items\":[%@]}", "{\"data\":null,\"items\":[%@]}"].enumerated() {
            let courseRow = #"{"id":44,"name":"Basics","description":"","next_event":"2026-11-25 17:30:00","lecturer":{"id":144,"name":"Johannes","subtitle":null,"image":null}}"#
            let lecturerRow = #"{"id":144,"name":"Johannes","subtitle":null,"future_events_count":0,"next_event":null,"image":null,"media":[]}"#
            let transport = CourseTransport([
                "/api/courses": [.json(wrap.replacingOccurrences(of: "%@", with: courseRow))],
                "/api/lecturers": [.json(wrap.replacingOccurrences(of: "%@", with: lecturerRow))]
            ])
            let client = CoursesClient(transport: transport)
            let courses = try await client.courses()
            let lecturers = try await client.lecturers()
            XCTAssertEqual(courses.map(\.id), [44], "wrapper \(index)")
            XCTAssertTrue(courses[0].events.isEmpty) // next_event is not a fabricated full appointment.
            XCTAssertEqual(courses[0].description, "")
            XCTAssertNil(courses[0].imageURL)
            XCTAssertNil(courses[0].shortDescription)
            XCTAssertEqual(lecturers.map(\.id), [144])
            XCTAssertEqual(lecturers[0].description, "")
            XCTAssertNil(lecturers[0].intro)
            XCTAssertTrue(lecturers[0].courses.isEmpty)
            for (url, accept) in await transport.recorded() {
                XCTAssertEqual(url.host, "portal.einundzwanzig.space")
                XCTAssertEqual(url.query, "withDetails=1")
                XCTAssertEqual(accept, "application/json")
            }
        }
    }

    func testDottedRelationshipsAliasesSanitizationAndVenueOnlyCoordinates() async throws {
        let row = #"""
        {"id":7,"title":"<b>Basics &amp; practice</b>","description":"<p>Hello &lt;world&gt;</p><script>bad()</script><style>bad</style>",
         "intro":"<em>Short</em>","image":"/storage/course.png","portalLink":"/de/course/7",
         "latitude":1,"longitude":2,"lecturer.id":8,"lecturer.name":"Flat name","lecturer.subtitle":"<b>Teacher</b>",
         "lecturer":{"id":9,"name":"Nested name","latitude":11,"longitude":12},
         "course_events":[{"id":"date-1","name":"<b>Session</b>","start":"2026-07-01 18:15:00","end":null,
            "venue.name":"Flat venue","venue.latitude":"48.1","venue.longitude":"16.2",
            "venue":{"name":"Nested venue","latitude":49,"longitude":17},"link":"https://example.org/join"},
           {"id":2,"from":"2026-07-02T12:00:00Z","to":null,"city":{"latitude":33,"longitude":44},"meetup":{"latitude":55,"longitude":66}}]}
        """#
        let client = CoursesClient(transport: CourseTransport(["/api/courses/7": [.json(row)]]), floatingTimeZone: vienna)
        let result = try await client.courseDetail(id: 7)
        let course = try XCTUnwrap(result.value)
        XCTAssertEqual(course.name, "Basics & practice")
        XCTAssertEqual(course.description, "Hello <world>")
        XCTAssertEqual(course.shortDescription, "Short")
        XCTAssertEqual(course.lecturers.first?.id, 8)
        XCTAssertEqual(course.lecturers.first?.name, "Flat name")
        XCTAssertEqual(course.lecturers.first?.shortDescription, "Teacher")
        XCTAssertEqual(course.events[0].start, DateParsing.portal("2026-07-01T16:15:00Z"))
        XCTAssertEqual(course.events[0].location, "Flat venue")
        XCTAssertEqual(course.events[0].latitude, 48.1)
        XCTAssertEqual(course.events[0].longitude, 16.2)
        XCTAssertNil(course.events[0].end)
        XCTAssertNil(course.events[1].latitude)
        XCTAssertNil(course.events[1].longitude)
    }

    func testUnsafeLinksAndImagesDoNotEscapeWebURLPolicy() async throws {
        let row = #"""
        {"id":7,"image":"javascript:alert(1)","logo":"//evil.example/logo.png","avatar":"/storage/safe.png",
         "portalLink":"https://user:pass@example.org/private",
         "lecturer":{"id":8,"name":"Teacher","website":"file:///etc/passwd","twitter_username":"javascript:bad","nostr":"npub1bare"},
         "events":[{"id":1,"from":"2026-01-01T12:00:00Z","link":"javascript:bad","url":"data:text/html,bad",
           "osm_lat":999,"osm_lon":"NaN"}]}
        """#
        let transport = CourseTransport(["/api/courses/7": [.json(row)], "/api/lecturers/8": [.offline]])
        let client = CoursesClient(transport: transport)
        let result = try await client.courseDetail(id: 7)
        let course = try XCTUnwrap(result.value)
        XCTAssertEqual(course.imageURL?.path, "/storage/safe.png")
        XCTAssertNil(course.url)
        XCTAssertNil(course.events[0].joinURL)
        XCTAssertNil(course.events[0].url)
        XCTAssertNil(course.events[0].latitude)
        XCTAssertNil(course.events[0].longitude)
        let lecturerResult = try await client.lecturerDetail(id: 8)
        let lecturer = try XCTUnwrap(lecturerResult.value)
        XCTAssertNil(lecturer.websiteURL)
        XCTAssertNil(lecturer.twitterURL)
        XCTAssertNil(lecturer.nostrURL)
    }

    func testFloatingLocalDatesOffsetsFractionsAndDST() throws {
        func date(_ text: String) -> Date? { CourseParsing.date(text, floatingTimeZone: vienna) }
        XCTAssertEqual(date("2026-11-25 17:30:00"), DateParsing.portal("2026-11-25T16:30:00Z"))
        XCTAssertEqual(date("2026-07-01 17:30:00"), DateParsing.portal("2026-07-01T15:30:00Z"))
        XCTAssertEqual(date("2026-11-25T17:30:00.000000Z"), DateParsing.portal("2026-11-25T17:30:00Z"))
        XCTAssertEqual(date("2026-07-01T17:30:00+02:00"), DateParsing.portal("2026-07-01T15:30:00Z"))
        XCTAssertEqual(try XCTUnwrap(date("2026-07-01 17:30:00.123456")).timeIntervalSince1970,
                       try XCTUnwrap(DateParsing.portal("2026-07-01T15:30:00.123456Z")).timeIntervalSince1970, accuracy: 0.000001)
        // Spring gap advances 02:30 to 03:30; autumn's repeated 02:30 chooses the first occurrence.
        XCTAssertEqual(date("2026-03-29 02:30:00"), DateParsing.portal("2026-03-29T01:30:00Z"))
        XCTAssertEqual(date("2026-10-25 02:30:00"), DateParsing.portal("2026-10-25T00:30:00Z"))
        XCTAssertNil(date("2026-02-30 18:00:00"))
        XCTAssertNil(date("2026-10-21 24:00:00"))
        XCTAssertNil(date("yesterday"))
        // Existing meetup semantics remain UTC.
        XCTAssertEqual(DateParsing.portal("2026-11-25 17:30:00"), DateParsing.portal("2026-11-25T17:30:00Z"))
    }

    func testLecturerNostrReferencesUseValidatedCanonicalPublicProfiles() async throws {
        let identity = try PublicIdentity(String(repeating: "ab", count: 32))
        for (raw, expected) in [
            (identity.hex, "https://njump.me/" + identity.npub),
            (identity.npub, "https://njump.me/" + identity.npub),
            ("nostr:" + identity.npub.uppercased(), "https://njump.me/" + identity.npub),
            ("https://example.org/profile", "https://example.org/profile"),
            ("npub1invalid", ""), ("javascript:bad", ""), ("nsec1invalid", "")
        ] {
            let data = try JSONSerialization.data(withJSONObject: ["id": 1, "nostr": raw])
            let body = try XCTUnwrap(String(data: data, encoding: .utf8))
            let client = CoursesClient(transport: CourseTransport(["/api/lecturers/1": [.json(body)]]))
            let result = try await client.lecturerDetail(id: 1)
            XCTAssertEqual(result.source, .fresh)
            XCTAssertEqual(result.value?.nostrURL?.absoluteString ?? "", expected)
        }
    }

    func testCached404OfflineAndMalformedDetailsKeepHonestProvenance() async throws {
        let transport = CourseTransport([
            "/api/courses": [.json(#"[{"id":44,"name":"Summary","description":"List description","lecturer":{"id":144,"name":"Johannes"}}]"#)],
            "/api/courses/44": [.failure(.httpStatus(404)), .json(course44), .offline, .json(#"{"data":{"id":99,"name":"Wrong ID"}}"#)],
            "/api/lecturers/144": [.failure(.httpStatus(404))]
        ])
        let client = CoursesClient(transport: transport)
        _ = try await client.courses()
        let missingDetail = try await client.courseDetail(id: 44)
        XCTAssertEqual(missingDetail.source, .cache)
        XCTAssertEqual(missingDetail.failure, .notFound)
        XCTAssertEqual(missingDetail.value?.description, "List description")
        let nestedLecturer = try await client.lecturerDetail(id: 144)
        XCTAssertEqual(nestedLecturer.source, .cache)
        XCTAssertEqual(nestedLecturer.value?.courses.map(\.id), [44])
        let fresh = try await client.courseDetail(id: 44)
        XCTAssertEqual(fresh.source, .fresh)
        XCTAssertNil(fresh.failure)
        let offline = try await client.courseDetail(id: 44)
        XCTAssertEqual(offline.source, .cache)
        XCTAssertNotNil(offline.failure)
        XCTAssertEqual(offline.value, fresh.value)
        let malformed = try await client.courseDetail(id: 44)
        XCTAssertEqual(malformed.source, .cache)
        guard case .invalidData? = malformed.failure else { return XCTFail("Wrong-ID responses must be rejected") }
        XCTAssertEqual(malformed.value?.id, 44)
    }

    func testNestedRelatedCachesPreserveDetailsAcrossSummaryHydration() async throws {
        let transport = CourseTransport([
            "/api/courses/44": [.json(course44), .offline],
            "/api/lecturers/144": [.json(lecturer144), .offline],
            "/api/courses/45": [.failure(.httpStatus(404))],
            "/api/lecturers": [.json(#"[{"id":144,"name":"Johannes","subtitle":"Summary","image":"/storage/lecturer-thumb.jpg"}]"#)],
            "/api/courses": [.json(#"[{"id":44,"name":"Summary course","image":"/storage/course-thumb.jpg","description":"Short","lecturer":{"id":144,"name":"Johannes"}}]"#)]
        ])
        let client = CoursesClient(transport: transport)
        _ = try await client.courseDetail(id: 44)
        _ = try await client.lecturerDetail(id: 144)
        _ = try await client.lecturers()
        _ = try await client.courses()
        let cachedCourse = try await client.courseDetail(id: 44)
        XCTAssertEqual(cachedCourse.source, .cache)
        XCTAssertEqual(cachedCourse.value?.events.count, 1)
        XCTAssertEqual(cachedCourse.value?.imageURL?.lastPathComponent, "course-preview.jpg")
        XCTAssertEqual(cachedCourse.value?.description, "Am ersten Kurstag legen wir das Fundament.")
        let cachedLecturer = try await client.lecturerDetail(id: 144)
        XCTAssertEqual(cachedLecturer.source, .cache)
        XCTAssertEqual(cachedLecturer.value?.description, "Johannes gibt sein Wissen weiter.")
        XCTAssertEqual(cachedLecturer.value?.imageURL?.lastPathComponent, "lecturer-preview.jpg")
        XCTAssertEqual(cachedLecturer.value?.courses.map(\.id), [44, 45])
        let nestedCourse = try await client.courseDetail(id: 45)
        XCTAssertEqual(nestedCourse.source, .cache)
        XCTAssertEqual(nestedCourse.failure, .notFound)
        XCTAssertEqual(nestedCourse.value?.name, "Praxiswissen")
        XCTAssertEqual(nestedCourse.value?.lecturers.map(\.id), [144])
    }

    func testUncachedNotFoundFailuresCancellationAndConvenienceAPI() async throws {
        let transport = CourseTransport([
            "/api/courses/1": [.failure(.httpStatus(404)), .failure(.httpStatus(404))],
            "/api/courses/2": [.offline, .offline],
            "/api/lecturers/3": [.failure(.httpStatus(503))],
            "/api/courses/4": [.cancelled]
        ])
        let client = CoursesClient(transport: transport)
        let missing = try await client.courseDetail(id: 1)
        XCTAssertEqual(missing.source, .unavailable)
        XCTAssertEqual(missing.failure, .notFound)
        XCTAssertNil(missing.value)
        let missingConvenience = try await client.course(id: 1)
        XCTAssertNil(missingConvenience)
        let offline = try await client.courseDetail(id: 2)
        XCTAssertEqual(offline.source, .unavailable)
        XCTAssertNotNil(offline.failure)
        do { _ = try await client.course(id: 2); XCTFail("Uncached offline errors must throw") }
        catch { XCTAssertTrue(error is PublicDetailFailure) }
        let unavailable = try await client.lecturerDetail(id: 3)
        XCTAssertEqual(unavailable.failure, .httpStatus(503))
        XCTAssertEqual(unavailable.source, .unavailable)
        do { _ = try await client.courseDetail(id: 4); XCTFail("Cancellation must propagate") }
        catch { XCTAssertTrue(error is CancellationError) }
        let invalidID = try await client.courseDetail(id: -1)
        XCTAssertEqual(invalidID.source, .unavailable)
        let requests = await transport.recorded()
        XCTAssertEqual(requests.count, 6)
    }

    func testMalformedListsDetailsAndIDsFailWithoutInventedContent() async throws {
        for body in ["not JSON", "{}", "[true]", #"{"data":{},"items":[]}"#, #"[{"name":"Missing ID"}]"#,
                     #"[{"id":true}]"#, #"[{"id":1.5}]"#, #"[{"id":0}]"#, #"[{"id":-1}]"#,
                     #"[{"id":"1x"}]"#, #"[{"id":1,"events":[{"from":"bad"}]}]"#,
                     #"[{"id":1,"lecturer":{"name":"No ID"}}]"#,
                     #"[{"id":1,"events":[{"id":true,"from":"2026-01-01T12:00:00Z"}]}]"#,
                     #"[{"id":1,"events":[{"id":1.5,"from":"2026-01-01T12:00:00Z"}]}]"#] {
            let client = CoursesClient(transport: CourseTransport(["/api/courses": [.json(body)]]))
            do { _ = try await client.courses(); XCTFail("Expected malformed list to throw: \(body)") }
            catch { XCTAssertTrue(error is MeetupCoreError) }
        }
        for body in ["[]", #"{"data":[]}"#, #"{"data":null}"#, #"{"id":1,"events":{}}"#] {
            let client = CoursesClient(transport: CourseTransport(["/api/courses/1": [.json(body)]]))
            let result = try await client.courseDetail(id: 1)
            XCTAssertEqual(result.source, .unavailable)
            guard case .invalidData? = result.failure else { return XCTFail("Expected malformed detail failure") }
        }
    }

    func testMalformedResponseDoesNotPartiallyHydrateRelatedCache() async throws {
        let transport = CourseTransport([
            "/api/courses": [.json(#"[{"id":1,"lecturer":{"id":2,"name":"Uncommitted summary"}},{"id":true}]"#)],
            "/api/lecturers/2": [.failure(.httpStatus(404))]
        ])
        let client = CoursesClient(transport: transport)
        do { _ = try await client.courses(); XCTFail("Malformed list must fail") } catch {}
        let related = try await client.lecturerDetail(id: 2)
        XCTAssertEqual(related.source, .unavailable)
        XCTAssertNil(related.value)
        XCTAssertEqual(related.failure, .notFound)
    }

    func testListIdentityMatchesCachedFallbackAndListFailuresStillThrow() async throws {
        let transport = CourseTransport([
            "/api/courses": [.json(#"[{"id":"44","name":"First"},{"id":44,"name":"Duplicate"}]"#), .offline],
            "/api/lecturers": [.json(#"[{"id":144,"name":"Teacher"}]"#), .failure(.httpStatus(503))],
            "/api/courses/44": [.failure(.httpStatus(404))]
        ])
        let client = CoursesClient(transport: transport)
        let list = try await client.courses()
        XCTAssertEqual(list.map(\.id), [44])
        XCTAssertEqual(list.first?.name, "First")
        let fallback = try await client.courseDetail(id: 44)
        XCTAssertEqual(fallback.value?.name, "First")
        _ = try await client.lecturers()
        do { _ = try await client.courses(); XCTFail("Cached lists must not mask an offline refresh") }
        catch { XCTAssertTrue(error is URLError) }
        do { _ = try await client.lecturers(); XCTFail("Cached lists must not mask an HTTP refresh failure") }
        catch { XCTAssertEqual(error as? MeetupCoreError, .httpStatus(503)) }
    }

    func testStableEventIdentityAndEmptyPrimaryEventsArray() async throws {
        let row = #"""
        {"id":1,"name":"Course","events":[{"from":"2026-01-01 12:00:00","location":"Venue"},
          {"from":"2026-01-01 12:00:00","location":"Venue"},{"id":"explicit","from":"2026-01-01 13:00:00"}]}
        """#
        let transport = CourseTransport([
            "/api/courses/1": [.json(row), .json(row)],
            "/api/courses/2": [.json(#"{"id":2,"events":[],"course_events":[{"from":"2026-01-01 12:00:00"}]}"#)]
        ])
        let client = CoursesClient(transport: transport, floatingTimeZone: vienna)
        let first = try await client.courseDetail(id: 1)
        let second = try await client.courseDetail(id: 1)
        XCTAssertEqual(first.value?.events.count, 2)
        XCTAssertEqual(first.value?.events.map(\.id), second.value?.events.map(\.id))
        let empty = try await client.courseDetail(id: 2)
        XCTAssertEqual(empty.value?.events.count, 0)
    }

    func testNormalizedModelsHavePublicInitializersAndCodableRoundTrips() throws {
        let course = Course(id: 1, name: "Course", lecturers: [LecturerSummary(id: 2, name: "Teacher")],
                            events: [CourseDate(id: "3", start: Date(timeIntervalSince1970: 1), end: nil)])
        let lecturer = Lecturer(id: 2, name: "Teacher", courses: [course.summary])
        XCTAssertEqual(try JSONDecoder().decode(Course.self, from: JSONEncoder().encode(course)), course)
        XCTAssertEqual(try JSONDecoder().decode(Lecturer.self, from: JSONEncoder().encode(lecturer)), lecturer)
        XCTAssertEqual(Set([course, course]).count, 1)
    }
}

import Foundation
import XCTest
@testable import MeetupCore

private actor PublicLearningLinkTransport: HTTPTransport {
    private var replies: [String: [String]]
    init(_ replies: [String: [String]]) { self.replies = replies }
    func get(_ url: URL, accept: String) async throws -> Data {
        guard var values = replies[url.path], !values.isEmpty else { throw MeetupCoreError.httpStatus(503) }
        let value = values.removeFirst(); replies[url.path] = values
        return Data(value.utf8)
    }
}

final class PublicLearningLinksParityTests: XCTestCase, @unchecked Sendable {
    func testDescriptionLinksIncludeHTMLDestinationsAndPreserveOrderWithoutDuplicates() {
        let source = #"Read (https://example.org/one?a=1&amp;b=2), https://example.org/one?a=1&amp;b=2 and <a href="https://example.org/two">register</a>. javascript:alert(1) file:///tmp/no https://user:pass@example.org/private https:///missing https://example.org/a\b"#
        XCTAssertEqual(CourseDescriptionLinks.urls(in: source).map(\.absoluteString), ["https://example.org/one?a=1&b=2", "https://example.org/two"])
    }
    func testOldCachedCourseWithoutLinkFieldRemainsDecodableAndClickable() throws {
        let json = #"{"id":7,"name":"Course","description":"Read https://example.org/register","lecturers":[],"events":[]}"#
        let course = try JSONDecoder().decode(Course.self, from: Data(json.utf8))
        XCTAssertNil(course.descriptionLinks)
        XCTAssertEqual(course.descriptionURLs.map(\.absoluteString), ["https://example.org/register"])
    }
    func testCachedDescriptionDestinationsAreRevalidated() {
        let course = Course(id: 7, descriptionLinks: [URL(string: "javascript:alert(1)")!, URL(string: "file:///tmp/no")!, URL(string: "https://user:pass@example.org")!, URL(string: "https://example.org/register")!])
        XCTAssertEqual(course.descriptionURLs.map(\.absoluteString), ["https://example.org/register"])
    }
    func testCourseClientRetainsHTMLLinkAcrossCacheRoundTripAndNestedSummary() async throws {
        let full = #"{"id":7,"name":"Course","description":"<p>Read <a href=\"https://example.org/register?a=1&amp;b=2\">register here</a></p>"}"#
        let lecturer = #"{"id":9,"name":"Teacher","courses":[{"id":7,"name":"Summary","description":"https://example.org/summary"}]}"#
        let client = CoursesClient(transport: PublicLearningLinkTransport(["/api/courses/7": [full], "/api/lecturers/9": [lecturer]]))
        let fresh = try await client.courseDetail(id: 7)
        let course = try XCTUnwrap(fresh.value)
        XCTAssertEqual(course.description, "Read register here")
        XCTAssertEqual(course.descriptionURLs.map(\.absoluteString), ["https://example.org/register?a=1&b=2"])
        let restored = try JSONDecoder().decode(Course.self, from: JSONEncoder().encode(course))
        XCTAssertEqual(restored.descriptionURLs, course.descriptionURLs)
        _ = try await client.lecturerDetail(id: 9)
        let fallback = try await client.courseDetail(id: 7)
        XCTAssertEqual(fallback.source, .cache)
        XCTAssertEqual(fallback.value?.descriptionURLs, course.descriptionURLs)
    }
}

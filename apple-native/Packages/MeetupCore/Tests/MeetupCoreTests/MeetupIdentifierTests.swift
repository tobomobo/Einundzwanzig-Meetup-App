import XCTest
@testable import MeetupCore

final class MeetupIdentifierTests: XCTestCase {
    func testSignedSlugResolvesOnlyUnambiguousCityAndISOCountry() {
        let austria = Meetup(id: "7", city: " Neu Wien ", country: "AT")
        let germany = Meetup(id: "8", city: "Neu Wien", country: "DE")
        XCTAssertEqual(MeetupIdentifier.compose(city: " Neu Wien ", country: "at"), "neu-wien-at")
        XCTAssertEqual(MeetupIdentifier.resolve("neu-wien-at", among: [austria, germany])?.id, "7")
        XCTAssertEqual(MeetupIdentifier.resolve("7", among: [austria, germany])?.id, "7")
        XCTAssertNil(MeetupIdentifier.resolve("neu-wien", among: [austria, germany]))
        XCTAssertNil(MeetupIdentifier.resolve("neu-wien-ch", among: [austria, germany]))
        XCTAssertNil(MeetupIdentifier.resolve("neu-wien-at", among: [austria, Meetup(id: "9", city: "Neu Wien", country: "AT")]))
    }
}

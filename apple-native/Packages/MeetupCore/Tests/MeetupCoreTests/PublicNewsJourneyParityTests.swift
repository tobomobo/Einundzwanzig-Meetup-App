import Foundation
import XCTest
@testable import MeetupCore

final class PublicNewsJourneyParityTests: XCTestCase {
    func testNostrOnlyRSSArticleRemainsReadableAndSharesOfficialOverview() throws {
        let address = "30023:" + String(repeating: "a", count: 64) + ":identifier:with:colons"
        let feed = "<rss><channel><item><guid>" + address + "</guid><title>Relay article</title><description>Readable summary</description></item></channel></rss>"
        let article = try XCTUnwrap(RSSParsing.articles(Data(feed.utf8)).first)
        XCTAssertEqual(article.id, address)
        XCTAssertEqual(article.title, "Relay article")
        XCTAssertEqual(article.summary, "Readable summary")
        XCTAssertEqual(article.url, NewsClient.websiteURL)
    }

    func testNostrFallbackDoesNotHideUsableArticleURLOrExecuteUnsafeLink() throws {
        let address = "30023:" + String(repeating: "f", count: 64) + ":article"
        for (link, expected) in [("https://example.org/original", "https://example.org/original"), ("javascript:alert(1)", NewsClient.websiteURL.absoluteString)] {
            let feed = "<rss><channel><item><guid>" + address + "</guid><title>Article</title><link>" + link + "</link></item></channel></rss>"
            let article = try XCTUnwrap(RSSParsing.articles(Data(feed.utf8)).first)
            XCTAssertEqual(article.url.absoluteString, expected)
        }
    }

    func testMalformedNostrGUIDDoesNotCreateAnArticleDestination() throws {
        for address in ["30023:key:article", "30023:" + String(repeating: "g", count: 64) + ":article", "30023:" + String(repeating: "a", count: 64) + ":", "1:" + String(repeating: "a", count: 64) + ":article"] {
            let feed = "<rss><channel><item><guid>" + address + "</guid><title>Unusable</title></item></channel></rss>"
            XCTAssertTrue(try RSSParsing.articles(Data(feed.utf8)).isEmpty)
        }
    }
}

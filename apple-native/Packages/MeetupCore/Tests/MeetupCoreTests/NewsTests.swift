import Foundation
import XCTest
@testable import MeetupCore

final class NewsTests: XCTestCase, @unchecked Sendable {
    func testRSSCDATAEntitiesImagesAndOffsetDates() throws {
        let feed = #"""
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:content="http://purl.org/rss/1.0/modules/content/">
        <channel><title>Feed title is not an article</title>
        <item><guid>30023:pubkey:identifier:with:colons</guid><title><![CDATA[Hello &amp; <b>Bitcoin</b>]]></title>
        <link>https://example.org/one?a=1&amp;b=2</link><author>Fallback author</author><dc:creator>Alice</dc:creator>
        <pubDate>Mon, 01 Jan 2024 02:00:00 +0200</pubDate>
        <description><![CDATA[<p>It&#x27;s &nbsp; safe &amp; useful.</p><script>doBadThings()</script><style>hidden</style><p>Next.</p>]]></description>
        <enclosure url="https://example.org/cover.jpg" type="image/jpeg"/>
        </item>
        <item><link>https://example.org/two</link><author>Bob</author><pubDate>nonsense</pubDate>
        <content:encoded><![CDATA[<p>Fallback body</p><img src='https://example.org/fallback.jpg' />]]></content:encoded></item>
        </channel></rss>
        """#
        let articles = try RSSParsing.articles(Data(feed.utf8))
        XCTAssertEqual(articles.count, 2)
        XCTAssertEqual(articles[0].id, "30023:pubkey:identifier:with:colons")
        XCTAssertEqual(articles[0].title, "Hello & Bitcoin")
        XCTAssertEqual(articles[0].summary, "It's safe & useful. Next.")
        XCTAssertEqual(articles[0].author, "Alice")
        XCTAssertEqual(articles[0].url.absoluteString, "https://example.org/one?a=1&b=2")
        XCTAssertEqual(articles[0].published, DateParsing.portal("2024-01-01T00:00:00Z"))
        XCTAssertEqual(articles[0].imageURL?.absoluteString, "https://example.org/cover.jpg")
        XCTAssertEqual(articles[1].title, "(ohne Titel)")
        XCTAssertEqual(articles[1].summary, "Fallback body")
        XCTAssertEqual(articles[1].author, "Bob")
        XCTAssertNil(articles[1].published)
        XCTAssertEqual(articles[1].imageURL?.absoluteString, "https://example.org/fallback.jpg")
    }

    func testRSSSkipsUnsafeUnusableAndDuplicateArticles() throws {
        let feed = #"""
        <rss><channel>
        <item><title>Unsafe</title><link>javascript:alert(1)</link></item>
        <item><title>Missing link</title><guid>30023:key:event</guid></item>
        <item><link>https://example.org/empty</link></item>
        <item><guid>same-id</guid><title>One</title><link>https://example.org/one</link><enclosure url="file:///etc/passwd"/></item>
        <item><guid>same-id</guid><title>Duplicate</title><link>https://example.org/two</link></item>
        <item><title>Web GUID fallback</title><guid>https://example.org/three</guid></item>
        </channel></rss>
        """#
        let articles = try RSSParsing.articles(Data(feed.utf8))
        XCTAssertEqual(articles.map(\.title), ["One", "Web GUID fallback"])
        XCTAssertNil(articles[0].imageURL)
    }

    func testRSSRejectsMalformedXMLAndEntityDeclarations() {
        for text in ["<rss><channel>", "<html/>", #"<!DOCTYPE rss [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss><channel>&x;</channel></rss>"#,
                     #"<!DOCTYPE rss [<!ENTITY x "hello">]><rss/>"#] {
            XCTAssertThrowsError(try RSSParsing.articles(Data(text.utf8)), text)
        }
        XCTAssertThrowsError(try RSSParsing.articles(Data(repeating: 32, count: 4 * 1024 * 1024 + 1)))
    }

    func testNewsClientUsesOfficialFeed() async throws {
        let transport = RecordingTransport(["/s/einundzwanzig-news/feed.xml": "<rss><channel/></rss>"])
        let articles = try await NewsClient(transport: transport).articles()
        XCTAssertEqual(articles, [])
        let paths = await transport.paths()
        XCTAssertEqual(paths, ["/s/einundzwanzig-news/feed.xml"])
    }

    func testPlainTextPreservesComparisonsAndDecodesCommonEntities() {
        XCTAssertEqual(HTMLText.plain("2 &lt; 3 &amp; 5 &gt; 4"), "2 < 3 & 5 > 4")
        XCTAssertEqual(HTMLText.plain("2 < 3 & 5 > 4"), "2 < 3 & 5 > 4")
        XCTAssertEqual(HTMLText.plain("<p>Gr&uuml;&szlig;e kosten 5 &euro; &copy; &Ouml; &#X1F ₿</p>"), "Grüße kosten 5 € © Ö &#X1F ₿")
        XCTAssertEqual(HTMLText.plain("<p>A</p><script>hidden</script><style>hidden</style><p>B</p>"), "A B")
    }

    func testMalformedHTMLAndManyEntitiesUseBoundedScans() {
        let brackets = String(repeating: "<", count: 200_000)
        XCTAssertEqual(HTMLText.plain(brackets), brackets)
        let unterminated = "<img src='" + String(repeating: "<img src='", count: 20_000)
        XCTAssertFalse(HTMLText.plain(unterminated).isEmpty)
        XCTAssertEqual(HTMLText.entities(String(repeating: "&amp;", count: 50_000)), String(repeating: "&", count: 50_000))
    }

    func testImageAttributeUsesExactSrcAndIgnoresHiddenContent() {
        XCTAssertEqual(HTMLText.firstImage("<img src='https://example.org/real.jpg' data-src='https://example.org/other.jpg'>")?.absoluteString,
                       "https://example.org/real.jpg")
        XCTAssertNil(HTMLText.firstImage("<img data-src='https://example.org/other.jpg'>"))
        XCTAssertEqual(HTMLText.firstImage("<script><img src='https://example.org/hidden'></script><IMG SRC=https://example.org/real>")?.absoluteString,
                       "https://example.org/real")
    }

    func testInertXMLDeclarationTextInCDATAOrCommentsIsAllowed() throws {
        let xml = #"""
        <!-- An inert example: <!DOCTYPE html> -->
        <rss><channel><item><title>Example</title><link>https://example.org/article</link>
        <description><![CDATA[<!DOCTYPE html><p>Visible text &lt; 3.</p>]]></description>
        </item></channel></rss>
        """#
        let articles = try RSSParsing.articles(Data(xml.utf8))
        XCTAssertEqual(articles.first?.summary, "Visible text < 3.")
    }
}

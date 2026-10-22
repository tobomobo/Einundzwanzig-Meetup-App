import Foundation
#if canImport(FoundationXML)
import FoundationXML
#endif

public actor NewsClient {
    public static let websiteURL = URL(string: "https://media.einundzwanzig.space/s/einundzwanzig-news")!
    public static let feedURL = URL(string: "https://media.einundzwanzig.space/s/einundzwanzig-news/feed.xml")!
    private let transport: any HTTPTransport

    public init() { transport = URLSessionTransport() }
    init(transport: any HTTPTransport) { self.transport = transport }

    public func articles() async throws -> [NewsArticle] {
        let data = try await transport.get(Self.feedURL, accept: "application/rss+xml, application/xml, text/xml")
        return try RSSParsing.articles(data)
    }
}

enum RSSParsing {
    static func articles(_ data: Data) throws -> [NewsArticle] {
        guard data.count <= 4 * 1024 * 1024 else { throw MeetupCoreError.responseTooLarge(limit: 4 * 1024 * 1024) }
        guard let text = String(data: data, encoding: .utf8) else { throw MeetupCoreError.invalidData("RSS must be UTF-8.") }
        guard !XMLSafety.hasDeclarations(text) else {
            throw MeetupCoreError.invalidData("RSS document types and entity declarations are unsupported.")
        }
        let parser = XMLParser(data: data)
        let delegate = RSSDelegate()
        parser.delegate = delegate
        parser.shouldResolveExternalEntities = false
        parser.shouldProcessNamespaces = false
        guard parser.parse(), parser.parserError == nil, delegate.failure == nil, delegate.isComplete else {
            throw MeetupCoreError.invalidData(delegate.failure ?? parser.parserError?.localizedDescription ?? "Malformed RSS XML.")
        }
        guard delegate.root == "rss" else { throw MeetupCoreError.invalidData("Expected an RSS document.") }
        return delegate.articles
    }
}

private final class RSSDelegate: NSObject, XMLParserDelegate {
    var articles: [NewsArticle] = []
    var failure: String?
    var root: String?
    var isComplete: Bool { stack.isEmpty && root != nil }
    private var stack: [String] = []
    private var fields: [String: String] = [:]
    private var itemDepth: Int?
    private var field: String?
    private var value = ""
    private var image: URL?
    private var contentImage: URL?
    private var ids = Set<String>()
    private let supportedFields: Set<String> = ["title", "link", "guid", "description", "content:encoded", "pubdate", "dc:creator", "author"]

    func parser(_ parser: XMLParser, parseErrorOccurred parseError: any Error) {
        failure = parseError.localizedDescription
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        let name = elementName.lowercased()
        stack.append(name)
        if root == nil { root = name }
        guard stack.count <= 64 else { failure = "RSS nesting limit exceeded."; parser.abortParsing(); return }
        if name == "item", itemDepth == nil {
            fields = [:]; image = nil; contentImage = nil; itemDepth = stack.count
        } else if let itemDepth {
            if stack.count == itemDepth + 1, supportedFields.contains(name) {
                field = name; value = ""
            } else if field != nil { value += " " }
            if name == "enclosure", image == nil,
               attributeDict["type"]?.lowercased().hasPrefix("image/") ?? true {
                image = attributeDict["url"].flatMap(SafeURL.web)
            }
            if name == "media:content" || name == "media:thumbnail", image == nil {
                image = attributeDict["url"].flatMap(SafeURL.web)
            }
            if name == "img", contentImage == nil { contentImage = attributeDict["src"].flatMap(SafeURL.web) }
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) { if field != nil { value += string } }
    func parser(_ parser: XMLParser, foundCDATA CDATABlock: Data) {
        if field != nil { value += String(decoding: CDATABlock, as: UTF8.self) }
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        let name = elementName.lowercased()
        if let itemDepth, stack.count == itemDepth + 1, field == name {
            if fields[name] == nil { fields[name] = value.trimmingCharacters(in: .whitespacesAndNewlines) }
            field = nil; value = ""
        }
        if name == "item", stack.count == itemDepth {
            appendArticle()
            self.itemDepth = nil
            if articles.count > 1000 { failure = "RSS item limit exceeded."; parser.abortParsing() }
        }
        _ = stack.popLast()
    }

    private func appendArticle() {
        let title = HTMLText.plain(fields["title"] ?? "")
        let summary = HTMLText.plain(fields["description"] ?? fields["content:encoded"] ?? "")
        guard !title.isEmpty || !summary.isEmpty else { return }
        let guid = fields["guid"] ?? ""
        let address = guid.split(separator: ":", maxSplits: 2, omittingEmptySubsequences: false)
        let isNostrArticle = address.count == 3 && address[0] == "30023" && address[1].utf8.count == 64
            && address[1].utf8.allSatisfy { (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0) }
            && !address[2].isEmpty
        // NIP-23 GUIDs identify relay articles, not web URLs. Keep these feed
        // items readable and use the overview for browser/share fallback.
        guard let url = fields["link"].flatMap(SafeURL.web) ?? fields["guid"].flatMap(SafeURL.web)
            ?? (isNostrArticle ? NewsClient.websiteURL : nil) else { return }
        let id = fields["guid"].flatMap { $0.isEmpty ? nil : $0 } ?? url.absoluteString
        guard ids.insert(id).inserted else { return }
        let creator = HTMLText.plain(fields["dc:creator"] ?? "")
        let author = creator.isEmpty ? HTMLText.plain(fields["author"] ?? "") : creator
        let html = fields["content:encoded"] ?? fields["description"] ?? ""
        articles.append(NewsArticle(id: id, title: title.isEmpty ? "(ohne Titel)" : title,
                                    summary: summary, url: url, published: DateParsing.rss(fields["pubdate"]),
                                    author: author.isEmpty ? nil : author,
                                    imageURL: image ?? contentImage ?? HTMLText.firstImage(html)))
    }
}

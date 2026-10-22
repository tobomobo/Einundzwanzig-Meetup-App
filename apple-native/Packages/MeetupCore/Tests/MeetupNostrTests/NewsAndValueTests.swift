import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@testable import MeetupNostr

// Public expired BOLT-11 example, never a payable live invoice.
// https://github.com/lightning/bolts/blob/master/11-payment-encoding.md#examples
private let coffeeInvoice = "lnbc2500u1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpu9qrsgquk0rl77nj30yxdy8j9vdx85fkpmdla2087ne0xh8nhedh8w27kyke0lp53ut353s06fv3qfegext0eh0ymjpf39tuven09sam30g4vgpfna3rh"
private struct InvoiceFixtureHTTP: ProductHTTPTransport {
    let handler: @Sendable (URLRequest) async throws -> JSONValue
    func send(_ request: URLRequest) async throws -> ProductHTTPResponse {
        ProductHTTPResponse(status: 200, data: try JSONEncoder().encode(await handler(request)))
    }
}
private actor NewsFixtureRelay: NostrRelayTransport {
    let events: [NostrEvent]
    var published: [NostrEvent] = []
    init(_ events: [NostrEvent]) { self.events = events }
    func query(_ filter: NostrFilter, relays: [URL], timeout: TimeInterval) -> [NostrEvent] { events }
    func publish(_ event: NostrEvent, relays: [URL], signer: (any NostrSigner)?) { published.append(event) }
}
@Suite struct NewsAndValueTests {
    @Test func officialBolt11AmountChecksumAndCaseAreCheckedBeforeWalletHandoff() throws {
        #expect(try ValueForValueService.invoiceMillisats(coffeeInvoice) == 250_000_000)
        #expect(try ValueForValueService.invoiceMillisats(coffeeInvoice.uppercased()) == 250_000_000)
        #expect(throws: ValueForValueError.invalidInvoice) { try ValueForValueService.invoiceMillisats("L" + coffeeInvoice.dropFirst()) }
        #expect(throws: ValueForValueError.invalidInvoice) { try ValueForValueService.invoiceMillisats(String(coffeeInvoice.dropLast()) + "q") }
    }
    @Test func zapUsesSignedCallbackRequestWithoutPublishingAndRejectsAmountMismatch() async throws {
        let signer = try LocalIdentitySigner(privateKey: Data(repeating: 1, count: 32)), key = try await signer.publicKey()
        let article = "30023:" + key + ":fixture:article"
        let http = InvoiceFixtureHTTP { request in
            if request.url?.path == "/.well-known/lnurlp/alice" {
                return .object(["tag": .string("payRequest"), "callback": .string("https://fixture.invalid/pay"), "metadata": .string("[[\"text/plain\",\"Fixture\"]]"),
                    "minSendable": .number(1000), "maxSendable": .number(1_000_000_000), "allowsNostr": .bool(true), "nostrPubkey": .string(key)])
            }
            let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems!
            #expect(query.first { $0.name == "amount" }?.value == "250000000")
            let encoded = try #require(query.first { $0.name == "nostr" }?.value)
            let event = try NostrEvent.decodeJSON(Data(encoded.utf8))
            #expect(await NostrCrypto().verify(event)); #expect(event.kind == 9734)
            #expect(event.tags.contains(["a", article]) && event.tags.contains(["k", "30023"]))
            #expect(event.tags.contains(["lnurl", query.first { $0.name == "lnurl" }!.value!]))
            return .object(["pr": .string(coffeeInvoice)])
        }
        let invoice = try await ValueForValueService(transport: http).invoice(address: "alice@fixture.invalid", sats: 250_000, article: article, signer: signer)
        #expect(invoice.zapRequested); #expect(invoice.url?.scheme == "lightning")
        let wrong = InvoiceFixtureHTTP { _ in .object(["tag": .string("payRequest"), "callback": .string("https://fixture.invalid/pay"), "metadata": .string("[[\"text/plain\",\"Fixture\"]]"), "minSendable": .number(1000), "maxSendable": .number(1000), "pr": .string(coffeeInvoice)]) }
        await #expect(throws: ValueForValueError.invalidInvoice) { try await ValueForValueService(transport: wrong).invoice(address: "alice@fixture.invalid", sats: 1) }
    }
    @Test func newsChecksFilterLatestSignatureAndDeduplicatesPositiveReactions() async throws {
        let signer = try LocalIdentitySigner(privateKey: Data(repeating: 1, count: 32)), key = try await signer.publicKey()
        let old = try await signer.sign(kind: 30023, tags: [["d", "article:1"]], content: "old", createdAt: 100)
        let latest = try await signer.sign(kind: 30023, tags: [["d", "article:1"]], content: "new", createdAt: 200)
        let other = try await signer.sign(kind: 30023, tags: [["d", "foreign"]], content: "wrong", createdAt: 300)
        let address = "30023:" + key + ":article:1"
        let positive = try await signer.sign(kind: 7, tags: [["a", address]], content: "+", createdAt: 200)
        let duplicate = try await signer.sign(kind: 7, tags: [["a", address]], content: "❤️", createdAt: 300)
        let negative = try await signer.sign(kind: 7, tags: [["a", address]], content: "-", createdAt: 400)
        let relay = NewsFixtureRelay([other, old, latest, positive, duplicate, negative]), service = NewsNostrService(relay: relay)
        #expect(try await service.fullArticle(address, relays: []) == latest)
        #expect(try await service.likes(address, relays: []) == [key])
        try await service.like(address, signer: signer, relays: [])
        #expect(await relay.published.isEmpty)
    }
}

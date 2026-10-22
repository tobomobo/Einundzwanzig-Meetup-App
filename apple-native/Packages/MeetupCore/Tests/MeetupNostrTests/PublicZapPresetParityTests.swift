import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@testable import MeetupNostr

// Synthetic BOLT-11 checksum/amount fixture with a zero signature. Never payable;
// wallet-side signature/expiry validation remains outside this invoice service.
private func publicPresetInvoice(_ sats: Int) -> String {
    let prefix = "lnbc" + String(sats * 10) + "n", hrp = Array(prefix.utf8)
    var words = Array(repeating: UInt32(0), count: 110), checksum: UInt32 = 1
    let generators: [UInt32] = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]
    for value in hrp.map({ UInt32($0 >> 5) }) + [0] + hrp.map({ UInt32($0 & 31) }) + words + Array(repeating: 0, count: 6) {
        let top = checksum >> 25; checksum = (checksum & 0x1ffffff) << 5 ^ value
        for i in 0..<5 where top >> i & 1 != 0 { checksum ^= generators[i] }
    }
    checksum ^= 1
    words += (0..<6).map { checksum >> (5 * (5 - $0)) & 31 }
    let alphabet = Array("qpzry9x8gf2tvdw0s3jn54khce6mua7l")
    return prefix + "1" + String(words.map { alphabet[Int($0)] })
}
private actor PublicZapPresetHTTP: ProductHTTPTransport {
    let provider: String
    let invoiceSats: Int
    private(set) var urls: [URL] = []
    private(set) var request: NostrEvent?
    init(provider: String, invoiceSats: Int) { self.provider = provider; self.invoiceSats = invoiceSats }
    func send(_ request: URLRequest) async throws -> ProductHTTPResponse {
        let url = try #require(request.url)
        urls.append(url)
        let value: JSONValue
        if url.path == "/.well-known/lnurlp/author" {
            value = .object(["tag": .string("payRequest"), "callback": .string("https://preset.fixture.invalid/pay"),
                "metadata": .string("[[\"text/plain\",\"Article author\"]]"), "minSendable": .number(1000), "maxSendable": .number(1_000_000_000),
                "allowsNostr": .bool(true), "nostrPubkey": .string(provider)])
        } else {
            let query = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems)
            let encoded = try #require(query.first { $0.name == "nostr" }?.value)
            self.request = try NostrEvent.decodeJSON(Data(encoded.utf8))
            value = .object(["pr": .string(publicPresetInvoice(invoiceSats))])
        }
        return ProductHTTPResponse(status: 200, data: try JSONEncoder().encode(value))
    }
}
private actor PublicZapIdentityFence {
    let allowed: Int
    var checks = 0
    init(_ allowed: Int) { self.allowed = allowed }
    func current(_ key: String) -> Bool { checks += 1; return checks <= allowed }
}

@Suite struct PublicZapPresetParityTests {
    @Test(arguments: ValueForValueService.articleZapAmounts)
    func eachPresetBindsSignedZapAndReturnedInvoiceToSameAmount(_ sats: Int) async throws {
        let signer = try LocalIdentitySigner(privateKey: Data(repeating: 0, count: 31) + Data([1])), key = try await signer.publicKey()
        let address = "30023:" + key + ":preset:article"
        let transport = PublicZapPresetHTTP(provider: key, invoiceSats: sats)
        let invoice = try await ValueForValueService(transport: transport).invoice(address: "author@preset.fixture.invalid", sats: sats, comment: "Article title", article: address, signer: signer)
        #expect(invoice.sats == sats && invoice.address == "author@preset.fixture.invalid" && invoice.zapRequested)
        #expect(try ValueForValueService.invoiceMillisats(invoice.bolt11) == sats * 1000)
        let event = try #require(await transport.request)
        #expect(await NostrCrypto().verify(event))
        #expect(event.kind == 9734 && event.content == "Article title")
        #expect(event.tags.contains(["a", address]) && event.tags.contains(["p", key]) && event.tags.contains(["amount", String(sats * 1000)]))
        let callback = try #require(await transport.urls.last)
        #expect(URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "amount" }?.value == String(sats * 1000))
    }
    @Test func identityOrDraftInvalidationCannotSendOrReturnAStaleZapInvoice() async throws {
        let signer = try LocalIdentitySigner(privateKey: Data(repeating: 0, count: 31) + Data([1])), key = try await signer.publicKey()
        for allowed in [0, 1, 2] {
            let transport = PublicZapPresetHTTP(provider: key, invoiceSats: 21), fence = PublicZapIdentityFence(allowed)
            await #expect(throws: CalendarChatError.identityMismatch) {
                try await ValueForValueService(transport: transport).invoice(address: "author@preset.fixture.invalid", sats: 21, article: "30023:" + key + ":fence", signer: signer, isCurrentIdentity: { await fence.current($0) })
            }
            #expect(await transport.urls.count == (allowed == 2 ? 2 : 1))
        }
    }
    @Test func presetInvoiceStillRejectsMismatchingWalletAmountAndProviderKey() async throws {
        let signer = try LocalIdentitySigner(privateKey: Data(repeating: 0, count: 31) + Data([1])), key = try await signer.publicKey()
        let article = "30023:" + key + ":binding"
        await #expect(throws: ValueForValueError.invalidInvoice) {
            try await ValueForValueService(transport: PublicZapPresetHTTP(provider: key, invoiceSats: 21)).invoice(address: "author@preset.fixture.invalid", sats: 210, article: article, signer: signer)
        }
        let malformedProvider = PublicZapPresetHTTP(provider: String(repeating: "f", count: 64), invoiceSats: 21)
        await #expect(throws: ValueForValueError.invalidMetadata) {
            try await ValueForValueService(transport: malformedProvider).invoice(address: "author@preset.fixture.invalid", sats: 21, article: article, signer: signer)
        }
        #expect(await malformedProvider.urls.count == 1)
    }
}

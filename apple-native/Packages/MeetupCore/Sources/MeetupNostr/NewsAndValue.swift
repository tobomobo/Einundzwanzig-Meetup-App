import Foundation
import P256K
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public struct NostrArticleAddress: Sendable {
    public let publicKey: String
    public let identifier: String
    public let value: String
    public init(_ input: String) throws {
        let parts = input.split(separator: ":", maxSplits: 2, omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0] == "30023", !parts[2].isEmpty else { throw CalendarChatError.invalidEvent }
        _ = try NostrHex.decode(String(parts[1]), byteCount: 32, field: "article author")
        publicKey = String(parts[1]); identifier = String(parts[2]); value = input
    }
}
public actor NewsNostrService {
    private let relay: any NostrRelayTransport
    private let crypto = NostrCrypto()
    private let localLikesKey: String?
    private var localLikes: [String]
    private var pendingLikes: Set<String> = []
    public init(relay: any NostrRelayTransport, localLikesKey: String? = "news-liked-reactions-v1") {
        self.relay = relay
        self.localLikesKey = localLikesKey
        localLikes = localLikesKey.map { Array((UserDefaults.standard.stringArray(forKey: $0) ?? []).suffix(500)) } ?? []
    }
    public func hasLikedLocally(_ address: String, publicKey: String) -> Bool {
        localLikes.contains(publicKey + "|" + address)
    }
    private func rememberLike(_ address: String, publicKey: String) {
        let entry = publicKey + "|" + address
        if !localLikes.contains(entry) { localLikes.append(entry) }
        localLikes = Array(localLikes.suffix(500))
        if let localLikesKey { UserDefaults.standard.set(localLikes, forKey: localLikesKey) }
    }
    public func fullArticle(_ address: String, relays: [URL]) async throws -> NostrEvent? {
        let address = try NostrArticleAddress(address)
        let filter = NostrFilter(authors: [address.publicKey], kinds: [30023], limit: 10, tags: ["d": [address.identifier]])
        let events = try await relay.query(filter, relays: relays, timeout: 8)
        var verified: [NostrEvent] = []
        for event in events where filter.matches(event) && event.createdAt <= Int(Date().timeIntervalSince1970) + 600 {
            if await crypto.verify(event) { verified.append(event) }
        }
        return verified.sorted { $0.createdAt == $1.createdAt ? $0.id < $1.id : $0.createdAt > $1.createdAt }.first
    }
    public func likes(_ address: String, relays: [URL]) async throws -> Set<String> {
        _ = try NostrArticleAddress(address)
        let filter = NostrFilter(kinds: [7], limit: 1000, tags: ["a": [address]])
        let events = try await relay.query(filter, relays: relays, timeout: 8)
        var keys: Set<String> = []
        for entry in localLikes {
            let parts = entry.split(separator: "|", maxSplits: 1, omittingEmptySubsequences: false)
            if parts.count == 2, parts[1] == address { keys.insert(String(parts[0])) }
        }
        for event in events where filter.matches(event) && event.createdAt <= Int(Date().timeIntervalSince1970) + 600 && event.content.trimmingCharacters(in: .whitespacesAndNewlines) != "-" {
            if await crypto.verify(event) { keys.insert(event.pubkey) }
        }
        return keys
    }
    public func like(_ address: String, signer: any NostrSigner, relays: [URL],
                     isCurrentIdentity: @Sendable (String) async -> Bool = { _ in true }) async throws {
        let article = try NostrArticleAddress(address)
        let key = try await signer.publicKey()
        guard await isCurrentIdentity(key) else { throw CalendarChatError.identityMismatch }
        if hasLikedLocally(address, publicKey: key) { return }
        let entry = key + "|" + address
        guard pendingLikes.insert(entry).inserted else { return }
        defer { pendingLikes.remove(entry) }
        if try await likes(address, relays: relays).contains(key) {
            rememberLike(address, publicKey: key)
            return
        }
        try Task.checkCancellation()
        guard await isCurrentIdentity(key) else { throw CalendarChatError.identityMismatch }
        let tags = [["a", address], ["p", article.publicKey], ["k", "30023"]]
        let now = Int(Date().timeIntervalSince1970)
        let event = try await signer.sign(kind: 7, tags: tags, content: "+", createdAt: now)
        try await BadgeSecurity.requireSigned(event, signer: signer, kind: 7, tags: tags, content: "+", createdAt: now)
        try Task.checkCancellation()
        guard await isCurrentIdentity(key) else { throw CalendarChatError.identityMismatch }
        try await relay.publish(event, relays: relays, signer: signer)
        rememberLike(address, publicKey: key)
    }
    public func lightningAddress(author: String, relays: [URL]) async throws -> String {
        let filter = NostrFilter(authors: [author], kinds: [0], limit: 10)
        let events = try await relay.query(filter, relays: relays, timeout: 8)
        for event in events.sorted(by: { $0.createdAt == $1.createdAt ? $0.id < $1.id : $0.createdAt > $1.createdAt }) where filter.matches(event) && event.createdAt <= Int(Date().timeIntervalSince1970) + 600 {
            guard await crypto.verify(event), let data = event.content.data(using: .utf8), let object = try? JSONDecoder().decode(JSONValue.self, from: data),
                  let address = object["lud16"].string, address.contains("@") else { continue }
            return address
        }
        throw ValueForValueError.invalidAddress
    }
}
public enum ValueForValueError: Error, Sendable, Equatable, LocalizedError {
    case invalidAddress, invalidAmount, invalidMetadata, invalidInvoice
    public var errorDescription: String? {
        switch self {
        case .invalidAddress: "Enter a valid Lightning address."
        case .invalidAmount: "The amount is outside the receiver's supported range."
        case .invalidMetadata: "The receiver did not return valid LNURL-pay metadata."
        case .invalidInvoice: "The receiver did not return a matching Lightning invoice."
        }
    }
}
public struct ExternalWalletInvoice: Sendable, Equatable {
    public let bolt11: String
    public let sats: Int
    public let address: String
    public let zapRequested: Bool
    public var url: URL? { URL(string: "lightning:" + bolt11) }
}
public actor ValueForValueService {
    public static let projectAddress = "wolpertinger1@timecatcher.lnbits.de"
    private let transport: any ProductHTTPTransport
    public init(transport: any ProductHTTPTransport = ProductURLSessionTransport()) { self.transport = transport }
    public func invoice(address: String = projectAddress, sats: Int, comment: String = "", article: String? = nil,
                        signer: (any NostrSigner)? = nil, relays: [URL] = []) async throws -> ExternalWalletInvoice {
        let parts = address.split(separator: "@", omittingEmptySubsequences: false)
        guard parts.count == 2, !parts[0].isEmpty, !parts[1].isEmpty,
              !parts[1].contains(where: { "/:?#@".contains($0) }), parts[0].utf8.count <= 128 else { throw ValueForValueError.invalidAddress }
        guard sats > 0, sats <= 21_000_000 * 100_000_000 / 1000 else { throw ValueForValueError.invalidAmount }
        var components = URLComponents(); components.scheme = "https"; components.host = String(parts[1])
        components.path = "/.well-known/lnurlp/" + String(parts[0])
        guard let url = components.url else { throw ValueForValueError.invalidAddress }
        let metadata = try await get(url)
        guard metadata["tag"].string == "payRequest", let callbackText = metadata["callback"].string,
              var callback = URLComponents(string: callbackText), callback.scheme == "https", callback.host != nil,
              callback.user == nil, callback.password == nil, callback.fragment == nil,
              let minimum = metadata["minSendable"].integer, let maximum = metadata["maxSendable"].integer,
              minimum >= 0, maximum >= minimum,
              let description = metadata["metadata"].string,
              let metadataItems = try? JSONDecoder().decode([[String]].self, from: Data(description.utf8)),
              metadataItems.contains(where: { $0.count == 2 && $0[0] == "text/plain" }) else { throw ValueForValueError.invalidMetadata }
        let msats = sats * 1000
        guard msats >= minimum, msats <= maximum else { throw ValueForValueError.invalidAmount }
        var query = (callback.queryItems ?? []).filter { !["amount", "comment", "nostr"].contains($0.name) }
        query.append(URLQueryItem(name: "amount", value: String(msats)))
        let zap = metadata["allowsNostr"].boolean == true && article != nil
        if zap {
            guard let signer, let article else { throw IdentityError.locked }
            guard let provider = metadata["nostrPubkey"].string,
                  let providerBytes = try? NostrHex.decode(provider, byteCount: 32, field: "zap provider"),
                  (try? P256K.KeyAgreement.PublicKey(dataRepresentation: Data([2]) + providerBytes)) != nil else { throw ValueForValueError.invalidMetadata }
            let target = try NostrArticleAddress(article)
            let lnurl = NIP19.encode(prefix: "lnurl", bytes: Data(url.absoluteString.utf8))
            let tags = [["relays"] + relays.map(\.absoluteString), ["amount", String(msats)], ["lnurl", lnurl], ["p", target.publicKey], ["a", article], ["k", "30023"]]
            let now = Int(Date().timeIntervalSince1970)
            let event = try await signer.sign(kind: 9734, tags: tags, content: comment, createdAt: now)
            try await BadgeSecurity.requireSigned(event, signer: signer, kind: 9734, tags: tags, content: comment, createdAt: now)
            query.append(URLQueryItem(name: "nostr", value: String(decoding: try JSONEncoder().encode(event), as: UTF8.self)))
            query.removeAll { $0.name == "lnurl" }; query.append(URLQueryItem(name: "lnurl", value: lnurl))
        } else if let allowed = metadata["commentAllowed"].integer, allowed > 0, !comment.isEmpty {
            query.append(URLQueryItem(name: "comment", value: String(comment.prefix(Swift.min(allowed, 1000)))))
        }
        callback.queryItems = query
        guard let callbackURL = callback.url else { throw ValueForValueError.invalidMetadata }
        let response = try await get(callbackURL)
        guard response["status"].string?.uppercased() != "ERROR", let invoice = response["pr"].string,
              try Self.invoiceMillisats(invoice) == msats else { throw ValueForValueError.invalidInvoice }
        return ExternalWalletInvoice(bolt11: invoice, sats: sats, address: address, zapRequested: zap)
    }
    private func get(_ url: URL) async throws -> JSONValue {
        guard url.scheme == "https", url.host != nil, url.user == nil, url.password == nil else { throw ValueForValueError.invalidAddress }
        var request = URLRequest(url: url, timeoutInterval: 15); request.setValue("application/json", forHTTPHeaderField: "Accept")
        let response = try await transport.send(request)
        guard response.status == 200, response.data.count <= 65536 else { throw ValueForValueError.invalidMetadata }
        return try JSONDecoder().decode(JSONValue.self, from: response.data)
    }
    /// Validates Bech32 and the BOLT-11 amount. Payment authorization and invoice
    /// signature/expiry verification remain with the explicitly opened wallet.
    static func invoiceMillisats(_ invoice: String) throws -> Int {
        guard invoice.utf8.count <= 12000,
              !(invoice.contains(where: { $0.isLowercase }) && invoice.contains(where: { $0.isUppercase })) else { throw ValueForValueError.invalidInvoice }
        let invoice = invoice.lowercased()
        guard let separator = invoice.lastIndex(of: "1") else { throw ValueForValueError.invalidInvoice }
        let hrp = String(invoice[..<separator]); let payload = invoice[invoice.index(after: separator)...]
        guard hrp.hasPrefix("lnbc"), payload.count >= 110 else { throw ValueForValueError.invalidInvoice }
        let alphabet = Array("qpzry9x8gf2tvdw0s3jn54khce6mua7l")
        var values: [UInt32] = []
        for c in payload { guard let index = alphabet.firstIndex(of: c) else { throw ValueForValueError.invalidInvoice }; values.append(UInt32(index)) }
        let hrpBytes = Array(hrp.utf8)
        var checksum: UInt32 = 1
        for value in hrpBytes.map({ UInt32($0 >> 5) }) + [0] + hrpBytes.map({ UInt32($0 & 31) }) + values {
            let top = checksum >> 25; checksum = (checksum & 0x1ffffff) << 5 ^ value
            let generator: [UInt32] = [0x3b6a57b2,0x26508e6d,0x1ea119fa,0x3d4233dd,0x2a1462b3]
            for i in 0..<5 where (top >> i) & 1 != 0 { checksum ^= generator[i] }
        }
        guard checksum == 1 else { throw ValueForValueError.invalidInvoice }
        var amount = String(hrp.dropFirst(4)); var multiplier = 100_000_000_000
        if let suffix = amount.last, !suffix.isNumber {
            amount.removeLast()
            switch suffix { case "m": multiplier = 100_000_000; case "u": multiplier = 100_000; case "n": multiplier = 100; case "p": multiplier = 1; default: throw ValueForValueError.invalidInvoice }
            if suffix == "p" { guard let n = Int(amount), n > 0, n % 10 == 0 else { throw ValueForValueError.invalidInvoice }; return n / 10 }
        }
        guard let number = Int(amount), number > 0 else { throw ValueForValueError.invalidInvoice }
        let (result, overflow) = number.multipliedReportingOverflow(by: multiplier)
        guard !overflow else { throw ValueForValueError.invalidInvoice }; return result
    }
}

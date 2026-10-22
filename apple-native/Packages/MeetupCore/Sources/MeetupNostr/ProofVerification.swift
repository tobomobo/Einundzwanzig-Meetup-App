import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public protocol ProofHTTPClient: Sendable { func get(_ url: URL) async throws -> BadgeJSON }
public struct ProofURLSessionClient: ProofHTTPClient {
    private let transport: any ProductHTTPTransport
    public init(transport: any ProductHTTPTransport = ProductURLSessionTransport()) { self.transport = transport }
    public func get(_ url: URL) async throws -> BadgeJSON {
        guard url.scheme == "https", let host = url.host, !host.isEmpty else { throw BadgeProtocolError.malformed }
        var request = URLRequest(url: url); request.timeoutInterval = 6
        let response = try await transport.send(request)
        let data = response.data
        guard response.status == 200, data.count <= 65_536, let text = String(data: data, encoding: .utf8) else { throw BadgeProtocolError.malformed }
        return try BadgeWire.parse(text, limit: 65_536)
    }
}

public struct PlatformIdentityProof: Codable, Equatable, Sendable, Identifiable {
    public let platform: String
    public let username: String
    public let proofSig: String
    public let createdAt: Int
    public var id: String { platform }
    enum CodingKeys: String, CodingKey { case platform, username; case proofSig = "proof_sig"; case createdAt = "created_at" }
    public init(platform: String, username: String, proofSig: String, createdAt: Int) {
        self.platform = platform; self.username = username; self.proofSig = proofSig; self.createdAt = createdAt
    }
    public init(json: BadgeJSON, platform: String = "") {
        let m = json.object; self.platform = m["platform"]?.string ?? platform; username = m["username"]?.string ?? ""
        proofSig = m["proof_sig"]?.string ?? ""; createdAt = m["created_at"]?.integer ?? 0
    }
    public var json: BadgeJSON { .object(["platform": .string(platform), "username": .string(username), "proof_sig": .string(proofSig), "created_at": .int(createdAt)]) }
    public var content: String { BadgeWire.canonical(["action": .string("platform_proof"), "protocol": .string("21rep"), "version": .int(2), "platform": .string(platform), "username": .string(username), "created_at": .int(createdAt)]) }
    public var tags: [[String]] { [["t", "platform_proof"], ["platform", platform], ["username", username]] }
    public func event(publicKey: String) throws -> NostrEvent {
        let u = try UnsignedNostrEvent(pubkey: publicKey, createdAt: createdAt, kind: 21003, tags: tags, content: content)
        return try NostrEvent(unsigned: u, id: NostrHex.encode(try u.eventID()), sig: proofSig)
    }
    public static func create(platform: String, username: String, signer: any NostrSigner, now: Int) async throws -> Self {
        let name = username.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, name.utf8.count <= 256, !name.contains("::"), !platform.isEmpty, platform.utf8.count <= 64, !platform.contains("::") else { throw BadgeProtocolError.malformed }
        let draft = Self(platform: platform, username: name, proofSig: "", createdAt: now)
        let event = try await signer.sign(kind: 21003, tags: draft.tags, content: draft.content, createdAt: now)
        try await BadgeSecurity.requireSigned(event, signer: signer, kind: 21003, tags: draft.tags, content: draft.content, createdAt: now)
        return Self(platform: platform, username: name, proofSig: event.sig, createdAt: now)
    }
    public func verifyString(publicKey: String) throws -> String {
        "21rep::\(try NIP19.encodePublicKey(publicKey))::\(platform)::\(username)::sig=\(proofSig)"
    }
    public static func verify(_ input: String, reputation: NostrEvent, expectedUsername: String? = nil, now: Int) async throws -> Self {
        let p = input.trimmingCharacters(in: .whitespacesAndNewlines).components(separatedBy: "::")
        guard p.count == 5, p[0] == "21rep", p[4].hasPrefix("sig="),
              let key = try? NIP19.decodePublicKey(p[1]), key == reputation.pubkey,
              expectedUsername == nil || p[3] == expectedUsername,
              reputation.kind == 30078, reputation.tags.contains(["d", "einundzwanzig-reputation"]),
              reputation.createdAt <= now + 300, await NostrCrypto().verify(reputation) else { throw BadgeProtocolError.invalidSignature }
        let map = try BadgeWire.parse(reputation.content).object
        guard let raw = map["platform_proofs"]?.object[p[2]] else { throw BadgeProtocolError.malformed }
        let proof = Self(json: raw, platform: p[2])
        guard proof.platform == p[2], proof.username == p[3], proof.proofSig == String(p[4].dropFirst(4)),
              proof.createdAt <= now + 300, let event = try? proof.event(publicKey: key), await NostrCrypto().verify(event) else { throw BadgeProtocolError.invalidSignature }
        return proof
    }
}

public struct HumanityReceipt: Codable, Equatable, Sendable {
    public let receipt: NostrEvent
    public let request: NostrEvent
    public var recipient: String { receipt.tags.first(where: { $0.count > 1 && $0[0] == "p" })?[1] ?? "" }
    public var sender: String { request.pubkey }
    public var timestamp: Int { receipt.createdAt }
    public init(receipt: NostrEvent, request: NostrEvent) { self.receipt = receipt; self.request = request }
    public var publicSummary: BadgeJSON { .object(["verified": .bool(true), "method": .string("lightning_zap"), "first_zap_at": .int(timestamp), "receipt_id": .string(receipt.id)]) }
}

public enum ProofVerifier {
    public static func verifyNIP05(_ identifier: String, publicKey: String, http: any ProofHTTPClient) async throws -> Bool {
        let parts = identifier.components(separatedBy: "@")
        guard parts.count == 2, !parts[0].isEmpty, validDomain(parts[1]) else { throw BadgeProtocolError.malformed }
        var components = URLComponents(); components.scheme = "https"; components.host = parts[1].lowercased()
        components.path = "/.well-known/nostr.json"; components.queryItems = [URLQueryItem(name: "name", value: parts[0])]
        guard let url = components.url else { throw BadgeProtocolError.malformed }
        let data = try await http.get(url)
        return data.object["names"]?.object[parts[0]]?.string.lowercased() == publicKey.lowercased()
    }
    public static func localReceipt(_ receipt: NostrEvent, now: Int) async throws -> HumanityReceipt {
        func tag(_ name: String) -> String { receipt.tags.first { $0.count > 1 && $0[0] == name }?[1] ?? "" }
        guard receipt.kind == 9735, receipt.createdAt <= now + 300, await NostrCrypto().verify(receipt),
              !tag("p").isEmpty, let request = try? NostrEvent.decodeJSON(Data(tag("description").utf8)),
              request.kind == 9734, request.createdAt <= receipt.createdAt + 300, await NostrCrypto().verify(request),
              request.tags.first(where: { $0.count > 1 && $0[0] == "p" })?[1] == tag("p"),
              try descriptionHash(tag("bolt11")) == BadgeWire.sha256(tag("description")) else { throw BadgeProtocolError.invalidSignature }
        return HumanityReceipt(receipt: receipt, request: request)
    }
    public static func humanity(_ receipt: NostrEvent, identity: String, recipientMetadata: NostrEvent,
                                http: any ProofHTTPClient, now: Int) async throws -> HumanityReceipt {
        let proof = try await localReceipt(receipt, now: now)
        guard proof.sender == identity || proof.recipient == identity,
              receipt.pubkey != proof.sender, receipt.pubkey != proof.recipient,
              recipientMetadata.kind == 0, recipientMetadata.pubkey == proof.recipient,
              recipientMetadata.createdAt <= now + 300, await NostrCrypto().verify(recipientMetadata) else { throw BadgeProtocolError.unauthorized }
        let metadata = try BadgeWire.parse(recipientMetadata.content).object
        let address = (metadata["lud16"]?.string ?? "").components(separatedBy: "@")
        guard address.count == 2, !address[0].isEmpty, validDomain(address[1]) else { throw BadgeProtocolError.malformed }
        var url = URLComponents(); url.scheme = "https"; url.host = address[1].lowercased(); url.path = "/.well-known/lnurlp/" + address[0]
        guard let endpoint = url.url else { throw BadgeProtocolError.malformed }
        let provider = try await http.get(endpoint).object
        guard provider["allowsNostr"]?.boolean == true, provider["nostrPubkey"]?.string.lowercased() == receipt.pubkey else { throw BadgeProtocolError.unauthorized }
        return proof
    }
    private static func validDomain(_ text: String) -> Bool {
        !text.isEmpty && text.utf8.count <= 253 && text.range(of: #"^[a-zA-Z0-9.-]+$"#, options: .regularExpression) != nil
    }
    /// Reads the signed BOLT11 description hash (tag h, word type 23).
    public static func descriptionHash(_ invoice: String) throws -> String {
        let text = invoice.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.count <= 4096, text == text.lowercased() || text == text.uppercased() else { throw BadgeProtocolError.malformed }
        let lower = text.lowercased(), alphabet = Array("qpzry9x8gf2tvdw0s3jn54khce6mua7l")
        guard let split = lower.lastIndex(of: "1"), lower[..<split].hasPrefix("ln") else { throw BadgeProtocolError.malformed }
        let hrp = Array(lower[..<split].utf8)
        let words = try lower[lower.index(after: split)...].map { c -> Int in
            guard let i = alphabet.firstIndex(of: c) else { throw BadgeProtocolError.malformed }; return i
        }
        let g: [UInt32] = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]
        var checksum: UInt32 = 1
        for v in hrp.map({ Int($0 >> 5) }) + [0] + hrp.map({ Int($0 & 31) }) + words {
            let top = checksum >> 25; checksum = (checksum & 0x1ffffff) << 5 ^ UInt32(v)
            for i in 0..<5 where top >> i & 1 != 0 { checksum ^= g[i] }
        }
        guard checksum == 1, words.count >= 7 + 104 + 6 else { throw BadgeProtocolError.malformed }
        var i = 7; let end = words.count - 104 - 6
        while i + 3 <= end {
            let type = words[i], count = words[i + 1] << 5 | words[i + 2]; i += 3
            guard i + count <= end else { throw BadgeProtocolError.malformed }
            if type == 23, count == 52 {
                var accumulator: UInt32 = 0, bits = 0, bytes: [UInt8] = []
                for word in words[i..<(i + count)] {
                    accumulator = accumulator << 5 | UInt32(word); bits += 5
                    while bits >= 8 { bits -= 8; bytes.append(UInt8((accumulator >> bits) & 255)) }
                }
                guard bytes.count == 32 else { throw BadgeProtocolError.malformed }
                return NostrHex.encode(Data(bytes))
            }
            i += count
        }
        throw BadgeProtocolError.malformed
    }
}

import Foundation

public enum NIP46Error: Error, Equatable, Sendable, LocalizedError {
    case invalidPointer, notConnected, disconnected, timeout, invalidReply, rejected(String)
    public var errorDescription: String? {
        switch self {
        case .invalidPointer: "Enter a valid bunker address with a secure wss relay."
        case .notConnected: "Connect the remote signer first."
        case .disconnected: "The remote signer session was closed."
        case .timeout: "The remote signer did not answer before the request expired."
        case .invalidReply: "The remote signer returned an invalid response."
        case .rejected(let reason): "Remote signer rejected the request: \(reason)"
        }
    }
}

public struct BunkerPointer: Codable, Equatable, Sendable {
    public let publicKey: String
    public let relays: [URL]
    public let secret: String?
    public init(_ input: String) throws {
        guard input.utf8.count <= 16_384, let parts = URLComponents(string: input.trimmingCharacters(in: .whitespacesAndNewlines)),
              parts.scheme?.lowercased() == "bunker", parts.user == nil, parts.password == nil, parts.port == nil,
              parts.fragment == nil, parts.path.isEmpty || parts.path == "/", let raw = parts.host else { throw NIP46Error.invalidPointer }
        publicKey = raw.lowercased().hasPrefix("npub1") ? try NIP19.decodePublicKey(raw) : raw.lowercased()
        _ = try NostrHex.decode(publicKey, byteCount: 32, field: "bunker key")
        let values = parts.queryItems ?? []
        var found: [URL] = []
        for item in values where item.name == "relay" {
            guard let text = item.value, let relay = URLComponents(string: text), relay.scheme == "wss",
                  relay.host?.isEmpty == false, relay.user == nil, relay.password == nil, relay.fragment == nil,
                  let url = relay.url else { throw NIP46Error.invalidPointer }
            if !found.contains(url) { found.append(url) }
        }
        guard (1...8).contains(found.count), values.filter({ $0.name == "secret" }).count <= 1 else { throw NIP46Error.invalidPointer }
        relays = found
        secret = values.first(where: { $0.name == "secret" })?.value
    }
    public var withoutSecret: BunkerPointer { BunkerPointer(publicKey: publicKey, relays: relays) }
    private init(publicKey: String, relays: [URL]) { self.publicKey = publicKey; self.relays = relays; secret = nil }
    public var uri: String {
        var parts = URLComponents(); parts.scheme = "bunker"; parts.host = publicKey
        parts.queryItems = relays.map { URLQueryItem(name: "relay", value: $0.absoluteString) }
        return parts.string ?? ""
    }
    public init(from decoder: any Decoder) throws {
        let c = try decoder.singleValueContainer(); try self.init(c.decode(String.self))
    }
    public func encode(to encoder: any Encoder) throws {
        var c = encoder.singleValueContainer(); try c.encode(uri) // single-use secret is never persisted
    }
}

/// NIP-46 RPC over the shared relay transport. Bunker transport keys and the
/// user's signing key remain distinct; both outer and returned events verify.
public actor NIP46Signer: NostrSigner {
    public typealias AuthURLHandler = @Sendable (URL) async -> Void
    private let transport: any NostrRelayTransport
    private let pointer: BunkerPointer
    private let client: LocalIdentitySigner
    private let clientPublicKey: String
    private let crypto = NostrCrypto()
    private let onAuthURL: AuthURLHandler?
    private var userPublicKey: String?
    private var epoch = UUID()
    private var closed = false
    private let requestTimeout: TimeInterval

    public init(pointer: BunkerPointer, clientPrivateKey: Data, transport: any NostrRelayTransport,
                requestTimeout: TimeInterval = 60, onAuthURL: AuthURLHandler? = nil) throws {
        self.pointer = pointer; self.transport = transport; self.onAuthURL = onAuthURL
        self.client = try LocalIdentitySigner(privateKey: clientPrivateKey)
        clientPublicKey = try IdentityKey.publicKey(clientPrivateKey)
        guard requestTimeout.isFinite, requestTimeout > 0, requestTimeout <= 120 else { throw NIP46Error.invalidPointer }
        self.requestTimeout = requestTimeout
    }
    public func connect() async throws -> String {
        let stamp = epoch
        let permissions = "get_public_key,nip44_encrypt,nip44_decrypt," +
            [21000, 21001, 21002, 21003, 22242, 30021, 30078, 30079, 31922, 31923].map { "sign_event:\($0)" }.joined(separator: ",")
        let result = try await rpc("connect", params: [pointer.publicKey, pointer.secret ?? "", permissions], timeout: min(75, requestTimeout))
        guard result.string == "ack" || (pointer.secret != nil && result.string == pointer.secret) else { throw NIP46Error.invalidReply }
        let key = try await rpc("get_public_key", params: [])
        guard let hex = key.string else { throw NIP46Error.invalidReply }
        _ = try NostrHex.decode(hex, byteCount: 32, field: "user key")
        guard stamp == epoch, !closed else { throw NIP46Error.disconnected }
        userPublicKey = hex
        return hex
    }
    public func publicKey() throws -> String {
        guard !closed else { throw NIP46Error.disconnected }
        guard let userPublicKey else { throw NIP46Error.notConnected }
        return userPublicKey
    }
    public func disconnect() async { closed = true; epoch = UUID(); userPublicKey = nil; await client.invalidate() }
    public func ping() async throws -> Bool { try await rpc("ping", params: [], timeout: min(15, requestTimeout)).string == "pong" }
    public func sign(kind: Int, tags: [[String]], content: String, createdAt: Int) async throws -> NostrEvent {
        let stamp = epoch
        let unsigned = try UnsignedNostrEvent(pubkey: publicKey(), createdAt: createdAt, kind: kind, tags: tags, content: content)
        let text = String(decoding: try JSONEncoder().encode(unsigned), as: UTF8.self)
        let reply = try await rpc("sign_event", params: [text])
        let data: Data
        if let string = reply.string { data = Data(string.utf8) } else { data = try JSONEncoder().encode(reply) }
        let event = try NostrEvent.decodeJSON(data)
        guard event.unsigned == unsigned, await crypto.verify(event) else { throw NIP46Error.invalidReply }
        guard stamp == epoch, !closed else { throw NIP46Error.disconnected }
        return event
    }
    public func encrypt(_ plaintext: String, to publicKey: String) async throws -> String {
        _ = try self.publicKey(); _ = try NostrHex.decode(publicKey, byteCount: 32, field: "recipient")
        guard (1...65_535).contains(plaintext.utf8.count),
              let result = try await rpc("nip44_encrypt", params: [publicKey, plaintext]).string,
              result.utf8.count <= 87_472 else { throw NIP46Error.invalidReply }
        return result
    }
    public func decrypt(_ ciphertext: String, from publicKey: String) async throws -> String {
        _ = try self.publicKey(); _ = try NostrHex.decode(publicKey, byteCount: 32, field: "sender")
        guard (132...87_472).contains(ciphertext.utf8.count),
              let result = try await rpc("nip44_decrypt", params: [publicKey, ciphertext]).string,
              result.utf8.count <= 65_535 else { throw NIP46Error.invalidReply }
        return result
    }
    private struct Request: Encodable { let id: String; let method: String; let params: [String] }
    private struct Reply: Decodable { let id: String; let result: BackupValue?; let error: String? }
    private func rpc(_ method: String, params: [String], timeout: TimeInterval? = nil) async throws -> BackupValue {
        try Task.checkCancellation()
        guard !closed else { throw NIP46Error.disconnected }
        let stamp = epoch, id = UUID().uuidString, sentAt = Int(Date().timeIntervalSince1970)
        let text = String(decoding: try JSONEncoder().encode(Request(id: id, method: method, params: params)), as: UTF8.self)
        let encrypted = try await client.encrypt(text, to: pointer.publicKey)
        let event = try await client.sign(kind: 24133, tags: [["p", pointer.publicKey]], content: encrypted, createdAt: sentAt)
        let filter = NostrFilter(authors: [pointer.publicKey], kinds: [24133], since: sentAt - 5, limit: 256, tags: ["p": [clientPublicKey]])
        let clock = ContinuousClock(), deadline = ContinuousClock.now.advanced(by: .milliseconds(Int((timeout ?? requestTimeout) * 1000)))
        var seen = Set<String>()
        // Subscribe before publishing; repeated bounded history reads also catch
        // replies arriving after a relay immediately emits EOSE on the first REQ.
        async let first = transport.queryAuthenticated(filter, relays: pointer.relays, timeout: min(2, timeout ?? requestTimeout), signer: client)
        try Task.checkCancellation()
        try await transport.publish(event, relays: pointer.relays, signer: client)
        var batch = (try? await first) ?? []
        while clock.now < deadline {
            try Task.checkCancellation()
            guard stamp == epoch, !closed else { throw NIP46Error.disconnected }
            for response in batch.prefix(256) where seen.insert(response.id).inserted {
                guard filter.matches(response), response.createdAt <= Int(Date().timeIntervalSince1970) + 60,
                      await crypto.verify(response),
                      let plain = try? await client.decrypt(response.content, from: pointer.publicKey),
                      plain.utf8.count <= 131_072,
                      let reply = try? JSONDecoder().decode(Reply.self, from: Data(plain.utf8)), reply.id == id else { continue }
                if reply.result?.string == "auth_url" {
                    guard let value = reply.error, let url = URL(string: value), url.scheme == "https", url.host != nil,
                          url.user == nil, url.password == nil else { throw NIP46Error.invalidReply }
                    await onAuthURL?(url); continue
                }
                if let error = reply.error, !error.isEmpty { throw NIP46Error.rejected(String(error.prefix(240))) }
                guard let result = reply.result, result != .null else { throw NIP46Error.invalidReply }
                guard stamp == epoch, !closed else { throw NIP46Error.disconnected }
                return result
            }
            if seen.count > 4096 { throw IdentityError.resourceLimit }
            let remaining = clock.now.duration(to: deadline)
            if remaining <= .zero { break }
            try await Task.sleep(for: min(.milliseconds(100), remaining))
            let seconds = min(2, Double(remaining.components.seconds) + Double(remaining.components.attoseconds) / 1e18)
            batch = (try? await transport.queryAuthenticated(filter, relays: pointer.relays, timeout: max(0.01, seconds), signer: client)) ?? []
        }
        throw NIP46Error.timeout
    }
}

import Foundation
import Testing
@testable import MeetupNostr

private actor IdentityBunkerFixture: NostrRelayTransport {
    enum Mode: Sendable { case normal, forgedOuterThenValid, wrongSignedContent, authURL, silence, wrongID }
    let server: LocalIdentitySigner
    let user: LocalIdentitySigner
    let serverPublicKey: String
    let userPublicKey: String
    let mode: Mode
    var events: [NostrEvent] = []
    var methods: [String] = []
    var authenticatedClientKeys: Set<String> = []
    var publishedClientKeys: Set<String> = []
    init(mode: Mode = .normal) throws {
        let serverSecret = try IdentityKey.parse(String(repeating: "02", count: 32))
        let userSecret = try IdentityKey.parse(String(repeating: "03", count: 32))
        server = try LocalIdentitySigner(privateKey: serverSecret); user = try LocalIdentitySigner(privateKey: userSecret)
        serverPublicKey = try IdentityKey.publicKey(serverSecret); userPublicKey = try IdentityKey.publicKey(userSecret)
        self.mode = mode
    }
    func query(_ filter: NostrFilter, relays: [URL], timeout: TimeInterval) -> [NostrEvent] { events }
    func queryAuthenticated(_ filter: NostrFilter, relays: [URL], timeout: TimeInterval, signer: any NostrSigner) async throws -> [NostrEvent] {
        authenticatedClientKeys.insert(try await signer.publicKey())
        return events
    }
    func publish(_ event: NostrEvent, relays: [URL], signer: (any NostrSigner)?) async throws {
        publishedClientKeys.insert(event.pubkey)
        let plain = try await server.decrypt(event.content, from: event.pubkey)
        let request = try JSONDecoder().decode([String: BackupValue].self, from: Data(plain.utf8))
        let method = try #require(request["method"]?.string), id = try #require(request["id"]?.string)
        let params = request["params"]?.array?.compactMap(\.string) ?? []
        methods.append(method)
        if mode == .silence { return }
        var result: BackupValue
        switch method {
        case "connect": result = .string("ack")
        case "get_public_key": result = .string(userPublicKey)
        case "ping": result = .string("pong")
        case "nip44_encrypt": result = .string(try await user.encrypt(params[1], to: params[0]))
        case "nip44_decrypt": result = .string(try await user.decrypt(params[1], from: params[0]))
        case "sign_event":
            let unsigned = try UnsignedNostrEvent.decodeJSON(Data(params[0].utf8))
            let signed = try await user.sign(kind: unsigned.kind, tags: unsigned.tags,
                content: mode == .wrongSignedContent ? "attacker content" : unsigned.content, createdAt: unsigned.createdAt)
            result = .string(String(decoding: try JSONEncoder().encode(signed), as: UTF8.self))
        default: throw NIP46Error.invalidReply
        }
        let replyID = mode == .wrongID ? "another-request" : id
        if mode == .authURL && method == "connect" {
            try await enqueue(["id": .string(id), "result": .string("auth_url"), "error": .string("https://signer.example/authorize")], to: event.pubkey)
        }
        let reply: [String: BackupValue] = ["id": .string(replyID), "result": result]
        if mode == .forgedOuterThenValid {
            let encrypted = try await server.encrypt(String(decoding: JSONEncoder().encode(reply), as: UTF8.self), to: event.pubkey)
            let forged = try NostrEvent(id: String(repeating: "0", count: 64), pubkey: serverPublicKey,
                createdAt: Int(Date().timeIntervalSince1970), kind: 24133, tags: [["p", event.pubkey]], content: encrypted,
                sig: String(repeating: "0", count: 128))
            events.append(forged)
        }
        try await enqueue(reply, to: event.pubkey)
    }
    private func enqueue(_ fields: [String: BackupValue], to key: String) async throws {
        let encrypted = try await server.encrypt(String(decoding: JSONEncoder().encode(fields), as: UTF8.self), to: key)
        events.append(try await server.sign(kind: 24133, tags: [["p", key]], content: encrypted, createdAt: Int(Date().timeIntervalSince1970)))
    }
}

@Suite struct IdentityNIP46Tests {
    private func session(_ fixture: IdentityBunkerFixture, timeout: TimeInterval = 2,
                         auth: NIP46Signer.AuthURLHandler? = nil) async throws -> NIP46Signer {
        let pointer = try BunkerPointer("bunker://\(fixture.serverPublicKey)?relay=wss%3A%2F%2Ffixture.invalid&secret=once")
        return try NIP46Signer(pointer: pointer, clientPrivateKey: IdentityKey.parse(String(repeating: "01", count: 32)),
                               transport: fixture, requestTimeout: timeout, onAuthURL: auth)
    }
    @Test func actualEncryptedRPCSeparatesTransportAndUserKeysAndVerifiesSignatures() async throws {
        let fixture = try IdentityBunkerFixture(mode: .forgedOuterThenValid), client = try await session(fixture)
        let key = try await client.connect()
        #expect(key == fixture.userPublicKey)
        #expect(key != fixture.serverPublicKey)
        let event = try await client.sign(kind: 30078, tags: [["d", "badges-i"]], content: "fixture", createdAt: 1)
        #expect(await NostrCrypto().verify(event))
        #expect(event.pubkey == fixture.userPublicKey)
        let cipher = try await client.encrypt("fixture wallet", to: key)
        #expect(try await client.decrypt(cipher, from: key) == "fixture wallet")
        #expect(await fixture.methods == ["connect", "get_public_key", "sign_event", "nip44_encrypt", "nip44_decrypt"])
        let authKeys = await fixture.authenticatedClientKeys, publishedKeys = await fixture.publishedClientKeys
        #expect(authKeys == publishedKeys)
        #expect(!authKeys.contains(fixture.userPublicKey))
        #expect(!authKeys.contains(fixture.serverPublicKey))
        await client.disconnect()
        await #expect(throws: NIP46Error.disconnected) { try await client.sign(kind: 1, tags: [], content: "no", createdAt: 1) }
    }
    @Test func signedButAlteredEventIsRejected() async throws {
        let fixture = try IdentityBunkerFixture(mode: .wrongSignedContent), client = try await session(fixture)
        _ = try await client.connect()
        await #expect(throws: NIP46Error.invalidReply) { try await client.sign(kind: 1, tags: [], content: "requested", createdAt: 1) }
    }
    @Test func silenceAndWrongRequestIDTimeOut() async throws {
        for mode in [IdentityBunkerFixture.Mode.silence, .wrongID] {
            let fixture = try IdentityBunkerFixture(mode: mode), client = try await session(fixture, timeout: 0.12)
            await #expect(throws: NIP46Error.timeout) { try await client.connect() }
        }
    }
    @Test func authenticatedAuthorizationURLIsSurfacedAndRequestContinues() async throws {
        actor URLs { var values: [URL] = []; func add(_ url: URL) { values.append(url) } }
        let urls = URLs(), fixture = try IdentityBunkerFixture(mode: .authURL)
        let client = try await session(fixture, auth: { await urls.add($0) })
        _ = try await client.connect()
        #expect(await urls.values.map(\.absoluteString) == ["https://signer.example/authorize"])
    }
    @Test func pointersRejectInsecureRelaysAndNeverPersistOneTimeSecret() throws {
        let hex = String(repeating: "1", count: 64)
        #expect(throws: NIP46Error.invalidPointer) { try BunkerPointer("bunker://\(hex)?relay=ws://relay.invalid") }
        #expect(throws: NIP46Error.invalidPointer) { try BunkerPointer("bunker://\(hex)?relay=wss://user:password@relay.invalid") }
        let pointer = try BunkerPointer("bunker://\(hex)?relay=wss://relay.invalid&secret=single-use")
        let encoded = try JSONEncoder().encode(pointer)
        #expect(!String(decoding: encoded, as: UTF8.self).contains("single-use"))
        #expect(try JSONDecoder().decode(BunkerPointer.self, from: encoded).secret == nil)
    }
}

import Foundation
import Testing
import MeetupNostr

private actor AvatarFixtureRelay: NostrRelayTransport {
    private let events: [NostrEvent]
    private let delayed: Bool
    private var started = false
    private var waiter: CheckedContinuation<Void, Never>?
    private var pending: CheckedContinuation<Void, Never>?
    init(_ events: [NostrEvent], delayed: Bool = false) { self.events = events; self.delayed = delayed }
    func query(_ filter: NostrFilter, relays: [URL], timeout: TimeInterval) async throws -> [NostrEvent] {
        started = true; waiter?.resume(); waiter = nil
        if delayed { await withCheckedContinuation { pending = $0 } }
        return events.filter { filter.authors?.contains($0.pubkey) ?? true }
    }
    func publish(_ event: NostrEvent, relays: [URL], signer: (any NostrSigner)?) async throws { throw CancellationError() }
    func waitStarted() async { if !started { await withCheckedContinuation { waiter = $0 } } }
    func release() { pending?.resume(); pending = nil }
}

extension IdentityAppStoreContinuityTests {
    @Test func profileLocalPictureRelaunchInvalidReplacementAndResetFence() async throws {
        let defaults = try fresh(), directory = UITestFixtureMode.directory
        let picture = try #require(Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jzZkAAAAASUVORK5CYII="))
        let store = ProfilePictureStore(directory: directory)
        try store.setLocalPicture(picture)
        #expect(ProfilePictureStore(directory: directory).localPicture == picture)
        #expect(throws: LocalDataError.tooLarge) { try store.setLocalPicture(Data()) }
        #expect(throws: LocalDataError.tooLarge) { try store.setLocalPicture(Data(repeating: 0, count: 5 * 1024 * 1024 + 1)) }
        #expect(ProfilePictureStore(directory: directory).localPicture == picture)
        let token = store.selectionToken
        let app = AppStore(legacyDefaults: defaults)
        await app.resetLocalData()
        #expect(app.persistenceIssue == nil)
        #expect(ProfilePictureStore(directory: directory).localPicture == nil)
        #expect(throws: CancellationError.self) { try store.setLocalPicture(picture, expectedReset: token) }
        #expect(!FileManager.default.fileExists(atPath: directory.appendingPathComponent("local-profile-picture.jpg").path))
    }
    @Test func profileSignedAvatarCacheRelaunchAndOwnerIsolation() async throws {
        _ = try fresh()
        let directory = UITestFixtureMode.directory
        let signer = try LocalIdentitySigner(privateKey: Data(repeating: 0, count: 31) + Data([1]))
        let key = try await signer.publicKey(), now = Int(Date().timeIntervalSince1970)
        let profile = try await signer.sign(kind: 0, tags: [], content: "{\"picture\":\"https://images.example/person.jpg\"}", createdAt: now - 1)
        let store = ProfilePictureStore(directory: directory, relay: AvatarFixtureRelay([profile]))
        await store.load(publicKey: key, relays: [])
        #expect(store.pictureURL?.absoluteString == "https://images.example/person.jpg")
        let offline = ProfilePictureStore(directory: directory, relay: AvatarFixtureRelay([]))
        await offline.load(publicKey: key, relays: [])
        #expect(offline.pictureURL == store.pictureURL)
        let other = try LocalIdentitySigner(privateKey: Data(repeating: 0, count: 31) + Data([2]))
        await offline.load(publicKey: try await other.publicKey(), relays: [])
        #expect(offline.pictureURL == nil)
        let cache = try JSONDecoder().decode([String: NostrPublicProfile].self, from: Data(contentsOf: directory.appendingPathComponent("public-profile-metadata.json")))
        #expect(cache[key]?.profile?.id == profile.id)
    }
    @Test func profileActualResetRejectsDelayedPublicLookup() async throws {
        let defaults = try fresh(), directory = UITestFixtureMode.directory
        let signer = try LocalIdentitySigner(privateKey: Data(repeating: 0, count: 31) + Data([1]))
        let key = try await signer.publicKey()
        let event = try await signer.sign(kind: 0, tags: [], content: "{\"picture\":\"https://images.example/late.jpg\"}", createdAt: Int(Date().timeIntervalSince1970) - 1)
        let relay = AvatarFixtureRelay([event], delayed: true)
        let store = ProfilePictureStore(directory: directory, relay: relay)
        let load = Task { await store.load(publicKey: key, relays: []) }
        await relay.waitStarted()
        let app = AppStore(legacyDefaults: defaults)
        await app.resetLocalData()
        await relay.release(); await load.value
        #expect(app.persistenceIssue == nil)
        #expect(store.pictureURL == nil)
        #expect(!FileManager.default.fileExists(atPath: directory.appendingPathComponent("public-profile-metadata.json").path))
    }
}

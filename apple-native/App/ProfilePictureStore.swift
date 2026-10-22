import Foundation
import Observation
import MeetupNostr

/// Public avatars stay separate from the signing vault and the exported identity.
@Observable @MainActor final class ProfilePictureStore {
    private(set) var pictureURL: URL?
    private(set) var localPicture: Data?
    private(set) var loading = false
    private let directory: URL
    private let lookup: NostrPublicProfileLookup
    private var owner = ""
    private var generation = 0
    private static var resetGeneration = 0
    private static let maximumImageBytes = 5 * 1024 * 1024

    init(directory: URL = UITestFixtureMode.directory, relay: (any NostrRelayTransport)? = nil) {
        self.directory = directory
        // Metadata discovery is public and must not ask an unlocked bunker to
        // authenticate merely to display an avatar.
        lookup = NostrPublicProfileLookup(relay: relay ?? UITestFixtureMode.relay())
        localPicture = try? Self.readImage(directory.appendingPathComponent("local-profile-picture.jpg"))
    }
    func load(publicKey: String, relays: [URL], force: Bool = false) async {
        generation += 1
        let epoch = generation, resetEpoch = Self.resetGeneration
        if owner != publicKey { pictureURL = nil }
        owner = publicKey
        localPicture = try? Self.readImage(directory.appendingPathComponent("local-profile-picture.jpg"))
        guard !publicKey.isEmpty else { pictureURL = nil; loading = false; return }
        loading = true
        var cache = readCache()
        let snapshot = await lookup.load(publicKey: publicKey, relays: relays, cached: cache[publicKey], force: force)
        guard epoch == generation, resetEpoch == Self.resetGeneration, !Task.isCancelled else { return }
        loading = false
        pictureURL = snapshot?.pictureURL
        guard let snapshot else { return }
        // Reread after awaiting another view's lookup so different owners do
        // not overwrite each other's public cache entries.
        cache = readCache(); cache[publicKey] = snapshot
        while cache.count > 32 { cache.removeValue(forKey: cache.min { $0.value.fetchedAt < $1.value.fetchedAt }!.key) }
        while let data = try? JSONEncoder().encode(cache), data.count > 512 * 1024, cache.count > 1 {
            cache.removeValue(forKey: cache.min { $0.value.fetchedAt < $1.value.fetchedAt }!.key)
        }
        if let data = try? JSONEncoder().encode(cache), data.count <= 512 * 1024 {
            try? write(data, name: "public-profile-metadata.json")
        }
    }
    var selectionToken: Int { Self.resetGeneration }
    func setLocalPicture(_ data: Data, expectedReset: Int? = nil) throws {
        guard expectedReset == nil || expectedReset == Self.resetGeneration else { throw CancellationError() }
        guard !data.isEmpty, data.count <= Self.maximumImageBytes else { throw LocalDataError.tooLarge }
        try write(data, name: "local-profile-picture.jpg")
        localPicture = data
    }
    func removeLocalPicture() throws {
        let path = directory.appendingPathComponent("local-profile-picture.jpg")
        if FileManager.default.fileExists(atPath: path.path) { try FileManager.default.removeItem(at: path) }
        localPicture = nil
    }
    static func resetFiles(in directory: URL) throws {
        resetGeneration += 1
        for name in ["local-profile-picture.jpg", "public-profile-metadata.json"] {
            let path = directory.appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: path.path) { try FileManager.default.removeItem(at: path) }
        }
    }
    private static func readImage(_ url: URL) throws -> Data {
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard (1...maximumImageBytes).contains(size) else { throw LocalDataError.tooLarge }
        return try Data(contentsOf: url)
    }
    private func readCache() -> [String: NostrPublicProfile] {
        let url = directory.appendingPathComponent("public-profile-metadata.json")
        guard let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size <= 512 * 1024,
              let data = try? Data(contentsOf: url), let values = try? JSONDecoder().decode([String: NostrPublicProfile].self, from: data),
              values.count <= 32 else { return [:] }
        return values
    }
    private func write(_ data: Data, name: String) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let file = directory.appendingPathComponent(name)
        try data.write(to: file, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
    }
}

#if PLEBRAP_JOURNEY_TESTS
import Foundation
import MeetupCore
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

private actor PlebRapTestLatch {
    private var opened = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    func wait() async { if !opened { await withCheckedContinuation { waiters.append($0) } } }
    func open() { opened = true; let pending = waiters; waiters = []; for waiter in pending { waiter.resume() } }
}
private actor PlebRapTestDownloads: PlebRapDownloadTransport {
    let folder: URL
    let bytes: Data
    let firstStarted = PlebRapTestLatch()
    let releaseFirst: PlebRapTestLatch?
    private(set) var calls: [URL] = []
    private(set) var active = 0
    private(set) var peak = 0
    init(folder: URL, bytes: Data = Data(repeating: 7, count: 6), releaseFirst: PlebRapTestLatch? = nil) {
        self.folder = folder; self.bytes = bytes; self.releaseFirst = releaseFirst
    }
    func download(_ url: URL, maximumBytes: Int) async throws -> URL {
        calls.append(url); active += 1; peak = max(peak, active)
        let first = calls.count == 1
        if first { await firstStarted.open(); await releaseFirst?.wait() }
        // Deliberately ignore cancellation here: cache generation checks must
        // reject late results even from a noncooperative transport.
        let file = folder.appendingPathComponent(UUID().uuidString + ".part")
        try bytes.write(to: file)
        active -= 1
        return file
    }
}
@MainActor private final class PlebRapTestPlayback: PlebRapPlayback {
    struct Load { let url: URL; let token: UUID; let position: TimeInterval; let playing: Bool }
    var onEvent: ((PlebRapPlaybackEvent) -> Void)?
    var onCommand: ((PlebRapPlaybackCommand) -> Void)?
    var loads: [Load] = []
    var playing = false
    var nowPlayingID: String?
    var lastSeek: TimeInterval?
    func load(_ url: URL, token: UUID, position: TimeInterval, playing: Bool) {
        loads.append(Load(url: url, token: token, position: position, playing: playing)); self.playing = playing
    }
    func play() { playing = true }
    func pause() { playing = false }
    func seek(_ seconds: TimeInterval) { lastSeek = seconds }
    func stop() { playing = false }
    func nowPlaying(_ track: PlebTrack?, elapsed: TimeInterval, duration: TimeInterval, playing: Bool, hasPrevious: Bool, hasNext: Bool) { nowPlayingID = track?.id }
}
private final class PlebRapTestURLProtocol: URLProtocol {
    struct Response: Sendable {
        var status = 200
        var headers: [String: String] = [:]
        var chunks: [Data] = []
        var hang = false
        var started: PlebRapTestLatch?
    }
    private final class State: @unchecked Sendable {
        let lock = NSLock()
        var response = Response()
        func set(_ response: Response) { lock.lock(); self.response = response; lock.unlock() }
        func get() -> Response { lock.lock(); defer { lock.unlock() }; return response }
    }
    private static let state = State()
    static func respond(_ response: Response) { state.set(response) }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let fixture = Self.state.get()
        if let started = fixture.started { Task { await started.open() } }
        if fixture.hang { return }
        let response = HTTPURLResponse(url: request.url!, statusCode: fixture.status, httpVersion: "HTTP/1.1", headerFields: fixture.headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        for chunk in fixture.chunks { client?.urlProtocol(self, didLoad: chunk) }
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}

@main @MainActor private enum PlebRapJourneyParityTests {
    private struct Failure: Error, CustomStringConvertible { let description: String }
    private static func require(_ condition: Bool, _ message: String) throws { if !condition { throw Failure(description: message) } }
    private static func track(_ id: String) -> PlebTrack {
        PlebTrack(id: id, album: "Fixture", title: "Track " + id, artist: "Fixture", url: URL(string: "https://media.fixture.invalid/" + id + ".mp3")!)
    }
    private static func workspace() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("plebrap-parity-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
    private static func mustCancel(_ task: Task<URL, any Error>) async throws {
        do { _ = try await task.value; throw Failure(description: "Stale cache job committed") }
        catch is CancellationError { }
    }
    static func main() async throws {
        let tests: [(String, @MainActor () async throws -> Void)] = [
            ("automatic cache reload preserves paused position", automaticCache),
            ("completed cache is reused without network", offlineReuse),
            ("failure advances once and stale callbacks cannot change tracks", failedTracks),
            ("playlist end stops without wrapping", playlistEnd),
            ("reset rejects late download and clears Now Playing", resetRace),
            ("reset before automatic task starts cannot recreate cache", immediateReset),
            ("remove fences a late download and allows a fresh retry", removeRace),
            ("cache serializes saves and evicts oldest unpinned file", boundedCache),
            ("oversized and unsafe cache entries are rejected", rejectedCache),
            ("network adapter streams valid bytes and enforces both bounds", streamingBounds),
            ("network cancellation resolves without a cache file", networkCancellation),
        ]
        for (name, test) in tests { try await test(); print("PASS " + name) }
        print("PlebRapJourneyParityTests: \(tests.count) passed")
    }
    private static func automaticCache() async throws {
        let root = try workspace(); defer { try? FileManager.default.removeItem(at: root) }
        let gate = PlebRapTestLatch(), downloads = PlebRapTestDownloads(folder: root, releaseFirst: gate)
        let playback = PlebRapTestPlayback(), song = track("a")
        let media = NativeMediaPlayer(playback: playback, files: PlebRapFileCache(directory: root.appendingPathComponent("cache"), transport: downloads), tracks: [song])
        media.play(song)
        await downloads.firstStarted.wait()
        let token = playback.loads[0].token
        playback.onEvent?(.progress(token, 37, 0)); media.pause()
        let finished = media.cache(song)
        await gate.open(); await finished.value
        try require(playback.loads.count == 2 && playback.loads[1].url.isFileURL, "Unknown-duration stream did not reload local file")
        try require(playback.loads[1].position == 37 && !playback.loads[1].playing && !media.playing, "Reload changed pause or position")
        try require(media.isCached(song) && !media.downloading, "Track was not automatically cached")
        try require(await downloads.calls.count == 1, "Automatic/manual save was not deduplicated")
    }
    private static func offlineReuse() async throws {
        let root = try workspace(); defer { try? FileManager.default.removeItem(at: root) }
        let downloads = PlebRapTestDownloads(folder: root), files = PlebRapFileCache(directory: root.appendingPathComponent("cache"), transport: downloads), song = track("a")
        _ = try await files.store(song)
        let playback = PlebRapTestPlayback(), media = NativeMediaPlayer(playback: playback, files: files, tracks: [song])
        media.play(song)
        try require(playback.loads[0].url.isFileURL, "Cached playback attempted streaming")
        try require(await downloads.calls.count == 1, "Cached play started another download")
        playback.onEvent?(.progress(playback.loads[0].token, 5, 100)); media.seek(0.5)
        try require(playback.lastSeek == 50, "Cached track could not seek")
    }
    private static func failedTracks() async throws {
        let root = try workspace(); defer { try? FileManager.default.removeItem(at: root) }
        let gate = PlebRapTestLatch(), downloads = PlebRapTestDownloads(folder: root, releaseFirst: gate)
        let playback = PlebRapTestPlayback(), songs = [track("a"), track("b"), track("c")]
        let media = NativeMediaPlayer(playback: playback, files: PlebRapFileCache(directory: root.appendingPathComponent("cache"), transport: downloads), tracks: songs)
        media.play(songs[0]); let old = playback.loads[0].token
        playback.onEvent?(.failed(old))
        try require(media.selected?.id == "b" && media.playing, "Failed track did not advance")
        playback.onEvent?(.failed(old)); playback.onEvent?(.finished(old)); playback.onEvent?(.progress(old, 99, 100))
        try require(media.selected?.id == "b" && media.elapsed == 0, "Stale item callbacks changed playback")
        let second = playback.loads.last!.token
        playback.onEvent?(.failed(second))
        try require(media.selected?.id == "c", "Consecutive failure did not advance")
        playback.onEvent?(.failed(playback.loads.last!.token))
        try require(media.selected?.id == "c" && !media.playing && media.error == "The track could not be played. End of playlist.", "Final failure wrapped or kept playing")
        try await media.reset(); await gate.open()
    }
    private static func playlistEnd() async throws {
        let root = try workspace(); defer { try? FileManager.default.removeItem(at: root) }
        let playback = PlebRapTestPlayback(), songs = [track("a"), track("b")]
        let files = PlebRapFileCache(directory: root.appendingPathComponent("cache"), transport: PlebRapTestDownloads(folder: root))
        for song in songs { _ = try await files.store(song) }
        let media = NativeMediaPlayer(playback: playback, files: files, tracks: songs)
        media.play(songs[0]); media.next(offset: -1)
        try require(media.selected?.id == "a" && !media.hasPrevious && media.hasNext, "Previous wrapped at beginning")
        playback.onEvent?(.finished(playback.loads.last!.token))
        let last = playback.loads.last!.token
        playback.onEvent?(.progress(last, 100, 100)); playback.onEvent?(.finished(last)); media.next()
        try require(media.selected?.id == "b" && !media.playing && !media.hasNext, "Completion wrapped at end")
    }
    private static func resetRace() async throws {
        let root = try workspace(); defer { try? FileManager.default.removeItem(at: root) }
        let cache = root.appendingPathComponent("cache"), gate = PlebRapTestLatch()
        let downloads = PlebRapTestDownloads(folder: root, releaseFirst: gate), playback = PlebRapTestPlayback(), song = track("a")
        let media = NativeMediaPlayer(playback: playback, files: PlebRapFileCache(directory: cache, transport: downloads), tracks: [song])
        media.play(song); await downloads.firstStarted.wait()
        let waiter = media.cache(song), old = playback.loads[0].token
        try await media.reset(); await gate.open(); await waiter.value
        playback.onEvent?(.finished(old)); playback.onEvent?(.failed(old))
        try require(!FileManager.default.fileExists(atPath: cache.path), "Late download recreated reset cache")
        try require(media.selected == nil && !media.playing && !media.downloading && media.error == nil && playback.nowPlayingID == nil, "Reset retained playback state")
    }
    private static func immediateReset() async throws {
        let root = try workspace(); defer { try? FileManager.default.removeItem(at: root) }
        let downloads = PlebRapTestDownloads(folder: root), playback = PlebRapTestPlayback(), song = track("a")
        let media = NativeMediaPlayer(playback: playback, files: PlebRapFileCache(directory: root.appendingPathComponent("cache"), transport: downloads), tracks: [song])
        media.play(song); try await media.reset()
        for _ in 0..<10 { await Task.yield() }
        try require(await downloads.calls.isEmpty, "Pre-reset queued automatic save started after reset")
    }
    private static func removeRace() async throws {
        let root = try workspace(); defer { try? FileManager.default.removeItem(at: root) }
        let gate = PlebRapTestLatch(), downloads = PlebRapTestDownloads(folder: root, releaseFirst: gate), song = track("a")
        let files = PlebRapFileCache(directory: root.appendingPathComponent("cache"), transport: downloads)
        let saving = Task { try await files.store(song) }; await downloads.firstStarted.wait()
        try files.remove(song); await gate.open(); try await mustCancel(saving)
        try require(files.cachedURL(song) == nil, "Removed track resurrected")
        _ = try await files.store(song)
        let count = await downloads.calls.count
        try require(files.cachedURL(song) != nil && count == 2, "Fresh save could not follow removal")
    }
    private static func boundedCache() async throws {
        let root = try workspace(); defer { try? FileManager.default.removeItem(at: root) }
        let gate = PlebRapTestLatch(), downloads = PlebRapTestDownloads(folder: root, releaseFirst: gate)
        let files = PlebRapFileCache(directory: root.appendingPathComponent("cache"), transport: downloads, maximumFileBytes: 8, maximumTotalBytes: 12)
        let a = track("a"), b = track("b"), c = track("c")
        let first = Task { try await files.store(a) }; await downloads.firstStarted.wait()
        let second = Task { try await files.store(b) }; await gate.open()
        let firstURL = try await first.value, secondURL = try await second.value
        try require(await downloads.peak == 1, "Downloads were not serialized")
        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: 100)], ofItemAtPath: firstURL.path)
        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: 200)], ofItemAtPath: secondURL.path)
        files.protectedID = a.id
        _ = try await files.store(c)
        try require(files.cachedURL(a) != nil && files.cachedURL(b) == nil && files.cachedURL(c) != nil, "Eviction did not protect playing file")
        let bytes = try FileManager.default.contentsOfDirectory(at: root.appendingPathComponent("cache"), includingPropertiesForKeys: [.fileSizeKey]).reduce(0) { try $0 + ($1.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) }
        try require(bytes <= 12, "Total cache bound exceeded")
    }
    private static func rejectedCache() async throws {
        let root = try workspace(); defer { try? FileManager.default.removeItem(at: root) }
        let downloads = PlebRapTestDownloads(folder: root, bytes: Data(repeating: 1, count: 9))
        let files = PlebRapFileCache(directory: root.appendingPathComponent("cache"), transport: downloads, maximumFileBytes: 8, maximumTotalBytes: 12)
        do { _ = try await files.store(track("a")); throw Failure(description: "Oversized file installed") } catch is PlebRapCacheError { }
        try require(files.cachedURL(track("a")) == nil, "Oversized file remained cached")
        let unsafe = PlebTrack(id: "../escape", album: "", title: "", artist: "", url: URL(string: "https://media.fixture.invalid/a")!)
        do { _ = try await files.store(unsafe); throw Failure(description: "Path traversal accepted") } catch is URLError { }
        try require(await downloads.calls.count == 1, "Unsafe track reached transport")
    }
    private static func transport() -> PlebRapFileDownload {
        PlebRapFileDownload(configuration: { let configuration = URLSessionConfiguration.ephemeral; configuration.protocolClasses = [PlebRapTestURLProtocol.self]; return configuration })
    }
    private static func streamingBounds() async throws {
        let url = URL(string: "https://media.fixture.invalid/song.mp3")!
        PlebRapTestURLProtocol.respond(.init(headers: ["Content-Length": "6"], chunks: [Data([1, 2, 3]), Data([4, 5, 6])]))
        let file = try await transport().download(url, maximumBytes: 8)
        defer { try? FileManager.default.removeItem(at: file) }
        try require(try Data(contentsOf: file) == Data([1, 2, 3, 4, 5, 6]), "Streaming bytes changed")
        for fixture in [PlebRapTestURLProtocol.Response(headers: ["Content-Length": "9"], chunks: [Data(repeating: 0, count: 9)]), .init(chunks: [Data(repeating: 0, count: 5), Data(repeating: 0, count: 4)])] {
            PlebRapTestURLProtocol.respond(fixture)
            do { let file = try await transport().download(url, maximumBytes: 8); try? FileManager.default.removeItem(at: file); throw Failure(description: "Oversized network response accepted") } catch is PlebRapCacheError { }
        }
        for fixture in [PlebRapTestURLProtocol.Response(status: 503, chunks: [Data([1])]), .init(headers: ["Content-Length": "6"], chunks: [Data([1, 2, 3])]), .init(chunks: [])] {
            PlebRapTestURLProtocol.respond(fixture)
            do { _ = try await transport().download(url, maximumBytes: 8); throw Failure(description: "Failed, truncated or empty response cached") } catch is URLError { }
        }
    }
    private static func networkCancellation() async throws {
        let started = PlebRapTestLatch()
        PlebRapTestURLProtocol.respond(.init(hang: true, started: started))
        let request = Task { try await transport().download(URL(string: "https://media.fixture.invalid/hang")!, maximumBytes: 8) }
        await started.wait(); request.cancel(); try await mustCancel(request)
    }
}
#endif

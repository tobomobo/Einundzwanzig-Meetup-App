import Foundation
import MeetupCore
import Observation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
#if canImport(AVFoundation)
import AVFoundation
import MediaPlayer
#endif

protocol PlebRapDownloadTransport: Sendable {
    /// Returns an owned temporary file. The caller removes it after installation.
    func download(_ url: URL, maximumBytes: Int) async throws -> URL
}

struct PlebRapFileDownload: PlebRapDownloadTransport {
    var configuration: @Sendable () -> URLSessionConfiguration = { .ephemeral }
    func download(_ url: URL, maximumBytes: Int) async throws -> URL {
        guard url.scheme == "https", SafeURL.web(url.absoluteString) != nil else { throw URLError(.badURL) }
        let operation = PlebRapFileRequest(maximumBytes: maximumBytes)
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                operation.start(url, configuration: configuration(), continuation: continuation)
            }
        } onCancel: { operation.cancel() }
    }
}

/// Streams into a private temporary file. Both advertised and actual bytes are
/// bounded; a server without Content-Length cannot fill the disk unboundedly.
private final class PlebRapFileRequest: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private let maximumBytes: Int
    private var received = 0
    private var expectedBytes: Int64?
    private var output: FileHandle?
    private var temporary: URL?
    private var continuation: CheckedContinuation<URL, any Error>?
    private var session: URLSession?
    private var finished = false
    private var cancelled = false
    init(maximumBytes: Int) { self.maximumBytes = maximumBytes }
    func start(_ url: URL, configuration: URLSessionConfiguration, continuation: CheckedContinuation<URL, any Error>) {
        lock.lock()
        guard !cancelled else { lock.unlock(); continuation.resume(throwing: CancellationError()); return }
        self.continuation = continuation
        do {
            let file = FileManager.default.temporaryDirectory.appendingPathComponent("plebrap-" + UUID().uuidString + ".part")
            guard FileManager.default.createFile(atPath: file.path, contents: nil, attributes: [.posixPermissions: 0o600]) else { throw URLError(.cannotCreateFile) }
            temporary = file
            output = try FileHandle(forWritingTo: file)
        } catch { lock.unlock(); finish(.failure(error)); return }
        configuration.timeoutIntervalForRequest = 120
        configuration.timeoutIntervalForResource = 120
        configuration.httpCookieStorage = nil
        configuration.urlCredentialStorage = nil
        configuration.urlCache = nil
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        self.session = session
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 120)
        request.setValue("audio/mpeg, application/octet-stream", forHTTPHeaderField: "Accept")
        let task = session.dataTask(with: request)
        lock.unlock()
        task.resume()
    }
    func cancel() { lock.lock(); cancelled = true; lock.unlock(); finish(.failure(CancellationError())) }
    private func finish(_ result: Result<URL, any Error>) {
        lock.lock()
        guard !finished, let continuation else { lock.unlock(); return }
        finished = true
        self.continuation = nil
        let session = self.session; self.session = nil
        let output = self.output; self.output = nil
        let temporary = self.temporary; self.temporary = nil
        lock.unlock()
        try? output?.close()
        if case .failure = result, let temporary { try? FileManager.default.removeItem(at: temporary) }
        session?.invalidateAndCancel()
        continuation.resume(with: result)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping @Sendable (URLSession.ResponseDisposition) -> Void) {
        guard let http = response as? HTTPURLResponse, http.statusCode == 200,
              let url = response.url, url.scheme == "https", SafeURL.web(url.absoluteString) != nil else {
            completionHandler(.cancel); finish(.failure(URLError(.badServerResponse))); return
        }
        guard response.expectedContentLength <= Int64(maximumBytes) else {
            completionHandler(.cancel); finish(.failure(PlebRapCacheError.limitExceeded)); return
        }
        lock.lock()
        expectedBytes = response.expectedContentLength >= 0 ? response.expectedContentLength : nil
        lock.unlock()
        completionHandler(.allow)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        guard !finished else { lock.unlock(); return }
        guard data.count <= maximumBytes - received else { lock.unlock(); finish(.failure(PlebRapCacheError.limitExceeded)); return }
        do { try output?.write(contentsOf: data); received += data.count; lock.unlock() }
        catch { lock.unlock(); finish(.failure(error)) }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: (any Error)?) {
        if let error { finish(.failure(error)); return }
        lock.lock()
        let file = temporary, complete = received > 0 && (expectedBytes.map { $0 == Int64(received) } ?? true)
        lock.unlock()
        if let file, complete { finish(.success(file)) } else { finish(.failure(URLError(.badServerResponse))) }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        guard let url = request.url, url.scheme == "https", SafeURL.web(url.absoluteString) != nil else {
            completionHandler(nil); finish(.failure(URLError(.badURL))); return
        }
        completionHandler(request)
    }
}

enum PlebRapCacheError: Error, LocalizedError {
    case limitExceeded
    var errorDescription: String? { "The download exceeded the offline cache limit." }
}

@MainActor final class PlebRapFileCache {
    private struct Job { let id: UUID; let task: Task<URL, any Error> }
    private let directory: URL
    private let transport: any PlebRapDownloadTransport
    private let maximumFileBytes: Int
    private let maximumTotalBytes: Int
    private var generation = UUID()
    private var jobs: [String: Job] = [:]
    private var tail: Job?
    var protectedID: String?
    init(directory: URL? = nil, transport: any PlebRapDownloadTransport = PlebRapFileDownload(),
         maximumFileBytes: Int = 128 * 1024 * 1024, maximumTotalBytes: Int = 512 * 1024 * 1024) {
        self.directory = directory ?? FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("PlebRap", isDirectory: true)
        self.transport = transport
        self.maximumFileBytes = maximumFileBytes; self.maximumTotalBytes = maximumTotalBytes
    }
    private func target(_ id: String) throws -> URL {
        guard !id.isEmpty, id.utf8.count <= 80, id.utf8.allSatisfy({ (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || $0 == 45 || $0 == 95 }) else { throw URLError(.badURL) }
        return directory.appendingPathComponent(id + ".mp3")
    }
    func cachedURL(_ track: PlebTrack, touch: Bool = false) -> URL? {
        guard let url = try? target(track.id), let info = try? url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey]),
              info.isRegularFile == true, info.isSymbolicLink != true, let size = info.fileSize, size > 0, size <= maximumFileBytes, size <= maximumTotalBytes else { return nil }
        if touch {
            try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: url.path)
            // Also bring older app-version caches under the current total limit.
            try? makeRoom(for: 0, replacing: directory.appendingPathComponent(".unused"))
        }
        return url
    }
    func store(_ track: PlebTrack) async throws -> URL {
        let target = try target(track.id)
        guard track.url.scheme == "https", SafeURL.web(track.url.absoluteString) != nil else { throw URLError(.badURL) }
        if let cached = cachedURL(track, touch: true) { return cached }
        if let job = jobs[track.id] { return try await job.task.value }
        let epoch = generation, id = UUID(), previous = tail?.task
        let transport = self.transport, maximumBytes = maximumFileBytes
        let task = Task { @MainActor [weak self] () throws -> URL in
            // One network save at a time, even when tracks are changed quickly.
            if let previous { _ = await previous.result }
            try Task.checkCancellation()
            guard let self, epoch == self.generation else { throw CancellationError() }
            let temporary = try await transport.download(track.url, maximumBytes: maximumBytes)
            defer { try? FileManager.default.removeItem(at: temporary) }
            try Task.checkCancellation()
            guard epoch == self.generation, self.jobs[track.id]?.id == id else { throw CancellationError() }
            let info = try temporary.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
            guard info.isRegularFile == true, info.isSymbolicLink != true, let bytes = info.fileSize, bytes > 0 else { throw URLError(.badServerResponse) }
            guard bytes <= self.maximumFileBytes, bytes <= self.maximumTotalBytes else { throw PlebRapCacheError.limitExceeded }
            try FileManager.default.createDirectory(at: self.directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
            try self.makeRoom(for: bytes, replacing: target)
            if FileManager.default.fileExists(atPath: target.path) { try FileManager.default.removeItem(at: target) }
            try FileManager.default.moveItem(at: temporary, to: target)
            try FileManager.default.setAttributes([.modificationDate: Date(), .posixPermissions: 0o600], ofItemAtPath: target.path)
            return target
        }
        let job = Job(id: id, task: task)
        jobs[track.id] = job; tail = job
        defer {
            if jobs[track.id]?.id == id { jobs[track.id] = nil }
            if tail?.id == id { tail = nil }
        }
        return try await task.value
    }
    private func makeRoom(for bytes: Int, replacing target: URL) throws {
        let keys: Set<URLResourceKey> = [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey, .contentModificationDateKey]
        let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: Array(keys)).filter { $0.pathExtension == "mp3" && $0 != target }
        var records: [(URL, Int, Date)] = []
        for file in files {
            let info = try file.resourceValues(forKeys: keys)
            if info.isRegularFile == true, info.isSymbolicLink != true { records.append((file, info.fileSize ?? 0, info.contentModificationDate ?? .distantPast)) }
        }
        var total = records.reduce(Int64(bytes)) { $0 + Int64($1.1) }
        for record in records.sorted(by: { $0.2 == $1.2 ? $0.0.lastPathComponent < $1.0.lastPathComponent : $0.2 < $1.2 }) where total > maximumTotalBytes {
            if record.0.deletingPathExtension().lastPathComponent == protectedID { continue }
            try FileManager.default.removeItem(at: record.0)
            total -= Int64(record.1)
        }
        guard total <= maximumTotalBytes else { throw PlebRapCacheError.limitExceeded }
    }
    func remove(_ track: PlebTrack) throws {
        jobs.removeValue(forKey: track.id)?.task.cancel()
        let url = try target(track.id)
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
    }
    func reset() throws {
        generation = UUID()
        for job in jobs.values { job.task.cancel() }
        jobs = [:]; tail = nil; protectedID = nil
        if FileManager.default.fileExists(atPath: directory.path) { try FileManager.default.removeItem(at: directory) }
    }
}

enum PlebRapPlaybackEvent: Sendable {
    case progress(UUID, TimeInterval, TimeInterval)
    case finished(UUID)
    case failed(UUID)
}
enum PlebRapPlaybackCommand: Sendable { case play, pause, next, previous }
@MainActor protocol PlebRapPlayback: AnyObject {
    var onEvent: ((PlebRapPlaybackEvent) -> Void)? { get set }
    var onCommand: ((PlebRapPlaybackCommand) -> Void)? { get set }
    func load(_ url: URL, token: UUID, position: TimeInterval, playing: Bool)
    func play()
    func pause()
    func seek(_ seconds: TimeInterval)
    func stop()
    func nowPlaying(_ track: PlebTrack?, elapsed: TimeInterval, duration: TimeInterval, playing: Bool, hasPrevious: Bool, hasNext: Bool)
}

/// The playlist/cache controller is portable; AVPlayer is the platform adapter.
@Observable @MainActor final class NativeMediaPlayer {
    private let playback: any PlebRapPlayback
    private let files: PlebRapFileCache
    private let tracks: [PlebTrack]
    private var token = UUID()
    private var usingCachedFile = false
    private var failedToken: UUID?
    private var generation = UUID()
    private var downloadCount = 0
    private var cacheRevision = 0
    private(set) var selected: PlebTrack?
    private(set) var playing = false
    private(set) var elapsed: TimeInterval = 0
    private(set) var duration: TimeInterval = 0
    private(set) var downloading = false
    private(set) var error: String?
    var hasPrevious: Bool { index.map { $0 > 0 } ?? false }
    var hasNext: Bool { index.map { $0 + 1 < tracks.count } ?? false }
    private var index: Int? { selected.flatMap { current in tracks.firstIndex { $0.id == current.id } } }
    init(playback: any PlebRapPlayback, files: PlebRapFileCache, tracks: [PlebTrack] = PlebRapCatalog.tracks) {
        self.playback = playback; self.files = files; self.tracks = tracks
        playback.onEvent = { [weak self] event in self?.receive(event) }
        playback.onCommand = { [weak self] command in
            switch command { case .play: self?.resume(); case .pause: self?.pause(); case .next: self?.next(); case .previous: self?.next(offset: -1) }
        }
    }
    #if canImport(AVFoundation)
    convenience init() { self.init(playback: SystemPlebRapPlayback(), files: PlebRapFileCache()) }
    #endif
    func play(_ track: PlebTrack) { start(track, clearIssue: true) }
    private func start(_ track: PlebTrack, clearIssue: Bool) {
        guard tracks.contains(where: { $0.id == track.id && $0.url == track.url }), track.url.scheme == "https", SafeURL.web(track.url.absoluteString) != nil else { return }
        let request = UUID()
        token = request; failedToken = nil; selected = track; elapsed = 0; duration = 0; playing = true
        files.protectedID = track.id
        if clearIssue { error = nil }
        let cached = files.cachedURL(track, touch: true)
        usingCachedFile = cached != nil
        playback.load(cached ?? track.url, token: request, position: 0, playing: true)
        guard token == request else { return }
        updateNowPlaying()
        if cached == nil { cache(track) }
    }
    func resume() {
        guard let selected else { if let first = tracks.first { play(first) }; return }
        if failedToken == token { play(selected); return }
        if duration > 0, elapsed >= duration { elapsed = 0; playback.seek(0) }
        playback.play(); playing = true; updateNowPlaying()
    }
    func pause() { playback.pause(); playing = false; updateNowPlaying() }
    func next(offset: Int = 1) {
        guard let index else { return }
        let candidate = index + offset
        guard tracks.indices.contains(candidate) else { return }
        play(tracks[candidate])
    }
    func seek(_ value: Double) {
        guard duration > 0, value.isFinite else { return }
        elapsed = min(1, max(0, value)) * duration
        playback.seek(elapsed); updateNowPlaying()
    }
    private func receive(_ event: PlebRapPlaybackEvent) {
        switch event {
        case .progress(let request, let current, let length):
            guard request == token else { return }
            elapsed = current.isFinite ? max(0, current) : 0
            duration = length.isFinite ? max(0, length) : 0
            updateNowPlaying()
        case .finished(let request):
            guard request == token, let index else { return }
            if index + 1 < tracks.count { start(tracks[index + 1], clearIssue: true) }
            else { elapsed = duration; pause() }
        case .failed(let request):
            guard request == token, request != failedToken, let index else { return }
            failedToken = request
            if index + 1 < tracks.count {
                error = "The track could not be played. Skipping to the next track."
                start(tracks[index + 1], clearIssue: false)
            } else {
                error = "The track could not be played. End of playlist."
                pause()
            }
        }
    }
    func isCached(_ track: PlebTrack) -> Bool { _ = cacheRevision; return files.cachedURL(track) != nil }
    /// Capture the user's save intent before enqueueing work, so even a task
    /// that has not started yet cannot cross a subsequent local-data reset.
    @discardableResult func cache(_ track: PlebTrack) -> Task<Void, Never> {
        let epoch = generation, request = selected?.id == track.id ? token : nil
        return Task { [weak self] in
            guard let self, self.generation == epoch else { return }
            await self.cache(track, playbackToken: request)
        }
    }
    private func cache(_ track: PlebTrack, playbackToken: UUID?) async {
        let epoch = generation
        downloadCount += 1; downloading = true
        defer { if epoch == generation { downloadCount -= 1; downloading = downloadCount > 0 } }
        do {
            let url = try await files.store(track)
            guard epoch == generation, !Task.isCancelled else { return }
            cacheRevision += 1
            // No HTTP range support: reload the completed local file so AVPlayer
            // can discover duration and seek, preserving pause and position.
            if playbackToken == token, selected?.id == track.id, duration <= 0, !usingCachedFile, failedToken != token {
                usingCachedFile = true
                token = UUID()
                playback.load(url, token: token, position: elapsed, playing: playing)
                updateNowPlaying()
            }
        } catch is CancellationError { }
        catch { if epoch == generation, !Task.isCancelled { self.error = error.localizedDescription } }
    }
    func removeCached(_ track: PlebTrack) throws { try files.remove(track); cacheRevision += 1 }
    func reset() async throws {
        generation = UUID(); token = UUID(); failedToken = nil; usingCachedFile = false
        playback.stop(); selected = nil; playing = false; elapsed = 0; duration = 0
        downloadCount = 0; downloading = false; error = nil; cacheRevision += 1
        updateNowPlaying()
        try files.reset()
    }
    private func updateNowPlaying() { playback.nowPlaying(selected, elapsed: elapsed, duration: duration, playing: playing, hasPrevious: hasPrevious, hasNext: hasNext) }
}

#if canImport(AVFoundation)
@MainActor private final class SystemPlebRapPlayback: PlebRapPlayback {
    var onEvent: ((PlebRapPlaybackEvent) -> Void)?
    var onCommand: ((PlebRapPlaybackCommand) -> Void)?
    private let player = AVPlayer()
    private var token = UUID()
    private var observers: [any NSObjectProtocol] = []
    private var statusObserver: NSKeyValueObservation?
    private var timeObserver: Any?
    private var commandTargets: [(MPRemoteCommand, Any)] = []
    init() {
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.onEvent?(.progress(self.token, self.player.currentTime().seconds, self.player.currentItem?.duration.seconds ?? 0))
            }
        }
        let commands = MPRemoteCommandCenter.shared()
        for (command, action) in [(commands.playCommand, PlebRapPlaybackCommand.play), (commands.pauseCommand, .pause), (commands.nextTrackCommand, .next), (commands.previousTrackCommand, .previous)] {
            let target = command.addTarget { [weak self] _ in Task { @MainActor in self?.onCommand?(action) }; return .success }
            commandTargets.append((command, target))
        }
    }
    isolated deinit {
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        for (command, target) in commandTargets { command.removeTarget(target) }
    }
    func load(_ url: URL, token: UUID, position: TimeInterval, playing: Bool) {
        self.token = token
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        observers = []; statusObserver = nil
        do {
            #if os(iOS)
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
            try AVAudioSession.sharedInstance().setActive(true)
            #endif
            let item = AVPlayerItem(url: url)
            observers.append(NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
                Task { @MainActor in guard let self, self.token == token else { return }; self.onEvent?(.finished(token)) }
            })
            observers.append(NotificationCenter.default.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] _ in
                Task { @MainActor in guard let self, self.token == token else { return }; self.onEvent?(.failed(token)) }
            })
            statusObserver = item.observe(\.status, options: [.initial, .new]) { [weak self] _, _ in
                Task { @MainActor in guard let self, self.token == token, self.player.currentItem?.status == .failed else { return }; self.onEvent?(.failed(token)) }
            }
            player.replaceCurrentItem(with: item)
            if position > 0 { player.seek(to: CMTime(seconds: position, preferredTimescale: 600)) }
            if playing { player.play() } else { player.pause() }
        } catch { onEvent?(.failed(token)) }
    }
    func play() { player.play() }
    func pause() { player.pause() }
    func seek(_ seconds: TimeInterval) { player.seek(to: CMTime(seconds: seconds, preferredTimescale: 600)) }
    func stop() {
        token = UUID(); player.pause(); player.replaceCurrentItem(with: nil)
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        observers = []; statusObserver = nil
    }
    func nowPlaying(_ track: PlebTrack?, elapsed: TimeInterval, duration: TimeInterval, playing: Bool, hasPrevious: Bool, hasNext: Bool) {
        let commands = MPRemoteCommandCenter.shared()
        commands.playCommand.isEnabled = track != nil; commands.pauseCommand.isEnabled = track != nil
        commands.nextTrackCommand.isEnabled = hasNext; commands.previousTrackCommand.isEnabled = hasPrevious
        guard let track else { MPNowPlayingInfoCenter.default().nowPlayingInfo = nil; return }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [MPMediaItemPropertyTitle: track.title, MPMediaItemPropertyArtist: track.artist,
            MPMediaItemPropertyAlbumTitle: track.album, MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: elapsed, MPNowPlayingInfoPropertyPlaybackRate: playing ? 1.0 : 0.0]
    }
}
#endif

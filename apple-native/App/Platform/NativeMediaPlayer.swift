import Foundation
import Observation
import AVFoundation
import MediaPlayer

@Observable @MainActor final class NativeMediaPlayer {
    private let player = AVPlayer()
    private(set) var selected: PlebTrack?
    private(set) var playing = false
    private(set) var elapsed: TimeInterval = 0
    private(set) var duration: TimeInterval = 0
    private(set) var downloading = false
    private(set) var error: String?
    @ObservationIgnored private var completionObserver: (any NSObjectProtocol)?
    init() {
        completionObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.next() }
        }
        let commands = MPRemoteCommandCenter.shared()
        commands.playCommand.addTarget { [weak self] _ in Task { @MainActor in self?.resume() }; return .success }
        commands.pauseCommand.addTarget { [weak self] _ in Task { @MainActor in self?.pause() }; return .success }
        commands.nextTrackCommand.addTarget { [weak self] _ in Task { @MainActor in self?.next() }; return .success }
        commands.previousTrackCommand.addTarget { [weak self] _ in Task { @MainActor in self?.next(offset: -1) }; return .success }
    }
    func play(_ track: PlebTrack) {
        do {
            #if os(iOS)
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
            try AVAudioSession.sharedInstance().setActive(true)
            #endif
            let cached = try cacheURL(track)
            let url = FileManager.default.fileExists(atPath: cached.path) ? cached : track.url
            guard url.isFileURL || url.scheme == "https" else { return }
            selected = track; player.replaceCurrentItem(with: AVPlayerItem(url: url)); error = nil; resume()
        } catch { self.error = error.localizedDescription }
    }
    func resume() { player.play(); playing = true; updateNowPlaying() }
    func pause() { player.pause(); playing = false; updateNowPlaying() }
    func next(offset: Int = 1) {
        guard !PlebRapCatalog.tracks.isEmpty else { return }
        let index = selected.flatMap { current in PlebRapCatalog.tracks.firstIndex { $0.id == current.id } } ?? 0
        let count = PlebRapCatalog.tracks.count
        play(PlebRapCatalog.tracks[(index + offset + count) % count])
    }
    func seek(_ value: Double) { guard duration > 0 else { return }; player.seek(to: CMTime(seconds: value * duration, preferredTimescale: 600)) }
    func refreshProgress() {
        let current = player.currentTime().seconds, length = player.currentItem?.duration.seconds ?? 0
        elapsed = current.isFinite ? max(0, current) : 0; duration = length.isFinite ? max(0, length) : 0
        if let failure = player.currentItem?.error { error = failure.localizedDescription; playing = false }
        updateNowPlaying()
    }
    func isCached(_ track: PlebTrack) -> Bool { (try? cacheURL(track)).map { FileManager.default.fileExists(atPath: $0.path) } == true }
    func cache(_ track: PlebTrack) async {
        guard !downloading else { return }; downloading = true; defer { downloading = false }
        do {
            guard track.url.scheme == "https" else { return }
            let request = URLRequest(url: track.url, timeoutInterval: 120)
            let (temporary, response) = try await URLSession.shared.download(for: request)
            defer { try? FileManager.default.removeItem(at: temporary) }
            guard let http = response as? HTTPURLResponse, http.statusCode == 200, response.url?.scheme == "https",
                  (try temporary.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max) <= 256 * 1024 * 1024 else { throw URLError(.badServerResponse) }
            let target = try cacheURL(track)
            if FileManager.default.fileExists(atPath: target.path) { try FileManager.default.removeItem(at: target) }
            try FileManager.default.moveItem(at: temporary, to: target)
        } catch { self.error = error.localizedDescription }
    }
    func removeCached(_ track: PlebTrack) throws { let url = try cacheURL(track); if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) } }
    private func cacheURL(_ track: PlebTrack) throws -> URL {
        let directory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("PlebRap", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent(track.id + ".mp3")
    }
    private func updateNowPlaying() {
        guard let selected else { return }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [MPMediaItemPropertyTitle: selected.title, MPMediaItemPropertyArtist: selected.artist,
            MPMediaItemPropertyAlbumTitle: selected.album, MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: elapsed, MPNowPlayingInfoPropertyPlaybackRate: playing ? 1.0 : 0.0]
    }
}

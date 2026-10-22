import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum NostrRelayError: Error, Sendable, Equatable, LocalizedError {
    case invalidRelay, unavailable, timeout, invalidFrame, rejected(String), authenticationRequired
    public var errorDescription: String? {
        switch self {
        case .invalidRelay: "Use a secure wss relay URL without credentials."
        case .unavailable: "No relay completed the request."
        case .timeout: "The relay did not respond before the deadline."
        case .invalidFrame: "The relay returned an invalid or oversized frame."
        case .rejected(let message): "The relay rejected the request: \(message)"
        case .authenticationRequired: "This relay requires an unlocked signing identity."
        }
    }
}

public protocol NostrSocket: Sendable {
    func send(_ data: Data) async throws
    func receive() async throws -> Data
    func close()
}

/// Socket injection keeps protocol/authentication regressions entirely local.
public actor NostrRelayClient: NostrRelayTransport {
    public typealias SocketFactory = @Sendable (URL) throws -> any NostrSocket
    private let makeSocket: SocketFactory
    private let crypto = NostrCrypto()
    private var signer: (any NostrSigner)?
    public init(socketFactory: @escaping SocketFactory = { try NostrWebSocket(url: $0) }) {
        makeSocket = socketFactory
    }
    public func setSigner(_ signer: (any NostrSigner)?) { self.signer = signer }

    public func query(_ filter: NostrFilter, relays: [URL], timeout: TimeInterval = 10) async throws -> [NostrEvent] {
        try await queryWithSigner(filter, relays: relays, timeout: timeout, authenticationSigner: signer)
    }
    public func queryAuthenticated(_ filter: NostrFilter, relays: [URL], timeout: TimeInterval, signer: any NostrSigner) async throws -> [NostrEvent] {
        try await queryWithSigner(filter, relays: relays, timeout: timeout, authenticationSigner: signer)
    }
    private func queryWithSigner(_ filter: NostrFilter, relays: [URL], timeout: TimeInterval, authenticationSigner: (any NostrSigner)?) async throws -> [NostrEvent] {
        let urls = try Self.targets(relays)
        let signer = authenticationSigner
        let factory = makeSocket
        return try await withThrowingTaskGroup(of: [NostrEvent]?.self) { group in
            for url in urls {
                group.addTask { [self] in
                    do { return try await request(filter, at: url, signer: signer, timeout: timeout, factory: factory) }
                    catch is CancellationError { throw CancellationError() }
                    catch { return nil }
                }
            }
            var successful = false
            var events: [String: NostrEvent] = [:]
            for try await result in group {
                if let result {
                    successful = true
                    for event in result { events[event.id] = event }
                }
            }
            guard successful else { throw NostrRelayError.unavailable }
            return events.values.sorted { $0.createdAt == $1.createdAt ? $0.id < $1.id : $0.createdAt > $1.createdAt }
        }
    }

    public func publish(_ event: NostrEvent, relays: [URL], signer: (any NostrSigner)? = nil) async throws {
        guard await crypto.verify(event) else { throw NostrRelayError.invalidFrame }
        let urls = try Self.targets(relays)
        let signer = signer ?? self.signer
        let factory = makeSocket
        try await withThrowingTaskGroup(of: Bool.self) { group in
            for url in urls {
                group.addTask { [self] in
                    do { try await send(event, at: url, signer: signer, factory: factory); return true }
                    catch is CancellationError { throw CancellationError() }
                    catch { return false }
                }
            }
            for try await success in group where success { group.cancelAll(); return }
            throw NostrRelayError.unavailable
        }
    }

    private static func targets(_ relays: [URL]) throws -> [URL] {
        let unique = Array(Set(relays)).sorted { $0.absoluteString < $1.absoluteString }
        guard !unique.isEmpty, unique.count <= 16, unique.allSatisfy({
            $0.scheme?.lowercased() == "wss" && $0.host != nil && $0.user == nil && $0.password == nil && $0.fragment == nil
        }) else { throw NostrRelayError.invalidRelay }
        return unique
    }

    private func request(_ filter: NostrFilter, at url: URL, signer: (any NostrSigner)?, timeout: TimeInterval,
                         factory: SocketFactory) async throws -> [NostrEvent] {
        let socket = try factory(url)
        let id = UUID().uuidString
        let frame = try Self.frame("REQ", id, object: JSONEncoder().encode(filter))
        return try await timed(socket: socket, seconds: timeout) { [self] in
            defer { socket.close() }
            try await socket.send(frame)
            var events: [String: NostrEvent] = [:]
            var authenticationID: String?
            var retries = 0
            var authenticationAttempts = 0
            while true {
                try Task.checkCancellation()
                switch try NostrRelayFrame.decode(await socket.receive()) {
                case .auth(let challenge):
                    guard authenticationAttempts < 2 else { throw NostrRelayError.authenticationRequired }
                    authenticationAttempts += 1
                    authenticationID = try await authenticate(socket, url: url, challenge: challenge, signer: signer)
                case .ok(let eventID, let accepted, _) where eventID == authenticationID:
                    guard accepted, retries < 2 else { throw NostrRelayError.authenticationRequired }
                    retries += 1
                    try await socket.send(frame)
                case .event(let subscription, let event) where subscription == id:
                    if filter.matches(event), event.createdAt <= Int(Date().timeIntervalSince1970) + 600,
                       await crypto.verify(event), events.count < min(filter.limit ?? 2000, 2000) { events[event.id] = event }
                case .eose(let subscription) where subscription == id:
                    if authenticationID != nil && retries == 0 { continue }
                    try? await socket.send(Self.array(["CLOSE", id]))
                    return Array(events.values)
                case .closed(let subscription, let reason) where subscription == id:
                    if reason.hasPrefix("auth-required:"), authenticationID != nil, retries < 2 { continue }
                    throw NostrRelayError.rejected(String(reason.prefix(256)))
                default: continue
                }
            }
        }
    }

    private func send(_ event: NostrEvent, at url: URL, signer: (any NostrSigner)?, factory: SocketFactory) async throws {
        let socket = try factory(url)
        let frame = try Self.frame("EVENT", nil, object: JSONEncoder().encode(event))
        try await timed(socket: socket, seconds: 12) { [self] in
            defer { socket.close() }
            try await socket.send(frame)
            var authenticationID: String?
            var retries = 0
            var authenticationAttempts = 0
            while true {
                switch try NostrRelayFrame.decode(await socket.receive()) {
                case .auth(let challenge):
                    guard authenticationAttempts < 2 else { throw NostrRelayError.authenticationRequired }
                    authenticationAttempts += 1
                    authenticationID = try await authenticate(socket, url: url, challenge: challenge, signer: signer)
                case .ok(let id, let accepted, let reason):
                    if id == authenticationID {
                        guard accepted, retries < 2 else { throw NostrRelayError.authenticationRequired }
                        retries += 1
                        try await socket.send(frame)
                    } else if id == event.id {
                        if accepted { return }
                        if reason.hasPrefix("auth-required:"), authenticationID != nil, retries < 2 { continue }
                        throw NostrRelayError.rejected(String(reason.prefix(256)))
                    }
                default: continue
                }
            }
        }
    }

    private func authenticate(_ socket: any NostrSocket, url: URL, challenge: String, signer: (any NostrSigner)?) async throws -> String {
        guard let signer else { throw NostrRelayError.authenticationRequired }
        guard !challenge.isEmpty, challenge.utf8.count < 4096 else { throw NostrRelayError.invalidFrame }
        let key = try await signer.publicKey()
        let event = try await signer.sign(kind: 22242, tags: [["relay", url.absoluteString], ["challenge", challenge]],
                                          content: "", createdAt: Int(Date().timeIntervalSince1970))
        guard event.pubkey == key, event.kind == 22242, event.tags == [["relay", url.absoluteString], ["challenge", challenge]],
              event.content.isEmpty, abs(event.createdAt - Int(Date().timeIntervalSince1970)) <= 60,
              await crypto.verify(event) else { throw NostrRelayError.authenticationRequired }
        try await socket.send(Self.frame("AUTH", nil, object: JSONEncoder().encode(event)))
        return event.id
    }

    private func timed<T: Sendable>(socket: any NostrSocket, seconds: TimeInterval,
                                    operation: @escaping @Sendable () async throws -> T) async throws -> T {
        try await withTaskCancellationHandler {
            try await withThrowingTaskGroup(of: T.self) { group in
                group.addTask { try await operation() }
                group.addTask {
                    try await Task.sleep(for: .seconds(max(0.1, min(seconds, 60))))
                    socket.close()
                    throw NostrRelayError.timeout
                }
                defer { group.cancelAll(); socket.close() }
                return try await group.next()!
            }
        } onCancel: { socket.close() }
    }
    private static func array(_ values: [String]) throws -> Data { try JSONEncoder().encode(values) }
    private static func frame(_ kind: String, _ id: String?, object: Data) throws -> Data {
        var result = Data("[\"\(kind)\",".utf8)
        if let id { result.append(try JSONEncoder().encode(id)); result.append(44) }
        result.append(object); result.append(93)
        return result
    }
}

public final class NostrWebSocket: NostrSocket, @unchecked Sendable {
    private let session: URLSession
    private let task: URLSessionWebSocketTask
    public init(url: URL) throws {
        guard url.scheme == "wss", url.host != nil, url.user == nil, url.password == nil else { throw NostrRelayError.invalidRelay }
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 12; config.timeoutIntervalForResource = 60
        config.httpCookieStorage = nil; config.urlCredentialStorage = nil; config.urlCache = nil
        session = URLSession(configuration: config)
        task = session.webSocketTask(with: url)
        task.maximumMessageSize = 1024 * 1024
        task.resume()
    }
    public func send(_ data: Data) async throws {
        guard data.count <= 1024 * 1024, let text = String(data: data, encoding: .utf8) else { throw NostrRelayError.invalidFrame }
        try await task.send(.string(text))
    }
    public func receive() async throws -> Data {
        switch try await task.receive() {
        case .string(let text): Data(text.utf8)
        case .data(let data): data
        @unknown default: throw NostrRelayError.invalidFrame
        }
    }
    public func close() { task.cancel(with: .normalClosure, reason: nil); session.invalidateAndCancel() }
}

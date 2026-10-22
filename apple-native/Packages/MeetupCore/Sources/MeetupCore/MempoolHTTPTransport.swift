import Foundation
#if canImport(Network)
import Network
#endif

/// Only MempoolClient uses this adapter. HTTPS keeps URLSession/ATS; explicit
/// custom HTTP uses a read-only Network connection, never portal/signing APIs.
/// https://developer.apple.com/documentation/security/preventing-insecure-network-connections
struct MempoolHTTPTransport: HTTPTransport {
    func get(_ url: URL, accept: String) async throws -> Data {
        guard SafeURL.web(url.absoluteString) != nil, url.fragment == nil else { throw MeetupCoreError.invalidURL }
        let timeout = MempoolConfiguration.timeout(for: url)
        if url.scheme?.lowercased() == "http" {
            #if canImport(Network)
            let request = MempoolPlainRequest()
            return try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { request.start(url: url, accept: accept, timeout: timeout, continuation: $0) }
            } onCancel: { request.cancel() }
            #endif
        }
        return try await URLSessionTransport(maximumBytes: 256 * 1024, timeout: timeout).get(url, accept: accept)
    }
}

/// Incremental, bounded HTTP/1 response framing for public custom Bitcoin GETs.
/// Returns nil until a complete body is available; EOF never repairs truncation.
struct MempoolHTTPResponse {
    static let bodyLimit = 256 * 1024
    static let headerLimit = 32 * 1024
    static let wireLimit = bodyLimit * 2 + headerLimit
    static func body(_ wire: Data, eof: Bool) throws -> Data? {
        guard wire.count <= wireLimit else { throw MeetupCoreError.responseTooLarge(limit: bodyLimit) }
        guard let separator = wire.range(of: Data("\r\n\r\n".utf8)) else {
            guard wire.count <= headerLimit else { throw MeetupCoreError.responseTooLarge(limit: headerLimit) }
            if eof { throw MeetupCoreError.invalidResponse }; return nil
        }
        guard separator.lowerBound <= headerLimit,
              let header = String(data: wire[..<separator.lowerBound], encoding: .ascii) else { throw MeetupCoreError.invalidResponse }
        let lines = header.components(separatedBy: "\r\n")
        let status = (lines.first ?? "").split(separator: " ", omittingEmptySubsequences: false)
        guard status.count >= 2, ["HTTP/1.0", "HTTP/1.1"].contains(String(status[0])),
              status[1].count == 3, let code = Int(status[1]) else { throw MeetupCoreError.invalidResponse }
        // In particular, never follow redirects to a different endpoint.
        guard (200...299).contains(code) else { throw MeetupCoreError.httpStatus(code) }
        var fields: [String: [String]] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":"), colon != line.startIndex,
                  !line.hasPrefix(" "), !line.hasPrefix("\t") else { throw MeetupCoreError.invalidResponse }
            let name = String(line[..<colon]).lowercased()
            guard name.utf8.allSatisfy({ (97...122).contains($0) || (48...57).contains($0) || $0 == 45 }) else { throw MeetupCoreError.invalidResponse }
            let value = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
            guard value.utf8.allSatisfy({ $0 == 9 || (32...126).contains($0) }) else { throw MeetupCoreError.invalidResponse }
            fields[name, default: []].append(value)
        }
        guard fields["content-encoding"] == nil || fields["content-encoding"] == ["identity"],
              !(fields["content-length"] != nil && fields["transfer-encoding"] != nil) else { throw MeetupCoreError.invalidResponse }
        let data = Data(wire[separator.upperBound...])
        if let lengths = fields["content-length"] {
            guard lengths.count == 1, let text = lengths.first, !text.isEmpty,
                  text.utf8.allSatisfy({ (48...57).contains($0) }), let length = Int(text) else { throw MeetupCoreError.invalidResponse }
            guard length <= bodyLimit else { throw MeetupCoreError.responseTooLarge(limit: bodyLimit) }
            guard data.count <= length else { throw MeetupCoreError.invalidResponse }
            if data.count == length { return data }
            if eof { throw MeetupCoreError.invalidResponse }; return nil
        }
        if let encoding = fields["transfer-encoding"] {
            guard encoding.count == 1, encoding[0].lowercased() == "chunked" else { throw MeetupCoreError.invalidResponse }
            return try chunks(data, eof: eof)
        }
        guard data.count <= bodyLimit else { throw MeetupCoreError.responseTooLarge(limit: bodyLimit) }
        return eof ? data : nil
    }
    private static func chunks(_ data: Data, eof: Bool) throws -> Data? {
        var offset = 0, result = Data(), count = 0
        while offset < data.count {
            guard let end = data.range(of: Data("\r\n".utf8), in: offset..<data.count) else {
                if data.count - offset > 1024 || eof { throw MeetupCoreError.invalidResponse }; return nil
            }
            guard end.lowerBound - offset <= 1024,
                  let line = String(data: data[offset..<end.lowerBound], encoding: .ascii),
                  let sizeText = line.split(separator: ";", maxSplits: 1, omittingEmptySubsequences: false).first,
                  !sizeText.isEmpty, sizeText.count <= 8,
                  sizeText.utf8.allSatisfy({ (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0) }),
                  let size = Int(sizeText, radix: 16) else { throw MeetupCoreError.invalidResponse }
            offset = end.upperBound; count += 1
            guard count <= bodyLimit else { throw MeetupCoreError.responseTooLarge(limit: bodyLimit) }
            guard size <= bodyLimit - result.count else { throw MeetupCoreError.responseTooLarge(limit: bodyLimit) }
            if size == 0 {
                if data.count - offset >= 2, data[offset..<(offset + 2)] == Data("\r\n".utf8) {
                    guard offset + 2 == data.count else { throw MeetupCoreError.invalidResponse }; return result
                }
                guard data.count - offset <= headerLimit else { throw MeetupCoreError.responseTooLarge(limit: headerLimit) }
                if let trailers = data.range(of: Data("\r\n\r\n".utf8), in: offset..<data.count) {
                    guard trailers.upperBound == data.count else { throw MeetupCoreError.invalidResponse }; return result
                }
                if eof { throw MeetupCoreError.invalidResponse }; return nil
            }
            guard data.count - offset >= size + 2 else { if eof { throw MeetupCoreError.invalidResponse }; return nil }
            result.append(data[offset..<(offset + size)])
            guard data[(offset + size)..<(offset + size + 2)] == Data("\r\n".utf8) else { throw MeetupCoreError.invalidResponse }
            offset += size + 2
        }
        if eof { throw MeetupCoreError.invalidResponse }; return nil
    }
}

#if canImport(Network)
final class MempoolPlainRequest: @unchecked Sendable {
    private let queue = DispatchQueue(label: "space.einundzwanzig.mempool-http")
    private let lock = NSLock()
    private var connection: NWConnection?
    private var continuation: CheckedContinuation<Data, any Error>?
    private var cancelled = false
    // Buffer is accessed only on the serial connection queue.
    private var buffer = Data()
    func start(url: URL, accept: String, timeout: TimeInterval, continuation: CheckedContinuation<Data, any Error>) {
        guard let parts = URLComponents(url: url, resolvingAgainstBaseURL: false), let host = parts.host,
              let port = NWEndpoint.Port(rawValue: UInt16(parts.port ?? 80)),
              ["application/json", "text/plain"].contains(accept) else { continuation.resume(throwing: MeetupCoreError.invalidURL); return }
        let path = parts.percentEncodedPath.isEmpty ? "/" : parts.percentEncodedPath
        let target = path + (parts.percentEncodedQuery.map { "?" + $0 } ?? "")
        let hostHeader = host + (parts.port.map { ":\($0)" } ?? "")
        let request = Data("GET \(target) HTTP/1.1\r\nHost: \(hostHeader)\r\nAccept: \(accept)\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n".utf8)
        let connection = NWConnection(host: NWEndpoint.Host(host.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))), port: port, using: .tcp)
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection else { return }
            switch state {
            case .ready:
                connection.send(content: request, completion: .contentProcessed { [weak self] error in
                    if let error { self?.finish(.failure(error)) } else { self?.receive(connection) }
                })
            case .failed(let error): self.finish(.failure(error))
            case .cancelled: self.finish(.failure(CancellationError()))
            default: break
            }
        }
        lock.lock()
        if cancelled { lock.unlock(); connection.stateUpdateHandler = nil; connection.cancel(); continuation.resume(throwing: CancellationError()); return }
        self.connection = connection; self.continuation = continuation
        lock.unlock()
        queue.asyncAfter(deadline: .now() + timeout) { [weak self] in self?.finish(.failure(URLError(.timedOut))) }
        queue.async { [weak self, weak connection] in
            guard let self, let connection else { return }
            lock.lock(); let active = self.connection === connection && !cancelled; lock.unlock()
            if active { connection.start(queue: queue) }
        }
    }
    func cancel() {
        lock.lock(); cancelled = true; lock.unlock()
        // Start, cancellation, receive and timeout completion share one queue.
        queue.async { [weak self] in self?.finish(.failure(CancellationError())) }
    }
    private func receive(_ connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16 * 1024) { [weak self] data, _, complete, error in
            guard let self else { return }
            if let error { finish(.failure(error)); return }
            do {
                if let data { buffer.append(data) }
                if let body = try MempoolHTTPResponse.body(buffer, eof: complete) { finish(.success(body)) }
                else { receive(connection) }
            } catch { finish(.failure(error)) }
        }
    }
    private func finish(_ result: Result<Data, any Error>) {
        lock.lock()
        let continuation = self.continuation; self.continuation = nil
        let connection = self.connection; self.connection = nil
        lock.unlock()
        connection?.stateUpdateHandler = nil; connection?.cancel()
        continuation?.resume(with: result)
    }
}
#endif

#if canImport(Network)
import Foundation
import Network
import XCTest
@testable import MeetupCore

private final class MempoolLoopbackServer: @unchecked Sendable {
    private let queue = DispatchQueue(label: "fixture.mempool-loopback")
    private let lock = NSLock()
    private let listener: NWListener
    private var connections: [NWConnection] = []
    private var requestBytes = Data()
    private var startContinuation: CheckedContinuation<URL, any Error>?
    private let response: Data?
    init(response: String?) throws {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: parameters)
        self.response = response.map { Data($0.utf8) }
    }
    func start() async throws -> URL {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<URL, any Error>) in
            lock.lock(); startContinuation = continuation; lock.unlock()
            listener.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                switch state {
                case .ready:
                    guard let port = listener.port else { return }
                    finishStart(.success(URL(string: "http://127.0.0.1:\(port.rawValue)")!))
                case .failed(let error): finishStart(.failure(error))
                default: break
                }
            }
            listener.newConnectionHandler = { [weak self] connection in
                guard let self else { connection.cancel(); return }
                lock.lock(); connections.append(connection); lock.unlock()
                connection.start(queue: queue)
                receive(connection)
            }
            queue.asyncAfter(deadline: .now() + 2) { [weak self] in self?.finishStart(.failure(URLError(.timedOut))) }
            listener.start(queue: queue)
        }
    }
    private func finishStart(_ result: Result<URL, any Error>) {
        lock.lock(); let continuation = startContinuation; startContinuation = nil; lock.unlock()
        continuation?.resume(with: result)
    }
    private func receive(_ connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16384) { [weak self] data, _, complete, error in
            guard let self else { return }
            if let data { lock.lock(); requestBytes.append(data); let request = requestBytes; lock.unlock()
                if request.range(of: Data("\r\n\r\n".utf8)) != nil {
                    if let response { connection.send(content: response, completion: .contentProcessed { _ in connection.send(content: nil, contentContext: .finalMessage, isComplete: true, completion: .idempotent) }) }
                    return
                }
            }
            if !complete, error == nil { receive(connection) }
        }
    }
    func request() -> String { lock.lock(); defer { lock.unlock() }; return String(decoding: requestBytes, as: UTF8.self) }
    func stop() { listener.cancel(); lock.lock(); let active = connections; connections = []; lock.unlock(); for connection in active { connection.cancel() } }
}
final class MempoolNetworkTests: XCTestCase, @unchecked Sendable {
    func testActualNetworkAdapterReadsChunkedGETWithoutCredentials() async throws {
        let server = try MempoolLoopbackServer(response: "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n42\r\n0\r\n\r\n")
        defer { server.stop() }
        let url = try await server.start().appendingPathComponent("api/blocks/tip/height")
        let result = try await MempoolHTTPTransport().get(url, accept: "text/plain")
        XCTAssertEqual(result, Data("42".utf8))
        let request = server.request()
        XCTAssertTrue(request.hasPrefix("GET /api/blocks/tip/height HTTP/1.1\r\n"))
        XCTAssertFalse(request.contains("Authorization:")); XCTAssertFalse(request.contains("Cookie:"))
    }
    func testActualNetworkAdapterRejectsTruncationAndRedirect() async throws {
        for response in ["HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhi", "HTTP/1.1 302 Found\r\nLocation: http://foreign.invalid\r\n\r\n"] {
            let server = try MempoolLoopbackServer(response: response)
            defer { server.stop() }
            let url = try await server.start()
            do { _ = try await MempoolHTTPTransport().get(url, accept: "text/plain"); XCTFail("Invalid response accepted") } catch { XCTAssertTrue(error is MeetupCoreError) }
        }
    }
    func testActualNetworkTimeoutAndCancellationResolveExactlyOnce() async throws {
        let server = try MempoolLoopbackServer(response: nil)
        defer { server.stop() }
        let url = try await server.start()
        let operation = MempoolPlainRequest()
        do {
            _ = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, any Error>) in operation.start(url: url, accept: "text/plain", timeout: 0.05, continuation: continuation) }
            XCTFail("Held server response should time out")
        } catch { XCTAssertEqual((error as? URLError)?.code, .timedOut) }
        for _ in 0..<10 {
            let cancelled = MempoolPlainRequest()
            let task = Task {
                try await withTaskCancellationHandler {
                    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, any Error>) in cancelled.start(url: url, accept: "text/plain", timeout: 1, continuation: continuation) }
                } onCancel: { cancelled.cancel() }
            }
            task.cancel()
            do { _ = try await task.value; XCTFail("Cancelled operation succeeded") } catch { XCTAssertTrue(error is CancellationError) }
        }
    }
}
#endif

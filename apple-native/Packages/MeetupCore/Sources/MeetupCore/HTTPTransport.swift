import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum MeetupCoreError: Error, Equatable, Sendable, LocalizedError {
    case invalidURL
    case invalidResponse
    case httpStatus(Int)
    case responseTooLarge(limit: Int)
    case invalidData(String)

    public var errorDescription: String? {
        switch self {
        case .invalidURL: "A valid HTTP or HTTPS URL without credentials is required."
        case .invalidResponse: "The server did not return a valid HTTP response."
        case .httpStatus(let status): "The server returned HTTP \(status)."
        case .responseTooLarge(let limit): "The response exceeded the \(limit)-byte limit."
        case .invalidData(let detail): "The server returned invalid data: \(detail)"
        }
    }
}

protocol HTTPTransport: Sendable {
    func get(_ url: URL, accept: String) async throws -> Data
}

struct URLSessionTransport: HTTPTransport {
    let maximumBytes: Int
    private let configuration: @Sendable () -> URLSessionConfiguration
    init(maximumBytes: Int = 4 * 1024 * 1024,
         configuration: @escaping @Sendable () -> URLSessionConfiguration = { .ephemeral }) {
        self.maximumBytes = maximumBytes
        self.configuration = configuration
    }

    func get(_ url: URL, accept: String) async throws -> Data {
        guard SafeURL.web(url.absoluteString) != nil else { throw MeetupCoreError.invalidURL }
        let request = BoundedRequest(maximumBytes: maximumBytes)
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                request.start(url: url, accept: accept, configuration: configuration(), continuation: continuation)
            }
        } onCancel: {
            request.cancel()
        }
    }
}

/// URLSession's convenience data API buffers the entire body. This delegate caps it while receiving.
/// All mutable state is guarded by the lock; delegate callbacks and task cancellation may race.
private final class BoundedRequest: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private let maximumBytes: Int
    private var body = Data()
    private var continuation: CheckedContinuation<Data, any Error>?
    private var session: URLSession?
    private var task: URLSessionDataTask?
    private var finished = false
    private var cancelled = false

    init(maximumBytes: Int) { self.maximumBytes = maximumBytes }

    func start(url: URL, accept: String, configuration: URLSessionConfiguration,
               continuation: CheckedContinuation<Data, any Error>) {
        lock.lock()
        if cancelled {
            lock.unlock()
            continuation.resume(throwing: CancellationError())
            return
        }
        self.continuation = continuation
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 15
        configuration.httpCookieStorage = nil
        configuration.urlCredentialStorage = nil
        configuration.urlCache = nil
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
        request.httpMethod = "GET"
        request.setValue(accept, forHTTPHeaderField: "Accept")
        request.setValue("Einundzwanzig-Apple-Prototype/0.1", forHTTPHeaderField: "User-Agent")
        let task = session.dataTask(with: request)
        self.session = session
        self.task = task
        lock.unlock()
        task.resume()
    }

    func cancel() {
        lock.lock()
        cancelled = true
        lock.unlock()
        finish(.failure(CancellationError()))
    }

    private func finish(_ result: Result<Data, any Error>) {
        lock.lock()
        guard !finished, let continuation else { lock.unlock(); return }
        finished = true
        self.continuation = nil
        let session = self.session
        self.session = nil
        self.task = nil
        lock.unlock()
        session?.invalidateAndCancel()
        continuation.resume(with: result)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask,
                    didReceive response: URLResponse,
                    completionHandler: @escaping @Sendable (URLSession.ResponseDisposition) -> Void) {
        guard let http = response as? HTTPURLResponse else {
            completionHandler(.cancel); finish(.failure(MeetupCoreError.invalidResponse)); return
        }
        guard (200...299).contains(http.statusCode) else {
            completionHandler(.cancel); finish(.failure(MeetupCoreError.httpStatus(http.statusCode))); return
        }
        guard response.expectedContentLength <= Int64(maximumBytes) else {
            completionHandler(.cancel)
            finish(.failure(MeetupCoreError.responseTooLarge(limit: maximumBytes))); return
        }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        guard !finished else { lock.unlock(); return }
        guard data.count <= maximumBytes - body.count else {
            lock.unlock(); finish(.failure(MeetupCoreError.responseTooLarge(limit: maximumBytes))); return
        }
        body.append(data)
        lock.unlock()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: (any Error)?) {
        lock.lock()
        let data = body
        lock.unlock()
        if let error { finish(.failure(error)) } else { finish(.success(data)) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        guard let url = request.url, SafeURL.web(url.absoluteString) != nil,
              !(response.url?.scheme?.lowercased() == "https" && url.scheme?.lowercased() == "http") else {
            completionHandler(nil); finish(.failure(MeetupCoreError.invalidURL)); return
        }
        completionHandler(request)
    }
}

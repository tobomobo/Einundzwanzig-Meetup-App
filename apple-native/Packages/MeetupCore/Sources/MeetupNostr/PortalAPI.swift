import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum JSONValue: Codable, Sendable, Equatable {
    case string(String), number(Double), bool(Bool), object([String: JSONValue]), array([JSONValue]), null
    public init(from decoder: any Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let x = try? c.decode(Bool.self) { self = .bool(x) }
        else if let x = try? c.decode(String.self) { self = .string(x) }
        else if let x = try? c.decode(Double.self) { self = .number(x) }
        else if let x = try? c.decode([String: JSONValue].self) { self = .object(x) }
        else { self = .array(try c.decode([JSONValue].self)) }
    }
    public func encode(to encoder: any Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .string(let x): try c.encode(x)
        case .number(let x): try c.encode(x)
        case .bool(let x): try c.encode(x)
        case .object(let x): try c.encode(x)
        case .array(let x): try c.encode(x)
        case .null: try c.encodeNil()
        }
    }
    public var string: String? { if case .string(let x) = self { return x }; return nil }
    public var integer: Int? { if case .number(let x) = self { return Int(exactly: x) }; return nil }
    public var object: [String: JSONValue]? { if case .object(let x) = self { return x }; return nil }
    public var array: [JSONValue]? { if case .array(let x) = self { return x }; return nil }
    public var boolean: Bool? { if case .bool(let x) = self { return x }; return nil }
    public subscript(_ key: String) -> JSONValue { object?[key] ?? .null }
    public var unwrapped: JSONValue { object?["data"] ?? self }
}

public struct ProductHTTPResponse: Sendable { public let status: Int; public let data: Data
    public init(status: Int, data: Data) { self.status = status; self.data = data }
}
public protocol ProductHTTPTransport: Sendable { func send(_ request: URLRequest) async throws -> ProductHTTPResponse }

/// No redirects: an authorization header must never follow a different origin.
public struct ProductURLSessionTransport: ProductHTTPTransport {
    public init() {}
    public func send(_ request: URLRequest) async throws -> ProductHTTPResponse {
        let operation = ProductHTTPRequest()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { operation.start(request, continuation: $0) }
        } onCancel: { operation.cancel() }
    }
}
private final class ProductHTTPRequest: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<ProductHTTPResponse, any Error>?
    private var session: URLSession?
    private var status = 0
    private var data = Data()
    private var cancelled = false
    func start(_ request: URLRequest, continuation: CheckedContinuation<ProductHTTPResponse, any Error>) {
        lock.lock()
        if cancelled { lock.unlock(); continuation.resume(throwing: CancellationError()); return }
        self.continuation = continuation
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 15; config.timeoutIntervalForResource = 20
        config.httpCookieStorage = nil; config.urlCredentialStorage = nil; config.urlCache = nil
        let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
        self.session = session
        lock.unlock()
        session.dataTask(with: request).resume()
    }
    func cancel() { lock.lock(); cancelled = true; lock.unlock(); finish(.failure(CancellationError())) }
    private func finish(_ result: Result<ProductHTTPResponse, any Error>) {
        lock.lock()
        let continuation = self.continuation; self.continuation = nil
        let session = self.session; self.session = nil
        lock.unlock()
        session?.invalidateAndCancel(); continuation?.resume(with: result)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping @Sendable (URLSession.ResponseDisposition) -> Void) {
        guard let http = response as? HTTPURLResponse, response.expectedContentLength <= 4 * 1024 * 1024 else {
            completionHandler(.cancel); finish(.failure(PortalAPIError.invalidResponse)); return
        }
        lock.lock(); status = http.statusCode; lock.unlock(); completionHandler(.allow)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive chunk: Data) {
        lock.lock()
        if chunk.count > 4 * 1024 * 1024 - data.count {
            lock.unlock(); finish(.failure(PortalAPIError.invalidResponse)); return
        }
        data.append(chunk); lock.unlock()
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: (any Error)?) {
        lock.lock(); let result = ProductHTTPResponse(status: status, data: data); lock.unlock()
        if let error { finish(.failure(error)) } else { finish(.success(result)) }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        completionHandler(nil); finish(.failure(PortalAPIError.redirectRefused))
    }
}

public enum PortalAPIError: Error, Sendable, Equatable, LocalizedError {
    case invalidResponse, invalidInput, identityMismatch, redirectRefused, http(Int, String)
    public var errorDescription: String? {
        switch self {
        case .invalidResponse: "The portal returned an invalid response."
        case .invalidInput: "Check the event fields and dates."
        case .identityMismatch: "The portal session belongs to a different signing identity."
        case .redirectRefused: "The authenticated request was redirected and has been stopped."
        case .http(let code, let message): "Portal HTTP \(code): \(message)"
        }
    }
}
public struct PortalSession: Codable, Sendable, Equatable {
    public let token: String
    public let publicKey: String
    public init(token: String, publicKey: String) throws {
        _ = try NostrHex.decode(publicKey, byteCount: 32, field: "portal identity")
        guard !token.isEmpty, token.utf8.count <= 4096, !token.contains(where: { $0.isNewline }) else { throw PortalAPIError.invalidInput }
        self.token = token; self.publicKey = publicKey
    }
}
public struct ManagedMeetup: Identifiable, Sendable, Equatable {
    public let id: Int; public let name: String; public let slug: String; public let isLeader: Bool
    init?(_ value: JSONValue) {
        guard let id = value["id"].integer else { return nil }
        self.id = id; name = value["name"].string ?? ""; slug = value["slug"].string ?? ""; isLeader = value["is_leader"].boolean == true
    }
}
public struct PortalEventDraft: Codable, Sendable {
    public var meetupID: Int
    public var start: String
    public var location: String?
    public var description: String?
    public var link: String?
    public var recurrenceType: String?
    public var recurrenceDayOfWeek: String?
    public var recurrenceDayPosition: String?
    public var recurrenceInterval: Int?
    public var recurrenceEndDate: String?
    public init(meetupID: Int, start: String, location: String? = nil, description: String? = nil, link: String? = nil,
                recurrenceType: String? = nil, recurrenceDayOfWeek: String? = nil, recurrenceDayPosition: String? = nil,
                recurrenceInterval: Int? = nil, recurrenceEndDate: String? = nil) {
        self.meetupID = meetupID; self.start = start; self.location = location; self.description = description; self.link = link
        self.recurrenceType = recurrenceType; self.recurrenceDayOfWeek = recurrenceDayOfWeek
        self.recurrenceDayPosition = recurrenceDayPosition; self.recurrenceInterval = recurrenceInterval; self.recurrenceEndDate = recurrenceEndDate
    }
    enum CodingKeys: String, CodingKey {
        case meetupID = "meetup_id", start, location, description, link, recurrenceType = "recurrence_type"
        case recurrenceDayOfWeek = "recurrence_day_of_week", recurrenceDayPosition = "recurrence_day_position"
        case recurrenceInterval = "recurrence_interval", recurrenceEndDate = "recurrence_end_date"
    }
    func validate() throws {
        guard meetupID > 0, ISO8601DateFormatter().date(from: start) != nil,
              location?.utf8.count ?? 0 <= 4096, description?.utf8.count ?? 0 <= 65536,
              recurrenceType == nil || ["daily", "weekly", "monthly", "yearly", "custom"].contains(recurrenceType!),
              recurrenceInterval == nil || (1...100).contains(recurrenceInterval!) else { throw PortalAPIError.invalidInput }
        if let link, !link.isEmpty { guard let url = URL(string: link), ["https", "http"].contains(url.scheme), url.host != nil, url.user == nil else { throw PortalAPIError.invalidInput } }
        if let recurrenceEndDate { guard let end = ISO8601DateFormatter().date(from: recurrenceEndDate), let start = ISO8601DateFormatter().date(from: start), end >= start else { throw PortalAPIError.invalidInput } }
    }
}

public actor PortalAPIClient {
    private let transport: any ProductHTTPTransport
    private let baseURL: URL
    private let crypto = NostrCrypto()
    public init(baseURL: URL = URL(string: "https://portal.einundzwanzig.space/api")!, transport: any ProductHTTPTransport = ProductURLSessionTransport()) {
        self.baseURL = baseURL; self.transport = transport
    }
    public func login(signer: any NostrSigner, deviceName: String = "Einundzwanzig Apple") async throws -> PortalSession {
        let key = try await signer.publicKey()
        var rng = SystemRandomNumberGenerator()
        let challenge = NostrHex.encode(Data((0..<32).map { _ in UInt8.random(in: .min ... .max, using: &rng) }))
        let now = Int(Date().timeIntervalSince1970)
        let event = try await signer.sign(kind: 22242, tags: [["challenge", challenge]], content: "", createdAt: now)
        guard event.pubkey == key, event.kind == 22242, event.tags == [["challenge", challenge]], event.content.isEmpty,
              event.createdAt == now, await crypto.verify(event) else { throw PortalAPIError.identityMismatch }
        let body: JSONValue = .object(["k1": .string(challenge), "event": try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(event)), "device_name": .string(deviceName)])
        let response = try await request("POST", "mobile/token", body: body)
        guard let token = response["token"].string, key == (try await signer.publicKey()) else { throw PortalAPIError.invalidResponse }
        return try PortalSession(token: token, publicKey: key)
    }
    public func profile(_ session: PortalSession) async throws -> JSONValue { try await request("GET", "user", session: session) }
    public func managedMeetups(_ session: PortalSession) async throws -> [ManagedMeetup] {
        try await request("GET", "my-meetups", session: session).unwrapped.array?.compactMap(ManagedMeetup.init) ?? []
    }
    public func managedEvents(_ session: PortalSession) async throws -> [JSONValue] { try await request("GET", "my-meetup-events", session: session).unwrapped.array ?? [] }
    public func saveEvent(_ draft: PortalEventDraft, eventID: Int? = nil, session: PortalSession) async throws -> JSONValue {
        try draft.validate()
        if let eventID, eventID <= 0 { throw PortalAPIError.invalidInput }
        let body = try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(draft))
        return try await request(eventID == nil ? "POST" : "PATCH", eventID.map { "meetup-events/\($0)" } ?? "meetup-events", session: session, body: body)
    }
    public func leaders(meetupID: Int, session: PortalSession) async throws -> [JSONValue] { try await request("GET", "meetup/\(meetupID)/leaders", session: session).unwrapped.array ?? [] }
    public func addLeader(meetupID: Int, npub: String, session: PortalSession) async throws {
        _ = try NIP19.decodePublicKey(npub)
        _ = try await request("POST", "meetup/\(meetupID)/leaders", session: session, body: .object(["npub": .string(npub)]))
    }
    public func removeLeader(meetupID: Int, userID: Int, session: PortalSession) async throws {
        guard meetupID > 0, userID > 0 else { throw PortalAPIError.invalidInput }
        _ = try await request("DELETE", "meetup/\(meetupID)/leaders/\(userID)", session: session)
    }
    public func rsvp(eventID: Int, status: String? = nil, session: PortalSession?) async throws -> JSONValue {
        guard eventID > 0, status == nil || ["attending", "maybe", "none"].contains(status!) else { throw PortalAPIError.invalidInput }
        return try await request(status == nil ? "GET" : "POST", "meetup-events/\(eventID)/rsvp", session: session,
                                 body: status.map { .object(["status": .string($0)]) })
    }
    public func logout(_ session: PortalSession) async throws { _ = try await request("DELETE", "mobile/token", session: session) }
    private func request(_ method: String, _ path: String, session: PortalSession? = nil, body: JSONValue? = nil) async throws -> JSONValue {
        guard baseURL.scheme == "https", baseURL.host != nil, baseURL.user == nil, baseURL.password == nil,
              !path.contains(".."), !path.hasPrefix("/") else { throw PortalAPIError.invalidInput }
        var request = URLRequest(url: baseURL.appendingPathComponent(path), timeoutInterval: 15)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let session { request.setValue("Bearer \(session.token)", forHTTPHeaderField: "Authorization") }
        if let body { request.httpBody = try JSONEncoder().encode(body); request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let response = try await transport.send(request)
        guard (200...299).contains(response.status) else {
            // Never surface arbitrary server response bodies, which can echo tokens.
            throw PortalAPIError.http(response.status, response.status == 401 ? "Sign in again." : "Request failed.")
        }
        if response.data.isEmpty { return .null }
        return try JSONDecoder().decode(JSONValue.self, from: response.data)
    }
}

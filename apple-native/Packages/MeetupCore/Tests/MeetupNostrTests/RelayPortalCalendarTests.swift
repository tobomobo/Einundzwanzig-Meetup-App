import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@testable import MeetupNostr

private final class FixtureSocket: NostrSocket, @unchecked Sendable {
    private let stream: AsyncThrowingStream<Data, any Error>
    private let continuation: AsyncThrowingStream<Data, any Error>.Continuation
    private let handler: @Sendable (Data) async throws -> [Data]
    init(handler: @escaping @Sendable (Data) async throws -> [Data]) {
        let pair = AsyncThrowingStream<Data, any Error>.makeStream()
        stream = pair.stream; continuation = pair.continuation; self.handler = handler
    }
    func send(_ data: Data) async throws { for response in try await handler(data) { continuation.yield(response) } }
    func receive() async throws -> Data {
        var iterator = stream.makeAsyncIterator()
        guard let next = try await iterator.next() else { throw CancellationError() }; return next
    }
    func close() { continuation.finish() }
}
private actor FixtureRelayScript {
    let valid: NostrEvent
    let other: NostrEvent
    var authenticated = false
    var authentication: NostrEvent?
    init(valid: NostrEvent, other: NostrEvent) { self.valid = valid; self.other = other }
    func reply(_ data: Data) async throws -> [Data] {
        let parts = try JSONSerialization.jsonObject(with: data) as! [Any]
        let kind = parts[0] as! String
        if kind == "AUTH" {
            let event = try NostrEvent.decodeJSON(JSONSerialization.data(withJSONObject: parts[1]))
            guard await NostrCrypto().verify(event) else { throw NostrRelayError.invalidFrame }
            authentication = event; authenticated = true
            return [try JSONSerialization.data(withJSONObject: ["OK", event.id, true, ""])]
        }
        if kind == "REQ", let id = parts[1] as? String {
            if !authenticated { return [Data("[\"AUTH\",\"fixture-challenge\"]".utf8), try JSONSerialization.data(withJSONObject: ["CLOSED", id, "auth-required: login"])] }
            var tampered = try JSONSerialization.jsonObject(with: JSONEncoder().encode(valid)) as! [String: Any]
            tampered["content"] = "forged"
            return [try JSONSerialization.data(withJSONObject: ["EVENT", id, tampered]),
                    try frame(other, id: id), try frame(valid, id: id),
                    try JSONSerialization.data(withJSONObject: ["EOSE", id])]
        }
        if kind == "EVENT" {
            let event = try NostrEvent.decodeJSON(JSONSerialization.data(withJSONObject: parts[1]))
            if !authenticated { return [Data("[\"AUTH\",\"fixture-challenge\"]".utf8), try JSONSerialization.data(withJSONObject: ["OK", event.id, false, "auth-required: login"])] }
            return [try JSONSerialization.data(withJSONObject: ["OK", event.id, true, ""])]
        }
        return []
    }
    private func frame(_ event: NostrEvent, id: String) throws -> Data {
        try JSONSerialization.data(withJSONObject: ["EVENT", id, JSONSerialization.jsonObject(with: JSONEncoder().encode(event))])
    }
}
private actor FixtureHTTP: ProductHTTPTransport {
    var requests: [URLRequest] = []
    let handler: @Sendable (URLRequest) async throws -> ProductHTTPResponse
    init(handler: @escaping @Sendable (URLRequest) async throws -> ProductHTTPResponse) { self.handler = handler }
    func send(_ request: URLRequest) async throws -> ProductHTTPResponse { requests.append(request); return try await handler(request) }
}
private actor CapturingRelay: NostrRelayTransport {
    var published: [NostrEvent] = []
    var events: [NostrEvent]
    init(events: [NostrEvent] = []) { self.events = events }
    func query(_ filter: NostrFilter, relays: [URL], timeout: TimeInterval) -> [NostrEvent] { events }
    func publish(_ event: NostrEvent, relays: [URL], signer: (any NostrSigner)?) { published.append(event) }
}
@Suite struct RelayPortalCalendarTests {
    private var relayURL: URL { URL(string: "wss://fixture.invalid")! }
    private func signer(_ n: UInt8 = 1) throws -> LocalIdentitySigner { try LocalIdentitySigner(privateKey: Data(repeating: n, count: 32)) }

    @Test func authenticatedRelayRejectsForgeryAndEventsOutsideRequestedFilter() async throws {
        let signer = try signer()
        let now = Int(Date().timeIntervalSince1970)
        let valid = try await signer.sign(kind: 9, tags: [["h", "vienna"]], content: "hello", createdAt: now)
        let other = try await signer.sign(kind: 9, tags: [["h", "different-room"]], content: "unrelated", createdAt: now)
        let script = FixtureRelayScript(valid: valid, other: other)
        let client = NostrRelayClient { _ in FixtureSocket { try await script.reply($0) } }
        await client.setSigner(signer)
        let results = try await client.query(NostrFilter(kinds: [9], tags: ["h": ["vienna"]]), relays: [relayURL], timeout: 2)
        #expect(results == [valid])
        let authentication = await script.authentication
        #expect(authentication?.kind == 22242)
        #expect(authentication?.tags == [["relay", relayURL.absoluteString], ["challenge", "fixture-challenge"]])
        try await client.publish(valid, relays: [relayURL], signer: signer)
    }
    @Test func relayClosesOnTimeoutAndRejectsInsecureEndpoint() async throws {
        let client = NostrRelayClient { _ in FixtureSocket { _ in [] } }
        await #expect(throws: NostrRelayError.self) { try await client.query(NostrFilter(kinds: [1]), relays: [relayURL], timeout: 0.1) }
        await #expect(throws: NostrRelayError.invalidRelay) {
            try await client.query(NostrFilter(), relays: [URL(string: "ws://fixture.invalid")!], timeout: 1)
        }
    }
    @Test func repeatedAuthWithoutAcknowledgementHasIndependentSigningBudget() async throws {
        let signer = try signer()
        let flood = AuthenticationFlood()
        let client = NostrRelayClient { _ in FixtureSocket { try await flood.reply($0) } }
        await client.setSigner(signer)
        await #expect(throws: NostrRelayError.self) {
            try await client.query(NostrFilter(kinds: [1]), relays: [relayURL], timeout: 2)
        }
        #expect(await flood.authCount == 2)
    }
    @Test func portalChallengeMatchesFlutterAndTokenBindsToSigningKey() async throws {
        let signer = try signer()
        let key = try await signer.publicKey()
        let transport = FixtureHTTP { request in
            #expect(request.url?.path == "/api/mobile/token")
            #expect(request.httpMethod == "POST")
            #expect(request.value(forHTTPHeaderField: "Authorization") == nil)
            let body = try JSONDecoder().decode(JSONValue.self, from: request.httpBody!)
            let event = try NostrEvent.decodeJSON(JSONEncoder().encode(body["event"]))
            #expect(event.kind == 22242)
            #expect(event.tags == [["challenge", body["k1"].string!]])
            #expect(body["k1"].string?.count == 64)
            #expect(await NostrCrypto().verify(event))
            return ProductHTTPResponse(status: 201, data: Data("{\"token\":\"fixture-only-token\",\"user\":{}}".utf8))
        }
        let session = try await PortalAPIClient(transport: transport).login(signer: signer)
        #expect(session.publicKey == key)
        #expect(session.token == "fixture-only-token")
    }
    @Test func portalWritesUseSpecificFlutterEndpointsAndOmitNilRecurrence() async throws {
        let key = try await signer().publicKey()
        let session = try PortalSession(token: "fixture-token", publicKey: key)
        let transport = FixtureHTTP { request in
            #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer fixture-token")
            if request.httpMethod == "PATCH" {
                #expect(request.url?.path == "/api/meetup-events/42")
                let body = try JSONDecoder().decode(JSONValue.self, from: request.httpBody!)
                #expect(body["meetup_id"].integer == 7)
                #expect(body.object?["recurrence_type"] == nil)
            } else if request.httpMethod == "POST" {
                #expect(request.url?.path == "/api/meetup-events/42/rsvp")
                #expect(try JSONDecoder().decode(JSONValue.self, from: request.httpBody!)["status"].string == "attending")
            }
            return ProductHTTPResponse(status: 200, data: Data("{}".utf8))
        }
        let api = PortalAPIClient(transport: transport)
        _ = try await api.saveEvent(PortalEventDraft(meetupID: 7, start: "2026-10-25T18:00:00Z"), eventID: 42, session: session)
        _ = try await api.rsvp(eventID: 42, status: "attending", session: session)
        #expect(await transport.requests.count == 2)
    }
    @Test func calendarAndCommentsAreSignedAndEditingRequiresOwnership() async throws {
        let signer = try self.signer(), different = try self.signer(2)
        let relay = CapturingRelay()
        let service = CalendarChatService(relay: relay)
        let draft = CalendarEventDraft(title: "Vienna", start: Date(timeIntervalSince1970: 1_800_000_000), end: Date(timeIntervalSince1970: 1_800_007_200), location: "Meetup", identifier: "fixture-id")
        let entry = try await service.publish(draft, signer: signer, relays: [relayURL])
        #expect(entry.event.kind == 31923)
        #expect(entry.event.tags.contains(["D", "20833"]))
        try await service.send(text: "hello", entry: entry, signer: signer, relays: [relayURL])
        let comment = try #require(await relay.published.last)
        #expect(comment.kind == 1111)
        #expect(comment.tags.contains(["A", entry.id]) && comment.tags.contains(["a", entry.id]))
        try await service.rsvp(entry, status: "accepted", signer: signer, relays: [relayURL])
        #expect(await relay.published.last?.kind == 31925)
        await #expect(throws: CalendarChatError.notAuthor) { try await service.cancel(entry, signer: different, relays: [relayURL]) }
        #expect(await relay.published.count == 3)
    }
    @Test func malformedCivilDateCannotBecomeCalendarEntry() async throws {
        let signer = try signer()
        let event = try await signer.sign(kind: 31922, tags: [["d", "x"], ["title", "x"], ["start", "2026-02-30"]], content: "", createdAt: 100)
        #expect(throws: CalendarChatError.invalidEvent) { try NostrCalendarEntry(event: event) }
    }
}

private actor AuthenticationFlood {
    var authCount = 0
    func reply(_ data: Data) throws -> [Data] {
        let members = try JSONSerialization.jsonObject(with: data) as! [Any]
        if members[0] as? String == "AUTH" { authCount += 1 }
        return [try JSONSerialization.data(withJSONObject: ["AUTH", "challenge-" + String(authCount)])]
    }
}

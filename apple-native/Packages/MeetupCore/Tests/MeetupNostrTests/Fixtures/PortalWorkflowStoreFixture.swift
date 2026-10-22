import Foundation
import Observation
import Testing
import MeetupCore
import MeetupNostr
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

private enum WorkflowParityStorage {
    static let values = WorkflowParityKeychain()
}
private final class WorkflowParityKeychain: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String: Data] = [:]
    func read(_ key: String) -> Data? { lock.withLock { values[key] } }
    func write(_ data: Data, _ key: String) { lock.withLock { values[key] = data } }
    func delete(_ key: String) { lock.withLock { _ = values.removeValue(forKey: key) } }
    func reset() { lock.withLock { values = [:] } }
}
enum KeychainStore {
    static func read(account: String) throws -> Data? { WorkflowParityStorage.values.read(account) }
    static func write(_ data: Data, account: String) throws { WorkflowParityStorage.values.write(data, account) }
    static func delete(account: String) throws { WorkflowParityStorage.values.delete(account) }
}
// The app-owned persistence seam is defined in BadgeStore.swift; the harness
// exercises WorkflowStore only, with the same protocol signature.
@MainActor protocol BadgeWalletPersistence {
    func read() throws -> Data?
    func write(_ data: Data) throws
    func delete() throws
}
enum BadgeStoreError: Error { case noSigner }
enum UITestFixtureMode { static let enabled = false }

private actor WorkflowParityHTTP: ProductHTTPTransport {
    var requests: [URLRequest] = []
    var rsvpWaiters: [Int: CheckedContinuation<ProductHTTPResponse, any Error>] = [:]
    var rsvpReads = 0
    var shouldSuspendRSVP = false
    var rejectWrites = false
    var expireWrites = false
    var rsvpStatus = "none"
    func suspendRSVP() { shouldSuspendRSVP = true }
    func failWrites() { rejectWrites = true }
    func expireToken() { expireWrites = true }
    func send(_ request: URLRequest) async throws -> ProductHTTPResponse {
        requests.append(request)
        let path = request.url!.path
        if request.httpMethod == "POST" || request.httpMethod == "PATCH" {
            if expireWrites { return ProductHTTPResponse(status: 401, data: Data(#"{"message":"fixture-token-must-not-leak"}"#.utf8)) }
            if rejectWrites { return ProductHTTPResponse(status: 422, data: Data(#"{"message":"fixture-token-must-not-leak"}"#.utf8)) }
            if path.hasSuffix("/rsvp"), let body = request.httpBody { rsvpStatus = try JSONDecoder().decode(JSONValue.self, from: body)["status"].string ?? "none" }
            return ProductHTTPResponse(status: 200, data: Data("{}".utf8))
        }
        if path == "/api/user" { return ProductHTTPResponse(status: 200, data: Data(#"{"nostr":""}"#.utf8)) }
        if path.hasSuffix("/rsvp") {
            rsvpReads += 1
            if shouldSuspendRSVP {
                let id = rsvpReads
                return try await withCheckedThrowingContinuation { rsvpWaiters[id] = $0 }
            }
            return response(count: rsvpStatus == "none" ? 0 : 1, status: rsvpStatus)
        }
        return ProductHTTPResponse(status: 200, data: Data("[]".utf8))
    }
    func finishRSVP(_ id: Int, count: Int, status: String) { rsvpWaiters.removeValue(forKey: id)?.resume(returning: response(count: count, status: status)) }
    private func response(count: Int, status: String) -> ProductHTTPResponse {
        ProductHTTPResponse(status: 200, data: Data("{\"data\":{\"count\":\(count),\"status\":\"\(status)\",\"attendees_public\":false}}".utf8))
    }
}
private final class WorkflowParitySocket: NostrSocket, @unchecked Sendable {
    private let stream: AsyncThrowingStream<Data, any Error>
    private let continuation: AsyncThrowingStream<Data, any Error>.Continuation
    private let events: [NostrEvent]
    init(events: [NostrEvent]) {
        let pair = AsyncThrowingStream<Data, any Error>.makeStream()
        stream = pair.stream; continuation = pair.continuation; self.events = events
    }
    func send(_ data: Data) async throws {
        let frame = try JSONSerialization.jsonObject(with: data) as! [Any]
        guard frame[0] as? String == "REQ", let subscription = frame[1] as? String else { return }
        for event in events {
            continuation.yield(try JSONSerialization.data(withJSONObject: ["EVENT", subscription, JSONSerialization.jsonObject(with: JSONEncoder().encode(event))]))
        }
        continuation.yield(try JSONSerialization.data(withJSONObject: ["EOSE", subscription]))
    }
    func receive() async throws -> Data { var iterator = stream.makeAsyncIterator(); guard let data = try await iterator.next() else { throw CancellationError() }; return data }
    func close() { continuation.finish() }
}

@Suite(.serialized) @MainActor struct PortalWorkflowStoreParityTests {
    private func store(_ http: WorkflowParityHTTP, events: [NostrEvent] = []) async throws -> WorkflowStore {
        WorkflowParityStorage.values.reset()
        let workflow = WorkflowStore(relay: NostrRelayClient { _ in WorkflowParitySocket(events: events) }, transport: http)
        await workflow.configure(signer: try LocalIdentitySigner(privateKey: Data(repeating: 1, count: 32)))
        await workflow.login(manualToken: "fixture-token")
        #expect(workflow.session != nil)
        return workflow
    }
    private func waitForReads(_ number: Int, _ http: WorkflowParityHTTP) async throws {
        for _ in 0..<1_000 {
            if await http.rsvpReads >= number { return }
            try await Task.sleep(for: .milliseconds(1))
        }
        Issue.record("Fixture did not enter the expected RSVP read")
    }
    @Test func failedPortalEditPreservesErrorAndDoesNotRefreshAwayFailure() async throws {
        let http = WorkflowParityHTTP(), workflow = try await store(http)
        await http.failWrites()
        let saved = await workflow.savePortalEvent(PortalEventDraft(meetupID: 7, start: "2026-10-25T18:00:00Z"), eventID: 42)
        #expect(!saved && workflow.error?.contains("422") == true)
        #expect(workflow.error?.contains("fixture-token") == false)
        #expect(await http.requests.last?.httpMethod == "PATCH")
        let key = try #require(workflow.publicKey)
        await workflow.changeLeader(meetupID: 7, npub: try NIP19.encodePublicKey(key))
        #expect(workflow.error?.contains("422") == true)
        #expect(await http.requests.last?.httpMethod == "POST")
    }
    @Test func successfulPortalRSVPRefreshesOwnStatusAndCount() async throws {
        let http = WorkflowParityHTTP(), workflow = try await store(http)
        await workflow.loadPortalRSVP(eventID: 42)
        #expect(workflow.portalRSVPs[42]?.status == "none")
        await workflow.portalRSVP(eventID: 42, status: "maybe")
        #expect(workflow.portalRSVPs[42]?.status == "maybe" && workflow.portalRSVPs[42]?.count == 1)
        await workflow.portalRSVP(eventID: 42, status: "none")
        #expect(workflow.portalRSVPs[42]?.status == "none" && workflow.portalRSVPs[42]?.count == 0)
    }
    @Test func newestReadWinsAndIdentityChangeFencesOldPrivateRSVPSummary() async throws {
        let http = WorkflowParityHTTP(), workflow = try await store(http)
        await http.suspendRSVP()
        let old = Task { await workflow.loadPortalRSVP(eventID: 42) }
        try await waitForReads(1, http)
        let newer = Task { await workflow.loadPortalRSVP(eventID: 42) }
        try await waitForReads(2, http)
        await http.finishRSVP(2, count: 2, status: "maybe"); await newer.value
        await http.finishRSVP(1, count: 1, status: "attending"); await old.value
        #expect(workflow.portalRSVPs[42]?.count == 2 && workflow.portalRSVPs[42]?.status == "maybe")
        let pending = Task { await workflow.loadPortalRSVP(eventID: 43) }
        try await waitForReads(3, http)
        await workflow.configure(signer: nil)
        await http.finishRSVP(3, count: 99, status: "attending"); await pending.value
        #expect(workflow.portalRSVPs.isEmpty && workflow.portalRSVPErrors.isEmpty && workflow.session == nil)
    }
    @Test func roomNavigationClearsOtherRoomMessagesMembershipAndReadMarksRemainPrivate() async throws {
        let signer = try LocalIdentitySigner(privateKey: Data(repeating: 1, count: 32)), key = try await signer.publicKey()
        let member = try await signer.sign(kind: 39002, tags: [["d", "room-a"], ["p", key]], content: "", createdAt: 100)
        let first = try await signer.sign(kind: 9, tags: [["h", "room-a"]], content: "Room A", createdAt: 100)
        let second = try await signer.sign(kind: 9, tags: [["h", "room-b"]], content: "Room B", createdAt: 101)
        let workflow = try await store(WorkflowParityHTTP(), events: [member, first, second])
        await workflow.loadMessages(room: "room-a")
        #expect(workflow.member && workflow.messages == [first])
        await workflow.loadMessages(room: "room-b")
        #expect(!workflow.member && workflow.messages == [second] && workflow.activeChatKey == "room-b")
        #expect(await workflow.send("Keep unsent", room: "room-b") == false)
        #expect(workflow.error == CalendarChatError.notMember.localizedDescription)
        let marks = try JSONDecoder().decode([String: Int].self, from: #require(try KeychainStore.read(account: "nostr-chat-read-marks-v1")))
        #expect(marks == ["room-a": 100, "room-b": 101])
        try await workflow.clearIdentity()
        #expect(try KeychainStore.read(account: "nostr-chat-read-marks-v1") == nil)
    }
    @Test func expiredPortalWriteClearsSavedSessionAndPrivateManagedState() async throws {
        let http = WorkflowParityHTTP(), workflow = try await store(http)
        await http.expireToken()
        await workflow.portalRSVP(eventID: 42, status: "attending")
        #expect(workflow.session == nil && !workflow.busy)
        #expect(try KeychainStore.read(account: "portal-session-v1") == nil)
        #expect(workflow.error?.contains("401") == true && workflow.error?.contains("fixture-token") == false)
    }
}

extension PortalWorkflowStoreParityTests {
    @Test func ownResponseJourneyResolvesCalendarOutsideCommunityDirectoryFeed() async throws {
        let signer = try LocalIdentitySigner(privateKey: Data(repeating: 1, count: 32))
        let source = try await signer.sign(kind: 31922, tags: [["d", "outside-feed"], ["title", "Responded external event"], ["start", "2026-10-25"]], content: "", createdAt: 100)
        let entry = try NostrCalendarEntry(event: source)
        let response = try await signer.sign(kind: 31925, tags: [["d", "fixture-rsvp"], ["a", entry.id], ["status", "tentative"]], content: "", createdAt: 101)
        let workflow = try await store(WorkflowParityHTTP(), events: [source, response])
        await workflow.loadCalendar(onlyMyResponses: true)
        #expect(workflow.entries == [entry] && workflow.myRSVPs[entry.id] == "tentative")
        #expect(workflow.error == nil)
    }
}

private actor WorkflowDirectoryGate {
    private var counts: [String: Int] = [:]
    private var waiters: [String: CheckedContinuation<[NostrEvent], any Error>] = [:]
    private let auxiliaryEvents: [NostrEvent]
    init(auxiliaryEvents: [NostrEvent] = []) { self.auxiliaryEvents = auxiliaryEvents }
    func started(_ context: String) -> Int { counts[context, default: 0] }
    func reply(_ data: Data) async throws -> [Data] {
        let frame = try JSONSerialization.jsonObject(with: data) as! [Any]
        guard frame[0] as? String == "REQ", let subscription = frame[1] as? String else { return [] }
        // NostrFilter encodes tag constraints as #i on the actual REQ wire frame.
        let filter = frame[2] as! [String: Any]
        let events: [NostrEvent]
        if filter["kinds"] as? [Int] == [39000] {
            let context = (filter["#i"] as? [String])?.first ?? "all"
            counts[context, default: 0] += 1
            let key = context + "#" + String(counts[context]!)
            events = try await withCheckedThrowingContinuation { waiters[key] = $0 }
        } else { events = auxiliaryEvents }
        return try events.map { event in
            try JSONSerialization.data(withJSONObject: ["EVENT", subscription, JSONSerialization.jsonObject(with: JSONEncoder().encode(event))])
        } + [try JSONSerialization.data(withJSONObject: ["EOSE", subscription])]
    }
    func finish(_ context: String, request: Int, events: [NostrEvent]) {
        waiters.removeValue(forKey: context + "#" + String(request))?.resume(returning: events)
    }
    func fail(_ context: String, request: Int) {
        waiters.removeValue(forKey: context + "#" + String(request))?.resume(throwing: URLError(.cannotConnectToHost))
    }
}
private final class WorkflowDirectorySocket: NostrSocket, @unchecked Sendable {
    private let stream: AsyncThrowingStream<Data, any Error>
    private let continuation: AsyncThrowingStream<Data, any Error>.Continuation
    private let gate: WorkflowDirectoryGate
    init(_ gate: WorkflowDirectoryGate) {
        let pair = AsyncThrowingStream<Data, any Error>.makeStream()
        stream = pair.stream; continuation = pair.continuation; self.gate = gate
    }
    func send(_ data: Data) async throws { for frame in try await gate.reply(data) { continuation.yield(frame) } }
    func receive() async throws -> Data { var iterator = stream.makeAsyncIterator(); guard let data = try await iterator.next() else { throw CancellationError() }; return data }
    func close() { continuation.finish() }
}
extension PortalWorkflowStoreParityTests {
    private func directoryStore(_ gate: WorkflowDirectoryGate) async throws -> WorkflowStore {
        WorkflowParityStorage.values.reset()
        let workflow = WorkflowStore(relay: NostrRelayClient { _ in WorkflowDirectorySocket(gate) }, transport: WorkflowParityHTTP())
        await workflow.configure(signer: try LocalIdentitySigner(privateKey: Data(repeating: 1, count: 32)))
        return workflow
    }
    private func directoryRoom(_ meetupID: Int) async throws -> NostrEvent {
        try await LocalIdentitySigner(privateKey: Data(repeating: 1, count: 32)).sign(kind: 39000,
            tags: [["d", "room-\(meetupID)"], ["i", "meetup:\(meetupID)"], ["t", "meetup"], ["name", "Meetup \(meetupID)"]], content: "", createdAt: 100)
    }
    private func waitForDirectory(_ context: String, request: Int, gate: WorkflowDirectoryGate) async throws {
        for _ in 0..<1_000 {
            if await gate.started(context) >= request { return }
            try await Task.sleep(for: .milliseconds(1))
        }
        try #require(await gate.started(context) >= request, "Expected contextual directory query was not started")
    }
    @Test func directoryContextChangeHidesOldRoomsBeforeAwaitAndAfterFailure() async throws {
        let room = try await directoryRoom(7), now = Int(Date().timeIntervalSince1970)
        let incoming = try await LocalIdentitySigner(privateKey: Data(repeating: 2, count: 32)).sign(kind: 9, tags: [["h", "room-7"]], content: "Unread", createdAt: now)
        let gate = WorkflowDirectoryGate(auxiliaryEvents: [incoming]), workflow = try await directoryStore(gate)
        let first = Task { await workflow.loadRooms(meetupID: "7") }
        try await waitForDirectory("meetup:7", request: 1, gate: gate)
        await gate.finish("meetup:7", request: 1, events: [room]); await first.value
        #expect(workflow.rooms(for: "7") == [room] && workflow.unreadCounts["room-7"] == 1)
        // The view's accessor hides the previous context even before its task starts.
        #expect(workflow.rooms(for: "8").isEmpty)
        let second = Task { await workflow.loadRooms(meetupID: "8") }
        try await waitForDirectory("meetup:8", request: 1, gate: gate)
        #expect(workflow.rooms.isEmpty && workflow.unreadCounts.isEmpty && workflow.roomsAreLoading(for: "8"))
        await gate.fail("meetup:8", request: 1); await second.value
        #expect(workflow.rooms(for: "8").isEmpty && workflow.rooms(for: "7").isEmpty)
        #expect(!workflow.roomsAreLoading(for: "8") && workflow.error != nil)
    }
    @Test func lateOtherMeetupSuccessOrFailureCannotReplaceCurrentDirectory() async throws {
        let a = try await directoryRoom(7), b = try await directoryRoom(8)
        let gate = WorkflowDirectoryGate(), workflow = try await directoryStore(gate)
        let old = Task { await workflow.loadRooms(meetupID: "7") }
        try await waitForDirectory("meetup:7", request: 1, gate: gate)
        let current = Task { await workflow.loadRooms(meetupID: "8") }
        try await waitForDirectory("meetup:8", request: 1, gate: gate)
        await gate.finish("meetup:8", request: 1, events: [b]); await current.value
        await gate.finish("meetup:7", request: 1, events: [a]); await old.value
        #expect(workflow.rooms(for: "8") == [b] && workflow.rooms(for: "7").isEmpty && workflow.error == nil)
        let oldFailure = Task { await workflow.loadRooms(meetupID: "7") }
        try await waitForDirectory("meetup:7", request: 2, gate: gate)
        let latest = Task { await workflow.loadRooms(meetupID: "8") }
        try await waitForDirectory("meetup:8", request: 2, gate: gate)
        await gate.finish("meetup:8", request: 2, events: [b]); await latest.value
        await gate.fail("meetup:7", request: 2); await oldFailure.value
        #expect(workflow.rooms(for: "8") == [b] && workflow.error == nil && !workflow.roomsAreLoading(for: "8"))
    }
    @Test func sameDirectoryFailedRefreshRetainsKnownRoomsForThatContextOnly() async throws {
        let room = try await directoryRoom(7), gate = WorkflowDirectoryGate(), workflow = try await directoryStore(gate)
        let first = Task { await workflow.loadRooms(meetupID: "7") }
        try await waitForDirectory("meetup:7", request: 1, gate: gate)
        await gate.finish("meetup:7", request: 1, events: [room]); await first.value
        let refresh = Task { await workflow.loadRooms(meetupID: "7") }
        try await waitForDirectory("meetup:7", request: 2, gate: gate)
        #expect(workflow.rooms(for: "7") == [room] && workflow.roomsAreLoading(for: "7"))
        await gate.fail("meetup:7", request: 2); await refresh.value
        #expect(workflow.rooms(for: "7") == [room] && workflow.rooms(for: "8").isEmpty && workflow.rooms(for: nil).isEmpty)
        #expect(!workflow.roomsAreLoading(for: "7") && workflow.error != nil)
    }
    @Test func identityChangeFencesPendingDirectoryAndReleasesLoadingState() async throws {
        let room = try await directoryRoom(7), gate = WorkflowDirectoryGate(), workflow = try await directoryStore(gate)
        let pending = Task { await workflow.loadRooms(meetupID: "7") }
        try await waitForDirectory("meetup:7", request: 1, gate: gate)
        await workflow.configure(signer: nil)
        #expect(!workflow.roomsAreLoading(for: "7") && workflow.rooms.isEmpty)
        await gate.finish("meetup:7", request: 1, events: [room]); await pending.value
        #expect(workflow.rooms.isEmpty && workflow.unreadCounts.isEmpty && workflow.error == nil)
    }
}

import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@testable import MeetupNostr

private enum PortalParityFailure: Error { case rejected }
private actor PortalParityRelay: NostrRelayTransport {
    var events: [NostrEvent]
    var published: [NostrEvent] = []
    var queries: [NostrFilter] = []
    var queriedRelays: [[URL]] = []
    var publishRelays: [[URL]] = []
    let rejectedKinds: Set<Int>
    init(_ events: [NostrEvent] = [], rejectedKinds: Set<Int> = []) { self.events = events; self.rejectedKinds = rejectedKinds }
    func query(_ filter: NostrFilter, relays: [URL], timeout: TimeInterval) -> [NostrEvent] { queries.append(filter); queriedRelays.append(relays); return events }
    func publish(_ event: NostrEvent, relays: [URL], signer: (any NostrSigner)?) throws {
        published.append(event); publishRelays.append(relays)
        if rejectedKinds.contains(event.kind) { throw PortalParityFailure.rejected }
        events.append(event)
    }
}
private actor PortalParityHTTP: ProductHTTPTransport {
    var requests: [URLRequest] = []
    let handler: @Sendable (URLRequest) throws -> ProductHTTPResponse
    init(_ handler: @escaping @Sendable (URLRequest) throws -> ProductHTTPResponse) { self.handler = handler }
    func send(_ request: URLRequest) throws -> ProductHTTPResponse { requests.append(request); return try handler(request) }
}
@Suite struct PortalWorkflowParityTests {
    private var relayURL: URL { URL(string: "wss://local-fixture.invalid")! }
    private func signer(_ byte: UInt8 = 1) throws -> LocalIdentitySigner { try LocalIdentitySigner(privateKey: Data(repeating: byte, count: 32)) }
    private func entry(_ signer: LocalIdentitySigner, at: Int = 100) async throws -> NostrCalendarEntry {
        try NostrCalendarEntry(event: await signer.sign(kind: 31922,
            tags: [["d", "civil:fixture"], ["title", "Fixture"], ["start", "2026-10-25"], ["end", "2026-10-27"],
                   ["location", "Fixture venue"], ["client", "einundzwanzig-meetup-app"], ["badge", "true"], ["g", "48.2", "16.3"], ["p", signer.publicKey(), "", "issuer"], ["custom", "preserve"]],
            content: "Preserved source description", createdAt: at))
    }
    private func json(_ text: String) throws -> JSONValue { try JSONDecoder().decode(JSONValue.self, from: Data(text.utf8)) }

    @Test func cancellationReplacesSourceAndSurvivesRejectedDeletion() async throws {
        let signer = try signer(), original = try await entry(signer)
        let relay = PortalParityRelay([original.event], rejectedKinds: [5]), service = CalendarChatService(relay: relay)
        try await service.cancel(original, signer: signer, relays: [relayURL])
        let writes = await relay.published
        #expect(writes.map(\.kind) == [31922, 5])
        #expect(writes[0].tags == original.event.tags + [["status", "cancelled"]])
        #expect(writes[0].content == original.event.content && writes[0].createdAt > original.event.createdAt)
        #expect(await NostrCrypto().verify(writes[0]))
        #expect(try await service.calendar(relays: [relayURL]).isEmpty)
        #expect(try await service.calendarEntry(address: original.id, relays: [relayURL]) == nil)
    }
    @Test func rejectedCancellationDoesNotSendDeletionOrReportSuccess() async throws {
        let signer = try signer(), original = try await entry(signer)
        let relay = PortalParityRelay([original.event], rejectedKinds: [31922]), service = CalendarChatService(relay: relay)
        await #expect(throws: PortalParityFailure.rejected) { try await service.cancel(original, signer: signer, relays: [relayURL]) }
        #expect(await relay.published.map(\.kind) == [31922])
    }
    @Test func editingAdvancesReplacementTimestampAndPreservesCalendarKind() async throws {
        let signer = try signer(), original = try await entry(signer, at: Int(Date().timeIntervalSince1970))
        let relay = PortalParityRelay(), service = CalendarChatService(relay: relay)
        let draft = CalendarEventDraft(title: "Updated", start: original.start, allDay: true, identifier: original.identifier)
        let updated = try await service.publish(draft, existing: original, signer: signer, relays: [relayURL])
        #expect(updated.id == original.id && updated.event.createdAt > original.event.createdAt)
        var changedKind = draft; changedKind.allDay = false
        await #expect(throws: CalendarChatError.notAuthor) { try await service.publish(changedKind, existing: original, signer: signer, relays: [relayURL]) }
    }
    @Test func exactAddressLookupFiltersUnrelatedRowsAndAuthorDeletion() async throws {
        let signer = try signer(), original = try await entry(signer), unrelated = try await entry(self.signer(2))
        let relay = PortalParityRelay([original.event, unrelated.event]), service = CalendarChatService(relay: relay)
        #expect(try await service.calendarEntry(address: original.id, relays: [relayURL]) == original)
        let queries = await relay.queries
        #expect(queries.first?.authors == [original.event.pubkey] && queries.first?.tags["d"] == ["civil:fixture"])
        let deletion = try await signer.sign(kind: 5, tags: [["a", original.id]], content: "", createdAt: 101)
        let deleted = CalendarChatService(relay: PortalParityRelay([original.event, deletion]))
        #expect(try await deleted.calendarEntry(address: original.id, relays: [relayURL]) == nil)
        await #expect(throws: CalendarChatError.invalidEvent) { try await deleted.calendarEntry(address: "not-an-address", relays: [relayURL]) }
    }
    @Test func rsvpUsesFlutterStableIdentifierAndIgnoresInvalidLatestStatus() async throws {
        let signer = try signer(), original = try await entry(signer)
        let relay = PortalParityRelay(), service = CalendarChatService(relay: relay)
        try await service.rsvp(original, status: "accepted", signer: signer, relays: [relayURL])
        let sent = try #require(await relay.published.last)
        #expect(sent.tags.contains(["d", "rsvp-" + original.id.lowercased().replacingOccurrences(of: "[^a-z0-9]+", with: "-", options: .regularExpression)]))
        let accepted = try await signer.sign(kind: 31925, tags: [["d", "legacy"], ["a", original.id], ["status", "accepted"]], content: "", createdAt: 101)
        let declined = try await signer.sign(kind: 31925, tags: [["d", "flutter"], ["a", original.id], ["status", "declined"]], content: "", createdAt: 102)
        let invalid = try await signer.sign(kind: 31925, tags: [["d", "bad"], ["a", original.id], ["status", "forged-status"]], content: "", createdAt: 103)
        let future = try await signer.sign(kind: 31925, tags: [["d", "future"], ["a", original.id], ["status", "accepted"]], content: "", createdAt: Int(Date().timeIntervalSince1970) + 601)
        let reads = CalendarChatService(relay: PortalParityRelay([accepted, declined, invalid, future, declined]))
        #expect(try await reads.rsvps(original, relays: [relayURL]) == [declined])
    }
    @Test func discoveryReplacesRoomMetadataAndUsesExactPortalMeetupBinding() async throws {
        let signer = try signer()
        let old = try await signer.sign(kind: 39000, tags: [["d", "wien"], ["name", "Old"], ["t", "meetup"], ["i", "meetup:7"]], content: "", createdAt: 100)
        let fresh = try await signer.sign(kind: 39000, tags: [["d", "wien"], ["name", "Fresh"], ["t", "meetup"], ["i", "meetup:7"]], content: "", createdAt: 101)
        let noDirectoryTag = try await signer.sign(kind: 39000, tags: [["d", "wien-walk"], ["name", "Walk"], ["i", "meetup:8"]], content: "", createdAt: 101)
        let relay = PortalParityRelay([old, fresh, noDirectoryTag]), service = CalendarChatService(relay: relay)
        #expect(try await service.rooms() == [fresh])
        #expect(try await service.rooms(meetupID: "8") == [noDirectoryTag])
        #expect(await relay.queries.last?.tags == ["i": ["meetup:8"]])
    }
    @Test func latestMembershipControlsSendingAndJoinLeaveKindsMatchFlutter() async throws {
        let signer = try signer(), key = try await signer.publicKey()
        let member = try await signer.sign(kind: 39002, tags: [["d", "room"], ["p", key]], content: "", createdAt: 100)
        let left = try await signer.sign(kind: 39002, tags: [["d", "room"]], content: "", createdAt: 101)
        let relay = PortalParityRelay([member, left]), service = CalendarChatService(relay: relay)
        #expect(try await service.isMember(room: "room", publicKey: key) == false)
        await #expect(throws: CalendarChatError.notMember) { try await service.send(text: "Unsent", room: "room", signer: signer, relays: [relayURL]) }
        #expect(await relay.published.isEmpty)
        try await service.membership(room: "room", join: true, signer: signer)
        try await service.membership(room: "room", join: false, signer: signer)
        #expect(await relay.published.map(\.kind) == [9021, 9022])
        let joinedRelay = PortalParityRelay([member]), joined = CalendarChatService(relay: joinedRelay)
        try await joined.send(text: " Joined ", room: "room", signer: signer, relays: [relayURL])
        #expect(await joinedRelay.published.last?.content == "Joined")
    }
    @Test func unreadCountsExcludeOwnDuplicateOldAndUnrelatedMessages() async throws {
        let me = try signer(), other = try signer(2), now = Int(Date().timeIntervalSince1970)
        let incoming = try await other.sign(kind: 9, tags: [["h", "room"]], content: "new", createdAt: now)
        let own = try await me.sign(kind: 9, tags: [["h", "room"]], content: "own", createdAt: now)
        let old = try await other.sign(kind: 9, tags: [["h", "room"]], content: "seen", createdAt: now - 10)
        let unrelated = try await other.sign(kind: 9, tags: [["h", "different"]], content: "other", createdAt: now)
        let service = CalendarChatService(relay: PortalParityRelay([incoming, incoming, own, old, unrelated]))
        let counts = try await service.unreadCounts(rooms: ["room"], lastRead: ["room": now - 1], publicKey: me.publicKey())
        #expect(counts == ["room": 1])
    }
    @Test func portalSummaryReadsFlutterBooleanCountsAndNestedNames() throws {
        let summary = try PortalRSVPSummary(response: json(#"{"data":{"going":true,"attendees":3,"attendee_names":["id_42|Alice",{"user":{"nickname":"Bob","npub":"synthetic-public-key"}},"id_42|Alice"]}}"#))
        #expect(summary.status == "attending" && summary.isGoing && summary.count == 3)
        #expect(summary.attendees.map(\.name) == ["Alice", "Bob"])
        let maybe = try PortalRSVPSummary(response: json(#"{"status":"maybe","count":2}"#))
        #expect(maybe.isGoing)
        let privateList = try PortalRSVPSummary(response: json(#"{"attendees_public":false,"attendee_names":["hidden"],"rsvped":false}"#))
        #expect(privateList.attendees.isEmpty && privateList.status == "none")
        #expect(throws: PortalAPIError.invalidResponse) { try PortalRSVPSummary(response: .array([])) }
    }
    @Test func portalNamesFallbackRetainsStatusCountAndUsesAuthenticatedOrganizerContract() async throws {
        let session = try await PortalSession(token: "fixture-token", publicKey: signer().publicKey())
        let http = PortalParityHTTP { request in
            #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer fixture-token")
            switch request.url?.path {
            case "/api/meetup-events/42/rsvp": return ProductHTTPResponse(status: 200, data: Data(#"{"data":{"status":"maybe","count":2}}"#.utf8))
            case "/api/my-meetup-events/42": return ProductHTTPResponse(status: 403, data: Data())
            case "/api/meetup-events/42/attendees": return ProductHTTPResponse(status: 200, data: Data(#"{"data":["id_2|Alice",{"profile":{"display_name":"Bob"}}]}"#.utf8))
            default: throw PortalParityFailure.rejected
            }
        }
        let summary = try await PortalAPIClient(transport: http).rsvpSummary(eventID: 42, session: session)
        #expect(summary.status == "maybe" && summary.count == 2 && summary.attendees.map(\.name) == ["Alice", "Bob"])
        #expect(await http.requests.count == 3)
    }
    @Test func privateRsvpListDoesNotProbeOptionalEndpointsAndAnonymousWritesFail() async throws {
        let http = PortalParityHTTP { _ in ProductHTTPResponse(status: 200, data: Data(#"{"count":2,"attendees_public":false}"#.utf8)) }
        let api = PortalAPIClient(transport: http)
        #expect(try await api.rsvpSummary(eventID: 42, session: nil).count == 2)
        #expect(await http.requests.count == 1)
        await #expect(throws: PortalAPIError.identityMismatch) { try await api.rsvp(eventID: 42, status: "attending", session: nil) }
        #expect(await http.requests.count == 1)
    }
    @Test func portalRecurrenceWritesPreserveFractionalRFC3339AndAllRecurrenceFields() async throws {
        let session = try await PortalSession(token: "fixture-token", publicKey: signer().publicKey())
        let http = PortalParityHTTP { request in
            #expect(request.httpMethod == "PATCH" && request.url?.path == "/api/meetup-events/42")
            let body = try JSONDecoder().decode(JSONValue.self, from: #require(request.httpBody))
            #expect(body["start"].string == "2026-10-25T18:00:00.123Z")
            #expect(body["recurrence_day_of_week"].string == "monday")
            #expect(body["recurrence_day_position"].string == "first" && body["recurrence_interval"].integer == 2)
            return ProductHTTPResponse(status: 204, data: Data())
        }
        let draft = PortalEventDraft(meetupID: 7, start: "2026-10-25T18:00:00.123Z", recurrenceType: "custom", recurrenceDayOfWeek: "monday", recurrenceDayPosition: "first", recurrenceInterval: 2, recurrenceEndDate: "2026-12-25T18:00:00Z")
        _ = try await PortalAPIClient(transport: http).saveEvent(draft, eventID: 42, session: session)
    }
    @Test func malformedManagementListsDoNotBecomeEmptySuccess() async throws {
        let session = try await PortalSession(token: "fixture-token", publicKey: signer().publicKey())
        let api = PortalAPIClient(transport: PortalParityHTTP { _ in ProductHTTPResponse(status: 200, data: Data(#"{"data":{"unexpected":true}}"#.utf8)) })
        await #expect(throws: PortalAPIError.invalidResponse) { try await api.managedMeetups(session) }
        await #expect(throws: PortalAPIError.invalidResponse) { try await api.managedEvents(session) }
        await #expect(throws: PortalAPIError.invalidResponse) { try await api.leaders(meetupID: 7, session: session) }
    }
}

extension PortalWorkflowParityTests {
    @Test func communityScopeUsesCommunityRelayAndCalendarWritesAlwaysIncludeIt() async throws {
        let signer = try signer(), app = try await entry(signer)
        let external = try await signer.sign(kind: 31922, tags: [["d", "external"], ["title", "External"], ["start", "2026-10-25"]], content: "", createdAt: 100)
        let relay = PortalParityRelay([app.event, external]), service = CalendarChatService(relay: relay)
        #expect(try await service.calendar(relays: [relayURL], worldwide: false) == [app])
        #expect(await relay.queriedRelays.first == [CalendarChatService.communityRelay])
        #expect(try await service.calendar(relays: [relayURL], worldwide: true).count == 2)
        _ = try await service.publish(CalendarEventDraft(title: "Synthetic", start: app.start, allDay: true), signer: signer, relays: [relayURL])
        #expect(await relay.publishRelays.last == [relayURL, CalendarChatService.communityRelay])
    }
    @Test func ownResponsesLoadAcrossAddressesAndRejectAnotherAuthorOrMalformedRoots() async throws {
        let signer = try signer(), other = try self.signer(2), app = try await entry(signer)
        let key = try await signer.publicKey()
        let old = try await signer.sign(kind: 31925, tags: [["d", "old"], ["a", app.id], ["status", "accepted"]], content: "", createdAt: 100)
        let fresh = try await signer.sign(kind: 31925, tags: [["d", "fresh"], ["a", app.id], ["status", "declined"]], content: "", createdAt: 101)
        let foreign = try await other.sign(kind: 31925, tags: [["d", "other"], ["a", app.id], ["status", "accepted"]], content: "", createdAt: 102)
        let malformed = try await signer.sign(kind: 31925, tags: [["d", "bad"], ["a", "not-a-calendar-root"], ["status", "accepted"]], content: "", createdAt: 102)
        let service = CalendarChatService(relay: PortalParityRelay([old, fresh, foreign, malformed]))
        #expect(try await service.myRSVPs(publicKey: key, relays: [relayURL]) == [app.id: fresh])
    }
    @Test func eventUnreadCountsUseCapitalRootAndExcludeOwnAndSeenComments() async throws {
        let signer = try signer(), other = try self.signer(2), app = try await entry(signer), now = Int(Date().timeIntervalSince1970)
        let incoming = try await other.sign(kind: 1111, tags: [["A", app.id]], content: "New", createdAt: now)
        let own = try await signer.sign(kind: 1111, tags: [["A", app.id]], content: "Own", createdAt: now)
        let wrongRoot = try await other.sign(kind: 1111, tags: [["a", app.id]], content: "No capital root", createdAt: now)
        let old = try await other.sign(kind: 1111, tags: [["A", app.id]], content: "Seen", createdAt: now - 2)
        let service = CalendarChatService(relay: PortalParityRelay([incoming, incoming, own, old, wrongRoot]))
        #expect(try await service.unreadEventCounts(addresses: [app.id], lastRead: [app.id: now - 1], publicKey: signer.publicKey(), relays: [relayURL]) == [app.id: 1])
    }
}

private actor PortalParityRotatingSigner: NostrSigner {
    let before: LocalIdentitySigner
    let after: LocalIdentitySigner
    private var reads = 0
    init(before: LocalIdentitySigner, after: LocalIdentitySigner) { self.before = before; self.after = after }
    func publicKey() async throws -> String { reads += 1; return try await (reads == 1 ? before : after).publicKey() }
    func sign(kind: Int, tags: [[String]], content: String, createdAt: Int) async throws -> NostrEvent { try await after.sign(kind: kind, tags: tags, content: content, createdAt: createdAt) }
    func encrypt(_ plaintext: String, to publicKey: String) throws -> String { throw CalendarChatError.identityMismatch }
    func decrypt(_ ciphertext: String, from publicKey: String) throws -> String { throw CalendarChatError.identityMismatch }
}
extension PortalWorkflowParityTests {
    @Test func identityRotationBetweenOwnershipAndSigningCannotPublishCalendarMutation() async throws {
        let before = try signer(), after = try signer(2), original = try await entry(before)
        let relay = PortalParityRelay(), service = CalendarChatService(relay: relay)
        let draft = CalendarEventDraft(title: "Synthetic", start: original.start, allDay: true, identifier: original.identifier)
        await #expect(throws: CalendarChatError.identityMismatch) {
            try await service.publish(draft, existing: original, signer: PortalParityRotatingSigner(before: before, after: after), relays: [relayURL])
        }
        await #expect(throws: CalendarChatError.identityMismatch) {
            try await service.cancel(original, signer: PortalParityRotatingSigner(before: before, after: after), relays: [relayURL])
        }
        #expect(await relay.published.isEmpty)
    }
    @Test func portalNamesPrivacyAppliesOutsideDataWrapperAndMaybeCountIsPreserved() async throws {
        let response = try json(#"{"attendees_public":false,"data":{"attendee_names":["Private"],"attendees":2,"might_attendees":1}}"#)
        let summary = try PortalRSVPSummary(response: response)
        #expect(summary.attendees.isEmpty && summary.count == 2 && summary.maybeCount == 1)
        let http = PortalParityHTTP { _ in ProductHTTPResponse(status: 200, data: try JSONEncoder().encode(response)) }
        #expect(try await PortalAPIClient(transport: http).rsvpSummary(eventID: 42, session: nil).attendees.isEmpty)
        #expect(await http.requests.count == 1)
    }
}

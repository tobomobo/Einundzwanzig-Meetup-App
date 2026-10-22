import Foundation
import Observation
import MeetupCore
import MeetupNostr

struct KeychainBadgeWalletPersistence: BadgeWalletPersistence {
    func read() throws -> Data? { try KeychainStore.read(account: "badge-wallet-v1") }
    func write(_ data: Data) throws { try KeychainStore.write(data, account: "badge-wallet-v1") }
    func delete() throws { try KeychainStore.delete(account: "badge-wallet-v1") }
}

@Observable @MainActor final class WorkflowStore {
    let relay: NostrRelayClient
    let calendar: CalendarChatService
    let portal: PortalAPIClient
    let news: NewsNostrService
    let value = ValueForValueService()
    var relays = [URL(string: "wss://relay.damus.io")!, URL(string: "wss://nos.lol")!]
    private(set) var signer: (any NostrSigner)?
    private(set) var publicKey: String?
    private(set) var session: PortalSession?
    private(set) var portalRSVPs: [Int: PortalRSVPSummary] = [:]
    private(set) var portalRSVPErrors: [Int: String] = [:]
    private var portalRSVPRequests: [Int: UUID] = [:]
    var calendarWorldwide = false
    private(set) var myRSVPs: [String: String] = [:]
    private(set) var eventUnreadCounts: [String: Int] = [:]
    private(set) var unreadCounts: [String: Int] = [:]
    private(set) var activeChatKey: String?
    private var leaderRequest = UUID()
    private var rsvpRequest = UUID()
    private(set) var managedMeetups: [ManagedMeetup] = []
    private(set) var managedEvents: [JSONValue] = []
    private(set) var leaders: [JSONValue] = []
    private(set) var entries: [NostrCalendarEntry] = []
    private(set) var rooms: [NostrEvent] = []
    private(set) var messages: [NostrEvent] = []
    private(set) var rsvpEvents: [NostrEvent] = []
    private(set) var member = false
    private(set) var busy = false
    private var generation = 0
    private var chatRequest = UUID()
    var error: String?
    var notice: String?
    private let readMarksKey = "nostr-chat-read-marks-v1"

    init(relay: NostrRelayClient, transport: any ProductHTTPTransport = ProductURLSessionTransport()) { self.relay = relay; portal = PortalAPIClient(transport: transport); calendar = CalendarChatService(relay: relay); news = NewsNostrService(relay: relay) }
    #if DEBUG
    func installNavigationFixture() async throws {
        guard UITestFixtureMode.enabled else { return }
        let fixtureSigner = try LocalIdentitySigner(privateKey: Data(repeating: 0, count: 31) + Data([1]))
        let event = try await fixtureSigner.sign(kind: 31922, tags: [["d", "fixture-civil-event"], ["title", "Fixture civil date"], ["start", "2026-10-25"], ["location", "Original venue"], ["g", "48.2", "16.3"]], content: "Synthetic navigation fixture", createdAt: Int(Date().timeIntervalSince1970))
        entries = [try NostrCalendarEntry(event: event)]
    }
    #endif
    func configure(signer: (any NostrSigner)?) async {
        generation += 1
        let epoch = generation
        chatRequest = UUID(); busy = false; error = nil; notice = nil
        self.signer = signer; publicKey = nil; activeChatKey = nil
        portalRSVPs = [:]; portalRSVPErrors = [:]; portalRSVPRequests = [:]; unreadCounts = [:]; eventUnreadCounts = [:]; myRSVPs = [:]
        session = nil; managedMeetups = []; managedEvents = []; leaders = []; entries = []; rooms = []; rsvpEvents = []; messages = []; member = false
        guard let signer else { return }
        do {
            let key = try await signer.publicKey()
            guard epoch == generation else { return }
            publicKey = key
            if let data = try KeychainStore.read(account: "portal-session-v1") {
                let saved = try JSONDecoder().decode(PortalSession.self, from: data)
                guard saved.publicKey == key else { throw PortalAPIError.identityMismatch }
                session = saved
            }
        } catch { self.error = error.localizedDescription }
    }
    func clearIdentity() async throws {
        generation += 1
        try KeychainStore.delete(account: "portal-session-v1")
        try KeychainStore.delete(account: readMarksKey)
        publicKey = nil; activeChatKey = nil; portalRSVPs = [:]; portalRSVPErrors = [:]; portalRSVPRequests = [:]; unreadCounts = [:]; eventUnreadCounts = [:]; myRSVPs = [:]
        signer = nil; session = nil; managedMeetups = []; managedEvents = []; leaders = []
        entries = []; rooms = []; messages = []; rsvpEvents = []; member = false; error = nil; notice = nil; busy = false
    }
    func login(manualToken: String = "") async {
        let epoch = generation
        let succeeded = await perform { epoch in
            let signer = try self.requireSigner()
            let next: PortalSession
            if manualToken.isEmpty { next = try await self.portal.login(signer: signer) }
            else { next = try PortalSession(token: manualToken.trimmingCharacters(in: .whitespacesAndNewlines), publicKey: await signer.publicKey()) }
            guard epoch == self.generation else { throw CalendarChatError.identityMismatch }
            // Verify manually supplied tokens against the portal user before binding them.
            if !manualToken.isEmpty {
                let profile = try await self.portal.profile(next).unwrapped
                guard profile.object != nil else { throw PortalAPIError.invalidResponse }
                let candidate = (profile["nostr"].string ?? profile["npub"].string ?? profile["nostr_pubkey"].string ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                // Flutter also permits manually authenticated portal accounts without a Nostr key.
                if !candidate.isEmpty {
                    let key = (try? NIP19.decodePublicKey(candidate.lowercased())) ?? candidate.lowercased()
                    guard key == next.publicKey else { throw PortalAPIError.identityMismatch }
                }
            }
            guard epoch == self.generation else { throw CalendarChatError.identityMismatch }
            try KeychainStore.write(JSONEncoder().encode(next), account: "portal-session-v1")
            self.session = next
            self.notice = "Portal signed in."
        }
        if succeeded, epoch == generation, session != nil { await loadPortal() }
    }
    func logout() async {
        generation += 1
        busy = false
        let saved = session
        portalRSVPs = [:]; portalRSVPErrors = [:]; portalRSVPRequests = [:]
        do { try KeychainStore.delete(account: "portal-session-v1"); session = nil; managedMeetups = []; managedEvents = []; leaders = [] }
        catch { self.error = error.localizedDescription; return }
        if let saved { try? await portal.logout(saved) }
    }
    func loadPortal() async {
        await perform { epoch in
            let session = try self.requireSession()
            async let meetups = self.portal.managedMeetups(session)
            async let events = self.portal.managedEvents(session)
            let result = try await (meetups, events)
            guard epoch == self.generation else { return }
            self.managedMeetups = result.0; self.managedEvents = result.1
        }
    }
    @discardableResult func savePortalEvent(_ draft: PortalEventDraft, eventID: Int?) async -> Bool {
        let epoch = generation
        let succeeded = await perform { epoch in
            _ = try await self.portal.saveEvent(draft, eventID: eventID, session: self.requireSession())
            guard epoch == self.generation else { return }; self.notice = "Event saved in the portal."
        }
        if succeeded, epoch == generation { await loadPortal() }
        return succeeded && epoch == generation
    }
    func loadLeaders(_ meetupID: Int) async {
        let request = UUID(); leaderRequest = request; leaders = []
        await perform { epoch in
            let result = try await self.portal.leaders(meetupID: meetupID, session: self.requireSession())
            guard epoch == self.generation, request == self.leaderRequest else { return }; self.leaders = result
        }
    }
    func changeLeader(meetupID: Int, npub: String? = nil, removeUserID: Int? = nil) async {
        let epoch = generation
        let succeeded = await perform { _ in
            if let npub { try await self.portal.addLeader(meetupID: meetupID, npub: npub, session: self.requireSession()) }
            else if let removeUserID { try await self.portal.removeLeader(meetupID: meetupID, userID: removeUserID, session: self.requireSession()) }
        }
        if succeeded, epoch == generation { await loadLeaders(meetupID) }
    }
    func portalRSVP(eventID: Int, status: String) async {
        let epoch = generation
        let succeeded = await perform { epoch in
            let response = try await self.portal.rsvp(eventID: eventID, status: status, session: self.requireSession())
            guard epoch == self.generation else { return }; self.notice = response.unwrapped["status"].string ?? "RSVP saved."
        }
        if succeeded, epoch == generation { await loadPortalRSVP(eventID: eventID) }
    }
    func loadPortalRSVP(eventID: Int) async {
        let epoch = generation, request = UUID()
        portalRSVPRequests[eventID] = request; portalRSVPErrors[eventID] = nil
        do {
            let result = try await portal.rsvpSummary(eventID: eventID, session: session)
            guard epoch == generation, portalRSVPRequests[eventID] == request, !Task.isCancelled else { return }
            portalRSVPs[eventID] = result
        } catch {
            if epoch == generation, portalRSVPRequests[eventID] == request, !Task.isCancelled {
                handleError(error, epoch: epoch)
                portalRSVPErrors[eventID] = error.localizedDescription
            }
        }
    }
    func loadCalendarEntry(_ address: String) async {
        await perform { epoch in
            let result = try await self.calendar.calendarEntry(address: address, relays: self.relays)
            guard epoch == self.generation else { return }
            self.entries.removeAll { $0.id == address }
            if let result { self.entries.append(result) }
            else { self.notice = "The calendar event is unavailable or cancelled." }
        }
    }
    func loadCalendar(onlyMyResponses: Bool = false) async {
        await perform { epoch in
            var result: [NostrCalendarEntry] = []
            if !onlyMyResponses { result = try await self.calendar.calendar(relays: self.relays, worldwide: self.calendarWorldwide) }
            guard epoch == self.generation, !Task.isCancelled else { return }
            if !onlyMyResponses { self.entries = result }
            if let key = self.publicKey {
                let responses = try await self.calendar.myRSVPs(publicKey: key, relays: self.relays)
                guard epoch == self.generation, !Task.isCancelled else { return }
                self.myRSVPs = responses.compactMapValues(CalendarChatService.rsvpStatus)
                if onlyMyResponses {
                    result = try await self.calendar.calendarEntries(addresses: Array(responses.keys), relays: self.relays)
                    guard epoch == self.generation, !Task.isCancelled else { return }; self.entries = result
                }
            } else if onlyMyResponses { self.entries = [] }
            let marks = try self.readMarks()
            let eventMarks = Dictionary(uniqueKeysWithValues: marks.filter { $0.key.hasPrefix("evt|") }.map { (String($0.key.dropFirst(4)), $0.value) })
            let unread = try await self.calendar.unreadEventCounts(addresses: result.map(\.id), lastRead: eventMarks, publicKey: self.publicKey, relays: self.relays)
            guard epoch == self.generation, !Task.isCancelled else { return }; self.eventUnreadCounts = unread
        }
    }
    @discardableResult func publishCalendar(_ draft: CalendarEventDraft, existing: NostrCalendarEntry? = nil) async -> Bool {
        await perform { epoch in
            let result = try await self.calendar.publish(draft, existing: existing, signer: self.requireSigner(), relays: self.relays)
            guard epoch == self.generation else { return }
            self.entries.removeAll { $0.id == result.id }; self.entries.append(result); self.notice = "Event accepted by a relay."
        }
    }
    @discardableResult func cancel(_ entry: NostrCalendarEntry) async -> Bool {
        await perform { epoch in
            try await self.calendar.cancel(entry, signer: self.requireSigner(), relays: self.relays)
            guard epoch == self.generation else { return }; self.entries.removeAll { $0.id == entry.id }
        }
    }
    func rsvp(_ entry: NostrCalendarEntry, status: String) async {
        let epoch = generation
        let succeeded = await perform { _ in try await self.calendar.rsvp(entry, status: status, signer: self.requireSigner(), relays: self.relays) }
        if succeeded, epoch == generation { myRSVPs[entry.id] = status; await loadRSVPs(entry) }
    }
    func loadRSVPs(_ entry: NostrCalendarEntry) async {
        let request = UUID(); rsvpRequest = request; rsvpEvents = []
        await perform { epoch in
            let result = try await self.calendar.rsvps(entry, relays: self.relays)
            guard epoch == self.generation, request == self.rsvpRequest else { return }; self.rsvpEvents = result
        }
    }
    func loadRooms(meetupID: String? = nil) async {
        await perform { epoch in
            let result = try await self.calendar.rooms(meetupID: meetupID)
            guard epoch == self.generation else { return }; self.rooms = result
            let marks = try self.readMarks()
            let keys = result.compactMap { $0.tags.first(where: { $0.count > 1 && $0[0] == "d" })?[1] }
            let unread = try await self.calendar.unreadCounts(rooms: keys, lastRead: marks, publicKey: self.publicKey)
            guard epoch == self.generation else { return }; self.unreadCounts = unread
        }
    }
    func loadMessages(room: String? = nil, entry: NostrCalendarEntry? = nil) async {
        let epoch = generation
        let request = UUID(); chatRequest = request
        let key = room ?? entry.map { "evt|" + $0.id }
        if activeChatKey != key { activeChatKey = key; messages = []; member = false }
        do {
            let result = try await calendar.messages(room: room, eventAddress: entry?.id, relays: relays)
            guard epoch == generation, request == chatRequest, !Task.isCancelled else { return }
            let nextMember: Bool
            if let room, let signer { nextMember = try await calendar.isMember(room: room, publicKey: signer.publicKey()) }
            else { nextMember = false }
            guard epoch == generation, request == chatRequest, !Task.isCancelled else { return }
            messages = result; member = nextMember
            if let last = result.last, let key {
                var marks = try readMarks()
                marks[key] = max(marks[key] ?? 0, last.createdAt)
                if let entry { eventUnreadCounts[entry.id] = 0 } else { unreadCounts[key] = 0 }
                try KeychainStore.write(JSONEncoder().encode(marks), account: readMarksKey)
            }
        } catch { if epoch == generation, request == chatRequest, !Task.isCancelled { self.error = error.localizedDescription } }
    }
    func changeMembership(room: String, join: Bool) async {
        let epoch = generation
        let succeeded = await perform { _ in try await self.calendar.membership(room: room, join: join, signer: self.requireSigner()) }
        if succeeded, epoch == generation, activeChatKey == room { await loadMessages(room: room) }
    }
    func send(_ text: String, room: String? = nil, entry: NostrCalendarEntry? = nil) async -> Bool {
        let succeeded = await perform { _ in try await self.calendar.send(text: text, room: room, entry: entry, signer: self.requireSigner(), relays: self.relays) }
        guard succeeded else { return false }
        if activeChatKey == (room ?? entry.map { "evt|" + $0.id }) { await loadMessages(room: room, entry: entry) }
        return true
    }
    private func readMarks() throws -> [String: Int] {
        try KeychainStore.read(account: readMarksKey).map { try JSONDecoder().decode([String: Int].self, from: $0) } ?? [:]
    }
    private func requireSigner() throws -> any NostrSigner { guard let signer else { throw BadgeStoreError.noSigner }; return signer }
    private func requireSession() throws -> PortalSession { guard let session, signer != nil else { throw PortalAPIError.identityMismatch }; return session }
    private func handleError(_ error: any Error, epoch: Int) {
        guard epoch == generation else { return }
        if case PortalAPIError.http(401, _) = error {
            do { try KeychainStore.delete(account: "portal-session-v1") }
            catch { self.error = error.localizedDescription; return }
            generation += 1; busy = false; session = nil; notice = nil
            managedMeetups = []; managedEvents = []; leaders = []
            portalRSVPs = [:]; portalRSVPErrors = [:]; portalRSVPRequests = [:]
        }
        self.error = error.localizedDescription
    }
    @discardableResult private func perform(_ operation: (Int) async throws -> Void) async -> Bool {
        guard !busy else { return false }; busy = true; error = nil
        let epoch = generation
        defer { if epoch == generation { busy = false } }
        do { try await operation(epoch); return epoch == generation }
        catch { handleError(error, epoch: epoch); return false }
    }
}

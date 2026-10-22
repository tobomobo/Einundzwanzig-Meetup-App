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
    private(set) var session: PortalSession?
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
    func configure(signer: (any NostrSigner)?) async {
        generation += 1
        let epoch = generation
        chatRequest = UUID(); busy = false; error = nil; notice = nil
        self.signer = signer
        session = nil; managedMeetups = []; managedEvents = []; leaders = []; entries = []; rooms = []; rsvpEvents = []; messages = []; member = false
        guard let signer else { return }
        do {
            let key = try await signer.publicKey()
            guard epoch == generation else { return }
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
        signer = nil; session = nil; managedMeetups = []; managedEvents = []; leaders = []
        entries = []; rooms = []; messages = []; rsvpEvents = []; member = false; error = nil; notice = nil; busy = false
    }
    func login(manualToken: String = "") async {
        await perform { epoch in
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
        if session != nil { await loadPortal() }
    }
    func logout() async {
        generation += 1
        busy = false
        let saved = session
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
    func savePortalEvent(_ draft: PortalEventDraft, eventID: Int?) async {
        await perform { epoch in
            _ = try await self.portal.saveEvent(draft, eventID: eventID, session: self.requireSession())
            guard epoch == self.generation else { return }; self.notice = "Event saved in the portal."
        }
        await loadPortal()
    }
    func loadLeaders(_ meetupID: Int) async {
        await perform { epoch in
            let result = try await self.portal.leaders(meetupID: meetupID, session: self.requireSession())
            guard epoch == self.generation else { return }; self.leaders = result
        }
    }
    func changeLeader(meetupID: Int, npub: String? = nil, removeUserID: Int? = nil) async {
        await perform { _ in
            if let npub { try await self.portal.addLeader(meetupID: meetupID, npub: npub, session: self.requireSession()) }
            else if let removeUserID { try await self.portal.removeLeader(meetupID: meetupID, userID: removeUserID, session: self.requireSession()) }
        }
        await loadLeaders(meetupID)
    }
    func portalRSVP(eventID: Int, status: String) async {
        await perform { epoch in
            let response = try await self.portal.rsvp(eventID: eventID, status: status, session: self.requireSession())
            guard epoch == self.generation else { return }; self.notice = response.unwrapped["status"].string ?? "RSVP saved."
        }
    }
    func loadCalendar() async {
        await perform { epoch in
            let result = try await self.calendar.calendar(relays: self.relays)
            guard epoch == self.generation else { return }; self.entries = result
        }
    }
    func publishCalendar(_ draft: CalendarEventDraft, existing: NostrCalendarEntry? = nil) async {
        await perform { epoch in
            let result = try await self.calendar.publish(draft, existing: existing, signer: self.requireSigner(), relays: self.relays)
            guard epoch == self.generation else { return }
            self.entries.removeAll { $0.id == result.id }; self.entries.append(result); self.notice = "Event accepted by a relay."
        }
    }
    func cancel(_ entry: NostrCalendarEntry) async {
        await perform { epoch in
            try await self.calendar.cancel(entry, signer: self.requireSigner(), relays: self.relays)
            guard epoch == self.generation else { return }; self.entries.removeAll { $0.id == entry.id }
        }
    }
    func rsvp(_ entry: NostrCalendarEntry, status: String) async {
        await perform { _ in try await self.calendar.rsvp(entry, status: status, signer: self.requireSigner(), relays: self.relays) }
        await loadRSVPs(entry)
    }
    func loadRSVPs(_ entry: NostrCalendarEntry) async {
        await perform { epoch in
            let result = try await self.calendar.rsvps(entry, relays: self.relays)
            guard epoch == self.generation else { return }; self.rsvpEvents = result
        }
    }
    func loadRooms() async {
        await perform { epoch in
            let result = try await self.calendar.rooms()
            guard epoch == self.generation else { return }; self.rooms = result
        }
    }
    func loadMessages(room: String? = nil, entry: NostrCalendarEntry? = nil) async {
        let epoch = generation
        let request = UUID(); chatRequest = request
        do {
            let result = try await calendar.messages(room: room, eventAddress: entry?.id, relays: relays)
            guard epoch == generation, request == chatRequest, !Task.isCancelled else { return }; messages = result
            if let room, let signer {
                let result = try await calendar.isMember(room: room, publicKey: signer.publicKey())
                guard epoch == generation, request == chatRequest, !Task.isCancelled else { return }; member = result
            }
            if let last = result.last, let key = room ?? entry?.id {
                var marks = try KeychainStore.read(account: readMarksKey).map { try JSONDecoder().decode([String: Int].self, from: $0) } ?? [:]
                marks[key] = last.createdAt
                try KeychainStore.write(JSONEncoder().encode(marks), account: readMarksKey)
            }
        } catch { if epoch == generation, request == chatRequest, !Task.isCancelled { self.error = error.localizedDescription } }
    }
    func changeMembership(room: String, join: Bool) async {
        await perform { _ in try await self.calendar.membership(room: room, join: join, signer: self.requireSigner()) }
        await loadMessages(room: room)
    }
    func send(_ text: String, room: String? = nil, entry: NostrCalendarEntry? = nil) async -> Bool {
        let succeeded = await perform { _ in try await self.calendar.send(text: text, room: room, entry: entry, signer: self.requireSigner(), relays: self.relays) }
        guard succeeded else { return false }
        await loadMessages(room: room, entry: entry); return true
    }
    private func requireSigner() throws -> any NostrSigner { guard let signer else { throw BadgeStoreError.noSigner }; return signer }
    private func requireSession() throws -> PortalSession { guard let session, signer != nil else { throw PortalAPIError.identityMismatch }; return session }
    @discardableResult private func perform(_ operation: (Int) async throws -> Void) async -> Bool {
        guard !busy else { return false }; busy = true; error = nil
        let epoch = generation
        defer { if epoch == generation { busy = false } }
        do { try await operation(epoch); return epoch == generation }
        catch { if epoch == generation { self.error = error.localizedDescription }; return false }
    }
}

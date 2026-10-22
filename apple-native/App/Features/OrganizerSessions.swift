import SwiftUI
import MeetupNostr

struct OrganizerHubView: View {
    @Environment(AppStore.self) private var app
    @Environment(WorkflowStore.self) private var workflow
    @Environment(BadgeStore.self) private var wallet
    @State private var meetupID = ""
    @State private var blockHeight = ""
    @State private var hours = 4
    @State private var eventJSON = ""
    @State private var eventTitle = ""
    @State private var error: String?
    @State private var selectedMeetup = ""
    @State private var selectedEvent = ""
    @State private var location = NearbyLocationController()
    private var deviceLocation: BadgeCoordinate? {
        guard location.status == .ready, let fix = location.fix, abs(fix.timestamp.timeIntervalSinceNow) <= 120 else { return nil }
        return try? BadgeCoordinate(latitude: fix.point.latitude, longitude: fix.point.longitude)
    }
    var body: some View {
        Form {
            Section("Issuance location") {
                Button { Task { await location.request() } } label: { Label("Use current organizer location", systemImage: "location") }
                if location.status == .requesting { ProgressView("Finding current location") }
                Text(LocalizedStringKey(deviceLocation == nil ? "A current location is required to start issuance." : "Current location ready.")).font(.caption)
                Text("Meetup issuance checks this meetup’s portal reference within 50 km; calendar event issuance checks the signed venue within 5 km.").font(.caption).foregroundStyle(.secondary)
            }
            if let session = wallet.session {
                Section("Active organizer session") {
                    Text(session.meetupID).font(.headline)
                    Text(Date(timeIntervalSince1970: Double(session.expiresAt)), format: .dateTime.day().month().hour().minute())
                    TimelineView(.periodic(from: .now, by: 1)) { context in
                        let now = Int(context.date.timeIntervalSince1970)
                        if let payload = try? session.qr(now: now) {
                            BadgeQRView(payload: payload)
                            Text("QR changes in \(10 - now % 10) seconds").font(.caption)
                            ShareLink(item: payload) { Label("Share current rolling payload", systemImage: "square.and.arrow.up") }
                        } else { Label("Session expired", systemImage: "clock.badge.exclamationmark") }
                    }
                    ShareLink(item: BadgeWire.canonical(session.payload)) { Label("Export static NFC payload", systemImage: "wave.3.right") }
                    Text("The base badge has a Schnorr signature. Rolling nonce timing is an additional proximity check; it does not authenticate a scanner's location.").font(.caption).foregroundStyle(.secondary)
                    if let marker = wallet.badges.first(where: { $0.isOrganizer && $0.sig == session.payload["s"]?.string }) {
                        NavigationLink { CollectedBadgeDetailView(badge: marker) } label: { Label("Organizer participation", systemImage: "person.crop.circle.badge.checkmark") }
                        Text("Your organizer record stays in your wallet after this session ends. Open it to share attendance with your network. It does not increase your reputation.").font(.caption).foregroundStyle(.secondary)
                    }
                    BadgeActionButton("End session", systemImage: "stop.circle") { try await wallet.endSession() }
                }
            }
            Section("Create signed meetup session") {
                Picker("Meetup", selection: $selectedMeetup) {
                    Text("Choose a meetup").tag("")
                    ForEach(app.meetups) { meetup in Text(meetup.name.isEmpty ? meetup.city : meetup.name).tag(meetup.id) }
                }.onChange(of: selectedMeetup) {
                    if let meetup = app.meetups.first(where: { $0.id == selectedMeetup }) {
                        meetupID = BadgeSecurity.meetupID(city: meetup.city.isEmpty ? meetup.name : meetup.city, country: meetup.country)
                    }
                }
                TextField("Signed meetup ID, e.g. wien-at", text: $meetupID).autocorrectionDisabled()
                TextField("Bitcoin block height", text: $blockHeight)
                Stepper("Validity: \(hours) hours", value: $hours, in: 1...4)
                BadgeActionButton("Create organizer session", systemImage: "qrcode") {
                    guard let block = Int(blockHeight), block >= 0 else { throw BadgeProtocolError.malformed }
                    try await wallet.startSession(meetupID: meetupID, blockHeight: block, hours: hours, deviceLocation: deviceLocation)
                }.disabled(meetupID.isEmpty || blockHeight.isEmpty || deviceLocation == nil)
                Text("Requires verified registry authority or the independently verified organizer progression threshold.").font(.caption).foregroundStyle(.secondary)
                BadgeActionButton("Refresh organizer registry", systemImage: "arrow.clockwise") { try await wallet.refreshRegistry() }
            }
            Section("Calendar event issuance") {
                Button("Refresh signed calendar events") { Task { await workflow.loadCalendar() } }
                Picker("Signed calendar event", selection: $selectedEvent) {
                    Text("Choose an event").tag("")
                    ForEach(workflow.entries) { entry in Text(entry.title).tag(entry.id) }
                }
                BadgeActionButton("Start issuance for selected event", systemImage: "ticket") {
                    guard let entry = workflow.entries.first(where: { $0.id == selectedEvent }) else { throw BadgeProtocolError.malformed }
                    try await wallet.startEventSession(event: entry.event, title: entry.title, blockHeight: Int(blockHeight) ?? 0, deviceLocation: deviceLocation)
                }.disabled(selectedEvent.isEmpty || deviceLocation == nil)
                NavigationLink("Manage calendar events") { NostrEventsView() }
                NavigationLink("Portal meetup management") { PortalManagementView() }
            }
            DisclosureGroup("Advanced signed event JSON") {
                TextField("Event title", text: $eventTitle)
                TextEditor(text: $eventJSON).frame(minHeight: 110).font(.caption.monospaced())
                BadgeActionButton("Verify event issuer chain and start", systemImage: "ticket") {
                    let event = try NostrEvent.decodeJSON(Data(eventJSON.utf8))
                    try await wallet.startEventSession(event: event, title: eventTitle, blockHeight: Int(blockHeight) ?? 0, deviceLocation: deviceLocation)
                }.disabled(eventJSON.isEmpty || eventTitle.isEmpty || deviceLocation == nil)
                Text("Paste the signed calendar event. The creator must have registry authority, badge issuance must be enabled, and your key must be a current or time-authorized issuer.").font(.caption).foregroundStyle(.secondary)
            }
            if !wallet.status.isEmpty { Text(LocalizedStringKey(wallet.status)).font(.caption) }
        }.navigationTitle("Organizer")
        .task { if blockHeight.isEmpty, let height = app.bitcoin?.height { blockHeight = String(height) } }
        .onDisappear { location.cancel() }
    }
}

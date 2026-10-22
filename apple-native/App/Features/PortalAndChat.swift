import SwiftUI
import MeetupNostr
import MeetupCore

struct WorkflowNotice: View {
    @Environment(WorkflowStore.self) private var workflow
    var body: some View {
        if let error = workflow.error { Text(LocalizedStringKey(error)).foregroundStyle(.red).textSelection(.enabled) }
        if let notice = workflow.notice { Text(LocalizedStringKey(notice)).foregroundStyle(.secondary) }
        if workflow.busy { ProgressView() }
    }
}

struct PortalManagementView: View {
    @Environment(WorkflowStore.self) private var workflow
    @State private var manualToken = ""
    var body: some View {
        List {
            Section { WorkflowNotice() }
            if workflow.session == nil {
                Section("Portal sign in") {
                    NavigationLink("Signing identity") { IdentitySetupView() }
                    Button("Sign in with Nostr") { Task { await workflow.login() } }.disabled(workflow.signer == nil || workflow.busy)
                    SecureField("Personal access token", text: $manualToken).autocorrectionDisabled()
                    Button("Use token") { Task { await workflow.login(manualToken: manualToken); manualToken = "" } }.disabled(manualToken.isEmpty || workflow.busy)
                }
            } else {
                Section("Managed meetups") {
                    ForEach(workflow.managedMeetups) { meetup in
                        NavigationLink {
                            PortalMeetupManagementView(meetup: meetup)
                        } label: { Label(meetup.name, systemImage: meetup.isLeader ? "person.badge.key" : "person.3") }
                    }
                    if workflow.managedMeetups.isEmpty { Text("No managed meetups returned by the portal.").foregroundStyle(.secondary) }
                }
                Section("Managed dates") {
                    ForEach(Array(workflow.managedEvents.enumerated()), id: \.offset) { _, event in
                        if let meetupID = event["meetup_id"].integer {
                            NavigationLink {
                                PortalEventEditor(meetupID: meetupID, existing: event)
                            } label: { Text(event["start"].string ?? "Meetup date") }
                        }
                    }
                }
                Button("Sign out", role: .destructive) { Task { await workflow.logout() } }
            }
        }.navigationTitle("Portal").refreshable { await workflow.loadPortal() }
            .task { if workflow.session != nil { await workflow.loadPortal() } }
    }
}
struct PortalMeetupManagementView: View {
    @Environment(WorkflowStore.self) private var workflow
    let meetup: ManagedMeetup
    @State private var npub = ""
    var body: some View {
        List {
            Section { WorkflowNotice(); NavigationLink("Create meetup date") { PortalEventEditor(meetupID: meetup.id) } }
            if meetup.isLeader {
                Section("Meetup leaders") {
                    ForEach(Array(workflow.leaders.enumerated()), id: \.offset) { _, leader in
                        HStack {
                            VStack(alignment: .leading) { Text(leader["name"].string ?? "Leader"); Text(leader["npub"].string ?? "").font(.caption).textSelection(.enabled) }
                            Spacer()
                            if let id = leader["id"].integer {
                                Button("Remove", role: .destructive) { Task { await workflow.changeLeader(meetupID: meetup.id, removeUserID: id) } }
                            }
                        }
                    }
                    TextField("Leader npub", text: $npub).autocorrectionDisabled()
                    Button("Add leader") { Task { await workflow.changeLeader(meetupID: meetup.id, npub: npub); if workflow.error == nil { npub = "" } } }.disabled(npub.isEmpty || workflow.busy)
                }
            }
        }.navigationTitle(meetup.name).task { if meetup.isLeader { await workflow.loadLeaders(meetup.id) } }
    }
}
struct PortalEventEditor: View {
    @Environment(WorkflowStore.self) private var workflow
    let meetupID: Int
    var existing: JSONValue? = nil
    @State private var start = Date()
    @State private var location = ""
    @State private var description = ""
    @State private var link = ""
    @State private var recurrence = ""
    @State private var recurrenceEnd = Date().addingTimeInterval(30 * 86400)
    @State private var interval = 1
    var body: some View {
        Form {
            Section { WorkflowNotice() }
            Section("Meetup date") {
                DatePicker("Start", selection: $start)
                TextField("Location", text: $location)
                TextField("Description", text: $description, axis: .vertical).lineLimit(3...8)
                TextField("Website", text: $link).autocorrectionDisabled()
            }
            Section("Recurrence") {
                Picker("Repeat", selection: $recurrence) {
                    Text("Once").tag(""); Text("Daily").tag("daily"); Text("Weekly").tag("weekly")
                    Text("Monthly").tag("monthly"); Text("Yearly").tag("yearly"); Text("Custom").tag("custom")
                }
                if !recurrence.isEmpty {
                    Stepper("Interval: \(interval)", value: $interval, in: 1...100)
                    DatePicker("Repeat until", selection: $recurrenceEnd, in: start...)
                }
            }
            Button(LocalizedStringKey(existing == nil ? "Create date" : "Update date")) {
                let f = ISO8601DateFormatter()
                let draft = PortalEventDraft(meetupID: meetupID, start: f.string(from: start), location: location, description: description, link: link,
                                             recurrenceType: recurrence.isEmpty ? nil : recurrence, recurrenceInterval: recurrence.isEmpty ? nil : interval,
                                             recurrenceEndDate: recurrence.isEmpty ? nil : f.string(from: recurrenceEnd))
                Task { await workflow.savePortalEvent(draft, eventID: existing?["id"].integer) }
            }.disabled(workflow.busy)
        }.navigationTitle(existing == nil ? "Create date" : "Edit date")
            .onAppear {
                if let existing {
                    start = existing["start"].string.flatMap { ISO8601DateFormatter().date(from: $0) } ?? Date()
                    location = existing["location"].string ?? ""; description = existing["description"].string ?? ""; link = existing["link"].string ?? ""
                }
            }
    }
}

struct NostrEventsView: View {
    @Environment(WorkflowStore.self) private var workflow
    @State private var query = ""
    var body: some View {
        List {
            Section { WorkflowNotice(); NavigationLink("Publish an event") { NostrEventEditor() } }
            ForEach(workflow.entries.filter { query.isEmpty || ($0.title + $0.location).localizedStandardContains(query) }) { entry in
                NavigationLink {
                    NostrEventDetailView(entry: entry)
                } label: {
                    VStack(alignment: .leading) { Text(entry.title).font(.headline); Text(entry.start, style: .date); Text(entry.location).font(.caption).foregroundStyle(.secondary) }
                }
            }
        }.navigationTitle("Nostr calendar").searchable(text: $query)
            .task { await workflow.loadCalendar() }.refreshable { await workflow.loadCalendar() }
    }
}
struct NostrEventDetailView: View {
    @Environment(WorkflowStore.self) private var workflow
    let entry: NostrCalendarEntry
    var body: some View {
        List {
            Section {
                Text(entry.title).font(.title.bold()); Text(entry.start, style: .date)
                if !entry.allDay { Text(entry.start, style: .time) }
                Text(entry.location); Text(entry.event.content).textSelection(.enabled); WorkflowNotice()
            }
            Section("Participation") {
                ForEach(["accepted", "tentative", "declined"], id: \.self) { status in
                    Button(LocalizedStringKey(status.capitalized)) { Task { await workflow.rsvp(entry, status: status) } }.disabled(workflow.signer == nil || workflow.busy)
                }
                ForEach(workflow.rsvpEvents, id: \.id) { event in
                    HStack { Text(String(event.pubkey.prefix(12))).font(.caption); Spacer(); Text(event.tags.first { $0.count > 1 && $0[0] == "status" }?[1] ?? "") }
                }
            }
            Section("Actions") {
                NavigationLink("Event chat") { NativeChatView(entry: entry) }
                NativeCalendarButton(event: entry.meetupEvent)
                NavigationLink("Edit event") { NostrEventEditor(existing: entry) }
                Button("Cancel event", role: .destructive) { Task { await workflow.cancel(entry) } }.disabled(workflow.signer == nil || workflow.busy)
            }
        }.navigationTitle(entry.title).task { await workflow.loadRSVPs(entry) }
    }
}
struct NostrEventEditor: View {
    @Environment(WorkflowStore.self) private var workflow
    var existing: NostrCalendarEntry? = nil
    @State private var title = ""
    @State private var description = ""
    @State private var location = ""
    @State private var start = Date()
    @State private var end = Date().addingTimeInterval(7200)
    @State private var hasEnd = false
    @State private var allDay = false
    @State private var badges = false
    @State private var image = ""
    @State private var issuers = ""
    @State private var latitude = ""
    @State private var longitude = ""
    var body: some View {
        Form {
            Section { WorkflowNotice(); if workflow.signer == nil { NavigationLink("Unlock a signing identity") { IdentitySetupView() } } }
            Section("Event") {
                TextField("Title", text: $title); TextField("Description", text: $description, axis: .vertical).lineLimit(3...8)
                TextField("Location", text: $location)
                NavigationLink("Choose location") { NativeVenuePicker(location: $location, latitude: $latitude, longitude: $longitude) }
                Toggle("All day", isOn: $allDay).disabled(existing != nil)
                DatePicker("Start", selection: $start, displayedComponents: allDay ? [.date] : [.date, .hourAndMinute])
                Toggle("End date", isOn: $hasEnd)
                if hasEnd { DatePicker("End", selection: $end, in: start..., displayedComponents: allDay ? [.date] : [.date, .hourAndMinute]) }
            }
            Section("Attendance badge") {
                Toggle("Enable badge", isOn: $badges)
                if badges {
                    TextField("Badge image URL", text: $image)
                    TextField("Issuer public keys, comma separated", text: $issuers, axis: .vertical)
                    TextField("Latitude", text: $latitude).autocorrectionDisabled()
                    TextField("Longitude", text: $longitude).autocorrectionDisabled()
                    Text("Select the badge venue on the map. Signed coordinates are checked when an issuer starts a session.").font(.caption)
                }
            }
            Button(LocalizedStringKey(existing == nil ? "Publish event" : "Update event")) {
                let currentIssuers = Array(Set(issuers.split(separator: ",").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) })).sorted()
                var former: [String: Int] = [:]
                for tag in existing?.event.tags ?? [] where tag.count >= 5 && tag[0] == "p" && tag[3] == "former_issuer" {
                    if let cutoff = Int(tag[4]) { former[tag[1]] = max(former[tag[1]] ?? 0, cutoff) }
                }
                for tag in existing?.event.tags ?? [] where tag.count > 3 && tag[0] == "p" && tag[3] == "issuer" && !currentIssuers.contains(tag[1]) {
                    former[tag[1]] = Int(Date().timeIntervalSince1970)
                }
                for issuer in currentIssuers { former.removeValue(forKey: issuer) }
                let coordinate: BadgeCoordinate?
                if let lat = Double(latitude), let lon = Double(longitude) { coordinate = try? BadgeCoordinate(latitude: lat, longitude: lon) } else { coordinate = nil }
                let draft = CalendarEventDraft(title: title, description: description, start: start, end: hasEnd ? end : nil,
                                               allDay: allDay, location: location, identifier: existing?.identifier ?? UUID().uuidString,
                                               badgeEnabled: badges, badgeImage: image,
                                               issuers: currentIssuers, formerIssuers: former, coordinate: coordinate)
                Task { await workflow.publishCalendar(draft, existing: existing) }
            }.disabled(workflow.signer == nil || workflow.busy || title.isEmpty)
        }.navigationTitle("Calendar event").onAppear {
            if let existing {
                title = existing.title; description = existing.event.content; location = existing.location; start = existing.start
                end = existing.end ?? start.addingTimeInterval(7200); hasEnd = existing.end != nil; allDay = existing.allDay
                badges = existing.event.tags.contains(["badge", "true"])
                image = existing.event.tags.first { $0.count > 1 && $0[0] == "badge_image" }?[1] ?? ""
                issuers = existing.event.tags.filter { $0.count > 3 && $0[0] == "p" && $0[3] == "issuer" }.map { $0[1] }.joined(separator: ",")
                if let tag = existing.event.tags.first(where: { $0.count >= 3 && $0[0] == "g" }) { latitude = tag[1]; longitude = tag[2] }
            }
        }
    }
}

struct NativeChatDirectoryView: View {
    @Environment(WorkflowStore.self) private var workflow
    var body: some View {
        List {
            Section { WorkflowNotice(); if workflow.signer == nil { NavigationLink("Signing identity") { IdentitySetupView() } } }
            ForEach(workflow.rooms, id: \.id) { event in
                if let room = event.tags.first(where: { $0.count > 1 && $0[0] == "d" })?[1] {
                    NavigationLink {
                        NativeChatView(room: room)
                    } label: { Text(event.tags.first { $0.count > 1 && $0[0] == "name" }?[1] ?? room) }
                }
            }
        }.navigationTitle("Meetup chat").task { await workflow.loadRooms() }.refreshable { await workflow.loadRooms() }
    }
}
struct NativeChatView: View {
    @Environment(WorkflowStore.self) private var workflow
    var room: String? = nil
    var entry: NostrCalendarEntry? = nil
    @State private var text = ""
    var body: some View {
        VStack {
            List {
                Section { WorkflowNotice() }
                if let room {
                    Button(LocalizedStringKey(workflow.member ? "Leave room" : "Join room")) { Task { await workflow.changeMembership(room: room, join: !workflow.member) } }.disabled(workflow.signer == nil || workflow.busy)
                }
                ForEach(workflow.messages, id: \.id) { event in
                    VStack(alignment: .leading, spacing: 5) {
                        HStack { Text(String(event.pubkey.prefix(12))).font(.caption.monospaced()); Spacer(); Text(Date(timeIntervalSince1970: TimeInterval(event.createdAt)), style: .time).font(.caption) }
                        Text(event.content).textSelection(.enabled)
                    }
                }
            }
            HStack {
                TextField("Message", text: $text, axis: .vertical).lineLimit(1...5)
                Button("Send") { let message = text; Task { if await workflow.send(message, room: room, entry: entry) { text = "" } } }
                    .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || workflow.signer == nil || workflow.busy)
            }.padding()
        }.navigationTitle(entry?.title ?? room ?? "Chat")
            .task(id: room ?? entry?.id) {
                while !Task.isCancelled {
                    await workflow.loadMessages(room: room, entry: entry)
                    do { try await Task.sleep(for: .seconds(5)) } catch { return }
                }
            }
            .refreshable { await workflow.loadMessages(room: room, entry: entry) }
    }
}

extension NostrCalendarEntry {
    var meetupEvent: MeetupEvent {
        MeetupEvent(id: id, title: title, description: event.content, start: start, end: end, location: location,
                    nostrAddress: id, isAllDay: allDay, calendarTimeZoneID: allDay ? "UTC" : nil)
    }
}

struct PortalRSVPView: View {
    @Environment(WorkflowStore.self) private var workflow
    let eventID: Int
    var body: some View {
        VStack(alignment: .leading) {
            Text("Portal RSVP").font(.headline)
            WorkflowNotice()
            HStack {
                ForEach(["attending", "maybe", "none"], id: \.self) { status in
                    Button(LocalizedStringKey(status.capitalized)) { Task { await workflow.portalRSVP(eventID: eventID, status: status) } }.buttonStyle(.bordered)
                }
            }.disabled(workflow.session == nil || workflow.busy)
            if workflow.session == nil { NavigationLink("Sign in to the portal") { PortalManagementView() } }
        }
    }
}
struct NostrEventLookupView: View {
    @Environment(WorkflowStore.self) private var workflow
    let address: String
    var body: some View {
        Group {
            if let entry = workflow.entries.first(where: { $0.id == address }) { NostrEventDetailView(entry: entry) }
            else { VStack { WorkflowNotice(); ProgressView(); Text("Loading the calendar event…") } }
        }.task { await workflow.loadCalendar() }
    }
}

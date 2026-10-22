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
                            } label: { Text(event["start_iso"].string ?? event["start"].string ?? "Meetup date") }
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
    @State private var removingLeader: Int?
    var body: some View {
        List {
            Section { WorkflowNotice(); NavigationLink("Create meetup date") { PortalEventEditor(meetupID: meetup.id) } }
            Section("Managed dates") {
                ForEach(Array(workflow.managedEvents.filter { $0["meetup_id"].integer == meetup.id }.enumerated()), id: \.offset) { _, event in
                    NavigationLink { PortalEventEditor(meetupID: meetup.id, existing: event) }
                    label: { Text(event["start_iso"].string ?? event["start"].string ?? "Meetup date") }
                }
            }
            NavigationLink("Meetup chat") { NativeChatDirectoryView(meetupID: String(meetup.id)) }
            if meetup.isLeader {
                Section("Meetup leaders") {
                    ForEach(Array(workflow.leaders.enumerated()), id: \.offset) { _, leader in
                        HStack {
                            VStack(alignment: .leading) { Text(leader["name"].string ?? "Leader"); Text(leader["nostr"].string ?? leader["npub"].string ?? "").font(.caption).textSelection(.enabled) }
                            Spacer()
                            if leader["is_creator"].boolean == true { Text("Meetup creator").font(.caption).foregroundStyle(.secondary) }
                            else if let id = leader["id"].integer {
                                Button("Remove", role: .destructive) { removingLeader = id }.disabled(workflow.busy)
                            }
                        }
                    }
                    TextField("Leader npub", text: $npub).autocorrectionDisabled()
                    Button("Add leader") { Task { await workflow.changeLeader(meetupID: meetup.id, npub: npub.trimmingCharacters(in: .whitespacesAndNewlines)); if workflow.error == nil { npub = "" } } }.disabled(npub.isEmpty || workflow.busy)
                }
            }
        }.navigationTitle(meetup.name).task { if meetup.isLeader { await workflow.loadLeaders(meetup.id) } }
            .confirmationDialog("Remove meetup leader?", isPresented: Binding(get: { removingLeader != nil }, set: { if !$0 { removingLeader = nil } })) {
                Button("Remove", role: .destructive) {
                    if let id = removingLeader { Task { await workflow.changeLeader(meetupID: meetup.id, removeUserID: id) } }
                    removingLeader = nil
                }
            }
    }
}
struct PortalEventEditor: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(WorkflowStore.self) private var workflow
    let meetupID: Int
    var existing: JSONValue? = nil
    @State private var start = Date()
    @State private var location = ""
    @State private var description = ""
    @State private var link = ""
    @State private var recurrence = ""
    @State private var recurrenceWeekday = ""
    @State private var recurrencePosition = ""
    @State private var recurrenceEnd = Date().addingTimeInterval(30 * 86400)
    @State private var interval = 1
    @State private var loaded = false
    @State private var invalidSource = false
    var body: some View {
        Form {
            Section { WorkflowNotice(); if invalidSource { Text("The existing portal date is invalid. Saving is disabled to preserve it.").foregroundStyle(.red) } }
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
                    if recurrence == "monthly" || recurrence == "custom" {
                        TextField("Recurrence weekday", text: $recurrenceWeekday).autocorrectionDisabled()
                        TextField("Recurrence position", text: $recurrencePosition).autocorrectionDisabled()
                    }
                    Stepper("Interval: \(interval)", value: $interval, in: 1...100)
                    DatePicker("Repeat until", selection: $recurrenceEnd, in: start...)
                }
            }
            Button(LocalizedStringKey(existing == nil ? "Create date" : "Update date")) {
                let f = ISO8601DateFormatter()
                let draft = PortalEventDraft(meetupID: meetupID, start: f.string(from: start), location: location, description: description, link: link,
                                             recurrenceType: recurrence.isEmpty ? nil : recurrence,
                                             recurrenceDayOfWeek: recurrence.isEmpty || recurrenceWeekday.isEmpty ? nil : recurrenceWeekday,
                                             recurrenceDayPosition: recurrence.isEmpty || recurrencePosition.isEmpty ? nil : recurrencePosition, recurrenceInterval: recurrence.isEmpty ? nil : interval,
                                             recurrenceEndDate: recurrence.isEmpty ? nil : f.string(from: recurrenceEnd))
                Task { if await workflow.savePortalEvent(draft, eventID: existing?["id"].integer) { dismiss() } }
            }.disabled(workflow.busy || invalidSource)
        }.navigationTitle(existing == nil ? "Create date" : "Edit date")
            .onAppear {
                guard !loaded else { return }; loaded = true
                if let existing {
                    guard let id = existing["id"].integer, id > 0, existing["meetup_id"].integer == meetupID,
                          let saved = DateParsing.portal(existing["start_iso"].string ?? existing["start"].string) else { invalidSource = true; return }
                    start = saved
                    location = existing["location"].string ?? ""; description = existing["description"].string ?? ""; link = existing["link"].string ?? ""
                    recurrence = existing["recurrence_type"].string ?? ""; interval = existing["recurrence_interval"].integer ?? 1
                    guard ["", "daily", "weekly", "monthly", "yearly", "custom"].contains(recurrence), (1...100).contains(interval) else { invalidSource = true; return }
                    recurrenceWeekday = existing["recurrence_day_of_week"].string ?? ""
                    recurrencePosition = existing["recurrence_day_position"].string ?? ""
                    if let rawEnd = existing["recurrence_end_date"].string, !rawEnd.isEmpty {
                        guard let savedEnd = DateParsing.portal(rawEnd), savedEnd >= start else { invalidSource = true; return }
                        recurrenceEnd = savedEnd
                    } else { recurrenceEnd = start.addingTimeInterval(30 * 86400) }
                }
            }
    }
}

struct NostrEventsView: View {
    @Environment(WorkflowStore.self) private var workflow
    @State private var query = ""
    @State private var onlyMyResponses = false
    var body: some View {
        @Bindable var workflow = workflow
        List {
            Section { WorkflowNotice(); NavigationLink("Publish an event") { NostrEventEditor() } }
            Section {
                Toggle("Worldwide events", isOn: $workflow.calendarWorldwide).disabled(workflow.busy || onlyMyResponses)
                Toggle("My responses", isOn: $onlyMyResponses).disabled(workflow.publicKey == nil || workflow.busy)
            }
            ForEach(workflow.entries.filter { (!onlyMyResponses || workflow.myRSVPs[$0.id] != nil) && (query.isEmpty || ($0.title + $0.location).localizedStandardContains(query)) }) { entry in
                NavigationLink {
                    NostrEventDetailView(entry: entry)
                } label: {
                    VStack(alignment: .leading) {
                        Text(entry.title).font(.headline)
                        Text(entry.start, format: entry.meetupEvent.displayFormat(.dateTime.year().month().day()))
                        Text(entry.location).font(.caption).foregroundStyle(.secondary)
                        if let status = workflow.myRSVPs[entry.id] { Text(LocalizedStringKey(status.capitalized)).font(.caption) }
                        if let count = workflow.eventUnreadCounts[entry.id], count > 0 { Label(String(count), systemImage: "bubble.left").font(.caption).accessibilityLabel("Unread messages") }
                    }
                }
            }
        }.navigationTitle("Nostr calendar").searchable(text: $query)
            .task { await workflow.loadCalendar(onlyMyResponses: onlyMyResponses) }.refreshable { await workflow.loadCalendar(onlyMyResponses: onlyMyResponses) }
            .onChange(of: workflow.calendarWorldwide) { Task { await workflow.loadCalendar(onlyMyResponses: onlyMyResponses) } }
            .onChange(of: onlyMyResponses) { Task { await workflow.loadCalendar(onlyMyResponses: onlyMyResponses) } }
    }
}
struct NostrEventDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(WorkflowStore.self) private var workflow
    let entry: NostrCalendarEntry
    var body: some View {
        let entry = workflow.entries.first { $0.id == self.entry.id } ?? self.entry
        List {
            Section {
                Text(entry.title).font(.title.bold()); Text(entry.start, format: entry.meetupEvent.displayFormat(.dateTime.year().month().day())).accessibilityIdentifier("calendar.civilDate")
                if !entry.allDay { Text(entry.start, style: .time) }
                Text(entry.location); Text(entry.event.content).textSelection(.enabled); WorkflowNotice()
            }
            Section("Participation") {
                if let status = workflow.myRSVPs[entry.id] ?? workflow.rsvpEvents.first(where: { $0.pubkey == workflow.publicKey })?.tags.first(where: { $0.count > 1 && $0[0] == "status" })?[1] {
                    LabeledContent("Your RSVP") { Text(LocalizedStringKey(status.capitalized)) }
                }
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
                if workflow.publicKey == entry.event.pubkey {
                    NavigationLink("Edit event") { NostrEventEditor(existing: entry) }
                    Button("Cancel event", role: .destructive) { Task { if await workflow.cancel(entry) { dismiss() } } }.disabled(workflow.busy)
                }
            }
        }.navigationTitle(entry.title).task { await workflow.loadRSVPs(entry) }
    }
}
struct NostrEventEditor: View {
    @Environment(\.dismiss) private var dismiss
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
    @State private var loaded = false
    var body: some View {
        Form {
            Section { WorkflowNotice(); if workflow.signer == nil { NavigationLink("Unlock a signing identity") { IdentitySetupView() } } }
            Section("Event") {
                TextField("Title", text: $title).accessibilityIdentifier("calendar.title"); TextField("Description", text: $description, axis: .vertical).lineLimit(3...8)
                TextField("Location", text: $location).accessibilityIdentifier("calendar.location")
                NavigationLink("Choose location") { NativeVenuePicker(location: $location, latitude: $latitude, longitude: $longitude) }.accessibilityIdentifier("calendar.chooseLocation")
                Toggle("All day", isOn: $allDay).disabled(existing != nil)
                DatePicker("Start", selection: $start, displayedComponents: allDay ? [.date] : [.date, .hourAndMinute]).environment(\.timeZone, allDay ? TimeZone(secondsFromGMT: 0)! : .current)
                Toggle("End date", isOn: $hasEnd)
                if hasEnd { DatePicker("End", selection: $end, in: start..., displayedComponents: allDay ? [.date] : [.date, .hourAndMinute]).environment(\.timeZone, allDay ? TimeZone(secondsFromGMT: 0)! : .current) }
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
                Task { if await workflow.publishCalendar(draft, existing: existing) { dismiss() } }
            }.disabled(workflow.signer == nil || workflow.busy || title.isEmpty)
        }.navigationTitle("Calendar event").onAppear {
            guard !loaded else { return }; loaded = true
            if let existing {
                title = existing.title; description = existing.event.content; location = existing.location; start = existing.start
                end = existing.end ?? start.addingTimeInterval(7200); hasEnd = existing.end != nil; allDay = existing.allDay
                badges = existing.event.tags.contains(["badge", "true"])
                image = existing.event.tags.first { $0.count > 1 && $0[0] == "badge_image" }?[1] ?? ""
                issuers = existing.event.tags.filter { $0.count > 3 && $0[0] == "p" && $0[3] == "issuer" }.map { $0[1] }.joined(separator: ",")
                if let tag = existing.event.tags.first(where: { $0.count >= 3 && $0[0] == "g" }) { latitude = tag[1]; longitude = tag[2] }
            }
        }
        .onChange(of: allDay) { _, allDay in
            guard existing == nil else { return }
            let utc = TimeZone(secondsFromGMT: 0)!
            let from = allDay ? TimeZone.current : utc, to = allDay ? utc : TimeZone.current
            if let value = CivilDateBridge.rebase(start, from: from, to: to) { start = value }
            if let value = CivilDateBridge.rebase(end, from: from, to: to) { end = value }
        }
    }
}

struct NativeChatDirectoryView: View {
    var meetupID: String? = nil
    @Environment(WorkflowStore.self) private var workflow
    var body: some View {
        List {
            Section { WorkflowNotice(); if workflow.roomsAreLoading(for: meetupID) { ProgressView() }; if workflow.signer == nil { NavigationLink("Signing identity") { IdentitySetupView() } } }
            ForEach(workflow.rooms(for: meetupID), id: \.id) { event in
                if let room = event.tags.first(where: { $0.count > 1 && $0[0] == "d" })?[1] {
                    NavigationLink {
                        NativeChatView(room: room, roomName: event.tags.first { $0.count > 1 && $0[0] == "name" }?[1])
                    } label: {
                        HStack {
                            VStack(alignment: .leading) {
                                Text(event.tags.first { $0.count > 1 && $0[0] == "name" }?[1] ?? room)
                                if let about = event.tags.first(where: { $0.count > 1 && $0[0] == "about" })?[1], !about.isEmpty { Text(about).font(.caption).foregroundStyle(.secondary) }
                            }
                            Spacer()
                            if let count = workflow.unreadCounts[room], count > 0 { Text(count, format: .number).font(.caption.bold()).accessibilityLabel("Unread messages") }
                        }
                    }
                }
            }
            if workflow.rooms(for: meetupID).isEmpty && !workflow.roomsAreLoading(for: meetupID) && !workflow.busy { Text("No meetup chat room was returned.").foregroundStyle(.secondary) }
        }.navigationTitle("Meetup chat").task(id: meetupID) { await workflow.loadRooms(meetupID: meetupID) }.refreshable { await workflow.loadRooms(meetupID: meetupID) }
    }
}
struct NativeChatView: View {
    @Environment(WorkflowStore.self) private var workflow
    var room: String? = nil
    var roomName: String? = nil
    var entry: NostrCalendarEntry? = nil
    @State private var draft = ChatDraft()
    var body: some View {
        VStack {
            List {
                Section { WorkflowNotice(); if workflow.signer == nil { NavigationLink("Signing identity") { IdentitySetupView() } } }
                if let room {
                    if !workflow.member { Text("Join the room before sending a message.").foregroundStyle(.secondary) }
                    Button(LocalizedStringKey(workflow.member ? "Leave room" : "Join room")) { Task { await workflow.changeMembership(room: room, join: !workflow.member) } }.disabled(workflow.signer == nil || workflow.busy || workflow.activeChatKey != room)
                }
                if workflow.messages.isEmpty { Text("No messages yet.").foregroundStyle(.secondary) }
                ForEach(workflow.messages, id: \.id) { event in
                    VStack(alignment: .leading, spacing: 5) {
                        HStack { Text(String(event.pubkey.prefix(12))).font(.caption.monospaced()); Spacer(); Text(Date(timeIntervalSince1970: TimeInterval(event.createdAt)), style: .time).font(.caption) }
                        Text(event.content).textSelection(.enabled)
                    }
                }
            }
            HStack {
                TextField("Message", text: Binding(get: { draft.text }, set: { draft.update($0) }), axis: .vertical).lineLimit(1...5).accessibilityIdentifier("chat.message")
                Button("Send") { let snapshot = draft.snapshot; Task { let sent = await workflow.send(snapshot.text, room: room, entry: entry); draft.complete(snapshot, sent: sent) } }
                    .disabled(draft.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || workflow.signer == nil || workflow.busy || (room != nil && (!workflow.member || workflow.activeChatKey != room)))
            }.padding()
        }.navigationTitle(entry?.title ?? roomName ?? room ?? "Chat")
            .task(id: room ?? entry?.id) {
                while !Task.isCancelled {
                    await workflow.loadMessages(room: room, entry: entry)
                    do { try await Task.sleep(for: .seconds(5)) } catch { return }
                }
            }
            .refreshable { await workflow.loadMessages(room: room, entry: entry) }
            .onChange(of: room ?? entry?.id) { draft.update("") }
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
        VStack(alignment: .leading, spacing: 8) {
            Text("Portal RSVP").font(.headline)
            WorkflowNotice()
            if let summary = workflow.portalRSVPs[eventID] {
                if let count = summary.count { LabeledContent("Attendees") { Text(count, format: .number) } }
                if let count = summary.maybeCount { LabeledContent("Maybe") { Text(count, format: .number) } }
                if workflow.session != nil, let status = summary.status { LabeledContent("Your RSVP") { Text(LocalizedStringKey(status.capitalized)) } }
                ForEach(Array(summary.attendees.enumerated()), id: \.offset) { _, attendee in
                    Text(attendee.name.isEmpty ? attendee.publicKey : attendee.name).font(.subheadline).textSelection(.enabled)
                }
            }
            if let error = workflow.portalRSVPErrors[eventID] { Text(LocalizedStringKey(error)).foregroundStyle(.red) }
            HStack {
                ForEach(["attending", "maybe", "none"], id: \.self) { status in
                    Button(LocalizedStringKey(status.capitalized)) { Task { await workflow.portalRSVP(eventID: eventID, status: status) } }.buttonStyle(.bordered)
                }
            }.disabled(workflow.session == nil || workflow.busy)
            Button("Refresh participation") { Task { await workflow.loadPortalRSVP(eventID: eventID) } }
            if workflow.session == nil { NavigationLink("Sign in to the portal") { PortalManagementView() } }
        }.task(id: "\(eventID)|\(workflow.session != nil)") { await workflow.loadPortalRSVP(eventID: eventID) }
    }
}
struct NostrEventLookupView: View {
    @Environment(WorkflowStore.self) private var workflow
    let address: String
    @State private var loading = true
    var body: some View {
        Group {
            if let entry = workflow.entries.first(where: { $0.id == address }) { NostrEventDetailView(entry: entry) }
            else {
                VStack {
                    WorkflowNotice()
                    if loading { ProgressView(); Text("Loading the calendar event…") }
                    else { Text("The calendar event is unavailable or cancelled."); Button("Retry") { Task { loading = true; await workflow.loadCalendarEntry(address); loading = false } } }
                }
            }
        }.task(id: address) { loading = true; await workflow.loadCalendarEntry(address); loading = false }
    }
}

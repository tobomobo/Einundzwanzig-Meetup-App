import SwiftUI
import MapKit
import MeetupCore

struct EventRow: View {
    let event: MeetupEvent
    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            VStack(spacing: 1) {
                Text(event.start, format: event.displayFormat(.dateTime.day())).font(.title2.bold().monospacedDigit())
                Text(event.start, format: event.displayFormat(.dateTime.month(.abbreviated))).font(.caption.weight(.semibold))
            }
            .frame(width: 52).padding(.vertical, 10)
            .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
            VStack(alignment: .leading, spacing: 5) {
                Text(event.title).font(.headline).foregroundStyle(.primary)
                if event.isAllDay { Text("event.allDay").font(.subheadline).foregroundStyle(.secondary) }
                else { Text(event.start, format: .dateTime.weekday().hour().minute()).font(.subheadline).foregroundStyle(.secondary) }
                if !event.location.isEmpty {
                    Label(event.location, systemImage: "mappin.and.ellipse").font(.caption).foregroundStyle(.secondary)
                }
                if event.id.hasPrefix("demo-") { Label("source.demo", systemImage: "testtube.2").font(.caption2).foregroundStyle(.secondary) }
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

struct EventsView: View {
    @Environment(AppStore.self) private var store
    @Environment(WorkflowStore.self) private var workflow
    var meetupID: String? = nil
    @State private var query = ""
    @State private var favoritesOnly = false
    @State private var showDayPicker = false
    @State private var selectedDay = Date()

    private var combined: [MeetupEvent] {
        guard !store.preferences.demo else { return store.events }
        let courses = store.learning.cache.courses.flatMap { course in course.events.map { $0.calendarEvent(course: course) } }
        let all = store.events + workflow.entries.map(\.meetupEvent) + courses
        return Array(Dictionary(all.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first }).values)
    }
    private var filtered: [MeetupEvent] {
        combined.filter { event in
            (meetupID == nil || event.meetupID == meetupID) &&
            (!favoritesOnly || event.meetupID.map { store.isFavorite($0) } == true) &&
            (!showDayPicker || Calendar.current.isDate(CalendarDisplayWindow.filterDate(for: event), inSameDayAs: selectedDay)) &&
            (query.isEmpty || "\(event.title) \(event.location) \(event.meetupName ?? "")".localizedStandardContains(query)) &&
            CalendarDisplayWindow.includes(event)
        }.sorted { $0.start < $1.start }
    }

    var body: some View {
        List {
            Section {
                CalendarSourceNotice()
                NavigationLink(value: AppRoute.plans) {
                    Label("nav.plans", systemImage: "calendar.badge.clock")
                        .badge(store.plans.count)
                }
            }
            Section("event.filter") {
                Toggle("meetups.favoritesOnly", isOn: $favoritesOnly)
                Toggle("event.chooseDay", isOn: $showDayPicker)
                if showDayPicker {
                    DatePicker("event.day", selection: $selectedDay, displayedComponents: .date)
                        .datePickerStyle(.graphical)
                }
            }
            Section("event.upcoming") {
                if filtered.isEmpty { EmptyState(title: "event.empty", symbol: "calendar", message: "event.empty.body") }
                ForEach(filtered) { event in
                    NavigationLink(value: AppRoute.event(event.id)) { EventRow(event: event) }
                }
            }
            Section {
                Text("event.sources.body").font(.caption).foregroundStyle(.secondary)
                NavigationLink("Nostr calendar and publishing") { NostrEventsView() }
                NavigationLink("Portal dates and management") { PortalManagementView() }
            }
        }
        .searchable(text: $query, prompt: "event.search")
        .navigationTitle("nav.events")
        .refreshable { await store.refresh(); await refreshAdditionalSources() }
        .task { await refreshAdditionalSources() }
        .overlay { if store.loading && combined.isEmpty { ProgressView() } }
    }
    private func refreshAdditionalSources() async {
        guard !store.preferences.demo, !UITestFixtureMode.enabled else { return }
        await store.learning.refresh()
        await workflow.loadCalendar()
    }
}

struct EventDetailView: View {
    @Environment(AppStore.self) private var store
    let event: MeetupEvent
    @State private var note = ""
    @State private var savedNotice = false

    var body: some View {
        List {
            Section {
                Text(event.title).font(.largeTitle.bold())
                Label {
                    if event.isAllDay { Text(event.start, format: event.displayFormat(.dateTime.weekday(.wide).day().month(.wide).year())); Text("event.allDay") }
                    else { Text(event.start, format: .dateTime.weekday(.wide).day().month(.wide).year().hour().minute()) }
                } icon: { Image(systemName: "calendar") }
                if let end = event.end, !event.isAllDay {
                    LabeledContent("event.ends") { Text(end, format: .dateTime.day().month().hour().minute()) }
                }
                if !event.location.isEmpty { Label(event.location, systemImage: "mappin.and.ellipse") }
                if !event.description.isEmpty { Text(event.description).textSelection(.enabled) }
                if event.id.hasPrefix("demo-") { Label("source.demo", systemImage: "testtube.2") }
            }
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text("plan.title").font(.headline)
                    Text("plan.localOnly").font(.subheadline).foregroundStyle(.secondary)
                }
                HStack {
                    ForEach(PlanStatus.allCases) { status in
                        Button {
                            savedNotice = store.savePlan(event: event, status: status, note: note)
                        } label: {
                            Label(LocalizedStringKey(status.title), systemImage: store.plan(for: event.id)?.status == status ? "checkmark.circle.fill" : "circle")
                        }.buttonStyle(.bordered)
                    }
                }
                TextField("plan.note", text: $note, axis: .vertical).lineLimit(3...6)
                    .onChange(of: note) { savedNotice = false }
                if let plan = store.plan(for: event.id) {
                    LabeledContent("plan.saved") { Text(LocalizedStringKey(plan.status.title)) }
                    Button("plan.saveNote") { savedNotice = store.savePlan(event: event, status: plan.status, note: note) }
                    Button("plan.remove", role: .destructive) { if store.savePlan(event: event, status: nil) { savedNotice = false } }
                }
                if savedNotice { Label("plan.savedDevice", systemImage: "checkmark").foregroundStyle(.secondary) }
            } header: { Text("nav.plans") }
            Section("event.actions") {
                NativeCalendarButton(event: event)
                if let url = event.url { Link("event.openPortal", destination: url); ShareLink(item: url) }
                NavigationLink("nav.chat") { NativeChatDirectoryView(meetupID: event.meetupID) }
                if let id = Int(event.id.replacingOccurrences(of: "portal-", with: "")) {
                    PortalRSVPView(eventID: id)
                } else if let address = event.nostrAddress {
                    NavigationLink("Event participation and chat") {
                        NostrEventLookupView(address: address)
                    }
                }
            }
            if let latitude = event.latitude, let longitude = event.longitude,
               (-90...90).contains(latitude), (-180...180).contains(longitude) {
                Section("meetup.location") {
                    Map(initialPosition: .region(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: latitude, longitude: longitude), span: MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02)))) {
                        Marker(event.title, coordinate: CLLocationCoordinate2D(latitude: latitude, longitude: longitude))
                    }.frame(height: 220).clipShape(RoundedRectangle(cornerRadius: 16))
                }
            }
        }
        .navigationTitle("event.detail")
        .onAppear { note = store.plan(for: event.id)?.note ?? "" }
    }
}

struct PlansView: View {
    @Environment(AppStore.self) private var store
    var body: some View {
        List {
            Section { Text("plan.localOnly").font(.subheadline).foregroundStyle(.secondary); CalendarSourceNotice() }
            if store.plans.isEmpty { EmptyState(title: "plan.empty", symbol: "calendar.badge.plus", message: "plan.empty.body") }
            ForEach(store.plans) { plan in
                NavigationLink(value: AppRoute.event(plan.event.id)) {
                    VStack(alignment: .leading, spacing: 8) {
                        EventRow(event: plan.event)
                        Label(LocalizedStringKey(plan.status.title), systemImage: "bookmark.fill").font(.caption).foregroundStyle(Color.accentColor)
                        if !plan.note.isEmpty { Text(plan.note).font(.subheadline).foregroundStyle(.secondary) }
                    }
                }
                .swipeActions {
                    Button("plan.remove", role: .destructive) { store.savePlan(event: plan.event, status: nil) }
                }
            }
        }.navigationTitle("nav.plans")
    }
}

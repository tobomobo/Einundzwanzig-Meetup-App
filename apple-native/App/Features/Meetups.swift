import SwiftUI
import MapKit
import MeetupCore

extension Meetup {
    var coordinate: CLLocationCoordinate2D? {
        guard let latitude, let longitude, latitude.isFinite, longitude.isFinite,
              (-90...90).contains(latitude), (-180...180).contains(longitude) else { return nil }
        return CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }
}

struct MeetupDirectoryView: View {
    @Environment(AppStore.self) private var store
    @State private var query = ""
    @State private var mode = "list"
    @State private var favoritesOnly = false
    @State private var selectedID: String?
    @State private var camera: MapCameraPosition = .automatic
    @State private var location = NearbyLocationController()
    @State private var places = NearbyPlaceSearch()
    @State private var origin: GeoPoint?
    @State private var originLabel = ""
    @State private var useRadius = false
    @State private var radius = 25.0
    @State private var placeQuery = ""
    @State private var showFilters = false
    @State private var dateMode = "any"
    @State private var dayFrom = Date()
    @State private var dayTo = Date()

    private var dateInterval: DateInterval? {
        guard dateMode != "any" else { return nil }
        let calendar = Calendar.current
        let start = calendar.startOfDay(for: dayFrom)
        let last = dateMode == "day" ? dayFrom : max(dayFrom, dayTo)
        guard let end = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: last)) else { return nil }
        return DateInterval(start: start, end: end)
    }
    private var results: [NearbyMeetupMatch] {
        NearbySearch.search(meetups: store.meetups, events: store.events, center: useRadius ? origin : nil,
                            radiusKilometers: radius, interval: dateInterval).filter {
            (!favoritesOnly || store.isFavorite($0.id)) &&
            (query.isEmpty || "\($0.meetup.name) \($0.meetup.city) \($0.meetup.country)".localizedStandardContains(query))
        }
    }
    var body: some View {
        VStack(spacing: 0) {
            Picker("meetups.view", selection: $mode) {
                Text("meetups.list").tag("list")
                Text("meetups.map").tag("map")
            }.pickerStyle(.segmented).padding(.horizontal).padding(.top)
            HStack {
                Button { showFilters = true } label: { Label("nearby.filters", systemImage: "slider.horizontal.3") }
                Spacer()
                if useRadius, origin != nil { Text("nearby.kilometers \(Int(radius))").font(.caption).foregroundStyle(.secondary) }
            }.padding()
            if mode == "map" { directoryMap } else { directoryList }
        }
        .navigationTitle("nav.meetups")
        .sheet(isPresented: $showFilters) {
            NavigationStack {
                Form { filters }.navigationTitle("nearby.filters")
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("action.done") { showFilters = false } } }
            }.presentationDetents([.large])
        }
        .searchable(text: $query, prompt: "meetups.search")
        .onChange(of: query) { camera = .automatic }
        .onChange(of: radius) { camera = .automatic }
        .onChange(of: location.fix) {
            if let fix = location.fix, location.status == .ready {
                origin = fix.point
                originLabel = store.text("nearby.here")
                useRadius = true
                camera = .automatic
            }
        }
        .onChange(of: dayFrom) { if dayTo < dayFrom { dayTo = dayFrom } }
        .onDisappear { location.cancel(); places.cancel() }
        .overlay { if store.loading && store.meetups.isEmpty { ProgressView() } }
    }
    private var directoryMap: some View {
        Map(position: $camera, selection: $selectedID) {
            if useRadius, let origin {
                Marker(originLabel, systemImage: "scope", coordinate: origin.coordinate).tint(Color.blue)
                MapCircle(center: origin.coordinate, radius: radius * 1000).foregroundStyle(Color.blue.opacity(0.08))
            }
            ForEach(results, id: \.id) { (result: NearbyMeetupMatch) in
                meetupMarker(result)
            }
        }
        .mapControls { MapCompass(); MapScaleView() }
        .safeAreaInset(edge: .bottom) {
            if let selectedID, let result = results.first(where: { $0.id == selectedID }) {
                NavigationLink(value: AppRoute.meetup(result.id)) { matchRow(result).padding().background(.background, in: RoundedRectangle(cornerRadius: 20)) }
                    .buttonStyle(.plain).padding()
            }
        }
    }
    @MapContentBuilder
    private func meetupMarker(_ result: NearbyMeetupMatch) -> some MapContent {
        if let coordinate = result.meetup.coordinate {
            Marker(result.meetup.name, systemImage: store.isFavorite(result.id) ? "star.fill" : "person.3.fill", coordinate: coordinate)
                .tint(Color.accentColor).tag(result.id)
        }
    }
    private var directoryList: some View {
                List {
                    SourceNotice()
                    if dateMode != "any" { CalendarSourceNotice() }
                    if results.isEmpty { EmptyState(title: "meetups.empty", symbol: "map", message: "nearby.empty") }
                    ForEach(results) { result in
                        HStack(spacing: 12) {
                            NavigationLink(value: AppRoute.meetup(result.id)) { matchRow(result) }
                            Button { store.toggleFavorite(result.id) } label: {
                                Image(systemName: store.isFavorite(result.id) ? "star.fill" : "star").foregroundStyle(Color.accentColor).padding(8)
                            }.buttonStyle(.borderless).accessibilityLabel(store.isFavorite(result.id) ? "favorite.remove" : "favorite.add")
                        }
                    }
                }.refreshable { await store.refresh() }
    }
    private var filters: some View {
        VStack(alignment: .leading, spacing: 14) {
            Toggle("meetups.favoritesOnly", isOn: $favoritesOnly)
            Button { places.cancel(); Task { await location.request() } } label: { Label("nearby.current", systemImage: "location") }
                .disabled(location.status == .requesting)
            if location.status == .requesting { ProgressView("nearby.locating") }
            if ![.idle, .ready, .requesting].contains(location.status) {
                Text(LocalizedStringKey("nearby.location." + location.status.rawValue)).font(.caption).foregroundStyle(.secondary)
            }
            HStack {
                TextField("nearby.place", text: $placeQuery).textFieldStyle(.roundedBorder).onSubmit { searchPlace() }
                Button("nearby.findPlace") { searchPlace() }.disabled(placeQuery.trimmingCharacters(in: .whitespacesAndNewlines).count < 2 || places.loading)
            }
            if places.loading { ProgressView("action.loading") }
            if places.failed { Text("nearby.placeFailed").font(.caption).foregroundStyle(.secondary) }
            if places.searched && !places.loading && !places.failed && places.results.isEmpty { Text("nearby.placeEmpty").font(.caption).foregroundStyle(.secondary) }
            ForEach(places.results) { place in
                Button {
                    location.cancel()
                    origin = place.point
                    originLabel = place.name
                    useRadius = true
                    places.cancel()
                    camera = .automatic
                } label: { Label(place.name, systemImage: "mappin.and.ellipse") }.buttonStyle(.plain)
            }
            if origin != nil {
                Toggle("nearby.limitRadius", isOn: $useRadius)
                Text(originLabel).font(.caption).foregroundStyle(.secondary)
                if useRadius {
                    Slider(value: $radius, in: 5...500, step: 5) { Text("nearby.radius") }
                    Text("nearby.kilometers \(Int(radius))").font(.caption)
                    Button("nearby.clearOrigin") { location.cancel(); origin = nil; useRadius = false; camera = .automatic }
                }
            }
            Picker("nearby.date", selection: $dateMode) {
                Text("nearby.anyDate").tag("any")
                Text("nearby.oneDay").tag("day")
                Text("nearby.dateRange").tag("range")
            }
            if dateMode != "any" {
                DatePicker("nearby.from", selection: $dayFrom, displayedComponents: .date)
                if dateMode == "range" { DatePicker("nearby.to", selection: $dayTo, in: dayFrom..., displayedComponents: .date) }
            }
            Text("nearby.privacy").font(.caption).foregroundStyle(.secondary)
        }
    }
    private func searchPlace() {
        location.cancel()
        Task { await places.find(placeQuery) }
    }
    private func matchRow(_ result: NearbyMeetupMatch) -> some View {
        HStack(spacing: 12) {
            MeetupAvatar(meetup: result.meetup)
            VStack(alignment: .leading, spacing: 5) {
                Text(result.meetup.name).font(.headline)
                Text("\(result.meetup.city) · \(result.meetup.country)").font(.caption).foregroundStyle(.secondary)
                if let distance = result.distanceKilometers { Text("nearby.distance \(distance.formatted(.number.precision(.fractionLength(1))))").font(.caption).foregroundStyle(.secondary) }
                if let event = result.nextEvent { Text(event.start, format: event.displayFormat(.dateTime.day().month().year())).font(.caption).foregroundStyle(.secondary) }
            }
        }
    }
}

extension GeoPoint {
    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: latitude, longitude: longitude) }
}

struct MeetupDetailView: View {
    @Environment(AppStore.self) private var store
    let meetup: Meetup
    var body: some View {
        List {
            Section {
                HStack(spacing: 18) {
                    MeetupAvatar(meetup: meetup, size: 74)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(meetup.name).font(.title2.bold())
                        Label("\(meetup.city) · \(meetup.country)", systemImage: "mappin.and.ellipse").foregroundStyle(.secondary)
                    }
                }.padding(.vertical, 12)
                Button { store.toggleFavorite(meetup.id) } label: {
                    Label(store.isFavorite(meetup.id) ? "favorite.remove" : "favorite.add", systemImage: store.isFavorite(meetup.id) ? "star.fill" : "star")
                }
                if !meetup.description.isEmpty { Text(meetup.description).textSelection(.enabled) }
                else { Text("meetup.descriptionPortal").foregroundStyle(.secondary) }
            }
            Section("nav.events") {
                let dates = store.events.filter { $0.meetupID == meetup.id }.sorted { $0.start < $1.start }
                if dates.isEmpty { Text("event.none").foregroundStyle(.secondary) }
                ForEach(dates) { event in NavigationLink(value: AppRoute.event(event.id)) { EventRow(event: event) } }
            }
            if let coordinate = meetup.coordinate {
                Section("meetup.location") {
                    Map(initialPosition: .region(MKCoordinateRegion(center: coordinate, span: MKCoordinateSpan(latitudeDelta: 0.04, longitudeDelta: 0.04)))) {
                        Marker(meetup.name, coordinate: coordinate)
                    }.frame(height: 240).clipShape(RoundedRectangle(cornerRadius: 16))
                    Text("meetup.coordinateNote").font(.caption).foregroundStyle(.secondary)
                    Button("map.open") {
                        let item = MKMapItem(location: CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude), address: nil)
                        item.name = meetup.name
                        item.openInMaps()
                    }
                }
            }
            Section("resource.links") {
                if let url = meetup.portalURL { Link("resource.portal", destination: url); ShareLink(item: url) }
                if let url = meetup.telegramURL { Link("resource.telegram", destination: url) }
                NavigationLink("nav.chat", value: AppRoute.unavailable(.chat))
            }
        }
        .navigationTitle(meetup.city)
    }
}

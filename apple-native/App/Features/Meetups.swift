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

    private var filtered: [Meetup] {
        store.meetups.filter { meetup in
            (!favoritesOnly || store.isFavorite(meetup.id)) &&
            (query.isEmpty || "\(meetup.name) \(meetup.city) \(meetup.country)".localizedStandardContains(query))
        }.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    var body: some View {
        VStack(spacing: 0) {
            Picker("meetups.view", selection: $mode) {
                Text("meetups.list").tag("list")
                Text("meetups.map").tag("map")
            }.pickerStyle(.segmented).padding()
            if mode == "map" {
                Map(position: $camera, selection: $selectedID) {
                    ForEach(filtered) { meetup in
                        if let coordinate = meetup.coordinate {
                            Marker(meetup.name, systemImage: store.isFavorite(meetup.id) ? "star.fill" : "person.3.fill", coordinate: coordinate)
                                .tint(.accentColor).tag(meetup.id)
                        }
                    }
                }
                .mapControls { MapCompass(); MapScaleView() }
                .safeAreaInset(edge: .bottom) {
                    if let selectedID, let meetup = filtered.first(where: { $0.id == selectedID }) {
                        NavigationLink(value: AppRoute.meetup(meetup.id)) {
                            HStack {
                                MeetupAvatar(meetup: meetup)
                                VStack(alignment: .leading) { Text(meetup.name).font(.headline); Text(meetup.city).font(.caption) }
                                Spacer()
                                Image(systemName: "chevron.right")
                            }.padding().background(.background, in: RoundedRectangle(cornerRadius: 20))
                        }.buttonStyle(.plain).padding()
                    }
                }
                .onChange(of: query) { camera = .automatic }
            } else {
                List {
                    SourceNotice()
                    Toggle("meetups.favoritesOnly", isOn: $favoritesOnly)
                    if filtered.isEmpty {
                        EmptyState(title: "meetups.empty", symbol: "map", message: "meetups.empty.body")
                    }
                    ForEach(filtered) { meetup in
                        HStack(spacing: 12) {
                            NavigationLink(value: AppRoute.meetup(meetup.id)) {
                                HStack(spacing: 12) {
                                    MeetupAvatar(meetup: meetup)
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(meetup.name).font(.headline)
                                        Text("\(meetup.city) · \(meetup.country)").font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                            }
                            Button { store.toggleFavorite(meetup.id) } label: {
                                Image(systemName: store.isFavorite(meetup.id) ? "star.fill" : "star")
                                    .foregroundStyle(Color.accentColor).padding(8)
                            }.buttonStyle(.borderless)
                                .accessibilityLabel(store.isFavorite(meetup.id) ? "favorite.remove" : "favorite.add")
                        }
                    }
                }.refreshable { await store.refresh() }
            }
        }
        .navigationTitle("nav.meetups")
        .searchable(text: $query, prompt: "meetups.search")
        .toolbar {
            if mode == "map" {
                Toggle(isOn: $favoritesOnly) { Label("meetups.favoritesOnly", systemImage: favoritesOnly ? "star.fill" : "star") }
                    .toggleStyle(.button)
            }
        }
        .overlay { if store.loading && store.meetups.isEmpty { ProgressView() } }
    }
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

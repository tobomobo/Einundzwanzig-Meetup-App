import SwiftUI
import MapKit
import MeetupCore

struct NativeVenuePicker: View {
    @Binding var location: String
    @Binding var latitude: String
    @Binding var longitude: String
    @State private var camera: MapCameraPosition = .automatic
    @State private var query = ""
    @State private var search = NearbyPlaceSearch()
    @State private var device = NearbyLocationController()
    private var selected: CLLocationCoordinate2D? {
        guard let lat = Double(latitude), let lon = Double(longitude), abs(lat) <= 90, abs(lon) <= 180 else { return nil }
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                TextField("Search a venue", text: $query).textFieldStyle(.roundedBorder).onSubmit { Task { await search.find(query) } }
                Button("Search") { Task { await search.find(query) } }.disabled(search.loading || query.count < 2)
                Button { Task { await device.request() } } label: { Image(systemName: "location") }.accessibilityLabel("Use current location")
            }.padding()
            MapReader { proxy in
                Map(position: $camera) {
                    if let selected { Marker(location.isEmpty ? "Selected location" : location, coordinate: selected) }
                }.onTapGesture { position in
                    if let coordinate = proxy.convert(position, from: .local) { choose(coordinate) }
                }
            }
            if search.loading || device.status == .requesting { ProgressView().padding() }
            if !search.results.isEmpty {
                List(search.results) { place in
                    Button(place.name) { location = place.name; choose(place.point.coordinate) }
                }.frame(maxHeight: 220)
            }
            Text("Tap the map or choose a search result to set the venue.").font(.caption).padding()
            TextField("Location", text: $location).textFieldStyle(.roundedBorder).padding().accessibilityIdentifier("venue.name")
            HStack {
                TextField("Latitude", text: $latitude).accessibilityIdentifier("venue.latitude")
                TextField("Longitude", text: $longitude).accessibilityIdentifier("venue.longitude")
            }.textFieldStyle(.roundedBorder).padding(.horizontal)
        }.navigationTitle("Choose location")
            .onChange(of: device.fix) {
                if let fix = device.fix, device.status == .ready { choose(fix.point.coordinate) }
            }
            .onDisappear { search.cancel(); device.cancel() }
    }
    private func choose(_ coordinate: CLLocationCoordinate2D) {
        latitude = String(coordinate.latitude); longitude = String(coordinate.longitude)
        camera = .region(MKCoordinateRegion(center: coordinate, span: MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02)))
    }
}

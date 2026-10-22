import CoreLocation
import MapKit
import MeetupCore
import Observation

struct NearbyLocationFix: Equatable, Sendable {
    let point: GeoPoint
    let accuracy: Double
    let timestamp: Date
}

@MainActor @Observable
final class NearbyLocationController: NSObject, CLLocationManagerDelegate {
    enum Status: String { case idle, requesting, ready, denied, restricted, disabled, noFix, failed }
    private(set) var status: Status = .idle
    private(set) var fix: NearbyLocationFix?
    @ObservationIgnored private let manager = CLLocationManager()
    @ObservationIgnored private var timeout: Task<Void, Never>?
    @ObservationIgnored private var requested = false
    @ObservationIgnored private var generation = UUID()

    override init() {
        super.init()
        manager.desiredAccuracy = kCLLocationAccuracyKilometer
        manager.delegate = self
    }

    /// Never called on launch: the user explicitly chooses the current-location action.
    func request() async {
        cancel()
        fix = nil
        let requestID = UUID()
        generation = requestID
        status = .requesting
        let enabled = await Task.detached(priority: .userInitiated) { CLLocationManager.locationServicesEnabled() }.value
        guard generation == requestID, !Task.isCancelled else { return }
        guard enabled else { status = .disabled; return }
        requested = true
        switch manager.authorizationStatus {
        case .notDetermined: manager.requestWhenInUseAuthorization()
        case .authorizedAlways, .authorizedWhenInUse: beginFix()
        case .denied: requested = false; status = .denied
        case .restricted: requested = false; status = .restricted
        @unknown default: requested = false; status = .failed
        }
    }

    func cancel() {
        generation = UUID()
        requested = false
        timeout?.cancel()
        timeout = nil
        manager.stopUpdatingLocation()
        if status == .requesting { status = .idle }
    }

    private func beginFix() {
        guard requested else { return }
        let requestID = generation
        status = .requesting
        manager.requestLocation()
        timeout?.cancel()
        timeout = Task { @MainActor [weak self] in
            do { try await Task.sleep(for: .seconds(25)) } catch { return }
            guard let self, self.generation == requestID, self.requested else { return }
            self.requested = false
            self.manager.stopUpdatingLocation()
            self.status = .noFix
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor [weak self] in
            guard let self, self.requested else { return }
            switch self.manager.authorizationStatus {
            case .authorizedAlways, .authorizedWhenInUse: self.beginFix()
            case .denied: self.cancel(); self.status = .denied
            case .restricted: self.cancel(); self.status = .restricted
            case .notDetermined: break
            @unknown default: self.cancel(); self.status = .failed
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let candidate = locations.reversed().compactMap { location -> NearbyLocationFix? in
            let age = Date().timeIntervalSince(location.timestamp)
            guard location.horizontalAccuracy >= 0, age >= -30, age <= 600,
                  let point = GeoPoint(latitude: location.coordinate.latitude, longitude: location.coordinate.longitude) else { return nil }
            return NearbyLocationFix(point: point, accuracy: location.horizontalAccuracy, timestamp: location.timestamp)
        }.first
        Task { @MainActor [weak self] in
            guard let self, self.requested else { return }
            self.timeout?.cancel()
            self.requested = false
            self.manager.stopUpdatingLocation()
            self.fix = candidate
            self.status = candidate == nil ? .noFix : .ready
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: any Error) {
        let denied = (error as? CLError)?.code == .denied
        Task { @MainActor [weak self] in
            guard let self, self.requested else { return }
            self.cancel()
            self.status = denied ? .denied : .noFix
        }
    }
}

struct NearbyPlace: Identifiable, Hashable {
    let id: String
    let name: String
    let point: GeoPoint
}

@MainActor @Observable
final class NearbyPlaceSearch {
    private(set) var results: [NearbyPlace] = []
    private(set) var loading = false
    private(set) var failed = false
    private(set) var searched = false
    @ObservationIgnored private var search: MKLocalSearch?
    @ObservationIgnored private var generation = UUID()

    func find(_ text: String) async {
        cancel()
        let query = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard query.count >= 2 else { return }
        let requestID = UUID()
        generation = requestID
        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = String(query.prefix(200))
        request.resultTypes = [.address, .pointOfInterest]
        let operation = MKLocalSearch(request: request)
        search = operation
        loading = true
        do {
            let response = try await operation.start()
            guard requestID == generation, !Task.isCancelled else { return }
            var seen: Set<String> = []
            results = response.mapItems.compactMap { item in
                let coordinate = item.location.coordinate
                guard let name = item.name,
                      let point = GeoPoint(latitude: coordinate.latitude, longitude: coordinate.longitude) else { return nil }
                let id = "\(name)|\(point.latitude)|\(point.longitude)"
                guard seen.insert(id).inserted else { return nil }
                return NearbyPlace(id: id, name: name, point: point)
            }.prefix(8).map { $0 }
            loading = false
            searched = true
        } catch {
            guard requestID == generation, !Task.isCancelled else { return }
            failed = true
            searched = true
            loading = false
        }
    }
    func cancel() {
        generation = UUID()
        search?.cancel()
        search = nil
        results = []
        loading = false
        failed = false
        searched = false
    }
}

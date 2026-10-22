import Foundation

public struct BadgeCoordinate: Codable, Equatable, Sendable {
    public let latitude: Double
    public let longitude: Double
    public init(latitude: Double, longitude: Double) throws {
        guard latitude.isFinite, longitude.isFinite, abs(latitude) <= 90, abs(longitude) <= 180 else { throw BadgeProtocolError.malformed }
        self.latitude = latitude; self.longitude = longitude
    }
    public func distance(to other: Self) -> Double {
        let lat = (other.latitude - latitude) * .pi / 180, lon = (other.longitude - longitude) * .pi / 180
        let a = pow(sin(lat / 2), 2) + cos(latitude * .pi / 180) * cos(other.latitude * .pi / 180) * pow(sin(lon / 2), 2)
        return 6371 * 2 * atan2(sqrt(a), sqrt(max(0, 1 - a)))
    }
}
public enum BadgePresence {
    /// Missing location/reference is unverified; a measured distant position
    /// is contrary evidence and rejects collection. Compact la/lo is ignored.
    public static func participant(device: BadgeCoordinate?, reference: BadgeCoordinate?, measuredReference: Bool = false) throws -> Bool {
        guard let device, let reference else { return false }
        guard device.distance(to: reference) <= (measuredReference ? 5 : 50) else { throw BadgeProtocolError.tooFarAway }
        return true
    }
    public static func eventCoordinate(_ event: NostrEvent) -> BadgeCoordinate? {
        guard let g = event.tags.first(where: { $0.count >= 3 && $0[0] == "g" }),
              let lat = Double(g[1]), let lon = Double(g[2]), lat != 0 || lon != 0 else { return nil }
        return try? BadgeCoordinate(latitude: lat, longitude: lon)
    }
    public static func issuer(device: BadgeCoordinate?, reference: BadgeCoordinate?, event: Bool) throws {
        guard let device, let reference else { throw BadgeProtocolError.locationUnavailable }
        guard device.distance(to: reference) <= (event ? 5 : 50) else { throw BadgeProtocolError.tooFarAway }
    }
}

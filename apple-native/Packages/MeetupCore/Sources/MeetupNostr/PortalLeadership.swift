import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// HTTPS portal evidence is scoped to a signed meetup ID. It never grants
/// global registry authority merely because a key leads a different meetup.
public actor PortalLeadership {
    private let transport: any ProductHTTPTransport
    private var loaded: Date?
    private var leaders: [Int: Set<String>] = [:]
    public init(transport: any ProductHTTPTransport = ProductURLSessionTransport()) { self.transport = transport }
    public func isLeader(publicKey: String, meetupID: String) async throws -> Bool {
        guard let id = Int(meetupID), id > 0 else { return false }
        let npub = try NIP19.encodePublicKey(publicKey)
        if loaded == nil || Date().timeIntervalSince(loaded!) > 86400 {
            var request = URLRequest(url: URL(string: "https://portal.einundzwanzig.space/api/meetup-leaders")!, timeoutInterval: 8)
            request.setValue("application/json", forHTTPHeaderField: "Accept")
            let response = try await transport.send(request)
            guard response.status == 200, let rows = try JSONDecoder().decode(JSONValue.self, from: response.data).array else { throw PortalAPIError.invalidResponse }
            var fresh: [Int: Set<String>] = [:]
            for row in rows {
                guard let id = row["meetup_id"].integer, id > 0, let keys = row["npubs"].array else { continue }
                fresh[id] = Set(keys.compactMap(\.string).filter { (try? NIP19.decodePublicKey($0)) != nil })
            }
            guard !fresh.isEmpty else { throw PortalAPIError.invalidResponse }
            leaders = fresh; loaded = Date()
        }
        return leaders[id]?.contains(npub) == true
    }
}

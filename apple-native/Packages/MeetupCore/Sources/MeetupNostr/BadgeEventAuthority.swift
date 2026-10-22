import Foundation

public enum BadgeEventAuthority {
    public static func filter(for address: String) throws -> NostrFilter {
        let p = address.components(separatedBy: ":")
        guard p.count == 3, let kind = Int(p[0]), [31922,31923].contains(kind),
              (try? NostrHex.decode(p[1], byteCount: 32, field: "event author")) != nil, !p[2].isEmpty else { throw BadgeProtocolError.malformed }
        return NostrFilter(authors: [p[1]], kinds: [kind], limit: 20, tags: ["d": [p[2]]])
    }
    public static func verify(address: String, signer: String, issuedAt: Int, event: NostrEvent,
                              registry: RegistrySnapshot, now: Int) async -> Bool {
        guard let filter = try? filter(for: address), filter.matches(event), event.createdAt <= now + 300,
              await NostrCrypto().verify(event), registry.isAuthorized(event.pubkey),
              event.tags.contains(where: { $0.count > 1 && $0[0] == "badge" && $0[1].lowercased() == "true" }) else { return false }
        return signer == event.pubkey || event.tags.contains { tag in
            guard tag.count >= 4, tag[0] == "p", tag[1].lowercased() == signer else { return false }
            if tag[3] == "issuer" { return true }
            return tag.count >= 5 && tag[3] == "former_issuer" && (Int(tag[4]).map { issuedAt <= $0 } ?? false)
        }
    }
}

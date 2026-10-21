import Foundation

/// One tag-name constraint, with OR across its exact UTF-8 values. An array of
/// constraints preserves distinct Unicode spellings of names as well as values.
/// A Swift String-keyed Dictionary/Set would collapse some of those spellings.
public struct NostrTagFilter: Hashable, Sendable {
    public let name: String
    public let values: [String]

    public init(name: String, values: [String]) {
        self.name = name
        self.values = values
    }

    public static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.name.utf8.elementsEqual(rhs.name.utf8) && lhs.values.count == rhs.values.count &&
        zip(lhs.values, rhs.values).allSatisfy { $0.utf8.elementsEqual($1.utf8) }
    }

    public func hash(into hasher: inout Hasher) {
        hasher.combine(Data(name.utf8))
        hasher.combine(values.count)
        for value in values { hasher.combine(Data(value.utf8)) }
    }
}

/// Pure, local read-policy helpers for a future relay consumer. These do not
/// verify signatures or establish any organization/author authorization.
public enum NostrReadPolicy {
    /// AND across supplied fields/tag constraints, OR within each set/list.
    /// Tag names omit '#'. Only the first tag value is compared. Names and
    /// values compare exact UTF-8, without Unicode normalization.
    /// This intentionally has no network transport, subscription, or JSON parser.
    public static func matches(
        _ event: NostrEvent,
        ids: Set<String>? = nil,
        kinds: Set<Int>? = nil,
        authors: Set<String>? = nil,
        since: Int? = nil,
        until: Int? = nil,
        tagFilters: [NostrTagFilter] = []
    ) -> Bool {
        if let ids, !ids.contains(event.id) { return false }
        if let kinds, !kinds.contains(event.kind) { return false }
        if let authors, !authors.contains(event.pubkey) { return false }
        if let since, event.createdAt < since { return false }
        if let until, event.createdAt > until { return false }
        for filter in tagFilters {
            guard !filter.name.isEmpty, !filter.values.isEmpty,
                  event.tags.contains(where: { tag in
                      tag.count >= 2 && tag[0].utf8.elementsEqual(filter.name.utf8) &&
                      filter.values.contains(where: { $0.utf8.elementsEqual(tag[1].utf8) })
                  }) else { return false }
        }
        return true
    }

    /// Clock policy for choosing the latest replaceable event, independent of
    /// cryptographic validity. Defaults to the existing Dart client's 10 minutes.
    /// The caller supplies time explicitly; no clock is hidden inside verification.
    public static func replaceableCreatedAt(_ event: NostrEvent, now: Int, maximumFutureSkew: Int = 600) -> Int? {
        guard now >= 0, maximumFutureSkew >= 0 else { return nil }
        let (latest, overflow) = now.addingReportingOverflow(maximumFutureSkew)
        guard !overflow, event.createdAt <= latest else { return nil }
        return event.createdAt
    }
}

import Foundation

/// A successful send acknowledges its captured revision, never later typing.
struct ChatDraft {
    struct Snapshot: Sendable { let text: String; let revision: UInt64 }
    private(set) var text = ""
    private var revision: UInt64 = 0
    var snapshot: Snapshot { Snapshot(text: text, revision: revision) }
    mutating func update(_ text: String) { self.text = text; revision &+= 1 }
    mutating func complete(_ snapshot: Snapshot, sent: Bool) {
        if sent, snapshot.revision == revision, snapshot.text == text { update("") }
    }
}

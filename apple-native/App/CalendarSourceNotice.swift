import SwiftUI
import MeetupCore

struct CalendarSourceNotice: View {
    @Environment(AppStore.self) private var store
    private var failed: Bool { store.networkIssues.contains("source.events.failed") }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if store.preferences.demo {
                Label("source.demo", systemImage: "testtube.2").font(.caption.weight(.semibold))
                Text("source.demo.body").font(.caption).foregroundStyle(.secondary)
            } else {
                if let snapshot = store.cache.calendarSnapshot {
                    Label(snapshot.source == .portal ? "calendar.source.portal" : "calendar.source.ical", systemImage: snapshot.source == .portal ? "calendar" : "arrow.triangle.branch")
                        .font(.caption.weight(.semibold))
                    if failed || !store.calendarRefreshedThisSession { Text("calendar.source.cached").font(.caption).foregroundStyle(.secondary) }
                    HStack {
                        Text("calendar.source.fetched")
                        Text(snapshot.fetchedAt, format: .dateTime.day().month().year().hour().minute())
                    }.font(.caption).foregroundStyle(.secondary)
                    if !snapshot.warnings.isEmpty {
                        DisclosureGroup("calendar.source.notes") {
                            ForEach(Array(snapshot.warnings.enumerated()), id: \.offset) { _, warning in
                                Text(LocalizedStringKey("calendar.warning." + warning.cause.rawValue)).font(.caption).foregroundStyle(.secondary)
                            }
                        }.font(.caption)
                    }
                } else if !store.cache.events.isEmpty {
                    Label("calendar.source.legacyCache", systemImage: "internaldrive").font(.caption)
                } else { Text("calendar.source.awaiting").font(.caption).foregroundStyle(.secondary) }
                if failed {
                    Label("calendar.source.failed", systemImage: "wifi.exclamationmark").font(.caption).foregroundStyle(.secondary)
                    if let date = store.calendarLastAttempt { Text(date, format: .dateTime.hour().minute()).font(.caption).foregroundStyle(.secondary) }
                    Button("action.retry") { Task { await store.refresh() } }.font(.caption)
                }
            }
        }
    }
}

import SwiftUI
import MeetupCore

struct SourceNotice: View {
    @Environment(AppStore.self) private var store
    private var feedIssues: [String] { store.networkIssues.filter { $0 != "source.bitcoin.failed" } }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(store.preferences.demo ? "source.demo" : "source.portal", systemImage: store.preferences.demo ? "testtube.2" : "network")
                .font(.caption.weight(.semibold))
            if store.preferences.demo {
                Text("source.demo.body").font(.caption).foregroundStyle(.secondary)
            } else if !feedIssues.isEmpty {
                ForEach(feedIssues, id: \.self) { key in
                    Label(LocalizedStringKey(key), systemImage: "wifi.exclamationmark").font(.caption).foregroundStyle(.secondary)
                }
                Button("action.retry") { Task { await store.refresh() } }.font(.caption)
            } else if let date = store.cache.fetchedAt {
                Text(date, format: .dateTime.day().month().hour().minute()).font(.caption).foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

struct MissingContentView: View {
    var body: some View { ContentUnavailableView("state.notFound", systemImage: "magnifyingglass", description: Text("state.notFound.body")) }
}

struct EmptyState: View {
    let title: String
    let symbol: String
    let message: String
    var body: some View {
        ContentUnavailableView(LocalizedStringKey(title), systemImage: symbol, description: Text(LocalizedStringKey(message)))
    }
}

struct MeetupAvatar: View {
    let meetup: Meetup
    var size: CGFloat = 48
    var body: some View {
        AsyncImage(url: meetup.logoURL) { image in
            image.resizable().scaledToFit()
        } placeholder: {
            Image(systemName: "person.3.fill").font(.title3).foregroundStyle(Color.accentColor)
        }
        .frame(width: size, height: size)
        .background(Color.accentColor.opacity(0.08), in: RoundedRectangle(cornerRadius: 14))
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .accessibilityHidden(true)
    }
}

struct SectionTitle: View {
    let title: String
    let subtitle: String
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(LocalizedStringKey(title)).font(.title2.bold())
            Text(LocalizedStringKey(subtitle)).font(.subheadline).foregroundStyle(.secondary)
        }
    }
}

struct ReadableCard<Content: View>: View {
    let content: Content
    init(@ViewBuilder content: () -> Content) { self.content = content() }
    var body: some View {
        content.padding(20).frame(maxWidth: .infinity, alignment: .leading)
            .background(.background, in: RoundedRectangle(cornerRadius: 24))
            .overlay { RoundedRectangle(cornerRadius: 24).strokeBorder(.primary.opacity(0.08)) }
    }
}

/// Custom floating controls use the real SwiftUI 26 glass API. Content cards stay opaque.
struct GlassControlSurface: ViewModifier {
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    func body(content: Content) -> some View {
        if reduceTransparency {
            content.background(.background, in: Capsule()).overlay { Capsule().strokeBorder(.primary.opacity(0.25)) }
        } else {
            content.glassEffect(.regular.interactive(), in: .capsule)
        }
    }
}

struct IntegrationView: View {
    let integration: Integration
    var body: some View {
        List {
            Section {
                Label("integration.unavailable", systemImage: "wrench.and.screwdriver").font(.headline)
                Text(LocalizedStringKey(integration.explanation)).foregroundStyle(.secondary)
            }
            Section("security.boundary") {
                Text("security.noSigning")
            }
            if integration == .organizer || integration == .courses || integration == .eventPublishing {
                Link("resource.portal", destination: URL(string: "https://portal.einundzwanzig.space")!)
            }
            if integration == .plebrap { Link("resource.plebrap", destination: URL(string: "https://plebrap.de")!) }
            if integration == .donations { Link("resource.v4v", destination: URL(string: "https://plebrap.de/V4V/")!) }
        }
        .navigationTitle(LocalizedStringKey(integration.title))
    }
}

extension DashboardTile {
    var route: AppRoute {
        switch self {
        case .meetups: .meetups
        case .plans: .plans
        case .reputation: .reputation
        case .community: .community
        case .news: .news
        case .bitcoin: .bitcoin
        case .converter: .converter
        case .resources: .resources
        }
    }
}

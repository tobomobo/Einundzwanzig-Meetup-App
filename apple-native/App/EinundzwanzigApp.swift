import SwiftUI

@main
struct EinundzwanzigApp: App {
    @State private var store = AppStore()

    var body: some Scene {
        WindowGroup {
            AppRootView()
                .environment(store)
                .environment(\.locale, store.locale)
                .preferredColorScheme(store.colorScheme)
                .tint(Color.accentColor)
                .frame(minWidth: minimumWidth, minHeight: minimumHeight)
        }
        #if os(macOS)
        .defaultSize(width: 1160, height: 820)
        .commands {
            CommandMenu("menu.navigate") {
                Button("nav.home") { store.selectedSection = .home }.keyboardShortcut("1")
                Button("nav.badges") { store.selectedSection = .badges }.keyboardShortcut("2")
                Button("nav.events") { store.selectedSection = .events }.keyboardShortcut("3")
                Button("nav.meetups") { store.selectedSection = .meetups }.keyboardShortcut("4")
                Button("nav.community") { store.selectedSection = .community }.keyboardShortcut("5")
                Divider()
                Button("scan.title") { store.scanPresented = true }.keyboardShortcut("s", modifiers: [.command, .shift])
                Button("action.refresh") { Task { await store.refresh() } }.keyboardShortcut("r")
            }
        }
        #endif
        #if os(macOS)
        Settings {
            NavigationStack { SettingsView() }
                .environment(store)
                .environment(\.locale, store.locale)
                .preferredColorScheme(store.colorScheme)
                .frame(width: 620, height: 720)
        }
        #endif
    }

    private var minimumWidth: CGFloat? {
        #if os(macOS)
        760
        #else
        nil
        #endif
    }
    private var minimumHeight: CGFloat? {
        #if os(macOS)
        560
        #else
        nil
        #endif
    }
}

struct AppRootView: View {
    @Environment(AppStore.self) private var store

    var body: some View {
        @Bindable var store = store
        Group {
            if store.profile.onboarded {
                shell
            } else {
                NavigationStack { OnboardingView() }
            }
        }
        .sheet(isPresented: $store.scanPresented) {
            NavigationStack { BadgeImportView() }
                .presentationDetents([.large])
        }
        .sheet(isPresented: $store.settingsPresented) {
            NavigationStack { SettingsView() }
                .frame(idealWidth: 620, idealHeight: 720)
        }
        .alert("error.title", isPresented: Binding(get: { store.persistenceIssue != nil }, set: { if !$0 { store.persistenceIssue = nil } })) {
            Button("action.ok") { store.persistenceIssue = nil }
        } message: { Text(store.persistenceIssue ?? "") }
        .task { await store.refresh() }
    }

    @ViewBuilder private var shell: some View {
        @Bindable var store = store
        #if os(macOS)
        NavigationSplitView {
            List(selection: Binding<AppSection?>(get: { store.selectedSection }, set: { if let section = $0 { store.selectedSection = section } })) {
                Section {
                    ForEach(AppSection.allCases) { section in
                        Label(LocalizedStringKey(section.title), systemImage: section.symbol).tag(section)
                    }
                } header: {
                    Label("Einundzwanzig", systemImage: "bitcoinsign.circle.fill")
                }
                Section {
                    Button { store.scanPresented = true } label: { Label("scan.title", systemImage: "qrcode.viewfinder") }
                    Button { store.settingsPresented = true } label: { Label("settings.title", systemImage: "gearshape") }
                }
            }
            .navigationTitle("Einundzwanzig")
            .navigationSplitViewColumnWidth(min: 210, ideal: 240)
        } detail: {
            SectionNavigation(section: store.selectedSection).id(store.selectedSection)
        }
        #else
        TabView(selection: $store.selectedSection) {
            Tab("nav.home", systemImage: "house", value: AppSection.home) { SectionNavigation(section: .home) }
            Tab("nav.badges", systemImage: "seal", value: AppSection.badges) { SectionNavigation(section: .badges) }
            Tab("nav.events", systemImage: "calendar", value: AppSection.events) { SectionNavigation(section: .events) }
        }
        #endif
    }
}

struct SectionNavigation: View {
    @Environment(AppStore.self) private var store
    let section: AppSection

    var body: some View {
        NavigationStack {
            content
                .navigationDestination(for: AppRoute.self) { RouteDestination(route: $0) }
                .toolbar {
                    ToolbarItemGroup(placement: .automatic) {
                        Button { store.scanPresented = true } label: { Label("scan.title", systemImage: "qrcode.viewfinder") }
                        Button { store.settingsPresented = true } label: { Label("settings.title", systemImage: "gearshape") }
                    }
                }
        }
    }

    @ViewBuilder private var content: some View {
        switch section {
        case .home: HomeView()
        case .badges: BadgeWalletView()
        case .events: EventsView()
        case .meetups: MeetupDirectoryView()
        case .community: CommunityView()
        case .bitcoin: BitcoinView()
        }
    }
}

struct RouteDestination: View {
    @Environment(AppStore.self) private var store
    let route: AppRoute

    @ViewBuilder var body: some View {
        switch route {
        case .meetups: MeetupDirectoryView()
        case .meetup(let id):
            if let meetup = store.meetups.first(where: { $0.id == id }) { MeetupDetailView(meetup: meetup) }
            else { MissingContentView() }
        case .events(let id): EventsView(meetupID: id)
        case .event(let id):
            if let event = store.events.first(where: { $0.id == id }) ?? store.plans.first(where: { $0.id == id })?.event {
                EventDetailView(event: event)
            } else { MissingContentView() }
        case .plans: PlansView()
        case .badges: BadgeWalletView()
        case .collection(let name): BadgeWalletView(collection: name)
        case .badge(let id):
            if let badge = store.badges.first(where: { $0.id == id }) { BadgeDetailView(badge: badge) }
            else { MissingContentView() }
        case .badgeMap: BadgeMapView()
        case .reputation: ReputationView()
        case .community: CommunityView()
        case .news: NewsView()
        case .article(let id):
            if let article = store.articles.first(where: { $0.id == id }) { ArticleDetailView(article: article) }
            else { MissingContentView() }
        case .bitcoin: BitcoinView()
        case .converter: ConverterView()
        case .resources: ResourcesView()
        case .glossary: GlossaryView()
        case .unavailable(let integration): IntegrationView(integration: integration)
        }
    }
}

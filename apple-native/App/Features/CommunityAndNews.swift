import SwiftUI
import MeetupCore

struct CommunityView: View {
    var body: some View {
        List {
            Section {
                SectionTitle(title: "community.title", subtitle: "community.subtitle").padding(.vertical, 12)
                NavigationLink(value: AppRoute.news) { Label("nav.news", systemImage: "newspaper") }
                NavigationLink(value: AppRoute.meetups) { Label("nav.meetups", systemImage: "map") }
                NavigationLink(value: AppRoute.events(nil)) { Label("nav.events", systemImage: "calendar") }
                NavigationLink(value: AppRoute.resources) { Label("nav.resources", systemImage: "square.grid.2x2") }
            }
            Section("community.portal") {
                Link("resource.portal", destination: URL(string: "https://portal.einundzwanzig.space")!)
                NavigationLink("integration.courses", value: AppRoute.unavailable(.courses))
                NavigationLink("home.organizer", value: AppRoute.unavailable(.organizer))
            }
            Section("community.network") {
                NavigationLink("nav.reputation", value: AppRoute.reputation)
                NavigationLink("integration.socialGraph", value: AppRoute.unavailable(.socialGraph))
                NavigationLink("nav.chat", value: AppRoute.unavailable(.chat))
            }
            Section("community.culture") {
                Link("resource.podcast", destination: URL(string: "https://einundzwanzig.space/podcast/")!)
                Link("resource.shoutout", destination: URL(string: "https://shoutout.einundzwanzig.space")!)
                NavigationLink("resource.plebrap", value: AppRoute.unavailable(.plebrap))
                Link("resource.satoshiduell", destination: URL(string: "https://satoshiduell.de")!)
                Text("community.external").font(.caption).foregroundStyle(.secondary)
            }
        }.navigationTitle("nav.community")
    }
}

struct NewsView: View {
    @Environment(AppStore.self) private var store
    @State private var query = ""
    private var filtered: [NewsArticle] {
        store.articles.filter { query.isEmpty || "\($0.title) \($0.summary)".localizedStandardContains(query) }
    }
    var body: some View {
        List {
            Section {
                SourceNotice()
                Text("news.source").font(.caption).foregroundStyle(.secondary)
            }
            if filtered.isEmpty { EmptyState(title: "news.empty", symbol: "newspaper", message: "news.empty.body") }
            ForEach(filtered) { article in
                NavigationLink(value: AppRoute.article(article.id)) {
                    HStack(alignment: .top, spacing: 14) {
                        if let url = article.imageURL {
                            AsyncImage(url: url) { image in image.resizable().scaledToFill() } placeholder: { Color.accentColor.opacity(0.1) }
                                .frame(width: 80, height: 80).clipShape(RoundedRectangle(cornerRadius: 14)).accessibilityHidden(true)
                        }
                        VStack(alignment: .leading, spacing: 8) {
                            Text(article.title).font(.headline)
                            if !article.summary.isEmpty { Text(article.summary).font(.subheadline).lineLimit(3).foregroundStyle(.secondary) }
                            if let date = article.published { Text(date, format: .dateTime.day().month().year()).font(.caption).foregroundStyle(.secondary) }
                            if !store.preferences.lastReadArticleIDs.contains(article.id) { Text("news.unread").font(.caption2.weight(.bold)).foregroundStyle(Color.accentColor) }
                        }
                    }.padding(.vertical, 8)
                }
            }
        }
        .navigationTitle("nav.news")
        .searchable(text: $query, prompt: "news.search")
        .refreshable { await store.refresh() }
    }
}

struct ArticleDetailView: View {
    @Environment(AppStore.self) private var store
    let article: NewsArticle
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                if let url = article.imageURL {
                    AsyncImage(url: url) { image in image.resizable().scaledToFit() } placeholder: { Color.accentColor.opacity(0.08).frame(height: 180) }
                        .clipShape(RoundedRectangle(cornerRadius: 24)).accessibilityHidden(true)
                }
                Text(article.title).font(.largeTitle.bold()).textSelection(.enabled)
                HStack {
                    if let author = article.author { Text(author) }
                    if let date = article.published { Text(date, format: .dateTime.day().month().year()) }
                }.font(.caption).foregroundStyle(.secondary)
                Text(article.summary).font(.body).textSelection(.enabled)
                Text("news.summaryOnly").font(.caption).foregroundStyle(.secondary)
                Link(destination: article.url) { Label("news.readOriginal", systemImage: "safari") }.buttonStyle(.borderedProminent)
                ShareLink(item: article.url)
            }.padding(24).frame(maxWidth: 760).frame(maxWidth: .infinity)
        }
        .navigationTitle("news.article")
        .onAppear { store.markRead(article) }
    }
}

struct ResourcesView: View {
    var body: some View {
        List {
            Section("resource.links") {
                Link("resource.portal", destination: URL(string: "https://portal.einundzwanzig.space")!)
                Link("resource.news", destination: URL(string: "https://media.einundzwanzig.space/s/einundzwanzig-news")!)
                Link("resource.telegram", destination: URL(string: "https://t.me/einundzwanzig")!)
                Link("resource.podcast", destination: URL(string: "https://einundzwanzig.space/podcast/")!)
                Link("resource.membership", destination: URL(string: "https://verein.einundzwanzig.space/")!)
                Link("resource.shoutout", destination: URL(string: "https://shoutout.einundzwanzig.space")!)
                Link("resource.satoshiduell", destination: URL(string: "https://satoshiduell.de/")!)
            }
            Section("resource.tools") {
                NavigationLink("nav.glossary", value: AppRoute.glossary)
                NavigationLink("nav.bitcoin", value: AppRoute.bitcoin)
                NavigationLink("nav.converter", value: AppRoute.converter)
            }
            Section("integration.remaining") {
                ForEach([Integration.courses, .plebrap, .donations, .proofs, .humanity, .socialGraph, .organizer, .eventPublishing]) { item in
                    NavigationLink(LocalizedStringKey(item.title), value: AppRoute.unavailable(item))
                }
            }
        }.navigationTitle("nav.resources")
    }
}

struct GlossaryView: View {
    @Environment(AppStore.self) private var store
    @State private var query = ""
    private let entries = ["glWhatIsApp", "glHomeMeetup", "glNickname", "glFindMeetup", "glOffline", "glBadgeProof", "glRollingQr", "glOnSite", "glBlockHeight", "glTrustScore", "glHumanity", "glKeys", "glSigner", "glNcryptsec"]
    var body: some View {
        List {
            Section { Text("glossary.prototype").font(.caption).foregroundStyle(.secondary) }
            ForEach(entries.filter { query.isEmpty || AppLocalization.text($0 + "Title", language: store.preferences.language).localizedStandardContains(query) || AppLocalization.text($0 + "Body", language: store.preferences.language).localizedStandardContains(query) }, id: \.self) { key in
                DisclosureGroup {
                    Text(LocalizedStringKey(key + "Body")).textSelection(.enabled).padding(.vertical, 8)
                } label: { Text(LocalizedStringKey(key + "Title")).font(.headline) }
            }
        }.navigationTitle("nav.glossary").searchable(text: $query, prompt: "glossary.search")
    }
}

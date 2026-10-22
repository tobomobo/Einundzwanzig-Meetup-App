import SwiftUI
import MeetupCore

struct OnboardingView: View {
    @Environment(AppStore.self) private var store
    @State private var nickname = ""
    @State private var npub = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                Image("MeetupLogo").resizable().scaledToFit().frame(width: 100, height: 100)
                    .clipShape(RoundedRectangle(cornerRadius: 24)).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 10) {
                    Text("onboarding.eyebrow").font(.caption.weight(.bold)).foregroundStyle(Color.accentColor)
                    Text("onboarding.title").font(.largeTitle.bold())
                    Text("onboarding.body").foregroundStyle(.secondary)
                }
                ReadableCard {
                    VStack(alignment: .leading, spacing: 16) {
                        Text("profile.local").font(.headline)
                        TextField("profile.nickname", text: $nickname).textContentType(.nickname).accessibilityIdentifier("profile.nickname")
                            .textFieldStyle(.roundedBorder)
                        TextField("profile.npub.optional", text: $npub)
                            .textFieldStyle(.roundedBorder).autocorrectionDisabled()
                        Text("profile.public.body").font(.caption).foregroundStyle(.secondary)
                        Button("onboarding.continue") {
                            _ = store.saveProfile(nickname: nickname, npub: npub)
                        }
                        .buttonStyle(.borderedProminent).controlSize(.large).accessibilityIdentifier("onboarding.continue")
                        .disabled(nickname.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
                VStack(alignment: .leading, spacing: 12) {
                    NavigationLink("Create or import a signing identity") { IdentitySetupView() }
                    Button("onboarding.preview") {
                        if store.saveProfile(nickname: nickname.isEmpty ? "Preview" : nickname, npub: "") { store.setDemo(true) }
                    }.buttonStyle(.bordered)
                }
                Picker("settings.language", selection: Binding(get: { store.preferences.language }, set: { language in store.updatePreferences { $0.language = language } })) {
                    Text("language.system").tag("system")
                    Text("Deutsch").tag("de")
                    Text("English").tag("en")
                    Text("Español").tag("es")
                }
                .pickerStyle(.menu)
            }
            .padding(28).frame(maxWidth: 600)
            .frame(maxWidth: .infinity)
        }
        .background(Color.accentColor.opacity(0.035))
        .navigationTitle("Einundzwanzig")
        .toolbar { Button { store.settingsPresented = true } label: { Label("settings.title", systemImage: "gearshape") } }
        .onAppear { nickname = store.profile.nickname; npub = store.profile.npub }
    }
}

struct HomeView: View {
    @Environment(AppStore.self) private var store
    @Environment(BadgeStore.self) private var wallet
    private let columns = [GridItem(.adaptive(minimum: 155), spacing: 16)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 26) {
                hero
                SourceNotice()
                CalendarSourceNotice()
                GlassEffectContainer(spacing: 16) {
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 16) { discoverControl; scanControl }
                        VStack(alignment: .leading, spacing: 16) { discoverControl; scanControl }
                    }
                }
                VStack(alignment: .leading, spacing: 14) {
                    HStack {
                        Text("home.favorites").font(.title2.bold())
                        Spacer()
                        NavigationLink("action.edit", value: AppRoute.meetups).font(.subheadline)
                    }
                    if store.favorites.isEmpty {
                        ReadableCard {
                            VStack(alignment: .leading, spacing: 12) {
                                Label("home.chooseMeetups", systemImage: "star")
                                Text("home.favorites.body").font(.subheadline).foregroundStyle(.secondary)
                                NavigationLink("home.discover", value: AppRoute.meetups).buttonStyle(.borderedProminent)
                            }
                        }
                    } else {
                        ForEach(store.favorites) { meetup in FavoriteMeetupCard(meetup: meetup) }
                    }
                }
                LazyVGrid(columns: columns, alignment: .leading, spacing: 16) {
                    ForEach(store.preferences.dashboardOrder.filter { !store.preferences.hiddenTiles.contains($0) }) { tile in
                        NavigationLink(value: tile.route) {
                            VStack(alignment: .leading, spacing: 14) {
                                Image(systemName: tile.symbol).font(.title2).foregroundStyle(Color.accentColor)
                                Text(LocalizedStringKey(tile.title)).font(.headline).foregroundStyle(.primary)
                                Text(LocalizedStringKey("tile.\(tile.rawValue).body")).font(.caption).foregroundStyle(.secondary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            .frame(maxWidth: .infinity, minHeight: 110, alignment: .topLeading).padding(18)
                            .background(.background, in: RoundedRectangle(cornerRadius: 22))
                            .overlay { RoundedRectangle(cornerRadius: 22).strokeBorder(.primary.opacity(0.08)) }
                        }.buttonStyle(.plain)
                    }
                }
                NavigationLink("home.organizer", value: AppRoute.unavailable(.organizer)).font(.footnote)
            }
            .padding(20).frame(maxWidth: 1000).frame(maxWidth: .infinity)
        }
        .background(Color.accentColor.opacity(0.025))
        .navigationTitle("nav.home")
        .toolbar {
            #if DEBUG
            if UITestFixtureMode.enabled { NavigationLink { FixtureToolsView() } label: { Image(systemName: "hammer") }.accessibilityLabel("Fixture tools").accessibilityIdentifier("fixture.open") }
            #endif
        }
        .refreshable { await store.refresh() }
        .overlay { if store.loading && store.meetups.isEmpty { ProgressView().controlSize(.large) } }
    }

    private var discoverControl: some View {
        NavigationLink(value: AppRoute.meetups) {
            Label("home.discover", systemImage: "map").font(.subheadline.weight(.semibold)).padding(.horizontal, 18).padding(.vertical, 14)
        }.buttonStyle(.plain).modifier(GlassControlSurface())
    }
    private var scanControl: some View {
        Button { store.scanPresented = true } label: {
            Label("scan.short", systemImage: "qrcode.viewfinder").font(.subheadline.weight(.semibold)).padding(.horizontal, 18).padding(.vertical, 14)
        }.buttonStyle(.plain).modifier(GlassControlSurface())
    }

    private var hero: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top) {
                Label("Einundzwanzig", systemImage: "bitcoinsign.circle.fill").font(.headline)
                Spacer()
                NavigationLink(value: AppRoute.glossary) { Image(systemName: "questionmark.circle").font(.title3) }
                    .accessibilityLabel("nav.glossary")
            }
            HStack(spacing: 14) {
                ProfilePictureView(allowsLocalPicture: true, size: 52)
                Text("home.greeting \(store.profile.nickname)").font(.largeTitle.bold())
            }
                .fixedSize(horizontal: false, vertical: true)
            Text("home.tagline").font(.title3).foregroundStyle(.secondary)
            NavigationLink(value: AppRoute.reputation) {
                Label {
                    if wallet.walletLoaded, !wallet.walletUnreadable, wallet.publicKey == store.identity.publicKey {
                        HStack { Text("Trust score"); Text(wallet.trust.total, format: .number.precision(.fractionLength(1))) }
                    } else { Text("Unlock to show reputation") }
                } icon: { Image(systemName: "shield.lefthalf.filled") }
                    .font(.caption.weight(.semibold)).accessibilityElement(children: .combine)
                    .padding(.horizontal, 12).padding(.vertical, 8).background(.background.opacity(0.8), in: Capsule())
            }.buttonStyle(.plain)
        }
        .padding(24).frame(maxWidth: .infinity, alignment: .leading)
        .background(LinearGradient(colors: [Color.accentColor.opacity(0.2), Color.accentColor.opacity(0.04)], startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 28))
    }
}

struct FavoriteMeetupCard: View {
    @Environment(AppStore.self) private var store
    let meetup: Meetup
    var body: some View {
        ReadableCard {
            VStack(alignment: .leading, spacing: 16) {
                NavigationLink(value: AppRoute.meetup(meetup.id)) {
                    HStack(spacing: 14) {
                        MeetupAvatar(meetup: meetup)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(meetup.name).font(.headline).foregroundStyle(.primary)
                            Text("\(meetup.city) · \(meetup.country)").font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").font(.caption).foregroundStyle(.secondary)
                    }
                }.buttonStyle(.plain)
                if let event = store.nextEvent(for: meetup.id) {
                    NavigationLink(value: AppRoute.event(event.id)) { EventRow(event: event) }.buttonStyle(.plain)
                } else if let date = meetup.nextEventStart {
                    Label { Text(date, format: .dateTime.weekday().day().month().hour().minute()) } icon: { Image(systemName: "calendar") }
                    Text("event.detailPending").font(.caption).foregroundStyle(.secondary)
                } else {
                    Text("event.none").font(.subheadline).foregroundStyle(.secondary)
                }
                HStack {
                    NavigationLink("nav.events", value: AppRoute.events(meetup.id)).buttonStyle(.bordered)
                    NavigationLink { NativeChatDirectoryView(meetupID: meetup.id) } label: { Label("nav.chat", systemImage: "bubble.left.and.bubble.right") }.buttonStyle(.bordered)
                }
            }
        }
    }
}

struct ProfileEditView: View {
    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var nickname = ""
    @State private var npub = ""
    @State private var fullName = ""
    @State private var homeMeetupID = ""
    @State private var telegramHandle = ""
    @State private var twitterHandle = ""
    @State private var loaded = false
    var body: some View {
        Form {
            Section("profile.local") {
                HStack { Spacer(); ProfilePictureView(size: 76); Spacer() }
                TextField("profile.nickname", text: $nickname).textContentType(.nickname).accessibilityIdentifier("profile.editNickname")
                TextField("Full name", text: $fullName).textContentType(.name).accessibilityIdentifier("profile.fullName")
                TextField("profile.npub.optional", text: $npub).autocorrectionDisabled()
                TextField("Telegram handle", text: $telegramHandle).autocorrectionDisabled()
                TextField("Twitter handle", text: $twitterHandle).autocorrectionDisabled()
                NavigationLink {
                    HomeMeetupPickerView(selection: $homeMeetupID)
                } label: {
                    LabeledContent("Home meetup") {
                        Text(store.meetups.first(where: { $0.id == homeMeetupID })?.name ?? homeMeetupID)
                            .foregroundStyle(.secondary)
                    }
                }
                NavigationLink("Choose favorite meetups") { MeetupDirectoryView() }
                Text("profile.public.body").font(.caption).foregroundStyle(.secondary)
            }
            Section("security.boundary") {
                NavigationLink("Signing identity and recovery") { IdentitySetupView() }
                NavigationLink("integration.signing") { IdentitySetupView() }
                NavigationLink("integration.proofs") { TrustProofsView() }
                NavigationLink("integration.humanity") { TrustProofsView() }
            }
            Button("action.save") { if store.saveProfile(nickname: nickname, npub: npub, fullName: fullName, homeMeetupID: homeMeetupID, favoriteMeetupIDs: UserContinuity.ordered(([homeMeetupID].filter { !$0.isEmpty }) + store.preferences.favoriteIDs.sorted()), telegramHandle: telegramHandle, twitterHandle: twitterHandle) { dismiss() } }
                .disabled(nickname.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .formStyle(.grouped)
        .navigationTitle("profile.title")
        .onAppear {
            guard !loaded else { return }; loaded = true
            nickname = store.profile.nickname; npub = store.profile.npub; fullName = store.profile.fullName
            homeMeetupID = store.profile.homeMeetupID; telegramHandle = store.profile.telegramHandle; twitterHandle = store.profile.twitterHandle
        }
    }
}

struct HomeMeetupPickerView: View {
    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @Binding var selection: String
    @State private var search = ""
    var body: some View {
        List {
            ForEach(store.meetups.filter {
                search.isEmpty || ($0.name + " " + $0.city + " " + $0.country).localizedCaseInsensitiveContains(search)
            }.sorted { $0.city.localizedStandardCompare($1.city) == .orderedAscending }) { meetup in
                Button {
                    selection = meetup.id
                    dismiss()
                } label: {
                    HStack {
                        MeetupAvatar(meetup: meetup, size: 36)
                        VStack(alignment: .leading) {
                            Text(meetup.name)
                            Text(meetup.city + " · " + meetup.country).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        if selection == meetup.id { Image(systemName: "checkmark").foregroundStyle(Color.accentColor) }
                    }
                }.buttonStyle(.plain)
            }
        }
        .searchable(text: $search)
        .navigationTitle("Choose your home meetup")
        .overlay {
            if store.meetups.isEmpty {
                ContentUnavailableView("state.notFound", systemImage: "person.3", description: Text("home.favorites.body"))
            }
        }
    }
}

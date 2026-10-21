import SwiftUI
import MeetupCore

struct SettingsView: View {
    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var resetPresented = false
    var body: some View {
        Form {
            Section("settings.account") {
                NavigationLink { ProfileEditView() } label: {
                    Label {
                        if store.profile.nickname.isEmpty { Text("profile.title") }
                        else { Text(store.profile.nickname) }
                    } icon: { Image(systemName: "person.crop.circle") }
                }
                Text("profile.public.body").font(.caption).foregroundStyle(.secondary)
            }
            Section("settings.appearance") {
                Picker("settings.language", selection: preference(\.language)) {
                    Text("language.system").tag("system"); Text("Deutsch").tag("de"); Text("English").tag("en"); Text("Español").tag("es")
                }
                Picker("settings.theme", selection: preference(\.appearance)) {
                    Text("appearance.system").tag("system"); Text("appearance.light").tag("light"); Text("appearance.dark").tag("dark")
                }
                Picker("settings.currency", selection: preference(\.currency)) { Text("EUR").tag("EUR"); Text("USD").tag("USD") }
                NavigationLink("settings.dashboard") { DashboardSettingsView() }
            }
            Section("settings.network") {
                NavigationLink("settings.relays") { RelaySettingsView() }
                NavigationLink("settings.mempool") { MempoolSettingsView() }
                NavigationLink("settings.diagnostics") { DiagnosticsView() }
                NavigationLink("integration.signing") { IntegrationView(integration: .signing) }
            }
            Section {
                Toggle("settings.demo", isOn: Binding(get: { store.preferences.demo }, set: { store.setDemo($0) }))
                Text("settings.demo.body").font(.caption).foregroundStyle(.secondary)
            } header: { Text("settings.preview") }
            Section("settings.data") {
                Text("settings.storage.body").font(.caption).foregroundStyle(.secondary)
                NavigationLink("integration.backup") { IntegrationView(integration: .backup) }
                Button("settings.reset", role: .destructive) { resetPresented = true }
            }
            Section {
                Link("resource.repository", destination: URL(string: "https://github.com/louisthecat86/Einundzwanzig-Meetup-App")!)
                Text("settings.version").font(.caption).foregroundStyle(.secondary)
                Text("settings.accessibility").font(.caption).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .navigationTitle("settings.title")
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("action.done") { dismiss() } } }
        .confirmationDialog("settings.reset.confirm", isPresented: $resetPresented, titleVisibility: .visible) {
            Button("settings.reset", role: .destructive) { store.resetLocalData(); if !store.profile.onboarded { dismiss() } }
            Button("action.cancel", role: .cancel) { }
        } message: { Text("settings.reset.body") }
    }

    private func preference<Value>(_ path: WritableKeyPath<LocalPreferences, Value>) -> Binding<Value> {
        Binding(get: { store.preferences[keyPath: path] }, set: { value in store.updatePreferences { $0[keyPath: path] = value } })
    }
}

struct DashboardSettingsView: View {
    @Environment(AppStore.self) private var store
    var body: some View {
        List {
            Section { Text("settings.dashboard.body").font(.subheadline).foregroundStyle(.secondary) }
            ForEach(store.preferences.dashboardOrder) { tile in
                HStack {
                    Label(LocalizedStringKey(tile.title), systemImage: tile.symbol)
                    Spacer()
                    Toggle("settings.visible", isOn: Binding(get: { !store.preferences.hiddenTiles.contains(tile) }, set: { visible in
                        store.updatePreferences {
                            if visible { $0.hiddenTiles.remove(tile) } else { $0.hiddenTiles.insert(tile) }
                        }
                    })).labelsHidden().accessibilityLabel(LocalizedStringKey(tile.title))
                    Button { move(tile, by: -1) } label: { Image(systemName: "arrow.up") }
                        .buttonStyle(.borderless).accessibilityLabel("settings.moveUp")
                        .disabled(store.preferences.dashboardOrder.first == tile)
                    Button { move(tile, by: 1) } label: { Image(systemName: "arrow.down") }
                        .buttonStyle(.borderless).accessibilityLabel("settings.moveDown")
                        .disabled(store.preferences.dashboardOrder.last == tile)
                }
            }
        }.navigationTitle("settings.dashboard")
    }
    private func move(_ tile: DashboardTile, by offset: Int) {
        store.updatePreferences {
            guard let index = $0.dashboardOrder.firstIndex(of: tile), $0.dashboardOrder.indices.contains(index + offset) else { return }
            $0.dashboardOrder.swapAt(index, index + offset)
        }
    }
}

struct RelaySettingsView: View {
    @Environment(AppStore.self) private var store
    @State private var relay = "wss://"
    @State private var invalid = false
    var body: some View {
        Form {
            Section { Text("relay.localOnly").font(.subheadline).foregroundStyle(.secondary) }
            Section("relay.configured") {
                ForEach(store.preferences.relays, id: \.self) { item in
                    HStack {
                        Text(item).font(.callout.monospaced()).textSelection(.enabled)
                        Spacer()
                        Button(role: .destructive) { store.updatePreferences { $0.relays.removeAll { $0 == item } } } label: { Image(systemName: "minus.circle") }
                            .accessibilityLabel("relay.remove")
                    }
                }
            }
            Section("relay.add") {
                TextField("relay.address", text: $relay).autocorrectionDisabled()
                if invalid { Text("relay.invalid").foregroundStyle(.red) }
                Button("action.add") {
                    guard let url = SafeURL.relay(relay),
                          (url.path.isEmpty || url.path == "/"), url.query == nil,
                          !store.preferences.relays.contains(url.absoluteString), store.preferences.relays.count < 12 else { invalid = true; return }
                    if store.updatePreferences({ $0.relays.append(url.absoluteString) }) { relay = "wss://"; invalid = false }
                }
            }
            Button("relay.defaults") {
                store.updatePreferences { $0.relays = LocalPreferences().relays }
            }
        }.formStyle(.grouped).navigationTitle("settings.relays")
    }
}

struct MempoolSettingsView: View {
    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var server = ""
    @State private var invalid = false
    var body: some View {
        Form {
            Section {
                TextField("mempool.address", text: $server).autocorrectionDisabled()
                Text("mempool.https").font(.caption).foregroundStyle(.secondary)
                if invalid { Text("mempool.invalid").foregroundStyle(.red) }
                Button("action.save") {
                    guard let url = SafeURL.web(server), url.scheme?.lowercased() == "https", url.query == nil, url.fragment == nil else { invalid = true; return }
                    if store.updatePreferences({ $0.mempoolServer = url.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) }) { dismiss() }
                }
                Button("mempool.default") { server = "https://mempool.space"; invalid = false }
            }
            Section { Text("mempool.noTor").font(.caption).foregroundStyle(.secondary) }
        }
        .formStyle(.grouped).navigationTitle("settings.mempool")
        .onAppear { server = store.preferences.mempoolServer }
    }
}

struct DiagnosticsView: View {
    @Environment(AppStore.self) private var store
    var body: some View {
        List {
            Section {
                Text("diagnostics.body").font(.caption).foregroundStyle(.secondary)
                LabeledContent("diagnostics.meetups", value: store.cache.meetups.count.formatted())
                LabeledContent("diagnostics.events", value: store.cache.events.count.formatted())
                LabeledContent("diagnostics.articles", value: store.cache.articles.count.formatted())
            }
            ForEach(Array(store.diagnostics.enumerated()), id: \.offset) { _, entry in Text(entry).font(.caption.monospaced()).textSelection(.enabled) }
        }.navigationTitle("settings.diagnostics")
    }
}

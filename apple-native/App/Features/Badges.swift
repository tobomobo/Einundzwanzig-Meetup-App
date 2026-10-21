import SwiftUI
import UniformTypeIdentifiers
import MapKit

struct BadgeWalletView: View {
    @Environment(AppStore.self) private var store
    var collection: String? = nil
    @State private var query = ""
    @State private var grouping = "meetup"
    private var filtered: [BadgePreview] {
        store.badges.filter { badge in
            (collection == nil || badge.name == collection) && (query.isEmpty || badge.name.localizedStandardContains(query))
        }.sorted { $0.importedAt > $1.importedAt }
    }
    private var groups: [String] { Array(Set(filtered.map(\.name))).sorted() }

    var body: some View {
        List {
            Section {
                Label("badge.previewOnly", systemImage: "exclamationmark.shield").font(.headline)
                Text("badge.previewOnly.body").font(.subheadline).foregroundStyle(.secondary)
                NavigationLink(value: AppRoute.reputation) { Label("nav.reputation", systemImage: "person.crop.circle.badge.questionmark") }
                Button { store.scanPresented = true } label: { Label("scan.title", systemImage: "qrcode.viewfinder") }
            }
            if filtered.isEmpty {
                EmptyState(title: "badge.empty", symbol: "seal", message: "badge.empty.body")
            } else {
                if collection == nil {
                    Picker("badge.grouping", selection: $grouping) {
                        Text("badge.byMeetup").tag("meetup")
                        Text("badge.byDate").tag("date")
                    }.pickerStyle(.segmented)
                }
                if collection == nil && grouping == "meetup" {
                    ForEach(groups, id: \.self) { name in
                        NavigationLink(value: AppRoute.collection(name)) {
                            HStack(spacing: 16) {
                                Image(systemName: "seal.fill").font(.largeTitle).foregroundStyle(Color.accentColor)
                                VStack(alignment: .leading, spacing: 5) {
                                    Text(name).font(.headline)
                                    Text("badge.previewCount \(filtered.filter { $0.name == name }.count)").font(.caption).foregroundStyle(.secondary)
                                }
                            }.padding(.vertical, 8)
                        }
                    }
                } else {
                    ForEach(filtered) { badge in
                        NavigationLink(value: AppRoute.badge(badge.id)) { BadgeRow(badge: badge) }
                    }
                }
            }
            Section {
                NavigationLink("badge.map", value: AppRoute.badgeMap)
                Text("badge.storage.body").font(.caption).foregroundStyle(.secondary)
            }
        }
        .navigationTitle(collection.map { Text(verbatim: $0) } ?? Text("nav.badges"))
        .searchable(text: $query, prompt: "badge.search")
    }
}

struct BadgeRow: View {
    let badge: BadgePreview
    var body: some View {
        HStack(spacing: 16) {
            Image(systemName: badge.isDemo ? "seal" : "doc.text.magnifyingglass")
                .font(.largeTitle).foregroundStyle(Color.accentColor).frame(width: 54)
            VStack(alignment: .leading, spacing: 4) {
                Text(badge.name).font(.headline)
                Text(badge.importedAt, format: .dateTime.day().month().year()).font(.caption).foregroundStyle(.secondary)
                Text(badge.isDemo ? "source.demo" : "badge.unverified").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            }
        }.padding(.vertical, 8)
    }
}

struct BadgeDetailView: View {
    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let badge: BadgePreview
    @State private var confirmDelete = false
    var body: some View {
        List {
            Section {
                VStack(spacing: 20) {
                    Image(systemName: "seal").font(.system(size: 88)).foregroundStyle(Color.accentColor).accessibilityHidden(true)
                    Text(badge.name).font(.title.bold()).multilineTextAlignment(.center)
                    Label(badge.isDemo ? "source.demo" : "badge.unverified", systemImage: "exclamationmark.shield")
                }.frame(maxWidth: .infinity).padding(.vertical, 30)
            }
            Section("badge.metadata") {
                LabeledContent("badge.imported") { Text(badge.importedAt, format: .dateTime.day().month().year().hour().minute()) }
                if let height = badge.blockHeight { LabeledContent("bitcoin.block", value: height.formatted()) }
                LabeledContent("badge.verification") { Text("state.unavailable") }
                Text("badge.noProof").foregroundStyle(.secondary)
            }
            Section {
                NavigationLink("nav.reputation", value: AppRoute.reputation)
                if !badge.isDemo {
                    Button("badge.delete", role: .destructive) { confirmDelete = true }
                }
            }
        }
        .navigationTitle("badge.detail")
        .confirmationDialog("badge.delete.confirm", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("badge.delete", role: .destructive) { store.deletePreview(badge.id); if !store.badges.contains(where: { $0.id == badge.id }) { dismiss() } }
        }
    }
}

struct BadgeImportView: View {
    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var input = ""
    @State private var showCamera = false
    @State private var showFileImporter = false
    @State private var error: String?
    @State private var imported = false

    var body: some View {
        Form {
            Section {
                Text("scan.boundary").font(.subheadline).foregroundStyle(.secondary)
                if store.preferences.demo { Label("scan.demoDisabled", systemImage: "testtube.2") }
                Button { showCamera = true } label: { Label("scan.camera", systemImage: "camera.viewfinder") }
                Button { showFileImporter = true } label: { Label("scan.file", systemImage: "doc") }
            }
            Section("scan.input") {
                TextEditor(text: $input).frame(minHeight: 150).font(.system(.body, design: .monospaced))
                    .autocorrectionDisabled().accessibilityLabel("scan.input")
                    .onChange(of: input) { imported = false; error = nil }
                Text("scan.example").font(.caption).foregroundStyle(.secondary)
                Button("scan.inspect") {
                    imported = false
                    do { _ = try store.importPreview(input); error = nil; imported = true }
                    catch { self.error = store.errorMessage(error) }
                }.disabled(input.isEmpty || store.preferences.demo)
                if let error { Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.red) }
                if imported { Label("scan.savedPreview", systemImage: "doc.badge.plus").foregroundStyle(.secondary) }
            }
            Section {
                NavigationLink("integration.verification") { IntegrationView(integration: .verification) }
                Text("scan.noPrivateKeys").font(.caption).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .navigationTitle("scan.title")
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("action.done") { dismiss() } } }
        .sheet(isPresented: $showCamera) {
            NavigationStack {
                NativeQRScanner { text in input = text; showCamera = false }
                    .navigationTitle("scan.camera")
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("action.cancel") { showCamera = false } } }
            }
        }
        .fileImporter(isPresented: $showFileImporter, allowedContentTypes: [.json, .plainText], allowsMultipleSelection: false) { result in
            imported = false
            do {
                guard let url = try result.get().first else { return }
                let access = url.startAccessingSecurityScopedResource()
                defer { if access { url.stopAccessingSecurityScopedResource() } }
                let values = try url.resourceValues(forKeys: [.fileSizeKey])
                guard let size = values.fileSize, size <= 65_536 else { throw LocalDataError.tooLarge }
                let data = try Data(contentsOf: url, options: .mappedIfSafe)
                guard data.count <= 65_536, let text = String(data: data, encoding: .utf8) else { throw LocalDataError.invalidImport }
                input = text
            } catch { self.error = store.errorMessage(error) }
        }
    }
}

struct ReputationView: View {
    var body: some View {
        List {
            Section {
                Image(systemName: "shield.lefthalf.filled").font(.system(size: 60)).foregroundStyle(Color.accentColor).padding(.vertical)
                Text("reputation.unavailable").font(.title2.bold())
                Text("reputation.body").foregroundStyle(.secondary)
                LabeledContent("reputation.trustedBadges", value: "0")
                LabeledContent("reputation.score", value: "—")
                Text("reputation.notZero").font(.caption).foregroundStyle(.secondary)
            }
            Section("security.required") {
                Label("security.organizerSignature", systemImage: "1.circle")
                Label("security.collectorClaim", systemImage: "2.circle")
                Label("security.registry", systemImage: "3.circle")
                Label("security.proofHash", systemImage: "4.circle")
                Text("security.noSigning").font(.caption).foregroundStyle(.secondary)
            }
            Section {
                NavigationLink("integration.verification", value: AppRoute.unavailable(.verification))
                NavigationLink("integration.socialGraph", value: AppRoute.unavailable(.socialGraph))
                NavigationLink("integration.proofs", value: AppRoute.unavailable(.proofs))
            }
        }.navigationTitle("nav.reputation")
    }
}

struct BadgeMapView: View {
    var body: some View {
        EmptyState(title: "badge.map.empty", symbol: "map", message: "badge.map.empty.body")
            .navigationTitle("badge.map")
    }
}

import SwiftUI
import MeetupNostr

struct PlebRapView: View {
    @Environment(AppStore.self) private var store
    @State private var query = ""
    @State private var refresh = 0
    @State private var issue: String?
    private var albums: [String] { Array(Set(PlebRapCatalog.tracks.map(\.album))).sorted() }
    var body: some View {
        List {
            if let track = store.media.selected {
                Section("Now playing") {
                    Text(track.title).font(.headline); Text(track.artist).foregroundStyle(.secondary)
                    Slider(value: Binding(get: { store.media.duration > 0 ? store.media.elapsed / store.media.duration : 0 }, set: { store.media.seek($0) }), in: 0...1)
                    HStack {
                        Button("Previous") { store.media.next(offset: -1) }
                        Button(LocalizedStringKey(store.media.playing ? "Pause" : "Play")) { if store.media.playing { store.media.pause() } else { store.media.resume() } }
                        Button("Next") { store.media.next() }
                    }
                    Text("\(Int(store.media.elapsed)) / \(Int(store.media.duration)) s").font(.caption.monospacedDigit())
                }
            }
            if let error = issue ?? store.media.error { Text(LocalizedStringKey(error)).foregroundStyle(.secondary) }
            ForEach(albums, id: \.self) { album in
                Section(album) {
                    ForEach(PlebRapCatalog.tracks.filter { $0.album == album && (query.isEmpty || ($0.title + $0.artist).localizedStandardContains(query)) }) { track in
                        HStack {
                            Button { store.media.play(track) } label: {
                                VStack(alignment: .leading, spacing: 4) { Text(track.title); Text(track.artist).font(.caption).foregroundStyle(.secondary) }
                            }.buttonStyle(.plain)
                            Spacer()
                            if store.media.isCached(track) {
                                Button { do { try store.media.removeCached(track); refresh += 1; issue = nil } catch { issue = error.localizedDescription } } label: { Image(systemName: "trash") }.accessibilityLabel("Remove download")
                            } else {
                                Button { Task { await store.media.cache(track); refresh += 1 } } label: { Image(systemName: "arrow.down.circle") }
                                    .disabled(store.media.downloading).accessibilityLabel("Download for offline playback")
                            }
                        }
                    }
                }
            }
            Section("Value for value") {
                Link("Support PlebRap artists", destination: URL(string: "https://plebrap.de/V4V/")!)
                NavigationLink("Support the app") { ValueForValueView() }
            }
        }.navigationTitle("PlebRap").searchable(text: $query)
            .task {
                while !Task.isCancelled {
                    store.media.refreshProgress()
                    do { try await Task.sleep(for: .seconds(1)) } catch { return }
                }
            }
    }
}

struct ValueForValueView: View {
    @Environment(WorkflowStore.self) private var workflow
    @Environment(\.openURL) private var openURL
    var article: String? = nil
    @State private var address = ValueForValueService.projectAddress
    @State private var amount = "1000"
    @State private var comment = ""
    @State private var invoice: ExternalWalletInvoice?
    @State private var issue: String?
    @State private var loading = false
    var body: some View {
        Form {
            Section("Value for value") {
                TextField("Lightning address", text: $address).autocorrectionDisabled()
                TextField("Satoshis", text: $amount).autocorrectionDisabled()
                TextField("Comment", text: $comment, axis: .vertical)
                Button("Create wallet invoice") {
                    guard let sats = Int(amount), sats > 0 else { issue = ValueForValueError.invalidAmount.localizedDescription; return }
                    loading = true; invoice = nil; issue = nil
                    Task {
                        defer { loading = false }
                        do { invoice = try await workflow.value.invoice(address: address, sats: sats, comment: comment, article: article, signer: workflow.signer, relays: workflow.relays) }
                        catch { issue = error.localizedDescription }
                    }
                }.disabled(loading)
                if loading { ProgressView() }
                if let issue { Text(LocalizedStringKey(issue)).foregroundStyle(.red) }
            }
            if let invoice {
                Section("Review in your wallet") {
                    LabeledContent("Receiver", value: invoice.address)
                    LabeledContent("Satoshis", value: invoice.sats.formatted())
                    Text(LocalizedStringKey(invoice.zapRequested ? "A signed zap request was attached." : "Ordinary Lightning payment without a zap receipt request.")).font(.caption)
                    Text(invoice.bolt11).font(.caption.monospaced()).textSelection(.enabled)
                    if let url = invoice.url { Button("Open Lightning wallet") { openURL(url) }; ShareLink(item: url) }
                    Text("The wallet reviews and authorizes payment. Returning here does not confirm payment.").font(.caption).foregroundStyle(.secondary)
                }
            }
        }.navigationTitle(LocalizedStringKey(article == nil ? "Support the app" : "Zap author"))
            .task(id: article) {
                guard let article, let target = try? NostrArticleAddress(article) else { return }
                do { address = try await workflow.news.lightningAddress(author: target.publicKey, relays: workflow.relays) }
                catch { address = ""; issue = error.localizedDescription }
            }
    }
}

import SwiftUI
import MeetupNostr

struct PlebRapView: View {
    @Environment(AppStore.self) private var store
    @State private var query = ""
    @State private var issue: String?
    private var albums: [String] { Array(Set(PlebRapCatalog.tracks.map(\.album))).sorted() }
    var body: some View {
        List {
            if let track = store.media.selected {
                Section("Now playing") {
                    Text(track.title).font(.headline); Text(track.artist).foregroundStyle(.secondary)
                    Slider(value: Binding(get: { store.media.duration > 0 ? min(1, max(0, store.media.elapsed / store.media.duration)) : 0 }, set: { store.media.seek($0) }), in: 0...1)
                    .disabled(store.media.duration <= 0)
                    HStack {
                        Button("Previous") { store.media.next(offset: -1) }.disabled(!store.media.hasPrevious)
                        Button(LocalizedStringKey(store.media.playing ? "Pause" : "Play")) { if store.media.playing { store.media.pause() } else { store.media.resume() } }
                        Button("Next") { store.media.next() }.disabled(!store.media.hasNext)
                    }
                    Text("\(Int(store.media.elapsed)) / \(Int(store.media.duration)) s").font(.caption.monospacedDigit())
                }
            }
            if store.media.downloading { Label("Saving for offline playback", systemImage: "arrow.down.circle").font(.caption).foregroundStyle(.secondary) }
            Text("Played tracks are saved automatically for offline listening.").font(.caption).foregroundStyle(.secondary)
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
                                Button { do { try store.media.removeCached(track); issue = nil } catch { issue = error.localizedDescription } } label: { Image(systemName: "trash") }.accessibilityLabel("Remove download")
                            } else {
                                Button { store.media.cache(track) } label: { Image(systemName: "arrow.down.circle") }
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
    }
}

@MainActor private final class ValueInvoiceRequestFence { var id = UUID() }

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
    @State private var resolvingAddress = false
    @State private var requestFence = ValueInvoiceRequestFence()
    private func invalidateInvoice() { requestFence.id = UUID(); invoice = nil; loading = false }
    @ViewBuilder private var zapAmountButtons: some View {
        ForEach(ValueForValueService.articleZapAmounts, id: \.self) { sats in
            Button { invalidateInvoice(); amount = String(sats); issue = nil } label: {
                Text("\(sats.formatted()) sat")
            }.buttonStyle(.bordered).disabled(loading)
        }
    }
    var body: some View {
        Form {
            Section("Value for value") {
                TextField("Lightning address", text: $address).autocorrectionDisabled().disabled(loading || resolvingAddress)
                if article != nil {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Quick zap amounts").font(.subheadline.weight(.semibold))
                        ViewThatFits(in: .horizontal) {
                            HStack { zapAmountButtons }
                            VStack(alignment: .leading) { zapAmountButtons }
                        }
                        Text("Choose an amount, then create an invoice and review it in your wallet.").font(.caption).foregroundStyle(.secondary)
                    }
                }
                TextField("Satoshis", text: $amount).autocorrectionDisabled().disabled(loading)
                TextField("Comment", text: $comment, axis: .vertical).disabled(loading)
                Button("Create wallet invoice") {
                    guard let sats = Int(amount), sats > 0 else { issue = ValueForValueError.invalidAmount.localizedDescription; return }
                    let request = UUID(), key = workflow.publicKey
                    let receiver = address, note = comment, targetArticle = article
                    let signer = workflow.signer, relays = workflow.relays
                    requestFence.id = request
                    loading = true; invoice = nil; issue = nil
                    Task {
                        defer { if requestFence.id == request { loading = false } }
                        do {
                            let result = try await workflow.value.invoice(address: receiver, sats: sats, comment: note, article: targetArticle, signer: signer, relays: relays,
                                isCurrentIdentity: { [requestFence, workflow] candidate in
                                    await MainActor.run { requestFence.id == request && workflow.publicKey == candidate }
                                })
                            guard requestFence.id == request, workflow.publicKey == key, !Task.isCancelled else { return }
                            invoice = result
                        } catch { if requestFence.id == request, workflow.publicKey == key, !Task.isCancelled { issue = error.localizedDescription } }
                    }
                }.disabled(loading || resolvingAddress || address.isEmpty)
                if loading || resolvingAddress { ProgressView() }
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
            .onChange(of: amount) { _, _ in invalidateInvoice() }
            .onChange(of: address) { _, _ in invalidateInvoice() }
            .onChange(of: comment) { _, _ in invalidateInvoice() }
            .onChange(of: workflow.publicKey) { _, _ in invalidateInvoice() }
            .onDisappear { invalidateInvoice() }
            .task(id: article) {
                invalidateInvoice()
                guard let article else { address = ValueForValueService.projectAddress; resolvingAddress = false; return }
                address = ""; resolvingAddress = true
                defer { if !Task.isCancelled { resolvingAddress = false } }
                do {
                    let target = try NostrArticleAddress(article)
                    let result = try await workflow.news.lightningAddress(author: target.publicKey, relays: workflow.relays)
                    guard !Task.isCancelled else { return }
                    address = result
                } catch { if !Task.isCancelled { address = ""; issue = error.localizedDescription } }
            }
    }
}

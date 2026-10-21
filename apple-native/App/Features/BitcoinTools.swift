import SwiftUI
import MeetupCore

struct BitcoinView: View {
    @Environment(AppStore.self) private var store
    var body: some View {
        List {
            Section {
                Label("bitcoin.liveSource", systemImage: "network").font(.caption)
                Text(store.preferences.mempoolServer).font(.caption.monospaced()).foregroundStyle(.secondary)
                if store.networkIssues.contains("source.bitcoin.failed") {
                    Label("source.bitcoin.failed", systemImage: "wifi.exclamationmark").foregroundStyle(.secondary)
                }
            }
            if let snapshot = store.bitcoin {
                Section {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("bitcoin.block").font(.caption.weight(.bold)).foregroundStyle(.secondary)
                        Text(snapshot.height.formatted()).font(.system(.largeTitle, design: .rounded).bold()).foregroundStyle(Color.accentColor)
                            .minimumScaleFactor(0.7)
                        Text(snapshot.fetchedAt, format: .dateTime.hour().minute().second()).font(.caption).foregroundStyle(.secondary)
                    }.padding(.vertical, 18)
                }
                Section("bitcoin.price") {
                    LabeledContent("EUR") { Text(snapshot.eur, format: .currency(code: "EUR")) }
                    LabeledContent("USD") { Text(snapshot.usd, format: .currency(code: "USD")) }
                }
                Section("bitcoin.fees") {
                    LabeledContent("bitcoin.fast") { Text("\(snapshot.fastestFee) sat/vB").monospacedDigit() }
                    LabeledContent("bitcoin.halfHour") { Text("\(snapshot.halfHourFee) sat/vB").monospacedDigit() }
                    LabeledContent("bitcoin.hour") { Text("\(snapshot.hourFee) sat/vB").monospacedDigit() }
                    Text("bitcoin.feesNote").font(.caption).foregroundStyle(.secondary)
                }
            } else if store.bitcoinLoading {
                ProgressView("action.loading")
            } else {
                EmptyState(title: "bitcoin.noData", symbol: "bitcoinsign.circle", message: "bitcoin.noData.body")
            }
            Section {
                Button("action.refresh") { Task { await store.refreshBitcoin() } }.disabled(store.bitcoinLoading)
                NavigationLink("nav.converter", value: AppRoute.converter)
                NavigationLink("settings.mempool") { MempoolSettingsView() }
                Text("bitcoin.scope").font(.caption).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("nav.bitcoin")
        .refreshable { await store.refreshBitcoin() }
        .task(id: store.preferences.mempoolServer) {
            while !Task.isCancelled {
                await store.refreshBitcoin()
                do { try await Task.sleep(for: .seconds(60)) } catch { break }
            }
        }
    }
}

struct ConverterView: View {
    @Environment(AppStore.self) private var store
    @State private var amount = "100000"
    @State private var unit = "sats"
    private var sats: Int64? {
        if unit == "sats" {
            guard amount.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
            return Int64(amount)
        }
        guard let value = try? BitcoinConverter.sats(fromBTC: amount.replacingOccurrences(of: ",", with: ".")), value >= 0 else { return nil }
        return value
    }
    var body: some View {
        Form {
            Section("converter.input") {
                Picker("converter.unit", selection: $unit) { Text("Satoshis").tag("sats"); Text("BTC").tag("btc") }
                    .pickerStyle(.segmented)
                TextField("converter.amount", text: $amount).font(.title2.monospacedDigit()).autocorrectionDisabled()
                Text("converter.precision").font(.caption).foregroundStyle(.secondary)
            }
            Section("converter.result") {
                if let sats {
                    LabeledContent("Satoshis", value: sats.formatted())
                    LabeledContent("BTC", value: BitcoinConverter.btc(fromSats: sats))
                    if let snapshot = store.bitcoin {
                        let currency = store.preferences.currency
                        LabeledContent(currency) {
                            Text(BitcoinConverter.fiat(sats: sats, price: currency == "EUR" ? snapshot.eur : snapshot.usd), format: .currency(code: currency))
                        }
                        Text(snapshot.fetchedAt, format: .dateTime.day().month().hour().minute()).font(.caption).foregroundStyle(.secondary)
                        Text("converter.estimate").font(.caption).foregroundStyle(.secondary)
                    } else {
                        Text("converter.noPrice").font(.subheadline).foregroundStyle(.secondary)
                        Button("converter.loadPrice") { Task { await store.refreshBitcoin() } }.disabled(store.bitcoinLoading)
                    }
                } else { Label("converter.invalid", systemImage: "exclamationmark.triangle").foregroundStyle(.secondary) }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("nav.converter")
    }
}

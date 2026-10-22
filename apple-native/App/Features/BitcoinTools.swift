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
                Section("Network metrics") {
                    if let supply = BitcoinDashboard.circulatingSupply(atHeight: snapshot.height) {
                        LabeledContent("Circulating supply (BTC)", value: supply.formatted())
                    }
                    if let time = BitcoinDashboard.moscowTime(usdPrice: BitcoinDashboard.price(for: "USD", dashboard: store.bitcoinDashboard, snapshot: snapshot)) {
                        LabeledContent("Sats per USD (Moscow time)", value: time)
                    }
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
            if let dashboard = store.bitcoinDashboard {
                Section("Network metrics") {
                    ForEach(dashboard.values.keys.filter { !$0.hasPrefix("lightning") }.sorted(), id: \.self) { key in
                        LabeledContent { Text(dashboard.values[key]!, format: .number.precision(.fractionLength(0...2))) } label: { Text(LocalizedStringKey(metricName(key))) }
                    }
                    ForEach(dashboard.failedSources, id: \.self) { source in Text("Unavailable source: \(source)").font(.caption).foregroundStyle(.secondary) }
                }
                Section("Lightning") {
                    if let capacity = dashboard.values["lightningCapacityBTC"] {
                        LabeledContent("Capacity (BTC)") { Text(capacity, format: .number.precision(.fractionLength(0...8))) }
                    }
                    if let nodes = dashboard.values["lightningNodes"] { LabeledContent("Nodes") { Text(nodes, format: .number.precision(.fractionLength(0))) } }
                    if let channels = dashboard.values["lightningChannels"] { LabeledContent("Channels") { Text(channels, format: .number.precision(.fractionLength(0))) } }
                    if dashboard.failedSources.contains("lightning") { Text("Lightning statistics unavailable").font(.caption).foregroundStyle(.secondary) }
                }
                Section("Currencies") {
                    ForEach(dashboard.prices.keys.sorted(), id: \.self) { currency in
                        LabeledContent(currency) { Text(dashboard.prices[currency]!, format: .currency(code: currency)) }
                    }
                }
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
    private func metricName(_ key: String) -> String {
        ["count": "Pending transactions", "vsize": "Mempool vbytes", "total_fee": "Pending fees (sats)",
         "currentHashrate": "Hashrate (H/s)", "currentDifficulty": "Difficulty", "progressPercent": "Difficulty period (%)",
         "difficultyChange": "Expected adjustment (%)", "remainingBlocks": "Blocks until adjustment",
         "estimatedRetargetDate": "Retarget (Unix milliseconds)", "timeAvg": "Average block time (ms)"][key] ?? key
    }

}

struct ConverterView: View {
    @Environment(AppStore.self) private var store
    @State private var amount = "100000"
    @State private var unit = "sats"
    @State private var premium = ""
    private var price: Double? {
        BitcoinDashboard.price(for: store.preferences.currency, dashboard: store.bitcoinDashboard, snapshot: store.bitcoin)
    }
    private var sats: Int64? { try? (BitcoinAmountUnit(rawValue: unit) ?? .sats).sats(amount, price: price) }
    private var selectedUnit: BitcoinAmountUnit { BitcoinAmountUnit(rawValue: unit) ?? .sats }
    private var quote: BitcoinPremiumQuote? {
        guard let price else { return nil }
        return try? BitcoinPremiumQuote(amount: amount, unit: selectedUnit, price: price, percentage: premium)
    }
    private func changeUnit(_ next: String) {
        let target = BitcoinAmountUnit(rawValue: next) ?? .sats
        if !amount.isEmpty {
            guard let sats, let converted = try? target.input(sats: sats, price: price) else { return }
            amount = converted
        }
        unit = next
    }
    var body: some View {
        Form {
            Section("converter.input") {
                Picker("settings.currency", selection: Binding(get: { store.preferences.currency }, set: { currency in store.updatePreferences { $0.currency = currency } })) {
                    ForEach(["EUR", "USD", "GBP", "CHF", "CAD", "AUD", "JPY"], id: \.self) { currency in Text(currency).tag(currency) }
                }
                Picker("converter.unit", selection: Binding(get: { unit }, set: changeUnit)) {
                    Text("Satoshis").tag("sats"); Text("BTC").tag("btc"); Text("mBTC").tag("mBTC")
                    Text("µBTC").tag("microBTC"); Text(store.preferences.currency).tag("fiat")
                }
                    .pickerStyle(.segmented)
                TextField("converter.amount", text: $amount).font(.title2.monospacedDigit()).autocorrectionDisabled()
                Text("converter.precision").font(.caption).foregroundStyle(.secondary)
            }
            Section("Trading premium or discount") {
                TextField("Premium or discount (%)", text: $premium).autocorrectionDisabled()
                Text("A positive percentage adds a premium; a negative percentage applies a discount.")
                    .font(.caption).foregroundStyle(.secondary)
                Slider(value: Binding(get: { min(20, max(-20, (try? BitcoinPremiumQuote.percentage(premium)) ?? 0)) },
                                      set: { premium = String($0) }), in: -20...20, step: 0.5) {
                    Text("Premium or discount (%)")
                } minimumValueLabel: { Text("−20%") } maximumValueLabel: { Text("+20%") }
                if let quote {
                    LabeledContent("Adjusted amount") { Text(quote.adjustedFiat, format: .currency(code: store.preferences.currency)) }
                    LabeledContent("Resulting satoshis", value: quote.adjustedSats.formatted())
                    LabeledContent("Base amount") { Text(quote.baseFiat, format: .currency(code: store.preferences.currency)) }
                } else if (try? BitcoinPremiumQuote.percentage(premium)) == nil {
                    Text("Enter a valid percentage of at least -100%.").font(.caption).foregroundStyle(.secondary)
                }
            }
            Section("converter.result") {
                if let sats {
                    LabeledContent("Satoshis", value: sats.formatted())
                    LabeledContent("BTC", value: BitcoinConverter.btc(fromSats: sats))
                    if let price {
                        let currency = store.preferences.currency
                        LabeledContent(currency) {
                            Text(BitcoinConverter.fiat(sats: sats, price: price), format: .currency(code: currency))
                        }
                        Text(store.bitcoinDashboard?.fetchedAt ?? store.bitcoin?.fetchedAt ?? .now, format: .dateTime.day().month().hour().minute()).font(.caption).foregroundStyle(.secondary)
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
        .refreshable { await store.refreshBitcoin() }
        .toolbar { Button("action.refresh") { Task { await store.refreshBitcoin() } }.disabled(store.bitcoinLoading) }
        .task { if !store.preferences.demo { await store.refreshBitcoin() } }
    }
}

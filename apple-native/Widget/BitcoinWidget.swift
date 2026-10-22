import AppIntents
import SwiftUI
import WidgetKit
import MeetupCore

struct BitcoinWidgetConfiguration: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "Bitcoin network"
    static let description = IntentDescription("Block height and fees from your chosen Bitcoin server. Onion requires a system VPN.")
    @Parameter(title: "Mempool server", default: "https://mempool.space") var server: String
}
struct BitcoinWidgetEntry: TimelineEntry {
    let date: Date
    let snapshot: BitcoinSnapshot?
    let server: String
}
struct BitcoinWidgetProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> BitcoinWidgetEntry { BitcoinWidgetEntry(date: .now, snapshot: nil, server: "mempool.space") }
    func snapshot(for configuration: BitcoinWidgetConfiguration, in context: Context) async -> BitcoinWidgetEntry { await fetch(configuration) }
    func timeline(for configuration: BitcoinWidgetConfiguration, in context: Context) async -> Timeline<BitcoinWidgetEntry> {
        Timeline(entries: [await fetch(configuration)], policy: .after(Date().addingTimeInterval(900)))
    }
    private func fetch(_ configuration: BitcoinWidgetConfiguration) async -> BitcoinWidgetEntry {
        guard let url = try? MempoolConfiguration.normalize(configuration.server) else {
            return BitcoinWidgetEntry(date: .now, snapshot: nil, server: "Invalid server")
        }
        let snapshot = try? await MempoolClient().snapshot(baseURL: url)
        return BitcoinWidgetEntry(date: .now, snapshot: snapshot, server: url.host ?? "")
    }
}
struct BitcoinWidgetView: View {
    let entry: BitcoinWidgetEntry
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Bitcoin", systemImage: "bitcoinsign.circle.fill").font(.headline).foregroundStyle(.orange)
            if let snapshot = entry.snapshot {
                Text(snapshot.height.formatted()).font(.title.bold().monospacedDigit())
                Text("\(snapshot.fastestFee) sat/vB · next block").font(.caption)
                Text(snapshot.fetchedAt, style: .time).font(.caption2).foregroundStyle(.secondary)
            } else { Text("Open app to refresh").font(.caption) }
            Text(entry.server).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
        }.frame(maxWidth: .infinity, alignment: .leading)
            .containerBackground(.fill.tertiary, for: .widget)
            .widgetURL(URL(string: "einundzwanzig://bitcoin"))
    }
}
@main struct EinundzwanzigBitcoinWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "EinundzwanzigBitcoin", intent: BitcoinWidgetConfiguration.self, provider: BitcoinWidgetProvider()) { entry in
            BitcoinWidgetView(entry: entry)
        }.configurationDisplayName("Bitcoin network").description("Current block height and fee estimate.")
            .supportedFamilies([.systemSmall, .systemMedium])
    }
}

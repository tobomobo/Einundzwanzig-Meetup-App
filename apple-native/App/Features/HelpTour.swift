import SwiftUI

struct HelpTourView: View {
    @State private var page = 0
    private let steps: [(String, AppRoute)] = [
        ("glFindMeetup", .meetups), ("glKeys", .unavailable(.signing)), ("glCollect", .badges),
        ("glTrustScore", .reputation), ("glCalendarSources", .events(nil)),
        ("glNews", .news), ("glBackup", .unavailable(.backup))
    ]
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Text("\(page + 1) / \(steps.count)").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                Text(LocalizedStringKey(steps[page].0 + "Title")).font(.largeTitle.bold())
                Text(LocalizedStringKey(steps[page].0 + "Body")).font(.body).textSelection(.enabled)
                NavigationLink("Open this feature") { RouteDestination(route: steps[page].1) }.buttonStyle(.borderedProminent)
                HStack {
                    Button("Previous") { page = max(0, page - 1) }.disabled(page == 0)
                    Spacer()
                    Button("Next") { page = min(steps.count - 1, page + 1) }.disabled(page == steps.count - 1)
                }
            }.padding(24).frame(maxWidth: 680).frame(maxWidth: .infinity)
        }.navigationTitle("Guided introduction")
    }
}

import Foundation
import MeetupCore
import WidgetKit
#if os(iOS)
import BackgroundTasks
#endif

/// Public data only. Background work never unlocks a vault or signs an event.
@MainActor enum NativeBackgroundRefresh {
    static let identifier = "space.einundzwanzig.meetup.apple.refresh"
    #if os(macOS)
    private static var activity: NSBackgroundActivityScheduler?
    #endif
    static func register() {
        #if os(iOS)
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            guard let refresh = task as? BGAppRefreshTask else { task.setTaskCompleted(success: false); return }
            Task { @MainActor in handle(refresh) }
        }
        #elseif os(macOS)
        let activity = NSBackgroundActivityScheduler(identifier: identifier)
        activity.interval = 900; activity.tolerance = 300; activity.repeats = true
        activity.schedule { completion in
            Task { @MainActor in
                let success = await fetch()
                completion(success ? .finished : .deferred)
            }
        }
        self.activity = activity
        #endif
    }
    static func schedule() {
        guard !UITestFixtureMode.enabled else { return }
        #if os(iOS)
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date().addingTimeInterval(900)
        do { try BGTaskScheduler.shared.submit(request) }
        catch { UserDefaults.standard.set("Background refresh not scheduled: \(error.localizedDescription)", forKey: "background-refresh-status") }
        #endif
    }
    #if os(iOS)
    private static func handle(_ task: BGAppRefreshTask) {
        schedule()
        let work = Task { @MainActor in task.setTaskCompleted(success: await fetch()) }
        task.expirationHandler = { work.cancel() }
    }
    #endif
    private static func fetch() async -> Bool {
        let host = UserDefaults.standard.string(forKey: "public-mempool-server") ?? "https://mempool.space"
        guard let url = try? MempoolConfiguration.normalize(host) else { return false }
        do {
            let snapshot = try await MempoolClient().snapshot(baseURL: url)
            try Task.checkCancellation()
            await saveWidget(snapshot: snapshot)
            return true
        } catch { UserDefaults.standard.set("Public refresh failed: \(error.localizedDescription)", forKey: "background-refresh-status"); return false }
    }
    static func saveWidget(snapshot: BitcoinSnapshot) async {
        if let data = try? JSONEncoder().encode(snapshot) {
            UserDefaults.standard.set(data, forKey: "public-bitcoin-snapshot")
            UserDefaults.standard.set("Public data refreshed \(snapshot.fetchedAt.formatted())", forKey: "background-refresh-status")
        }
        WidgetCenter.shared.reloadAllTimelines()
    }
}

#if LOCAL_MODEL_TESTS
import Foundation
import MeetupCore

@main
enum LocalModelTests {
    struct Failure: Error, CustomStringConvertible { let description: String }
    static func require(_ condition: @autoclosure () -> Bool, _ message: String) throws {
        if !condition() { throw Failure(description: message) }
    }
    static func rejected(_ text: String) throws {
        do { _ = try PreviewImport.parse(text) }
        catch is LocalDataError { return }
        throw Failure(description: "Unsafe/invalid import accepted")
    }
    @MainActor static func main() async throws {
        let tests: [(String, () throws -> Void)] = [
            ("legacy caches and local plan events decode without all-day metadata", {
                let old = Data(#"{"meetups":[],"events":[{"id":"old-event","title":"Legacy","description":"","start":0,"location":""}],"articles":[]}"#.utf8)
                let cache = try JSONDecoder().decode(DataCache.self, from: old)
                try require(cache.calendarSnapshot == nil && cache.events.count == 1 && !cache.events[0].isAllDay, "Lost existing calendar/plan compatibility")
            }),
            ("all-day native calendar conversion preserves source civil day", {
                var source = Calendar(identifier: .gregorian)
                source.timeZone = TimeZone(identifier: "America/Santiago")!
                let start = source.date(from: DateComponents(year: 2026, month: 9, day: 6))!
                let event = MeetupEvent(id: "date", start: start, isAllDay: true, calendarTimeZoneID: source.timeZone.identifier)
                let converted = event.forNativeCalendar
                let parts = Calendar.current.dateComponents([.year, .month, .day], from: converted.start)
                try require(parts.year == 2026 && parts.month == 9 && parts.day == 6 && converted.isAllDay, "All-day date shifted into another day")
            }),
            ("selected language resolves search text from actual translated resources", {
                guard CommandLine.arguments.count > 1, let bundle = Bundle(path: CommandLine.arguments[1]) else {
                    throw Failure(description: "Missing real app resource bundle")
                }
                let english = AppLocalization.text("glHomeMeetupTitle", language: "en", bundle: bundle)
                let german = AppLocalization.text("glHomeMeetupTitle", language: "de", bundle: bundle)
                let spanish = AppLocalization.text("glHomeMeetupTitle", language: "es", bundle: bundle)
                try require(english != "glHomeMeetupTitle" && english != german && english != spanish, "Selected language ignored")
                try require(english.localizedStandardContains("home meetup"), "Displayed English glossary would not match search")
            }),
            ("source compact QR fields including country are previewable", {
                let value = try PreviewImport.parse(#"{"v":2,"t":"meetup","m":"vienna_AT","c":"AT","b":900000,"x":2000000000,"s":"unverified","p":"unverified","e":"unverified","n":"00","ts":123,"d":"rolling_qr"}"#)
                try require(value.name == "vienna_AT" && value.blockHeight == 900000 && !value.isDemo, "Lost compact metadata")
            }),
            ("claims and proof-shaped fields cannot become trust", {
                let value = try PreviewImport.parse(#"{"meetupName":"Example","blockHeight":900001,"presenceVerified":true,"sigVersion":2,"sig":"not-a-signature","claimSig":"not-a-claim","claimPubkey":"not-a-key"}"#)
                let fields = try JSONSerialization.jsonObject(with: JSONEncoder().encode(value)) as! [String: Any]
                try require(Set(fields.keys) == ["id", "name", "importedAt", "blockHeight", "isDemo"], "Retained credential/trust input")
            }),
            ("private key and unknown field imports rejected", {
                try rejected(#"{"meetupName":"Example","nsec":"secret"}"#)
                try rejected(#"{"m":"Example","privateKey":"secret"}"#)
                try rejected(#"{"m":"Example","verified":true}"#)
            }),
            ("malformed, array and arbitrary text imports rejected", {
                try rejected("nsec1do-not-import")
                try rejected(#"[{"m":"Example"}]"#)
                try rejected(#"{"m":{"name":"Example"}}"#)
                try rejected(#"{"m":"   "}"#)
            }),
            ("bounded payload and display name", {
                try rejected(String(repeating: "x", count: 65_537))
                try rejected("{\"m\":\"" + String(repeating: "x", count: 201) + "\"}")
                try rejected(#"{"m":"Example","b":-1}"#)
            }),
            ("preview metadata roundtrips without original payload", {
                let value = try PreviewImport.parse(#"{"m":"Example","sigContent":"discard me"}"#)
                let encoded = try JSONEncoder().encode(value)
                try require(!String(decoding: encoded, as: UTF8.self).contains("discard me"), "Persisted raw content")
                let decoded = try JSONDecoder().decode(BadgePreview.self, from: encoded)
                try require(decoded == value, "Preview metadata lost")
            }),
            ("demo and ordinary planning domains remain independent after persistence", {
                var preferences = LocalPreferences()
                let real = MeetupEvent(id: "real-1", title: "Real", start: Date(timeIntervalSince1970: 100))
                let demo = MeetupEvent(id: "demo-1", title: "Preview", start: Date(timeIntervalSince1970: 200))
                preferences.favoriteIDs = ["real-group"]
                preferences.demoFavoriteIDs = ["demo-group"]
                preferences.plans[real.id] = LocalPlan(event: real, status: .going, note: "private local note")
                preferences.demoPlans[demo.id] = LocalPlan(event: demo, status: .maybe, note: "preview")
                let decoded = try JSONDecoder().decode(LocalPreferences.self, from: JSONEncoder().encode(preferences))
                try require(decoded.plans[demo.id] == nil && decoded.demoPlans[real.id] == nil, "Mixed planning sources")
                try require(decoded.plans[real.id]?.note == "private local note", "Lost saved local note")
                try require(decoded.favoriteIDs.isDisjoint(with: decoded.demoFavoriteIDs), "Mixed favorite sources")
            })
        ]
        for (name, test) in tests { try test(); print("PASS \(name)") }
        let temporary = FileManager.default.temporaryDirectory.appendingPathComponent("learning-tests-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: temporary) }
        let client = ControlledLearningClient()
        let learning = LearningStore(clientFactory: { client }, cacheFile: temporary.appendingPathComponent("cache.json"))
        let first = Task { await learning.refresh() }
        await client.waitForCourses(1)
        first.cancel()
        await first.value
        try require(!learning.loading, "Cancelled refresh permanently held loading")
        await learning.refresh()
        try require(!learning.loading && learning.cache.courses.first?.id == 7, "Refresh did not recover after cancellation")
        print("PASS learning refresh cancellation releases loading and permits retry")
        let old = Task { await learning.refresh() }
        await client.waitForCourses(3)
        learning.invalidateRequests()
        let fresh = Task { await learning.refresh() }
        await client.waitForCourses(4)
        old.cancel()
        await old.value
        try require(learning.loading, "Old cancelled request cleared newer generation loading")
        await fresh.value
        try require(!learning.loading && learning.cache.courses.count == 1, "Newer generation did not complete")
        print("PASS obsolete learning generation cannot clear a newer refresh")
        var nested = LearningCache()
        nested.courses = [Course(id: 7, name: "List course", lecturers: [LecturerSummary(id: 144, name: "Nested educator")])]
        nested.lecturers = [Lecturer(id: 8, name: "List educator", courses: [CourseSummary(id: 44, name: "Nested course")])]
        let nestedFile = temporary.appendingPathComponent("nested.json")
        try JSONEncoder().encode(nested).write(to: nestedFile)
        let restored = LearningStore(cacheFile: nestedFile)
        try require(restored.cachedLecturer(144)?.name == "Nested educator" && restored.cachedCourse(44)?.name == "Nested course", "Persisted list relationships lost their fallback")
        print("PASS offline related IDs resolve from persisted lists")
        let server = LearningFixtureServer()
        let resettable = LearningStore(clientFactory: { ResetCachingClient(server: server) }, cacheFile: temporary.appendingPathComponent("reset.json"))
        let beforeReset = try await resettable.course(44)
        try require(beforeReset.value?.name == "Before reset", "Expected initial public detail")
        try resettable.reset()
        await server.goOffline()
        let afterReset = try await resettable.course(44)
        try require(afterReset.value == nil && afterReset.source == .unavailable, "Reset resurrected a pre-reset client detail")
        print("PASS reset replaces client cache and cannot resurrect old details")
        print("LocalModels: \(tests.count + 4) tests passed")
    }
}

actor ControlledLearningClient: LearningDataSource {
    private var calls = 0
    private var waiters: [(Int, CheckedContinuation<Void, Never>)] = []
    func waitForCourses(_ count: Int) async {
        if calls >= count { return }
        await withCheckedContinuation { waiters.append((count, $0)) }
    }
    func courses() async throws -> [Course] {
        calls += 1
        let ready = waiters.filter { $0.0 <= calls }
        waiters.removeAll { $0.0 <= calls }
        ready.forEach { $0.1.resume() }
        try await Task.sleep(for: .milliseconds(80))
        return [Course(id: 7, name: "Fixture course")]
    }
    func lecturers() async throws -> [Lecturer] { [] }
    func courseDetail(id: Int) async throws -> PublicDetailResult<Course> { PublicDetailResult(value: nil, source: .unavailable, failure: .notFound) }
    func lecturerDetail(id: Int) async throws -> PublicDetailResult<Lecturer> { PublicDetailResult(value: nil, source: .unavailable, failure: .notFound) }
}

actor LearningFixtureServer {
    private var online = true
    func goOffline() { online = false }
    func detail() -> Course? { online ? Course(id: 44, name: "Before reset") : nil }
}
actor ResetCachingClient: LearningDataSource {
    let server: LearningFixtureServer
    private var cached: Course?
    init(server: LearningFixtureServer) { self.server = server }
    func courses() async throws -> [Course] { [] }
    func lecturers() async throws -> [Lecturer] { [] }
    func courseDetail(id: Int) async throws -> PublicDetailResult<Course> {
        if let value = await server.detail() { cached = value; return PublicDetailResult(value: value, source: .fresh) }
        return PublicDetailResult(value: cached, source: cached == nil ? .unavailable : .cache, failure: .notFound)
    }
    func lecturerDetail(id: Int) async throws -> PublicDetailResult<Lecturer> { PublicDetailResult(value: nil, source: .unavailable, failure: .notFound) }
}
#endif

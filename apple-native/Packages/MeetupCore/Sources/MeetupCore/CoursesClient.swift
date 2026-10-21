import Foundation

/// Public, read-only Portal courses. The shared transport bounds responses and uses a 15-second timeout.
public actor CoursesClient {
    private let transport: any HTTPTransport
    private let floatingTimeZone: TimeZone
    private var courseCache: [Int: CourseRecord] = [:]
    private var lecturerCache: [Int: LecturerRecord] = [:]

    public init() { transport = URLSessionTransport(); floatingTimeZone = .current }
    init(transport: any HTTPTransport, floatingTimeZone: TimeZone = .current) {
        self.transport = transport; self.floatingTimeZone = floatingTimeZone
    }

    public func courses() async throws -> [Course] {
        let data = try await get("api/courses", withDetails: true)
        var graph = CourseGraph()
        let values = try CourseParsing.rows(data).map {
            try CourseParsing.course($0, rank: 1, timeZone: floatingTimeZone, graph: &graph)
        }
        hydrate(graph)
        return unique(values, id: \.id)
    }

    public func lecturers() async throws -> [Lecturer] {
        let data = try await get("api/lecturers", withDetails: true)
        var graph = CourseGraph()
        let values = try CourseParsing.rows(data).map {
            try CourseParsing.lecturer($0, rank: 1, timeZone: floatingTimeZone, graph: &graph)
        }
        hydrate(graph)
        return unique(values, id: \.id)
    }

    /// For UI use `courseDetail(id:)` so cached content is visibly identified as such.
    /// An uncached 404 returns nil. Other uncached errors throw; cached failures return the fallback.
    public func course(id: Int) async throws -> Course? { try unwrap(await courseDetail(id: id)) }

    /// For UI use `lecturerDetail(id:)` to retain provenance and the reason for a fallback.
    public func lecturer(id: Int) async throws -> Lecturer? { try unwrap(await lecturerDetail(id: id)) }

    /// Only cancellation throws. All other failures carry cache/unavailable provenance in the result.
    public func courseDetail(id: Int) async throws -> PublicDetailResult<Course> {
        do {
            guard id > 0 else { throw MeetupCoreError.invalidData("Course ID must be positive.") }
            let data = try await get("api/courses/\(id)")
            let row = try CourseParsing.detail(data)
            guard CourseParsing.integerID(row["id"]) == id else {
                throw MeetupCoreError.invalidData("Course detail ID does not match the request.")
            }
            var graph = CourseGraph()
            let value = try CourseParsing.course(row, rank: 2, timeZone: floatingTimeZone, graph: &graph)
            hydrate(graph)
            return PublicDetailResult(value: value, source: .fresh)
        } catch {
            try rethrowCancellation(error)
            return fallback(courseCache[id]?.value, error: error)
        }
    }

    public func lecturerDetail(id: Int) async throws -> PublicDetailResult<Lecturer> {
        do {
            guard id > 0 else { throw MeetupCoreError.invalidData("Lecturer ID must be positive.") }
            let data = try await get("api/lecturers/\(id)")
            let row = try CourseParsing.detail(data)
            guard CourseParsing.integerID(row["id"]) == id else {
                throw MeetupCoreError.invalidData("Lecturer detail ID does not match the request.")
            }
            var graph = CourseGraph()
            let value = try CourseParsing.lecturer(row, rank: 2, timeZone: floatingTimeZone, graph: &graph)
            hydrate(graph)
            return PublicDetailResult(value: value, source: .fresh)
        } catch {
            try rethrowCancellation(error)
            return fallback(lecturerCache[id]?.value, error: error)
        }
    }

    private func get(_ path: String, withDetails: Bool = false) async throws -> Data {
        var parts = URLComponents(url: PortalClient.baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if withDetails { parts.queryItems = [URLQueryItem(name: "withDetails", value: "1")] }
        return try await transport.get(parts.url!, accept: "application/json")
    }

    private func unwrap<T>(_ result: PublicDetailResult<T>) throws -> T? {
        if let value = result.value { return value }
        if let failure = result.failure, failure != .notFound { throw failure }
        return nil
    }

    private func rethrowCancellation(_ error: any Error) throws {
        if error is CancellationError || Task.isCancelled || (error as? URLError)?.code == .cancelled {
            throw CancellationError()
        }
    }

    private func fallback<T>(_ value: T?, error: any Error) -> PublicDetailResult<T> {
        let failure: PublicDetailFailure
        switch error {
        case MeetupCoreError.httpStatus(404): failure = .notFound
        case MeetupCoreError.httpStatus(let code): failure = .httpStatus(code)
        case MeetupCoreError.invalidData(let message): failure = .invalidData(message)
        default: failure = .transport(error.localizedDescription)
        }
        return PublicDetailResult(value: value, source: value == nil ? .unavailable : .cache, failure: failure)
    }

    private func hydrate(_ graph: CourseGraph) {
        // Presence and per-field priority keep nested/list summaries from erasing full detail fields.
        for record in graph.courses {
            var cached = courseCache[record.value.id] ?? CourseRecord(value: Course(id: record.value.id))
            cached.merge(record)
            courseCache[record.value.id] = cached
        }
        for record in graph.lecturers {
            var cached = lecturerCache[record.value.id] ?? LecturerRecord(value: Lecturer(id: record.value.id))
            cached.merge(record)
            lecturerCache[record.value.id] = cached
        }
        // Reverse references are useful even when nested summaries have no relationship fields.
        for (courseID, lecturerID) in graph.relationships {
            guard var course = courseCache[courseID], var lecturer = lecturerCache[lecturerID] else { continue }
            if !course.value.lecturers.contains(where: { $0.id == lecturerID }) {
                course.value.lecturers.append(lecturer.value.summary)
            }
            if !lecturer.value.courses.contains(where: { $0.id == courseID }) {
                lecturer.value.courses.append(course.value.summary)
            }
            courseCache[courseID] = course; lecturerCache[lecturerID] = lecturer
        }
    }
}

private func unique<T, ID: Hashable>(_ values: [T], id: KeyPath<T, ID>) -> [T] {
    var seen = Set<ID>()
    return values.filter { seen.insert($0[keyPath: id]).inserted }
}

private struct CourseRecord {
    var value: Course
    var ranks: [String: Int] = [:]

    mutating func merge(_ incoming: Self) {
        func take<T>(_ key: String, _ path: WritableKeyPath<Course, T>) {
            guard let rank = incoming.ranks[key], rank >= (ranks[key] ?? -1) else { return }
            value[keyPath: path] = incoming.value[keyPath: path]; ranks[key] = rank
        }
        take("name", \.name); take("description", \.description); take("shortDescription", \.shortDescription)
        take("image", \.imageURL); take("url", \.url); take("lecturers", \.lecturers); take("events", \.events)
    }
}

private struct LecturerRecord {
    var value: Lecturer
    var ranks: [String: Int] = [:]

    mutating func merge(_ incoming: Self) {
        func take<T>(_ key: String, _ path: WritableKeyPath<Lecturer, T>) {
            guard let rank = incoming.ranks[key], rank >= (ranks[key] ?? -1) else { return }
            value[keyPath: path] = incoming.value[keyPath: path]; ranks[key] = rank
        }
        take("name", \.name); take("description", \.description); take("intro", \.intro)
        take("image", \.imageURL); take("url", \.url); take("website", \.websiteURL)
        take("twitter", \.twitterURL); take("nostr", \.nostrURL); take("courses", \.courses)
    }
}

private struct CourseGraph {
    var courses: [CourseRecord] = []
    var lecturers: [LecturerRecord] = []
    var relationships: [(Int, Int)] = []
}

enum CourseParsing {
    static func rows(_ data: Data) throws -> [[String: Any]] {
        let root = try json(data)
        let object = root as? [String: Any]
        let payload = nonNull(object?["data"]) ?? nonNull(object?["items"]) ?? root
        guard let rows = payload as? [[String: Any]] else {
            throw MeetupCoreError.invalidData("Expected an array of course or lecturer objects.")
        }
        var seen = Set<Int>()
        return try rows.filter { row in
            guard let id = integerID(row["id"]) else { throw MeetupCoreError.invalidData("List entry has no valid integer ID.") }
            return seen.insert(id).inserted
        }
    }

    static func detail(_ data: Data) throws -> [String: Any] {
        let root = try json(data)
        guard let object = root as? [String: Any],
              let row = (nonNull(object["data"]) ?? root) as? [String: Any] else {
            throw MeetupCoreError.invalidData("Expected a course or lecturer object.")
        }
        return row
    }

    private static func json(_ data: Data) throws -> Any {
        do { return try JSONSerialization.jsonObject(with: data) }
        catch { throw MeetupCoreError.invalidData("Expected valid JSON.") }
    }

    static func integerID(_ value: Any?) -> Int? {
        guard let text = JSONFields.string(value), !text.isEmpty,
              text.utf8.allSatisfy({ (48...57).contains($0) }), let id = Int(text), id > 0 else { return nil }
        return id
    }

    fileprivate static func course(_ row: [String: Any], rank: Int, timeZone: TimeZone,
                                   graph: inout CourseGraph, depth: Int = 0) throws -> Course {
        guard depth <= 8, let id = integerID(row["id"]) else {
            throw MeetupCoreError.invalidData("Course has no valid integer ID, or relationships are too deeply nested.")
        }
        var lecturers: [LecturerSummary] = []
        var related = try objects(row["lecturers"], field: "lecturers")
        if let single = try relationship(row, prefix: "lecturer") { related.insert(single, at: 0) }
        for nested in related {
            let lecturer = try lecturer(nested, rank: 0, timeZone: timeZone, graph: &graph, depth: depth + 1)
            lecturers.append(lecturer.summary); graph.relationships.append((id, lecturer.id))
        }
        let eventRows = try objects(row["events"] is [Any] ? row["events"] : row["course_events"] ?? row["events"], field: "events")
        let value = Course(
            id: id, name: plain(first(row, ["name", "title"])) ?? "",
            description: plain(first(row, ["description", "intro", "bio"])) ?? "",
            shortDescription: plain(first(row, ["intro"])), imageURL: image(row), url: portalURL(row),
            lecturers: unique(lecturers, id: \.id),
            events: unique(try eventRows.map { try event($0, courseID: id, courseName: first(row, ["name", "title"]) ?? "", timeZone: timeZone) }, id: \.id)
        )
        let fields: [String: [String]] = ["name": ["name", "title"], "description": ["description", "intro", "bio"],
            "shortDescription": ["intro"], "image": imageKeys, "url": ["portalLink", "url"],
            "lecturers": ["lecturer", "lecturers", "lecturer.id"], "events": ["events", "course_events"]]
        graph.courses.append(CourseRecord(value: value, ranks: ranks(row, fields: fields, rank: rank)))
        return value
    }

    fileprivate static func lecturer(_ row: [String: Any], rank: Int, timeZone: TimeZone,
                                     graph: inout CourseGraph, depth: Int = 0) throws -> Lecturer {
        guard depth <= 8, let id = integerID(row["id"]) else {
            throw MeetupCoreError.invalidData("Lecturer has no valid integer ID, or relationships are too deeply nested.")
        }
        var courses: [CourseSummary] = []
        for nested in try objects(row["courses"], field: "courses") {
            let course = try course(nested, rank: 0, timeZone: timeZone, graph: &graph, depth: depth + 1)
            courses.append(course.summary); graph.relationships.append((course.id, id))
        }
        let value = Lecturer(
            id: id, name: plain(first(row, ["name", "title"])) ?? "",
            description: plain(first(row, ["description", "bio", "intro"])) ?? "",
            intro: plain(first(row, ["intro", "subtitle"])), imageURL: image(row), url: portalURL(row),
            websiteURL: first(row, ["website"]).flatMap(SafeURL.web),
            twitterURL: twitter(first(row, ["twitter", "twitter_username"])),
            nostrURL: nostr(first(row, ["nostr"])), courses: unique(courses, id: \.id)
        )
        let fields: [String: [String]] = ["name": ["name", "title"], "description": ["description", "bio", "intro"],
            "intro": ["intro", "subtitle"], "image": imageKeys, "url": ["portalLink", "url"],
            "website": ["website"], "twitter": ["twitter", "twitter_username"], "nostr": ["nostr"], "courses": ["courses"]]
        graph.lecturers.append(LecturerRecord(value: value, ranks: ranks(row, fields: fields, rank: rank)))
        return value
    }

    private static func event(_ row: [String: Any], courseID: Int, courseName: String, timeZone: TimeZone) throws -> CourseDate {
        guard let start = date(first(row, ["from", "start"]), floatingTimeZone: timeZone) else {
            throw MeetupCoreError.invalidData("Course \(courseID) event has no valid start date.")
        }
        let title = plain(first(row, ["name", "title"])) ?? HTMLText.plain(courseName)
        let location = plain(first(row, ["location"]) ?? text(field(row, "venue.name"))) ?? ""
        let id: String
        if let raw = nonNull(row["id"]) {
            let normalized = raw is String ? JSONFields.string(raw) : integerID(raw).map(String.init)
            guard let value = normalized else { throw MeetupCoreError.invalidData("Invalid course event ID.") }
            id = value
        } else {
            // Stable across reloads and array ordering, even if the endpoint omits an event ID.
            id = "course:\(courseID):\(start.timeIntervalSince1970):\(title.utf8.count):\(title):\(location)"
        }
        let latitude = JSONFields.firstNumber(row["osm_lat"], row["latitude"], row["lat"], field(row, "venue.latitude"), field(row, "venue.lat"))
        let longitude = JSONFields.firstNumber(row["osm_lon"], row["longitude"], row["lon"], row["lng"], field(row, "venue.longitude"), field(row, "venue.lon"), field(row, "venue.lng"))
        return CourseDate(
            id: id, title: title, description: plain(first(row, ["description"])) ?? "", start: start,
            end: date(first(row, ["to", "end"]), floatingTimeZone: timeZone), location: location,
            joinURL: first(row, ["link"]).flatMap(SafeURL.web),
            url: first(row, ["url", "portalLink"]).flatMap(SafeURL.web),
            latitude: latitude.flatMap { (-90...90).contains($0) ? $0 : nil },
            longitude: longitude.flatMap { (-180...180).contains($0) ? $0 : nil }
        )
    }

    /// Flutter's course UI uses plain DateTime.parse, unlike the meetup UTC contract.
    /// Offset-free timestamps use the device zone captured by the client. DST gaps move forward
    /// preserving minutes; repeated times select the first occurrence. Explicit offsets stay absolute.
    static func date(_ text: String?, floatingTimeZone: TimeZone) -> Date? {
        guard let text = text?.trimmingCharacters(in: .whitespacesAndNewlines),
              let naive = DateParsing.portal(text) else { return nil }
        if text.range(of: #"(?:[Zz]|[+-][0-9]{2}:?[0-9]{2})$"#, options: .regularExpression) != nil { return naive }
        var utc = Calendar(identifier: .gregorian); utc.timeZone = TimeZone(secondsFromGMT: 0)!
        var local = Calendar(identifier: .gregorian); local.timeZone = floatingTimeZone
        let components = utc.dateComponents([.year, .month, .day, .hour, .minute, .second], from: naive)
        guard let midnight = local.date(from: DateComponents(year: components.year, month: components.month, day: components.day)),
              let value = local.nextDate(after: midnight.addingTimeInterval(-1), matching: components,
                                         matchingPolicy: .nextTimePreservingSmallerComponents, repeatedTimePolicy: .first) else { return nil }
        return value.addingTimeInterval(naive.timeIntervalSince1970 - floor(naive.timeIntervalSince1970))
    }

    private static let imageKeys = ["image", "logo", "avatar", "picture", "cover"]
    private static func image(_ row: [String: Any]) -> URL? {
        imageKeys.compactMap { text(row[$0]).flatMap(portalWeb) }.first
    }
    private static func portalURL(_ row: [String: Any]) -> URL? {
        if let value = text(row["portalLink"]) { return portalWeb(value) }
        return text(row["url"]).flatMap(SafeURL.web)
    }
    private static func portalWeb(_ value: String) -> URL? {
        guard !value.contains("\\"),
              !value.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else { return nil }
        return PortalParsing.web(value)
    }
    private static func twitter(_ value: String?) -> URL? {
        guard let value else { return nil }
        if let url = SafeURL.web(value) { return url }
        let name = value.hasPrefix("@") ? String(value.dropFirst()) : value
        guard (1...15).contains(name.utf8.count), name.utf8.allSatisfy({
            (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 95
        }) else { return nil }
        return SafeURL.web("https://x.com/" + name)
    }
    private static func nostr(_ value: String?) -> URL? {
        guard let value else { return nil }
        if let url = SafeURL.web(value) { return url }
        guard let identity = PublicIdentity.normalize(value) else { return nil }
        return SafeURL.web("https://njump.me/" + identity.npub)
    }
    private static func plain(_ value: String?) -> String? {
        guard let value else { return nil }
        let result = HTMLText.plain(value)
        return result.isEmpty ? nil : result
    }
    private static func text(_ value: Any?) -> String? { (value as? String).flatMap(JSONFields.string) }
    private static func first(_ row: [String: Any], _ keys: [String]) -> String? { keys.compactMap { text(row[$0]) }.first }
    private static func nonNull(_ value: Any?) -> Any? { value is NSNull ? nil : value }
    private static func field(_ row: [String: Any], _ key: String) -> Any? {
        if let flat = nonNull(row[key]) { return flat }
        let keys = key.split(separator: ".", maxSplits: 1).map(String.init)
        return (row[keys[0]] as? [String: Any])?[keys[1]]
    }
    private static func relationship(_ row: [String: Any], prefix: String) throws -> [String: Any]? {
        var nested = row[prefix] as? [String: Any] ?? [:]
        for (key, value) in row where key.hasPrefix(prefix + ".") && !(value is NSNull) {
            nested[String(key.dropFirst(prefix.count + 1))] = value
        }
        if nested.isEmpty {
            guard nonNull(row[prefix]) == nil else { throw MeetupCoreError.invalidData("Invalid \(prefix) relationship.") }
            return nil
        }
        return nested
    }
    private static func objects(_ value: Any?, field: String) throws -> [[String: Any]] {
        guard let value = nonNull(value) else { return [] }
        guard let rows = value as? [[String: Any]] else { throw MeetupCoreError.invalidData("Invalid \(field) relationship array.") }
        return rows
    }
    private static func ranks(_ row: [String: Any], fields: [String: [String]], rank: Int) -> [String: Int] {
        fields.reduce(into: [:]) { result, entry in
            if entry.value.contains(where: { row.keys.contains($0) }) { result[entry.key] = rank }
        }
    }
}

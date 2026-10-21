import Foundation
import MeetupCore
import Observation

protocol LearningDataSource: Sendable {
    func courses() async throws -> [Course]
    func lecturers() async throws -> [Lecturer]
    func courseDetail(id: Int) async throws -> PublicDetailResult<Course>
    func lecturerDetail(id: Int) async throws -> PublicDetailResult<Lecturer>
}
extension CoursesClient: LearningDataSource {}

struct LearningCache: Codable {
    var courses: [Course] = []
    var lecturers: [Lecturer] = []
    var courseDetails: [Int: Course] = [:]
    var lecturerDetails: [Int: Lecturer] = [:]
    var courseTimes: [Int: Date] = [:]
    var lecturerTimes: [Int: Date] = [:]
    var coursesFetchedAt: Date?
    var lecturersFetchedAt: Date?
}

@MainActor @Observable
final class LearningStore {
    private(set) var cache = LearningCache()
    private(set) var loading = false
    private(set) var issues: [String] = []
    private(set) var refreshedThisSession = false
    var persistenceFailed = false
    private var client: any LearningDataSource
    private let clientFactory: () -> any LearningDataSource
    private var generation = UUID()
    private let file: URL

    init(clientFactory: @escaping () -> any LearningDataSource = { CoursesClient() }, cacheFile: URL? = nil) {
        self.clientFactory = clientFactory
        self.client = clientFactory()
        file = cacheFile ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("EinundzwanzigApple", isDirectory: true).appendingPathComponent("public-learning-cache.json")
        if let data = try? Data(contentsOf: file), let decoded = try? JSONDecoder().decode(LearningCache.self, from: data) { cache = decoded }
    }
    func cachedCourse(_ id: Int) -> Course? {
        if let detail = cache.courseDetails[id] { return detail }
        if let value = cache.courses.first(where: { $0.id == id }) { return value }
        if let related = (cache.lecturerDetails.values.flatMap(\.courses) + cache.lecturers.flatMap(\.courses)).first(where: { $0.id == id }) {
            return Course(id: id, name: related.name, shortDescription: related.shortDescription, imageURL: related.imageURL, url: related.url)
        }
        return nil
    }
    func cachedLecturer(_ id: Int) -> Lecturer? {
        if let detail = cache.lecturerDetails[id] { return detail }
        if let value = cache.lecturers.first(where: { $0.id == id }) { return value }
        if let related = (cache.courseDetails.values.flatMap(\.lecturers) + cache.courses.flatMap(\.lecturers)).first(where: { $0.id == id }) {
            return Lecturer(id: id, name: related.name, intro: related.shortDescription, imageURL: related.imageURL, url: related.url)
        }
        return nil
    }

    func refresh() async {
        guard !loading, !Task.isCancelled else { return }
        loading = true
        let requestID = generation
        defer { if requestID == generation { loading = false } }
        issues = []
        let client = self.client
        async let coursesResult = learningResult { try await client.courses() }
        async let lecturersResult = learningResult { try await client.lecturers() }
        let (courses, lecturers) = await (coursesResult, lecturersResult)
        guard requestID == generation, !Task.isCancelled else { return }
        switch courses {
        case .success(let values): cache.courses = values; cache.coursesFetchedAt = Date()
        case .failure: issues.append("learning.coursesFailed")
        }
        switch lecturers {
        case .success(let values): cache.lecturers = values; cache.lecturersFetchedAt = Date()
        case .failure: issues.append("learning.lecturersFailed")
        }
        refreshedThisSession = true
        loading = false
        persist()
    }

    func course(_ id: Int) async throws -> PublicDetailResult<Course> {
        let requestID = generation
        let response = try await client.courseDetail(id: id)
        guard requestID == generation, !Task.isCancelled else { throw CancellationError() }
        if response.source == .fresh, let value = response.value {
            cache.courseDetails[id] = value
            cache.courseTimes[id] = Date()
            persist()
            return response
        }
        let fallback = cachedCourse(id) ?? response.value
        return PublicDetailResult(value: fallback, source: fallback == nil ? .unavailable : .cache, failure: response.failure)
    }
    func lecturer(_ id: Int) async throws -> PublicDetailResult<Lecturer> {
        let requestID = generation
        let response = try await client.lecturerDetail(id: id)
        guard requestID == generation, !Task.isCancelled else { throw CancellationError() }
        if response.source == .fresh, let value = response.value {
            cache.lecturerDetails[id] = value
            cache.lecturerTimes[id] = Date()
            persist()
            return response
        }
        let fallback = cachedLecturer(id) ?? response.value
        return PublicDetailResult(value: fallback, source: fallback == nil ? .unavailable : .cache, failure: response.failure)
    }
    func invalidateRequests() {
        generation = UUID()
        loading = false
        issues = []
    }
    func reset() throws {
        invalidateRequests()
        if FileManager.default.fileExists(atPath: file.path) { try FileManager.default.removeItem(at: file) }
        // Replacing the actor also isolates any late cache hydration by old requests.
        client = clientFactory()
        cache = LearningCache()
        refreshedThisSession = false
        persistenceFailed = false
    }
    private func persist() {
        do {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
            let data = try JSONEncoder().encode(cache)
            #if os(iOS)
            try data.write(to: file, options: [.atomic, .completeFileProtection])
            #else
            try data.write(to: file, options: .atomic)
            #endif
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
            var url = file
            var attributes = URLResourceValues()
            attributes.isExcludedFromBackup = true
            try url.setResourceValues(attributes)
            persistenceFailed = false
        } catch { persistenceFailed = true }
    }
}

private func learningResult<T: Sendable>(_ request: @Sendable () async throws -> T) async -> Result<T, any Error> {
    do { return .success(try await request()) } catch { return .failure(error) }
}

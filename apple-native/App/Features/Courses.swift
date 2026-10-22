import SwiftUI
import MeetupCore

struct CoursesView: View {
    @Environment(AppStore.self) private var store
    @State private var tab = "courses"
    @State private var query = ""
    private var courses: [Course] { store.preferences.demo ? [PreviewFixtures.course] : store.learning.cache.courses }
    private var lecturers: [Lecturer] { store.preferences.demo ? [PreviewFixtures.lecturer] : store.learning.cache.lecturers }

    var body: some View {
        List {
            Section {
                if store.preferences.demo { Label("source.demo", systemImage: "testtube.2") }
                else {
                    Label("learning.publicSource", systemImage: "graduationcap").font(.caption)
                    Text("learning.summaryNotice").font(.caption).foregroundStyle(.secondary)
                    if !store.learning.refreshedThisSession { Text("learning.cached").font(.caption).foregroundStyle(.secondary) }
                    ForEach(store.learning.issues, id: \.self) { Text(LocalizedStringKey($0)).font(.caption).foregroundStyle(.secondary) }
                    if let fetched = tab == "courses" ? store.learning.cache.coursesFetchedAt : store.learning.cache.lecturersFetchedAt {
                        Text(fetched, format: .dateTime.day().month().year().hour().minute()).font(.caption).foregroundStyle(.secondary)
                    }
                    if store.learning.persistenceFailed { Text("learning.cacheFailed").font(.caption).foregroundStyle(.secondary) }
                }
                Picker("learning.browse", selection: $tab) {
                    Text("learning.courses").tag("courses")
                    Text("learning.lecturers").tag("lecturers")
                }.pickerStyle(.segmented)
            }
            if tab == "courses" {
                let filtered = courses.filter { query.isEmpty || "\($0.name) \($0.description) \($0.shortDescription ?? "")".localizedStandardContains(query) }
                if filtered.isEmpty { EmptyState(title: "learning.empty", symbol: "graduationcap", message: "learning.empty.body") }
                ForEach(filtered) { course in
                    NavigationLink(value: AppRoute.course(course.id)) {
                        LearningRow(name: course.name, summary: course.shortDescription ?? course.description, imageURL: course.imageURL, symbol: "graduationcap")
                    }
                }
            } else {
                let filtered = lecturers.filter { query.isEmpty || "\($0.name) \($0.description) \($0.intro ?? "")".localizedStandardContains(query) }
                if filtered.isEmpty { EmptyState(title: "learning.empty", symbol: "person", message: "learning.empty.body") }
                ForEach(filtered) { lecturer in
                    NavigationLink(value: AppRoute.lecturer(lecturer.id)) {
                        LearningRow(name: lecturer.name, summary: lecturer.intro ?? lecturer.description, imageURL: lecturer.imageURL, symbol: "person")
                    }
                }
            }
        }
        .navigationTitle("learning.title")
        .searchable(text: $query, prompt: "learning.search")
        .refreshable { if !store.preferences.demo { await store.learning.refresh() } }
        .task(id: store.preferences.demo) { if !store.preferences.demo { await store.learning.refresh() } }
        .overlay { if !store.preferences.demo && store.learning.loading && courses.isEmpty && lecturers.isEmpty { ProgressView("action.loading") } }
    }
}

struct LearningRow: View {
    let name: String
    let summary: String
    let imageURL: URL?
    let symbol: String
    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            AsyncImage(url: imageURL) { image in image.resizable().scaledToFill() } placeholder: {
                Image(systemName: symbol).foregroundStyle(Color.accentColor)
            }.frame(width: 56, height: 56).clipShape(RoundedRectangle(cornerRadius: 12)).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 5) {
                Text(name).font(.headline)
                if !summary.isEmpty { Text(summary).font(.subheadline).foregroundStyle(.secondary).lineLimit(3) }
            }
        }.padding(.vertical, 6)
    }
}

struct LearningDetailNotice: View {
    let source: PublicDetailSource
    let failure: PublicDetailFailure?
    let fetchedAt: Date?
    let demo: Bool
    let loading: Bool
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(demo ? "source.demo" : source == .fresh ? "learning.fresh" : "learning.cached", systemImage: demo ? "testtube.2" : source == .fresh ? "network" : "internaldrive")
                .font(.caption.weight(.semibold))
            if loading { ProgressView("action.loading") }
            if failure != nil { Text("learning.detailFailed").font(.caption).foregroundStyle(.secondary) }
            if case .some(.notFound) = failure { Text("learning.notFound").font(.caption).foregroundStyle(.secondary) }
            if let fetchedAt { Text(fetchedAt, format: .dateTime.day().month().year().hour().minute()).font(.caption).foregroundStyle(.secondary) }
        }
    }
}

struct CourseDetailView: View {
    @Environment(AppStore.self) private var store
    let id: Int
    @State private var response: PublicDetailResult<Course>?
    @State private var loading = false
    @State private var reload = UUID()
    @State private var requestGeneration = UUID()
    private var course: Course? { store.preferences.demo ? (id == PreviewFixtures.course.id ? PreviewFixtures.course : nil) : response?.value ?? store.learning.cachedCourse(id) }
    var body: some View {
        List {
            LearningDetailNotice(source: response?.source ?? .cache, failure: response?.failure,
                                 fetchedAt: store.learning.cache.courseTimes[id], demo: store.preferences.demo, loading: loading)
            if let course {
                Section {
                    LearningRow(name: course.name, summary: course.shortDescription ?? "", imageURL: course.imageURL, symbol: "graduationcap")
                    if let url = course.url { Link("learning.openPortal", destination: url); ShareLink(item: url) }
                }
                Section("learning.dates") {
                    if course.events.isEmpty { Text("learning.noDatesIncluded").foregroundStyle(.secondary) }
                    ForEach(course.events.sorted { $0.start < $1.start }) { date in
                        VStack(alignment: .leading, spacing: 12) {
                            let event = date.calendarEvent(course: course)
                            EventRow(event: event)
                            if let end = date.end { LabeledContent("event.ends") { Text(end, format: .dateTime.day().month().hour().minute()) }.font(.caption) }
                            if !date.description.isEmpty { Text(date.description).font(.subheadline).foregroundStyle(.secondary) }
                            NativeCalendarButton(event: event)
                            if let url = date.joinURL { Link("learning.join", destination: url) }
                        }.padding(.vertical, 8)
                    }
                }
                if !course.description.isEmpty { Section("learning.about") { Text(course.description).textSelection(.enabled) } }
                if !course.descriptionURLs.isEmpty {
                    Section("resource.links") {
                        ForEach(course.descriptionURLs, id: \.self) { url in Link(url.absoluteString, destination: url) }
                    }
                }
                if !course.lecturers.isEmpty {
                    Section("learning.lecturers") {
                        ForEach(course.lecturers) { lecturer in
                            NavigationLink(value: AppRoute.lecturer(lecturer.id)) { LearningRow(name: lecturer.name, summary: lecturer.shortDescription ?? "", imageURL: lecturer.imageURL, symbol: "person") }
                        }
                    }
                }
            } else if !loading { MissingContentView() }
            if !store.preferences.demo { Button("action.retry") { reload = UUID() } }
            if store.learning.persistenceFailed { Text("learning.cacheFailed").font(.caption).foregroundStyle(.secondary) }
        }
        .navigationTitle("learning.courses")
        .task(id: "\(id)|\(store.preferences.demo)|\(reload)") {
            let requestID = UUID()
            requestGeneration = requestID
            response = nil
            loading = false
            guard !store.preferences.demo else { return }
            loading = true
            defer { if requestID == requestGeneration { loading = false } }
            do { let result = try await store.learning.course(id); if !Task.isCancelled { response = result } }
            catch { /* A replaced detail task does not publish a stale result. */ }
        }
    }
}

struct LecturerDetailView: View {
    @Environment(AppStore.self) private var store
    let id: Int
    @State private var response: PublicDetailResult<Lecturer>?
    @State private var loading = false
    @State private var reload = UUID()
    @State private var requestGeneration = UUID()
    private var lecturer: Lecturer? { store.preferences.demo ? (id == PreviewFixtures.lecturer.id ? PreviewFixtures.lecturer : nil) : response?.value ?? store.learning.cachedLecturer(id) }
    var body: some View {
        List {
            LearningDetailNotice(source: response?.source ?? .cache, failure: response?.failure,
                                 fetchedAt: store.learning.cache.lecturerTimes[id], demo: store.preferences.demo, loading: loading)
            if let lecturer {
                Section { LearningRow(name: lecturer.name, summary: lecturer.intro ?? "", imageURL: lecturer.imageURL, symbol: "person") }
                if !lecturer.description.isEmpty { Section("learning.about") { Text(lecturer.description).textSelection(.enabled) } }
                if !lecturer.courses.isEmpty {
                    Section("learning.courses") {
                        ForEach(lecturer.courses) { course in
                            NavigationLink(value: AppRoute.course(course.id)) { LearningRow(name: course.name, summary: course.shortDescription ?? "", imageURL: course.imageURL, symbol: "graduationcap") }
                        }
                    }
                }
                Section("resource.links") {
                    if let url = lecturer.url { Link("learning.openPortal", destination: url) }
                    if let url = lecturer.websiteURL { Link("learning.website", destination: url) }
                    if let url = lecturer.twitterURL { Link("learning.twitter", destination: url) }
                    if let url = lecturer.nostrURL { Link("learning.nostr", destination: url) }
                }
            } else if !loading { MissingContentView() }
            if !store.preferences.demo { Button("action.retry") { reload = UUID() } }
        }
        .navigationTitle("learning.lecturers")
        .task(id: "\(id)|\(store.preferences.demo)|\(reload)") {
            let requestID = UUID()
            requestGeneration = requestID
            response = nil
            loading = false
            guard !store.preferences.demo else { return }
            loading = true
            defer { if requestID == requestGeneration { loading = false } }
            do { let result = try await store.learning.lecturer(id); if !Task.isCancelled { response = result } }
            catch { }
        }
    }
}

extension CourseDate {
    func calendarEvent(course: Course) -> MeetupEvent {
        MeetupEvent(id: "course:\(course.id):\(id)", title: title.isEmpty ? course.name : title,
                    description: description.isEmpty ? course.description : description,
                    start: start, end: end, location: location, latitude: latitude, longitude: longitude, url: joinURL ?? url ?? course.url)
    }
}

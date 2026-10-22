import Foundation
import MeetupCore
import SwiftUI
import UniformTypeIdentifiers

/// Exporting this document writes a file. It does not report a calendar save.
struct CalendarDocument: FileDocument {
    static var contentType: UTType {
        UTType(filenameExtension: "ics", conformingTo: .calendarEvent) ?? .calendarEvent
    }
    static var readableContentTypes: [UTType] { [contentType] }
    static var writableContentTypes: [UTType] { [contentType] }

    private let contents: Data

    init(event: MeetupEvent) throws {
        contents = try CalendarICS(
            uid: event.id,
            title: event.title,
            description: event.description,
            start: event.start,
            end: event.end,
            location: event.location,
            url: event.url,
            allDay: event.isAllDay,
            timeZone: event.civilTimeZone
        ).data()
    }

    init(configuration: ReadConfiguration) throws {
        guard let contents = configuration.file.regularFileContents else {
            throw CocoaError(.fileReadCorruptFile)
        }
        self.contents = contents
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: contents)
    }
}

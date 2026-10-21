import EventKit
import Foundation
import MeetupCore
import SwiftUI

#if os(iOS)
import EventKitUI
import UIKit
#endif

@MainActor
struct NativeCalendarButton: View {
    private let event: MeetupEvent
    @State private var isReviewPresented = false
    @State private var isExportPresented = false
    @State private var exportDocument: CalendarDocument?
    @State private var reviewDidComplete = false
    @State private var outcome: CalendarOutcome?
    @State private var errorMessage: String?

    init(event: MeetupEvent) {
        self.event = event
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Button("calendar.add", systemImage: "calendar.badge.plus") {
                    outcome = nil
                    guard event.start.timeIntervalSinceReferenceDate.isFinite else {
                        errorMessage = String(localized: "calendar.error.invalid_date")
                        return
                    }
                    reviewDidComplete = false
                    isReviewPresented = true
                }
                .accessibilityLabel("calendar.add.accessibility")

                Button("calendar.export", systemImage: "square.and.arrow.up") {
                    outcome = nil
                    do {
                        exportDocument = try CalendarDocument(event: event)
                        isExportPresented = true
                    } catch {
                        errorMessage = String(localized: "calendar.error.invalid_date")
                    }
                }
                .accessibilityLabel("calendar.export.accessibility")
            }
            if let outcome {
                Text(outcome.message)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .sheet(isPresented: $isReviewPresented, onDismiss: {
            // Interactive dismissal is cancellation, never evidence of a save.
            if !reviewDidComplete { outcome = .cancelled }
        }) {
            #if os(iOS)
            CalendarEventEditor(event: event) { result in
                reviewDidComplete = true
                outcome = result
                isReviewPresented = false
            }
            #elseif os(macOS)
            MacCalendarConfirmation(event: event) { result in
                reviewDidComplete = true
                outcome = result
                isReviewPresented = false
            }
            #endif
        }
        .fileExporter(
            isPresented: $isExportPresented,
            document: exportDocument,
            contentTypes: [CalendarDocument.contentType],
            defaultFilename: "meetup.ics",
            onCompletion: { result in
                switch result {
                case .success: outcome = .exported
                case .failure(let error):
                    if (error as NSError).code == CocoaError.userCancelled.rawValue,
                       (error as NSError).domain == NSCocoaErrorDomain {
                        outcome = .cancelled
                    } else {
                        errorMessage = error.localizedDescription
                    }
                }
            },
            onCancellation: { outcome = .cancelled }
        )
        .alert("calendar.error.title", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } }
        )) {
            Button("common.ok", role: .cancel) { errorMessage = nil }
        } message: {
            Text(verbatim: errorMessage ?? "")
        }
    }
}

private enum CalendarOutcome {
    case saved, cancelled, deleted, exported, notSaved

    var message: LocalizedStringKey {
        switch self {
        case .saved: "calendar.result.saved"
        case .cancelled: "calendar.result.cancelled"
        case .deleted: "calendar.result.deleted"
        case .exported: "calendar.result.exported"
        case .notSaved: "calendar.result.not_saved"
        }
    }
}

@MainActor
private func makeCalendarEvent(_ source: MeetupEvent, store: EKEventStore, end: Date) -> EKEvent {
    let event = EKEvent(eventStore: store)
    event.title = source.title
    event.notes = source.description
    event.location = source.location
    event.startDate = source.start
    event.endDate = end
    event.url = source.url
    return event
}

private func proposedEnd(for event: MeetupEvent) -> Date {
    if let end = event.end, end > event.start { return end }
    // A proposal for review in native UI, not an asserted event duration.
    return event.start.addingTimeInterval(3_600)
}

#if os(iOS)
@MainActor
private struct CalendarEventEditor: UIViewControllerRepresentable {
    let event: MeetupEvent
    let onCompletion: (CalendarOutcome) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onCompletion: onCompletion) }

    func makeUIViewController(context: Context) -> EKEventEditViewController {
        // TN3152: EventKit UI handles access outside our process on iOS 17+.
        // Do not request authorization or query calendars here.
        let store = EKEventStore()
        let editor = EKEventEditViewController()
        editor.eventStore = store
        editor.event = makeCalendarEvent(event, store: store, end: proposedEnd(for: event))
        editor.editViewDelegate = context.coordinator
        return editor
    }

    func updateUIViewController(_ controller: EKEventEditViewController, context: Context) {}

    @MainActor
    final class Coordinator: NSObject, EKEventEditViewDelegate {
        private let onCompletion: (CalendarOutcome) -> Void
        private var didComplete = false

        init(onCompletion: @escaping (CalendarOutcome) -> Void) {
            self.onCompletion = onCompletion
        }

        nonisolated func eventEditViewController(
            _ controller: EKEventEditViewController,
            didCompleteWith action: EKEventEditViewAction
        ) {
            // Delegate isolation differs across EventKitUI SDK annotations. Do
            // not transfer the controller; only its Sendable enum result crosses.
            Task { @MainActor [weak self] in
                guard let self, !self.didComplete else { return }
                self.didComplete = true
                switch action {
                case .saved: self.onCompletion(.saved)
                case .canceled: self.onCompletion(.cancelled)
                case .deleted: self.onCompletion(.deleted)
                @unknown default: self.onCompletion(.notSaved)
                }
            }
        }
    }
}
#endif

#if os(macOS)
@MainActor
private struct MacCalendarConfirmation: View {
    let event: MeetupEvent
    let onCompletion: (CalendarOutcome) -> Void
    @State private var end: Date
    @State private var isSaving = false
    @State private var errorMessage: String?
    @State private var writer = MacCalendarWriter()

    init(event: MeetupEvent, onCompletion: @escaping (CalendarOutcome) -> Void) {
        self.event = event
        self.onCompletion = onCompletion
        _end = State(initialValue: proposedEnd(for: event))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("calendar.review.title").font(.headline)
            Form {
                LabeledContent("calendar.event.title") { Text(verbatim: event.title) }
                LabeledContent("calendar.event.start") { Text(event.start, format: .dateTime) }
                DatePicker("calendar.event.end", selection: $end, in: event.start...)
                    .accessibilityLabel("calendar.event.end.accessibility")
                if event.end.map({ $0 <= event.start }) ?? true {
                    Text("calendar.end.proposed.explanation").font(.caption)
                }
                LabeledContent("calendar.event.location") { Text(verbatim: event.location) }
            }
            Text("calendar.write_only.explanation")
                .font(.callout)
            Text("calendar.export.fallback_hint")
                .font(.caption)
                .foregroundStyle(.secondary)
            HStack {
                Button("common.cancel", role: .cancel) { onCompletion(.cancelled) }
                    .keyboardShortcut(.cancelAction)
                    .disabled(isSaving)
                Spacer()
                if isSaving { ProgressView().controlSize(.small) }
                Button("calendar.confirm_save") {
                    isSaving = true
                    errorMessage = nil
                    writer.save(event: event, end: end) { result in
                        isSaving = false
                        switch result {
                        case .success: onCompletion(.saved)
                        case .failure(let error): errorMessage = error.localizedDescription
                        }
                    }
                }
                .keyboardShortcut(.defaultAction)
                .disabled(isSaving || end <= event.start)
                .accessibilityLabel("calendar.confirm_save.accessibility")
            }
        }
        .padding()
        .frame(minWidth: 420)
        .interactiveDismissDisabled(isSaving)
        .alert("calendar.error.title", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } }
        )) {
            Button("common.ok", role: .cancel) { errorMessage = nil }
        } message: {
            Text(verbatim: errorMessage ?? "")
        }
    }
}

/// EventKit and pending non-Sendable model objects stay on the main actor.
@MainActor
private final class MacCalendarWriter {
    private let store = EKEventStore()
    private var pendingSave: PendingSave?

    private struct PendingSave {
        let event: MeetupEvent
        let end: Date
        let completion: @MainActor (Result<Void, any Error>) -> Void
    }

    func save(
        event: MeetupEvent,
        end: Date,
        completion: @escaping @MainActor (Result<Void, any Error>) -> Void
    ) {
        guard pendingSave == nil else { return }
        pendingSave = PendingSave(event: event, end: end, completion: completion)
        switch EKEventStore.authorizationStatus(for: .event) {
        case .writeOnly, .fullAccess:
            finishAuthorization(granted: true, error: nil)
        case .notDetermined:
            guard let usage = Bundle.main.object(
                forInfoDictionaryKey: "NSCalendarsWriteOnlyAccessUsageDescription"
            ) as? String, !usage.isEmpty else {
                finishAuthorization(granted: false, error: CalendarAdapterError.configuration)
                return
            }
            store.requestWriteOnlyAccessToEvents { [weak self] granted, error in
                Task { @MainActor [weak self] in
                    self?.finishAuthorization(granted: granted, error: error)
                }
            }
        case .denied, .restricted:
            finishAuthorization(granted: false, error: CalendarAdapterError.accessDenied)
        @unknown default:
            finishAuthorization(granted: false, error: CalendarAdapterError.unavailable)
        }
    }

    private func finishAuthorization(granted: Bool, error: (any Error)?) {
        guard let pending = pendingSave else { return }
        pendingSave = nil
        if let error {
            pending.completion(.failure(error))
            return
        }
        guard granted else {
            pending.completion(.failure(CalendarAdapterError.accessDenied))
            return
        }
        do {
            guard let calendar = store.defaultCalendarForNewEvents else {
                throw CalendarAdapterError.unavailable
            }
            let event = makeCalendarEvent(pending.event, store: store, end: pending.end)
            event.calendar = calendar
            try store.save(event, span: .thisEvent, commit: true)
            pending.completion(.success(()))
        } catch {
            pending.completion(.failure(error))
        }
    }
}

private enum CalendarAdapterError: LocalizedError {
    case accessDenied, configuration, unavailable

    var errorDescription: String? {
        switch self {
        case .accessDenied: String(localized: "calendar.error.access_denied")
        case .configuration: String(localized: "calendar.error.configuration")
        case .unavailable: String(localized: "calendar.error.unavailable")
        }
    }
}
#endif

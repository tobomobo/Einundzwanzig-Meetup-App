import SwiftUI
import UniformTypeIdentifiers
import MapKit
import PhotosUI
import CoreImage.CIFilterBuiltins
import MeetupNostr
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

struct BadgeWalletView: View {
    @Environment(AppStore.self) private var app
    @Environment(BadgeStore.self) private var wallet
    var collection: String? = nil
    @State private var query = ""
    @State private var grouping = "meetup"
    @State private var error: String?
    private var filtered: [CollectedBadge] {
        wallet.badges.filter { (collection == nil || $0.meetupName == collection) && (query.isEmpty || $0.meetupName.localizedStandardContains(query)) }
            .sorted { $0.dateValue > $1.dateValue }
    }
    private var groups: [String] { Array(Set(filtered.map(\.meetupName))).sorted() }
    var body: some View {
        List {
            Section {
                NavigationLink { BadgeImportView() } label: { Label("Collect a badge", systemImage: "qrcode.viewfinder") }
                NavigationLink { ReputationView() } label: { Label("Reputation and trust", systemImage: "shield.lefthalf.filled") }
                NavigationLink { OrganizerHubView() } label: { Label("Organizer sessions", systemImage: "qrcode") }
                if wallet.publicKey.isEmpty { Text("Unlock a signing identity to collect and bind badges.").foregroundStyle(.secondary) }
                if wallet.walletUnreadable { Label("The encrypted wallet could not be read. Its stored copy has been preserved.", systemImage: "lock.trianglebadge.exclamationmark").foregroundStyle(.red) }
                BadgeActionButton("Synchronize encrypted wallet", systemImage: "arrow.triangle.2.circlepath") { try await wallet.sync() }
                if !wallet.status.isEmpty { Text(LocalizedStringKey(wallet.status)).font(.caption).foregroundStyle(.secondary) }
            }
            if app.preferences.demo {
                Section("Demo previews") {
                    ForEach(app.badges) { preview in NavigationLink { BadgeDetailView(badge: preview) } label: {
                        Label(preview.name, systemImage: "testtube.2")
                    } }
                    Text("Demo previews do not contribute to reputation.").font(.caption)
                }
            }
            if filtered.isEmpty {
                ContentUnavailableView("No collected badges", systemImage: "seal", description: Text("Scan a signed organizer badge to start your wallet."))
            } else {
                if collection == nil {
                    Picker("Group badges", selection: $grouping) { Text("Meetup").tag("meetup"); Text("Date").tag("date") }.pickerStyle(.segmented)
                }
                if collection == nil && grouping == "meetup" {
                    ForEach(groups, id: \.self) { name in
                        NavigationLink { BadgeWalletView(collection: name) } label: {
                            HStack {
                                Image(systemName: "seal.fill").font(.largeTitle).foregroundStyle(Color.accentColor)
                                VStack(alignment: .leading) { Text(name).font(.headline); Text("\(filtered.filter { $0.meetupName == name }.count) badges").font(.caption) }
                            }.padding(.vertical, 6)
                        }
                    }
                } else {
                    ForEach(filtered) { badge in NavigationLink { CollectedBadgeDetailView(badge: badge) } label: { BadgeRow(badge: badge) } }
                }
            }
            Section {
                NavigationLink { BadgeMapView() } label: { Label("Badge map", systemImage: "map") }
                Text("Full badge proofs are encrypted on this device. Relay sync encrypts gzip wallet chunks to your own signing identity.").font(.caption).foregroundStyle(.secondary)
            }
        }
        .navigationTitle(collection ?? "Badges")
        .searchable(text: $query, prompt: "Search badges")
    }
}

struct BadgeRow: View {
    @Environment(BadgeStore.self) private var wallet
    let badge: CollectedBadge
    var body: some View {
        HStack(spacing: 16) {
            Image(systemName: badge.isEvent ? "ticket.fill" : "seal.fill").font(.largeTitle).foregroundStyle(Color.accentColor).frame(width: 50)
            VStack(alignment: .leading, spacing: 4) {
                Text(badge.meetupName).font(.headline)
                Text(badge.dateValue, format: .dateTime.day().month().year()).font(.caption)
                Text(LocalizedStringKey(wallet.trust.trustedBadgeIDs.contains(badge.id) ? "Source, collector and authority verified" : "Not counted toward verified reputation"))
                    .font(.caption).foregroundStyle(.secondary)
            }
        }.padding(.vertical, 6)
    }
}

struct CollectedBadgeDetailView: View {
    @Environment(BadgeStore.self) private var wallet
    @Environment(\.dismiss) private var dismiss
    let badge: CollectedBadge
    @State private var confirmDelete = false
    @State private var shareAttendance = false
    @State private var error: String?
    var body: some View {
        List {
            Section {
                VStack(spacing: 14) {
                    Image(systemName: badge.isEvent ? "ticket.fill" : "seal.fill").font(.system(size: 76)).foregroundStyle(Color.accentColor)
                    Text(badge.meetupName).font(.title.bold())
                    Label(LocalizedStringKey(wallet.trust.trustedBadgeIDs.contains(badge.id) ? "Verified for this identity" : "Evidence not fully verified"), systemImage: wallet.trust.trustedBadgeIDs.contains(badge.id) ? "checkmark.shield" : "exclamationmark.shield")
                }.frame(maxWidth: .infinity).padding(.vertical)
            }
            Section("Badge evidence") {
                LabeledContent("Collected") { Text(badge.dateValue, format: .dateTime.day().month().year().hour().minute()) }
                LabeledContent("Bitcoin block", value: badge.blockHeight.formatted())
                LabeledContent("Delivery", value: badge.delivery)
                LabeledContent("Location checked") { Text(LocalizedStringKey(badge.presenceVerified ? "Yes" : "No")) }
                LabeledContent("Retroactive claim") { Text(LocalizedStringKey(badge.isRetroactive ? "Yes" : "No")) }
                Text("Issuer: \(badge.signerNpub)").font(.caption.monospaced()).textSelection(.enabled)
                Text("Source: \(badge.sigId)").font(.caption.monospaced()).textSelection(.enabled)
                Text("Collector: \(badge.claimPubkey)").font(.caption.monospaced()).textSelection(.enabled)
                Text("Claim: \(badge.claimEventId)").font(.caption.monospaced()).textSelection(.enabled)
            }
            Section {
                ShareLink(item: badge.wireJSON) { Label("Share full badge proof", systemImage: "square.and.arrow.up") }
                Text("The full proof includes this meetup, collection time and any stored coordinates.").font(.caption).foregroundStyle(.secondary)
                Button { shareAttendance = true } label: { Label("Publish attendance to trust network", systemImage: "person.3") }
                Button("Delete local badge", role: .destructive) { confirmDelete = true }
                if let error { Text(LocalizedStringKey(error)).foregroundStyle(.red) }
            }
        }.navigationTitle("Badge details")
        .confirmationDialog("Publish this meetup attendance publicly?", isPresented: $shareAttendance, titleVisibility: .visible) {
            Button("Publish signed attendance") { Task { do { try await wallet.publishAttendance(badge) } catch { self.error = error.localizedDescription } } }
        } message: { Text("This opt-in event links your public identity to this meetup session.") }
        .confirmationDialog("Delete this badge from this device?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete badge", role: .destructive) { Task { do { try await wallet.deleteBadge(badge.id); dismiss() } catch { self.error = error.localizedDescription } } }
        } message: { Text("Copies on other devices can return during sync.") }
    }
}

/// Compatibility for existing demo routes. Real wallet navigation carries the
/// complete CollectedBadge and never converts a preview into trusted evidence.
struct BadgeDetailView: View {
    let badge: BadgePreview
    var body: some View {
        List {
            Label(badge.name, systemImage: "seal")
            Text(badge.importedAt, format: .dateTime.day().month().year())
            Text(LocalizedStringKey(badge.isDemo ? "Demo preview" : "Unverified preview"))
            Text("This preview has no source or collector proof and does not contribute to reputation.")
        }.navigationTitle("Badge preview")
    }
}

struct BadgeImportView: View {
    @Environment(AppStore.self) private var app
    @Environment(BadgeStore.self) private var wallet
    @Environment(\.dismiss) private var dismiss
    @State private var input = ""
    @State private var showCamera = false
    @State private var showFileImporter = false
    @State private var allowUnknown = false
    @State private var error: String?
    @State private var imported: CollectedBadge?
    @State private var photo: PhotosPickerItem?
    @State private var decodingPhoto = false
    @State private var imageDecoder = QRImageDecoder()
    @State private var location = NearbyLocationController()
    var body: some View {
        Form {
            Section {
                Button { photo = nil; showCamera = true } label: { Label("Scan camera", systemImage: "camera.viewfinder") }
                PhotosPicker(selection: $photo, matching: .images) { Label("Read QR from photo", systemImage: "photo") }
                Button { showFileImporter = true } label: { Label("Import JSON file", systemImage: "doc") }
                if decodingPhoto { ProgressView() }
                Button { Task { await location.request() } } label: { Label("Check current collection location", systemImage: "location") }
                if location.status == .requesting { ProgressView("Finding current location") }
                if location.status == .ready { Text("Location ready. The app will compare it with this meetup’s reference.").font(.caption) }
                if app.preferences.demo { Text("Badge collection is disabled in demo mode.") }
            }
            Section("Signed badge payload") {
                TextEditor(text: $input).frame(minHeight: 160).font(.system(.body, design: .monospaced)).autocorrectionDisabled()
                    .onChange(of: input) { imported = nil; error = nil }
                Toggle("Save even if issuer authority is unknown", isOn: $allowUnknown)
                Text("Unknown issuers remain outside verified reputation. Rolling QR freshness checks time proximity; its nonce and coordinates are unsigned.").font(.caption).foregroundStyle(.secondary)
                BadgeActionButton("Verify source and bind to my identity", systemImage: "signature") {
                    let point = location.status == .ready && abs(location.fix?.timestamp.timeIntervalSinceNow ?? .infinity) <= 120 ? location.fix?.point : nil
                    let coordinate = try point.map { try BadgeCoordinate(latitude: $0.latitude, longitude: $0.longitude) }
                    imported = try await wallet.importBadge(input, allowUnknownIssuer: allowUnknown, deviceLocation: coordinate)
                }.disabled(input.isEmpty || app.preferences.demo || decodingPhoto)
                if let imported { NavigationLink { CollectedBadgeDetailView(badge: imported) } label: { Label("Badge saved with collector claim", systemImage: "checkmark.seal") } }
                if let error { Text(LocalizedStringKey(error)).foregroundStyle(.red) }
                BadgeActionButton("Refresh verified issuer registry", systemImage: "arrow.clockwise") { try await wallet.refreshRegistry() }
            }
        }.navigationTitle("Collect badge")
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
        .sheet(isPresented: $showCamera) {
            NavigationStack { NativeQRScanner { text in input = text; showCamera = false }.navigationTitle("Scan badge")
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { showCamera = false } } } }
        }
        .onChange(of: photo) { _, selection in Task { await inspectPhoto(selection) } }
        .onDisappear { location.cancel() }
        .fileImporter(isPresented: $showFileImporter, allowedContentTypes: [.json, .plainText], allowsMultipleSelection: false) { result in
            do {
                guard let url = try result.get().first else { return }
                let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
                guard let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size <= 65_536 else { throw BadgeProtocolError.oversized }
                let data = try Data(contentsOf: url)
                guard data.count <= 65_536, let text = String(data: data, encoding: .utf8) else { throw BadgeProtocolError.malformed }; input = text
            } catch { self.error = error.localizedDescription }
        }
    }
    private func inspectPhoto(_ selection: PhotosPickerItem?) async {
        guard let selection else { return }; decodingPhoto = true; imported = nil; error = nil
        defer { if photo == selection { decodingPhoto = false } }
        do {
            guard let data = try await selection.loadTransferable(type: Data.self) else { throw QRImageError.invalidImage }
            let payload = try await imageDecoder.decode(data)
            guard photo == selection, !Task.isCancelled else { return }; input = payload
        } catch { if photo == selection { self.error = error.localizedDescription } }
    }
}

struct BadgeMapView: View {
    @Environment(BadgeStore.self) private var wallet
    private var located: [CollectedBadge] { wallet.badges.filter { ($0.lat != 0 || $0.lng != 0) && abs($0.lat) <= 90 && abs($0.lng) <= 180 } }
    var body: some View {
        Group {
            if located.isEmpty { ContentUnavailableView("No badge locations", systemImage: "map", description: Text("Unsigned QR coordinates are never used as location evidence.")) }
            else {
                Map {
                    ForEach(located) { badge in
                        Annotation(badge.meetupName, coordinate: CLLocationCoordinate2D(latitude: badge.lat, longitude: badge.lng)) {
                            NavigationLink { CollectedBadgeDetailView(badge: badge) } label: {
                                Image(systemName: badge.presenceVerified ? "seal.fill" : "seal").font(.title).padding(8).background(.regularMaterial, in: Circle())
                            }
                        }
                    }
                }
            }
        }.navigationTitle("Badge map")
    }
}

struct BadgeActionButton: View {
    @Environment(BadgeStore.self) private var wallet
    let title: String
    let systemImage: String
    let action: @MainActor () async throws -> Void
    @State private var error: String?
    @State private var running = false
    init(_ title: String, systemImage: String, action: @escaping @MainActor () async throws -> Void) {
        self.title = title; self.systemImage = systemImage; self.action = action
    }
    var body: some View {
        VStack(alignment: .leading) {
            Button { Task {
                running = true; defer { running = false }
                do { error = nil; try await action() } catch { self.error = error.localizedDescription }
            } } label: { Label(LocalizedStringKey(title), systemImage: systemImage) }
                .disabled(wallet.busy || wallet.publicKey.isEmpty || running)
            if let error { Text(LocalizedStringKey(error)).font(.caption).foregroundStyle(.red) }
        }
    }
}

struct BadgeQRView: View {
    let payload: String
    var body: some View {
        Group {
            if let image = qrImage {
                image.interpolation(.none).resizable().scaledToFit().padding(16).background(.white).accessibilityLabel("QR code")
            } else { Text("The payload is too large for one QR code. Share its text instead.") }
        }.frame(maxWidth: 360, maxHeight: 360)
    }
    private var qrImage: Image? {
        let filter = CIFilter.qrCodeGenerator(); filter.message = Data(payload.utf8); filter.correctionLevel = "M"
        guard let output = filter.outputImage, let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        #if os(iOS)
        return Image(uiImage: UIImage(cgImage: cg))
        #elseif os(macOS)
        return Image(nsImage: NSImage(cgImage: cg, size: NSSize(width: CGFloat(cg.width), height: CGFloat(cg.height))))
        #else
        return nil
        #endif
    }
}

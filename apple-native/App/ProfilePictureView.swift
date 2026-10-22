import SwiftUI
import MeetupCore
import PhotosUI
import ImageIO
import UniformTypeIdentifiers
#if os(iOS)
import UIKit
#else
import AppKit
#endif

struct ProfilePictureView: View {
    @Environment(AppStore.self) private var app
    @State private var pictures = ProfilePictureStore()
    @State private var selection: PhotosPickerItem?
    @State private var error: String?
    var allowsLocalPicture = false
    var size: CGFloat = 48
    private var publicKey: String {
        if !app.identity.publicKey.isEmpty { return app.identity.publicKey }
        return (try? PublicIdentity(app.profile.npub).hex) ?? ""
    }
    var body: some View {
        Group {
            if allowsLocalPicture {
                PhotosPicker(selection: $selection, matching: .images) { avatar }
                    .buttonStyle(.plain)
                    .disabled(pictures.pictureURL != nil && pictures.localPicture == nil)
                    .accessibilityLabel("Choose local profile picture")
                    .contextMenu {
                        if pictures.localPicture != nil {
                            Button("Remove local profile picture", role: .destructive) {
                                do { try pictures.removeLocalPicture() } catch { self.error = app.text("error.persistence") }
                            }
                        }
                    }
            } else { avatar.accessibilityLabel("Nostr profile picture") }
        }
        .task(id: publicKey + app.preferences.relays.joined()) {
            await pictures.load(publicKey: publicKey, relays: app.preferences.relays.compactMap(URL.init(string:)))
        }
        .onChange(of: selection) {
            guard let item = selection else { return }
            let token = pictures.selectionToken
            Task {
                do {
                    guard let data = try await item.loadTransferable(type: Data.self) else { return }
                    try pictures.setLocalPicture(try Self.thumbnail(data), expectedReset: token)
                } catch is CancellationError { }
                catch { self.error = app.text("The selected profile picture could not be loaded.") }
                if selection == item { selection = nil }
            }
        }
        .alert("error.title", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("action.ok") { error = nil }
        } message: { Text(error ?? "") }
    }
    private var avatar: some View {
        Group {
            if allowsLocalPicture, let bytes = pictures.localPicture, let image = Self.image(bytes) { image.resizable().scaledToFill() }
            else if let url = pictures.pictureURL {
                AsyncImage(url: url) { phase in
                    if let image = phase.image { image.resizable().scaledToFill() } else { fallback }
                }
            } else { fallback }
        }
        .frame(width: size, height: size).background(Color.accentColor.opacity(0.15), in: Circle()).clipShape(Circle())
    }
    private var fallback: some View {
        Text(app.profile.nickname.first.map { String($0).uppercased() } ?? "?")
            .font(.system(size: size * 0.4, weight: .bold)).foregroundStyle(Color.accentColor)
    }
    private static func image(_ data: Data) -> Image? {
        #if os(iOS)
        UIImage(data: data).map { Image(uiImage: $0) }
        #else
        NSImage(data: data).map { Image(nsImage: $0) }
        #endif
    }
    private static func thumbnail(_ data: Data) throws -> Data {
        guard !data.isEmpty, data.count <= 20 * 1024 * 1024,
              let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: 400
              ] as CFDictionary) else { throw LocalDataError.invalidImport }
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output, UTType.jpeg.identifier as CFString, 1, nil) else { throw LocalDataError.invalidImport }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.8] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { throw LocalDataError.invalidImport }
        return output as Data
    }
}

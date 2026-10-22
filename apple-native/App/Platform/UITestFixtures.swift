import Foundation
import SwiftUI
import MeetupCore
import MeetupNostr
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

enum UITestFixtureMode {
    static var enabled: Bool {
        #if DEBUG
        ProcessInfo.processInfo.arguments.contains("--ui-testing")
        #else
        false
        #endif
    }
    static var directory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent(enabled ? "EinundzwanzigAppleUITests" : "EinundzwanzigApple", isDirectory: true)
    }
    static func relay() -> NostrRelayClient {
        if enabled { return NostrRelayClient { _ in throw URLError(.notConnectedToInternet) } }
        return NostrRelayClient()
    }
    static var http: any ProductHTTPTransport { enabled ? OfflineFixtureHTTP() : ProductURLSessionTransport() }
    static func prepare() throws {
        #if DEBUG
        guard enabled, ProcessInfo.processInfo.arguments.contains("--reset-ui-test-data") else { return }
        try KeychainStore.resetUITestScope()
        if FileManager.default.fileExists(atPath: directory.path) { try FileManager.default.removeItem(at: directory) }
        if ProcessInfo.processInfo.arguments.contains("--seed-navigation-fixtures") {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var cache = DataCache()
            cache.meetups = [Meetup(id: "7001", name: "Fixture Meetup", city: "Fixture City", country: "AT", latitude: 48.2, longitude: 16.3)]
            try JSONEncoder().encode(cache).write(to: directory.appendingPathComponent("public-cache.json"), options: .atomic)
        }
        #endif
    }
}
private struct OfflineFixtureHTTP: ProductHTTPTransport {
    func send(_ request: URLRequest) async throws -> ProductHTTPResponse { throw URLError(.notConnectedToInternet) }
}

#if DEBUG
/// This screen exists only in debug builds explicitly launched by XCUITest.
/// A public synthetic organizer key supplies fixture evidence; collection,
/// signature verification, collector binding, encryption and Keychain storage
/// still execute the production implementations.
struct FixtureToolsView: View {
    @Environment(AppStore.self) private var app
    @Environment(BadgeStore.self) private var wallet
    @Environment(IdentityStore.self) private var identity
    @State private var status = ""
    var body: some View {
        List {
            NavigationLink("Signing identity") { IdentitySetupView() }.accessibilityIdentifier("fixture.identity")
            NavigationLink("Collected wallet") { BadgeWalletView() }.accessibilityIdentifier("fixture.wallet")
            Text("Wallet count: \(wallet.badges.count)").accessibilityIdentifier("fixture.walletCount")
            Text(identity.signer == nil ? "Locked" : "Unlocked").accessibilityIdentifier("fixture.identityState")
            Text(status).accessibilityIdentifier("fixture.status")
            Button("Collect signed fixture") {
                Task {
                    do {
                        let organizer = try LocalIdentitySigner(privateKey: Data(repeating: 0, count: 31) + Data([1]))
                        let now = Int(Date().timeIntervalSince1970)
                        let payload = try await BadgeSecurity.signCompact(meetupID: "fixture-city-at", blockHeight: 900000, expiresAt: now + 3600, signer: organizer, createdAt: now)
                        _ = try await wallet.importBadge(BadgeWire.canonical(payload))
                        status = "Fixture collected with signed claim."
                    } catch { status = error.localizedDescription }
                }
            }.disabled(identity.signer == nil || wallet.busy).accessibilityIdentifier("fixture.collect")
            Button("Check stored cryptographic claim") {
                Task {
                    do {
                        guard let badge = wallet.badges.first else { throw BadgeProtocolError.malformed }
                        guard await BadgeSecurity.verifyStored(badge, collector: identity.publicKey, now: Int(Date().timeIntervalSince1970)) else { throw BadgeProtocolError.invalidSignature }
                        let claim = try BadgeClaim.event(for: badge)
                        guard await NostrCrypto().verify(claim), claim.pubkey == identity.publicKey else { throw BadgeProtocolError.invalidClaim }
                        status = "Stored signature and collector claim verified."
                    } catch { status = error.localizedDescription }
                }
            }.disabled(wallet.badges.isEmpty).accessibilityIdentifier("fixture.verify")
            Button("Offline portal login") { Task { await app.workflow.login(); status = app.workflow.session == nil && app.workflow.error != nil ? "Offline request failed closed." : "Unexpected portal state." } }.accessibilityIdentifier("fixture.offlinePortal")
            WorkflowNotice()
        }.navigationTitle("Fixture tools")
    }
}
#endif

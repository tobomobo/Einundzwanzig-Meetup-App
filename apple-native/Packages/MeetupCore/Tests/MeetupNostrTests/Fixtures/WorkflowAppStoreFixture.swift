import Foundation
import Testing
import MeetupNostr
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

private actor WorkflowPendingLogin: ProductHTTPTransport {
    private var continuation: CheckedContinuation<ProductHTTPResponse, any Error>?
    private(set) var started = false
    func send(_ request: URLRequest) async throws -> ProductHTTPResponse {
        if request.url?.path == "/api/mobile/token", request.httpMethod == "POST" {
            started = true
            return try await withCheckedThrowingContinuation { continuation = $0 }
        }
        return ProductHTTPResponse(status: 200, data: Data("[]".utf8))
    }
    func finish() { continuation?.resume(returning: ProductHTTPResponse(status: 201, data: Data("{\"token\":\"synthetic-delayed-token\"}".utf8))); continuation = nil }
}
private struct WorkflowProfileHTTP: ProductHTTPTransport {
    let nostr: String
    func send(_ request: URLRequest) async throws -> ProductHTTPResponse {
        let data = request.url?.path == "/api/user"
            ? try JSONEncoder().encode(JSONValue.object(["nostr": .string(nostr)])) : Data("[]".utf8)
        return ProductHTTPResponse(status: 200, data: data)
    }
}

extension IdentityAppStoreContinuityTests {
    @Test func busySendRetainsUnsentTextAndLogoutFencesDelayedLogin() async throws {
        _ = try fresh()
        let http = WorkflowPendingLogin(), workflow = WorkflowStore(relay: UITestFixtureMode.relay(), transport: http)
        await workflow.configure(signer: try LocalIdentitySigner(privateKey: Data(repeating: 1, count: 32)))
        let login = Task { await workflow.login() }
        for _ in 0..<2_000 {
            if await http.started { break }
            try await Task.sleep(for: .milliseconds(2))
        }
        #expect(await http.started); #expect(workflow.busy)
        #expect(await workflow.send("Keep this unsent message", room: "fixture") == false)
        await workflow.logout()
        await http.finish(); await login.value
        #expect(workflow.session == nil); #expect(!workflow.busy)
        #expect(try KeychainStore.read(account: "portal-session-v1") == nil)
        #expect(workflow.managedMeetups.isEmpty && workflow.managedEvents.isEmpty)
    }

    @Test func identityConfigurationReleasesBusyAndFencesPriorLogin() async throws {
        _ = try fresh()
        let http = WorkflowPendingLogin(), workflow = WorkflowStore(relay: UITestFixtureMode.relay(), transport: http)
        await workflow.configure(signer: try LocalIdentitySigner(privateKey: Data(repeating: 1, count: 32)))
        let login = Task { await workflow.login() }
        for _ in 0..<2_000 {
            if await http.started { break }
            try await Task.sleep(for: .milliseconds(2))
        }
        #expect(await http.started)
        await workflow.configure(signer: nil)
        #expect(!workflow.busy)
        await http.finish(); await login.value
        #expect(workflow.signer == nil && workflow.session == nil && !workflow.busy)
        #expect(try KeychainStore.read(account: "portal-session-v1") == nil)
    }

    @Test func manualPortalTokenUsesFlutterNostrFieldAndPermitsUnlinkedAccount() async throws {
        _ = try fresh()
        let signer = try LocalIdentitySigner(privateKey: Data(repeating: 1, count: 32))
        let foreign = try await NIP19.encodePublicKey(LocalIdentitySigner(privateKey: Data(repeating: 2, count: 32)).publicKey())
        let mismatched = WorkflowStore(relay: UITestFixtureMode.relay(), transport: WorkflowProfileHTTP(nostr: foreign))
        await mismatched.configure(signer: signer); await mismatched.login(manualToken: "fixture-token")
        #expect(mismatched.session == nil); #expect(mismatched.error != nil)
        #expect(try KeychainStore.read(account: "portal-session-v1") == nil)
        let unlinked = WorkflowStore(relay: UITestFixtureMode.relay(), transport: WorkflowProfileHTTP(nostr: ""))
        await unlinked.configure(signer: signer); await unlinked.login(manualToken: "fixture-token")
        #expect(unlinked.session?.publicKey == (try await signer.publicKey()))
        #expect(try KeychainStore.read(account: "portal-session-v1") != nil)
        await unlinked.logout()
        let linked = WorkflowStore(relay: UITestFixtureMode.relay(), transport: WorkflowProfileHTTP(nostr: try await NIP19.encodePublicKey(signer.publicKey())))
        await linked.configure(signer: signer); await linked.login(manualToken: "fixture-token")
        #expect(linked.session?.publicKey == (try await signer.publicKey()))
    }
}

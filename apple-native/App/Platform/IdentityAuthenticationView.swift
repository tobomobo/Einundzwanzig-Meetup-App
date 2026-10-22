import SwiftUI
#if os(iOS)
import SafariServices
#elseif os(macOS)
import WebKit
#endif

/// Keep web authorization inside the active app. Opening an external browser
/// would background the app and intentionally lock/revoke its pending session.
struct IdentityAuthenticationView: View {
    let url: URL
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        #if os(iOS)
        IdentitySafariView(url: url, finished: { dismiss() })
        #elseif os(macOS)
        VStack(spacing: 0) {
            HStack {
                Text(url.host() ?? "Signer authorization").font(.headline)
                Spacer()
                Button("Done") { dismiss() }
            }.padding()
            Divider()
            IdentityWebView(url: url)
        }.frame(minWidth: 640, minHeight: 480)
        #endif
    }
}

#if os(iOS)
private struct IdentitySafariView: UIViewControllerRepresentable {
    let url: URL
    let finished: @MainActor () -> Void
    func makeCoordinator() -> Coordinator { Coordinator(finished: finished) }
    func makeUIViewController(context: Context) -> SFSafariViewController {
        let controller = SFSafariViewController(url: url)
        controller.delegate = context.coordinator
        controller.dismissButtonStyle = .done
        return controller
    }
    func updateUIViewController(_ controller: SFSafariViewController, context: Context) {}
    @MainActor final class Coordinator: NSObject, SFSafariViewControllerDelegate {
        let finished: @MainActor () -> Void
        init(finished: @escaping @MainActor () -> Void) { self.finished = finished }
        nonisolated func safariViewControllerDidFinish(_ controller: SFSafariViewController) {
            Task { @MainActor [weak self] in self?.finished() }
        }
    }
}
#elseif os(macOS)
private struct IdentityWebView: NSViewRepresentable {
    let url: URL
    func makeNSView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.load(URLRequest(url: url))
        return view
    }
    func updateNSView(_ view: WKWebView, context: Context) {}
}
#endif

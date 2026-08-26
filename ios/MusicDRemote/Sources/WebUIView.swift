import SwiftUI
import WebKit

/// Hosts the bundled front-end.
///
/// There is no HTTP server here, unlike Android. The page asks for `/api/...`
/// with relative URLs, so serving it under a custom scheme makes those resolve
/// to `musicd://app/api/...` and a `WKURLSchemeHandler` answers them inside the
/// process. No socket, no port, no loopback binding, and nothing for App
/// Transport Security to object to — the same front-end, with the transport
/// taken out from under it.
struct WebUIView: UIViewRepresentable {
    @ObservedObject var model: AppModel

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.setURLSchemeHandler(context.coordinator.handler, forURLScheme: AppScheme.name)
        config.mediaTypesRequiringUserActionForPlayback = .all

        let web = WKWebView(frame: .zero, configuration: config)
        web.isOpaque = false
        web.backgroundColor = .black
        web.scrollView.backgroundColor = .black
        web.scrollView.contentInsetAdjustmentBehavior = .never
        web.load(URLRequest(url: AppScheme.indexURL))
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator {
        let handler = AppSchemeHandler()
    }
}

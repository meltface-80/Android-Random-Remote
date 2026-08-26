import Foundation
import WebKit
import UniformTypeIdentifiers

enum AppScheme {
    static let name = "musicd"
    static let host = "app"
    static var indexURL: URL { URL(string: "\(name)://\(host)/index.html")! }
}

/// Serves the bundled front-end, and will serve `/api/...` once the Roon client
/// is behind it.
///
/// A path is resolved against the bundled `web` folder and nothing else. That
/// check is not decoration: the page builds URLs from album and artist names,
/// and a `..` reaching out of the bundle would be a file-read primitive handed
/// to anything that can get a string into the page.
final class AppSchemeHandler: NSObject, WKURLSchemeHandler {

    private let root: URL? = Bundle.main.url(forResource: "web", withExtension: nil)

    func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
        guard let url = task.request.url else {
            task.didFailWithError(URLError(.badURL))
            return
        }

        var path = url.path
        if path.isEmpty || path == "/" { path = "/index.html" }

        if path.hasPrefix("/api/") {
            // Answered by the Roon client once it is wired up. Until then this
            // is an honest 503 rather than a hang: the page shows its own
            // "not connected" state for exactly this.
            respond(task: task, url: url, status: 503, type: "application/json",
                    data: Data(#"{"error":"not connected"}"#.utf8))
            return
        }

        guard let root, let file = resolve(path, under: root) else {
            respond(task: task, url: url, status: 404, type: "text/plain",
                    data: Data("not found".utf8))
            return
        }

        do {
            let data = try Data(contentsOf: file)
            respond(task: task, url: url, status: 200, type: mimeType(for: file), data: data)
        } catch {
            task.didFailWithError(error)
        }
    }

    func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) {}

    /// Resolves [path] inside [root], or nil if it would escape it.
    private func resolve(_ path: String, under root: URL) -> URL? {
        let candidate = root.appendingPathComponent(path).standardizedFileURL
        let base = root.standardizedFileURL.path
        guard candidate.path == base || candidate.path.hasPrefix(base + "/") else { return nil }
        guard FileManager.default.fileExists(atPath: candidate.path) else { return nil }
        return candidate
    }

    private func mimeType(for file: URL) -> String {
        switch file.pathExtension.lowercased() {
        case "html": return "text/html; charset=utf-8"
        case "js":   return "text/javascript; charset=utf-8"
        case "css":  return "text/css; charset=utf-8"
        case "json": return "application/json"
        case "svg":  return "image/svg+xml"
        case "png":  return "image/png"
        case "jpg", "jpeg": return "image/jpeg"
        case "webp": return "image/webp"
        case "woff2": return "font/woff2"
        case "ico":  return "image/x-icon"
        default:
            return UTType(filenameExtension: file.pathExtension)?.preferredMIMEType
                ?? "application/octet-stream"
        }
    }

    private func respond(task: WKURLSchemeTask, url: URL, status: Int, type: String, data: Data) {
        let response = HTTPURLResponse(
            url: url,
            statusCode: status,
            httpVersion: "HTTP/1.1",
            headerFields: [
                "Content-Type": type,
                "Content-Length": String(data.count),
                "Cache-Control": "no-store"
            ]
        )!
        task.didReceive(response)
        task.didReceive(data)
        task.didFinish()
    }
}

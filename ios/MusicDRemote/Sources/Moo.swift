import Foundation

/// MOO is Roon's RPC framing. Each WebSocket binary frame carries one message
/// in an HTTP-like text format with LF line endings:
///
///     MOO/1 REQUEST com.roonlabs.transport:2/change_volume
///     Request-Id: 7
///     Content-Length: 63
///     Content-Type: application/json
///
///     {"output_id":"1701...","how":"relative_step","value":1}
///
/// Verbs are REQUEST (either direction), COMPLETE (final response) and
/// CONTINUE (a further response on a still-open request, which is how
/// subscriptions push updates). Request-Id correlates the two directions.
///
/// This is a second implementation of a format that already has one in
/// `core/src/main/kotlin/com/musicd/lite/roon/Moo.kt`, which is normally how a
/// protocol starts drifting into two subtly different dialects. What stops it
/// here is `tools/wire-fixtures`: committed, byte-exact frames that this, the
/// Kotlin client and RoonLabs' own moo.js are all held to. Change the bytes and
/// three test suites object at once.
enum Moo {

    static let verbRequest = "REQUEST"
    static let verbComplete = "COMPLETE"
    static let verbContinue = "CONTINUE"

    struct Message {
        let verb: String
        /// Only set for REQUEST, e.g. "com.roonlabs.ping:1".
        let service: String?
        /// Method name for REQUEST, result name ("Success", "Changed", …) otherwise.
        let name: String
        let requestId: String
        let headers: [String: String]
        let body: Data?

        var bodyText: String? {
            guard let body else { return nil }
            return String(data: body, encoding: .utf8)
        }
    }

    /// - Parameter line: the part after "MOO/1 <VERB> ", i.e. "service/method"
    ///   for a request or a result name for a response.
    static func encode(
        verb: String,
        line: String,
        requestId: Int,
        body: Data? = nil,
        contentType: String = "application/json"
    ) -> Data {
        var header = "MOO/1 \(verb) \(line)\n"
        header += "Request-Id: \(requestId)\n"
        if let body {
            header += "Content-Length: \(body.count)\n"
            header += "Content-Type: \(contentType)\n"
        }
        header += "\n"

        var out = Data(header.utf8)
        if let body { out.append(body) }
        return out
    }

    static func parse(_ buf: Data) -> Message? {
        if buf.isEmpty { return nil }

        var verb: String?
        var service: String?
        var name: String?
        var requestId: String?
        var contentLength: Int?
        var headers: [String: String] = [:]

        // Indices into `buf`. Data can be a slice with a non-zero startIndex,
        // so everything below is expressed relative to it rather than to 0 —
        // the kind of thing that works in every test and then fails on a frame
        // that arrived as part of a larger buffer.
        let base = buf.startIndex
        var start = base
        var i = base
        var inHeaders = false

        while i < buf.endIndex {
            if buf[i] != 0x0A {
                i = buf.index(after: i)
                continue
            }

            guard let line = String(data: buf[start..<i], encoding: .utf8) else { return nil }

            if !inHeaders {
                // MOO/1 <VERB> <rest>
                if !line.hasPrefix("MOO/") { return nil }
                guard let sp1 = line.firstIndex(of: " ") else { return nil }
                let afterFirst = line.index(after: sp1)
                guard let sp2 = line[afterFirst...].firstIndex(of: " ") else { return nil }
                verb = String(line[afterFirst..<sp2])
                let rest = String(line[line.index(after: sp2)...])
                if verb == verbRequest {
                    guard let slash = rest.firstIndex(of: "/") else { return nil }
                    service = String(rest[rest.startIndex..<slash])
                    name = String(rest[rest.index(after: slash)...])
                } else {
                    name = rest
                }
                inHeaders = true
            } else if line.isEmpty {
                // Blank line ends the headers; the body follows verbatim.
                guard let verb, let requestId else { return nil }
                var body: Data?
                if let len = contentLength, len > 0 {
                    let bodyStart = buf.index(after: i)
                    guard buf.distance(from: bodyStart, to: buf.endIndex) >= len else { return nil }
                    let bodyEnd = buf.index(bodyStart, offsetBy: len)
                    // Re-based, so callers get a Data indexed from zero rather
                    // than a slice that still remembers where it came from.
                    body = Data(buf[bodyStart..<bodyEnd])
                }
                return Message(
                    verb: verb,
                    service: service,
                    name: name ?? "",
                    requestId: requestId,
                    headers: headers,
                    body: body
                )
            } else {
                guard let colon = line.firstIndex(of: ":") else { return nil }
                let key = String(line[line.startIndex..<colon])
                let value = String(line[line.index(after: colon)...])
                    .drop(while: { $0 == " " || $0 == "\t" })
                switch key {
                case "Request-Id": requestId = String(value)
                case "Content-Length": contentLength = Int(value)
                default: headers[key] = String(value)
                }
            }

            i = buf.index(after: i)
            start = i
        }
        return nil
    }
}

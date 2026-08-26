import XCTest
@testable import MusicDRemote

/// MOO framing, held to the same bytes as every other implementation here.
///
/// `tools/wire-fixtures` is the contract. The Kotlin client asserts against it,
/// `tools/verify-wire.js` feeds it to RoonLabs' own moo.js, and this asserts
/// against it too — so "the Swift one encodes it slightly differently" is a
/// build failure rather than a zone that mysteriously ignores commands.
final class MooTests: XCTestCase {

    /// The fixtures, copied into this test bundle by the project spec.
    private func fixture(_ name: String) throws -> Data {
        let bundle = Bundle(for: type(of: self))
        let url = try XCTUnwrap(
            bundle.url(forResource: "wire-fixtures/\(name)", withExtension: nil)
                ?? bundle.resourceURL?
                    .appendingPathComponent("wire-fixtures")
                    .appendingPathComponent(name),
            "\(name) is not in the test bundle — check the wire-fixtures resource in ios/project.yml"
        )
        return try Data(contentsOf: url)
    }

    func testEncodesRequestWithBodyExactlyAsCommitted() throws {
        let body = Data(#"{"output_id":"1701","how":"relative_step","value":1}"#.utf8)
        let bytes = Moo.encode(
            verb: Moo.verbRequest,
            line: "com.roonlabs.transport:2/change_volume",
            requestId: 7,
            body: body
        )
        XCTAssertEqual(bytes, try fixture("request.bin"))
    }

    func testEncodesResponseWithoutBodyExactlyAsCommitted() throws {
        let bytes = Moo.encode(verb: Moo.verbComplete, line: "Success", requestId: 3)
        XCTAssertEqual(bytes, try fixture("complete.bin"))
    }

    func testParsesTheCommittedContinueFrame() throws {
        let msg = try XCTUnwrap(Moo.parse(try fixture("continue.bin")))
        XCTAssertEqual(msg.verb, Moo.verbContinue)
        XCTAssertEqual(msg.name, "Changed")
        XCTAssertNil(msg.service)
        XCTAssertEqual(msg.requestId, "12")
        XCTAssertEqual(
            msg.bodyText,
            #"{"zones_changed":[{"zone_id":"16","state":"playing"}]}"#
        )
    }

    func testParsesTheCommittedRequestFrame() throws {
        let msg = try XCTUnwrap(Moo.parse(try fixture("request.bin")))
        XCTAssertEqual(msg.verb, Moo.verbRequest)
        XCTAssertEqual(msg.service, "com.roonlabs.transport:2")
        XCTAssertEqual(msg.name, "change_volume")
        XCTAssertEqual(msg.requestId, "7")
    }

    func testRoundTripsRequest() throws {
        let body = Data(#"{"a":1}"#.utf8)
        let bytes = Moo.encode(
            verb: Moo.verbRequest, line: "com.roonlabs.ping:1/ping", requestId: 42, body: body
        )
        let msg = try XCTUnwrap(Moo.parse(bytes))
        XCTAssertEqual(msg.verb, Moo.verbRequest)
        XCTAssertEqual(msg.service, "com.roonlabs.ping:1")
        XCTAssertEqual(msg.name, "ping")
        XCTAssertEqual(msg.requestId, "42")
        XCTAssertEqual(msg.body, body)
    }

    func testBodyIsBinarySafe() throws {
        // Content-Length counts BYTES. A body carrying multi-byte UTF-8 is
        // where a length measured in characters silently truncates — and an
        // album called "Café Bleu" is not an exotic case.
        let body = Data(#"{"title":"Café Bleu — Reissue"}"#.utf8)
        let bytes = Moo.encode(
            verb: Moo.verbRequest, line: "com.roonlabs.browse:1/browse", requestId: 1, body: body
        )
        let msg = try XCTUnwrap(Moo.parse(bytes))
        XCTAssertEqual(msg.body, body)
        XCTAssertTrue(
            String(data: bytes, encoding: .utf8)!.contains("Content-Length: \(body.count)")
        )
    }

    func testParsesAFrameThatArrivedInsideALargerBuffer() throws {
        // Data can be a slice whose startIndex is not zero. Indexing such a
        // slice from 0 traps at runtime, and it is exactly what a socket
        // handing over part of a read buffer produces — invisible in a test
        // that only ever builds Data from scratch.
        var envelope = Data(repeating: 0x2A, count: 16)
        let frame = try fixture("request.bin")
        envelope.append(frame)
        let slice = envelope[envelope.index(envelope.startIndex, offsetBy: 16)...]

        let msg = try XCTUnwrap(Moo.parse(slice))
        XCTAssertEqual(msg.name, "change_volume")
        XCTAssertEqual(
            msg.bodyText,
            #"{"output_id":"1701","how":"relative_step","value":1}"#
        )
    }

    func testRejectsRubbish() {
        XCTAssertNil(Moo.parse(Data()))
        XCTAssertNil(Moo.parse(Data("not a moo frame\n\n".utf8)))
        // Headers that never end: no blank line, so no message.
        XCTAssertNil(Moo.parse(Data("MOO/1 COMPLETE Success\nRequest-Id: 3\n".utf8)))
    }
}

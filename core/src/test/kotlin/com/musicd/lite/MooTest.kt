package com.musicd.lite

import com.musicd.lite.roon.Moo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MOO framing.
 *
 * The byte-exact assertions here are the contract, and the frames they produce
 * are also written to build/moo-samples so `tools/verify-wire.js` can feed them
 * to node-roon-api's own moo.js — the framing is then checked against Roon's
 * reference implementation rather than against one reading of it.
 *
 * They are additionally checked against the committed bytes in
 * tools/wire-fixtures. That directory is the shared contract between three
 * implementations of this protocol: this one, RoonLabs' own moo.js, and the
 * Swift client in ios/. A second implementation of a wire format is normally a
 * slow drift into disagreement; holding all of them to the same committed
 * bytes is what makes it safe to have one.
 */
class MooTest {

    private val samples = File("build/moo-samples").apply { mkdirs() }

    /** Where the shared, committed fixtures live, from :core's project dir. */
    private val fixtures = File("../tools/wire-fixtures")

    /**
     * Fails when [bytes] is not exactly what is committed.
     *
     * Deliberately not "write it if it is missing": a fixture that regenerates
     * itself records whatever the code currently does, which is the opposite of
     * a contract. Changing one is a deliberate act, and the diff should show it.
     */
    private fun matchesFixture(name: String, bytes: ByteArray) {
        val file = File(fixtures, name)
        assertTrue(
            "tools/wire-fixtures/$name is missing. It is committed on purpose — " +
                "see the class comment; regenerate it deliberately, do not let a test do it.",
            file.isFile
        )
        assertEquals(
            "$name no longer matches the committed frame. Every implementation of " +
                "MOO in this repo is held to these bytes, so changing them changes " +
                "the contract for all of them.",
            file.readBytes().toString(Charsets.UTF_8),
            bytes.toString(Charsets.UTF_8)
        )
    }

    @Test
    fun encodesRequestWithBody() {
        val body = """{"output_id":"1701","how":"relative_step","value":1}"""
        val bytes = Moo.encode(
            Moo.VERB_REQUEST, "com.roonlabs.transport:2/change_volume", 7, body.toByteArray()
        )
        File(samples, "request.bin").writeBytes(bytes)
        matchesFixture("request.bin", bytes)

        assertEquals(
            "MOO/1 REQUEST com.roonlabs.transport:2/change_volume\n" +
                "Request-Id: 7\n" +
                "Content-Length: ${body.toByteArray().size}\n" +
                "Content-Type: application/json\n" +
                "\n" +
                body,
            bytes.toString(Charsets.UTF_8)
        )
    }

    @Test
    fun encodesResponseWithoutBody() {
        val bytes = Moo.encode(Moo.VERB_COMPLETE, "Success", 3)
        File(samples, "complete.bin").writeBytes(bytes)
        matchesFixture("complete.bin", bytes)
        assertEquals("MOO/1 COMPLETE Success\nRequest-Id: 3\n\n", bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun roundTripsRequest() {
        val body = """{"a":1}""".toByteArray()
        val bytes = Moo.encode(Moo.VERB_REQUEST, "com.roonlabs.ping:1/ping", 42, body)
        val msg = Moo.parse(bytes)!!
        assertEquals(Moo.VERB_REQUEST, msg.verb)
        assertEquals("com.roonlabs.ping:1", msg.service)
        assertEquals("ping", msg.name)
        assertEquals("42", msg.requestId)
        assertArrayEquals(body, msg.body)
    }

    @Test
    fun parsesContinueChanged() {
        val body = """{"zones_changed":[{"zone_id":"16","state":"playing"}]}"""
        val raw = "MOO/1 CONTINUE Changed\n" +
            "Request-Id: 12\n" +
            "Content-Length: ${body.toByteArray().size}\n" +
            "Content-Type: application/json\n" +
            "\n" + body
        File(samples, "continue.bin").writeBytes(raw.toByteArray())
        matchesFixture("continue.bin", raw.toByteArray())

        val msg = Moo.parse(raw.toByteArray())!!
        assertEquals(Moo.VERB_CONTINUE, msg.verb)
        assertEquals("Changed", msg.name)
        assertNull(msg.service)
        assertEquals(body, msg.bodyText)
    }

    @Test
    fun bodyIsBinarySafe() {
        // Content-Length counts BYTES. A body carrying multi-byte UTF-8 is where
        // a length measured in characters would silently truncate.
        val body = "{\"t\":\"Björk — Homogénic\"}".toByteArray(Charsets.UTF_8)
        val bytes = Moo.encode(Moo.VERB_REQUEST, "com.roonlabs.browse:1/browse", 1, body)
        val msg = Moo.parse(bytes)!!
        assertArrayEquals(body, msg.body)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(Moo.parse(ByteArray(0)))
        assertNull(Moo.parse("not a moo frame\n\n".toByteArray()))
        // Headers that never terminate must not be treated as a whole message.
        assertNull(Moo.parse("MOO/1 COMPLETE Success\nRequest-Id: 1\n".toByteArray()))
    }

    @Test
    fun bodyShorterThanContentLengthIsRejected() {
        // A truncated frame off the wire must not parse as a short body — that
        // would hand a caller half a zone list as if it were the whole thing.
        val raw = "MOO/1 COMPLETE Success\nRequest-Id: 1\nContent-Length: 99\n\n{\"a\":1}"
        assertNull(Moo.parse(raw.toByteArray()))
    }
}

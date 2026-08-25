package com.musicd.lite

import com.musicd.lite.roon.Sood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** SOOD, Roon's UDP discovery. Query bytes and reply parsing. */
class SoodTest {

    private val samples = File("build/moo-samples").apply { mkdirs() }

    @Test
    fun queryHasTheRoonServiceId() {
        val q = Sood.buildQuery()
        File(samples, "sood-query.bin").writeBytes(q)

        assertEquals("SOOD", String(q, 0, 4, Charsets.UTF_8))
        assertEquals(2, q[4].toInt())
        assertEquals('Q', q[5].toInt().toChar())

        val text = String(q, Charsets.ISO_8859_1)
        assertTrue("query names _tid", text.contains("_tid"))
        assertTrue("query names query_service_id", text.contains("query_service_id"))
        assertTrue(
            "query carries Roon's service id",
            text.contains("00720724-5143-4a9b-abac-0e50cba674bb")
        )
    }

    /** Build a reply the way a Core does, so the parser is tested on real shapes. */
    private fun reply(props: List<Pair<String, String?>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("SOOD".toByteArray())
        out.write(2)
        out.write('R'.code)
        for ((name, value) in props) {
            val n = name.toByteArray()
            out.write(n.size)
            out.write(n)
            if (value == null) {
                // 0xFFFF is SOOD's null.
                out.write(0xFF); out.write(0xFF)
            } else {
                val v = value.toByteArray()
                out.write((v.size shr 8) and 0xFF)
                out.write(v.size and 0xFF)
                out.write(v)
            }
        }
        return out.toByteArray()
    }

    @Test
    fun parsesACoreReply() {
        val bytes = reply(
            listOf(
                "unique_id" to "1f7f0a2c-0000-0000-0000-000000000001",
                "http_port" to "9330",
                "name" to "Study Core",
                "tid" to null
            )
        )
        val props = Sood.parseReply(bytes, bytes.size)!!
        assertEquals("1f7f0a2c-0000-0000-0000-000000000001", props["unique_id"])
        assertEquals("9330", props["http_port"])
        assertEquals("Study Core", props["name"])
        assertNull(props["tid"])
    }

    @Test
    fun emptyValueIsNotNull() {
        // An empty string and a null are different answers, and conflating them
        // is how a Core with a blank name becomes a Core with no name field.
        val bytes = reply(listOf("name" to "", "unique_id" to "x"))
        val props = Sood.parseReply(bytes, bytes.size)!!
        assertEquals("", props["name"])
        assertTrue(props.containsKey("name"))
    }

    @Test
    fun rejectsNonReplies() {
        assertNull(Sood.parseReply(Sood.buildQuery(), Sood.buildQuery().size))  // a query, not a reply
        assertNull(Sood.parseReply("nope".toByteArray(), 4))
        val short = reply(listOf("unique_id" to "x"))
        // A truncated packet must not yield half a property map.
        assertNull(Sood.parseReply(short, short.size - 3))
    }
}

package com.musicd.lite.roon

import java.io.ByteArrayOutputStream

/**
 * MOO is Roon's RPC framing. Each WebSocket binary frame carries one message
 * in an HTTP-like text format with LF line endings:
 *
 *     MOO/1 REQUEST com.roonlabs.transport:2/change_volume
 *     Request-Id: 7
 *     Content-Length: 63
 *     Content-Type: application/json
 *
 *     {"output_id":"1701...","how":"relative_step","value":1}
 *
 * Verbs are REQUEST (either direction), COMPLETE (final response) and
 * CONTINUE (a further response on a still-open request, which is how
 * subscriptions push updates). Request-Id correlates the two directions.
 */
object Moo {

    const val VERB_REQUEST = "REQUEST"
    const val VERB_COMPLETE = "COMPLETE"
    const val VERB_CONTINUE = "CONTINUE"

    class Message(
        val verb: String,
        /** Only set for REQUEST, e.g. "com.roonlabs.ping:1". */
        val service: String?,
        /** Method name for REQUEST, result name ("Success", "Changed", ...) otherwise. */
        val name: String,
        val requestId: String,
        val headers: Map<String, String>,
        val body: ByteArray?
    ) {
        val bodyText: String? get() = body?.toString(Charsets.UTF_8)
    }

    /**
     * @param line the part after "MOO/1 <VERB> ", i.e. "service/method" for a
     *             request or a result name for a response.
     */
    fun encode(
        verb: String,
        line: String,
        requestId: Int,
        body: ByteArray? = null,
        contentType: String? = "application/json"
    ): ByteArray {
        val header = StringBuilder()
        header.append("MOO/1 ").append(verb).append(' ').append(line).append('\n')
        header.append("Request-Id: ").append(requestId).append('\n')
        if (body != null) {
            header.append("Content-Length: ").append(body.size).append('\n')
            header.append("Content-Type: ").append(contentType ?: "application/json").append('\n')
        }
        header.append('\n')

        val out = ByteArrayOutputStream()
        out.write(header.toString().toByteArray(Charsets.UTF_8))
        if (body != null) out.write(body)
        return out.toByteArray()
    }

    fun parse(buf: ByteArray): Message? {
        if (buf.isEmpty()) return null

        var verb: String? = null
        var service: String? = null
        var name: String? = null
        var requestId: String? = null
        var contentLength: Int? = null
        val headers = HashMap<String, String>()

        var start = 0
        var i = 0
        var inHeaders = false

        while (i < buf.size) {
            if (buf[i].toInt() != 0x0A) { i++; continue }

            val line = String(buf, start, i - start, Charsets.UTF_8)

            if (!inHeaders) {
                // MOO/1 <VERB> <rest>
                if (!line.startsWith("MOO/")) return null
                val sp1 = line.indexOf(' ')
                if (sp1 < 0) return null
                val sp2 = line.indexOf(' ', sp1 + 1)
                if (sp2 < 0) return null
                verb = line.substring(sp1 + 1, sp2)
                val rest = line.substring(sp2 + 1)
                if (verb == VERB_REQUEST) {
                    val slash = rest.indexOf('/')
                    if (slash < 0) return null
                    service = rest.substring(0, slash)
                    name = rest.substring(slash + 1)
                } else {
                    name = rest
                }
                inHeaders = true
            } else if (line.isEmpty()) {
                // Blank line ends the headers; the body follows verbatim.
                if (requestId == null) return null
                val body = contentLength?.let { len ->
                    if (len <= 0) null
                    else {
                        val bodyStart = i + 1
                        if (bodyStart + len > buf.size) return null
                        buf.copyOfRange(bodyStart, bodyStart + len)
                    }
                }
                return Message(verb!!, service, name ?: "", requestId, headers, body)
            } else {
                val colon = line.indexOf(':')
                if (colon < 0) return null
                val key = line.substring(0, colon)
                val value = line.substring(colon + 1).trimStart()
                when (key) {
                    "Request-Id" -> requestId = value
                    "Content-Length" -> contentLength = value.toIntOrNull()
                    else -> headers[key] = value
                }
            }

            i++
            start = i
        }
        return null
    }
}

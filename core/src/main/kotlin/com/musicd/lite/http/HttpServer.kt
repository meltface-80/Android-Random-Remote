package com.musicd.lite.http

import com.musicd.lite.Log
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small HTTP/1.1 server bound to the loopback interface.
 *
 * This is what lets MusicD-Remote's front-end run unchanged inside the app: the
 * page is the same HTML, CSS and JavaScript the Docker build serves, and it
 * still talks to `/api/...` over HTTP. The alternative — intercepting requests
 * in the WebView — cannot work, because `shouldInterceptRequest` is not given
 * the body of a POST, and the UI POSTs for every play, queue and control.
 *
 * Loopback-only by construction: nothing here is reachable from the network,
 * so there is no authentication and none is needed.
 */
class HttpServer(
    private val handler: Handler,
    /** 0 asks the OS for a free port, which avoids clashing with anything. */
    requestedPort: Int = 0
) {

    fun interface Handler {
        fun handle(request: Request): Response
    }

    private val server = ServerSocket(
        requestedPort, 64, InetAddress.getByName("127.0.0.1")
    )

    private val running = AtomicBoolean(false)
    private val threadSeq = AtomicInteger(0)

    private val workers = ThreadPoolExecutor(
        2, 24, 60L, TimeUnit.SECONDS, SynchronousQueue(),
        { r -> Thread(r, "http-${threadSeq.incrementAndGet()}").apply { isDaemon = true } },
        ThreadPoolExecutor.CallerRunsPolicy()
    )

    private val acceptor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "http-accept").apply { isDaemon = true }
    }

    /** The port actually bound. Ask after [start]. */
    val port: Int get() = server.localPort

    val rootUrl: String get() = "http://127.0.0.1:$port"

    fun start() {
        if (!running.compareAndSet(false, true)) return
        acceptor.execute {
            Log.i(TAG, "listening on $rootUrl")
            while (running.get()) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    if (running.get()) Log.w(TAG, "accept failed: ${e.message}")
                    continue
                }
                workers.execute { serve(client) }
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { server.close() }
        workers.shutdownNow()
        acceptor.shutdownNow()
    }

    // ----------------------------------------------------------- connection

    private fun serve(client: Socket) {
        try {
            client.soTimeout = READ_TIMEOUT_MS
            client.tcpNoDelay = true
            val input = client.getInputStream().buffered()
            val output = BufferedOutputStream(client.getOutputStream())

            // Keep-alive: the front-end polls several endpoints every 1.5s, and
            // a fresh connection per poll is pure overhead on a phone.
            while (running.get()) {
                val request = try {
                    readRequest(input)
                } catch (e: SocketTimeoutException) {
                    return
                } catch (e: HttpError) {
                    write(output, Response.text(e.status, e.message ?: "Bad request"), false)
                    output.flush()
                    return
                } ?: return

                val response = try {
                    handler.handle(request)
                } catch (e: Exception) {
                    Log.w(TAG, "handler threw for ${request.path}: ${e.message}", e)
                    Response.json(500, """{"error":${quote(e.message ?: "Internal error")}}""")
                }

                write(output, response, request.method == "HEAD")
                output.flush()
                if (!request.keepAlive) return
            }
        } catch (e: IOException) {
            Log.d(TAG, "connection ended: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    private class HttpError(val status: Int, message: String) : IOException(message)

    /** Returns null at a clean end of stream. */
    private fun readRequest(input: InputStream): Request? {
        val line = readLine(input) ?: return null
        if (line.isEmpty()) return null

        val parts = line.split(' ')
        if (parts.size < 3) throw HttpError(400, "Malformed request line")
        val method = parts[0].uppercase()
        val target = parts[1]

        val headers = HashMap<String, String>()
        var headerBytes = line.length
        while (true) {
            val h = readLine(input) ?: throw HttpError(400, "Truncated headers")
            if (h.isEmpty()) break
            headerBytes += h.length
            if (headerBytes > MAX_HEADER_BYTES) throw HttpError(431, "Headers too large")
            val colon = h.indexOf(':')
            if (colon <= 0) continue
            headers[h.substring(0, colon).lowercase()] = h.substring(colon + 1).trim()
        }

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_BODY_BYTES) throw HttpError(413, "Body too large")
        val body = if (length > 0) ByteArray(length).also { readFully(input, it) } else ByteArray(0)

        // HTTP/1.1 keeps the connection open unless the client says otherwise.
        val connection = headers["connection"]?.lowercase()
        val keepAlive = connection != "close"

        val q = target.indexOf('?')
        val path = if (q < 0) target else target.substring(0, q)
        val query = if (q < 0) emptyMap() else parseQuery(target.substring(q + 1))

        return Request(method, decodePath(path), query, headers, body, keepAlive)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder(128)
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(c.toChar())
            if (sb.length > MAX_HEADER_BYTES) throw HttpError(431, "Header line too long")
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) throw HttpError(400, "Truncated body")
            read += n
        }
    }

    private fun write(out: BufferedOutputStream, response: Response, headOnly: Boolean) {
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(response.status).append(' ')
            .append(statusText(response.status)).append("\r\n")
        sb.append("Content-Type: ").append(response.contentType).append("\r\n")
        sb.append("Content-Length: ").append(response.body.size).append("\r\n")
        for ((k, v) in response.headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) out.write(response.body)
    }

    companion object Codec {
        private const val TAG = "Http"
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024

        fun parseQuery(raw: String): Map<String, String> {
            if (raw.isEmpty()) return emptyMap()
            val out = HashMap<String, String>()
            for (pair in raw.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val key = if (eq < 0) pair else pair.substring(0, eq)
                val value = if (eq < 0) "" else pair.substring(eq + 1)
                out[formDecode(key)] = formDecode(value)
            }
            return out
        }

        /** Query values are form-encoded, so "+" is a space. */
        private fun formDecode(s: String): String =
            try {
                URLDecoder.decode(s, "UTF-8")
            } catch (e: Exception) {
                s
            }

        /**
         * Path segments are percent-encoded but "+" is a literal plus, not a
         * space — decoding it as one corrupts every image key that contains it.
         */
        fun decodePath(s: String): String =
            try {
                URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
            } catch (e: Exception) {
                s
            }

        fun quote(s: String): String {
            val sb = StringBuilder(s.length + 2)
            sb.append('"')
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
            sb.append('"')
            return sb.toString()
        }

        fun statusText(code: Int): String = when (code) {
            200 -> "OK"
            204 -> "No Content"
            206 -> "Partial Content"
            304 -> "Not Modified"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            504 -> "Gateway Timeout"
            else -> "Status $code"
        }
    }
}

class Request(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
    val keepAlive: Boolean
) {
    val bodyText: String get() = body.toString(Charsets.UTF_8)

    /** A parameter from the query string, falling back to a JSON body field. */
    fun param(name: String): String? = query[name]?.takeIf { it.isNotEmpty() }
}

class Response(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
    val headers: Map<String, String> = emptyMap()
) {
    companion object {
        fun json(status: Int, json: String) =
            Response(status, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))

        fun text(status: Int, text: String) =
            Response(status, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))

        fun bytes(
            status: Int,
            contentType: String,
            body: ByteArray,
            headers: Map<String, String> = emptyMap()
        ) = Response(status, contentType, body, headers)

        fun notFound() = json(404, """{"error":"Not found"}""")
    }
}

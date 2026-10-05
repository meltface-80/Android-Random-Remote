package com.musicd.lite

import com.musicd.lite.api.StaticAssets
import com.musicd.lite.roon.Zone
import com.musicd.lite.store.MemoryStore
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals

/**
 * The shipping server over a real socket, against a scripted Core, for the
 * suites written after RemoteApiTest. Same library and zone as that suite:
 * five albums, two artists with two each, one playing zone.
 *
 * Outbound requests (MusicBrainz, Wikipedia, Deezer, …) go to [outbound], which
 * refuses them unless a test installs an answer — so a suite never reaches the
 * internet, and a test can see exactly which calls a route made.
 */
class ApiFixture(
    /** Answers outbound requests a test wants answered; null refuses them. */
    var outbound: ((okhttp3.Request) -> okhttp3.Response?)? = null
) {
    val core = FakeCore()
    val store = MemoryStore()
    val outboundCalls: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
    lateinit var app: MusicdLite

    private val assets = object : StaticAssets {
        override fun read(path: String): Pair<ByteArray, String>? =
            if (path == "/index.html") "<!doctype html><title>t</title>".toByteArray() to "text/html" else null
    }

    fun start(): ApiFixture {
        listOf(
            "Blue Lines" to "Massive Attack",
            "Dummy" to "Portishead",
            "Mezzanine" to "Massive Attack",
            "Third" to "Portishead",
            "Kid A" to "Radiohead"
        ).forEach { (t, a) -> core.addAlbum(t, a, "img-${t.replace(' ', '-')}") }
        core.genres["Trip-Hop"] = mutableListOf(0, 1, 2)
        core.zonesList = listOf(zone("z1", "Study", playing = "Mezzanine" to "Massive Attack"))
        core.outputsList = core.zonesList.flatMap { it.outputs }

        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            outboundCalls.add(req.url.toString())
            outbound?.invoke(req) ?: throw IOException("the test refuses outbound requests")
        }).build()

        app = MusicdLite(
            store = store, assets = assets, artDir = null, version = "test",
            httpPort = 0, importSettleMs = 0, httpClient = client
        ) { _, _, _ -> core }
        app.start()
        app.index.build(core.tree)
        return this
    }

    fun stop() = app.stop()

    fun zone(id: String, name: String, playing: Pair<String, String>? = null, track: String = "Teardrop",
             remaining: Int? = null): Zone = Zone.parse(
        JSONObject(
            """
            {"zone_id":"$id","display_name":"$name","state":"${if (playing != null) "playing" else "stopped"}",
             "is_play_allowed":true,"is_pause_allowed":true,
             "is_next_allowed":true,"is_previous_allowed":true,"is_seek_allowed":true,
             ${if (remaining != null) "\"queue_items_remaining\":$remaining," else ""}
             "settings":{"shuffle":false,"loop":"disabled","auto_radio":false},
             "outputs":[{"output_id":"o-$id","zone_id":"$id","display_name":"Amp",
               "volume":{"type":"db","min":-80,"max":0,"value":-25,"step":0.5,"is_muted":false}}]
             ${if (playing != null) """,
             "now_playing":{"three_line":{"line1":"$track","line2":"${playing.second}","line3":"${playing.first}"},
               "length":330,"seek_position":40,"image_key":"img-${playing.first.replace(' ', '-')}"}""" else ""}
            }
            """.trimIndent()
        )
    )

    fun get(path: String): Pair<Int, String> = request("GET", path, null)
    fun post(path: String, body: String): Pair<Int, String> = request("POST", path, body)

    fun request(method: String, path: String, body: String?): Pair<Int, String> {
        val conn = URL(app.rootUrl + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5000
        conn.readTimeout = 40000
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        }
        val code = conn.responseCode
        val stream = if (code < 400) conn.inputStream else conn.errorStream
        val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() } ?: ""
        conn.disconnect()
        return code to text
    }

    fun json(path: String): JSONObject {
        val (code, text) = get(path)
        assertEquals("GET $path -> $text", 200, code)
        return JSONObject(text)
    }

    fun postJson(path: String, body: String): JSONObject {
        val (code, text) = post(path, body)
        assertEquals("POST $path -> $text", 200, code)
        return JSONObject(text)
    }
}

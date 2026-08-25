package com.musicd.lite.api

import com.musicd.lite.http.Request
import com.musicd.lite.http.Response
import com.musicd.lite.library.AlbumRecord
import org.json.JSONArray
import org.json.JSONObject

/** JSON shapes shared by the endpoint handlers. */
object Json {

    fun ok(body: JSONObject = JSONObject()): Response =
        Response.json(200, body.put("ok", true).toString())

    fun obj(body: JSONObject): Response = Response.json(200, body.toString())

    fun error(status: Int, message: String): Response =
        Response.json(status, JSONObject().put("error", message).toString())

    fun arrayOf(items: List<JSONObject>): JSONArray =
        JSONArray().also { a -> items.forEach(a::put) }

    fun strings(items: Collection<String>): JSONArray =
        JSONArray().also { a -> items.forEach(a::put) }

    /**
     * One album tile, in the shape the front-end has always been handed.
     *
     * `source` is the Qobuz / TIDAL / local badge. It is always null here:
     * without a streaming service connected the original suppresses the badge
     * too, because elimination would then mark every album "local" and the
     * badge would stop being a fact and become decoration on every tile.
     * `quality` and `hires` are likewise absent — they come from reading file
     * tags off a mounted music directory, which a phone does not have.
     */
    fun album(al: AlbumRecord, extra: JSONObject? = null): JSONObject {
        val o = JSONObject()
            .put("offset", al.offset)
            .put("title", al.title)
            .put("subtitle", al.subtitle)
            .put("image_key", al.imageKey ?: JSONObject.NULL)
            .put("source", JSONObject.NULL)
        extra?.keys()?.forEach { o.put(it, extra.get(it)) }
        return o
    }

    fun albums(list: List<AlbumRecord>): JSONArray =
        JSONArray().also { a -> list.forEach { a.put(album(it)) } }

    /** A POST body as JSON, or an empty object when there isn't one. */
    fun body(request: Request): JSONObject {
        val text = request.bodyText
        if (text.isBlank()) return JSONObject()
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            JSONObject()
        }
    }
}

/** A query parameter, falling back to the JSON body — the UI uses both. */
fun Request.str(name: String): String? =
    query[name]?.takeIf { it.isNotEmpty() }
        ?: Json.body(this).optString(name).takeIf { it.isNotEmpty() }

fun Request.int(name: String): Int? = str(name)?.toIntOrNull()

fun Request.bool(name: String): Boolean? {
    val raw = query[name] ?: return Json.body(this).let {
        if (it.has(name)) it.optBoolean(name) else null
    }
    return raw == "1" || raw.equals("true", true)
}

package com.musicd.lite

import org.json.JSONArray
import org.json.JSONObject

/**
 * A JSON string field, reading an explicit `null` as absent.
 *
 * `optString` must not be used on anything that came off a network, because
 * the two org.json implementations disagree about JSON null:
 *
 *   {"subtitle": null}
 *     desktop org.json  -> optString("subtitle") == ""
 *     Android's org.json -> optString("subtitle") == "null"   (the LITERAL text)
 *
 * Android's version routes through `JSON.toString`, which is `String.valueOf`
 * for any non-null reference — and `JSONObject.NULL.toString()` is "null".
 *
 * This is not a theoretical difference. Roon's browse API documents `hint` as
 * nullable ("`null` — Unknown, display item generically") and `subtitle` as
 * optional, and it sends them as JSON null rather than omitting them. Every
 * album's Play menu is found by `hint == "action_list" && subtitle.isEmpty()`,
 * so on Android a null subtitle read as "null", the menu was never found, and
 * every album in the app showed "Roon offered no playback options for this
 * album." A null image_key became the string "null" and was requested as art.
 *
 * The unit tests could not catch any of it: they run on the JVM, against the
 * desktop implementation, where the bug does not exist. `JsonSafeTest` scans
 * the source instead, and fails if `optString` comes back.
 */
fun JSONObject.str(key: String, fallback: String = ""): String =
    if (isNull(key)) fallback else optString(key, fallback)

/** As [str], for a field that is absent, empty or JSON null. */
fun JSONObject.strOrNull(key: String): String? = str(key).takeIf { it.isNotEmpty() }

fun JSONArray.str(index: Int, fallback: String = ""): String =
    if (isNull(index)) fallback else optString(index, fallback)

/** Every non-empty string in an array, skipping nulls. */
fun JSONArray.strings(): List<String> =
    (0 until length()).mapNotNull { str(it).takeIf { s -> s.isNotEmpty() } }

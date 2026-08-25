package com.musicd.lite

import com.musicd.lite.roon.BrowseItem
import com.musicd.lite.roon.Zone
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JSON null, which the two org.json implementations disagree about.
 *
 * These tests run on the JVM, against the desktop implementation, where the
 * bug this file exists for CANNOT happen. So the important test here is not an
 * assertion about behaviour — it is [optStringIsNeverUsedOnParsedJson], which
 * reads the source. That is the only guard available for a fault that only
 * appears on a device.
 */
class JsonSafeTest {

    /**
     * Android's org.json returns the literal text "null" here; the desktop one
     * returns "". This reproduces Android's implementation exactly (AOSP
     * JSONObject.optString -> JSON.toString -> String.valueOf) so the divergence
     * is written down somewhere that runs.
     */
    private fun androidOptString(o: JSONObject, key: String): String {
        val v = o.opt(key)
        return when {
            v is String -> v
            v != null -> v.toString()     // JSONObject.NULL.toString() == "null"
            else -> ""
        }
    }

    @Test
    fun androidAndDesktopDisagreeAboutJsonNull() {
        val o = JSONObject("""{"subtitle":null}""")
        assertEquals("", o.optString("subtitle"))          // this JVM
        assertEquals("null", androidOptString(o, "subtitle"))  // a phone
        // The accessor the app uses agrees with neither implementation's quirk:
        // it reports what the document actually says.
        assertEquals("", o.str("subtitle"))
    }

    @Test
    fun theSafeAccessorTreatsNullAsAbsent() {
        val o = JSONObject("""{"a":null,"b":"","c":"x"}""")
        assertEquals("", o.str("a"))
        assertEquals("", o.str("b"))
        assertEquals("x", o.str("c"))
        assertEquals("", o.str("missing"))
        assertEquals("fallback", o.str("a", "fallback"))
        assertEquals("fallback", o.str("missing", "fallback"))
        assertNull(o.strOrNull("a"))
        assertNull(o.strOrNull("b"))
        assertEquals("x", o.strOrNull("c"))
    }

    /**
     * The exact shape that broke every album in the app.
     *
     * Roon documents `hint` as nullable and `subtitle` as optional, and sends
     * them as JSON null. The Play menu is identified by
     * `hint == "action_list" && subtitle.isEmpty()`, so a subtitle read as
     * "null" meant no album anywhere had playback controls.
     */
    @Test
    fun anAlbumsPlayMenuIsFoundWhenRoonSendsNulls() {
        val level = listOf(
            JSONObject("""{"title":"Play Album","subtitle":null,"item_key":"1","hint":"action_list"}"""),
            JSONObject("""{"title":"1. Opening","subtitle":"An Artist","item_key":"2","hint":"action_list"}""")
        ).map(BrowseItem::parse)

        val playMenu = level.firstOrNull {
            it.hint == "action_list" && it.subtitle.isEmpty() && it.title.startsWith("play", true)
        }
        assertEquals("Play Album", playMenu?.title)
        assertEquals("", level[0].subtitle)
    }

    @Test
    fun aNullImageKeyIsAbsentRatherThanTheTextNull() {
        // Requesting art for the key "null" is a guaranteed miss on every tile.
        val item = BrowseItem.parse(
            JSONObject("""{"title":"An Album","subtitle":"An Artist","image_key":null,"hint":null}""")
        )
        assertNull(item.imageKey)
        assertNull(item.hint)
        assertNull(item.itemKey)
    }

    @Test
    fun aZoneWithNullFieldsParsesCleanly() {
        val zone = Zone.parse(
            JSONObject(
                """
                {"zone_id":"z1","display_name":"Study","state":"playing","settings":{"loop":null},
                 "now_playing":{"three_line":{"line1":"A Track","line2":null,"line3":null},
                                "image_key":null}}
                """.trimIndent()
            )
        )
        assertEquals("", zone.nowPlaying!!.line2)
        assertEquals("", zone.nowPlaying!!.line3)
        assertNull(zone.nowPlaying!!.imageKey)
        assertEquals("disabled", zone.settings.loop)
    }

    /**
     * The real guard.
     *
     * A JVM test cannot catch the Android behaviour, so this reads the shipping
     * source instead and fails if `optString` returns to any file that parses
     * JSON. Every one of them must go through [str], which asks `isNull` first
     * and therefore behaves the same on both implementations.
     */
    @Test
    fun optStringIsNeverUsedOnParsedJson() {
        val root = File("src/main/kotlin")
        assertTrue("cannot find the sources at ${root.absolutePath}", root.isDirectory)

        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "JsonSafe.kt" }
            .flatMap { file ->
                file.readLines().asSequence().mapIndexedNotNull { i, line ->
                    if (line.contains(".optString(")) "${file.name}:${i + 1}: ${line.trim()}" else null
                }
            }
            .toList()

        assertTrue(
            "optString cannot be used on JSON that came off a network — Android returns the\n" +
                "literal string \"null\" for a JSON null, the JVM returns \"\", and these tests run\n" +
                "on the JVM. Use `str(...)` from JsonSafe.kt instead.\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}

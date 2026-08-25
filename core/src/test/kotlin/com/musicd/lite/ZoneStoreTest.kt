package com.musicd.lite

import com.musicd.lite.roon.OutputStore
import com.musicd.lite.roon.ZoneStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/** The subscribe_zones stream: the initial set, then deltas. */
class ZoneStoreTest {

    private fun zone(id: String, name: String, state: String = "playing", track: String = "Song") =
        """
        {
          "zone_id": "$id",
          "display_name": "$name",
          "state": "$state",
          "is_play_allowed": true,
          "is_pause_allowed": true,
          "is_next_allowed": true,
          "is_previous_allowed": false,
          "is_seek_allowed": true,
          "settings": { "shuffle": true, "loop": "loop_one", "auto_radio": false },
          "outputs": [
            {
              "output_id": "out-$id",
              "zone_id": "$id",
              "display_name": "$name Output",
              "can_group_with_output_ids": ["out-a", "out-b"],
              "source_controls": [
                { "control_key": "1", "display_name": "Amp", "status": "selected", "supports_standby": true },
                { "display_name": "Keyless", "status": "selected" }
              ],
              "volume": { "type": "db", "min": -80, "max": 0, "value": -30, "step": 0.5, "is_muted": false }
            }
          ],
          "now_playing": {
            "three_line": { "line1": "$track", "line2": "An Artist", "line3": "An Album" },
            "length": 240,
            "seek_position": 12,
            "image_key": "img-$id"
          }
        }
        """.trimIndent()

    @Test
    fun readsTheInitialSet() {
        val store = ZoneStore()
        store.applySubscribed(JSONObject("""{"zones":[${zone("2", "Study")},${zone("1", "Kitchen")}]}"""))

        val all = store.all()
        assertEquals(2, all.size)
        // Sorted by display name, not by arrival order.
        assertEquals("Kitchen", all[0].displayName)

        val study = store.byId("2")!!
        assertTrue(study.isPlaying)
        assertTrue(study.settings.shuffle)
        assertEquals("loop_one", study.settings.loop)
        assertEquals(-30.0, study.primaryVolume!!.value, 0.0001)
        assertEquals("An Album", study.nowPlaying!!.line3)
        assertEquals(listOf("out-a", "out-b"), study.outputs[0].canGroupWith)
        // The keyless source control is dropped: it cannot be addressed, so a
        // power button for it would silently do nothing.
        assertEquals(1, study.outputs[0].sourceControls.size)
        assertEquals("Amp", study.outputs[0].sourceControls[0].displayName)
    }

    @Test
    fun appliesAddedRemovedAndChanged() {
        val store = ZoneStore()
        store.applySubscribed(JSONObject("""{"zones":[${zone("1", "Kitchen")}]}"""))

        store.applyChanged(JSONObject("""{"zones_added":[${zone("2", "Study")}]}"""))
        assertEquals(2, store.all().size)

        store.applyChanged(
            JSONObject("""{"zones_changed":[${zone("1", "Kitchen", "paused", "Another Song")}]}""")
        )
        assertEquals("paused", store.byId("1")!!.state)
        assertEquals("Another Song", store.byId("1")!!.nowPlaying!!.line1)

        store.applyChanged(JSONObject("""{"zones_removed":["2"]}"""))
        assertNull(store.byId("2"))
        assertEquals(1, store.all().size)
    }

    @Test
    fun seekDeltaTouchesOnlyThePosition() {
        val store = ZoneStore()
        store.applySubscribed(JSONObject("""{"zones":[${zone("1", "Kitchen")}]}"""))

        store.applyChanged(
            JSONObject("""{"zones_seek_changed":[{"zone_id":"1","seek_position":97}]}""")
        )
        val z = store.byId("1")!!
        assertEquals(97, z.nowPlaying!!.seekPosition)
        // Everything else must survive: the seek delta arrives many times a
        // minute and carries nothing but a position.
        assertEquals("Song", z.nowPlaying!!.line1)
        assertEquals("An Album", z.nowPlaying!!.line3)
        assertEquals(240, z.nowPlaying!!.lengthSeconds)
        assertEquals("playing", z.state)
    }

    @Test
    fun seekDeltaForAnUnknownZoneIsIgnored() {
        val store = ZoneStore()
        store.applySubscribed(JSONObject("""{"zones":[${zone("1", "Kitchen")}]}"""))
        store.applyChanged(JSONObject("""{"zones_seek_changed":[{"zone_id":"99","seek_position":5}]}"""))
        assertEquals(1, store.all().size)
        assertEquals(12, store.byId("1")!!.nowPlaying!!.seekPosition)
    }

    @Test
    fun zoneWithNoSettingsBlockReadsAsOff() {
        // A zone that has never been played gets no settings block at all.
        val store = ZoneStore()
        store.applySubscribed(
            JSONObject("""{"zones":[{"zone_id":"9","display_name":"New","state":"stopped"}]}""")
        )
        val z = store.byId("9")!!
        assertFalse(z.settings.shuffle)
        assertEquals("disabled", z.settings.loop)
        assertNull(z.nowPlaying)
        assertNull(z.primaryVolume)
    }

    @Test
    fun unknownLoopModeReadsAsOff() {
        // A value we cannot render is worse than off.
        val store = ZoneStore()
        store.applySubscribed(
            JSONObject("""{"zones":[{"zone_id":"9","display_name":"N","settings":{"loop":"next"}}]}""")
        )
        assertEquals("disabled", store.byId("9")!!.settings.loop)
    }

    @Test
    fun outputStoreTracksItsOwnFeed() {
        val outputs = OutputStore()
        assertTrue(outputs.isEmpty())
        outputs.applySubscribed(
            JSONObject("""{"outputs":[{"output_id":"a","display_name":"Amp"},{"output_id":"b","display_name":"Deck"}]}""")
        )
        assertEquals(2, outputs.all().size)
        outputs.applyChanged(JSONObject("""{"outputs_removed":["a"]}"""))
        assertEquals(1, outputs.all().size)
        outputs.applyChanged(
            JSONObject("""{"outputs_changed":[{"output_id":"b","display_name":"Deck 2"}]}""")
        )
        assertEquals("Deck 2", outputs.all()[0].displayName)
        assertNotNull(outputs.all()[0])
    }
}

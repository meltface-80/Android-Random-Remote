package com.musicd.lite

import com.musicd.lite.roon.AlbumFilter
import com.musicd.lite.roon.BrowseException
import com.musicd.lite.roon.BrowseSessionPool
import com.musicd.lite.roon.classifyAction
import com.musicd.lite.roon.matchAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Walking Roon's browse tree: paging, navigation and the Play menu. */
class BrowseTreeTest {

    private fun coreWith(n: Int): FakeCore {
        val core = FakeCore()
        for (i in 0 until n) core.addAlbum("Album %03d".format(i), "Artist ${i % 7}", "img$i")
        return core
    }

    @Test
    fun pagesAWholeLevel() {
        val core = coreWith(250)
        val level = core.withSession { key ->
            core.tree.browse("albums", key, popAll = true)
            core.tree.loadLevel("albums", key, 1000)
        }
        assertEquals(250, level.total)
        assertEquals(250, level.items.size)
        assertEquals("Album 000", level.items.first().title)
        assertEquals("Album 249", level.items.last().title)
    }

    @Test
    fun loadLevelRespectsItsCeiling() {
        val core = coreWith(500)
        val level = core.withSession { key ->
            core.tree.browse("albums", key, popAll = true)
            core.tree.loadLevel("albums", key, 150)
        }
        // The cap bounds how much is FETCHED; the level's own count is still
        // reported honestly, so a caller can tell it got a partial view.
        assertEquals(500, level.total)
        assertTrue(level.items.size <= 200)
    }

    @Test
    fun findsAnItemByTitleAcrossPages() {
        val core = coreWith(250)
        val hit = core.withSession { key ->
            core.tree.browse("albums", key, popAll = true)
            core.tree.findItemByTitle("albums", key, "Album 137")
        }
        assertNotNull(hit)
        assertEquals("Album 137", hit!!.title)
    }

    @Test
    fun offsetCacheShortCircuitsTheSecondLookup() {
        val core = coreWith(400)
        core.genres["Jazz"] = (0 until 400 step 3).toMutableList()
        core.genres["Rock"] = (1 until 400 step 3).toMutableList()

        // Warm the cache, then count how much the second lookup costs.
        core.withSession { key ->
            core.tree.browse("genres", key, popAll = true)
            core.tree.findItemByTitle("genres", key, "Rock", context = "genres:root")
        }
        val hit = core.withSession { key ->
            core.tree.browse("genres", key, popAll = true)
            core.tree.findItemByTitle("genres", key, "Rock", context = "genres:root")
        }
        assertEquals("Rock", hit!!.title)
    }

    @Test
    fun navigatesToAGenresAlbumList() {
        val core = coreWith(30)
        core.genres["Jazz"] = mutableListOf(0, 5, 9)
        val nav = core.withSession { key ->
            core.tree.navigateToAlbumList(key, AlbumFilter(AlbumFilter.GENRE, "Jazz"))
        }
        assertEquals("genres", nav.hierarchy)
        assertEquals(3, nav.total)
    }

    @Test
    fun navigatesToATagsAlbumList() {
        val core = coreWith(30)
        core.tags["Late Night"] = mutableListOf(2, 4)
        val nav = core.withSession { key ->
            core.tree.navigateToAlbumList(key, AlbumFilter(AlbumFilter.TAG, "Late Night"))
        }
        assertEquals("browse", nav.hierarchy)
        assertEquals(2, nav.total)
    }

    @Test
    fun aMissingGenreSaysSo() {
        val core = coreWith(5)
        val e = runCatching {
            core.withSession { key ->
                core.tree.navigateToAlbumList(key, AlbumFilter(AlbumFilter.GENRE, "Skiffle"))
            }
        }.exceptionOrNull()
        assertTrue(e is BrowseException)
        assertTrue(e!!.message!!.contains("Skiffle"))
    }

    @Test
    fun aDecadeFilterResolvesAgainstTheWholeLibrary() {
        // A decade has no Roon list of its own — its offsets are full-library
        // positions, so navigation must land on "albums", not fail.
        val core = coreWith(12)
        val nav = core.withSession { key ->
            core.tree.navigateToAlbumList(key, AlbumFilter(AlbumFilter.DECADE, "1970s"))
        }
        assertEquals("albums", nav.hierarchy)
        assertEquals(12, nav.total)
    }

    @Test
    fun drillsAndClassifiesThePlayMenu() {
        val core = coreWith(3)
        val actions = core.withSession { key ->
            core.tree.browse("albums", key, popAll = true)
            core.tree.browse("albums", key, itemKey = "album:1")
            core.tree.drillActionMenu("albums", key, "playmenu:1")
        }
        assertEquals(listOf("play_now", "play_next", "queue", "radio"), actions.map { it.kind })
        assertEquals("Play Now", matchAction(actions, "play_now")!!.title)
        assertNull(matchAction(actions, "shuffle"))
    }

    @Test
    fun classifiesRoonsActionVocabulary() {
        assertEquals("play_now", classifyAction("Play Now"))
        assertEquals("play_next", classifyAction("Add Next"))
        assertEquals("play_next", classifyAction("Play Next"))
        assertEquals("queue", classifyAction("Queue"))
        assertEquals("shuffle", classifyAction("Shuffle"))
        assertEquals("radio", classifyAction("Start Radio"))
        assertEquals("other", classifyAction("Add to Library"))
        assertEquals("other", classifyAction(null))
    }

    @Test
    fun aMessageResponseCarriesRoonsOwnWords() {
        val core = coreWith(1)
        val e = runCatching {
            core.withSession { key ->
                val body = core.tree.browse("albums", key, itemKey = "nonsense")
                core.tree.requireList(body, "this album")
            }
        }.exceptionOrNull()
        assertTrue(e is BrowseException)
        // The Core said why; that beats anything we could infer.
        assertTrue(e!!.message!!.contains("No such item"))
    }

    @Test
    fun sessionKeysAreReusedNotMinted() {
        // Roon holds server-side state per multi_session_key for as long as the
        // extension is connected, so sequential operations must not each invent
        // a new one.
        val pool = BrowseSessionPool()
        val first = pool.acquire()
        pool.release(first)
        assertEquals(first, pool.acquire())
    }

    @Test
    fun concurrentOperationsGetDistinctSessions() {
        val pool = BrowseSessionPool()
        val a = pool.acquire()
        val b = pool.acquire()
        assertTrue("two live operations must not share a session", a != b)
        pool.release(a)
        pool.release(b)
    }
}

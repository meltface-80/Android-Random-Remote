package com.musicd.lite

import com.musicd.lite.meta.ImageCache
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Album art caching.
 *
 * The point of the two tiers is to keep a wall of sixty tiles off the Roon
 * Core: the memory tier serves the screen being looked at, and the disk tier
 * survives a restart. Every test here is about how many times the Core is
 * actually asked.
 */
class ImageCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun art(size: Int, fill: Byte = 1) =
        ImageCache.Art("image/jpeg", ByteArray(size) { fill })

    /** Counts what reached the Core, and what it was asked for. */
    private class CountingFetcher(private val answer: (String) -> ImageCache.Art?) :
        ImageCache.Fetcher {
        val calls = AtomicInteger(0)
        val urls = ArrayList<String>()

        override fun fetch(url: String): ImageCache.Art? {
            calls.incrementAndGet()
            synchronized(urls) { urls += url }
            return answer(url)
        }
    }

    @Test
    fun aSecondRequestIsServedFromMemory() {
        val fetcher = CountingFetcher { art(100) }
        val cache = ImageCache(fetcher, diskDir = null)

        val first = cache.get("http://core/img/a", "a|512")
        val second = cache.get("http://core/img/a", "a|512")

        assertEquals(100, first!!.bytes.size)
        assertArrayEquals(first.bytes, second!!.bytes)
        assertEquals("the Core must be asked exactly once", 1, fetcher.calls.get())
    }

    @Test
    fun differentRenderingsOfOneImageAreDifferentEntries() {
        // A thumbnail request must never be served the full-size art, so the
        // size is part of the cache key even though the image key is the same.
        val fetcher = CountingFetcher { art(100) }
        val cache = ImageCache(fetcher, diskDir = null)

        cache.get("http://core/img/a?w=128", "a|128")
        cache.get("http://core/img/a?w=1024", "a|1024")

        assertEquals(2, fetcher.calls.get())
        assertEquals(setOf("a|128", "a|1024"), cache.memoryKeys())
    }

    @Test
    fun aFetchThatFailsIsNotCached() {
        // Caching a miss would turn one bad moment into a permanently blank
        // tile for as long as the app stays open.
        val fetcher = CountingFetcher { null }
        val cache = ImageCache(fetcher, diskDir = null)

        assertNull(cache.get("http://core/img/a", "a|512"))
        assertNull(cache.get("http://core/img/a", "a|512"))
        assertEquals(2, fetcher.calls.get())
        assertTrue(cache.memoryKeys().isEmpty())
    }

    // ------------------------------------------------------------- eviction

    @Test
    fun theMemoryTierStaysWithinItsBudget() {
        val fetcher = CountingFetcher { art(100) }
        val cache = ImageCache(fetcher, diskDir = null, memoryBudgetBytes = 250)

        repeat(10) { cache.get("http://core/img/$it", "k$it") }

        assertTrue(
            "footprint ${cache.memoryFootprint()} exceeded the 250-byte budget",
            cache.memoryFootprint() <= 250
        )
        assertEquals(2, cache.memoryKeys().size)
    }

    @Test
    fun evictionKeepsTheAccountingHonestOverManyRounds() {
        // The bug this guards against is a footprint that drifts away from what
        // the map actually holds: once the counter reads low, the budget stops
        // being enforced and the tier grows without limit.
        val fetcher = CountingFetcher { art(100) }
        val cache = ImageCache(fetcher, diskDir = null, memoryBudgetBytes = 250)

        repeat(200) { cache.get("http://core/img/$it", "k$it") }

        val held = cache.memoryKeys().size.toLong() * 100
        assertEquals("the counter must equal the bytes actually held", held, cache.memoryFootprint())
        assertTrue(cache.memoryFootprint() <= 250)
    }

    @Test
    fun reCachingTheSameKeyDoesNotDoubleCountIt() {
        val fetcher = CountingFetcher { art(100) }
        val cache = ImageCache(fetcher, diskDir = null, memoryBudgetBytes = 10_000)

        cache.get("http://core/img/a", "a|512")
        cache.clearMemory()
        cache.get("http://core/img/a", "a|512")
        cache.get("http://core/img/a", "a|512")

        assertEquals(1, cache.memoryKeys().size)
        assertEquals(100L, cache.memoryFootprint())
    }

    @Test
    fun anImageLargerThanTheWholeBudgetIsStillReturned() {
        // It cannot be held, but the caller still needs the picture.
        val fetcher = CountingFetcher { art(500) }
        val cache = ImageCache(fetcher, diskDir = null, memoryBudgetBytes = 250)

        val hit = cache.get("http://core/img/big", "big")
        assertEquals(500, hit!!.bytes.size)
        assertEquals(0L, cache.memoryFootprint())
        assertTrue(cache.memoryKeys().isEmpty())
    }

    // ----------------------------------------------------------- disk tier

    @Test
    fun theDiskTierSurvivesLosingTheMemoryTier() {
        val dir = temp.newFolder("art")
        val fetcher = CountingFetcher { art(100, fill = 7) }
        val cache = ImageCache(fetcher, diskDir = dir)

        val first = cache.get("http://core/img/a", "a|512")!!
        cache.clearMemory()          // as a process restart would
        val second = cache.get("http://core/img/a", "a|512")!!

        assertArrayEquals(first.bytes, second.bytes)
        assertEquals("the second read came off disk, not the Core", 1, fetcher.calls.get())
    }

    @Test
    fun cacheFilesCannotEscapeTheDirectory() {
        // Roon's image keys are opaque and have contained characters that are
        // not safe in a path, so they are hashed rather than sanitised.
        val dir = temp.newFolder("art")
        val cache = ImageCache(CountingFetcher { art(10) }, diskDir = dir)

        cache.get("http://core/img/x", "../../escape|512")
        cache.get("http://core/img/y", "a/b/c|512")

        val written = dir.listFiles().orEmpty()
        assertEquals(2, written.size)
        for (f in written) {
            assertEquals(dir, f.parentFile)
            assertTrue("unexpected name ${f.name}", Regex("^[0-9a-f]{64}\\.jpg$").matches(f.name))
        }
    }

    @Test
    fun anEmptyFileOnDiskIsIgnoredRatherThanServed() {
        // A write interrupted by the process dying must not become a permanent
        // zero-byte "image".
        val dir = temp.newFolder("art")
        val fetcher = CountingFetcher { art(100) }
        val cache = ImageCache(fetcher, diskDir = dir)

        cache.get("http://core/img/a", "a|512")
        cache.clearMemory()
        // Truncate every cached file, as a half-finished write would.
        dir.listFiles().orEmpty().forEach { it.writeBytes(ByteArray(0)) }

        val hit = cache.get("http://core/img/a", "a|512")
        assertEquals(100, hit!!.bytes.size)
        assertEquals("a truncated file must send us back to the Core", 2, fetcher.calls.get())
    }

    @Test
    fun noDiskDirectoryIsFineAndNothingIsWritten() {
        val fetcher = CountingFetcher { art(100) }
        val cache = ImageCache(fetcher, diskDir = null)
        assertEquals(100, cache.get("http://core/img/a", "a|512")!!.bytes.size)
        cache.clearMemory()
        assertEquals(100, cache.get("http://core/img/a", "a|512")!!.bytes.size)
        assertEquals("without a disk tier every miss goes to the Core", 2, fetcher.calls.get())
    }

    @Test
    fun theUrlIsPassedThroughUntouched() {
        // The caller builds the sized URL; the cache must not reinterpret it.
        val fetcher = CountingFetcher { art(10) }
        val cache = ImageCache(fetcher, diskDir = null)
        val url = "http://core:9330/api/image/abc?scale=fit&width=720&height=720&format=image/jpeg"
        cache.get(url, "abc|720")
        assertEquals(listOf(url), fetcher.urls)
    }

    @Test
    fun anUnwritableDiskDirectoryDoesNotBreakFetching() {
        // Android may take the cache directory away at any moment; losing the
        // disk tier must cost a refetch and nothing more.
        val blocked = File(temp.newFile("not-a-directory"), "art")
        val fetcher = CountingFetcher { art(100) }
        val cache = ImageCache(fetcher, diskDir = blocked)

        assertEquals(100, cache.get("http://core/img/a", "a|512")!!.bytes.size)
        assertEquals(100, cache.get("http://core/img/a", "a|512")!!.bytes.size)
        assertEquals("the memory tier still absorbed the second read", 1, fetcher.calls.get())
    }
}

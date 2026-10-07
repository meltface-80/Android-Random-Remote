package com.musicd.lite

import com.musicd.lite.library.AlbumIndex
import com.musicd.lite.library.AlbumRecord
import com.musicd.lite.meta.Deezer
import com.musicd.lite.meta.ShareLinks
import com.musicd.lite.meta.TtlCache
import com.musicd.lite.store.Store
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt
import org.json.JSONObject

/**
 * "If you like this" — three acts worth hearing next, under the share card —
 * WEIGHTED BY WHAT YOU PLAY (Rouen v1.8.82, from Mandarin v0.7.6 and v0.7.9).
 *
 * It was Deezer's first three related acts, so a pop record could suggest a
 * children's choir and the same share always suggested the same three. Now:
 *
 *  - the POOL is Deezer's related acts for the playing one (twenty, not
 *    three), cached a day;
 *  - each is scored by Deezer's rank AND by how near it sits to what you play:
 *    the TASTE GRAPH, built once a day from the related lists of your
 *    most-played acts (the plays table, ranked by distinct days played — the
 *    same seeds Discover uses). An act near nothing you play scores next to
 *    nothing, and one near your listening comes first;
 *  - two of the three are acts you have not heard of (not in the library,
 *    never played), the third an act you know with a record you don't own —
 *    never one you play heavily;
 *  - each names the act's best-known record (the album most of its top tracks
 *    come from) with a line saying why it is there;
 *  - the draw is weighted random, and what was shown is remembered for a
 *    month, so the same record shared twice gives a different three.
 *
 * Until the day's first taste build finishes (or with no plays yet), nothing
 * is near and Deezer's order stands, as before.
 *
 * ON A PHONE: the taste build is up to eighty Deezer calls the first time, so
 * it runs on its own thread, started by a share — there is no timer — and the
 * share that started it is answered from yesterday's graph, or none. The
 * seeds' related lists are kept a week, so the daily rebuild is usually no
 * network at all. All of it lives in memory: the caches are rebuilt by the next
 * share after a restart, and keeping them out of the settings store keeps them
 * out of backups.
 */
class Similar(
    private val store: Store,
    private val index: AlbumIndex,
    private val deezer: Deezer,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val random: () -> Double = Math::random,
    private val worker: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "similar-taste").apply { isDaemon = true }
    }
) {
    /** One act in Deezer's related list for some act, as the scoring needs it. */
    data class PoolAct(val id: String, val name: String, val rank: Int, val fans: Int?, val picture: String?)

    /** How near an act sits to your listening, 0..1, and through which of your acts. */
    data class Near(val score: Double, val via: List<String>)

    data class Ranked(val act: PoolAct, val score: Double, val known: String?, val near: Boolean, val via: List<String>)

    data class TopAlbum(val id: String, val title: String, val cover: String?, val n: Int)
    data class ListedAlbum(val id: String, val title: String, val year: Int?, val cover: String?)
    data class Record(val title: String, val year: Int?, val cover: String?)

    /** One suggestion, as the route sends it. */
    data class Act(
        val name: String,
        val id: String,
        val album: String?,
        val year: Int?,
        val cover: String?,
        val reason: String,
        val known: String?
    )

    companion object {
        private const val TAG = "Similar"

        const val WANTED = 3          // acts to suggest
        const val RELATED_ROWS = 20   // related acts to consider for the playing act
        const val POOL = 8            // the best of them the draw is made from
        const val UNKNOWN = 2         // slots for acts not heard of
        const val SHOWN_KEEP = 30     // acts remembered as shown, per playing act
        const val MIN_FANS = 1000     // below this, Deezer's "related" is mostly tribute acts

        private const val DAY_MS = 24L * 60 * 60 * 1000
        const val POOL_TTL_MS = DAY_MS          // the playing act's related acts
        const val SEED_TTL_MS = 7 * DAY_MS      // a seed's related acts, for the taste graph
        const val ACT_TTL_MS = 7 * DAY_MS       // an act's top tracks and albums
        const val SHOWN_TTL_MS = 30 * DAY_MS    // what was shown, per playing act
        const val TASTE_SEED_DAYS = 180L        // plays considered for the taste graph
        const val TASTE_SEEDS = 40              // acts whose related lists make it
        const val HEAVY_DAYS = 5                // played on this many days: needs no introduction

        private fun norm(s: String?) = Deezer.normalize(s)

        /** Related acts with what the scoring needs: rank, followers, picture. */
        fun readPool(json: JSONObject?, limit: Int = RELATED_ROWS): List<PoolAct> {
            val data = json?.optJSONArray("data") ?: return emptyList()
            val seen = HashSet<String>()
            val out = ArrayList<PoolAct>()
            for (i in 0 until data.length()) {
                val a = data.optJSONObject(i) ?: continue
                val id = if (a.isNull("id")) "" else a.opt("id")?.toString().orEmpty()
                val name = a.str("name").trim()
                if (id.isEmpty() || name.isEmpty() || !seen.add(id)) continue
                val fans = if (a.has("nb_fan") && !a.isNull("nb_fan")) a.optInt("nb_fan", 0) else null
                out += PoolAct(id, name, out.size, fans, a.str("picture_medium").ifEmpty { a.str("picture") }.ifEmpty { null })
                if (out.size >= limit) break
            }
            return out
        }

        /**
         * The taste graph: for every act related to something you play, how
         * near it is to your listening (0..1) and through which of your acts.
         * A seed counts for the days it was played, discounted when those
         * plays are old; each related act takes that weight, tapering down the
         * seed's list.
         */
        fun tasteGraph(seeds: List<Deezer.Seed>, relatedOf: (String) -> List<PoolAct>?, now: Long): Map<String, Near> {
            class Acc(var score: Double = 0.0, val via: MutableList<String> = ArrayList())
            val g = LinkedHashMap<String, Acc>()
            for (seed in seeds) {
                if (seed.name.isBlank()) continue
                val age = (now - seed.last).toDouble() / DAY_MS
                val recency = if (age <= 30) 1.0 else if (age <= 90) 0.6 else 0.3
                val w = max(1, seed.days) * recency
                val rel = relatedOf(seed.name) ?: emptyList()
                rel.forEachIndexed { i, act ->
                    val key = norm(act.name)
                    if (key.isEmpty() || key == norm(seed.name)) return@forEachIndexed
                    val e = g.getOrPut(key) { Acc() }
                    e.score += w * (1 - i.toDouble() / (2 * max(1, rel.size)))
                    if (seed.name !in e.via) e.via += seed.name
                }
            }
            val top = g.values.maxOfOrNull { it.score } ?: 0.0
            return g.mapValues { (_, e) -> Near(if (top > 0) e.score / top else 0.0, e.via.toList()) }
        }

        /**
         * Score the pool for one share. [known] says "library", "played" or
         * null for a name; [heavy] holds the names you play most (never
         * suggested); [shown] the ids shown for this playing act lately.
         */
        fun rankActs(
            pool: List<PoolAct>,
            playing: String,
            taste: Map<String, Near>,
            known: (String) -> String?,
            heavy: Set<String>,
            shown: Set<String>
        ): List<Ranked> {
            val n = max(1, pool.size)
            val out = ArrayList<Ranked>()
            for (act in pool) {
                val key = norm(act.name)
                if (key.isEmpty() || key == norm(playing) || key in heavy) continue
                val t = taste[key]
                // Nearness to your listening counts for more than Deezer's
                // rank, and its square root so an act near a lightly played
                // seed still stands well clear of one near nothing you play.
                var score = 0.3 * (1 - act.rank.toDouble() / n) + (t?.let { sqrt(it.score) } ?: 0.0)
                if (act.fans != null && act.fans < MIN_FANS) score *= 0.5
                // Shown lately: behind the others, not out of the running.
                if (act.id in shown) score *= 0.5
                out += Ranked(act, score, known(act.name), t != null, t?.via?.take(2) ?: emptyList())
            }
            return out.sortedByDescending { it.score }
        }

        /** A weighted draw: the chance rises with the square of the score. */
        fun draw(list: List<Ranked>, rnd: () -> Double): Int {
            val w = list.map { max(0.01, it.score).let { v -> v * v } }
            var r = rnd() * w.sum()
            for (i in list.indices) {
                r -= w[i]
                if (r <= 0) return i
            }
            return list.size - 1
        }

        /**
         * Three acts from the ranking: [UNKNOWN] not heard of, then one you
         * know, filled from the rest. Acts near what you play go first: one
         * near nothing you play only fills a slot the near ones can't.
         */
        fun choose(ranked: List<Ranked>, want: Int = WANTED, rnd: () -> Double): List<Ranked> {
            fun byNear(l: List<Ranked>) = l.filter { it.near } + l.filter { !it.near }
            val unknown = byNear(ranked.filter { it.known == null }).take(POOL).toMutableList()
            val familiar = ranked.filter { it.known != null }.take(POOL).toMutableList()
            val picks = ArrayList<Ranked>()
            fun take(from: MutableList<Ranked>): Boolean {
                if (from.isEmpty()) return false
                val near = if (from.any { it.near }) from.filter { it.near } else from
                val pick = near[draw(near, rnd)]
                from.remove(pick)
                picks += pick
                return true
            }
            for (i in 0 until UNKNOWN) { if (picks.size >= want || !take(unknown)) break }
            if (picks.size < want) take(familiar)
            while (picks.size < want && (take(unknown) || take(familiar))) { /* fill */ }
            return picks
        }

        /** Why an act is on the row, in a few words. */
        fun reasonFor(r: Ranked, playing: String): String {
            if (r.known == "library") return "In your library — a record you don't have"
            if (r.known == "played") return "Something you've played — a record you don't have"
            val via = r.via.filter { norm(it) != norm(playing) }
            if (via.size >= 2) return "Near ${via[0]} and ${via[1]}, which you play"
            if (via.size == 1) return "Near ${via[0]}, which you play"
            return if (playing.isNotBlank()) "Near $playing" else ""
        }

        /**
         * An act's best-known record from its top tracks: the album most of
         * them are from. Not a debut (obscure, or a mis-dated reissue) and not
         * the newest (whatever they happen to have put out).
         */
        fun readTop(json: JSONObject?): TopAlbum? {
            val data = json?.optJSONArray("data") ?: return null
            val tally = LinkedHashMap<String, TopAlbum>()
            for (i in 0 until data.length()) {
                val al = data.optJSONObject(i)?.optJSONObject("album") ?: continue
                if (al.isNull("id")) continue
                val id = al.opt("id")?.toString().orEmpty()
                if (id.isEmpty()) continue
                val was = tally[id]
                tally[id] = was?.copy(n = was.n + 1) ?: TopAlbum(
                    id, al.str("title").trim(), al.str("cover_medium").ifEmpty { al.str("cover") }.ifEmpty { null }, 1
                )
            }
            var best: TopAlbum? = null
            for (e in tally.values) if (e.title.isNotEmpty() && (best == null || e.n > best.n)) best = e
            return best
        }

        /** Every full album in an artist's listing, newest first. */
        fun readAlbumList(json: JSONObject?, thisYear: Int = LocalDate.now().year): List<ListedAlbum> {
            val data = json?.optJSONArray("data") ?: return emptyList()
            val out = ArrayList<ListedAlbum>()
            for (i in 0 until data.length()) {
                val a = data.optJSONObject(i) ?: continue
                if (a.isNull("id") || a.str("record_type").lowercase() != "album") continue
                val title = a.str("title").trim()
                if (title.isEmpty()) continue
                val year = Regex("^(\\d{4})").find(a.str("release_date").trim())
                    ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1900..(thisYear + 1) }
                out += ListedAlbum(
                    a.opt("id").toString(), title, year,
                    a.str("cover_medium").ifEmpty { a.str("cover") }.ifEmpty { null }
                )
            }
            return out.sortedByDescending { it.year ?: 0 }
        }

        /** "Rumours (Super Deluxe)" is the "Rumours" you own. */
        fun ownedTitleKey(t: String): String = Deezer.titleKey(Deezer.stripEdition(t).first)

        /**
         * The record to name. An act you don't know: its best-known (the year
         * from its listing). An act you know: its newest full album you don't
         * own, else its best-known if you don't own that either.
         */
        fun recordFor(
            known: String?,
            top: TopAlbum?,
            albums: List<ListedAlbum>,
            ownedTitles: List<String>,
            titleKey: (String) -> String = ::ownedTitleKey
        ): Record? {
            val owned = ownedTitles.map(titleKey).toSet()
            fun ofTop(t: TopAlbum): Record {
                val hit = albums.firstOrNull { it.id == t.id }
                return Record(t.title, hit?.year, t.cover ?: hit?.cover)
            }
            fun ofListed(a: ListedAlbum) = Record(a.title, a.year, a.cover)
            if (known != null) {
                albums.firstOrNull { it.year != null && titleKey(it.title) !in owned }?.let { return ofListed(it) }
                if (top != null && titleKey(top.title) !in owned) return ofTop(top)
                return null
            }
            return top?.let(::ofTop) ?: albums.firstOrNull()?.let(::ofListed)
        }
    }

    // ------------------------------------------------------------- caches

    private val poolCache = TtlCache<String, List<PoolAct>>(POOL_TTL_MS, 300)
    private val seedCache = TtlCache<String, List<PoolAct>>(SEED_TTL_MS, 200)
    private val actCache = TtlCache<String, Pair<TopAlbum?, List<ListedAlbum>>>(ACT_TTL_MS, 500)
    private val shownCache = TtlCache<String, List<String>>(SHOWN_TTL_MS, 300)

    /**
     * An act's related acts, best first, or empty when Deezer does not know
     * them. Every candidate carrying the right name is tried, not just the
     * best. Only a real answer is kept: a failed call — a timeout, or Deezer's
     * quota error, which arrives as a 200 carrying {error} — says nothing about
     * the act, and kept as "no related acts" it blanked a taste seed for a week.
     */
    private fun related(name: String, seed: Boolean): List<PoolAct> {
        val cache = if (seed) seedCache else poolCache
        val key = norm(name)
        cache.peek(key)?.let { return it }
        val search = deezer.searchArtist(name) ?: return emptyList()
        var pool = emptyList<PoolAct>()
        for (cand in Deezer.readArtists(search, name).take(Deezer.CANDIDATES)) {
            val rel = deezer.related(cand.id, RELATED_ROWS) ?: return emptyList()
            pool = readPool(rel)
            if (pool.isNotEmpty()) break
        }
        cache.put(key, pool)
        return pool
    }

    /** An act's best-known record and its full albums, kept a week when either answered. */
    private fun records(act: PoolAct): Pair<TopAlbum?, List<ListedAlbum>> {
        actCache.peek(act.id)?.let { return it }
        val top = readTop(deezer.top(act.id))
        val albums = readAlbumList(deezer.albums(act.id), LocalDate.now(zone()).year)
        val v = top to albums
        if (top != null || albums.isNotEmpty()) actCache.put(act.id, v)
        return v
    }

    // --------------------------------------------------------- taste graph

    private class Taste(val day: String?, val graph: Map<String, Near>, val played: Set<String>, val heavy: Set<String>)

    @Volatile private var taste = Taste(null, emptyMap(), emptySet(), emptySet())
    private val inFlight = AtomicBoolean(false)

    /** Whether today's taste graph is being built right now. */
    val building: Boolean get() = inFlight.get()

    private fun today(): String = Instant.ofEpochMilli(clock()).atZone(zone()).toLocalDate().toString()

    /** Today's graph, or the last one while today's builds. */
    private fun taste(): Taste {
        val t = taste
        if (t.day != today()) buildTaste()
        return taste
    }

    private fun buildTaste() {
        if (!inFlight.compareAndSet(false, true)) return
        try {
            worker.execute {
                try {
                    val day = today()
                    val rows = store.playsSince(clock() - TASTE_SEED_DAYS * DAY_MS).map { it.artist to it.at }
                    val all = Deezer.playedArtists(rows, Int.MAX_VALUE, ShareLinks::primaryArtist)
                    val seeds = all.take(TASTE_SEEDS)
                    val rel = HashMap<String, List<PoolAct>>()
                    for (s in seeds) rel[s.name] = related(s.name, seed = true)
                    taste = Taste(
                        day,
                        tasteGraph(seeds, { rel[it] }, clock()),
                        all.map { norm(it.name) }.toSet(),
                        seeds.filter { it.days >= HEAVY_DAYS }.take(5).map { norm(it.name) }.toSet()
                    )
                    if (seeds.isNotEmpty()) Log.i(TAG, "${taste.graph.size} acts near the ${seeds.size} you play")
                } catch (e: Exception) {
                    Log.w(TAG, "taste build failed: ${e.message}", e)
                } finally {
                    inFlight.set(false)
                }
            }
        } catch (e: Exception) {
            inFlight.set(false)
            Log.w(TAG, "taste build not started: ${e.message}", e)
        }
    }

    // -------------------------------------------------------------- library

    @Volatile private var byArtist: Pair<Long, Map<String, List<AlbumRecord>>>? = null

    /**
     * The library's albums by an act — EXACT, once normalised and with a
     * leading "The" discounted, never by containment: owning Prince must not
     * make "Prince Buster" an act you know. Indexed once per library build.
     */
    private fun libraryAlbumsBy(name: String): List<AlbumRecord> {
        fun bare(n: String) = n.removePrefix("the ")
        val gen = index.generation
        val map = byArtist?.takeIf { it.first == gen }?.second ?: run {
            val m = HashMap<String, MutableList<AlbumRecord>>()
            fun add(n: String, al: AlbumRecord) {
                if (n.isEmpty()) return
                val list = m.getOrPut(bare(n)) { ArrayList() }
                if (al !in list) list += al
            }
            for (al in index.albums) {
                add(norm(al.subtitle), al)
                for (a in al.artistNames) add(norm(a.name), al)
            }
            byArtist = gen to m
            m
        }
        return map[bare(norm(name))] ?: emptyList()
    }

    /** Stops the taste thread; the app is shutting down. */
    fun close() {
        (worker as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }

    // --------------------------------------------------------------- answer

    /** Three acts for [primary], the first credited act on the card. */
    fun suggest(primary: String): List<Act> {
        val key = norm(primary)
        if (key.isEmpty()) return emptyList()
        val pool = related(primary, seed = false)
        if (pool.isEmpty()) return emptyList()
        val t = taste()
        val known = { name: String ->
            if (libraryAlbumsBy(name).isNotEmpty()) "library"
            else if (norm(name) in t.played) "played" else null
        }
        val shownBefore = shownCache.peek(key) ?: emptyList()
        val ranked = rankActs(pool, primary, t.graph, known, t.heavy, shownBefore.toSet())
        val picks = choose(ranked, WANTED, random)
        val acts = picks.map { r ->
            val (top, albums) = records(r.act)
            val owned = if (r.known == "library") libraryAlbumsBy(r.act.name).map { it.title } else emptyList()
            val rec = recordFor(r.known, top, albums, owned)
            Act(r.act.name, r.act.id, rec?.title, rec?.year, rec?.cover ?: r.act.picture, reasonFor(r, primary), r.known)
        }
        if (picks.isNotEmpty()) {
            shownCache.put(key, (picks.map { it.act.id } + shownBefore).distinct().take(SHOWN_KEEP))
        }
        return acts
    }
}

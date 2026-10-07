package com.musicd.lite.backup

import com.musicd.lite.Discover
import com.musicd.lite.Log
import com.musicd.lite.str
import com.musicd.lite.api.Settings
import com.musicd.lite.library.LibraryView
import com.musicd.lite.library.ListenLater
import com.musicd.lite.library.UserPlaylists
import com.musicd.lite.store.Store
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Backup & restore (Rouen v1.8.84, after Mandarin v0.6.14).
 *
 * A backup is one JSON file, rouen-lite-backup-<stamp>.json, holding whichever
 * of these parts were chosen:
 *
 *   settings   every preference in the settings store that is not a key, a
 *              playlist, Listen later or this phone's own
 *   playlists  your playlists
 *   later      the Listen later list
 *   keys       the Discogs token and the FanArt.tv key
 *
 * Never in a backup: LAN access (whether this phone serves the page to the
 * network, its PIN and its signing key — restoring another phone's backup must
 * not open this one's port), the zone ids Roon hands out (they do not survive a
 * Core restart), caches and stamps the app rebuilds, play history, the library,
 * the Roon pairing, and the page's look (theme and UI Settings, which each
 * device keeps for itself). A backup is for what a person MADE or CHOSE.
 *
 * Settings are classified by DENYING rather than allowing: a preference added
 * later goes into the settings part without anyone remembering to list it
 * here. The risk that runs the other way — a new secret riding in the settings
 * part — is closed by [looksSecret], which sends any unclassified key named
 * like a token, secret, password or key to the keys part instead.
 *
 * Restoring REPLACES each chosen part: a setting absent from the backup goes
 * back to its default rather than keeping today's value, because "restore"
 * means "as it was then". That is why every restore keeps a "before restore"
 * backup of all four parts first.
 *
 * Unlike Rouen, nothing restarts afterwards. Rouen restarts because most of
 * its settings are read into variables at start-up; here every reader goes to
 * the store each time, so one transaction is the whole restore and the page
 * reloads to show it.
 */
object BackupFormat {

    const val FORMAT = 1
    const val APP = "Rouen Lite"
    val PARTS = listOf("settings", "playlists", "later", "keys")

    /** The keys part: what signs this app in somewhere. */
    private val KEY_KEYS = setOf(Settings.KEY_DISCOGS_TOKEN, Settings.KEY_FANART_KEY)
    private val PLAYLIST_KEYS = setOf(UserPlaylists.KEY)
    private val LATER_KEYS = setOf(ListenLater.KEY)

    /**
     * Never backed up, never restored over. LAN access is this phone's network
     * exposure; the last zone and the radio zones are zone ids; the rest are
     * caches and stamps the app keeps for itself and rebuilds.
     */
    private val NEVER_KEYS = setOf(
        Settings.KEY_LAN, Settings.KEY_LAST_ZONE, Settings.KEY_RADIO_ZONES,
        Discover.KEY_LIST, LibraryView.KEY_AOTD
    )

    /**
     * Bookkeeping inside a settings document: left out of a backup, and kept
     * from today's document on a restore. "later_day" is the day whose Smart
     * Picks were already sent to Listen later — put back from an older backup,
     * today's picks would be sent again.
     */
    private val KEEP_FIELDS = mapOf(Settings.KEY_SMART_PICKS to listOf("later_day"))

    /** The shape every key this app writes has. Anything else in a file is ignored. */
    private val KEY_SHAPE = Regex("^[a-z0-9_]{1,64}$")

    private val SECRET = Regex("token|secret|password|passwd|md5|apikey|key$", RegexOption.IGNORE_CASE)
    private fun looksSecret(k: String) = SECRET.containsMatchIn(k)

    /** Which part a settings key belongs to, or "never". */
    fun classify(key: String): String = when {
        key in NEVER_KEYS -> "never"
        key in KEY_KEYS -> "keys"
        key in PLAYLIST_KEYS -> "playlists"
        key in LATER_KEYS -> "later"
        looksSecret(key) -> "keys"
        else -> "settings"
    }

    /** Which parts a request asked for: known names, once each, in canonical order. Absent is all. */
    fun partsFrom(v: JSONArray?): List<String> {
        if (v == null) return PARTS
        val want = (0 until v.length()).map { v.opt(it)?.toString() }.toSet()
        return PARTS.filter { it in want }
    }

    private fun docOrNull(raw: String?): JSONObject? =
        raw?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** A backup of [parts] from the live settings store's contents. */
    fun build(
        live: Map<String, String>,
        parts: List<String>,
        kind: String,
        version: String,
        now: Long
    ): JSONObject {
        val chosen = PARTS.filter { it in parts }
        val b = JSONObject()
            .put("app", APP)
            .put("format", FORMAT)
            .put("version", version)
            .put("created", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(now - now % 1000)))
            .put("kind", kind)
            .put("parts", JSONArray(chosen))
        for (part in chosen) b.put(part, JSONObject())
        for (key in live.keys.sorted()) {
            val part = classify(key)
            if (part !in chosen || !KEY_SHAPE.matches(key)) continue
            val doc = docOrNull(live[key]) ?: continue
            KEEP_FIELDS[key]?.forEach { doc.remove(it) }
            b.getJSONObject(part).put(key, doc)
        }
        return b
    }

    /**
     * A backup read back from text (a stored file or an upload). Throws
     * [BackupException] with a message fit to show when it is not one;
     * otherwise returns it with `parts` reduced to the parts it carries.
     */
    fun parse(text: String): JSONObject {
        val b = runCatching { JSONObject(text) }.getOrNull()
            ?: throw BackupException("That file is not a Rouen Lite backup.")
        val app = b.opt("app")
        if (app == "Rouen" || app == "MusicD Remote") {
            throw BackupException(
                "That is a backup from Rouen, not Rouen Lite. The two keep their settings " +
                    "differently, so it can't be restored here."
            )
        }
        if (app != APP) throw BackupException("That file is not a Rouen Lite backup.")
        val format = b.opt("format")
        if (format !is Int || format < 1) throw BackupException("That file is not a Rouen Lite backup.")
        if (format > FORMAT) {
            throw BackupException("That backup was made by a newer Rouen Lite. Update first, then restore it.")
        }
        val has = PARTS.filter { b.opt(it) is JSONObject }
        if (has.isEmpty()) throw BackupException("That backup holds nothing to restore.")
        b.put("parts", JSONArray(has))
        return b
    }

    /** What a restore writes: every document put, and every key removed. */
    data class Plan(val parts: List<String>, val puts: Map<String, String>, val removes: Set<String>)

    /**
     * What restoring [parts] of [b] over [current] (the live settings store)
     * produces. Pure. Each part takes only the keys that belong to it — a
     * token tucked into the settings part is not restored as a setting — and
     * nothing classified "never" is touched in either direction.
     */
    fun plan(b: JSONObject, parts: List<String>, current: Map<String, String>): Plan {
        val held = b.optJSONArray("parts")?.let { a -> (0 until a.length()).map { a.str(it) } } ?: emptyList()
        val doing = PARTS.filter { it in parts && it in held }
        val puts = LinkedHashMap<String, String>()
        val removes = HashSet<String>()
        for (part in doing) {
            for (k in current.keys) if (classify(k) == part) removes += k
            val from = b.optJSONObject(part) ?: JSONObject()
            for (k in from.keySet()) {
                if (!KEY_SHAPE.matches(k) || classify(k) != part) continue
                val doc = from.optJSONObject(k) ?: continue
                puts[k] = when (k) {
                    ListenLater.KEY -> ListenLater.normalize(doc)
                    UserPlaylists.KEY -> UserPlaylists.normalize(doc)
                    else -> doc
                }.toString()
            }
        }
        if ("settings" in doing) {
            for ((key, fields) in KEEP_FIELDS) {
                val now = docOrNull(current[key]) ?: continue
                val kept = fields.filter { now.has(it) }
                if (kept.isEmpty()) continue
                val doc = docOrNull(puts[key]) ?: JSONObject()
                for (f in kept) doc.put(f, now.get(f))
                puts[key] = doc.toString()
            }
        }
        removes.removeAll(puts.keys)
        return Plan(doing, puts, removes)
    }
}

class BackupException(message: String) : Exception(message)

/**
 * The backups on disk: the last ten of your own (made here or brought in from
 * a file) and the last five kept before a restore, counted apart so a run of
 * restores never pushes out a backup you made.
 */
class BackupStore(
    private val dir: File,
    private val zone: () -> ZoneId = ZoneId::systemDefault
) {
    companion object {
        const val KEEP_MANUAL = 10
        const val KEEP_BEFORE = 5
        private val ID = Regex("^rouen-lite-(backup|before-restore)-\\d{8}-\\d{6}(-\\d+)?$")
        private val STAMP = Regex("(\\d{8}-\\d{6})(?:-(\\d+))?$")
        private val NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        fun validId(id: String): Boolean = ID.matches(id)
    }

    data class Entry(
        val id: String,
        val size: Long,
        val created: String,
        val kind: String,
        val version: String,
        val parts: List<String>,
        val error: String?
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("size", size)
            .put("created", created)
            .put("kind", kind)
            .put("version", version)
            .put("parts", JSONArray(parts))
            .also { if (error != null) it.put("error", error) }
    }

    private val lock = Any()

    private fun file(id: String) = File(dir, "$id.json")

    /** Newest first, by when each was STORED. A file that cannot be read is listed as such. */
    fun list(): List<Entry> = synchronized(lock) {
        val names = dir.list() ?: return emptyList()
        val out = ArrayList<Entry>()
        for (n in names) {
            if (!n.endsWith(".json")) continue
            val id = n.removeSuffix(".json")
            if (!validId(id)) continue
            val f = File(dir, n)
            if (!f.isFile) continue
            out += try {
                val b = BackupFormat.parse(f.readText())
                val kind = b.str("kind").takeIf { it == "before-restore" || it == "uploaded" } ?: "manual"
                val parts = b.getJSONArray("parts").let { a -> (0 until a.length()).map { a.getString(it) } }
                Entry(id, f.length(), b.str("created"), kind, b.str("version"), parts, null)
            } catch (e: Exception) {
                Entry(
                    id, f.length(), Instant.ofEpochMilli(f.lastModified()).toString(),
                    if (id.startsWith("rouen-lite-before")) "before-restore" else "manual",
                    "", emptyList(), e.message ?: "unreadable"
                )
            }
        }
        // By the stamp in the name, not the backup's own date: one brought in
        // from a file may be old, and ordering by its own date would put it
        // past the ten kept and prune it on arrival.
        fun at(id: String): String = STAMP.find(id)?.let {
            it.groupValues[1] + "-" + (it.groupValues[2].ifEmpty { "1" }).padStart(4, '0')
        } ?: ""
        return out.sortedByDescending { at(it.id) }
    }

    /**
     * Writes [b] and returns its id. Atomic: written to a temporary file and
     * renamed, so a phone killed mid-write leaves no half-file. [keep] is an id
     * pruning must not remove — the backup a restore is reading, which is the
     * OLDEST before-restore copy exactly when restoring the oldest.
     */
    fun save(b: JSONObject, now: Long, keep: String? = null): String = synchronized(lock) {
        if (!dir.isDirectory && !dir.mkdirs()) throw java.io.IOException("can't create ${dir.name}")
        val kind = if (b.str("kind") == "before-restore") "before-restore" else "backup"
        val base = "rouen-lite-$kind-" + NAME.format(Instant.ofEpochMilli(now).atZone(zone()))
        var id = base
        var i = 2
        while (file(id).exists()) id = "$base-${i++}"
        val tmp = File(dir, "$id.json.tmp")
        tmp.writeText(b.toString())
        if (!tmp.renameTo(file(id))) {
            tmp.delete()
            throw java.io.IOException("can't write $id")
        }
        prune(keep)
        id
    }

    /** The file's text exactly, and what it holds. */
    fun read(id: String): Pair<String, JSONObject> = synchronized(lock) {
        if (!validId(id)) throw BackupException("No such backup.")
        val text = runCatching { file(id).readText() }.getOrNull() ?: throw BackupException("No such backup.")
        text to BackupFormat.parse(text)
    }

    fun remove(id: String): Boolean = synchronized(lock) {
        validId(id) && file(id).delete()
    }

    private fun prune(keep: String?) {
        val all = list().filter { it.id != keep }
        all.filter { it.kind != "before-restore" }.drop(KEEP_MANUAL).forEach { file(it.id).delete() }
        all.filter { it.kind == "before-restore" }.drop(KEEP_BEFORE).forEach { file(it.id).delete() }
    }
}

/**
 * The live half: reads the settings store into a backup, and writes a plan
 * back to it in one transaction.
 */
class Backups(
    private val store: Store,
    val files: BackupStore,
    private val version: String,
    /** Told which parts were restored, so the live revisions move. */
    private val onRestored: (List<String>) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** Changes on every start. Rouen's page reads it; nothing here restarts. */
    val boot: String = java.lang.Long.toString(clock(), 36)

    /** One make or restore at a time: a restore's before-copy must be of now. */
    private val lock = Any()

    private fun live(): Map<String, String> {
        val out = HashMap<String, String>()
        for (k in store.settingKeys()) store.setting(k)?.let { out[k] = it }
        return out
    }

    fun make(parts: List<String>, kind: String = "manual"): String = synchronized(lock) {
        files.save(BackupFormat.build(live(), parts, kind, version, clock()), clock())
    }

    fun upload(text: String): String {
        val b = BackupFormat.parse(text)
        b.put("kind", "uploaded")
        return synchronized(lock) { files.save(b, clock()) }
    }

    data class Restored(val parts: List<String>, val before: String)

    /**
     * Restores [parts] of backup [id]. A "before restore" copy of all four
     * parts is written first; if that cannot be written, nothing is restored.
     */
    fun restore(id: String, parts: List<String>): Restored = synchronized(lock) {
        val b = files.read(id).second
        val held = b.getJSONArray("parts").let { a -> (0 until a.length()).map { a.getString(it) } }
        val doing = parts.filter { it in held }
        if (doing.isEmpty()) throw IllegalArgumentException("Nothing chosen that this backup holds.")
        val current = live()
        val before = try {
            files.save(BackupFormat.build(current, BackupFormat.PARTS, "before-restore", version, clock()), clock(), keep = id)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Couldn't keep a copy of how things are now, so nothing was restored: ${e.message}"
            )
        }
        val plan = BackupFormat.plan(b, doing, current)
        try {
            store.replaceSettings(plan.puts, plan.removes)
        } catch (e: Exception) {
            throw IllegalStateException("Restore failed: ${e.message}. Nothing was changed.")
        }
        // Only the live revisions move here: what was restored is already
        // stored, and a page reloads to read it whether or not they do.
        runCatching { onRestored(plan.parts) }
            .onFailure { Log.w("Backups", "restored, but telling the screens failed: ${it.message}", it) }
        Restored(plan.parts, before)
    }
}

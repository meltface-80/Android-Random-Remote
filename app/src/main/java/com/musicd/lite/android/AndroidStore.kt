package com.musicd.lite.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.musicd.lite.store.PlayRow
import com.musicd.lite.store.Store

/**
 * The app's memory, in SQLite.
 *
 * MusicD-Remote keeps the same facts in a better-sqlite3 database on the Docker
 * volume; the schema here is the subset this build needs, minus every label
 * table. Pairing tokens and the Core address go to SharedPreferences rather than
 * the database — they must survive even if the database cannot be opened, since
 * losing a token means asking the user to approve the extension again.
 */
class AndroidStore(context: Context) : Store {

    private companion object {
        const val DB_NAME = "musicd-lite.db"
        const val DB_VERSION = 1
        const val PREFS = "musicd-lite"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val helper = object : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS settings (
                  key   TEXT PRIMARY KEY,
                  value TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS plays (
                  album_key TEXT NOT NULL,
                  album     TEXT NOT NULL,
                  artist    TEXT NOT NULL,
                  track     TEXT NOT NULL,
                  ts        INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS plays_key ON plays(album_key)")
            db.execSQL("CREATE INDEX IF NOT EXISTS plays_ts ON plays(ts)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS album_seen (
                  album_key TEXT PRIMARY KEY,
                  first_ts  INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS album_years (
                  album_key TEXT PRIMARY KEY,
                  year      INTEGER NOT NULL,
                  src_rank  INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS album_genres (
                  album_key TEXT NOT NULL,
                  genre     TEXT NOT NULL,
                  PRIMARY KEY (album_key, genre)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS album_tracks (
                  album_key TEXT NOT NULL,
                  idx       INTEGER NOT NULL,
                  title     TEXT NOT NULL,
                  PRIMARY KEY (album_key, idx)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE TABLE IF NOT EXISTS pick_blocks (album_key TEXT PRIMARY KEY)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS pick_seen (
                  album_key TEXT PRIMARY KEY,
                  ts        INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Version 1 is the first release; there is nothing to migrate yet.
            // When there is, migrate — never drop. This database holds listening
            // history that cannot be recovered from anywhere else.
        }
    }

    private val db: SQLiteDatabase get() = helper.writableDatabase

    // --------------------------------------------------------------- pairing

    override fun tokenFor(coreId: String): String? = prefs.getString("token_$coreId", null)

    override fun saveToken(coreId: String, token: String) {
        prefs.edit().putString("token_$coreId", token).apply()
    }

    override fun lastCore(): Pair<String, Int>? {
        val host = prefs.getString("core_host", null) ?: return null
        val port = prefs.getInt("core_port", 0)
        return if (port > 0) host to port else null
    }

    override fun saveLastCore(host: String, port: Int) {
        prefs.edit().putString("core_host", host).putInt("core_port", port).apply()
    }

    override fun forgetLastCore() {
        prefs.edit().remove("core_host").remove("core_port").apply()
    }

    // -------------------------------------------------------------- settings

    override fun setting(key: String): String? =
        db.rawQuery("SELECT value FROM settings WHERE key = ?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    override fun putSetting(key: String, value: String) {
        db.insertWithOnConflict(
            "settings", null,
            ContentValues().apply { put("key", key); put("value", value) },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // ---------------------------------------------------------------- plays

    override fun recordPlay(
        albumKey: String,
        album: String,
        artist: String,
        track: String,
        at: Long
    ) {
        db.insert(
            "plays", null,
            ContentValues().apply {
                put("album_key", albumKey)
                put("album", album)
                put("artist", artist)
                put("track", track)
                put("ts", at)
            }
        )
    }

    override fun playsSince(since: Long): List<PlayRow> =
        db.rawQuery(
            "SELECT album_key, album, artist, track, ts FROM plays WHERE ts >= ? ORDER BY ts DESC",
            arrayOf(since.toString())
        ).use { c ->
            val out = ArrayList<PlayRow>(c.count)
            while (c.moveToNext()) {
                out += PlayRow(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4))
            }
            out
        }

    override fun lastPlayed(albumKey: String): Long? =
        db.rawQuery("SELECT MAX(ts) FROM plays WHERE album_key = ?", arrayOf(albumKey)).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }

    override fun lastPlayedAll(): Map<String, Long> =
        db.rawQuery("SELECT album_key, MAX(ts) FROM plays GROUP BY album_key", null).use { c ->
            val out = HashMap<String, Long>(c.count)
            while (c.moveToNext()) out[c.getString(0)] = c.getLong(1)
            out
        }

    override fun playCounts(): Map<String, Int> =
        db.rawQuery("SELECT album_key, COUNT(*) FROM plays GROUP BY album_key", null).use { c ->
            val out = HashMap<String, Int>(c.count)
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
            out
        }

    override fun prunePlays(before: Long) {
        db.delete("plays", "ts < ?", arrayOf(before.toString()))
    }

    // ------------------------------------------------------- album metadata

    override fun firstSeen(albumKey: String): Long? =
        db.rawQuery("SELECT first_ts FROM album_seen WHERE album_key = ?", arrayOf(albumKey)).use {
            // A zero means "seen on the very first scan", which is not a date:
            // dating every album at once would make "recently added" mean
            // "everything".
            if (it.moveToFirst()) it.getLong(0).takeIf { ts -> ts > 0 } else null
        }

    override fun recordFirstSeen(entries: Map<String, Long>) {
        if (entries.isEmpty()) return
        db.beginTransaction()
        try {
            for ((key, ts) in entries) {
                db.insertWithOnConflict(
                    "album_seen", null,
                    ContentValues().apply { put("album_key", key); put("first_ts", ts) },
                    SQLiteDatabase.CONFLICT_IGNORE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun firstSeenAll(): Map<String, Long> =
        db.rawQuery("SELECT album_key, first_ts FROM album_seen", null).use { c ->
            val out = HashMap<String, Long>(c.count)
            while (c.moveToNext()) out[c.getString(0)] = c.getLong(1)
            out
        }

    override fun albumYear(albumKey: String): Int? =
        db.rawQuery("SELECT year FROM album_years WHERE album_key = ?", arrayOf(albumKey)).use {
            if (it.moveToFirst()) it.getInt(0) else null
        }

    /**
     * A good answer is never overwritten by a worse one.
     *
     * Written as a read then a write inside a transaction rather than as an
     * UPSERT: `ON CONFLICT ... DO UPDATE` needs SQLite 3.24, which Android only
     * carries from API 30, and this app runs from API 26.
     */
    override fun putAlbumYear(albumKey: String, year: Int, sourceRank: Int) {
        db.beginTransaction()
        try {
            val existingRank = db.rawQuery(
                "SELECT src_rank FROM album_years WHERE album_key = ?", arrayOf(albumKey)
            ).use { if (it.moveToFirst()) it.getInt(0) else null }

            if (existingRank == null || sourceRank >= existingRank) {
                db.insertWithOnConflict(
                    "album_years", null,
                    ContentValues().apply {
                        put("album_key", albumKey); put("year", year); put("src_rank", sourceRank)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun albumYears(): Map<String, Int> =
        db.rawQuery("SELECT album_key, year FROM album_years", null).use { c ->
            val out = HashMap<String, Int>(c.count)
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
            out
        }

    override fun albumGenres(albumKey: String): List<String> =
        db.rawQuery("SELECT genre FROM album_genres WHERE album_key = ?", arrayOf(albumKey)).use { c ->
            val out = ArrayList<String>(c.count)
            while (c.moveToNext()) out += c.getString(0)
            out
        }

    override fun putAlbumGenres(albumKey: String, genres: List<String>) {
        db.beginTransaction()
        try {
            db.delete("album_genres", "album_key = ?", arrayOf(albumKey))
            for (g in genres) {
                db.insertWithOnConflict(
                    "album_genres", null,
                    ContentValues().apply { put("album_key", albumKey); put("genre", g) },
                    SQLiteDatabase.CONFLICT_IGNORE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun albumGenresAll(): Map<String, List<String>> =
        db.rawQuery("SELECT album_key, genre FROM album_genres", null).use { c ->
            val out = HashMap<String, MutableList<String>>()
            while (c.moveToNext()) out.getOrPut(c.getString(0)) { ArrayList(2) } += c.getString(1)
            out
        }

    override fun albumTracks(albumKey: String): List<String> =
        db.rawQuery(
            "SELECT title FROM album_tracks WHERE album_key = ? ORDER BY idx", arrayOf(albumKey)
        ).use { c ->
            val out = ArrayList<String>(c.count)
            while (c.moveToNext()) out += c.getString(0)
            out
        }

    override fun putAlbumTracks(albumKey: String, tracks: List<String>) {
        db.beginTransaction()
        try {
            // Replaced wholesale, deliberately, so a re-rip cannot leave phantom
            // tracks behind. Callers must never write a partial list.
            db.delete("album_tracks", "album_key = ?", arrayOf(albumKey))
            tracks.forEachIndexed { i, title ->
                db.insertWithOnConflict(
                    "album_tracks", null,
                    ContentValues().apply {
                        put("album_key", albumKey); put("idx", i); put("title", title)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ----------------------------------------------------------- smart picks

    override fun blockedPicks(): Set<String> =
        db.rawQuery("SELECT album_key FROM pick_blocks", null).use { c ->
            val out = HashSet<String>(c.count)
            while (c.moveToNext()) out += c.getString(0)
            out
        }

    override fun blockPick(albumKey: String) {
        db.insertWithOnConflict(
            "pick_blocks", null,
            ContentValues().apply { put("album_key", albumKey) },
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    override fun seenPicks(): Set<String> =
        db.rawQuery("SELECT album_key FROM pick_seen", null).use { c ->
            val out = HashSet<String>(c.count)
            while (c.moveToNext()) out += c.getString(0)
            out
        }

    override fun markPickSeen(albumKey: String, at: Long) {
        db.insertWithOnConflict(
            "pick_seen", null,
            ContentValues().apply { put("album_key", albumKey); put("ts", at) },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    override fun close() {
        helper.close()
    }
}

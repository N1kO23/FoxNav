package app.nullpointr.foxnav.data

import android.database.sqlite.SQLiteDatabase
import app.nullpointr.foxnav.shared.geo.GeoPoint
import app.nullpointr.foxnav.shared.search.SearchEntry
import app.nullpointr.foxnav.shared.search.SearchKind
import app.nullpointr.foxnav.shared.search.SearchText
import java.io.File
import kotlin.math.PI
import kotlin.math.cos

/**
 * The search packs' cells on the phone (protocol/SPEC.md §3.4), in SQLite with a full-text index:
 * search without a connection, wherever there's road data. [RoadData] fills it, [PlaceSearch] asks it.
 */
class SearchIndex(private val file: File) {
    private val db: SQLiteDatabase by lazy {
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).apply {
            // Searches read while downloads write.
            enableWriteAheadLogging()
            execSQL("CREATE TABLE IF NOT EXISTS cells (region TEXT NOT NULL, cell INTEGER NOT NULL, build TEXT NOT NULL, PRIMARY KEY (region, cell))")
            execSQL(
                "CREATE TABLE IF NOT EXISTS entries (id INTEGER PRIMARY KEY, region TEXT NOT NULL, cell INTEGER NOT NULL, " +
                    "kind TEXT NOT NULL, title TEXT NOT NULL, detail TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, weight REAL NOT NULL, aka TEXT NOT NULL)",
            )
            execSQL("CREATE INDEX IF NOT EXISTS entries_by_cell ON entries (region, cell)")
            // The words to find each entry by; its docid is the entry's id.
            execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS entry_words USING fts4(words)")
        }
    }

    /** The cells on the phone, by region: cell → the build it came from. */
    @Synchronized
    fun cells(): Map<String, Map<Long, String>> = buildMap<String, MutableMap<Long, String>> {
        db.rawQuery("SELECT region, cell, build FROM cells", null).use { rows ->
            while (rows.moveToNext()) getOrPut(rows.getString(0)) { mutableMapOf() }[rows.getLong(1)] = rows.getString(2)
        }
    }

    /** Puts [entries] in as [region]'s [cell] from [build], in place of what it had. */
    @Synchronized
    fun replace(region: String, cell: Long, build: String, entries: List<SearchEntry>) = transaction {
        deleteRows(region, cell)
        val entry = db.compileStatement("INSERT INTO entries (region, cell, kind, title, detail, lat, lon, weight, aka) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
        val words = db.compileStatement("INSERT INTO entry_words (docid, words) VALUES (?, ?)")
        for (item in entries) {
            entry.clearBindings()
            entry.bindString(1, region)
            entry.bindLong(2, cell)
            entry.bindString(3, item.kind.key)
            entry.bindString(4, item.title)
            entry.bindString(5, item.detail)
            entry.bindDouble(6, item.latitude)
            entry.bindDouble(7, item.longitude)
            entry.bindDouble(8, item.weight)
            entry.bindString(9, item.aka.joinToString(AKA_SEPARATOR))
            val id = entry.executeInsert()
            words.bindLong(1, id)
            words.bindString(2, SearchText.indexed(item))
            words.executeInsert()
        }
        db.execSQL("INSERT OR REPLACE INTO cells (region, cell, build) VALUES (?, ?, ?)", arrayOf<Any>(region, cell, build))
    }

    @Synchronized
    fun delete(region: String, cell: Long) = transaction {
        deleteRows(region, cell)
        db.execSQL("DELETE FROM cells WHERE region = ? AND cell = ?", arrayOf<Any>(region, cell))
    }

    /** The best results for [query] among the cells on the phone, the nearer to [near] the better. */
    fun search(query: String, near: GeoPoint?, limit: Int = 8): List<SearchEntry> {
        val match = SearchText.ftsQuery(query) ?: return emptyList()
        // The index finds every entry with all the words; the nearest (or most notable) go on to be ranked.
        val order = if (near != null) {
            val squash = cos(near.latitude * PI / 180).let { it * it }
            "(e.lat - ${near.latitude}) * (e.lat - ${near.latitude}) + (e.lon - ${near.longitude}) * (e.lon - ${near.longitude}) * $squash"
        } else {
            "e.weight DESC"
        }
        val candidates = db.rawQuery(
            "SELECT e.kind, e.title, e.detail, e.lat, e.lon, e.weight, e.aka FROM entry_words w JOIN entries e ON e.id = w.docid " +
                "WHERE w.words MATCH ? ORDER BY $order LIMIT $CANDIDATES",
            arrayOf(match),
        ).use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    val kind = SearchKind.of(rows.getString(0)) ?: continue
                    add(
                        SearchEntry(
                            kind, rows.getString(1), rows.getString(2), rows.getDouble(3), rows.getDouble(4), rows.getDouble(5),
                            rows.getString(6).split(AKA_SEPARATOR).filter { it.isNotEmpty() },
                        ),
                    )
                }
            }
        }
        return SearchText.rank(query, candidates, near, limit)
    }

    /** What the index takes on the phone. */
    val bytes: Long get() = file.length() + File(file.path + "-wal").length()

    private fun deleteRows(region: String, cell: Long) {
        db.execSQL("DELETE FROM entry_words WHERE docid IN (SELECT id FROM entries WHERE region = ? AND cell = ?)", arrayOf<Any>(region, cell))
        db.execSQL("DELETE FROM entries WHERE region = ? AND cell = ?", arrayOf<Any>(region, cell))
    }

    private inline fun transaction(block: () -> Unit) {
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        const val CANDIDATES = 400
        const val AKA_SEPARATOR = "\u001f"
    }
}

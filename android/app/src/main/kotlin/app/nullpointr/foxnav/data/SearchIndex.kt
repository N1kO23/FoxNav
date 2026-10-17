package app.nullpointr.foxnav.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.nullpointr.foxnav.shared.geo.GeoPoint
import app.nullpointr.foxnav.shared.search.SearchEntry
import app.nullpointr.foxnav.shared.search.SearchKind
import app.nullpointr.foxnav.shared.search.SearchPackManifest
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
            // Every word in there, for finding the ones a typo away from what's typed.
            execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS entry_terms USING fts4aux(entry_words)")
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

    /**
     * The best results for [query] among the cells on the phone, the nearer to [near] the better:
     * spelt as typed first, then, if those are too few, give or take a typo or two.
     */
    fun search(query: String, near: GeoPoint?, limit: Int = 8): List<SearchEntry> {
        val match = SearchText.ftsQuery(query) ?: return emptyList()
        val exact = SearchText.rank(query, matching(match, near, CANDIDATES), near, limit)
        if (exact.size >= limit) return exact
        val loose = looseCandidates(query, near).filter { SearchText.matchesLoosely(query, it) }
        return (exact + SearchText.rank(query, loose, near, limit)).distinctBy { it.title + "|" + it.detail }.take(limit)
    }

    /**
     * Entries that may be [query] with typos in it: those with a word a typo or two from its longest
     * word, among the index's words with the same first letter. A typo in the first letter is too
     * much to look for.
     */
    private fun looseCandidates(query: String, near: GeoPoint?): List<SearchEntry> {
        val longest = SearchText.words(query).filter { SearchText.typosAllowed(it) > 0 }.maxByOrNull { it.length } ?: return emptyList()
        val first = longest.first()
        val similar = db.rawQuery("SELECT term FROM entry_terms WHERE col = '*' AND term >= ? AND term < ?", arrayOf(first.toString(), (first + 1).toString())).use { rows ->
            buildList { while (rows.moveToNext()) rows.getString(0).takeIf { SearchText.similar(longest, it) }?.let(::add) }
        }
        return similar
            .sortedBy { SearchText.distance(longest, it.take(longest.length), SearchText.typosAllowed(longest)) }
            .take(MAX_SIMILAR_WORDS)
            .flatMap { word -> matching(word, near, LOOSE_CANDIDATES) }
    }

    /**
     * Entries matching the full-text query [match]: the [limit] nearest to [near], and the most
     * notable wherever they are. Otherwise a street's many house numbers nearby would crowd out the
     * street itself, or the town of that name further off.
     */
    private fun matching(match: String, near: GeoPoint?, limit: Int): List<SearchEntry> {
        val notable = select(match, "e.weight DESC", if (near == null) limit else NOTABLE_CANDIDATES)
        if (near == null) return notable
        val squash = cos(near.latitude * PI / 180).let { it * it }
        val distance = "(e.lat - ${near.latitude}) * (e.lat - ${near.latitude}) + (e.lon - ${near.longitude}) * (e.lon - ${near.longitude}) * $squash"
        return (select(match, distance, limit) + notable).distinct()
    }

    private fun select(match: String, order: String, limit: Int): List<SearchEntry> = db.rawQuery(
        "SELECT e.kind, e.title, e.detail, e.lat, e.lon, e.weight, e.aka FROM entry_words w JOIN entries e ON e.id = w.docid " +
            "WHERE w.words MATCH ? ORDER BY $order LIMIT $limit",
        arrayOf(match),
    ).use(::entries)

    /** What's within [radiusM] of [point], nearest first, for naming places. */
    fun nearby(point: GeoPoint, radiusM: Double, limit: Int = 500): List<SearchEntry> {
        val dLat = radiusM / METRES_PER_DEGREE
        val dLon = dLat / cos(point.latitude * PI / 180).coerceAtLeast(0.01)
        val south = SearchPackManifest.cellAt(GeoPoint(point.latitude - dLat, point.longitude - dLon))
        val north = SearchPackManifest.cellAt(GeoPoint(point.latitude + dLat, point.longitude - dLon))
        val width = SearchPackManifest.cellAt(GeoPoint(point.latitude - dLat, point.longitude + dLon)) - south
        val cells = generateSequence(south) { it + COLUMNS }.takeWhile { it <= north }.flatMap { row -> (row..row + width) }.joinToString(",")
        val squash = cos(point.latitude * PI / 180).let { it * it }
        return cells().keys.flatMap { region ->
            db.rawQuery(
                "SELECT e.kind, e.title, e.detail, e.lat, e.lon, e.weight, e.aka FROM entries e WHERE e.region = ? AND e.cell IN ($cells) " +
                    "AND e.lat BETWEEN ? AND ? AND e.lon BETWEEN ? AND ? " +
                    "ORDER BY (e.lat - ${point.latitude}) * (e.lat - ${point.latitude}) + (e.lon - ${point.longitude}) * (e.lon - ${point.longitude}) * $squash LIMIT $limit",
                arrayOf(region, "${point.latitude - dLat}", "${point.latitude + dLat}", "${point.longitude - dLon}", "${point.longitude + dLon}"),
            ).use(::entries)
        }.sortedBy { it.point.distanceTo(point) }.take(limit)
    }

    private fun entries(rows: Cursor): List<SearchEntry> = buildList {
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
        const val LOOSE_CANDIDATES = 50
        const val NOTABLE_CANDIDATES = 40
        const val MAX_SIMILAR_WORDS = 12
        const val AKA_SEPARATOR = "\u001f"
        const val METRES_PER_DEGREE = 111_195.0
        const val COLUMNS = (360 / SearchPackManifest.CELL_SIZE).toLong()
    }
}

package app.nullpointr.foxnav.server.reports

import app.nullpointr.foxnav.shared.convoy.secureRandomBytes
import app.nullpointr.foxnav.shared.reports.NewReport
import app.nullpointr.foxnav.shared.reports.Report
import app.nullpointr.foxnav.shared.reports.ReportFeed
import app.nullpointr.foxnav.shared.reports.ReportType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The current reports (protocol/SPEC.md §4), drivers' and official ones, in memory. Drivers'
 * reports are also kept in [snapshot], so a restart doesn't lose them; official ones come back
 * with the next fetch from their source. No report carries anything about who made it.
 */
class ReportStore(private val snapshot: Path? = null, private val clock: () -> Long = System::currentTimeMillis) {
    private class Entry(var report: Report, var expiresMs: Long, var goneVotes: Int = 0)

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Entry>()

    /** Changes with every change to the feed, for ETags. */
    private var version = 0L

    init {
        snapshot?.takeIf { it.exists() }?.let { path ->
            runCatching { ReportFeed.parse(path.readText()) }.getOrNull()?.reports?.forEach { report ->
                val expires = runCatching { Instant.parse(report.expires).toEpochMilli() }.getOrNull() ?: return@forEach
                if (expires > clock()) entries[report.id] = Entry(report, expires)
            }
        }
    }

    /** The feed as it stands, and its version. */
    suspend fun feed(): Pair<Long, ReportFeed> = mutex.withLock {
        purge()
        version to ReportFeed(reports = entries.values.map { it.report.copy(expires = iso(it.expiresMs)) })
    }

    /** Adds a driver's report; null if it isn't valid. */
    suspend fun add(new: NewReport): String? {
        val lifetime = LIFETIMES_MS[new.type] ?: return null
        if (new.lat !in -90.0..90.0 || new.lon !in -180.0..180.0 || new.bearing?.let { it !in 0.0..360.0 } == true) return null
        return mutex.withLock {
            val now = clock()
            var id: String
            do id = Base64.getUrlEncoder().withoutPadding().encodeToString(secureRandomBytes(9)) while (id in entries)
            // About a metre and a tenth of a degree: more says nothing about the road, only about the phone.
            val report = Report(
                id = id,
                type = new.type,
                lat = round(new.lat, 5),
                lon = round(new.lon, 5),
                bearing = new.bearing?.let { round(it % 360, 1) },
                reported = iso(now),
                expires = iso(now + lifetime),
            )
            entries[id] = Entry(report, now + lifetime)
            changed()
            id
        }
    }

    /**
     * A driver passing by says whether it's still there. Yes keeps it for at least half its
     * type's lifetime from now, but no report lasts more than three lifetimes in all. Once noes
     * outnumber yeses (and there are two), a driver's report goes; official reports stay until
     * their source ends them. Returns false for unknown or expired reports.
     */
    suspend fun vote(id: String, stillThere: Boolean): Boolean = mutex.withLock {
        purge()
        val entry = entries[id] ?: return@withLock false
        val lifetime = LIFETIMES_MS.getValue(entry.report.type)
        if (stillThere) {
            entry.report = entry.report.copy(confirmations = entry.report.confirmations + 1)
            entry.expiresMs = maxOf(entry.expiresMs, minOf(clock() + lifetime / 2, entry.report.reportedMs() + lifetime * 3))
        } else {
            entry.goneVotes++
            if (entry.report.source == null && entry.goneVotes >= 2 && entry.goneVotes > entry.report.confirmations) entries.remove(id)
        }
        changed()
        true
    }

    /** Replaces every report from [source] with [reports] and when each expires: the latest fetch from an official feed. */
    suspend fun replaceOfficial(source: String, reports: List<Pair<Report, Long>>) = mutex.withLock {
        val before = entries.values.filter { it.report.source?.name == source }.associateBy { it.report.id }
        entries.values.removeAll { it.report.source?.name == source }
        for ((report, expires) in reports) {
            // Drivers' confirmations carry over; the source's end time wins over theirs.
            val previous = before[report.id]
            entries[report.id] = Entry(report.copy(confirmations = previous?.report?.confirmations ?: 0), expires, previous?.goneVotes ?: 0)
        }
        val after = entries.values.filter { it.report.source?.name == source }.associateBy { it.report.id }
        if (before.keys != after.keys || before.any { (id, entry) -> entry.report != after.getValue(id).report || entry.expiresMs != after.getValue(id).expiresMs }) changed()
    }

    private fun purge() {
        val now = clock()
        if (entries.values.removeAll { it.expiresMs <= now }) changed()
    }

    private fun changed() {
        version++
        save()
    }

    /** Drivers' reports only: official ones come back with the next fetch. */
    private fun save() {
        val path = snapshot ?: return
        val drivers = entries.values.filter { it.report.source == null }.map { it.report.copy(expires = iso(it.expiresMs)) }
        runCatching {
            Files.createDirectories(path.toAbsolutePath().parent)
            val temporary = path.resolveSibling(path.fileName.toString() + ".part")
            temporary.writeText(ReportFeed(reports = drivers).toJson())
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun Report.reportedMs() = runCatching { Instant.parse(reported).toEpochMilli() }.getOrDefault(clock())

    companion object {
        /** How long each type of report lasts without anyone confirming it. */
        val LIFETIMES_MS: Map<String, Long> = mapOf(
            ReportType.POLICE to 60.minutes,
            ReportType.SPEED_CHECK to 60.minutes,
            ReportType.ACCIDENT to 90.minutes,
            ReportType.HAZARD to 60.minutes,
            ReportType.STOPPED_VEHICLE to 45.minutes,
            ReportType.ROADWORKS to 24.hours,
            ReportType.SLIPPERY_ROAD to 4.hours,
            ReportType.ANIMAL to 30.minutes,
            ReportType.ROAD_CLOSED to 6.hours,
        ).mapValues { it.value.inWholeMilliseconds }

        fun iso(ms: Long): String = Instant.ofEpochSecond(ms / 1000).toString()

        private fun round(value: Double, decimals: Int): Double = java.math.BigDecimal(value).setScale(decimals, java.math.RoundingMode.HALF_UP).toDouble()
    }
}

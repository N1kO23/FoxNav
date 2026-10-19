package app.nullpointr.foxnav.shared.convoy

import app.nullpointr.foxnav.protocol.convoy.v0.Card
import app.nullpointr.foxnav.protocol.convoy.v0.KeyRotation
import app.nullpointr.foxnav.protocol.convoy.v0.NowPlaying
import app.nullpointr.foxnav.protocol.convoy.v0.OwnerStatement
import app.nullpointr.foxnav.protocol.convoy.v0.Position
import app.nullpointr.foxnav.protocol.convoy.v0.Profile
import app.nullpointr.foxnav.protocol.convoy.v0.RouteShare
import app.nullpointr.foxnav.protocol.convoy.v0.Waypoint
import app.nullpointr.foxnav.protocol.convoy.v0.WrappedKey
import app.nullpointr.foxnav.shared.geo.GeoPoint
import okio.ByteString.Companion.toByteString
import kotlin.math.roundToInt

/** What we share with the convoy. The session sends each part as a frame of its own (spec §2.4). */
data class MemberState(val profile: Profile, val fix: Fix? = null, val route: RouteShare? = null, val nowPlaying: NowPlaying? = null)

/** Our state as the others see it. [fix] is null until we have a GPS fix. */
fun memberState(profile: Profile, fix: Fix?, route: RouteShare? = null, nowPlaying: NowPlaying? = null) = MemberState(profile, fix, route, nowPlaying)

/** Frame contents, shortened to fit [ConvoyFrames.MAX_CONTENT] where they hold text (spec §2.4). */
internal object FrameContents {
    fun card(identity: Identity, profile: Profile): ByteArray {
        var shown = profile
        while (true) {
            val bytes = Card.ADAPTER.encode(Card(signing_key = identity.signingKey.toByteString(), exchange_key = identity.exchangeKey.toByteString(), profile = shown))
            val excess = bytes.size - ConvoyFrames.MAX_CONTENT
            if (excess <= 0) return bytes
            val car = shown.car
            val texts = listOf(shown.display_name, car?.make.orEmpty(), car?.model.orEmpty())
            val (longest, shorter) = shortenLongest(texts, excess)
            shown = when (longest) {
                0 -> shown.copy(display_name = shorter)
                1 -> shown.copy(car = car?.copy(make = shorter))
                else -> shown.copy(car = car?.copy(model = shorter))
            }
        }
    }

    /** As many waypoints as fit, the destination (last) first to stay; then shorter names. */
    fun route(route: RouteShare?): ByteArray {
        var waypoints = route?.waypoints.orEmpty()
        while (true) {
            val bytes = RouteShare.ADAPTER.encode(RouteShare(waypoints = waypoints))
            val excess = bytes.size - ConvoyFrames.MAX_CONTENT
            if (excess <= 0) return bytes
            waypoints = if (waypoints.size > 1) {
                waypoints.drop(1)
            } else {
                waypoints.map { it.copy(name = it.name.shortenedBy(excess)) }
            }
        }
    }

    fun song(nowPlaying: NowPlaying?): ByteArray {
        var shown = nowPlaying ?: NowPlaying()
        while (true) {
            val bytes = NowPlaying.ADAPTER.encode(shown)
            val excess = bytes.size - ConvoyFrames.MAX_CONTENT
            if (excess <= 0) return bytes
            val texts = listOf(shown.title, shown.artist, shown.app)
            val (longest, shorter) = shortenLongest(texts, excess)
            shown = when (longest) {
                0 -> shown.copy(title = shorter)
                1 -> shown.copy(artist = shorter)
                else -> shown.copy(app = shorter)
            }
        }
    }

    /** A key rotation in as few frames as hold all of [keys]: a convoy of two takes one frame. */
    fun keyRotation(epoch: Int, removed: List<Int>, keys: List<WrappedKey>): List<ByteArray> {
        fun encode(part: Int, parts: Int, some: List<WrappedKey>) =
            OwnerStatement.ADAPTER.encode(OwnerStatement(key_rotation = KeyRotation(epoch = epoch, removed = removed, part = part, parts = parts, keys = some)))
        val chunks = mutableListOf<List<WrappedKey>>()
        var current = emptyList<WrappedKey>()
        for (key in keys) {
            // Sized with part numbers as large as they'll ever get here.
            current = if (current.isNotEmpty() && encode(MAX_PARTS, MAX_PARTS, current + key).size > ConvoyFrames.MAX_CONTENT) {
                chunks += current
                listOf(key)
            } else {
                current + key
            }
        }
        chunks += current
        return chunks.mapIndexed { index, some -> encode(index + 1, chunks.size, some) }
    }

    private const val MAX_PARTS = 127
}

fun Position.toFix() = Fix(GeoPoint(lat_e7 / 1e7, lon_e7 / 1e7), speed_cms / 100.0, heading_deg.toDouble(), fix_time_ms)

fun Fix.toPosition() = Position(
    lat_e7 = (point.latitude * 1e7).roundToInt(),
    lon_e7 = (point.longitude * 1e7).roundToInt(),
    speed_cms = (speedMps * 100).roundToInt().coerceAtLeast(0),
    heading_deg = ((headingDeg % 360 + 360) % 360).roundToInt() % 360,
    fix_time_ms = timeMs,
)

fun GeoPoint.toWaypoint(name: String = "") = Waypoint(lat_e7 = (latitude * 1e7).roundToInt(), lon_e7 = (longitude * 1e7).roundToInt(), name = name)

fun Waypoint.toGeoPoint() = GeoPoint(lat_e7 / 1e7, lon_e7 / 1e7)

private fun String.utf8Size(): Int = encodeToByteArray().size

/**
 * Which of [texts] to shorten, and to what: the longest, by [excess] bytes but not below the next
 * longest, so long texts all lose a little rather than one losing everything.
 */
private fun shortenLongest(texts: List<String>, excess: Int): Pair<Int, String> {
    val sizes = texts.map { it.utf8Size() }
    val longest = sizes.indices.maxBy { sizes[it] }
    val next = sizes.filterIndexed { index, _ -> index != longest }.maxOrNull() ?: 0
    return longest to texts[longest].shortenedBy(excess.coerceAtMost(sizes[longest] - next).coerceAtLeast(1))
}

/** At least [bytes] bytes shorter in UTF-8 (or empty), cut between characters. */
private fun String.shortenedBy(bytes: Int): String {
    val target = utf8Size() - bytes
    var end = length
    while (end > 0 && substring(0, end).utf8Size() > target) {
        end--
        if (end > 0 && this[end - 1].isHighSurrogate()) end--
    }
    return substring(0, end).trimEnd()
}

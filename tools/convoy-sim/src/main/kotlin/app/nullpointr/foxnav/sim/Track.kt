package app.nullpointr.foxnav.sim

import app.nullpointr.foxnav.shared.geo.GeoPoint
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Where a bot is and which way it faces. */
data class Pose(val point: GeoPoint, val headingDeg: Double)

/**
 * A road to drive along, there and back again forever, so bots keep moving however long a test
 * runs.
 */
class Track(private val points: List<GeoPoint>) {
    private val cumulative = DoubleArray(points.size)

    /** Length one way, in metres. */
    val length: Double

    init {
        require(points.size >= 2) { "A track needs at least two points" }
        for (index in 1 until points.size) cumulative[index] = cumulative[index - 1] + points[index - 1].distanceTo(points[index])
        length = cumulative.last()
        require(length > 0) { "The track has no length" }
    }

    /** The pose [distance] metres from the start, driving to the end and back. */
    fun poseAt(distance: Double): Pose {
        val lap = 2 * length
        val along = ((distance % lap) + lap) % lap
        return if (along <= length) poseOneWay(along, backwards = false) else poseOneWay(lap - along, backwards = true)
    }

    private fun poseOneWay(along: Double, backwards: Boolean): Pose {
        // The segment containing `along`: the last vertex at or before it.
        var index = cumulative.binarySearch(along).let { if (it >= 0) it else -it - 2 }.coerceIn(0, points.size - 2)
        while (index < points.size - 2 && cumulative[index + 1] == cumulative[index]) index++ // skip repeated points
        val start = points[index]
        val end = points[index + 1]
        val segment = cumulative[index + 1] - cumulative[index]
        val t = if (segment == 0.0) 0.0 else ((along - cumulative[index]) / segment).coerceIn(0.0, 1.0)
        val point = GeoPoint(start.latitude + (end.latitude - start.latitude) * t, start.longitude + (end.longitude - start.longitude) * t)
        val heading = if (backwards) end.bearingTo(start) else start.bearingTo(end)
        return Pose(point, heading)
    }

    companion object {
        /** The track points (or, without any, route points) of a GPX file. */
        fun fromGpx(file: File): Track {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            fun points(tag: String) = document.getElementsByTagName(tag).let { nodes ->
                (0 until nodes.length).map { index ->
                    val element = nodes.item(index) as Element
                    GeoPoint(element.getAttribute("lat").toDouble(), element.getAttribute("lon").toDouble())
                }
            }
            return Track(points("trkpt").ifEmpty { points("rtept") })
        }
    }
}

/** Decodes an encoded polyline, as Valhalla returns route shapes (precision 6). */
fun decodePolyline(encoded: String, precision: Int = 6): List<GeoPoint> {
    val factor = Math.pow(10.0, precision.toDouble())
    val points = ArrayList<GeoPoint>()
    var index = 0
    var latitude = 0L
    var longitude = 0L
    fun next(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val byte = encoded[index++].code - 63
            result = result or ((byte and 0x1F).toLong() shl shift)
            shift += 5
            if (byte < 0x20) break
        }
        return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
    }
    while (index < encoded.length) {
        latitude += next()
        longitude += next()
        points += GeoPoint(latitude / factor, longitude / factor)
    }
    return points
}

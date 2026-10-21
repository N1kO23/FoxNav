package app.nullpointr.foxnav.shared.safety

import app.nullpointr.foxnav.shared.convoy.Fix
import app.nullpointr.foxnav.shared.convoy.project
import app.nullpointr.foxnav.shared.geo.GeoPoint

/**
 * How far we are through an average speed section, and our average over it so far: distance along
 * the section over the time since entering it, as the cameras at its ends measure it. Null until
 * there's enough of the section behind us for the average to mean something.
 */
data class SectionProgress(val section: Camera, val travelledM: Double, val lengthM: Double, val averageMps: Double?) {
    val remainingM: Double get() = (lengthM - travelledM).coerceAtLeast(0.0)
}

/**
 * Follows the car through average speed sections (cameras of kind [Camera.AVERAGE_SPEED]): from
 * entering one near its start, heading the way it checks, until its end, or until the car turns
 * off it. The average is the driver's own, for the screen: nothing keeps or sends it.
 */
class SectionWatch(cameras: List<Camera>) {
    private val sections = cameras.mapNotNull { camera ->
        if (camera.kind != Camera.AVERAGE_SPEED) return@mapNotNull null
        val line = camera.line.mapNotNull { if (it.size >= 2) GeoPoint(it[1], it[0]) else null }
        if (line.size < 2) null else Section(camera, line, line.zipWithNext { a, b -> a.distanceTo(b) }.sum())
    }

    private var current: Entered? = null

    /** Where we are now, after [fix]. */
    fun update(fix: Fix): SectionProgress? {
        current = current?.let { entered -> follow(entered, fix) } ?: enter(fix)
        return current?.let { entered ->
            val along = entered.lastAlongM - entered.startAlongM
            val seconds = (entered.lastTimeMs - entered.startTimeMs) / 1000.0
            SectionProgress(
                section = entered.section.camera,
                travelledM = entered.lastAlongM,
                lengthM = entered.section.lengthM,
                averageMps = if (seconds >= MIN_AVERAGE_S && along >= MIN_AVERAGE_M) along / seconds else null,
            )
        }
    }

    private fun enter(fix: Fix): Entered? {
        if (fix.speedMps < MIN_SPEED_MPS) return null
        for (section in sections) {
            if (fix.point.distanceTo(section.line.first()) > ENTER_M) continue
            val at = project(section.line, fix.point) ?: continue
            if (at.offM <= ON_SECTION_M && at.alongM <= ENTER_M && angleBetween(at.bearingDeg, fix.headingDeg) <= DIRECTION_TOLERANCE_DEG) {
                return Entered(section, at.alongM, fix.timeMs, at.alongM, fix.timeMs, offFixes = 0)
            }
        }
        return null
    }

    /** The section we're in after [fix], or null once we've left it. */
    private fun follow(entered: Entered, fix: Fix): Entered? {
        val at = project(entered.section.line, fix.point) ?: return null
        if (at.alongM >= entered.section.lengthM - END_M) return null // through: the last camera is behind us
        if (at.offM > ON_SECTION_M || at.alongM < entered.lastAlongM - BACK_M) {
            // A fix or two off the line is GPS; more is a turn off the section.
            return if (entered.offFixes + 1 >= OFF_FIXES) null else entered.copy(offFixes = entered.offFixes + 1)
        }
        return entered.copy(lastAlongM = maxOf(entered.lastAlongM, at.alongM), lastTimeMs = fix.timeMs, offFixes = 0)
    }

    private class Section(val camera: Camera, val line: List<GeoPoint>, val lengthM: Double)

    private data class Entered(
        val section: Section,
        val startAlongM: Double,
        val startTimeMs: Long,
        val lastAlongM: Double,
        val lastTimeMs: Long,
        val offFixes: Int,
    )

    private companion object {
        /** Entering: this close to the first camera, on the line. */
        const val ENTER_M = 60.0
        const val ON_SECTION_M = 40.0
        /** Through once this close to the end. */
        const val END_M = 15.0
        /** Further back along the line than this is turning round. */
        const val BACK_M = 50.0
        const val OFF_FIXES = 3
        const val MIN_AVERAGE_S = 10.0
        const val MIN_AVERAGE_M = 100.0
    }
}

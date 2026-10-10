package app.nullpointr.foxnav.shared.reports

import app.nullpointr.foxnav.shared.convoy.Fix
import app.nullpointr.foxnav.shared.geo.GeoPoint
import app.nullpointr.foxnav.shared.safety.AheadWatch
import app.nullpointr.foxnav.shared.safety.MIN_SPEED_MPS
import app.nullpointr.foxnav.shared.safety.angleBetween

data class ReportAhead(val report: Report, val distanceM: Double)

/** Finds the next report on our road, the way [app.nullpointr.foxnav.shared.safety.CameraWatch] finds cameras. */
class ReportWatch(reports: List<Report>) {
    private val watch = AheadWatch(reports, pointOf = Report::point, concerns = { report, heading -> report.concerns(heading) })

    fun ahead(me: Fix, route: List<GeoPoint>? = null): ReportAhead? =
        watch.ahead(me, route)?.let { (report, distance) -> ReportAhead(report, distance) }
}

/**
 * Notices when we drive past a report we were warned about: the moment to ask whether it's still
 * there. A report counts as passed once we've been right by it and it's behind us while we're
 * moving; one we turned away from before reaching it doesn't. Each report comes up once.
 */
class PassedReports {
    private class Approach(val report: Report, var closestM: Double)

    private val approaching = LinkedHashMap<String, Approach>()
    private val done = HashSet<String>()

    /** Call with every fix and what's ahead then; returns a report we just passed, if any. */
    fun next(me: Fix, ahead: ReportAhead?): Report? {
        if (ahead != null && ahead.distanceM <= NEAR_M && ahead.report.id !in done) {
            approaching.getOrPut(ahead.report.id) { Approach(ahead.report, ahead.distanceM) }
        }
        val iterator = approaching.values.iterator()
        while (iterator.hasNext()) {
            val approach = iterator.next()
            val distance = me.point.distanceTo(approach.report.point)
            approach.closestM = minOf(approach.closestM, distance)
            val behind = angleBetween(me.point.bearingTo(approach.report.point), me.headingDeg) > BEHIND_DEG
            when {
                distance > FORGET_M -> iterator.remove()
                me.speedMps >= MIN_SPEED_MPS && approach.closestM <= BY_IT_M && behind -> {
                    iterator.remove()
                    done += approach.report.id
                    return approach.report
                }
            }
        }
        return null
    }

    /** Never ask about this report, e.g. because we made it. */
    fun skip(id: String) {
        done += id
        approaching.remove(id)
    }

    private companion object {
        /** Close enough to be noticed on the way. */
        const val NEAR_M = 300.0

        /** Right by it: on its road, allowing for GPS and the other lane. */
        const val BY_IT_M = 60.0
        const val FORGET_M = 500.0
        const val BEHIND_DEG = 100.0
    }
}

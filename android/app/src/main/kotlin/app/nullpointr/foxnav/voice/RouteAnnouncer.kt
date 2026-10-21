package app.nullpointr.foxnav.voice

import app.nullpointr.foxnav.settings.Settings
import app.nullpointr.foxnav.shared.navigation.Cue
import app.nullpointr.foxnav.shared.navigation.GuidanceTiming
import com.stadiamaps.ferrostar.core.FerrostarCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import uniffi.ferrostar.RouteDeviation
import uniffi.ferrostar.TripState

/**
 * Says a trip's instructions as the car nears each manoeuvre, timed for the speed it's going
 * ([GuidanceTiming]) rather than where Valhalla placed them.
 */
class RouteAnnouncer(private val voice: GuidanceVoice, private val settings: StateFlow<Settings>) {
    private val timing = GuidanceTiming()

    /** Follows [core]'s trips until the job is cancelled. */
    fun follow(core: FerrostarCore, scope: CoroutineScope): Job = scope.launch {
        core.state.collect { update(it.tripState) }
    }

    private fun update(trip: TripState) {
        val navigating = trip as? TripState.Navigating ?: return
        // Off the route, or the wrong way along it: a new route is on its way, nothing to say about this one.
        if (navigating.deviation is RouteDeviation.Deviation) return
        val step = navigating.remainingSteps.firstOrNull() ?: return
        val cues = step.spokenInstructions.map { Cue(it.utteranceId.toString(), it.text, it.triggerDistanceBeforeManeuver) }
        val location = navigating.userLocation
        val current = settings.value
        val announcement = timing.next(
            cues,
            distanceM = navigating.progress.distanceToNextManeuver,
            travelledM = navigating.summary.snappedDistanceTraveled,
            timeMs = location.timestamp.toEpochMilli(),
            speedMps = location.speed?.value,
            units = current.units,
            speechRate = current.speechRate.toDouble(),
        ) ?: return
        // What the step's banner shows coming up: the manoeuvre its instructions are about.
        val maneuver = step.visualInstructions.firstOrNull()?.primaryContent
            ?.let { content -> content.maneuverType?.name?.let { it to content.maneuverModifier?.name } }
        voice.announce(announcement.cue.text, maneuver, announcement.heardAtM)
    }
}

package app.nullpointr.foxnav.voice

import app.nullpointr.foxnav.settings.Settings
import app.nullpointr.foxnav.shared.navigation.Cue
import app.nullpointr.foxnav.shared.navigation.GuidanceTiming
import app.nullpointr.foxnav.shared.voice.Phrases
import app.nullpointr.foxnav.shared.voice.chainsNext
import com.stadiamaps.ferrostar.core.FerrostarCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import uniffi.ferrostar.RouteDeviation
import uniffi.ferrostar.RouteStep
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
        val steps = navigating.remainingSteps
        val text = announcement.cue.text
        voice.announce(text, maneuverAfter(steps, 0), announcement.heardAtM, then = if (chainsNext(text)) maneuverAfter(steps, 1) else null)
    }

    /**
     * The manoeuvre at the end of [steps]' [index]th, as its banner shows it coming up. A
     * roundabout's exit is on the step that goes round it, the next.
     */
    private fun maneuverAfter(steps: List<RouteStep>, index: Int): Phrases.Maneuver? {
        val content = steps.getOrNull(index)?.visualInstructions?.firstOrNull()?.primaryContent ?: return null
        val type = content.maneuverType?.name ?: return null
        return Phrases.Maneuver(type, content.maneuverModifier?.name, steps.getOrNull(index + 1)?.roundaboutExitNumber?.toInt())
    }
}

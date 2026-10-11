@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.nullpointr.foxnav.convoy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.core.content.ContextCompat
import app.nullpointr.foxnav.BuildConfig
import app.nullpointr.foxnav.data.fetchDiscovery
import app.nullpointr.foxnav.data.normalizeServerUrl
import app.nullpointr.foxnav.protocol.convoy.v0.QuickMessage
import app.nullpointr.foxnav.protocol.convoy.v0.RouteShare
import app.nullpointr.foxnav.protocol.convoy.v0.Waypoint
import app.nullpointr.foxnav.shared.convoy.ConvoyEvent
import app.nullpointr.foxnav.shared.convoy.ConvoyMember
import app.nullpointr.foxnav.shared.convoy.ConvoySession
import app.nullpointr.foxnav.shared.convoy.ConvoyStatus
import app.nullpointr.foxnav.shared.convoy.EndReason
import app.nullpointr.foxnav.shared.convoy.Fix
import app.nullpointr.foxnav.shared.convoy.Invite
import app.nullpointr.foxnav.shared.convoy.fixOf
import app.nullpointr.foxnav.shared.convoy.memberState
import app.nullpointr.foxnav.shared.convoy.toWaypoint
import app.nullpointr.foxnav.shared.discovery.Feature
import app.nullpointr.foxnav.shared.geo.GeoPoint
import com.stadiamaps.ferrostar.core.AndroidTtsObserver
import com.stadiamaps.ferrostar.core.location.NavigationLocationProviding
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uniffi.ferrostar.SpokenInstruction
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import app.nullpointr.foxnav.protocol.convoy.v0.Profile
import app.nullpointr.foxnav.settings.Settings
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** A convoy we're in. */
class ActiveConvoy(val session: ConvoySession, val serverUrl: String)

/** Something to show about the convoy for a few seconds: a quick message, a regroup point, someone leaving. */
data class ConvoyNotice(val text: String, val colorRgb: Int? = null, val warning: Boolean = false, val id: UUID = UUID.randomUUID())

/** A meeting point someone dropped for the convoy. */
data class RegroupPoint(val point: GeoPoint, val name: String, val by: String)

/**
 * The app's convoy, at most one at a time. It owns the session, feeds it our position from the
 * location provider (live GPS, or the simulated drive) and our profile, and says quick messages
 * out loud. [ConvoyService] keeps it all running with the screen off.
 */
class ConvoyController(
    private val context: Context,
    private val locationProvider: NavigationLocationProviding,
    val profiles: ProfileStore,
    private val voice: AndroidTtsObserver,
    /** What the car's music plays: shared if [settings] say so, and played along to. */
    val music: MusicSessions,
    private val settings: StateFlow<Settings>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = HttpClient(OkHttp) { install(WebSockets) }

    private val _active = MutableStateFlow<ActiveConvoy?>(null)
    val active: StateFlow<ActiveConvoy?> = _active.asStateFlow()

    private val _myFix = MutableStateFlow<Fix?>(null)

    /** Our latest position while in a convoy. */
    val myFix: StateFlow<Fix?> = _myFix.asStateFlow()

    private val myDestination = MutableStateFlow<Waypoint?>(null)

    private val _myRole = MutableStateFlow(Profile.Role.ROLE_UNSPECIFIED)

    /** Our part in this convoy: leading, at the back, or just driving. It ends with the convoy. */
    val myRole: StateFlow<Profile.Role> = _myRole.asStateFlow()

    private val _playingAlongWith = MutableStateFlow<Long?>(null)

    /** The member whose music we follow, quietly, song by song; null when we don't. */
    val playingAlongWith: StateFlow<Long?> = _playingAlongWith.asStateFlow()

    private val _regroupPoint = MutableStateFlow<RegroupPoint?>(null)
    val regroupPoint: StateFlow<RegroupPoint?> = _regroupPoint.asStateFlow()

    private val _notices = MutableSharedFlow<ConvoyNotice>(extraBufferCapacity = 8)
    val notices: SharedFlow<ConvoyNotice> = _notices.asSharedFlow()

    // Display names by member ID, kept after people leave so "Aki left" still has a name.
    private val names = ConcurrentHashMap<Long, String>()
    private val colors = ConcurrentHashMap<Long, Int>()
    private val awaitingName = ConcurrentHashMap.newKeySet<Long>()

    /** Starts a new convoy on the server at [serverUrl] (as the user typed it). */
    suspend fun create(serverUrl: String): Result<Unit> = normalizeServerUrl(serverUrl).let { url -> begin(url, ConvoySession.Start.Create(url)) }

    suspend fun join(invite: Invite): Result<Unit> = begin(invite.server, ConvoySession.Start.Join(invite))

    private suspend fun begin(serverUrl: String, start: ConvoySession.Start): Result<Unit> = runCatching {
        _active.value?.session?.leave()
        val document = fetchDiscovery(serverUrl)
        val realtime = document.realtime
        require(realtime != null && Feature.CONVOY in document.features) { "${document.name} doesn't host convoys" }
        val session = ConvoySession(
            http = http,
            realtimeUrl = realtime,
            start = start,
            scope = scope,
            clock = System::currentTimeMillis,
            clientName = "FoxNav Android ${BuildConfig.VERSION_NAME}",
        )
        val active = ActiveConvoy(session, serverUrl)
        _active.value = active
        scope.launch { run(active) }
        // The service keeps the convoy alive with the screen off. Android only allows a location
        // service with the location permission; without it, the convoy lasts while the app is open.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            ConvoyService.start(context)
        }
    }

    /** Leaves for good. */
    suspend fun leave() {
        _active.value?.session?.leave()
    }

    fun sendQuickMessage(kind: QuickMessage.Kind) {
        val session = _active.value?.session ?: return
        session.sendQuickMessage(kind)
        notify(ConvoyNotice("Sent: ${kind.label}"))
    }

    /** Asks everyone to meet where we are now. */
    fun dropRegroupPoint() {
        val session = _active.value?.session ?: return
        val here = _myFix.value?.point ?: return notify(ConvoyNotice("Waiting for your location"))
        session.dropRegroupPin(here, REGROUP_NAME)
        _regroupPoint.value = RegroupPoint(here, REGROUP_NAME, by = "you")
        notify(ConvoyNotice("Regroup point sent"))
    }

    fun clearRegroupPoint() {
        _regroupPoint.value = null
    }

    /** Shares where we're navigating to, so the others can follow; null when we stop. */
    fun shareDestination(point: GeoPoint?, name: String = "") {
        myDestination.value = point?.toWaypoint(name)
    }

    fun nameOf(memberId: Long): String = names[memberId]?.takeIf { it.isNotBlank() } ?: "A driver"

    fun setRole(role: Profile.Role) {
        _myRole.value = role
    }

    /**
     * Plays what [memberId] is playing, in our own music app. Where FoxNav can steer the music
     * app quietly, it keeps following their songs until [stopPlayingAlong]; otherwise the music
     * app opens for this one song.
     */
    fun playAlong(memberId: Long) {
        val track = _active.value?.session?.members?.value?.get(memberId)?.nowPlaying ?: return
        when (music.playAlong(track)) {
            PlayAlong.QUIET -> {
                _playingAlongWith.value = memberId
                notify(ConvoyNotice("Playing along with ${nameOf(memberId)}", colors[memberId]))
            }
            PlayAlong.OPENED -> _playingAlongWith.value = null
            PlayAlong.FAILED -> notify(ConvoyNotice("No music app on this phone takes a song to play", warning = true))
        }
    }

    fun stopPlayingAlong() {
        _playingAlongWith.value = null
    }

    private suspend fun run(active: ActiveConvoy) {
        val session = active.session
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = NetworkWatch(session)
        connectivity.registerDefaultNetworkCallback(network)
        try {
            coroutineScope {
                val workers = listOf(
                    // Subscribed before the session starts, so no event is missed.
                    launch(start = CoroutineStart.UNDISPATCHED) { session.events.collect(::onEvent) },
                    launch { session.members.collect(::rememberMembers) },
                    launch { publishOurState(session) },
                    launch { followTheirMusic(session) },
                )
                session.start()
                val ended = session.status.filterIsInstance<ConvoyStatus.Ended>().first()
                workers.forEach { it.cancel() }
                endMessage(ended.reason)?.let { notify(ConvoyNotice(it, warning = true), speak = true) }
            }
        } finally {
            connectivity.unregisterNetworkCallback(network)
        }
        if (_active.compareAndSet(active, null)) {
            _myFix.value = null
            _regroupPoint.value = null
            _myRole.value = Profile.Role.ROLE_UNSPECIFIED
            _playingAlongWith.value = null
            names.clear()
            colors.clear()
            awaitingName.clear()
        }
    }

    /** Their next song, quietly in our music app too, while we play along with them. */
    private suspend fun followTheirMusic(session: ConvoySession) {
        combine(_playingAlongWith, session.members) { id, members -> id?.let { members[it]?.nowPlaying } }
            .distinctUntilChanged()
            .drop(1) // the song we just started
            .filterNotNull()
            .collect { track -> if (music.playAlong(track) != PlayAlong.QUIET) _playingAlongWith.value = null }
    }

    private suspend fun publishOurState(session: ConvoySession) = coroutineScope {
        launch {
            var previous: Fix? = null
            locationProvider.locationUpdates(LOCATION_INTERVAL_MS)
                .catch { Log.w(TAG, "No location for the convoy", it) } // e.g. no permission: we still share our profile
                .collect { location ->
                    val fix = fixOf(
                        point = GeoPoint(location.latitude, location.longitude),
                        timeMs = location.time,
                        speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
                        headingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
                        previous = previous,
                    )
                    previous = fix
                    _myFix.value = fix
                }
        }
        // Only while the driver shares it: the music apps' sessions aren't even looked at otherwise.
        val playing = settings.map { it.shareNowPlaying }.distinctUntilChanged().flatMapLatest { share -> if (share) music.nowPlaying() else flowOf(null) }
        combine(profiles.profile, _myFix, myDestination, _myRole, playing) { profile, fix, destination, role, track ->
            memberState(profile.toProtocol().copy(role = role), fix, destination?.let { RouteShare(waypoints = listOf(it)) }, track)
        }.collect(session::updateState)
    }

    private fun rememberMembers(members: Map<Long, ConvoyMember>) {
        for ((id, member) in members) {
            val profile = member.profile ?: continue
            names[id] = profile.display_name
            colors[id] = profile.car?.color_rgb ?: ConvoyPalette.first()
            if (awaitingName.remove(id)) notify(ConvoyNotice("${nameOf(id)} joined the convoy", colors[id]))
        }
    }

    private fun onEvent(event: ConvoyEvent) {
        when (event) {
            is ConvoyEvent.QuickMessageReceived -> notify(
                ConvoyNotice("${nameOf(event.from)}: ${event.kind.phrase}", colors[event.from], warning = event.kind.isWarning),
                speak = true,
            )
            is ConvoyEvent.RegroupPinDropped -> {
                val name = nameOf(event.from)
                _regroupPoint.value = RegroupPoint(event.point, event.name.ifBlank { REGROUP_NAME }, by = name)
                notify(ConvoyNotice("$name set a regroup point", colors[event.from]), speak = true)
            }
            // Their name arrives with their first state.
            is ConvoyEvent.MemberJoined -> awaitingName += event.memberId
            is ConvoyEvent.MemberLeft -> {
                awaitingName -= event.memberId
                val text = if (event.removed) "${nameOf(event.memberId)} was removed. Old invite links stopped working." else "${nameOf(event.memberId)} left"
                notify(ConvoyNotice(text, colors[event.memberId]))
            }
            is ConvoyEvent.Refused -> notify(ConvoyNotice("The server turned that down: ${event.message}"))
        }
    }

    private fun notify(notice: ConvoyNotice, speak: Boolean = false) {
        _notices.tryEmit(notice)
        if (speak) voice.onSpokenInstructionTrigger(SpokenInstruction(notice.text, null, 0.0, notice.id))
    }

    private fun endMessage(reason: EndReason): String? = when (reason) {
        EndReason.LEFT, EndReason.CLOSED -> null
        EndReason.REMOVED -> "The convoy's owner removed you"
        EndReason.CONVOY_GONE -> "This convoy has ended"
        EndReason.INVITE_INVALID -> "This invite isn't valid"
        EndReason.CONVOY_FULL -> "This convoy is full"
    }

    /**
     * Out of a tunnel, or from Wi-Fi to mobile data: the connection is gone with the old network,
     * so reconnect at once instead of waiting for the backoff or a timeout.
     */
    private class NetworkWatch(private val session: ConvoySession) : ConnectivityManager.NetworkCallback() {
        @Volatile
        private var current: Network? = null

        @Volatile
        private var lost = false

        override fun onAvailable(network: Network) {
            // The first call reports the network we're on; later ones are changes.
            val previous = current
            if (previous != null && (network != previous || lost)) session.reconnectNow()
            current = network
            lost = false
        }

        override fun onLost(network: Network) {
            lost = true
        }
    }

    private companion object {
        const val TAG = "FoxNavConvoy"
        const val LOCATION_INTERVAL_MS = 1_000L
        const val REGROUP_NAME = "Regroup point"
    }
}

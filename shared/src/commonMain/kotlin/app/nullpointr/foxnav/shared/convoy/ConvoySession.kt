package app.nullpointr.foxnav.shared.convoy

import app.nullpointr.foxnav.protocol.convoy.v0.Channel
import app.nullpointr.foxnav.protocol.convoy.v0.ClientMessage
import app.nullpointr.foxnav.protocol.convoy.v0.ConvoyPayload
import app.nullpointr.foxnav.protocol.convoy.v0.CreateConvoy
import app.nullpointr.foxnav.protocol.convoy.v0.Hello
import app.nullpointr.foxnav.protocol.convoy.v0.JoinConvoy
import app.nullpointr.foxnav.protocol.convoy.v0.Joined
import app.nullpointr.foxnav.protocol.convoy.v0.LeaveConvoy
import app.nullpointr.foxnav.protocol.convoy.v0.MemberState
import app.nullpointr.foxnav.protocol.convoy.v0.Ping
import app.nullpointr.foxnav.protocol.convoy.v0.Position
import app.nullpointr.foxnav.protocol.convoy.v0.Profile
import app.nullpointr.foxnav.protocol.convoy.v0.Publish
import app.nullpointr.foxnav.protocol.convoy.v0.QuickMessage
import app.nullpointr.foxnav.protocol.convoy.v0.RegroupPin
import app.nullpointr.foxnav.protocol.convoy.v0.Relay
import app.nullpointr.foxnav.protocol.convoy.v0.RemoveMember
import app.nullpointr.foxnav.protocol.convoy.v0.Resume
import app.nullpointr.foxnav.protocol.convoy.v0.RouteShare
import app.nullpointr.foxnav.protocol.convoy.v0.ServerMessage
import app.nullpointr.foxnav.protocol.convoy.v0.Waypoint
import app.nullpointr.foxnav.shared.geo.GeoPoint
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import kotlin.concurrent.Volatile
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import app.nullpointr.foxnav.protocol.convoy.v0.Error as ProtocolError
import kotlinx.coroutines.channels.Channel as AsyncChannel

/** Another member of the convoy, as far as we know them. */
data class ConvoyMember(
    val id: Long,
    /** Null until their first state arrives. */
    val profile: Profile? = null,
    val fix: Fix? = null,
    /** The destination and via points they share, if any. */
    val route: RouteShare? = null,
    /** When they last sent their state (our clock, Unix ms); null until the first one. */
    val lastHeardMs: Long? = null,
)

sealed interface ConvoyStatus {
    data object Connecting : ConvoyStatus

    /** In the convoy as member [memberId]. */
    data class Live(val memberId: Long) : ConvoyStatus

    /** The connection dropped and the session is trying again. The server keeps our seat for a few minutes. */
    data class Reconnecting(val attempt: Int) : ConvoyStatus

    /** Over for good. */
    data class Ended(val reason: EndReason) : ConvoyStatus
}

enum class EndReason {
    /** We left. */
    LEFT,

    /** The convoy's owner removed us. */
    REMOVED,

    /** The server doesn't know the convoy: everyone left long ago, or the server restarted. */
    CONVOY_GONE,

    /** The invite's join token is wrong. */
    INVITE_INVALID,

    CONVOY_FULL,

    /** [ConvoySession.close]: we went away without leaving, so the seat stays resumable for a while. */
    CLOSED,
}

sealed interface ConvoyEvent {
    data class MemberJoined(val memberId: Long) : ConvoyEvent

    data class MemberLeft(val memberId: Long, val removed: Boolean) : ConvoyEvent

    data class QuickMessageReceived(val from: Long, val kind: QuickMessage.Kind) : ConvoyEvent

    data class RegroupPinDropped(val from: Long, val point: GeoPoint, val name: String) : ConvoyEvent

    /** The server turned a request down, e.g. too many quick messages in a row. */
    data class Refused(val code: ProtocolError.Code, val message: String) : ConvoyEvent
}

/**
 * One convoy membership (protocol/SPEC.md §2). It connects to the convoy's server, creates or
 * joins the convoy, keeps everyone's latest decrypted state and publishes ours. When the
 * connection drops, it reconnects with backoff and resumes its seat, or joins again with the
 * invite if the seat expired in the meantime.
 *
 * [http] needs Ktor's WebSockets plugin. [realtimeUrl] is the server's `realtime` endpoint from
 * its discovery document. [clock] gives Unix milliseconds.
 */
class ConvoySession(
    private val http: HttpClient,
    private val realtimeUrl: String,
    private val start: Start,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val clientName: String = "FoxNav",
    private val timing: Timing = Timing(),
) {
    sealed interface Start {
        /** A new convoy. [serverUrl] goes into the invite, so it must be how others reach the server. */
        data class Create(val serverUrl: String) : Start

        data class Join(val invite: Invite) : Start
    }

    /** How often the session talks to the server. The defaults follow protocol/SPEC.md §2.1. */
    data class Timing(
        val stateIntervalMs: Long = 1_000,
        /**
         * An unchanged state goes out again this often: the others take silence for a lost
         * signal, and a parked car's GPS may go quiet.
         */
        val stateRefreshMs: Long = 5_000,
        val pingIntervalMs: Long = 15_000,
        /** A connection that delivers nothing for this long counts as dead. */
        val silenceTimeoutMs: Long = 45_000,
        val minBackoffMs: Long = 1_000,
        val maxBackoffMs: Long = 30_000,
        /** Quick messages and pins still unsent after this long are dropped: old news misleads. */
        val maxEventAgeMs: Long = 60_000,
    )

    private val joining = (start as? Start.Join)?.invite
    private val key = joining?.key ?: ConvoyCrypto.newKey()
    private val keys = mapOf(KEY_EPOCH to key)

    // Only the connection loop touches these, one connection at a time.
    private var convoyId = joining?.convoyId
    private var joinToken = joining?.joinToken
    private var resumeToken: ByteArray? = null
    private var everJoined = false

    private val _status = MutableStateFlow<ConvoyStatus>(ConvoyStatus.Connecting)
    val status: StateFlow<ConvoyStatus> = _status.asStateFlow()

    private val _invite = MutableStateFlow(joining)

    /** The invite to share: known from the start when joining, and once the server has created the convoy when creating. */
    val invite: StateFlow<Invite?> = _invite.asStateFlow()

    private val _members = MutableStateFlow<Map<Long, ConvoyMember>>(emptyMap())

    /** Everyone else in the convoy, by member ID. */
    val members: StateFlow<Map<Long, ConvoyMember>> = _members.asStateFlow()

    private val _ownerId = MutableStateFlow<Long?>(null)

    /** The member who may remove others: the creator, and after they leave, whoever has been in the convoy longest. */
    val ownerId: StateFlow<Long?> = _ownerId.asStateFlow()

    private val _events = MutableSharedFlow<ConvoyEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ConvoyEvent> = _events.asSharedFlow()

    private val myState = MutableStateFlow<MemberState?>(null)
    private val outgoingEvents = AsyncChannel<QueuedEvent>(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val commands = AsyncChannel<ClientMessage>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val wake = AsyncChannel<Unit>(AsyncChannel.CONFLATED)

    @Volatile
    private var socket: DefaultClientWebSocketSession? = null

    @Volatile
    private var ended = false

    @Volatile
    private var job: Job? = null

    /** Connects. Call it once, after subscribing to [events]. */
    fun start() {
        if (job == null && !ended) job = scope.launch { connectLoop() }
    }

    /**
     * Our latest state: published right away, then at most once per [Timing.stateIntervalMs],
     * and again every [Timing.stateRefreshMs] while it stays the same.
     */
    fun updateState(state: MemberState) {
        myState.value = state
    }

    fun sendQuickMessage(kind: QuickMessage.Kind) = queueEvent(ConvoyPayload(quick_message = QuickMessage(kind = kind)))

    fun dropRegroupPin(point: GeoPoint, name: String) = queueEvent(ConvoyPayload(regroup_pin = RegroupPin(location = point.toWaypoint(name))))

    /** Asks the server to remove [memberId]. Only the owner may. */
    fun remove(memberId: Long) {
        commands.trySend(ClientMessage(remove_member = RemoveMember(member_id = memberId)))
    }

    /** Skips the wait before the next reconnection attempt, e.g. when the phone has a network again. */
    fun reconnectNow() {
        wake.trySend(Unit)
    }

    /** Leaves the convoy for good: the others see us go and the seat can't be resumed. */
    suspend fun leave() {
        if (ended) return
        ended = true
        socket?.let { open ->
            withTimeoutOrNull(LEAVE_TIMEOUT_MS) {
                try {
                    open.send(ClientMessage(leave_convoy = LeaveConvoy()))
                    open.close(CloseReason(CloseReason.Codes.NORMAL, "Left the convoy"))
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    // The connection broke meanwhile: the server frees the seat once it expires.
                }
            }
        }
        end(EndReason.LEFT)
        job?.cancel()
    }

    /** Disconnects without leaving, e.g. when the app is closed. The seat stays resumable for a few minutes. */
    fun close() {
        if (ended) return
        end(EndReason.CLOSED)
        job?.cancel()
    }

    private fun queueEvent(payload: ConvoyPayload) {
        outgoingEvents.trySend(QueuedEvent(payload, TimeSource.Monotonic.markNow()))
    }

    /** Ended is final: later status changes are ignored. */
    private fun setStatus(status: ConvoyStatus) {
        _status.update { current -> if (current is ConvoyStatus.Ended) current else status }
    }

    private fun end(reason: EndReason) {
        ended = true
        setStatus(ConvoyStatus.Ended(reason))
    }

    private suspend fun connectLoop() {
        var attempt = 0
        while (!ended) {
            try {
                http.webSocket(realtimeUrl, request = { header(HttpHeaders.SecWebSocketProtocol, SUBPROTOCOL) }) {
                    socket = this
                    try {
                        if (Connection(this).run()) attempt = 0
                    } finally {
                        socket = null
                    }
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive() // we were cancelled: stop here
                // Otherwise the server was unreachable or the connection broke: try again below.
            }
            if (ended) break
            attempt++
            setStatus(ConvoyStatus.Reconnecting(attempt))
            wake.tryReceive() // a wake-up from before this wait doesn't count
            withTimeoutOrNull(backoffMs(attempt)) { wake.receive() }
        }
    }

    private fun backoffMs(attempt: Int): Long {
        val exponential = min(timing.maxBackoffMs, timing.minBackoffMs shl min(attempt - 1, 16))
        return (exponential * Random.nextDouble(0.8, 1.2)).roundToLong()
    }

    private fun openingMessage(): ClientMessage {
        resumeToken?.let { return ClientMessage(resume = Resume(resume_token = it.toByteString())) }
        val id = convoyId
        val token = joinToken
        if (id != null && token != null) {
            return ClientMessage(join_convoy = JoinConvoy(convoy_id = id.toByteString(), join_token = token.toByteString()))
        }
        return ClientMessage(create_convoy = CreateConvoy())
    }

    /** One connection to the server, from the opening message until it drops. */
    private inner class Connection(private val socket: DefaultClientWebSocketSession) {
        private var seat: Seat? = null
        private val seated = CompletableDeferred<Seat>()

        /** Returns whether we got into the convoy on this connection. */
        suspend fun run(): Boolean {
            socket.send(ClientMessage(hello = Hello(client = clientName)))
            socket.send(openingMessage())
            val writers = socket.launch { write(seated.await()) }
            try {
                while (true) {
                    val frame = withTimeoutOrNull(timing.silenceTimeoutMs) { socket.incoming.receiveCatching() }?.getOrNull() ?: break
                    if (frame !is Frame.Binary) continue
                    val message = runCatching { ServerMessage.ADAPTER.decode(frame.readBytes()) }.getOrNull() ?: continue
                    if (!handle(message)) break
                }
            } finally {
                writers.cancel()
            }
            return seat != null
        }

        /** Sends our state, events, commands and pings for as long as the connection lasts. */
        private suspend fun write(seat: Seat): Unit = coroutineScope {
            launch {
                while (true) {
                    // Only ever the latest state: after a stall, old positions are skipped.
                    val state = myState.filterNotNull().first()
                    publish(seat, Channel.CHANNEL_STATE, ConvoyPayload(state = state))
                    delay(timing.stateIntervalMs)
                    withTimeoutOrNull(timing.stateRefreshMs - timing.stateIntervalMs) { myState.first { it !== state } }
                }
            }
            launch {
                for (event in outgoingEvents) {
                    if (event.queuedAt.elapsedNow().inWholeMilliseconds <= timing.maxEventAgeMs) publish(seat, Channel.CHANNEL_EVENT, event.payload)
                }
            }
            launch {
                for (command in commands) socket.send(command)
            }
            while (true) {
                delay(timing.pingIntervalMs)
                socket.send(ClientMessage(ping = Ping()))
            }
        }

        private suspend fun publish(seat: Seat, channel: Channel, payload: ConvoyPayload) {
            val sealed = ConvoyCrypto.seal(key, KEY_EPOCH, seat.convoyId, seat.memberId, channel, payload)
            socket.send(ClientMessage(publish = Publish(channel = channel, payload = sealed)))
        }

        /** Applies one server message. Returns false when this connection is done for. */
        private suspend fun handle(message: ServerMessage): Boolean {
            message.convoy_created?.let { created ->
                val id = created.convoy_id.toByteArray()
                val token = created.join_token.toByteArray()
                convoyId = id
                joinToken = token
                (start as? Start.Create)?.let { _invite.value = Invite(it.serverUrl, id, token, key) }
            }
            message.joined?.let { onJoined(it) }
            message.relay?.let { onRelay(it) }
            message.member_joined?.let { joined ->
                _members.update { it + (joined.member_id to (it[joined.member_id] ?: ConvoyMember(joined.member_id))) }
                _events.tryEmit(ConvoyEvent.MemberJoined(joined.member_id))
            }
            message.member_left?.let { left ->
                if (left.member_id == seat?.memberId) {
                    end(if (left.removed) EndReason.REMOVED else EndReason.LEFT)
                    return false
                }
                _members.update { it - left.member_id }
                if (left.new_owner != 0L) _ownerId.value = left.new_owner
                _events.tryEmit(ConvoyEvent.MemberLeft(left.member_id, left.removed))
            }
            message.error?.let { return onError(it) }
            return true
        }

        private fun onJoined(joined: Joined) {
            val id = joined.convoy_id.toByteArray()
            convoyId = id
            resumeToken = joined.resume_token.toByteArray()
            _ownerId.value = joined.owner

            val now = clock()
            val before = _members.value
            val after = joined.members.associate { member ->
                val state = member.last_state?.let { ConvoyCrypto.open(keys, id, member.member_id, Channel.CHANNEL_STATE, it)?.state }
                val known = before[member.member_id] ?: ConvoyMember(member.member_id)
                member.member_id to known.withState(state, heardMs = now - member.last_state_age_ms)
            }
            _members.value = after
            if (everJoined) {
                // Back after a dropped connection: catch up on who came and went in the meantime.
                for (gone in before.keys - after.keys) _events.tryEmit(ConvoyEvent.MemberLeft(gone, removed = false))
                for (new in after.keys - before.keys) _events.tryEmit(ConvoyEvent.MemberJoined(new))
            }
            everJoined = true

            val newSeat = Seat(id, joined.member_id)
            seat = newSeat
            seated.complete(newSeat)
            setStatus(ConvoyStatus.Live(joined.member_id))
        }

        private fun onRelay(relay: Relay) {
            val id = seat?.convoyId ?: return
            val sealed = relay.payload ?: return
            val payload = ConvoyCrypto.open(keys, id, relay.sender, relay.channel, sealed) ?: return
            when (relay.channel) {
                Channel.CHANNEL_STATE -> payload.state?.let { state ->
                    val now = clock()
                    _members.update { it + (relay.sender to (it[relay.sender] ?: ConvoyMember(relay.sender)).withState(state, now)) }
                }
                Channel.CHANNEL_EVENT -> {
                    payload.quick_message?.let { _events.tryEmit(ConvoyEvent.QuickMessageReceived(relay.sender, it.kind)) }
                    payload.regroup_pin?.location?.let { _events.tryEmit(ConvoyEvent.RegroupPinDropped(relay.sender, it.toGeoPoint(), it.name)) }
                }
                else -> Unit
            }
        }

        private suspend fun onError(error: ProtocolError): Boolean {
            if (seat == null) {
                // The answer to our opening message.
                when (error.code) {
                    ProtocolError.Code.CODE_NOT_FOUND -> {
                        if (resumeToken != null) {
                            // Our seat expired while we were away: join again with the invite.
                            resumeToken = null
                            socket.send(openingMessage())
                            return true
                        }
                        end(EndReason.CONVOY_GONE)
                        return false
                    }
                    ProtocolError.Code.CODE_FORBIDDEN -> {
                        end(EndReason.INVITE_INVALID)
                        return false
                    }
                    ProtocolError.Code.CODE_CONVOY_FULL -> {
                        end(EndReason.CONVOY_FULL)
                        return false
                    }
                    else -> Unit
                }
            }
            _events.tryEmit(ConvoyEvent.Refused(error.code, error.message))
            return true
        }
    }

    companion object {
        /** The WebSocket subprotocol of protocol version 0. */
        const val SUBPROTOCOL = "foxnav.v0"
        private const val KEY_EPOCH = 0
        private const val LEAVE_TIMEOUT_MS = 2_000L
    }
}

private class Seat(val convoyId: ByteArray, val memberId: Long)

private class QueuedEvent(val payload: ConvoyPayload, val queuedAt: TimeMark)

private suspend fun DefaultClientWebSocketSession.send(message: ClientMessage) {
    send(Frame.Binary(true, ClientMessage.ADAPTER.encode(message)))
}

/** Applies a member's state. One older than what we have (a replay) changes nothing. */
internal fun ConvoyMember.withState(state: MemberState?, heardMs: Long): ConvoyMember {
    if (state == null) return this
    val position = state.position
    if (position != null && fix != null && position.fix_time_ms < fix.timeMs) return this
    return copy(profile = state.profile ?: profile, fix = position?.toFix() ?: fix, route = state.route, lastHeardMs = heardMs)
}

/** Our state as the others see it. [fix] is null until we have a GPS fix. */
fun memberState(profile: Profile, fix: Fix?, route: RouteShare? = null) =
    MemberState(profile = profile, position = fix?.toPosition(), route = route)

fun Position.toFix() = Fix(GeoPoint(lat_e7 / 1e7, lon_e7 / 1e7), speed_mps.toDouble(), heading_deg.toDouble(), fix_time_ms)

fun Fix.toPosition() = Position(
    lat_e7 = (point.latitude * 1e7).roundToInt(),
    lon_e7 = (point.longitude * 1e7).roundToInt(),
    speed_mps = speedMps.toFloat(),
    heading_deg = headingDeg.toFloat(),
    fix_time_ms = timeMs,
)

fun GeoPoint.toWaypoint(name: String = "") = Waypoint(lat_e7 = (latitude * 1e7).roundToInt(), lon_e7 = (longitude * 1e7).roundToInt(), name = name)

fun Waypoint.toGeoPoint() = GeoPoint(lat_e7 / 1e7, lon_e7 / 1e7)

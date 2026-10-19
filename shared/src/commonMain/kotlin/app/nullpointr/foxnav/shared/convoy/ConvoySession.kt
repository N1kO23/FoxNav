package app.nullpointr.foxnav.shared.convoy

import app.nullpointr.foxnav.protocol.convoy.v0.Card
import app.nullpointr.foxnav.protocol.convoy.v0.ClientMessage
import app.nullpointr.foxnav.protocol.convoy.v0.CreateConvoy
import app.nullpointr.foxnav.protocol.convoy.v0.Event
import app.nullpointr.foxnav.protocol.convoy.v0.HandOver
import app.nullpointr.foxnav.protocol.convoy.v0.Handover
import app.nullpointr.foxnav.protocol.convoy.v0.Hello
import app.nullpointr.foxnav.protocol.convoy.v0.JoinConvoy
import app.nullpointr.foxnav.protocol.convoy.v0.Joined
import app.nullpointr.foxnav.protocol.convoy.v0.KeyRotation
import app.nullpointr.foxnav.protocol.convoy.v0.LeaveConvoy
import app.nullpointr.foxnav.protocol.convoy.v0.NowPlaying
import app.nullpointr.foxnav.protocol.convoy.v0.OwnerStatement
import app.nullpointr.foxnav.protocol.convoy.v0.Ping
import app.nullpointr.foxnav.protocol.convoy.v0.Position
import app.nullpointr.foxnav.protocol.convoy.v0.Profile
import app.nullpointr.foxnav.protocol.convoy.v0.Publish
import app.nullpointr.foxnav.protocol.convoy.v0.QuickMessage
import app.nullpointr.foxnav.protocol.convoy.v0.RegroupPin
import app.nullpointr.foxnav.protocol.convoy.v0.RemoveMember
import app.nullpointr.foxnav.protocol.convoy.v0.Resume
import app.nullpointr.foxnav.protocol.convoy.v0.RouteShare
import app.nullpointr.foxnav.protocol.convoy.v0.ServerMessage
import app.nullpointr.foxnav.protocol.convoy.v0.WrappedKey
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
import kotlinx.coroutines.cancel
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import app.nullpointr.foxnav.protocol.convoy.v0.Error as ProtocolError
import kotlinx.coroutines.channels.Channel as AsyncChannel

/** Another member of the convoy, as far as we know them. */
data class ConvoyMember(
    val id: Long,
    /** Null until their card arrives. */
    val profile: Profile? = null,
    val fix: Fix? = null,
    /** The destination and via points they share, if any. */
    val route: RouteShare? = null,
    /** What their music is playing, if they share it. */
    val nowPlaying: NowPlaying? = null,
    /** When we last heard from them (our clock, Unix ms); null until the first frame. */
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
 * joins the convoy, keeps everyone's latest state and publishes ours. When the connection drops,
 * it reconnects with backoff and resumes its seat, or joins again with the invite if the seat
 * expired in the meantime.
 *
 * Everything it sends is a frame (§2.3), signed with its own identity and sealed with the convoy
 * key; everything it takes in is checked against its sender's key, so neither the server nor
 * another member can send as anyone else. As the owner, it rotates the key after a removal and
 * hands the convoy over before leaving.
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

    /** How often the session talks to the server. The defaults follow protocol/SPEC.md §2. */
    data class Timing(
        val stateIntervalMs: Long = 1_000,
        /**
         * An unchanged position goes out again this often (or the card, before the first fix): the
         * others take silence for a lost signal, and a parked car's GPS may go quiet.
         */
        val stateRefreshMs: Long = 5_000,
        val pingIntervalMs: Long = 15_000,
        /** A connection that delivers nothing for this long counts as dead. */
        val silenceTimeoutMs: Long = 45_000,
        val minBackoffMs: Long = 1_000,
        val maxBackoffMs: Long = 30_000,
        /** Quick messages and pins still unsent after this long are dropped: old news misleads. */
        val maxEventAgeMs: Long = 60_000,
        /** A frame from someone whose card (or a key we don't have yet) waits this long for it. */
        val holdMs: Long = 30_000,
        /** After a rotation, frames under the old key still open for this long. */
        val oldKeyGraceMs: Long = 120_000,
    )

    private val joining = (start as? Start.Join)?.invite

    // Who we are, and our frame counter: they go together (spec §2.3), and the counter only
    // changes under [sendLock].
    @Volatile
    private var identity = Identity.generate()
    private var counter = 0L
    private val sendLock = Mutex()

    @Volatile
    private var keyring = Keyring(null, joining?.keyEpoch ?: 0, mapOf((joining?.keyEpoch ?: 0) to (joining?.key ?: ConvoyFrames.newKey())), emptyMap())

    @Volatile
    private var ownerKey: ByteArray = joining?.ownerKey ?: identity.signingKey

    @Volatile
    private var handovers = joining?.handovers ?: 0

    @Volatile
    private var removed: Set<Int> = emptySet()

    /** Members' cards by member ID: usually one each, more only if two keys share an ID. */
    @Volatile
    private var cards: Map<Int, List<Card>> = emptyMap()

    // Only the connection loop touches these, one connection at a time.
    private var convoyId = joining?.convoyId
    private var joinToken = joining?.joinToken
    private var resumeToken: ByteArray? = null
    private var everJoined = false
    private val windows = HashMap<Int, ReplayWindow>()
    private var held = ArrayDeque<Held>()

    /** The members connected to the server, longest in first: the next owner is the first of them. */
    @Volatile
    private var connected: List<Int> = emptyList()

    private val _status = MutableStateFlow<ConvoyStatus>(ConvoyStatus.Connecting)
    val status: StateFlow<ConvoyStatus> = _status.asStateFlow()

    private val _invite = MutableStateFlow(joining)

    /** The invite to share: known from the start when joining, and once the server has created the convoy when creating. */
    val invite: StateFlow<Invite?> = _invite.asStateFlow()

    private val _members = MutableStateFlow<Map<Long, ConvoyMember>>(emptyMap())

    /** Everyone else in the convoy, by member ID. */
    val members: StateFlow<Map<Long, ConvoyMember>> = _members.asStateFlow()

    private val _ownerId = MutableStateFlow<Long?>(joining?.ownerKey?.let { memberIdOf(it).toMemberId() })

    /** The member who may remove others: the creator, or whoever they handed the convoy over to. */
    val ownerId: StateFlow<Long?> = _ownerId.asStateFlow()

    private val _events = MutableSharedFlow<ConvoyEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ConvoyEvent> = _events.asSharedFlow()

    private val myState = MutableStateFlow<MemberState?>(null)
    private val stateNudge = AsyncChannel<Unit>(AsyncChannel.CONFLATED)

    /** Set when everything kept must go out again: on a new connection, and under a new key. */
    @Volatile
    private var republish = true
    private val outgoingEvents = AsyncChannel<QueuedEvent>(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val removals = AsyncChannel<Int>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val rewraps = AsyncChannel<Rewrap>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Which members we (as the owner) wrapped a key for after its rotation, by epoch. Only the writer touches it. */
    private val rewrapped = HashSet<Rewrap>()
    private val wake = AsyncChannel<Unit>(AsyncChannel.CONFLATED)

    @Volatile
    private var skipBackoff = false

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
     * Our latest state. Each part goes out as its own frame: the position right away, then at most
     * once per [Timing.stateIntervalMs] and again every [Timing.stateRefreshMs] while it stays the
     * same; the profile, route and song when they change.
     */
    fun updateState(state: MemberState) {
        myState.value = state
        stateNudge.trySend(Unit)
    }

    fun sendQuickMessage(kind: QuickMessage.Kind) = queueEvent(Event(quick_message = QuickMessage(kind = kind)))

    fun dropRegroupPin(point: GeoPoint, name: String) = queueEvent(Event(regroup_pin = RegroupPin(location = point.toWaypoint(name))))

    /**
     * Removes [memberId]. Only the owner may. The session tells the server, which stops relaying to
     * them and changes the join token, and sends a new key to everyone else, each separately, so
     * the removed member can neither read along nor come back with their old invite.
     */
    fun remove(memberId: Long) {
        removals.trySend(memberId.toInt())
    }

    /**
     * Reconnects right away, e.g. when the phone switched networks: the current connection
     * probably went with the old network, and waiting for it to time out takes a while.
     */
    fun reconnectNow() {
        skipBackoff = true
        wake.trySend(Unit)
        socket?.cancel()
    }

    /**
     * Leaves the convoy for good: the others see us go and the seat can't be resumed. As the
     * owner, it first hands the convoy over to whoever has been in it longest.
     */
    suspend fun leave() {
        if (ended) return
        ended = true
        socket?.let { open ->
            withTimeoutOrNull(LEAVE_TIMEOUT_MS) {
                try {
                    convoyId?.let { handOver(open, it) }
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

    private fun queueEvent(event: Event) {
        outgoingEvents.trySend(QueuedEvent(event.copy(sent_ms = clock()), TimeSource.Monotonic.markNow()))
    }

    /** Ended is final: later status changes are ignored. */
    private fun setStatus(status: ConvoyStatus) {
        _status.update { current -> if (current is ConvoyStatus.Ended) current else status }
    }

    private fun end(reason: EndReason) {
        ended = true
        setStatus(ConvoyStatus.Ended(reason))
    }

    private val isOwner: Boolean get() = ownerKey.contentEquals(identity.signingKey)

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
            if (!skipBackoff) {
                wake.tryReceive() // a wake-up from before this wait doesn't count
                withTimeoutOrNull(backoffMs(attempt)) { wake.receive() }
            }
            skipBackoff = false
        }
    }

    /** The invite as it stands: the join token and key change when someone is removed, the owner with a handover. */
    private fun refreshInvite() {
        val id = convoyId ?: return
        val token = joinToken ?: return
        val server = when (start) {
            is Start.Create -> start.serverUrl
            is Start.Join -> start.invite.server
        }
        val ring = keyring
        _invite.value = Invite(server, id, token, ring.raw.getValue(ring.epoch), ownerKey, ring.epoch, handovers)
    }

    private fun backoffMs(attempt: Int): Long {
        val exponential = min(timing.maxBackoffMs, timing.minBackoffMs shl min(attempt - 1, 16))
        return (exponential * Random.nextDouble(0.8, 1.2)).roundToLong()
    }

    private fun openingMessage(): ClientMessage {
        resumeToken?.let { return ClientMessage(resume = Resume(resume_token = it.toByteString())) }
        val id = convoyId
        val token = joinToken
        val me = identity
        if (id != null && token != null) {
            return ClientMessage(join_convoy = JoinConvoy(convoy_id = id.toByteString(), join_token = token.toByteString(), member_id = me.memberId))
        }
        return ClientMessage(create_convoy = CreateConvoy(member_id = me.memberId, owner_key = me.signingKey.toByteString()))
    }

    /** Seals [content] as our next frame, under the current key unless [epoch] says another, and sends it. */
    private suspend fun sendFrame(socket: DefaultClientWebSocketSession, convoyId: ByteArray, kind: FrameKind, content: ByteArray, epoch: Int? = null) = sendLock.withLock {
        val key = keyring.key(convoyId, epoch ?: keyring.epoch) ?: return@withLock
        counter += 1
        val frame = ConvoyFrames.seal(convoyId, key, identity, counter, kind, content)
        socket.send(ClientMessage(publish = Publish(frame = frame.toByteString())))
    }

    /** As the owner, before leaving: to whoever has been in the convoy longest (and isn't only watching, if anyone). */
    private suspend fun handOver(socket: DefaultClientWebSocketSession, convoyId: ByteArray) {
        if (!isOwner) return
        val candidates = connected.mapNotNull { id -> cards[id]?.lastOrNull() }
        val next = candidates.firstOrNull { it.profile?.watching != true } ?: candidates.firstOrNull() ?: return
        val newOwnerKey = next.signing_key.toByteArray()
        val number = handovers + 1
        sendFrame(socket, convoyId, FrameKind.OWNER, OwnerStatement.ADAPTER.encode(OwnerStatement(handover = Handover(number = number, new_owner_key = next.signing_key))))
        val signature = identity.sign(OwnerSignatures.handover(convoyId, number, newOwnerKey))
        socket.send(ClientMessage(hand_over = HandOver(number = number, new_owner_key = next.signing_key, signature = signature.toByteString())))
        handovers = number
        ownerKey = newOwnerKey
        _ownerId.value = memberIdOf(newOwnerKey).toMemberId()
    }

    /** As the owner: [target] out, and a new key for everyone else, each wrapped for them alone. */
    private suspend fun removeAndRotate(socket: DefaultClientWebSocketSession, convoyId: ByteArray, target: Int) {
        val me = identity
        if (!isOwner || target == me.memberId) return
        val ring = keyring
        val epoch = ring.epoch + 1
        val signature = me.sign(OwnerSignatures.removal(convoyId, target, epoch))
        socket.send(ClientMessage(remove_member = RemoveMember(member_id = target, epoch = epoch, signature = signature.toByteString())))
        removed = removed + target
        val newKey = ConvoyFrames.newKey()
        val keys = cards.filterKeys { it != target && it !in removed && it != me.memberId }.mapNotNull { (id, theirs) ->
            val card = theirs.lastOrNull() ?: return@mapNotNull null
            KeyWraps.wrap(convoyId, epoch, me, id, card.exchange_key.toByteArray(), newKey)?.let { WrappedKey(member_id = id, key = it.toByteString()) }
        }
        // Under the current key: the removed member may read who was removed, but not the new key.
        for (part in FrameContents.keyRotation(epoch, listOf(target), keys)) sendFrame(socket, convoyId, FrameKind.OWNER, part)
        switchKey(epoch, newKey)
    }

    /** As the owner: the current key for [request]'s member, under the older key they still use. */
    private suspend fun rewrap(socket: DefaultClientWebSocketSession, convoyId: ByteArray, request: Rewrap) {
        val ring = keyring
        val member = request.member
        if (!isOwner || member in removed || !rewrapped.add(Rewrap(member, ring.epoch))) return
        val card = cards[member]?.lastOrNull() ?: return
        val wrapped = KeyWraps.wrap(convoyId, ring.epoch, identity, member, card.exchange_key.toByteArray(), ring.raw.getValue(ring.epoch)) ?: return
        for (part in FrameContents.keyRotation(ring.epoch, emptyList(), listOf(WrappedKey(member_id = member, key = wrapped.toByteString())))) {
            sendFrame(socket, convoyId, FrameKind.OWNER, part, epoch = request.epoch)
        }
    }

    private fun switchKey(epoch: Int, key: ByteArray) {
        keyring = keyring.rotated(epoch, key, clock())
        refreshInvite()
        // What the server keeps of ours is under the old key, which newcomers won't have.
        republish = true
        stateNudge.trySend(Unit)
    }

    /**
     * Takes in one frame: [heardMs] is when it was sent, as far as we know (our clock). It counts
     * only if it opens with a key we have and is signed by its sender's key, once.
     */
    private fun receive(convoyId: ByteArray, frame: ByteArray, heardMs: Long) {
        val header = FrameHeader.parse(frame) ?: return
        val me = identity
        if (header.sender == me.memberId || header.sender in removed) return
        val now = clock()
        val (key, current) = keyring.byTag(convoyId, header.tag, now, timing.oldKeyGraceMs) ?: run {
            hold(frame, heardMs, now) // under a key we may get yet, say from a rotation still on its way
            return
        }
        if (windows[header.sender]?.isNew(header.counter) == false) return
        val opened = ConvoyFrames.open(convoyId, key, frame) ?: return
        val kind = FrameKind.of(header.kind) ?: return
        val signer: ByteArray = when (kind) {
            FrameKind.OWNER -> ownerKey.takeIf { memberIdOf(it) == header.sender && opened.signedBy(convoyId, it) } ?: return
            FrameKind.CARD -> {
                val card = runCatching { Card.ADAPTER.decode(opened.content) }.getOrNull() ?: return
                card.signing_key.toByteArray().takeIf { memberIdOf(it) == header.sender && opened.signedBy(convoyId, it) } ?: return
            }
            else -> {
                val theirs = cards[header.sender]
                if (theirs == null) {
                    hold(frame, heardMs, now) // their card is surely on its way
                    return
                }
                theirs.firstNotNullOfOrNull { card -> card.signing_key.toByteArray().takeIf { opened.signedBy(convoyId, it) } } ?: return
            }
        }
        val statement = if (kind == FrameKind.OWNER) {
            runCatching { OwnerStatement.ADAPTER.decode(opened.content) }.getOrNull() ?: return
        } else {
            null
        }
        if (statement?.key_rotation?.let { needsOwnerCard(it) } == true) {
            hold(frame, heardMs, now)
            return
        }
        // Someone still on an old key missed the rotation, or wasn't in it (we had no card of theirs
        // yet). As the owner: wrap the current key for them, under the key they have.
        if (key.epoch < keyring.epoch && isOwner && kind != FrameKind.OWNER) rewraps.trySend(Rewrap(header.sender, key.epoch))
        if (!current) return // under a key replaced too long ago: not taken in
        windows.getOrPut(header.sender) { ReplayWindow() }.accept(header.counter)
        apply(convoyId, kind, header.sender, opened.content, signer, statement, heardMs)
    }

    /** A rotation with a key for us needs the owner's card, for their X25519 key. */
    private fun needsOwnerCard(rotation: KeyRotation): Boolean =
        rotation.epoch > keyring.epoch && rotation.keys.any { it.member_id == identity.memberId } && ownerCard() == null

    private fun ownerCard(): Card? = cards[memberIdOf(ownerKey)]?.firstOrNull { it.signing_key.toByteArray().contentEquals(ownerKey) }

    private fun apply(convoyId: ByteArray, kind: FrameKind, sender: Int, content: ByteArray, signer: ByteArray, statement: OwnerStatement?, heardMs: Long) {
        val id = sender.toMemberId()
        when (kind) {
            FrameKind.CARD -> {
                val card = Card.ADAPTER.decode(content)
                cards = cards + (sender to (cards[sender].orEmpty().filterNot { it.signing_key.toByteArray().contentEquals(signer) } + card))
                updateMember(id, heardMs) { copy(profile = card.profile ?: profile) }
                releaseHeld(convoyId)
            }
            FrameKind.POSITION -> {
                val fix = runCatching { Position.ADAPTER.decode(content) }.getOrNull()?.toFix() ?: return
                // One older than what we have (a late arrival) changes nothing.
                if (_members.value[id]?.fix?.let { fix.timeMs < it.timeMs } == true) return
                updateMember(id, heardMs) { copy(fix = fix) }
            }
            FrameKind.ROUTE -> {
                val route = runCatching { RouteShare.ADAPTER.decode(content) }.getOrNull() ?: return
                updateMember(id, heardMs) { copy(route = route.takeIf { it.waypoints.isNotEmpty() }) }
            }
            FrameKind.SONG -> {
                val song = runCatching { NowPlaying.ADAPTER.decode(content) }.getOrNull() ?: return
                updateMember(id, heardMs) { copy(nowPlaying = song.takeUnless { it.title.isEmpty() && it.artist.isEmpty() && it.app.isEmpty() }) }
            }
            FrameKind.EVENT -> {
                val event = runCatching { Event.ADAPTER.decode(content) }.getOrNull() ?: return
                if (clock() - event.sent_ms > MAX_EVENT_AGE_MS) return // old news, or a replay
                updateMember(id, heardMs) { this }
                event.quick_message?.let { _events.tryEmit(ConvoyEvent.QuickMessageReceived(id, it.kind)) }
                event.regroup_pin?.location?.let { _events.tryEmit(ConvoyEvent.RegroupPinDropped(id, it.toGeoPoint(), it.name)) }
            }
            FrameKind.OWNER -> {
                statement?.handover?.let { handover ->
                    // Only the next one: an old handover sent again changes nothing.
                    if (handover.number != handovers + 1 || handover.new_owner_key.size != ConvoyFrames.KEY_SIZE) return
                    handovers = handover.number
                    ownerKey = handover.new_owner_key.toByteArray()
                    _ownerId.value = memberIdOf(ownerKey).toMemberId()
                    refreshInvite()
                }
                statement?.key_rotation?.let { rotation ->
                    if (rotation.epoch <= keyring.epoch) return
                    removed = removed + rotation.removed
                    for (gone in rotation.removed) forget(gone)
                    val mine = rotation.keys.firstOrNull { it.member_id == identity.memberId } ?: return // in another part
                    val ownerExchange = ownerCard()?.exchange_key?.toByteArray() ?: return
                    val newKey = KeyWraps.unwrap(convoyId, rotation.epoch, identity, ownerExchange, mine.key.toByteArray()) ?: return
                    if (newKey.size != ConvoyFrames.KEY_SIZE) return
                    switchKey(rotation.epoch, newKey)
                    releaseHeld(convoyId)
                }
            }
        }
    }

    private fun updateMember(id: Long, heardMs: Long, change: ConvoyMember.() -> ConvoyMember) {
        _members.update { all ->
            val known = all[id] ?: ConvoyMember(id)
            all + (id to known.change().copy(lastHeardMs = max(known.lastHeardMs ?: heardMs, heardMs)))
        }
    }

    private fun forget(member: Int) {
        cards = cards - member
        windows.remove(member)
        _members.update { it - member.toMemberId() }
    }

    private fun hold(frame: ByteArray, heardMs: Long, now: Long) {
        held.addLast(Held(frame, heardMs, now))
        while (held.size > MAX_HELD) held.removeFirst()
    }

    /** Tries the held frames again, now that a card or a key arrived; those held too long go. */
    private fun releaseHeld(convoyId: ByteArray) {
        if (held.isEmpty()) return
        val now = clock()
        val again = held
        held = ArrayDeque()
        for (frame in again) if (now - frame.heldAtMs <= timing.holdMs) receive(convoyId, frame.frame, frame.heardMs)
    }

    /** One connection to the server, from the opening message until it drops. */
    private inner class Connection(private val socket: DefaultClientWebSocketSession) {
        private var seat: ByteArray? = null
        private val joinedConvoy = CompletableDeferred<ByteArray>()

        /** Returns whether we got into the convoy on this connection. */
        suspend fun run(): Boolean {
            socket.send(ClientMessage(hello = Hello(client = clientName)))
            socket.send(openingMessage())
            val writers = socket.launch { write(joinedConvoy.await()) }
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

        /** Sends our state, events, removals and pings for as long as the connection lasts. */
        private suspend fun write(convoyId: ByteArray): Unit = coroutineScope {
            launch { publishState(convoyId) }
            launch {
                for (event in outgoingEvents) {
                    if (event.queuedAt.elapsedNow().inWholeMilliseconds <= timing.maxEventAgeMs) {
                        sendFrame(socket, convoyId, FrameKind.EVENT, Event.ADAPTER.encode(event.event))
                    }
                }
            }
            launch {
                for (target in removals) removeAndRotate(socket, convoyId, target)
            }
            launch {
                for (request in rewraps) rewrap(socket, convoyId, request)
            }
            while (true) {
                delay(timing.pingIntervalMs)
                socket.send(ClientMessage(ping = Ping()))
            }
        }

        private suspend fun publishState(convoyId: ByteArray) {
            republish = true // the server may have lost what it kept of ours
            var sent = Sent()
            while (true) {
                // Our card goes out even before there's a state: the others need our keys, say to send us a new convoy key.
                sent = publishDue(convoyId, myState.value ?: MemberState(Profile()), sent)
                delay(timing.stateIntervalMs)
                withTimeoutOrNull(timing.stateRefreshMs - timing.stateIntervalMs) { stateNudge.receive() }
            }
        }

        /** Sends whatever of [state] changed since [sent], or is due again; returns what's sent now. */
        private suspend fun publishDue(convoyId: ByteArray, state: MemberState, sent: Sent): Sent {
            val now = clock()
            val all = republish
            republish = false
            // Due again a little early, so the others never go [Timing.stateRefreshMs] without hearing from us.
            val refresh = timing.stateRefreshMs - timing.stateIntervalMs
            var next = sent
            if (all || state.profile != sent.profile || (state.fix == null && now - sent.cardAtMs >= refresh)) {
                sendFrame(socket, convoyId, FrameKind.CARD, FrameContents.card(identity, state.profile))
                next = next.copy(profile = state.profile, cardAtMs = now)
            }
            if (all || state.route != sent.route) {
                sendFrame(socket, convoyId, FrameKind.ROUTE, FrameContents.route(state.route))
                next = next.copy(route = state.route)
            }
            if (all || state.nowPlaying != sent.nowPlaying) {
                sendFrame(socket, convoyId, FrameKind.SONG, FrameContents.song(state.nowPlaying))
                next = next.copy(nowPlaying = state.nowPlaying)
            }
            val fix = state.fix
            if (fix != null && (all || fix != sent.fix || now - sent.positionAtMs >= refresh)) {
                sendFrame(socket, convoyId, FrameKind.POSITION, Position.ADAPTER.encode(fix.toPosition()))
                next = next.copy(fix = fix, positionAtMs = now)
            }
            return next
        }

        /** Applies one server message. Returns false when this connection is done for. */
        private suspend fun handle(message: ServerMessage): Boolean {
            message.convoy_created?.let { created ->
                convoyId = created.convoy_id.toByteArray()
                joinToken = created.join_token.toByteArray()
                refreshInvite()
            }
            message.invite_changed?.let { changed ->
                joinToken = changed.join_token.toByteArray()
                refreshInvite()
            }
            message.joined?.let { onJoined(it) }
            message.relay?.let { relay -> seat?.let { receive(it, relay.frame.toByteArray(), clock()) } }
            message.member_joined?.let { joined ->
                val id = joined.member_id
                connected = connected - id + id
                _members.update { it + (id.toMemberId() to (it[id.toMemberId()] ?: ConvoyMember(id.toMemberId()))) }
                _events.tryEmit(ConvoyEvent.MemberJoined(id.toMemberId()))
            }
            message.member_left?.let { left ->
                if (left.member_id == identity.memberId) {
                    end(if (left.removed) EndReason.REMOVED else EndReason.LEFT)
                    return false
                }
                connected = connected - left.member_id
                forget(left.member_id)
                _events.tryEmit(ConvoyEvent.MemberLeft(left.member_id.toMemberId(), left.removed))
            }
            message.error?.let { return onError(it) }
            return true
        }

        private fun onJoined(joined: Joined) {
            val id = joined.convoy_id.toByteArray()
            convoyId = id
            if (keyring.convoyId == null) keyring = keyring.forConvoy(id)
            resumeToken = joined.resume_token.toByteArray()
            if (joined.join_token.size > 0) joinToken = joined.join_token.toByteArray()
            refreshInvite()

            val now = clock()
            val before = _members.value
            connected = joined.members
            _members.value = joined.members.associate { member -> member.toMemberId() to (before[member.toMemberId()] ?: ConvoyMember(member.toMemberId())) }
            // What the server kept, oldest first: cards, the latest positions, and the owner's statements.
            for (relay in joined.frames) receive(id, relay.frame.toByteArray(), now - relay.age_ms)
            val after = _members.value
            if (everJoined) {
                // Back after a dropped connection: catch up on who came and went in the meantime.
                for (gone in before.keys - after.keys) _events.tryEmit(ConvoyEvent.MemberLeft(gone, removed = false))
                for (new in after.keys - before.keys) _events.tryEmit(ConvoyEvent.MemberJoined(new))
            }
            everJoined = true
            _ownerId.value = memberIdOf(ownerKey).toMemberId()

            seat = id
            joinedConvoy.complete(id)
            setStatus(ConvoyStatus.Live(identity.memberId.toMemberId()))
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
                    ProtocolError.Code.CODE_CONFLICT -> {
                        // Someone in the convoy has our member ID: be someone else (a new key, a new counter).
                        sendLock.withLock {
                            identity = Identity.generate()
                            counter = 0
                        }
                        socket.send(openingMessage())
                        return true
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
        private const val LEAVE_TIMEOUT_MS = 2_000L

        /** Events older than this are dropped (spec §2.4). */
        private const val MAX_EVENT_AGE_MS = 5 * 60_000L

        /** At most this many frames wait for a card or a key. */
        private const val MAX_HELD = 256
    }
}

/**
 * The convoy's keys by epoch. We seal with the newest; older ones still open frames for a while
 * after they're replaced. Keys derive from the convoy ID, so they're usable once it's known.
 */
private class Keyring(val convoyId: ByteArray?, val epoch: Int, val raw: Map<Int, ByteArray>, val retiredAtMs: Map<Int, Long>) {
    private val keys: Map<Int, ConvoyKey> by lazy { convoyId?.let { id -> raw.mapValues { (epoch, key) -> ConvoyKey(epoch, key, id) } }.orEmpty() }

    fun forConvoy(convoyId: ByteArray) = Keyring(convoyId, epoch, raw, retiredAtMs)

    fun key(convoyId: ByteArray, epoch: Int): ConvoyKey? = keysFor(convoyId)[epoch]

    /**
     * The key with [tag], and whether its frames still count: the current key's do, and an older
     * one's for [graceMs] after it was replaced, so that those on their way still arrive.
     */
    fun byTag(convoyId: ByteArray, tag: Int, nowMs: Long, graceMs: Long): Pair<ConvoyKey, Boolean>? {
        val key = keysFor(convoyId).values.firstOrNull { it.tag == tag } ?: return null
        val retired = retiredAtMs[key.epoch]
        return key to (key.epoch == epoch || (retired != null && nowMs - retired <= graceMs))
    }

    private fun keysFor(convoyId: ByteArray): Map<Int, ConvoyKey> = (if (this.convoyId == null) forConvoy(convoyId) else this).keys

    fun rotated(epoch: Int, key: ByteArray, nowMs: Long) =
        Keyring(convoyId, epoch, raw + (epoch to key), retiredAtMs + raw.keys.filter { it !in retiredAtMs }.associateWith { nowMs })
}

/** What we last sent of our state, and when. */
private data class Sent(
    val profile: Profile? = null,
    val cardAtMs: Long = 0,
    val route: RouteShare? = null,
    val nowPlaying: NowPlaying? = null,
    val fix: Fix? = null,
    val positionAtMs: Long = 0,
)

private class Held(val frame: ByteArray, val heardMs: Long, val heldAtMs: Long)

/** A member still sealing under the key of [epoch]. */
private data class Rewrap(val member: Int, val epoch: Int)

private class QueuedEvent(val event: Event, val queuedAt: TimeMark)

private suspend fun DefaultClientWebSocketSession.send(message: ClientMessage) {
    send(Frame.Binary(true, ClientMessage.ADAPTER.encode(message)))
}

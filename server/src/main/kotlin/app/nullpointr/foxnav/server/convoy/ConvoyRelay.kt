package app.nullpointr.foxnav.server.convoy

import app.nullpointr.foxnav.protocol.convoy.v0.ClientMessage
import app.nullpointr.foxnav.protocol.convoy.v0.ConvoyCreated
import app.nullpointr.foxnav.protocol.convoy.v0.CreateConvoy
import app.nullpointr.foxnav.protocol.convoy.v0.HandOver
import app.nullpointr.foxnav.protocol.convoy.v0.InviteChanged
import app.nullpointr.foxnav.protocol.convoy.v0.JoinConvoy
import app.nullpointr.foxnav.protocol.convoy.v0.Joined
import app.nullpointr.foxnav.protocol.convoy.v0.MemberJoined
import app.nullpointr.foxnav.protocol.convoy.v0.MemberLeft
import app.nullpointr.foxnav.protocol.convoy.v0.Pong
import app.nullpointr.foxnav.protocol.convoy.v0.Publish
import app.nullpointr.foxnav.protocol.convoy.v0.Relay
import app.nullpointr.foxnav.protocol.convoy.v0.RemoveMember
import app.nullpointr.foxnav.protocol.convoy.v0.Resume
import app.nullpointr.foxnav.protocol.convoy.v0.ServerMessage
import app.nullpointr.foxnav.protocol.convoy.v0.Welcome
import app.nullpointr.foxnav.shared.convoy.ConvoyFrames
import app.nullpointr.foxnav.shared.convoy.FrameHeader
import app.nullpointr.foxnav.shared.convoy.FrameKind
import app.nullpointr.foxnav.shared.convoy.OwnerSignatures
import app.nullpointr.foxnav.shared.convoy.ed25519Verify
import app.nullpointr.foxnav.shared.convoy.memberIdOf
import app.nullpointr.foxnav.shared.convoy.secureRandomBytes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.MessageDigest
import app.nullpointr.foxnav.protocol.convoy.v0.Error as ProtocolError

/**
 * The convoy relay (protocol/SPEC.md §2.2): rooms of members that forward signed, end-to-end
 * encrypted frames to each other. It reads only a frame's header, never what's inside. It checks
 * the owner's signature before removing anyone or handing the convoy over. Rooms live in memory only.
 */
class ConvoyRelay(
    private val limits: Limits = Limits(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Limits(
        val maxConvoySize: Int = 20,
        /**
         * At most [positionBurst] POSITION frames per member and sender per [positionWindowMs]; more
         * are dropped. Clients send one a second, but after a network stall several arrive at once.
         */
        val positionBurst: Int = 5,
        val positionWindowMs: Long = 3_000,
        /** At most [otherBurst] frames of other kinds per member and sender per [otherWindowMs]. */
        val otherBurst: Int = 10,
        val otherWindowMs: Long = 10_000,
        /** How long a dropped member keeps their seat and can resume. */
        val resumeWindowMs: Long = 5 * 60_000,
        /** How long a convoy with nobody in it stays joinable. */
        val emptyRoomTtlMs: Long = 60 * 60_000,
        /** How many OWNER frames a convoy keeps for members who were away. */
        val ownerFrames: Int = 64,
    )

    /** One client connection; [send] queues a message for it. */
    class Connection internal constructor(internal val send: (ServerMessage) -> Unit) {
        internal var room: Room? = null
        internal var member: Member? = null
    }

    internal class Room(val id: ByteString, var joinToken: ByteString, var ownerKey: ByteString) {
        val members = LinkedHashMap<Int, Member>()
        var handovers = 0
        val removed = HashSet<Int>()
        var emptySince: Long? = null
        val ownerFrames = ArrayDeque<Kept>()
        val ownerId: Int get() = memberIdOf(ownerKey.toByteArray())
    }

    internal class Member(val id: Int, val resumeToken: ByteString) {
        var connection: Connection? = null
        var disconnectedAt: Long? = null

        /** The latest frame of each kind it published, per sender: its own, or a radio's as a gateway. */
        val kept = HashMap<Pair<Int, Int>, Kept>()
        val recentPositions = HashMap<Int, ArrayDeque<Long>>()
        val recentOthers = HashMap<Int, ArrayDeque<Long>>()
    }

    /** A frame kept for late joiners; [arrival] counts up with every frame, for their order. */
    internal class Kept(val frame: ByteString, val from: Int, val receivedAt: Long, val arrival: Long)

    private val mutex = Mutex()

    /** Frames received so far. Milliseconds don't order them: a client's card and position often arrive in the same one. */
    private var arrivals = 0L
    private val rooms = HashMap<ByteString, Room>()
    private val seats = HashMap<ByteString, Pair<Room, Member>>()

    fun connect(send: (ServerMessage) -> Unit) = Connection(send)

    suspend fun receive(connection: Connection, message: ClientMessage) = mutex.withLock { dispatch(connection, message) }

    /** The connection closed. The member keeps their seat for [Limits.resumeWindowMs]. */
    suspend fun disconnect(connection: Connection) = mutex.withLock {
        val member = connection.member ?: return@withLock
        if (member.connection === connection) {
            member.connection = null
            member.disconnectedAt = clock()
        }
    }

    /** Frees seats nobody resumed and convoys nobody came back to. Run every few seconds. */
    suspend fun sweep() = mutex.withLock {
        val now = clock()
        for (room in rooms.values.toList()) {
            for (member in room.members.values.toList()) {
                val since = member.disconnectedAt
                if (since != null && now - since > limits.resumeWindowMs) removeMember(room, member, removed = false)
            }
            val emptySince = room.emptySince
            if (room.members.isEmpty() && emptySince != null && now - emptySince > limits.emptyRoomTtlMs) rooms.remove(room.id)
        }
    }

    /** Number of live convoys, for monitoring and tests. */
    suspend fun convoyCount(): Int = mutex.withLock { rooms.size }

    private fun dispatch(connection: Connection, message: ClientMessage) {
        message.hello?.let {
            connection.send(ServerMessage(welcome = Welcome(server = SERVER_NAME, max_convoy_size = limits.maxConvoySize)))
            return
        }
        message.create_convoy?.let { return create(connection, it) }
        message.join_convoy?.let { return join(connection, it) }
        message.resume?.let { return resume(connection, it) }
        message.publish?.let { return publish(connection, it) }
        message.leave_convoy?.let { return leave(connection) }
        message.remove_member?.let { return remove(connection, it) }
        message.hand_over?.let { return handOver(connection, it) }
        message.ping?.let { return connection.send(ServerMessage(pong = Pong())) }
        connection.error(ProtocolError.Code.CODE_BAD_REQUEST, "Unknown or empty message")
    }

    private fun create(connection: Connection, request: CreateConvoy) {
        if (connection.member != null) return connection.error(ProtocolError.Code.CODE_BAD_REQUEST, "Already in a convoy")
        if (request.owner_key.size != ConvoyFrames.KEY_SIZE || memberIdOf(request.owner_key.toByteArray()) != request.member_id) {
            return connection.error(ProtocolError.Code.CODE_BAD_REQUEST, "The member ID isn't the owner key's")
        }
        var id: ByteString
        do id = secureRandomBytes(16).toByteString() while (id in rooms)
        val room = Room(id, secureRandomBytes(16).toByteString(), request.owner_key)
        rooms[id] = room
        val member = seat(room, connection, request.member_id)
        connection.send(ServerMessage(convoy_created = ConvoyCreated(convoy_id = room.id, join_token = room.joinToken)))
        connection.send(ServerMessage(joined = joinedMessage(room, member)))
    }

    private fun join(connection: Connection, request: JoinConvoy) {
        if (connection.member != null) return connection.error(ProtocolError.Code.CODE_BAD_REQUEST, "Already in a convoy")
        val room = rooms[request.convoy_id] ?: return connection.error(ProtocolError.Code.CODE_NOT_FOUND, "No such convoy")
        if (!MessageDigest.isEqual(room.joinToken.toByteArray(), request.join_token.toByteArray())) {
            return connection.error(ProtocolError.Code.CODE_FORBIDDEN, "Wrong join token")
        }
        if (request.member_id in room.removed) return connection.error(ProtocolError.Code.CODE_FORBIDDEN, "Removed from the convoy")
        if (request.member_id in room.members) return connection.error(ProtocolError.Code.CODE_CONFLICT, "Someone in the convoy has that member ID")
        if (room.members.size >= limits.maxConvoySize) return connection.error(ProtocolError.Code.CODE_CONVOY_FULL, "The convoy is full")
        val member = seat(room, connection, request.member_id)
        connection.send(ServerMessage(joined = joinedMessage(room, member)))
        broadcast(room, except = member, ServerMessage(member_joined = MemberJoined(member_id = member.id)))
    }

    private fun resume(connection: Connection, request: Resume) {
        if (connection.member != null) return connection.error(ProtocolError.Code.CODE_BAD_REQUEST, "Already in a convoy")
        val (room, member) = seats[request.resume_token] ?: return connection.error(ProtocolError.Code.CODE_NOT_FOUND, "Seat expired")
        member.connection?.let { previous -> previous.member = null; previous.room = null }
        member.connection = connection
        member.disconnectedAt = null
        connection.room = room
        connection.member = member
        connection.send(ServerMessage(joined = joinedMessage(room, member)))
    }

    private fun publish(connection: Connection, request: Publish) {
        val room = connection.room
        val member = connection.member
        if (room == null || member == null) return connection.error(ProtocolError.Code.CODE_BAD_REQUEST, "Join a convoy first")
        val frame = request.frame
        if (frame.size > ConvoyFrames.MAX_SIZE) return connection.error(ProtocolError.Code.CODE_BAD_REQUEST, "Frame too large")
        if (frame.size < ConvoyFrames.MIN_SIZE) return connection.error(ProtocolError.Code.CODE_BAD_REQUEST, "Not a frame")
        val header = FrameHeader.parse(frame.toByteArray()) ?: return connection.error(ProtocolError.Code.CODE_BAD_REQUEST, "Not a frame")
        if (header.sender in room.removed) return // removed: dropped
        val now = clock()
        val kind = FrameKind.of(header.kind)
        if (kind == FrameKind.POSITION) {
            if (!member.recentPositions.getOrPut(header.sender) { ArrayDeque() }.admit(now, limits.positionBurst, limits.positionWindowMs)) return // too fast: dropped
        } else if (!member.recentOthers.getOrPut(header.sender) { ArrayDeque() }.admit(now, limits.otherBurst, limits.otherWindowMs)) {
            return connection.error(ProtocolError.Code.CODE_RATE_LIMITED, "Too many frames")
        }
        val kept = Kept(frame, member.id, now, arrivals++)
        when {
            kind == FrameKind.OWNER -> {
                if (header.sender != room.ownerId) return connection.error(ProtocolError.Code.CODE_FORBIDDEN, "Only the owner's frames go out as the owner's")
                room.ownerFrames.addLast(kept)
                while (room.ownerFrames.size > limits.ownerFrames) room.ownerFrames.removeFirst()
            }
            kind?.keptPerSender == true -> member.kept[header.sender to header.kind] = kept
            else -> Unit // events, and kinds this server doesn't know: relayed, not kept
        }
        broadcast(room, except = member, ServerMessage(relay = Relay(frame = frame, from = member.id, server_time_ms = now)))
    }

    private fun leave(connection: Connection) {
        val room = connection.room ?: return
        val member = connection.member ?: return
        connection.room = null
        connection.member = null
        removeMember(room, member, removed = false)
    }

    private fun remove(connection: Connection, request: RemoveMember) {
        val room = connection.room
        val requester = connection.member
        if (room == null || requester == null) return connection.error(ProtocolError.Code.CODE_BAD_REQUEST, "Join a convoy first")
        val signed = OwnerSignatures.removal(room.id.toByteArray(), request.member_id, request.epoch)
        if (!ed25519Verify(room.ownerKey.toByteArray(), signed, request.signature.toByteArray())) {
            return connection.error(ProtocolError.Code.CODE_FORBIDDEN, "Only the convoy's owner can remove members")
        }
        if (request.member_id == room.ownerId) return connection.error(ProtocolError.Code.CODE_BAD_REQUEST, "Leave instead of removing yourself")
        room.removed += request.member_id
        // Seated or not (they may be on a radio only): from now on, nothing from them goes out.
        room.members[request.member_id]?.let { target ->
            target.connection?.let { removed ->
                removed.send(ServerMessage(member_left = MemberLeft(member_id = target.id, removed = true)))
                removed.room = null
                removed.member = null
            }
            removeMember(room, target, removed = true)
        }
        for (member in room.members.values) member.kept.keys.removeAll { (sender, _) -> sender == request.member_id }
        // They still have the invite: a new join token keeps them from coming straight back.
        room.joinToken = secureRandomBytes(16).toByteString()
        broadcast(room, except = null, ServerMessage(invite_changed = InviteChanged(join_token = room.joinToken)))
    }

    private fun handOver(connection: Connection, request: HandOver) {
        val room = connection.room ?: return connection.error(ProtocolError.Code.CODE_BAD_REQUEST, "Join a convoy first")
        val signed = OwnerSignatures.handover(room.id.toByteArray(), request.number, request.new_owner_key.toByteArray())
        if (!ed25519Verify(room.ownerKey.toByteArray(), signed, request.signature.toByteArray())) {
            return connection.error(ProtocolError.Code.CODE_FORBIDDEN, "Only the convoy's owner can hand it over")
        }
        if (request.number != room.handovers + 1 || request.new_owner_key.size != ConvoyFrames.KEY_SIZE) {
            return connection.error(ProtocolError.Code.CODE_BAD_REQUEST, "Not the next handover")
        }
        room.handovers = request.number
        room.ownerKey = request.new_owner_key
    }

    private fun seat(room: Room, connection: Connection, id: Int): Member {
        val member = Member(id, secureRandomBytes(32).toByteString())
        member.connection = connection
        room.members[id] = member
        room.emptySince = null
        seats[member.resumeToken] = room to member
        connection.room = room
        connection.member = member
        return member
    }

    private fun removeMember(room: Room, member: Member, removed: Boolean) {
        room.members.remove(member.id)
        seats.remove(member.resumeToken)
        broadcast(room, except = member, ServerMessage(member_left = MemberLeft(member_id = member.id, removed = removed)))
        if (room.members.isEmpty()) room.emptySince = clock()
    }

    /** Everyone else seated, and every frame kept, oldest first; but not the frames [member] published itself. */
    private fun joinedMessage(room: Room, member: Member): Joined {
        val now = clock()
        val kept = (room.members.values.filter { it !== member }.flatMap { it.kept.values } + room.ownerFrames.filter { it.from != member.id })
            .sortedBy { it.arrival }
        return Joined(
            convoy_id = room.id,
            member_id = member.id,
            resume_token = member.resumeToken,
            members = room.members.keys.filter { it != member.id },
            join_token = room.joinToken,
            frames = kept.map { Relay(frame = it.frame, from = it.from, server_time_ms = it.receivedAt, age_ms = (now - it.receivedAt).coerceAtLeast(0)) },
        )
    }

    /** Sliding-window rate limit over arrival times: admits (and records) [now] unless [burst] arrived in the last [windowMs]. */
    private fun ArrayDeque<Long>.admit(now: Long, burst: Int, windowMs: Long): Boolean {
        while (isNotEmpty() && now - first() >= windowMs) removeFirst()
        if (size >= burst) return false
        addLast(now)
        return true
    }

    private fun broadcast(room: Room, except: Member?, message: ServerMessage) {
        for (member in room.members.values) {
            if (member !== except) member.connection?.send?.invoke(message)
        }
    }

    private fun Connection.error(code: ProtocolError.Code, text: String) {
        send(ServerMessage(error = ProtocolError(code = code, message = text)))
    }

    companion object {
        const val SERVER_NAME = "foxnav-server"
    }
}

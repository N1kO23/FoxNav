package app.nullpointr.foxnav.server.convoy

import app.nullpointr.foxnav.protocol.convoy.v0.Channel
import app.nullpointr.foxnav.protocol.convoy.v0.ClientMessage
import app.nullpointr.foxnav.protocol.convoy.v0.ConvoyCreated
import app.nullpointr.foxnav.protocol.convoy.v0.JoinConvoy
import app.nullpointr.foxnav.protocol.convoy.v0.Joined
import app.nullpointr.foxnav.protocol.convoy.v0.MemberJoined
import app.nullpointr.foxnav.protocol.convoy.v0.MemberLeft
import app.nullpointr.foxnav.protocol.convoy.v0.Pong
import app.nullpointr.foxnav.protocol.convoy.v0.Publish
import app.nullpointr.foxnav.protocol.convoy.v0.Relay
import app.nullpointr.foxnav.protocol.convoy.v0.RemoveMember
import app.nullpointr.foxnav.protocol.convoy.v0.Resume
import app.nullpointr.foxnav.protocol.convoy.v0.Sealed
import app.nullpointr.foxnav.protocol.convoy.v0.ServerMessage
import app.nullpointr.foxnav.protocol.convoy.v0.Welcome
import app.nullpointr.foxnav.shared.convoy.secureRandomBytes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.MessageDigest
import app.nullpointr.foxnav.protocol.convoy.v0.Error as ProtocolError
import app.nullpointr.foxnav.protocol.convoy.v0.Member as ProtocolMember

/**
 * The convoy relay (protocol/SPEC.md §2.2): rooms of members that forward end-to-end encrypted
 * payloads to each other. It never sees inside a payload. Rooms live in memory only.
 */
class ConvoyRelay(
    private val limits: Limits = Limits(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Limits(
        val maxConvoySize: Int = 20,
        /**
         * At most [stateBurst] STATE payloads per member per [stateWindowMs]; more are dropped.
         * Clients send one a second, but after a network stall several arrive at once.
         */
        val stateBurst: Int = 5,
        val stateWindowMs: Long = 3_000,
        /** At most [eventBurst] EVENT payloads per member per [eventWindowMs]. */
        val eventBurst: Int = 10,
        val eventWindowMs: Long = 10_000,
        val maxCiphertextBytes: Int = 4_096,
        /** How long a dropped member keeps their seat and can resume. */
        val resumeWindowMs: Long = 5 * 60_000,
        /** How long a convoy with nobody in it stays joinable. */
        val emptyRoomTtlMs: Long = 60 * 60_000,
    )

    /** One client connection; [send] queues a message for it. */
    class Connection internal constructor(internal val send: (ServerMessage) -> Unit) {
        internal var room: Room? = null
        internal var member: Member? = null
    }

    internal class Room(val id: ByteString, val joinToken: ByteString) {
        val members = LinkedHashMap<Long, Member>()
        var owner = 0L
        var emptySince: Long? = null
    }

    internal class Member(val id: Long, val resumeToken: ByteString) {
        var connection: Connection? = null
        var lastState: Sealed? = null
        var lastStateAt = 0L
        var disconnectedAt: Long? = null
        val recentStates = ArrayDeque<Long>()
        val recentEvents = ArrayDeque<Long>()
    }

    private val mutex = Mutex()
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
        message.create_convoy?.let { return create(connection) }
        message.join_convoy?.let { return join(connection, it) }
        message.resume?.let { return resume(connection, it) }
        message.publish?.let { return publish(connection, it) }
        message.leave_convoy?.let { return leave(connection) }
        message.remove_member?.let { return remove(connection, it) }
        message.ping?.let { return connection.send(ServerMessage(pong = Pong())) }
        connection.error(ProtocolError.Code.CODE_BAD_REQUEST, "Unknown or empty message")
    }

    private fun create(connection: Connection) {
        if (connection.member != null) return connection.error(ProtocolError.Code.CODE_BAD_REQUEST, "Already in a convoy")
        var id: ByteString
        do id = secureRandomBytes(16).toByteString() while (id in rooms)
        val room = Room(id, secureRandomBytes(16).toByteString())
        rooms[id] = room
        val member = seat(room, connection)
        room.owner = member.id
        connection.send(ServerMessage(convoy_created = ConvoyCreated(convoy_id = room.id, join_token = room.joinToken)))
        connection.send(ServerMessage(joined = joinedMessage(room, member)))
    }

    private fun join(connection: Connection, request: JoinConvoy) {
        if (connection.member != null) return connection.error(ProtocolError.Code.CODE_BAD_REQUEST, "Already in a convoy")
        val room = rooms[request.convoy_id] ?: return connection.error(ProtocolError.Code.CODE_NOT_FOUND, "No such convoy")
        if (!MessageDigest.isEqual(room.joinToken.toByteArray(), request.join_token.toByteArray())) {
            return connection.error(ProtocolError.Code.CODE_FORBIDDEN, "Wrong join token")
        }
        if (room.members.size >= limits.maxConvoySize) return connection.error(ProtocolError.Code.CODE_CONVOY_FULL, "The convoy is full")
        val member = seat(room, connection)
        if (room.owner == 0L) room.owner = member.id
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
        val sealed = request.payload ?: return connection.error(ProtocolError.Code.CODE_BAD_REQUEST, "Nothing to publish")
        if (sealed.ciphertext.size > limits.maxCiphertextBytes) return connection.error(ProtocolError.Code.CODE_BAD_REQUEST, "Payload too large")
        val now = clock()
        when (request.channel) {
            Channel.CHANNEL_STATE -> {
                if (!member.recentStates.admit(now, limits.stateBurst, limits.stateWindowMs)) return // too fast: dropped
                member.lastState = sealed
                member.lastStateAt = now
            }
            Channel.CHANNEL_EVENT -> {
                if (!member.recentEvents.admit(now, limits.eventBurst, limits.eventWindowMs)) {
                    return connection.error(ProtocolError.Code.CODE_RATE_LIMITED, "Too many events")
                }
            }
            else -> return connection.error(ProtocolError.Code.CODE_BAD_REQUEST, "Unknown channel")
        }
        broadcast(room, except = member, ServerMessage(relay = Relay(sender = member.id, channel = request.channel, payload = sealed, server_time_ms = now)))
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
        if (room.owner != requester.id) return connection.error(ProtocolError.Code.CODE_FORBIDDEN, "Only the convoy's owner can remove members")
        val target = room.members[request.member_id] ?: return connection.error(ProtocolError.Code.CODE_NOT_FOUND, "No such member")
        if (target === requester) return connection.error(ProtocolError.Code.CODE_BAD_REQUEST, "Leave instead of removing yourself")
        target.connection?.let { removed ->
            removed.send(ServerMessage(member_left = MemberLeft(member_id = target.id, removed = true)))
            removed.room = null
            removed.member = null
        }
        removeMember(room, target, removed = true)
    }

    private fun seat(room: Room, connection: Connection): Member {
        var id: Long
        do id = secureRandomBytes(8).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) } and Long.MAX_VALUE
        while (id == 0L || id in room.members)
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
        var newOwner = 0L
        if (room.owner == member.id) {
            room.owner = room.members.keys.firstOrNull() ?: 0L
            newOwner = room.owner
        }
        broadcast(room, except = member, ServerMessage(member_left = MemberLeft(member_id = member.id, removed = removed, new_owner = newOwner)))
        if (room.members.isEmpty()) room.emptySince = clock()
    }

    private fun joinedMessage(room: Room, member: Member): Joined {
        val now = clock()
        return Joined(
            convoy_id = room.id,
            member_id = member.id,
            resume_token = member.resumeToken,
            members = room.members.values.filter { it !== member }.map { other ->
                val age = if (other.lastState != null) (now - other.lastStateAt).coerceAtLeast(0) else 0
                ProtocolMember(member_id = other.id, last_state = other.lastState, last_state_age_ms = age)
            },
            owner = room.owner,
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

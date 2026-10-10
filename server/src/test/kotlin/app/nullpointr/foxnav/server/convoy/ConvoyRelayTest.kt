package app.nullpointr.foxnav.server.convoy

import app.nullpointr.foxnav.protocol.convoy.v0.Channel
import app.nullpointr.foxnav.protocol.convoy.v0.ClientMessage
import app.nullpointr.foxnav.protocol.convoy.v0.CreateConvoy
import app.nullpointr.foxnav.protocol.convoy.v0.JoinConvoy
import app.nullpointr.foxnav.protocol.convoy.v0.Joined
import app.nullpointr.foxnav.protocol.convoy.v0.LeaveConvoy
import app.nullpointr.foxnav.protocol.convoy.v0.Publish
import app.nullpointr.foxnav.protocol.convoy.v0.RemoveMember
import app.nullpointr.foxnav.protocol.convoy.v0.Resume
import app.nullpointr.foxnav.protocol.convoy.v0.Sealed
import app.nullpointr.foxnav.protocol.convoy.v0.ServerMessage
import kotlinx.coroutines.test.runTest
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.nullpointr.foxnav.protocol.convoy.v0.Error as ProtocolError

class ConvoyRelayTest {
    private var now = 1_000_000L
    private val relay = ConvoyRelay(ConvoyRelay.Limits(maxConvoySize = 3, stateBurst = 2, stateWindowMs = 2_000, eventBurst = 2), clock = { now })

    private inner class Client {
        val inbox = mutableListOf<ServerMessage>()
        val connection = relay.connect { inbox += it }

        suspend fun send(message: ClientMessage) = relay.receive(connection, message)

        fun <T : Any> last(pick: (ServerMessage) -> T?): T = inbox.mapNotNull(pick).last()

        fun <T : Any> all(pick: (ServerMessage) -> T?): List<T> = inbox.mapNotNull(pick)

        fun errors(): List<ProtocolError.Code> = all { it.error }.map { it.code }
    }

    private fun opaque(text: String) = Sealed(key_epoch = 0, nonce = ByteString.of(1, 2, 3), ciphertext = text.encodeUtf8())

    private fun publish(channel: Channel, text: String) = ClientMessage(publish = Publish(channel = channel, payload = opaque(text)))

    /** Creates a convoy with [owner]; returns its id and join token. */
    private suspend fun create(owner: Client): Pair<ByteString, ByteString> {
        owner.send(ClientMessage(create_convoy = CreateConvoy()))
        val created = owner.last { it.convoy_created }
        return created.convoy_id to created.join_token
    }

    private suspend fun Client.join(convoy: Pair<ByteString, ByteString>): Joined? {
        send(ClientMessage(join_convoy = JoinConvoy(convoy_id = convoy.first, join_token = convoy.second)))
        return all { it.joined }.lastOrNull()
    }

    @Test
    fun createJoinAndRelayOpaquePayloads() = runTest {
        val owner = Client()
        val convoy = create(owner)
        val ownerSeat = owner.last { it.joined }
        assertEquals(ownerSeat.member_id, ownerSeat.owner)

        val member = Client()
        val seat = member.join(convoy)!!
        assertEquals(ownerSeat.member_id, seat.owner)
        assertEquals(seat.member_id, owner.last { it.member_joined }.member_id)

        member.send(publish(Channel.CHANNEL_STATE, "position"))
        val relayed = owner.last { it.relay }
        assertEquals(seat.member_id, relayed.sender)
        assertEquals(opaque("position"), relayed.payload) // byte for byte: the server never looks inside
        assertTrue(member.all { it.relay }.isEmpty()) // not echoed back
    }

    @Test
    fun lateJoinersGetEveryonesLatestState() = runTest {
        val owner = Client()
        val convoy = create(owner)
        owner.send(publish(Channel.CHANNEL_STATE, "first"))
        now += 1_000
        owner.send(publish(Channel.CHANNEL_STATE, "latest"))
        owner.send(publish(Channel.CHANNEL_EVENT, "fuel stop")) // events aren't kept
        now += 4_000

        val late = Client()
        val seat = late.join(convoy)!!
        assertEquals(listOf(opaque("latest")), seat.members.map { it.last_state })
        assertEquals(listOf(4_000L), seat.members.map { it.last_state_age_ms })
    }

    @Test
    fun wrongTokensUnknownConvoysAndFullConvoysAreRejected() = runTest {
        val owner = Client()
        val convoy = create(owner)

        val stranger = Client()
        assertNull(stranger.join(convoy.first to "guess".encodeUtf8()))
        assertNull(stranger.join(ByteString.of(*ByteArray(16)) to convoy.second))
        assertEquals(listOf(ProtocolError.Code.CODE_FORBIDDEN, ProtocolError.Code.CODE_NOT_FOUND), stranger.errors())

        Client().join(convoy)
        Client().join(convoy)
        val fourth = Client()
        assertNull(fourth.join(convoy))
        assertEquals(listOf(ProtocolError.Code.CODE_CONVOY_FULL), fourth.errors())
    }

    @Test
    fun stateBurstsAreCappedAndEventsAreLimited() = runTest {
        val owner = Client()
        val convoy = create(owner)
        val member = Client()
        member.join(convoy)

        member.send(publish(Channel.CHANNEL_STATE, "a"))
        now += 100
        member.send(publish(Channel.CHANNEL_STATE, "b")) // queued behind a network stall: still relayed
        now += 100
        member.send(publish(Channel.CHANNEL_STATE, "c")) // over the burst: dropped
        now += 2_000
        member.send(publish(Channel.CHANNEL_STATE, "d"))
        assertEquals(listOf("a", "b", "d"), owner.all { it.relay }.map { it.payload!!.ciphertext.utf8() })

        repeat(3) { member.send(publish(Channel.CHANNEL_EVENT, "event")) }
        assertEquals(2, owner.all { it.relay }.count { it.channel == Channel.CHANNEL_EVENT })
        assertEquals(listOf(ProtocolError.Code.CODE_RATE_LIMITED), member.errors())
    }

    @Test
    fun onlyTheOwnerRemovesAndOwnershipMovesOnWhenTheOwnerLeaves() = runTest {
        val owner = Client()
        val convoy = create(owner)
        val second = Client()
        val secondSeat = second.join(convoy)!!
        val third = Client()
        val thirdSeat = third.join(convoy)!!

        second.send(ClientMessage(remove_member = RemoveMember(member_id = thirdSeat.member_id)))
        assertEquals(listOf(ProtocolError.Code.CODE_FORBIDDEN), second.errors())

        owner.send(ClientMessage(remove_member = RemoveMember(member_id = thirdSeat.member_id)))
        assertTrue(third.last { it.member_left }.removed)
        assertEquals(thirdSeat.member_id, second.last { it.member_left }.member_id)
        third.send(publish(Channel.CHANNEL_STATE, "still here?"))
        assertEquals(listOf(ProtocolError.Code.CODE_BAD_REQUEST), third.errors())

        owner.send(ClientMessage(leave_convoy = LeaveConvoy()))
        assertEquals(secondSeat.member_id, second.last { it.member_left }.new_owner)
    }

    @Test
    fun removingSomeoneChangesTheJoinToken() = runTest {
        val owner = Client()
        val convoy = create(owner)
        val rider = Client()
        val riderSeat = rider.join(convoy)!!
        val other = Client()
        other.join(convoy)

        owner.send(ClientMessage(remove_member = RemoveMember(member_id = riderSeat.member_id)))
        val newToken = other.last { it.invite_changed }.join_token
        assertEquals(newToken, owner.last { it.invite_changed }.join_token)
        assertTrue(rider.all { it.invite_changed }.isEmpty())
        assertNotEquals(convoy.second, newToken)

        // The removed member still has the old invite; it no longer works. The new one does.
        val comeback = Client()
        assertNull(comeback.join(convoy))
        assertEquals(listOf(ProtocolError.Code.CODE_FORBIDDEN), comeback.errors())
        assertEquals(newToken, Client().join(convoy.first to newToken)!!.join_token)
    }

    @Test
    fun onlyTheOwnerRotatesKeysAndLaterArrivalsGetTheLatestRotations() = runTest {
        val owner = Client()
        val convoy = create(owner)
        val member = Client()
        member.join(convoy)

        member.send(publish(Channel.CHANNEL_KEY, "not yours to rotate"))
        assertEquals(listOf(ProtocolError.Code.CODE_FORBIDDEN), member.errors())

        repeat(10) { index ->
            now += 6_000
            owner.send(publish(Channel.CHANNEL_KEY, "key $index"))
        }
        assertEquals(10, member.all { it.relay }.count { it.channel == Channel.CHANNEL_KEY })
        val late = Client().join(convoy)!!
        assertEquals((2..9).map { "key $it" }, late.key_history.map { it.payload!!.ciphertext.utf8() })
    }

    @Test
    fun droppedMembersResumeTheirSeat() = runTest {
        val owner = Client()
        val convoy = create(owner)
        val member = Client()
        val seat = member.join(convoy)!!
        relay.disconnect(member.connection)

        now += 60_000
        relay.sweep()
        owner.send(publish(Channel.CHANNEL_STATE, "while you were away"))

        val reconnected = Client()
        reconnected.send(ClientMessage(resume = Resume(resume_token = seat.resume_token)))
        val resumed = reconnected.last { it.joined }
        assertEquals(seat.member_id, resumed.member_id)
        assertEquals(listOf(opaque("while you were away")), resumed.members.map { it.last_state })
        assertTrue(owner.all { it.member_left }.isEmpty()) // nobody was told they'd gone
    }

    @Test
    fun sweepFreesSeatsNobodyResumedAndConvoysNobodyCameBackTo() = runTest {
        val owner = Client()
        val convoy = create(owner)
        val member = Client()
        val seat = member.join(convoy)!!
        relay.disconnect(member.connection)

        now += 6 * 60_000
        relay.sweep()
        assertEquals(seat.member_id, owner.last { it.member_left }.member_id)
        val late = Client()
        late.send(ClientMessage(resume = Resume(resume_token = seat.resume_token)))
        assertEquals(listOf(ProtocolError.Code.CODE_NOT_FOUND), late.errors())

        owner.send(ClientMessage(leave_convoy = LeaveConvoy()))
        now += 30 * 60_000
        relay.sweep()
        assertEquals(1, relay.convoyCount()) // empty, but still joinable for a while
        now += 31 * 60_000
        relay.sweep()
        assertEquals(0, relay.convoyCount())
    }
}

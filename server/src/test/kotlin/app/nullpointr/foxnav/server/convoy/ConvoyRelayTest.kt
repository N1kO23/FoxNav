package app.nullpointr.foxnav.server.convoy

import app.nullpointr.foxnav.protocol.convoy.v0.ClientMessage
import app.nullpointr.foxnav.protocol.convoy.v0.CreateConvoy
import app.nullpointr.foxnav.protocol.convoy.v0.HandOver
import app.nullpointr.foxnav.protocol.convoy.v0.JoinConvoy
import app.nullpointr.foxnav.protocol.convoy.v0.Joined
import app.nullpointr.foxnav.protocol.convoy.v0.LeaveConvoy
import app.nullpointr.foxnav.protocol.convoy.v0.Publish
import app.nullpointr.foxnav.protocol.convoy.v0.RemoveMember
import app.nullpointr.foxnav.protocol.convoy.v0.Resume
import app.nullpointr.foxnav.protocol.convoy.v0.ServerMessage
import app.nullpointr.foxnav.shared.convoy.ConvoyFrames
import app.nullpointr.foxnav.shared.convoy.FrameHeader
import app.nullpointr.foxnav.shared.convoy.FrameKind
import app.nullpointr.foxnav.shared.convoy.Identity
import app.nullpointr.foxnav.shared.convoy.OwnerSignatures
import kotlinx.coroutines.test.runTest
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.nullpointr.foxnav.protocol.convoy.v0.Error as ProtocolError

class ConvoyRelayTest {
    private var now = 1_000_000L
    private val relay = ConvoyRelay(ConvoyRelay.Limits(maxConvoySize = 3, positionBurst = 2, positionWindowMs = 2_000, otherBurst = 2), clock = { now })

    private inner class Client(val identity: Identity = Identity.generate()) {
        val inbox = mutableListOf<ServerMessage>()
        val connection = relay.connect { inbox += it }
        val id: Int get() = identity.memberId

        suspend fun send(message: ClientMessage) = relay.receive(connection, message)

        suspend fun publish(kind: FrameKind, text: String, sender: Int = id) = send(ClientMessage(publish = Publish(frame = frame(kind, sender, text))))

        fun <T : Any> last(pick: (ServerMessage) -> T?): T = inbox.mapNotNull(pick).last()

        fun <T : Any> all(pick: (ServerMessage) -> T?): List<T> = inbox.mapNotNull(pick)

        fun errors(): List<ProtocolError.Code> = all { it.error }.map { it.code }
    }

    /** A frame as the relay sees it: a header, then bytes it can't read (here, [text]). */
    private fun frame(kind: FrameKind, sender: Int, text: String): ByteString {
        val body = text.encodeUtf8().toByteArray()
        return (FrameHeader(kind.value, 0x0BAD_CAFE, sender, 1).toBytes() + body + ByteArray(ConvoyFrames.MIN_SIZE - FrameHeader.SIZE - body.size)).toByteString()
    }

    private fun ByteString.text() = substring(FrameHeader.SIZE).utf8().trimEnd('\u0000')

    private data class Convoy(val id: ByteString, val token: ByteString)

    private suspend fun create(owner: Client): Convoy {
        owner.send(ClientMessage(create_convoy = CreateConvoy(member_id = owner.id, owner_key = owner.identity.signingKey.toByteString())))
        val created = owner.last { it.convoy_created }
        return Convoy(created.convoy_id, created.join_token)
    }

    private suspend fun Client.join(convoy: Convoy, token: ByteString = convoy.token): Joined? {
        send(ClientMessage(join_convoy = JoinConvoy(convoy_id = convoy.id, join_token = token, member_id = id)))
        return all { it.joined }.lastOrNull()
    }

    private fun Client.removal(convoy: Convoy, target: Int, epoch: Int = 1) = ClientMessage(
        remove_member = RemoveMember(member_id = target, epoch = epoch, signature = identity.sign(OwnerSignatures.removal(convoy.id.toByteArray(), target, epoch)).toByteString()),
    )

    private fun Client.handover(convoy: Convoy, to: Client, number: Int) = ClientMessage(
        hand_over = HandOver(
            number = number,
            new_owner_key = to.identity.signingKey.toByteString(),
            signature = identity.sign(OwnerSignatures.handover(convoy.id.toByteArray(), number, to.identity.signingKey)).toByteString(),
        ),
    )

    @Test
    fun membersComeWithTheirOwnIdsAndFramesGoOutAsTheyAre() = runTest {
        val owner = Client()
        val convoy = create(owner)
        assertEquals(owner.id, owner.last { it.joined }.member_id)

        val member = Client()
        val seat = member.join(convoy)!!
        assertEquals(member.id, seat.member_id)
        assertEquals(listOf(owner.id), seat.members)
        assertEquals(member.id, owner.last { it.member_joined }.member_id)

        member.publish(FrameKind.POSITION, "position")
        val relayed = owner.last { it.relay }
        assertEquals(member.id, relayed.from)
        assertEquals(frame(FrameKind.POSITION, member.id, "position"), relayed.frame) // byte for byte
        assertTrue(member.all { it.relay }.isEmpty()) // not echoed back
    }

    @Test
    fun theCreatorsIdMustBeTheirKeysAndIdsAreUnique() = runTest {
        val owner = Client()
        owner.send(ClientMessage(create_convoy = CreateConvoy(member_id = owner.id + 1, owner_key = owner.identity.signingKey.toByteString())))
        assertEquals(listOf(ProtocolError.Code.CODE_BAD_REQUEST), owner.errors())

        val convoy = create(owner)
        val twin = Client(owner.identity)
        assertNull(twin.join(convoy))
        assertEquals(listOf(ProtocolError.Code.CODE_CONFLICT), twin.errors())
    }

    @Test
    fun lateJoinersGetTheLatestOfEachKindPerSender() = runTest {
        val owner = Client()
        val convoy = create(owner)
        owner.publish(FrameKind.CARD, "card")
        owner.publish(FrameKind.POSITION, "first")
        now += 1_000
        owner.publish(FrameKind.POSITION, "latest")
        owner.publish(FrameKind.EVENT, "fuel stop") // events aren't kept
        owner.send(ClientMessage(publish = Publish(frame = (byteArrayOf(99) + frame(FrameKind.CARD, owner.id, "?").toByteArray().drop(1)).toByteString())))
        val gateway = Client()
        gateway.join(convoy)
        now += 1_000
        gateway.publish(FrameKind.POSITION, "from the radio", sender = 0x0D10)
        now += 3_000

        val late = Client()
        val seat = late.join(convoy)!!
        assertEquals(listOf("card", "latest", "from the radio"), seat.frames.map { it.frame.text() })
        assertEquals(listOf(owner.id, owner.id, gateway.id), seat.frames.map { it.from })
        assertEquals(listOf(5_000L, 4_000L, 3_000L), seat.frames.map { it.age_ms })
        assertEquals(listOf(owner.id, gateway.id), seat.members)
    }

    @Test
    fun onlyFramesGoThrough() = runTest {
        val owner = Client()
        create(owner)
        owner.send(ClientMessage(publish = Publish(frame = ByteArray(ConvoyFrames.MAX_SIZE + 1).toByteString())))
        owner.send(ClientMessage(publish = Publish(frame = ByteArray(ConvoyFrames.MIN_SIZE - 1).toByteString())))
        assertEquals(listOf(ProtocolError.Code.CODE_BAD_REQUEST, ProtocolError.Code.CODE_BAD_REQUEST), owner.errors())
    }

    @Test
    fun wrongTokensUnknownConvoysAndFullConvoysAreRejected() = runTest {
        val owner = Client()
        val convoy = create(owner)

        val stranger = Client()
        assertNull(stranger.join(convoy, "guess".encodeUtf8()))
        assertNull(stranger.join(Convoy(ByteString.of(*ByteArray(16)), convoy.token)))
        assertEquals(listOf(ProtocolError.Code.CODE_FORBIDDEN, ProtocolError.Code.CODE_NOT_FOUND), stranger.errors())

        Client().join(convoy)
        Client().join(convoy)
        val fourth = Client()
        assertNull(fourth.join(convoy))
        assertEquals(listOf(ProtocolError.Code.CODE_CONVOY_FULL), fourth.errors())
    }

    @Test
    fun positionBurstsAreCappedAndOtherKindsLimitedPerSender() = runTest {
        val owner = Client()
        val convoy = create(owner)
        val member = Client()
        member.join(convoy)

        member.publish(FrameKind.POSITION, "a")
        now += 100
        member.publish(FrameKind.POSITION, "b") // queued behind a network stall: still relayed
        now += 100
        member.publish(FrameKind.POSITION, "c") // over the burst: dropped
        member.publish(FrameKind.POSITION, "radio", sender = 0x0D10) // another sender, through a gateway
        now += 2_000
        member.publish(FrameKind.POSITION, "d")
        assertEquals(listOf("a", "b", "radio", "d"), owner.all { it.relay }.map { it.frame.text() })

        repeat(3) { member.publish(FrameKind.EVENT, "event") }
        assertEquals(2, owner.all { it.relay }.count { it.frame.text() == "event" })
        assertEquals(listOf(ProtocolError.Code.CODE_RATE_LIMITED), member.errors())
    }

    @Test
    fun removalsTakeTheOwnersSignature() = runTest {
        val owner = Client()
        val convoy = create(owner)
        val second = Client()
        second.join(convoy)
        val third = Client()
        third.join(convoy)

        second.send(second.removal(convoy, third.id)) // signed, but not by the owner
        owner.send(owner.removal(convoy, third.id).let { it.copy(remove_member = it.remove_member!!.copy(epoch = 2)) }) // not what was signed
        assertEquals(listOf(ProtocolError.Code.CODE_FORBIDDEN), second.errors())
        assertEquals(listOf(ProtocolError.Code.CODE_FORBIDDEN), owner.errors())

        // Anyone may deliver the owner's signed removal; it's the signature that counts.
        second.send(owner.removal(convoy, third.id))
        assertTrue(third.last { it.member_left }.removed)
        assertEquals(third.id, second.last { it.member_left }.member_id)
        third.publish(FrameKind.POSITION, "still here?")
        assertEquals(listOf(ProtocolError.Code.CODE_BAD_REQUEST), third.errors())
        // Nor do their frames come through anyone else.
        second.publish(FrameKind.POSITION, "theirs, from the radio", sender = third.id)
        assertTrue(owner.all { it.relay }.none { it.frame.text() == "theirs, from the radio" })
    }

    @Test
    fun removingSomeoneChangesTheJoinTokenAndKeepsThemOut() = runTest {
        val owner = Client()
        val convoy = create(owner)
        val rider = Client()
        rider.join(convoy)
        val other = Client()
        other.join(convoy)

        owner.send(owner.removal(convoy, rider.id))
        val newToken = other.last { it.invite_changed }.join_token
        assertEquals(newToken, owner.last { it.invite_changed }.join_token)
        assertTrue(rider.all { it.invite_changed }.isEmpty())
        assertNotEquals(convoy.token, newToken)

        // The removed member still has the old invite; it no longer works. The new one does, but not for them.
        val comeback = Client(rider.identity)
        assertNull(comeback.join(convoy))
        assertNull(comeback.join(convoy, newToken))
        assertEquals(listOf(ProtocolError.Code.CODE_FORBIDDEN, ProtocolError.Code.CODE_FORBIDDEN), comeback.errors())
        assertEquals(newToken, Client().join(convoy, newToken)!!.join_token)
    }

    @Test
    fun ownerFramesComeOnlyFromTheOwnerAndStayForLaterArrivals() = runTest {
        val owner = Client()
        val convoy = create(owner)
        val member = Client()
        member.join(convoy)

        member.publish(FrameKind.OWNER, "not yours to say")
        assertEquals(listOf(ProtocolError.Code.CODE_FORBIDDEN), member.errors())

        repeat(70) { index ->
            now += 6_000 // within the rate limit
            owner.publish(FrameKind.OWNER, "statement $index")
        }
        assertEquals(70, member.all { it.relay }.size)
        member.send(ClientMessage(leave_convoy = LeaveConvoy()))
        val late = Client().join(convoy)!!
        assertEquals((6..69).map { "statement $it" }, late.frames.map { it.frame.text() })
    }

    @Test
    fun handoversTakeTheOwnersSignatureInOrder() = runTest {
        val owner = Client()
        val convoy = create(owner)
        val heir = Client()
        heir.join(convoy)
        val other = Client()
        other.join(convoy)

        heir.send(heir.handover(convoy, to = heir, number = 1))
        owner.send(owner.handover(convoy, to = heir, number = 2))
        assertEquals(listOf(ProtocolError.Code.CODE_FORBIDDEN), heir.errors())
        assertEquals(listOf(ProtocolError.Code.CODE_BAD_REQUEST), owner.errors())

        owner.send(owner.handover(convoy, to = heir, number = 1))
        owner.send(ClientMessage(leave_convoy = LeaveConvoy()))
        assertEquals(owner.id, heir.last { it.member_left }.member_id)
        // The new owner speaks for the convoy now; the old one doesn't.
        heir.publish(FrameKind.OWNER, "rotation")
        assertEquals("rotation", other.last { it.relay }.frame.text())
        other.send(owner.removal(convoy, other.id))
        assertEquals(listOf(ProtocolError.Code.CODE_FORBIDDEN), other.errors())
        heir.send(heir.removal(convoy, other.id))
        assertTrue(other.last { it.member_left }.removed)
        // An old handover again changes nothing.
        heir.send(owner.handover(convoy, to = heir, number = 1))
        assertEquals(listOf(ProtocolError.Code.CODE_FORBIDDEN, ProtocolError.Code.CODE_FORBIDDEN), heir.errors())
    }

    @Test
    fun droppedMembersResumeTheirSeat() = runTest {
        val owner = Client()
        val convoy = create(owner)
        val member = Client()
        val seat = member.join(convoy)!!
        member.publish(FrameKind.CARD, "mine")
        relay.disconnect(member.connection)

        now += 60_000
        relay.sweep()
        owner.publish(FrameKind.POSITION, "while you were away")

        val reconnected = Client(member.identity)
        reconnected.send(ClientMessage(resume = Resume(resume_token = seat.resume_token)))
        val resumed = reconnected.last { it.joined }
        assertEquals(member.id, resumed.member_id)
        assertEquals(listOf("while you were away"), resumed.frames.map { it.frame.text() }) // not its own card
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
        assertEquals(member.id, owner.last { it.member_left }.member_id)
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

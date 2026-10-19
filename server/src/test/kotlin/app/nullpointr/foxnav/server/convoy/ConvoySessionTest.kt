package app.nullpointr.foxnav.server.convoy

import app.nullpointr.foxnav.protocol.convoy.v0.NowPlaying
import app.nullpointr.foxnav.protocol.convoy.v0.Profile
import app.nullpointr.foxnav.protocol.convoy.v0.QuickMessage
import app.nullpointr.foxnav.shared.convoy.ConvoyEvent
import app.nullpointr.foxnav.shared.convoy.ConvoySession
import app.nullpointr.foxnav.shared.convoy.ConvoyStatus
import app.nullpointr.foxnav.shared.convoy.EndReason
import app.nullpointr.foxnav.shared.convoy.Fix
import app.nullpointr.foxnav.shared.convoy.Invite
import app.nullpointr.foxnav.shared.convoy.memberIdOf
import app.nullpointr.foxnav.shared.convoy.memberState
import app.nullpointr.foxnav.shared.convoy.toMemberId
import app.nullpointr.foxnav.shared.geo.GeoPoint
import io.ktor.client.HttpClient
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/** The shared convoy client against the relay, through the real realtime endpoint. */
class ConvoySessionTest {
    private val relayClock = AtomicLong(1_000_000)
    private val relay = ConvoyRelay(clock = relayClock::get)

    /** Server side of every connection, in the order they opened, so tests can cut them. */
    private val sockets = CopyOnWriteArrayList<DefaultWebSocketServerSession>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A session plus everything it has reported so far. */
    private class Member(val session: ConvoySession, scope: CoroutineScope) {
        val events = MutableStateFlow<List<ConvoyEvent>>(emptyList())

        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { session.events.collect { event -> events.update { it + event } } }
            session.start()
        }

        suspend fun id() = (session.status.await("a seat") { it is ConvoyStatus.Live } as ConvoyStatus.Live).memberId

        suspend fun invite() = session.invite.await("the invite") { it != null }!!
    }

    private inner class Fixture(private val http: HttpClient) {
        fun create(timing: ConvoySession.Timing = ConvoySession.Timing()) = member(ConvoySession.Start.Create(SERVER_URL), timing)

        fun join(invite: Invite, timing: ConvoySession.Timing = ConvoySession.Timing()) = member(ConvoySession.Start.Join(invite), timing)

        private fun member(start: ConvoySession.Start, timing: ConvoySession.Timing) =
            Member(ConvoySession(http, REALTIME_PATH, start, scope, System::currentTimeMillis, timing = timing), scope)
    }

    private fun convoyTest(block: suspend Fixture.() -> Unit) = testApplication {
        application {
            install(WebSockets)
            routing {
                webSocket(REALTIME_PATH, protocol = SUBPROTOCOL) {
                    sockets += this
                    serveConvoy(relay)
                }
            }
        }
        val http = createClient { install(ClientWebSockets) }
        try {
            Fixture(http).block()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun membersShareStateAndMessagesEndToEnd() = convoyTest {
        val owner = create()
        val ownerId = owner.id()
        val invite = owner.invite()
        assertEquals(SERVER_URL, invite.server)

        val rider = join(invite)
        val riderId = rider.id()
        assertEquals(ownerId, rider.session.ownerId.value)
        owner.events.await("the rider joining") { ConvoyEvent.MemberJoined(riderId) in it }

        owner.session.updateState(memberState(Profile(display_name = "Aki"), Fix(HELSINKI, speedMps = 22.0, headingDeg = 90.0, timeMs = 1_700_000_000_000)))
        val seen = rider.session.members.await("the owner's state") { it[ownerId]?.profile?.display_name == "Aki" && it[ownerId]?.fix != null }.getValue(ownerId)
        assertEquals("Aki", seen.profile?.display_name)
        assertEquals(HELSINKI.latitude, seen.fix!!.point.latitude, 1e-6)
        assertEquals(HELSINKI.longitude, seen.fix!!.point.longitude, 1e-6)
        assertEquals(22.0, seen.fix!!.speedMps, 1e-3)

        rider.session.sendQuickMessage(QuickMessage.Kind.KIND_FUEL)
        rider.session.dropRegroupPin(HELSINKI, "Neste")
        owner.events.await("the quick message and the pin") { events ->
            ConvoyEvent.QuickMessageReceived(riderId, QuickMessage.Kind.KIND_FUEL) in events &&
                events.any { it is ConvoyEvent.RegroupPinDropped && it.from == riderId && it.name == "Neste" }
        }

        // Someone joining later gets the owner's latest state from the server and decrypts it.
        val late = join(invite)
        val lateId = late.id()
        late.session.members.await("the cached state") { it[ownerId]?.profile?.display_name == "Aki" }

        rider.session.leave()
        assertEquals(ConvoyStatus.Ended(EndReason.LEFT), rider.session.status.value)
        owner.events.await("the rider leaving") { ConvoyEvent.MemberLeft(riderId, removed = false) in it }
        owner.session.members.await("the rider gone") { it.keys == setOf(lateId) }

        owner.session.leave()
        late.session.ownerId.await("ownership to move on") { it == lateId }
    }

    @Test
    fun droppedConnectionsResumeTheSameSeat() = convoyTest {
        val owner = create()
        val ownerId = owner.id()
        val rider = join(owner.invite())
        val riderId = rider.id()

        sockets.forEach { it.close() }
        eventually("both to reconnect") { sockets.size >= 4 }
        assertEquals(ownerId, owner.id())
        assertEquals(riderId, rider.id())

        owner.session.updateState(memberState(Profile(display_name = "Aki"), Fix(HELSINKI, 0.0, 0.0, timeMs = 1)))
        rider.session.members.await("state after resuming") { it[ownerId]?.profile?.display_name == "Aki" }
        assertTrue(owner.events.value.none { it is ConvoyEvent.MemberLeft }, "nobody saw anyone leave")
    }

    @Test
    fun anUnchangedStateIsSentAgainSoTheOthersDontThinkWeAreLost() = convoyTest {
        val owner = create(ConvoySession.Timing(stateIntervalMs = 200, stateRefreshMs = 600))
        val ownerId = owner.id()
        val rider = join(owner.invite())
        rider.id()

        owner.session.updateState(memberState(Profile(display_name = "Parked"), Fix(HELSINKI, 0.0, 0.0, timeMs = 1)))
        val first = rider.session.members.await("the first state") { it[ownerId]?.lastHeardMs != null }.getValue(ownerId).lastHeardMs!!
        // No new state from the owner, yet the rider keeps hearing from them.
        rider.session.members.await("the state again") { (it[ownerId]?.lastHeardMs ?: 0) > first }
    }

    @Test
    fun anExpiredSeatJoinsAgainWithTheInvite() = convoyTest {
        val owner = create()
        owner.id()
        // Reconnects slowly enough for the test to expire the seat first.
        val rider = join(owner.invite(), ConvoySession.Timing(minBackoffMs = 3_000))
        val oldId = rider.id()

        sockets[1].close() // the rider's connection
        eventually("the rider's seat to expire") {
            relayClock.addAndGet(6 * 60_000)
            relay.sweep()
            ConvoyEvent.MemberLeft(oldId, removed = false) in owner.events.value
        }

        // The same identity in a new seat: the others see them leave and come back.
        owner.events.await("the rider back") { events -> events.count { it == ConvoyEvent.MemberJoined(oldId) } == 2 }
        rider.session.status.await("a new seat") { it == ConvoyStatus.Live(oldId) }
    }

    @Test
    fun aMemberWhoseCardCameTooLateForARotationStillGetsTheKey() = convoyTest {
        val owner = create()
        val ownerId = owner.id()
        val invite = owner.invite()
        val removedId = join(invite).id()
        owner.session.members.await("their card") { it[removedId]?.profile != null }
        // The owner removes someone the moment a newcomer is in, likely before the newcomer's card arrives.
        val newcomer = join(invite)
        newcomer.id()
        owner.session.remove(removedId)
        newcomer.session.invite.await("the new key, wrapped for them when their frames came in under the old one") { it?.keyEpoch == 1 }
        owner.session.updateState(memberState(Profile(display_name = "After"), Fix(HELSINKI, 0.0, 0.0, timeMs = 1)))
        newcomer.session.members.await("the owner's new state") { it[ownerId]?.profile?.display_name == "After" }
    }

    @Test
    fun theOwnerRemovesMembers() = convoyTest {
        val owner = create()
        owner.id()
        val rider = join(owner.invite())
        val riderId = rider.id()
        owner.events.await("the rider joining") { ConvoyEvent.MemberJoined(riderId) in it }

        owner.session.remove(riderId)
        rider.session.status.await("the removal") { it == ConvoyStatus.Ended(EndReason.REMOVED) }
        owner.events.await("the removal") { ConvoyEvent.MemberLeft(riderId, removed = true) in it }
    }

    @Test
    fun removingSomeoneRotatesTheKeyAndTheInvite() = convoyTest {
        val owner = create()
        val ownerId = owner.id()
        val oldInvite = owner.invite()
        val removed = join(oldInvite)
        val removedId = removed.id()
        val stays = join(oldInvite)
        stays.id()
        owner.events.await("both joining") { events -> events.count { it is ConvoyEvent.MemberJoined } == 2 }

        owner.session.remove(removedId)
        removed.session.status.await("the removal") { it == ConvoyStatus.Ended(EndReason.REMOVED) }
        // Everyone left gets the new invite: a new join token and the next key.
        val newInvite = stays.session.invite.await("the new invite") { it != null && it.keyEpoch == 1 && !it.joinToken.contentEquals(oldInvite.joinToken) }!!
        owner.session.invite.await("the owner's new invite") { it == newInvite }
        assertFalse(newInvite.key.contentEquals(oldInvite.key))

        // From now on the owner shares under the new key, and the member who stayed reads it.
        owner.session.updateState(memberState(Profile(display_name = "After"), Fix(HELSINKI, 0.0, 0.0, timeMs = 1)))
        stays.session.members.await("the owner's new state") { it[ownerId]?.profile?.display_name == "After" }

        // The old invite gets nobody in any more; the new one does, and its holder reads along.
        join(oldInvite).session.status.await("the refusal") { it == ConvoyStatus.Ended(EndReason.INVITE_INVALID) }
        join(newInvite).session.members.await("the owner's state") { it[ownerId]?.profile?.display_name == "After" }
    }

    @Test
    fun aMemberAwayDuringARotationCatchesUpWhenTheyResume() = convoyTest {
        val owner = create()
        val ownerId = owner.id()
        val invite = owner.invite()
        val removedId = join(invite).id()
        // Comes back slowly, so the rotation happens while they're away.
        val away = join(invite, ConvoySession.Timing(minBackoffMs = 3_000))
        val awayId = away.id()
        // The owner needs their card to send them the next key.
        owner.session.members.await("their card") { it[awayId]?.profile != null }
        sockets.last().close()
        away.session.status.await("the drop") { it is ConvoyStatus.Reconnecting }

        owner.session.remove(removedId)
        owner.session.invite.await("the rotation") { it?.keyEpoch == 1 }
        owner.session.updateState(memberState(Profile(display_name = "Rotated"), Fix(HELSINKI, 0.0, 0.0, timeMs = 1)))

        // Back in the same seat, with the key they missed.
        away.session.status.await("the resume") { it == ConvoyStatus.Live(awayId) }
        away.session.members.await("the owner's new state") { it[ownerId]?.profile?.display_name == "Rotated" }
        assertEquals(1, away.session.invite.value?.keyEpoch)
    }

    @Test
    fun anOlderPositionChangesNothingAndAnEmptySongEndsTheShare() = convoyTest {
        val owner = create()
        val ownerId = owner.id()
        val rider = join(owner.invite())
        rider.id()
        val song = NowPlaying(title = "Plastic Love", artist = "Mariya Takeuchi", app = "Music")
        val profile = Profile(display_name = "Aki", role = Profile.Role.ROLE_LEAD)

        owner.session.updateState(memberState(profile, Fix(HELSINKI, 20.0, 0.0, timeMs = 2_000), nowPlaying = song))
        val seen = rider.session.members.await("the song") { it[ownerId]?.nowPlaying == song }.getValue(ownerId)
        assertEquals(Profile.Role.ROLE_LEAD, seen.profile?.role)

        // A late, older position: it doesn't move them back.
        owner.session.updateState(memberState(profile, Fix(GeoPoint(61.0, 25.0), 30.0, 0.0, timeMs = 1_000)))
        val after = rider.session.members.await("the song to end") { it[ownerId]?.nowPlaying == null }.getValue(ownerId)
        assertEquals(2_000, after.fix?.timeMs)
        assertEquals(HELSINKI.latitude, after.fix!!.point.latitude, 1e-6)
    }

    @Test
    fun theOwnerHandsTheConvoyOverWhenLeaving() = convoyTest {
        val owner = create()
        owner.id()
        val first = join(owner.invite())
        val firstId = first.id()
        val second = join(owner.invite())
        val secondId = second.id()
        owner.session.members.await("both cards") { it[firstId]?.profile != null && it[secondId]?.profile != null }

        owner.session.leave()
        // To whoever has been in the convoy longest; the others follow the owner's signed handover.
        first.session.ownerId.await("the handover") { it == firstId }
        second.session.ownerId.await("the handover") { it == firstId }
        val invite = first.session.invite.await("the new invite") { it?.handovers == 1 }!!
        assertEquals(firstId, memberIdOf(invite.ownerKey).toMemberId())

        // The new owner can remove members, and the rest follow its new key.
        val third = join(invite)
        val thirdId = third.id()
        first.session.members.await("the newcomer's card") { it[thirdId]?.profile != null }
        first.session.remove(thirdId)
        third.session.status.await("the removal") { it == ConvoyStatus.Ended(EndReason.REMOVED) }
        second.session.invite.await("the new key") { it?.keyEpoch == 1 }
    }

    @Test
    fun reconnectNowDoesntWaitForTheBackoff() = convoyTest {
        // A backoff longer than the test waits.
        val owner = create(ConvoySession.Timing(minBackoffMs = 30_000))
        val ownerId = owner.id()
        val before = sockets.size
        owner.session.reconnectNow()
        eventually("a new connection") { sockets.size > before }
        assertEquals(ownerId, owner.id())
    }

    @Test
    fun badInvitesEndTheSession() = convoyTest {
        val owner = create()
        owner.id()
        val invite = owner.invite()

        val forged = join(Invite(invite.server, invite.convoyId, "guessed".encodeToByteArray(), invite.key, invite.ownerKey))
        forged.session.status.await("the refusal") { it == ConvoyStatus.Ended(EndReason.INVITE_INVALID) }

        val unknown = join(Invite(invite.server, ByteArray(16), invite.joinToken, invite.key, invite.ownerKey))
        unknown.session.status.await("the refusal") { it == ConvoyStatus.Ended(EndReason.CONVOY_GONE) }
    }

    private companion object {
        const val SERVER_URL = "http://localhost"
        val HELSINKI = GeoPoint(60.1699, 24.9384)

        suspend fun <T> StateFlow<T>.await(what: String, predicate: (T) -> Boolean): T =
            withTimeoutOrNull(10.seconds) { first(predicate) } ?: fail("Timed out waiting for $what; last value: $value")

        suspend fun eventually(what: String, condition: suspend () -> Boolean) {
            withTimeoutOrNull(10.seconds) { while (!condition()) delay(20) } ?: fail("Timed out waiting for $what")
        }
    }
}

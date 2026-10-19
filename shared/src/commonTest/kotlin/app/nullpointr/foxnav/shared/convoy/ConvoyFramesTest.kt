package app.nullpointr.foxnav.shared.convoy

import app.nullpointr.foxnav.protocol.convoy.v0.Car
import app.nullpointr.foxnav.protocol.convoy.v0.Card
import app.nullpointr.foxnav.protocol.convoy.v0.NowPlaying
import app.nullpointr.foxnav.protocol.convoy.v0.OwnerStatement
import app.nullpointr.foxnav.protocol.convoy.v0.Profile
import app.nullpointr.foxnav.protocol.convoy.v0.RouteShare
import app.nullpointr.foxnav.protocol.convoy.v0.Waypoint
import app.nullpointr.foxnav.protocol.convoy.v0.WrappedKey
import app.nullpointr.foxnav.shared.geo.GeoPoint
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConvoyFramesTest {
    private val convoyId = secureRandomBytes(16)
    private val key = ConvoyKey(0, ConvoyFrames.newKey(), convoyId)
    private val alice = Identity.generate()
    private val bob = Identity.generate()
    private val content = "a position".encodeToByteArray()

    private fun seal(identity: Identity = alice, counter: Long = 1, kind: FrameKind = FrameKind.POSITION, bytes: ByteArray = content) =
        ConvoyFrames.seal(convoyId, key, identity, counter, kind, bytes)

    @Test
    fun opensAndChecksOut() {
        val frame = seal(counter = 7)
        val opened = assertNotNull(ConvoyFrames.open(convoyId, key, frame))
        assertEquals(FrameHeader(FrameKind.POSITION.value, key.tag, alice.memberId, 7), opened.header)
        assertEquals(FrameHeader.parse(frame), opened.header)
        assertContentEquals(content, opened.content)
        assertTrue(opened.signedBy(convoyId, alice.signingKey))
        assertEquals(FrameHeader.SIZE + content.size + ConvoyFrames.SIGNATURE_SIZE + 16, frame.size)
    }

    @Test
    fun memberIdsComeFromKeys() {
        assertEquals(memberIdOf(alice.signingKey), alice.memberId)
        assertEquals(4_294_967_295L, (-1).toMemberId())
    }

    @Test
    fun aMemberCantSendAsAnother() {
        // Bob holds the convoy key too, so the frame opens, but only Bob's key checks out.
        val forged = ConvoyFrames.seal(convoyId, key, bob, 1, FrameKind.POSITION, content).also { alice.memberId.toBytes().copyInto(it, 5) }
        assertNull(ConvoyFrames.open(convoyId, key, forged), "the sender is part of the associated data")
        val bobs = assertNotNull(ConvoyFrames.open(convoyId, key, seal(identity = bob)))
        assertFalse(bobs.signedBy(convoyId, alice.signingKey))
    }

    @Test
    fun boundToItsConvoyKeyAndHeader() {
        val frame = seal()
        assertNull(ConvoyFrames.open(secureRandomBytes(16), key, frame), "another convoy")
        assertNull(ConvoyFrames.open(convoyId, ConvoyKey(1, ConvoyFrames.newKey(), convoyId), frame), "another key")
        for (at in listOf(0, 9, 12, frame.size - 1)) {
            val changed = frame.copyOf().also { it[at] = (it[at] + 1).toByte() }
            assertNull(ConvoyFrames.open(convoyId, key, changed), "byte $at changed")
        }
    }

    @Test
    fun eachKeyHasItsOwnTag() {
        assertNotEquals(key.tag, ConvoyKey(1, ConvoyFrames.newKey(), convoyId).tag)
        assertNotEquals(key.tag, ConvoyKey(0, key.raw, secureRandomBytes(16)).tag)
        assertEquals(key.tag, ConvoyKey(0, key.raw, convoyId).tag)
    }

    @Test
    fun framesFitOnePacket() {
        assertEquals(ConvoyFrames.MAX_SIZE, seal(bytes = ByteArray(ConvoyFrames.MAX_CONTENT)).size)
        assertEquals(140, ConvoyFrames.MAX_CONTENT)
        assertFailsWith<IllegalArgumentException> { seal(bytes = ByteArray(ConvoyFrames.MAX_CONTENT + 1)) }
    }

    @Test
    fun countersCountOnce() {
        val window = ReplayWindow()
        assertFalse(window.isNew(0))
        for (counter in listOf(1L, 3, 2, 70)) {
            assertTrue(window.isNew(counter), "$counter")
            window.accept(counter)
            assertFalse(window.isNew(counter), "$counter again")
        }
        assertTrue(window.isNew(69), "late, but within the window")
        assertTrue(window.isNew(6))
        assertFalse(window.isNew(5), "older than the window")
        window.accept(70 + 64)
        assertFalse(window.isNew(70), "64 below the highest, accepted")
        assertTrue(window.isNew(71))
        window.accept(1_000)
        assertFalse(window.isNew(70 + 64))
        assertTrue(window.isNew(999))
    }

    @Test
    fun aNewKeyOpensOnlyForItsMember() {
        val newKey = ConvoyFrames.newKey()
        val wrapped = assertNotNull(KeyWraps.wrap(convoyId, 1, alice, bob.memberId, bob.exchangeKey, newKey))
        assertEquals(40, wrapped.size)
        assertContentEquals(newKey, KeyWraps.unwrap(convoyId, 1, bob, alice.exchangeKey, wrapped))
        assertNull(KeyWraps.unwrap(convoyId, 1, Identity.generate(), alice.exchangeKey, wrapped), "someone else")
        assertNull(KeyWraps.unwrap(convoyId, 2, bob, alice.exchangeKey, wrapped), "another epoch")
        assertNull(KeyWraps.unwrap(convoyId, 1, bob, Identity.generate().exchangeKey, wrapped), "not from the owner")
        assertNull(KeyWraps.wrap(convoyId, 1, alice, bob.memberId, ByteArray(32), newKey), "a key that gives no secret")
    }

    @Test
    fun ownerSignaturesCheckOut() {
        val removal = OwnerSignatures.removal(convoyId, bob.memberId, 3)
        val signature = alice.sign(removal)
        assertTrue(ed25519Verify(alice.signingKey, removal, signature))
        assertFalse(ed25519Verify(bob.signingKey, removal, signature))
        assertFalse(ed25519Verify(alice.signingKey, OwnerSignatures.removal(convoyId, bob.memberId, 4), signature))
        assertFalse(ed25519Verify(alice.signingKey, removal, signature.copyOf(63)))
        assertFalse(ed25519Verify(ByteArray(3), removal, signature))
    }

    @Test
    fun cardsFitWithLongNames() {
        val long = "Ääkkösiä ".repeat(20)
        val profile = Profile(display_name = long, car = Car(make = long, model = long, color_rgb = 0xFF0000))
        val bytes = FrameContents.card(alice, profile)
        assertTrue(bytes.size <= ConvoyFrames.MAX_CONTENT, "${bytes.size}")
        val card = Card.ADAPTER.decode(bytes)
        assertContentEquals(alice.signingKey, card.signing_key.toByteArray())
        assertContentEquals(alice.exchangeKey, card.exchange_key.toByteArray())
        assertTrue(card.profile!!.display_name.isNotEmpty() && long.startsWith(card.profile!!.display_name))
        assertEquals(0xFF0000, card.profile!!.car!!.color_rgb)
        // A short one stays as it is.
        assertEquals(Profile(display_name = "Aki"), Card.ADAPTER.decode(FrameContents.card(alice, Profile(display_name = "Aki"))).profile)
    }

    @Test
    fun aRouteKeepsItsDestination() {
        val stops = (1..20).map { GeoPoint(60.0 + it / 100.0, 25.0).toWaypoint("Stop number $it") }
        val route = RouteShare.ADAPTER.decode(FrameContents.route(RouteShare(waypoints = stops)))
        assertTrue(route.waypoints.size in 2 until stops.size)
        assertEquals(stops.last(), route.waypoints.last())
        val far = RouteShare(waypoints = listOf(Waypoint(name = "x".repeat(300))))
        assertTrue(FrameContents.route(far).size <= ConvoyFrames.MAX_CONTENT)
        assertTrue(RouteShare.ADAPTER.decode(FrameContents.route(null)).waypoints.isEmpty())
    }

    @Test
    fun songsFit() {
        val bytes = FrameContents.song(NowPlaying(title = "t".repeat(200), artist = "Mariya Takeuchi", app = "Music"))
        assertTrue(bytes.size <= ConvoyFrames.MAX_CONTENT)
        assertEquals("Mariya Takeuchi", NowPlaying.ADAPTER.decode(bytes).artist)
    }

    @Test
    fun aKeyRotationSpansFramesAsNeeded() {
        val keys = (1..9).map { WrappedKey(member_id = it * 1_000_003, key = ByteArray(40) { b -> b.toByte() }.toByteString()) }
        val parts = FrameContents.keyRotation(5, listOf(77), keys).map { OwnerStatement.ADAPTER.decode(it).key_rotation!! }
        assertTrue(parts.size > 1)
        assertEquals(keys, parts.flatMap { it.keys })
        for ((index, part) in parts.withIndex()) {
            assertEquals(5, part.epoch)
            assertEquals(listOf(77), part.removed)
            assertEquals(index + 1, part.part)
            assertEquals(parts.size, part.parts)
        }
        assertTrue(FrameContents.keyRotation(5, listOf(77), keys).all { it.size <= ConvoyFrames.MAX_CONTENT })
        assertEquals(1, FrameContents.keyRotation(5, listOf(77), emptyList()).size, "nobody left to tell")
    }

    @Test
    fun positionsRoundTrip() {
        val fix = Fix(GeoPoint(60.1699, -24.9384), 13.57, -90.4, 1_791_000_000_123)
        val position = fix.toPosition()
        assertEquals(270, position.heading_deg)
        assertEquals(1357, position.speed_cms)
        val back = position.toFix()
        assertEquals(60.1699, back.point.latitude, 1e-7)
        assertEquals(-24.9384, back.point.longitude, 1e-7)
        assertEquals(13.57, back.speedMps, 1e-9)
        assertEquals(fix.timeMs, back.timeMs)
    }
}

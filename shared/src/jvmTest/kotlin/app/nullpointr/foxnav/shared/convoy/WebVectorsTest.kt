package app.nullpointr.foxnav.shared.convoy

import app.nullpointr.foxnav.protocol.convoy.v0.Car
import app.nullpointr.foxnav.protocol.convoy.v0.ClientMessage
import app.nullpointr.foxnav.protocol.convoy.v0.Joined
import app.nullpointr.foxnav.protocol.convoy.v0.Ping
import app.nullpointr.foxnav.protocol.convoy.v0.Position
import app.nullpointr.foxnav.protocol.convoy.v0.Profile
import app.nullpointr.foxnav.protocol.convoy.v0.Relay
import app.nullpointr.foxnav.protocol.convoy.v0.RemoveMember
import app.nullpointr.foxnav.protocol.convoy.v0.ServerMessage
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Bytes the web client (web/test/vectors.test.js) must read and write exactly like this, the app's
 * own code: identities, a sealed frame, a card, a wrapped key, the owner's signature and server
 * messages. Change both together.
 */
class WebVectorsTest {
    private val convoyId = ByteArray(16) { (0xA0 + it).toByte() }
    private val key = ConvoyKey(0, ByteArray(32) { it.toByte() }, convoyId)
    private val alice = Identity(ByteArray(32) { (0x10 + it).toByte() }, ByteArray(32) { (0x30 + it).toByte() })
    private val bob = Identity(ByteArray(32) { (0x50 + it).toByte() }, ByteArray(32) { (0x70 + it).toByte() })
    private val newKey = ByteArray(32) { (0x90 + it).toByte() }

    private val position = Position(lat_e7 = 601_699_000, lon_e7 = -249_384_000, speed_cms = 1350, heading_deg = 271, accuracy_m = 4, fix_time_ms = 1_791_000_000_123)
    private val profile = Profile(display_name = "Äkä", car = Car(make = "Mazda", color_rgb = 0x4C8DFF), watching = true)

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun identities() {
        assertEquals(ALICE_SIGNING, hex(alice.signingKey))
        assertEquals(ALICE_EXCHANGE, hex(alice.exchangeKey))
        assertEquals(ALICE_ID, alice.memberId.toMemberId())
        assertEquals(TAG, key.tag.toMemberId())
    }

    @Test
    fun frame() {
        val frame = ConvoyFrames.seal(convoyId, key, alice, 7, FrameKind.POSITION, Position.ADAPTER.encode(position))
        assertEquals(FRAME, hex(frame))
    }

    @Test
    fun card() {
        assertEquals(CARD, hex(FrameContents.card(alice, profile)))
    }

    @Test
    fun wrappedKeyAndRemoval() {
        assertEquals(WRAPPED, hex(KeyWraps.wrap(convoyId, 1, alice, bob.memberId, bob.exchangeKey, newKey)!!))
        assertEquals(REMOVAL, hex(alice.sign(OwnerSignatures.removal(convoyId, bob.memberId, 1))))
    }

    @Test
    fun serverMessages() {
        val frame = ByteArray(3) { 7 }.toByteString()
        val joined = ServerMessage(
            joined = Joined(
                convoy_id = convoyId.toByteString(),
                member_id = alice.memberId,
                resume_token = ByteArray(4) { 1 }.toByteString(),
                members = listOf(bob.memberId, 42),
                join_token = ByteArray(2) { 9 }.toByteString(),
                frames = listOf(Relay(frame = frame, from = bob.memberId, server_time_ms = 1_791_000_000_000, age_ms = 1500)),
            ),
        )
        assertEquals(JOINED, hex(ServerMessage.ADAPTER.encode(joined)))
        val remove = ClientMessage(remove_member = RemoveMember(member_id = bob.memberId, epoch = 1, signature = ByteArray(2) { 5 }.toByteString()))
        assertEquals(REMOVE, hex(ClientMessage.ADAPTER.encode(remove)))
        assertEquals("4200", hex(ClientMessage.ADAPTER.encode(ClientMessage(ping = Ping()))))
    }

    private companion object {
        const val ALICE_SIGNING = "7776e870b93354f2a0b24c23f2a36cc4e80e223218c1b97926fdd018396a2b9b"
        const val ALICE_EXCHANGE = "34e42d4af5ef94a07a3a84201b889d4cd1a743cb27b11b6a10438a8feb8e5847"
        const val ALICE_ID = 0x0A5B6754L
        const val TAG = 0xF987E3BAL
        const val FRAME = "02f987e3ba0a5b67540000000705d618bf9b10dd3d54a75ff44cde8eaeacef92fe7fe80f65689b4e96d180a34626d365eb187f38588ed706531b9cc922dbf1b4222e33bee74205d9879a76a627c824e3f9fffed347f44e585373173a321ca9268ef36158faa6b375cacc6a2fcf2f1eb0d34a6a4d7079"
        const val CARD = "0a207776e870b93354f2a0b24c23f2a36cc4e80e223218c1b97926fdd018396a2b9b122034e42d4af5ef94a07a3a84201b889d4cd1a743cb27b11b6a10438a8feb8e58471a170a05c3846bc3a4120c0a054d617a646118ff9bb2021801"
        const val WRAPPED = "9b46fb9a8d09ca79c39b7883194280625af1d0e98bb47d3f4d69d0fb627f9abb257f73baa446cc5f"
        const val REMOVAL = "f4de908dfa2cdfaf14ca9103a3ae2c7a86e5a7bb53afe5f043597082ab9c3ee17c9f9391c2af2f9a64f8aa3edd992769f83615f96e5d3b068e3e8cd4e34ecf0d"
        const val JOINED = "1a410a10a0a1a2a3a4a5a6a7a8a9aaabacadaeaf1554675b0a1a04010101012208546b94552a000000320209093a140a0307070715546b94551880ecacff8f3420dc0b"
        const val REMOVE = "3a0b0d546b945510011a020505"
    }
}

package app.nullpointr.foxnav.shared.convoy

import app.nullpointr.foxnav.protocol.convoy.v0.Car
import app.nullpointr.foxnav.protocol.convoy.v0.Channel
import app.nullpointr.foxnav.protocol.convoy.v0.ClientMessage
import app.nullpointr.foxnav.protocol.convoy.v0.ConvoyPayload
import app.nullpointr.foxnav.protocol.convoy.v0.Joined
import app.nullpointr.foxnav.protocol.convoy.v0.Member
import app.nullpointr.foxnav.protocol.convoy.v0.MemberState
import app.nullpointr.foxnav.protocol.convoy.v0.Ping
import app.nullpointr.foxnav.protocol.convoy.v0.Position
import app.nullpointr.foxnav.protocol.convoy.v0.Profile
import app.nullpointr.foxnav.protocol.convoy.v0.Relay
import app.nullpointr.foxnav.protocol.convoy.v0.RouteShare
import app.nullpointr.foxnav.protocol.convoy.v0.Sealed
import app.nullpointr.foxnav.protocol.convoy.v0.ServerMessage
import app.nullpointr.foxnav.protocol.convoy.v0.Waypoint
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Bytes the web client (web/test/vectors.test.js) must read and write exactly like this, the app's
 * own code: payload encoding, a sealed STATE payload and a server message. Change both together.
 */
class WebVectorsTest {
    private val key = ByteArray(32) { it.toByte() }
    private val nonce = ByteArray(12) { (0xF0 + it).toByte() }
    private val convoyId = ByteArray(16) { (0xA0 + it).toByte() }
    private val sender = 0x7123456789ABCDEFL

    private val state = ConvoyPayload(
        state = MemberState(
            profile = Profile(display_name = "Äkä", car = Car(color_rgb = 0x4C8DFF), watching = true),
            position = Position(lat_e7 = 601_699_000, lon_e7 = -249_384_000, speed_mps = 13.5f, heading_deg = 271.25f, accuracy_m = 4f, fix_time_ms = 1_791_000_000_123),
            route = RouteShare(waypoints = listOf(Waypoint(lat_e7 = 601_000_000, lon_e7 = 249_000_000, name = "Kauppatori"))),
        ),
    )

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun payload() {
        assertEquals(PAYLOAD, hex(ConvoyPayload.ADAPTER.encode(state)))
    }

    @Test
    fun sealedState() {
        val sealed = aesGcmSeal(key, nonce, ConvoyPayload.ADAPTER.encode(state), ConvoyCrypto.associatedData(convoyId, sender, Channel.CHANNEL_STATE))
        assertEquals(CIPHERTEXT, hex(sealed))
    }

    @Test
    fun serverMessage() {
        val sealed = Sealed(key_epoch = 2, nonce = nonce.toByteString(), ciphertext = ByteArray(3) { 7 }.toByteString())
        val joined = ServerMessage(
            joined = Joined(
                convoy_id = convoyId.toByteString(),
                member_id = sender,
                resume_token = ByteArray(4) { 1 }.toByteString(),
                members = listOf(Member(member_id = 42, last_state = sealed, last_state_age_ms = 1500)),
                owner = 42,
                join_token = ByteArray(2) { 9 }.toByteString(),
                key_history = listOf(Relay(sender = 42, channel = Channel.CHANNEL_KEY, payload = sealed, server_time_ms = 1_791_000_000_000)),
            ),
        )
        assertEquals(JOINED, hex(ServerMessage.ADAPTER.encode(joined)))
        assertEquals("4200", hex(ClientMessage.ADAPTER.encode(ClientMessage(ping = Ping()))))
    }

    private companion object {
        const val PAYLOAD = "0a520a100a05c3846bc3a4120518ff9bb2021801122208f0cae9bd0410ffb0eaed011d000058412500a087432d0000804030fbecacff8f341a1a0a180880a194bd041080c1bbed011a0a4b6175707061746f7269"
        const val CIPHERTEXT = "63544910763f11f0f43453888b529d2da2e341c32a2547689cbf8ea449cc62edf1110b8440dbf0bc98f849303a39f30f8b6cfc98da8b9fb450cc64c655eb4d33b23f6244fad2e9762b7a660d1c91a93029ee442b40350a1d370e364384a8604ecd05d0a1"
        const val JOINED = "1a6a0a10a0a1a2a3a4a5a6a7a8a9aaabacadaeaf10ef9bafcdf8acd191711a0401010101221c082a12150802120cf0f1f2f3f4f5f6f7f8f9fafb1a0307070718dc0b282a320209093a22082a10031a150802120cf0f1f2f3f4f5f6f7f8f9fafb1a030707072080ecacff8f34"
    }
}

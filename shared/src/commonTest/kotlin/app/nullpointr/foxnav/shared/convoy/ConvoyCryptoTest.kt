package app.nullpointr.foxnav.shared.convoy

import app.nullpointr.foxnav.protocol.convoy.v0.Channel
import app.nullpointr.foxnav.protocol.convoy.v0.ConvoyPayload
import app.nullpointr.foxnav.protocol.convoy.v0.MemberState
import app.nullpointr.foxnav.protocol.convoy.v0.Position
import app.nullpointr.foxnav.protocol.convoy.v0.QuickMessage
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ConvoyCryptoTest {
    private val key = ConvoyCrypto.newKey()
    private val convoyId = secureRandomBytes(16)
    private val payload = ConvoyPayload(state = MemberState(position = Position(lat_e7 = 601_699_000, lon_e7 = 249_384_000, speed_mps = 22.2f, heading_deg = 90f)))

    @Test
    fun roundTrip() {
        val sealed = ConvoyCrypto.seal(key, epoch = 0, convoyId, sender = 42, Channel.CHANNEL_STATE, payload)
        assertEquals(payload, ConvoyCrypto.open(mapOf(0 to key), convoyId, 42, Channel.CHANNEL_STATE, sealed))
    }

    @Test
    fun theServerSeesOnlyCiphertext() {
        val sealed = ConvoyCrypto.seal(key, 0, convoyId, 42, Channel.CHANNEL_STATE, payload)
        assertFalse(sealed.ciphertext.toByteArray().toList().windowed(4).contains(ConvoyPayload.ADAPTER.encode(payload).take(4)))
        assertNotEquals(sealed.nonce, ConvoyCrypto.seal(key, 0, convoyId, 42, Channel.CHANNEL_STATE, payload).nonce)
    }

    @Test
    fun payloadsMovedToAnotherSenderChannelOrConvoyDontOpen() {
        val sealed = ConvoyCrypto.seal(key, 0, convoyId, 42, Channel.CHANNEL_STATE, payload)
        val keys = mapOf(0 to key)
        assertNull(ConvoyCrypto.open(keys, convoyId, 43, Channel.CHANNEL_STATE, sealed))
        assertNull(ConvoyCrypto.open(keys, convoyId, 42, Channel.CHANNEL_EVENT, sealed))
        assertNull(ConvoyCrypto.open(keys, secureRandomBytes(16), 42, Channel.CHANNEL_STATE, sealed))
    }

    @Test
    fun tamperedOrWrongKeyDoesntOpen() {
        val sealed = ConvoyCrypto.seal(key, 0, convoyId, 42, Channel.CHANNEL_EVENT, ConvoyPayload(quick_message = QuickMessage(QuickMessage.Kind.KIND_FUEL)))
        val flipped = sealed.ciphertext.toByteArray().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(ConvoyCrypto.open(mapOf(0 to key), convoyId, 42, Channel.CHANNEL_EVENT, sealed.copy(ciphertext = flipped.toByteString())))
        assertNull(ConvoyCrypto.open(mapOf(0 to ConvoyCrypto.newKey()), convoyId, 42, Channel.CHANNEL_EVENT, sealed))
        assertNull(ConvoyCrypto.open(mapOf(1 to key), convoyId, 42, Channel.CHANNEL_EVENT, sealed))
    }

    @Test
    fun associatedDataLayout() {
        val aad = ConvoyCrypto.associatedData(ByteArray(16) { 7 }, 0x0102030405060708, Channel.CHANNEL_EVENT)
        assertContentEquals(ByteArray(16) { 7 } + byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 2), aad)
    }
}

package app.nullpointr.foxnav.shared.convoy

import app.nullpointr.foxnav.protocol.convoy.v0.Channel
import app.nullpointr.foxnav.protocol.convoy.v0.ConvoyPayload
import app.nullpointr.foxnav.protocol.convoy.v0.Sealed
import okio.ByteString.Companion.toByteString

/**
 * End-to-end encryption of convoy payloads (protocol/SPEC.md §2.3). The associated data binds
 * each payload to its convoy, sender and channel, so a server can't move it elsewhere unnoticed.
 */
object ConvoyCrypto {
    const val KEY_SIZE = 32
    private const val NONCE_SIZE = 12

    fun newKey(): ByteArray = secureRandomBytes(KEY_SIZE)

    fun seal(key: ByteArray, epoch: Int, convoyId: ByteArray, sender: Long, channel: Channel, payload: ConvoyPayload): Sealed {
        val nonce = secureRandomBytes(NONCE_SIZE)
        val ciphertext = aesGcmSeal(key, nonce, ConvoyPayload.ADAPTER.encode(payload), associatedData(convoyId, sender, channel))
        return Sealed(key_epoch = epoch, nonce = nonce.toByteString(), ciphertext = ciphertext.toByteString())
    }

    /** The payload, or null when it doesn't decrypt with [keysByEpoch] (wrong key, tampered or moved). */
    fun open(keysByEpoch: Map<Int, ByteArray>, convoyId: ByteArray, sender: Long, channel: Channel, sealed: Sealed): ConvoyPayload? {
        val key = keysByEpoch[sealed.key_epoch] ?: return null
        val plaintext = aesGcmOpen(key, sealed.nonce.toByteArray(), sealed.ciphertext.toByteArray(), associatedData(convoyId, sender, channel))
            ?: return null
        return runCatching { ConvoyPayload.ADAPTER.decode(plaintext) }.getOrNull()
    }

    /** convoy_id ‖ sender member_id (8 bytes, big-endian) ‖ channel (1 byte). */
    internal fun associatedData(convoyId: ByteArray, sender: Long, channel: Channel): ByteArray =
        convoyId + ByteArray(8) { index -> (sender ushr (56 - 8 * index)).toByte() } + channel.value.toByte()
}

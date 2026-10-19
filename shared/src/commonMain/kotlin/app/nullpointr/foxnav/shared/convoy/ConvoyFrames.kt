package app.nullpointr.foxnav.shared.convoy

/** A frame's kind (protocol/SPEC.md §2.3): its first byte. */
enum class FrameKind(val value: Int) {
    CARD(1),
    POSITION(2),
    ROUTE(3),
    SONG(4),
    EVENT(5),
    OWNER(6),
    ;

    /** Whether the server keeps each sender's latest one for people who join later. */
    val keptPerSender: Boolean get() = this == CARD || this == POSITION || this == ROUTE || this == SONG

    companion object {
        fun of(value: Int): FrameKind? = entries.firstOrNull { it.value == value }
    }
}

/** A member ID (spec §2.3): the first 4 bytes of the SHA-256 of their signing key, big-endian. */
fun memberIdOf(signingKey: ByteArray): Int = sha256(signingKey).readInt(0)

/** A member ID as the unsigned number the session's API uses. */
fun Int.toMemberId(): Long = toLong() and 0xFFFF_FFFFL

/**
 * A member's own keys in one convoy (spec §2.3): Ed25519 to sign their frames, X25519 to receive
 * new convoy keys. They live only in memory, as long as the frame counter that goes with them.
 */
class Identity internal constructor(private val signingPrivate: ByteArray, private val exchangePrivate: ByteArray) {
    val signingKey: ByteArray = ed25519PublicKey(signingPrivate)
    val exchangeKey: ByteArray = x25519PublicKey(exchangePrivate)
    val memberId: Int = memberIdOf(signingKey)

    fun sign(message: ByteArray): ByteArray = ed25519Sign(signingPrivate, message)

    internal fun agree(publicKey: ByteArray): ByteArray? = x25519(exchangePrivate, publicKey)

    override fun toString(): String = "Identity(${memberId.toMemberId()})" // never log the keys

    companion object {
        fun generate(): Identity = Identity(secureRandomBytes(ConvoyFrames.KEY_SIZE), secureRandomBytes(ConvoyFrames.KEY_SIZE))
    }
}

/** One convoy key and what derives from it (spec §2.3): the frame key and the convoy tag. */
class ConvoyKey(val epoch: Int, val raw: ByteArray, convoyId: ByteArray) {
    internal val frameKey: ByteArray = hkdfSha256(raw, convoyId, "foxnav v0 frame".encodeToByteArray(), ConvoyFrames.KEY_SIZE)
    val tag: Int = hkdfSha256(raw, convoyId, "foxnav v0 tag".encodeToByteArray(), 4).readInt(0)
}

/** A frame's 13-byte header, which servers and gateways can read. [counter] is unsigned. */
data class FrameHeader(val kind: Int, val tag: Int, val sender: Int, val counter: Long) {
    fun toBytes(): ByteArray = byteArrayOf(kind.toByte()) + tag.toBytes() + sender.toBytes() + counter.toInt().toBytes()

    companion object {
        const val SIZE = 13

        fun parse(frame: ByteArray): FrameHeader? =
            if (frame.size < SIZE) null else FrameHeader(frame[0].toInt() and 0xFF, frame.readInt(1), frame.readInt(5), frame.readInt(9).toMemberId())
    }
}

/** A frame decrypted but not yet checked: it's the sender's only if [signedBy] their key. */
class OpenedFrame internal constructor(
    val header: FrameHeader,
    private val headerBytes: ByteArray,
    val content: ByteArray,
    private val signature: ByteArray,
) {
    fun signedBy(convoyId: ByteArray, signingKey: ByteArray): Boolean =
        ed25519Verify(signingKey, ConvoyFrames.signedBytes(convoyId, headerBytes, content), signature)
}

/**
 * Convoy frames (spec §2.3): signed by their sender, then sealed with the convoy key. The same
 * bytes over the server and over a radio.
 */
object ConvoyFrames {
    const val KEY_SIZE = 32
    const val SIGNATURE_SIZE = 64
    private const val TAG_SIZE = 16

    /** One Meshtastic packet's payload. */
    const val MAX_SIZE = 233
    const val MIN_SIZE = FrameHeader.SIZE + SIGNATURE_SIZE + TAG_SIZE
    const val MAX_CONTENT = MAX_SIZE - MIN_SIZE

    private val label = "foxnav v0 frame".encodeToByteArray()

    fun newKey(): ByteArray = secureRandomBytes(KEY_SIZE)

    /** [identity]'s frame number [counter], which must never repeat for that identity. */
    fun seal(convoyId: ByteArray, key: ConvoyKey, identity: Identity, counter: Long, kind: FrameKind, content: ByteArray): ByteArray {
        require(content.size <= MAX_CONTENT) { "Frame content of ${content.size} bytes, more than $MAX_CONTENT" }
        val header = FrameHeader(kind.value, key.tag, identity.memberId, counter).toBytes()
        val signature = identity.sign(signedBytes(convoyId, header, content))
        return header + aesGcmSeal(key.frameKey, nonce(identity.memberId, counter), content + signature, convoyId + header)
    }

    /** The frame decrypted with [key], or null when it isn't one of [key]'s (or was changed). */
    fun open(convoyId: ByteArray, key: ConvoyKey, frame: ByteArray): OpenedFrame? {
        if (frame.size !in MIN_SIZE..MAX_SIZE) return null
        val header = FrameHeader.parse(frame) ?: return null
        if (header.tag != key.tag) return null
        val headerBytes = frame.copyOfRange(0, FrameHeader.SIZE)
        val plaintext = aesGcmOpen(key.frameKey, nonce(header.sender, header.counter), frame.copyOfRange(FrameHeader.SIZE, frame.size), convoyId + headerBytes)
            ?: return null
        val split = plaintext.size - SIGNATURE_SIZE
        return OpenedFrame(header, headerBytes, plaintext.copyOfRange(0, split), plaintext.copyOfRange(split, plaintext.size))
    }

    internal fun signedBytes(convoyId: ByteArray, header: ByteArray, content: ByteArray): ByteArray = label + convoyId + header + content

    /** Sender ‖ counter ‖ 4 zero bytes: unique as long as no identity reuses a counter. */
    private fun nonce(sender: Int, counter: Long): ByteArray = sender.toBytes() + counter.toInt().toBytes() + ByteArray(4)
}

/** What the owner signs for the server (spec §2.3), so it can check removals and handovers too. */
object OwnerSignatures {
    fun removal(convoyId: ByteArray, memberId: Int, epoch: Int): ByteArray =
        "foxnav v0 remove".encodeToByteArray() + convoyId + memberId.toBytes() + epoch.toBytes()

    fun handover(convoyId: ByteArray, number: Int, newOwnerKey: ByteArray): ByteArray =
        "foxnav v0 handover".encodeToByteArray() + convoyId + number.toBytes() + newOwnerKey
}

/**
 * New convoy keys wrapped for one member each (spec §2.3): AES-KW under a key from X25519
 * between the owner's and the member's keys, never under the old convoy key.
 */
internal object KeyWraps {
    fun wrap(convoyId: ByteArray, epoch: Int, owner: Identity, memberId: Int, memberExchangeKey: ByteArray, newKey: ByteArray): ByteArray? =
        kek(convoyId, epoch, owner.agree(memberExchangeKey), memberId)?.let { aesKeyWrap(it, newKey) }

    fun unwrap(convoyId: ByteArray, epoch: Int, me: Identity, ownerExchangeKey: ByteArray, wrapped: ByteArray): ByteArray? =
        kek(convoyId, epoch, me.agree(ownerExchangeKey), me.memberId)?.let { aesKeyUnwrap(it, wrapped) }

    private fun kek(convoyId: ByteArray, epoch: Int, secret: ByteArray?, memberId: Int): ByteArray? =
        secret?.let { hkdfSha256(it, convoyId, "foxnav v0 wrap".encodeToByteArray() + epoch.toBytes() + memberId.toBytes(), ConvoyFrames.KEY_SIZE) }
}

/** Which of a sender's frame counters were accepted: the highest, and which of the 64 below it. */
internal class ReplayWindow {
    private var highest = 0L
    private var below = 0L // bit i: highest - 1 - i

    fun isNew(counter: Long): Boolean {
        if (counter < 1) return false
        if (counter > highest) return true
        val distance = highest - counter
        return distance in 1..WIDTH && below and (1L shl (distance - 1).toInt()) == 0L
    }

    fun accept(counter: Long) {
        if (counter > highest) {
            val shift = counter - highest
            below = if (shift > WIDTH) 0L else (if (shift == WIDTH.toLong()) 0L else below shl shift.toInt()) or (1L shl (shift - 1).toInt())
            highest = counter
        } else {
            val distance = highest - counter
            if (distance in 1..WIDTH) below = below or (1L shl (distance - 1).toInt())
        }
    }

    private companion object {
        const val WIDTH = 64
    }
}

internal fun Int.toBytes(): ByteArray = byteArrayOf((this ushr 24).toByte(), (this ushr 16).toByte(), (this ushr 8).toByte(), toByte())

internal fun ByteArray.readInt(at: Int): Int =
    ((this[at].toInt() and 0xFF) shl 24) or ((this[at + 1].toInt() and 0xFF) shl 16) or ((this[at + 2].toInt() and 0xFF) shl 8) or (this[at + 3].toInt() and 0xFF)

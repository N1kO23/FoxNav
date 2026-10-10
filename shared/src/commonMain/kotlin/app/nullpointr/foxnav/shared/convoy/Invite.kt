package app.nullpointr.foxnav.shared.convoy

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * An invitation to a convoy: which server hosts it, the room, the join token, and the
 * encryption key with its epoch (it changes when someone is removed). Everything travels in the
 * link's fragment, which browsers never send to any server (protocol/SPEC.md §2.2).
 */
class Invite(
    val server: String,
    val convoyId: ByteArray,
    val joinToken: ByteArray,
    val key: ByteArray,
    val keyEpoch: Int = 0,
) {
    /** A link anyone can open: `foxnav://join#v=0&s=…&c=…&t=…&k=…`, plus `&e=…` after a key rotation. */
    fun toLink(base: String = DEFAULT_BASE): String =
        "$base#v=$VERSION&s=${percentEncode(server)}&c=${base64Url(convoyId)}&t=${base64Url(joinToken)}&k=${base64Url(key)}" +
            if (keyEpoch != 0) "&e=$keyEpoch" else ""

    override fun equals(other: Any?): Boolean = other is Invite && server == other.server && keyEpoch == other.keyEpoch &&
        convoyId.contentEquals(other.convoyId) && joinToken.contentEquals(other.joinToken) && key.contentEquals(other.key)

    override fun hashCode(): Int = server.hashCode() * 31 + convoyId.contentHashCode()

    override fun toString(): String = "Invite(server=$server)" // never log the key

    companion object {
        /** Until FoxNav has a domain for App Links, invites use the app's own scheme. */
        const val DEFAULT_BASE = "foxnav://join"
        private const val VERSION = "0"

        /** Reads an invite from any link carrying the fragment, or returns null if it isn't one. */
        fun parse(link: String): Invite? {
            val fragment = link.trim().substringAfter('#', missingDelimiterValue = "").ifEmpty { return null }
            val fields = fragment.split('&').mapNotNull { part ->
                part.split('=', limit = 2).takeIf { it.size == 2 }?.let { (name, value) -> name to value }
            }.toMap()
            if (fields["v"] != VERSION) return null
            val server = fields["s"]?.let(::percentDecode)?.takeIf { it.isNotBlank() } ?: return null
            val convoyId = fields["c"]?.let(::fromBase64Url)?.takeIf { it.size == 16 } ?: return null
            val joinToken = fields["t"]?.let(::fromBase64Url)?.takeIf { it.isNotEmpty() } ?: return null
            val key = fields["k"]?.let(::fromBase64Url)?.takeIf { it.size == ConvoyCrypto.KEY_SIZE } ?: return null
            val keyEpoch = fields["e"]?.let { it.toIntOrNull()?.takeIf { epoch -> epoch >= 0 } ?: return null } ?: 0
            return Invite(server, convoyId, joinToken, key, keyEpoch)
        }

        @OptIn(ExperimentalEncodingApi::class)
        private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

        @OptIn(ExperimentalEncodingApi::class)
        private fun base64Url(bytes: ByteArray): String = base64.encode(bytes)

        @OptIn(ExperimentalEncodingApi::class)
        private fun fromBase64Url(text: String): ByteArray? = runCatching { base64.decode(text) }.getOrNull()

        private fun percentEncode(value: String): String = buildString {
            for (byte in value.encodeToByteArray()) {
                val char = byte.toInt().toChar()
                if (byte >= 0 && (char.isLetterOrDigit() || char in "-._~")) append(char)
                else append('%').append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
            }
        }

        private fun percentDecode(value: String): String? {
            val bytes = ArrayList<Byte>(value.length)
            var index = 0
            while (index < value.length) {
                val char = value[index]
                if (char == '%') {
                    if (index + 2 >= value.length) return null
                    bytes += value.substring(index + 1, index + 3).toIntOrNull(16)?.toByte() ?: return null
                    index += 3
                } else {
                    bytes += char.toString().encodeToByteArray().toList()
                    index++
                }
            }
            return bytes.toByteArray().decodeToString()
        }

        private const val HEX = "0123456789ABCDEF"
    }
}

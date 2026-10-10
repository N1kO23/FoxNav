package app.nullpointr.foxnav.conformance

import app.nullpointr.foxnav.protocol.convoy.v0.ClientMessage
import app.nullpointr.foxnav.protocol.convoy.v0.ServerMessage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A failed expectation; its message says what the server should have done. */
class ConformanceFailure(message: String) : Exception(message)

internal fun fail(message: String): Nothing = throw ConformanceFailure(message)

internal fun expect(condition: Boolean, message: () -> String) {
    if (!condition) fail(message())
}

/**
 * A bare protocol connection: sends `ClientMessage`s and waits for particular `ServerMessage`s.
 * Messages that arrive while waiting for something else are kept for later expectations.
 */
internal class RawClient private constructor(private val socket: DefaultClientWebSocketSession) {
    private val inbox = Channel<ServerMessage>(Channel.UNLIMITED)
    private val pending = ArrayList<ServerMessage>()

    init {
        socket.launch {
            for (frame in socket.incoming) {
                if (frame !is Frame.Binary) continue
                runCatching { ServerMessage.ADAPTER.decode(frame.readBytes()) }.getOrNull()?.let { inbox.send(it) }
            }
        }
    }

    suspend fun send(message: ClientMessage) = socket.send(Frame.Binary(true, ClientMessage.ADAPTER.encode(message)))

    suspend fun sendBytes(bytes: ByteArray) = socket.send(Frame.Binary(true, bytes))

    /** The first message [pick] accepts, waiting up to [timeout]. */
    suspend fun <T : Any> expect(what: String, timeout: Duration = 5.seconds, pick: (ServerMessage) -> T?): T {
        take(pick)?.let { return it }
        val found = withTimeoutOrNull(timeout) {
            var picked: T? = null
            while (picked == null) {
                val message = inbox.receive()
                picked = pick(message)
                if (picked == null) pending += message
            }
            picked
        }
        return found ?: fail("expected $what; got ${pending.describe()}")
    }

    /** Fails if a message [pick] accepts arrives within [within]. */
    suspend fun <T : Any> expectNone(what: String, within: Duration = 1.seconds, pick: (ServerMessage) -> T?) {
        take(pick)?.let { fail("didn't expect $what") }
        withTimeoutOrNull(within) {
            while (true) {
                val message = inbox.receive()
                if (pick(message) != null) fail("didn't expect $what")
                pending += message
            }
        }
    }

    suspend fun close() = socket.close()

    private fun <T : Any> take(pick: (ServerMessage) -> T?): T? {
        val index = pending.indexOfFirst { pick(it) != null }
        return if (index < 0) null else pick(pending.removeAt(index))
    }

    private fun List<ServerMessage>.describe(): String = if (isEmpty()) "nothing" else joinToString { it.kind() }

    private fun ServerMessage.kind(): String = when {
        welcome != null -> "Welcome"
        convoy_created != null -> "ConvoyCreated"
        joined != null -> "Joined"
        relay != null -> "Relay"
        member_joined != null -> "MemberJoined"
        member_left != null -> "MemberLeft"
        pong != null -> "Pong"
        invite_changed != null -> "InviteChanged"
        error != null -> "Error ${error?.code?.name?.removePrefix("CODE_")}"
        else -> "an empty message"
    }

    companion object {
        suspend fun connect(http: HttpClient, url: String) =
            RawClient(http.webSocketSession(url) { header(HttpHeaders.SecWebSocketProtocol, SUBPROTOCOL) })

        const val SUBPROTOCOL = "foxnav.v0"
    }
}

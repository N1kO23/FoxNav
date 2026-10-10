package app.nullpointr.foxnav.server.convoy

import app.nullpointr.foxnav.protocol.convoy.v0.ClientMessage
import app.nullpointr.foxnav.protocol.convoy.v0.ServerMessage
import app.nullpointr.foxnav.shared.convoy.ConvoySession
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import app.nullpointr.foxnav.protocol.convoy.v0.Error as ProtocolError

/** The realtime endpoint (protocol/SPEC.md §2.1): one binary protobuf message per frame. */
fun Route.convoyRoutes(relay: ConvoyRelay) {
    webSocket(REALTIME_PATH, protocol = SUBPROTOCOL) { serveConvoy(relay) }
}

/** Serves one realtime connection until it closes. */
suspend fun DefaultWebSocketServerSession.serveConvoy(relay: ConvoyRelay) {
    // A phone that can't keep up loses its oldest queued messages, never anyone else's speed.
    val outgoing = Channel<ServerMessage>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val connection = relay.connect { message -> outgoing.trySend(message) }
    val writer = launch {
        for (message in outgoing) send(Frame.Binary(true, ServerMessage.ADAPTER.encode(message)))
    }
    try {
        for (frame in incoming) {
            if (frame !is Frame.Binary) continue
            val message = runCatching { ClientMessage.ADAPTER.decode(frame.readBytes()) }.getOrNull()
            if (message == null) {
                outgoing.trySend(ServerMessage(error = ProtocolError(code = ProtocolError.Code.CODE_BAD_REQUEST, message = "Couldn't decode the message")))
                continue
            }
            relay.receive(connection, message)
        }
    } finally {
        relay.disconnect(connection)
        outgoing.close()
        writer.cancel()
    }
}

const val REALTIME_PATH = "/v1/ws"
const val SUBPROTOCOL = ConvoySession.SUBPROTOCOL

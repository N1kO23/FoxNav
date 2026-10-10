package app.nullpointr.foxnav.shared

import kotlinx.serialization.json.Json

/**
 * JSON settings shared by FoxNav clients and servers: unknown fields are ignored so older
 * clients keep working against newer servers, and absent optional fields are left out.
 */
val FoxNavJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

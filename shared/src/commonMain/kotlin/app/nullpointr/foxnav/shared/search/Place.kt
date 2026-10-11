package app.nullpointr.foxnav.shared.search

import kotlinx.serialization.Serializable

/** A search result the user can pick as a destination. */
@Serializable
data class Place(
    /** Main line, e.g. "Tampere" or "Mannerheimintie 1". */
    val title: String,
    /** Second line giving context, e.g. "Helsinki" or "Pirkanmaa, Finland"; may be empty. */
    val detail: String,
    val latitude: Double,
    val longitude: Double,
)

package app.nullpointr.foxnav.shared.search

import app.nullpointr.foxnav.shared.FoxNavJson
import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Client-side helpers for the [Photon](https://github.com/komoot/photon) search API. */
object Photon {
    /**
     * Path and query for a search, relative to the search endpoint's URL. A [near] position biases
     * results towards it, so "Mannerheimintie 1" finds the street in the user's city first.
     */
    fun searchPath(query: String, near: GeoPoint? = null, limit: Int = 8, language: String? = null): String =
        buildString {
            append("/api?q=").append(percentEncode(query)).append("&limit=").append(limit)
            near?.let { append("&lat=").append(it.latitude).append("&lon=").append(it.longitude) }
            language?.let { append("&lang=").append(percentEncode(it)) }
        }

    /** Parses a Photon GeoJSON response into places, skipping results without a usable name. */
    fun parse(json: String): List<Place> =
        FoxNavJson.decodeFromString(PhotonResponse.serializer(), json).features.mapNotNull { it.toPlace() }

    private fun PhotonFeature.toPlace(): Place? {
        val (longitude, latitude) = geometry.coordinates.takeIf { it.size >= 2 } ?: return null
        val address = listOfNotNull(properties.street, properties.housenumber).joinToString(" ").ifEmpty { null }
        val title = properties.name ?: address ?: return null
        val detail = listOfNotNull(
            address.takeIf { properties.name != null },
            properties.city?.takeIf { it != title },
            properties.state?.takeIf { properties.city == null && it != title },
            properties.country?.takeIf { properties.city == null },
        ).joinToString(", ")
        return Place(title = title, detail = detail, latitude = latitude, longitude = longitude)
    }

    private fun percentEncode(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val char = byte.toInt().toChar()
            if (char.isLetterOrDigit() && byte >= 0 || char in "-._~") {
                append(char)
            } else {
                append('%').append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
}

@Serializable
private class PhotonResponse(val features: List<PhotonFeature> = emptyList())

@Serializable
private class PhotonFeature(val geometry: PhotonGeometry, val properties: PhotonProperties)

@Serializable
private class PhotonGeometry(val coordinates: List<Double> = emptyList())

@Serializable
private class PhotonProperties(
    val name: String? = null,
    val street: String? = null,
    val housenumber: String? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    @SerialName("osm_value") val osmValue: String? = null,
)

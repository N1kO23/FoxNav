package app.nullpointr.foxnav.shared.speed

/**
 * What the speedometer shows: current speed and limit, in the user's units (km/h or mph), whether
 * to warn, and the season when the limit is a seasonal one (see [SpeedLimits]).
 */
data class SpeedReading(
    val speed: Int? = null,
    val limit: Int? = null,
    val overLimit: Boolean = false,
    val limitSeason: String? = null,
) {
    /**
     * The next reading. Over the limit starts at [margin] above it and ends only back at or under
     * the limit, so hovering around the threshold doesn't make the warning (and chime) flap.
     */
    fun next(speed: Int?, limit: Int?, margin: Int = DEFAULT_MARGIN, limitSeason: String? = null): SpeedReading {
        val over = when {
            speed == null || limit == null -> false
            overLimit && limit == this.limit -> speed > limit
            else -> speed >= limit + margin
        }
        return SpeedReading(speed, limit, over, limitSeason.takeIf { limit != null })
    }

    companion object {
        const val DEFAULT_MARGIN = 3
    }
}

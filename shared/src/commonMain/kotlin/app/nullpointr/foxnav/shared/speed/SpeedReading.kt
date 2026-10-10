package app.nullpointr.foxnav.shared.speed

/** What the speedometer shows: current speed and limit in km/h, and whether to warn. */
data class SpeedReading(val speedKmh: Int? = null, val limitKmh: Int? = null, val overLimit: Boolean = false) {
    /**
     * The next reading. Over the limit starts at [toleranceKmh] above it and ends only back at or
     * under the limit, so hovering around the threshold doesn't make the warning (and chime) flap.
     */
    fun next(speedKmh: Int?, limitKmh: Int?, toleranceKmh: Int = DEFAULT_TOLERANCE_KMH): SpeedReading {
        val over = when {
            speedKmh == null || limitKmh == null -> false
            overLimit && limitKmh == this.limitKmh -> speedKmh > limitKmh
            else -> speedKmh >= limitKmh + toleranceKmh
        }
        return SpeedReading(speedKmh, limitKmh, over)
    }

    companion object {
        const val DEFAULT_TOLERANCE_KMH = 3
    }
}

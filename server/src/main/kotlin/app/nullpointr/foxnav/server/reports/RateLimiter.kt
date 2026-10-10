package app.nullpointr.foxnav.server.reports

/** At most a number of actions per key in a sliding window; keys are clients' addresses, kept in memory only. */
class RateLimiter(private val clock: () -> Long = System::currentTimeMillis) {
    private val recent = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun allow(key: String, limit: Int, windowMs: Long): Boolean {
        val now = clock()
        if (recent.size > MAX_KEYS) recent.values.removeAll { times -> times.isEmpty() || now - times.last() >= windowMs }
        val times = recent.getOrPut(key) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.first() >= windowMs) times.removeFirst()
        if (times.size >= limit) return false
        times.addLast(now)
        return true
    }

    private companion object {
        const val MAX_KEYS = 10_000
    }
}

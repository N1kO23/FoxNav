package app.nullpointr.foxnav.shared.search

import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlin.math.abs
import kotlin.math.log10

/**
 * Searching the offline search pack: how text is compared and results ranked. Text is compared in
 * lower case, without accents (ä matches a, so "aanekoski" finds Äänekoski), in words of letters
 * and digits. Every word typed matches the start of a word, and when that finds too little, the
 * start of a word give or take a typo or two ([similar]): "mannerhem" still finds Mannerheimintie.
 */
object SearchText {
    /** [text] as the index stores and compares it. */
    fun fold(text: String): String = buildString(text.length) {
        var space = true
        for (char in text.lowercase()) {
            val folded = FOLDS[char] ?: char.toString()
            if (char.isLetterOrDigit()) {
                append(folded)
                space = false
            } else if (!space) {
                append(' ')
                space = true
            }
        }
    }.trimEnd()

    fun words(text: String): List<String> = fold(text).split(' ').filter { it.isNotEmpty() }

    /** What the index stores for [entry]: every name, and where it is. */
    fun indexed(entry: SearchEntry): String = fold((listOf(entry.title, entry.detail) + entry.aka).joinToString(" "))

    /** A full-text query (SQLite FTS) for [query], every word as a prefix; null when there's nothing to look for. */
    fun ftsQuery(query: String): String? = words(query).take(MAX_WORDS).joinToString(" ") { "$it*" }.ifEmpty { null }

    /**
     * The best of [candidates], entries that have every word of [query] somewhere: names that are
     * or start with the query first, then towns over landmarks over streets, addresses when the
     * query has a house number, the more notable, and the nearer to [near].
     */
    fun rank(query: String, candidates: List<SearchEntry>, near: GeoPoint?, limit: Int = 8): List<SearchEntry> {
        val words = words(query)
        val full = words.joinToString(" ")
        val number = words.any { it.first().isDigit() }
        return candidates
            .map { entry ->
                val names = (listOf(entry.title) + entry.aka).map(::fold)
                var score = when {
                    names.any { it == full } -> 4.0
                    names.any { it.startsWith(full) } -> 3.0
                    words.all { word -> names.any { name -> name.split(' ').any { it.startsWith(word) } } } -> 1.5
                    words.all { word -> names.any { name -> name.split(' ').any { similar(word, it) } } } -> 1.0
                    else -> 0.0
                }
                score += when (entry.kind) {
                    SearchKind.PLACE -> 1.0
                    SearchKind.POI -> 0.6
                    SearchKind.STREET -> 0.5
                    SearchKind.ADDRESS -> if (number) 1.2 else 0.0
                }
                score += entry.weight * 2
                if (near != null) score -= log10(1 + near.distanceTo(entry.point) / 1000) / 2
                entry to score
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { fold(it.title) + "|" + fold(it.detail) }
            .take(limit)
    }

    /** How many typos a typed word may have: none in short ones, where one makes another word. */
    fun typosAllowed(word: String): Int = when {
        word.length <= 4 -> 0
        word.length <= 8 -> 1
        else -> 2
    }

    /**
     * Whether [typed] is the start of [word] give or take [typosAllowed] typos: a wrong, missing,
     * extra or swapped letter. The start of the word, since the last word is often still being typed.
     */
    fun similar(typed: String, word: String): Boolean {
        val allowed = typosAllowed(typed)
        if (word.startsWith(typed)) return true
        if (allowed == 0) return false
        // The start of the word a letter or two shorter or longer than what's typed: missing and extra letters.
        return (typed.length - allowed..typed.length + allowed).any { length ->
            length in 1..word.length && distance(typed, word.take(length), allowed) <= allowed
        }
    }

    /** Whether every word of [query] is [similar] to a word of [entry]'s names or place. */
    fun matchesLoosely(query: String, entry: SearchEntry): Boolean {
        val words = indexed(entry).split(' ')
        return words(query).all { typed -> words.any { similar(typed, it) } }
    }

    /** The optimal string alignment distance between [a] and [b], or anything above [max] once it's past it. */
    fun distance(a: String, b: String, max: Int): Int {
        if (abs(a.length - b.length) > max) return max + 1
        var before = IntArray(b.length + 1)
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var best = current[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var value = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) value = minOf(value, before[j - 2] + 1)
                current[j] = value
                best = minOf(best, value)
            }
            if (best > max) return max + 1
            val spare = before
            before = previous
            previous = current
            current = spare
        }
        return previous[b.length]
    }

    private const val MAX_WORDS = 8

    private val FOLDS: Map<Char, String> = buildMap {
        fun fold(chars: String, to: String) = chars.forEach { put(it, to) }
        fold("àáâãäåāą", "a")
        fold("çćč", "c")
        fold("ďđ", "d")
        fold("èéêëēęě", "e")
        fold("ìíîïī", "i")
        fold("łľ", "l")
        fold("ñńňŋ", "n")
        fold("òóôõöøō", "o")
        fold("ŕř", "r")
        fold("śšş", "s")
        fold("ťŧ", "t")
        fold("ùúûüūů", "u")
        fold("ýÿ", "y")
        fold("źżž", "z")
        put('æ', "ae")
        put('ß', "ss")
        put('þ', "th")
    }
}

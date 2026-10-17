package eu.kanade.tachiyomi.extension.all.mangaball

import eu.kanade.tachiyomi.source.model.SManga

/**
 * Typo-tolerant, relevance-first ranking on top of the site's own search.
 *
 * The site's search engine is fuzzy, but it is generous: a query like `mieruko-ct` comes back
 * with "Mieruko-chan" *and* a pile of unrelated `-chan` titles, and when a search is combined
 * with `sort_by=lastupdate` those unrelated titles can even land above the real match. This
 * object supplies:
 *
 *  * [similarity] - a 0..1 score between a query and a title (or one of its alternative titles)
 *    that tolerates typos, missing spaces, hyphens, accents and word order,
 *  * [candidateQueries] - a short, ordered list of alternative spellings to retry on the server
 *    when nothing in the first answer looks like the query,
 *  * [rank] - the final filter + ordering of a result set.
 *
 * Everything here is plain Kotlin (no Android APIs beyond [SManga], which is a stub in tests), so
 * the whole scoring model is covered by unit tests.
 */
internal object SmartSearch {

    /** The server's answer clearly contains what was asked for - no retries needed. */
    const val STRONG: Double = 0.80

    /** Results below this score are dropped when a better match exists. */
    const val KEEP: Double = 0.50

    /** Below this nothing looks like the query at all; the server's own answer is trusted. */
    const val WEAK: Double = 0.34

    /** How many extra requests one search may spend on typo variants. */
    const val MAX_PROBES: Int = 3

    /** A word scoring below this is simply not present in the target at all. */
    private const val MISSING: Double = 0.25

    /** The server's "leave out 18+ titles" mode, and the one that includes everything. */
    const val ADULT_MODE_SAFE: String = "no_18"
    const val ADULT_MODE_ALL: String = "all"

    /**
     * Lowercases, strips accents and turns every run of punctuation or spaces into a single
     * space, so that "Mieruko-Chan!", "mieruko chan" and "MIERUKO  CHAN" all become
     * "mieruko chan". CJK and other letter-based scripts are preserved as-is.
     */
    fun normalize(text: String): String {
        val decomposed = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        val builder = StringBuilder(decomposed.length)
        var pendingSpace = false
        for (character in decomposed) {
            when {
                character.code in 0x0300..0x036F -> Unit // combining accent left over from NFD
                character.isLetterOrDigit() -> {
                    if (pendingSpace && builder.isNotEmpty()) builder.append(' ')
                    pendingSpace = false
                    builder.append(character.lowercaseChar())
                }
                else -> pendingSpace = true
            }
        }
        return builder.toString()
    }

    fun tokens(normalized: String): List<String> =
        normalized.split(' ').filter { it.isNotEmpty() }

    /**
     * How close [target] is to [query], 0..1. Tuned for titles:
     *
     *  * exact / substring / prefix matches score ~1.0 ("mieruko" - "Mieruko-chan"),
     *  * one typo in a word keeps a high score ("nartuo" - "Naruto" = 0.83),
     *  * spaces and hyphens do not matter ("sololeveling" - "Solo Leveling" = 1.0),
     *  * multi-word queries require most words to be present, so "mieruko-ct" still matches
     *    "Mieruko-chan" (one word exact, one half-typed) while "Super! Makoto-Chan" scores ~0.1.
     */
    fun similarity(query: String, target: String): Double {
        val q = normalize(query)
        val t = normalize(target)
        if (q.isEmpty() || t.isEmpty()) return 0.0
        if (q == t) return 1.0
        if (t.length > q.length && t.contains(q)) return 0.97

        val qWords = tokens(q)
        val tWords = tokens(t)
        if (qWords.isEmpty() || tWords.isEmpty()) return 0.0

        val packedQuery = q.replace(" ", "")
        val packedTarget = t.replace(" ", "")
        val longestPacked = maxOf(packedQuery.length, packedTarget.length)
        val joined = if (packedQuery.isEmpty() || packedTarget.isEmpty() ||
            kotlin.math.abs(packedQuery.length - packedTarget.length) > longestPacked * 0.4
        ) {
            // A typo cannot explain 40% of the string being absent.
            0.0
        } else {
            1.0 - editDistance(packedQuery, packedTarget, 12).toDouble() / longestPacked
        }

        val perWord = qWords.map { word -> tWords.maxOf { candidate -> tokenSimilarity(word, candidate) } }
        val coverage = perWord.average()

        val penalized = if (perWord.any { it < MISSING } || coverage < 0.5) {
            // A word that is simply not there ("bad thinkin" vs "There Are No Bad Warriors in
            // This World") is not a typo, and one matching word out of two is somebody else's
            // title - so the word-based score is halved. The packed-string score is kept,
            // because "mieruko-ct" vs "Mieruko-chan" is still recognisable as a whole
            // ("mierukoct" -> "mierukochan" is three edits).
            coverage * 0.5
        } else {
            coverage
        }

        return if (qWords.size == 1) {
            maxOf(joined, coverage).coerceIn(0.0, 1.0)
        } else {
            maxOf(joined, penalized).coerceIn(0.0, 1.0)
        }
    }

    /** The best score over every text a result is known by (title + alternative titles). */
    fun score(query: String, texts: List<String>): Double {
        var best = 0.0
        for (text in texts) {
            if (best >= 1.0) break
            val candidate = similarity(query, text)
            if (candidate > best) best = candidate
        }
        return best
    }

    /**
     * Alternative spellings worth asking the server for when the first answer had nothing close
     * to the query. Ordered by how likely they are to help: each word on its own (the site's
     * fuzzy search happily completes "mieruko" into "Mieruko-chan"), the words squashed together,
     * then shorter prefixes and single-character typos of the longest word.
     */
    fun candidateQueries(query: String, limit: Int = 12): List<String> {
        val normalized = normalize(query)
        val words = tokens(normalized)
        if (words.isEmpty()) return emptyList()

        val candidates = LinkedHashSet<String>()
        fun offer(candidate: String) {
            val clean = candidate.trim()
            if (clean.isNotEmpty() && normalize(clean) != normalized) candidates += clean
        }

        val byLength = words.sortedByDescending { it.length }
        val longest = byLength.first()
        val longestIndex = words.indexOf(longest)
        fun withLongestReplaced(replacement: String) {
            offer(words.toMutableList().also { it[longestIndex] = replacement }.joinToString(" "))
        }

        // 0. A word missing its final "g": "thinkin" -> "thinking", "nothin" -> "nothing". This is
        //    the most common omission in English titles and the server finds the real title for
        //    the completed word.
        if (longest.length >= 4 && longest.endsWith("in")) withLongestReplaced(longest + "g")

        // 1. Each word on its own. The server's fuzzy search completes "mieruko" into
        //    "Mieruko-chan", so a query that trails off ("mieruko-ct") is best answered by its
        //    most distinctive word.
        byLength.filter { it.length >= 4 }.forEach { offer(it) }

        // 2. Spaces the user left out (or added): "solo leveling" -> "sololeveling".
        if (words.size > 1) offer(words.joinToString(""))

        // 3. A word that was cut short or mistyped near its end ("naruto shippu" -> "naruto
        //    shipp" -> "naruto ship"): the server completes a correct prefix.
        for (cut in 1..3) {
            if (longest.length - cut >= 4) withLongestReplaced(longest.dropLast(cut))
        }

        // 4. Adjacent transpositions, the most common typo of all ("peice" -> "piece",
        //    "nartuo" -> "naruto"). Replacing the word inside the query keeps the rest intact,
        //    so "one peice" is retried as "one piece".
        var swaps = 0
        for (index in 0 until longest.length - 1) {
            if (longest[index] == longest[index + 1]) continue
            if (swaps >= 6) break
            swaps++
            withLongestReplaced(
                longest.substring(0, index) +
                    longest[index + 1] +
                    longest[index] +
                    longest.substring(index + 2),
            )
        }

        // 5. A letter typed twice: "narutto" -> "naruto".
        var collapses = 0
        for (index in 0 until longest.length - 1) {
            if (longest[index] != longest[index + 1]) continue
            if (collapses >= 3) break
            collapses++
            withLongestReplaced(longest.removeRange(index, index + 1))
        }

        return candidates.take(limit)
    }

    /**
     * True when nothing in the result set looks like the query AND the 18+ filter is on, i.e. the
     * title that was asked for may have been removed by the server before it was ever seen. In
     * that case the search is repeated once with 18+ titles allowed, so that a title known by name
     * can never be invisible. [rescueEnabled] is the user's switch for this.
     */
    fun needsAdultRescue(best: Double, adultMode: String, rescueEnabled: Boolean): Boolean =
        rescueEnabled && adultMode == ADULT_MODE_SAFE && best < WEAK

    /**
     * Final pass over a result set:
     *
     *  * nothing remotely similar (a search for an author, a description keyword, a font of
     *    junk) - the server's own answer is returned untouched, because it may have matched a
     *    field this scoring does not model,
     *  * otherwise every result that is clearly worse than the best match is removed and the
     *    rest is sorted by how well it matches the query.
     *
     * The sort is stable, so results with an equal score keep the server's order.
     */
    fun <T> rank(query: String, items: List<T>, texts: (T) -> List<String>): List<T> {
        if (items.size < 2) return items
        val scored = items.map { it to score(query, texts(it)) }
        val best = scored.maxOf { it.second }
        if (best < WEAK) return items

        val threshold = minOf(KEEP, best)
        return scored
            .filter { it.second >= threshold }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /**
     * Optimal string alignment distance (Levenshtein plus adjacent transpositions), abandoned as
     * soon as it is certain to exceed [cap] - titles are short, but there are a lot of them.
     */
    internal fun editDistance(a: String, b: String, cap: Int): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        if (kotlin.math.abs(a.length - b.length) > cap) return cap + 1

        var previousPrevious = IntArray(b.length + 1)
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            var rowMinimum = current[0]
            for (j in 1..b.length) {
                val substitution = if (a[i - 1] == b[j - 1]) 0 else 1
                var value = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + substitution,
                )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    value = minOf(value, previousPrevious[j - 2] + 1)
                }
                current[j] = value
                if (value < rowMinimum) rowMinimum = value
            }
            if (rowMinimum > cap) return cap + 1
            val recycled = previousPrevious
            previousPrevious = previous
            previous = current
            current = recycled
        }
        return previous[b.length]
    }

    private fun tokenSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        // "thinkin" vs "in" must not count as a match. A fragment of 4+ characters may match as
        // a prefix or as a word inside a longer one ("mieruko" in "mieruko-chan").
        if (shorter.length >= 4) {
            if (longer.startsWith(shorter)) return 0.95
            if (longer.contains(shorter)) return 0.90
        }
        // Shorter than half the longer word is a coincidence, not a half-typed word: "in" inside
        // "thinkin" is not evidence. "ct" inside "chan" still is - the user was typing a word.
        if (shorter.length * 2 < longer.length) return 0.0
        // One typo is forgivable in a short word ("thinkin" -> "thinking" = 0.86); four changes
        // across two different words ("thinkin" -> "there") are not a typo at all, which is why
        // the distance is measured against the *shorter* word.
        val distance = editDistance(a, b, 6)
        return (1.0 - distance.toDouble() / shorter.length).coerceIn(0.0, 1.0)
    }
}

// ---------------------------------------------------------------------------
// SManga adapters. Kept deliberately thin: everything that matters is scored in [SmartSearch],
// which takes plain strings so that it is fully covered by the unit tests (the real
// SManga.create() only exists inside the reader app, not in the test classpath).
// ---------------------------------------------------------------------------

/** The texts a result is known by: its title plus every alternative title. */
internal fun SManga.searchTexts(): List<String> = listOf(title) + memoAltTitles()

// ---------------------------------------------------------------------------
// Alternative titles
// ---------------------------------------------------------------------------

/**
 * How many alternative titles are remembered per work, and how much text they may take up.
 *
 * The API lists one alternative title per translation a work is known by - 20 to 50 of them is
 * normal, and *any* of them can be what a reader types. Keeping only the first dozen (the site
 * lists the English one surprisingly late) silently broke the rest: the work was in the answer,
 * scored as "nothing like what was typed" and was then dropped from the page.
 */
internal const val ALT_TITLE_COUNT = 48
internal const val ALT_TITLE_CHARS = 3000
internal const val ALT_TITLE_LENGTH = 140

/**
 * The alternative titles worth keeping, in the order the site lists them: trimmed, deduplicated
 * and bounded ([ALT_TITLE_COUNT] entries, [ALT_TITLE_CHARS] characters in total, at most
 * [ALT_TITLE_LENGTH] characters each).
 */
internal fun altTitlesToKeep(raw: List<String>): List<String> {
    val kept = LinkedHashSet<String>()
    var used = 0
    for (title in raw) {
        if (kept.size >= ALT_TITLE_COUNT) break
        val clean = title.trim().take(ALT_TITLE_LENGTH)
        if (clean.isEmpty() || used + clean.length > ALT_TITLE_CHARS) continue
        if (!kept.add(clean)) continue
        used += clean.length
    }
    return kept.toList()
}

/** Score of a search result against the query. */
internal fun scoreManga(query: String, manga: SManga): Double =
    SmartSearch.score(query, manga.searchTexts())

/** Filter + relevance ordering of a result set. */
internal fun rankMangas(query: String, items: List<SManga>): List<SManga> =
    SmartSearch.rank(query, items) { it.searchTexts() }

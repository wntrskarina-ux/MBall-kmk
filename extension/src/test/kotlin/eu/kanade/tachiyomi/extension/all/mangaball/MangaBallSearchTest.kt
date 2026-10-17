package eu.kanade.tachiyomi.extension.all.mangaball

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the typo-tolerant search scoring. The junk pool in
 * [the reported junk list keeps only the searched title] is the exact list the user saw when
 * searching `mieruko-ct`: the site's fuzzy search had ranked unrelated `-chan` titles above the
 * title that was asked for, because the search was combined with `sort_by=lastupdate`.
 */
class MangaBallSearchTest {

    private fun score(query: String, title: String): Double = SmartSearch.similarity(query, title)

    /** Ranking works on plain strings, which is exactly how [SmartSearch] is written. */
    private fun rank(query: String, titles: List<String>): List<String> =
        SmartSearch.rank(query, titles) { listOf(it) }

    // ------------------------------------------------------------------ normalization

    @Test
    fun `normalization ignores case, accents, punctuation and repeated spaces`() {
        assertEquals("mieruko chan", SmartSearch.normalize("  Mieruko-CHAN! "))
        assertEquals("cafe creme", SmartSearch.normalize("Café  Crème"))
        assertEquals("one piece", SmartSearch.normalize("One_Piece"))
        assertEquals("", SmartSearch.normalize("   ---   "))
    }

    @Test
    fun `normalization keeps non latin scripts intact`() {
        assertEquals("見える子ちゃん", SmartSearch.normalize("見える子ちゃん"))
        assertEquals("naruto ナルト", SmartSearch.normalize("Naruto・ナルト"))
    }

    // ------------------------------------------------------------------ similarity

    @Test
    fun `exact and substring titles score highest`() {
        assertEquals(1.0, score("naruto", "Naruto"), 0.001)
        assertTrue(score("mieruko", "Mieruko-chan") >= 0.95)
    }

    @Test
    fun `a missing space or hyphen does not matter`() {
        assertEquals(1.0, score("sololeveling", "Solo Leveling"), 0.001)
        assertEquals(1.0, score("mierukochan", "Mieruko-chan"), 0.001)
    }

    @Test
    fun `single typos keep a high score`() {
        assertTrue("transposed letters", score("nartuo", "Naruto") >= 0.8)
        assertTrue("transposed letters", score("peice", "Piece") >= 0.75)
        assertTrue("wrong letter", score("mieruko-chanm", "Mieruko-chan") >= 0.8)
    }

    @Test
    fun `a half typed second word still matches its title`() {
        // The exact query from the user's screenshot: "mieruko-ct" is "Mieruko-chan" with the
        // last word barely started.
        val matched = score("mieruko-ct", "Mieruko-chan")
        assertTrue("expected >= 0.6 but was $matched", matched >= 0.6)
        assertTrue(matched >= SmartSearch.KEEP)
    }

    @Test
    fun `multi word queries need most of their words`() {
        assertTrue(score("one peice", "One Piece") >= 0.8)
        assertTrue(score("attack on titan", "Shingeki no Kyojin") < SmartSearch.WEAK)
    }

    @Test
    fun `unrelated titles score below the junk threshold`() {
        listOf(
            "Super! Makoto-Chan",
            "Onii-chan Is Done For!",
            "Ri-chan",
            "Toudou-san to Nia-chan",
            "You are Bun-chan's Love",
            "Amaechau?",
        ).forEach { title ->
            val junk = score("mieruko-ct", title)
            assertTrue("$title scored $junk", junk < SmartSearch.WEAK)
        }
    }

    @Test
    fun `alternative titles are searched too`() {
        val mieruko = listOf("見える子ちゃん", "Mieruko-chan", "The Girl Who Can See Them")
        assertTrue(SmartSearch.score("mieruko", mieruko) >= 0.95)
        assertTrue(SmartSearch.score("the girl who can see them", mieruko) >= 0.95)
        assertTrue(SmartSearch.score("見える子ちゃん", mieruko) >= 0.99)
    }

    // ------------------------------------------------------------------ variants

    @Test
    fun `variant list offers the distinctive word of the query`() {
        // "mieruko-ct" trails off; "mieruko" alone is what makes the server find Mieruko-chan.
        val variants = SmartSearch.candidateQueries("mieruko-ct")
        assertTrue("expected 'mieruko' in $variants", "mieruko" in variants)
    }

    @Test
    fun `variant list corrects a typo inside the phrase`() {
        // "one peice" must be retried as "one piece", not as the squashed typo "onepeice".
        val variants = SmartSearch.candidateQueries("one peice")
        assertTrue("expected 'one piece' in $variants", "one piece" in variants)
    }

    @Test
    fun `variant list removes spaces the user typed`() {
        assertTrue("sololeveling" in SmartSearch.candidateQueries("solo leveling"))
    }

    @Test
    fun `variant list fixes a transposed typo`() {
        assertTrue("naruto" in SmartSearch.candidateQueries("nartuo"))
        assertTrue("piece" in SmartSearch.candidateQueries("peice"))
    }

    @Test
    fun `variant list never repeats the original query`() {
        val original = SmartSearch.normalize("Mieruko-ct")
        SmartSearch.candidateQueries("Mieruko-ct").forEach { variant ->
            assertTrue("$variant repeats the query", SmartSearch.normalize(variant) != original)
        }
    }

    @Test
    fun `variant list stays short and avoids blank entries`() {
        val variants = SmartSearch.candidateQueries("mieruko-ct")
        assertTrue(variants.size <= 12)
        assertTrue(variants.none { it.isBlank() })
        assertTrue(SmartSearch.candidateQueries("").isEmpty())
    }

    // ------------------------------------------------------------------ ranking

    @Test
    fun `the reported junk list keeps only the searched title`() {
        val pool = listOf(
            "Super! Makoto-Chan",
            "You are Bun-chan's Love",
            "Onii-chan Is Done For!",
            "Ri-chan",
            "Mieruko-chan",
            "Toudou-san to Nia-chan",
        )
        assertEquals(listOf("Mieruko-chan"), rank("mieruko-ct", pool))
    }

    @Test
    fun `relevant results come first and junk is dropped`() {
        val pool = listOf("Boruto", "One Piece", "Naruto", "Naruto Gaiden")
        val ranked = rank("naruto", pool)
        assertEquals("Naruto", ranked.first())
        assertTrue(ranked.none { it == "One Piece" })
        assertTrue(ranked.size >= 2)
    }

    @Test
    fun `equal scores keep the order the server returned`() {
        val pool = listOf("Naruto", "Naruto Gaiden", "Naruto Shippuden")
        assertEquals(pool, rank("naruto", pool))
    }

    @Test
    fun `when nothing matches the servers answer is trusted`() {
        // A search for an author, a description word or a scanlation group can legitimately
        // return titles whose *names* have nothing to do with the query - dropping those would
        // break the search, so the server's answer must survive untouched.
        val pool = listOf("Naruto", "One Piece", "Bleach")
        assertEquals(pool, rank("izumi tomoki", pool))
    }

    @Test
    fun `a word that lost its last letter is retried with it added`() {
        assertEquals("bad thinking", SmartSearch.candidateQueries("bad thinkin").first())
        assertEquals("thinking", SmartSearch.candidateQueries("thinkin").first())
    }

    @Test
    fun `a word that is simply not there is not a match`() {
        // "There Are No Bad Warriors in This World" shares only the word "bad" - that is not the
        // title the user typed, and it must not survive next to the real one.
        assertTrue(score("bad thinkin", "There Are No Bad Warriors in This World") < 0.34)
        assertTrue(score("bad thinkin", "The Dangers in My Heart") < 0.34)
        assertTrue(score("bad thinkin", "Bad Thinking Diary") >= 0.9)
    }

    @Test
    fun `the reported junk list keeps only the title that was typed`() {
        // The real first page for "bad thinkin", straight from the site.
        val pool = listOf(
            "There Are No Bad Warriors in This World",
            "The Dangers in My Heart",
            "24 Hours Resurrection of the Villainess",
            "Bad Thinking Diary",
        )
        assertEquals(listOf("Bad Thinking Diary"), rank("bad thinkin", pool))
    }

    @Test
    fun `a search that found nothing looks at 18+ titles when the filter is on`() {
        assertTrue(SmartSearch.needsAdultRescue(0.0, SmartSearch.ADULT_MODE_SAFE, true))
        assertTrue(SmartSearch.needsAdultRescue(0.2, SmartSearch.ADULT_MODE_SAFE, true))
        assertTrue(!SmartSearch.needsAdultRescue(0.9, SmartSearch.ADULT_MODE_SAFE, true))
        assertTrue(!SmartSearch.needsAdultRescue(0.0, SmartSearch.ADULT_MODE_ALL, true))
        assertTrue(!SmartSearch.needsAdultRescue(0.0, SmartSearch.ADULT_MODE_SAFE, false))
    }

    @Test
    fun `empty result sets are returned as is`() {
        assertEquals(emptyList<String>(), rank("naruto", emptyList()))
        val single = listOf("Naruto")
        assertEquals(single, rank("naruto", single))
    }

    // ------------------------------------------------------------------ distance

    @Test
    fun `edit distance counts a transposition as one edit`() {
        assertEquals(0, SmartSearch.editDistance("naruto", "naruto", 6))
        assertEquals(1, SmartSearch.editDistance("nartuo", "naruto", 6))
        assertEquals(1, SmartSearch.editDistance("peice", "piece", 6))
        assertEquals(2, SmartSearch.editDistance("", "ab", 6))
        // Distance work is abandoned as soon as it cannot stay under the cap.
        assertEquals(7, SmartSearch.editDistance("a", "abcdefghijklmnop", 6))
    }

    // ------------------------------------------------------- alternative titles

    @Test
    fun `an alternative title late in the list is still kept and matched`() {
        // The API lists one alternative title per translation a work is known by, and the one a
        // reader knows is often not among the first few. Keeping only the first dozen dropped it:
        // the work was in the server's answer, scored as "nothing like what was typed" and was
        // filtered off the page the user saw.
        val raw = (1..30).map { "Langsames Ding $it" } + listOf("The Girl Who Can See Them")
        val kept = altTitlesToKeep(raw)
        assertTrue("The Girl Who Can See Them" in kept)
        assertEquals(1.0, SmartSearch.score("the girl who can see them", kept), 0.001)
    }

    @Test
    fun `a work known only by an alternative title outranks the junk`() {
        val pool = listOf(
            "junk-a" to listOf("Love Diary (Hanna)"),
            "right" to listOf("The Girl Who Can See Them", "見える子ちゃん"),
            "junk-b" to listOf("I Can See the Titles"),
        )
        val ranked = SmartSearch.rank("the girl who can see them", pool) { it.second }
        assertEquals(listOf("right"), ranked.map { it.first })
    }

    @Test
    fun `kept alternative titles are trimmed deduplicated and bounded`() {
        val raw = listOf(" Mieruko-chan ", "Mieruko-chan", "", "   ") +
            (1..100).map { "Beam title $it" }
        val kept = altTitlesToKeep(raw)
        assertEquals("Mieruko-chan", kept.first())
        assertEquals(1, kept.count { it == "Mieruko-chan" })
        assertTrue(kept.none { it.isBlank() })
        assertTrue(kept.size <= ALT_TITLE_COUNT)
        assertTrue(kept.sumOf { it.length } <= ALT_TITLE_CHARS)
    }

    @Test
    fun `one huge alternative title is shortened instead of eating the budget`() {
        val kept = altTitlesToKeep(listOf("x".repeat(500), "Short one"))
        assertEquals(ALT_TITLE_LENGTH, kept.first().length)
        assertTrue("Short one" in kept)
    }
}

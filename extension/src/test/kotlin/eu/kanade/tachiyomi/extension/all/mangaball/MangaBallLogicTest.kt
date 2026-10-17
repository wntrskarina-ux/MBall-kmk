package eu.kanade.tachiyomi.extension.all.mangaball

import eu.kanade.tachiyomi.source.model.Filter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests cover the logic that is easy to get subtly wrong: the search query language,
 * the language matching (the site reports codes and names mixed together) and the local
 * result ordering. They run on every CI build.
 */
class MangaBallLogicTest {

    private val actionTag = "685146c5f3ed681c80f257e3"
    private val romanceTag = "685148cf15e8b86aae68e4db"
    private val ecchiTag = "6892a73ba943baf927094e37"
    private val martialArtsTag = "6851490615e8b86aae68e5c2"

    // ------------------------------------------------------------ query syntax

    @Test
    fun `plain words stay in the keyword`() {
        val options = SearchSyntax.parse("solo leveling")
        assertEquals("solo leveling", options.keyword)
        assertTrue(options.includedTags.isEmpty())
    }

    @Test
    fun `tag token resolves to a tag id`() {
        val options = SearchSyntax.parse("naruto tag:action")
        assertEquals("naruto", options.keyword)
        assertEquals(listOf(actionTag), options.includedTags)
    }

    @Test
    fun `negated tag token becomes an exclusion and is not part of the keyword`() {
        val options = SearchSyntax.parse("tag:romance -tag:ecchi")
        assertEquals("", options.keyword)
        assertEquals(listOf(romanceTag), options.includedTags)
        assertEquals(listOf(ecchiTag), options.excludedTags)
    }

    @Test
    fun `quoted tag names with spaces work`() {
        val options = SearchSyntax.parse("tag:\"martial arts\"")
        assertEquals(listOf(martialArtsTag), options.includedTags)
        assertEquals("", options.keyword)
    }

    @Test
    fun `genre and theme operators behave like tag`() {
        assertEquals(listOf(actionTag), SearchSyntax.parse("genre:action").includedTags)
        assertEquals(listOf(martialArtsTag), SearchSyntax.parse("theme:martial-arts").includedTags)
    }

    @Test
    fun `sort order status type and demographic tokens are parsed`() {
        val options = SearchSyntax.parse("sort:views order:asc status:ongoing type:manhwa demo:seinen")
        assertEquals("views", options.sortBy)
        assertEquals("asc", options.sortOrder)
        assertEquals("ongoing", options.status)
        assertEquals("manhwa", options.type)
        assertEquals("seinen", options.demographic)
    }

    @Test
    fun `adult mode and tag mode tokens are parsed`() {
        assertEquals("no_18", SearchSyntax.parse("adult:safe").adultMode)
        assertEquals("all", SearchSyntax.parse("adult:nsfw").adultMode)
        assertEquals("OR", SearchSyntax.parse("mode:or").tagMode)
        assertEquals("AND", SearchSyntax.parse("mode:and").tagMode)
    }

    @Test
    fun `language tokens are collected separately and stripped from the keyword`() {
        val options = SearchSyntax.parse("one piece lang:en,es")
        assertEquals(listOf("en", "es"), SearchSyntax.languageTokens("one piece lang:en,es"))
        assertEquals("one piece", options.keyword)
    }

    @Test
    fun `search depth is clamped to a sane range`() {
        assertEquals(3, SearchSyntax.parse("pages:3").deepPages)
        assertEquals(5, SearchSyntax.parse("pages:99").deepPages)
        assertEquals(1, SearchSyntax.parse("pages:0").deepPages)
    }

    @Test
    fun `unknown operators are kept as plain keywords`() {
        val options = SearchSyntax.parse("hello foo:bar")
        assertEquals("hello foo:bar", options.keyword)
        assertTrue(options.includedTags.isEmpty())
    }

    @Test
    fun `sort aliases map onto api values`() {
        assertEquals("views", SearchSyntax.parse("sort:popular").sortBy)
        assertEquals("lastupdate", SearchSyntax.parse("sort:latest").sortBy)
        assertEquals("created_at", SearchSyntax.parse("sort:newest").sortBy)
        assertEquals("rating", SearchSyntax.parse("sort:score").sortBy)
        assertNull(SearchSyntax.sortBy("nonsense"))
    }

    // --------------------------------------------------------------- languages

    @Test
    fun `region codes share candidates with their base code`() {
        assertTrue("es" in languageCandidates("es-419"))
        assertTrue("pt-br" in languageCandidates("pt-br"))
        assertTrue("es-419" in languageCandidates("es-419"))
    }

    @Test
    fun `language names are mapped onto codes`() {
        assertTrue("es" in languageCandidates("spanish"))
        assertTrue("en" in languageCandidates("english"))
        assertTrue("vi" in languageCandidates("vietnamese"))
        assertTrue("vi" in languageCandidates("vn"))
        assertTrue("ar" in languageCandidates("arabic"))
        assertTrue("pt" in languageCandidates("portuguese"))
        assertTrue("zh" in languageCandidates("cn"))
    }

    @Test
    fun `including a language matches every spelling of it`() {
        assertTrue(languageMatches(listOf("spanish"), listOf("es"), emptyList()))
        assertTrue(languageMatches(listOf("es-419"), listOf("es"), emptyList()))
        assertTrue(languageMatches(listOf("english"), listOf("en"), emptyList()))
        assertFalse(languageMatches(listOf("fr"), listOf("en"), emptyList()))
    }

    @Test
    fun `excluding a language also removes its regional variants`() {
        assertFalse(languageMatches(listOf("es-419"), emptyList(), listOf("es")))
        assertFalse(languageMatches(listOf("vietnamese"), emptyList(), listOf("vi")))
        assertTrue(languageMatches(listOf("en"), emptyList(), listOf("es")))
    }

    @Test
    fun `an empty include list matches everything`() {
        assertTrue(languageMatches(emptyList(), emptyList(), emptyList()))
        assertTrue(languageMatches(listOf("ja"), emptyList(), emptyList()))
    }

    @Test
    fun `chapter language sets cover regional variants`() {
        assertTrue("es-419" in siteLanguages("es"))
        assertTrue("pt" in siteLanguages("pt-BR"))
        assertTrue("jp" in siteLanguages("ja"))
        assertTrue("en" in siteLanguages("en"))
    }

    // ------------------------------------------------------------- tag catalog

    @Test
    fun `catalog resolves tag names ids and slugs`() {
        assertEquals(actionTag, MangaBallCatalog.resolve("action")?.id)
        assertEquals(actionTag, MangaBallCatalog.resolve("Action")?.id)
        assertEquals(actionTag, MangaBallCatalog.resolve(actionTag)?.id)
        assertEquals(romanceTag, MangaBallCatalog.resolve("romance")?.id)
        assertEquals(ecchiTag, MangaBallCatalog.resolve("ecchi")?.id)
    }

    @Test
    fun `catalog knows every tag group`() {
        assertTrue(MangaBallCatalog.ofGroup(TagGroup.GENRE).any { it.name == "Action" })
        assertTrue(MangaBallCatalog.ofGroup(TagGroup.THEME).any { it.name == "Martial Arts" })
        assertTrue(MangaBallCatalog.ofGroup(TagGroup.FORMAT).any { it.name == "Long Strip" })
        assertTrue(MangaBallCatalog.ofGroup(TagGroup.CONTENT).any { it.name == "Gore" })
    }

    // ------------------------------------------------------- filter extraction

    @Test
    fun `tri-state filters split tags into included and excluded`() {
        val genres = GenreFilter()
        val action = genres.state.first { it.id == actionTag }
        val romance = genres.state.first { it.id == romanceTag }
        action.state = Filter.TriState.STATE_INCLUDE
        romance.state = Filter.TriState.STATE_EXCLUDE

        assertEquals(listOf(actionTag), genres.included)
        assertEquals(listOf(romanceTag), genres.excluded)
    }

    @Test
    fun `language filter splits languages into included and excluded`() {
        val languages = LanguageFilter("Languages", MangaBallFilters.LANGUAGES)
        languages.state.first { it.code == "en" }.state = Filter.TriState.STATE_INCLUDE
        languages.state.first { it.code == "es" }.state = Filter.TriState.STATE_EXCLUDE

        assertEquals(listOf("en"), languages.included)
        assertEquals(listOf("es"), languages.excluded)
    }

    // ------------------------------------------------------------ result order

    private val rows = listOf(
        RankingRow("Beta", setOf(actionTag), views = 10, chapters = 5),
        RankingRow("alpha", setOf(romanceTag), views = 500, chapters = 1),
        RankingRow("Gamma", setOf(actionTag, romanceTag), views = 50, chapters = 90),
    )

    @Test
    fun `ordering by title is case insensitive and stable`() {
        assertEquals(listOf(1, 0, 2), rankingPermutation(rows, "title", "", "", emptyList()))
    }

    @Test
    fun `ordering by views puts the most viewed first`() {
        assertEquals(listOf(1, 2, 0), rankingPermutation(rows, "views", "", "", emptyList()))
    }

    @Test
    fun `ordering by chapters puts the longest first`() {
        assertEquals(listOf(2, 0, 1), rankingPermutation(rows, "chapters", "", "", emptyList()))
    }

    @Test
    fun `tag match ordering prefers entries carrying more included tags`() {
        val order = rankingPermutation(rows, "tag_match", "", "", listOf(actionTag, romanceTag))
        assertEquals(listOf(2, 0, 1), order)
    }

    @Test
    fun `focus genre floats that genre to the top`() {
        val order = rankingPermutation(rows, "focus_genre", romanceTag, "", listOf(actionTag, romanceTag))
        // Gamma has both focus genre and the included tag, alpha has the focus genre only.
        assertEquals(listOf(2, 1, 0), order)
    }

    @Test
    fun `focus tag does the same for themes and formats`() {
        val order = rankingPermutation(rows, "focus_tag", "", actionTag, emptyList())
        assertEquals(listOf(0, 2, 1), order)
    }

    @Test
    fun `server order is left untouched`() {
        assertEquals(listOf(0, 1, 2), rankingPermutation(rows, "server", "", "", emptyList()))
    }

    @Test
    fun `a focus tag that no entry has keeps the original order`() {
        val order = rankingPermutation(rows, "focus_tag", "", "", emptyList())
        assertEquals(listOf(0, 1, 2), order)
    }

    // ------------------------------------------------------- search ordering

    @Test
    fun `a text search is ranked by relevance not by the default date sort`() {
        // The Sort by filter defaults to "Latest updated". Reading it directly was the bug: a
        // search for a title came back as "whatever was updated in the last few minutes".
        assertNull(
            resolveSortBy(
                syntaxSort = null,
                keywordSearch = true,
                smartSearch = true,
                chosenSort = null,
                forcedSort = null,
                defaultSort = "lastupdate",
            ),
        )
    }

    @Test
    fun `a sort token written in the query box is still honoured`() {
        assertEquals(
            "views",
            resolveSortBy(
                syntaxSort = "views",
                keywordSearch = true,
                smartSearch = true,
                chosenSort = null,
                forcedSort = null,
                defaultSort = "lastupdate",
            ),
        )
    }

    @Test
    fun `with smart search off the sort filter is read again`() {
        assertEquals(
            "rating",
            resolveSortBy(
                syntaxSort = null,
                keywordSearch = true,
                smartSearch = false,
                chosenSort = "rating",
                forcedSort = null,
                defaultSort = "lastupdate",
            ),
        )
    }

    @Test
    fun `feeds keep their own ordering while the sort filter is untouched`() {
        assertEquals(
            "views",
            resolveSortBy(
                syntaxSort = null,
                keywordSearch = false,
                smartSearch = true,
                chosenSort = null,
                forcedSort = "views",
                defaultSort = "lastupdate",
            ),
        )
        // ...but a deliberately picked sort wins over it.
        assertEquals(
            "rating",
            resolveSortBy(
                syntaxSort = null,
                keywordSearch = false,
                smartSearch = true,
                chosenSort = "rating",
                forcedSort = "views",
                defaultSort = "lastupdate",
            ),
        )
    }

    @Test
    fun `the sort filter tells an untouched default apart from a real choice`() {
        val untouched = SortFilter()
        assertNull(untouched.chosenSortBy)

        untouched.state = Filter.Sort.Selection(0, false)
        assertNull("'Latest updated' descending is the default", untouched.chosenSortBy)

        untouched.state = Filter.Sort.Selection(1, false)
        assertEquals("views", untouched.chosenSortBy)

        untouched.state = Filter.Sort.Selection(0, true)
        assertEquals("lastupdate", untouched.chosenSortBy)
    }

    // ------------------------------------------------------- deeper look

    @Test
    fun `a search with nothing close looks a couple of pages deeper`() {
        assertTrue(shouldFetchMorePages(best = 0.2, pagesFetched = 1, autoDepth = 3, hasMore = true))
        assertTrue(shouldFetchMorePages(best = 0.79, pagesFetched = 1, autoDepth = 3, hasMore = true))
    }

    @Test
    fun `a search that already matched, or has no more pages, stops`() {
        assertFalse(shouldFetchMorePages(best = 0.9, pagesFetched = 1, autoDepth = 3, hasMore = true))
        assertFalse(shouldFetchMorePages(best = 0.2, pagesFetched = 3, autoDepth = 3, hasMore = true))
        assertFalse(shouldFetchMorePages(best = 0.2, pagesFetched = 1, autoDepth = 3, hasMore = false))
    }
}

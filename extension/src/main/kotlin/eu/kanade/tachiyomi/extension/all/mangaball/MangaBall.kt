package eu.kanade.tachiyomi.extension.all.mangaball

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal const val BASE_URL = "https://mangaball.com"
private const val API_URL = "$BASE_URL/api/v1"
private const val PAGE_SIZE = 40
private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Mobile Safari/537.36"

private val JSON_MEDIA_TYPE: MediaType = "application/json".toMediaType()

private const val NOT_JSON_HELP =
    "The server did not answer with JSON. If this repeats, turn on \"Use Cloudflare bypass client\" " +
        "in this source's settings."

// Preferences
private const val PREF_BYPASS = "pref_cloudflare_client"
private const val PREF_HIDE_NSFW = "pref_hide_nsfw"
private const val PREF_ONLY_SOURCE_LANGUAGE = "pref_only_source_language"
private const val PREF_ALL_CHAPTER_LANGUAGES = "pref_all_chapter_languages"
private const val PREF_DEPTH = "pref_search_depth"
private const val PREF_SCANLATOR_BLACKLIST = "pref_scanlator_blacklist"
private const val PREF_SMART_SEARCH = "pref_smart_search"
private const val PREF_ADULT_RESCUE = "pref_adult_rescue"

internal class MangaBallSource(
    override val lang: String,
    private val languageLabel: String,
) : HttpSource(), ConfigurableSource {

    override val name: String = "MangaBall ($languageLabel)"
    override val baseUrl: String = BASE_URL
    override val supportsLatest: Boolean = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    /**
     * The site is behind Cloudflare. Normal requests usually go through, but a user can
     * opt into the reader's challenge-solving client when they hit blocks.
     */
    override val client: OkHttpClient
        get() = if (preferences.getBoolean(PREF_BYPASS, false)) network.cloudflareClient else network.client

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", USER_AGENT)
        .add("Accept", "application/json, text/plain, */*")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Referer", "$baseUrl/")

    // ------------------------------------------------------------------ browse

    override suspend fun getPopularManga(page: Int): MangasPage =
        search(page, query = "", filters = FilterList(), forcedSort = SearchSyntax.POPULAR_SORT)

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        search(page, query = "", filters = FilterList(), forcedSort = SearchSyntax.DEFAULT_SORT)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        search(page, query, filters, forcedSort = null)

    private suspend fun search(
        page: Int,
        query: String,
        filters: FilterList,
        forcedSort: String?,
    ): MangasPage {
        val syntax = SearchSyntax.parse(query)
        val extraKeywords = filters.ofType<ExtraKeywordFilter>()?.state?.trim().orEmpty()
        val keyword = listOf(syntax.keyword, extraKeywords)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        val sortFilter = filters.ofType<SortFilter>()
        val keywordSearch = keyword.isNotBlank()
        val smartSearch = preferences.getBoolean(PREF_SMART_SEARCH, true)

        // Text searches are sent in the site's relevance order. The "Sort by" filter defaults to
        // "Latest updated" and cannot be told apart from a deliberate choice by the framework, so
        // reading it directly turned every search into "whatever was updated in the last few
        // minutes that loosely matches" - a search for "bad thinking diary" came back with
        // "Strategy Guide for a Merry Bad Ending" while the title itself was nowhere in the page.
        // The filter still orders the Popular / Latest feeds; `sort:` in the query box is always
        // honoured, and found results can be reordered with "Result ordering (local)".
        val sortBy: String? = resolveSortBy(
            syntaxSort = syntax.sortBy,
            keywordSearch = keywordSearch,
            smartSearch = smartSearch,
            chosenSort = sortFilter?.chosenSortBy,
            forcedSort = forcedSort,
            defaultSort = SearchSyntax.DEFAULT_SORT,
        )
        val sortOrder: String? =
            if (sortBy == null) null else (syntax.sortOrder ?: sortFilter?.sortOrder ?: "desc")

        val tagGroups = filters.filterIsInstance<TagStateGroup>()
        val includedTags = (tagGroups.flatMap { it.included } + syntax.includedTags).distinct()
        val excludedTags = (tagGroups.flatMap { it.excluded } + syntax.excludedTags)
            .filterNot { it in includedTags }
            .distinct()

        val tagMode = syntax.tagMode ?: filters.ofType<TagModeFilter>()?.selected ?: "AND"
        val type = syntax.type ?: filters.ofType<TypeFilter>()?.selected.orEmpty()
        val status = syntax.status ?: filters.ofType<StatusFilter>()?.selected.orEmpty()
        val demographic = syntax.demographic ?: filters.ofType<DemographicFilter>()?.selected.orEmpty()
        val adultMode = syntax.adultMode
            ?: filters.ofType<AdultFilter>()?.selected
            ?: if (preferences.getBoolean(PREF_HIDE_NSFW, true)) {
                SmartSearch.ADULT_MODE_SAFE
            } else {
                SmartSearch.ADULT_MODE_ALL
            }

        // Languages are matched against each result locally: the API parameter for this is
        // not documented and a wrong value would silently return nothing.
        val languageFilter = filters.ofType<LanguageFilter>()
        val includeLanguages = languageFilter?.included.orEmpty().ifEmpty { SearchSyntax.languageTokens(query) }
        val excludeLanguages = languageFilter?.excluded.orEmpty()

        val resultOrder = filters.ofType<ResultOrderFilter>()?.selected ?: "server"
        val focusGenre = filters.ofType<FocusGenreFilter>()?.selected.orEmpty()
        val focusTag = filters.ofType<FocusTagFilter>()?.selected.orEmpty()

        val depth = (syntax.deepPages
            ?: preferences.getString(PREF_DEPTH, "1")?.toIntOrNull()
            ?: 1).coerceIn(1, 5)

        val collected = LinkedHashMap<String, SManga>()
        var hasNextPage = false
        var currentPage = page
        var fetched = 0
        while (fetched < depth) {
            val url = buildSearchUrl(
                page = currentPage,
                keyword = keyword,
                sortBy = sortBy,
                sortOrder = sortOrder,
                tagMode = tagMode,
                includedTags = includedTags,
                excludedTags = excludedTags,
                type = type,
                status = status,
                demographic = demographic,
                adultMode = adultMode,
            )
            val (items, more) = fetchSearchPage(url)
            items.forEach { collected.putIfAbsent(it.url, it) }
            hasNextPage = more
            fetched++
            if (!more) break
            currentPage++
        }

        // A text search that found nothing close to what was typed is usually a title the site
        // ranked below its loose word matches - an alternative title is the classic case, and
        // such a query can have tens of thousands of barely related answers. Score the next
        // pages as well, so the work that is known by exactly that title is still found. Bounded,
        // only on the first page, and skipped when a specific depth was asked for.
        if (keywordSearch && smartSearch && page == 1 && syntax.deepPages == null) {
            while (shouldFetchMorePages(bestScore(keyword, collected), fetched, AUTO_DEPTH, hasNextPage)) {
                val url = buildSearchUrl(
                    page = currentPage,
                    keyword = keyword,
                    sortBy = sortBy,
                    sortOrder = sortOrder,
                    tagMode = tagMode,
                    includedTags = includedTags,
                    excludedTags = excludedTags,
                    type = type,
                    status = status,
                    demographic = demographic,
                    adultMode = adultMode,
                )
                val (items, more) = try {
                    fetchSearchPage(url)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: IOException) {
                    // The extra pages exist to find more, never to fail the page already found.
                    break
                }
                items.forEach { collected.putIfAbsent(it.url, it) }
                hasNextPage = more
                fetched++
                currentPage++
            }
        }

        var results: List<SManga> = collected.values.toList()

        if (keywordSearch && smartSearch) {
            results = refineForQuery(
                query = keyword,
                items = results,
                adultMode = adultMode,
                adultRescue = preferences.getBoolean(PREF_ADULT_RESCUE, true),
                // Probes are always relevance-ranked: they exist to *find* the title, and a
                // date (or any other) sort can leave it outside the first page entirely.
                urlFor = { variant, mode ->
                    buildSearchUrl(
                        page = 1,
                        keyword = variant,
                        sortBy = null,
                        sortOrder = null,
                        tagMode = tagMode,
                        includedTags = includedTags,
                        excludedTags = excludedTags,
                        type = type,
                        status = status,
                        demographic = demographic,
                        adultMode = mode,
                    )
                },
            )
        }

        val onlySourceLanguage = preferences.getBoolean(PREF_ONLY_SOURCE_LANGUAGE, false)
        if (includeLanguages.isNotEmpty() || excludeLanguages.isNotEmpty() || onlySourceLanguage) {
            results = results.filter { manga ->
                // An empty language list means the site said nothing, so fall back to this
                // source's own language instead of dropping the entry.
                val reported = manga.memoLanguages().ifEmpty { listOf(lang) }
                languageMatches(reported, includeLanguages, excludeLanguages) &&
                    (!onlySourceLanguage || languageMatches(reported, listOf(lang), emptyList()))
            }
        }
        results = applyOrdering(results, resultOrder, focusGenre, focusTag, includedTags)

        return MangasPage(results, hasNextPage)
    }

    private fun buildSearchUrl(
        page: Int,
        keyword: String,
        sortBy: String?,
        sortOrder: String?,
        tagMode: String,
        includedTags: List<String>,
        excludedTags: List<String>,
        type: String,
        status: String,
        demographic: String,
        adultMode: String,
    ): HttpUrl = "$API_URL/title/search-advanced".toHttpUrl().newBuilder()
        .addQueryParameter("page", page.toString())
        .addQueryParameter("limit", PAGE_SIZE.toString())
        .addQueryParameter("tag_mode", tagMode)
        .addQueryParameter("adult_mode", adultMode)
        .apply {
            // No sort_by at all means "rank by relevance", which is what a search wants.
            if (sortBy != null) addQueryParameter("sort_by", sortBy)
            if (sortOrder != null) addQueryParameter("sort_order", sortOrder)
            if (keyword.isNotBlank()) addQueryParameter("keyword", keyword)
            if (includedTags.isNotEmpty()) addQueryParameter("included_tags", includedTags.joinToString(","))
            if (excludedTags.isNotEmpty()) addQueryParameter("excluded_tags", excludedTags.joinToString(","))
            if (type.isNotBlank()) addQueryParameter("type", type)
            if (status.isNotBlank()) addQueryParameter("status", status)
            if (demographic.isNotBlank()) addQueryParameter("publicationDemographic", demographic)
        }
        .build()

    /**
     * Removes results that have nothing to do with the query and, when the server's first answer
     * contained nothing close to it, retries with typo-tolerant variants ("mieruko", "mieruk",
     * "nartuo" -> "naruto") - bounded to [SmartSearch.MAX_PROBES] extra requests so a search
     * stays fast. The scoring and the variant list live in [SmartSearch].
     */
    private suspend fun refineForQuery(
        query: String,
        items: List<SManga>,
        adultMode: String,
        adultRescue: Boolean,
        urlFor: (String, String) -> HttpUrl,
    ): List<SManga> {
        val pool = LinkedHashMap<String, SManga>()
        items.forEach { pool[it.url] = it }

        var best = probeVariants(query, pool, bestScore(query, pool), adultMode, urlFor)

        // Nothing in the answer looks like what was typed. If the 18+ filter is on, the title may
        // have been removed by the server before it was ever seen - send the same query once more
        // with 18+ titles allowed, so that a title known by name can never be invisible (a search
        // for "bad thinking diary" returns nothing at all while that filter is on).
        if (SmartSearch.needsAdultRescue(best, adultMode, adultRescue)) {
            fetchOrEmpty(urlFor(query, SmartSearch.ADULT_MODE_ALL))
                .forEach { pool.putIfAbsent(it.url, it) }
            best = bestScore(query, pool)
            if (best < SmartSearch.STRONG) {
                probeVariants(query, pool, best, SmartSearch.ADULT_MODE_ALL, urlFor)
            }
        }

        return rankMangas(query, pool.values.toList())
    }

    private fun bestScore(query: String, pool: Map<String, SManga>): Double =
        pool.values.maxOfOrNull { scoreManga(query, it) } ?: 0.0

    /** Asks the server for the typo variants of the query, adding whatever it returns to [pool]. */
    private suspend fun probeVariants(
        query: String,
        pool: LinkedHashMap<String, SManga>,
        bestSoFar: Double,
        adultMode: String,
        urlFor: (String, String) -> HttpUrl,
    ): Double {
        var best = bestSoFar
        if (best >= SmartSearch.KEEP) return best

        val variants = SmartSearch.candidateQueries(query)
        for (index in 0 until minOf(variants.size, SmartSearch.MAX_PROBES)) {
            fetchOrEmpty(urlFor(variants[index], adultMode)).forEach { pool.putIfAbsent(it.url, it) }
            best = bestScore(query, pool)
            if (best >= SmartSearch.STRONG) break
        }
        return best
    }

    /** A failed probe (Cloudflare, timeout, one odd page) must never fail the whole search. */
    private suspend fun fetchOrEmpty(url: HttpUrl): List<SManga> = try {
        fetchSearchPage(url).first
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Performs one request and parses the JSON answer, retrying a transient failure (a Cloudflare
     * 52x, a timeout, a challenge page that is HTML instead of JSON) a couple of times before the
     * error reaches the reader. A 522 means the site's origin did not answer - waiting a second
     * and asking again is exactly the right reaction.
     */
    private suspend fun jsonOf(request: Request): JsonObject {
        var last: IOException? = null
        for (attempt in 1..RETRY_ATTEMPTS) {
            try {
                client.newCall(request).await().use { response ->
                    if (!response.isSuccessful) throw HttpStatusException(response.code)
                    val body = response.body?.string().orEmpty()
                    return body.parseJsonObject() ?: throw IOException(NOT_JSON_HELP)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: IOException) {
                if (!error.isWorthRetrying()) throw error
                last = error
                if (attempt < RETRY_ATTEMPTS) delay(retryDelayMillis(attempt))
            }
        }
        throw last ?: IOException("Request failed")
    }

    private suspend fun fetchSearchPage(url: HttpUrl): Pair<List<SManga>, Boolean> {
        val root = jsonOf(GET(url, headers))
        val data = root.arr("data")
            ?: throw IOException(root.string("message") ?: "Malformed API response")
        val items = data.mapNotNull { element -> element.asObject()?.toSManga() }
        val currentPage = root.obj("pagination")?.int("page")?.toInt() ?: 1
        val totalPages = root.obj("pagination")?.int("total_pages")?.toInt() ?: currentPage
        return items to (currentPage < totalPages)
    }

    // ------------------------------------------------------------- local logic

    private fun applyOrdering(
        items: List<SManga>,
        order: String,
        focusGenre: String,
        focusTag: String,
        includedTags: List<String>,
    ): List<SManga> {
        if (order == "server" || items.size < 2) return items
        val rows = items.map { manga ->
            RankingRow(
                title = manga.title,
                tagIds = manga.memoTagIds().toSet(),
                views = manga.memoViews(),
                chapters = manga.memoChapterCount(),
            )
        }
        return rankingPermutation(rows, order, focusGenre, focusTag, includedTags).map { items[it] }
    }

    // --------------------------------------------------------------- details

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val details = async { if (fetchDetails) loadDetails(manga) else manga }
        val chapterList = async { if (fetchChapters) loadChapters(manga) else chapters }
        SMangaUpdate(details.await(), chapterList.await())
    }

    private suspend fun loadDetails(manga: SManga): SManga {
        val identifier = manga.titleId() ?: manga.url
        val root = jsonOf(GET("$API_URL/title/detail/$identifier", headers))
        val data = root.obj("data")
            ?: root.arr("data")?.firstOrNull()?.asObject()
            ?: throw IOException("Malformed response")
        return data.toMangaDetails().apply { url = manga.url }
    }

    private suspend fun loadChapters(manga: SManga): List<SChapter> {
        val titleId = manga.titleId() ?: loadTitleId(manga)
            ?: throw IOException("Could not resolve the title id")

        val payload = buildJsonObject { put("title_id", titleId) }
            .toString()
            .toByteArray()
            .toRequestBody(JSON_MEDIA_TYPE)

        val root = jsonOf(POST("$API_URL/chapter/chapter-listing-by-title-id", headers, payload))
        val data = root.arr("data") ?: return emptyList()
        val allowedLanguages =
            if (preferences.getBoolean(PREF_ALL_CHAPTER_LANGUAGES, false)) emptySet() else siteLanguages(lang)
        val blacklist = scanlatorBlacklist()
        return data.mapNotNull { element -> element.asObject()?.toSChapter(allowedLanguages) }
            .filterNot { chapter ->
                blacklist.any { blocked -> chapter.scanlator?.equals(blocked, true) == true }
            }
    }

    private suspend fun loadTitleId(manga: SManga): String? {
        val root = try {
            jsonOf(GET("$API_URL/title/detail/${manga.url}", headers))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            return null
        }
        val data = root.obj("data") ?: root.arr("data")?.firstOrNull()?.asObject() ?: return null
        return data.string("id")
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val root = jsonOf(GET("$API_URL/chapter-detail?chapter_id=${chapter.url}", headers))
        val pages = root.obj("data")?.obj("chapter")?.strings("pages").orEmpty()
        if (pages.isEmpty()) throw IOException("This chapter has no readable pages")
        return pages.mapIndexed { index, url -> Page(index, url, url) }
    }

    override fun getMangaUrl(manga: SManga): String =
        "$baseUrl/title-detail/${manga.titleId() ?: manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/chapter-detail/${chapter.url}"

    // --------------------------------------------------------------- filters

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        ResultOrderFilter(),
        FocusGenreFilter(),
        FocusTagFilter(),
        Filter.Separator(),
        Filter.Header(
            "Search box extras: tag:romance -tag:ecchi sort:views order:asc status:ongoing " +
                "type:manhwa demo:shounen lang:en,es adult:safe mode:or pages:3",
        ),
        Filter.Separator(),
        TypeFilter(),
        DemographicFilter(),
        StatusFilter(),
        TagModeFilter(),
        AdultFilter(),
        ExtraKeywordFilter(),
        Filter.Separator(),
        Filter.Header("Languages - tap once to require, tap twice to exclude (matched locally)"),
        LanguageFilter("Languages", MangaBallFilters.LANGUAGES),
        Filter.Separator(),
        Filter.Header("Tags - tap once to include, tap twice to exclude"),
        GenreFilter(),
        ThemeFilter(),
        FormatFilter(),
        ContentFilter(),
        Filter.Header("Missing tag? Type tag:its name in the search box."),
    )

    // ----------------------------------------------------------- preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_HIDE_NSFW
            title = "Hide 18+ titles"
            summary = "Ask the server to leave out titles marked as 18+. A search that would come " +
                "back empty still looks at them (see the switch below). Recommended."
            setDefaultValue(true)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SMART_SEARCH
            title = "Smart search (typo tolerant)"
            summary = "Drops results that do not match what you typed, ignores accents, hyphens " +
                "and missing spaces, and retries close spellings when nothing matches " +
                "(\"mieruko-ct\" finds Mieruko-chan, \"bad thinkin\" finds Bad Thinking Diary). " +
                "A work is also found by any of its alternative titles, in any language. Text " +
                "searches are ranked by relevance instead of the date order the Sort by filter " +
                "would give them, and a search that finds nothing close looks a couple of pages " +
                "deeper. Reorder the results with \"Result ordering (local)\". Turn off to see " +
                "the site's raw answer."
            setDefaultValue(true)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ADULT_RESCUE
            title = "Find 18+ titles when nothing else matches"
            summary = "\"Hide 18+ titles\" stays in charge, except when a search finds nothing " +
                "that looks like what you typed - then 18+ titles are searched too, so a title " +
                "you know by name is never missing. Turn off for a strict 18+ filter."
            setDefaultValue(true)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ONLY_SOURCE_LANGUAGE
            title = "Only titles available in this source's language"
            summary = "Filters results locally by the languages a title has chapters in."
            setDefaultValue(false)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ALL_CHAPTER_LANGUAGES
            title = "Show chapters in every language"
            summary = "Off: only chapters matching this source's language are listed."
            setDefaultValue(false)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_BYPASS
            title = "Use Cloudflare bypass client"
            summary = "Turn on when searches fail with HTTP 403 / a Cloudflare challenge."
            setDefaultValue(false)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_DEPTH
            title = "Search depth (1-5 pages)"
            summary = "How many result pages a single search merges. Higher is slower but finds " +
                "more. Smart search adds up to two pages of its own when nothing matches."
            setDefaultValue("1")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_SCANLATOR_BLACKLIST
            title = "Scanlator blacklist"
            summary = "Comma separated group names whose chapters are hidden."
            setDefaultValue("")
        }.also(screen::addPreference)
    }

    private fun scanlatorBlacklist(): List<String> =
        preferences.getString(PREF_SCANLATOR_BLACKLIST, null).orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}

private inline fun <reified T : Filter<*>> FilterList.ofType(): T? =
    firstOrNull { it is T } as? T

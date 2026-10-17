package eu.kanade.tachiyomi.extension.all.mangaball

/**
 * Result of parsing the free text typed into the search box.
 *
 * The search box understands a small query language on top of plain keywords, for example:
 *
 * ```
 * naruto tag:action -tag:ecchi sort:views order:desc status:completed pages:3
 * onepiece genre:adventure theme:"martial arts" lang:en,es
 * ```
 */
internal data class SearchOptions(
    val keyword: String = "",
    val includedTags: List<String> = emptyList(),
    val excludedTags: List<String> = emptyList(),
    val tagMode: String? = null,
    val sortBy: String? = null,
    val sortOrder: String? = null,
    val status: String? = null,
    val type: String? = null,
    val demographic: String? = null,
    val adultMode: String? = null,
    val deepPages: Int? = null,
)

/**
 * Which `sort_by` a request should carry, or null for the site's own relevance order.
 *
 * The subtlety that made text searches useless: the "Sort by" filter's default is
 * *Latest updated*, and a filter that was never touched is indistinguishable from one the user
 * deliberately set - [SortFilter.chosenSortBy] exists to tell those apart (it returns null while
 * the filter sits at its default). A text search must live in the site's relevance order: its
 * fuzzy answer contains a hundred loosely related titles, and putting those in date order is
 * exactly what buries the title that was typed.
 */
internal fun resolveSortBy(
    syntaxSort: String?,
    keywordSearch: Boolean,
    smartSearch: Boolean,
    chosenSort: String?,
    forcedSort: String?,
    defaultSort: String,
): String? = when {
    // "sort:views" written in the query box is an explicit instruction - always honoured.
    syntaxSort != null -> syntaxSort
    // A text search is relevance-ranked; "Result ordering (local)" reorders what was found.
    keywordSearch && smartSearch -> null
    keywordSearch -> chosenSort
    // Popular / Latest feeds keep their own ordering unless a sort was deliberately picked.
    else -> chosenSort ?: forcedSort ?: defaultSort
}

/**
 * How many result pages a text search may merge when the first page held nothing close to it.
 *
 * The site's search is a loose word match - a query can legitimately return tens of thousands of
 * barely related entries - so the work that is known by exactly that title (an alternative title
 * is the classic case) can sit below the first page. Two more pages are pooled and scored as a
 * whole. Bounded: only for the first page, only under Smart search, and only while the result set
 * holds no strong match.
 */
internal const val AUTO_DEPTH = 3

/** True when a text search should look at the next result page before giving up. */
internal fun shouldFetchMorePages(
    best: Double,
    pagesFetched: Int,
    autoDepth: Int,
    hasMore: Boolean,
): Boolean = hasMore && pagesFetched < autoDepth && best < SmartSearch.STRONG

internal object SearchSyntax {

    private val TOKEN = Regex("""(?:^|\s)-?[A-Za-z_]+:(?:"[^"]*"|\S+)""")

    private val SORT_ALIASES = mapOf(
        "views" to "views",
        "popular" to "views",
        "popularity" to "views",
        "mostviewed" to "views",
        "lastupdate" to "lastupdate",
        "lastupdated" to "lastupdate",
        "updated" to "lastupdate",
        "latest" to "lastupdate",
        "recent" to "lastupdate",
        "rating" to "rating",
        "rated" to "rating",
        "score" to "rating",
        "createdat" to "created_at",
        "created" to "created_at",
        "new" to "created_at",
        "newest" to "created_at",
        "name" to "name",
        "title" to "name",
        "az" to "name",
    )

    private const val SORT_BY_VIEWS = "views"
    private const val SORT_BY_LAST_UPDATE = "lastupdate"

    fun parse(query: String): SearchOptions {
        var options = SearchOptions()
        var keyword = query
        val included = mutableListOf<String>()
        val excluded = mutableListOf<String>()

        TOKEN.findAll(query).forEach { match ->
            val token = match.value.trim()
            val isNegative = token.startsWith("-")
            val body = if (isNegative) token.substring(1) else token
            val separator = body.indexOf(':')
            if (separator <= 0) return@forEach

            val key = body.substring(0, separator).lowercase()
            val rawValue = body.substring(separator + 1).trim().removeSurrounding("\"").trim()
            if (rawValue.isEmpty()) return@forEach

            var handled = true
            when (key) {
                "tag", "tags", "genre", "genres", "theme", "themes", "format", "content" -> {
                    val tag = MangaBallCatalog.resolve(rawValue)
                    if (tag == null) {
                        handled = false
                    } else if (isNegative) {
                        excluded += tag.id
                    } else {
                        included += tag.id
                    }
                }
                "sort", "sortby", "orderby" -> options = options.copy(sortBy = sortBy(rawValue))
                "order", "sortorder" -> options = options.copy(sortOrder = direction(rawValue))
                "status" -> options = options.copy(status = STATUSES[rawValue.lowercase()])
                "type" -> options = options.copy(type = TYPES[rawValue.lowercase()])
                "demo", "demographic", "magazine" -> options = options.copy(demographic = DEMOGRAPHICS[rawValue.lowercase()])
                "adult", "nsfw" -> options = options.copy(adultMode = adult(rawValue))
                "mode", "tagmode" -> options = options.copy(tagMode = tagMode(rawValue))
                // Languages are matched locally against each result, see MangaBallSource.
                "lang", "language", "languages" -> Unit
                "pages", "deep" -> options = options.copy(deepPages = rawValue.toIntOrNull()?.coerceIn(1, 5))
                else -> handled = false
            }

            if (!handled) {
                // Unknown operator: keep it as part of the keyword search instead of dropping it.
                return@forEach
            }
            keyword = keyword.replace(match.value, " ")
        }

        // Language tokens are handled by the caller (they need the filter state), so they are
        // simply stripped from the keyword here to avoid sending them to the API.
        keyword = LANGUAGE_TOKEN.replace(keyword, " ")

        return options.copy(
            keyword = keyword.replace(Regex("\\s+"), " ").trim(),
            includedTags = included.distinct(),
            excludedTags = excluded.distinct(),
        )
    }

    private val LANGUAGE_TOKEN = Regex("""(?:^|\s)[Ll]ang(?:uage|uages)?:(?:"[^"]*"|\S+)""")

    /** Language codes requested through the search box, e.g. `lang:en,es`. */
    fun languageTokens(query: String): List<String> =
        LANGUAGE_TOKEN.findAll(query)
            .mapNotNull { it.value.substringAfter(':', "").removeSurrounding("\"").trim().takeIf { v -> v.isNotEmpty() } }
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toList()

    fun sortBy(raw: String): String? = SORT_ALIASES[raw.lowercase().replace("_", "").replace("-", "")]

    fun direction(raw: String): String? = when (raw.lowercase()) {
        "asc", "ascending", "up" -> "asc"
        "desc", "descending", "down" -> "desc"
        else -> null
    }

    fun adult(raw: String): String? = when (raw.lowercase()) {
        "all", "nsfw", "18+", "adult", "true", "yes" -> "all"
        "safe", "sfw", "no18", "no_18", "false", "no", "hide" -> "no_18"
        else -> null
    }

    fun tagMode(raw: String): String? = when (raw.lowercase()) {
        "and", "all", "every" -> "AND"
        "or", "any", "some" -> "OR"
        else -> null
    }

    val DEFAULT_SORT: String = SORT_BY_LAST_UPDATE
    val POPULAR_SORT: String = SORT_BY_VIEWS

    val STATUSES = mapOf(
        "ongoing" to "ongoing",
        "completed" to "completed",
        "complete" to "completed",
        "finished" to "completed",
        "hiatus" to "hiatus",
        "onhold" to "hiatus",
        "cancelled" to "cancelled",
        "canceled" to "cancelled",
        "dropped" to "cancelled",
    )

    val TYPES = mapOf(
        "manga" to "manga",
        "manhwa" to "manhwa",
        "manhua" to "manhua",
        "comic" to "comics",
        "comics" to "comics",
    )

    val DEMOGRAPHICS = mapOf(
        "shounen" to "shounen",
        "shonen" to "shounen",
        "shoujo" to "shoujo",
        "shojo" to "shoujo",
        "seinen" to "seinen",
        "josei" to "josei",
    )
}

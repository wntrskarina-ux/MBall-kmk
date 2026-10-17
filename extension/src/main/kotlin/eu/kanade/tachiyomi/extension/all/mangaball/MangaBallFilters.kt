package eu.kanade.tachiyomi.extension.all.mangaball

import eu.kanade.tachiyomi.source.model.Filter

/** Base class for a dropdown whose entries map to an API value. */
internal abstract class SelectFilter(
    name: String,
    private val options: List<Pair<String, String>>,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val selected: String get() = options.getOrNull(state)?.second.orEmpty()
}

/** A single tri-state (include / exclude / ignore) entry, keyed by the API tag id. */
internal class TagStateFilter(name: String, val id: String) : Filter.TriState(name)

/** A tri-state group of tags (used for genres, themes, formats and content flags). */
internal abstract class TagStateGroup(
    name: String,
    tags: List<MangaBallTag>,
) : Filter.Group<TagStateFilter>(name, tags.map { TagStateFilter(it.name, it.id) }) {
    val included: List<String> get() = state.filter { it.isIncluded() }.map { it.id }
    val excluded: List<String> get() = state.filter { it.isExcluded() }.map { it.id }
}

internal class GenreFilter : TagStateGroup("Genres", MangaBallCatalog.ofGroup(TagGroup.GENRE))

internal class ThemeFilter : TagStateGroup("Themes", MangaBallCatalog.ofGroup(TagGroup.THEME))

internal class FormatFilter : TagStateGroup("Formats", MangaBallCatalog.ofGroup(TagGroup.FORMAT))

internal class ContentFilter : TagStateGroup("Content warnings", MangaBallCatalog.ofGroup(TagGroup.CONTENT))

/** Sort options supported by the site's search endpoint. */
internal class SortFilter : Filter.Sort(
    "Sort by",
    arrayOf("Latest updated", "Most viewed", "Top rated", "Recently added", "Title"),
    Selection(0, false),
) {
    val sortBy: String get() = SORT_VALUES.getOrNull(state?.index ?: 0) ?: SORT_VALUES[0]
    val sortOrder: String get() = if (state?.ascending == true) "asc" else "desc"

    /**
     * The sort the user actually picked, or null while the filter still sits at its default
     * ("Latest updated", descending). Filters carry no "was this changed" flag, so the default
     * selection has to be recognised by value - otherwise an untouched filter silently forces
     * every search into date order.
     */
    val chosenSortBy: String?
        get() {
            val selection = state ?: return null
            if ((selection.index ?: 0) == 0 && selection.ascending != true) return null
            return sortBy
        }

    companion object {
        private val SORT_VALUES = arrayOf("lastupdate", "views", "rating", "created_at", "name")
    }
}

/** How the already fetched results are ordered locally (no extra request). */
internal class ResultOrderFilter : SelectFilter(
    "Result ordering (local)",
    listOf(
        "Server order" to "server",
        "Best tag match first" to "tag_match",
        "Focus genre first" to "focus_genre",
        "Focus tag first" to "focus_tag",
        "Title (A-Z)" to "title",
        "Most viewed first" to "views",
        "Most chapters first" to "chapters",
    ),
)

internal class FocusGenreFilter : SelectFilter(
    "Focus genre (for local ordering)",
    listOf("Off" to "") + MangaBallCatalog.ofGroup(TagGroup.GENRE).map { it.name to it.id },
)

internal class FocusTagFilter : SelectFilter(
    "Focus tag (for local ordering)",
    listOf("Off" to "") + MangaBallCatalog.ofGroup(TagGroup.THEME)
        .plus(MangaBallCatalog.ofGroup(TagGroup.FORMAT))
        .sortedBy { it.name.lowercase() }
        .map { it.name to it.id },
)

internal class TypeFilter : SelectFilter(
    "Type",
    listOf(
        "All" to "",
        "Manga" to "manga",
        "Manhwa" to "manhwa",
        "Manhua" to "manhua",
        "Comics" to "comics",
    ),
)

internal class DemographicFilter : SelectFilter(
    "Magazine demographic",
    listOf(
        "All" to "",
        "Shounen" to "shounen",
        "Shoujo" to "shoujo",
        "Seinen" to "seinen",
        "Josei" to "josei",
    ),
)

internal class StatusFilter : SelectFilter(
    "Publication status",
    listOf(
        "All" to "",
        "Ongoing" to "ongoing",
        "Completed" to "completed",
        "Hiatus" to "hiatus",
        "Cancelled" to "cancelled",
    ),
)

internal class TagModeFilter : SelectFilter(
    "Tag mode (include logic)",
    listOf(
        "AND - must have every included tag" to "AND",
        "OR - must have at least one included tag" to "OR",
    ),
)

internal class AdultFilter : SelectFilter(
    "18+ content from the server",
    listOf(
        "Include 18+ titles" to "all",
        "Exclude 18+ titles" to "no_18",
    ),
)

internal class LanguageStateFilter(name: String, val code: String) : Filter.TriState(name)

internal class LanguageFilter(
    name: String,
    options: List<Pair<String, String>>,
) : Filter.Group<LanguageStateFilter>(name, options.map { LanguageStateFilter(it.first, it.second) }) {
    val included: List<String> get() = state.filter { it.isIncluded() }.map { it.code }
    val excluded: List<String> get() = state.filter { it.isExcluded() }.map { it.code }
}

internal class ExtraKeywordFilter : Filter.Text("Extra keywords")

internal object MangaBallFilters {

    /** Languages offered by the local language filter (site codes). */
    val LANGUAGES: List<Pair<String, String>> = listOf(
        "English" to "en",
        "Español" to "es",
        "Español (LatAm)" to "es-419",
        "Português (BR)" to "pt-br",
        "Français" to "fr",
        "Deutsch" to "de",
        "Italiano" to "it",
        "Русский" to "ru",
        "Українська" to "uk",
        "Polski" to "pl",
        "Türkçe" to "tr",
        "العربية" to "ar",
        "Indonesia" to "id",
        "Tiếng Việt" to "vi",
        "ไทย" to "th",
        "हिन्दी" to "hi",
        "日本語" to "ja",
        "한국어" to "ko",
        "中文" to "zh",
        "Nederlands" to "nl",
        "Čeština" to "cs",
        "Română" to "ro",
        "Magyar" to "hu",
        "فارسی" to "fa",
        "Ελληνικά" to "el",
    ).distinctBy { it.second }
}

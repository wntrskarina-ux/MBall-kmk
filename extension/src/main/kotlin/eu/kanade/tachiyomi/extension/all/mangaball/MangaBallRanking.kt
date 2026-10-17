package eu.kanade.tachiyomi.extension.all.mangaball

/**
 * Pure helpers used by [MangaBallSource].
 *
 * They are kept free of Android and network types so they can be unit tested: the search
 * syntax, the language matching and the local result ordering all live here.
 */

// ---------------------------------------------------------------------------
// Languages
// ---------------------------------------------------------------------------

/**
 * The site is inconsistent about language codes: the same title can report `es`, `es-419`
 * or even the written-out name `spanish` / `arabic` / `vietnamese`. This table maps those
 * spellings onto the base code so a filter matches all of them.
 */
private val LANGUAGE_ALIASES: Map<String, String> = buildMap {
    fun put(list: List<String>, code: String) = list.forEach { put(it, code) }

    put(listOf("english", "eng"), "en")
    put(listOf("spanish", "espanol", "español", "spa", "castellano"), "es")
    put(listOf("portuguese", "portugues", "português"), "pt")
    put(listOf("french", "francais", "français", "fre"), "fr")
    put(listOf("german", "deutsch", "ger"), "de")
    put(listOf("italian", "italiano"), "it")
    put(listOf("dutch", "nederlands"), "nl")
    put(listOf("russian", "russkiy"), "ru")
    put(listOf("ukrainian", "ukrainsk"), "uk")
    put(listOf("polish", "polski"), "pl")
    put(listOf("czech", "cestina", "čeština"), "cs")
    put(listOf("slovak", "slovencina", "slovenčina"), "sk")
    put(listOf("romanian", "romana", "română"), "ro")
    put(listOf("hungarian", "magyar"), "hu")
    put(listOf("bulgarian", "balgarski"), "bg")
    put(listOf("serbian", "srpski"), "sr")
    put(listOf("croatian", "hrvatski"), "hr")
    put(listOf("turkish", "turkce", "türkçe"), "tr")
    put(listOf("arabic", "arabi"), "ar")
    put(listOf("hebrew", "ivrit"), "he")
    put(listOf("persian", "farsi"), "fa")
    put(listOf("greek", "ellinika"), "el")
    put(listOf("indonesian", "indonesia", "bahasaindonesia"), "id")
    put(listOf("malay", "melayu"), "ms")
    put(listOf("vietnamese", "viet", "tiengviet", "tiếngviệt"), "vi")
    put(listOf("thai", "thaii"), "th")
    put(listOf("hindi", "hindī"), "hi")
    put(listOf("bengali", "bangla"), "bn")
    put(listOf("tamil"), "ta")
    put(listOf("malayalam"), "ml")
    put(listOf("nepali"), "ne")
    put(listOf("japanese", "nihongo"), "ja")
    put(listOf("korean", "hangul"), "ko")
    put(listOf("chinese", "mandarin", "zhongwen"), "zh")
    put(listOf("danish"), "da")
    put(listOf("finnish", "suomi"), "fi")
    put(listOf("norwegian", "norsk"), "no")
    put(listOf("swedish", "svenska"), "sv")
    put(listOf("icelandic", "islenska", "íslenska"), "is")
    put(listOf("albanian", "shqip"), "sq")
    put(listOf("catalan", "catala", "català"), "ca")
    put(listOf("georgian"), "ka")
    put(listOf("lithuanian"), "lt")
    put(listOf("mongolian"), "mn")
    put(listOf("burmese", "myanmar"), "my")
    put(listOf("latin", "latina"), "la")
    put(listOf("tagalog", "filipino"), "tl")

    // Short forms the site mixes in with the ISO codes
    put(listOf("jp"), "ja")
    put(listOf("kr"), "ko")
    put(listOf("cn"), "zh")
    put(listOf("vn", "vie"), "vi")
    put(listOf("spa"), "es")
    put(listOf("por"), "pt")
}

/**
 * Every spelling that should be treated as the same language as [code].
 * `es-419` yields `es-419`, `es` and the alias `es`; `spanish` yields `spanish`, `spanish`
 * (its own base) and `es`.
 */
internal fun languageCandidates(code: String): Set<String> {
    val lower = code.trim().lowercase()
    if (lower.isEmpty()) return emptySet()
    val base = lower.substringBefore('-')
    val normalized = base.filter { it.isLetter() }
    val alias = LANGUAGE_ALIASES[normalized] ?: base
    return setOf(lower, base, alias)
}

/** Chapter languages that belong to a source language, e.g. `es` also covers `es-419`. */
internal fun siteLanguages(code: String): Set<String> = when (code.lowercase()) {
    "es" -> setOf("es", "es-419", "es-la", "es-es", "es-mx", "es-ar", "spanish", "espanol", "español")
    "pt-br" -> setOf("pt-br", "pt", "pt-pt", "portuguese", "portugues", "português")
    "zh" -> setOf("zh", "zh-cn", "zh-hk", "zh-tw", "cn", "chinese", "mandarin")
    "ja" -> setOf("ja", "jp", "japanese")
    "ko" -> setOf("ko", "kr", "korean")
    "vi" -> setOf("vi", "vn", "vie", "vietnamese")
    "id" -> setOf("id", "indonesian", "indonesia")
    "ar" -> setOf("ar", "arabic")
    else -> languageCandidates(code)
}

/**
 * True when the languages a title reports satisfy the include/exclude lists.
 * An empty [include] means "any language".
 */
internal fun languageMatches(
    reported: Collection<String>,
    include: Collection<String>,
    exclude: Collection<String>,
): Boolean {
    val effective = reported.flatMapTo(mutableSetOf()) { languageCandidates(it) }
    val includeSet = include.flatMapTo(mutableSetOf()) { languageCandidates(it) }
    val excludeSet = exclude.flatMapTo(mutableSetOf()) { languageCandidates(it) }
    val matchesInclude = includeSet.isEmpty() || effective.any { it in includeSet }
    val matchesExclude = excludeSet.none { it in effective }
    return matchesInclude && matchesExclude
}

// ---------------------------------------------------------------------------
// Local result ordering
// ---------------------------------------------------------------------------

/** The bits of a list entry the local ordering needs. */
internal data class RankingRow(
    val title: String,
    val tagIds: Set<String>,
    val views: Long,
    val chapters: Long,
)

/**
 * Returns the order in which [rows] should be shown as a list of indices.
 *
 * `tag_match`, `focus_genre` and `focus_tag` are stable: entries with the same score keep the
 * order the server returned them in.
 */
internal fun rankingPermutation(
    rows: List<RankingRow>,
    order: String,
    focusGenre: String,
    focusTag: String,
    includedTags: List<String>,
): List<Int> {
    val indices = rows.indices.toList()
    fun tagHits(row: RankingRow) = includedTags.count { it in row.tagIds }
    return when (order) {
        "title" -> indices.sortedWith(compareBy({ rows[it].title.lowercase() }, { it }))
        "views" -> indices.sortedWith(compareByDescending<Int> { rows[it].views }.thenBy { it })
        "chapters" -> indices.sortedWith(compareByDescending<Int> { rows[it].chapters }.thenBy { it })
        "tag_match" -> indices.sortedWith(
            compareByDescending<Int> { tagHits(rows[it]) }.thenBy { it },
        )
        "focus_genre" -> indices.sortedWith(
            compareByDescending<Int> {
                (if (focusGenre.isNotEmpty() && focusGenre in rows[it].tagIds) 1_000 else 0) + tagHits(rows[it])
            }.thenBy { it },
        )
        "focus_tag" -> indices.sortedWith(
            compareByDescending<Int> {
                (if (focusTag.isNotEmpty() && focusTag in rows[it].tagIds) 1_000 else 0) + tagHits(rows[it])
            }.thenBy { it },
        )
        else -> indices
    }
}
